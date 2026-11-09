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

  build-day-types  offline (#557): Saturday (WEEK_TAG 2) and holiday (3) trips of one line for apply_day_types.py.
          Per KTDB direction (route id suffix): travel order = its longest trip (a loop or branch line fails);
          INOUT_TAG ↔ direction from the weekday match (ambiguous fails); each API train goes to the route with
          the same stop list, else one holding it as a contiguous run (express trains to express routes).  Gate per
          direction: weekday API ↔ KTDB match ≥ --min-weekday-match of both sides and weekend trains without a
          route ≤ --max-unmatched; a failing direction writes no file (its routes stay on B1).  A train with the
          same stops and times in both tables is one M_SH trip.  Run it on a feed before step 2h.

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
GENERAL = "G"  # EXPRESS_YN of a local train


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


def api_trains(rows_by_station: dict[str, list[dict]], order: list[str],
               allow_gaps: bool = False) -> tuple[dict[str, dict], dict]:
    """TRAIN_NO → {origin, dest, express, stops: [(code, arr, dep)]} in line order, GTFS seconds (24:xx past midnight).

    allow_gaps: keep trains that skip stations between origin and dest (express); otherwise they are problems."""
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
        if gap and not allow_gaps:
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
        express = any(v[2].get("EXPRESS_YN", GENERAL) != GENERAL for v in visits)
        trains[no] = dict(trainNo=no, origin=origin, dest=dest, express=express, stops=out)
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
    m = match(gtfs, trains, stop_code, window)
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


# --------------------------------------------------------------------------- #
# build-day-types (#557): Saturday / holiday trips for apply_day_types.py
# --------------------------------------------------------------------------- #

WEEK_WD, WEEK_SA, WEEK_HO = "1", "2", "3"
DAY_TYPES_ISSUE = "minsuk330/next-route#557"


def read_gtfs_routes(zip_path: Path, route_ids: list[str]) -> dict[str, dict[str, dict]]:
    """route_id → read_gtfs_route()'s trips, in one pass over stop_times.txt; each route must have one service."""
    wanted = set(route_ids)
    with zipfile.ZipFile(zip_path) as zf:
        trips, route_of = {r: {} for r in route_ids}, {}
        with zf.open("trips.txt") as f:
            for r in csv.DictReader(io.TextIOWrapper(f, "utf-8-sig")):
                if r["route_id"] in wanted:
                    trips[r["route_id"]][r["trip_id"]] = dict(service=r["service_id"], shape=r.get("shape_id", ""),
                                                              stops=[])
                    route_of[r["trip_id"]] = r["route_id"]
        empty = [r for r, t in trips.items() if not t]
        if empty:
            raise TimetableError(f"routes with no trips in {zip_path}: {empty}")
        prefixes = tuple(r.encode() + b"_" for r in route_ids)
        with zf.open("stop_times.txt") as f:
            lines = io.BufferedReader(f, 1 << 20)
            header = next(lines).decode("utf-8-sig").strip().split(",")
            col = {h: i for i, h in enumerate(header)}
            for ln in lines:
                if not ln.startswith(prefixes):
                    continue
                r = next(csv.reader([ln.decode("utf-8")]))
                rid = route_of.get(r[col["trip_id"]])
                if rid is not None:
                    trips[rid][r[col["trip_id"]]]["stops"].append(
                        (int(r[col["stop_sequence"]]), r[col["stop_id"]],
                         parse_hms(r[col["arrival_time"]]), parse_hms(r[col["departure_time"]])))
    for rid, ts in trips.items():
        for t in ts.values():
            t["stops"] = [x[1:] for x in sorted(t["stops"])]
        services = {t["service"] for t in ts.values()}
        if len(services) != 1:
            raise TimetableError(f"{rid}: several service_ids {sorted(services)} — run on a feed before step 2h")
    return trips


def stop_code(sid: str) -> str:
    """Station code of a stop: the Seoul station code for RS_ACC1_S-1-{code}, the stop_id itself otherwise.
    Patterns, trains and match() must all use this one mapping (other regions' stop_ids have no such prefix)."""
    return sid[len(STOP_PREFIX):] if sid.startswith(STOP_PREFIX) else sid


def codes_of(stops) -> tuple[str, ...]:
    return tuple(stop_code(s[0]) for s in stops)


def contains_run(seq: tuple[str, ...], run: tuple[str, ...]) -> bool:
    n = len(run)
    return any(seq[i:i + n] == run for i in range(len(seq) - n + 1))


def route_patterns(gtfs: dict[str, dict[str, dict]]) -> dict[str, set[tuple[str, ...]]]:
    return {rid: {codes_of(t["stops"]) for t in ts.values()} for rid, ts in gtfs.items()}


def assign_route(train: dict, routes: list[str], patterns: dict[str, set[tuple[str, ...]]],
                 express_routes: set[str]) -> tuple[str | None, str]:
    """(route_id, 'same'|'slice') for an API train, or (None, reason).  Same stop list beats a contiguous slice;
    between routes an express train goes to an express route and a local train to a local one."""
    want = tuple(s[0] for s in train["stops"])
    same = [r for r in routes if want in patterns[r]]
    kind, cands = ("same", same) if same else ("slice", [r for r in routes
                                                         if any(contains_run(p, want) for p in patterns[r])])
    if not cands:
        return None, "noTemplate"
    if len(cands) > 1:
        cands = [r for r in cands if (r in express_routes) == train["express"]] or cands
    if len(cands) > 1:
        return None, f"ambiguous {sorted(cands)}"
    return cands[0], kind


def direction_of(route_id: str) -> str:
    return route_id[-1]


def line_order(gtfs: dict[str, dict[str, dict]], routes: list[str]) -> list[str]:
    """Travel order of one direction = the longest trip among its routes; every route stop must lie on it."""
    longest = max((codes_of(t["stops"]) for r in routes for t in gtfs[r].values()), key=len)
    if len(set(longest)) != len(longest):
        raise TimetableError(f"{routes}: the longest trip visits a station twice (loop line)")
    on = set(longest)
    off = sorted({c for r in routes for t in gtfs[r].values() for c in codes_of(t["stops"])} - on)
    if off:
        raise TimetableError(f"{routes}: stops off the longest trip (branch line): {off}")
    return list(longest)


def express_route_set(gtfs: dict[str, dict[str, dict]], routes: list[str], order: list[str]) -> set[str]:
    """Routes whose trips skip stations of the travel order."""
    pos = {c: i for i, c in enumerate(order)}
    out = set()
    for r in routes:
        for t in gtfs[r].values():
            p = [pos[c] for c in codes_of(t["stops"])]
            if any(b - a != 1 for a, b in zip(p, p[1:])):
                out.add(r)
                break
    return out


def weekday_match(gtfs, routes, trains, patterns, express_routes, window) -> dict:
    """Weekday API trains vs the routes' KTDB trips: assigned per route, then matched one-to-one."""
    by_route: dict[str, dict[str, dict]] = {r: {} for r in routes}
    unassigned = 0
    for no, a in trains.items():
        rid, _ = assign_route(a, routes, patterns, express_routes)
        if rid is None:
            unassigned += 1
        else:
            by_route[rid][no] = a
    matched = n_gtfs = 0
    per_route, deltas = {}, []
    for r in routes:
        m = match(gtfs[r], by_route[r], stop_code, window)
        matched += len(m["pairs"])
        deltas += m["stopDeltaSec"]
        n_gtfs += len(gtfs[r])
        per_route[r] = dict(gtfsTrips=len(gtfs[r]), apiTrains=len(by_route[r]), matched=len(m["pairs"]))
    n_api = len(trains)
    return dict(matched=matched, gtfsTrips=n_gtfs, apiTrains=n_api, unassignedApiTrains=unassigned,
                stopDeltaP90=percentile(deltas, .9),
                gtfsRate=round(matched / n_gtfs, 4) if n_gtfs else 0.0,
                apiRate=round(matched / n_api, 4) if n_api else 0.0, routes=per_route)


def weekend_entries(sa: dict[str, dict], ho: dict[str, dict]) -> list[tuple[str, dict]]:
    """(service, train): a train the Saturday and holiday tables give with the same stops and times → M_SH."""
    out = []
    for no in sorted(set(sa) | set(ho)):
        a, b = sa.get(no), ho.get(no)
        if a is not None and b is not None and a["stops"] == b["stops"]:
            out.append(("M_SH", a))
            continue
        if a is not None:
            out.append(("M_SA", a))
        if b is not None:
            out.append(("M_HO", b))
    return out


def build_day_types(cache: Path, gtfs_zip: Path, line: str, routes: list[str], fetched: str, window: int = 300,
                    min_weekday_match: float = 0.95, max_unmatched: float = 0.05) -> tuple[dict, dict[str, dict]]:
    """(report, route_id → data doc) — only routes of directions that pass the gate and have weekend trips."""
    gtfs = read_gtfs_routes(gtfs_zip, routes)
    patterns = route_patterns(gtfs)
    dirs: dict[str, list[str]] = {}
    for r in routes:
        dirs.setdefault(direction_of(r), []).append(r)
    tables = {(w, i): load_tables(cache, line, w, i)[1] for w in (WEEK_WD, WEEK_SA, WEEK_HO) for i in ("1", "2")}
    info = {}
    for d, rs in dirs.items():
        order = line_order(gtfs, rs)
        info[d] = dict(routes=rs, order=order, express=express_route_set(gtfs, rs, order))
    # INOUT_TAG ↔ KTDB direction from the weekday match, never assumed
    score = {}
    for i in ("1", "2"):
        for d, x in info.items():
            trains, _ = api_trains(tables[(WEEK_WD, i)], x["order"], allow_gaps=True)
            score[(i, d)] = weekday_match(gtfs, x["routes"], trains, patterns, x["express"], window)
    pairing = {}
    for d in info:
        ranked = sorted(("1", "2"), key=lambda i: -score[(i, d)]["matched"])
        best, other = score[(ranked[0], d)]["matched"], score[(ranked[1], d)]["matched"]
        if best == 0 or other * 2 > best:
            raise TimetableError(f"direction {d}: INOUT_TAG pairing is ambiguous (matched {best} vs {other})")
        pairing[d] = ranked[0]
    if len(set(pairing.values())) != len(pairing):
        raise TimetableError(f"two directions paired with one INOUT_TAG: {pairing}")
    report = dict(issue=DAY_TYPES_ISSUE, line=line, fetched=fetched, gate=dict(
        minWeekdayMatch=min_weekday_match, maxUnmatched=max_unmatched, matchWindowSec=window), directions={})
    docs: dict[str, dict] = {}
    for d, x in info.items():
        i = pairing[d]
        wd = score[(i, d)]
        sa, sa_diag = api_trains(tables[(WEEK_SA, i)], x["order"], allow_gaps=True)
        ho, ho_diag = api_trains(tables[(WEEK_HO, i)], x["order"], allow_gaps=True)
        entries = weekend_entries(sa, ho)
        per_route: dict[str, list[tuple[str, dict, str]]] = {r: [] for r in x["routes"]}
        unmatched = [dict(problem=p) for p in sa_diag["problems"] + ho_diag["problems"]]
        for svc, a in entries:
            rid, kind = assign_route(a, x["routes"], patterns, x["express"])
            if rid is None:
                unmatched.append(dict(service=svc, trainNo=a["trainNo"], reason=kind,
                                      first=fmt_hms(a["stops"][0][2]), origin=a["origin"], dest=a["dest"]))
            else:
                per_route[rid].append((svc, a, kind))
        total = len(entries) + len(sa_diag["problems"]) + len(ho_diag["problems"])
        rate = round(len(unmatched) / total, 4) if total else 1.0
        passed = wd["gtfsRate"] >= min_weekday_match and wd["apiRate"] >= min_weekday_match and rate <= max_unmatched
        counts = {}
        for svc, _ in entries:
            counts[svc] = counts.get(svc, 0) + 1
        dir_rep = dict(inoutTag=i, routes=x["routes"], expressRoutes=sorted(x["express"]),
                       pairingMatched={k: score[(k, d)]["matched"] for k in ("1", "2")},
                       weekday={k: v for k, v in wd.items()}, saturdayTrains=len(sa), holidayTrains=len(ho),
                       entries=counts, unmatchedRate=rate, unmatched=unmatched, passed=passed, files=[],
                       noWeekendTrips=[])
        if passed:
            for rid, got in per_route.items():
                if not got:
                    dir_rep["noWeekendTrips"].append(rid)  # stays on B1: no trip to give its weekend
                    continue
                trips = []
                for svc, a, kind in sorted(got, key=lambda g: (g[1]["stops"][0][2], g[0], g[1]["trainNo"])):
                    trips.append(dict(tripId=f"{rid}_{svc[2:]}_{a['trainNo']}", trainNo=a["trainNo"], service=svc,
                                      template=kind,
                                      stops=[[STOP_PREFIX + c, fmt_hms(p), fmt_hms(q)] for c, p, q in a["stops"]]))
                docs[rid] = dict(
                    issue=DAY_TYPES_ISSUE,
                    source=dict(url=SOURCE_URL, stationList=BASE + "{KEY}/json/" + STATION_LIST, line=line,
                                weekTags=[WEEK_SA, WEEK_HO], inoutTag=i, fetched=fetched,
                                rule="ARRIVETIME 00:00:00 at a non-origin station = no stop; the API gives times past "
                                     "midnight as 24:xx within each table (that day's night); M_SH = same TRAIN_NO, "
                                     "stops and times in the Saturday and holiday tables"),
                    routeId=rid,
                    validation=dict(weekday=wd["routes"][rid], directionWeekday=dict(
                        gtfsRate=wd["gtfsRate"], apiRate=wd["apiRate"]), directionUnmatchedRate=rate),
                    tripCount=len(trips), trips=trips)
                dir_rep["files"].append(rid)
        report["directions"][d] = dir_rep
    return report, docs


def write_day_data(path: Path, doc: dict) -> None:
    """One trip per line so a review diff shows trips, not stop rows."""
    lines = ["  " + json.dumps(t, ensure_ascii=False, separators=(",", ":")) for t in doc["trips"]]
    head = json.dumps({k: v for k, v in doc.items() if k != "trips"}, ensure_ascii=False, indent=1)
    path.write_text(head[:-2] + ',\n "trips": [\n' + ",\n".join(lines) + "\n ]\n}\n", encoding="utf-8")


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
    d = sub.add_parser("build-day-types", help="Saturday / holiday trips of one line → day-types/*.json (#557)")
    d.add_argument("--cache", type=Path, required=True)
    d.add_argument("--gtfs", type=Path, required=True, help="feed before step 2h (routes on one service)")
    d.add_argument("--line", required=True, help="e.g. 07호선")
    d.add_argument("--routes", required=True, help="the line's KTDB route ids (comma), both directions")
    d.add_argument("--fetched", required=True, help="YYYY-MM-DD the cache was fetched")
    d.add_argument("--match-window", type=int, default=300)
    d.add_argument("--min-weekday-match", type=float, default=0.95)
    d.add_argument("--max-unmatched", type=float, default=0.05)
    d.add_argument("--out-dir", type=Path, help="write {routeId}.json here; omit for a report-only run")
    d.add_argument("--report", type=Path)
    a = ap.parse_args()
    try:
        if a.cmd == "build-day-types":
            rep, docs = build_day_types(a.cache, a.gtfs, a.line, [r.strip() for r in a.routes.split(",") if r.strip()],
                                        a.fetched, a.match_window, a.min_weekday_match, a.max_unmatched)
            if a.report:
                a.report.write_text(json.dumps(rep, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
            if a.out_dir:
                a.out_dir.mkdir(parents=True, exist_ok=True)
                for rid, doc in docs.items():
                    write_day_data(a.out_dir / f"{rid}.json", doc)
            print(json.dumps({d: {k: v for k, v in x.items() if k not in ("unmatched",)}
                              for d, x in rep["directions"].items()}, ensure_ascii=False, indent=1))
            return 0
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
