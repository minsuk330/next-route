#!/usr/bin/env python3
"""Weekday / Saturday / holiday metro service patterns (#557, step 2h of build_otp_graph.sh).

KTDB GTFS 202503 runs every trip on one service ``B1`` (all seven days), built from one weekday in March 2025, so
weekends and public holidays get the weekday timetable.  For the routes that have a reviewed data file in
``config/otp-national/day-types/`` (written by ``seoul_timetable_trips.py build-day-types``) this step:

* moves every existing trip of the route from ``B1`` to ``M_WD`` (Mon-Fri);
* adds the data file's Saturday / holiday trips on ``M_SA`` (Sat), ``M_HO`` (Sun) or ``M_SH`` (Sat+Sun, a train
  the Saturday and holiday tables give with the same stops and times).  Each new trip copies an ``M_WD`` trip of
  the same route as its template (add_missing_trips.plan_trip: same stop list, else a contiguous slice);
* writes those services to calendar.txt with B1's start/end, and public holidays (``kr-holidays.json``) to
  calendar_dates.txt: a weekday holiday removes ``M_WD`` and adds ``M_HO`` + ``M_SH``; a Saturday holiday removes
  ``M_SA`` and adds ``M_HO``; a Sunday holiday changes nothing.

Routes without a data file stay on ``B1`` byte for byte.  Run it after 2f (calendar end), so the holiday guard sees
the final service range.  Guards (the build fails):
  data: route not metro, trip id not ``{routeId}_``-prefixed or repeated, service not M_SA/M_HO/M_SH, times not
        increasing, a stop not on the route, no M_WD template for a stop list;
  feed: a target route trip not on B1, B1 missing from calendar.txt, a service id already in calendar.txt;
  days: two trips running on the same day with the same stop list leaving within 60 s, or one overtaking the next
        in one stop pattern (only trips that share a day are compared: Sat = M_SA+M_SH, Sun = M_HO+M_SH);
  holidays: the service range (max(start_date, build day - 1) .. end_date) is not inside the holiday file's
        ``coverage`` — a holiday list that stops early would silently run weekday trains on a holiday.
Holidays outside the service range are skipped and reported.

Idempotent: when calendar.txt already has M_WD and every data trip is in trips.txt with the same stops and times,
the input is copied unchanged (``alreadyApplied``); any other already-applied state fails.  Existing rows are
written back as their original bytes except the service_id of target trips.  Stdlib only.
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from add_missing_trips import AddTripsError, check_overtaking, check_times, plan_trip, read_route  # noqa: E402
from apply_subway_corrections import (  # noqa: E402
    Table, copy_member, format_line, line_end, out_info, sha256_file, write_member, ZIP64_FORCE_BYTES)
from calibrate_bus_runtimes import parse_time  # noqa: E402

METRO_ROUTE_TYPE = "1"
BASE_SERVICE = "B1"
WD, SA, HO, SH = "M_WD", "M_SA", "M_HO", "M_SH"
NEW_SERVICES = (SA, HO, SH)
DAYS = {WD: "1111100", SA: "0000010", HO: "0000001", SH: "0000011"}
# service day (Mon..Sun index) → services running on it, for the duplicate / overtaking checks
RUNS_ON = {"sat": (SA, SH), "sun": (HO, SH)}
DUPLICATE_SEC = 60
DAY_COLS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
NEW_MEMBER_DATE = (1980, 1, 1, 0, 0, 0)


class DayTypesError(ValueError):
    pass


def parse_ymd(s: str) -> date:
    s = s.replace("-", "")
    return date(int(s[:4]), int(s[4:6]), int(s[6:8]))


def fmt_ymd(d: date) -> str:
    return d.strftime("%Y%m%d")


def load_data(path: Path) -> dict:
    doc = json.loads(path.read_text(encoding="utf-8"))
    for k in ("routeId", "source", "trips"):
        if k not in doc:
            raise DayTypesError(f"{path.name}: missing {k}")
    if doc.get("tripCount") != len(doc["trips"]):
        raise DayTypesError(f"{path.name}: tripCount {doc.get('tripCount')} != {len(doc['trips'])} trips")
    ids = [t["tripId"] for t in doc["trips"]]
    if len(set(ids)) != len(ids):
        raise DayTypesError(f"{path.name}: a tripId is repeated")
    if not doc["trips"]:
        raise DayTypesError(f"{path.name}: no trips (a route without weekend trips must stay on {BASE_SERVICE})")
    for t in doc["trips"]:
        if not t["tripId"].startswith(doc["routeId"] + "_"):
            raise DayTypesError(f"{path.name}: {t['tripId']} does not start with {doc['routeId']}_")
        if t.get("service") not in NEW_SERVICES:
            raise DayTypesError(f"{path.name}: {t['tripId']} service {t.get('service')} not in {NEW_SERVICES}")
        if len(t["stops"]) < 2:
            raise DayTypesError(f"{path.name}: {t['tripId']} has fewer than 2 stops")
        try:
            check_times(t["tripId"], [(parse_time(a), parse_time(d)) for _, a, d in t["stops"]])
        except AddTripsError as e:
            raise DayTypesError(f"{path.name}: {e}") from None
    return doc


def load_holidays(path: Path) -> dict:
    doc = json.loads(path.read_text(encoding="utf-8"))
    cov = doc.get("coverage") or {}
    if "from" not in cov or "to" not in cov:
        raise DayTypesError(f"{path.name}: coverage.from/to required")
    lo, hi = parse_ymd(cov["from"]), parse_ymd(cov["to"])
    days = []
    for h in doc.get("days", []):
        d = parse_ymd(h["date"])
        if not lo <= d <= hi:
            raise DayTypesError(f"{path.name}: {h['date']} outside coverage {cov['from']}..{cov['to']}")
        if not h.get("source"):
            raise DayTypesError(f"{path.name}: {h['date']} has no source")
        days.append(dict(date=d, name=h.get("name", "")))
    if len({h["date"] for h in days}) != len(days):
        raise DayTypesError(f"{path.name}: a date is repeated")
    return dict(lo=lo, hi=hi, days=sorted(days, key=lambda h: h["date"]))


def holiday_rows(days: list[dict], start: date, end: date, used: set[str]) -> tuple[list[tuple[str, str, str]], list]:
    """calendar_dates rows (service_id, date, exception_type) and the holidays skipped as outside the range."""
    rows, skipped = [], []
    for h in days:
        d = h["date"]
        if not start <= d <= end:
            skipped.append(d.isoformat())
            continue
        wd = d.weekday()
        if wd < 5:
            change = [(WD, "2"), (HO, "1"), (SH, "1")]
        elif wd == 5:
            change = [(SA, "2"), (HO, "1")]
        else:
            change = []
        rows += [(s, fmt_ymd(d), e) for s, e in change if s in used]
    return rows, skipped


def run(in_zip: Path, data_paths: list[Path], holidays_path: Path, out_zip: Path,
        report_path: Path | None = None, build_day: date | None = None) -> dict:
    if in_zip.resolve() == out_zip.resolve():
        raise DayTypesError("--out must not be --in")
    if not data_paths:
        raise DayTypesError("no day-types data file")
    docs = [(p, load_data(p)) for p in data_paths]
    rids = [d["routeId"] for _, d in docs]
    if len(set(rids)) != len(rids):
        raise DayTypesError("a route is in two data files")
    id_list = [t["tripId"] for _, d in docs for t in d["trips"]]
    all_ids = set(id_list)
    if len(all_ids) != len(id_list):
        raise DayTypesError("a tripId is in two data files")
    hol = load_holidays(holidays_path)
    build_day = build_day or date.today()
    route_ids = set(rids)
    with zipfile.ZipFile(in_zip) as src:
        names = src.namelist()
        trips = Table.read(src, "trips.txt")
        routes = Table.read(src, "routes.txt")
        stops = Table.read(src, "stops.txt")
        calendar = Table.read(src, "calendar.txt")
        cal_dates = Table.read(src, "calendar_dates.txt") if "calendar_dates.txt" in names else None
        route_type = {r[routes.col["route_id"]]: r[routes.col["route_type"]] for r in routes.rows if r is not None}
        stop_ids = {r[stops.col["stop_id"]] for r in stops.rows if r is not None}
        trip_k = {r[trips.col["trip_id"]]: k for k, r in enumerate(trips.rows) if r is not None}
        cal = {r[calendar.col["service_id"]]: r for r in calendar.rows if r is not None}
        if BASE_SERVICE not in cal:
            raise DayTypesError(f"calendar.txt has no {BASE_SERVICE}")
        b1 = cal[BASE_SERVICE]
        start, end = parse_ymd(b1[calendar.col["start_date"]]), parse_ymd(b1[calendar.col["end_date"]])
        eff_start = max(start, build_day - timedelta(days=1))
        if eff_start < hol["lo"] or end > hol["hi"]:
            raise DayTypesError(
                f"service range {eff_start}..{end} is not inside the holiday coverage {hol['lo']}..{hol['hi']}: "
                f"extend {holidays_path.name} first")
        for p, d in docs:
            if route_type.get(d["routeId"]) != METRO_ROUTE_TYPE:
                raise DayTypesError(f"{p.name}: route {d['routeId']} missing or not metro")
        existing_rows, st_header, eol = read_route(src, route_ids, trips)
        col = {h: i for i, h in enumerate(st_header)}

        if WD in cal:
            return already_applied(in_zip, out_zip, docs, existing_rows, trip_k, col, report_path)
        clash = [s for s in (WD,) + NEW_SERVICES if s in cal]
        if clash:
            raise DayTypesError(f"calendar.txt already has {clash}")

        # 1. target trips B1 → M_WD
        moved: dict[str, int] = {}
        for k, r in enumerate(trips.rows):
            if r is None or r[trips.col["route_id"]] not in route_ids:
                continue
            if r[trips.col["service_id"]] != BASE_SERVICE:
                raise DayTypesError(f"{r[trips.col['trip_id']]}: on {r[trips.col['service_id']]}, not {BASE_SERVICE}")
            if r[trips.col["trip_id"]] in all_ids:
                raise DayTypesError(f"{r[trips.col['trip_id']]}: a data trip id is already in trips.txt")
            trips.set(k, "service_id", WD)
            rid = r[trips.col["route_id"]]
            moved[rid] = moved.get(rid, 0) + 1

        # 2. new trips from M_WD templates
        new_trip_lines, new_st_lines, results, used = [], [], [], {WD}
        trip_eol = line_end(trips.lines[-1]) or b"\n"
        for path, doc in docs:
            rid = doc["routeId"]
            base = {t: r for t, r in existing_rows.items() if trips.get(trip_k[t], "route_id") == rid}
            if not base:
                raise DayTypesError(f"{path.name}: route {rid} has no trips to use as templates")
            route_stops = {r[col["stop_id"]] for v in base.values() for r in v}
            new_rows: dict[str, list[list[str]]] = {}
            service_of: dict[str, str] = {}
            kinds: dict[str, int] = {}
            per_service: dict[str, int] = {}
            for t in doc["trips"]:
                tid, svc = t["tripId"], t["service"]
                want = [s for s, _, _ in t["stops"]]
                bad = [s for s in want if s not in stop_ids or s not in route_stops]
                if bad:
                    raise DayTypesError(f"{tid}: stops not on {rid}: {bad}")
                try:
                    tmpl, rows, kind = plan_trip(t, base, col)
                except AddTripsError as e:
                    raise DayTypesError(f"{path.name}: {e}") from None
                trow = list(trips.rows[trip_k[tmpl]])
                trow[trips.col["trip_id"]] = tid
                trow[trips.col["service_id"]] = svc
                new_trip_lines.append(format_line(trow, trip_eol))
                new_st_lines += [format_line(r, eol) for r in rows]
                new_rows[tid], service_of[tid] = rows, svc
                kinds[kind] = kinds.get(kind, 0) + 1
                per_service[svc] = per_service.get(svc, 0) + 1
                used.add(svc)
            checked = check_days(new_rows, service_of, col)
            results.append(dict(data=str(path), dataSha256=sha256_file(path), routeId=rid,
                                weekdayTrips=moved.get(rid, 0), added=per_service, templateKinds=kinds,
                                sameDayPairsChecked=checked))

        # 3. calendar + calendar_dates
        cal_eol = line_end(calendar.lines[-1]) if calendar.lines else b"\n"
        if calendar.lines and not cal_eol:
            raise DayTypesError("calendar.txt does not end with a line break")
        new_cal_lines = []
        for s in (WD,) + NEW_SERVICES:
            if s not in used:
                continue
            row = [""] * len(calendar.col)
            row[calendar.col["service_id"]] = s
            for c, v in zip(DAY_COLS, DAYS[s]):
                row[calendar.col[c]] = v
            row[calendar.col["start_date"]] = b1[calendar.col["start_date"]]
            row[calendar.col["end_date"]] = b1[calendar.col["end_date"]]
            new_cal_lines.append(format_line(row, cal_eol or b"\n"))
        hrows, skipped = holiday_rows(hol["days"], eff_start, end, used)
        if cal_dates is not None:
            if cal_dates.lines and not line_end(cal_dates.lines[-1]):
                raise DayTypesError("calendar_dates.txt does not end with a line break")
            cd_eol = line_end(cal_dates.lines[-1]) if cal_dates.lines else b"\n"
            cd_col = cal_dates.col
            cd_bytes = cal_dates.to_bytes()
        else:
            cd_eol, cd_col = b"\n", {"service_id": 0, "date": 1, "exception_type": 2}
            cd_bytes = b"service_id,date,exception_type\n"
        for s, d, e in hrows:
            row = [""] * len(cd_col)
            row[cd_col["service_id"]], row[cd_col["date"]], row[cd_col["exception_type"]] = s, d, e
            cd_bytes += format_line(row, cd_eol)

        if new_trip_lines and trips.lines and not line_end(trips.lines[-1]):
            raise DayTypesError("trips.txt does not end with a line break")
        tmp = out_zip.with_suffix(out_zip.suffix + ".tmp")
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
            for info in src.infolist():
                if info.filename == "trips.txt":
                    write_member(dst, info, trips.to_bytes() + b"".join(new_trip_lines))
                elif info.filename == "calendar.txt":
                    write_member(dst, info, calendar.to_bytes() + b"".join(new_cal_lines))
                elif info.filename == "calendar_dates.txt":
                    write_member(dst, info, cd_bytes)
                elif info.filename == "stop_times.txt":
                    with src.open(info) as r, dst.open(out_info(info), "w",
                                                       force_zip64=info.file_size > ZIP64_FORCE_BYTES) as w:
                        while chunk := r.read(1 << 20):
                            w.write(chunk)
                        w.write(b"".join(new_st_lines))
                else:
                    copy_member(src, dst, info)
            if cal_dates is None:
                zi = zipfile.ZipInfo("calendar_dates.txt", date_time=NEW_MEMBER_DATE)
                zi.compress_type = zipfile.ZIP_DEFLATED
                dst.writestr(zi, cd_bytes)
        tmp.replace(out_zip)
    report = dict(input=str(in_zip), inputSha256=sha256_file(in_zip), script=Path(__file__).name,
                  scriptSha256=sha256_file(Path(__file__)), output=str(out_zip), outputSha256=sha256_file(out_zip),
                  status="applied", holidays=str(holidays_path), holidaysSha256=sha256_file(holidays_path),
                  serviceRange=f"{eff_start}..{end}", services=sorted(used),
                  calendarDatesRowsAdded=len(hrows), holidaysOutsideRange=skipped,
                  tripsMovedToWeekday=sum(moved.values()), tripsAdded=len(new_trip_lines),
                  stopTimesRowsAdded=len(new_st_lines), routes=results)
    if report_path:
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return report


def check_days(new_rows: dict[str, list[list[str]]], service_of: dict[str, str], col: dict[str, int]) -> int:
    """Duplicate / overtaking checks among trips sharing a service day (weekday trips are KTDB's, unchanged)."""
    checked = 0
    for day, services in RUNS_ON.items():
        rows = {t: r for t, r in new_rows.items() if service_of[t] in services}
        seen: dict[tuple[str, ...], list[tuple[int, str]]] = {}
        for t, r in rows.items():
            key, d0 = tuple(x[col["stop_id"]] for x in r), parse_time(r[0][col["departure_time"]])
            for d1, other in seen.get(key, []):
                if abs(d1 - d0) <= DUPLICATE_SEC:
                    raise DayTypesError(f"{t}: duplicates {other} on {day}")
            seen.setdefault(key, []).append((d0, t))
        try:
            checked += check_overtaking(rows, col, set(rows))
        except AddTripsError as e:
            raise DayTypesError(f"{day}: {e}") from None
    return checked


def already_applied(in_zip: Path, out_zip: Path, docs, existing_rows, trip_k, col, report_path) -> dict:
    for path, doc in docs:
        for t in doc["trips"]:
            have = existing_rows.get(t["tripId"])
            if t["tripId"] not in trip_k or have is None or \
                    [[r[col["stop_id"]], r[col["arrival_time"]], r[col["departure_time"]]] for r in have] \
                    != [list(s) for s in t["stops"]]:
                raise DayTypesError(f"calendar.txt has {WD} but {t['tripId']} is missing or differs: "
                                    f"run on a feed before step 2h")
    out_zip.write_bytes(in_zip.read_bytes())
    report = dict(input=str(in_zip), output=str(out_zip), status="alreadyApplied",
                  outputSha256=sha256_file(out_zip))
    if report_path:
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return report


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--in", dest="in_zip", type=Path, required=True)
    ap.add_argument("--day-types", type=Path, required=True, help="directory of day-types/*.json")
    ap.add_argument("--holidays", type=Path, required=True, help="config/otp-national/kr-holidays.json")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--report", type=Path)
    ap.add_argument("--build-day", help="YYYY-MM-DD (default today); the OTP service window starts the day before")
    a = ap.parse_args()
    try:
        paths = sorted(a.day_types.glob("*.json"))
        rep = run(a.in_zip, paths, a.holidays, a.out, a.report,
                  parse_ymd(a.build_day) if a.build_day else None)
    except DayTypesError as e:
        print(f"apply day types failed: {e}", file=sys.stderr)
        return 1
    print(json.dumps({k: v for k, v in rep.items() if k != "routes"}, ensure_ascii=False, indent=1))
    for r in rep.get("routes", []):
        print(json.dumps({k: v for k, v in r.items() if k not in ("data", "dataSha256")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
