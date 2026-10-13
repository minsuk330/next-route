#!/usr/bin/env python3
"""Build the stop-pair section time table for calibrate_bus_runtimes.py from Seoul TOPIS open data.

Source: 서울 열린데이터광장 OA-21217 "노선별 정류장 구간별 평균 운행시간", Open API service
``tpssRouteSectionTime`` (last 30 days, newest day first).  One row = (day, route, from stop, to stop) with the
average running seconds for each hour (``OPR_HR_00`` .. ``OPR_HR_23``, 0 = no bus).  Seoul routes use Seoul stop
ids (``BS_TAGO_SEB`` + id in our feed); Gyeonggi routes inside Seoul use GBIS stop ids (``BS_TAGO_GGB`` + id).

Output CSV ``from_stop,to_stop,hour,seconds,samples``: per stop pair and hour the median over routes and
days, plus an ``all`` row (median over every hourly value) used when a bus leaves in an hour with no data.

Only full weekdays are kept (Saturday, Sunday and ``--exclude-dates`` dropped); the API is paged newest day
first and paging stops once ``--weekdays`` full days are in.  The key is read from ``--env-file`` and never
printed; responses that echo it are dropped.

    python3 runner/fetch_seoul_section_times.py --env-file ../../env/api.env --key-name SEOUL_BUS_USE \\
        --weekdays 3 --cache /tmp/topis-pages --out data/otp-national/seoul-section-times.csv
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import re
import statistics
import sys
import time
import urllib.request
from pathlib import Path

SERVICE = "tpssRouteSectionTime"
PAGE = 1000


def read_key(env_file: Path, name: str) -> str:
    m = re.search(rf"^{re.escape(name)}=(.*)$", env_file.read_text(encoding="utf-8"), re.M)
    if not m or not m.group(1).strip():
        raise SystemExit(f"{name} not found in {env_file}")
    return m.group(1).strip().strip('"').strip("'")


def fetch_page(key: str, page: int, cache: Path | None) -> list[dict]:
    f = cache / f"{page:05d}.json" if cache else None
    if f and f.exists():
        return json.loads(f.read_text())
    s, e = (page - 1) * PAGE + 1, page * PAGE
    url = f"http://openapi.seoul.go.kr:8088/{key}/json/{SERVICE}/{s}/{e}/"
    for attempt in range(3):
        try:
            body = urllib.request.urlopen(url, timeout=60).read().decode()
            break
        except OSError:
            time.sleep(3 * (attempt + 1))
    else:
        raise SystemExit(f"page {page}: request failed 3 times")
    if key in body:
        raise SystemExit(f"page {page}: response echoes the key, dropped")
    doc = json.loads(body)
    if SERVICE not in doc:
        raise SystemExit(f"page {page}: {str(doc)[:200]}")
    rows = doc[SERVICE].get("row", [])
    if f:
        f.write_text(json.dumps(rows))
    return rows


def weekday(d: str, excluded: set[str]) -> bool:
    return dt.date(int(d[:4]), int(d[4:6]), int(d[6:])).weekday() < 5 and d not in excluded


def collect(key: str, n_days: int, excluded: set[str], cache: Path | None) -> tuple[list[dict], list[str]]:
    """Rows of the newest ``n_days`` full weekdays.  A day is full once an older day shows up after it."""
    kept: list[dict] = []
    days: list[str] = []
    page = 1
    while True:
        rows = fetch_page(key, page, cache)
        if not rows:
            break
        for r in rows:
            d = r["CRTR_DD"]
            if not days or days[-1] != d:
                if len([x for x in days if weekday(x, excluded)]) >= n_days:
                    return kept, [x for x in days if weekday(x, excluded)]
                days.append(d)
            if weekday(d, excluded):
                kept.append(r)
        page += 1
    raise SystemExit(f"only {len([x for x in days if weekday(x, excluded)])} full weekdays available")


def aggregate(rows: list[dict]) -> list[tuple[str, str, str, float, int]]:
    hourly: dict[tuple[str, str, int], list[float]] = {}
    for r in rows:
        k = (r["DPTRE_STOPS_ID"], r["ARVL_STOPS_ID"])
        for h in range(24):
            v = float(r[f"OPR_HR_{h:02d}"])
            if v > 0:
                hourly.setdefault((k[0], k[1], h), []).append(v)
    out = []
    every: dict[tuple[str, str], list[float]] = {}
    for (a, b, h), v in sorted(hourly.items()):
        out.append((a, b, str(h), round(statistics.median(v), 1), len(v)))
        every.setdefault((a, b), []).extend(v)
    out += [(a, b, "all", round(statistics.median(v), 1), len(v)) for (a, b), v in sorted(every.items())]
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--env-file", type=Path, required=True)
    ap.add_argument("--key-name", default="SEOUL_BUS_USE")
    ap.add_argument("--weekdays", type=int, default=3)
    ap.add_argument("--exclude-dates", default="", help="comma separated YYYYMMDD (public holidays)")
    ap.add_argument("--cache", type=Path, help="page cache dir (reruns do not call the API again)")
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    if a.cache:
        a.cache.mkdir(parents=True, exist_ok=True)
    rows, days = collect(read_key(a.env_file, a.key_name), a.weekdays,
                         {x for x in a.exclude_dates.split(",") if x}, a.cache)
    table = aggregate(rows)
    with a.out.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["from_stop", "to_stop", "hour", "seconds", "samples"])
        w.writerows(table)
    pairs = sum(1 for t in table if t[2] == "all")
    print(json.dumps(dict(days=days, rows=len(rows), pairs=pairs, out=str(a.out))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
