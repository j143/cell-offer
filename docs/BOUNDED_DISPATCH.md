# Bounded offer dispatch

Implementation prepared directly against main at e2c10b44a20ca332ba98ab0fc50720c653806de1.

## Architecture

REST -> per-cell bounded priority queue -> fair round-robin selection -> bounded dispatch ownership -> fixed RPC worker pool -> explicit terminal outcome.

The scheduler reserves capacity under the dispatcher lock before polling. Retries and rejected executor submissions retain the same owned Work. The outstanding-work bound includes executor-queued, running and retry-wait work. Worker calls never execute while holding a cell or dispatch lock. A round-robin cursor survives ticks so capacity shortages do not permanently favor low-sorted cell IDs. Cell-list sorting still costs O(number of registered cells log number of registered cells); cell cardinality eviction is not solved here.

## Settings (defaults)

- cell.dispatch.workers=4
- cell.dispatch.capacity=256 (must be >= workers)
- cell.dispatch.max-attempts=3 (includes first attempt)
- cell.dispatch.retry-base-ms=100
- cell.dispatch.retry-max-ms=2000 (upper validation limit: one day)
- cell.dispatch.retry-ambiguous=false
- cell.dispatch.shutdown-ms=5000
- push.gateway.send-deadline-ms=250
- Existing cell.offer.flush-interval-ms determines selection tick frequency.

Each visit selects one offer before rotating to another cell; repeated rounds can fill available capacity even with one hot cell. A tick stops after at most registered-cell-count plus capacity visits, or a full empty round. Many historical empty cells can still reduce utilization; active-cell indexing is a follow-up. This is bounded fairness, not a claim of optimal throughput. Concurrency=1 is a comparison setting, not a byte-for-byte rollback of the old eager-drain behavior.

## Outcomes and ownership

ACCEPTED means gateway acceptance, not driver ACK. REJECTED is explicit application rejection. EXPIRED is a terminal local deadline decision. INVALID covers request construction/serialization errors. AMBIGUOUS_FAILURE is a transport failure that may follow gateway acceptance; default behavior is no automatic retry. RETRYABLE_FAILURE is supported for transports that can prove non-acceptance; the current gRPC adapter conservatively classifies all StatusRuntimeException outcomes as ambiguous. No automatic safe-retry inference from UNAVAILABLE or DEADLINE_EXCEEDED is made.

Enable retry-ambiguous only after accepting duplicate risk or implementing/verifying gateway idempotency. offerId in JSON does not itself deduplicate requests. Pending work is volatile: kill -9 or process failure can lose it. No durable outbox is implemented. Shutdown explicitly counts unfinished dispatch work as abandoned; queued cell offers are still volatile. Running calls may finish remotely after abandonment. No exactly-once or crash-durability claim.

Original expiration is preserved; remaining TTL is calculated after serialization, checked at millisecond resolution and bounds the RPC deadline. Expiry can race transit; the existing relative-TTL protocol cannot strictly enforce absolute expiration on the remote server without an absolute deadline field or clock agreement.

Legacy flushDueOffers remains available for compatibility/tests, but the scheduler now uses pollForDispatch. Legacy flushed stats count transfer out of a cell, not gateway success. The old private sendToRamenSink helper/Javadoc is retained unchanged but no longer called; the active path uses Jackson serialization. Public enqueue now returns a boolean and the controller returns 429 on non-admission; rebuild all callers because this changes the Java method descriptor.

## Counters and reconciliation

Low-cardinality queue counters: cell_offer_admitted_total, cell_offer_rejected_total, cell_offer_duplicate_total, cell_offer_evicted_total, cell_offer_transferred_total, cell_offer_queue_expired_total.

Quiescent queue equation, with all counters from the same process epoch:

admitted = current queued + evicted + queue expired + transferred.

Attempts = admitted + rejected + duplicate (invalid calls throwing before admission are outside this equation).

Dispatch equation at quiescence:

selected = pending + in-flight + sum(cell_dispatch_terminal_total) + cell_dispatch_abandoned_total.

Retries and retry-exhausted are diagnostic counters, not additional terminal outcomes. Legacy flushDueOffers can transfer offers outside the dispatcher, so transferred equals selected only when the production pollForDispatch path is the sole consumer. Concurrent reads of several counters are not an atomic snapshot; check conservation after pausing/draining work.

cell_dispatch_oldest_age_seconds covers dispatch-owned offers only, not offers still in cell queues. The age index is maintained incrementally. Pending/in-flight gauges scan at most the configured dispatch capacity. Existing per-cell series remain high-cardinality; this increment does not remove them.

## Test and comparison

```bash
mvn -Dtest=BoundedOfferDispatcherTest test
mvn clean verify
git diff --check
```

Repository AGENTS.md documents intentionally failing gateway drill tests. Report their actual results separately rather than hiding failures. This patch has NOT been compiled or run in the authoring sandbox: Java, javac and Maven are unavailable.

After a successful build, run the app with cell.dispatch.workers=1, then 2, then 4, keeping heap/CPU, selection cadence, total dispatch capacity, gateway and workload fixed. Warm up before each measured run. Keep load generator resource headroom.

```bash
k6 run -e RPS=500 -e CELLS=100 -e DURATION=5m -e SUMMARY=workers-1.json loadtest/dispatch-plateau.js
```

Use a separate run for each worker setting and repeat baseline/candidate/baseline. Record admitted/s, gateway accepted/s, rejections, expiry, pending/in-flight, oldest dispatch age, RPC p99, selection-cycle latency, REST p99, GC and dropped iterations. Compare gateway acceptance and expiration, not only scheduler timing. A shorter selection tick does not mean a faster delivery.

## Known remaining work

No claimed benchmark gains. Real blocked-I/O/shutdown integration tests, a controllable delayed gRPC gateway fixture, whole-system oldest pending age, and gateway idempotency remain follow-ups. Existing admission configuration/input validation beyond positive capacity is unchanged. Do not call this durable or production-ready solely because the executor is bounded.
