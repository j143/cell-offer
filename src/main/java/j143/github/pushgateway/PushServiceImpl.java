package j143.github.pushgateway;

import j143.github.push.proto.ClientAck;
import j143.github.push.proto.ClientHello;
import j143.github.push.proto.ClientToServer;
import j143.github.push.proto.PushServiceGrpc;
import j143.github.push.proto.SendPushRequest;
import j143.github.push.proto.SendPushResponse;
import j143.github.push.proto.ServerControl;
import j143.github.push.proto.ServerToClient;
import j143.github.pushgateway.model.ClientSession;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * gRPC service implementation for {@code PushService}.
 *
 * <p>Handles two RPCs:
 * <ul>
 *   <li>{@link #connectStream} – bidirectional stream between a client and the gateway.
 *       Processes {@code ClientHello} (session registration), {@code ClientAck}
 *       (message acknowledgement), and {@code ClientHeartbeat} (liveness ping).</li>
 *   <li>{@link #sendPush} – unary RPC used by producers (cell-offer service)
 *       to deliver a push message for a specific user.</li>
 * </ul>
 */
@Component
public class PushServiceImpl extends PushServiceGrpc.PushServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PushServiceImpl.class);

    private final ConnectionManager connectionManager;
    private final MessageStore      messageStore;
    private final MeterRegistry     meterRegistry;
    private final long              defaultTtlMillis;

    public PushServiceImpl(
            ConnectionManager connectionManager,
            MessageStore messageStore,
            MeterRegistry meterRegistry,
            @Value("${push.gateway.default-ttl-ms:30000}") long defaultTtlMillis) {
        this.connectionManager = connectionManager;
        this.messageStore      = messageStore;
        this.meterRegistry     = meterRegistry;
        this.defaultTtlMillis  = defaultTtlMillis;
    }

    // -----------------------------------------------------------------------
    // ConnectStream – bidirectional streaming RPC
    // -----------------------------------------------------------------------

    @Override
    public StreamObserver<ClientToServer> connectStream(
            StreamObserver<ServerToClient> responseObserver) {

        return new StreamObserver<ClientToServer>() {

            /** Set on the first ClientHello; used for all subsequent messages. */
            private volatile String userId = null;

            @Override
            public void onNext(ClientToServer msg) {
                switch (msg.getPayloadCase()) {
                    case HELLO     -> handleHello(msg.getHello(), responseObserver);
                    case ACK       -> handleAck(msg.getAck());
                    case HEARTBEAT -> handleHeartbeat();
                    default        -> log.warn("[PushServiceImpl] Unknown message type from client");
                }
            }

            private void handleHello(ClientHello hello,
                                     StreamObserver<ServerToClient> outbound) {
                userId = hello.getUserId();
                String deviceId      = hello.getDeviceId();
                long   resumeSeqId   = hello.getLastAckSeqId();

                log.info("[PushServiceImpl] ClientHello: user={} device={} resumeSeqId={}",
                        userId, deviceId, resumeSeqId);

                ClientSession session = new ClientSession(
                        userId, deviceId, outbound, resumeSeqId);
                connectionManager.registerSession(userId, session);

                // Prune any already-acked messages from the queue
                if (resumeSeqId > 0) {
                    messageStore.pruneAcked(userId, resumeSeqId);
                }

                meterRegistry.gauge("active_sessions", connectionManager,
                        ConnectionManager::activeSessionCount);

                // Send a ServerControl RESUME_FROM_SEQ to acknowledge the resume point
                ServerToClient control = ServerToClient.newBuilder()
                        .setControl(ServerControl.newBuilder()
                                .setType(ServerControl.Type.RESUME_FROM_SEQ)
                                .setLastKnownSeqId(resumeSeqId)
                                .setMessage("Session established")
                                .build())
                        .build();
                outbound.onNext(control);
            }

            private void handleAck(ClientAck ack) {
                if (userId == null) return;
                long seqId = ack.getSeqId();
                log.debug("[PushServiceImpl] ClientAck: user={} seqId={}", userId, seqId);
                connectionManager.getSession(userId)
                        .ifPresent(s -> s.updateAck(seqId));
                messageStore.pruneAcked(userId, seqId);
            }

            private void handleHeartbeat() {
                if (userId == null) return;
                log.debug("[PushServiceImpl] ClientHeartbeat: user={}", userId);
                connectionManager.getSession(userId)
                        .ifPresent(ClientSession::touchHeartbeat);
                meterRegistry.counter("heartbeat_received_total", "userId", userId).increment();
            }

            @Override
            public void onError(Throwable t) {
                if (userId != null) {
                    log.warn("[PushServiceImpl] Stream error for user={}: {}", userId, t.getMessage());
                    connectionManager.removeSession(userId);
                }
                meterRegistry.counter("reconnects_total").increment();
            }

            @Override
            public void onCompleted() {
                if (userId != null) {
                    log.info("[PushServiceImpl] Stream completed for user={}", userId);
                    connectionManager.removeSession(userId);
                }
                responseObserver.onCompleted();
            }
        };
    }

    // -----------------------------------------------------------------------
    // SendPush – unary RPC for producers
    // -----------------------------------------------------------------------

    @Override
    public void sendPush(SendPushRequest request,
                         StreamObserver<SendPushResponse> responseObserver) {
        String userId  = request.getUserId();
        long   ttlMs   = request.getTtlMs() > 0 ? request.getTtlMs() : defaultTtlMillis;
        int    priority = request.getPriority();

        log.debug("[PushServiceImpl] SendPush: user={} priority={} ttlMs={}", userId, priority, ttlMs);

        long seqId = messageStore.enqueue(
                userId,
                request.getPayloadBytes().toByteArray(),
                Duration.ofMillis(ttlMs),
                priority);

        boolean success = seqId > 0;
        responseObserver.onNext(SendPushResponse.newBuilder()
                .setSuccess(success)
                .setReason(success ? "" : "Message dropped (queue full / low priority)")
                .build());
        responseObserver.onCompleted();
    }
}
