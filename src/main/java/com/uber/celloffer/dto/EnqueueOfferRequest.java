package com.uber.celloffer.dto;

import lombok.Data;

/**
 * HTTP request body for {@code POST /cells/{cellId}/offers}.
 */
@Data
public class EnqueueOfferRequest {

    private String driverId;
    private String riderId;

    /**
     * Priority of the offer. Higher value = higher urgency.
     * Defaults to 0 (lowest).
     */
    private int priority;

    /**
     * Time-to-live for this offer in milliseconds.
     * After this duration the offer is considered expired and will be dropped
     * during the next flush cycle.
     */
    private long ttlMillis;
}
