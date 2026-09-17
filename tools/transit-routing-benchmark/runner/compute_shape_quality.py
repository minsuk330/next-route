#!/usr/bin/env python3
"""Per-trip shape-quality gate for a pfaedle-shaped GTFS feed (P3, national-otp).

pfaedle map-matches a GTFS feed against an OSM road graph and writes
``shapes.txt``.  It shapes *every* bus trip it can, but a matched shape may still
be wrong (matched to the wrong parallel road, folded back on itself, anchored to
the wrong terminus).  OTP will happily return that shape's encoded polyline, and
OTP *also* returns a straight stop-to-stop polyline for an unshaped trip — so the
app can never tell "real shape" from "fallback" by looking at the geometry
(intent §5, PLAN safety contract).  The decision has to be made *here*, offline,
per trip, from the shaped feed alone.

This tool reads only the shaped GTFS (``shapes.txt`` + ``trips.txt`` +
``stop_times.txt`` + ``stops.txt`` + ``routes.txt`` for the bus filter) and
computes, for every bus trip:

* ``residual_p90`` / ``residual_max`` — stop → nearest-point-on-shape distance
  (metres).  A shape matched to the wrong road leaves its stops far from it.
* ``anchor_err`` — max(first stop→shape start, last stop→shape end) in metres.
  A shape anchored to the wrong terminus fails here even if the middle fits.
* ``detour_ratio`` — shape length / straight stop-to-stop length.  ~1.3-1.6 is a
  normal road detour; a huge or sub-1 ratio means a degenerate match.

No thresholds are baked in.  Run once over the whole feed with ``--report`` to
print the distribution (percentiles + histogram) of each metric and decide the
cut points *with the data in hand* (PLAN: "임계값은 전량 실행 분포 보고 확정").
Then pass ``--thresholds`` to emit ``coverage-index.json`` whose
``degradedTripIds`` = trips that fail any threshold (DEGRADED) ∪ trips pfaedle
left unshaped (MISSING).  Trips absent from that list are SHAPE_OK by default, so
a stale / mismatched index can only *under*-claim, never mismark (PLAN §safety).

Pure stdlib on purpose: the gate must run without a venv (no numpy/shapely), so
the release pipeline has no Python dependency to pin.  Distances use a single
local equirectangular projection centred on the feed; over a metropolitan bbox
its scale error is well under the metre-level tolerances that matter here.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

csv.field_size_limit(min(sys.maxsize, 2**31 - 1))

# Feed-local equirectangular projection anchor (수도권 중심). Scale error at the
# bbox edges (≈0.8° from centre) is <0.4%, far below the metre tolerances used.
LAT0 = 37.55
LON0 = 127.0
_KX = math.cos(math.radians(LAT0)) * 111320.0
_KY = 110540.0

# standard GTFS route_type (prepare_gtfs.py remaps KTDB→standard). 1:1 per mode —
# rail(2) is mapped but out of scope for Stage2 (P4 §A0: "1차는 subway 만",
# rail 2개 노선은 분포 보고 별도 판단). Mapping it here costs nothing and means
# adding rail later is a --modes arg change, not a code change.
MODE_ROUTE_TYPES: dict[str, str] = {"bus": "3", "subway": "1", "rail": "2"}
ROUTE_TYPE_MODE: dict[str, str] = {v: k for k, v in MODE_ROUTE_TYPES.items()}


def _to_m(lat: float, lon: float) -> tuple[float, float]:
    return ((lon - LON0) * _KX, (lat - LAT0) * _KY)


def _read_rows(path: Path):
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        yield from csv.DictReader(stream)


# --------------------------------------------------------------------------- #
# feed loading
# --------------------------------------------------------------------------- #

def _load_trip_modes(gtfs: Path, modes: list[str]) -> tuple[dict[str, str], dict[str, str]]:
    """Return (trip_id -> mode, trip_id -> shape_id or '') restricted to the given modes."""
    route_types = {MODE_ROUTE_TYPES[m] for m in modes}
    route_mode = {
        r["route_id"]: ROUTE_TYPE_MODE[r["route_type"]]
        for r in _read_rows(gtfs / "routes.txt")
        if r.get("route_type") in route_types
    }
    trip_mode: dict[str, str] = {}
    trip_shape: dict[str, str] = {}
    for r in _read_rows(gtfs / "trips.txt"):
        mode = route_mode.get(r["route_id"])
        if mode is not None:
            trip_mode[r["trip_id"]] = mode
            trip_shape[r["trip_id"]] = (r.get("shape_id") or "").strip()
    return trip_mode, trip_shape


def _load_stops(gtfs: Path) -> dict[str, tuple[float, float]]:
    """stop_id -> (x, y) metres."""
    stops: dict[str, tuple[float, float]] = {}
    for r in _read_rows(gtfs / "stops.txt"):
        lat_s = (r.get("stop_lat") or "").strip()
        lon_s = (r.get("stop_lon") or "").strip()
        if not lat_s or not lon_s:
            continue
        try:
            stops[r["stop_id"]] = _to_m(float(lat_s), float(lon_s))
        except ValueError:
            continue
    return stops


def _load_shapes(gtfs: Path) -> dict[str, list[tuple[float, float]]]:
    """shape_id -> ordered [(x, y) metres]."""
    raw: dict[str, list[tuple[int, float, float]]] = defaultdict(list)
    for r in _read_rows(gtfs / "shapes.txt"):
        raw[r["shape_id"]].append(
            (
                int(r["shape_pt_sequence"]),
                float(r["shape_pt_lat"]),
                float(r["shape_pt_lon"]),
            )
        )
    shapes: dict[str, list[tuple[float, float]]] = {}
    for shape_id, pts in raw.items():
        pts.sort(key=lambda p: p[0])
        shapes[shape_id] = [_to_m(lat, lon) for _, lat, lon in pts]
    return shapes


def _load_trip_stop_sequences(
    gtfs: Path, trip_ids: set[str]
) -> dict[str, list[str]]:
    """trip_id -> stop_ids in stop_sequence order (restricted to the given trip_ids)."""
    raw: dict[str, list[tuple[int, str]]] = defaultdict(list)
    src = gtfs / "stop_times.txt"
    with src.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.reader(stream)
        header = next(reader)
        ti = header.index("trip_id")
        si = header.index("stop_id")
        qi = header.index("stop_sequence")
        for parts in reader:
            trip_id = parts[ti]
            if trip_id in trip_ids:
                raw[trip_id].append((int(parts[qi]), parts[si]))
    seqs: dict[str, list[str]] = {}
    for trip_id, rows in raw.items():
        rows.sort(key=lambda p: p[0])
        seqs[trip_id] = [s for _, s in rows]
    return seqs


# --------------------------------------------------------------------------- #
# geometry
# --------------------------------------------------------------------------- #

class ShapeGeom:
    """A polyline in metre space with cumulative lengths for along-projection."""

    __slots__ = ("pts", "cum", "length")

    def __init__(self, pts: list[tuple[float, float]]):
        self.pts = pts
        cum = [0.0]
        for (ax, ay), (bx, by) in zip(pts, pts[1:]):
            cum.append(cum[-1] + math.hypot(bx - ax, by - ay))
        self.cum = cum
        self.length = cum[-1] if cum else 0.0

    def distance(self, px: float, py: float) -> float:
        """Perpendicular distance from (px, py) to the polyline (metres).

        Unconstrained nearest-point: this is the honest "how far is this stop from
        the drawn route" residual. We deliberately do NOT try to enforce along-shape
        monotonicity here — most Korean bus routes are out-and-back / loop
        (기점→회차→기점) so the shape passes close to itself, and any greedy forward
        snap cascades (one early mis-snap forces every later stop far away). Reversal
        / wrong-terminus is caught by anchor_err instead, and a degenerate shape by
        detour_ratio; a low residual on a self-approaching shape is genuinely fine.
        """
        best_d = math.inf
        pts = self.pts
        for i in range(len(pts) - 1):
            ax, ay = pts[i]
            bx, by = pts[i + 1]
            dx = bx - ax
            dy = by - ay
            seg2 = dx * dx + dy * dy
            if seg2 == 0.0:
                t = 0.0
            else:
                t = ((px - ax) * dx + (py - ay) * dy) / seg2
                if t < 0.0:
                    t = 0.0
                elif t > 1.0:
                    t = 1.0
            cx = ax + t * dx
            cy = ay + t * dy
            d = math.hypot(px - cx, py - cy)
            if d < best_d:
                best_d = d
        return best_d


def _percentile(sorted_vals: list[float], pct: float) -> float:
    if not sorted_vals:
        return 0.0
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    k = (len(sorted_vals) - 1) * (pct / 100.0)
    lo = math.floor(k)
    hi = math.ceil(k)
    if lo == hi:
        return sorted_vals[int(k)]
    return sorted_vals[lo] * (hi - k) + sorted_vals[hi] * (k - lo)


# --------------------------------------------------------------------------- #
# per-trip metrics
# --------------------------------------------------------------------------- #

def _trip_metrics(
    stop_ids: list[str],
    geom: ShapeGeom,
    stops: dict[str, tuple[float, float]],
) -> dict | None:
    """Metrics for one shaped trip; None if too few located stops to judge."""
    pts = [stops[s] for s in stop_ids if s in stops]
    if len(pts) < 2 or geom.length == 0.0:
        return None

    residuals = sorted(geom.distance(px, py) for px, py in pts)

    (fx, fy), (lx, ly) = pts[0], pts[-1]
    (sx, sy), (ex, ey) = geom.pts[0], geom.pts[-1]
    anchor_err = max(math.hypot(fx - sx, fy - sy), math.hypot(lx - ex, ly - ey))

    straight = 0.0
    for (ax, ay), (bx, by) in zip(pts, pts[1:]):
        straight += math.hypot(bx - ax, by - ay)
    detour_ratio = geom.length / straight if straight > 0 else math.inf

    return {
        "residual_p90": _percentile(residuals, 90),
        "residual_max": residuals[-1],
        "anchor_err": anchor_err,
        "detour_ratio": detour_ratio,
        "n_stops": len(pts),
    }


METRIC_KEYS = ("residual_p90", "residual_max", "anchor_err", "detour_ratio")


def _distribution(values: list[float]) -> dict:
    vals = sorted(values)
    return {
        "n": len(vals),
        "p50": round(_percentile(vals, 50), 1),
        "p75": round(_percentile(vals, 75), 1),
        "p90": round(_percentile(vals, 90), 1),
        "p95": round(_percentile(vals, 95), 1),
        "p99": round(_percentile(vals, 99), 1),
        "max": round(vals[-1], 1) if vals else 0.0,
    }


def _histogram(values: list[float], edges: list[float]) -> list[tuple[str, int]]:
    counts = [0] * (len(edges) + 1)
    for v in values:
        placed = False
        for i, e in enumerate(edges):
            if v <= e:
                counts[i] += 1
                placed = True
                break
        if not placed:
            counts[-1] += 1
    out: list[tuple[str, int]] = []
    prev = "0"
    for e, c in zip(edges, counts):
        out.append((f"{prev}..{e}", c))
        prev = str(e)
    out.append((f">{edges[-1]}", counts[-1]))
    return out


HISTOGRAM_EDGES = {
    "residual_p90": [5, 10, 20, 50, 100, 200],
    "residual_max": [10, 25, 50, 100, 200, 500],
    "anchor_err": [5, 10, 25, 50, 100, 300],
    "detour_ratio": [1.1, 1.3, 1.6, 2.0, 3.0, 5.0],
}


# --------------------------------------------------------------------------- #
# classification / index
# --------------------------------------------------------------------------- #

def _is_degraded(m: dict, t: dict) -> bool:
    return (
        m["residual_p90"] > t.get("residual_p90", math.inf)
        or m["residual_max"] > t.get("residual_max", math.inf)
        or m["anchor_err"] > t.get("anchor_err", math.inf)
        or m["detour_ratio"] > t.get("detour_ratio_max", math.inf)
        or m["detour_ratio"] < t.get("detour_ratio_min", 0.0)
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("gtfs", type=Path, help="pfaedle-shaped GTFS directory")
    parser.add_argument(
        "--metrics-out",
        type=Path,
        default=None,
        help="write per-trip metrics as CSV (trip_id + metrics + status)",
    )
    parser.add_argument(
        "--thresholds",
        type=Path,
        default=None,
        help="JSON of gate thresholds; when given, emit coverage-index.json. "
        "Keys: residual_p90, residual_max, anchor_err, "
        "detour_ratio_max, detour_ratio_min (any subset).",
    )
    parser.add_argument(
        "--index-out",
        type=Path,
        default=None,
        help="coverage-index.json path (requires --thresholds and --graph-version)",
    )
    parser.add_argument(
        "--graph-version",
        default=None,
        help="graphVersion string stamped into coverage-index.json",
    )
    parser.add_argument(
        "--modes",
        default="bus",
        help="comma-separated GTFS modes to load: bus,subway,rail (default: bus, "
        "P3 Stage1 behavior). coveredModes/counts in coverage-index.json are "
        "derived from this list — see P4 PLAN §A0.",
    )
    args = parser.parse_args()

    modes = sorted({m.strip() for m in args.modes.split(",") if m.strip()})
    unknown = [m for m in modes if m not in MODE_ROUTE_TYPES]
    if unknown:
        parser.error(f"unknown mode(s): {unknown}. known: {sorted(MODE_ROUTE_TYPES)}")
    if args.thresholds and len(modes) > 1:
        # 플랜 §A1: "지하철 전용 임계를 따로 둘지 결정한다(버스 임계 재사용 금지)". 지금
        # thresholds 는 mode 무관 flat dict 하나뿐이라, 여러 mode 에 그대로 적용하면
        # 부적합한 임계가 조용히 새는 구멍이 된다. mode 별 임계 구조는 P4 §A2 의 몫.
        parser.error(
            "--thresholds only supports a single mode at a time for now — a flat "
            "threshold dict can't be safely reused across modes (P4 PLAN §A1: "
            "'버스 임계 재사용 금지'). Run per mode until §A2's mode-keyed "
            "threshold structure lands."
        )

    gtfs = args.gtfs
    print(f"loading shaped feed: {gtfs}  modes={modes}", file=sys.stderr)
    trip_mode, trip_shape = _load_trip_modes(gtfs, modes)
    stops = _load_stops(gtfs)
    shapes = _load_shapes(gtfs)
    seqs = _load_trip_stop_sequences(gtfs, set(trip_mode))
    print(
        f"trips={len(trip_mode)} shapes={len(shapes)} stops={len(stops)}",
        file=sys.stderr,
    )

    # Cache geometry per shape_id and metrics per (shape_id, stop-sequence): many
    # trips share both, so this collapses ~150k trips to a few thousand solves.
    geom_cache: dict[str, ShapeGeom] = {}
    metric_cache: dict[tuple, dict | None] = {}

    missing: dict[str, list[str]] = defaultdict(list)    # pfaedle left unshaped, by mode
    unjudged: dict[str, list[str]] = defaultdict(list)   # shaped but <2 located stops, by mode
    per_trip: dict[str, dict] = {}                       # trip_id -> metrics (any mode)
    for trip_id, mode in trip_mode.items():
        shape_id = trip_shape.get(trip_id, "")
        if not shape_id or shape_id not in shapes:
            missing[mode].append(trip_id)
            continue
        stop_ids = seqs.get(trip_id, [])
        key = (shape_id, tuple(stop_ids))
        if key not in metric_cache:
            geom = geom_cache.get(shape_id)
            if geom is None:
                geom = ShapeGeom(shapes[shape_id])
                geom_cache[shape_id] = geom
            metric_cache[key] = _trip_metrics(stop_ids, geom, stops)
        m = metric_cache[key]
        if m is None:
            unjudged[mode].append(trip_id)
            continue
        per_trip[trip_id] = m

    # ---- distribution report (always, one block per mode) ----
    for mode in modes:
        mode_total = sum(1 for mo in trip_mode.values() if mo == mode)
        mode_judged = {t: m for t, m in per_trip.items() if trip_mode[t] == mode}
        print(f"\n=== shape-quality distribution ({mode} trips) ===")
        print(
            f"judged={len(mode_judged)}  missing(unshaped)={len(missing[mode])}  "
            f"unjudged(<2 stops)={len(unjudged[mode])}  {mode}_total={mode_total}"
        )
        for key in METRIC_KEYS:
            vals = [m[key] for m in mode_judged.values() if math.isfinite(m[key])]
            dist = _distribution(vals)
            print(f"\n{key}: {dist}")
            for label, count in _histogram(vals, HISTOGRAM_EDGES[key]):
                bar = "#" * min(60, count * 60 // max(1, len(vals)))
                print(f"  {label:>12} | {count:>7} {bar}")

    # ---- optional per-trip CSV ----
    thresholds = json.loads(args.thresholds.read_text()) if args.thresholds else None
    if args.metrics_out:
        with args.metrics_out.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.writer(stream)
            writer.writerow(["trip_id", "mode", *METRIC_KEYS, "n_stops", "status"])
            for mode in modes:
                for trip_id in missing[mode]:
                    writer.writerow([trip_id, mode, *[""] * len(METRIC_KEYS), "", "MISSING"])
                for trip_id in unjudged[mode]:
                    writer.writerow([trip_id, mode, *[""] * len(METRIC_KEYS), "", "UNJUDGED"])
            for trip_id, m in per_trip.items():
                status = "SHAPE_OK"
                if thresholds and _is_degraded(m, thresholds):
                    status = "DEGRADED"
                writer.writerow(
                    [trip_id, trip_mode[trip_id], *[round(m[k], 2) for k in METRIC_KEYS],
                     m["n_stops"], status]
                )
        print(f"\nwrote per-trip metrics: {args.metrics_out}")

    # ---- optional coverage-index (single-mode only, see guard above) ----
    if thresholds:
        if not args.index_out or not args.graph_version:
            parser.error("--thresholds requires --index-out and --graph-version")
        degraded = [t for t, m in per_trip.items() if _is_degraded(m, thresholds)]
        total_missing = sum(len(v) for v in missing.values())
        total_unjudged = sum(len(v) for v in unjudged.values())
        # DEGRADED ∪ MISSING ∪ UNJUDGED — anything not provably good falls back.
        degraded_ids = sorted(
            set(degraded) | {t for v in missing.values() for t in v}
            | {t for v in unjudged.values() for t in v}
        )
        index = {
            "graphVersion": args.graph_version,
            "coveredModes": modes,
            "thresholds": thresholds,
            "counts": {
                **{f"{mode}Trips": sum(1 for mo in trip_mode.values() if mo == mode)
                   for mode in modes},
                "shapeOk": len(per_trip) - len(degraded),
                "degraded": len(degraded),
                "missing": total_missing,
                "unjudged": total_unjudged,
            },
            "degradedTripIds": degraded_ids,
        }
        args.index_out.write_text(json.dumps(index, ensure_ascii=False, indent=2))
        print(
            f"\nwrote coverage-index: {args.index_out}  "
            f"SHAPE_OK={index['counts']['shapeOk']}  "
            f"degraded={index['counts']['degraded']}  "
            f"missing={index['counts']['missing']}"
        )


if __name__ == "__main__":
    main()
