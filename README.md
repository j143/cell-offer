# Cell Offer Queue Service

A simplified **DispatchCellQueue** backend that simulates the per-cell priority
queuing, TTL management, and observability challenges described in Uber's
real-time push/dispatch infrastructure (RAMEN).

---

## Architecture

```
POST /cells/{cellId}/offers
          │
          ▼
  CellOfferQueueManager
  ┌──────────────────────────────────────────────────┐
  │  ConcurrentHashMap<cellId, PriorityBlockingQueue>│
  │  • bounded per-cell queue (default max 100)      │
  │  • highest-priority offer first                  │
  │  • evicts lowest-priority offer when at capacity │
  └──────────────────────────────────────────────────┘
          │
          ▼  (via OfferPushClient)
  PushServiceImpl (gRPC)
  ┌──────────────────────────────────────────────────┐
  │  MessageStore – immutable ledger until client ACK│
  │  ConnectionManager – active bidi-stream sessions │
  │  Dispatcher – event-driven drain via onReady     │
  └──────────────────────────────────────────────────┘
          │  ConnectStream (bidi gRPC stream)
          ▼
  PushGatewayClient (driver/rider mobile SDK)
          │
          ▼
  Micrometer metrics  ──►  /actuator/prometheus
```

### Core components

#### Cell-offer layer

| Class | Purpose |
|---|---|
| `Offer` | Immutable model with `offerId`, `cellId`, `driverId`, `riderId`, `priority`, `createdAt`, `ttl` |
| `CellQueueStats` | Thread-safe counters: `enqueued`, `flushed`, `expired`, `droppedOnEnqueue` |
| `CellOfferQueueManager` | Per-cell bounded `PriorityBlockingQueue`; evicts lowest-priority on overflow |
| `OfferFlusher` | `@Scheduled` component; flushes all cell queues every N ms |
| `DispatchController` | REST API: enqueue offers, read per-cell or global stats |

#### Push gateway layer

| Class | Purpose |
|---|---|
| `MessageStore` | Per-user priority `TreeSet` with TTL; **read-only during dispatch** – messages removed only on explicit `ClientAck` (at-least-once) |
| `ConnectionManager` | Registry of live `userId → ClientSession` mappings |
| `ClientSession` | Live gRPC stream + metadata; all writes serialized via `ReentrantLock` (thread-safe) |
| `PushServiceImpl` | gRPC service: handles `ConnectStream` (bidi) and `SendPush` (unary); registers `onReadyHandler` for backpressure-aware dispatch |
| `Dispatcher` | **Event-driven** message drain (`drainIfReady`); heartbeat sender; liveness timeout checker |
| `PushGatewayClient` | Mobile-side SDK; exponential backoff that resets only on confirmed server ack |

---

## Push Gateway Design – Key Invariants

### At-Least-Once Delivery
`MessageStore.pollDueMessages()` is intentionally **read-only** for live
messages. A message is removed from the store only when `pruneAcked()` is
called after receiving a `ClientAck`. This guarantees that a network drop
between dispatch and device receipt will result in a re-delivery, not
permanent message loss.

### Event-Driven Backpressure
There is no polling scheduler that pushes to all sessions every N ms. Instead,
`PushServiceImpl` casts the gRPC `responseObserver` to
`ServerCallStreamObserver` and registers an `onReadyHandler`. gRPC fires this
handler whenever the client's TCP window transitions from full → available. Only
then does the server drain the user's pending queue. This prevents unbounded
memory growth and CPU waste on slow mobile connections.

### Thread-Safe Stream Writes
`io.grpc.stub.StreamObserver` is not thread-safe. `ClientSession.writeToClient()`
and `ClientSession.completeStream()` serialize all writes with a
`ReentrantLock`, preventing `IllegalStateException` from concurrent gRPC worker
and scheduler threads.

### Gauge Registration
Micrometer gauges are registered exactly once per logical entity:
`push_queue_depth` is registered inside `computeIfAbsent` (first enqueue for a
user); `active_sessions` is registered in the `PushServiceImpl` constructor.
Registering in hot paths (every `enqueue`, every `handleHello`) creates
duplicate registrations and eventual OOM.

---

## Quick Start

### Requirements

* Java 17+
* Maven 3.9+

### Run

```bash
mvn spring-boot:run
```

The service starts on **port 8080** (HTTP) and **port 9090** (gRPC).

### Enqueue an offer

```bash
curl -s -X POST http://localhost:8080/cells/8928308280fffff/offers \
  -H 'Content-Type: application/json' \
  -d '{"driverId":"d-001","riderId":"r-001","priority":5,"ttlMillis":5000}'
```

Response:

```json
{"offerId":"<uuid>","cellId":"8928308280fffff"}
```

### Read cell stats

```bash
curl -s http://localhost:8080/cells/8928308280fffff/stats | jq .
```

```json
{
  "cellId": "8928308280fffff",
  "queueDepth": 1,
  "enqueued": 1,
  "flushed": 0,
  "expired": 0,
  "droppedOnEnqueue": 0
}
```

### Global stats (all cells)

```bash
curl -s http://localhost:8080/cells/stats | jq .
```

### Prometheus metrics

```bash
curl -s http://localhost:8080/actuator/prometheus | grep offer
```

Key metrics:

| Metric | Type | Description |
|---|---|---|
| `offer_queue_depth{cellId}` | Gauge | Current number of offers waiting in the queue |
| `offers_enqueued_total{cellId}` | Counter | Cumulative offers added |
| `offers_expired_total{cellId}` | Counter | Offers dropped because TTL elapsed |
| `offers_dropped_total{cellId}` | Counter | Offers rejected at enqueue time (queue full) |
| `push_queue_depth{userId}` | Gauge | Pending push messages per user |
| `push_messages_sent_total{userId}` | Counter | Messages delivered over gRPC |
| `active_sessions` | Gauge | Currently connected mobile clients |

### Actuator health

```bash
curl -s http://localhost:8080/actuator/health | jq .
```

---

## Configuration

Edit `src/main/resources/application.properties`:

| Property | Default | Description |
|---|---|---|
| `cell.offer.max-queue-size` | `100` | Max offers per cell before eviction kicks in |
| `cell.offer.flush-interval-ms` | `300` | How often (ms) the flush scheduler runs |
| `push.gateway.max-queue-size-per-user` | `200` | Max pending push messages per user |
| `push.gateway.dispatch-batch-size` | `10` | Messages drained per `onReadyHandler` call |
| `push.gateway.heartbeat-interval-ms` | `10000` | Server heartbeat cadence (ms) |
| `push.gateway.heartbeat-timeout-ms` | `30000` | Session liveness timeout (ms) |
| `push.gateway.default-ttl-ms` | `30000` | Default push message TTL (ms) |
| `server.port` | `8080` | HTTP server port |

---

## Load simulation

Use the following Python snippet to hammer a "hot" cell:

```python
import requests, random, time

HOT_CELL = "8928308280fffff"
URL = f"http://localhost:8080/cells/{HOT_CELL}/offers"

for i in range(1000):
    requests.post(URL, json={
        "driverId": f"d-{i}",
        "riderId": f"r-{random.randint(0, 50)}",
        "priority": random.randint(1, 10),
        "ttlMillis": random.choice([500, 1000, 5000])
    })
    time.sleep(0.001)  # 1000 req/s
```

Observe `droppedOnEnqueue` rising when the queue is saturated and TTL-expiry
kicking in when `ttlMillis` is very short.

---

## Running tests

```bash
mvn test
```