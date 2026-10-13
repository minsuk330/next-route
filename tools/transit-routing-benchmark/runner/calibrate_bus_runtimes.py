#!/usr/bin/env python3
"""Re-time bus stop_times from hop distance and stop count, one coefficient pair per region.

KTDB bus stop_times are estimates (collected departure times + a time-of-day speed DB x distance + a fixed
10 s dwell), not measured runs.  OTP bus rides came out 35-39 % shorter than ODsay for the same route and
stops (2026-10-14, 60 OD / 991 legs; 421 강남 → 서울역 OTP 24 min, ODsay 34, our measurements 38-44), so
buses ranked ahead of the subway and every bus ETA was early (issue #405).

The per-hop KTDB time carries almost no information about ODsay's (correlation of segment speeds 0.07), but
distance and stop count do.  So each hop of a bus trip in a listed region becomes

    hop seconds = (secPerMeter x hop metres + secPerHop) x trip factor

* hop metres = ``shape_dist_traveled`` difference, or the straight line x ``detourFactor`` when the feed has
  no usable distance for that hop;
* trip factor = this trip's original running time / the median running time of its pattern's trips that
  leave inside ``todWindow`` (clamped to ``todClamp``), which keeps KTDB's time-of-day shape (rush hour
  slower) while the level comes from the coefficients.

The first departure and every dwell (departure - arrival) are kept, so headways, first/last bus and the
10 s dwell stay as KTDB has them.  Only ``route_type`` 3 routes whose ``route_id`` starts with a region prefix
change; subway, rail, intercity (``BR_ACC3_``) and unlisted regions are copied unchanged.

Overtaking guard: OTP expects trips of one pattern not to overtake.  Within a pattern trips are walked in
departure order and a trip's factor is raised just enough to arrive at the last stop no earlier than the
trip before it; the written feed is then re-checked and the build fails if any trip still overtakes.

Every other file in the zip is copied byte for byte.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import math
import statistics
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

BUS_ROUTE_TYPE = "3"
SEC_PER_METER_RANGE = (0.03, 0.3)  # cruise 12-120 km/h
SEC_PER_HOP_RANGE = (0.0, 120.0)


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

    def region_of(self, route_id: str) -> Region | None:
        best: tuple[int, Region] | None = None
        for r in self.regions:
            for p in r.prefixes:
                if route_id.startswith(p) and (best is None or len(p) > best[0]):
                    best = (len(p), r)
        return best[1] if best else None


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
    return Coefficients(tuple(regions), (parse_time(lo), parse_time(hi)), (float(cl[0]), float(cl[1])),
                        float(doc.get("detourFactor", 1.25)))


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


@dataclass
class TripPlan:
    """What pass 1 learns about one in-scope trip; pass 2 writes from it."""
    region: Region
    pattern: tuple[str, int, int]  # (route_id, stop count, hash of stop ids)
    dep0: int
    run_orig: int
    base_total: float  # base seconds first stop -> last stop (before the factor)
    dwell_total: int
    factor: float = 1.0


@dataclass
class Report:
    regions: dict[str, dict] = field(default_factory=dict)
    fallback_hops: int = 0
    clamped_factors: int = 0
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


def plan_trips(zf: zipfile.ZipFile, coef: Coefficients, stops: dict[str, tuple[float, float]],
               report: Report) -> dict[str, TripPlan]:
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
        reg = route_region[rid]
        idx = {h: i for i, h in enumerate(header)}
        rows = trip_rows_sorted(rows, idx["stop_sequence"])
        arr = [parse_time(r[idx["arrival_time"]]) for r in rows]
        dep = [parse_time(r[idx["departure_time"]]) for r in rows]
        base = cumulative_base(rows, idx, stops, coef, reg, report)[-1]
        run = sum(arr[k] - dep[k - 1] for k in range(1, len(rows)))
        dwell = sum(dep[k] - arr[k] for k in range(1, len(rows) - 1))
        pattern = (rid, len(rows), hash(tuple(r[idx["stop_id"]] for r in rows)))  # stop ids kept out of memory
        plans[tid] = TripPlan(reg, pattern, dep[0], run, base, dwell)
        report.regions[reg.name]["trips"] += 1
    assign_factors(plans, coef, report)
    return plans


def read_stops(zf: zipfile.ZipFile) -> dict[str, tuple[float, float]]:
    return {s["stop_id"]: (float(s["stop_lat"]), float(s["stop_lon"])) for s in read_csv(zf, "stops.txt")
            if s.get("stop_lat") and s.get("stop_lon")}


def cumulative_base(rows: list[list[str]], idx: dict[str, int], stops: dict[str, tuple[float, float]],
                    coef: Coefficients, reg: Region, report: Report) -> list[float]:
    """Base seconds from the first stop to stop k (index 0 = 0), before the trip factor."""
    cum = [0.0]
    for m in hop_metres(rows, idx, stops, coef.detour_factor, report):
        cum.append(cum[-1] + reg.sec_per_meter * m + reg.sec_per_hop)
    return cum


def assign_factors(plans: dict[str, TripPlan], coef: Coefficients, report: Report) -> None:
    by_pattern: dict[tuple[str, int, int], list[TripPlan]] = {}
    for p in plans.values():
        by_pattern.setdefault(p.pattern, []).append(p)
    lo, hi = coef.tod_window
    cmin, cmax = coef.tod_clamp
    for trips in by_pattern.values():
        mid = [t.run_orig for t in trips if lo <= t.dep0 < hi and t.run_orig > 0]
        allr = [t.run_orig for t in trips if t.run_orig > 0]
        ref = statistics.median(mid) if mid else (statistics.median(allr) if allr else 0)
        for t in trips:
            f = t.run_orig / ref if ref > 0 else 1.0
            if f < cmin or f > cmax:
                report.clamped_factors += 1
                f = min(max(f, cmin), cmax)
            t.factor = f
        trips.sort(key=lambda t: t.dep0)
        for a, b in zip(trips, trips[1:]):
            base = b.base_total
            if base <= 0:
                continue
            end_a = a.dep0 + round(a.factor * a.base_total) + a.dwell_total
            need = (end_a - b.dep0 - b.dwell_total) / base
            if b.factor < need:
                b.factor = need + 1e-9
                report.guard_raised += 1


def retimed(rows: list[list[str]], idx: dict[str, int], plan: TripPlan, cum_base: list[float]) -> list[list[str]]:
    rows = trip_rows_sorted(rows, idx["stop_sequence"])
    ai, di = idx["arrival_time"], idx["departure_time"]
    arr = [parse_time(r[ai]) for r in rows]
    dep = [parse_time(r[di]) for r in rows]
    out, cum_dwell = [], 0
    for k, r in enumerate(rows):
        r = list(r)
        if k > 0:
            if k > 1:
                cum_dwell += dep[k - 1] - arr[k - 1]
            a = dep[0] + round(plan.factor * cum_base[k]) + cum_dwell
            r[ai] = format_time(a)
            r[di] = format_time(a + dep[k] - arr[k])
        out.append(r)
    return out


def run(in_zip: Path, coef_path: Path, out_zip: Path, report_path: Path | None) -> Report:
    coef = load_coefficients(coef_path)
    report = Report()
    with zipfile.ZipFile(in_zip) as src:
        stops = read_stops(src)
        plans = plan_trips(src, coef, stops, report)
        ratios: dict[str, list[float]] = {r.name: [] for r in coef.regions}
        last_arrival: dict[tuple[str, int, int], list[tuple[int, int]]] = {}
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
                        new = retimed(rows, idx, plan, cumulative_base(rows, idx, stops, coef, plan.region, Report()))
                        w.writerows(new)
                        a0 = parse_time(new[0][idx["departure_time"]])
                        old_end = max(parse_time(r[idx["arrival_time"]]) for r in rows)
                        new_end = parse_time(new[-1][idx["arrival_time"]])
                        if old_end > a0:
                            ratios[plan.region.name].append((new_end - a0) / (old_end - a0))
                        last_arrival.setdefault(plan.pattern, []).append((a0, new_end))
                    out.flush()
                    out.detach()
        for trips in last_arrival.values():
            trips.sort()
            report.overtaking_after += sum(1 for a, b in zip(trips, trips[1:]) if b[1] < a[1])
        for name, v in ratios.items():
            if v:
                v.sort()
                report.regions[name]["tripRuntimeRatio"] = {
                    "p10": round(v[len(v) // 10], 3), "p50": round(v[len(v) // 2], 3), "p90": round(v[len(v) * 9 // 10], 3)}
        if report.overtaking_after:
            tmp.unlink()
            raise ValueError(f"{report.overtaking_after} trips still overtake after the guard")
        tmp.replace(out_zip)
    if report_path:
        doc = dict(input=str(in_zip), coefficients=str(coef_path), regions=report.regions,
                   fallbackHops=report.fallback_hops, clampedFactors=report.clamped_factors,
                   guardRaised=report.guard_raised, overtakingAfter=report.overtaking_after)
        report_path.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return report


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--in", dest="in_zip", type=Path, required=True)
    ap.add_argument("--coefficients", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--report", type=Path)
    a = ap.parse_args()
    rep = run(a.in_zip, a.coefficients, a.out, a.report)
    print(json.dumps(dict(regions=rep.regions, fallbackHops=rep.fallback_hops, clampedFactors=rep.clamped_factors,
                          guardRaised=rep.guard_raised, overtakingAfter=rep.overtaking_after), ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
