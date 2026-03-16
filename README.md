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
          ▼  (every 300 ms)
      OfferFlusher  ──► logs / RAMEN-sink (simulated)
          │
          ▼
  Micrometer metrics  ──►  /actuator/prometheus
```

### Core components

| Class | Purpose |
|---|---|
| `Offer` | Immutable model with `offerId`, `cellId`, `driverId`, `riderId`, `priority`, `createdAt`, `ttl` |
| `CellQueueStats` | Thread-safe counters: `enqueued`, `flushed`, `expired`, `droppedOnEnqueue` |
| `CellOfferQueueManager` | Per-cell bounded `PriorityBlockingQueue`; evicts lowest-priority on overflow |
| `OfferFlusher` | `@Scheduled` component; flushes all cell queues every N ms |
| `DispatchController` | REST API: enqueue offers, read per-cell or global stats |

---

## Quick Start

### Requirements

* Java 17+
* Maven 3.9+

### Run

```bash
mvn spring-boot:run
```

The service starts on **port 8080**.

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