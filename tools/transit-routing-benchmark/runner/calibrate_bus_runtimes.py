#!/usr/bin/env python3
"""Re-time bus stop_times: official section times where known, hop distance + stop count elsewhere.

KTDB bus stop_times are estimates (collected departure times + a time-of-day speed DB x distance + a fixed
10 s dwell), not measured runs.  OTP bus rides came out 35-39 % shorter than ODsay for the same route and
stops (2026-10-14, 60 OD / 991 legs; 421 강남 → 서울역 OTP 24 min, ODsay 34, our measurements 38-44), so
buses ranked ahead of the subway and every bus ETA was early (issue #405).

Each hop (stop -> next stop) of a bus trip in a listed region gets a new running time:

* **section time** — when ``--section-times`` has the stop pair (Seoul TOPIS ``tpssRouteSectionTime``:
  hourly average running time per stop pair, built by ``fetch_seoul_section_times.py``), the hop is
  ``sectionTimes.scale`` x the value for the hour the bus leaves the stop (any-hour median when that hour is
  missing).  This carries real congestion (테헤란로 slower than an open road), which a formula cannot.
* **formula** — otherwise ``(secPerMeter x hop metres + secPerHop) x trip factor``.  KTDB's per-hop time
  carries almost no information about ODsay's (correlation of segment speeds 0.07), distance and stop count
  do.  hop metres = ``shape_dist_traveled`` difference, or the straight line x ``detourFactor`` when the feed
  has no usable distance.  trip factor = this trip's original running time / the median running time of its
  pattern's trips leaving inside ``todWindow`` (clamped to ``todClamp``), which keeps KTDB's time-of-day shape.

The first departure and every dwell (departure - arrival) are kept, so headways, first/last bus and the
10 s dwell stay as KTDB has them.  Only ``route_type`` 3 routes whose ``route_id`` starts with a region prefix
change; subway, rail, intercity (``BR_ACC3_``) and unlisted regions are copied unchanged.

Overtaking guard: OTP expects trips of one pattern not to overtake.  Within a pattern trips are walked in
departure order; when a trip would arrive at (or leave) a stop earlier than the trip before it, the rest of
the trip is pushed later by the difference.  The result is checked again and the build fails if any trip
still overtakes.

Every other file in the zip is copied byte for byte.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import statistics
import sys
import zipfile
from array import array
from dataclasses import dataclass, field
from pathlib import Path

BUS_ROUTE_TYPE = "3"
SEC_PER_METER_RANGE = (0.03, 0.3)  # cruise 12-120 km/h
SEC_PER_HOP_RANGE = (0.0, 120.0)
SECTION_SCALE_RANGE = (0.5, 1.5)
SECTION_VS_FORMULA = (0.2, 5.0)  # a section time this far from the formula is treated as bad data


@dataclass(frozen=True)
class Region:
    name: str
    prefixes: tuple[str, ...]
    sec_per_meter: float
    sec_per_hop: float


@dataclass(frozen=True)
class Coefficients:
    regions: tuple[Region, ...]
    tod_window: tuple[int, int]
    tod_clamp: tuple[float, float]
    detour_factor: float
    section_scale: float = 1.0
    section_stop_prefixes: tuple[str, ...] = ()

    def region_of(self, route_id: str) -> Region | None:
        best: tuple[int, Region] | None = None
        for r in self.regions:
            for p in r.prefixes:
                if route_id.startswith(p) and (best is None or len(p) > best[0]):
                    best = (len(p), r)
        return best[1] if best else None


@dataclass(frozen=True)
class SectionTimes:
    """Stop pair -> 24 hourly seconds (None = no bus that hour) + any-hour median, keys without id prefix."""
    scale: float
    stop_prefixes: tuple[str, ...]
    by_hour: dict[tuple[str, str], list[float | None]]
    any_hour: dict[tuple[str, str], float]
    sha256: str = ""

    def _key(self, stop_id: str) -> str | None:
        for p in self.stop_prefixes:
            if stop_id.startswith(p):
                return stop_id[len(p):]
        return None

    def seconds(self, a: str, b: str, hour: int) -> float | None:
        ka, kb = self._key(a), self._key(b)
        if ka is None or kb is None:
            return None
        hours = self.by_hour.get((ka, kb))
        if hours is None:
            return None
        v = hours[hour % 24]
        if v is None:
            v = self.any_hour[(ka, kb)]
        return self.scale * v


def parse_time(s: str) -> int:
    h, m, sec = s.strip().split(":")
    return int(h) * 3600 + int(m) * 60 + int(sec)


def format_time(t: int) -> str:
    return f"{t // 3600:02d}:{t % 3600 // 60:02d}:{t % 60:02d}"


def load_coefficients(path: Path) -> Coefficients:
    doc = json.loads(path.read_text(encoding="utf-8"))
    regions = []
    seen_prefixes: set[str] = set()
    for name, r in doc["regions"].items():
        spm, sph = float(r["secPerMeter"]), float(r["secPerHop"])
        if not SEC_PER_METER_RANGE[0] <= spm <= SEC_PER_METER_RANGE[1]:
            raise ValueError(f"{name}: secPerMeter {spm} outside {SEC_PER_METER_RANGE}")
        if not SEC_PER_HOP_RANGE[0] <= sph <= SEC_PER_HOP_RANGE[1]:
            raise ValueError(f"{name}: secPerHop {sph} outside {SEC_PER_HOP_RANGE}")
        prefixes = tuple(r["routeIdPrefixes"])
        if not prefixes:
            raise ValueError(f"{name}: no routeIdPrefixes")
        dup = seen_prefixes.intersection(prefixes)
        if dup:
            raise ValueError(f"{name}: prefix listed twice {sorted(dup)}")
        seen_prefixes.update(prefixes)
        regions.append(Region(name, prefixes, spm, sph))
    lo, hi = doc.get("todWindow", ["10:00:00", "16:00:00"])
    cl = doc.get("todClamp", [0.7, 1.4])
    if not 0 < cl[0] <= 1 <= cl[1]:
        raise ValueError(f"todClamp {cl} must contain 1")
    sec = doc.get("sectionTimes", {})
    scale = float(sec.get("scale", 1.0))
    if not SECTION_SCALE_RANGE[0] <= scale <= SECTION_SCALE_RANGE[1]:
        raise ValueError(f"sectionTimes.scale {scale} outside {SECTION_SCALE_RANGE}")
    return Coefficients(tuple(regions), (parse_time(lo), parse_time(hi)), (float(cl[0]), float(cl[1])),
                        float(doc.get("detourFactor", 1.25)), scale, tuple(sec.get("stopIdPrefixes", ())))


def load_section_times(path: Path, coef: Coefficients) -> SectionTimes:
    """CSV from_stop,to_stop,hour,seconds; hour 0-23 or ``all`` (any-hour median, required per pair)."""
    if not coef.section_stop_prefixes:
        raise ValueError("--section-times needs sectionTimes.stopIdPrefixes in the coefficients")
    raw = path.read_bytes()
    by_hour: dict[tuple[str, str], list[float | None]] = {}
    any_hour: dict[tuple[str, str], float] = {}
    for r in csv.DictReader(io.StringIO(raw.decode("utf-8-sig"))):
        k = (r["from_stop"], r["to_stop"])
        v = float(r["seconds"])
        if v <= 0:
            raise ValueError(f"section time {k} hour {r['hour']}: {v} s")
        if r["hour"] == "all":
            any_hour[k] = v
        else:
            by_hour.setdefault(k, [None] * 24)[int(r["hour"])] = v
    missing = [k for k in by_hour if k not in any_hour]
    if missing:
        raise ValueError(f"{len(missing)} section pairs have no 'all' row, e.g. {missing[0]}")
    for k in any_hour:
        by_hour.setdefault(k, [None] * 24)
    return SectionTimes(coef.section_scale, coef.section_stop_prefixes, by_hour, any_hour,
                        hashlib.sha256(raw).hexdigest())


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


@dataclass
class TripPlan:
    """What pass 1 learns about one in-scope trip; pass 2 writes ``arr`` (+ the original dwells)."""
    region: Region
    pattern: tuple[str, int, int]  # (route_id, stop count, hash of stop ids)
    dep0: int
    run_orig: int
    section: array  # hop k: scaled section seconds, or -1 when the formula applies
    formula: array  # hop k: formula seconds before the trip factor
    dwell: array  # stop k: departure - arrival
    factor: float = 1.0
    arr: array | None = None  # new arrival per stop


@dataclass
class Report:
    regions: dict[str, dict] = field(default_factory=dict)
    fallback_hops: int = 0
    clamped_factors: int = 0
    section_hops: int = 0
    formula_hops: int = 0
    section_rejected: int = 0
    guard_raised: int = 0
    overtaking_after: int = 0


def trip_rows_sorted(rows: list[list[str]], seq_i: int) -> list[list[str]]:
    return sorted(rows, key=lambda r: int(r[seq_i]))


def hop_metres(rows: list[list[str]], idx: dict[str, int], stops: dict[str, tuple[float, float]],
               detour: float, report: Report) -> list[float]:
    out = []
    dist_i = idx.get("shape_dist_traveled")
    for a, b in zip(rows, rows[1:]):
        d = None
        if dist_i is not None and a[dist_i].strip() and b[dist_i].strip():
            dd = float(b[dist_i]) - float(a[dist_i])
            if dd > 0:
                d = dd
        if d is None:
            sa, sb = stops.get(a[idx["stop_id"]]), stops.get(b[idx["stop_id"]])
            d = haversine_m(*sa, *sb) * detour if sa and sb else 0.0
            report.fallback_hops += 1
        out.append(d)
    return out


def read_csv(zf: zipfile.ZipFile, name: str) -> list[dict[str, str]]:
    with zf.open(name) as f:
        return list(csv.DictReader(io.TextIOWrapper(f, encoding="utf-8-sig")))


def read_stops(zf: zipfile.ZipFile) -> dict[str, tuple[float, float]]:
    return {s["stop_id"]: (float(s["stop_lat"]), float(s["stop_lon"])) for s in read_csv(zf, "stops.txt")
            if s.get("stop_lat") and s.get("stop_lon")}


def iter_trips(zf: zipfile.ZipFile):
    """Yield (header, trip_id, rows) for stop_times.txt grouped by trip; fail if a trip reappears."""
    with zf.open("stop_times.txt") as f:
        rd = csv.reader(io.TextIOWrapper(f, encoding="utf-8-sig", newline=""))
        header = next(rd)
        ti = header.index("trip_id")
        cur, rows, done = None, [], set()
        for row in rd:
            if row[ti] != cur:
                if rows:
                    yield header, cur, rows
                    done.add(cur)
                cur, rows = row[ti], []
                if cur in done:
                    raise ValueError(f"stop_times.txt is not grouped by trip: {cur} reappears")
            rows.append(row)
        if rows:
            yield header, cur, rows


def plan_trip(rows: list[list[str]], idx: dict[str, int], rid: str, reg: Region, coef: Coefficients,
              stops: dict[str, tuple[float, float]], sections: SectionTimes | None, report: Report) -> TripPlan:
    arr = [parse_time(r[idx["arrival_time"]]) for r in rows]
    dep = [parse_time(r[idx["departure_time"]]) for r in rows]
    sid = [r[idx["stop_id"]] for r in rows]
    section, formula = array("f"), array("f")
    for k, m in enumerate(hop_metres(rows, idx, stops, coef.detour_factor, report)):
        f = reg.sec_per_meter * m + reg.sec_per_hop
        s = sections.seconds(sid[k], sid[k + 1], dep[k] // 3600) if sections else None
        if s is not None and not SECTION_VS_FORMULA[0] <= s / max(f, 1.0) <= SECTION_VS_FORMULA[1]:
            report.section_rejected += 1
            s = None
        section.append(s if s is not None else -1.0)
        formula.append(f)
        if s is None:
            report.formula_hops += 1
        else:
            report.section_hops += 1
    run = sum(arr[k] - dep[k - 1] for k in range(1, len(rows)))
    pattern = (rid, len(rows), hash(tuple(sid)))  # stop ids kept out of memory
    return TripPlan(reg, pattern, dep[0], run, section, formula, array("i", (d - a for a, d in zip(arr, dep))))


def plan_trips(zf: zipfile.ZipFile, coef: Coefficients, stops: dict[str, tuple[float, float]],
               sections: SectionTimes | None, report: Report) -> dict[str, TripPlan]:
    routes = {r["route_id"]: r for r in read_csv(zf, "routes.txt")}
    route_region: dict[str, Region] = {}
    for rid, r in routes.items():
        if r.get("route_type") == BUS_ROUTE_TYPE:
            reg = coef.region_of(rid)
            if reg:
                route_region[rid] = reg
    for reg in coef.regions:
        n = sum(1 for v in route_region.values() if v is reg)
        if n == 0:
            raise ValueError(f"region {reg.name}: no bus route matches {reg.prefixes}")
        report.regions[reg.name] = dict(routes=n, trips=0)
    trip_route = {t["trip_id"]: t["route_id"] for t in read_csv(zf, "trips.txt") if t["route_id"] in route_region}
    plans: dict[str, TripPlan] = {}
    for header, tid, rows in iter_trips(zf):
        rid = trip_route.get(tid)
        if rid is None:
            continue
        idx = {h: i for i, h in enumerate(header)}
        rows = trip_rows_sorted(rows, idx["stop_sequence"])
        plans[tid] = plan_trip(rows, idx, rid, route_region[rid], coef, stops, sections, report)
        report.regions[route_region[rid].name]["trips"] += 1
    assign_times(plans, coef, report)
    return plans


def new_arrivals(p: TripPlan) -> array:
    """Arrival per stop: first stop kept, then hop by hop with the original dwells."""
    out = array("i", [p.dep0 - p.dwell[0]])
    cum, cum_dwell = 0.0, 0
    for k in range(len(p.formula)):
        s = p.section[k]
        cum += s if s >= 0 else p.formula[k] * p.factor
        if k > 0:
            cum_dwell += p.dwell[k]
        out.append(p.dep0 + round(cum) + cum_dwell)
    return out


def assign_times(plans: dict[str, TripPlan], coef: Coefficients, report: Report) -> None:
    by_pattern: dict[tuple[str, int, int], list[tuple[str, TripPlan]]] = {}
    for tid, p in plans.items():
        by_pattern.setdefault(p.pattern, []).append((tid, p))
    lo, hi = coef.tod_window
    cmin, cmax = coef.tod_clamp
    for trips in by_pattern.values():
        mid = [t.run_orig for _, t in trips if lo <= t.dep0 < hi and t.run_orig > 0]
        allr = [t.run_orig for _, t in trips if t.run_orig > 0]
        ref = statistics.median(mid) if mid else (statistics.median(allr) if allr else 0)
        for _, t in trips:
            f = t.run_orig / ref if ref > 0 else 1.0
            if f < cmin or f > cmax:
                report.clamped_factors += 1
                f = min(max(f, cmin), cmax)
            t.factor = f
            t.arr = new_arrivals(t)
            t.section = t.formula = None  # free memory; arr is all pass 2 needs
        trips.sort(key=lambda x: (x[1].dep0, x[0]))
        for (_, a), (_, b) in zip(trips, trips[1:]):
            raised = False
            for k in range(1, len(b.arr)):
                late = max(a.arr[k] - b.arr[k], (a.arr[k] + a.dwell[k]) - (b.arr[k] + b.dwell[k]))
                if late > 0:
                    for j in range(k, len(b.arr)):
                        b.arr[j] += late
                    raised = True
            report.guard_raised += raised
        for (_, a), (_, b) in zip(trips, trips[1:]):
            if any(b.arr[k] < a.arr[k] or b.arr[k] + b.dwell[k] < a.arr[k] + a.dwell[k] for k in range(1, len(b.arr))):
                report.overtaking_after += 1


def retimed(rows: list[list[str]], idx: dict[str, int], plan: TripPlan) -> list[list[str]]:
    ai, di = idx["arrival_time"], idx["departure_time"]
    out = []
    for k, r in enumerate(rows):
        r = list(r)
        if k > 0:
            r[ai] = format_time(plan.arr[k])
            r[di] = format_time(plan.arr[k] + plan.dwell[k])
        out.append(r)
    return out


def run(in_zip: Path, coef_path: Path, out_zip: Path, report_path: Path | None,
        section_times: Path | None = None) -> Report:
    coef = load_coefficients(coef_path)
    sections = load_section_times(section_times, coef) if section_times else None
    report = Report()
    with zipfile.ZipFile(in_zip) as src:
        stops = read_stops(src)
        plans = plan_trips(src, coef, stops, sections, report)
        if report.overtaking_after:
            raise ValueError(f"{report.overtaking_after} trips still overtake after the guard")
        ratios: dict[str, list[float]] = {r.name: [] for r in coef.regions}
        tmp = out_zip.with_suffix(out_zip.suffix + ".tmp")
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
            for info in src.infolist():
                if info.filename != "stop_times.txt":
                    dst.writestr(info, src.read(info.filename))
                    continue
                with dst.open(zipfile.ZipInfo("stop_times.txt", date_time=info.date_time), "w",
                              force_zip64=True) as raw:
                    out = io.TextIOWrapper(raw, encoding="utf-8", newline="")
                    w = csv.writer(out, lineterminator="\n")
                    header_written = False
                    for header, tid, rows in iter_trips(src):
                        if not header_written:
                            w.writerow(header)
                            header_written = True
                            idx = {h: i for i, h in enumerate(header)}
                        plan = plans.get(tid)
                        if plan is None:
                            w.writerows(rows)
                            continue
                        rows = trip_rows_sorted(rows, idx["stop_sequence"])
                        w.writerows(retimed(rows, idx, plan))
                        old_end = parse_time(rows[-1][idx["arrival_time"]])
                        if old_end > plan.dep0:
                            ratios[plan.region.name].append((plan.arr[-1] - plan.dep0) / (old_end - plan.dep0))
                    out.flush()
                    out.detach()
        for name, v in ratios.items():
            if v:
                v.sort()
                report.regions[name]["tripRuntimeRatio"] = {
                    "p10": round(v[len(v) // 10], 3), "p50": round(v[len(v) // 2], 3), "p90": round(v[len(v) * 9 // 10], 3)}
        tmp.replace(out_zip)
    if report_path:
        doc = dict(input=str(in_zip), coefficients=str(coef_path), regions=report.regions,
                   sectionTimes=dict(file=str(section_times), sha256=sections.sha256, pairs=len(sections.any_hour),
                                     scale=sections.scale) if sections else None,
                   **summary(report))
        report_path.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return report


def summary(rep: Report) -> dict:
    return dict(sectionHops=rep.section_hops, formulaHops=rep.formula_hops, sectionRejected=rep.section_rejected,
                fallbackHops=rep.fallback_hops, clampedFactors=rep.clamped_factors, guardRaised=rep.guard_raised,
                overtakingAfter=rep.overtaking_after)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--in", dest="in_zip", type=Path, required=True)
    ap.add_argument("--coefficients", type=Path, required=True)
    ap.add_argument("--section-times", type=Path, help="CSV from fetch_seoul_section_times.py")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--report", type=Path)
    a = ap.parse_args()
    rep = run(a.in_zip, a.coefficients, a.out, a.report, a.section_times)
    print(json.dumps(dict(regions=rep.regions, **summary(rep)), ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
