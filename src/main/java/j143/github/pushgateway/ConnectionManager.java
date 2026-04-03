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
     * <p><strong>BUG (Session Replacement Race – Invariant 3):</strong>
     * The previous session's stream is no longer closed before the new session
     * is registered.  Between the {@code sessions.put()} and the (now-absent)
     * {@code previous.completeStream()} call there is a window during which:
     * <ul>
     *   <li>The dispatcher or heartbeat scheduler can still write to the old
     *       stream (it was not completed).</li>
     *   <li>The client on the old stream receives messages intended for the
     *       new session, potentially causing duplicate processing.</li>
     *   <li>In concurrent-hello scenarios two threads can each see {@code null}
     *       as the previous session and both register without cleaning up the
     *       other.</li>
     * </ul>
     * <em>Fix:</em> call {@code previous.completeStream()} immediately after the
     * atomic {@code sessions.put()} so the old stream is torn down before any
     * subsequent dispatch or heartbeat can reach it.
     */
    public void registerSession(String userId, ClientSession session) {
        ClientSession previous = sessions.put(userId, session);
        if (previous != null) {
            log.info("[ConnectionManager] Replacing existing session for user={}", userId);
            // BUG 5: completeStream() on the old session is intentionally omitted.
            // The old stream stays open and the dispatcher may continue writing to it,
            // causing the stale client to receive messages meant for the new session.
            // Fix: restore this call → previous.completeStream();
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
