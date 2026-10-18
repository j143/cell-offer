# Instrumentation

This change adds the minimum instrumentation needed to explain load-test results.

## Service metrics

| Metric | Type | Meaning |
|---|---|---|
| `cell_offer_enqueue_duration` | Timer | Time to admit an offer into its cell queue |
| `cell_offer_flush_cycle_duration` | Timer | Duration of one complete flush cycle across all known cells |
| `cell_offer_push_send_duration` | Timer | Duration of one PushGateway `SendPush` RPC |
| `cell_offer_push_send_failures_total` | Counter | gRPC exceptions, including deadline exceeded |
| `cell_offer_push_send_rejections_total` | Counter | PushGateway explicitly rejected the offer |

The PushGateway call now has a configurable deadline:

```properties
push.gateway.send-deadline-ms=250
```

## Load-generator signal

The k6 workload now fails when more than 1,000 scheduled iterations are dropped:

```javascript
dropped_iterations: ['count<1000']
```

This prevents a run from being interpreted as a successful 2,000-RPS test when k6 could not actually start all scheduled work. Dropped iterations represent scheduled scenario work that was not started, typically because no VU was available. [cite:10]

## Decision workflow

1. Compare `cell_offer_enqueue_duration` with REST p95/p99.
2. Compare `cell_offer_flush_cycle_duration` with the configured flush interval.
3. Compare `cell_offer_push_send_duration` with PushGateway capacity.
4. Use `cell_offer_push_send_failures_total` and `cell_offer_push_send_rejections_total` to identify downstream reliability issues.
5. Use `dropped_iterations` to determine whether the workload achieved its intended arrival rate. [cite:10]
