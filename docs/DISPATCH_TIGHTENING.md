# Dispatch tightening: follow-up to BOUNDED_DISPATCH.md

This document supersedes the initial tick-only/historical-cell-scan and unused-helper descriptions in BOUNDED_DISPATCH.md. No benchmark gains or successful Maven runs are claimed by this commit.

## Scheduling

Worker completion invokes the existing bounded selector after releasing the state lock. Released capacity is replenished immediately without waiting for another 500ms scheduled tick. The periodic tick remains the idle-arrival/retry fallback. No extra scheduler, event executor or orchestration class is introduced. A newly arriving offer when the dispatcher is completely idle may still wait for the periodic fallback. Retry deadlines are serviced by completions or periodic ticks, not a dedicated timer.

Selection now navigates a ConcurrentSkipListSet of nonempty cell IDs using a rotating cursor. Empty-cell removal and activation occur under the same per-cell lock as queue mutation. No historical ID list is copied/sorted. Each tick bounds visits by active count plus capacity; each visit takes one live offer before moving to the next cell. Historical queue/stat/meter objects still persist: active indexing improves traversal, not total cell-memory bounds.

Capacity includes queued, running and retry-wait ownership exactly as before. Completion-triggered selection never performs RPCs. The dispatch lock still serializes state/selection; profiles should guide any further change rather than speculative queue sharding.

## Contract and workloads

The API requires ttlMillis > 0, otherwise returns 400 with reason ttlMillis_must_be_positive. Missing TTL, zero, negative, and ttlSeconds-only requests are rejected. Existing DTO unknown-field behavior is unchanged; a wrong-unit-only request cannot create a zero-TTL offer.

Mixed k6 workload now supplies ttlMillis=30000 by default, reports 202 admissions separately from 429 rejections and unexpected responses, and uses independent rejection-rate and unexpected-failure gates. 429 is expected by the transport classification but rejection rate must meet MAX_REJECTION_RATE (default 0.01). A run rejecting everything fails. Latency and dropped-iteration gates remain. The per-iteration random sleep was removed because the arrival-rate executor supplies pacing. Hot-cell probability is sampled independently per request rather than derived from each VU's local iteration number. Consequently older runs are not exact workload-equivalent baselines.

```bash
k6 run -e TTL_MS=30000 -e MAX_REJECTION_RATE=0.01 loadtest/k6-cell-offer-brutal.js
```

The old private formatted-JSON helper, dead fields and unused imports were removed from OfferFlusher. Its meaningful delivery explanation lives on BoundedOfferDispatcher; remaining Javadoc is retained/updated to describe selection rather than serial delivery. Flush-cycle timing now covers periodic selection only; it does not measure end-to-end completion or every completion-triggered pass.

## Reconciliation and age

Existing conservation equations still apply after pausing/draining traffic. Additional gauges:

- cell_offer_queued: incrementally maintained total cell backlog.
- cell_offer_active_cells: current active-index count.
- cell_offer_oldest_queued_age_seconds: age of oldest queued creation timestamp.
- cell_dispatch_oldest_age_seconds: oldest dispatch-owned age (existing).

Whole-service oldest pending age for one instance is max(queue age, dispatch age), not their sum:

```promql
max by (job, instance) ({__name__=~"cell_offer_oldest_queued_age_seconds|cell_dispatch_oldest_age_seconds"})
```

Age index is updated during queue mutations; scraping reads its first key rather than scanning all offers. The shared age-index lock adds an admission cost to measure. Gauge/counter multi-series reads and handoff between queue and dispatcher are not atomic snapshots. Brief transitions may be visible; verify conservation after quiescence.

Use rate(cell_offer_admitted_total[1m]) versus rate(cell_dispatch_terminal_total{outcome="accepted"}[1m]), with rejections/evictions/expiry and pending/in-flight plotted alongside. Gateway acceptance remains distinct from driver delivery. No idempotency or crash durability is added.

## Regression validation

```bash
mvn -Dtest=BoundedOfferDispatcherTest,DispatchContractTest test
mvn clean verify
git diff --check
```

The deterministic dispatcher test now requires completion alone to select the next queued cell, and drains priorities without extra ticks. Contract tests cover invalid TTL and distinct 202/429 outcomes. Active-index tests cover 1000 historical empty cells, reactivation, legacy flush and expiration. Real blocked-I/O tests and concurrent index races still require runtime validation; no tests were executed in the authoring environment because Java/Maven are unavailable. Compare worker counts 1/2/4 with the corrected workloads and identical resources before drawing capacity conclusions.
