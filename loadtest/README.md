# Brutal load and chaos testing

This directory contains the external workload used to validate Cell-Offer under hostile traffic patterns. It complements `CellOfferQueueBrutalLoadTest`, which validates in-process queue invariants.

## Run the k6 workload

```bash
k6 run \
  -e BASE_URL=http://localhost:8080 \
  -e START_RPS=50 \
  -e RAMP_RPS=500 \
  -e PEAK_RPS=2000 \
  -e SOAK_RPS=500 \
  loadtest/k6-cell-offer-brutal.js
```

The workload sends 80% of requests to 10 hot cells and 20% to 10,000 cold cells. It also sends 1% of traffic to 10 hot drivers, creating driver skew.

## Scenario matrix

| Scenario | Purpose | Expected result |
|---|---|---|
| Baseline | Establish a healthy operating envelope | p99 REST latency below 250 ms; no unexpected failures |
| Hot-cell stampede | Test per-cell synchronization and bounded capacity | No deadlock; queue depth stays at or below capacity; drops are explicitly counted |
| Cell explosion | Test map, gauge, and metrics cardinality | Heap and Prometheus scrape remain stable; no OOM |
| Slow PushGateway | Test scheduler behavior when `SendPush` is slow | Flush does not silently stall all cells; backlog is observable |
| PushGateway outage | Test failure handling and recovery | No silent offer loss; failures are counted and recoverable |
| Restart during flush | Test crash consistency | Offers are retried, dead-lettered, or deduplicated; none disappear silently |
| Driver skew | Test downstream fairness | Hot-driver traffic does not create unbounded hidden queues |
| Mixed chaos | Test cascading failure behavior | Graceful degradation with actionable metrics |

## Chaos injections

Use Toxiproxy or a service-mesh fault-injection rule in front of the PushGateway gRPC port.

| Injection | Values |
|---|---|
| Latency | 50 ms, 250 ms, 1 s, 5 s |
| gRPC errors | `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED` at 10%, 50%, 100% |
| Connection resets | 1% and 10% of connections |
| Bandwidth limit | 64 KB/s |
| Process kill | `kill -9` every 30-60 seconds during sustained ingress |

## Required pass/fail signals

- `offer_queue_depth` remains bounded and returns toward zero after ingress stops.
- `offers_enqueued_total` reconciles with flushed, expired, dropped, queued, failed, retried, and dead-lettered offers.
- No offer is silently lost after a PushGateway error or process restart.
- No duplicate delivery occurs unless explicitly deduplicated by `offerId`.
- JVM old-generation heap, thread count, open file descriptors, and Prometheus scrape duration remain stable during soak.
- End-to-end latency is measured from offer creation to client acknowledgement, not only REST enqueue latency.

## Known risks targeted by these tests

1. The queue is cleared before downstream delivery is confirmed, so a failed `SendPush` cannot currently be replayed.
2. `OfferPushClient` uses a blocking gRPC stub without a deadline, bounded concurrency, retry policy, or circuit breaker.
3. Cell IDs create unbounded queue, stats, gauge, and counter cardinality.
4. There is no per-driver admission control in addition to per-cell capacity.
5. Offer JSON is constructed with string formatting rather than a JSON serializer, making identifier injection a payload-integrity risk.
