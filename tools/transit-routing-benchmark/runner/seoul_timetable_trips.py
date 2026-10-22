#!/usr/bin/env python3
"""Seoul timetable API → metro trips missing from the KTDB feed (#385, 7호선 하행).

KTDB GTFS 202503 has 75 trips on ``RR_ACC1_S-1-07-1D`` (장암 → 석남, all leaving 16:00 or later) against 204 on
``-1U``: the raw KTDB download already lacks them.  This tool rebuilds the line's weekday timetable from the Seoul
open-data API and writes the trips KTDB does not have to a reviewable data file that add_missing_trips.py adds to
the feed before the graph build.

  fetch   per station of the line: ``SearchSTNTimeTableByIDService/1/1000/{STATION_CD}/{WEEK_TAG}/{INOUT_TAG}/``
          (station list from ``SearchSTNBySubwayLineInfo``).  Raw responses are cached as JSON under --cache
          (no key in them); a cached file is not fetched again.  Key: env SEOUL_SUBWAY_MASTER (env/api.env) —
          never printed, never in an error message (it is part of the URL).
  build   offline: cache + GTFS zip → data file.  Rows are joined on TRAIN_NO in line order.  Origin rows have
          ARRIVETIME 00:00:00, terminal rows LEFTTIME 00:00:00; ARRIVETIME 00:00:00 anywhere else is "no stop"
          (fetch_trainnos.py rule).  Times run on past midnight as 24:xx (GTFS), and a train leaving its origin
          before 03:00 belongs to the previous service day.  Station → stop_id by code: STATION_CD == the
          ``RS_ACC1_S-1-{code}`` suffix of a stop the route's trips already use (unmatched codes fail).
          Every existing trip of the route is matched to the API train with the same first and last stop and
          the nearest first departure (within --match-window); matched trains are left out, the rest go into
          the data file.  The match rate and time deltas are written to the data file's ``validation``.
          --check-route checks the method on a direction KTDB has in full (상행): every trip should match.

Stdlib only.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

BASE = "http://openapi.seoul.go.kr:8088/"
SOURCE_URL = BASE + "{KEY}/json/SearchSTNTimeTableByIDService/1/1000/{STATION_CD}/{WEEK_TAG}/{INOUT_TAG}/"
STATION_LIST = "SearchSTNBySubwayLineInfo/1/1000/"
NO_TIME = "00:00:00"
SERVICE_DAY_START = 3 * 3600  # a train leaving its origin before 03:00 runs on the previous service day
STOP_PREFIX = "RS_ACC1_S-1-"


class TimetableError(ValueError):
    pass


# --------------------------------------------------------------------------- #
# fetch
# --------------------------------------------------------------------------- #

def _get(key: str, path: str, tries: int = 6) -> dict:
    """GET with backoff.  The URL holds the key, so exceptions are re-raised without it."""
    delay = 2.0
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(BASE + key + "/json/" + path, timeout=30) as resp:
                body = json.load(resp)
            if key in json.dumps(body, ensure_ascii=False):
                raise TimetableError("response echoes the API key; not caching it")
            return body
        except TimetableError:
            raise
        except Exception as e:  # noqa: BLE001  (network is flaky; the message may carry the URL)
            last = type(e).__name__
        if attempt + 1 < tries:
            time.sleep(delay)
            delay *= 2
    raise TimetableError(f"{path.split('/')[0]}: {last} after {tries} tries")


def _cached(cache: Path, name: str, key: str, path: str) -> dict:
    f = cache / name
    if f.exists():
        return json.loads(f.read_text(encoding="utf-8"))
    body = _get(key, path)
    f.write_text(json.dumps(body, ensure_ascii=False) + "\n", encoding="utf-8")
    time.sleep(0.3)
    return body


def fetch(cache: Path, line: str, weeks: list[str], inouts: list[str], codes: list[str] | None) -> dict:
    key = os.environ.get("SEOUL_SUBWAY_MASTER")
    if not key:
        raise TimetableError("SEOUL_SUBWAY_MASTER not set (set -a; . env/api.env; set +a)")
    cache.mkdir(parents=True, exist_ok=True)
    stations = line_stations(_cached(cache, "stations.json", key, STATION_LIST), line)
    if codes:
        stations = [s for s in stations if s["STATION_CD"] in codes]
    n = 0
    for s in stations:
        for w in weeks:
            for io_ in inouts:
                body = _cached(cache, f"tt_{s['STATION_CD']}_{w}_{io_}.json", key,
                               f"SearchSTNTimeTableByIDService/1/1000/{s['STATION_CD']}/{w}/{io_}/")
                res = body.get("SearchSTNTimeTableByIDService", {})
                total, rows = res.get("list_total_count", 0), res.get("row", [])
                if total != len(rows):
                    raise TimetableError(f"{s['STATION_CD']} {w}/{io_}: {len(rows)} rows of {total} (page limit)")
                n += 1
    return dict(stations=len(stations), timetables=n)


def line_stations(body: dict, line: str) -> list[dict]:
    rows = [r for r in body.get("SearchSTNBySubwayLineInfo", {}).get("row", []) if r.get("LINE_NUM") == line]
    if not rows:
        raise TimetableError(f"no station of {line} in the station list")
    return sorted(rows, key=lambda r: r["FR_CODE"])


# --------------------------------------------------------------------------- #
# build
# --------------------------------------------------------------------------- #

def parse_hms(s: str) -> int:
    h, m, sec = (int(x) for x in s.split(":"))
    return h * 3600 + m * 60 + sec


def fmt_hms(t: int) -> str:
    return f"{t // 3600:02d}:{t % 3600 // 60:02d}:{t % 60:02d}"


def api_trains(rows_by_station: dict[str, list[dict]], order: list[str]) -> tuple[dict[str, dict], dict]:
    """TRAIN_NO → {origin, dest, stops: [(code, arr, dep)]} in line order, GTFS seconds (24:xx past midnight)."""
    pos = {c: i for i, c in enumerate(order)}
    by_train: dict[str, list[tuple[int, str, dict]]] = {}
    skipped = dict(noStopRows=0, offLineStations=0)
    for code, rows in rows_by_station.items():
        if code not in pos:
            skipped["offLineStations"] += 1
            continue
        for r in rows:
            by_train.setdefault(r["TRAIN_NO"], []).append((pos[code], code, r))
    trains: dict[str, dict] = {}
    problems: list[str] = []
    for no, visits in sorted(by_train.items()):
        visits.sort(key=lambda v: v[0])
        if len({v[0] for v in visits}) != len(visits):
            problems.append(f"{no}: a station twice")
            continue
        meta = {(v[2]["ORIGINSTATION"], v[2]["DESTSTATION"]) for v in visits}
        if len(meta) != 1:
            problems.append(f"{no}: origin/dest differ between stations {sorted(meta)}")
            continue
        origin, dest = meta.pop()
        stops: list[tuple[str, int | None, int | None]] = []
        for _, code, r in visits:
            arr, dep = r["ARRIVETIME"], r["LEFTTIME"]
            if code == origin:
                a = d = parse_hms(dep)
            elif code == dest:
                a = d = parse_hms(arr)
            elif arr == NO_TIME:
                skipped["noStopRows"] += 1
                continue
            else:
                a, d = parse_hms(arr), parse_hms(dep) if dep != NO_TIME else parse_hms(arr)
            stops.append((code, a, d))
        codes = [s[0] for s in stops]
        if not codes or codes[0] != origin or codes[-1] != dest:
            problems.append(f"{no}: stops {codes[:1]}..{codes[-1:]} do not run {origin} → {dest}")
            continue
        gap = [c for c in order[pos[origin]:pos[dest] + 1] if c not in codes]
        if gap:
            problems.append(f"{no}: {origin} → {dest} misses {gap}")
            continue
        # unroll midnight: day offset so that times never go backwards; early-morning origins are 24:xx
        day = 24 * 3600 if stops[0][1] < SERVICE_DAY_START else 0
        out, prev = [], -1
        for code, a, d in stops:
            a += day
            d += day
            if a < prev:
                a, d, day = a + 24 * 3600, d + 24 * 3600, day + 24 * 3600
            if d < a:
                d += 24 * 3600
            out.append((code, a, d))
            prev = d
        trains[no] = dict(trainNo=no, origin=origin, dest=dest, stops=out)
    return trains, dict(skipped=skipped, problems=problems)


def read_gtfs_route(zip_path: Path, route_id: str) -> tuple[dict[str, dict], str]:
    """trip_id → {stops: [(stop_id, arr, dep)], service, shape}; and the route's single service_id."""
    with zipfile.ZipFile(zip_path) as zf:
        trips = {}
        with zf.open("trips.txt") as f:
            for r in csv.DictReader(io.TextIOWrapper(f, "utf-8-sig")):
                if r["route_id"] == route_id:
                    trips[r["trip_id"]] = dict(service=r["service_id"], shape=r.get("shape_id", ""), stops=[])
        if not trips:
            raise TimetableError(f"route {route_id} has no trips in {zip_path}")
        prefix = route_id.encode() + b"_"
        with zf.open("stop_times.txt") as f:
            lines = io.BufferedReader(f, 1 << 20)
            header = next(lines).decode("utf-8-sig").strip().split(",")
            col = {h: i for i, h in enumerate(header)}
            for ln in lines:
                if not ln.startswith(prefix):
                    continue
                r = next(csv.reader([ln.decode("utf-8")]))
                t = trips.get(r[col["trip_id"]])
                if t is not None:
                    t["stops"].append((int(r[col["stop_sequence"]]), r[col["stop_id"]],
                                       parse_hms(r[col["arrival_time"]]), parse_hms(r[col["departure_time"]])))
    for t in trips.values():
        t["stops"] = [s[1:] for s in sorted(t["stops"])]
    services = {t["service"] for t in trips.values()}
    if len(services) != 1:
        raise TimetableError(f"{route_id}: several service_ids {sorted(services)}")
    return trips, services.pop()


def percentile(values: list[int], p: float) -> int | None:
    if not values:
        return None
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p * (len(s) - 1))))]


def match(gtfs: dict[str, dict], trains: dict[str, dict], to_code, window: int) -> dict:
    """Each GTFS trip → nearest-start API train with the same first/last stop (one-to-one, closest pairs first)."""
    cands = []
    for tid, t in gtfs.items():
        o, d, t0 = to_code(t["stops"][0][0]), to_code(t["stops"][-1][0]), t["stops"][0][2]
        for no, a in trains.items():
            if a["origin"] == o and a["dest"] == d and abs(a["stops"][0][2] - t0) <= window:
                cands.append((abs(a["stops"][0][2] - t0), tid, no))
    pairs, used_t, used_a = {}, set(), set()
    for _, tid, no in sorted(cands):
        if tid not in used_t and no not in used_a:
            pairs[tid] = no
            used_t.add(tid)
            used_a.add(no)
    start_d, stop_d, same_stops = [], [], 0
    for tid, no in pairs.items():
        g, a = gtfs[tid]["stops"], trains[no]["stops"]
        start_d.append(abs(a[0][2] - g[0][2]))
        if [to_code(s[0]) for s in g] == [s[0] for s in a]:
            same_stops += 1
            stop_d += [abs(x[1] - y[1]) for x, y in zip(a, g)]
    return dict(pairs=pairs, unmatchedGtfs=sorted(set(gtfs) - used_t), startDeltaSec=start_d,
                stopDeltaSec=stop_d, sameStopList=same_stops)


def summary(m: dict, n_gtfs: int) -> dict:
    s, st = m["startDeltaSec"], m["stopDeltaSec"]
    return dict(gtfsTrips=n_gtfs, matched=len(m["pairs"]), sameStopList=m["sameStopList"],
                unmatchedGtfs=m["unmatchedGtfs"],
                firstDepartureDeltaSec=dict(p50=percentile(s, .5), p90=percentile(s, .9), max=max(s, default=None)),
                stopTimeDeltaSec=dict(n=len(st), p50=percentile(st, .5), p90=percentile(st, .9),
                                      max=max(st, default=None),
                                      within60=sum(1 for x in st if x <= 60)))


def load_tables(cache: Path, line: str, week: str, inout: str) -> tuple[list[dict], dict[str, list[dict]]]:
    stations = line_stations(json.loads((cache / "stations.json").read_text(encoding="utf-8")), line)
    tables = {}
    for s in stations:
        f = cache / f"tt_{s['STATION_CD']}_{week}_{inout}.json"
        if not f.exists():
            raise TimetableError(f"not fetched: {f.name}")
        tables[s["STATION_CD"]] = json.loads(f.read_text(encoding="utf-8")).get(
            "SearchSTNTimeTableByIDService", {}).get("row", [])
    return stations, tables


def build(cache: Path, gtfs_zip: Path, route_id: str, line: str, week: str, inout: str, window: int,
          fetched: str) -> tuple[dict, dict[str, dict], dict[str, dict]]:
    stations, tables = load_tables(cache, line, week, inout)
    gtfs, service = read_gtfs_route(gtfs_zip, route_id)
    route_stops = {s[0] for t in gtfs.values() for s in t["stops"]}
    codes = [s["STATION_CD"] for s in stations]
    unmatched = [c for c in codes if STOP_PREFIX + c not in route_stops]
    if unmatched:
        raise TimetableError(f"API stations with no stop on {route_id}: {unmatched}")
    extra = sorted(route_stops - {STOP_PREFIX + c for c in codes})
    if extra:
        raise TimetableError(f"{route_id} stops with no API station: {extra}")
    # travel order = the route's longest stop list (KTDB direction), checked against the API's origin→dest
    longest = max((t["stops"] for t in gtfs.values()), key=len)
    order = [s[0][len(STOP_PREFIX):] for s in longest]
    if sorted(order) != sorted(codes):
        raise TimetableError(f"{route_id}: longest trip does not cover the line")
    trains, diag = api_trains(tables, order)
    to_code = lambda sid: sid[len(STOP_PREFIX):]  # noqa: E731
    m = match(gtfs, trains, to_code, window)
    matched_trains = set(m["pairs"].values())
    missing = [a for no, a in sorted(trains.items(), key=lambda kv: (kv[1]["stops"][0][2], kv[0]))
               if no not in matched_trains]
    meta = dict(
        issue="minsuk330/next-route#385",
        source=dict(url=SOURCE_URL, stationList=BASE + "{KEY}/json/" + STATION_LIST, line=line,
                    weekTag=week, inoutTag=inout, fetched=fetched,
                    rule="ARRIVETIME 00:00:00 at a non-origin station = no stop; 24:xx past midnight; "
                         "origin departure before 03:00 = previous service day"),
        routeId=route_id, serviceId=service,
        validation=dict(apiTrains=len(trains), apiProblems=diag["problems"], skipped=diag["skipped"],
                        matchWindowSec=window, **summary(m, len(gtfs))),
    )
    return meta, {no: trains[no] for no in sorted(trains)}, {a["trainNo"]: a for a in missing}


def write_data(path: Path, meta: dict, missing: dict[str, dict], route_id: str) -> None:
    """One trip per line so a review diff shows trips, not stop rows."""
    lines = []
    for a in sorted(missing.values(), key=lambda a: (a["stops"][0][2], a["trainNo"])):
        trip = dict(tripId=f"{route_id}_T{a['trainNo']}", trainNo=a["trainNo"],
                    stops=[[STOP_PREFIX + c, fmt_hms(x), fmt_hms(y)] for c, x, y in a["stops"]])
        lines.append("  " + json.dumps(trip, ensure_ascii=False, separators=(",", ":")))
    head = json.dumps(dict(meta, tripCount=len(missing)), ensure_ascii=False, indent=1)
    path.write_text(head[:-2] + ',\n "trips": [\n' + ",\n".join(lines) + "\n ]\n}\n", encoding="utf-8")


def hour_hist(starts: list[int]) -> dict[str, int]:
    out: dict[str, int] = {}
    for s in sorted(starts):
        out[f"{s // 3600:02d}"] = out.get(f"{s // 3600:02d}", 0) + 1
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch")
    f.add_argument("--cache", type=Path, required=True)
    f.add_argument("--line", default="07호선")
    f.add_argument("--weeks", default="1")
    f.add_argument("--inouts", default="2")
    f.add_argument("--stations", default="", help="STATION_CD list (comma); default all of --line")
    b = sub.add_parser("build")
    b.add_argument("--cache", type=Path, required=True)
    b.add_argument("--gtfs", type=Path, required=True)
    b.add_argument("--route", default="RR_ACC1_S-1-07-1D")
    b.add_argument("--line", default="07호선")
    b.add_argument("--week", default="1")
    b.add_argument("--inout", default="2")
    b.add_argument("--match-window", type=int, default=300)
    b.add_argument("--fetched", required=True, help="YYYY-MM-DD the cache was fetched")
    b.add_argument("--out", type=Path, help="data file (trips KTDB lacks); omit for a check run")
    b.add_argument("--check-route", action="store_true",
                   help="method check: report only, fail unless every GTFS trip matches")
    a = ap.parse_args()
    try:
        if a.cmd == "fetch":
            codes = [c.strip() for c in a.stations.split(",") if c.strip()] or None
            print(json.dumps(fetch(a.cache, a.line, a.weeks.split(","), a.inouts.split(","), codes)))
            return 0
        meta, trains, missing = build(a.cache, a.gtfs, a.route, a.line, a.week, a.inout, a.match_window, a.fetched)
        v = meta["validation"]
        report = dict(route=a.route, apiTrains=v["apiTrains"], gtfsTrips=v["gtfsTrips"], matched=v["matched"],
                      sameStopList=v["sameStopList"], unmatchedGtfs=len(v["unmatchedGtfs"]),
                      firstDepartureDeltaSec=v["firstDepartureDeltaSec"], stopTimeDeltaSec=v["stopTimeDeltaSec"],
                      apiProblems=v["apiProblems"], skipped=v["skipped"], missing=len(missing),
                      apiStartHours=hour_hist([t["stops"][0][2] for t in trains.values()]),
                      missingStartHours=hour_hist([t["stops"][0][2] for t in missing.values()]))
        print(json.dumps(report, ensure_ascii=False, indent=1))
        if a.check_route and (v["unmatchedGtfs"] or v["apiProblems"]):
            print("check failed: GTFS trips without an API train", file=sys.stderr)
            return 1
        if a.out:
            write_data(a.out, meta, missing, a.route)
        return 0
    except TimetableError as e:
        print(f"seoul timetable: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
