from __future__ import annotations

from datetime import datetime

import polars as pl

import weather_fetch
from build_dataset import (
    WEATHER_RAW_COLUMNS,
    attach_weather,
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
