from __future__ import annotations

from datetime import date, datetime

import polars as pl

from build_dataset import calendar_columns, holiday_sidecar_path, load_holidays


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
