#!/usr/bin/env python3
"""Telemetry: pfaedle shapes vs the existing VPS route polylines (P3, national-otp).

REPORT ONLY — this is *not* the gate. The gate (compute_shape_quality.py) judges a
shape on its own intrinsic fit to the stops, because there is no oracle for the
"true" route (intent §5, PLAN 설계원칙 #2: VPS is not an oracle — 96% of its
segments are quality_status=REVIEW). This script exists to *watch* how far the new
pfaedle base drifts from the old VPS lines, per route, so a human can eyeball the
agreement distribution — nothing here changes SHAPE_OK / DEGRADED.

Inputs
------
* shaped GTFS dir (pfaedle output: shapes.txt + trips.txt + routes.txt).
* VPS export (PSV, one row per segment):  ``route_id|seq|quality_status|geojson``
  where route_id is the 9-digit 서울 id (GTFS ``BR_TAGO_SEB<route_id>``), geojson
  is a LineString ``{"coordinates": [[lon,lat],...]}``. Produce it on the VPS:

      ssh vps "psql -U myuser -d mydatabase -At -F'|' -c \\
        \"select route_id, from_seq, quality_status, ST_AsGeoJSON(geom) \\
          from bus_route_segment_polyline order by route_id, from_seq\"" \\
        > results/vps_lines.psv

Output: ``results/pfaedle-vs-vps.json`` — per route, comparing OK VPS segments
against the best-matching pfaedle shape: mean / p90 / max point-to-line residual
and Hausdorff distance (metres).

Needs shapely + numpy (unlike the stdlib-only gate). Set up a venv:
    python3 -m venv .venv && .venv/bin/pip install shapely numpy
    .venv/bin/python runner/compare_pfaedle_vps.py ...
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from shapely.geometry import LineString, Point

csv.field_size_limit(min(sys.maxsize, 2**31 - 1))

LAT0 = 37.55
LON0 = 127.0
_KX = math.cos(math.radians(LAT0)) * 111320.0
_KY = 110540.0


def _to_m(lat: float, lon: float) -> tuple[float, float]:
    return ((lon - LON0) * _KX, (lat - LAT0) * _KY)


def _load_route_shapes(gtfs: Path):
    """(shape_id -> [(lat,lon)]), (route_id -> Counter(shape_id))."""
    shapes_raw: dict[str, list[tuple[int, float, float]]] = defaultdict(list)
    for r in csv.DictReader((gtfs / "shapes.txt").open(encoding="utf-8-sig")):
        shapes_raw[r["shape_id"]].append(
            (int(r["shape_pt_sequence"]), float(r["shape_pt_lat"]), float(r["shape_pt_lon"]))
        )
    shapes = {
        k: [(la, lo) for _, la, lo in sorted(v)] for k, v in shapes_raw.items()
    }
    route_shapes: dict[str, Counter] = defaultdict(Counter)
    for r in csv.DictReader((gtfs / "trips.txt").open(encoding="utf-8-sig")):
        if r.get("shape_id"):
            route_shapes[r["route_id"]][r["shape_id"]] += 1
    return shapes, route_shapes


def _load_vps(psv: Path):
    """seoul route_id -> list[(seq, quality, coords[[lon,lat]])]."""
    vps: dict[str, list] = defaultdict(list)
    with psv.open(encoding="utf-8") as stream:
        for line in stream:
            parts = line.rstrip("\n").split("|", 3)
            if len(parts) < 4:
                continue
            rid, seq, quality, geojson = parts
            try:
                coords = json.loads(geojson)["coordinates"]
            except Exception:
                continue
            vps[rid].append((int(seq), quality, coords))
    return vps


def _compare_route(seoul_id: str, segs, shapes, route_shapes) -> dict | None:
    gtfs_rid = "BR_TAGO_SEB" + seoul_id
    ok_coords = [c for _, q, c in sorted(segs) if q == "OK"]
    review = sum(1 for _, q, _ in segs if q != "OK")
    vpts = [(la, lo) for c in ok_coords for lo, la in c]
    if len(vpts) < 2 or gtfs_rid not in route_shapes:
        return {"seoul_id": seoul_id, "status": "no_overlap",
                "vps_ok_segments": len(ok_coords), "vps_review_segments": review}

    vps_line = LineString([_to_m(la, lo) for la, lo in vpts])
    best = None
    for shape_id, _ in route_shapes[gtfs_rid].most_common():
        pl = LineString([_to_m(la, lo) for la, lo in shapes[shape_id]])
        residuals = [pl.distance(Point(_to_m(la, lo))) for la, lo in vpts]
        mean = float(np.mean(residuals))
        if best is None or mean < best[0]:
            best = (mean, shape_id, residuals, pl)
    mean, shape_id, residuals, pl = best
    return {
        "seoul_id": seoul_id,
        "status": "ok",
        "vps_ok_segments": len(ok_coords),
        "vps_review_segments": review,
        "shape_id": shape_id,
        "shape_variants": len(route_shapes[gtfs_rid]),
        "mean_m": round(mean, 1),
        "p90_m": round(float(np.percentile(residuals, 90)), 1),
        "max_m": round(float(np.max(residuals)), 1),
        "hausdorff_m": round(float(vps_line.hausdorff_distance(pl)), 1),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("gtfs", type=Path, help="pfaedle-shaped GTFS directory")
    parser.add_argument("vps_export", type=Path, help="VPS PSV export (see docstring)")
    parser.add_argument("--out", type=Path, default=Path("results/pfaedle-vs-vps.json"))
    parser.add_argument(
        "--routes", nargs="*", default=None,
        help="limit to these 9-digit 서울 ids (default: all VPS routes)",
    )
    args = parser.parse_args()

    shapes, route_shapes = _load_route_shapes(args.gtfs)
    vps = _load_vps(args.vps_export)
    seoul_ids = args.routes or sorted(vps)

    rows = [
        _compare_route(sid, vps[sid], shapes, route_shapes)
        for sid in seoul_ids
        if sid in vps
    ]
    matched = [r for r in rows if r and r["status"] == "ok"]
    summary = {}
    if matched:
        means = [r["mean_m"] for r in matched]
        summary = {
            "routes_compared": len(matched),
            "routes_no_overlap": len(rows) - len(matched),
            "mean_of_route_means_m": round(float(np.mean(means)), 1),
            "p90_of_route_means_m": round(float(np.percentile(means, 90)), 1),
            "max_route_mean_m": round(float(np.max(means)), 1),
        }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"summary": summary, "routes": rows},
                                   ensure_ascii=False, indent=2))
    print(f"wrote {args.out}")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
