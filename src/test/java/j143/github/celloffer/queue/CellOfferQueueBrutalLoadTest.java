package j143.github.celloffer.queue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import j143.github.celloffer.model.Offer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Brutal combinations of load for {@link CellOfferQueueManager}.
 *
 * <p>These tests deliberately stress the boundaries that matter before this
 * service handles production offer volume: hot cells, concurrent enqueue and
 * flush, high cell cardinality, driver skew, expiry, and non-retryable flush
 * semantics.</p>
 */
class CellOfferQueueBrutalLoadTest {

    private static final int CAPACITY = 100;

    private CellOfferQueueManager manager;

    @BeforeEach
    void setUp() {
        manager = new CellOfferQueueManager(CAPACITY, new SimpleMeterRegistry());
    }

    private Offer offer(String offerId, String cellId, int priority) {
        return offer(offerId, cellId, "driver-" + offerId, priority, Duration.ofSeconds(30), Instant.now());
    }

    private Offer offer(
            String offerId,
            String cellId,
            String driverId,
            int priority,
            Duration ttl,
            Instant createdAt) {
        return Offer.builder()
                .offerId(offerId)
                .cellId(cellId)
                .driverId(driverId)
                .riderId("rider-" + offerId)
                .priority(priority)
                .createdAt(createdAt)
                .ttl(ttl)
                .build();
    }

    @Test
    @DisplayName("32 concurrent writers into one hot cell preserve the admission invariant")
    void concurrentWritersIntoHotCellPreserveAdmissionInvariant() throws Exception {
        int writers = 32;
        int offersPerWriter = 100;
        int totalOffers = writers * offersPerWriter;
        String cellId = "hot-cell";
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (int writer = 0; writer < writers; writer++) {
            final int base = writer * offersPerWriter;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < offersPerWriter; i++) {
                    manager.enqueue(offer("offer-" + base + "-" + i, cellId, (i % 10) + 1));
                }
                return null;
            }));
        }

        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Every offer must be either admitted or explicitly dropped; none may vanish.
        assertThat(manager.getStats(cellId).getEnqueued()
                + manager.getStats(cellId).getDroppedOnEnqueue())
                .isEqualTo(totalOffers);
        assertThat(manager.getQueueDepth(cellId)).isLessThanOrEqualTo(CAPACITY);

        List<Offer> flushed = manager.flushDueOffers(cellId, Instant.now());
        assertThat(flushed).hasSize(CAPACITY);
        assertThat(manager.getQueueDepth(cellId)).isZero();
    }

    @Test
    @DisplayName("Concurrent enqueue and flush never lose the enqueue/drop/flush accounting")
    void concurrentEnqueueAndFlushPreserveAccounting() throws Exception {
        String cellId = "mixed-hot-cell";
        int writers = 16;
        int offersPerWriter = 500;
        int totalOffers = writers * offersPerWriter;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stopFlusher = new AtomicBoolean(false);
        AtomicLong flushedTotal = new AtomicLong();

        for (int writer = 0; writer < writers; writer++) {
            final int base = writer * offersPerWriter;
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < offersPerWriter; i++) {
                    manager.enqueue(offer("offer-" + base + "-" + i, cellId, (i % 20) + 1));
                }
                return null;
            });
        }

        pool.submit(() -> {
            start.await();
            while (!stopFlusher.get()) {
                flushedTotal.addAndGet(manager.flushDueOffers(cellId, Instant.now()).size());
                TimeUnit.MILLISECONDS.sleep(1);
            }
            return null;
        });

        start.countDown();
        TimeUnit.SECONDS.sleep(2);
        stopFlusher.set(true);
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Drain whatever remains after the background flusher stops.
        flushedTotal.addAndGet(manager.flushDueOffers(cellId, Instant.now()).size());

        long admitted = manager.getStats(cellId).getEnqueued();
        long dropped = manager.getStats(cellId).getDroppedOnEnqueue();
        long expired = manager.getStats(cellId).getExpired();

        assertThatNoException().isThrownBy(() -> {
            if (admitted + dropped != totalOffers) {
                throw new AssertionError("admission accounting mismatch");
            }
            if (admitted != flushedTotal.get() + expired + manager.getQueueDepth(cellId)) {
                throw new AssertionError("delivery accounting mismatch");
            }
        });
    }

    @Test
    @DisplayName("High-cardinality cells remain individually isolated and fully flushable")
    void highCardinalityCellsRemainIsolatedAndFlushable() {
        int cellCount = 5_000;
        for (int i = 0; i < cellCount; i++) {
            manager.enqueue(offer("offer-" + i, "cell-" + i, i % 5));
        }

        assertThat(manager.getCellIds()).hasSize(cellCount);

        long flushed = 0;
        for (String cellId : manager.getCellIds()) {
            flushed += manager.flushDueOffers(cellId, Instant.now()).size();
        }

        assertThat(flushed).isEqualTo(cellCount);
    }

    @Test
    @DisplayName("Driver skew does not create phantom offers during a full flush")
    void driverSkewDoesNotCreatePhantomOffers() {
        int cellCount = 50;
        int offersPerCell = 100;
        int hotDriverCount = 10;
        Map<String, Integer> deliveriesByDriver = new ConcurrentHashMap<>();

        for (int cell = 0; cell < cellCount; cell++) {
            String cellId = "cell-" + cell;
            for (int i = 0; i < offersPerCell; i++) {
                String driverId = (i % 2 == 0)
                        ? "hot-driver-" + (i % hotDriverCount)
                        : "driver-" + cell + "-" + i;
                manager.enqueue(offer("offer-" + cell + "-" + i, cellId, driverId, 5,
                        Duration.ofSeconds(30), Instant.now()));
            }
        }

        for (String cellId : manager.getCellIds()) {
            for (Offer flushed : manager.flushDueOffers(cellId, Instant.now())) {
                deliveriesByDriver.merge(flushed.getDriverId(), 1, Integer::sum);
            }
        }

        assertThat(deliveriesByDriver.values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(cellCount * offersPerCell);
        assertThat(manager.getCellIds()).allSatisfy(cellId ->
                assertThat(manager.getQueueDepth(cellId)).isZero());
    }

    @Test
    @DisplayName("Expired offers are never returned by a burst flush")
    void expiredOffersAreNeverReturnedByBurstFlush() {
        String cellId = "expiry-cell";
        Instant now = Instant.now();

        for (int i = 0; i < 50; i++) {
            manager.enqueue(offer("live-" + i, cellId, "driver-" + i, 5,
                    Duration.ofSeconds(30), now));
            manager.enqueue(offer("expired-" + i, cellId, "driver-" + i, 5,
                    Duration.ofMillis(1), now.minusSeconds(60)));
        }

        List<Offer> flushed = manager.flushDueOffers(cellId, now);

        assertThat(flushed).hasSize(50);
        assertThat(flushed).allSatisfy(offer ->
                assertThat(offer.getOfferId()).startsWith("live-"));
        assertThat(manager.getStats(cellId).getExpired()).isEqualTo(50);
    }

    @Test
    @DisplayName("Flush drains without a retry path: a failed downstream send cannot be replayed")
    void flushDrainsWithoutRetryPath() {
        String cellId = "loss-probe-cell";
        manager.enqueue(offer("o1", cellId, 5));
        manager.enqueue(offer("o2", cellId, 4));
        manager.enqueue(offer("o3", cellId, 3));

        assertThat(manager.flushDueOffers(cellId, Instant.now())).hasSize(3);

        // This documents the current contract: once drained, a subsequent flush
        // cannot recover offers if the PushGateway call failed.
        assertThat(manager.flushDueOffers(cellId, Instant.now())).isEmpty();
        assertThat(manager.getStats(cellId).getFlushed()).isEqualTo(3);
    }

    @Test
    @DisplayName("Special-character cell IDs remain isolated")
    void specialCharacterCellIdsRemainIsolated() {
        String quotedCell = "cell\"with\"quotes";
        String unicodeCell = "cell-\u20b9-\u0939\u093f\u0928\u094d\u0926\u0940";

        manager.enqueue(offer("q1", quotedCell, 5));
        manager.enqueue(offer("u1", unicodeCell, 5));

        assertThat(manager.flushDueOffers(quotedCell, Instant.now())).hasSize(1);
        assertThat(manager.flushDueOffers(unicodeCell, Instant.now())).hasSize(1);
    }

    @Test
    @DisplayName("Equal priority and timestamp offers are ordered deterministically by offer ID")
    void equalPriorityAndTimestampOffersAreDeterministic() {
        String cellId = "tie-cell";
        Instant sameTime = Instant.parse("2026-01-01T00:00:00Z");

        manager.enqueue(offer("b-offer", cellId, "driver-b", 7,
                Duration.ofSeconds(30), sameTime));
        manager.enqueue(offer("a-offer", cellId, "driver-a", 7,
                Duration.ofSeconds(30), sameTime));

        List<Offer> flushed = manager.flushDueOffers(cellId, sameTime);

        assertThat(flushed).extracting(Offer::getOfferId)
                .containsExactly("a-offer", "b-offer");
    }
}
