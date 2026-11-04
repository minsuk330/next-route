"""Fetch Korean public holidays into a calendar sidecar parquet.

Authority is the same government endpoint the Java side uses
(``GongApiAdapter`` → getRestDeInfo, 한국천문연구원 특일정보). Keeping a single
authoritative source on both sides avoids train/serve skew on the calendar
features. build_dataset.py only ever reads the sidecar — it never calls the API.

Output: ``$DATA_DIR/calendar/holidays.parquet`` with one ``holiday_date`` (Date)
column. The file is merged idempotently, so re-running for overlapping years just
unions the dates. Network failures are surfaced per-year; callers (retrain.sh)
treat the step as best-effort so an API outage degrades to null holiday features
rather than failing the whole pipeline.
"""

from __future__ import annotations

import argparse
import os
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import date, datetime
from pathlib import Path

import polars as pl
from dotenv import load_dotenv


BASE_URL = (
    "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo"
)
HOLIDAY_DATE_FMT = "%Y%m%d"
REQUEST_TIMEOUT_SECONDS = 15


class HolidayFetchError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fetch KR public holidays into a calendar sidecar parquet."
    )
    parser.add_argument(
        "--from-year", type=int, required=True, help="Inclusive start year (e.g. 2025)."
    )
    parser.add_argument(
        "--to-year", type=int, required=True, help="Inclusive end year (e.g. 2026)."
    )
    return parser.parse_args()


def load_data_dir() -> Path:
    ml_dir = Path(__file__).resolve().parent
    load_dotenv(ml_dir / ".env")

    data_dir_value = os.getenv("DATA_DIR", "./data")
    data_dir = Path(data_dir_value).expanduser()
    if not data_dir.is_absolute():
        data_dir = ml_dir / data_dir
    return data_dir


def sidecar_path(data_dir: Path) -> Path:
    return data_dir / "calendar" / "holidays.parquet"


def _service_key() -> str:
    key = os.getenv("GONG_API")
    if not key:
        raise HolidayFetchError("GONG_API not set — cannot fetch holidays.")
    return key


def _request_xml(url: str) -> str:
    request = urllib.request.Request(url, headers={"Accept": "application/xml"})
    with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
        return response.read().decode("utf-8")


def fetch_month(service_key: str, year: int, month: int) -> set[date]:
    """Return the holiday dates for one month. The data.go.kr key is already
    URL-encoded, so it is appended verbatim while the rest is encoded normally."""
    params = urllib.parse.urlencode(
        {
            "solYear": year,
            "solMonth": f"{month:02d}",
            "numOfRows": 50,
            "pageNo": 1,
        }
    )
    url = f"{BASE_URL}?serviceKey={service_key}&{params}"
    root = ET.fromstring(_request_xml(url))

    result_code = root.findtext(".//header/resultCode")
    if result_code is not None and result_code != "00":
        result_msg = root.findtext(".//header/resultMsg")
        raise HolidayFetchError(
            f"{year}-{month:02d} resultCode={result_code} msg={result_msg}"
        )

    dates: set[date] = set()
    for item in root.findall(".//item"):
        is_holiday = (item.findtext("isHoliday") or "").strip()
        locdate = (item.findtext("locdate") or "").strip()
        if is_holiday == "Y" and locdate:
            dates.add(datetime.strptime(locdate, HOLIDAY_DATE_FMT).date())
    return dates


def fetch_years(service_key: str, from_year: int, to_year: int) -> set[date]:
    holidays: set[date] = set()
    for year in range(from_year, to_year + 1):
        for month in range(1, 13):
            holidays |= fetch_month(service_key, year, month)
        print(f"[holiday] {year}: cumulative {len(holidays)} dates")
    return holidays


def load_existing(path: Path) -> set[date]:
    if not path.exists():
        return set()
    frame = pl.read_parquet(str(path))
    if "holiday_date" not in frame.columns:
        return set()
    return set(frame.get_column("holiday_date").to_list())


def write_sidecar(path: Path, holidays: set[date]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    frame = pl.DataFrame(
        {"holiday_date": sorted(holidays)},
        schema={"holiday_date": pl.Date},
    )
    tmp_path = path.with_suffix(".parquet.tmp")
    frame.write_parquet(tmp_path)
    tmp_path.replace(path)


def main() -> int:
    args = parse_args()
    if args.to_year < args.from_year:
        print("[error] --to-year must be >= --from-year", file=sys.stderr)
        return 1
    try:
        data_dir = load_data_dir()
        path = sidecar_path(data_dir)
        existing = load_existing(path)
        fetched = fetch_years(_service_key(), args.from_year, args.to_year)
        merged = existing | fetched
        write_sidecar(path, merged)
        print(
            f"[done] holidays sidecar {path}: "
            f"+{len(merged - existing)} new, {len(merged)} total"
        )
    except (HolidayFetchError, OSError, ET.ParseError) as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
