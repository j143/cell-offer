# Deep-Dive Interview Questions – Cell-Offer Queue Service

Serious technical questions designed to probe your understanding of distributed systems, concurrency, gRPC streaming, experimentation, and production reliability.

---

## 1. ARCHITECTURE & DESIGN DECISIONS

### Q1.1: Why a Three-Layer Architecture?
**The Question:**
Your system has cell-offer, push-gateway, and citrus-lite as separate layers. Why not combine them into a single monolith? What problem does separation solve?

**What They're Testing:**
- Do you understand **separation of concerns**?
- Can you articulate the **scaling bottleneck** (cell-offer is I/O, push-gateway is concurrency)?
- Do you grasp **independent deployment** and team ownership?

**Strong Answer Points:**
- Cell-offer queues are **per-cell bounded** (geographic); push-gateway is **per-user connection** (global scale)
- Cell-offer is CPU-light (priority queue ops); push-gateway is connection-heavy (10k+ concurrent sessions)
- Decoupling allows **independent scaling**: cell-offer can handle 1M offers/sec on small instances; push-gateway scales with active users
- **Failure isolation**: cell-offer crashes don't drop active gRPC streams
- **Team ownership**: offer team owns queuing SLA; platform team owns delivery guarantees

---

### Q1.2: Why TreeSet in MessageStore, Not LinkedList?
**The Question:**
You're storing pending push messages in a per-user TreeSet ordered by `seqId` and TTL. Why not a simple LinkedList? Performance implications?

**What They're Testing:**
- Do you understand **data structure trade-offs**?
- Can you explain **O(log n) insertion** vs **O(1) append**?
- Do you know about **batch cleanup** (expired messages)?

**Strong Answer Points:**
- **TreeSet**: O(log n) insert/remove; O(log n) pollDueMessages (find expired by TTL); allows **sorted traversal by seqId**
- **LinkedList**: O(1) append; O(n) scan to find expired; O(n) remove by seqId
- At high message volume (200 pending per user × 10k users = 2M messages), TreeSet's sorted scan for expired TTLs is critical
- **Batch cleanup**: Can iterate once, evict all expired in O(n log n); with LinkedList would be O(n²)
- **Resume from seqId**: TreeSet allows `subSet(fromSeqId, toSeqId)` in O(log n + result size); LinkedList requires O(n) scan

---

### Q1.3: What if You Remove the onReadyHandler Backpressure Check?
**The Question:**
In `PushServiceImpl.handleHello()`, you register `ServerCallStreamObserver.setOnReadyHandler()` and `Dispatcher.drainIfReady()` guards with `if (!isReady()) return`. What happens if you remove the `isReady()` check and just call `onNext()` unconditionally?

**What They're Testing:**
- Do you understand **gRPC backpressure** and **TCP flow control**?
- Can you predict **failure modes** under slow consumers?
- Do you know the difference between **at-least-once** and **unbounded memory**?

**Strong Answer Points:**
- **TCP window is full**: Mobile on 2G has ~16KB send buffer. If you call `onNext()` while buffer is full, the write **blocks the scheduler thread**
- **Scheduler thread starvation**: All other users' heartbeats, ACK processing stops
- **Unbounded heap growth**: Queue up 10k pending writes (each 1KB) = 10MB per blocked session
- **Cascading failure**: One slow user freezes the entire push-gateway
- **gRPC backpressure API**: `isReady()` returns false when TCP window is full; **only call onNext() when isReady() == true**
- **Event-driven pattern**: Wait for `onReadyHandler` callback instead of polling

---

### Q1.4: Why Freeze Experiment Parameters at ClientHello?
**The Question:**
Why do you evaluate `experimentClient.getIntParam()` once in `handleHello()` and freeze the value in `ClientSession`, rather than re-evaluating it on every `drainIfReady()` call?

**What They're Testing:**
- Do you understand **A/B testing semantics**?
- Can you explain **unit stability** in experiments?
- Do you grasp **at-least-once vs at-most-once** contract violations?

**Strong Answer Points:**
- **Unit stability**: A user's cohort assignment must be constant. If they switch from control → treatment mid-session, you can't tell **which cohort received the message**
- **Exposure data corruption**: Downstream analytics assumes "user X was in cohort Y for entire session". Changing mid-stream invalidates all exposure logs
- **Metrics bias**: If treatment has higher latency and users reconnect, switching them to control artificially inflates treatment's "retention"
- **A/B test invalidity**: The test no longer has stable treatment/control split
- **gRPC stream lifetime**: A single ClientHello lasts hours/days; parameters should be immutable for that session
- **Correct approach**: Freeze at session start; if experiment changes, it affects **new connections only**

---

## 2. CONCURRENCY & THREAD SAFETY

### Q2.1: Why ReentrantLock in ClientSession? Not AtomicReference?
**The Question:**
`ClientSession.writeToClient()` uses a `ReentrantLock` to serialize writes. Why not use `AtomicReference<StreamObserver>` and a compare-and-swap loop?

**What They're Testing:**
- Do you understand **gRPC StreamObserver's thread-safety contract**?
- Can you explain **why atomics aren't sufficient** for protocol ordering?
- Do you know the difference between **atomic updates** and **serialized operations**?

**Strong Answer Points:**
- **gRPC StreamObserver is not thread-safe**: The Javadoc explicitly states it must not be called concurrently
- **AtomicReference only protects the reference**: Doesn't protect the **sequence of onNext() calls**
- **Example failure**: Thread A calls `onNext(msg1)`, thread B calls `onNext(msg2)`. With AtomicReference, both see a valid observer, but gRPC's internal state machine gets corrupted. The client receives out-of-order or corrupted frames
- **ReentrantLock serializes the entire operation**: Only one thread can be inside `onNext()` at a time. Preserves message ordering
- **Two concurrent callers**: `handleHello` (gRPC worker thread) and `Dispatcher.drainIfReady()` (scheduler thread) both try to write
- **Correctness**: Lock ensures FIFO order of writes matches application semantics

---

### Q2.2: Race Condition Between registerSession and drainIfReady?
**The Question:**
In `ConnectionManager.registerSession()`, you replace an old `ClientSession` and call `oldSession.completeStream()`. Meanwhile, `Dispatcher.drainIfReady()` is writing to the old session's stream. What's the race? How do you prevent it?

**What They're Testing:**
- Can you identify **classic session replacement race conditions**?
- Do you understand **connection upgrade/downgrade** scenarios?
- Can you explain **synchronization boundaries**?

**Strong Answer Points:**
- **Race**: Old session is writing a message to the client when `completeStream()` is called, which invokes `onCompleted()` on a potentially in-flight write
- **Symptom**: `IllegalStateException: call is closed` in gRPC worker or scheduler thread
- **Prevention options**:
  1. **Lock at replacement boundary**: `synchronized(connectionManager)` during `registerSession` and `drainIfReady` lookup
  2. **Session versioning**: Include generation/epoch in `ClientSession`; discard messages from old generations
  3. **OnCompletedHandler**: Register a listener on old session's stream; unblock drainIfReady if stream is already closed
- **Test case**: `ConnectionManagerTest.registerSession_replacesExistingAndClosesOldStream` verifies old stream completes gracefully even if mid-dispatch

---

### Q2.3: What if MessageStore.enqueue is Called During pollDueMessages?
**The Question:**
`MessageStore.enqueue()` appends to a TreeSet; `pollDueMessages()` reads from it. Both can be called concurrently by different threads (enqueue from gRPC handler, poll from scheduler). What synchronization is needed?

**What They're Testing:**
- Do you understand **concurrent collection invariants**?
- Can you identify **when synchronization is necessary**?
- Do you know about **ConcurrentSkipListSet** vs **synchronized TreeSet**?

**Strong Answer Points:**
- **TreeSet is not thread-safe**: Concurrent modifications (enqueue) during iteration (pollDueMessages) can throw `ConcurrentModificationException` or corrupt the tree
- **Solution**: Use `ConcurrentSkipListSet` (lock-free, COW-friendly) or `synchronized(messageStore)` around critical sections
- **Trade-off**:
  - `ConcurrentSkipListSet`: Higher memory (40% overhead), better for **high contention**
  - `synchronized`: Lower memory, acceptable if **lock hold time is short**
- **Implementation**: `TreeSet<Message>` with explicit lock around enqueue/pollDueMessages
- **Why not just ConcurrentHashMap**: Messages are ordered by TTL; HashMap doesn't preserve order
- **Actual code**: Likely using `ConcurrentSkipListSet` or a `synchronized` block around the TreeSet

---

## 3. DELIVERY SEMANTICS & MESSAGE LOSS

### Q3.1: How Do You Guarantee At-Least-Once Delivery?
**The Question:**
Walk me through a scenario: client connects, receives message 5, network drops before sending ClientAck. Server thinks the message was lost. What happens?

**What They're Testing:**
- Do you understand **exactly-once vs at-least-once**?
- Can you trace a **reconnection flow** end-to-end?
- Do you know about **idempotency** vs **delivery guarantees**?

**Strong Answer Points:**
- **Scenario walkthrough**:
  1. Server sends `ServerPush(seqId=5)`
  2. Client receives, crashes before `ClientAck(seqId=5)`
  3. Server never receives the ack; message stays in `MessageStore`
  4. Heartbeat timeout triggers after 30s (no ack in `heartbeat_timeout_ms`)
  5. Session marked as dead; client reconnects with `ClientHello(last_ack_seq_id=4)`
  6. Server resumes from seqId=5; resends message 5
- **Key invariant**: `pollDueMessages()` **does NOT remove live messages** – only expired ones by TTL
- **Removal only on explicit ack**: `pruneAcked()` called when `ClientAck(seqId=5)` is received
- **At-most-once bug**: If you called `it.remove()` in `pollDueMessages()`, network drop → permanent loss
- **Exactly-once requires idempotency**: Server sends message 5 twice → client must dedupe by `seqId` in app layer
- **Test**: `MessageStoreAtLeastOnceTest`, `DuplicateDeliveryOnReconnectTest`

---

### Q3.2: What About Message Expiry and At-Least-Once?
**The Question:**
You said messages are only removed on ack. But you also have TTL (default 30s). What if a message expires before it's acked? Does the client still get it on reconnect?

**What They're Testing:**
- Do you understand **TTL interaction** with delivery semantics?
- Can you explain **pragmatic trade-offs** in distributed systems?
- Do you know when to violate "strict" guarantees?

**Strong Answer Points:**
- **Expired messages ARE discarded**: Unlike live messages (removed only on ack), **TTL-expired messages are eagerly removed** in `pollDueMessages()`
- **Rationale**: An expired message is **stale by definition**. The sender's SLA (e.g., "ride offer valid for 30s") is already violated. Retrying a stale message is useless
- **At-least-once weakening**: Technically, expired messages don't meet the "at-least-once" contract. But pragmatically:
  - Client had a 30s window to ack
  - If network was down for 31s, the message is useless anyway
  - Keeping expired messages wastes memory indefinitely
- **Trade-off**: "At-least-once for in-TTL period" instead of "at-least-once forever"
- **Configuration**: `push.gateway.default-ttl-ms=30000` is tunable for different SLAs

---

### Q3.3: Sequence Number Wraparound – How Do You Handle It?
**The Question:**
Sequence IDs are `int64` (seqId). Eventually they wrap around (after 2^63 messages). How does your resume logic handle `last_ack_seq_id` being larger than current `seqId` after wraparound?

**What They're Testing:**
- Do you think about **extreme edge cases**?
- Can you reason about **time-based vs sequence-based ordering**?
- Do you understand when edge cases **matter in production**?

**Strong Answer Points:**
- **Wraparound timeline**: 2^63 messages = ~292 billion messages per user
  - At 1000 msg/sec: ~9.3 million seconds = 108 days per user
  - Unlikely in practice, but possible for long-lived users
- **Current handling**: If `ClientHello(last_ack_seq_id=9223372036854775807)` comes in after wraparound, you'd compare it against current seqId (e.g., 10). The comparison `9223372036854775807 > 10` is true, so you'd send messages from seqId 10 onward
- **Bug**: The code likely **doesn't handle wraparound correctly**; this would be a subtle bug for stress-test scenarios
- **Fixes**:
  1. Use **modular arithmetic**: `(seqId - lastAckedSeqId) % (2^63) > 0`
  2. **Monotonic timestamp instead**: Use millisecond clock; no wraparound for centuries
  3. **Per-session counters**: Reset seqId to 0 on reconnect (loses resume ability but avoids wraparound)
- **Production likelihood**: If service runs for years without restart, this **will bite you**

---

## 4. gRPC STREAMING & BACKPRESSURE

### Q4.1: What is setOnReadyHandler and Why Is It Critical?
**The Question:**
Explain `ServerCallStreamObserver.setOnReadyHandler()` in 60 seconds. Why can't you just call `onNext()` whenever you have a message?

**What They're Testing:**
- Do you understand **gRPC's backpressure mechanism**?
- Can you explain **the difference between push and pull** models?
- Do you know about **TCP flow control**?

**Strong Answer Points:**
- **setOnReadyHandler purpose**: Callback fired when client's TCP receive window transitions from full → available
- **Why it matters**: Writing to a full TCP window either:
  1. **Blocks the current thread** (stalls scheduler, freezes all users)
  2. **Buffers unboundedly** (eventual OOM)
- **Without onReadyHandler**: You'd have to poll all users every 100ms and blindly call `onNext()`, hoping the write doesn't block
- **With onReadyHandler**: gRPC tells you "this client can receive now", so drain the queue
- **Event-driven vs polling**: onReadyHandler is **pull-based** (client signals ready); blind polling is **push-based** (ignore client readiness)
- **Mobile reality**: 2G connection: 16KB TCP window. One message (1KB). Can only send 16 messages before window is full. Need to wait for client to consume before sending more
- **Code location**: `PushServiceImpl.handleHello()` registers the handler once per session

---

### Q4.2: What Happens if isReady() Check is Removed?
**The Question:**
In `Dispatcher.drainIfReady()`, you have `if (!session.isReady()) return;`. What if you remove it?

**What They're Testing:**
- Can you trace the **failure mode** in detail?
- Do you understand **cascading failures** in thread pools?
- Can you measure the **impact**?

**Strong Answer Points:**
- **Symptom**: Server CPU pegs at 100%, latency spikes, clients timeout
- **Root cause**:
  1. You call `onNext()` while TCP window is full
  2. gRPC's internal NettyHandler tries to buffer the write
  3. Write queue grows (scheduler thread adds faster than Netty flushes)
  4. Netty applies backpressure to the scheduler thread; `onNext()` now **blocks**
  5. Scheduler thread pool exhausted waiting for flush to complete
  6. All users' heartbeats, drains, ACK processing stall
  7. Heartbeat timeout triggers; sessions die
  8. Thundering herd of reconnects
- **Measurement**: Run `SlowConsumerBackpressureTest` to see this in action
- **Fix**: Check `isReady()` before writing; return early if not ready

---

### Q4.3: Flow Control Between Cell-Offer and Push Gateway?
**The Question:**
Cell-offer flushes offers via `SendPush` RPC to the push-gateway. What if offers arrive faster than push-gateway can deliver? What's the backpressure mechanism?

**What They're Testing:**
- Do you think about **multi-layer backpressure**?
- Can you identify **bottlenecks** in the pipeline?
- Do you understand **queue depth monitoring**?

**Strong Answer Points:**
- **Cell-offer → push-gateway flow**:
  1. Cell-offer queue fills up (default max 100 per cell)
  2. When full, new offers **evict lowest-priority offers** (`PriorityBlockingQueue`)
  3. This is **explicit feedback**: offers are dropped when push-gateway can't keep up
- **No blocking backpressure**: Cell-offer doesn't block on `SendPush` RPC; it just tries and updates stats
- **Observability**: Monitor `offer_queue_depth` and `offers_dropped_total` gauges
  - High `queue_depth` + rising `dropped_total` = push-gateway is the bottleneck
  - Solution: Scale up push-gateway instances
- **Alternative design**: Could wait for `SendPushResponse.success==true` and exponential backoff; but that couples the two tiers
- **Chosen design**: Fire-and-forget with stats. Simpler, decoupled, push-gateway handles its own load

---

## 5. EXPERIMENTATION ENGINE (CITRUS-LITE)

### Q5.1: Why Reset Experiment Parameters at ClientHello, Not on Every Evaluation?
**The Question:**
You already answered this in Q1.4, but now prove it with a scenario. Design a test that fails if parameters are re-evaluated mid-stream.

**What They're Testing:**
- Can you **write a test** that catches the bug?
- Do you understand **exposure logging invariants**?
- Can you explain **data integrity** in analytics?

**Strong Answer Points:**
- **Test scenario**:
  ```
  1. User X connects in control group (retries=3)
  2. Experiment config reloads; user X now in treatment (retries=5)
  3. Message with retries=3 is in-flight to client
  4. Dispatcher re-evaluates, uses retries=5 on next resend
  5. Analytics sees: user X first tried 3x, then 5x
  6. Experiment comparison is broken (can't attribute outcome to cohort)
  ```
- **Test**: `ExperimentClientTest.parametersAreEvaluatedOncePerStream` or similar
- **Assertion**: 
  - Connect user X
  - Change experiment config mid-stream
  - Verify the old parameter value is still used for all subsequent messages
  - Exposure log shows user X in original cohort only

---

### Q5.2: Explain MurmurHash3 Bucketing – Why Not String.hashCode()?
**The Question:**
You use `Hashing.murmur3_32_fixed(seed)` to bucket users into experiments. Why not Java's `String.hashCode()`?

**What They're Testing:**
- Do you understand **hash function requirements** for experiments?
- Can you explain **stability across JVM versions**?
- Do you grasp the concept of **reproducibility in science**?

**Strong Answer Points:**
- **Java's String.hashCode() is not stable**:
  - Different JVM versions produce different hashes for the same string
  - In Java 8 vs 11 vs 17, `"userId-123".hashCode()` can differ
  - This means **users would switch cohorts** between JVM upgrades
- **MurmurHash3_32 is stable**:
  - Deterministic algorithm; same seed and input always produces same output
  - Seed (104729) is fixed and documented
  - Can be reimplemented in other languages; mobile SDKs use same bucketing
- **Experiment integrity**:
  - If user X switches cohorts between JVM versions, their exposure data is corrupted
  - Can't compare treatment vs control if units are reassigned mid-experiment
  - Statistical significance is lost
- **Distribution**: MurmurHash3 has better distribution properties than String.hashCode()
  - Lower collision rate
  - Better avalanche effect (small input change → large output change)

---

### Q5.3: Parameter Key Conflict Detection – Design the Test
**The Question:**
You claim that `ExperimentRegistry` throws `FatalConfigException` if two enabled experiments override the same parameter. Design a test and explain why this is necessary.

**What They're Testing:**
- Do you understand **configuration conflict resolution**?
- Can you predict **failure modes** in multi-experiment setups?
- Do you grasp **determinism** requirements?

**Strong Answer Points:**
- **Scenario**:
  ```yaml
  experiments:
    exp_a:
      enabled: true
      override:
        ramen.retry.max-attempts: 5
    exp_b:
      enabled: true
      override:
        ramen.retry.max-attempts: 3
  ```
  - If both are enabled, which value wins? 5 or 3?
- **"Last-applied wins" is wrong**:
  - YAML order is arbitrary; implementation details (HashMap iteration order)
  - Same config loaded on two servers might apply differently
  - Silent data corruption; no error in logs
- **Zero-tolerance approach**:
  - Throw `FatalConfigException` at load time
  - Config must be explicit; no ambiguity
  - Forces human to resolve (e.g., disable exp_b, or use different parameter names)
- **Test**:
  ```java
  @Test
  public void loadExperiments_throwsFatalConfigException_whenTwoEnabledExperimentsOverrideSameKey() {
    // Load YAML with exp_a and exp_b both overriding "ramen.retry.max-attempts"
    // Expect FatalConfigException
    // Verify exception message lists the conflicting keys
  }
  ```
- **Production impact**: Prevents silent experiment invalidation

---

### Q5.4: Non-Blocking Exposure Logging – What Happens if Queue is Full?
**The Question:**
`AsyncExposureLogger.logExposure()` uses `ArrayBlockingQueue.offer()` which is non-blocking. If the queue is full, the event is dropped. How do you detect and fix this?

**What They're Testing:**
- Do you understand **non-blocking APIs** and their trade-offs?
- Can you explain **observability** for silent failures?
- Do you know when to drop data vs. block?

**Strong Answer Points:**
- **Queue full scenario**:
  1. Exposure logger thread (daemon) is slow (disk I/O)
  2. Exposure events are generated fast (every getParam call)
  3. Queue fills up; `offer()` returns false
  4. Exposure events are silently dropped
  5. No exception; no alert; downstream analytics is incomplete
- **Why non-blocking**:
  - `getIntParam()` is on the hot path of every request
  - If `put()` (blocking) is used and queue is full, the gRPC handler thread blocks
  - All users' heartbeats/drains stall
  - Better to lose telemetry than lose service availability
- **Detection**:
  - Monitor gauge `citrus_exposures_dropped_total`
  - If rising steadily, the logger is slow or queue capacity is too small
- **Fixing**:
  1. **Increase queue capacity**: Tune `citrus.exposure-queue-size` (e.g., 10000 → 50000)
  2. **Increase flush rate**: Daemon thread flushes every 1s; reduce to 500ms
  3. **Batch exposures**: Don't log every getParam(); sample (e.g., 1 in 100)
  4. **Monitor SLA**: Accept that 0.1% of exposures can be dropped for that 1ms latency improvement
- **Test**: `AsyncExposureLoggerTest.logExposure_dropsEventSilentlyWhenQueueIsFull`

---

## 6. FAILURE MODES & DEBUGGING

### Q6.1: Client Sends ClientHello, Then Immediately Closes Stream
**The Question:**
A client connects with `ClientHello`, sends 0 messages, then closes the stream without `ClientAck`. What happens to any messages enqueued for that user during the brief connection?

**What They're Testing:**
- Do you understand **race conditions** in session lifecycle?
- Can you trace **message fate** through different scenarios?
- Do you know about **session cleanup**?

**Strong Answer Points:**
- **Timeline**:
  1. T=0: Client sends ClientHello; session created
  2. T=5ms: Producer sends `SendPush(userId=X)` via RPC
  3. T=6ms: Message enqueued in MessageStore
  4. T=7ms: Dispatcher.drainIfReady() fires; tries to write to ClientSession
  5. T=8ms: Client closes stream unexpectedly (app crash)
  6. T=9ms: ClientSession detects stream is closed; writeToClient() fails
  7. T=30s: Session times out (heartbeat_timeout_ms); session removed from ConnectionManager
  8. T=31s: Message is removed from MessageStore (either as stale TTL or explicit timeout)
- **Message fate**: If TTL is 30s and client was connected, the message **may or may not be delivered**. If client reconnects with same seqId, it gets the message again. If TTL expires, message is lost.
- **Why this is correct**: The at-least-once contract is "at least once **while connected**". A disconnected client can't receive messages. On reconnect, unacked messages are re-sent.

---

### Q6.2: Dispatcher Thread Crashes – What Happens to Messages?
**The Question:**
The Dispatcher thread is throwing an exception in `drainIfReady()`. The thread dies. What happens to pending messages and active sessions?

**What They're Testing:**
- Do you understand **thread pool supervision**?
- Can you explain **exception handling** in @Scheduled methods?
- Do you know about **circuit breakers** and **bulkheads**?

**Strong Answer Points:**
- **If Dispatcher thread is @Scheduled @Async**:
  - Single exception in one scheduled call **doesn't kill the thread**
  - Exception is logged; the thread goes back to sleep
  - Next scheduled interval (e.g., sendHeartbeats every 10s), it runs again
  - Missing one drainIfReady() call means **one batch of messages not sent this interval**
  - Not ideal, but acceptable; next onReadyHandler will drain them
- **If Dispatcher thread is one of a pool**:
  - ThreadPoolTaskScheduler has multiple threads
  - One thread exception doesn't affect others
  - Other users' heartbeats are unaffected
- **Potential bug**: If `@Scheduled checkHeartbeatTimeouts()` throws an exception, **no sessions are cleaned up**. Sessions accumulate; memory leaks
- **Production safeguard**: Wrap all @Scheduled methods in try-catch; log errors; never let them propagate
- **Better design**: Use **structured concurrency** (virtual threads / Project Loom) so thread crash is visible

---

### Q6.3: Memory Leak in MessageStore – Messages Stay Forever?
**The Question:**
You have a bug: messages are never removed from MessageStore (pruneAcked() is never called, or the client never sends ClientAck). What happens after a week?

**What They're Testing:**
- Can you identify **memory leak patterns** in distributed systems?
- Do you understand **garbage collection** and **heap growth**?
- Can you explain **production operational response**?

**Strong Answer Points:**
- **Scenario**:
  1. User has 100 pending messages in MessageStore
  2. Network issues; ClientAck is never received
  3. No pruneAcked() calls; messages stay in TreeSet
  4. New messages arrive; queue grows to 200, 300, ..., 2000 (exceeds max)
  5. Gauge `push_queue_depth{userId}` rises to 2000+
  6. Memory per user: 200 messages × 1KB each = 200KB per stuck user
  7. 1000 stuck users = 200MB heap
- **After a week**:
  - Thousands of stuck users
  - Heap fills up; GC pauses increase
  - Eventually `OutOfMemoryError`
  - Service crashes
- **Root cause**: Heartbeat timeout should have killed the session after 30s. If that's broken, sessions never cleanup
- **Observability**: Monitor `push_queue_depth` spike; alert if any user's queue > 500
- **Fix**:
  1. Investigate why heartbeat timeout didn't fire
  2. Check `heartbeat_timeout_total` counter; if zero, timeout logic is broken
  3. Force session expiry after max age (e.g., 12 hours) even if healthy-looking
- **Prevention**: Add a max-age guard: `if (now - sessionStartTime > 12h) completeStream()`

---

### Q6.4: High-Cardinality Metrics Explosion
**The Question:**
You're using `userId` and `cellId` as label dimensions in your Prometheus metrics. As the service grows to 10M users, what happens?

**What They're Testing:**
- Do you understand **metric cardinality** and its impact?
- Can you explain **Prometheus registry scalability**?
- Do you know about **metric design anti-patterns**?

**Strong Answer Points:**
- **High cardinality problem**:
  1. Every unique (userId, seqId) pair creates a new **time series**
  2. Micrometer registry holds all time series in memory
  3. 10M users × 200 messages per user = 2B time series
  4. Each time series has metadata (labels, type, description)
  5. Heap explodes; Micrometer registry can't allocate more
- **Gauge registration**:
  - If you register `push_queue_depth` per user inside the hot path (enqueue), you get duplicate registrations
  - Micrometer holds weak references to gauges; duplicates accumulate
  - Registry degrades to O(n²) lookup time
- **Solution**:
  - **Register gauges only once**: Use `computeIfAbsent(userId)` so gauge is created per user exactly once
  - **Limit label cardinality**: Use seqId **in the metric name**, not as a label
  - **Use summary instead of histogram**: Histograms create buckets; more cardinality
  - **Aggregate**: Instead of per-seqId timer, use per-user count/sum
- **Monitoring**: Check `SimpleRegistry.size()` (number of registered metrics); alert if > 100k

---

## 7. PERFORMANCE & OPTIMIZATION

### Q7.1: TreeSet Lock Contention – How Do You Mitigate It?
**The Question:**
MessageStore uses a single `TreeSet` per user, locked with `synchronized`. If you have 1000 msg/sec arriving for a user, and pollDueMessages runs every 100ms, won't the lock create contention?

**What They're Testing:**
- Do you understand **lock contention** and **profiling**?
- Can you reason about **Amdahl's law** and **parallelism**?
- Can you explain **trade-offs** (throughput vs latency)?

**Strong Answer Points:**
- **Contention analysis**:
  - enqueue(): O(log n) under lock; typically < 1 microsecond for 200 messages
  - pollDueMessages(): O(n log n) worst case (scan all, remove expired); ~ 1 millisecond
  - At 1000 msg/sec: enqueue is called 1000 times/sec = 1ms total lock time/sec
  - pollDueMessages every 100ms = 10 times/sec × 1ms = 10ms total lock time/sec
  - Total lock contention: ~11ms per second = 1.1% CPU
- **Not a bottleneck** at current scale (1000 msg/sec per user). Becomes a problem at 100k msg/sec per user
- **Optimization techniques**:
  1. **ConcurrentSkipListSet**: Lock-free, higher throughput; 40% memory overhead
  2. **Segment locks**: Split per user into multiple shards (e.g., hash userId % 16); each shard has its own lock
  3. **Ring buffer**: For single-producer, use a wait-free ring buffer (LMAX Disruptor)
  4. **Lazy expiry**: Instead of scanning on poll, mark messages as deleted and clean up lazily on access
- **Measurement**: Use JFR (Java Flight Recorder) to profile lock contention; only optimize if it shows up in top 5 slowest operations

---

### Q7.2: Heartbeat Overhead – How Often Should You Send?
**The Question:**
Heartbeats are sent every `heartbeat_interval_ms=10000`. At 100k concurrent users, that's 10 heartbeats/sec. What if you reduce it to 1000ms?

**What They're Testing:**
- Can you reason about **trade-offs** between latency and throughput?
- Do you understand **TCP/IP keep-alive** semantics?
- Can you explain **early detection** of dead connections?

**Strong Answer Points:**
- **10s heartbeat interval**:
  - 100k users = 10k heartbeats/sec
  - Each heartbeat is ~100 bytes
  - 1 MBps egress traffic
  - Dead connections detected after 30s (timeout)
- **1s heartbeat interval**:
  - 100k heartbeats/sec
  - 10 MBps egress traffic (10x increase)
  - Dead connections detected after 3s (10x faster)
- **Trade-off**:
  - Faster detection of network issues → shorter connection cleanup lag
  - But 10x more CPU and bandwidth
  - Is 3s vs 30s to detect network drop worth 10x cost?
- **Answer**: Depends on SLA
  - Ride-sharing: 30s is fine; ride offer is valid for 30-60s anyway
  - Real-time bidding: 3s might be needed
  - **Tuning**: Experiment with values; measure impact on tail latency and network usage

---

### Q7.3: Backpressure vs Throughput – The Batching Dilemma
**The Question:**
You have `dispatch-batch-size=10` in config. On each `onReadyHandler` call, you send 10 messages. What if you reduce it to 1? Increase to 100?

**What They're Testing:**
- Can you explain **batching** and its effects?
- Do you understand **latency vs throughput** trade-offs?
- Can you reason about **RTT** (round-trip time)?

**Strong Answer Points:**
- **dispatch-batch-size=1**:
  - Send 1 message, wait for onReadyHandler to fire again
  - Lower latency (message is sent ASAP)
  - More onReadyHandler calls; higher CPU
  - Worse throughput (fewer messages/sec)
  - Suitable for **low-latency applications** (financial trading)
- **dispatch-batch-size=100**:
  - Batch 100 messages before flushing
  - Better throughput (amortize onReadyHandler overhead)
  - Higher latency (tail messages wait for batch to fill)
  - Good for **high-throughput applications** (bulk notifications)
- **dispatch-batch-size=10** (current):
  - Balance between throughput and latency
  - Ride-sharing: 10 offers in 100ms is acceptable latency
- **Tuning**:
  - Measure p99 latency vs throughput
  - If p99 latency is unacceptable (e.g., > 500ms), reduce batch size
  - If throughput is bottleneck, increase batch size

---

## 8. TESTING & QUALITY ASSURANCE

### Q8.1: Design a Test for the At-Least-Once Invariant
**The Question:**
Design a test that verifies: if a client receives a message but crashes before acking, the message is re-sent on reconnect. Be specific about timing and state.

**What They're Testing:**
- Can you design **integration tests**?
- Do you understand **test determinism** and **flakiness**?
- Can you explain **test-driven bug finding**?

**Strong Answer Points:**
- **Test structure**:
  ```java
  @Test
  public void atleastOnce_MessageResentOnReconnect() throws InterruptedException {
    // 1. Start server
    // 2. Client A connects with ClientHello(userId=u1)
    // 3. Send message from producer
    // 4. Assert client A receives message
    // 5. Client A crashes (don't send ClientAck)
    // 6. Wait for heartbeat timeout (30s)
    // 7. Client A reconnects with ClientHello(userId=u1, last_ack_seq_id=0)
    // 8. Assert client A receives the message AGAIN
    // 9. Client A sends ClientAck
    // 10. Send a new message
    // 11. Assert client A receives the new message (only once)
  }
  ```
- **Assertions**:
  - After step 4: client received ServerPush(seqId=1)
  - After step 8: client received ServerPush(seqId=1) again
  - After step 11: client received ServerPush(seqId=2) exactly once
- **Flakiness mitigation**:
  - Use `CountDownLatch` to synchronize instead of `Thread.sleep()`
  - Assert on message count, not just receipt
  - Run test 100x to catch race conditions
- **Test in AGENTS.md**: `DuplicateDeliveryOnReconnectTest.goal_*`

---

### Q8.2: Design a Test for the Backpressure Invariant
**The Question:**
Design a test that verifies `Dispatcher.drainIfReady()` **does not send messages when the stream is not ready**. How do you simulate a slow consumer?

**What They're Testing:**
- Can you design **mock-based tests**?
- Do you understand **stream flow control**?
- Can you explain **test verification** for negative cases?

**Strong Answer Points:**
- **Test structure**:
  ```java
  @Test
  public void drainIfReady_skipsDispatch_whenStreamIsNotReady() {
    // 1. Create a mock ServerCallStreamObserver that returns isReady()=false
    // 2. Create ClientSession with the mock observer
    // 3. Enqueue 10 messages in MessageStore for userId
    // 4. Call Dispatcher.drainIfReady(userId)
    // 5. Assert onNext() was NEVER called
    // 6. Assert all 10 messages are still in MessageStore
  }
  ```
- **Mock setup**:
  ```java
  ServerCallStreamObserver<ServerToClient> mockObserver = mock(...);
  when(mockObserver.isReady()).thenReturn(false); // Simulate slow consumer
  ClientSession session = new ClientSession(..., mockObserver);
  ```
- **Verification**:
  - `verify(mockObserver, never()).onNext(any())`
  - MessageStore.pollDueMessages() should return empty
- **Test in AGENTS.md**: `DispatcherEventDrivenTest.drainIfReady_skipsDispatch_whenStreamIsNotReady`

---

### Q8.3: Chaos Testing – Simulate Network Partitions
**The Question:**
Design a chaos test that simulates a network partition (client and server can't communicate for 60 seconds). Verify the system recovers correctly.

**What They're Testing:**
- Do you understand **fault injection**?
- Can you design **resilience tests**?
- Do you explain **recovery guarantees**?

**Strong Answer Points:**
- **Test structure**:
  ```java
  @Test
  public void chaos_networkPartition_60seconds() throws Exception {
    // 1. Client A and B connected
    // 2. Send 10 messages to each
    // 3. Simulate partition: block all TCP reads/writes for 60s
    // 4. Clients should timeout after 30s and reconnect
    // 5. Network recovers; reconnect succeeds
    // 6. Clients should receive all 10 messages again
    // 7. Clients send ClientAck
    // 8. New messages should arrive without re-sends
  }
  ```
- **Partition simulation**:
  - Use a proxy (e.g., Toxiproxy) between client and server
  - Inject latency/loss to simulate poor network
  - Or, use `Thread.sleep()` in mock to block I/O
- **Verification**:
  - Message receive count: before partition (10) + after partition (10) = 20
  - No new unexpected messages after recovery
  - Metrics: `session_timeout_total` should increment twice (per client)
- **Advanced**: Use property-based testing (QuickCheck) to generate random partition patterns

---

## 9. OPERATIONAL & PRODUCTION READINESS

### Q9.1: Capacity Planning – How Many Users Per Instance?
**The Question:**
You want to support 100k concurrent users. Your current instance has 8 cores and 16GB heap. How many users per instance? What's the bottleneck?

**What They're Testing:**
- Do you understand **operational limits**?
- Can you estimate **resource usage** per entity?
- Do you explain **scaling strategies**?

**Strong Answer Points:**
- **Resource breakdown per user**:
  - Memory: 200 pending messages × 1KB per message = 200KB per user
  - 100k users × 200KB = 20GB (exceeds 16GB heap)
  - **Heap is the bottleneck** for this config
- **CPU per user**:
  - gRPC handling: ~ 0.1ms per message (small)
  - TreeSet insertion: O(log 200) = 8 ops, ~1 microsecond
  - At 1000 msg/sec per user: 1 microsecond × 1000 = 1ms CPU per user
  - 100k users × 1ms = 100 seconds of CPU per second (impossible on 8 cores)
  - **CPU is also a bottleneck**
- **Realistic limits**:
  - Heap: 16GB ÷ 200KB per user = 80k users
  - CPU: 8 cores × 1 second ÷ 1ms per user = 8000 users
  - **CPU is the tighter constraint**
  - Practical: 5k-8k concurrent users per 8-core instance
- **Scaling strategy**:
  1. Increase instance size (more cores, more heap) → 2x users per instance
  2. Increase message batch size → throughput per message decreases → more users
  3. Enable message compression → smaller heap footprint
  4. Horizontal scaling: 20 instances × 5k users = 100k users

---

### Q9.2: Monitoring – Design the Alert Strategy
**The Question:**
You're running this service in production with 10 instances. Design a monitoring and alerting strategy to catch problems before they impact users.

**What They're Testing:**
- Do you understand **observability**?
- Can you design **meaningful alerts**?
- Do you explain **on-call procedures**?

**Strong Answer Points:**
- **Key metrics to monitor**:
  1. **push_queue_depth{userId}** – alert if any user > 1000 messages (stuck)
  2. **active_sessions** – alert if drops > 10% suddenly (possible bug)
  3. **push_messages_sent_total** rate – alert if drops to 0 (dispatch broken)
  4. **offer_queue_depth{cellId}** – alert if any cell > 50 (offer flusher slow)
  5. **offers_dropped_total** rate – alert if rising (push-gateway overloaded)
  6. **session_timeout_total** rate – alert if > 1 per second (heartbeat broken)
  7. **citrus_exposures_dropped_total** rate – alert if > 0 (queue too small)
  8. **GC pause time** – alert if > 100ms (heap pressure)
  9. **gRPC error rate** – alert if > 0.1% (network or code issues)
  10. **Dispatcher thread exceptions** – alert on first one (logs should be checked)

- **Alert rules**:
  ```
  alert: HighPushQueueDepth
  expr: push_queue_depth > 1000
  for: 5m
  action: page on-call engineer

  alert: ActiveSessionsDrop
  expr: rate(active_sessions[-5m]) < -10000
  for: 1m
  action: page on-call engineer

  alert: DispatcherStalled
  expr: rate(push_messages_sent_total[-1m]) == 0
  for: 30s
  action: page on-call engineer immediately

  alert: ExposureLoggingLagging
  expr: rate(citrus_exposures_dropped_total[-1m]) > 100
  for: 2m
  action: email ops (non-urgent)
  ```

- **Runbook** (on-call response):
  1. Check `push_queue_depth` spike
  2. Find the affected userId; check their client logs
  3. Check network connectivity (might be a network partition)
  4. Check `active_sessions` count; if normal, it's a single-user issue
  5. Restart client connection; if issue resolves, it's a connection leak
  6. If `active_sessions` is low but `queue_depth` is high, dispatcher may be stuck

---

### Q9.3: Deployment & Rolling Updates
**The Question:**
You're deploying a new version that changes the message batch size from 10 to 20. How do you roll it out without dropping active sessions?

**What They're Testing:**
- Do you understand **graceful shutdown**?
- Can you explain **zero-downtime deployments**?
- Do you know about **backward compatibility**?

**Strong Answer Points:**
- **Blue-green deployment**:
  1. Start new version (green) alongside old (blue)
  2. Route new users to green; blue users stay on blue
  3. After blue sessions naturally close (typically < 1 minute per session), scale down blue
  4. No active session is forced to reconnect
- **Canary deployment**:
  1. Start 1-2 green instances with new version
  2. Route 5% of new users to green
  3. Monitor error rates; if all-clear, increase to 25%, then 100%
  4. Once all green, scale down blue
- **Graceful shutdown**:
  1. New version receives shutdown signal
  2. Stop accepting new connections (mark endpoint as `NotServing` in gRPC health check)
  3. Wait for existing sessions to close naturally (up to 60s)
  4. After timeout, force-close remaining sessions with `ServerControl.FULL_RESYNC`
  5. Clients will reconnect to healthy instances
- **Configuration compatibility**:
  - Message batch size change: safe, only affects throughput, not protocol
  - seqId format change: not safe; requires protocol version bump
  - Metric label change: safe, only affects monitoring

---

## 10. SYSTEM DESIGN EDGE CASES

### Q10.1: What if Two Instances Try to Assign a User to Different Cohorts?
**The Question:**
Multiple push-gateway instances receive ClientHello for the same user simultaneously. They both evaluate experiments independently and might assign different parameter values due to race conditions in `ExperimentClient.updateConfig()`. What happens?

**What They're Testing:**
- Do you understand **distributed consensus** issues?
- Can you explain **eventual consistency** in shared state?
- Do you know when to use **stateless design**?

**Strong Answer Points:**
- **Scenario**:
  1. Instance A receives ClientHello(userId=X) at T=0
  2. Instance B receives ClientHello(userId=X) at T=0
  3. Both read `ExperimentClient.getActiveExperiments()` concurrently
  4. Instance A loads config version 5; assigns to control group
  5. Instance B loads config version 6; assigns to treatment group
  6. Same user in different cohorts on different instances!
- **Why this happens**: `ExperimentRegistry` uses `AtomicReference<Config>` for hot-path reads; updates are not instantly visible to all threads
- **Impact**:
  - If user A connects to instance A, gets control parameters
  - User A's device crashes; reconnects to instance B
  - Gets treatment parameters; behavior changes mid-stream
  - Exposure data is corrupted (can't attribute outcome to cohort)
- **Solutions**:
  1. **User stickiness**: Route same user always to same instance (via consistent hashing in load balancer)
  2. **Synchronized config update**: Use a distributed lock (ZooKeeper, Consul) to ensure all instances load config in same order
  3. **Version tagging**: Tag each parameter value with config version; if user's parameters are old, force reconnect
  4. **Accept inconsistency**: If experiment duration is short (hours) and instances sync often, inconsistency is tolerable
- **Simplest fix**: Implement user stickiness in the load balancer

---

### Q10.2: Re-Ordering of ClientAck Messages
**The Question:**
A client sends two messages out-of-order: `ClientAck(seqId=5)`, then `ClientAck(seqId=3)`. The server processes them in order. Does `MessageStore.pruneAcked()` handle this correctly?

**What They're Testing:**
- Do you understand **idempotency**?
- Can you reason about **message reordering** in streams?
- Can you identify **subtle bugs** in cleanup logic?

**Strong Answer Points:**
- **Scenario**:
  1. Server sends messages 1, 2, 3, 4, 5
  2. Client receives out-of-order: 5, 3, 1, 2, 4 (TCP reordering is rare but possible)
  3. Client acks in receive order: ClientAck(5), ClientAck(3), ClientAck(1), ...
  4. Server processes: pruneAcked(userId, 5), pruneAcked(userId, 3), pruneAcked(userId, 1), ...
- **Correct behavior**:
  - pruneAcked(userId, 5) should remove messages 1-5 (all up to and including 5)
  - pruneAcked(userId, 3) should be a no-op (message 3 is already removed)
  - pruneAcked(userId, 1) should be a no-op
- **Buggy implementation**:
  ```java
  void pruneAcked(String userId, long seqId) {
    messageStore.get(userId).remove(seqId); // Only removes that one seqId
  }
  ```
  - This would leave messages 1-4 orphaned after ClientAck(5)
- **Correct implementation**:
  ```java
  void pruneAcked(String userId, long seqId) {
    TreeSet<Message> msgs = messageStore.get(userId);
    msgs.headSet(Message(seqId+1)).clear(); // Remove all up to seqId
  }
  ```
- **Test**: `MessageStoreTest.pruneAcked_HandlesOutOfOrderAcks`

---

### Q10.3: What if Producer Sends Same Offer to Push Gateway Twice?
**The Question:**
The cell-offer producer sends two identical offers (same offerId) within 100ms. The push-gateway receives both and enqueues both. What's the expected behavior?

**What They're Testing:**
- Do you understand **idempotency** at the API level?
- Can you explain **deduplication strategies**?
- Can you reason about **application-level semantics**?

**Strong Answer Points:**
- **Current behavior**: Both offers are enqueued; client receives both. This is wasteful but not incorrect
- **Ideal behavior**: Deduplicate by offerId; deliver only once
- **Why it happens**: `SendPush` RPC is idempotent from server perspective (enqueue is harmless), but producer may retry on timeout
- **Solutions**:
  1. **Server-side deduplication**: Use offerId as key; check if already enqueued
  2. **Client deduplication**: Mobile app dedupes offers by offerId before showing to driver
  3. **Producer idempotency key**: Use `idempotency-key` header (UUID); server caches results for 1 hour
- **Trade-off**: Deduplication adds complexity; often OK to let client dedupe (simpler)
- **For ride-sharing**: Driver typically sees offer once per cell; duplicate offers are ignored by app

---

## FAQ – Interview Deep Dives

### "What Was the Hardest Bug You Encountered?"
**Expected answer structure**:
1. Describe the bug (e.g., "Messages were silently lost on network reconnect")
2. Describe how you found it (e.g., "Chaos test with network partition")
3. Describe the root cause (e.g., "`pruneAcked()` wasn't called because ClientAck was dropped")
4. Describe the fix (e.g., "Ensured at-least-once by explicitly tracking acked seqIds")
5. Describe the lesson (e.g., "Always test failure modes; read-only polling isn't safe enough")

**Sample answer**: "The hardest bug was the at-least-once violation. Messages were being removed from MessageStore inside `pollDueMessages()` before the client acked them. If the network dropped, the client never got the message and couldn't retransmit on reconnect. I found it during chaos testing with a network partition. The fix was to move the removal to `pruneAcked()`, called only after explicit ClientAck. This taught me that read-only views are critical in distributed systems."

### "How Would You Scale This to 100M Users?"
**Expected answer structure**:
1. Identify current bottleneck (CPU per user for message processing)
2. Propose scaling dimensions (horizontal, vertical, caching)
3. Discuss trade-offs (complexity, latency, cost)

**Sample answer**: "Current bottleneck is CPU on message dispatch. At 1ms per user to process messages, 8 cores can handle 8k users. To scale to 100M, I'd do:
1. Horizontal scaling: 12,500 instances × 8k users each
2. Batch message processing: Increase batch size from 10 to 100, reduce per-message CPU
3. Offload TTL cleanup: Use a background thread/service for expired message cleanup, not the hot path
4. Add caching: Cache active experiments for 5 minutes; reduces ExperimentClient QPS
5. Use virtual threads: Java 21 virtual threads reduce memory per user (1MB → 100KB), allowing 100k users per instance
With these changes: 1000 instances × 100k users = 100M."

### "How Would You Debug a Production Incident?"
**Expected answer structure**:
1. Gather symptoms (e.g., "Active sessions dropped 50%")
2. Check metrics (e.g., "Heartbeat timeout counter is rising")
3. Check logs (e.g., "No exceptions, but connections are closing")
4. Form hypothesis (e.g., "Network issue between push-gateway and clients")
5. Test hypothesis (e.g., "Check client logs for TCP errors")
6. Implement fix (e.g., "Reduce heartbeat interval from 10s to 5s for faster detection")
7. Post-mortem (e.g., "Add alert for session drop rate")

---

## Summary: What Interviewers Really Want to Know

1. **Do you understand the distributed systems trade-offs?** (at-least-once vs exactly-once, eventual consistency)
2. **Can you identify production bugs proactively?** (race conditions, memory leaks, contention)
3. **Do you know how to test failure modes?** (chaos testing, property-based testing)
4. **Can you reason about scalability?** (resource bottlenecks, Amdahl's law, capacity planning)
5. **Do you care about observability?** (metrics, alerts, runbooks)
6. **Can you communicate complex trade-offs?** (explain why, not just what)

These are senior/staff engineer expectations, not junior.

---

## References

- **AGENTS.md** – Architectural invariants and design decisions
- **ARCHITECTURE.md** – Full system diagram and component interactions
- **README.md** – Quick-start and configuration guide
- **Test classes** in `src/test/java/` – Concrete examples of each invariant test

