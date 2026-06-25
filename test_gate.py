"""Tests for the retrain deploy gate.

Run: uv run --extra serve-dev pytest test_gate.py
"""

from __future__ import annotations

import json
from pathlib import Path

import gate


def test_overall_mae_reads_nested_lightgbm_structure():
    # real train.py output (ml/train.py:208)
    metrics = {"lightgbm": {"overall": {"mae": 87.5}, "by_horizon": {}}}
    assert gate.overall_mae(metrics) == 87.5


def test_overall_mae_flat_fallback():
    assert gate.overall_mae({"overall": {"mae": 12.0}}) == 12.0


def test_metric_gate_pass_within_abs_and_no_prev():
    ok, _ = gate.metric_gate(90.0, None, abs_mae=100.0, max_regression=1.05)
    assert ok


def test_metric_gate_fail_over_absolute():
    ok, reason = gate.metric_gate(120.0, None, abs_mae=100.0, max_regression=1.05)
    assert not ok and "abs threshold" in reason


def test_metric_gate_fail_regression_vs_prev():
    # prev=100, factor 1.05 → limit 105. new 110 → fail.
    ok, reason = gate.metric_gate(110.0, 100.0, abs_mae=200.0, max_regression=1.05)
    assert not ok and "prev" in reason


def test_metric_gate_pass_small_regression_within_factor():
    ok, _ = gate.metric_gate(104.0, 100.0, abs_mae=200.0, max_regression=1.05)
    assert ok


def test_metric_gate_fail_nan():
    ok, reason = gate.metric_gate(float("nan"), None, abs_mae=100.0, max_regression=1.05)
    assert not ok and "NaN" in reason


def test_coverage_gate_pass_when_training_covers_expected():
    ok, missing = gate.coverage_gate({"143", "272", "160"}, {"143", "272"})
    assert ok and missing == set()


def test_coverage_gate_fail_lists_missing():
    ok, missing = gate.coverage_gate({"143"}, {"143", "272", "160"})
    assert not ok and missing == {"272", "160"}


def _write_model(d: Path, mae: float, training_routes: list[str]) -> Path:
    d.mkdir(parents=True, exist_ok=True)
    # real train.py structure: {"lightgbm": {"overall": {...}, ...}} (ml/train.py:208)
    (d / "metrics.json").write_text(json.dumps({"lightgbm": {"overall": {"mae": mae}}}))
    (d / "training_manifest.json").write_text(
        json.dumps({"training_route_categories": training_routes})
    )
    return d


def test_main_pass_exit_0(tmp_path, capsys):
    model = _write_model(tmp_path / "new", 90.0, ["143", "272"])
    routes = tmp_path / "expected.txt"
    routes.write_text("143\n272\n")
    code = gate.main(
        ["--model-dir", str(model), "--abs-mae", "100", "--expected-routes-file", str(routes)]
    )
    assert code == 0
    assert json.loads(capsys.readouterr().out)["verdict"] == "pass"


def test_main_coverage_fail_exit_2(tmp_path, capsys):
    model = _write_model(tmp_path / "new", 90.0, ["143"])
    routes = tmp_path / "expected.txt"
    routes.write_text("143\n272\n")
    code = gate.main(
        ["--model-dir", str(model), "--abs-mae", "100", "--expected-routes-file", str(routes)]
    )
    assert code == 2
    body = json.loads(capsys.readouterr().out)
    assert body["verdict"] == "fail"
    assert body["coverage_gate"]["missing"] == ["272"]


def test_main_metric_fail_exit_2(tmp_path, capsys):
    model = _write_model(tmp_path / "new", 150.0, ["143"])
    code = gate.main(["--model-dir", str(model), "--abs-mae", "100"])
    assert code == 2
    assert json.loads(capsys.readouterr().out)["metric_gate"]["ok"] is False


def test_main_error_exit_1_when_metrics_missing(tmp_path, capsys):
    (tmp_path / "new").mkdir()
    code = gate.main(["--model-dir", str(tmp_path / "new"), "--abs-mae", "100"])
    assert code == 1
    assert json.loads(capsys.readouterr().out)["verdict"] == "error"
