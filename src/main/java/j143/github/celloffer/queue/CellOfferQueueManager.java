package j143.github.celloffer.queue;

import j143.github.celloffer.model.CellQueueStats;
import j143.github.celloffer.model.Offer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CellOfferQueueManager {

    private static final Logger log = LoggerFactory.getLogger(CellOfferQueueManager.class);

    static final Comparator<Offer> OFFER_ORDER = Comparator
            .comparingInt(Offer::getPriority).reversed()
            .thenComparing(Offer::getCreatedAt)
            .thenComparing(Offer::getOfferId);

    private final int maxQueueSize;
    private final MeterRegistry meterRegistry;
    private final Timer enqueueTimer;

    private final ConcurrentHashMap<String, TreeSet<Offer>> queues =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, CellQueueStats> statsMap =
            new ConcurrentHashMap<>();

    public CellOfferQueueManager(
            @Value("${cell.offer.max-queue-size:100}") int maxQueueSize,
            MeterRegistry meterRegistry) {
        this.maxQueueSize = maxQueueSize;
        this.meterRegistry = meterRegistry;
        this.enqueueTimer = Timer.builder("cell_offer_enqueue_duration")
                .description("Time spent admitting an offer into a cell queue")
                .register(meterRegistry);
    }

    public void enqueue(Offer offer) {
        enqueueTimer.record(() -> enqueueInternal(offer));
    }

    private void enqueueInternal(Offer offer) {
        String cellId = offer.getCellId();
        TreeSet<Offer> queue = queues.computeIfAbsent(
                cellId, k -> new TreeSet<>(OFFER_ORDER));
        CellQueueStats stats = statsMap.computeIfAbsent(cellId, k -> {
            CellQueueStats s = new CellQueueStats();
            meterRegistry.gauge(
                    "offer_queue_depth",
                    List.of(Tag.of("cellId", k)),
                    queue,
                    TreeSet::size);
            return s;
        });

        synchronized (queue) {
            if (queue.size() < maxQueueSize) {
                queue.add(offer);
                stats.incrementEnqueued();
                meterRegistry.counter("offers_enqueued_total", "cellId", cellId).increment();
                log.debug("Enqueued offer {} for cell {}", offer.getOfferId(), cellId);
            } else {
                Offer worst = queue.last();
                if (OFFER_ORDER.compare(offer, worst) < 0) {
                    queue.remove(worst);
                    queue.add(offer);
                    stats.incrementEnqueued();
                    meterRegistry.counter("offers_enqueued_total", "cellId", cellId).increment();
                    log.debug("Evicted low-priority offer {} to make room for {}",
                            worst.getOfferId(), offer.getOfferId());
                } else {
                    stats.incrementDroppedOnEnqueue();
                    meterRegistry.counter("offers_dropped_total", "cellId", cellId).increment();
                    log.debug("Dropped offer {} for cell {} (queue full, priority too low)",
                            offer.getOfferId(), cellId);
                }
            }
        }
    }

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
        }

        for (Offer offer : drained) {
            if (offer.isExpired(now)) {
                stats.incrementExpired();
                meterRegistry.counter("offers_expired_total", "cellId", cellId).increment();
                log.debug("Discarding expired offer {} for cell {}", offer.getOfferId(), cellId);
            } else {
                flushed.add(offer);
                stats.incrementFlushed();
            }
        }
        return flushed;
    }

    public Set<String> getCellIds() {
        return queues.keySet();
    }

    public CellQueueStats getStats(String cellId) {
        return statsMap.getOrDefault(cellId, new CellQueueStats());
    }

    public int getQueueDepth(String cellId) {
        TreeSet<Offer> queue = queues.get(cellId);
        return queue == null ? 0 : queue.size();
    }

    public Map<String, CellQueueStats> getAllStats() {
        return Map.copyOf(statsMap);
    }
}
