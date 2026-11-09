#!/usr/bin/env python3
"""KRIC urban-rail timetable file → trains per line and day type (#557, lines outside Seoul 1-9).

Source: 철도데이터포털 (KRIC) "전체_도시철도운행정보" xlsx, no key:
  https://data.kric.go.kr/rips/dataset/download.file?type=filedata&id=900&operation=1
Columns: 열차번호, 노선번호, 노선명, 운행구간기점명, 운행구간종점명, 운행유형, 요일구분, 운행구간정거장, 정거장도착시각,
정가장출발시각 (sic), 운행속도, 운영기관전화번호, 데이터기준일자 (Excel serial day).

Two row layouts, per line:
  row    one row = one stop of one train; times are fractions of a day ("0.2291…", after midnight "1.5625E-2")
         or "H:MM[:SS]".  A train = consecutive rows with the same (노선번호, 요일구분, 열차번호, 기점, 종점).
  multi  one row = one train; 운행구간정거장 "001-장기역+002-운양역…" (separators + , /), times "001-5:26:10+…",
         "3752-05:30:00,…", "001+06:00+…" or "001-05:00/…".  The first stop's arrival and the last stop's departure
         are placeholders ("00:00", ":", "  :  ").
Rows whose times are prose ("운행시격 3분~10분", "운영시간 …") have no timetable and are counted, not parsed.
Times never go backwards inside a train: a drop means midnight and adds 24 h (GTFS 24:xx).

Build (CLI): KRIC trains of each line in config/otp-national/kric-day-types.json → day-types data files for
apply_day_types.py (step 2h).  Station names → KTDB stop_ids by normalized stop_name, else
config/otp-national/kric-station-aliases.json (null = non-revenue stop, cut).  A missing terminal time (부산,
의정부) or one typo'd middle time is filled with the previous departure + the KTDB template's running time; minute
ties move 30 s.  Gate per direction as in seoul_timetable_trips build-day-types (60 s window, weekday match >= 95%
both ways, stop-time p90 <= 90 s) plus the line's lost trains (unmapped, no template, unfillable) in the
denominators.  Run on a feed before step 2h:

  python3 runner/kric_timetable_trips.py --kric data/gen/kric/data.xlsx --gtfs <feed before 2h> \
    --config config/otp-national/kric-day-types.json --aliases config/otp-national/kric-station-aliases.json \
    --out-dir config/otp-national/day-types --report data/gen/kric-report.json

Stdlib only (xlsx = zip + XML).
"""

from __future__ import annotations

import re
import zipfile
import xml.etree.ElementTree as ET
from datetime import date, timedelta
from pathlib import Path
from typing import Iterator

SOURCE_URL = "https://data.kric.go.kr/rips/dataset/download.file?type=filedata&id=900&operation=1"
NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
COLUMNS = ("열차번호", "노선번호", "노선명", "운행구간기점명", "운행구간종점명", "운행유형", "요일구분", "운행구간정거장",
           "정거장도착시각", "정가장출발시각", "운행속도", "운영기관전화번호", "데이터기준일자")
DAY = 86400
MIDNIGHT_DROP = 3600  # a time more than an hour before the previous stop's is past midnight

MULTI_STOP = re.compile(r"([A-Z]?\d+)-([^+,/]+)")
MULTI_TIME = re.compile(r"([A-Z]?\d+)\s*[-+]\s*(\d{1,2}:\d{2}(?::\d{2})?)")
HMS = re.compile(r"\s*(\d{1,2}):(\d{2})(?::(\d{2}))?\s*")
FRACTION = re.compile(r"\s*\d+(?:\.\d+)?(?:[Ee][-+]?\d+)?\s*")


class KricError(ValueError):
    pass


# --------------------------------------------------------------------------- #
# xlsx
# --------------------------------------------------------------------------- #

def _col_index(ref: str) -> int:
    n = 0
    for ch in re.match(r"[A-Z]+", ref).group(0):
        n = n * 26 + ord(ch) - 64
    return n - 1


def read_xlsx(path: Path) -> Iterator[dict[str, str]]:
    """Rows of the first sheet as {header: text}; the header row must be COLUMNS."""
    with zipfile.ZipFile(path) as z:
        shared: list[str] = []
        if "xl/sharedStrings.xml" in z.namelist():
            for _, el in ET.iterparse(z.open("xl/sharedStrings.xml")):
                if el.tag == NS + "si":
                    shared.append("".join(t.text or "" for t in el.iter(NS + "t")))
                    el.clear()
        header: list[str] | None = None
        for _, el in ET.iterparse(z.open("xl/worksheets/sheet1.xml")):
            if el.tag != NS + "row":
                continue
            cells: dict[int, str] = {}
            for c in el.iter(NS + "c"):
                v = c.find(NS + "v")
                if c.get("t") == "inlineStr":
                    text = "".join(t.text or "" for t in c.iter(NS + "t"))
                elif v is None:
                    continue
                elif c.get("t") == "s":
                    text = shared[int(v.text)]
                else:
                    text = v.text or ""
                cells[_col_index(c.get("r"))] = text
            el.clear()
            row = [cells.get(i, "") for i in range(max(cells, default=-1) + 1)]
            if header is None:
                header = row
                if tuple(header[:len(COLUMNS)]) != COLUMNS:
                    raise KricError(f"unexpected header {header[:len(COLUMNS)]}")
                continue
            yield {h: (row[i] if i < len(row) else "") for i, h in enumerate(header)}


# --------------------------------------------------------------------------- #
# times and trains
# --------------------------------------------------------------------------- #

def parse_time(cell: str) -> int | None:
    """Seconds after midnight of a 'H:MM[:SS]' or day-fraction cell; None for placeholders and prose."""
    if not cell or not cell.strip():
        return None
    m = HMS.fullmatch(cell)
    if m:
        return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3) or 0)
    if FRACTION.fullmatch(cell):
        return round(float(cell) * DAY)
    return None


def excel_day(serial: str) -> str | None:
    try:
        return (date(1899, 12, 30) + timedelta(days=int(float(serial)))).isoformat()
    except ValueError:
        return None


NIGHT_FROM, NIGHT_TO = 20 * 3600, 4 * 3600  # a train may cross midnight only from >= 20:00 to < 04:00
SERVICE_DAY_START = 3 * 3600  # a train leaving its origin before 03:00 runs on the previous service day (24:xx)

Stop = tuple  # (name, arrival | None, departure | None)


def _clock(stops: list[Stop]) -> list[Stop] | None:
    """One clock for the stops with times (None times kept as None); None when times go backwards other than
    once across midnight."""
    out, prev, extra = [], None, 0
    for name, a, d in stops:
        if a is None:
            out.append((name, None, None))
            continue
        if d < a and a >= NIGHT_FROM and d < NIGHT_TO:
            d += DAY
        a, d = a + extra, d + extra
        if prev is not None and a < prev:
            if extra == 0 and prev % DAY >= NIGHT_FROM and a % DAY < NIGHT_TO:
                extra = DAY
                a, d = a + DAY, d + DAY
            else:
                return None
        if d < a:
            return None
        out.append((name, a, d))
        prev = d
    return out


def unroll(stops: list[Stop]) -> tuple[list[Stop] | None, int]:
    """(stops on one clock, number of middle stops blanked).  When the times go backwards, one middle stop whose
    time is the odd one out (a typo) is blanked — the builder fills it from the template's running times.  A train
    whose origin leaves before 03:00 moves to 24:xx (previous service day).  None = unusable train."""
    if len(stops) < 2 or stops[0][1] is None:
        return None, 0
    out, blanked = _clock(stops), 0
    if out is None:
        for i in range(1, len(stops) - 1):
            if stops[i][1] is None:
                continue
            trial = stops[:i] + [(stops[i][0], None, None)] + stops[i + 1:]
            out = _clock(trial)
            if out is not None:
                blanked = 1
                break
    if out is None:
        return None, 0
    if out[0][2] < SERVICE_DAY_START:
        out = [(n, None if a is None else a + DAY, None if d is None else d + DAY) for n, a, d in out]
    return out, blanked


def _pick(i: int, n: int, arr: int | None, dep: int | None) -> tuple[int | None, int | None]:
    """(arrival, departure) of stop i of n.  Origin = departure, terminal = arrival, each falling back to the
    other cell; (None, None) when the stop has no time (filled later from the template)."""
    if i == 0:
        t = dep if dep is not None else arr
    elif i == n - 1:
        t = arr if arr is not None else dep
    else:
        if arr is None and dep is None:
            return None, None
        return (arr if arr is not None else dep, dep if dep is not None else arr)
    return t, t


def is_multi(row: dict[str, str]) -> bool:
    s = row["운행구간정거장"]
    return bool(MULTI_STOP.match(s.strip())) and any(sep in s for sep in "+,/")


def has_times(row: dict[str, str]) -> bool:
    return any(parse_time(x) is not None or MULTI_TIME.search(x or "")
               for x in (row["정거장도착시각"], row["정가장출발시각"]))


def train_key(row: dict[str, str]) -> tuple[str, ...]:
    return (row["노선명"], row["노선번호"], row["요일구분"], row["열차번호"], row["운행구간기점명"], row["운행구간종점명"])


def _backwards(cells: list[tuple[str, str]]) -> bool:
    ts = [service_day_time(parse_time(v)) for _, v in cells if parse_time(v) is not None]
    return any(y < x for x, y in zip(ts, ts[1:]))


def multi_stops(row: dict[str, str]) -> list[Stop]:
    """Stops of a one-row train in travel order.  Order = first appearance of each sequence code in the
    departure, arrival and station cells (대구3 drops some station names but keeps their times).  Sequence codes
    are positions within the train, not station ids, so a missing name stays None — the builder fills it from the
    KTDB stop sequence between the named neighbours."""
    names = dict(MULTI_STOP.findall(row["운행구간정거장"]))
    arr_l = MULTI_TIME.findall(row["정거장도착시각"])
    dep_l = MULTI_TIME.findall(row["정가장출발시각"])
    arr = {k: parse_time(v) for k, v in arr_l}
    dep = {k: parse_time(v) for k, v in dep_l}
    if _backwards(dep_l) and arr_l and not _backwards(arr_l):  # 신림: departure cell in reverse order
        dep = dict(arr)
    order: list[str] = []
    for seq in [k for k, _ in dep_l] + [k for k, _ in arr_l] + list(names):
        if seq not in order:
            order.append(seq)
    if names and list(names) != [q for q in order if q in names]:
        order = list(names) + [q for q in order if q not in names]  # cells disagree: trust the station cell
    out = []
    for i, seq in enumerate(order):
        name = names.get(seq)
        a, d = _pick(i, len(order), arr.get(seq), dep.get(seq))
        out.append((name.strip() if name else None, a, d))
    return out


def service_day_time(t: int | None) -> int:
    """Sort key: before 04:00 belongs to the night before."""
    if t is None:
        return 10 ** 9
    return t + DAY if t < NIGHT_TO else t


def collect_trains(rows) -> tuple[list[dict], dict]:
    """[{line, lineNo, day, trainNo, origin, dest, kind, baseDate, blanked, stops: [(name, arr|None, dep|None)]}]
    and stats.  Row layout: rows are grouped by train key over the whole file (one train's rows are not always
    adjacent), ordered by time and split where a station comes again (a train number can run a round trip)."""
    rows = list(rows)
    stats = dict(rows=len(rows), proseRows={}, invalidTrains={})

    def bump(k: str, key: str) -> None:
        stats[k][key] = stats[k].get(key, 0) + 1

    raw: dict[tuple[str, ...], list] = {}
    metas: dict[tuple[str, ...], dict] = {}
    found: list[tuple[dict, list[Stop]]] = []
    for row in rows:
        line = row["노선명"]
        if not has_times(row):
            bump("proseRows", f"{line}|{row['요일구분']}")
            continue
        meta = dict(line=line, lineNo=row["노선번호"], day=row["요일구분"], trainNo=row["열차번호"],
                    origin=row["운행구간기점명"], dest=row["운행구간종점명"], kind=row["운행유형"],
                    baseDate=excel_day(row["데이터기준일자"]) or row["데이터기준일자"])
        if is_multi(row):
            found.append((meta, multi_stops(row)))
            continue
        key = train_key(row)
        metas.setdefault(key, meta)
        raw.setdefault(key, []).append((row["운행구간정거장"].strip(), parse_time(row["정거장도착시각"]),
                                       parse_time(row["정가장출발시각"])))
    for key, rs in raw.items():
        rs.sort(key=lambda r: service_day_time(r[2] if r[2] is not None else r[1]))
        runs, cur = [], []
        for r in rs:
            if any(x[0] == r[0] for x in cur):
                runs.append(cur)
                cur = []
            cur.append(r)
        runs.append(cur)
        for run in runs:
            found.append((metas[key], [(n,) + _pick(i, len(run), a, d) for i, (n, a, d) in enumerate(run)]))
    trains = []
    for meta, stops in found:
        out, blanked = unroll(stops)
        if out is None:
            bump("invalidTrains", meta["line"])
            continue
        trains.append(dict(meta, blanked=blanked, stops=out))
    return trains, stats


# --------------------------------------------------------------------------- #
# build: KRIC trains → day-types data files (apply_day_types.py, step 2h)
# --------------------------------------------------------------------------- #

import json  # noqa: E402
import sys  # noqa: E402
import zipfile as _zipfile  # noqa: E402
import csv as _csv  # noqa: E402
import io as _io  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
import seoul_timetable_trips as stt  # noqa: E402

ISSUE = "minsuk330/next-route#557"
SERVICES = ("M_WD", "M_SA", "M_HO", "M_SH")


def norm_name(name: str) -> str:
    """Station name key: no spaces, no bracketed line note, no trailing 역, one middle-dot form."""
    n = re.sub(r"\(.*?\)|\[.*?\]", "", name or "")
    n = re.sub(r"\s+", "", n).replace("．", ".").replace("·", ".").replace("ㆍ", ".")
    return n[:-1] if n.endswith("역") and len(n) > 2 else n


def read_stop_names(zip_path: Path, stop_ids: set[str]) -> dict[str, str]:
    out = {}
    with _zipfile.ZipFile(zip_path) as zf, zf.open("stops.txt") as f:
        for r in _csv.DictReader(_io.TextIOWrapper(f, "utf-8-sig")):
            if r["stop_id"] in stop_ids:
                out[r["stop_id"]] = r["stop_name"]
    return out


def station_map(stop_names: dict[str, str], aliases: dict[str, str | None]) -> tuple[dict[str, str | None], list]:
    """normalized KRIC name → stop_id (None = non-revenue stop, cut); and names that map to several stops."""
    by_name: dict[str, set[str]] = {}
    for sid, n in stop_names.items():
        by_name.setdefault(norm_name(n), set()).add(sid)
    out: dict[str, str | None] = {}
    ambiguous = []
    for n, ids in by_name.items():
        if len(ids) == 1:
            out[n] = next(iter(ids))
        else:
            ambiguous.append(dict(name=n, stops=sorted(ids)))
    for k, v in aliases.items():
        if v is not None and v not in stop_names:  # a GTFS stop name instead of a stop_id
            ids = by_name.get(norm_name(v), set())
            if len(ids) != 1:
                raise KricError(f"alias {k} → {v}: {len(ids)} stops of the line's routes have that name")
            v = next(iter(ids))
        out[norm_name(k)] = v
    return out, ambiguous


def map_train(t: dict, smap: dict[str, str | None], sequences: list[list[str]],
              used: dict[str, set[str]] | None = None) -> tuple[list | None, list, bool]:
    """KRIC stop names → stop_ids.  (stops, unmapped names, non_revenue).
    None-mapped (non-revenue) stops are cut; a train left with fewer than two stops is a non-revenue move.
    Unnamed stops (대구3) are filled from a KTDB stop sequence that has exactly that many stops between the named
    neighbours.  used: stop_id → KRIC names seen, for the one-to-one check."""
    out, unmapped = [], []
    for name, a, d in t["stops"]:
        if name is None:
            out.append((None, a, d))
            continue
        k = norm_name(name)
        if k not in smap:
            unmapped.append(name)
            continue
        sid = smap[k]
        if sid is None:
            continue
        if used is not None:
            used.setdefault(sid, set()).add(k)
        out.append((sid, a, d))
    if unmapped:
        return None, unmapped, False
    i = 0
    while i < len(out):
        if out[i][0] is not None:
            i += 1
            continue
        j = i
        while j < len(out) and out[j][0] is None:
            j += 1
        if i == 0 or j == len(out):
            return None, ["(unnamed end stop)"], False
        left, right, n = out[i - 1][0], out[j][0], j - i
        fill = None
        for seq in sequences:
            if left in seq and right in seq:
                a, b = seq.index(left), seq.index(right)
                if b - a - 1 == n:
                    fill = seq[a + 1:b]
                    break
        if fill is None:
            return None, ["(unnamed stops)"], False
        for k2, sid in enumerate(fill):
            out[i + k2] = (sid,) + out[i + k2][1:]
        i = j
    if len(out) < 2:
        return None, [], True
    if out[0][1] is None:
        return None, ["(origin without time)"], False
    return out, [], False


def fill_times(stops: list[tuple[str, int | None, int | None]], route_trips: dict[str, dict]) -> list | None:
    """Blank times ← previous known departure + the template's running time.  Template = a trip of the route
    with the same stop list, else one holding it as a contiguous run.  None when no template fits."""
    if all(a is not None for _, a, _ in stops):
        return stops
    want = [s for s, _, _ in stops]
    tmpl = None
    for t in route_trips.values():
        ids = [x[0] for x in t["stops"]]
        for i in range(len(ids) - len(want) + 1):
            if ids[i:i + len(want)] == want:
                tmpl = t["stops"][i:i + len(want)]
                break
        if tmpl:
            break
    if tmpl is None:
        return None
    out = list(stops)
    for i, (sid, a, d) in enumerate(out):
        if a is not None:
            continue
        j = i - 1
        while out[j][2] is None:
            j -= 1
        base = out[j][2] + (tmpl[i][1] - tmpl[j][2])
        dwell = tmpl[i][2] - tmpl[i][1] if i < len(out) - 1 else 0
        nxt = next((out[k][1] for k in range(i + 1, len(out)) if out[k][1] is not None), None)
        if nxt is not None and base + dwell > nxt:  # never run into the next known time
            return None
        out[i] = (sid, base, base + dwell)
    return out


TIE_STEP = 30  # minute-precision timetables repeat a time at the next stop; nudge within the rounding error


def strictly_increasing(stops: list) -> list:
    """Each arrival after the previous arrival and not before the previous departure (add_missing_trips rule).
    A tie from minute rounding moves the later stop 30 s on; departures never before arrivals."""
    out, pa, pd = [], None, None
    for sid, a, d in stops:
        if pa is not None and (a <= pa or a < pd):
            a = max(pa + TIE_STEP, pd)
        d = max(d, a)
        out.append((sid, a, d))
        pa, pd = a, d
    return out


def build_line(trains: list[dict], cfg: dict, gtfs: dict[str, dict[str, dict]], stop_names: dict[str, str],
               aliases: dict[str, str | None], window: int = 60, min_match: float = 0.95,
               max_unmatched: float = 0.05, max_p90: int = 90,
               typo_aliases: set[str] = frozenset()) -> tuple[dict, dict[str, dict]]:
    """One KRIC line → (report, route_id → data doc).  Gate per direction as in seoul build-day-types, plus the
    line's lost trains (unmapped names, bad times, no template) in the denominators."""
    routes = cfg["routes"]
    smap, ambiguous = station_map(stop_names, aliases)
    patterns = stt.route_patterns(gtfs)
    code_to_sid = {stt.stop_code(s): s for s in stop_names}
    exclude = set(cfg.get("excludeKinds", []))
    lost = {s: dict(unmapped=0, noTemplate=0, badFill=0) for s in SERVICES}
    sequences = sorted({tuple(x[0] for x in t["stops"]) for ts in gtfs.values() for t in ts.values()}, key=len,
                       reverse=True)
    sequences = [list(q) for q in sequences]
    non_revenue = 0
    unmapped_names: dict[str, int] = {}
    unknown_days = sorted({t["day"] for t in trains} - set(cfg["days"]))
    if unknown_days:
        raise KricError(f"{trains[0]['line']}: day labels not in config: {unknown_days}")
    by_service: dict[str, dict[str, dict]] = {s: {} for s in SERVICES}
    used: dict[str, set[str]] = {}
    excluded = filled = 0
    for t in sorted(trains, key=lambda t: (t["day"], t["stops"][0][2], t["trainNo"])):
        svc = cfg["days"][t["day"]]
        if svc is None:
            continue
        if t["kind"] in exclude:
            excluded += 1
            continue
        stops, bad, nonrev = map_train(t, smap, sequences, used)
        if nonrev:
            non_revenue += 1
            continue
        if stops is None:
            lost[svc]["unmapped"] += 1
            for n in bad:
                unmapped_names[n] = unmapped_names.get(n, 0) + 1
            continue
        probe = dict(trainNo=t["trainNo"], express=False, stops=[(stt.stop_code(s), a, d) for s, a, d in stops])
        rid, _ = stt.assign_route(probe, routes, patterns, set())
        if rid is None:
            lost[svc]["noTemplate"] += 1
            continue
        if any(a is None for _, a, _ in stops):
            stops = fill_times(stops, gtfs[rid])
            if stops is None:
                lost[svc]["badFill"] += 1
                continue
            filled += 1
        stops = strictly_increasing(stops)
        key, n = t["trainNo"], 1
        while key in by_service[svc]:
            n += 1
            key = f"{t['trainNo']}-{n}"
        by_service[svc][key] = dict(trainNo=key, origin=stt.stop_code(stops[0][0]), dest=stt.stop_code(stops[-1][0]),
                                    express=False, filled=t["blanked"] > 0 or stops != t["stops"],
                                    stops=[(stt.stop_code(s), a, d) for s, a, d in stops])
    typos = {norm_name(k) for k in typo_aliases}
    clash = {sid: sorted(ks) for sid, ks in used.items() if len(ks - typos) > 1}
    if clash:
        raise KricError(f"two KRIC names map to one stop: {clash}")
    dirs: dict[str, list[str]] = {}
    for r in routes:
        dirs.setdefault(stt.direction_of(r), []).append(r)
    rep = dict(issue=ISSUE, line=trains[0]["line"] if trains else None,
               baseDates=sorted({t["baseDate"] for t in trains if t.get("baseDate")}),
               trains={s: len(v) for s, v in by_service.items()}, lost=lost, excludedKinds=excluded,
               nonRevenueMoves=non_revenue,
               filledTrains=filled, unmappedNames=unmapped_names, ambiguousNames=ambiguous,
               gate=dict(window=window, minWeekdayMatch=min_match, maxUnmatched=max_unmatched, maxStopDeltaP90=max_p90),
               directions={})
    wd_lost = sum(lost["M_WD"].values())
    we_lost = sum(sum(lost[s].values()) for s in ("M_SA", "M_HO", "M_SH"))
    n_wd = len(by_service["M_WD"]) + wd_lost
    n_we = sum(len(by_service[s]) for s in ("M_SA", "M_HO", "M_SH")) + we_lost
    line_ok = (n_wd and wd_lost / n_wd <= 1 - min_match) and (n_we and we_lost / n_we <= max_unmatched)
    docs: dict[str, dict] = {}
    for d, rs in dirs.items():
        def mine(group):
            return {k: v for k, v in group.items() if stt.assign_route(v, rs, patterns, set())[0] is not None}
        wd = stt.weekday_match(gtfs, rs, mine(by_service["M_WD"]), patterns, set(), window)
        entries = stt.weekend_entries(mine(by_service["M_SA"]), mine(by_service["M_HO"])) + \
            [("M_SH", v) for v in mine(by_service["M_SH"]).values()]
        per_route: dict[str, list] = {r: [] for r in rs}
        for svc, v in entries:
            rid, kind = stt.assign_route(v, rs, patterns, set())
            per_route[rid].append((svc, v, kind))
        p90 = wd.get("stopDeltaP90")
        passed = bool(line_ok and wd["gtfsRate"] >= min_match and wd["apiRate"] >= min_match
                      and p90 is not None and p90 <= max_p90)
        dr = dict(routes=rs, weekday={k: v for k, v in wd.items() if k != "routes"}, weekdayRoutes=wd["routes"],
                  entries={s: sum(1 for x, _ in entries if x == s) for s in ("M_SA", "M_HO", "M_SH")},
                  lineLostWeekday=wd_lost, lineLostWeekend=we_lost, passed=passed, files=[], noWeekendTrips=[])
        if passed:
            for rid, got in per_route.items():
                if not got:
                    dr["noWeekendTrips"].append(rid)
                    continue
                trips = [dict(tripId=f"{rid}_{svc[2:]}_{v['trainNo']}", trainNo=v["trainNo"], service=svc,
                              template=kind, **({"filled": True} if v["filled"] else {}),
                              stops=[[code_to_sid[c], stt.fmt_hms(a), stt.fmt_hms(b)] for c, a, b in v["stops"]])
                         for svc, v, kind in sorted(got, key=lambda g: (g[1]["stops"][0][2], g[0], g[1]["trainNo"]))]
                docs[rid] = dict(
                    issue=ISSUE,
                    source=dict(url=SOURCE_URL, file="전체_도시철도운행정보", line=rep["line"],
                                baseDates=rep["baseDates"], days=cfg["days"],
                                rule="KRIC train rows; times past midnight as 24:xx (origin before 03:00 = previous "
                                     "service day); a missing terminal time or one typo'd middle time is filled with "
                                     "the previous departure + the KTDB template's running time (trip 'filled'); "
                                     "M_SH = Saturday and holiday rows with the same stops and times, or a "
                                     "Saturday+holiday label"),
                    routeId=rid,
                    validation=dict(weekday=wd["routes"][rid], directionWeekday=dict(
                        gtfsRate=wd["gtfsRate"], apiRate=wd["apiRate"], stopDeltaP90=p90)),
                    tripCount=len(trips), trips=trips)
                dr["files"].append(rid)
        rep["directions"][d] = dr
    return rep, docs


def main() -> int:
    import argparse
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--kric", type=Path, required=True, help="the KRIC xlsx")
    ap.add_argument("--gtfs", type=Path, required=True, help="feed before step 2h")
    ap.add_argument("--config", type=Path, required=True, help="config/otp-national/kric-day-types.json")
    ap.add_argument("--aliases", type=Path, required=True, help="config/otp-national/kric-station-aliases.json")
    ap.add_argument("--lines", default="", help="KRIC line names (comma); default every line in --config")
    ap.add_argument("--window", type=int, default=60)
    ap.add_argument("--out-dir", type=Path, help="write {routeId}.json here; omit for a report-only run")
    ap.add_argument("--report", type=Path)
    a = ap.parse_args()
    try:
        cfg = json.loads(a.config.read_text(encoding="utf-8"))["lines"]
        aliases = json.loads(a.aliases.read_text(encoding="utf-8")).get("lines", {})
        lines = [x.strip() for x in a.lines.split(",") if x.strip()] or list(cfg)
        trains, stats = collect_trains(read_xlsx(a.kric))
        routes = [r for ln in lines for r in cfg[ln]["routes"]]
        gtfs_all = stt.read_gtfs_routes(a.gtfs, routes)
        names = read_stop_names(a.gtfs, {x[0] for ts in gtfs_all.values() for t in ts.values() for x in t["stops"]})
        report = dict(parse={k: v for k, v in stats.items()}, lines={})
        for ln in lines:
            c = cfg[ln]
            g = {r: gtfs_all[r] for r in c["routes"]}
            sn = {x[0]: names[x[0]] for ts in g.values() for t in ts.values() for x in t["stops"]}
            try:
                al = aliases.get(ln, {})
                rep, docs = build_line([t for t in trains if t["line"] == ln], c, g, sn,
                                       {k: v["stop"] for k, v in al.items()}, a.window,
                                       typo_aliases={k for k, v in al.items() if v.get("typo")})
            except KricError as e:
                report["lines"][ln] = dict(error=str(e))
                continue
            report["lines"][ln] = rep
            if a.out_dir:
                a.out_dir.mkdir(parents=True, exist_ok=True)
                for rid, doc in docs.items():
                    stt.write_day_data(a.out_dir / f"{rid}.json", doc)
        if a.report:
            a.report.write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        for ln, r in report["lines"].items():
            if "error" in r:
                print(f"{ln}: ERROR {r['error']}")
                continue
            ds = " ".join(f"{d}:{'PASS' if x['passed'] else 'fail'}(wd {x['weekday']['gtfsRate']}/{x['weekday']['apiRate']} "
                          f"p90 {x['weekday']['stopDeltaP90']})" for d, x in r["directions"].items())
            print(f"{ln}: trains {r['trains']} lost {r['lost']} unmapped {len(r['unmappedNames'])} {ds}")
        return 0
    except KricError as e:
        print(f"kric timetable: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
