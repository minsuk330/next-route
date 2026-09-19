#!/usr/bin/env python3
"""Issue a fixed set of OTP GraphQL transit searches at one simultaneous start time.

This is a load-test helper, not a benchmark of route quality. It deliberately
uses only Python's standard library so results can be repeated on the build host.
"""
from __future__ import annotations

import argparse
import json
import statistics
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass
from threading import Barrier
from urllib.request import Request, urlopen

QUERY = """query ($origin: PlanLabeledLocationInput!, $destination: PlanLabeledLocationInput!, $when: OffsetDateTime!) {
  planConnection(origin: $origin, destination: $destination, dateTime: { earliestDeparture: $when }, first: 3) {
    edges { node { start end duration numberOfTransfers legs { mode } } }
    routingErrors { code description }
  }
}"""

CASES = (
    ("seoul-busan", (37.5663, 126.9779), (35.1151, 129.0414)),
    ("cheonggye-sadang", (37.569147, 126.978647), (37.487272, 126.983399)),
    ("daegu-gwangju", (35.8714, 128.6014), (35.1595, 126.8526)),
    ("incheon-chuncheon", (37.4563, 126.7052), (37.8813, 127.7298)),
)

@dataclass
class Result:
    case_id: str
    elapsed_ms: float
    http_status: int | None
    itinerary_count: int
    routing_error_codes: list[str]
    error: str | None

def execute(base_url: str, departure_at: str, barrier: Barrier, case: tuple[str, tuple[float, float], tuple[float, float]]) -> Result:
    case_id, origin, destination = case
    body = json.dumps({
        "query": QUERY,
        "variables": {
            "origin": {"location": {"coordinate": {"latitude": origin[0], "longitude": origin[1]}}},
            "destination": {"location": {"coordinate": {"latitude": destination[0], "longitude": destination[1]}}},
            "when": departure_at,
        },
    }).encode()
    request = Request(
        base_url.rstrip("/") + "/otp/routers/default/index/graphql",
        data=body,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    barrier.wait()
    started = time.perf_counter()
    try:
        with urlopen(request, timeout=90) as response:
            payload = json.load(response)
            plan = payload.get("data", {}).get("planConnection", {})
            return Result(
                case_id, (time.perf_counter() - started) * 1000, response.status,
                len(plan.get("edges", [])),
                [error.get("code", "UNKNOWN") for error in plan.get("routingErrors", [])], None,
            )
    except Exception as error:
        return Result(case_id, (time.perf_counter() - started) * 1000, None, 0, [], str(error))

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:18081")
    parser.add_argument("--concurrency", type=int, choices=(1, 2, 4), required=True)
    parser.add_argument("--departure-at", default="2026-09-07T17:00:00+09:00")
    args = parser.parse_args()
    selected = CASES[:args.concurrency]
    barrier = Barrier(len(selected))
    with ThreadPoolExecutor(max_workers=len(selected)) as executor:
        futures = [executor.submit(execute, args.url, args.departure_at, barrier, case) for case in selected]
        results = [future.result() for future in as_completed(futures)]
    results.sort(key=lambda result: result.case_id)
    elapsed = [result.elapsed_ms for result in results]
    print(json.dumps({
        "concurrency": args.concurrency,
        "departureAt": args.departure_at,
        "results": [asdict(result) for result in results],
        "latency": {"minMs": min(elapsed), "maxMs": max(elapsed), "meanMs": statistics.mean(elapsed)},
    }, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
