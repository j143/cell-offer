package j143.github.pushgateway;

import j143.github.push.proto.ServerHeartbeat;
import j143.github.push.proto.ServerPush;
import j143.github.push.proto.ServerToClient;
import j143.github.pushgateway.model.ClientSession;
import j143.github.pushgateway.model.PendingMessage;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.google.protobuf.ByteString;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Handles push message delivery and session liveness for all connected clients.
 *
 * <h3>Event-driven dispatch (Fix 4 – "Loop of Death")</h3>
 * <p>The previous implementation polled all sessions every 50 ms regardless of
 * TCP window state, which caused unbounded in-memory buffering and CPU waste on
 * slow mobile connections. The current implementation is fully event-driven:
 * <ul>
 *   <li>{@link #drainIfReady(String)} is called by {@link PushServiceImpl} as
 *       soon as a new message is enqueued (via {@code SendPush}) <em>and</em>
 *       from the gRPC {@code onReadyHandler} registered in
 *       {@code PushServiceImpl.handleHello}. gRPC's {@code onReadyHandler} fires
 *       whenever the client's TCP window transitions from full → available,
 *       providing native backpressure.</li>
 *   <li>The old {@code @Scheduled dispatchAll()} method has been deleted.</li>
 * </ul>
 *
 * <p>Heartbeat sending and session-liveness checking remain scheduled.
 */
@Component
public class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final ConnectionManager connectionManager;
    private final MessageStore      messageStore;
    private final MeterRegistry     meterRegistry;

    /** Maximum messages dispatched per user per drain call. */
    private final int dispatchBatchSize;

    /** How long to wait without a heartbeat before the connection is considered dead. */
    private final Duration heartbeatTimeout;

    public Dispatcher(
            ConnectionManager connectionManager,
            MessageStore messageStore,
            MeterRegistry meterRegistry,
            @Value("${push.gateway.dispatch-batch-size:10}") int dispatchBatchSize,
            @Value("${push.gateway.heartbeat-timeout-ms:30000}") long heartbeatTimeoutMs) {
        this.connectionManager = connectionManager;
        this.messageStore      = messageStore;
        this.meterRegistry     = meterRegistry;
        this.dispatchBatchSize = dispatchBatchSize;
        this.heartbeatTimeout  = Duration.ofMillis(heartbeatTimeoutMs);
    }

    // -----------------------------------------------------------------------
    // Event-driven drain
    // -----------------------------------------------------------------------

    /**
     * Drains pending messages for {@code userId} if the gRPC stream is ready.
     *
     * <p>Called from two sites:
     * <ol>
     *   <li>The {@code onReadyHandler} registered in
     *       {@code PushServiceImpl.handleHello} – fires when gRPC signals the
     *       client's TCP window has room.</li>
     *   <li>{@code PushServiceImpl.sendPush} – for low-latency delivery when a
     *       new message arrives while the stream is already open and ready.</li>
     * </ol>
     *
     * <p><strong>BUG (Slow Consumer / Backpressure – Invariant 4):</strong>
     * The {@code isReady()} back-pressure guard has been removed. The server now
     * pushes messages blindly regardless of whether the client's TCP window has
     * space. On a slow mobile connection the gRPC write buffer fills up without
     * bound, causing heap growth, GC pressure, and eventual OOM.
     * <br>
     * <em>Fix:</em> restore the early-return guard:
     * {@code if (!serverObs.isReady()) return;}
     */
    public void drainIfReady(String userId) {
        connectionManager.getSession(userId).ifPresent(session -> {
            StreamObserver<ServerToClient> obs = session.getOutbound();
            if (!(obs instanceof ServerCallStreamObserver)) {
                log.warn("[Dispatcher] drainIfReady: observer for user={} is not a "
                        + "ServerCallStreamObserver (type={}); skipping drain",
                        userId, obs.getClass().getSimpleName());
                return;
            }
            @SuppressWarnings("unchecked")
            ServerCallStreamObserver<ServerToClient> serverObs =
                    (ServerCallStreamObserver<ServerToClient>) obs;
            // BUG 1: isReady() check removed – server ignores TCP backpressure.
            // Restore: if (!serverObs.isReady()) return;

            List<PendingMessage> messages =
                    messageStore.pollDueMessages(userId, Instant.now(), dispatchBatchSize);

            for (PendingMessage msg : messages) {
                // BUG 1 (continued): mid-loop readiness check also removed,
                // so a window that fills up mid-batch is not respected either.
                // Restore: if (!serverObs.isReady()) { break; }
                Timer.Sample sample = Timer.start(meterRegistry);
                try {
                    ServerPush push = ServerPush.newBuilder()
                            .setSeqId(msg.getSeqId())
                            .setUserId(msg.getUserId())
                            .setPayloadBytes(ByteString.copyFrom(msg.getPayload()))
                            .setTtlMs(msg.getTtl().toMillis())
                            .setPriority(msg.getPriority())
                            .build();

                    session.writeToClient(ServerToClient.newBuilder().setPush(push).build());
                    meterRegistry.counter("push_messages_sent_total", "userId", userId).increment();
                    log.debug("[Dispatcher] Sent seqId={} to user={}", msg.getSeqId(), userId);
                } catch (Exception e) {
                    log.warn("[Dispatcher] Error sending to user={}: {}", userId, e.getMessage());
                    connectionManager.removeSession(userId);
                    break;
                } finally {
                    // BUG 7 (High-Cardinality Metrics): seqId added as a metric tag.
                    // Every unique seqId creates a new time-series in Prometheus/Micrometer.
                    // With thousands of messages per second this causes a metric-cardinality
                    // explosion that bloats the Prometheus scrape payload and can OOM the
                    // metrics registry.
                    // Fix: remove the "seqId" tag; use low-cardinality tags only (e.g. userId
                    // bucket, priority range) and log high-cardinality detail as structured log
                    // events instead.
                    sample.stop(meterRegistry.timer("push_delivery_latency",
                            "userId", userId,
                            "seqId", String.valueOf(msg.getSeqId())));
                }
            }
        });
    }

    // -----------------------------------------------------------------------
    // BUG 2: Head-of-line blocking via @Scheduled serial poll
    // -----------------------------------------------------------------------

    /**
     * <strong>BUG (Head-of-Line Blocking – Invariant 4):</strong>
     * This method re-introduces the deleted {@code @Scheduled dispatchAll()} anti-pattern.
     * It iterates every active session serially on a fixed 50 ms timer. A single
     * slow or stalled session (e.g. one with a full write buffer) causes
     * {@code drainIfReady} to block or spin, which delays delivery to <em>all</em>
     * other sessions for the remainder of the 50 ms window. Under a mixed
     * population of healthy and unhealthy clients this produces observable tail
     * latency for every healthy driver.
     *
     * <p>Additionally, because Bug 1 removed the {@code isReady()} guard,
     * {@code drainIfReady} blindly calls {@code onNext()} into full write buffers,
     * compounding the memory pressure.
     *
     * <p><em>Fix:</em> delete this method entirely and rely solely on the event-driven
     * {@code onReadyHandler} + {@code sendPush} triggers in {@link PushServiceImpl}.
     */
    @Scheduled(fixedDelayString = "${push.gateway.drain-all-interval-ms:50}")
    public void dispatchAll() {
        for (String userId : List.copyOf(connectionManager.getActiveUserIds())) {
            // BUG: serial iteration – one slow session delays all others.
            drainIfReady(userId);
        }
    }

    // -----------------------------------------------------------------------
    // Heartbeat sender
    // -----------------------------------------------------------------------

    /**
     * Sends a {@link ServerHeartbeat} to every active client every
     * {@code push.gateway.heartbeat-interval-ms} milliseconds (default 10 s).
     */
    @Scheduled(fixedDelayString = "${push.gateway.heartbeat-interval-ms:10000}")
    public void sendHeartbeats() {
        ServerToClient heartbeatMsg = ServerToClient.newBuilder()
                .setHeartbeat(ServerHeartbeat.getDefaultInstance())
                .build();

        for (String userId : List.copyOf(connectionManager.getActiveUserIds())) {
            connectionManager.getSession(userId).ifPresent(session -> {
                try {
                    session.writeToClient(heartbeatMsg);
                    log.debug("[Dispatcher] Sent heartbeat to user={}", userId);
                } catch (Exception e) {
                    log.warn("[Dispatcher] Heartbeat send failed for user={}: {}", userId, e.getMessage());
                }
            });
        }
    }

    // -----------------------------------------------------------------------
    // Heartbeat / liveness timeout check
    // -----------------------------------------------------------------------

    /**
     * Checks every {@code push.gateway.heartbeat-interval-ms} milliseconds
     * whether any session has exceeded the heartbeat timeout, and closes stale
     * sessions. Previously this was co-located in the polling dispatch loop;
     * extracting it here keeps the scheduler independent of message dispatch.
     */
    @Scheduled(fixedDelayString = "${push.gateway.heartbeat-interval-ms:10000}")
    public void checkHeartbeatTimeouts() {
        Instant now = Instant.now();
        for (String userId : List.copyOf(connectionManager.getActiveUserIds())) {
            connectionManager.getSession(userId).ifPresent(session -> {
                if (Duration.between(session.getLastHeartbeat(), now).compareTo(heartbeatTimeout) > 0) {
                    log.warn("[Dispatcher] Heartbeat timeout for user={} – closing stream", userId);
                    meterRegistry.counter("heartbeat_missed_total", "userId", userId).increment();
                    try {
                        session.completeStream();
                    } catch (Exception ignored) { /* stream may already be dead */ }
                    connectionManager.removeSession(userId);
                }
            });
        }
    }
}
