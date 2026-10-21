#!/usr/bin/env python3
"""Move calendar.txt end_date later, and fail when OTP would cut the graph before it (PR-G1).

The national feed's calendar.txt is one every-day service ``B1``.  Its dates are not KTDB data: the KTDB
timetable is one representative week, and prepare_gtfs.py ``--calendar-center/--calendar-margin-days`` clamps
its 2017-2030 span to a window around the build (``select_calendar`` intersects with the source span, so it
cannot move a window later).  The 09-29 national feed was cut to 20260729..20261130 (= 20260929 ± 62 days),
so every graph built from it has no transit after 2026-11-30.

This step rewrites ``end_date`` of the selected services (default: all) to ``--end``.  It only extends:
an earlier ``--end`` fails, an equal one is ``alreadyApplied`` (re-running writes the same bytes).
start_date, trips and times are unchanged.  calendar_dates.txt rows are kept (they are exceptions on dates
that stay valid); a selected service that exists only in calendar_dates.txt fails (no weekly rule to extend).

OTP keeps a second, relative window: ``transitServiceStart``/``transitServiceEnd`` of build-config.json
(national: -P1D/P2M from the build day).  Every service day in it becomes a TripPatternForDate per pattern
at startup — about 3.5 MB of heap per day on the national graph (measured 2026-10-21 on a 63-day graph:
1,906,128 TripPatternForDate = 63 days x 30,256 patterns; 76 MB + TripTimes[] 116 MB + FrequencyEntry[] 31 MB)
— so that window is the heap guard and ``--end`` must not reach past it, or the
graph silently ends earlier than its graphVersion says.  ``--otp-build-config`` checks that and reports the
service days the graph will hold.

Every other file is copied unchanged.  Stdlib only.
"""

from __future__ import annotations

import argparse
import calendar as cal
import json
import re
import sys
import zipfile
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from apply_subway_corrections import Table, copy_member, sha256_file, write_member  # noqa: E402

OTP_DEFAULT_START, OTP_DEFAULT_END = "-P1Y", "P3Y"  # OTP BuildConfig defaults
PERIOD = re.compile(r"^(-)?P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?$")


class CalendarError(ValueError):
    pass


def parse_ymd(s: str) -> date:
    s = s.strip()
    if not re.fullmatch(r"\d{8}", s):
        raise CalendarError(f"not YYYYMMDD: {s!r}")
    return date(int(s[:4]), int(s[4:6]), int(s[6:]))


def fmt_ymd(d: date) -> str:
    return d.strftime("%Y%m%d")


def add_months(d: date, months: int) -> date:
    y, m = divmod(d.month - 1 + months, 12)
    y, m = d.year + y, m + 1
    return date(y, m, min(d.day, cal.monthrange(y, m)[1]))


def otp_date(value: str, build_day: date) -> date:
    """OTP's LocalDate-or-Period config value (java.time.Period.addTo: years, months, then days)."""
    value = value.strip()
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
        return date.fromisoformat(value)
    m = PERIOD.match(value)
    if not m or value in ("P", "-P"):
        raise CalendarError(f"unsupported OTP period {value!r}")
    sign = -1 if m.group(1) else 1
    y, mo, w, d = (int(g or 0) for g in m.groups()[1:])
    out = add_months(build_day, sign * (12 * y + mo))
    return out + timedelta(days=sign * (7 * w + d))


def otp_window(build_config: Path, build_day: date) -> tuple[date, date, str, str]:
    doc = json.loads(build_config.read_text(encoding="utf-8"))
    s, e = doc.get("transitServiceStart", OTP_DEFAULT_START), doc.get("transitServiceEnd", OTP_DEFAULT_END)
    return otp_date(s, build_day), otp_date(e, build_day), s, e


def run(in_zip: Path, out_zip: Path, end: date, services: set[str] | None = None,
        build_config: Path | None = None, build_day: date | None = None, report_path: Path | None = None) -> dict:
    if in_zip.resolve() == out_zip.resolve():
        raise CalendarError("--out must not be --in")
    with zipfile.ZipFile(in_zip) as src:
        names = src.namelist()
        if "calendar.txt" not in names:
            raise CalendarError("no calendar.txt")
        t = Table.read(src, "calendar.txt")
        cd_services: set[str] = set()
        cd_rows = 0
        if "calendar_dates.txt" in names:
            cd = Table.read(src, "calendar_dates.txt")
            cd_rows = sum(1 for r in cd.rows if r is not None)
            cd_services = {r[cd.col["service_id"]] for r in cd.rows if r is not None}
        present = {r[t.col["service_id"]] for r in t.rows if r is not None}
        wanted = services if services is not None else present
        only_dates = sorted(wanted - present)
        if only_dates:
            raise CalendarError(f"services not in calendar.txt (calendar_dates only or unknown): {only_dates}")
        result = []
        for k, r in enumerate(t.rows):
            if r is None or r[t.col["service_id"]] not in wanted:
                continue
            sid, start, old_end = r[t.col["service_id"]], parse_ymd(r[t.col["start_date"]]), parse_ymd(r[t.col["end_date"]])
            if end < old_end:
                raise CalendarError(f"{sid}: --end {fmt_ymd(end)} is before the current end {fmt_ymd(old_end)}")
            if end < start:
                raise CalendarError(f"{sid}: --end {fmt_ymd(end)} is before start_date {fmt_ymd(start)}")
            t.set(k, "end_date", fmt_ymd(end))
            result.append(dict(serviceId=sid, before=f"{fmt_ymd(start)}..{fmt_ymd(old_end)}",
                               after=f"{fmt_ymd(start)}..{fmt_ymd(end)}",
                               status="alreadyApplied" if old_end == end else "applied"))
        if not result:
            raise CalendarError("calendar.txt has no service to extend")
        graph = None
        if build_config is not None:
            day = build_day or date.today()
            o_start, o_end, s_cfg, e_cfg = otp_window(build_config, day)
            if end > o_end:
                raise CalendarError(
                    f"OTP keeps service days only through {o_end} (transitServiceEnd {e_cfg} from build day {day}); "
                    f"a calendar to {fmt_ymd(end)} would be cut there. Pick --end <= {fmt_ymd(o_end)} or change "
                    f"build-config.json knowing each day costs heap")
            first = max(o_start, min(parse_ymd(x["after"][:8]) for x in result))
            graph = dict(buildDay=day.isoformat(), transitServiceStart=s_cfg, transitServiceEnd=e_cfg,
                         otpWindow=f"{o_start}..{o_end}", serviceDays=f"{first}..{end}",
                         days=(end - first).days + 1, expiresAfter=end.isoformat())
        tmp = out_zip.with_suffix(out_zip.suffix + ".tmp")
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
            for info in src.infolist():
                if info.filename == "calendar.txt":
                    write_member(dst, info, t.to_bytes())
                else:
                    copy_member(src, dst, info)
        tmp.replace(out_zip)
    report = dict(input=str(in_zip), inputSha256=sha256_file(in_zip), output=str(out_zip),
                  outputSha256=sha256_file(out_zip), script=Path(__file__).name,
                  scriptSha256=sha256_file(Path(__file__)), end=fmt_ymd(end), services=result,
                  calendarDates=dict(rows=cd_rows, services=sorted(cd_services)), graph=graph)
    if report_path:
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return report


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--in", dest="in_zip", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--end", required=True, help="new end_date YYYYMMDD (inclusive)")
    ap.add_argument("--service-ids", help="comma-separated; default every calendar.txt service")
    ap.add_argument("--otp-build-config", type=Path, help="fail if OTP's transitServiceEnd would cut --end")
    ap.add_argument("--build-day", help="YYYYMMDD the graph is built (default today)")
    ap.add_argument("--report", type=Path)
    a = ap.parse_args()
    try:
        rep = run(a.in_zip, a.out, parse_ymd(a.end), set(a.service_ids.split(",")) if a.service_ids else None,
                  a.otp_build_config, parse_ymd(a.build_day) if a.build_day else None, a.report)
    except CalendarError as e:
        print(f"calendar extension failed: {e}", file=sys.stderr)
        return 1
    print(json.dumps(dict(services=rep["services"], graph=rep["graph"]), ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
