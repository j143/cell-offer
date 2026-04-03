package j143.github.pushclient;

import j143.github.push.proto.ClientAck;
import j143.github.push.proto.ClientHeartbeat;
import j143.github.push.proto.ClientHello;
import j143.github.push.proto.ClientToServer;
import j143.github.push.proto.PushServiceGrpc;
import j143.github.push.proto.ServerControl;
import j143.github.push.proto.ServerHeartbeat;
import j143.github.push.proto.ServerPush;
import j143.github.push.proto.ServerToClient;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Simple gRPC client SDK for connecting to PushGateway.
 *
 * <p>Maintains a single bidirectional {@code ConnectStream}. On stream error it
 * reconnects with exponential back-off, resuming from the last acknowledged
 * {@code seqId}.
 *
 * <p>Usage:
 * <pre>{@code
 * PushGatewayClient client = new PushGatewayClient("localhost", 9090, "driver-1", "device-A");
 * client.onMessageReceived(payload -> System.out.println("Got: " + new String(payload)));
 * client.connect();
 * // ... later:
 * client.shutdown();
 * }</pre>
 */
public class PushGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PushGatewayClient.class);

    private static final long BASE_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS  = 32_000L;

    private final String host;
    private final int    port;
    private final String userId;
    private final String deviceId;

    private final AtomicLong lastAckSeqId = new AtomicLong(0);
    private final AtomicLong backoffMs    = new AtomicLong(BASE_BACKOFF_MS);

    private volatile boolean        shutdownRequested = false;
    private volatile ManagedChannel channel;
    private volatile StreamObserver<ClientToServer> requestObserver;

    private Consumer<byte[]> messageHandler = payload ->
            log.info("[PushGatewayClient] received {} bytes", payload.length);

    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "push-client-reconnect");
                t.setDaemon(true);
                return t;
            });

    public PushGatewayClient(String host, int port, String userId, String deviceId) {
        this.host     = host;
        this.port     = port;
        this.userId   = userId;
        this.deviceId = deviceId;
    }

    /** Registers a callback that is invoked whenever a push message is received. */
    public void onMessageReceived(Consumer<byte[]> handler) {
        this.messageHandler = handler;
    }

    // -----------------------------------------------------------------------
    // Connect / reconnect
    // -----------------------------------------------------------------------

    /**
     * Opens the gRPC channel and starts the bidirectional stream.
     * Safe to call multiple times (reconnect path).
     *
     * <p><strong>BUG (Reconnect Storm – Invariant 5):</strong>
     * {@code backoffMs} is reset to {@link #BASE_BACKOFF_MS} at the start of
     * every {@code connect()} call. Because a failed dial attempt delivers an
     * instantaneous {@code onError("Connection refused")}, the sequence is:
     * <ol>
     *   <li>{@code connect()} resets backoff to 1 000 ms.</li>
     *   <li>gRPC fires {@code onError} immediately.</li>
     *   <li>{@code scheduleReconnect()} reads the already-reset value (1 000 ms)
     *       and schedules the next attempt in exactly 1 000 ms.</li>
     *   <li>Repeat forever – backoff never grows beyond 1 000 ms.</li>
     * </ol>
     * During a server outage the client hammers the server at 1 req/s regardless
     * of how long the outage lasts, creating a reconnect storm that exhausts
     * server resources and prevents recovery.
     * <br>
     * <em>Fix:</em> remove {@code backoffMs.set(BASE_BACKOFF_MS)} from here.
     * The reset belongs only in {@code handleControl(RESUME_FROM_SEQ)}.
     */
    public synchronized void connect() {
        if (shutdownRequested) return;

        // BUG 6: backoff reset unconditionally on every connect() attempt.
        // This pins the reconnect delay at BASE_BACKOFF_MS (1 s) forever when
        // the server is unreachable. Remove this line to restore correct backoff.
        backoffMs.set(BASE_BACKOFF_MS);

        if (channel == null || channel.isShutdown() || channel.isTerminated()) {
            channel = ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .build();
        }

        PushServiceGrpc.PushServiceStub stub = PushServiceGrpc.newStub(channel);
        requestObserver = stub.connectStream(new ServerStreamHandler());

        // Send ClientHello to establish / resume the session
        requestObserver.onNext(ClientToServer.newBuilder()
                .setHello(ClientHello.newBuilder()
                        .setUserId(userId)
                        .setDeviceId(deviceId)
                        .setLastAckSeqId(lastAckSeqId.get())
                        .build())
                .build());

        log.info("[PushGatewayClient] Connected: user={} device={} resumeSeqId={}",
                userId, deviceId, lastAckSeqId.get());
    }

    /** Gracefully disconnects and shuts down the client. */
    public void shutdown() throws InterruptedException {
        shutdownRequested = true;
        reconnectExecutor.shutdown();
        if (requestObserver != null) {
            try { requestObserver.onCompleted(); } catch (Exception ignored) {}
        }
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // -----------------------------------------------------------------------
    // Server-stream handler
    // -----------------------------------------------------------------------

    private class ServerStreamHandler implements StreamObserver<ServerToClient> {

        @Override
        public void onNext(ServerToClient msg) {
            switch (msg.getPayloadCase()) {
                case PUSH      -> handlePush(msg.getPush());
                case CONTROL   -> handleControl(msg.getControl());
                case HEARTBEAT -> handleHeartbeat(msg.getHeartbeat());
                default        -> log.warn("[PushGatewayClient] Unknown server message type");
            }
        }

        private void handlePush(ServerPush push) {
            long seqId = push.getSeqId();
            log.debug("[PushGatewayClient] user={} received seqId={} priority={}",
                    userId, seqId, push.getPriority());

            byte[] payload = push.getPayloadBytes().toByteArray();
            messageHandler.accept(payload);

            // Acknowledge
            lastAckSeqId.accumulateAndGet(seqId, Math::max);
            if (requestObserver != null) {
                requestObserver.onNext(ClientToServer.newBuilder()
                        .setAck(ClientAck.newBuilder().setSeqId(seqId).build())
                        .build());
            }
        }

        private void handleControl(ServerControl control) {
            log.info("[PushGatewayClient] ServerControl type={} lastKnownSeqId={} msg={}",
                    control.getType(), control.getLastKnownSeqId(), control.getMessage());
            // Reset exponential backoff only when the server confirms the session is
            // successfully established. This prevents the backoff counter from being
            // wiped on every connect() attempt, which would cause reconnect storms
            // when the server is unreachable (connection failures are instantaneous
            // and previously kept the delay stuck at BASE_BACKOFF_MS forever).
            if (control.getType() == ServerControl.Type.RESUME_FROM_SEQ) {
                backoffMs.set(BASE_BACKOFF_MS);
            }
        }

        private void handleHeartbeat(ServerHeartbeat ignored) {
            log.debug("[PushGatewayClient] ServerHeartbeat received, user={}", userId);
            if (requestObserver != null) {
                requestObserver.onNext(ClientToServer.newBuilder()
                        .setHeartbeat(ClientHeartbeat.getDefaultInstance())
                        .build());
            }
        }

        @Override
        public void onError(Throwable t) {
            log.warn("[PushGatewayClient] Stream error for user={}: {}", userId, t.getMessage());
            scheduleReconnect();
        }

        @Override
        public void onCompleted() {
            log.info("[PushGatewayClient] Stream completed for user={}, reconnecting...", userId);
            scheduleReconnect();
        }
    }

    // -----------------------------------------------------------------------

    private void scheduleReconnect() {
        if (shutdownRequested) return;

        long delay = backoffMs.getAndUpdate(cur -> Math.min(cur * 2, MAX_BACKOFF_MS));
        log.info("[PushGatewayClient] Reconnecting user={} in {} ms", userId, delay);
        reconnectExecutor.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    // -----------------------------------------------------------------------
    // Stand-alone driver simulation entry-point
    // -----------------------------------------------------------------------

    /**
     * Starts a simulated driver client that connects to PushGateway and prints
     * received offers.
     *
     * <p>Arguments: {@code <host> <port> <userId> <deviceId>}
     */
    public static void main(String[] args) throws InterruptedException {
        String host     = args.length > 0 ? args[0] : "localhost";
        int    port     = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
        String userId   = args.length > 2 ? args[2] : "driver-sim-1";
        String deviceId = args.length > 3 ? args[3] : "device-sim-1";

        PushGatewayClient client = new PushGatewayClient(host, port, userId, deviceId);
        client.onMessageReceived(payload ->
                System.out.printf("[SIM] user=%s received offer: %s%n",
                        userId, new String(payload)));
        client.connect();

        // Run until interrupted
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(latch::countDown));
        latch.await();
        client.shutdown();
    }
}
