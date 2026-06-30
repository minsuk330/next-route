from __future__ import annotations

from datetime import date, datetime

import polars as pl

import weather_fetch
from build_dataset import (
    WEATHER_RAW_COLUMNS,
    attach_weather,
    calendar_columns,
    holiday_sidecar_path,
    load_holidays,
    load_weather,
    weather_derived_columns,
    weather_sidecar_path,
)


def _derive(rows: list[dict]) -> pl.DataFrame:
    frame = pl.DataFrame(
        rows,
        schema={c: pl.Float64 for c in WEATHER_RAW_COLUMNS},
    )
    return frame.with_columns(weather_derived_columns())


def test_rain_vs_snow_precedence():
    out = _derive(
        [
            # rain: precip>0, no snow
            {"temperature_c": 5.0, "precipitation_mm": 2.0, "humidity_pct": 80.0,
             "wind_speed_mps": 1.0, "snow_depth_cm": 0.0},
            # snow precedence: precip and snow both > 0 → snow wins, rain off
            {"temperature_c": -1.0, "precipitation_mm": 1.0, "humidity_pct": 90.0,
             "wind_speed_mps": 1.0, "snow_depth_cm": 3.0},
            # dry
            {"temperature_c": 20.0, "precipitation_mm": 0.0, "humidity_pct": 40.0,
             "wind_speed_mps": 1.0, "snow_depth_cm": 0.0},
        ]
    )
    assert out["is_raining"].to_list() == [1, 0, 0]
    assert out["is_snowing"].to_list() == [0, 1, 0]


def test_weather_severity_buckets():
    out = _derive(
        [
            {"temperature_c": None, "precipitation_mm": 0.0, "humidity_pct": None,
             "wind_speed_mps": 0.0, "snow_depth_cm": 0.0},   # 0
            {"temperature_c": None, "precipitation_mm": 0.2, "humidity_pct": None,
             "wind_speed_mps": 0.0, "snow_depth_cm": 0.0},   # 1 (light rain)
            {"temperature_c": None, "precipitation_mm": 0.0, "humidity_pct": None,
             "wind_speed_mps": 11.0, "snow_depth_cm": 0.0},  # 2 (wind>=10)
            {"temperature_c": None, "precipitation_mm": 12.0, "humidity_pct": None,
             "wind_speed_mps": 0.0, "snow_depth_cm": 0.0},   # 3 (heavy rain)
            {"temperature_c": None, "precipitation_mm": 0.0, "humidity_pct": None,
             "wind_speed_mps": 0.0, "snow_depth_cm": 6.0},   # 3 (heavy snow)
        ]
    )
    assert out["weather_severity"].to_list() == [0, 1, 2, 3, 3]


def test_derived_null_when_inputs_null():
    out = _derive(
        [{"temperature_c": None, "precipitation_mm": None, "humidity_pct": None,
          "wind_speed_mps": None, "snow_depth_cm": None}]
    )
    assert out["is_raining"].to_list() == [None]
    assert out["is_snowing"].to_list() == [None]
    assert out["weather_severity"].to_list() == [None]


def _base_frame(times: list[datetime]) -> pl.LazyFrame:
    return pl.DataFrame({"snapshot_at": times}, schema={"snapshot_at": pl.Datetime}).lazy()


def test_attach_weather_joins_on_hour():
    weather = pl.DataFrame(
        {
            "weather_hour": [datetime(2026, 1, 5, 8, 0)],
            "temperature_c": [3.0],
            "precipitation_mm": [4.0],
            "humidity_pct": [70.0],
            "wind_speed_mps": [2.0],
            "snow_depth_cm": [0.0],
        },
        schema={"weather_hour": pl.Datetime, **{c: pl.Float64 for c in WEATHER_RAW_COLUMNS}},
    ).lazy()

    frame = _base_frame([datetime(2026, 1, 5, 8, 37), datetime(2026, 1, 5, 9, 5)])
    out = attach_weather(frame, weather).collect()
    # 08:37 → weather_hour 08:00 matches; 09:05 → 09:00 no row → null
    assert out["temperature_c"].to_list() == [3.0, None]
    assert out["is_raining"].to_list() == [1, None]


def test_attach_weather_none_sidecar_all_null():
    frame = _base_frame([datetime(2026, 1, 5, 8, 37)])
    out = attach_weather(frame, None).collect()
    assert out["temperature_c"].to_list() == [None]
    assert out["is_raining"].to_list() == [None]
    assert out["weather_severity"].to_list() == [None]


def test_load_weather_missing_returns_none(tmp_path):
    from datetime import date

    assert load_weather(tmp_path, date(2026, 1, 5)) is None
    assert weather_sidecar_path(tmp_path, date(2026, 1, 5)).name == "data.parquet"


def test_asos_blank_value_conventions():
    # rn/dsnw blank → 0.0 (absent); ta/hm/ws blank → null (missing)
    assert weather_fetch._absent_zero("") == 0.0
    assert weather_fetch._absent_zero("2.2") == 2.2
    assert weather_fetch._missing_null("") is None
    assert weather_fetch._missing_null("-3.1") == -3.1


def _apply(rows: list[datetime], holidays: list[date]) -> pl.DataFrame:
    frame = pl.DataFrame({"snapshot_at": rows}, schema={"snapshot_at": pl.Datetime})
    return frame.with_columns(calendar_columns(holidays))


def test_commute_and_late_night_windows():
    rows = [
        datetime(2026, 1, 5, 8, 0),   # commute morning
        datetime(2026, 1, 5, 9, 0),   # 9 excluded (half-open)
        datetime(2026, 1, 5, 19, 0),  # commute evening
        datetime(2026, 1, 5, 12, 0),  # none
        datetime(2026, 1, 5, 23, 30),  # late night (>=23)
        datetime(2026, 1, 5, 4, 0),   # late night (<5)
    ]
    out = _apply(rows, holidays=[])
    assert out["is_commute_morning"].to_list() == [1, 0, 0, 0, 0, 0]
    assert out["is_commute_evening"].to_list() == [0, 0, 1, 0, 0, 0]
    assert out["is_late_night"].to_list() == [0, 0, 0, 0, 1, 1]


def test_holiday_pre_post():
    holidays = [date(2026, 1, 1)]
    rows = [
        datetime(2026, 1, 1, 10, 0),   # holiday
        datetime(2025, 12, 31, 10, 0),  # eve → pre_holiday
        datetime(2026, 1, 2, 10, 0),   # day after → post_holiday
        datetime(2026, 1, 10, 10, 0),  # ordinary
    ]
    out = _apply(rows, holidays)
    assert out["is_holiday"].to_list() == [1, 0, 0, 0]
    assert out["is_pre_holiday"].to_list() == [0, 1, 0, 0]
    assert out["is_post_holiday"].to_list() == [0, 0, 1, 0]


def test_holiday_features_null_when_sidecar_absent():
    out = _apply([datetime(2026, 1, 1, 10, 0)], holidays=[])
    assert out["is_holiday"].to_list() == [None]
    assert out["is_pre_holiday"].to_list() == [None]
    assert out["is_post_holiday"].to_list() == [None]
    # commute/late are independent of holidays — always computed
    assert out["is_commute_morning"].dtype == pl.Int8


def test_load_holidays_roundtrip(tmp_path):
    data_dir = tmp_path
    path = holiday_sidecar_path(data_dir)
    path.parent.mkdir(parents=True)
    pl.DataFrame(
        {"holiday_date": [date(2026, 1, 1), date(2026, 3, 1)]},
        schema={"holiday_date": pl.Date},
    ).write_parquet(path)

    assert sorted(load_holidays(data_dir)) == [date(2026, 1, 1), date(2026, 3, 1)]


def test_load_holidays_missing_returns_empty(tmp_path):
    assert load_holidays(tmp_path) == []
