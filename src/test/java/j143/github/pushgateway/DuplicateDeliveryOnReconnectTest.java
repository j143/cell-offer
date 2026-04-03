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
 * Demonstrates the Duplicate Delivery scenario that occurs when Bug 3
 * (At-Most-Once trap) is combined with client reconnect behaviour.
 *
 * <h3>The two failure modes in play</h3>
 * <ol>
 *   <li><strong>Bug 3 (At-Most-Once):</strong> {@link MessageStore#pollDueMessages}
 *       now removes messages immediately on read, before any {@code ClientAck} is
 *       received.  A network drop between dispatch and client receipt causes
 *       permanent message loss – the message is gone from the store and can never
 *       be re-delivered.</li>
 *   <li><strong>Reconnect gap:</strong> When the client reconnects it sends a
 *       {@code ClientHello} with {@code lastAckSeqId} from before the gap.  With
 *       the correct at-least-once implementation the server would still have the
 *       unacked messages and would re-deliver them.  With Bug 3 active, those
 *       messages are already gone – the reconnecting client silently loses them.</li>
 * </ol>
 *
 * <h3>How to observe both effects</h3>
 * <ol>
 *   <li>Enqueue several messages for a driver.</li>
 *   <li>Dispatch (simulate network send).</li>
 *   <li>Simulate network failure: no {@code ClientAck} arrives.</li>
 *   <li>Client reconnects with the old {@code lastAckSeqId}.</li>
 *   <li><em>Expected (correct):</em> messages with {@code seqId > lastAckSeqId}
 *       are still in the store and re-delivered.</li>
 *   <li><em>Actual (with Bug 3):</em> store is empty; messages are permanently
 *       lost.</li>
 * </ol>
 *
 * <h3>Fix</h3>
 * <p>In {@link MessageStore#pollDueMessages}, remove the {@code it.remove()}
 * call from the live-message branch.  Messages must stay in the store until
 * {@link MessageStore#pruneAcked} is called after receiving an explicit
 * {@code ClientAck}.
 *
 * <p>Additionally, ensure clients implement deduplication using the {@code seqId}
 * field so that legitimate at-least-once re-deliveries (after the fix) are
 * idempotent on the receiver side.
 */
class DuplicateDeliveryOnReconnectTest {

    private MessageStore store;

    @BeforeEach
    void setUp() {
        store = new MessageStore(100, new SimpleMeterRegistry());
    }

    /**
     * BUG 3 DEMONSTRATION: messages disappear after the first dispatch, even
     * without a {@code ClientAck}.
     *
     * <p>With the bug present the store removes messages in
     * {@code pollDueMessages()}, so a second poll (simulating a reconnect
     * re-delivery attempt) returns nothing – the messages are permanently lost.
     *
     * <p><strong>After the fix:</strong> the second poll must return the same
     * messages (the server re-delivers them because no ACK was received).
     */
    @Test
    void bugPresent_reconnectAfterNetworkDrop_messagesLostPermanently() {
        store.enqueue("driver-reconnect", "offer-A".getBytes(), Duration.ofSeconds(60), 5);
        store.enqueue("driver-reconnect", "offer-B".getBytes(), Duration.ofSeconds(60), 4);

        // First dispatch: simulate server sending over the wire
        List<PendingMessage> firstDispatch =
                store.pollDueMessages("driver-reconnect", Instant.now(), 10);
        assertThat(firstDispatch).as("First dispatch should return messages").hasSize(2);

        // Simulate network failure: client never sends ClientAck.
        // Client reconnects and server attempts re-delivery.
        List<PendingMessage> redelivery =
                store.pollDueMessages("driver-reconnect", Instant.now(), 10);

        // BUG 3: store is now empty – messages were removed on first poll without ACK.
        // Fix will make this return 2 messages (same ones), enabling at-least-once delivery.
        assertThat(redelivery)
                .as("BUG 3: store is empty after first poll even though no ClientAck was received. "
                        + "Messages are permanently lost on network failure. "
                        + "Fix: remove it.remove() from live-message branch in pollDueMessages().")
                .isEmpty();
    }

    /**
     * Demonstrates the correct at-least-once behaviour that must be restored by
     * the fix: messages remain in the store until explicitly acked.
     *
     * <p>This is a GOAL TEST: it currently FAILS with Bug 3 active.
     * Once {@code pollDueMessages()} is fixed (no {@code it.remove()} on live
     * messages), this test must pass.
     */
    @Test
    void goal_afterFix_messagesPersistedUntilExplicitAck() {
        store.enqueue("driver-fix-goal", "offer-X".getBytes(), Duration.ofSeconds(60), 5);

        // First poll: simulates dispatch to network
        List<PendingMessage> dispatched =
                store.pollDueMessages("driver-fix-goal", Instant.now(), 10);
        long seqId = dispatched.isEmpty() ? -1 : dispatched.get(0).getSeqId();

        // Simulate reconnect WITHOUT acking: second poll must still return the message
        List<PendingMessage> redelivery =
                store.pollDueMessages("driver-fix-goal", Instant.now(), 10);

        // This assertion FAILS with the bug. After fix, it must PASS.
        assertThat(redelivery)
                .as("GOAL (fails with bug): message must still be in store after first dispatch "
                        + "without ACK, so reconnect triggers re-delivery. "
                        + "Fix: remove it.remove() from live-message branch.")
                .isNotEmpty();

        // Only AFTER explicit ACK should the message be removed
        store.pruneAcked("driver-fix-goal", seqId);
        assertThat(store.queueDepth("driver-fix-goal"))
                .as("After pruneAcked the message must be gone")
                .isEqualTo(0);
    }

    /**
     * Demonstrates the interaction with {@link MessageStore#pruneAcked}:
     * even with Bug 3 active, calling pruneAcked on an already-empty store
     * is a no-op (doesn't throw).
     */
    @Test
    void pruneAcked_onEmptyStore_isNoOp() {
        // Bug 3 drains the store on first poll
        store.enqueue("driver-prune", "msg".getBytes(), Duration.ofSeconds(60), 5);
        store.pollDueMessages("driver-prune", Instant.now(), 10);
        assertThat(store.queueDepth("driver-prune")).isZero();

        // pruneAcked on an empty store must not throw
        store.pruneAcked("driver-prune", 999L);
        assertThat(store.queueDepth("driver-prune")).isZero();
    }
}
