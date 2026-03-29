package j143.github.pushgateway;

import j143.github.push.proto.ServerToClient;
import j143.github.pushgateway.model.ClientSession;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that maps {@code userId → ClientSession} for all currently
 * connected clients.
 *
 * <p>Only one active session per user is kept at any time. Registering a new
 * session for a user whose session already exists triggers a graceful close of
 * the old stream (the client will reconnect with its last-ack resume point).
 */
@Component
public class ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private final ConcurrentHashMap<String, ClientSession> sessions =
            new ConcurrentHashMap<>();

    /**
     * Registers {@code session} as the active session for {@code userId}.
     *
     * <p>If a previous session exists it is closed via {@code onCompleted()} so
     * the client side sees a clean stream end and reconnects.
     */
    public void registerSession(String userId, ClientSession session) {
        ClientSession previous = sessions.put(userId, session);
        if (previous != null) {
            log.info("[ConnectionManager] Replacing existing session for user={}", userId);
            try {
                previous.completeStream();
            } catch (Exception e) {
                log.debug("[ConnectionManager] Error closing previous session for user={}: {}",
                        userId, e.getMessage());
            }
        }
        log.info("[ConnectionManager] Registered session: user={} device={}",
                userId, session.getDeviceId());
    }

    /**
     * Returns the active session for {@code userId}, or empty if the user is
     * not connected.
     */
    public Optional<ClientSession> getSession(String userId) {
        return Optional.ofNullable(sessions.get(userId));
    }

    /**
     * Removes the session for {@code userId} (called on stream completion or error).
     */
    public void removeSession(String userId) {
        ClientSession removed = sessions.remove(userId);
        if (removed != null) {
            log.info("[ConnectionManager] Removed session: user={}", userId);
        }
    }

    /** Returns the number of currently active sessions. */
    public int activeSessionCount() {
        return sessions.size();
    }

    /** Returns a snapshot of all active user IDs. */
    public java.util.Set<String> getActiveUserIds() {
        return sessions.keySet();
    }
}
