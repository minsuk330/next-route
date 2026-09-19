#!/usr/bin/env python3
"""Replay the official Helsinki SpeedTest corpus through the OTP GTFS GraphQL API.

The script is intentionally outside the OTP source tree. It preserves request timing,
HTTP status, response fingerprints, and the complete response JSON for later comparison.
It supports both sequential correctness runs and a fixed-arrival-rate open-loop pilot.
"""

from __future__ import annotations

import argparse
import csv
import concurrent.futures
import hashlib
import json
import random
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


QUERY = """
query($from: InputCoordinates!, $to: InputCoordinates!, $date: String!, $time: String!, $arriveBy: Boolean!, $via: [PlanViaLocationInput!]) {
  plan(
    from: $from
    to: $to
    date: $date
    time: $time
    arriveBy: $arriveBy
    transportModes: [{mode: TRANSIT}, {mode: WALK}]
    numItineraries: 3
    via: $via
  ) {
    itineraries {
      start
      end
      duration
      numberOfTransfers
      generalizedCost
      legs {
        mode
        transitLeg
        start { scheduledTime }
        end { scheduledTime }
        from { lat lon name stop { gtfsId } }
        to { lat lon name stop { gtfsId } }
        route { gtfsId }
      }
    }
    messageStrings
    messageEnums
    routingErrors { code description }
  }
}
""".strip()


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def duration_to_iso(value: str) -> str:
    """Convert the corpus's compact duration (e.g. 60s) to GraphQL ISO duration."""
    if not value:
        return "PT0S"
    if value.endswith("s"):
        return f"PT{value[:-1]}S"
    if value.endswith("m"):
        return f"PT{value[:-1]}M"
    if value.endswith("h"):
        return f"PT{value[:-1]}H"
    raise ValueError(f"unsupported via duration: {value}")


def request_for(row: dict[str, str], date: str) -> dict[str, Any]:
    arrive_by = bool(row["arrival"])
    time_value = row["arrival"] if arrive_by else row["departure"]
    variables: dict[str, Any] = {
        "from": {"lat": float(row["fromLat"]), "lon": float(row["fromLon"])},
        "to": {"lat": float(row["toLat"]), "lon": float(row["toLon"])},
        "date": date,
        "time": time_value,
        "arriveBy": arrive_by,
        "via": None,
    }
    if row["viaLabel"]:
        variables["via"] = [
            {
                "visit": {
                    "label": row["viaLabel"],
                    "coordinate": {
                        "latitude": float(row["viaLat"]),
                        "longitude": float(row["viaLon"]),
                    },
                    "minimumWaitTime": duration_to_iso(row["viaMinimumWaitTime"]),
                }
            }
        ]
    return {"query": QUERY, "variables": variables}


def canonical_plan(response: Any) -> dict[str, Any]:
    """Keep externally observable routing fields and omit HTTP/debug timing fields."""
    if not isinstance(response, dict):
        return {"responseType": type(response).__name__, "response": response}
    data = response.get("data")
    if not isinstance(data, dict) or not isinstance(data.get("plan"), dict):
        return {
            "data": data,
            "errors": response.get("errors"),
        }
    plan = data["plan"]

    def place(value: Any) -> Any:
        if not isinstance(value, dict):
            return None
        stop = value.get("stop")
        return {
            "lat": value.get("lat"),
            "lon": value.get("lon"),
            "name": value.get("name"),
            "stop": stop.get("gtfsId") if isinstance(stop, dict) else None,
        }

    itineraries = []
    for itinerary in plan.get("itineraries") or []:
        if not isinstance(itinerary, dict):
            itineraries.append(itinerary)
            continue
        legs = []
        for leg in itinerary.get("legs") or []:
            if not isinstance(leg, dict):
                legs.append(leg)
                continue
            route = leg.get("route")
            legs.append(
                {
                    "mode": leg.get("mode"),
                    "transitLeg": leg.get("transitLeg"),
                    "start": (leg.get("start") or {}).get("scheduledTime"),
                    "end": (leg.get("end") or {}).get("scheduledTime"),
                    "from": place(leg.get("from")),
                    "to": place(leg.get("to")),
                    "route": route.get("gtfsId") if isinstance(route, dict) else None,
                }
            )
        itineraries.append(
            {
                "start": itinerary.get("start"),
                "end": itinerary.get("end"),
                "duration": itinerary.get("duration"),
                "numberOfTransfers": itinerary.get("numberOfTransfers"),
                "generalizedCost": itinerary.get("generalizedCost"),
                "legs": legs,
            }
        )
    return {
        "itineraries": itineraries,
        "messageStrings": plan.get("messageStrings"),
        "messageEnums": plan.get("messageEnums"),
        "routingErrors": plan.get("routingErrors"),
    }


def fingerprint(canonical: Any) -> str:
    encoded = json.dumps(canonical, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def send(endpoint: str, payload: dict[str, Any], timeout: float) -> tuple[int, bytes, str | None]:
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
    request = urllib.request.Request(
        endpoint,
        data=encoded,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read(), None
    except urllib.error.HTTPError as error:
        return error.code, error.read(), str(error)
    except Exception as error:  # preserve the error in the JSONL output
        return 0, b"", repr(error)


def make_record(
    endpoint: str,
    row: dict[str, str],
    date: str,
    sequence: int,
    scheduled_at: float,
    timeout: float,
) -> dict[str, Any]:
    payload = request_for(row, date)
    sent = time.monotonic()
    status, body, error = send(endpoint, payload, timeout)
    completed = time.monotonic()
    try:
        decoded = json.loads(body.decode("utf-8")) if body else None
    except Exception as decode_error:
        decoded = None
        error = f"{error or ''}; jsonDecode={decode_error!r}"
    canonical = canonical_plan(decoded)
    return {
        "seq": sequence,
        "testCaseId": row["testCaseId"],
        "category": row["category"],
        "sentAt": now_iso(),
        "elapsedMs": (completed - sent) * 1000.0,
        "scheduledLagMs": max(0.0, (sent - scheduled_at) * 1000.0),
        "httpStatus": status,
        "transportError": error,
        "responseFingerprint": fingerprint(canonical),
        "response": decoded,
        "canonical": canonical,
    }


def rows_from_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    expected = {str(i) for i in range(1, len(rows) + 1)}
    actual = {row["testCaseId"] for row in rows}
    if actual != expected:
        raise ValueError(f"corpus ids are not contiguous: expected 1..{len(rows)}")
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--date", default="2026-02-19")
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--interval-ms", type=float, default=0.0)
    parser.add_argument("--arrival-rate", type=float, default=0.0, help="open-loop requests/second")
    parser.add_argument("--duration-s", type=float, default=0.0)
    parser.add_argument("--seed", type=int, default=20260915)
    parser.add_argument("--max-cases", type=int, default=0)
    parser.add_argument("--workers", type=int, default=32)
    args = parser.parse_args()

    if args.repeat < 1 or args.max_cases < 0 or args.arrival_rate < 0 or args.workers < 1:
        parser.error("repeat/max-cases/arrival-rate/workers must be non-negative (repeat/workers must be >= 1)")
    if args.arrival_rate and args.duration_s <= 0:
        parser.error("--duration-s is required with --arrival-rate")

    rows = rows_from_csv(args.corpus)
    if args.max_cases:
        rows = rows[: args.max_cases]
    rng = random.Random(args.seed)
    out = args.output
    out.parent.mkdir(parents=True, exist_ok=True)
    metadata = {
        "startedAt": now_iso(),
        "endpoint": args.endpoint,
        "corpus": str(args.corpus),
        "date": args.date,
        "querySha256": hashlib.sha256(QUERY.encode()).hexdigest(),
        "rows": len(rows),
        "repeat": args.repeat,
        "intervalMs": args.interval_ms,
        "arrivalRate": args.arrival_rate,
        "durationS": args.duration_s,
        "seed": args.seed,
        "workers": args.workers,
    }

    with out.open("w", encoding="utf-8") as handle:
        handle.write(json.dumps({"meta": metadata}, ensure_ascii=False) + "\n")
        sequence: list[dict[str, str]] = []
        if args.arrival_rate:
            sequence = list(rows)
            rng.shuffle(sequence)
            total = max(1, int(args.duration_s * args.arrival_rate))
            sequence = [sequence[i % len(sequence)] for i in range(total)]
        else:
            for _ in range(args.repeat):
                sequence.extend(rows)

        if args.arrival_rate:
            start = time.monotonic()
            with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
                pending: set[concurrent.futures.Future[dict[str, Any]]] = set()

                def emit(done: set[concurrent.futures.Future[dict[str, Any]]]) -> None:
                    for future in done:
                        record = future.result()
                        handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
                        handle.flush()
                        print(f"{record['seq']}/{len(sequence)} case={record['testCaseId']} status={record['httpStatus']} elapsedMs={record['elapsedMs']:.1f} lagMs={record['scheduledLagMs']:.1f}", flush=True)

                for index, row in enumerate(sequence, start=1):
                    scheduled_at = start + (index - 1) / args.arrival_rate
                    wait = scheduled_at - time.monotonic()
                    if wait > 0:
                        time.sleep(wait)
                    pending.add(
                        pool.submit(
                            make_record, args.endpoint, row, args.date, index, scheduled_at, args.timeout
                        )
                    )
                    while len(pending) >= args.workers:
                        done, pending = concurrent.futures.wait(
                            pending, return_when=concurrent.futures.FIRST_COMPLETED
                        )
                        emit(done)
                    # Drain completed requests while the open-loop schedule is
                    # still being submitted, so an interrupted run retains
                    # all completed responses instead of buffering every future.
                    if pending:
                        done, pending = concurrent.futures.wait(
                            pending, timeout=0, return_when=concurrent.futures.FIRST_COMPLETED
                        )
                        emit(done)
                while pending:
                    done, pending = concurrent.futures.wait(
                        pending, return_when=concurrent.futures.FIRST_COMPLETED
                    )
                    emit(done)
        else:
            for index, row in enumerate(sequence, start=1):
                record = make_record(args.endpoint, row, args.date, index, time.monotonic(), args.timeout)
                handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
                handle.flush()
                if args.interval_ms:
                    time.sleep(args.interval_ms / 1000.0)
                print(f"{index}/{len(sequence)} case={row['testCaseId']} status={record['httpStatus']} elapsedMs={record['elapsedMs']:.1f}", flush=True)

        handle.write(json.dumps({"doneAt": now_iso(), "sent": len(sequence)}, ensure_ascii=False) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
