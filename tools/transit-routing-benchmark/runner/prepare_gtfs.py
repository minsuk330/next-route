#!/usr/bin/env python3
"""Normalize the KTDB nationwide GTFS feed into an engine-compatible feed.

The KTDB download is nationwide and uses non-standard ``route_type`` codes
(설명서 p4: "국제적으로 통용되는 코드와 다르게 구축").  This utility remaps
``route_type`` to standard GTFS values, narrows the feed to an operating scope,
drops modes that do not belong in a ground-transit graph (air), and preserves
the referenced trips, stop times, stops, and transfers.

Scope narrowing (P1, national-otp-map-migration):

* ``--bbox W,S,E,N`` keeps only trips whose *every* served stop lies inside the
  bounding box.  A trip with any out-of-bbox stop is dropped **whole** — stop
  rows are never deleted to fabricate a shortcut trip (intent/PLAN §3).  This
  replaces the old ``--route-prefix`` heuristic: 수도권 membership is decided by
  real stop coordinates, not by a route_id prefix (AGENT-GUIDE).
* ``--calendar-center`` / ``--calendar-margin-days`` clamps ``calendar.txt`` to
  a window around the build date so RAPTOR does not materialize a pattern ×
  service-date object for the KTDB feed's 2017–2030 span.  Services with no
  overlap are dropped and their trips cascade out.

``stop_times.txt`` is ~1.4 GB for the nationwide feed, so it is streamed row by
row (twice: once to detect out-of-bbox trips, once to write kept rows).  It is
never materialized in memory.

The source directory is read only.  The output directory may be replaced by the
caller because it is a generated artifact.
"""

from __future__ import annotations

import argparse
import csv
import shutil
import sys
from datetime import date, timedelta
from pathlib import Path

# KTDB local route_type -> standard GTFS route_type (설명서 202503 p4/p10).
#   0 시내/마을/농어촌버스 -> 3 bus
#   1 도시철도/경전철       -> 1 subway
#   2 해운                  -> 4 ferry
#   3 시외버스              -> 3 bus
#   4 일반철도              -> 2 rail
#   5 공항리무진버스        -> 3 bus
#   6 고속철도              -> 2 rail
#   7 항공                  -> excluded (not ground transit; OTP would produce
#                              meaningless itineraries without airport access)
ROUTE_TYPE_TO_STANDARD = {
    "0": "3",
    "1": "1",
    "2": "4",
    "3": "3",
    "4": "2",
    "5": "3",
    "6": "2",
}
# KTDB local route_type values intentionally dropped, with a human label so the
# report explains what was excluded instead of silently discarding it.
EXCLUDED_ROUTE_TYPES = {
    "7": "air (항공)",
}

# csv default field-size limit is too small for wide GTFS rows on some feeds.
csv.field_size_limit(min(sys.maxsize, 2**31 - 1))


def read_header(path: Path) -> list[str]:
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        return next(csv.reader(stream))


def read_rows(path: Path):
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        yield from csv.DictReader(stream)


def write_rows(path: Path, fieldnames: list[str], rows) -> None:
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


# --------------------------------------------------------------------------- #
# calendar window
# --------------------------------------------------------------------------- #

def _parse_yyyymmdd(value: str) -> date:
    value = value.strip()
    return date(int(value[0:4]), int(value[4:6]), int(value[6:8]))


def _fmt_yyyymmdd(value: date) -> str:
    return value.strftime("%Y%m%d")


def select_calendar(source: Path, output: Path, args) -> tuple[set[str], dict]:
    """Clamp calendar.txt to [center-margin, center+margin]; drop non-overlapping.

    Returns the set of service_ids that survive, so trips referencing a dropped
    service cascade out and referential closure is preserved.
    """
    center: date = args.calendar_center
    margin = timedelta(days=args.calendar_margin_days)
    win_start = center - margin
    win_end = center + margin

    src = source / "calendar.txt"
    fieldnames = read_header(src)
    valid_service_ids: set[str] = set()
    kept: list[dict[str, str]] = []
    dropped = 0
    for row in read_rows(src):
        orig_start = _parse_yyyymmdd(row["start_date"])
        orig_end = _parse_yyyymmdd(row["end_date"])
        new_start = max(orig_start, win_start)
        new_end = min(orig_end, win_end)
        if new_start > new_end:
            dropped += 1
            continue
        row["start_date"] = _fmt_yyyymmdd(new_start)
        row["end_date"] = _fmt_yyyymmdd(new_end)
        kept.append(row)
        valid_service_ids.add(row["service_id"])
    write_rows(output / "calendar.txt", fieldnames, kept)

    # calendar_dates.txt is optional (KTDB does not build it). When present,
    # keep only exceptions for surviving services within the window.
    cd_src = source / "calendar_dates.txt"
    cd_kept = 0
    if cd_src.exists():
        cd_fields = read_header(cd_src)
        cd_rows: list[dict[str, str]] = []
        for row in read_rows(cd_src):
            if row.get("service_id") not in valid_service_ids:
                continue
            d = _parse_yyyymmdd(row["date"])
            if win_start <= d <= win_end:
                cd_rows.append(row)
                cd_kept += 1
        write_rows(output / "calendar_dates.txt", cd_fields, cd_rows)

    report = {
        "calendar_window": f"{_fmt_yyyymmdd(win_start)}..{_fmt_yyyymmdd(win_end)}",
        "calendar_services_kept": len(kept),
        "calendar_services_dropped": dropped,
        "calendar_dates_kept": cd_kept,
    }
    return valid_service_ids, report


# --------------------------------------------------------------------------- #
# bbox stop membership
# --------------------------------------------------------------------------- #

def load_in_bbox_stops(source: Path, bbox) -> tuple[set[str], int, int]:
    """Return the set of stop_ids inside bbox, plus (total, missing_coord)."""
    west, south, east, north = bbox
    inside: set[str] = set()
    total = 0
    missing = 0
    for row in read_rows(source / "stops.txt"):
        total += 1
        lat_s = (row.get("stop_lat") or "").strip()
        lon_s = (row.get("stop_lon") or "").strip()
        if not lat_s or not lon_s:
            missing += 1
            continue
        try:
            lat = float(lat_s)
            lon = float(lon_s)
        except ValueError:
            missing += 1
            continue
        if west <= lon <= east and south <= lat <= north:
            inside.add(row["stop_id"])
    return inside, total, missing


# --------------------------------------------------------------------------- #
# route / trip / stop_times selection
# --------------------------------------------------------------------------- #

def route_matches_scope(route_id: str, route_type: str, args) -> bool:
    if args.route_prefix and not any(
        route_id.startswith(p) for p in args.route_prefix
    ):
        return False
    if args.keep_ktdb_type and route_type not in args.keep_ktdb_type:
        return False
    return True


def select_routes(source: Path, args) -> tuple[dict[str, dict], list[str], dict]:
    """Stream routes.txt; remap route_type, apply route scope, drop excluded modes.

    Returns (kept_rows_by_id, fieldnames, report). Routes are held (not written)
    until the bbox pass reveals which routes keep at least one trip.
    """
    kept: dict[str, dict[str, str]] = {}
    kept_by_std: dict[str, int] = {}
    excluded_by_reason: dict[str, int] = {}
    unknown_types: set[str] = set()
    fieldnames: list[str] = []

    for row in read_rows(source / "routes.txt"):
        if not fieldnames:
            fieldnames = list(row.keys())
        route_id = row["route_id"]
        ktdb_type = row.get("route_type", "")

        if ktdb_type in EXCLUDED_ROUTE_TYPES:
            reason = EXCLUDED_ROUTE_TYPES[ktdb_type]
            excluded_by_reason[reason] = excluded_by_reason.get(reason, 0) + 1
            continue
        if ktdb_type not in ROUTE_TYPE_TO_STANDARD:
            unknown_types.add(ktdb_type)
            continue
        if not route_matches_scope(route_id, ktdb_type, args):
            excluded_by_reason["route scope"] = (
                excluded_by_reason.get("route scope", 0) + 1
            )
            continue

        std_type = ROUTE_TYPE_TO_STANDARD[ktdb_type]
        row["route_type"] = std_type
        kept[route_id] = row
        kept_by_std[std_type] = kept_by_std.get(std_type, 0) + 1

    if unknown_types:
        raise ValueError(
            f"Unmapped KTDB route_type(s) {sorted(unknown_types)}. "
            "Update ROUTE_TYPE_TO_STANDARD or EXCLUDED_ROUTE_TYPES from 설명서."
        )
    if not kept:
        raise ValueError("No routes matched the requested scope")

    report = {
        "routes_candidate": len(kept),
        "routes_candidate_by_standard_type": kept_by_std,
        "routes_excluded_by_reason": excluded_by_reason,
    }
    return kept, fieldnames, report


def load_candidate_trips(
    source: Path, route_ids: set[str], valid_service_ids: set[str]
) -> tuple[dict[str, dict], list[str]]:
    """Trips whose route is in scope and whose service survives the calendar window."""
    candidates: dict[str, dict[str, str]] = {}
    fieldnames = read_header(source / "trips.txt")
    for row in read_rows(source / "trips.txt"):
        if row["route_id"] in route_ids and row["service_id"] in valid_service_ids:
            candidates[row["trip_id"]] = row
    return candidates, fieldnames


def _stop_times_columns(src: Path) -> tuple[list[str], int, int]:
    header = read_header(src)
    return header, header.index("trip_id"), header.index("stop_id")


def find_out_of_bbox_trips(
    source: Path, candidate_trip_ids: set[str], in_bbox_stops: set[str]
) -> set[str]:
    """Pass 1: stream stop_times; a candidate trip with any out-of-bbox stop is bad."""
    src = source / "stop_times.txt"
    _, ti, si = _stop_times_columns(src)
    bad: set[str] = set()
    with src.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.reader(stream)
        next(reader)  # header
        for parts in reader:
            trip_id = parts[ti]
            if trip_id not in candidate_trip_ids or trip_id in bad:
                continue
            if parts[si] not in in_bbox_stops:
                bad.add(trip_id)
    return bad


def write_stop_times(
    source: Path, output: Path, final_trip_ids: set[str]
) -> tuple[set[str], int]:
    """Pass 2: stream stop_times; write rows of kept trips, collect used stops."""
    src = source / "stop_times.txt"
    header, ti, si = _stop_times_columns(src)
    used_stop_ids: set[str] = set()
    written = 0
    with src.open("r", encoding="utf-8-sig", newline="") as stream, (
        output / "stop_times.txt"
    ).open("w", encoding="utf-8", newline="") as out:
        reader = csv.reader(stream)
        writer = csv.writer(out, lineterminator="\n")
        next(reader)  # header
        writer.writerow(header)
        for parts in reader:
            if parts[ti] in final_trip_ids:
                writer.writerow(parts)
                used_stop_ids.add(parts[si])
                written += 1
    return used_stop_ids, written


def select_stops(source: Path, output: Path, stop_ids: set[str]) -> int:
    kept = [r for r in read_rows(source / "stops.txt") if r["stop_id"] in stop_ids]
    fieldnames = (
        list(kept[0].keys()) if kept else read_header(source / "stops.txt")
    )
    write_rows(output / "stops.txt", fieldnames, kept)
    return len(kept)


def select_transfers(source: Path, output: Path, stop_ids: set[str]) -> int:
    src = source / "transfers.txt"
    if not src.exists():
        return 0
    kept = [
        r
        for r in read_rows(src)
        if r["from_stop_id"] in stop_ids and r["to_stop_id"] in stop_ids
    ]
    fieldnames = list(kept[0].keys()) if kept else read_header(src)
    write_rows(output / "transfers.txt", fieldnames, kept)
    return len(kept)


def select_feed(source: Path, output: Path, args) -> dict:
    output.mkdir(parents=True, exist_ok=True)

    # 1) calendar window first — trips reference services, so the surviving
    #    service set constrains which trips are even candidates.
    valid_service_ids, cal_report = select_calendar(source, output, args)

    # 2) route scope + route_type remap (held in memory, written after bbox pass).
    route_rows, route_fields, route_report = select_routes(source, args)
    route_ids = set(route_rows.keys())

    # 3) bbox stop membership.
    in_bbox_stops, stops_total, stops_missing = load_in_bbox_stops(source, args.bbox) \
        if args.bbox else (set(), 0, 0)

    # 4) candidate trips (route + service scoped).
    candidate_trips, trip_fields = load_candidate_trips(
        source, route_ids, valid_service_ids
    )

    # 5) bbox trip exclusion: drop whole trips that leave the box.
    if args.bbox:
        bad_trips = find_out_of_bbox_trips(
            source, set(candidate_trips.keys()), in_bbox_stops
        )
        final_trip_ids = set(candidate_trips.keys()) - bad_trips
    else:
        bad_trips = set()
        final_trip_ids = set(candidate_trips.keys())

    # 6) write trips (only surviving), then stream stop_times for them.
    write_rows(
        output / "trips.txt",
        trip_fields,
        [candidate_trips[t] for t in candidate_trips if t in final_trip_ids],
    )
    used_stop_ids, stop_times_written = write_stop_times(
        source, output, final_trip_ids
    )

    # 7) routes actually kept = those with >=1 surviving trip (report coverage loss).
    surviving_route_ids = {
        candidate_trips[t]["route_id"] for t in final_trip_ids
    }
    write_rows(
        output / "routes.txt",
        route_fields,
        [route_rows[r] for r in route_rows if r in surviving_route_ids],
    )

    stops_written = select_stops(source, output, used_stop_ids)
    transfers_written = select_transfers(source, output, used_stop_ids)

    # Small calendar file agency copied verbatim (calendar written above).
    for name in ("agency.txt", "feed_info.txt"):
        src = source / name
        if src.exists():
            shutil.copy2(src, output / name)

    report = {}
    report.update(cal_report)
    report.update(route_report)
    report.update(
        {
            "bbox": args.bbox,
            "stops_total_in_source": stops_total,
            "stops_missing_coord": stops_missing,
            "stops_in_bbox": len(in_bbox_stops) if args.bbox else "n/a",
            "trips_candidate": len(candidate_trips),
            "trips_excluded_out_of_bbox": len(bad_trips),
            "trips_kept": len(final_trip_ids),
            "routes_kept_with_trips": len(surviving_route_ids),
            "routes_dropped_no_trips": len(route_ids) - len(surviving_route_ids),
            "stop_times": stop_times_written,
            "stops": stops_written,
            "transfers": transfers_written,
        }
    )
    return report


def _parse_bbox(value: str):
    parts = [float(x) for x in value.split(",")]
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("bbox must be W,S,E,N")
    west, south, east, north = parts
    if west >= east or south >= north:
        raise argparse.ArgumentTypeError("bbox must satisfy W<E and S<N")
    return (west, south, east, north)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="KTDB GTFS directory")
    parser.add_argument("output", type=Path, help="generated GTFS directory")
    parser.add_argument(
        "--bbox",
        type=_parse_bbox,
        default=None,
        metavar="W,S,E,N",
        help="keep only trips whose every stop is inside this lon/lat box "
        "(e.g. 126.5,37.2,127.4,38.0). A trip with any out-of-bbox stop is "
        "dropped whole.",
    )
    parser.add_argument(
        "--calendar-center",
        type=_parse_yyyymmdd,
        default=date.today(),
        metavar="YYYYMMDD",
        help="center of the calendar window (default: build date = today).",
    )
    parser.add_argument(
        "--calendar-margin-days",
        type=int,
        default=62,
        metavar="DAYS",
        help="calendar window half-width in days (default 62 ~ 2 months). The "
        "window is [center-DAYS, center+DAYS]; services outside it are dropped.",
    )
    parser.add_argument(
        "--route-prefix",
        action="append",
        default=[],
        metavar="PREFIX",
        help="(legacy) keep only routes whose route_id starts with PREFIX. "
        "Prefer --bbox; a prefix alone does not decide 수도권 membership.",
    )
    parser.add_argument(
        "--keep-ktdb-type",
        action="append",
        default=[],
        metavar="KTDB_TYPE",
        help="keep only these KTDB route_type codes (repeatable, pre-remap). "
        "Default: all mapped types.",
    )
    args = parser.parse_args()

    report = select_feed(args.source, args.output, args)
    print("generated normalized feed")
    for key, value in report.items():
        print(f"{key}={value}")


if __name__ == "__main__":
    main()
