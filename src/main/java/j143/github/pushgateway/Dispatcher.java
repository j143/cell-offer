package j143.github.pushgateway;

import j143.github.push.proto.ServerHeartbeat;
import j143.github.push.proto.ServerPush;
import j143.github.push.proto.ServerToClient;
import j143.github.pushgateway.model.ClientSession;
import j143.github.pushgateway.model.PendingMessage;
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
import java.util.Optional;

/**
 * Periodically drains pending messages from {@link MessageStore} and writes
 * them to connected clients via their gRPC {@code ConnectStream} outbound observers.
 *
 * <p>The dispatcher also sends {@link ServerHeartbeat} pings and tears down
 * sessions whose last heartbeat exceeds the configured timeout.
 */
@Component
public class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final ConnectionManager connectionManager;
    private final MessageStore      messageStore;
    private final MeterRegistry     meterRegistry;

    /** Maximum messages dispatched per user per tick. */
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
    // Dispatch loop
    // -----------------------------------------------------------------------

    /**
     * Fires every {@code push.gateway.dispatch-interval-ms} milliseconds (default 50 ms).
     * Iterates over all active sessions and sends pending messages.
     */
    @Scheduled(fixedDelayString = "${push.gateway.dispatch-interval-ms:50}")
    public void dispatchAll() {
        Instant now = Instant.now();

        // Iterate a snapshot of session IDs to avoid holding locks across gRPC calls
        for (String userId : List.copyOf(connectionManager.getActiveUserIds())) {
            Optional<ClientSession> opt = connectionManager.getSession(userId);
            if (opt.isEmpty()) continue;
            ClientSession session = opt.get();

            // ---- heartbeat timeout check ----
            if (Duration.between(session.getLastHeartbeat(), now).compareTo(heartbeatTimeout) > 0) {
                log.warn("[Dispatcher] Heartbeat timeout for user={} – closing stream", userId);
                meterRegistry.counter("heartbeat_missed_total", "userId", userId).increment();
                try {
                    session.getOutbound().onCompleted();
                } catch (Exception ignored) { /* stream may already be dead */ }
                connectionManager.removeSession(userId);
                continue;
            }

            // ---- message dispatch ----
            List<PendingMessage> messages =
                    messageStore.pollDueMessages(userId, now, dispatchBatchSize);

            for (PendingMessage msg : messages) {
                Timer.Sample sample = Timer.start(meterRegistry);
                try {
                    ServerPush push = ServerPush.newBuilder()
                            .setSeqId(msg.getSeqId())
                            .setUserId(msg.getUserId())
                            .setPayloadBytes(ByteString.copyFrom(msg.getPayload()))
                            .setTtlMs(msg.getTtl().toMillis())
                            .setPriority(msg.getPriority())
                            .build();

                    session.getOutbound().onNext(
                            ServerToClient.newBuilder().setPush(push).build());

                    meterRegistry.counter("push_messages_sent_total", "userId", userId).increment();
                    log.debug("[Dispatcher] Sent seqId={} to user={}", msg.getSeqId(), userId);
                } catch (Exception e) {
                    log.warn("[Dispatcher] Error sending to user={}: {}", userId, e.getMessage());
                    connectionManager.removeSession(userId);
                    break;
                } finally {
                    sample.stop(meterRegistry.timer("push_delivery_latency", "userId", userId));
                }
            }
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
                    session.getOutbound().onNext(heartbeatMsg);
                    log.debug("[Dispatcher] Sent heartbeat to user={}", userId);
                } catch (Exception e) {
                    log.warn("[Dispatcher] Heartbeat send failed for user={}: {}", userId, e.getMessage());
                }
            });
        }
    }
}
