package j143.github.pushgateway.model;

import j143.github.push.proto.ServerToClient;
import io.grpc.stub.StreamObserver;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Holds the live gRPC stream and metadata for one connected client.
 *
 * <p>A session is created when a client sends {@code ClientHello} over
 * {@code ConnectStream}. It is removed when the stream completes or errors.
 *
 * <p><strong>Thread safety:</strong> {@link StreamObserver} is not thread-safe.
 * {@link #writeToClient} and {@link #completeStream} serialize all writes to
 * the network wire with a {@link ReentrantLock}, preventing
 * {@code IllegalStateException: call is closed} when a gRPC worker thread and
 * the dispatcher thread fire concurrently.
 */
public class ClientSession {

    private final String userId;
    private final String deviceId;
    private final StreamObserver<ServerToClient> outbound;

    /** Serializes all writes to the gRPC outbound observer. */
    private final ReentrantLock streamLock = new ReentrantLock();

    /**
     * The highest sequence-id acknowledged by the client.
     * Messages with seqId ≤ this value may be pruned from the queue.
     */
    private final AtomicLong lastAckSeqId;

    /** Updated whenever a heartbeat (or any inbound message) is received. */
    private volatile Instant lastHeartbeat;

    /**
     * Experiment-resolved retry attempts for this session.
     * Evaluated once at {@code ClientHello} (RAMEN Rule #1: Stateful Isolation).
     * A new value takes effect only on reconnect; never changes mid-stream.
     */
    private final int resolvedRetryAttempts;

    /**
     * Experiment-resolved heartbeat interval (ms) for this session.
     * Evaluated once at {@code ClientHello} (RAMEN Rule #1: Stateful Isolation).
     */
    private final long resolvedHeartbeatIntervalMs;

    /**
     * Convenience constructor that uses default experiment values.
     * Preserves backward compatibility for callers that do not inject
     * an {@link j143.github.citrus.ExperimentClient}.
     */
    public ClientSession(String userId, String deviceId,
                         StreamObserver<ServerToClient> outbound,
                         long resumeSeqId) {
        this(userId, deviceId, outbound, resumeSeqId, 3, 10_000L);
    }

    /**
     * Full constructor that stores experiment-resolved session parameters.
     *
     * @param userId                    user identifier
     * @param deviceId                  device identifier
     * @param outbound                  gRPC response observer (not thread-safe; guarded by lock)
     * @param resumeSeqId               last acknowledged sequence id from the client
     * @param resolvedRetryAttempts     retry attempts resolved from experimentation engine
     * @param resolvedHeartbeatIntervalMs heartbeat interval (ms) resolved from experimentation engine
     */
    public ClientSession(String userId, String deviceId,
                         StreamObserver<ServerToClient> outbound,
                         long resumeSeqId,
                         int resolvedRetryAttempts,
                         long resolvedHeartbeatIntervalMs) {
        this.userId                    = userId;
        this.deviceId                  = deviceId;
        this.outbound                  = outbound;
        this.lastAckSeqId              = new AtomicLong(resumeSeqId);
        this.lastHeartbeat             = Instant.now();
        this.resolvedRetryAttempts     = resolvedRetryAttempts;
        this.resolvedHeartbeatIntervalMs = resolvedHeartbeatIntervalMs;
    }

    public String getUserId()   { return userId;  }
    public String getDeviceId() { return deviceId; }

    /** Returns the retry attempts resolved from the experimentation engine at session creation. */
    public int  getResolvedRetryAttempts()      { return resolvedRetryAttempts;      }
    /** Returns the heartbeat interval (ms) resolved from the experimentation engine at session creation. */
    public long getResolvedHeartbeatIntervalMs() { return resolvedHeartbeatIntervalMs; }

    /**
     * Returns the underlying {@link StreamObserver}.
     *
     * <p><strong>Important:</strong> callers that write to the stream must use
     * {@link #writeToClient} or {@link #completeStream} instead of calling
     * {@code onNext}/{@code onCompleted} directly, to preserve thread safety.
     * This accessor exists only for type inspection (e.g., casting to
     * {@link io.grpc.stub.ServerCallStreamObserver} to check {@code isReady()}).
     */
    public StreamObserver<ServerToClient> getOutbound() { return outbound; }

    /**
     * Thread-safe write of a single message to the client stream.
     * All callers (gRPC worker thread, dispatcher, heartbeat scheduler) must
     * use this method to prevent concurrent {@code onNext} calls.
     */
    public void writeToClient(ServerToClient msg) {
        streamLock.lock();
        try {
            outbound.onNext(msg);
        } finally {
            streamLock.unlock();
        }
    }

    /**
     * Thread-safe stream completion.
     * Serialized with {@link #writeToClient} to avoid calling
     * {@code onCompleted} concurrently with an in-progress {@code onNext}.
     */
    public void completeStream() {
        streamLock.lock();
        try {
            outbound.onCompleted();
        } finally {
            streamLock.unlock();
        }
    }

    public long getLastAckSeqId() { return lastAckSeqId.get(); }

    /**
     * Updates {@code lastAckSeqId} if {@code seqId} is greater than the
     * current value (monotonically increasing acks).
     */
    public void updateAck(long seqId) {
        lastAckSeqId.accumulateAndGet(seqId, Math::max);
    }

    public Instant getLastHeartbeat() { return lastHeartbeat; }

    public void touchHeartbeat() { this.lastHeartbeat = Instant.now(); }
}
