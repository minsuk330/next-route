"""Tests for coverage-safe holdout selection.

Run: uv run --extra serve-dev pytest test_holdout.py
"""

from __future__ import annotations

import pytest

import holdout


def _dr(**kw):
    return {d: set(r) for d, r in kw.items()}


def test_normal_last_week_when_routes_recur():
    # every route appears across all days → plain last-N window is fine
    dr = {f"2026-06-{d:02d}": {"143", "272"} for d in range(1, 15)}
    test = holdout.select_test_dates(dr, max_test_days=7)
    assert test == sorted(dr)[-7:]  # last 7 days


def test_new_route_only_on_last_day_is_pushed_to_train():
    # 160 appears ONLY on the most recent day → must stay in train
    dr = _dr(**{
        "2026-06-10": ["143", "272"],
        "2026-06-11": ["143", "272"],
        "2026-06-12": ["143", "272"],
        "2026-06-13": ["143", "272", "160"],
    })
    test = holdout.select_test_dates(dr, max_test_days=7)
    assert "2026-06-13" not in test          # newest pushed to train
    stranded = holdout.stranded_routes(dr, set(test))
    assert stranded == set()                 # every route has a train date


def test_new_route_spanning_two_recent_days_keeps_one_in_train():
    dr = _dr(**{
        "2026-06-10": ["143"],
        "2026-06-11": ["143"],
        "2026-06-12": ["143", "999"],
        "2026-06-13": ["143", "999"],
    })
    test = holdout.select_test_dates(dr, max_test_days=7)
    assert holdout.stranded_routes(dr, set(test)) == set()
    assert test  # non-empty


def test_raises_when_single_date():
    with pytest.raises(holdout.HoldoutError):
        holdout.select_test_dates({"2026-06-13": {"143"}})


def test_main_prints_comma_list(tmp_path, capsys):
    import json

    f = tmp_path / "dr.json"
    f.write_text(json.dumps({
        "2026-06-10": ["143", "272"],
        "2026-06-11": ["143", "272"],
        "2026-06-12": ["143", "272"],
    }))
    code = holdout.main(["--date-routes-file", str(f), "--max-test-days", "1"])
    assert code == 0
    assert capsys.readouterr().out.strip() == "2026-06-12"
