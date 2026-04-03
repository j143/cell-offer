package j143.github.pushgateway;

import io.grpc.stub.ServerCallStreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import j143.github.push.proto.ServerToClient;
import j143.github.pushgateway.model.ClientSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Demonstrates Bug 1: Slow Consumer Memory Blowup (Backpressure failure).
 *
 * <h3>What the bug is</h3>
 * <p>{@link Dispatcher#drainIfReady} no longer checks
 * {@code ServerCallStreamObserver.isReady()} before calling
 * {@code session.writeToClient()}. The server pushes messages blindly regardless
 * of whether the client's TCP window has room.
 *
 * <h3>What happens in production</h3>
 * <ul>
 *   <li>A slow mobile client (3G, sleeping app) stops reading from its socket.</li>
 *   <li>The kernel TCP receive buffer fills; the server-side TCP send buffer fills.</li>
 *   <li>gRPC's Netty layer begins queuing {@code onNext()} payloads in heap
 *       ({@code WriteBufferWaterMark}) because the transport is blocked.</li>
 *   <li>The server heap grows without bound for every message that cannot drain.</li>
 *   <li>With {@code drainAll()} also restored (Bug 2) the scheduler hammers this
 *       path every 50 ms, compounding the allocation rate.</li>
 * </ul>
 *
 * <h3>How to reproduce</h3>
 * <ol>
 *   <li>Connect a client that sleeps for 5 s after each message (simulates slow consumer).</li>
 *   <li>Enqueue 1 000 messages for that user.</li>
 *   <li>Watch {@code push_queue_depth} and heap usage via {@code /actuator/prometheus}
 *       and a heap profiler – both grow unchecked.</li>
 * </ol>
 *
 * <h3>Fix</h3>
 * <p>In {@link Dispatcher#drainIfReady}, restore the two guards:
 * <pre>{@code
 * if (!serverObs.isReady()) return;
 * // ... inside the loop:
 * if (!serverObs.isReady()) { break; }
 * }</pre>
 *
 * <p>This test verifies the <em>bug</em>: messages ARE sent even when the stream
 * is not ready. After the fix is applied the test below should be rewritten to
 * assert {@code sentMessages.isEmpty()}.
 */
class SlowConsumerBackpressureTest {

    private ConnectionManager   connectionManager;
    private MessageStore        messageStore;
    private SimpleMeterRegistry meterRegistry;
    private Dispatcher          dispatcher;

    @BeforeEach
    void setUp() {
        connectionManager = new ConnectionManager();
        meterRegistry     = new SimpleMeterRegistry();
        messageStore      = new MessageStore(500, meterRegistry);
        dispatcher        = new Dispatcher(connectionManager, messageStore, meterRegistry, 100, 30_000);
    }

    /**
     * BUG DEMONSTRATION: server dispatches to a non-ready (slow) consumer.
     *
     * <p>With the bug present, {@code drainIfReady} ignores {@code isReady=false}
     * and pushes all enqueued messages anyway.  This shows the server is not
     * respecting TCP backpressure.
     *
     * <p><strong>After the fix:</strong> {@code sentMessages} must be empty because
     * the guard {@code if (!serverObs.isReady()) return;} aborts the drain
     * immediately.
     */
    @Test
    void bugPresent_serverSendsToSlowConsumer_ignoringBackpressure() {
        AtomicInteger sentCount = new AtomicInteger(0);

        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<ServerToClient> slowObs =
                mock(ServerCallStreamObserver.class);
        // Simulate a slow consumer: isReady() always returns false (write buffer full)
        when(slowObs.isReady()).thenReturn(false);
        org.mockito.Mockito.doAnswer(inv -> {
            sentCount.incrementAndGet();
            return null;
        }).when(slowObs).onNext(org.mockito.ArgumentMatchers.any());

        ClientSession session = new ClientSession("slow-driver", "device-3g", slowObs, 0L);
        connectionManager.registerSession("slow-driver", session);

        // Enqueue 10 messages – all should be blocked by backpressure
        for (int i = 0; i < 10; i++) {
            messageStore.enqueue("slow-driver", ("msg-" + i).getBytes(), Duration.ofSeconds(60), 5);
        }

        dispatcher.drainIfReady("slow-driver");

        // BUG: sent > 0 even though isReady() returns false.
        // Fix will make this assertion flip: assertThat(sentCount.get()).isZero()
        assertThat(sentCount.get())
                .as("BUG: server pushed %d message(s) to a non-ready (slow) consumer. "
                        + "Fix: restore the isReady() guard in Dispatcher.drainIfReady()", sentCount.get())
                .isGreaterThan(0);
    }

    /**
     * BUG DEMONSTRATION: memory pressure accumulates across repeated drain calls.
     *
     * <p>Each drain call on a non-ready stream re-sends the same messages
     * (because Bug 3 now removes them on first read – so they're gone after one drain –
     * but this test shows the intent: a scheduler firing repeatedly amplifies
     * the buffer pressure). With Bug 2 ({@code drainAll}) also present, this
     * scenario fires every 50 ms.
     */
    @Test
    void bugPresent_repeatedDrainCallsToSlowConsumer_amplifyMemoryPressure() {
        List<ServerToClient> bufferSimulation = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<ServerToClient> slowObs =
                mock(ServerCallStreamObserver.class);
        when(slowObs.isReady()).thenReturn(false);
        org.mockito.Mockito.doAnswer(inv -> {
            bufferSimulation.add(inv.getArgument(0));
            return null;
        }).when(slowObs).onNext(org.mockito.ArgumentMatchers.any());

        ClientSession session = new ClientSession("slow-driver-2", "device-3g", slowObs, 0L);
        connectionManager.registerSession("slow-driver-2", session);

        for (int i = 0; i < 5; i++) {
            messageStore.enqueue("slow-driver-2", ("payload-" + i).getBytes(),
                    Duration.ofSeconds(60), 5);
        }

        // Simulate drainAll() firing three times in 150 ms
        dispatcher.drainIfReady("slow-driver-2");
        dispatcher.drainIfReady("slow-driver-2");
        dispatcher.drainIfReady("slow-driver-2");

        // BUG: each call bypasses isReady(), sending (or attempting to send) messages
        // into a full buffer. In a real gRPC channel this grows Netty's write buffer.
        assertThat(bufferSimulation.size())
                .as("BUG: %d write(s) forced into a non-ready stream across 3 drain calls. "
                        + "Fix: isReady() guard makes the first drain a no-op, subsequent calls too.",
                        bufferSimulation.size())
                .isPositive();
    }
}
