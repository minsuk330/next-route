"""Fetch hourly ASOS weather into per-date sidecar parquet.

Source: 기상청 지상(종관, ASOS) 시간자료 (data.go.kr getWthrDataList). A single
representative station (ASOS_STATION, default 108=서울) is fetched per service_date
and written to ``$DATA_DIR/weather/service_date=YYYY-MM-DD/data.parquet`` keyed by
``weather_hour`` (KST, hour granularity). build_dataset.py left-joins this sidecar
on weather_hour — a missing sidecar or hour just yields null weather features.

Only raw observations are stored (temperature/precip/humidity/wind/snow-depth). The
derived flags (is_raining/is_snowing/weather_severity) are computed at dataset build
time from a deterministic function shared in spec with the Java serving builder, so
changing a threshold never requires a re-fetch.

ASOS blank-value convention: rn (precip) and dsnw (snow depth) blanks mean *absent*
→ 0.0; ta/hm/ws blanks mean *missing measurement* → null.

Auth: reuses GONG_API (same data.go.kr key as the holiday endpoint / Java side).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta
from pathlib import Path

import polars as pl
from dotenv import load_dotenv


BASE_URL = (
    "https://apis.data.go.kr/1360000/AsosHourlyInfoService/getWthrDataList"
)
DEFAULT_STATION = "108"  # 서울
TM_FMT = "%Y-%m-%d %H:%M"
REQUEST_TIMEOUT_SECONDS = 20

SIDECAR_SCHEMA = {
    "weather_hour": pl.Datetime,
    "temperature_c": pl.Float64,
    "precipitation_mm": pl.Float64,
    "humidity_pct": pl.Float64,
    "wind_speed_mps": pl.Float64,
    "snow_depth_cm": pl.Float64,
}


class WeatherFetchError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fetch hourly ASOS weather into a per-date sidecar parquet."
    )
    parser.add_argument(
        "service_date",
        nargs="?",
        help="Service date in YYYY-MM-DD format. Omit when using --from/--to.",
    )
    parser.add_argument("--from", dest="from_date", help="Inclusive start date.")
    parser.add_argument("--to", dest="to_date", help="Inclusive end date.")
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Refetch even when the sidecar already exists.",
    )
    return parser.parse_args()


def parse_service_date(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise WeatherFetchError(f"Invalid service date '{value}'. Use YYYY-MM-DD.") from exc


def service_dates_from_args(args: argparse.Namespace) -> list[date]:
    if args.from_date or args.to_date:
        if args.service_date:
            raise WeatherFetchError("Use either service_date or --from/--to, not both.")
        if not args.from_date or not args.to_date:
            raise WeatherFetchError("--from and --to must be used together.")
        start = parse_service_date(args.from_date)
        end = parse_service_date(args.to_date)
        if end < start:
            raise WeatherFetchError("--to must be >= --from.")
        days = (end - start).days
        return [start + timedelta(days=offset) for offset in range(days + 1)]

    if not args.service_date:
        raise WeatherFetchError("service_date is required unless --from/--to is used.")
    return [parse_service_date(args.service_date)]


def load_data_dir() -> Path:
    ml_dir = Path(__file__).resolve().parent
    load_dotenv(ml_dir / ".env")

    data_dir_value = os.getenv("DATA_DIR", "./data")
    data_dir = Path(data_dir_value).expanduser()
    if not data_dir.is_absolute():
        data_dir = ml_dir / data_dir
    return data_dir


def sidecar_path(data_dir: Path, service_date: date) -> Path:
    return (
        data_dir / "weather" / f"service_date={service_date.isoformat()}" / "data.parquet"
    )


def _service_key() -> str:
    # Same data.go.kr key as Java GongApiAdapter / holiday_fetch.py (one account key
    # covers 특일정보 and ASOS once both services are subscribed).
    key = os.getenv("GONG_API")
    if not key:
        raise WeatherFetchError("GONG_API not set — cannot fetch ASOS weather.")
    return key


def _station() -> str:
    return os.getenv("ASOS_STATION", DEFAULT_STATION)


def _request_json(url: str) -> dict:
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
        return json.loads(response.read().decode("utf-8"))


def _absent_zero(raw: str | None) -> float:
    """rn/dsnw: blank means none → 0.0."""
    value = (raw or "").strip()
    return float(value) if value else 0.0


def _missing_null(raw: str | None) -> float | None:
    """ta/hm/ws: blank means sensor missing → null."""
    value = (raw or "").strip()
    return float(value) if value else None


def fetch_day(service_key: str, station: str, service_date: date) -> pl.DataFrame:
    day = service_date.strftime("%Y%m%d")
    params = urllib.parse.urlencode(
        {
            "pageNo": 1,
            "numOfRows": 24,
            "dataType": "JSON",
            "dataCd": "ASOS",
            "dateCd": "HR",
            "startDt": day,
            "startHh": "00",
            "endDt": day,
            "endHh": "23",
            "stnIds": station,
        }
    )
    url = f"{BASE_URL}?serviceKey={service_key}&{params}"
    payload = _request_json(url)

    header = payload.get("response", {}).get("header", {})
    result_code = header.get("resultCode")
    if result_code is not None and result_code != "00":
        raise WeatherFetchError(
            f"{service_date} resultCode={result_code} msg={header.get('resultMsg')}"
        )

    body = payload.get("response", {}).get("body", {}) or {}
    items_holder = body.get("items") or {}
    items = items_holder.get("item") if isinstance(items_holder, dict) else None
    if not items:
        return pl.DataFrame(schema=SIDECAR_SCHEMA)
    if isinstance(items, dict):
        items = [items]

    rows = []
    for item in items:
        tm = (item.get("tm") or "").strip()
        if not tm:
            continue
        rows.append(
            {
                "weather_hour": datetime.strptime(tm, TM_FMT).replace(minute=0),
                "temperature_c": _missing_null(item.get("ta")),
                "precipitation_mm": _absent_zero(item.get("rn")),
                "humidity_pct": _missing_null(item.get("hm")),
                "wind_speed_mps": _missing_null(item.get("ws")),
                "snow_depth_cm": _absent_zero(item.get("dsnw")),
            }
        )
    return pl.DataFrame(rows, schema=SIDECAR_SCHEMA)


def write_sidecar(path: Path, frame: pl.DataFrame) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_suffix(".parquet.tmp")
    frame.sort("weather_hour").write_parquet(tmp_path)
    tmp_path.replace(path)


def main() -> int:
    args = parse_args()
    try:
        service_dates = service_dates_from_args(args)
        data_dir = load_data_dir()
        service_key = _service_key()
        station = _station()
        for service_date in service_dates:
            path = sidecar_path(data_dir, service_date)
            if path.exists() and not args.overwrite:
                print(f"[skip] weather {service_date}: {path} exists")
                continue
            frame = fetch_day(service_key, station, service_date)
            write_sidecar(path, frame)
            print(f"[done] weather {service_date} stn={station}: hours={frame.height} -> {path}")
    except (WeatherFetchError, OSError, ValueError) as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
