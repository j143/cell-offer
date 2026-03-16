package com.uber.celloffer.model;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;

/**
 * Represents a driver offer for a specific H3 cell.
 * Offers carry a TTL and a priority; the dispatcher processes higher-priority
 * offers first and drops offers whose TTL has elapsed.
 */
@Getter
@Builder
public class Offer implements Comparable<Offer> {

    /** Unique identifier for this offer. */
    private final String offerId;

    /** H3 cell identifier this offer belongs to. */
    private final String cellId;

    /** Driver submitting the offer. */
    private final String driverId;

    /** Rider requesting a ride (may be null for speculative offers). */
    private final String riderId;

    /**
     * Priority of the offer. Higher value means higher urgency; used to order
     * offers within the same cell queue.
     */
    private final int priority;

    /** Timestamp at which the offer was created. */
    private final Instant createdAt;

    /** Time-to-live for this offer. Offers older than {@code createdAt + ttl} are expired. */
    private final Duration ttl;

    /**
     * Returns {@code true} when the offer's TTL has elapsed relative to {@code now}.
     *
     * @param now the reference instant (typically {@link Instant#now()})
     * @return {@code true} if the offer has expired, {@code false} otherwise
     */
    public boolean isExpired(Instant now) {
        return now.isAfter(createdAt.plus(ttl));
    }

    /**
     * Natural ordering: higher priority first; ties broken by earlier {@code createdAt}
     * (FIFO within the same priority level).
     */
    @Override
    public int compareTo(Offer other) {
        // Descending by priority
        int cmp = Integer.compare(other.priority, this.priority);
        if (cmp != 0) {
            return cmp;
        }
        // Ascending by creation time (earlier = higher position)
        return this.createdAt.compareTo(other.createdAt);
    }
}
