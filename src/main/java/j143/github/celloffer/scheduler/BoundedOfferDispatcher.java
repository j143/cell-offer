package j143.github.celloffer.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import j143.github.celloffer.model.Offer;
import j143.github.celloffer.push.OfferPushClient;
import j143.github.celloffer.push.SendOutcome;
import j143.github.celloffer.queue.CellOfferQueueManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongUnaryOperator;

/**
 * Owns a bounded number of offers between cell selection and terminal outcomes.
 * The scheduler never calls the network. Capacity includes queued, running and
 * retry-wait offers. State transitions are serialized; RPCs run outside the lock.
 * This is an in-memory handoff, not a durable outbox or exactly-once transport.
 */
@Component
public class BoundedOfferDispatcher {
    private enum State { WAITING, QUEUED, RUNNING, TERMINAL }

    private static final class Work {
        private final Offer offer;
        private State state = State.WAITING;
        private int attempts;
        private Instant readyAt = Instant.MIN;

        private Work(Offer offer) {
            this.offer = offer;
        }
    }

    private final Object lock = new Object();
    private final CellOfferQueueManager queues;
    private final OfferPushClient client;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final Clock clock;
    private final int capacity;
    private final int maxAttempts;
    private final long retryBaseMs;
    private final long retryMaxMs;
    private final long shutdownMs;
    private final boolean retryAmbiguous;
    private final LongUnaryOperator jitter;
    private final Map<Work, Boolean> owned = new LinkedHashMap<>();
    private final TreeMap<Instant, Integer> creationTimes = new TreeMap<>();
    private final Map<SendOutcome, Counter> outcomes = new EnumMap<>(SendOutcome.class);
    private final Counter selected;
    private final Counter retries;
    private final Counter exhausted;
    private final Counter abandoned;
    private final Counter submissionRejected;
    private boolean closing;
    private String lastCell;

    /** Creates the production dispatcher with bounded executor and retry budgets. */
    @Autowired
    public BoundedOfferDispatcher(
            CellOfferQueueManager queues, OfferPushClient client, ObjectMapper mapper,
            MeterRegistry registry,
            @Value("${cell.dispatch.workers:4}") int workers,
            @Value("${cell.dispatch.capacity:256}") int capacity,
            @Value("${cell.dispatch.max-attempts:3}") int maxAttempts,
            @Value("${cell.dispatch.retry-base-ms:100}") long retryBaseMs,
            @Value("${cell.dispatch.retry-max-ms:2000}") long retryMaxMs,
            @Value("${cell.dispatch.retry-ambiguous:false}") boolean retryAmbiguous,
            @Value("${cell.dispatch.shutdown-ms:5000}") long shutdownMs) {
        this(queues, client, mapper, registry, Clock.systemUTC(),
                pool(workers, capacity), capacity, maxAttempts, retryBaseMs,
                retryMaxMs, retryAmbiguous, shutdownMs,
                bound -> ThreadLocalRandom.current().nextLong(bound + 1));
    }

    BoundedOfferDispatcher(
            CellOfferQueueManager queues, OfferPushClient client, ObjectMapper mapper,
            MeterRegistry registry, Clock clock, ExecutorService executor,
            int capacity, int maxAttempts, long retryBaseMs, long retryMaxMs,
            boolean retryAmbiguous, long shutdownMs, LongUnaryOperator jitter) {
        if (capacity < 1 || maxAttempts < 1 || retryBaseMs < 1
                || retryMaxMs < retryBaseMs || retryMaxMs > 86_400_000L
                || shutdownMs < 1) {
            throw new IllegalArgumentException("Invalid dispatch budget");
        }
        this.queues = queues;
        this.client = client;
        this.mapper = mapper;
        this.executor = executor;
        this.clock = clock;
        this.capacity = capacity;
        this.maxAttempts = maxAttempts;
        this.retryBaseMs = retryBaseMs;
        this.retryMaxMs = retryMaxMs;
        this.retryAmbiguous = retryAmbiguous;
        this.shutdownMs = shutdownMs;
        this.jitter = jitter;
        selected = registry.counter("cell_dispatch_selected_total");
        retries = registry.counter("cell_dispatch_retries_total");
        exhausted = registry.counter("cell_dispatch_retry_exhausted_total");
        abandoned = registry.counter("cell_dispatch_abandoned_total");
        submissionRejected = registry.counter("cell_dispatch_submission_rejected_total");
        for (SendOutcome outcome : SendOutcome.values()) {
            outcomes.put(outcome, registry.counter("cell_dispatch_terminal_total",
                    "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT)));
        }
        registry.gauge("cell_dispatch_pending", this, d -> d.count(false));
        registry.gauge("cell_dispatch_in_flight", this, d -> d.count(true));
        registry.gauge("cell_dispatch_oldest_age_seconds", this, d -> d.oldestAge());
    }

    private static ExecutorService pool(int workers, int capacity) {
        if (workers < 1 || capacity < workers) {
            throw new IllegalArgumentException("capacity must be >= workers > 0");
        }
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Selects from active cells in cyclic order without historical-cell scans.
     * Called by the periodic fallback and worker completions; neither waits on RPCs.
     * Ownership is reserved under the lock before polling each cell.
     */
    public void tick() {
        synchronized (lock) {
            if (closing) {
                return;
            }
            Instant now = clock.instant();
            for (Work work : new ArrayList<>(owned.keySet())) {
                if (work.state == State.WAITING && !work.readyAt.isAfter(now)) {
                    submit(work);
                }
            }
            long visits = (long) queues.getActiveCellCount() + capacity;
            for (long i = 0; i < visits && owned.size() < capacity; i++) {
                String cell = queues.nextActiveCell(lastCell);
                if (cell == null) {
                    break;
                }
                lastCell = cell;
                Offer offer = queues.pollForDispatch(cell, clock.instant());
                if (offer == null) {
                    continue;
                }
                Work work = new Work(offer);
                owned.put(work, Boolean.TRUE);
                creationTimes.merge(offer.getCreatedAt(), 1, Integer::sum);
                selected.increment();
                submit(work);
            }
        }
    }

    private void submit(Work work) {
        if (remainingMs(work.offer) <= 0) {
            finish(work, SendOutcome.EXPIRED);
            return;
        }
        work.state = State.QUEUED;
        try {
            executor.execute(() -> run(work));
        } catch (RejectedExecutionException e) {
            // Ownership and capacity stay with this Work, never normal enqueue.
            work.state = State.WAITING;
            submissionRejected.increment();
        }
    }

    private void run(Work work) {
        synchronized (lock) {
            if (work.state != State.QUEUED) {
                return;
            }
            work.state = State.RUNNING;
            work.attempts++;
        }
        SendOutcome result;
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("offerId", work.offer.getOfferId());
            payload.put("cellId", work.offer.getCellId());
            payload.put("driverId", work.offer.getDriverId());
            payload.put("riderId", work.offer.getRiderId());
            payload.put("priority", work.offer.getPriority());
            String json = mapper.writeValueAsString(payload);
            long remaining = remainingMs(work.offer);
            result = remaining <= 0 ? SendOutcome.EXPIRED : client.sendOffer(
                    work.offer.getDriverId(), json, remaining, work.offer.getPriority());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            result = SendOutcome.INVALID;
        } catch (RuntimeException e) {
            // A transport wrapper may throw after issuing a request.
            result = SendOutcome.AMBIGUOUS_FAILURE;
        }
        if (result == null) {
            result = SendOutcome.AMBIGUOUS_FAILURE;
        }
        synchronized (lock) {
            if (work.state == State.TERMINAL) {
                return; // shutdown already accounted this work
            }
            boolean retry = result == SendOutcome.RETRYABLE_FAILURE
                    || (retryAmbiguous && result == SendOutcome.AMBIGUOUS_FAILURE);
            if (!retry) {
                finish(work, result);
            } else if (remainingMs(work.offer) <= 0) {
                finish(work, SendOutcome.EXPIRED);
            } else if (closing) {
                abandon(work);
            } else if (work.attempts >= maxAttempts) {
                exhausted.increment();
                finish(work, result);
            } else {
                long delay = retryBaseMs;
                for (int i = 1; i < work.attempts && delay < retryMaxMs; i++) {
                    delay = Math.min(retryMaxMs, delay * 2);
                }
                work.readyAt = clock.instant().plusMillis(jitter.applyAsLong(delay));
                work.state = State.WAITING;
                retries.increment();
            }
        }
        // Capacity release replenishes work immediately; no extra executor/thread.
        // The closing check in tick prevents shutdown from starting new work.
        tick();
    }

    private long remainingMs(Offer offer) {
        return Duration.between(clock.instant(), offer.getCreatedAt().plus(offer.getTtl())).toMillis();
    }

    private void remove(Work work) {
        work.state = State.TERMINAL;
        owned.remove(work);
        creationTimes.computeIfPresent(work.offer.getCreatedAt(),
                (key, count) -> count == 1 ? null : count - 1);
    }

    private void finish(Work work, SendOutcome result) {
        remove(work);
        outcomes.get(result).increment();
    }

    private void abandon(Work work) {
        remove(work);
        abandoned.increment();
    }

    private int count(boolean running) {
        synchronized (lock) {
            return (int) owned.keySet().stream()
                    .filter(w -> (w.state == State.RUNNING) == running).count();
        }
    }

    private double oldestAge() {
        synchronized (lock) {
            return creationTimes.isEmpty() ? 0 : Math.max(0,
                    Duration.between(creationTimes.firstKey(), clock.instant()).toMillis() / 1000.0);
        }
    }

    /** Stops selection, drains for a bounded budget, and accounts unfinished work. */
    @PreDestroy
    public void close() {
        synchronized (lock) {
            if (closing) {
                return;
            }
            closing = true;
            for (Work work : new ArrayList<>(owned.keySet())) {
                if (work.state == State.WAITING) {
                    abandon(work);
                }
            }
            executor.shutdown();
        }
        try {
            executor.awaitTermination(shutdownMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
            synchronized (lock) {
                for (Work work : new ArrayList<>(owned.keySet())) {
                    abandon(work);
                }
            }
        }
    }
}
