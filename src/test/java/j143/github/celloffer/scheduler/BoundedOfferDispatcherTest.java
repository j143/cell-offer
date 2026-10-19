package j143.github.celloffer.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import j143.github.celloffer.model.Offer;
import j143.github.celloffer.push.OfferPushClient;
import j143.github.celloffer.push.SendOutcome;
import j143.github.celloffer.queue.CellOfferQueueManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BoundedOfferDispatcherTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock();
    private final ManualExecutor executor = new ManualExecutor();
    private final OfferPushClient client = mock(OfferPushClient.class);
    private final CellOfferQueueManager queues = new CellOfferQueueManager(100, registry);
    private BoundedOfferDispatcher dispatcher;

    @BeforeEach
    void setup() {
        when(client.sendOffer(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(SendOutcome.ACCEPTED);
        dispatcher = new BoundedOfferDispatcher(queues, client, new ObjectMapper(),
                registry, clock, executor, 2, 2, 100, 1000, false, 1, bound -> bound);
    }

    @AfterEach
    void close() {
        dispatcher.close();
        registry.close();
    }

    private Offer offer(String id, String cell, int priority) {
        return Offer.builder().offerId(id).cellId(cell).driverId(id).riderId(null)
                .priority(priority).createdAt(clock.instant())
                .ttl(Duration.ofSeconds(10)).build();
    }

    @Test
    void queuedWorkConsumesCapacityBeforeMoreOffersAreRemoved() {
        for (String cell : List.of("a", "b", "c")) {
            queues.enqueue(offer(cell, cell, 1));
        }
        dispatcher.tick();
        dispatcher.tick();
        assertThat(executor.tasks).hasSize(2);
        assertThat(queues.getQueueDepth("c")).isEqualTo(1);
        assertThat(registry.get("cell_dispatch_pending").gauge().value()).isEqualTo(2);
        verifyNoInteractions(client);
        executor.runNext();
        dispatcher.tick();
        assertThat(queues.getQueueDepth("c")).isZero();
        executor.runAll();
        assertThat(terminal("accepted")).isEqualTo(3);
    }

    @Test
    void oneOfferPerCellPreservesPriorityAndDoesNotLetHotCellMonopolize() {
        queues.enqueue(offer("low", "a", 1));
        queues.enqueue(offer("high", "a", 10));
        queues.enqueue(offer("cold", "b", 1));
        dispatcher.tick();
        executor.runAll();
        var order = inOrder(client);
        order.verify(client).sendOffer(eq("high"), anyString(), anyLong(), eq(10));
        order.verify(client).sendOffer(eq("cold"), anyString(), anyLong(), eq(1));
        assertThat(queues.getQueueDepth("a")).isEqualTo(1);
    }

    @Test
    void executionRejectionRetainsOwnershipWithoutNormalEnqueue() {
        executor.rejectNext = true;
        queues.enqueue(offer("one", "a", 1));
        dispatcher.tick();
        assertThat(queues.getQueueDepth("a")).isZero();
        assertThat(registry.get("cell_dispatch_pending").gauge().value()).isEqualTo(1);
        dispatcher.tick();
        executor.runAll();
        assertThat(terminal("accepted")).isEqualTo(1);
        assertThat(registry.get("cell_dispatch_submission_rejected_total").counter().count())
                .isEqualTo(1);
    }

    @Test
    void expiredQueuedOfferNeverReachesTransport() {
        queues.enqueue(offer("one", "a", 1));
        dispatcher.tick();
        clock.advance(10_000);
        executor.runAll();
        verifyNoInteractions(client);
        assertThat(terminal("expired")).isEqualTo(1);
    }

    @Test
    void remainingTtlAndJsonEscapingArePreserved() {
        queues.enqueue(offer("driver\"quoted", "a", 1));
        dispatcher.tick();
        clock.advance(2000);
        executor.runAll();
        verify(client).sendOffer(eq("driver\"quoted"),
                contains("driver\\\"quoted"), eq(8000L), eq(1));
    }

    @Test
    void safeRetryRetainsSlotAndStopsAtBudget() {
        when(client.sendOffer(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(SendOutcome.RETRYABLE_FAILURE);
        queues.enqueue(offer("one", "a", 1));
        dispatcher.tick();
        executor.runAll();
        assertThat(registry.get("cell_dispatch_pending").gauge().value()).isEqualTo(1);
        dispatcher.tick();
        assertThat(executor.tasks).isEmpty();
        clock.advance(100);
        dispatcher.tick();
        executor.runAll();
        verify(client, times(2)).sendOffer(anyString(), anyString(), anyLong(), anyInt());
        assertThat(registry.get("cell_dispatch_retry_exhausted_total").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("cell_dispatch_pending").gauge().value()).isZero();
    }

    @Test
    void ambiguousFailureDoesNotAutomaticallyRetry() {
        when(client.sendOffer(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(SendOutcome.AMBIGUOUS_FAILURE);
        queues.enqueue(offer("one", "a", 1));
        dispatcher.tick();
        executor.runAll();
        clock.advance(1000);
        dispatcher.tick();
        executor.runAll();
        verify(client, times(1)).sendOffer(anyString(), anyString(), anyLong(), anyInt());
        assertThat(terminal("ambiguous_failure")).isEqualTo(1);
    }

    @Test
    void shutdownAccountsQueuedWorkExactlyOnce() {
        queues.enqueue(offer("one", "a", 1));
        dispatcher.tick();
        dispatcher.close();
        dispatcher.close();
        assertThat(registry.get("cell_dispatch_abandoned_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("cell_dispatch_pending").gauge().value()).isZero();
        assertThat(registry.get("cell_dispatch_in_flight").gauge().value()).isZero();
        verifyNoInteractions(client);
    }

    @Test
    void retryWaitingOfferExpiresWithoutAnotherSend() {
        when(client.sendOffer(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(SendOutcome.RETRYABLE_FAILURE);
        queues.enqueue(offer("one", "a", 1));
        dispatcher.tick();
        executor.runAll();
        clock.advance(10_000);
        dispatcher.tick();
        executor.runAll();
        verify(client, times(1)).sendOffer(anyString(), anyString(), anyLong(), anyInt());
        assertThat(terminal("expired")).isEqualTo(1);
    }

    @Test
    void rejectionIsTerminalAndDoesNotLeakCapacity() {
        when(client.sendOffer(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(SendOutcome.REJECTED);
        queues.enqueue(offer("one", "a", 1));
        dispatcher.tick();
        executor.runAll();
        assertThat(terminal("rejected")).isEqualTo(1);
        assertThat(registry.get("cell_dispatch_pending").gauge().value()).isZero();
    }

    @Test
    void queueEvictionAndDuplicateAccountingReconcile() {
        CellOfferQueueManager small = new CellOfferQueueManager(1, new SimpleMeterRegistry());
        Offer low = offer("low", "a", 1);
        Offer high = offer("high", "a", 10);
        assertThat(small.enqueue(low)).isTrue();
        assertThat(small.enqueue(high)).isTrue();
        assertThat(small.enqueue(high)).isFalse();
        assertThat(small.enqueue(low)).isFalse();
        assertThat(small.pollForDispatch("a", clock.instant()).getOfferId()).isEqualTo("high");
        assertThat(small.getQueueDepth("a")).isZero();
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void blockedWorkerDoesNotAllowUnboundedRemoval() throws Exception {
        dispatcher.close();
        registry.clear();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var realPool = java.util.concurrent.Executors.newFixedThreadPool(1);
        dispatcher = new BoundedOfferDispatcher(queues, client, new ObjectMapper(),
                registry, clock, realPool, 2, 2, 100, 1000, false, 1000, bound -> bound);
        when(client.sendOffer(anyString(), anyString(), anyLong(), anyInt())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("worker was not released");
            }
            return SendOutcome.ACCEPTED;
        });
        try {
            queues.enqueue(offer("a", "a", 1));
            queues.enqueue(offer("b", "b", 1));
            queues.enqueue(offer("c", "c", 1));
            dispatcher.tick();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.tick();
            assertThat(queues.getQueueDepth("c")).isEqualTo(1);
        } finally {
            release.countDown();
            dispatcher.close();
        }
    }

    private double terminal(String outcome) {
        return registry.get("cell_dispatch_terminal_total").tag("outcome", outcome).counter().count();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-19T00:00:00Z");
        void advance(long millis) { now = now.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean stopped;
        private boolean rejectNext;
        @Override public void execute(Runnable command) {
            if (stopped || rejectNext) {
                rejectNext = false;
                throw new RejectedExecutionException();
            }
            tasks.add(command);
        }
        void runNext() { tasks.remove().run(); }
        void runAll() { while (!tasks.isEmpty()) { runNext(); } }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() {
            stopped = true;
            List<Runnable> removed = new ArrayList<>(tasks);
            tasks.clear();
            return removed;
        }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
