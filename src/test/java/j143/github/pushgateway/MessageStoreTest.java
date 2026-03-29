package j143.github.pushgateway;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import j143.github.pushgateway.model.PendingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageStoreTest {

    private MessageStore store;

    @BeforeEach
    void setUp() {
        store = new MessageStore(5, new SimpleMeterRegistry());
    }

    @Test
    void enqueue_assignsMonotonicallyIncreasingSeqIds() {
        long s1 = store.enqueue("user-1", bytes("msg1"), Duration.ofSeconds(30), 5);
        long s2 = store.enqueue("user-1", bytes("msg2"), Duration.ofSeconds(30), 5);
        assertThat(s1).isLessThan(s2);
        assertThat(s1).isPositive();
    }

    @Test
    void enqueue_differentUsersHaveIndependentSequences() {
        long s1 = store.enqueue("user-A", bytes("a"), Duration.ofSeconds(30), 1);
        long s2 = store.enqueue("user-B", bytes("b"), Duration.ofSeconds(30), 1);
        assertThat(s1).isEqualTo(1);
        assertThat(s2).isEqualTo(1); // independent per-user counter
    }

    @Test
    void pollDueMessages_returnsNonExpiredMessagesUpToLimit() {
        store.enqueue("user-2", bytes("a"), Duration.ofSeconds(30), 3);
        store.enqueue("user-2", bytes("b"), Duration.ofSeconds(30), 1);
        store.enqueue("user-2", bytes("c"), Duration.ofSeconds(30), 5);

        List<PendingMessage> msgs = store.pollDueMessages("user-2", java.time.Instant.now(), 10);

        assertThat(msgs).hasSize(3);
        // Highest priority first
        assertThat(msgs.get(0).getPriority()).isEqualTo(5);
        assertThat(msgs.get(1).getPriority()).isEqualTo(3);
        assertThat(msgs.get(2).getPriority()).isEqualTo(1);
        // At-least-once: messages are NOT removed from the store until pruneAcked().
        assertThat(store.queueDepth("user-2")).isEqualTo(3);
    }

    @Test
    void pollDueMessages_respectsLimit() {
        for (int i = 0; i < 5; i++) {
            store.enqueue("user-3", bytes("x"), Duration.ofSeconds(30), i);
        }
        List<PendingMessage> msgs = store.pollDueMessages("user-3", java.time.Instant.now(), 2);
        assertThat(msgs).hasSize(2);
        // All 5 messages remain in the store; only 2 were read (not removed).
        assertThat(store.queueDepth("user-3")).isEqualTo(5);
    }

    @Test
    void enqueue_whenAtCapacity_dropsLowPriorityIncoming() {
        for (int i = 1; i <= 5; i++) {
            store.enqueue("user-5", bytes("p" + i), Duration.ofSeconds(30), i + 10);
        }
        // Queue is full (capacity 5). Incoming with lower priority should be dropped.
        long result = store.enqueue("user-5", bytes("low"), Duration.ofSeconds(30), 1);
        assertThat(result).isEqualTo(-1);
        assertThat(store.queueDepth("user-5")).isEqualTo(5);
    }

    @Test
    void enqueue_whenAtCapacity_evictsWorstForHigherPriority() {
        for (int i = 1; i <= 5; i++) {
            store.enqueue("user-7", bytes("p" + i), Duration.ofSeconds(30), i);
        }
        // Incoming has higher priority than the worst (1) – should evict it
        long result = store.enqueue("user-7", bytes("high"), Duration.ofSeconds(30), 100);
        assertThat(result).isPositive();
        assertThat(store.queueDepth("user-7")).isEqualTo(5);
    }

    @Test
    void pruneAcked_removesMessagesUpToAckedSeqId() {
        long s1 = store.enqueue("user-6", bytes("a"), Duration.ofSeconds(30), 5);
        long s2 = store.enqueue("user-6", bytes("b"), Duration.ofSeconds(30), 4);
        long s3 = store.enqueue("user-6", bytes("c"), Duration.ofSeconds(30), 3);

        store.pruneAcked("user-6", s2);

        // Only the message with seqId > s2 remains (s3 has the lowest priority here)
        assertThat(store.queueDepth("user-6")).isEqualTo(1);
        List<PendingMessage> remaining = store.pollDueMessages(
                "user-6", java.time.Instant.now(), 10);
        assertThat(remaining.get(0).getSeqId()).isEqualTo(s3);
    }

    @Test
    void queueDepth_unknownUser_returnsZero() {
        assertThat(store.queueDepth("nobody")).isEqualTo(0);
    }

    @Test
    void pollDueMessages_unknownUser_returnsEmpty() {
        assertThat(store.pollDueMessages("nobody", java.time.Instant.now(), 5)).isEmpty();
    }

    // -----------------------------------------------------------------------

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
