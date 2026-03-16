package com.uber.celloffer.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OfferTest {

    private Offer offer(int priority, Instant createdAt, Duration ttl) {
        return Offer.builder()
                .offerId("id-" + priority)
                .cellId("cell-1")
                .driverId("driver-" + priority)
                .riderId("rider-1")
                .priority(priority)
                .createdAt(createdAt)
                .ttl(ttl)
                .build();
    }

    @Test
    void isExpired_returnsFalse_whenWithinTtl() {
        Instant now = Instant.now();
        Offer o = offer(5, now.minusSeconds(1), Duration.ofSeconds(5));
        assertThat(o.isExpired(now)).isFalse();
    }

    @Test
    void isExpired_returnsTrue_whenTtlElapsed() {
        Instant now = Instant.now();
        Offer o = offer(5, now.minusSeconds(10), Duration.ofSeconds(5));
        assertThat(o.isExpired(now)).isTrue();
    }

    @Test
    void compareTo_higherPriorityComesFirst() {
        Instant now = Instant.now();
        Offer high = offer(10, now, Duration.ofSeconds(30));
        Offer low = offer(1, now, Duration.ofSeconds(30));
        // high < low in natural ordering (high priority → smaller compareTo result)
        assertThat(high.compareTo(low)).isNegative();
        assertThat(low.compareTo(high)).isPositive();
    }

    @Test
    void compareTo_samePriority_earlierCreationFirst() {
        Instant base = Instant.now();
        Offer earlier = offer(5, base, Duration.ofSeconds(30));
        Offer later = offer(5, base.plusSeconds(1), Duration.ofSeconds(30));
        assertThat(earlier.compareTo(later)).isNegative();
    }

    @Test
    void compareTo_equalOffers_returnsZero() {
        Instant base = Instant.now();
        Offer a = offer(5, base, Duration.ofSeconds(30));
        Offer b = offer(5, base, Duration.ofSeconds(30));
        assertThat(a.compareTo(b)).isZero();
    }
}
