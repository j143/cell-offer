# Load-diagnosis decisions and experiments

Status: experiment plan, not executed evidence. Do not declare a bottleneck from a cumulative counter or whole-run average.

## First verify observability

Run `bash scripts/check-monitoring.sh`. Grafana must reach the metadata API from inside its container; host readiness alone is insufficient. Inspect target health and lastError. The script requires bash, curl, docker and a running app. Grafana credentials are local-development defaults; keep forwarded ports private.

Grafana uses http://host.docker.internal:9091; both services need the host-gateway mapping on Linux. Recreate Grafana after pulling this change:

```bash
make monitoring-up
docker compose -f deploy/docker-compose.monitoring.yml up -d --force-recreate grafana
bash scripts/check-monitoring.sh
```

If readiness still fails, collect `docker logs cell-offer-grafana --tail 50` and container network inspection; do not assume the host-port bypass fixes firewall/routing restrictions. Do not increase query timeouts to hide a TCP connection failure.

## Metric semantics and prerequisites

- Queue depth is a gauge. Timer count and sum are cumulative; plot rates and derived latency instead.
- Timer count means attempts/completed timed calls, not gateway acceptance or driver ACK.
- Histogram buckets are enabled by LoadDiagnosisMetricsConfiguration; rebuild/restart the app, verify buckets, and allow enough observations before using p99.
- The existing k6 accepted counter includes intentional rejections; it is NOT reliable admitted-offer accounting. Split 2xx, deliberate rejection and unexpected failure counts before making acceptance claims.
- Add distinct eviction, gateway success, retry, pending/in-flight and driver ACK accounting before claiming lossless delivery. The current implementation does not provide all these outcomes.
- Record exact commit, heap/CPU limits, generator resources, cell/driver distribution, priority, TTL and gateway configuration for every run.

## Queries

Enqueue attempts/s:

```promql
sum(rate(cell_offer_enqueue_duration_seconds_count[1m]))
```

Mean enqueue milliseconds:

```promql
1000 * sum(rate(cell_offer_enqueue_duration_seconds_sum[1m])) / sum(rate(cell_offer_enqueue_duration_seconds_count[1m]))
```

Enqueue p99 milliseconds (replace metric prefix for push/flush):

```promql
1000 * histogram_quantile(0.99, sum by (le) (rate(cell_offer_enqueue_duration_seconds_bucket[1m])))
```

Backlog:

```promql
sum(offer_queue_depth)
```

The scheduler uses fixedDelay: the configured delay follows cycle completion. A 500ms cycle alone does not establish overload. Compare offer age, backlog and completion throughput. A timer sum rising linearly does not mean latency rises.

## E1: hot-cell admission

Hypothesis: concentration increases lock waiting and enqueue p99. Hold total RPS and other distributions fixed; compare 1000, 10 and 1 cell, below saturation then near the latency knee. Capture lock profiles, enqueue p99, CPU, drops and evictions. First shorten the critical section if evidence supports it. Striped locking cannot make a single shared TreeSet concurrently safe; partitioning must preserve capacity, ordering and eviction semantics.

## E2: serial dispatch

Use a controlled gateway with fixed 0/10/50ms delays. Compare dispatch concurrency 1/2/4/8 with identical ingress. Measure completed throughput, oldest offer age, expiry, queue and in-flight backlog, and failures. Adopt the smallest useful bounded concurrency only if correctness and downstream health hold. This requires a controlled gateway and candidate implementation; the workload script alone does not inject latency.

## E3: downstream waiting

Compare a fast stub, fixed-latency stub and real gateway. Sweep concurrency separately from ingress. Measure RPC p99, deadlines, acceptance, gateway CPU/queue depth and active dispatcher thread wall profiles. More throughput at stable latency supports concurrency; throughput plateau plus rising errors supports investigating saturation. Idle threads in a wall profile do not prove a bottleneck.

## E4: allocations and GC

Keep heap settings and workload fixed. Collect allocation and JFR evidence in comparable windows; correlate allocation rate, GC pause timestamps and p99. Change one allocation-heavy path and rerun. Allocation prominence alone does not justify GC tuning.

## E5: sustainable capacity

Run constant arrival-rate plateaus at 250/500/750/1000/1500/2000 requests/s, warm up then measure five minutes at each. Stop escalation on clear failure. Use generator headroom and sufficient preallocated VUs; repeat suspicious dropped iterations with a separate generator. The current ramping script must be changed to a constant-arrival-rate scenario for these plateaus. Report the highest tested rate meeting latency, backlog and delivery criteria; a ramp-test average is not a capacity ceiling.

## Profiling during a steady load window

Install async-profiler matching the OS/architecture, set ASPROF to its executable, and find the actual app PID with `jcmd -l`. Run inside the app PID namespace as the same OS user; CPU profiling may require platform perf permissions. Run profile modes separately to limit distortion. Do not relax host security globally.

```bash
mkdir -p profiles
export PID=1234 # replace with the app JVM PID
export ASPROF=/path/to/async-profiler/bin/asprof
"$ASPROF" -e lock -d 30 -f profiles/lock.html "$PID"
"$ASPROF" -e wall -t -d 30 -f profiles/wall.html "$PID"
"$ASPROF" -e cpu -d 30 -f profiles/cpu.html "$PID"
"$ASPROF" -e alloc -d 30 -f profiles/alloc.html "$PID"
jcmd "$PID" JFR.start name=decision settings=profile duration=2m filename=/tmp/decision.jfr
# After two minutes:
jfr summary /tmp/decision.jfr
```

View HTML flame graphs in a browser; inspect JFR in JDK Mission Control. Archive profiles with run ID, commit and load window. Compare profiled results with unprofiled baselines.

## Adoption gate and decision record

Baseline -> candidate -> baseline again; preferably three repetitions. Change one variable. Require repeatable target-metric improvement without correctness loss or unacceptable resource/downstream regression. Do not automatically shard, parallelize or tune GC from one graph.

```text
Hypothesis:
Revision/config/resources:
Controlled variable:
Workload and measurement window:
Evidence: metrics, profile, raw test output:
Accounting/correctness limitations:
Results across repetitions:
Decision: adopt / reject / inconclusive:
Trade-offs and rollback:
```

Recommended order: observability and accounting prerequisites -> E5 -> E1/E3 -> evidence-supported E2/E4. No performance gains or experiment completion are claimed by adding this document.
