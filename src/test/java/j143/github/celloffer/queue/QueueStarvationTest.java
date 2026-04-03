package j143.github.celloffer.queue;

import j143.github.pushgateway.MessageStore;
import j143.github.pushgateway.model.PendingMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates Bug 3 (Queue Starvation by High Priority) in the push-message
 * delivery pipeline.
 *
 * <h3>What the bug is</h3>
 * <p>The {@link MessageStore} orders messages by priority (highest first) within
 * each user's {@link java.util.TreeSet}. When the queue reaches its maximum
 * capacity and a high-priority message arrives, it evicts the lowest-priority
 * message. If high-priority messages arrive faster than the queue is drained,
 * low-priority messages are <em>continuously</em> evicted before they can be
 * delivered – they are effectively starved.
 *
 * <p>This is not a bug per se in the eviction logic but a missing mechanism:
 * there is no <em>aging</em> (i.e. priority boosting over time) or
 * <em>weighted-fair-queue</em> policy to guarantee eventual delivery of
 * low-priority messages.
 *
 * <h3>What happens in production</h3>
 * <ul>
 *   <li>In a New Year's Eve surge, high-priority "surge pricing" offers flood
 *       the queue and continuously push out ordinary offers.</li>
 *   <li>Drivers never see the ordinary offers for minutes or hours, even though
 *       those offers have valid TTLs and are ready for delivery.</li>
 *   <li>{@code push_messages_dropped_total} spikes, but observability dashboards
 *       show healthy throughput because high-priority messages ARE flowing.</li>
 * </ul>
 *
 * <h3>Fix options</h3>
 * <ul>
 *   <li><strong>Aging:</strong> linearly boost the effective priority of a message
 *       by {@code (now - createdAt) / agingFactor} so old low-priority messages
 *       eventually outrank new high-priority ones.</li>
 *   <li><strong>Weighted-fair queue:</strong> maintain separate sub-queues per
 *       priority tier and drain them in proportion (e.g. 3 high : 1 low per
 *       drain cycle).</li>
 *   <li><strong>Minimum share reservation:</strong> reserve N queue slots for
 *       low-priority messages that cannot be evicted by high-priority ones.</li>
 * </ul>
 */
class QueueStarvationTest {

    private static final int    QUEUE_CAP = 10;
    private static final String USER      = "driver-starved";

    private MessageStore        store;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        store         = new MessageStore(QUEUE_CAP, meterRegistry);
    }

    /**
     * Demonstrates starvation: low-priority messages never reach the dispatcher
     * because high-priority messages continuously flood the bounded queue and
     * evict them before they are polled.
     *
     * <p>Scenario:
     * <ol>
     *   <li>Enqueue {@code QUEUE_CAP} low-priority messages (priority=1).</li>
     *   <li>Enqueue {@code QUEUE_CAP} high-priority messages (priority=10).</li>
     *   <li>Because the queue is full when high-priority messages arrive, each
     *       one evicts the current worst (lowest-priority) message.</li>
     *   <li>By the time all high-priority messages are enqueued, no low-priority
     *       messages remain in the queue.</li>
     * </ol>
     */
    @Test
    void starvation_lowPriorityMessagesEvicted_beforeEverBeingDispatched() {
        // Fill queue with low-priority messages
        for (int i = 0; i < QUEUE_CAP; i++) {
            long seq = store.enqueue(USER, ("low-" + i).getBytes(), Duration.ofSeconds(60), 1);
            assertThat(seq).as("Low-priority message %d should enqueue into empty queue", i)
                           .isPositive();
        }
        assertThat(store.queueDepth(USER)).isEqualTo(QUEUE_CAP);

        // Flood with high-priority messages – each one evicts a low-priority message
        int evictedCount = 0;
        for (int i = 0; i < QUEUE_CAP; i++) {
            long seq = store.enqueue(USER, ("high-" + i).getBytes(), Duration.ofSeconds(60), 10);
            if (seq > 0) {
                // Incoming was accepted (low-priority was evicted)
                evictedCount++;
            }
        }

        // At this point all low-priority messages have been evicted
        List<PendingMessage> polled =
                store.pollDueMessages(USER, Instant.now(), QUEUE_CAP * 2);

        long lowPriorityRemaining = polled.stream()
                .filter(m -> m.getPriority() == 1)
                .count();

        long highPriorityRemaining = polled.stream()
                .filter(m -> m.getPriority() == 10)
                .count();

        // STARVATION: zero low-priority messages survive
        assertThat(lowPriorityRemaining)
                .as("STARVATION: all %d low-priority messages were evicted before dispatch. "
                        + "Fix: add aging or weighted-fair-queue policy to guarantee eventual delivery.",
                        QUEUE_CAP)
                .isZero();

        assertThat(highPriorityRemaining)
                .as("High-priority messages fill the queue after starvation")
                .isEqualTo(QUEUE_CAP);
    }

    /**
     * Demonstrates that the starvation is unbounded: as long as high-priority
     * messages keep arriving faster than the queue is drained, low-priority
     * messages will wait forever regardless of their TTL.
     *
     * <p>This test shows that even a single low-priority message cannot make it
     * through when the queue is continuously saturated with high-priority work.
     */
    @Test
    void starvation_singleLowPriorityMessage_neverDeliveredUnderContinuousHighPriorityLoad() {
        // Seed queue with (QUEUE_CAP - 1) high-priority messages
        for (int i = 0; i < QUEUE_CAP - 1; i++) {
            store.enqueue(USER, ("hi-seed-" + i).getBytes(), Duration.ofSeconds(60), 10);
        }
        // Add one low-priority message – it fits (queue has one slot left)
        long lowSeq = store.enqueue(USER, "the-low-one".getBytes(), Duration.ofSeconds(60), 1);
        assertThat(lowSeq).isPositive();
        assertThat(store.queueDepth(USER)).isEqualTo(QUEUE_CAP);

        // Now flood with high-priority – the single low-priority is immediately evicted
        store.enqueue(USER, "hi-flood".getBytes(), Duration.ofSeconds(60), 10);

        List<PendingMessage> dispatched =
                store.pollDueMessages(USER, Instant.now(), QUEUE_CAP);

        boolean lowPriorityDelivered = dispatched.stream()
                .anyMatch(m -> m.getPriority() == 1);

        assertThat(lowPriorityDelivered)
                .as("STARVATION: low-priority message (seqId=%d) was evicted by flood and never delivered. "
                        + "Fix: reserve a minimum queue share for low-priority messages.", lowSeq)
                .isFalse();
    }

    /**
     * Demonstrates that the {@code push_messages_dropped_total} counter is
     * incremented for evicted messages, providing at least some observability
     * of the starvation.  The counter is tagged with {@code userId} which itself
     * introduces high-cardinality (see {@link HighCardinalityMetricsTest}).
     */
    @Test
    void starvation_evictedMessagesIncrementDropCounter() {
        // Fill the queue
        for (int i = 0; i < QUEUE_CAP; i++) {
            store.enqueue(USER, ("low-" + i).getBytes(), Duration.ofSeconds(60), 1);
        }
        // One high-priority evicts one low-priority → drop counter +1
        store.enqueue(USER, "high".getBytes(), Duration.ofSeconds(60), 10);

        double dropped = meterRegistry.counter("push_messages_dropped_total", "userId", USER).count();
        assertThat(dropped)
                .as("Evicted messages must increment the drop counter for observability")
                .isEqualTo(1.0);
    }
}
