package j143.github.celloffer.queue;

import j143.github.celloffer.model.CellQueueStats;
import j143.github.celloffer.model.Offer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
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

    /** cell-id → sorted offer set (head = best, tail = worst) */
    private final ConcurrentHashMap<String, TreeSet<Offer>> queues =
            new ConcurrentHashMap<>();

    /** cell-id → stats counters */
    private final ConcurrentHashMap<String, CellQueueStats> statsMap =
            new ConcurrentHashMap<>();

    public CellOfferQueueManager(
            @Value("${cell.offer.max-queue-size:100}") int maxQueueSize,
            MeterRegistry meterRegistry) {
        this.maxQueueSize = maxQueueSize;
        this.meterRegistry = meterRegistry;
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
     */
    public void enqueue(Offer offer) {
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
                // last() is O(log n) – the lowest-priority (worst) offer in the set
                Offer worst = queue.last();
                if (OFFER_ORDER.compare(offer, worst) < 0) {
                    // Incoming offer is better – evict the worst (O(log n))
                    queue.remove(worst);
                    queue.add(offer);
                    stats.incrementEnqueued();
                    meterRegistry.counter("offers_enqueued_total", "cellId", cellId).increment();
                    log.debug("Evicted low-priority offer {} to make room for {}",
                            worst.getOfferId(), offer.getOfferId());
                } else {
                    // Incoming offer is not better – drop it
                    stats.incrementDroppedOnEnqueue();
                    meterRegistry.counter("offers_dropped_total", "cellId", cellId).increment();
                    log.debug("Dropped offer {} for cell {} (queue full, priority too low)",
                            offer.getOfferId(), cellId);
                }
            }
        }
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
        return queue == null ? 0 : queue.size();
    }

    /**
     * Returns all cell-to-stats mappings (read-only view).
     */
    public Map<String, CellQueueStats> getAllStats() {
        return Map.copyOf(statsMap);
    }
}

