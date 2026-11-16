#!/usr/bin/env python3
"""Add Seoul bus routes the national feed lacks, from the ws.bus.go.kr cache.

The 2025-03 KTDB feed has no rows for routes opened since (and a few it never
carried: tour, river-shuttle, autonomous, commuter lines).  For each route id in
``--routes`` that the feed does not have (no ``BR_TAGO_SEB<id>[_U|_D]`` route)
this appends:

* one route (``BR_TAGO_SEB<id>``, agency and columns copied from an existing
  Seoul bus route);
* round trips ``BR_TAGO_SEB<id>_Ord###`` on the feed's every-day service, one
  per headway from the first to the last bus;
* stop_times over the official station list (pass-through points dropped, same
  rule as apply_gbis_routes.py --provider seoul), 10 s dwell;
* the official polyline as the shape, and any station the feed lacks.

There is no timetable for these routes, so times are an ESTIMATE: distance along
the official line at a fixed speed per route type.  ``timepoint`` is 0 on every
stop but the first to say so.  Routes without a usable station list or line are
skipped and listed in the report.

``--routes`` is a TSV: route_id, name, route_type (Seoul code), headway minutes,
first bus HHMM, last bus HHMM.  Stdlib only.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apply_gbis_routes as ag  # noqa: E402

csv.field_size_limit(min(sys.maxsize, 2**31 - 1))

ROUTE_PREFIX = "BR_TAGO_SEB"
DWELL_S = 10
DEFAULT_SPEED_MS = 16 / 3.6
SPEED_MS = {"1": 35 / 3.6}          # airport limousines run mostly on expressways
MAX_TRIPS = 400                     # a 3-minute headway over 20 hours; more means bad input
SERVICE_ID = "B1"                   # the feed's every-day service


def hhmm(value: str) -> int | None:
    digits = "".join(ch for ch in value if ch.isdigit())
    if len(digits) < 4:
        return None
    h, m = int(digits[:2]), int(digits[2:4])
    return h * 3600 + m * 60 if h < 30 and m < 60 else None


def departures(first: int, last: int, headway_min: int) -> list[int]:
    """First to last bus every headway; the last bus after midnight wraps past 24h."""
    if last < first:
        last += 24 * 3600
    if headway_min <= 0:
        return [first]
    step = headway_min * 60
    out = list(range(first, last + 1, step))
    return out[:MAX_TRIPS]


def run(in_zip: Path, out_zip: Path, source: Path, routes_tsv: Path, report_path: Path) -> dict:
    zin = zipfile.ZipFile(in_zip)

    def table(name):
        rd = csv.reader(io.TextIOWrapper(zin.open(name), encoding="utf-8-sig", newline=""))
        hdr = next(rd)
        return hdr, {c: i for i, c in enumerate(hdr)}, rd

    r_hdr, R, rd = table("routes.txt")
    route_rows = list(rd)
    have = {m.group(1) for r in route_rows if (m := ag.SEOUL_ROUTE_RE.match(r[R["route_id"]]))}
    template = next((r for r in route_rows if r[R["route_id"]].startswith(ROUTE_PREFIX)), None)
    if template is None:
        raise SystemExit("feed has no Seoul bus route to copy columns from")
    s_hdr, S, rd = table("stops.txt")
    stop_rows = list(rd)
    stop_ids = {r[S["stop_id"]] for r in stop_rows}
    stop_template = next((r for r in stop_rows if r[S["stop_id"]].startswith(ag.SEOUL_STOP_PREFIX)), stop_rows[0])
    c_hdr, C, rd = table("calendar.txt")
    if SERVICE_ID not in {r[C["service_id"]] for r in rd}:
        raise SystemExit(f"feed has no service {SERVICE_ID}")
    t_hdr, _, _ = table("trips.txt")
    st_hdr, _, _ = table("stop_times.txt")
    sh_hdr, _, _ = table("shapes.txt")

    new_routes, new_trips, new_stop_times, new_shapes, new_stops = [], [], [], [], []
    rep = {"added": [], "alreadyInFeed": 0, "skipped": {}}
    for line in routes_tsv.read_text().splitlines():
        if not line.strip():
            continue
        cols = (line.split("\t") + [""] * 6)[:6]
        rid, name, rtype, term, first, last = (c.strip() for c in cols)
        if rid in have:
            rep["alreadyInFeed"] += 1
            continue
        g = ag.load_seoul(source, rid)
        first_s, last_s = hhmm(first), hhmm(last)
        if g is None or len(g.stations) < 2:
            rep["skipped"][name or rid] = "no station list or line"
            continue
        if first_s is None or last_s is None:
            rep["skipped"][name or rid] = "no first/last bus time"
            continue
        meas = g.station_meas
        if meas[-1] - meas[0] < 100:
            rep["skipped"][name or rid] = "stations do not span the line"
            continue
        speed = SPEED_MS.get(rtype, DEFAULT_SPEED_MS)
        offsets, t = [], 0.0
        for k in range(len(meas)):
            if k:
                t += max(0.0, meas[k] - meas[k - 1]) / speed + DWELL_S
            offsets.append(int(round(t)))
        route_id = ROUTE_PREFIX + rid
        row = list(template)
        row[R["route_id"]], row[R["route_short_name"]] = route_id, name
        new_routes.append(row)
        shape_id = f"seoul_{rid}_0_{len(g.stations) - 1}"
        for k, (lat, lon, dist) in enumerate(g.line.cut(meas[0], meas[-1]), 1):
            new_shapes.append({"shape_id": shape_id, "shape_pt_lat": f"{lat:.6f}", "shape_pt_lon": f"{lon:.6f}",
                               "shape_pt_sequence": str(k), "shape_dist_traveled": f"{dist:.3f}"})
        for s in g.stations:
            sid = ag.SEOUL_STOP_PREFIX + s["stationId"]
            if sid not in stop_ids:
                stop_ids.add(sid)
                srow = [""] * len(s_hdr)
                for c in ("location_type", "wheelchair_boarding"):
                    if c in S:
                        srow[S[c]] = stop_template[S[c]]
                srow[S["stop_id"]], srow[S["stop_name"]] = sid, s["stationName"]
                srow[S["stop_lat"]], srow[S["stop_lon"]] = f"{s['y']:.6f}", f"{s['x']:.6f}"
                new_stops.append(srow)
        try:
            deps = departures(first_s, last_s, int(float(term or 0)))
        except ValueError:
            deps = [first_s]
        for n, dep in enumerate(deps, 1):
            trip_id = f"{route_id}_Ord{n:03d}"
            new_trips.append({"route_id": route_id, "service_id": SERVICE_ID, "trip_id": trip_id,
                              "shape_id": shape_id, "wheelchair_accessible": "0", "bikes_allowed": "0"})
            for k, s in enumerate(g.stations):
                arr = dep + offsets[k]
                new_stop_times.append({
                    "trip_id": trip_id, "arrival_time": ag.fmt_time(arr),
                    "departure_time": ag.fmt_time(arr + (DWELL_S if 0 < k < len(g.stations) - 1 else 0)),
                    "stop_id": ag.SEOUL_STOP_PREFIX + s["stationId"], "stop_sequence": str(k + 1),
                    "pickup_type": "0", "drop_off_type": "0",
                    "shape_dist_traveled": f"{meas[k] - meas[0]:.3f}", "timepoint": "1" if k == 0 else "0"})
        rep["added"].append({"route": name, "id": rid, "trips": len(deps), "stops": len(g.stations),
                             "runMinutes": round(offsets[-1] / 60), "lengthKm": round((meas[-1] - meas[0]) / 1000, 1)})

    appends = {
        "routes.txt": (None, new_routes), "stops.txt": (None, new_stops),
        "trips.txt": (t_hdr, new_trips), "stop_times.txt": (st_hdr, new_stop_times), "shapes.txt": (sh_hdr, new_shapes),
    }
    out_zip.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_zip.with_suffix(out_zip.suffix + ".tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
        for info in zin.infolist():
            name = info.filename
            with zin.open(name) as src, zout.open(name, "w", force_zip64=True) as dst:
                last_byte = b"\n"
                while chunk := src.read(1 << 20):
                    dst.write(chunk)
                    last_byte = chunk[-1:]
                if name in appends and appends[name][1]:
                    hdr, rows = appends[name]
                    buf = io.StringIO()
                    w = csv.writer(buf, lineterminator="\n")
                    for row in rows:
                        w.writerow(row if hdr is None else [row.get(c, "") for c in hdr])
                    dst.write((b"" if last_byte == b"\n" else b"\n") + buf.getvalue().encode("utf-8"))
    tmp.replace(out_zip)
    summary = {"input": str(in_zip), "output": str(out_zip), "routesAdded": len(rep["added"]),
               "tripsAdded": len(new_trips), "stopsAdded": len(new_stops), "alreadyInFeed": rep["alreadyInFeed"],
               "skipped": rep["skipped"], "added": rep["added"]}
    report_path.write_text(json.dumps(summary, ensure_ascii=False, indent=1))
    return summary


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("in_zip", type=Path)
    p.add_argument("out_zip", type=Path)
    p.add_argument("--source", type=Path, required=True, help="fetch_seoul_routes.py cache directory")
    p.add_argument("--routes", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    a = p.parse_args()
    s = run(a.in_zip, a.out_zip, a.source, a.routes, a.report)
    print(json.dumps({k: v for k, v in s.items() if k != "added"}, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
