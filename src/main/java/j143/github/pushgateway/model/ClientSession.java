package j143.github.pushgateway.model;

import j143.github.push.proto.ServerToClient;
import io.grpc.stub.StreamObserver;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the live gRPC stream and metadata for one connected client.
 *
 * <p>A session is created when a client sends {@code ClientHello} over
 * {@code ConnectStream}. It is removed when the stream completes or errors.
 */
public class ClientSession {

    private final String userId;
    private final String deviceId;
    private final StreamObserver<ServerToClient> outbound;

    /**
     * The highest sequence-id acknowledged by the client.
     * Messages with seqId ≤ this value may be pruned from the queue.
     */
    private final AtomicLong lastAckSeqId;

    /** Updated whenever a heartbeat (or any inbound message) is received. */
    private volatile Instant lastHeartbeat;

    public ClientSession(String userId, String deviceId,
                         StreamObserver<ServerToClient> outbound,
                         long resumeSeqId) {
        this.userId        = userId;
        this.deviceId      = deviceId;
        this.outbound      = outbound;
        this.lastAckSeqId  = new AtomicLong(resumeSeqId);
        this.lastHeartbeat = Instant.now();
    }

    public String getUserId()   { return userId;  }
    public String getDeviceId() { return deviceId; }

    public StreamObserver<ServerToClient> getOutbound() { return outbound; }

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
