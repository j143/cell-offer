SHELL := /bin/bash

.PHONY: help monitoring-up monitoring-down loadtest loadtest-capacity test

help:
	@echo "make monitoring-up         Start Prometheus and Grafana"
	@echo "make monitoring-down       Stop monitoring stack"
	@echo "make loadtest              Run the brutal mixed-load k6 scenario"
	@echo "make loadtest-capacity     Run a 500 RPS capacity probe"
	@echo "make test                  Run Maven tests"

monitoring-up:
	docker compose -f deploy/docker-compose.monitoring.yml up -d
	@echo "Grafana:      http://localhost:3000 (admin/admin)"
	@echo "Prometheus:   http://localhost:9091"
	@echo "Dashboard:    Cell-Offer Load Diagnosis"

monitoring-down:
	docker compose -f deploy/docker-compose.monitoring.yml down

loadtest:
	k6 run \
		-e BASE_URL=http://localhost:8080 \
		-e START_RPS=50 \
		-e RAMP_RPS=500 \
		-e PEAK_RPS=2000 \
		-e SOAK_RPS=500 \
		loadtest/k6-cell-offer-brutal.js

loadtest-capacity:
	k6 run \
		-e BASE_URL=http://localhost:8080 \
		-e START_RPS=50 \
		-e RAMP_RPS=250 \
		-e PEAK_RPS=500 \
		-e SOAK_RPS=500 \
		-e RAMP_DURATION=1m \
		-e PEAK_DURATION=3m \
		-e SOAK_DURATION=3m \
		loadtest/k6-cell-offer-brutal.js

test:
	mvn test
