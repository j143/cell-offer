package j143.github.pushgateway.model;

import java.time.Duration;
import java.time.Instant;

/**
 * A message waiting to be delivered to a specific user.
 *
 * <p>Messages are stored in a per-user priority queue ordered by
 * {@code priority desc, createdAt asc}.
 */
public class PendingMessage implements Comparable<PendingMessage> {

    private final long seqId;
    private final String userId;
    private final byte[] payload;
    private final Instant createdAt;
    private final Duration ttl;
    private final int priority;

    public PendingMessage(long seqId, String userId, byte[] payload,
                          Duration ttl, int priority) {
        this.seqId     = seqId;
        this.userId    = userId;
        this.payload   = payload;
        this.createdAt = Instant.now();
        this.ttl       = ttl;
        this.priority  = priority;
    }

    public long    getSeqId()     { return seqId;     }
    public String  getUserId()    { return userId;     }
    public byte[]  getPayload()   { return payload;    }
    public Instant getCreatedAt() { return createdAt;  }
    public Duration getTtl()      { return ttl;        }
    public int     getPriority()  { return priority;   }

    /** Returns {@code true} if the message's TTL has elapsed relative to {@code now}. */
    public boolean isExpired(Instant now) {
        return now.isAfter(createdAt.plus(ttl));
    }

    /**
     * Natural order: higher priority first; ties broken by earlier createdAt,
     * then by seqId (to keep the ordering stable and prevent duplicate-dropping
     * in a TreeSet).
     */
    @Override
    public int compareTo(PendingMessage other) {
        int cmp = Integer.compare(other.priority, this.priority); // desc
        if (cmp != 0) return cmp;
        cmp = this.createdAt.compareTo(other.createdAt);         // asc
        if (cmp != 0) return cmp;
        return Long.compare(this.seqId, other.seqId);            // asc
    }

    @Override
    public String toString() {
        return "PendingMessage{seqId=" + seqId + ", userId='" + userId
                + "', priority=" + priority + ", createdAt=" + createdAt + '}';
    }
}
