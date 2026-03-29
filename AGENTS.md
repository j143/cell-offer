# AGENTS.md – Design Direction & Project Notes

This file exists so that future AI agents (and human contributors) understand
the architectural invariants of the RAMEN-lite push transport layer and do not
reintroduce the distributed-systems flaws that were identified and fixed in the
initial review.

---

## What This Service Does

`cell-offer-queue-service` is a simplified implementation of Uber's **RAMEN**
(Reliable Async Messaging with Exactly-N delivery) push-dispatch layer.

It has two parts:

| Layer | Purpose |
|---|---|
| **Cell-offer queue** (`j143.github.celloffer`) | Per-cell bounded priority queue that enqueues `Offer` objects and flushes them to drivers |
| **Push gateway** (`j143.github.pushgateway` / `pushclient`) | gRPC bidirectional-stream transport that delivers messages from the server to connected mobile clients with at-least-once guarantees |

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

## Component Map

```
PushGatewayClient  (mobile device SDK)
       │  ConnectStream (bidi gRPC)
       ▼
PushServiceImpl    (gRPC service impl)
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
  └── completeStream() – serialized via ReentrantLock
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

When adding new features to the push gateway always add or update the
corresponding invariant test.

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
