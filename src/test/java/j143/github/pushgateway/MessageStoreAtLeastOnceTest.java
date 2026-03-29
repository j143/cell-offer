package j143.github.pushgateway;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import j143.github.pushgateway.model.PendingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that prove the "At-Least-Once" delivery guarantee.
 *
 * <p>Critical flaw being fixed: the original {@code pollDueMessages()} called
 * {@code it.remove()} before the message was acknowledged, creating an
 * "At-Most-Once" trap where network failures between dispatch and client-ack
 * caused permanent message loss. Messages must remain in the store until an
 * explicit {@code ClientAck} triggers {@link MessageStore#pruneAcked}.
 */
class MessageStoreAtLeastOnceTest {

    private MessageStore store;

    @BeforeEach
    void setUp() {
        store = new MessageStore(50, new SimpleMeterRegistry());
    }

    @Test
    void pollDueMessages_doesNotRemoveMessages_atLeastOnceGuarantee() {
        // Enqueue a message
        long seqId = store.enqueue("driver-1", bytes("offer"), Duration.ofSeconds(60), 5);
        assertThat(seqId).isPositive();
        assertThat(store.queueDepth("driver-1")).isEqualTo(1);

        // Simulate: dispatcher reads the message (as if about to send over network)
        List<PendingMessage> dispatched =
                store.pollDueMessages("driver-1", Instant.now(), 10);
        assertThat(dispatched).hasSize(1);

        // CRITICAL: message must still be in the store – network could have dropped
        // the packet before the client received it.
        assertThat(store.queueDepth("driver-1"))
                .as("Message must remain in store until client ACKs it")
                .isEqualTo(1);
    }

    @Test
    void pollDueMessages_returnsMessageAgainIfNotAcked_networkRetryScenario() {
        store.enqueue("driver-2", bytes("offer"), Duration.ofSeconds(60), 5);

        // First dispatch (simulates first send attempt)
        List<PendingMessage> firstDispatch =
                store.pollDueMessages("driver-2", Instant.now(), 10);
        assertThat(firstDispatch).hasSize(1);
        long seqId = firstDispatch.get(0).getSeqId();

        // Simulate network failure: client never sent an ACK.
        // A second poll (e.g., after reconnect) must return the same message.
        List<PendingMessage> retryDispatch =
                store.pollDueMessages("driver-2", Instant.now(), 10);
        assertThat(retryDispatch).hasSize(1);
        assertThat(retryDispatch.get(0).getSeqId())
                .as("Same message must be re-delivered after a network failure")
                .isEqualTo(seqId);
    }

    @Test
    void pruneAcked_removesMessages_onlyAfterExplicitAck() {
        long s1 = store.enqueue("driver-3", bytes("m1"), Duration.ofSeconds(60), 5);
        long s2 = store.enqueue("driver-3", bytes("m2"), Duration.ofSeconds(60), 4);
        long s3 = store.enqueue("driver-3", bytes("m3"), Duration.ofSeconds(60), 3);

        // Read all – none should be removed yet
        store.pollDueMessages("driver-3", Instant.now(), 10);
        assertThat(store.queueDepth("driver-3")).isEqualTo(3);

        // Client acks up to s2
        store.pruneAcked("driver-3", s2);

        // Only message with seqId > s2 (i.e. s3) should remain
        assertThat(store.queueDepth("driver-3"))
                .as("pruneAcked must remove messages up to the acked seqId")
                .isEqualTo(1);

        List<PendingMessage> remaining =
                store.pollDueMessages("driver-3", Instant.now(), 10);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getSeqId()).isEqualTo(s3);
    }

    @Test
    void expiredMessages_areRemovedEagerly_noAckNeeded() {
        // Expired messages cannot be delivered so they should be discarded without
        // requiring a ClientAck. Use a TTL of 1 ms and wait 20 ms to ensure expiry.
        store.enqueue("driver-4", bytes("old"), Duration.ofMillis(1), 5);

        Instant afterExpiry = Instant.now().plusMillis(20);

        List<PendingMessage> result =
                store.pollDueMessages("driver-4", afterExpiry, 10);

        assertThat(result).as("Expired messages must not be returned for delivery").isEmpty();
        assertThat(store.queueDepth("driver-4"))
                .as("Expired messages must be removed immediately without an ACK")
                .isEqualTo(0);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
