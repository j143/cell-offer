package com.uber.celloffer.model;

import java.util.concurrent.atomic.LongAdder;

/**
 * Accumulates runtime counters for a single H3 cell queue.
 *
 * <p>All mutating operations are thread-safe via {@link LongAdder}.
 */
public class CellQueueStats {

    /** Total number of offers successfully added to the queue. */
    private final LongAdder enqueued = new LongAdder();

    /** Total number of offers removed during a flush and forwarded downstream. */
    private final LongAdder flushed = new LongAdder();

    /** Total number of offers removed during a flush because their TTL had elapsed. */
    private final LongAdder expired = new LongAdder();

    /**
     * Total number of offers rejected at enqueue time because the queue was at
     * capacity and the incoming offer had lower-or-equal priority than the worst
     * offer already in the queue.
     */
    private final LongAdder droppedOnEnqueue = new LongAdder();

    public void incrementEnqueued() {
        enqueued.increment();
    }

    public void incrementFlushed() {
        flushed.increment();
    }

    public void incrementExpired() {
        expired.increment();
    }

    public void incrementDroppedOnEnqueue() {
        droppedOnEnqueue.increment();
    }

    public long getEnqueued() {
        return enqueued.sum();
    }

    public long getFlushed() {
        return flushed.sum();
    }

    public long getExpired() {
        return expired.sum();
    }

    public long getDroppedOnEnqueue() {
        return droppedOnEnqueue.sum();
    }

    @Override
    public String toString() {
        return "CellQueueStats{" +
                "enqueued=" + getEnqueued() +
                ", flushed=" + getFlushed() +
                ", expired=" + getExpired() +
                ", droppedOnEnqueue=" + getDroppedOnEnqueue() +
                '}';
    }
}
