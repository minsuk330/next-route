#!/usr/bin/env python3
"""Add metro trips the KTDB feed lacks, from a reviewed data file (#385, step 2g of build_otp_graph.sh).

KTDB GTFS 202503 has 75 trips on 7호선 하행 (``RR_ACC1_S-1-07-1D``, all ≥16:00) against 204 on 상행.  The
missing trains come from the Seoul timetable API via seoul_timetable_trips.py, which writes
``config/otp-national/added-trips/*.json``: routeId, serviceId, source/validation metadata and per trip
``tripId``, ``trainNo`` and ``stops`` = [[stop_id, arrival, departure], ...] in travel order.

Each new trip copies a template trip of the same route (not one of the added ones):

* same stop list → the trip with the nearest first departure: trips.txt row (service_id, shape_id, …) and every
  stop_times column but trip_id/arrival/departure (stop_sequence, pickup/drop-off, shape_dist_traveled, timepoint).
* no trip with that stop list → the trip whose stop list holds it as a contiguous run (longest such trip, then
  nearest departure): the slice of its rows, renumbered 1..n, first stop pickup/drop-off and last stop
  pickup/drop-off as the template's first/last row; its shape_id and shape_dist_traveled (so the slice keeps its
  place on that shape).

Guards (the build fails): route not metro (route_type 1); serviceId not in calendar.txt or not the template's;
a stop not on the route; times not increasing; a new trip duplicating an existing one (same stop list, first
departure within 60 s); a trip overtaking its neighbour in one stop pattern; trip ids not unique.

Idempotent: a trip id already in trips.txt with the same stops and times is ``alreadyApplied``; with other
content it fails.  Every existing row is written back as its original bytes; new trips are appended to the end
of trips.txt and stop_times.txt.  Stdlib only.
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from apply_subway_corrections import (  # noqa: E402  (same raw-row zip handling as step 2e)
    BOM, Table, copy_member, format_line, iter_stop_times, line_end, out_info, parse_line, sha256_file,
    trip_id_of, write_member, ZIP64_FORCE_BYTES)
from calibrate_bus_runtimes import format_time, parse_time  # noqa: E402

METRO_ROUTE_TYPE = "1"
DUPLICATE_SEC = 60


class AddTripsError(ValueError):
    pass


def load_data(path: Path) -> dict:
    doc = json.loads(path.read_text(encoding="utf-8"))
    for k in ("routeId", "serviceId", "source", "trips"):
        if k not in doc:
            raise AddTripsError(f"{path.name}: missing {k}")
    if doc.get("tripCount") != len(doc["trips"]):
        raise AddTripsError(f"{path.name}: tripCount {doc.get('tripCount')} != {len(doc['trips'])} trips")
    ids = [t["tripId"] for t in doc["trips"]]
    if len(set(ids)) != len(ids):
        raise AddTripsError(f"{path.name}: a tripId is repeated")
    for t in doc["trips"]:
        if not t["tripId"].startswith(doc["routeId"] + "_"):
            raise AddTripsError(f"{path.name}: {t['tripId']} does not start with {doc['routeId']}_")
        if len(t["stops"]) < 2:
            raise AddTripsError(f"{path.name}: {t['tripId']} has fewer than 2 stops")
        check_times(t["tripId"], [(parse_time(a), parse_time(d)) for _, a, d in t["stops"]])
    return doc


def check_times(tid: str, times: list[tuple[int, int]]) -> None:
    prev = None
    for k, (a, d) in enumerate(times):
        if d < a:
            raise AddTripsError(f"{tid}: departs before it arrives at stop {k + 1}")
        if prev is not None and (a <= prev[0] or a < prev[1]):
            raise AddTripsError(f"{tid}: time not increasing at stop {k + 1}")
        prev = (a, d)


def overtakes(a: list[tuple[int, int]], b: list[tuple[int, int]]) -> bool:
    """b leaves after a; True when b arrives at (or leaves) a later stop before a (calibrate's rule)."""
    return any(b[k][0] < a[k][0] or b[k][1] < a[k][1] for k in range(1, len(a)))


def read_route(zf: zipfile.ZipFile, route_ids: set[str], trips: Table) -> tuple[dict, list[str], bytes]:
    """Existing stop_times rows of the routes' trips, the stop_times header, and its line end."""
    wanted = {r[trips.col["trip_id"]].encode() for r in trips.rows
              if r is not None and r[trips.col["route_id"]] in route_ids}
    lines = iter_stop_times(zf)
    header_line = next(lines)
    header = parse_line(header_line.removeprefix(BOM))
    col = {h: i for i, h in enumerate(header)}
    rows: dict[str, list[list[str]]] = {}
    last = b""
    for ln in lines:
        last = ln
        if trip_id_of(ln, col["trip_id"]) in wanted:
            f = parse_line(ln)
            rows.setdefault(f[col["trip_id"]], []).append(f)
    if last and not line_end(last):
        raise AddTripsError("stop_times.txt does not end with a line break")
    for v in rows.values():
        v.sort(key=lambda r: int(r[col["stop_sequence"]]))
    return rows, header, line_end(header_line) or b"\n"


def plan_trip(new: dict, existing: dict[str, list[list[str]]], col: dict[str, int]) -> tuple[str, list[list[str]], str]:
    """(template trip id, new stop_times rows, 'same'|'slice')."""
    sid, ac, dc, seq = col["stop_id"], col["arrival_time"], col["departure_time"], col["stop_sequence"]
    want = [s for s, _, _ in new["stops"]]
    n, d0 = len(want), parse_time(new["stops"][0][2])
    same = [t for t, r in existing.items() if [x[sid] for x in r] == want]
    if same:
        tmpl = min(same, key=lambda t: (abs(parse_time(existing[t][0][dc]) - d0), t))
        base, kind = existing[tmpl], "same"
    else:
        cands = []
        for t, r in existing.items():
            s = [x[sid] for x in r]
            for i in range(len(s) - n + 1):
                if s[i:i + n] == want:
                    cands.append((-len(s), abs(parse_time(r[i][dc]) - d0), t, i))
                    break
        if not cands:
            raise AddTripsError(f"{new['tripId']}: no trip of the route runs its stop list {want[0]}..{want[-1]}")
        _, _, tmpl, i = min(cands)
        full = existing[tmpl]
        base = [list(r) for r in full[i:i + n]]
        for k, r in enumerate(base, start=1):
            r[seq] = str(k)
        for c in ("pickup_type", "drop_off_type"):
            if c in col:
                base[0][col[c]] = full[0][col[c]]
                base[-1][col[c]] = full[-1][col[c]]
        kind = "slice"
    out = []
    for r, (_, a, d) in zip(base, new["stops"]):
        r = list(r)
        r[col["trip_id"]], r[ac], r[dc] = new["tripId"], a, d
        out.append(r)
    return tmpl, out, kind


def run(in_zip: Path, data_paths: list[Path], out_zip: Path, report_path: Path | None = None) -> dict:
    if in_zip.resolve() == out_zip.resolve():
        raise AddTripsError("--out must not be --in")
    docs = [(p, load_data(p)) for p in data_paths]
    all_ids = [t["tripId"] for _, d in docs for t in d["trips"]]
    if len(set(all_ids)) != len(all_ids):
        raise AddTripsError("a tripId is in two data files")
    route_ids = {d["routeId"] for _, d in docs}
    with zipfile.ZipFile(in_zip) as src:
        trips = Table.read(src, "trips.txt")
        routes = Table.read(src, "routes.txt")
        stops = Table.read(src, "stops.txt")
        calendar = Table.read(src, "calendar.txt")
        route_type = {r[routes.col["route_id"]]: r[routes.col["route_type"]] for r in routes.rows if r is not None}
        services = {r[calendar.col["service_id"]] for r in calendar.rows if r is not None}
        stop_ids = {r[stops.col["stop_id"]] for r in stops.rows if r is not None}
        trip_k = {r[trips.col["trip_id"]]: k for k, r in enumerate(trips.rows) if r is not None}
        existing_rows, st_header, eol = read_route(src, route_ids, trips)
        col = {h: i for i, h in enumerate(st_header)}
        new_trip_lines, new_st_lines, results = [], [], []
        for path, doc in docs:
            rid, svc = doc["routeId"], doc["serviceId"]
            if route_type.get(rid) != METRO_ROUTE_TYPE:
                raise AddTripsError(f"{path.name}: route {rid} missing or not metro (route_type {route_type.get(rid)})")
            if svc not in services:
                raise AddTripsError(f"{path.name}: service {svc} not in calendar.txt")
            data_ids = {t["tripId"] for t in doc["trips"]}
            base = {t: r for t, r in existing_rows.items()
                    if trips.get(trip_k[t], "route_id") == rid and t not in data_ids}
            route_stops = {r[col["stop_id"]] for v in base.values() for r in v}
            before_starts = [parse_time(v[0][col["departure_time"]]) for v in base.values()]
            added, already, kinds = [], [], {}
            for t in doc["trips"]:
                tid, want = t["tripId"], [s for s, _, _ in t["stops"]]
                bad = [s for s in want if s not in stop_ids or s not in route_stops]
                if bad:
                    raise AddTripsError(f"{tid}: stops not on {rid}: {bad}")
                if tid in trip_k:
                    have = existing_rows.get(tid, [])
                    if [[r[col["stop_id"]], r[col["arrival_time"]], r[col["departure_time"]]] for r in have] \
                            != [list(s) for s in t["stops"]]:
                        raise AddTripsError(f"{tid}: already in trips.txt with other stops or times")
                    already.append(tid)
                    continue
                d0 = parse_time(t["stops"][0][2])
                dup = [b for b, v in base.items() if [r[col["stop_id"]] for r in v] == want
                       and abs(parse_time(v[0][col["departure_time"]]) - d0) <= DUPLICATE_SEC]
                if dup:
                    raise AddTripsError(f"{tid}: duplicates existing trip {dup[0]}")
                tmpl, rows, kind = plan_trip(t, base, col)
                trow = list(trips.rows[trip_k[tmpl]])
                trow[trips.col["trip_id"]] = tid
                if trow[trips.col["service_id"]] != svc:
                    raise AddTripsError(f"{tid}: template {tmpl} runs on {trow[trips.col['service_id']]}, not {svc}")
                new_trip_lines.append(format_line(trow, line_end(trips.lines[-1]) or b"\n"))
                new_st_lines += [format_line(r, eol) for r in rows]
                existing_rows[tid] = rows
                kinds[kind] = kinds.get(kind, 0) + 1
                added.append(dict(trip=tid, trainNo=t.get("trainNo"), template=tmpl, kind=kind,
                                  shape=trow[trips.col["shape_id"]] if "shape_id" in trips.col else None,
                                  first=rows[0][col["departure_time"]], last=rows[-1][col["arrival_time"]]))
            pairs = check_overtaking({tid: existing_rows[tid] for tid in list(base) + [a["trip"] for a in added]},
                                     col, {a["trip"] for a in added})
            after_starts = before_starts + [parse_time(a["first"]) for a in added]
            results.append(dict(data=str(path), dataSha256=sha256_file(path), routeId=rid, serviceId=svc,
                                added=len(added), alreadyApplied=len(already), templateKinds=kinds,
                                routeTripsBefore=len(base) + len(already), routeTripsAfter=len(base) + len(already)
                                + len(added), overtakingPairsChecked=pairs,
                                startHoursBefore=hours(before_starts), startHoursAfter=hours(after_starts),
                                trips=added))
        if new_trip_lines and trips.lines and not line_end(trips.lines[-1]):
            raise AddTripsError("trips.txt does not end with a line break")
        tmp = out_zip.with_suffix(out_zip.suffix + ".tmp")
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
            for info in src.infolist():
                if info.filename == "trips.txt":
                    write_member(dst, info, trips.to_bytes() + b"".join(new_trip_lines))
                elif info.filename == "stop_times.txt":
                    with src.open(info) as r, dst.open(out_info(info), "w",
                                                       force_zip64=info.file_size > ZIP64_FORCE_BYTES) as w:
                        while chunk := r.read(1 << 20):
                            w.write(chunk)
                        w.write(b"".join(new_st_lines))
                else:
                    copy_member(src, dst, info)
        tmp.replace(out_zip)
    report = dict(input=str(in_zip), inputSha256=sha256_file(in_zip), script=Path(__file__).name,
                  scriptSha256=sha256_file(Path(__file__)), output=str(out_zip), outputSha256=sha256_file(out_zip),
                  tripsAdded=len(new_trip_lines), stopTimesRowsAdded=len(new_st_lines), files=results)
    if report_path:
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return report


def check_overtaking(rows: dict[str, list[list[str]]], col: dict[str, int], changed: set[str]) -> int:
    by_pattern: dict[tuple[str, ...], list[str]] = {}
    for t, r in rows.items():
        by_pattern.setdefault(tuple(x[col["stop_id"]] for x in r), []).append(t)
    times = {t: [(parse_time(x[col["arrival_time"]]), parse_time(x[col["departure_time"]])) for x in r]
             for t, r in rows.items()}
    checked = 0
    for tids in by_pattern.values():
        tids.sort(key=lambda t: (times[t][0][1], t))
        for a, b in zip(tids, tids[1:]):
            if a in changed or b in changed:
                checked += 1
                if overtakes(times[a], times[b]):
                    raise AddTripsError(f"{b} overtakes {a} in one stop pattern")
    return checked


def hours(starts: list[int]) -> dict[str, int]:
    out: dict[str, int] = {}
    for s in sorted(starts):
        out[f"{s // 3600:02d}"] = out.get(f"{s // 3600:02d}", 0) + 1
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--in", dest="in_zip", type=Path, required=True)
    ap.add_argument("--trips", type=Path, action="append", required=True,
                    help="config/otp-national/added-trips/*.json (repeatable)")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--report", type=Path)
    a = ap.parse_args()
    try:
        rep = run(a.in_zip, a.trips, a.out, a.report)
    except AddTripsError as e:
        print(f"add missing trips failed: {e}", file=sys.stderr)
        return 1
    print(json.dumps(dict(tripsAdded=rep["tripsAdded"], stopTimesRowsAdded=rep["stopTimesRowsAdded"],
                          files=[{k: v for k, v in f.items() if k != "trips"} for f in rep["files"]]),
                     ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
