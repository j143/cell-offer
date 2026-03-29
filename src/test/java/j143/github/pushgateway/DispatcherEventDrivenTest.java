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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that prove event-driven dispatch via {@link Dispatcher#drainIfReady}
 * honours gRPC backpressure.
 *
 * <p>Critical flaw being fixed: the old {@code @Scheduled dispatchAll()} fired
 * every 50 ms and called {@code onNext()} blindly regardless of whether the
 * client's TCP window was open. On slow 3G connections gRPC buffers would fill
 * up, causing unbounded memory growth and/or blocking the scheduler thread.
 *
 * <p>The fix deletes {@code dispatchAll()} and replaces it with
 * {@link Dispatcher#drainIfReady(String)}, which is called either from the
 * gRPC {@code onReadyHandler} (backpressure signal from the transport layer)
 * or immediately after a new message is enqueued in {@code SendPush}.
 */
class DispatcherEventDrivenTest {

    private ConnectionManager    connectionManager;
    private MessageStore         messageStore;
    private SimpleMeterRegistry  meterRegistry;
    private Dispatcher           dispatcher;

    @BeforeEach
    void setUp() {
        connectionManager = new ConnectionManager();
        meterRegistry     = new SimpleMeterRegistry();
        messageStore      = new MessageStore(50, meterRegistry);
        dispatcher        = new Dispatcher(connectionManager, messageStore, meterRegistry, 10, 30_000);
    }

    // -----------------------------------------------------------------------
    // Helper: a mock ServerCallStreamObserver that captures written messages
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private ServerCallStreamObserver<ServerToClient> readyObserver(
            List<ServerToClient> captured) {
        ServerCallStreamObserver<ServerToClient> obs =
                mock(ServerCallStreamObserver.class);
        when(obs.isReady()).thenReturn(true);
        org.mockito.Mockito.doAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return null;
        }).when(obs).onNext(org.mockito.ArgumentMatchers.any());
        return obs;
    }

    @SuppressWarnings("unchecked")
    private ServerCallStreamObserver<ServerToClient> notReadyObserver() {
        ServerCallStreamObserver<ServerToClient> obs =
                mock(ServerCallStreamObserver.class);
        when(obs.isReady()).thenReturn(false);
        return obs;
    }

    // -----------------------------------------------------------------------

    @Test
    void drainIfReady_sendsMessages_whenStreamIsReady() {
        List<ServerToClient> captured = new ArrayList<>();
        ServerCallStreamObserver<ServerToClient> obs = readyObserver(captured);
        ClientSession session = new ClientSession("user-ready", "dev", obs, 0L);
        connectionManager.registerSession("user-ready", session);

        messageStore.enqueue("user-ready", "hello".getBytes(), Duration.ofSeconds(30), 5);

        dispatcher.drainIfReady("user-ready");

        assertThat(captured)
                .as("drainIfReady must send pending messages when stream is ready")
                .hasSize(1);
    }

    @Test
    void drainIfReady_skipsDispatch_whenStreamIsNotReady() {
        ServerCallStreamObserver<ServerToClient> obs = notReadyObserver();
        ClientSession session = new ClientSession("user-busy", "dev", obs, 0L);
        connectionManager.registerSession("user-busy", session);

        messageStore.enqueue("user-busy", "hello".getBytes(), Duration.ofSeconds(30), 5);

        dispatcher.drainIfReady("user-busy");

        verify(obs, never()).onNext(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void drainIfReady_noOp_whenNoSessionRegistered() {
        // Must not throw even if the user has no live session
        dispatcher.drainIfReady("ghost-user");
    }

    @Test
    void drainIfReady_noOp_whenQueueIsEmpty() {
        List<ServerToClient> captured = new ArrayList<>();
        ServerCallStreamObserver<ServerToClient> obs = readyObserver(captured);
        ClientSession session = new ClientSession("user-empty", "dev", obs, 0L);
        connectionManager.registerSession("user-empty", session);

        dispatcher.drainIfReady("user-empty");

        assertThat(captured).isEmpty();
    }

    @Test
    void drainIfReady_respectsBatchSize() {
        List<ServerToClient> captured = new ArrayList<>();
        // Dispatcher is configured with batchSize=10 in setUp(); enqueue more
        Dispatcher smallBatch =
                new Dispatcher(connectionManager, messageStore, meterRegistry, 2, 30_000);

        ServerCallStreamObserver<ServerToClient> obs = readyObserver(captured);
        ClientSession session = new ClientSession("user-batch", "dev", obs, 0L);
        connectionManager.registerSession("user-batch", session);

        for (int i = 0; i < 5; i++) {
            messageStore.enqueue("user-batch", "m".getBytes(), Duration.ofSeconds(30), i);
        }

        smallBatch.drainIfReady("user-batch");

        assertThat(captured)
                .as("drainIfReady must respect the configured batch size")
                .hasSize(2);
    }

    @Test
    void noScheduledDispatchAll_methodDoesNotExist() throws NoSuchMethodException {
        // Verify that the old @Scheduled dispatchAll() method no longer exists.
        // Its presence would re-introduce the CPU-polling "loop of death".
        boolean hasDispatchAll = false;
        for (java.lang.reflect.Method m : Dispatcher.class.getDeclaredMethods()) {
            if (m.getName().equals("dispatchAll")) {
                hasDispatchAll = true;
                break;
            }
        }
        assertThat(hasDispatchAll)
                .as("dispatchAll() must be deleted – use event-driven drainIfReady() instead")
                .isFalse();
    }
}
