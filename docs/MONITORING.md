# Monitoring quickstart

Start Prometheus and Grafana with the pre-provisioned Cell-Offer dashboard:

```bash
make monitoring-up
```

Then open:

- Grafana: http://localhost:3000 — login `admin` / `admin`
- Dashboard: **Cell-Offer Load Diagnosis**
- Prometheus: http://localhost:9091

By default, Prometheus scrapes:

```text
host.docker.internal:8080/actuator/prometheus
```

If Cell-Offer runs in a dev container, GitHub Codespace, or a different Docker network, override the target with its reachable IP:

```bash
# Find the container IP
docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' <cell-offer-container>

# Start monitoring against that IP
make monitoring-up CELL_OFFER_TARGET=172.17.0.5:8080
```

Verify the target:

```bash
curl -s 'http://localhost:9091/api/v1/targets' \
  | jq '.data.activeTargets[] | {scrapeUrl, health, lastError}'
```

The `cell-offer` target must show `"health": "up"` before application counters appear in Grafana.

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
| Flush-cycle latency | Is the configured flush budget being exceeded? |
| PushGateway RPC latency | Is the downstream `SendPush` call slow? |
| Delivery failures and shedding | Are failures, rejections, drops, or expirations increasing? |
| Queue backlog and reconciliation | Is ingress and flush throughput balanced? |

## Interpreting the current baseline

The first k6 run accepted 788,559 offers with zero HTTP failures, but p95 was 220.78 ms and p99 was 384.65 ms. It also dropped 24,440 scheduled iterations, so its effective arrival rate was about 939 RPS rather than the configured 2,000 RPS. [cite:10]

Use the new timers to determine whether the dominant delay is enqueue, the flush cycle, or the PushGateway RPC. The gRPC deadline is configured by:

```properties
push.gateway.send-deadline-ms=250
```

## Troubleshooting no metrics

1. Confirm the application exposes metrics:

   ```bash
   curl -s http://localhost:8080/actuator/prometheus \
     | grep 'cell_offer_'
   ```

2. Confirm Prometheus can reach that endpoint:

   ```bash
   docker exec -it cell-offer-prometheus \
     wget -qO- http://host.docker.internal:8080/actuator/prometheus | head
   ```

3. If this fails in a dev container or Codespace, start monitoring with the application container IP:

   ```bash
   make monitoring-up CELL_OFFER_TARGET=<APP_CONTAINER_IP>:8080
   ```

4. Restart Prometheus after changing the target:

   ```bash
   make monitoring-down && make monitoring-up CELL_OFFER_TARGET=<APP_CONTAINER_IP>:8080
   ```
