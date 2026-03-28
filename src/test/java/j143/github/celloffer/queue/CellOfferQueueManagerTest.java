package j143.github.celloffer.queue;

import j143.github.celloffer.model.CellQueueStats;
import j143.github.celloffer.model.Offer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CellOfferQueueManagerTest {

    private CellOfferQueueManager manager;

    @BeforeEach
    void setUp() {
        manager = new CellOfferQueueManager(3, new SimpleMeterRegistry());
    }

    private Offer offer(String id, String cellId, int priority, Duration ttl) {
        return Offer.builder()
                .offerId(id)
                .cellId(cellId)
                .driverId("driver-" + id)
                .riderId("rider-1")
                .priority(priority)
                .createdAt(Instant.now())
                .ttl(ttl)
                .build();
    }

    @Test
    void enqueue_addsOfferToQueue() {
        Offer o = offer("o1", "cell-A", 5, Duration.ofSeconds(30));
        manager.enqueue(o);
        assertThat(manager.getQueueDepth("cell-A")).isEqualTo(1);
        assertThat(manager.getStats("cell-A").getEnqueued()).isEqualTo(1);
    }

    @Test
    void enqueue_whenAtCapacity_dropsLowPriorityIncomingOffer() {
        // Fill queue to capacity (max=3) with high-priority offers
        manager.enqueue(offer("o1", "cell-B", 10, Duration.ofSeconds(30)));
        manager.enqueue(offer("o2", "cell-B", 9, Duration.ofSeconds(30)));
        manager.enqueue(offer("o3", "cell-B", 8, Duration.ofSeconds(30)));

        // Attempt to enqueue a low-priority offer – should be dropped
        manager.enqueue(offer("o4", "cell-B", 1, Duration.ofSeconds(30)));

        assertThat(manager.getQueueDepth("cell-B")).isEqualTo(3);
        assertThat(manager.getStats("cell-B").getDroppedOnEnqueue()).isEqualTo(1);
    }

    @Test
    void enqueue_whenAtCapacity_evictsWorstAndAddsHighPriorityOffer() {
        manager.enqueue(offer("o1", "cell-C", 5, Duration.ofSeconds(30)));
        manager.enqueue(offer("o2", "cell-C", 4, Duration.ofSeconds(30)));
        manager.enqueue(offer("o3", "cell-C", 3, Duration.ofSeconds(30)));

        // High-priority offer should evict o3 (priority 3)
        manager.enqueue(offer("o4", "cell-C", 10, Duration.ofSeconds(30)));

        assertThat(manager.getQueueDepth("cell-C")).isEqualTo(3);
        // 4 enqueued counted (o1..o4), no drops
        assertThat(manager.getStats("cell-C").getEnqueued()).isEqualTo(4);
        assertThat(manager.getStats("cell-C").getDroppedOnEnqueue()).isEqualTo(0);
    }

    @Test
    void flushDueOffers_returnsLiveOffers() {
        Instant now = Instant.now();
        manager.enqueue(offer("o1", "cell-D", 5, Duration.ofSeconds(30)));
        manager.enqueue(offer("o2", "cell-D", 3, Duration.ofSeconds(30)));

        List<Offer> flushed = manager.flushDueOffers("cell-D", now);

        assertThat(flushed).hasSize(2);
        assertThat(manager.getQueueDepth("cell-D")).isEqualTo(0);
        assertThat(manager.getStats("cell-D").getFlushed()).isEqualTo(2);
    }

    @Test
    void flushDueOffers_discardsExpiredOffers() {
        // Create an offer that is already expired
        Offer expired = Offer.builder()
                .offerId("exp-1")
                .cellId("cell-E")
                .driverId("driver-1")
                .riderId("rider-1")
                .priority(5)
                .createdAt(Instant.now().minusSeconds(60))
                .ttl(Duration.ofSeconds(1))
                .build();
        Offer live = offer("live-1", "cell-E", 3, Duration.ofSeconds(30));

        manager.enqueue(expired);
        manager.enqueue(live);

        List<Offer> flushed = manager.flushDueOffers("cell-E", Instant.now());

        assertThat(flushed).hasSize(1);
        assertThat(flushed.get(0).getOfferId()).isEqualTo("live-1");
        assertThat(manager.getStats("cell-E").getExpired()).isEqualTo(1);
    }

    @Test
    void flushDueOffers_unknownCell_returnsEmpty() {
        List<Offer> result = manager.flushDueOffers("unknown-cell", Instant.now());
        assertThat(result).isEmpty();
    }

    @Test
    void getStats_unknownCell_returnsZeroCounters() {
        CellQueueStats stats = manager.getStats("nonexistent");
        assertThat(stats.getEnqueued()).isZero();
        assertThat(stats.getFlushed()).isZero();
        assertThat(stats.getExpired()).isZero();
        assertThat(stats.getDroppedOnEnqueue()).isZero();
    }
}
