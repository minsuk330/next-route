#!/usr/bin/env python3
"""Run lightweight structural checks on a generated GTFS feed.

This is intentionally not a replacement for the MobilityData validator.  It
is a fast preflight check used before starting a multi-hour graph import.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import re
from collections import Counter
from contextlib import contextmanager
from pathlib import Path
from zipfile import ZipFile


TIME_RE = re.compile(r"^(\d+):([0-5]\d):([0-5]\d)$")


@contextmanager
def table(feed: Path, filename: str):
    """Open a GTFS table from either an unpacked directory or a ZIP archive."""
    if feed.is_dir():
        with (feed / filename).open("r", encoding="utf-8-sig", newline="") as stream:
            yield stream
        return
    with ZipFile(feed) as archive:
        with archive.open(filename) as binary_stream:
            with io.TextIOWrapper(binary_stream, encoding="utf-8-sig", newline="") as stream:
                yield stream


def rows(feed: Path, filename: str):
    with table(feed, filename) as stream:
        yield from csv.DictReader(stream)


def has_table(feed: Path, filename: str) -> bool:
    if feed.is_dir():
        return (feed / filename).exists()
    with ZipFile(feed) as archive:
        return filename in archive.namelist()


def time_seconds(value: str) -> int | None:
    match = TIME_RE.match(value)
    if not match:
        return None
    return int(match.group(1)) * 3600 + int(match.group(2)) * 60 + int(match.group(3))


def validate(feed: Path) -> dict[str, object]:
    route_ids: set[str] = set()
    route_types: Counter[str] = Counter()
    route_errors: list[str] = []
    for row in rows(feed, "routes.txt"):
        route_id = row.get("route_id", "")
        if route_id in route_ids:
            route_errors.append(f"duplicate route_id={route_id}")
        route_ids.add(route_id)
        route_types[row.get("route_type", "")] += 1

    stop_ids: set[str] = set()
    stop_errors: list[str] = []
    for row in rows(feed, "stops.txt"):
        stop_id = row.get("stop_id", "")
        if stop_id in stop_ids:
            stop_errors.append(f"duplicate stop_id={stop_id}")
        stop_ids.add(stop_id)

    service_ids = {row.get("service_id", "") for row in rows(feed, "calendar.txt")}
    trip_ids: set[str] = set()
    trip_route_ids: dict[str, str] = {}
    trip_errors: list[str] = []
    for row in rows(feed, "trips.txt"):
        trip_id = row.get("trip_id", "")
        route_id = row.get("route_id", "")
        if trip_id in trip_ids:
            trip_errors.append(f"duplicate trip_id={trip_id}")
        if route_id not in route_ids:
            trip_errors.append(f"trip={trip_id} references missing route={route_id}")
        if row.get("service_id", "") not in service_ids:
            trip_errors.append(f"trip={trip_id} references missing service={row.get('service_id')}")
        trip_ids.add(trip_id)
        trip_route_ids[trip_id] = route_id

    stop_time_count = 0
    stop_time_errors: list[str] = []
    last_sequence: dict[str, int] = {}
    min_time: int | None = None
    max_time: int | None = None
    for row in rows(feed, "stop_times.txt"):
        stop_time_count += 1
        trip_id = row.get("trip_id", "")
        stop_id = row.get("stop_id", "")
        if trip_id not in trip_ids:
            stop_time_errors.append(f"stop_time references missing trip={trip_id}")
        if stop_id not in stop_ids:
            stop_time_errors.append(f"stop_time references missing stop={stop_id}")
        try:
            sequence = int(row.get("stop_sequence", ""))
        except ValueError:
            stop_time_errors.append(f"invalid stop_sequence trip={trip_id}")
            sequence = -1
        if trip_id in last_sequence and sequence <= last_sequence[trip_id]:
            stop_time_errors.append(f"non-increasing stop_sequence trip={trip_id}")
        last_sequence[trip_id] = sequence
        for field in ("arrival_time", "departure_time"):
            parsed = time_seconds(row.get(field, ""))
            if parsed is None:
                stop_time_errors.append(f"invalid {field} trip={trip_id} value={row.get(field)!r}")
            elif min_time is None or parsed < min_time:
                min_time = parsed
            if max_time is None or parsed > max_time:
                max_time = parsed

    transfer_errors: list[str] = []
    transfer_count = 0
    for row in rows(feed, "transfers.txt"):
        transfer_count += 1
        if row.get("from_stop_id") not in stop_ids:
            transfer_errors.append(f"transfer references missing from_stop={row.get('from_stop_id')}")
        if row.get("to_stop_id") not in stop_ids:
            transfer_errors.append(f"transfer references missing to_stop={row.get('to_stop_id')}")

    errors = route_errors + stop_errors + trip_errors + stop_time_errors + transfer_errors
    return {
        "feed": str(feed),
        "routes": len(route_ids),
        "route_types": dict(sorted(route_types.items())),
        "stops": len(stop_ids),
        "trips": len(trip_ids),
        "stop_times": stop_time_count,
        "transfers": transfer_count,
        "time_range_seconds": {"min": min_time, "max": max_time},
        "has_shapes": has_table(feed, "shapes.txt"),
        "error_count": len(errors),
        "errors": errors[:100],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("feed", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = validate(args.feed)
    payload = json.dumps(result, ensure_ascii=False, indent=2)
    print(payload)
    if args.output:
        args.output.write_text(payload + "\n", encoding="utf-8")
    if result["error_count"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
