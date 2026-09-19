#!/usr/bin/env python3
"""Replay the official Baden-Württemberg search corpus through OTP GraphQL.

This runner deliberately keeps the original testCaseId values (the official CSV is a
selected subset of 1..416), preserves transport modes and search windows, and supports
both sequential correctness runs and fixed-arrival-rate open-loop runs.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
import hashlib
import json
import random
import re
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


QUERY = """
query(
  $from: InputCoordinates!,
  $to: InputCoordinates!,
  $date: String!,
  $time: String!,
  $arriveBy: Boolean!,
  $transportModes: [TransportMode!],
  $searchWindow: Long,
  $numItineraries: Int!
) {
  plan(
    from: $from
    to: $to
    date: $date
    time: $time
    arriveBy: $arriveBy
    transportModes: $transportModes
    searchWindow: $searchWindow
    numItineraries: $numItineraries
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


EXPECTED_FIELDS = {
    "testCaseId",
    "description",
    "departure",
    "origin",
    "destination",
    "fromLat",
    "fromLon",
    "toLat",
    "toLon",
    "category",
    "modes",
    "window",
}
WINDOW_RE = re.compile(r"^(?P<value>[0-9]+)(?P<unit>[smh])$")


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def parse_window(value: str) -> int | None:
    if not value:
        return None
    match = WINDOW_RE.fullmatch(value)
    if not match:
        raise ValueError(f"unsupported official window: {value!r}")
    amount = int(match.group("value"))
    unit = match.group("unit")
    return amount * {"s": 1, "m": 60, "h": 3600}[unit]


def load_rows(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        if set(reader.fieldnames or ()) != EXPECTED_FIELDS:
            raise ValueError(f"unexpected Baden CSV fields: {reader.fieldnames}")
        rows = list(reader)
    if len(rows) != 256:
        raise ValueError(f"expected 256 official cases, got {len(rows)}")
    if Counter(row["category"] for row in rows) != Counter({"transit": 240, "car": 16}):
        raise ValueError("official category counts are not transit=240/car=16")
    if Counter(row["modes"] for row in rows) != Counter({"TRANSIT|WALK": 240, "CAR": 16}):
        raise ValueError("official mode counts are not TRANSIT|WALK=240/CAR=16")
    if any(row["departure"] != "08:00" for row in rows):
        raise ValueError("Baden corpus contains a non-08:00 departure")
    for row in rows:
        int(row["testCaseId"])
        for field in ("fromLat", "fromLon", "toLat", "toLon"):
            float(row[field])
        parse_window(row["window"])
        modes = row["modes"].split("|")
        if modes not in (["TRANSIT", "WALK"], ["CAR"]):
            raise ValueError(f"unsupported mode set in case {row['testCaseId']}: {modes}")
    return rows


def request_for(row: dict[str, str], date: str, num_itineraries: int) -> dict[str, Any]:
    return {
        "query": QUERY,
        "variables": {
            "from": {"lat": float(row["fromLat"]), "lon": float(row["fromLon"])},
            "to": {"lat": float(row["toLat"]), "lon": float(row["toLon"])},
            "date": date,
            "time": row["departure"],
            "arriveBy": False,
            "transportModes": [{"mode": mode} for mode in row["modes"].split("|")],
            "searchWindow": parse_window(row["window"]),
            "numItineraries": num_itineraries,
        },
    }


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


def canonical_plan(response: Any) -> dict[str, Any]:
    """Keep observable route data and omit HTTP/debug timing fields."""
    if not isinstance(response, dict):
        return {"responseType": type(response).__name__, "response": response}
    data = response.get("data")
    if not isinstance(data, dict) or not isinstance(data.get("plan"), dict):
        return {"data": data, "errors": response.get("errors")}
    plan = data["plan"]
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


def fingerprint(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
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
        return error.code, error.read(), repr(error)
    except Exception as error:  # preserve transport failures in JSONL
        return 0, b"", repr(error)


def make_record(
    endpoint: str,
    row: dict[str, str],
    date: str,
    num_itineraries: int,
    sequence: int,
    cycle: int,
    scheduled_epoch_ns: int,
    scheduled_mono_ns: int,
    timeout: float,
    include_response: bool,
) -> dict[str, Any]:
    payload = request_for(row, date, num_itineraries)
    sent_epoch_ns = time.time_ns()
    sent_mono_ns = time.monotonic_ns()
    status, body, error = send(endpoint, payload, timeout)
    completed_epoch_ns = time.time_ns()
    completed_mono_ns = time.monotonic_ns()
    try:
        decoded = json.loads(body.decode("utf-8")) if body else None
    except Exception as decode_error:
        decoded = None
        error = f"{error or ''}; jsonDecode={decode_error!r}"
    canonical = canonical_plan(decoded)
    record: dict[str, Any] = {
        "seq": sequence,
        "cycle": cycle,
        "testCaseId": row["testCaseId"],
        "category": row["category"],
        "modes": row["modes"],
        "window": row["window"],
        "scheduledAtEpochNs": scheduled_epoch_ns,
        "scheduledAtMonotonicNs": scheduled_mono_ns,
        "sentAtEpochNs": sent_epoch_ns,
        "sentAtMonotonicNs": sent_mono_ns,
        "completedAtEpochNs": completed_epoch_ns,
        "completedAtMonotonicNs": completed_mono_ns,
        "elapsedMs": (completed_mono_ns - sent_mono_ns) / 1_000_000,
        "scheduleToCompletionMs": (completed_mono_ns - scheduled_mono_ns) / 1_000_000,
        "scheduledLagMs": max(0, sent_mono_ns - scheduled_mono_ns) / 1_000_000,
        "httpStatus": status,
        "transportError": error,
        "responseBytes": len(body),
        "responseFingerprint": fingerprint(canonical),
        "canonical": canonical,
    }
    if include_response:
        record["response"] = decoded
    return record


def metadata(args: argparse.Namespace, rows: list[dict[str, str]], schedule: list[tuple[dict[str, str], int]]) -> dict[str, Any]:
    schedule_json = json.dumps(
        [{"testCaseId": row["testCaseId"], "cycle": cycle} for row, cycle in schedule],
        separators=(",", ":"),
    ).encode()
    return {
        "startedAt": now_iso(),
        "endpoint": args.endpoint,
        "corpus": str(args.corpus),
        "date": args.date,
        "querySha256": hashlib.sha256(QUERY.encode()).hexdigest(),
        "corpusRows": len(rows),
        "categories": dict(Counter(row["category"] for row in rows)),
        "modes": dict(Counter(row["modes"] for row in rows)),
        "windows": dict(Counter(row["window"] or "<dynamic>" for row in rows)),
        "numItineraries": args.num_itineraries,
        "repeat": args.repeat,
        "arrivalRate": args.arrival_rate,
        "durationS": args.duration_s,
        "seed": args.seed,
        "workers": args.workers,
        "scheduleRows": len(schedule),
        "scheduleSha256": hashlib.sha256(schedule_json).hexdigest(),
        "wallMonotonicOrigin": {"epochNs": time.time_ns(), "monotonicNs": time.monotonic_ns()},
    }


def build_schedule(rows: list[dict[str, str]], args: argparse.Namespace) -> list[tuple[dict[str, str], int]]:
    rng = random.Random(args.seed)
    if args.arrival_rate:
        shuffled = list(rows)
        rng.shuffle(shuffled)
        count = max(1, int(args.duration_s * args.arrival_rate))
        return [(shuffled[index % len(shuffled)], index // len(shuffled)) for index in range(count)]
    return [(row, cycle) for cycle in range(args.repeat) for row in rows]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--date", default="2022-07-25")
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--arrival-rate", type=float, default=0.0)
    parser.add_argument("--duration-s", type=float, default=0.0)
    parser.add_argument("--seed", type=int, default=20260916)
    parser.add_argument("--max-cases", type=int, default=0)
    parser.add_argument("--workers", type=int, default=32)
    parser.add_argument("--num-itineraries", type=int, default=3)
    parser.add_argument("--include-response", action="store_true")
    args = parser.parse_args()
    if args.repeat < 1 or args.max_cases < 0 or args.arrival_rate < 0 or args.workers < 1:
        parser.error("repeat/max-cases/arrival-rate/workers are invalid")
    if args.arrival_rate and args.duration_s <= 0:
        parser.error("--duration-s is required with --arrival-rate")
    rows = load_rows(args.corpus)
    if args.max_cases:
        rows = rows[: args.max_cases]
    schedule = build_schedule(rows, args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as handle:
        handle.write(json.dumps({"meta": metadata(args, rows, schedule)}, ensure_ascii=False) + "\n")
        if args.arrival_rate:
            origin_mono_ns = time.monotonic_ns()
            origin_epoch_ns = time.time_ns()
            pending: set[concurrent.futures.Future[dict[str, Any]]] = set()
            with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
                def emit(done: set[concurrent.futures.Future[dict[str, Any]]]) -> None:
                    for future in done:
                        record = future.result()
                        handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
                        handle.flush()
                        print(
                            f"{record['seq']}/{len(schedule)} case={record['testCaseId']} "
                            f"status={record['httpStatus']} elapsedMs={record['elapsedMs']:.1f} "
                            f"lagMs={record['scheduledLagMs']:.1f}",
                            flush=True,
                        )

                for sequence, (row, cycle) in enumerate(schedule, start=1):
                    scheduled_mono_ns = origin_mono_ns + int((sequence - 1) * 1_000_000_000 / args.arrival_rate)
                    scheduled_epoch_ns = origin_epoch_ns + (scheduled_mono_ns - origin_mono_ns)
                    wait_ns = scheduled_mono_ns - time.monotonic_ns()
                    if wait_ns > 0:
                        time.sleep(wait_ns / 1_000_000_000)
                    pending.add(
                        pool.submit(
                            make_record,
                            args.endpoint,
                            row,
                            args.date,
                            args.num_itineraries,
                            sequence,
                            cycle,
                            scheduled_epoch_ns,
                            scheduled_mono_ns,
                            args.timeout,
                            args.include_response,
                        )
                    )
                    while len(pending) >= args.workers:
                        done, pending = concurrent.futures.wait(pending, return_when=concurrent.futures.FIRST_COMPLETED)
                        emit(done)
                    if pending:
                        done, pending = concurrent.futures.wait(
                            pending, timeout=0, return_when=concurrent.futures.FIRST_COMPLETED
                        )
                        emit(done)
                while pending:
                    done, pending = concurrent.futures.wait(pending, return_when=concurrent.futures.FIRST_COMPLETED)
                    emit(done)
        else:
            for sequence, (row, cycle) in enumerate(schedule, start=1):
                mono = time.monotonic_ns()
                record = make_record(
                    args.endpoint,
                    row,
                    args.date,
                    args.num_itineraries,
                    sequence,
                    cycle,
                    time.time_ns(),
                    mono,
                    args.timeout,
                    args.include_response,
                )
                handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
                handle.flush()
                print(
                    f"{sequence}/{len(schedule)} case={row['testCaseId']} "
                    f"status={record['httpStatus']} elapsedMs={record['elapsedMs']:.1f}",
                    flush=True,
                )
        handle.write(json.dumps({"doneAt": now_iso(), "sent": len(schedule)}, ensure_ascii=False) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
