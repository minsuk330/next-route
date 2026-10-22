#!/usr/bin/env python3
"""Hand corrections to the KTDB metro timetable OTP routes on (PR-G1, docs/plan/subway-express/PLAN.md).

KTDB GTFS 202503 is still the newest national feed, but some Seoul-area metro services changed after it was
built: 경인선 특급 runs as 급행 again, 경원선 급행 now stops at 덕계, one 경의중앙선 급행 ends at 팔당, and two
stations were renamed.  ``config/otp-national/subway-corrections.json`` lists them as operations:

* ``retemplate`` — the listed trips (one stop pattern, ``before``) take the stop pattern ``after`` of the same
  route: stop_id, stop_sequence, shape_dist_traveled, the time offsets from the first departure and the
  trip's shape_id are copied from the ``after`` trip whose first departure is closest.  The first departure
  is kept.
* ``insert_stop`` — ``stopId`` goes between the adjacent stops ``between`` (previous, next in travel order, so
  one op per direction).  Run times and stop_sequence come from the same-route trip that already serves
  previous → stop → next and leaves closest in time (a local train): arrival = previous departure + its run,
  departure = arrival + its dwell, and every later stop moves by what the stop costs (its run + dwell + run to
  the next stop - this trip's previous → next run).  shape_dist_traveled = the stop projected onto this trip's
  own shape.  ``expect`` pins the derived seconds so a timetable change fails the build instead of shifting.
* ``truncate_after`` — the trip stops after ``keepThrough`` and gets ``terminal`` as a new last stop (it passed
  it before): arrival from the same-route trip that runs keepThrough → terminal, shape_dist by projection,
  pickup/drop-off of the old last stop (no pickup, drop-off).  The stops after keepThrough go.
* ``rename`` — stop_name of ``stopId`` and of its virtual copies (``VST_<id>``, ``VEX_…_<id>``,
  ``VSA_P_<id>`` from add_station_entrances.py) that start with ``from`` start with ``to`` instead.  Ids stay.
* ``assert_absent`` — no trip of ``routeIds`` skips a stop of ``lineStops`` (공항철도 직통 is not routed, D3).
* ``move_stop`` — ``stopId`` (a metro platform whose KTDB point is wrong) moves from ``before`` to ``after`` (lat/lon,
  ``source`` names where ``after`` comes from).  Its virtual copies at the same point (``VST_<id>``, ``VSA_P_<id>``)
  move with it; exits (``VEX_``/``VSA_X_``) keep their OSM points.  pfaedle ends a shape on the stop it could not
  match, so the end point of every shape of ``routeIds`` whose trips start or end at the stop and that sits on
  ``before`` moves too (``expectShapeEnds`` of them).  shape_dist_traveled stays: the stop keeps its distance and
  OTP places stops by it.  ``expectMoveM`` pins the distance so a typo in a coordinate fails the build.

An op with ``"enabled": false`` is validated but not applied (K5851 until the train is identified).  Ids are
raw GTFS ids as in the zip; OTP shows them feed-scoped (``1:RR_ACC1_S-1-01-4D_Ord036``, pattern
``1:RR_ACC1_S-1-01-4D::03``) — a ``1:`` id in the config is rejected.

Idempotent: every target is checked against its before and after state.  One already in the after state is
skipped (``alreadyApplied``); one in neither state fails.  Running on its own output writes the same bytes.

Guards (the build fails): listed trips != ``expectTrips``; an ``exhaustive`` op leaves an unlisted trip of
the route in the before state; a changed trip overtakes the trip before or after it in its pattern (rule of
calibrate_bus_runtimes.py); stop_sequence, times or shape_dist_traveled stop increasing; a changed trip not on
a metro route (route_type 1) or a renamed stop outside ``RS_ACC1_``; the trip count changes.

Only rows of targeted trips and stops are rewritten.  Every other row of stops.txt, trips.txt and
stop_times.txt is written back as its original bytes, every other file is copied unchanged.  Stdlib only.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import shutil
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from calibrate_bus_runtimes import format_time, parse_time  # noqa: E402  (same time format as 2d)

METRO_ROUTE_TYPE = "1"
METRO_STOP_PREFIX = "RS_ACC1_"  # add_station_entrances.py --metro-prefix
TRIP_OPS = ("retemplate", "insert_stop", "truncate_after")
OPS = TRIP_OPS + ("rename", "assert_absent", "move_stop")
ZIP64_FORCE_BYTES = 1 << 30
BOM = b"\xef\xbb\xbf"


class CorrectionError(ValueError):
    pass


# --------------------------------------------------------------------------- #
# raw CSV rows: unchanged rows are written back as their original bytes
# --------------------------------------------------------------------------- #

def parse_line(line: bytes) -> list[str]:
    return next(csv.reader([line.decode("utf-8")]))


def line_end(line: bytes) -> bytes:
    if line.endswith(b"\r\n"):
        return b"\r\n"
    return line[-1:] if line[-1:] in (b"\n", b"\r") else b""


def format_line(fields: list[str], eol: bytes) -> bytes:
    buf = io.StringIO()
    csv.writer(buf, lineterminator=eol.decode()).writerow(fields)
    return buf.getvalue().encode("utf-8")


@dataclass
class Table:
    """A small GTFS file kept as raw lines + parsed rows."""
    name: str
    bom: bytes
    header_line: bytes
    col: dict[str, int]
    lines: list[bytes]
    rows: list[list[str] | None]  # None = blank line
    changed: dict[int, list[str]] = field(default_factory=dict)

    @classmethod
    def read(cls, zf: zipfile.ZipFile, name: str) -> "Table":
        data = zf.read(name)
        bom = BOM if data.startswith(BOM) else b""
        lines = data[len(bom):].splitlines(keepends=True)
        header = parse_line(lines[0])
        rows: list[list[str] | None] = []
        for k, ln in enumerate(lines[1:], start=2):
            if not ln.strip():
                rows.append(None)
                continue
            f = parse_line(ln)
            if len(f) != len(header):
                raise CorrectionError(f"{name}:{k}: {len(f)} fields, header has {len(header)}")
            rows.append(f)
        return cls(name, bom, lines[0], {h: i for i, h in enumerate(header)}, lines[1:], rows)

    def get(self, k: int, column: str) -> str:
        row = self.changed.get(k, self.rows[k])
        return row[self.col[column]]

    def set(self, k: int, column: str, value: str) -> None:
        row = list(self.changed.get(k, self.rows[k]))
        row[self.col[column]] = value
        if row == self.rows[k]:
            self.changed.pop(k, None)
        else:
            self.changed[k] = row

    def to_bytes(self) -> bytes:
        out = [self.bom, self.header_line]
        for k, ln in enumerate(self.lines):
            out.append(format_line(self.changed[k], line_end(ln)) if k in self.changed else ln)
        return b"".join(out)


# --------------------------------------------------------------------------- #
# zip members
# --------------------------------------------------------------------------- #

def out_info(info: zipfile.ZipInfo) -> zipfile.ZipInfo:
    """Same name/date/attributes as the source member → the same bytes when the content is the same."""
    zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
    zi.compress_type = zipfile.ZIP_DEFLATED
    zi.external_attr = info.external_attr
    zi.create_system = info.create_system
    return zi


def copy_member(src: zipfile.ZipFile, dst: zipfile.ZipFile, info: zipfile.ZipInfo) -> None:
    with src.open(info) as r, dst.open(out_info(info), "w", force_zip64=info.file_size > ZIP64_FORCE_BYTES) as w:
        shutil.copyfileobj(r, w, 1 << 20)


def write_member(dst: zipfile.ZipFile, info: zipfile.ZipInfo, data: bytes) -> None:
    with dst.open(out_info(info), "w", force_zip64=info.file_size > ZIP64_FORCE_BYTES) as w:
        w.write(data)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# --------------------------------------------------------------------------- #
# config
# --------------------------------------------------------------------------- #

def load_corrections(path: Path) -> dict:
    doc = json.loads(path.read_text(encoding="utf-8"))
    seen_ids: set[str] = set()
    seen_trips: dict[str, str] = {}
    for op in doc["operations"]:
        oid, kind = op.get("id"), op.get("op")
        if not oid or oid in seen_ids:
            raise CorrectionError(f"operation id missing or repeated: {oid!r}")
        seen_ids.add(oid)
        if kind not in OPS:
            raise CorrectionError(f"{oid}: unknown op {kind!r} (known: {', '.join(OPS)})")
        need = {"retemplate": ("routeId", "trips", "expectTrips", "before", "after"),
                "insert_stop": ("routeId", "trips", "expectTrips", "stopId", "between", "expect"),
                "truncate_after": ("routeId", "trips", "expectTrips", "keepThrough", "beforeTail", "terminal",
                                   "expect"),
                "rename": ("stopId", "from", "to"),
                "assert_absent": ("routeIds", "lineStops"),
                "move_stop": ("stopId", "routeIds", "before", "after", "expectMoveM", "expectShapeEnds", "source")}[kind]
        missing = [k for k in need if k not in op]
        if missing:
            raise CorrectionError(f"{oid}: missing {missing}")
        if not isinstance(op.get("enabled", True), bool):
            raise CorrectionError(f"{oid}: enabled must be true or false")
        feed_scoped = [v for v in _ids(op) if v.startswith("1:")]
        if feed_scoped:
            raise CorrectionError(f"{oid}: {feed_scoped[0]!r} is an OTP id; use the raw GTFS id without '1:'")
        if kind in TRIP_OPS:
            if len(op["trips"]) != op["expectTrips"]:
                raise CorrectionError(f"{oid}: {len(op['trips'])} trips listed, expectTrips {op['expectTrips']}")
            if len(set(op["trips"])) != len(op["trips"]):
                raise CorrectionError(f"{oid}: a trip is listed twice")
            for t in op["trips"]:
                if t in seen_trips:
                    raise CorrectionError(f"{oid}: trip {t} already changed by {seen_trips[t]}")
                seen_trips[t] = oid
        if kind == "insert_stop":
            if len(op["between"]) != 2 or op["stopId"] in op["between"]:
                raise CorrectionError(f"{oid}: 'between' must be the two stops around stopId")
            _check_expect(oid, op["expect"], ("arriveAfterPrevSec", "dwellSec", "shiftFollowingSec"))
        if kind == "truncate_after":
            if len(op["beforeTail"]) < 2 or op["beforeTail"][0] != op["keepThrough"]:
                raise CorrectionError(f"{oid}: beforeTail must start with keepThrough and drop at least one stop")
            if "stopId" not in op["terminal"] or op["terminal"]["stopId"] in op["beforeTail"]:
                raise CorrectionError(f"{oid}: terminal.stopId must be a stop the trip does not serve now")
            _check_expect(oid, op["expect"], ("arriveAfterPrevSec",))
        if kind == "move_stop":
            for k in ("before", "after"):
                pt = op[k]
                if not (isinstance(pt, dict) and all(isinstance(pt.get(c), (int, float)) for c in ("lat", "lon"))):
                    raise CorrectionError(f"{oid}: {k} must be {{lat, lon}} numbers")
            if not (isinstance(op["expectMoveM"], int) and isinstance(op["expectShapeEnds"], int)):
                raise CorrectionError(f"{oid}: expectMoveM and expectShapeEnds must be integers")
            if not (isinstance(op["source"], str) and op["source"].strip()):
                raise CorrectionError(f"{oid}: source must say where 'after' comes from")
        if kind == "rename":
            f, t = op["from"], op["to"]
            if not f or not t or f.startswith(t) or t.startswith(f):
                raise CorrectionError(f"{oid}: 'from' and 'to' must be non-empty and not prefixes of each other")
    return doc


def _ids(op: dict) -> list[str]:
    out = [op[k] for k in ("routeId", "stopId", "keepThrough") if isinstance(op.get(k), str)]
    for k in ("trips", "before", "after", "between", "beforeTail", "routeIds", "lineStops"):
        out += [v for v in op.get(k, []) if isinstance(v, str)]
    if isinstance(op.get("terminal"), dict):
        out.append(str(op["terminal"].get("stopId", "")))
    return out


def _check_expect(oid: str, expect: dict, keys: tuple[str, ...]) -> None:
    missing = [k for k in keys if not isinstance(expect.get(k), int)]
    if missing:
        raise CorrectionError(f"{oid}: expect needs integer {missing}")


def enabled_ops(doc: dict) -> list[dict]:
    return [op for op in doc["operations"] if op.get("enabled", True)]


# --------------------------------------------------------------------------- #
# feed
# --------------------------------------------------------------------------- #

@dataclass
class Feed:
    stops: Table
    trips: Table
    routes: dict[str, dict[str, str]]
    st_header: list[str]
    st_col: dict[str, int]
    trip_line: dict[str, int]  # trip_id -> trips.txt row index
    route_trips: dict[str, list[str]]  # loaded routes only
    rows: dict[str, list[list[str]]]  # trip_id -> stop_times rows by stop_sequence (loaded routes only)
    new_rows: dict[str, list[list[str]]] = field(default_factory=dict)
    shapes: dict[str, list[tuple[float, float, float]]] = field(default_factory=dict)  # (lat, lon, dist)
    shape_ends: dict[str, dict[str, tuple[int, str, str]]] = field(default_factory=dict)  # first/last: (seq, lat, lon)
    shape_moves: dict[tuple[str, int], tuple[str, str]] = field(default_factory=dict)  # (shape, seq) -> (lat, lon)

    def shape_id(self, tid: str) -> str:
        return self.trips.get(self.trip_line[tid], "shape_id") if "shape_id" in self.trips.col else ""

    def stop_point(self, sid: str) -> tuple[float, float]:
        if not hasattr(self, "_stop_k"):
            c = self.stops.col["stop_id"]
            self._stop_k = {r[c]: k for k, r in enumerate(self.stops.rows) if r is not None}
        k = self._stop_k.get(sid)
        if k is None:
            raise CorrectionError(f"stop {sid} not in stops.txt")
        return float(self.stops.get(k, "stop_lat")), float(self.stops.get(k, "stop_lon"))

    def trip_rows(self, tid: str) -> list[list[str]]:
        return self.new_rows.get(tid, self.rows.get(tid, []))

    def stop_ids(self, tid: str) -> list[str]:
        c = self.st_col["stop_id"]
        return [r[c] for r in self.trip_rows(tid)]

    def dep0(self, tid: str) -> int:
        return parse_time(self.trip_rows(tid)[0][self.st_col["departure_time"]])

    def end(self, tid: str) -> int:
        return parse_time(self.trip_rows(tid)[-1][self.st_col["arrival_time"]])


def trip_id_of(line: bytes, col: int) -> bytes:
    if col == 0 and not line.startswith(b'"'):
        cut = line.find(b",")
        return line if cut < 0 else line[:cut]
    return parse_line(line)[col].encode("utf-8")


def iter_stop_times(zf: zipfile.ZipFile):
    """Yield (header_line, raw lines...) of stop_times.txt in bytes, end of line included."""
    with zf.open("stop_times.txt") as f:
        yield from io.BufferedReader(f, 1 << 20)


def load_feed(zf: zipfile.ZipFile, route_ids: set[str]) -> tuple[Feed, int]:
    stops, trips = Table.read(zf, "stops.txt"), Table.read(zf, "trips.txt")
    routes_t = Table.read(zf, "routes.txt")
    routes = {r[routes_t.col["route_id"]]: {h: r[i] for h, i in routes_t.col.items()}
              for r in routes_t.rows if r is not None}
    for rid in sorted(route_ids):
        if rid not in routes:
            raise CorrectionError(f"route {rid} not in routes.txt")
    trip_line: dict[str, int] = {}
    route_trips: dict[str, list[str]] = {rid: [] for rid in route_ids}
    for k, r in enumerate(trips.rows):
        if r is None:
            continue
        tid, rid = r[trips.col["trip_id"]], r[trips.col["route_id"]]
        trip_line[tid] = k
        if rid in route_trips:
            route_trips[rid].append(tid)
    wanted = {t.encode("utf-8") for ts in route_trips.values() for t in ts}
    lines = iter_stop_times(zf)
    header_line = next(lines)
    header = parse_line(header_line.removeprefix(BOM))
    col = {h: i for i, h in enumerate(header)}
    for c in ("trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence"):
        if c not in col:
            raise CorrectionError(f"stop_times.txt has no {c}")
    rows: dict[str, list[list[str]]] = {}
    groups, prev = 0, None
    for ln in lines:
        tid = trip_id_of(ln, col["trip_id"])
        if tid != prev:
            groups += 1
            prev = tid
        if tid in wanted:
            f = parse_line(ln)
            if len(f) != len(header):
                raise CorrectionError(f"stop_times.txt: {len(f)} fields for {tid!r}, header has {len(header)}")
            rows.setdefault(f[col["trip_id"]], []).append(f)
    seq = col["stop_sequence"]
    for tid in rows:
        rows[tid].sort(key=lambda r: int(r[seq]))
    return Feed(stops, trips, routes, header, col, trip_line, route_trips, rows), groups


def load_shapes(zf: zipfile.ZipFile, shape_ids: set[str]) -> dict[str, list[tuple[float, float, float]]]:
    """Points (lat, lon, shape_dist_traveled) of the given shapes, by shape_pt_sequence."""
    if not shape_ids:
        return {}
    if "shapes.txt" not in zf.namelist():
        raise CorrectionError("shapes.txt missing: cannot place an inserted stop on its shape")
    pts: dict[str, list[tuple[int, float, float, float]]] = {}
    with zf.open("shapes.txt") as f:
        lines = io.BufferedReader(f, 1 << 20)
        header = parse_line(next(lines).removeprefix(BOM))
        col = {h: i for i, h in enumerate(header)}
        if "shape_dist_traveled" not in col:
            raise CorrectionError("shapes.txt has no shape_dist_traveled: cannot place an inserted stop")
        wanted = {s.encode("utf-8") for s in shape_ids}
        for ln in lines:
            if trip_id_of(ln, col["shape_id"]) not in wanted:
                continue
            r = parse_line(ln)
            pts.setdefault(r[col["shape_id"]], []).append(
                (int(r[col["shape_pt_sequence"]]), float(r[col["shape_pt_lat"]]), float(r[col["shape_pt_lon"]]),
                 float(r[col["shape_dist_traveled"]])))
    missing = sorted(shape_ids - pts.keys())
    if missing:
        raise CorrectionError(f"shapes not in shapes.txt: {missing}")
    return {s: [p[1:] for p in sorted(v)] for s, v in pts.items()}


def load_shape_ends(zf: zipfile.ZipFile, shape_ids: set[str]) -> dict[str, dict[str, tuple[int, str, str]]]:
    """First and last point (shape_pt_sequence, raw lat, raw lon) of the given shapes."""
    if not shape_ids:
        return {}
    ends: dict[str, dict[str, tuple[int, str, str]]] = {}
    with zf.open("shapes.txt") as f:
        lines = io.BufferedReader(f, 1 << 20)
        header = parse_line(next(lines).removeprefix(BOM))
        col = {h: i for i, h in enumerate(header)}
        wanted = {s.encode("utf-8") for s in shape_ids}
        for ln in lines:
            if trip_id_of(ln, col["shape_id"]) not in wanted:
                continue
            r = parse_line(ln)
            pt = (int(r[col["shape_pt_sequence"]]), r[col["shape_pt_lat"]], r[col["shape_pt_lon"]])
            e = ends.setdefault(r[col["shape_id"]], {"first": pt, "last": pt})
            if pt[0] < e["first"][0]:
                e["first"] = pt
            if pt[0] > e["last"][0]:
                e["last"] = pt
    missing = sorted(shape_ids - ends.keys())
    if missing:
        raise CorrectionError(f"shapes not in shapes.txt: {missing}")
    return ends


def metres(a: tuple[float, float], b: tuple[float, float]) -> float:
    ky = 111320.0
    return math.hypot((a[1] - b[1]) * ky * math.cos(math.radians(a[0])), (a[0] - b[0]) * ky)


MAX_STOP_TO_SHAPE_M = 50.0


def project(points: list[tuple[float, float, float]], lat: float, lon: float, lo: float, hi: float) -> tuple[float, float]:
    """(metres from the shape, shape_dist at the nearest point) over the shape part between lo and hi."""
    ky = 111320.0
    kx = ky * math.cos(math.radians(lat))
    best: tuple[float, float] | None = None
    for (a_lat, a_lon, a_d), (b_lat, b_lon, b_d) in zip(points, points[1:]):
        if b_d < lo or a_d > hi:
            continue
        ax, ay = (a_lon - lon) * kx, (a_lat - lat) * ky
        dx, dy = (b_lon - a_lon) * kx, (b_lat - a_lat) * ky
        seg = dx * dx + dy * dy
        t = 0.0 if seg == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / seg))
        off = math.hypot(ax + t * dx, ay + t * dy)
        if best is None or off < best[0]:
            best = (off, a_d + t * (b_d - a_d))
    if best is None:
        raise CorrectionError(f"shape has no part between shape_dist {lo} and {hi}")
    return best


# --------------------------------------------------------------------------- #
# operations
# --------------------------------------------------------------------------- #

def check_trips(feed: Feed, op: dict) -> None:
    for t in op["trips"]:
        if t not in feed.trip_line:
            raise CorrectionError(f"{op['id']}: trip {t} not in trips.txt")
        rid = feed.trips.get(feed.trip_line[t], "route_id")
        if rid != op["routeId"]:
            raise CorrectionError(f"{op['id']}: trip {t} is on {rid}, not {op['routeId']}")
        if t not in feed.rows:
            raise CorrectionError(f"{op['id']}: trip {t} has no stop_times")


def check_exhaustive(feed: Feed, op: dict, in_before_state) -> None:
    if not op.get("exhaustive"):
        return
    listed = set(op["trips"])
    left = sorted(t for t in feed.route_trips[op["routeId"]] if t not in listed and in_before_state(t))
    if left:
        raise CorrectionError(f"{op['id']}: {len(left)} unlisted trips in the before state, e.g. {left[:3]}")


def time_shifted(row: list[str], col: dict[str, int], delta: int) -> list[str]:
    r = list(row)
    for c in ("arrival_time", "departure_time"):
        r[col[c]] = format_time(parse_time(r[col[c]]) + delta)
    return r


def fmt_dist(v: float) -> str:
    return repr(round(v, 3))


def op_retemplate(feed: Feed, op: dict) -> dict:
    col, before, after = feed.st_col, op["before"], op["after"]
    check_trips(feed, op)
    check_exhaustive(feed, op, lambda t: feed.stop_ids(t) == before)
    listed = set(op["trips"])
    templates = [t for t in feed.route_trips[op["routeId"]] if t not in listed and feed.stop_ids(t) == after]
    if not templates:
        raise CorrectionError(f"{op['id']}: no trip of {op['routeId']} has the 'after' stop list")
    done, already = [], []
    for tid in op["trips"]:
        stops = feed.stop_ids(tid)
        if stops == after:
            already.append(tid)
            continue
        if stops != before:
            raise CorrectionError(f"{op['id']}: {tid} stop list is neither 'before' nor 'after': {stops}")
        d0 = feed.dep0(tid)
        tmpl = min(templates, key=lambda t: (abs(feed.dep0(t) - d0), feed.dep0(t), t))
        t0, old_end = feed.dep0(tmpl), feed.end(tid)
        new = []
        for r in feed.rows[tmpl]:
            r = time_shifted(r, col, d0 - t0)
            r[col["trip_id"]] = tid
            new.append(r)
        feed.new_rows[tid] = new
        k = feed.trip_line[tid]
        old_shape = feed.trips.get(k, "shape_id") if "shape_id" in feed.trips.col else None
        if old_shape is not None:
            feed.trips.set(k, "shape_id", feed.trips.get(feed.trip_line[tmpl], "shape_id"))
        done.append(dict(trip=tid, template=tmpl, firstDeparture=format_time(d0),
                         endBefore=format_time(old_end), endAfter=format_time(feed.end(tid)),
                         endShiftSec=feed.end(tid) - old_end, shapeBefore=old_shape,
                         shapeAfter=feed.trips.get(k, "shape_id") if old_shape is not None else None))
    return dict(applied=done, alreadyApplied=already)


def donor(feed: Feed, op: dict, at: int, run: list[str]) -> str:
    """Same-route trip (not a target) serving the stops ``run`` consecutively, leaving run[0] closest to ``at``."""
    listed = set(op["trips"])
    col, n = feed.st_col, len(run)
    best: tuple[int, int, str] | None = None
    for t in feed.route_trips[op["routeId"]]:
        if t in listed or t not in feed.rows:
            continue
        s = feed.stop_ids(t)
        for i in range(len(s) - n + 1):
            if s[i:i + n] == run:
                dep = parse_time(feed.rows[t][i][col["departure_time"]])
                key = (abs(dep - at), dep, t)
                best = key if best is None or key < best else best
                break
    if best is None:
        raise CorrectionError(f"{op['id']}: no trip of {op['routeId']} runs {' -> '.join(run)} to copy times from")
    return best[2]


def donor_times(feed: Feed, d: str, run: list[str]) -> tuple[list[tuple[int, int]], list[list[str]]]:
    col = feed.st_col
    s = feed.stop_ids(d)
    i = next(i for i in range(len(s)) if s[i:i + len(run)] == run)
    rows = feed.rows[d][i:i + len(run)]
    return [(parse_time(r[col["arrival_time"]]), parse_time(r[col["departure_time"]])) for r in rows], rows


def placed_on_shape(feed: Feed, op: dict, tid: str, sid: str, lo: float, hi: float) -> float:
    """shape_dist_traveled of ``sid`` on ``tid``'s own shape, strictly between lo and hi."""
    shape = feed.shape_id(tid)
    if not shape or shape not in feed.shapes:
        raise CorrectionError(f"{op['id']}: {tid} has no shape to place {sid} on")
    off, dist = project(feed.shapes[shape], *feed.stop_point(sid), lo, hi)
    if off > MAX_STOP_TO_SHAPE_M or not lo < dist < hi:
        raise CorrectionError(f"{op['id']}: {sid} is {off:.0f} m from {shape} at {dist:.1f} (needs {lo} < d < {hi})")
    return dist


def check_expect(op: dict, tid: str, got: dict) -> None:
    bad = {k: (v, op["expect"][k]) for k, v in got.items() if k in op["expect"] and v != op["expect"][k]}
    if bad:
        raise CorrectionError(f"{op['id']}: {tid} derived {bad} (got, expected) — timetable differs from the plan")


def op_insert_stop(feed: Feed, op: dict) -> dict:
    col, sid = feed.st_col, op["stopId"]
    prev, nxt = op["between"]

    def state(tid: str) -> tuple[str, int]:
        s = feed.stop_ids(tid)
        if sid in s:
            i = s.index(sid)
            if 0 < i < len(s) - 1 and s[i - 1] == prev and s[i + 1] == nxt and s.count(sid) == 1:
                return "after", i
            return "neither", -1
        hits = [i for i in range(len(s) - 1) if s[i] == prev and s[i + 1] == nxt]
        return ("before", hits[0]) if len(hits) == 1 else ("neither", -1)

    check_trips(feed, op)
    check_exhaustive(feed, op, lambda t: state(t)[0] == "before")
    done, already = [], []
    for tid in op["trips"]:
        st, i = state(tid)
        if st == "after":
            already.append(tid)
            continue
        if st != "before":
            raise CorrectionError(f"{op['id']}: {tid} has neither {prev}->{nxt} nor {prev}->{sid}->{nxt}")
        rows = feed.rows[tid]
        p, q = rows[i], rows[i + 1]
        pdep, qarr = parse_time(p[col["departure_time"]]), parse_time(q[col["arrival_time"]])
        if "expectHopSec" in op and qarr - pdep != op["expectHopSec"]:
            raise CorrectionError(f"{op['id']}: {tid} {prev}->{nxt} takes {qarr - pdep} s, expected {op['expectHopSec']}")
        d = donor(feed, op, pdep, [prev, sid, nxt])
        (_, d_pdep), (d_arr, d_dep), (d_narr, _) = donor_times(feed, d, [prev, sid, nxt])[0]
        run_in, dwell, run_out = d_arr - d_pdep, d_dep - d_arr, d_narr - d_dep
        shift = run_in + dwell + run_out - (qarr - pdep)
        check_expect(op, tid, dict(arriveAfterPrevSec=run_in, dwellSec=dwell, shiftFollowingSec=shift))
        if run_in <= 0 or run_out <= 0 or dwell < 0 or shift < 0:
            raise CorrectionError(f"{op['id']}: {tid} donor {d} gives run {run_in}/{run_out} s, dwell {dwell}, shift {shift}")
        seq = int(donor_times(feed, d, [prev, sid, nxt])[1][1][col["stop_sequence"]])
        if not int(p[col["stop_sequence"]]) < seq < int(q[col["stop_sequence"]]):
            raise CorrectionError(f"{op['id']}: {tid} has no free stop_sequence {seq} between {prev} and {nxt}")
        ins = list(p)
        ins[col["stop_id"]] = sid
        ins[col["stop_sequence"]] = str(seq)
        ins[col["arrival_time"]] = format_time(pdep + run_in)
        ins[col["departure_time"]] = format_time(pdep + run_in + dwell)
        for c in ("pickup_type", "drop_off_type"):
            if c in col:
                ins[col[c]] = "0"
        dist = None
        if "shape_dist_traveled" in col and p[col["shape_dist_traveled"]].strip():
            dist = placed_on_shape(feed, op, tid, sid, float(p[col["shape_dist_traveled"]]),
                                   float(q[col["shape_dist_traveled"]]))
            ins[col["shape_dist_traveled"]] = fmt_dist(dist)
        old_end = feed.end(tid)
        feed.new_rows[tid] = rows[:i + 1] + [ins] + [time_shifted(r, col, shift) for r in rows[i + 1:]]
        done.append(dict(trip=tid, donor=d, arrival=ins[col["arrival_time"]], departure=ins[col["departure_time"]],
                         stopSequence=seq, shapeDist=dist, shiftFollowingSec=shift,
                         endBefore=format_time(old_end), endAfter=format_time(feed.end(tid))))
    return dict(applied=done, alreadyApplied=already)


def op_truncate_after(feed: Feed, op: dict) -> dict:
    col, keep, before_tail, term = feed.st_col, op["keepThrough"], op["beforeTail"], op["terminal"]["stopId"]
    after_tail = [keep, term]
    check_trips(feed, op)
    done, already = [], []
    for tid in op["trips"]:
        s = feed.stop_ids(tid)
        if s[-2:] == after_tail and term not in s[:-1]:
            already.append(tid)
            continue
        if s[-len(before_tail):] != before_tail or term in s:
            raise CorrectionError(f"{op['id']}: {tid} ends with neither {before_tail} nor {after_tail}: {s[-6:]}")
        rows = feed.rows[tid]
        i = len(rows) - len(before_tail)
        kept, last, first_dropped = rows[:i + 1], rows[-1], rows[i + 1]
        d = donor(feed, op, parse_time(kept[-1][col["departure_time"]]), after_tail)
        (_, d_kdep), (d_tarr, _) = donor_times(feed, d, after_tail)[0]
        run = d_tarr - d_kdep
        check_expect(op, tid, dict(arriveAfterPrevSec=run))
        seq = int(donor_times(feed, d, after_tail)[1][1][col["stop_sequence"]])
        if not int(kept[-1][col["stop_sequence"]]) < seq < int(first_dropped[col["stop_sequence"]]):
            raise CorrectionError(f"{op['id']}: terminal stop_sequence {seq} does not fit after {keep}")
        end = list(last)  # a final stop's pickup/drop-off as KTDB writes it (no pickup, drop-off)
        end[col["stop_id"]] = term
        end[col["stop_sequence"]] = str(seq)
        t = parse_time(kept[-1][col["departure_time"]]) + run
        end[col["arrival_time"]] = end[col["departure_time"]] = format_time(t)
        dist = None
        if "shape_dist_traveled" in col and kept[-1][col["shape_dist_traveled"]].strip():
            dist = placed_on_shape(feed, op, tid, term, float(kept[-1][col["shape_dist_traveled"]]),
                                   float(first_dropped[col["shape_dist_traveled"]]))
            end[col["shape_dist_traveled"]] = fmt_dist(dist)
        old_end = feed.end(tid)
        feed.new_rows[tid] = kept + [end]
        done.append(dict(trip=tid, donor=d, dropped=[r[col["stop_id"]] for r in rows[i + 1:]], terminal=term,
                         stopSequence=seq, shapeDist=dist, endBefore=format_time(old_end), endAfter=format_time(t)))
    return dict(applied=done, alreadyApplied=already)


def op_rename(feed: Feed, op: dict) -> dict:
    sid, frm, to = op["stopId"], op["from"], op["to"]
    if not sid.startswith(METRO_STOP_PREFIX):
        raise CorrectionError(f"{op['id']}: {sid} is not a metro platform ({METRO_STOP_PREFIX}*)")
    t, c_id, c_name = feed.stops, feed.stops.col["stop_id"], feed.stops.col["stop_name"]
    family = [k for k, r in enumerate(t.rows) if r is not None and (r[c_id] == sid or r[c_id].endswith("_" + sid))]
    base = [k for k in family if t.rows[k][c_id] == sid]
    if not base:
        raise CorrectionError(f"{op['id']}: stop {sid} not in stops.txt")
    if not (t.get(base[0], "stop_name").startswith((frm, to))):
        raise CorrectionError(f"{op['id']}: {sid} is named {t.get(base[0], 'stop_name')!r}, neither {frm!r} nor {to!r}")
    done, already, other = [], [], []
    for k in family:
        name, stop = t.get(k, "stop_name"), t.rows[k][c_id]
        if name.startswith(to):
            already.append(stop)
        elif name.startswith(frm):
            t.set(k, "stop_name", to + name[len(frm):])
            done.append(dict(stop=stop, before=name, after=t.get(k, "stop_name")))
        else:
            other.append(dict(stop=stop, name=name))
    return dict(applied=done, alreadyApplied=already, untouched=other)


def op_assert_absent(feed: Feed, op: dict) -> dict:
    pos = {s: i for i, s in enumerate(op["lineStops"])}
    short = op.get("routeShortName")
    if short:
        extra = sorted(rid for rid, r in feed.routes.items()
                       if r.get("route_short_name") == short and rid not in op["routeIds"])
        if extra:
            raise CorrectionError(f"{op['id']}: other routes named {short!r}: {extra}")
    bad, n = [], 0
    for rid in op["routeIds"]:
        if not feed.route_trips[rid]:
            raise CorrectionError(f"{op['id']}: route {rid} has no trips")
        for tid in feed.route_trips[rid]:
            n += 1
            s = feed.stop_ids(tid)
            for a, b in zip(s, s[1:]):
                if a not in pos or b not in pos or abs(pos[a] - pos[b]) != 1:
                    bad.append(dict(trip=tid, hop=[a, b]))
                    break
    if bad:
        raise CorrectionError(f"{op['id']}: {len(bad)} trips skip stops (or leave the line), e.g. {bad[:3]}")
    return dict(tripsChecked=n)


SAME_POINT_M = 1.0  # a stop or shape point within this of before/after is in that state
MOVE_TOLERANCE_M = 5.0  # expectMoveM vs the before -> after distance
POINT_COPY_PREFIXES = ("VST_", "VSA_P_")  # add_station_entrances.py copies that sit on the platform point


def fmt_coord(raw: str, value: float) -> str:
    """At least 6 decimals (0.1 m), more when the row already had more (VST_ rows carry 7)."""
    decimals = max(6, len(raw.split(".", 1)[1]) if "." in raw else 0)
    return f"{value:.{decimals}f}"


def op_move_stop(feed: Feed, op: dict) -> dict:
    oid, sid = op["id"], op["stopId"]
    if not sid.startswith(METRO_STOP_PREFIX):
        raise CorrectionError(f"{oid}: {sid} is not a metro platform ({METRO_STOP_PREFIX}*)")
    before = (float(op["before"]["lat"]), float(op["before"]["lon"]))
    after = (float(op["after"]["lat"]), float(op["after"]["lon"]))
    moved = metres(before, after)
    if abs(moved - op["expectMoveM"]) > MOVE_TOLERANCE_M:
        raise CorrectionError(f"{oid}: before -> after is {moved:.0f} m, expectMoveM {op['expectMoveM']}")

    def state(lat: str, lon: str) -> str:
        p = (float(lat), float(lon))
        return "before" if metres(p, before) <= SAME_POINT_M else "after" if metres(p, after) <= SAME_POINT_M else ""

    t, c_id = feed.stops, feed.stops.col["stop_id"]
    family = [k for k, r in enumerate(t.rows) if r is not None
              and (r[c_id] == sid or (r[c_id].endswith("_" + sid) and r[c_id].startswith(POINT_COPY_PREFIXES)))]
    base = [k for k in family if t.rows[k][c_id] == sid]
    if not base:
        raise CorrectionError(f"{oid}: stop {sid} not in stops.txt")
    if not state(t.get(base[0], "stop_lat"), t.get(base[0], "stop_lon")):
        raise CorrectionError(f"{oid}: {sid} is at {t.get(base[0], 'stop_lat')},{t.get(base[0], 'stop_lon')}, "
                              f"neither before nor after")
    done, already, other = [], [], []
    for k in family:
        stop, lat, lon = t.rows[k][c_id], t.get(k, "stop_lat"), t.get(k, "stop_lon")
        st = state(lat, lon)
        if st == "before":
            t.set(k, "stop_lat", fmt_coord(lat, after[0]))
            t.set(k, "stop_lon", fmt_coord(lon, after[1]))
            done.append(dict(stop=stop, before=[lat, lon], after=[t.get(k, "stop_lat"), t.get(k, "stop_lon")]))
        elif st == "after":
            already.append(stop)
        else:
            other.append(dict(stop=stop, point=[lat, lon]))

    ends: set[tuple[str, str]] = set()
    for rid in op["routeIds"]:
        if not feed.route_trips[rid]:
            raise CorrectionError(f"{oid}: route {rid} has no trips")
        for tid in feed.route_trips[rid]:
            s = feed.stop_ids(tid)
            if sid in (s[0], s[-1]) and not feed.shape_id(tid):
                raise CorrectionError(f"{oid}: trip {tid} ends at {sid} without a shape")
            if s[0] == sid:
                ends.add((feed.shape_id(tid), "first"))
            if s[-1] == sid:
                ends.add((feed.shape_id(tid), "last"))
    if len(ends) != op["expectShapeEnds"]:
        raise CorrectionError(f"{oid}: {len(ends)} shape ends at {sid}, expectShapeEnds {op['expectShapeEnds']}")
    for shape, end in sorted(ends):
        seq, lat, lon = feed.shape_ends[shape][end]
        st = state(lat, lon)
        if st == "before":
            feed.shape_moves[(shape, seq)] = (fmt_coord(lat, after[0]), fmt_coord(lon, after[1]))
            done.append(dict(shape=shape, end=end, seq=seq, before=[lat, lon], after=list(feed.shape_moves[(shape, seq)])))
        elif st == "after":
            already.append(f"{shape}:{end}")
        else:
            raise CorrectionError(f"{oid}: {shape} {end} point {lat},{lon} is neither before nor after — "
                                  f"pfaedle matched it, do not move it")
    return dict(applied=done, alreadyApplied=already, untouched=other, moveM=round(moved))


OP_FUNCS = {"retemplate": op_retemplate, "insert_stop": op_insert_stop, "truncate_after": op_truncate_after,
            "rename": op_rename, "assert_absent": op_assert_absent, "move_stop": op_move_stop}


# --------------------------------------------------------------------------- #
# guards
# --------------------------------------------------------------------------- #

def check_monotonic(feed: Feed, tid: str) -> None:
    col = feed.st_col
    rows = feed.trip_rows(tid)
    dist_c = col.get("shape_dist_traveled")
    prev = None
    for r in rows:
        arr, dep, seq = parse_time(r[col["arrival_time"]]), parse_time(r[col["departure_time"]]), int(r[col["stop_sequence"]])
        dist = float(r[dist_c]) if dist_c is not None and r[dist_c].strip() else None
        if dep < arr:
            raise CorrectionError(f"{tid}: departs before it arrives at seq {seq}")
        if prev is not None:
            p_arr, p_dep, p_seq, p_dist = prev
            if seq <= p_seq or arr <= p_arr or arr < p_dep:
                raise CorrectionError(f"{tid}: stop_sequence or time not increasing at seq {seq}")
            if dist is not None and p_dist is not None and dist <= p_dist:
                raise CorrectionError(f"{tid}: shape_dist_traveled not increasing at seq {seq}")
        prev = (arr, dep, seq, dist)


def overtakes(a: list[tuple[int, int]], b: list[tuple[int, int]]) -> bool:
    """b leaves after a; True when b arrives at (or leaves) any later stop before a — calibrate's rule."""
    return any(b[k][0] < a[k][0] or b[k][1] < a[k][1] for k in range(1, len(a)))


def check_overtaking(feed: Feed, changed: set[str]) -> int:
    col = feed.st_col
    checked = 0
    by_pattern: dict[tuple[str, tuple[str, ...]], list[str]] = {}
    for rid, tids in feed.route_trips.items():
        for t in tids:
            if t in feed.rows:
                by_pattern.setdefault((rid, tuple(feed.stop_ids(t))), []).append(t)
    for (rid, _), tids in by_pattern.items():
        if not changed.intersection(tids):
            continue
        tids.sort(key=lambda t: (feed.dep0(t), t))
        times = {t: [(parse_time(r[col["arrival_time"]]), parse_time(r[col["departure_time"]]))
                     for r in feed.trip_rows(t)] for t in tids}
        for a, b in zip(tids, tids[1:]):
            if a in changed or b in changed:
                checked += 1
                if overtakes(times[a], times[b]):
                    raise CorrectionError(f"{rid}: {b} overtakes {a} in one stop pattern")
    return checked


# --------------------------------------------------------------------------- #
# run
# --------------------------------------------------------------------------- #

def apply(feed: Feed, doc: dict) -> list[dict]:
    results = []
    for op in doc["operations"]:
        if not op.get("enabled", True):
            results.append(dict(id=op["id"], op=op["op"], status="disabled", trips=op.get("trips", []),
                                reason=op.get("disabledReason", "")))
            continue
        res = OP_FUNCS[op["op"]](feed, op)
        applied, already = res.get("applied", []), res.get("alreadyApplied", [])
        status = ("checked" if op["op"] == "assert_absent" else
                  "applied" if applied and not already else
                  "alreadyApplied" if already and not applied else
                  "mixed" if applied else "noop")
        if op["op"] == "rename" and status == "noop":
            raise CorrectionError(f"{op['id']}: nothing named {op['from']!r} or {op['to']!r}")
        summary = dict(id=op["id"], op=op["op"], status=status, **res)
        for k in ("before", "after", "between", "beforeTail", "keepThrough", "from", "to"):
            if k in op:
                summary["spec_" + k] = op[k]
        results.append(summary)
    return results


def run(in_zip: Path, corrections: Path, out_zip: Path, report_path: Path | None = None) -> dict:
    if in_zip.resolve() == out_zip.resolve():
        raise CorrectionError("--out must not be --in")
    doc = load_corrections(corrections)
    ops = enabled_ops(doc)
    route_ids = {op["routeId"] for op in ops if op["op"] in TRIP_OPS}
    route_ids |= {r for op in ops if op["op"] in ("assert_absent", "move_stop") for r in op["routeIds"]}
    with zipfile.ZipFile(in_zip) as src:
        feed, groups_in = load_feed(src, route_ids)
        for rid in route_ids:
            if feed.routes[rid].get("route_type") != METRO_ROUTE_TYPE:
                raise CorrectionError(f"route {rid} is not a metro route (route_type {feed.routes[rid].get('route_type')})")
        placing = [t for op in ops if op["op"] in ("insert_stop", "truncate_after") for t in op["trips"]
                   if t in feed.trip_line]
        feed.shapes = load_shapes(src, {feed.shape_id(t) for t in placing} - {""})
        feed.shape_ends = load_shape_ends(src, {feed.shape_id(t) for op in ops if op["op"] == "move_stop"
                                                for rid in op["routeIds"] for t in feed.route_trips[rid]} - {""})
        results = apply(feed, doc)
        changed = {t for t, rows in feed.new_rows.items() if rows != feed.rows[t]}
        for t in changed:
            check_monotonic(feed, t)
        pairs = check_overtaking(feed, changed)
        changed_b = {t.encode("utf-8") for t in changed}
        rows_in = rows_out = groups_out = 0
        tmp = out_zip.with_suffix(out_zip.suffix + ".tmp")
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
            for info in src.infolist():
                if info.filename == "stops.txt":
                    write_member(dst, info, feed.stops.to_bytes())
                elif info.filename == "trips.txt":
                    write_member(dst, info, feed.trips.to_bytes())
                elif info.filename == "stop_times.txt":
                    rows_in, rows_out, groups_out = write_stop_times(src, dst, info, feed, changed_b)
                elif info.filename == "shapes.txt" and feed.shape_moves:
                    write_shapes(src, dst, info, feed)
                else:
                    copy_member(src, dst, info)
        if groups_out != groups_in:
            tmp.unlink()
            raise CorrectionError(f"trip count changed: stop_times trips {groups_in} -> {groups_out}")
        tmp.replace(out_zip)
    report = dict(
        input=str(in_zip), inputSha256=sha256_file(in_zip),
        corrections=str(corrections), correctionsSha256=sha256_file(corrections),
        correctionsVersion=doc.get("version"),
        script=Path(__file__).name, scriptSha256=sha256_file(Path(__file__)),
        output=str(out_zip), outputSha256=sha256_file(out_zip),
        operations=results,
        changes=dict(stopsRows=len(feed.stops.changed), tripsRows=len(feed.trips.changed),
                     shapesRows=len(feed.shape_moves),
                     stopTimesTrips=len(changed), stopTimesRowsIn=rows_in, stopTimesRowsOut=rows_out),
        guards=dict(trips=len(feed.trip_line), stopTimesTrips=groups_out, overtakingPairsChecked=pairs,
                    overtaking=0, monotonicTrips=len(changed)),
    )
    if report_path:
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return report


def write_stop_times(src: zipfile.ZipFile, dst: zipfile.ZipFile, info: zipfile.ZipInfo, feed: Feed,
                     changed: set[bytes]) -> tuple[int, int, int]:
    """Stream stop_times.txt: original bytes except the changed trips, written whole at their first row."""
    col = feed.st_col["trip_id"]
    rows_in = rows_out = groups = 0
    written: set[bytes] = set()
    prev = cur_out = None
    with dst.open(out_info(info), "w", force_zip64=info.file_size > ZIP64_FORCE_BYTES) as w:
        lines = iter_stop_times(src)
        header = next(lines)
        w.write(header)
        eol = line_end(header) or b"\n"
        for ln in lines:
            tid = trip_id_of(ln, col)
            if tid in changed:
                rows_in += 1
                if tid == prev:
                    continue
                if tid in written:
                    raise CorrectionError(f"stop_times.txt is not grouped by trip: {tid!r} reappears")
                written.add(tid)
                prev = tid
                if cur_out != tid:
                    groups += 1
                    cur_out = tid
                for r in feed.new_rows[tid.decode("utf-8")]:
                    w.write(format_line(r, eol))
                    rows_out += 1
                continue
            prev = tid
            if tid != cur_out:
                groups += 1
                cur_out = tid
            w.write(ln)
    return rows_in, rows_out, groups


def write_shapes(src: zipfile.ZipFile, dst: zipfile.ZipFile, info: zipfile.ZipInfo, feed: Feed) -> None:
    """Stream shapes.txt: original bytes except the moved end points (lat/lon only)."""
    with src.open(info) as f, dst.open(out_info(info), "w", force_zip64=info.file_size > ZIP64_FORCE_BYTES) as w:
        lines = io.BufferedReader(f, 1 << 20)
        header_line = next(lines)
        w.write(header_line)
        col = {h: i for i, h in enumerate(parse_line(header_line.removeprefix(BOM)))}
        wanted = {s.encode("utf-8") for s, _ in feed.shape_moves}
        hit = 0
        for ln in lines:
            if trip_id_of(ln, col["shape_id"]) in wanted:
                r = parse_line(ln)
                key = (r[col["shape_id"]], int(r[col["shape_pt_sequence"]]))
                if key in feed.shape_moves:
                    r[col["shape_pt_lat"]], r[col["shape_pt_lon"]] = feed.shape_moves[key]
                    w.write(format_line(r, line_end(ln) or b"\n"))
                    hit += 1
                    continue
            w.write(ln)
    if hit != len(feed.shape_moves):
        raise CorrectionError(f"shapes.txt: moved {hit} points, expected {len(feed.shape_moves)}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--in", dest="in_zip", type=Path, required=True)
    ap.add_argument("--corrections", type=Path, required=True, help="config/otp-national/subway-corrections.json")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--report", type=Path)
    a = ap.parse_args()
    try:
        rep = run(a.in_zip, a.corrections, a.out, a.report)
    except CorrectionError as e:
        print(f"subway corrections failed: {e}", file=sys.stderr)
        return 1
    print(json.dumps(dict(changes=rep["changes"], guards=rep["guards"],
                          operations=[dict(id=o["id"], status=o["status"],
                                           applied=len(o.get("applied", [])),
                                           alreadyApplied=len(o.get("alreadyApplied", [])))
                                      for o in rep["operations"]]), ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
