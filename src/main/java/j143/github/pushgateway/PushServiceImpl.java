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
import j143.github.citrus.EvaluationContext;
import j143.github.citrus.ExperimentClient;
import io.grpc.stub.ServerCallStreamObserver;
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
    private final Dispatcher        dispatcher;
    private final MeterRegistry     meterRegistry;
    private final ExperimentClient  experimentClient;
    private final long              defaultTtlMillis;
    private final int               defaultRetryAttempts;
    private final long              defaultHeartbeatIntervalMs;

    public PushServiceImpl(
            ConnectionManager connectionManager,
            MessageStore messageStore,
            Dispatcher dispatcher,
            MeterRegistry meterRegistry,
            ExperimentClient experimentClient,
            @Value("${push.gateway.default-ttl-ms:30000}") long defaultTtlMillis,
            @Value("${push.gateway.heartbeat-interval-ms:10000}") long defaultHeartbeatIntervalMs) {
        this.connectionManager          = connectionManager;
        this.messageStore               = messageStore;
        this.dispatcher                 = dispatcher;
        this.meterRegistry              = meterRegistry;
        this.experimentClient           = experimentClient;
        this.defaultTtlMillis           = defaultTtlMillis;
        this.defaultRetryAttempts       = 3;
        this.defaultHeartbeatIntervalMs = defaultHeartbeatIntervalMs;

        // Register the active_sessions gauge exactly once at construction time.
        // Previously this was registered inside handleHello() which runs on every
        // device connection, causing duplicate Micrometer gauge registrations and
        // eventual OOM inside the metrics registry.
        meterRegistry.gauge("active_sessions", connectionManager,
                ConnectionManager::activeSessionCount);
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

                // BUG 8 (Stale Experiment Config – Invariant C1):
                // Experiment parameters are now evaluated with the DEFAULT values and
                // not frozen from the experiment client at ClientHello time.  Instead,
                // handleAck() below re-reads the live registry on every ACK message,
                // allowing mid-stream parameter changes.
                //
                // This violates Citrus-lite Invariant C1 (Stateful Isolation): a config
                // push that changes retry.max-attempts mid-stream will silently alter
                // the delivery semantics of an in-flight session, breaking A/B analysis
                // (units can switch cohorts) and potentially violating at-least-once
                // guarantees if retry counts shrink below what is needed to re-deliver
                // an unacked message.
                //
                // Fix: restore the single evaluation at ClientHello:
                //   EvaluationContext ctx = new EvaluationContext().set("DRIVER", userId);
                //   int  retryAttempts       = experimentClient.getIntParam("ramen.retry.max-attempts", defaultRetryAttempts, ctx);
                //   long heartbeatIntervalMs = experimentClient.getLongParam("ramen.heartbeat.interval-ms", defaultHeartbeatIntervalMs, ctx);
                // and remove the re-evaluation from handleAck().

                ClientSession session = new ClientSession(
                        userId, deviceId, outbound, resumeSeqId,
                        defaultRetryAttempts, defaultHeartbeatIntervalMs);
                connectionManager.registerSession(userId, session);

                // Prune any already-acked messages from the queue
                if (resumeSeqId > 0) {
                    messageStore.pruneAcked(userId, resumeSeqId);
                }

                // Register an onReadyHandler so gRPC signals us when the client's
                // TCP window has space, replacing the old 50 ms polling loop.
                // This gives us native backpressure: we only attempt to send data
                // when the network is confirmed to be able to accept it.
                if (outbound instanceof ServerCallStreamObserver) {
                    @SuppressWarnings("unchecked")
                    ServerCallStreamObserver<ServerToClient> serverObs =
                            (ServerCallStreamObserver<ServerToClient>) outbound;
                    serverObs.setOnReadyHandler(() -> dispatcher.drainIfReady(userId));
                }

                // Send a ServerControl RESUME_FROM_SEQ to acknowledge the resume point
                ServerToClient control = ServerToClient.newBuilder()
                        .setControl(ServerControl.newBuilder()
                                .setType(ServerControl.Type.RESUME_FROM_SEQ)
                                .setLastKnownSeqId(resumeSeqId)
                                .setMessage("Session established")
                                .build())
                        .build();
                session.writeToClient(control);
            }

            private void handleAck(ClientAck ack) {
                if (userId == null) return;
                long seqId = ack.getSeqId();
                log.debug("[PushServiceImpl] ClientAck: user={} seqId={}", userId, seqId);
                connectionManager.getSession(userId)
                        .ifPresent(s -> {
                            s.updateAck(seqId);
                            // BUG 8 (continued): re-evaluate Citrus-lite experiment params
                            // on every ClientAck. If a config push occurs mid-stream, the
                            // next ACK silently reassigns the session to a new cohort.
                            // This makes per-session A/B assignment non-deterministic and
                            // produces inconsistent metrics between exposure log and outcome.
                            EvaluationContext ctx =
                                    new EvaluationContext().set("DRIVER", userId);
                            experimentClient.getIntParam(
                                    "ramen.retry.max-attempts", defaultRetryAttempts, ctx);
                            experimentClient.getLongParam(
                                    "ramen.heartbeat.interval-ms", defaultHeartbeatIntervalMs, ctx);
                        });
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

        // If the session is live and its TCP window is open, deliver immediately
        // instead of waiting for the next scheduled poll.
        if (seqId > 0) {
            dispatcher.drainIfReady(userId);
        }

        boolean success = seqId > 0;
        responseObserver.onNext(SendPushResponse.newBuilder()
                .setSuccess(success)
                .setReason(success ? "" : "Message dropped (queue full / low priority)")
                .build());
        responseObserver.onCompleted();
    }
}
