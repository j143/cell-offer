# AGENTS.md – Design Direction & Project Notes

This file exists so that future AI agents (and human contributors) understand
the architectural invariants of the RAMEN-lite push transport layer and do not
reintroduce the distributed-systems flaws that were identified and fixed in the
initial review.

---

## What This Service Does

`cell-offer-queue-service` is a simplified implementation of Uber's **RAMEN**
(Reliable Async Messaging with Exactly-N delivery) push-dispatch layer, with an
embedded experimentation engine (**Citrus-lite**) for safe A/B/N testing of
configuration parameters.

It has three parts:

| Layer | Purpose |
|---|---|
| **Cell-offer queue** (`j143.github.celloffer`) | Per-cell bounded priority queue that enqueues `Offer` objects and flushes them to drivers |
| **Push gateway** (`j143.github.pushgateway` / `pushclient`) | gRPC bidirectional-stream transport that delivers messages from the server to connected mobile clients with at-least-once guarantees |
| **Citrus-lite** (`j143.github.citrus`) | Local, sub-millisecond feature-flagging and A/B/N testing engine with dynamic config reload and async exposure logging |

---

## Architectural Invariants (DO NOT VIOLATE)

### 1. MessageStore is an Immutable Ledger Until Acked

`MessageStore.pollDueMessages()` **must never remove live messages**. It is a
read-only view of the pending queue. A message is removed only when
`pruneAcked(userId, seqId)` is called after the server receives a `ClientAck`
from the device.

**Why:** Removing before ack produces "At-Most-Once" semantics. A network drop
between dispatch and client receipt causes permanent, unrecoverable message loss.
Only expired messages (whose TTL has elapsed) may be discarded eagerly because
they can never reach a client.

### 2. Micrometer Gauges Are Registered Once Per Entity

Gauge registration calls (e.g., `meterRegistry.gauge(...)`) must be placed in
one-time initialisation paths:

- `push_queue_depth` per user → inside `MessageStore.enqueue`'s
  `computeIfAbsent` callback, which runs exactly once when the first message
  arrives for a user.
- `active_sessions` → in the `PushServiceImpl` constructor.

**Why:** Registering a gauge inside a hot path (e.g., every `enqueue` call,
every `handleHello`) creates duplicate registrations. Micrometer's internal
registry holds weak references to each gauge; duplicates accumulate in the
registry's internal collections and eventually cause OOM.

### 3. gRPC StreamObserver Writes Are Serialized

`io.grpc.stub.StreamObserver` is **not thread-safe**. All writes to a client
stream must go through `ClientSession.writeToClient(ServerToClient)` or
`ClientSession.completeStream()`, which hold a `ReentrantLock`.

**Why:** The gRPC worker thread (calling `handleHello`) and the dispatcher
thread (calling `drainIfReady`) may both try to write to the same stream
simultaneously. Unserialized concurrent `onNext` calls throw
`IllegalStateException: call is closed` and kill the user's connection.

### 4. Dispatch Is Event-Driven via onReadyHandler

There must be **no `@Scheduled` method that polls all sessions** to push
messages (the old `dispatchAll()` antipattern). Message delivery happens via:

1. `Dispatcher.drainIfReady(userId)` – called from
   `ServerCallStreamObserver.setOnReadyHandler(...)` registered in
   `PushServiceImpl.handleHello`. gRPC fires this handler when the client's
   TCP window transitions from full → available.
2. `Dispatcher.drainIfReady(userId)` – called immediately after
   `MessageStore.enqueue` succeeds in `PushServiceImpl.sendPush`, for
   low-latency delivery when the stream is already open and idle.

**Why:** Polling every N ms ignores gRPC's backpressure mechanism. On slow
mobile connections the internal write buffers fill up; blindly calling
`onNext()` either blocks the scheduler thread or causes unbounded heap growth.
`setOnReadyHandler` is the standard gRPC backpressure API for server streaming.

### 5. Reconnect Backoff Resets Only on Confirmed Success

`PushGatewayClient.backoffMs` must **not** be reset in `connect()`. It is
reset only inside `handleControl()` when the server sends
`ServerControl.Type.RESUME_FROM_SEQ`, confirming the session is alive.

**Why:** Resetting in `connect()` means that on every failed dial attempt
(instantaneous `Connection refused`), the backoff counter goes back to 1 s.
The client never actually backs off, creating a reconnect storm that pins the
device CPU at 100 % and hammers the server during outages.

---

## Citrus-lite Invariants (DO NOT VIOLATE)

### C1. Stateful Isolation – Evaluate Once Per gRPC Stream

Parameters consumed by long-lived gRPC streams (ramen-lite) must be evaluated
**exactly once** during `ClientHello` and frozen in `ClientSession`. They must
**not** be re-read on subsequent messages or by a background thread during the
stream's lifetime.

```java
// PushServiceImpl.handleHello() – correct
EvaluationContext ctx = new EvaluationContext().set("DRIVER", userId);
int  retries   = experimentClient.getIntParam("ramen.retry.max-attempts",  3,      ctx);
long heartbeat = experimentClient.getLongParam("ramen.heartbeat.interval-ms", 10000L, ctx);
ClientSession session = new ClientSession(userId, deviceId, outbound, resumeSeqId,
                                          retries, heartbeat);
```

**Why:** Applying a new experiment assignment mid-stream would silently change
the delivery semantics of an in-flight message, violating the at-least-once
contract and making A/B analysis impossible (units would switch cohorts).

### C2. Zero-Tolerance Parameter-Key Conflict Resolution

If `experiments.yaml` contains two or more **enabled** experiments that both
declare overrides for the same parameter key, `ExperimentRegistry` must throw
`FatalConfigException` at load time. There is no "last-applied wins" fallback.

**Why:** "Last-applied wins" produces silent data corruption. Each experiment
assumes it has sole ownership of the parameters it overrides. Allowing two
experiments to compete over the same key makes it impossible to draw valid
statistical conclusions from either.

### C3. Deterministic Bucketing via MurmurHash3_32

Traffic splits must use `ExperimentHasher.getBucket(unitId, experimentName)`
which is based on `Hashing.murmur3_32_fixed(HASH_SEED)` from Guava. The seed
(`104729`) is fixed and must never change.

Never use `String.hashCode()`. It is not specified to be stable across JVM
versions and produces allocation bias.

**Why:** Changing the bucketing algorithm or seed mid-experiment reassigns units
to different cohorts, invalidating all previously collected exposure data.

### C4. Non-Blocking Exposure Logging

`ExperimentClient.getIntParam` / `getLongParam` must **never** block the
calling thread. Exposure events are written to a bounded
`ArrayBlockingQueue` via the non-blocking `offer()` method.

If the queue is full, the event is silently dropped and the
`AsyncExposureLogger.droppedExposures` counter is incremented. Monitor this
counter: a sustained non-zero value means the queue capacity or flush rate
needs tuning.

**Why:** `getParam()` is on the hot path of every request. A blocking call
inside a gRPC handler or scheduler will stall the entire thread pool and cause
cascading latency spikes.

---

## Component Map

```
PushGatewayClient  (mobile device SDK)
       │  ConnectStream (bidi gRPC)
       ▼
PushServiceImpl    (gRPC service impl)
  ├── ExperimentClient.getIntParam/getLongParam (ONCE per ClientHello)
  ├── registerSession → ConnectionManager
  ├── pruneAcked      → MessageStore
  ├── setOnReadyHandler → Dispatcher.drainIfReady()
  └── sendPush        → MessageStore.enqueue()
                          └── Dispatcher.drainIfReady()

Dispatcher
  ├── drainIfReady(userId)  ← event-driven, no scheduler
  ├── sendHeartbeats()      ← @Scheduled
  └── checkHeartbeatTimeouts() ← @Scheduled

MessageStore
  ├── enqueue()   – appends to per-user TreeSet; gauge registered once
  ├── pollDueMessages() – READ-ONLY for live messages; removes expired only
  └── pruneAcked() – only deletion path for live messages

ClientSession
  ├── writeToClient()  – serialized via ReentrantLock
  ├── completeStream() – serialized via ReentrantLock
  ├── resolvedRetryAttempts     – frozen at ClientHello from ExperimentClient
  └── resolvedHeartbeatIntervalMs – frozen at ClientHello from ExperimentClient

ExperimentClient  (Citrus-lite SDK)
  ├── getIntParam()  – AtomicReference hot path, < 1 ms
  ├── getLongParam() – AtomicReference hot path, < 1 ms
  └── updateConfig() ← ExperimentsConfigLoader (every 60 s)

ExperimentsConfigLoader
  ├── @PostConstruct reload()
  └── @Scheduled scheduledReload() (every citrus.config.poll-interval-ms)

AsyncExposureLogger
  ├── logExposure() – non-blocking offer() to ArrayBlockingQueue
  └── flushLoop()   – daemon thread, writes JSON to citrus.exposure logger
```

---

## Testing Conventions

Each architectural invariant has a dedicated test class:

| Invariant | Test class |
|---|---|
| At-least-once (no pre-ack removal) | `MessageStoreAtLeastOnceTest` |
| Gauge registered once per entity | `GaugeMeterRegistrationTest` |
| StreamObserver thread-safety | `ClientSessionThreadSafetyTest` |
| Event-driven dispatch, no polling loop | `DispatcherEventDrivenTest` |
| Reconnect backoff correctness | `PushGatewayClientReconnectTest` |
| MurmurHash3 determinism & distribution | `ExperimentHasherTest` |
| Non-blocking exposure drop contract | `AsyncExposureLoggerTest` |
| Parameter-key conflict detection | `ExperimentRegistryTest` |
| Hot-path evaluation & variant assignment | `ExperimentClientTest` |
| YAML loading & scheduled reload | `ExperimentsConfigLoaderTest` |

### Failure-mode drill tests (intentionally failing – fix to make them pass)

The following tests were introduced as **debugging drills**. They demonstrate
intentional failure modes injected into the production code. Each test documents
the bug, shows how to observe it, and describes the fix. Fix the production code
to make these tests pass.

| Failure mode | Failing test(s) | Bug location |
|---|---|---|
| Bug 1 – Slow consumer / backpressure | `DispatcherEventDrivenTest.drainIfReady_skipsDispatch_whenStreamIsNotReady` | `Dispatcher.drainIfReady` – `isReady()` guard removed |
| Bug 2 – Head-of-line blocking | `DispatcherEventDrivenTest.noScheduledDispatchAll_methodDoesNotExist` | `Dispatcher.dispatchAll()` – polling scheduler restored |
| Bug 3 – At-most-once delivery | `MessageStoreAtLeastOnceTest.*`, `MessageStoreTest.pollDueMessages_*`, `DuplicateDeliveryOnReconnectTest.goal_*` | `MessageStore.pollDueMessages` – `it.remove()` on live messages |
| Bug 5 – Session replacement race | `ConnectionManagerTest.registerSession_replacesExistingAndClosesOldStream` | `ConnectionManager.registerSession` – `completeStream()` removed |

### Additional demonstration tests (passing – show the bug IS present)

| Failure mode | Demonstration test | What it shows |
|---|---|---|
| Bug 1 – Backpressure | `SlowConsumerBackpressureTest` | Server sends to non-ready (slow) consumer |
| Bug 7 – High-cardinality metrics | `HighCardinalityMetricsTest` | seqId tag creates one timer series per message |
| Bug 3 – Reconnect/duplicate delivery | `DuplicateDeliveryOnReconnectTest.bugPresent_*` | Messages lost after network drop without ACK |
| Bug 3 – Queue starvation | `QueueStarvationTest` | High-priority offers continuously evict low-priority offers |

When adding new features to the push gateway or Citrus-lite, always add or
update the corresponding invariant test.

---

## Build & Run

```bash
# Run all tests
mvn test

# Start the service (HTTP on :8080, gRPC on :9090)
mvn spring-boot:run
```

Key configuration properties (in `application.properties`):

| Property | Default | Description |
|---|---|---|
| `push.gateway.max-queue-size-per-user` | `200` | Max pending messages per user |
| `push.gateway.dispatch-batch-size` | `10` | Messages drained per onReadyHandler call |
| `push.gateway.heartbeat-interval-ms` | `10000` | Heartbeat cadence (ms) |
| `push.gateway.heartbeat-timeout-ms` | `30000` | Session liveness timeout (ms) |
| `push.gateway.default-ttl-ms` | `30000` | Default message TTL (ms) |
| `citrus.config.path` | _(classpath)_ | Path to `experiments.yaml`; blank = classpath fallback |
| `citrus.config.poll-interval-ms` | `60000` | How often to re-read the config file (ms) |
