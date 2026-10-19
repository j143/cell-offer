#!/usr/bin/env bash
set -euo pipefail
PROMETHEUS_URL=${PROMETHEUS_URL:-http://localhost:9091}
APP_URL=${APP_URL:-http://localhost:8080}
printf '\nPrometheus readiness\n'
curl -fsS --max-time 5 "$PROMETHEUS_URL/-/ready"
printf '\nGrafana hostname mapping\n'
docker exec cell-offer-grafana sh -c 'grep host.docker.internal /etc/hosts'
printf '\nGrafana -> Prometheus readiness\n'
docker exec cell-offer-grafana wget -T 5 -qO- http://host.docker.internal:9091/-/ready
printf '\nGrafana -> Prometheus metadata API\n'
docker exec cell-offer-grafana wget -T 5 -qO- http://host.docker.internal:9091/api/v1/metadata
printf '\nScrape targets: inspect health and lastError\n'
curl -fsS --max-time 10 "$PROMETHEUS_URL/api/v1/targets"
printf '\nCustom timer histogram buckets\n'
metrics=$(curl -fsS --max-time 10 "$APP_URL/actuator/prometheus")
for metric in cell_offer_enqueue_duration cell_offer_flush_cycle_duration cell_offer_push_send_duration; do
  if ! grep -q "^${metric}_seconds_bucket" <<< "$metrics"; then
    printf 'MISSING histogram: %s\n' "$metric" >&2
    exit 1
  fi
  printf 'FOUND %s_seconds_bucket\n' "$metric"
done
printf '\nTransport and histogram checks passed. Target health and actual delivery still require verification.\n'
