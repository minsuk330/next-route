#!/usr/bin/env python3
"""Put platforms that the feed declares as a transfer pair on one point.

The KTDB feed lists rail transfers in ``transfers.txt`` (신도림 2호선 ↔ 1호선, 68 s) but gives each line's
platform its own coordinate.  OTP builds stop-to-stop transfers by walking the street network from each
platform's street link, and ``transfers.txt`` only constrains a transfer that already exists.  When the two
platforms link to streets on opposite sides of the tracks the walk is far longer than ``maxTransferDuration``
(10 min) and the transfer is silently dropped:

* 신도림 2호선 ↔ 1호선: 77 m apart, 1,734 m on the street network (2026-11-16, production graph).  구로디지털단지 →
  송내 came out as 2호선 → bus → 1호선 in 41 min; KakaoMap gives 2호선 → 신도림 → 1호선 in 25 min.
* 옥수 3호선 ↔ 경의중앙선 3,483 m, 대곡 3호선 ↔ GTX-A 3,128 m, 온수 1호선 ↔ 7호선 2,052 m, 석남, 을지로3가, 초지 ...

Pairs whose platforms already share a coordinate (왕십리, 용산, 금정) work.  So this script moves the platforms
of a declared transfer group to the group's centroid.  The platforms then share a street link, the walk is
~0 m, and the feed's ``min_transfer_time`` is what the transfer costs.

Only groups named in ``--stops-file`` are moved (one stop id per line, ``#`` comments).  Most stations transfer
fine over the street network (잠실 2호선 ↔ 8호선 390 m), and moving a platform 200 m shifts every walk that
starts or ends at it, so the fix is limited to the stations measured as broken
(``transfer-platform-fixes.txt``: street walk > 3 × straight line + 150 m on the production graph).  Without
the file every declared group is moved.

Rules
* A group is the connected component of ``transfers.txt`` pairs (``transfer_type`` 2 or empty).
* Groups wider than ``--max-span-m`` are left alone and reported: that is a data error, not a station.
* Generated copies follow their platform: ``VST_<P>`` and ``VSA_P_<P>`` rows (``add_station_entrances.py``)
  get the same coordinate, whichever script ran first.  Exits (``VEX_*``, ``VSA_X_*``) are not moved.
* Every other file in the zip is copied byte for byte.  Stdlib only.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import math
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

COPY_PREFIXES = ("VST_", "VSA_P_")


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    k = math.cos(math.radians((lat1 + lat2) / 2))
    return math.hypot((lat1 - lat2) * 111_320, (lon1 - lon2) * 111_320 * k)


def transfer_groups(transfers: list[dict[str, str]], platform_ids: set[str]) -> list[list[str]]:
    """Connected components of declared transfer pairs, platforms only, sorted for stable output."""
    parent: dict[str, str] = {}

    def find(x: str) -> str:
        while parent.setdefault(x, x) != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for row in transfers:
        a, b = row.get("from_stop_id", ""), row.get("to_stop_id", "")
        if a == b or a not in platform_ids or b not in platform_ids:
            continue
        if row.get("transfer_type", "") not in ("", "0", "2"):
            continue
        parent[find(a)] = find(b)
    groups: dict[str, list[str]] = defaultdict(list)
    for stop_id in parent:
        groups[find(stop_id)].append(stop_id)
    return sorted(sorted(members) for members in groups.values() if len(members) > 1)


def colocate(stops: list[dict[str, str]], transfers: list[dict[str, str]], max_span_m: float,
             only: set[str] | None = None) -> dict:
    """Mutates ``stops`` in place.  ``only``: move a group only if it contains one of these stop ids."""
    by_id = {row["stop_id"]: row for row in stops}
    platform_ids = {row["stop_id"] for row in stops
                    if row.get("location_type", "") in ("", "0") and not row["stop_id"].startswith(("VEX_", "VSA_"))}
    moved, skipped, unchanged = [], [], 0
    for members in transfer_groups(transfers, platform_ids):
        points = [(float(by_id[m]["stop_lat"]), float(by_id[m]["stop_lon"])) for m in members]
        span = max(distance_m(*a, *b) for a in points for b in points)
        if span < 1.0:
            unchanged += 1
            continue
        if only is not None and not only.intersection(members):
            continue
        if span > max_span_m:
            skipped.append({"stops": members, "names": [by_id[m]["stop_name"] for m in members], "span_m": round(span)})
            continue
        lat = sum(p[0] for p in points) / len(points)
        lon = sum(p[1] for p in points) / len(points)
        for member in members:
            for stop_id in (member, *(prefix + member for prefix in COPY_PREFIXES)):
                row = by_id.get(stop_id)
                if row is not None:
                    row["stop_lat"], row["stop_lon"] = f"{lat:.6f}", f"{lon:.6f}"
        moved.append({"stops": members, "names": [by_id[m]["stop_name"] for m in members], "span_m": round(span),
                      "lat": round(lat, 6), "lon": round(lon, 6)})
    return {"moved_groups": len(moved), "already_colocated_groups": unchanged, "skipped_groups": skipped,
            "moved": moved}


def read_csv(zf: zipfile.ZipFile, name: str) -> tuple[list[str], list[dict[str, str]]]:
    with zf.open(name) as fh:
        reader = csv.DictReader(io.TextIOWrapper(fh, encoding="utf-8-sig", newline=""))
        rows = list(reader)
        return list(reader.fieldnames or []), rows


def read_stop_ids(path: Path) -> set[str]:
    return {line.split("#", 1)[0].strip() for line in path.read_text(encoding="utf-8").splitlines()
            if line.split("#", 1)[0].strip()}


def run(in_zip: Path, out_zip: Path, report_path: Path | None, max_span_m: float,
        only: set[str] | None = None) -> dict:
    with zipfile.ZipFile(in_zip) as zf:
        fields, stops = read_csv(zf, "stops.txt")
        transfers = read_csv(zf, "transfers.txt")[1] if "transfers.txt" in zf.namelist() else []
    report = colocate(stops, transfers, max_span_m, only)
    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=fields, lineterminator="\n")
    writer.writeheader()
    writer.writerows(stops)
    out_zip.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(in_zip) as src, zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            if info.filename == "stops.txt":
                dst.writestr("stops.txt", buffer.getvalue())
            else:
                with src.open(info) as fh, dst.open(info, "w") as out:
                    while chunk := fh.read(1 << 20):
                        out.write(chunk)
    if report_path is not None:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--in-zip", type=Path, required=True)
    parser.add_argument("--out-zip", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--max-span-m", type=float, default=500.0,
                        help="groups wider than this are left alone and reported (default 500)")
    parser.add_argument("--stops-file", type=Path,
                        help="move only groups containing one of these stop ids (one per line)")
    args = parser.parse_args()
    only = read_stop_ids(args.stops_file) if args.stops_file else None
    report = run(args.in_zip, args.out_zip, args.report, args.max_span_m, only)
    print(f"moved {report['moved_groups']} groups, already colocated {report['already_colocated_groups']}, "
          f"skipped {len(report['skipped_groups'])}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
