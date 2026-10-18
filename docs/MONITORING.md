# Monitoring quickstart

Start Prometheus and Grafana with the pre-provisioned Cell-Offer dashboard:

```bash
make monitoring-up
```

Then open:

- Grafana: http://localhost:3000 — login `admin` / `admin`
- Dashboard: **Cell-Offer Load Diagnosis**
- Prometheus: http://localhost:9091

Prometheus scrapes the locally running Cell-Offer service at:

```text
http://host.docker.internal:8080/actuator/prometheus
```

Run the load test while the dashboard is open:

```bash
make loadtest
```

For a first capacity probe, use:

```bash
make loadtest-capacity
```

## Dashboard panels

| Panel | Decision it answers |
|---|---|
| Offer API throughput | Is the intended arrival rate being served? |
| Offer API latency | Are REST p95/p99 within the SLO? |
| Queue enqueue latency | Is hot-cell admission becoming a bottleneck? |
| Flush-cycle latency | Is the 300 ms flush budget being exceeded? |
| PushGateway RPC latency | Is the downstream `SendPush` call slow? |
| Delivery failures and shedding | Are failures, rejections, drops, or expirations increasing? |
| Queue backlog and reconciliation | Is ingress and flush throughput balanced? |

## Interpreting the current baseline

The first k6 run accepted 788,559 offers with zero HTTP failures, but p95 was 220.78 ms and p99 was 384.65 ms. It also dropped 24,440 scheduled iterations, so its effective arrival rate was about 939 RPS rather than the configured 2,000 RPS. [cite:10]

Use the new timers to determine whether the dominant delay is enqueue, the flush cycle, or the PushGateway RPC. The gRPC deadline is configured by:

```properties
push.gateway.send-deadline-ms=250
```
