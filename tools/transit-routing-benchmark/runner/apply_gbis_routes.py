#!/usr/bin/env python3
"""Rewrite the Gyeonggi bus trips of a shaped GTFS feed with GBIS stations and shapes.

The national feed's Gyeonggi buses (``BR_TAGO_GGB<9>[_U|_D]`` and village
``BR_3100_<9>``) carry 2025-03 KTDB stop lists and pfaedle-guessed shapes.  GBIS
(경기도 버스정보) publishes the official route polyline and station list per
routeId (cached by fetch_gbis_routes.py).  This tool keeps every trip and its
timetable (count, first departure, last arrival, and the times of stops that
survive) and replaces, per trip:

* the stop sequence with the GBIS stations of the part of the route the trip
  runs (whole round trip, outbound ``_U`` up to the turn, inbound ``_D`` from it,
  or the span matched from the trip's first/last stops).  Pass-through waypoints
  (names ending in ``(경유)``) are not stops and are dropped;
* the shape with the GBIS polyline cut to that span, with
  ``shape_dist_traveled`` recomputed on both shapes and stop_times.

Stops keep the feed's id rule ``BS_TAGO_GGB<stationId>`` so realtime lookups
(which pass the 9 digits to GBIS) keep matching.  New stations are added to
stops.txt; existing ones move to the GBIS coordinate when only Gyeonggi trips
use them or the move is small.  A route GBIS has a line but no station list for
keeps its stops and only gets the new shape.  Anything that cannot be mapped is
left untouched and listed in the report.  Everything outside the Gyeonggi trips
is copied byte for byte.

``--provider seoul`` does the same for Seoul buses (``BR_TAGO_SEB<9>[_U|_D]``,
stops ``BS_TAGO_SEB<station>``) from the ws.bus.go.kr cache written by
fetch_seoul_routes.py (``--source`` = that directory).  Seoul pass-through
points (``(경유)``, ``(미정차)``, ``(가상)`` in the name) are dropped, the turn
is the station flagged ``transYn``, and existing stops also take the official
name (the 2025-03 names are stale for about a thousand Seoul stops).

Stdlib only (same reason as compute_shape_quality.py: the release pipeline runs
without a venv).
"""

from __future__ import annotations

import argparse
import bisect
import csv
import io
import json
import math
import re
import sys
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from compute_shape_quality import _to_m  # noqa: E402  (shared feed-local projection)

csv.field_size_limit(min(sys.maxsize, 2**31 - 1))

ROUTE_RE = re.compile(r"^BR_(?:TAGO_GGB|3100_)(\d{9})(_[UD])?$")
STOP_PREFIX = "BS_TAGO_GGB"
WAYPOINT_SUFFIX = "(경유)"
GBIS_ROUTE_RE, GBIS_STOP_PREFIX = ROUTE_RE, STOP_PREFIX
SHAPE_TAG = "gbis"          # prefix of the shape ids this run writes
REFRESH_NAMES = False       # also rename existing stops to the source's station name
SEOUL_ROUTE_RE = re.compile(r"^BR_TAGO_SEB(\d{9})(_[UD])?$")
SEOUL_STOP_PREFIX = "BS_TAGO_SEB"
SEOUL_PASS_THROUGH = ("(경유)", "(미정차)", "(가상)")
WHOLE_ROUTE_WHEN_OFF_LINE = False   # seoul: a trip whose stops are off the official line takes the whole list
OFF_LINE_M = 150.0      # OTP drops a shape whose stops sit farther than this from it
LOOP_START_M = 500.0    # first station this near the line start may be assigned to it (closed loops)
LOOP_M = 300.0          # trip's first≈last stop within this → round trip
# A new span much longer/shorter than the old trip means the span was mis-chosen
# (e.g. a short-turn trip spread over the whole loop): keep its stops, swap the shape only.
SPAN_LENGTH_RATIO = 2.5
SPAN_LENGTH_SLACK_M = 1000.0
# Timing sanity (≈90 km/h): an anchor that needs more is matched on the wrong pass,
# and a span that needs more on average does not fit the old timetable.
MAX_SPEED_MS = 25.0
NEAR_STOP_M = 300.0     # coordinate fallback when a stop id is not in the GBIS list
CANDIDATE_M = 150.0     # station → line projection candidates
SHARED_STOP_MOVE_M = 100.0  # max move for a stop that non-Gyeonggi trips also use


# --------------------------------------------------------------------------- geometry
class Line:
    """Metric polyline with cumulative distance and a coarse segment grid."""

    CELL = 200.0
    SAME_PASS_M = 50.0

    def __init__(self, latlon: list[tuple[float, float]]):
        self.latlon = latlon
        self.xy = [_to_m(lat, lon) for lat, lon in latlon]
        self.cum = [0.0]
        for a, b in zip(self.xy, self.xy[1:]):
            self.cum.append(self.cum[-1] + math.dist(a, b))
        self.grid: dict[tuple[int, int], list[int]] = defaultdict(list)
        for i, (a, b) in enumerate(zip(self.xy, self.xy[1:])):
            for gx in range(int(min(a[0], b[0]) // self.CELL), int(max(a[0], b[0]) // self.CELL) + 1):
                for gy in range(int(min(a[1], b[1]) // self.CELL), int(max(a[1], b[1]) // self.CELL) + 1):
                    self.grid[(gx, gy)].append(i)

    @property
    def length(self) -> float:
        return self.cum[-1]

    def _project(self, p, i) -> tuple[float, float]:
        """(distance to segment i, measure along the line of the foot point)."""
        a, b = self.xy[i], self.xy[i + 1]
        dx, dy = b[0] - a[0], b[1] - a[1]
        seg2 = dx * dx + dy * dy
        t = 0.0 if seg2 == 0 else max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / seg2))
        return math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy), self.cum[i] + t * math.sqrt(seg2)

    def candidates(self, p, radius: float = CANDIDATE_M) -> list[tuple[float, float]]:
        """(distance, measure) of the nearest point of each pass of the line near p.

        A round-trip line passes most roads twice; each pass is a separate
        candidate so the monotone assignment can pick the right one.  Passes are
        told apart by measure, not by segment adjacency: at a turnaround the two
        passes are contiguous segments.  A foot point is kept when no other foot
        point within ``SAME_PASS_M`` of measure is nearer.  Falls back to the
        global nearest point when nothing is within ``radius``.
        """
        cx, cy = int(p[0] // self.CELL), int(p[1] // self.CELL)
        r = int(radius // self.CELL) + 1
        segs = sorted({i for gx in range(cx - r, cx + r + 1) for gy in range(cy - r, cy + r + 1)
                       for i in self.grid.get((gx, gy), ())})
        near = [dm for dm in (self._project(p, i) for i in segs) if dm[0] <= radius]
        out = [(d, m) for d, m in near
               if not any(abs(m2 - m) <= self.SAME_PASS_M and (d2, m2) < (d, m) for d2, m2 in near)]
        if out:
            return out
        best = min((self._project(p, i) for i in range(len(self.xy) - 1)), default=(0.0, 0.0))
        return [best]

    def cut(self, m0: float, m1: float) -> list[tuple[float, float, float]]:
        """(lat, lon, dist from m0) of the line between measures m0 <= m1."""
        def at(m):
            i = max(0, min(len(self.cum) - 2, bisect.bisect_right(self.cum, m) - 1))
            seg = self.cum[i + 1] - self.cum[i]
            t = 0.0 if seg == 0 else (m - self.cum[i]) / seg
            (la, lo), (lb, lob) = self.latlon[i], self.latlon[i + 1]
            return la + (lb - la) * t, lo + (lob - lo) * t, i
        la, lo, i0 = at(m0)
        lb, lob, i1 = at(m1)
        out = [(la, lo, 0.0)]
        for k in range(i0 + 1, i1 + 1):
            if self.cum[k] > m0:
                out.append((self.latlon[k][0], self.latlon[k][1], self.cum[k] - m0))
        out.append((lb, lob, m1 - m0))
        return out


def monotone_measures(line: Line, points: list[tuple[float, float]],
                      first_may_be_line_start: bool = False) -> tuple[list[float], list[float]]:
    """Assign each point (in order) a line measure, non-decreasing, minimising total distance.

    DP over each point's candidates.  Returns (measures, residual distances).

    ``first_may_be_line_start``: on a closed loop the first station can sit nearer
    the line's end than its start (another floor or bay of the same terminal); its
    only candidate is then the end and every later station collapses onto it.
    The line start is offered as a further candidate for the first point when it
    is within ``LOOP_START_M``.
    """
    if not points:
        return [], []
    cands = [line.candidates(p) for p in points]
    if first_may_be_line_start:
        d0 = math.dist(points[0], line.xy[0])
        if d0 <= LOOP_START_M and all(m > 1.0 for _, m in cands[0]):
            cands[0] = [(d0, 0.0)] + list(cands[0])
    INF = float("inf")
    cost = [[d for d, _ in cands[0]]]
    back: list[list[int]] = [[-1] * len(cands[0])]
    for k in range(1, len(cands)):
        row, brow = [], []
        for d, m in cands[k]:
            best, arg = INF, -1
            for j, (_, pm) in enumerate(cands[k - 1]):
                if pm <= m + 1.0 and cost[-1][j] < best:
                    best, arg = cost[-1][j], j
            if arg < 0:  # no feasible predecessor: allow it at a large penalty (keeps a path)
                arg = min(range(len(cands[k - 1])), key=lambda j: cost[-1][j])
                best = cost[-1][arg] + 1e6
            row.append(best + d)
            brow.append(arg)
        cost.append(row)
        back.append(brow)
    j = min(range(len(cost[-1])), key=lambda x: cost[-1][x])
    picks = [0] * len(cands)
    for k in range(len(cands) - 1, -1, -1):
        picks[k] = j
        j = back[k][j]
    meas, resid = [], []
    run = 0.0
    for k, j in enumerate(picks):
        d, m = cands[k][j]
        run = max(run, m)  # enforce monotone even on the penalty path
        meas.append(run)
        resid.append(d)
    return meas, resid


# --------------------------------------------------------------------------- times
def parse_time(s: str) -> int:
    h, m, sec = s.split(":")
    return int(h) * 3600 + int(m) * 60 + int(sec)


def fmt_time(t: int) -> str:
    return f"{t // 3600:02d}:{t % 3600 // 60:02d}:{t % 60:02d}"


def lcs_pairs(a: list[str], b: list[str]) -> list[tuple[int, int]]:
    """Index pairs of a longest common subsequence of a and b."""
    n, m = len(a), len(b)
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n - 1, -1, -1):
        for j in range(m - 1, -1, -1):
            dp[i][j] = dp[i + 1][j + 1] + 1 if a[i] == b[j] else max(dp[i + 1][j], dp[i][j + 1])
    out, i, j = [], 0, 0
    while i < n and j < m:
        if a[i] == b[j]:
            out.append((i, j)); i += 1; j += 1
        elif dp[i + 1][j] >= dp[i][j + 1]:
            i += 1
        else:
            j += 1
    return out


def synthesize_times(old_ids: list[str], old_times: list[tuple[int, int]],
                     new_ids: list[str], new_meas: list[float]) -> list[tuple[int, int, bool]] | None:
    """(arrival, departure, is_anchor) for each new stop.

    The first and last new stop take the old trip's first departure / last
    arrival.  Stops shared with the old trip keep their times (anchors) when they
    are consistent: time never runs backwards and the bus never has to go faster
    than ``MAX_SPEED_MS`` to reach the anchor or to get from it to the end (a
    recurring station id matched on the wrong pass fails this).  The rest are
    interpolated by distance between anchors.  None when the whole span cannot be
    run in the old trip's time at that speed (span and timetable disagree).
    """
    n = len(new_ids)
    if n < 2:
        return None
    first_dep, last_arr = old_times[0][1], old_times[-1][0]
    m_first, m_last = new_meas[0], new_meas[-1]
    if last_arr < first_dep or (m_last - m_first) > MAX_SPEED_MS * max(1, last_arr - first_dep):
        return None
    # The old trip's first/last time belongs to the new first/last stop (forced below);
    # matched mid-span it would pin the rest of the span to the same minute.
    last_i = len(old_ids) - 1
    lcs = {j: old_times[i] for i, j in lcs_pairs(old_ids, new_ids)
           if (i != 0 or j == 0) and (i != last_i or j == n - 1)}
    first_arr = lcs[0][0] if 0 in lcs and lcs[0][0] <= first_dep else first_dep
    anchors: dict[int, tuple[int, int]] = {0: (first_arr, first_dep)}
    kept = [0]
    for j in sorted(k for k in lcs if 0 < k < n - 1):
        arr, dep = lcs[j]
        prev = kept[-1]
        prev_dep = anchors[prev][1]
        if not (prev_dep <= arr <= dep <= last_arr):
            continue
        if (new_meas[j] - new_meas[prev]) > MAX_SPEED_MS * max(1, arr - prev_dep):
            continue
        if (m_last - new_meas[j]) > MAX_SPEED_MS * max(1, last_arr - dep):
            continue
        anchors[j] = (arr, dep)
        kept.append(j)
    last_dep = max(last_arr, lcs[n - 1][1]) if n - 1 in lcs else last_arr
    anchors[n - 1] = (last_arr, last_dep)
    kept.append(n - 1)
    out: list[tuple[int, int, bool]] = [None] * n  # type: ignore[list-item]
    for k in kept:
        out[k] = (anchors[k][0], anchors[k][1], True)
    for lo, hi in zip(kept, kept[1:]):
        t0, t1 = anchors[lo][1], anchors[hi][0]
        m0, m1 = new_meas[lo], new_meas[hi]
        for k in range(lo + 1, hi):
            frac = (new_meas[k] - m0) / (m1 - m0) if m1 > m0 else (k - lo) / (hi - lo)
            t = int(round(t0 + (t1 - t0) * frac))
            out[k] = (t, t, False)
    return out


# --------------------------------------------------------------------------- GBIS
@dataclass
class Gbis:
    line: Line | None
    stations: list[dict]          # filtered (no waypoints), in order
    station_meas: list[float] = field(default_factory=list)
    station_resid: list[float] = field(default_factory=list)
    turn: int | None = None


def _body(path: Path, key: str) -> list[dict] | None:
    try:
        resp = json.loads(path.read_text())["response"]
    except (OSError, ValueError, KeyError):
        return None
    if int(resp.get("msgHeader", {}).get("resultCode", -1)) != 0:
        return None
    items = (resp.get("msgBody") or {}).get(key)
    if isinstance(items, dict):
        items = [items]
    return items or None


def load_gbis(gbis_dir: Path, route9: str) -> Gbis | None:
    pts = _body(gbis_dir / f"{route9}.line.json", "busRouteLineList")
    sts = _body(gbis_dir / f"{route9}.stations.json", "busRouteStationList")
    line = None
    if pts and len(pts) >= 2:
        pts = sorted(pts, key=lambda p: int(p["lineSeq"]))
        line = Line([(float(p["y"]), float(p["x"])) for p in pts])
    if line is None:
        return None
    stations: list[dict] = []
    turn = None
    for s in sorted(sts or [], key=lambda s: int(s["stationSeq"])):
        if str(s.get("stationName", "")).strip().endswith(WAYPOINT_SUFFIX):
            continue
        if s.get("turnYn") == "Y" and turn is None:
            turn = len(stations)
        stations.append(s)
    g = Gbis(line=line, stations=stations, turn=turn)
    if stations:
        g.station_meas, g.station_resid = monotone_measures(
            line, [_to_m(float(s["y"]), float(s["x"])) for s in stations])
    return g


def _seoul_items(path: Path) -> list[dict] | None:
    try:
        body = json.loads(path.read_text())
    except (OSError, ValueError):
        return None
    if str((body.get("msgHeader") or {}).get("headerCd")) != "0":
        return None
    items = (body.get("msgBody") or {}).get("itemList")
    if isinstance(items, dict):
        items = [items]
    return items or None


def _coord(item: dict) -> tuple[float, float] | None:
    """(lat, lon) of a ws.bus.go.kr item, None when it carries no usable coordinate."""
    try:
        lat, lon = float(item["gpsY"]), float(item["gpsX"])
    except (KeyError, TypeError, ValueError):
        return None
    return (lat, lon) if 33.0 < lat < 39.5 and 124.0 < lon < 132.0 else None


def load_seoul(source_dir: Path, route9: str) -> Gbis | None:
    """Same shape as load_gbis, from fetch_seoul_routes.py files (stations use the GBIS key names)."""
    pts = _seoul_items(source_dir / f"{route9}.path.json")
    sts = _seoul_items(source_dir / f"{route9}.stations.json")
    latlon = [c for p in sorted(pts or [], key=lambda p: int(p["no"])) if (c := _coord(p)) is not None]
    if len(latlon) < 2:
        return None
    line = Line(latlon)
    stations: list[dict] = []
    turn = None
    for s in sorted(sts or [], key=lambda s: int(s["seq"])):
        name = str(s.get("stationNm", "")).strip()
        c = _coord(s)
        # A real stop has a 5-digit stop number; bridges, junctions and toll gates on the list
        # ("…(가상)", "북인천IC", "영종대교") have none.
        ars = str(s.get("arsId") or "").strip()
        if (c is None or not str(s.get("station", "")).strip() or ars in ("", "0", "00000")
                or any(tag in name for tag in SEOUL_PASS_THROUGH)):
            continue
        if s.get("transYn") == "Y" and turn is None:
            turn = len(stations)
        stations.append({"stationId": str(s["station"]).strip(), "stationName": name,
                         "y": c[0], "x": c[1], "turnYn": s.get("transYn")})
    g = Gbis(line=line, stations=stations, turn=turn)
    if stations:
        g.station_meas, g.station_resid = monotone_measures(
            line, [_to_m(float(s["y"]), float(s["x"])) for s in stations], first_may_be_line_start=True)
    return g


LOADER = load_gbis


def configure(provider: str) -> None:
    """Point the module at one provider's id rules and cache format."""
    global ROUTE_RE, STOP_PREFIX, SHAPE_TAG, REFRESH_NAMES, LOADER, WHOLE_ROUTE_WHEN_OFF_LINE
    if provider == "seoul":
        ROUTE_RE, STOP_PREFIX, SHAPE_TAG, REFRESH_NAMES, LOADER, WHOLE_ROUTE_WHEN_OFF_LINE = (
            SEOUL_ROUTE_RE, SEOUL_STOP_PREFIX, "seoul", True, load_seoul, True)
    elif provider == "gbis":
        ROUTE_RE, STOP_PREFIX, SHAPE_TAG, REFRESH_NAMES, LOADER, WHOLE_ROUTE_WHEN_OFF_LINE = (
            GBIS_ROUTE_RE, GBIS_STOP_PREFIX, "gbis", False, load_gbis, False)
    else:
        raise ValueError(f"unknown provider {provider}")


def stop9(stop_id: str) -> str | None:
    return stop_id[len(STOP_PREFIX):] if stop_id.startswith(STOP_PREFIX) else None


def choose_span(g: Gbis, suffix: str | None, old_ids: list[str],
                old_xy: list[tuple[float, float] | None], round_trip: bool) -> tuple[int, int, str] | None:
    """[a, b] station index span of the GBIS list this trip runs, and how it was found."""
    ids = [str(s["stationId"]) for s in g.stations]
    n = len(ids)
    if n < 2:
        return None
    pos = defaultdict(list)
    for i, sid in enumerate(ids):
        pos[sid].append(i)
    sxy = [_to_m(float(s["y"]), float(s["x"])) for s in g.stations]

    def ends(k: int) -> dict[int, float]:
        """GBIS index → distance (0 for an id match) for the old trip's stop k."""
        if old_ids[k] in pos:
            return {i: 0.0 for i in pos[old_ids[k]]}
        if old_xy[k] is None:
            return {}
        return {i: d for i in range(n) if (d := math.dist(sxy[i], old_xy[k])) <= NEAR_STOP_M}

    first, last = ends(0), ends(len(old_ids) - 1)
    old_set = set(old_ids)
    best = None
    for a, da in first.items():
        for b, db in last.items():
            if b <= a:
                continue
            # distinct ids: on a loop the same station recurs, and counting it twice would
            # stretch the span past the old trip's end
            cover = len(old_set.intersection(ids[a:b + 1]))
            # most shared stops, then nearest ends (coordinate fallback), then closest stop count
            score = (cover, -round(da + db), -abs((b - a + 1) - len(old_ids)))
            if best is None or score > best[0]:
                best = (score, a, b)
    common = len(old_set & set(ids))
    if best is not None and round_trip and best[0][0] < 0.5 * common:
        # A round trip that GBIS starts at another station: its ends match two
        # neighbouring GBIS stations and the span would cover a sliver of the loop.
        return 0, n - 1, "loopRotated"
    if best is not None:
        how = "id" if old_ids[0] in pos and old_ids[-1] in pos else "near"
        return best[1], best[2], how
    if suffix == "_U" and g.turn is not None and g.turn > 0:
        return 0, g.turn, "suffix"
    if suffix == "_D" and g.turn is not None and g.turn < n - 1:
        return g.turn, n - 1, "suffix"
    if round_trip:
        return 0, n - 1, "loop"
    return None


# --------------------------------------------------------------------------- feed IO
def _reader(zf: zipfile.ZipFile, name: str):
    return csv.reader(io.TextIOWrapper(zf.open(name), encoding="utf-8-sig", newline=""))


def _lines(zf: zipfile.ZipFile, name: str):
    return io.TextIOWrapper(zf.open(name), encoding="utf-8-sig", newline="")


def run(in_zip: Path, out_zip: Path, gbis_dir: Path, report_path: Path) -> dict:
    zin = zipfile.ZipFile(in_zip)
    # trips
    rd = _reader(zin, "trips.txt")
    trips_hdr = next(rd)
    T = {c: i for i, c in enumerate(trips_hdr)}
    trips_rows = list(rd)
    gg_trip: dict[str, tuple[str, str | None]] = {}
    shape_users: Counter = Counter()
    for r in trips_rows:
        shape_users[r[T["shape_id"]]] += 1
        m = ROUTE_RE.match(r[T["route_id"]])
        if m:
            gg_trip[r[T["trip_id"]]] = (m.group(1), m.group(2))
    trip_shape = {r[T["trip_id"]]: r[T["shape_id"]] for r in trips_rows if r[T["trip_id"]] in gg_trip}

    # stops
    rd = _reader(zin, "stops.txt")
    stops_hdr = next(rd)
    S = {c: i for i, c in enumerate(stops_hdr)}
    stops_rows = list(rd)
    stop_xy = {r[S["stop_id"]]: _to_m(float(r[S["stop_lat"]]), float(r[S["stop_lon"]])) for r in stops_rows}

    # pass 1: Gyeonggi trips' stop_times + stops used by other trips
    st = _lines(zin, "stop_times.txt")
    st_hdr_line = st.readline()
    st_hdr = next(csv.reader([st_hdr_line]))
    C = {c: i for i, c in enumerate(st_hdr)}
    old_rows: dict[str, list[list[str]]] = defaultdict(list)
    other_stops: set[str] = set()
    for line in st:
        tid = line.split(",", 1)[0]
        if tid in gg_trip:
            old_rows[tid].append(next(csv.reader([line])))
        else:
            other_stops.add(line.split(",", 4)[3])
    for rows in old_rows.values():
        rows.sort(key=lambda r: int(r[C["stop_sequence"]]))

    gg_shapes = {trip_shape[t] for t in gg_trip}
    sh_hdr = next(_reader(zin, "shapes.txt"))

    # rewrite
    gbis_cache: dict[str, Gbis | None] = {}
    new_stop_times: dict[str, list[dict]] = {}
    new_trip_shape: dict[str, str] = {}
    new_shapes: dict[str, list[tuple[float, float, float]]] = {}
    station_votes: dict[str, Counter] = defaultdict(Counter)
    station_name: dict[str, str] = {}
    rep = {"trips": len(gg_trip), "rewritten": 0, "shapeOnly": 0, "unchanged": Counter(),
           "spanHow": Counter(), "spanLengthRejected": 0, "timeRejected": 0, "routes": {}, "stopResidualM": [], "shapeOnlyResidualM": [],
           "gbisMissingRoutes": []}
    for tid, (route9, suffix) in gg_trip.items():
        rows = old_rows.get(tid)
        if not rows:
            rep["unchanged"]["noStopTimes"] += 1
            continue
        if route9 not in gbis_cache:
            gbis_cache[route9] = LOADER(gbis_dir, route9)
            if gbis_cache[route9] is None:
                rep["gbisMissingRoutes"].append(route9)
        g = gbis_cache[route9]
        rr = rep["routes"].setdefault(route9, Counter())
        if g is None:
            rep["unchanged"]["noGbis"] += 1
            rr["unchanged"] += 1
            continue
        old_ids = [r[C["stop_id"]] for r in rows]
        old_times = [(parse_time(r[C["arrival_time"]]), parse_time(r[C["departure_time"]])) for r in rows]
        old9 = [stop9(s) or s for s in old_ids]
        # round trip = the trip itself ends where it starts (a shared shape may be a
        # full loop even for a short-turn trip)
        first_xy, last_xy = stop_xy.get(old_ids[0]), stop_xy.get(old_ids[-1])
        is_loop = bool(first_xy and last_xy and math.dist(first_xy, last_xy) <= LOOP_M)
        span = None
        if g.stations:
            span = choose_span(g, suffix, old9, [stop_xy.get(s) for s in old_ids], is_loop)
        if span is not None:
            try:
                old_len = float(rows[-1][C["shape_dist_traveled"]]) - float(rows[0][C["shape_dist_traveled"]])
            except (KeyError, ValueError):
                old_len = 0.0
            new_len = g.station_meas[span[1]] - g.station_meas[span[0]]
            if old_len > 0 and not (old_len / SPAN_LENGTH_RATIO - SPAN_LENGTH_SLACK_M
                                    <= new_len <= old_len * SPAN_LENGTH_RATIO + SPAN_LENGTH_SLACK_M):
                rep["spanLengthRejected"] += 1
                span = None
        times = None
        if span is not None:
            a, b, how = span
            sts = g.stations[a:b + 1]
            new9 = [str(s["stationId"]) for s in sts]
            meas = g.station_meas[a:b + 1]
            times = synthesize_times(old9, old_times, new9, meas) if meas[-1] > meas[0] else None
            if times is None:
                rep["timeRejected"] += 1
        if (times is None and WHOLE_ROUTE_WHEN_OFF_LINE and len(g.stations) >= 2
                and g.station_meas[-1] > g.station_meas[0] and all(s in stop_xy for s in old_ids)):
            # The old stops do not lie on the official line at all: the route was replaced, not
            # adjusted, and a shape-only swap would leave a trip that follows neither.  Run the
            # whole official list in the old trip's time window instead.
            _, off = monotone_measures(g.line, [stop_xy[s] for s in old_ids])
            if max(off) > OFF_LINE_M:
                a, b, how = 0, len(g.stations) - 1, "offLine"
                sts = g.stations
                new9 = [str(s["stationId"]) for s in sts]
                meas = g.station_meas
                times = synthesize_times(old9, old_times, new9, meas)
        if times is None:
            # shape only: keep stops and times, project the stops on the GBIS line
            if any(s not in stop_xy for s in old_ids):
                rep["unchanged"]["unknownStop"] += 1
                rr["unchanged"] += 1
                continue
            meas, resid = monotone_measures(g.line, [stop_xy[s] for s in old_ids])
            if meas[-1] <= meas[0]:
                rep["unchanged"]["shapeOnlyDegenerate"] += 1
                rr["unchanged"] += 1
                continue
            shape_id = f"{SHAPE_TAG}_{route9}_s{round(meas[0])}_{round(meas[-1])}"
            new_shapes.setdefault(shape_id, g.line.cut(meas[0], meas[-1]))
            out = []
            for r, m in zip(rows, meas):
                d = dict(zip(st_hdr, r))
                d["shape_dist_traveled"] = f"{m - meas[0]:.3f}"
                out.append(d)
            new_stop_times[tid] = out
            new_trip_shape[tid] = shape_id
            rep["shapeOnly"] += 1
            rr["shapeOnly"] += 1
            rr["shapeOnlyMaxResidualM"] = max(rr["shapeOnlyMaxResidualM"], round(max(resid)))
            rep["shapeOnlyResidualM"].extend(resid)
            continue
        rep["spanHow"][how] += 1
        shape_id = f"{SHAPE_TAG}_{route9}_{a}_{b}"
        new_shapes.setdefault(shape_id, g.line.cut(meas[0], meas[-1]))
        out = []
        for k, (s, (arr, dep, anchor)) in enumerate(zip(sts, times)):
            sid = STOP_PREFIX + str(s["stationId"])
            station_votes[sid][(round(float(s["y"]), 6), round(float(s["x"]), 6))] += 1
            station_name.setdefault(sid, str(s.get("stationName", "")).strip())
            out.append({"trip_id": tid, "arrival_time": fmt_time(arr), "departure_time": fmt_time(dep),
                        "stop_id": sid, "stop_sequence": str(k + 1), "pickup_type": "0", "drop_off_type": "0",
                        "shape_dist_traveled": f"{meas[k] - meas[0]:.3f}", "timepoint": "1" if anchor else "0"})
        new_stop_times[tid] = out
        new_trip_shape[tid] = shape_id
        rep["rewritten"] += 1
        rr["rewritten"] += 1
        old_set, new_set = set(old9), set(new9)
        rr["stopsAdded"] = max(rr["stopsAdded"], len(new_set - old_set))
        rr["stopsDropped"] = max(rr["stopsDropped"], len(old_set - new_set))
        rep["stopResidualM"].extend(g.station_resid[a:b + 1])

    # stops: add new GBIS stations, move existing Gyeonggi ones to GBIS coordinates
    template = next((r for r in stops_rows if r[S["stop_id"]].startswith(STOP_PREFIX)), stops_rows[0])
    moved = added = kept_shared = renamed = 0
    for r in stops_rows:
        sid = r[S["stop_id"]]
        if sid in station_votes:
            if REFRESH_NAMES and station_name.get(sid) and r[S["stop_name"]] != station_name[sid]:
                r[S["stop_name"]] = station_name[sid]
                renamed += 1
            lat, lon = station_votes[sid].most_common(1)[0][0]
            move = math.dist(stop_xy[sid], _to_m(lat, lon))
            if sid in other_stops and move > SHARED_STOP_MOVE_M:
                kept_shared += 1
                continue
            r[S["stop_lat"]], r[S["stop_lon"]] = f"{lat:.6f}", f"{lon:.6f}"
            moved += move > 0.5
    existing = {r[S["stop_id"]] for r in stops_rows}
    for sid, votes in station_votes.items():
        if sid in existing:
            continue
        lat, lon = votes.most_common(1)[0][0]
        row = [""] * len(stops_hdr)
        for c in ("location_type", "wheelchair_boarding"):
            if c in S:
                row[S[c]] = template[S[c]]
        row[S["stop_id"]], row[S["stop_name"]] = sid, station_name[sid]
        row[S["stop_lat"]], row[S["stop_lon"]] = f"{lat:.6f}", f"{lon:.6f}"
        stops_rows.append(row)
        added += 1

    # shapes no longer used by any trip
    for tid, sid in new_trip_shape.items():
        shape_users[trip_shape[tid]] -= 1
    dropped_shapes = {s for s in gg_shapes if shape_users[s] <= 0}

    # write
    out_zip.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_zip.with_suffix(out_zip.suffix + ".tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
        for info in zin.infolist():
            name = info.filename
            if name == "trips.txt":
                buf = io.StringIO(); w = csv.writer(buf, lineterminator="\n"); w.writerow(trips_hdr)
                for r in trips_rows:
                    if r[T["trip_id"]] in new_trip_shape:
                        r[T["shape_id"]] = new_trip_shape[r[T["trip_id"]]]
                    w.writerow(r)
                zout.writestr(name, buf.getvalue())
            elif name == "stops.txt":
                buf = io.StringIO(); w = csv.writer(buf, lineterminator="\n"); w.writerow(stops_hdr)
                w.writerows(stops_rows)
                zout.writestr(name, buf.getvalue())
            elif name == "stop_times.txt":
                with zout.open(name, "w", force_zip64=True) as raw:
                    out = io.TextIOWrapper(raw, encoding="utf-8", newline="")
                    out.write(st_hdr_line)
                    src = _lines(zin, name); src.readline()
                    for line in src:
                        if line.split(",", 1)[0] not in new_stop_times:
                            out.write(line)
                    w = csv.writer(out, lineterminator="\n")
                    for rows in new_stop_times.values():
                        for d in rows:
                            w.writerow([d.get(c, "") for c in st_hdr])
                    out.flush(); out.detach()
            elif name == "shapes.txt":
                with zout.open(name, "w", force_zip64=True) as raw:
                    out = io.TextIOWrapper(raw, encoding="utf-8", newline="")
                    src = _lines(zin, name)
                    out.write(src.readline())
                    for line in src:
                        if line.split(",", 1)[0] not in dropped_shapes:
                            out.write(line)
                    w = csv.writer(out, lineterminator="\n")
                    for sid, pts in new_shapes.items():
                        for k, (lat, lon, dist) in enumerate(pts, 1):
                            w.writerow([{"shape_id": sid, "shape_pt_lat": f"{lat:.6f}", "shape_pt_lon": f"{lon:.6f}",
                                         "shape_pt_sequence": str(k), "shape_dist_traveled": f"{dist:.3f}"}.get(c, "")
                                        for c in sh_hdr])
                    out.flush(); out.detach()
            else:
                with zin.open(name) as src, zout.open(name, "w", force_zip64=True) as dst:
                    while chunk := src.read(1 << 20):
                        dst.write(chunk)
    tmp.replace(out_zip)

    def dist(vals):
        v = sorted(vals)
        pct = lambda q: round(v[min(len(v) - 1, int(q * len(v)))], 1)  # noqa: E731
        return {"n": len(v), "p50": pct(.5), "p90": pct(.9), "p99": pct(.99), "max": round(v[-1], 1)} if v else None
    summary = {
        "input": str(in_zip), "output": str(out_zip),
        "trips": rep["trips"], "rewritten": rep["rewritten"], "shapeOnly": rep["shapeOnly"],
        "unchanged": dict(rep["unchanged"]), "spanHow": dict(rep["spanHow"]),
        "spanLengthRejected": rep["spanLengthRejected"], "timeRejected": rep["timeRejected"],
        "routes": len(rep["routes"]), "gbisMissingRoutes": sorted(set(rep["gbisMissingRoutes"])),
        "newShapes": len(new_shapes), "droppedShapes": len(dropped_shapes),
        "stops": {"added": added, "moved": moved, "sharedKeptOldCoord": kept_shared, "renamed": renamed},
        "stopToShapeResidualM": dist(rep["stopResidualM"]),
        # shape-only trips keep 2025-03 stops; OTP drops a shape whose stops sit >150 m off it
        # (ShapeGeometryTooFar) and draws those hops straight.
        "shapeOnlyResidualM": dist(rep["shapeOnlyResidualM"]),
        "shapeOnlyRoutesOver150M": sorted(k for k, v in rep["routes"].items() if v["shapeOnlyMaxResidualM"] > 150),
        "perRoute": {k: dict(v) for k, v in sorted(rep["routes"].items())},
    }
    report_path.write_text(json.dumps(summary, ensure_ascii=False, indent=1))
    return summary


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("in_zip", type=Path)
    p.add_argument("out_zip", type=Path)
    p.add_argument("--gbis", type=Path, help="fetch_gbis_routes.py cache directory (provider gbis)")
    p.add_argument("--provider", choices=("gbis", "seoul"), default="gbis")
    p.add_argument("--source", type=Path, help="fetch_seoul_routes.py cache directory (provider seoul)")
    p.add_argument("--report", type=Path, required=True)
    a = p.parse_args()
    source = a.source if a.provider == "seoul" else a.gbis
    if source is None:
        p.error("--gbis (provider gbis) or --source (provider seoul) is required")
    configure(a.provider)
    s = run(a.in_zip, a.out_zip, source, a.report)
    print(json.dumps({k: v for k, v in s.items() if k != "perRoute"}, ensure_ascii=False, indent=1)[:4000])
    return 0


if __name__ == "__main__":
    sys.exit(main())
