from __future__ import annotations

import argparse
import random
import statistics
import time

import requests

HOT_CELL = "8928308280fffff"
DEFAULT_BASE_URL = "http://localhost:8080"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate offers and surface the queue service's response characteristics."
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--cell-id", default=HOT_CELL)
    parser.add_argument("--count", type=int, default=1000)
    parser.add_argument("--rate-hz", type=float, default=1000.0)
    parser.add_argument("--report-every", type=int, default=100)
    parser.add_argument("--timeout", type=float, default=5.0)
    parser.add_argument("--sample-size", type=int, default=5)
    parser.add_argument("--show-stats", action="store_true", default=True)
    parser.add_argument("--no-stats", dest="show_stats", action="store_false")
    return parser.parse_args()


def build_payload(i: int) -> dict[str, object]:
    return {
        "driverId": f"d-{i}",
        "riderId": f"r-{random.randint(0, 50)}",
        "priority": random.randint(1, 10),
        "ttlMillis": random.choice([500, 1000, 5000]),
    }


def fetch_stats(session: requests.Session, base_url: str, cell_id: str, timeout: float) -> dict[str, object]:
    response = session.get(f"{base_url}/cells/{cell_id}/stats", timeout=timeout)
    response.raise_for_status()
    return response.json()


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = int(round((len(ordered) - 1) * pct))
    return ordered[index]


def main() -> int:
    args = parse_args()
    base_url = args.base_url.rstrip("/")
    post_url = f"{base_url}/cells/{args.cell_id}/offers"

    session = requests.Session()
    latencies_ms: list[float] = []
    status_counts: dict[int, int] = {}
    accepted_samples: list[dict[str, object]] = []
    failures: list[str] = []

    if args.show_stats:
        before = fetch_stats(session, base_url, args.cell_id, args.timeout)
        print(f"[before] {before}")

    start = time.perf_counter()
    next_report = max(1, args.report_every)

    for i in range(1, args.count + 1):
        payload = build_payload(i)
        request_started = time.perf_counter()

        try:
            response = session.post(post_url, json=payload, timeout=args.timeout)
            elapsed_ms = (time.perf_counter() - request_started) * 1000.0
            latencies_ms.append(elapsed_ms)
            status_counts[response.status_code] = status_counts.get(response.status_code, 0) + 1

            if response.status_code == 202 and len(accepted_samples) < args.sample_size:
                accepted_samples.append(response.json())
            elif response.status_code != 202 and len(failures) < args.sample_size:
                failures.append(f"status={response.status_code} body={response.text.strip()}")
        except requests.RequestException as exc:
            elapsed_ms = (time.perf_counter() - request_started) * 1000.0
            latencies_ms.append(elapsed_ms)
            status_counts[-1] = status_counts.get(-1, 0) + 1
            if len(failures) < args.sample_size:
                failures.append(str(exc))

        if i % next_report == 0 or i == args.count:
            completed = i / max(time.perf_counter() - start, 1e-9)
            print(
                f"[progress] sent={i}/{args.count} "
                f"accepted={status_counts.get(202, 0)} "
                f"errors={status_counts.get(-1, 0) + sum(v for k, v in status_counts.items() if k not in (202, 200, 201, -1))} "
                f"rate={completed:.1f}/s"
            )

        if args.rate_hz > 0:
            target_elapsed = i / args.rate_hz
            remaining = target_elapsed - (time.perf_counter() - start)
            if remaining > 0:
                time.sleep(remaining)

    total_elapsed = time.perf_counter() - start
    print(
        f"[summary] sent={args.count} "
        f"accepted={status_counts.get(202, 0)} "
        f"failures={status_counts.get(-1, 0) + sum(v for k, v in status_counts.items() if k not in (202, 200, 201, -1))} "
        f"elapsed={total_elapsed:.2f}s "
        f"rate={args.count / max(total_elapsed, 1e-9):.1f}/s "
        f"avg_ms={statistics.fmean(latencies_ms):.2f} "
        f"p95_ms={percentile(latencies_ms, 0.95):.2f}"
    )

    if accepted_samples:
        print(f"[accepted-sample] {accepted_samples}")
    if failures:
        print(f"[failure-sample] {failures}")

    if args.show_stats:
        after = fetch_stats(session, base_url, args.cell_id, args.timeout)
        print(f"[after] {after}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())