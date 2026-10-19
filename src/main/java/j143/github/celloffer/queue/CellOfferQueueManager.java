package j143.github.celloffer.queue;

import j143.github.celloffer.model.CellQueueStats;
import j143.github.celloffer.model.Offer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages per-cell bounded priority queues for driver offers.
 *
 * <p>Each H3 cell gets its own sorted set keyed by {@code cellId}.
 * Queues are created lazily on first enqueue. When a queue reaches
 * {@code maxQueueSize}, the lowest-priority offer is evicted to make room – or the
 * incoming offer is rejected if it has lower-or-equal priority than the worst
 * incumbent.
 *
 * <p>A {@link TreeSet} is used per cell so that both the best (head) and worst (tail)
 * offer are accessible in O(log n), avoiding the linear scan that a
 * {@link java.util.concurrent.PriorityBlockingQueue} would require to find the minimum.
 */
@Component
public class CellOfferQueueManager {

    private static final Logger log = LoggerFactory.getLogger(CellOfferQueueManager.class);

    /**
     * Comparator for the per-cell {@link TreeSet}: highest priority first, then
     * earliest creation time, then offer-id as a tiebreaker to prevent two distinct
     * offers with identical priority and timestamp from being treated as equal.
     */
    static final Comparator<Offer> OFFER_ORDER = Comparator
            .comparingInt(Offer::getPriority).reversed()
            .thenComparing(Offer::getCreatedAt)
            .thenComparing(Offer::getOfferId);

    private final int maxQueueSize;
    private final MeterRegistry meterRegistry;
    private final Timer enqueueTimer;
    private final Counter admitted;
    private final Counter rejected;
    private final Counter evicted;
    private final Counter duplicate;
    private final Counter transferred;
    private final Counter expired;
    private final ConcurrentSkipListSet<String> activeCells = new ConcurrentSkipListSet<>();
    private final AtomicInteger activeCount = new AtomicInteger();
    private final AtomicInteger queuedCount = new AtomicInteger();
    private final Object ageLock = new Object();
    private final TreeMap<Instant, Integer> queuedCreationTimes = new TreeMap<>();

    /** cell-id → sorted offer set (head = best, tail = worst) */
    private final ConcurrentHashMap<String, TreeSet<Offer>> queues =
            new ConcurrentHashMap<>();

    /** cell-id → stats counters */
    private final ConcurrentHashMap<String, CellQueueStats> statsMap =
            new ConcurrentHashMap<>();

    public CellOfferQueueManager(
            @Value("${cell.offer.max-queue-size:100}") int maxQueueSize,
            MeterRegistry meterRegistry) {
        if (maxQueueSize < 1) {
            throw new IllegalArgumentException("maxQueueSize must be positive");
        }
        this.maxQueueSize = maxQueueSize;
        this.meterRegistry = meterRegistry;
        this.admitted = meterRegistry.counter("cell_offer_admitted_total");
        this.rejected = meterRegistry.counter("cell_offer_rejected_total");
        this.evicted = meterRegistry.counter("cell_offer_evicted_total");
        this.duplicate = meterRegistry.counter("cell_offer_duplicate_total");
        this.transferred = meterRegistry.counter("cell_offer_transferred_total");
        this.expired = meterRegistry.counter("cell_offer_queue_expired_total");
        this.enqueueTimer = Timer.builder("cell_offer_enqueue_duration")
                .description("Time spent admitting an offer into a cell queue")
                .register(meterRegistry);
        meterRegistry.gauge("cell_offer_active_cells", activeCount);
        meterRegistry.gauge("cell_offer_queued", queuedCount);
        meterRegistry.gauge("cell_offer_oldest_queued_age_seconds", this,
                value -> value.oldestQueuedAgeSeconds());
    }

    /**
     * Enqueues an offer for its cell.
     *
     * <ul>
     *   <li>Creates the cell queue if it does not yet exist.</li>
     *   <li>If the queue is below capacity, the offer is added directly.</li>
     *   <li>If the queue is at capacity:
     *     <ul>
     *       <li>If the incoming offer has higher priority than the lowest incumbent,
     *           the incumbent is evicted (O(log n)) and the new offer takes its place.</li>
     *       <li>Otherwise the incoming offer is counted as {@code droppedOnEnqueue}.</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param offer the offer to enqueue (must not be null)
     * @return true if admitted; false for a duplicate or capacity rejection
     */
    public boolean enqueue(Offer offer) {
        return enqueueTimer.record(() -> enqueueInternal(offer));
    }

    private boolean enqueueInternal(Offer offer) {
        String cellId = offer.getCellId();
        TreeSet<Offer> queue = queues.computeIfAbsent(cellId, k -> new TreeSet<>(OFFER_ORDER));
        CellQueueStats stats = statsMap.computeIfAbsent(cellId, k -> {
            CellQueueStats s = new CellQueueStats();
            meterRegistry.gauge("offer_queue_depth", List.of(Tag.of("cellId", k)), queue,
                    value -> {
                        synchronized (value) {
                            return value.size();
                        }
                    });
            return s;
        });
        synchronized (queue) {
            if (queue.contains(offer)) {
                duplicate.increment();
                return false;
            }
            if (queue.size() >= maxQueueSize) {
                // last() is O(log n) – the lowest-priority (worst) offer in the set
                Offer worst = queue.last();
                if (OFFER_ORDER.compare(offer, worst) >= 0) {
                    // Incoming offer is not better – drop it
                    stats.incrementDroppedOnEnqueue();
                    rejected.increment();
                    meterRegistry.counter("offers_dropped_total", "cellId", cellId).increment();
                    log.debug("Dropped offer {} for cell {} (queue full, priority too low)",
                            offer.getOfferId(), cellId);
                    return false;
                }
                // Incoming offer is better – evict the worst (O(log n))
                queue.remove(worst);
                removeAge(worst);
                evicted.increment();
                log.debug("Evicted low-priority offer {} to make room for {}",
                        worst.getOfferId(), offer.getOfferId());
            }
            queue.add(offer);
            addAge(offer);
            activate(cellId);
            stats.incrementEnqueued();
            admitted.increment();
            meterRegistry.counter("offers_enqueued_total", "cellId", cellId).increment();
            log.debug("Enqueued offer {} for cell {}", offer.getOfferId(), cellId);
            return true;
        }
    }

    private void activate(String cellId) {
        if (activeCells.add(cellId)) {
            activeCount.incrementAndGet();
        }
    }

    private void deactivate(String cellId) {
        if (activeCells.remove(cellId)) {
            activeCount.decrementAndGet();
        }
    }

    private void addAge(Offer offer) {
        synchronized (ageLock) {
            queuedCreationTimes.merge(offer.getCreatedAt(), 1, Integer::sum);
            queuedCount.incrementAndGet();
        }
    }

    private void removeAge(Offer offer) {
        synchronized (ageLock) {
            queuedCreationTimes.computeIfPresent(offer.getCreatedAt(),
                    (key, count) -> count == 1 ? null : count - 1);
            queuedCount.decrementAndGet();
        }
    }

    private double oldestQueuedAgeSeconds() {
        synchronized (ageLock) {
            return queuedCreationTimes.isEmpty() ? 0 : Math.max(0,
                    Duration.between(queuedCreationTimes.firstKey(), Instant.now()).toMillis() / 1000.0);
        }
    }

    /**
     * Returns the next nonempty cell in sorted cyclic order without copying or
     * sorting historical cells. Membership changes under each cell's queue lock.
     *
     * @param after previous selected cell, or null to start
     * @return an active cell or null; concurrent drains can make the cell empty
     */
    public String nextActiveCell(String after) {
        String next = after == null ? null : activeCells.higher(after);
        return next != null ? next : activeCells.ceiling("");
    }

    /** Returns an approximate concurrent count; selection uses it only as a budget. */
    public int getActiveCellCount() {
        return Math.max(0, activeCount.get());
    }

    /**
     * Flushes offers that are ready to be sent for the given cell.
     *
     * <p>All non-expired offers currently in the queue are returned. Expired offers
     * are silently discarded and counted.
     *
     * @param cellId the cell whose queue should be flushed
     * @param now    reference instant for TTL evaluation
     * @return list of live offers, ordered by priority desc then creation time
     */
    public List<Offer> flushDueOffers(String cellId, Instant now) {
        TreeSet<Offer> queue = queues.get(cellId);
        if (queue == null) {
            return List.of();
        }
        CellQueueStats stats = statsMap.computeIfAbsent(cellId, k -> new CellQueueStats());
        List<Offer> flushed = new ArrayList<>();
        List<Offer> drained = new ArrayList<>();
        synchronized (queue) {
            drained.addAll(queue);
            queue.clear();
            deactivate(cellId);
            drained.forEach(this::removeAge);
        }
        for (Offer offer : drained) {
            if (offer.isExpired(now)) {
                stats.incrementExpired();
                expired.increment();
                meterRegistry.counter("offers_expired_total", "cellId", cellId).increment();
                log.debug("Discarding expired offer {} for cell {}", offer.getOfferId(), cellId);
            } else {
                flushed.add(offer);
                stats.incrementFlushed();
                transferred.increment();
            }
        }
        return flushed;
    }

    /**
     * Transfers at most one live offer after the dispatcher has reserved capacity.
     * Expired heads are removed under the same lock. This method performs no I/O.
     *
     * @param cellId cell from which to select the highest-priority live offer
     * @param now reference time for expiration
     * @return selected offer, or null when no live offer remains
     */
    public Offer pollForDispatch(String cellId, Instant now) {
        TreeSet<Offer> queue = queues.get(cellId);
        if (queue == null) {
            return null;
        }
        synchronized (queue) {
            while (!queue.isEmpty()) {
                Offer offer = queue.pollFirst();
                removeAge(offer);
                if (queue.isEmpty()) {
                    deactivate(cellId);
                }
                if (!now.isBefore(offer.getCreatedAt().plus(offer.getTtl()))) {
                    getStats(cellId).incrementExpired();
                    expired.increment();
                    meterRegistry.counter("offers_expired_total", "cellId", cellId).increment();
                } else {
                    getStats(cellId).incrementFlushed();
                    transferred.increment();
                    return offer;
                }
            }
            deactivate(cellId);
            return null;
        }
    }

    /** Returns all cell IDs that currently have a queue registered. */
    public Set<String> getCellIds() {
        return queues.keySet();
    }

    /**
     * Returns a snapshot of the stats for the given cell, or an empty stats object
     * if the cell is not yet known.
     */
    public CellQueueStats getStats(String cellId) {
        return statsMap.getOrDefault(cellId, new CellQueueStats());
    }

    /** Returns the current depth of the queue for the given cell (0 if unknown). */
    public int getQueueDepth(String cellId) {
        TreeSet<Offer> queue = queues.get(cellId);
        if (queue == null) {
            return 0;
        }
        synchronized (queue) {
            return queue.size();
        }
    }

    /**
     * Returns all cell-to-stats mappings (read-only view).
     */
    public Map<String, CellQueueStats> getAllStats() {
        return Map.copyOf(statsMap);
    }
}
