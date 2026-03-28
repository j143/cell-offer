package j143.github.pushgateway;

import j143.github.pushgateway.model.PendingMessage;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-user priority message store with TTL and bounded queue size.
 *
 * <p>Each user gets their own {@link TreeSet} ordered by
 * {@code priority desc, createdAt asc, seqId asc}. When the queue reaches
 * {@code maxQueueSizePerUser} the lowest-priority (worst) message is dropped
 * to make room for a higher-priority incoming message; if the incoming message
 * has lower-or-equal priority it is dropped instead.
 */
@Component
public class MessageStore {

    private static final Logger log = LoggerFactory.getLogger(MessageStore.class);

    private final int maxQueueSizePerUser;
    private final MeterRegistry meterRegistry;

    /** userId → per-user state */
    private final ConcurrentHashMap<String, UserQueue> userQueues =
            new ConcurrentHashMap<>();

    public MessageStore(
            @Value("${push.gateway.max-queue-size-per-user:200}") int maxQueueSizePerUser,
            MeterRegistry meterRegistry) {
        this.maxQueueSizePerUser = maxQueueSizePerUser;
        this.meterRegistry       = meterRegistry;
    }

    // -----------------------------------------------------------------------

    /**
     * Enqueues a push message for {@code userId}.
     *
     * @param userId   target user
     * @param payload  raw bytes (e.g. JSON)
     * @param ttl      message time-to-live
     * @param priority higher = more urgent
     * @return the assigned sequence-id, or -1 if the message was dropped
     */
    public long enqueue(String userId, byte[] payload, Duration ttl, int priority) {
        UserQueue uq = userQueues.computeIfAbsent(userId, UserQueue::new);

        PendingMessage msg = new PendingMessage(
                uq.nextSeqId.incrementAndGet(), userId, payload, ttl, priority);

        synchronized (uq.queue) {
            if (uq.queue.size() < maxQueueSizePerUser) {
                uq.queue.add(msg);
                recordEnqueued(userId);
                return msg.getSeqId();
            }

            // Queue full – compare with the worst (last) message
            PendingMessage worst = uq.queue.last();
            if (msg.compareTo(worst) < 0) {
                // Incoming is better: evict worst, insert new
                uq.queue.remove(worst);
                uq.queue.add(msg);
                meterRegistry.counter("push_messages_dropped_total", "userId", userId).increment();
                recordEnqueued(userId);
                log.debug("[MessageStore] Evicted seqId={} for user={} (new priority {} > {})",
                        worst.getSeqId(), userId, priority, worst.getPriority());
                return msg.getSeqId();
            } else {
                // Incoming is worse: drop it
                meterRegistry.counter("push_messages_dropped_total", "userId", userId).increment();
                log.debug("[MessageStore] Dropped incoming message for user={} (queue full, low priority)", userId);
                return -1;
            }
        }
    }

    /**
     * Removes all messages with {@code seqId ≤ ackedSeqId} for the user.
     *
     * <p>Called when the server receives a {@code ClientAck}.
     */
    public void pruneAcked(String userId, long ackedSeqId) {
        UserQueue uq = userQueues.get(userId);
        if (uq == null) return;

        synchronized (uq.queue) {
            uq.queue.removeIf(m -> m.getSeqId() <= ackedSeqId);
        }
    }

    /**
     * Returns up to {@code limit} non-expired messages for delivery, removing
     * them from the queue. Expired messages are silently discarded and counted.
     *
     * @param userId target user
     * @param now    reference instant for TTL check
     * @param limit  maximum number of messages to return per call
     * @return ordered list of messages ready for delivery
     */
    public List<PendingMessage> pollDueMessages(String userId, Instant now, int limit) {
        UserQueue uq = userQueues.get(userId);
        if (uq == null) return List.of();

        List<PendingMessage> result = new ArrayList<>();

        synchronized (uq.queue) {
            Iterator<PendingMessage> it = uq.queue.iterator();
            while (it.hasNext() && result.size() < limit) {
                PendingMessage msg = it.next();
                it.remove();
                if (msg.isExpired(now)) {
                    meterRegistry.counter("push_messages_expired_total", "userId", userId).increment();
                    log.debug("[MessageStore] Expired seqId={} for user={}", msg.getSeqId(), userId);
                } else {
                    result.add(msg);
                }
            }
        }

        return result;
    }

    /** Returns the current number of queued messages for {@code userId}. */
    public int queueDepth(String userId) {
        UserQueue uq = userQueues.get(userId);
        if (uq == null) return 0;
        synchronized (uq.queue) {
            return uq.queue.size();
        }
    }

    // -----------------------------------------------------------------------

    private void recordEnqueued(String userId) {
        meterRegistry.counter("push_messages_enqueued_total", "userId", userId).increment();
        // Update gauge for queue depth
        meterRegistry.gauge("push_queue_depth", List.of(
                io.micrometer.core.instrument.Tag.of("userId", userId)),
                userQueues.get(userId),
                uq -> {
                    synchronized (uq.queue) { return uq.queue.size(); }
                });
    }

    // -----------------------------------------------------------------------

    /** Holds per-user state: sequence counter + ordered message set. */
    static class UserQueue {
        final String    userId;
        final AtomicLong nextSeqId = new AtomicLong(0);
        final TreeSet<PendingMessage> queue = new TreeSet<>();

        UserQueue(String userId) { this.userId = userId; }
    }
}
