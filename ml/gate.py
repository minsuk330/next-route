"""Retrain deploy gate. Decides whether a freshly trained model may replace
the current served model.

Two independent gates, both must pass to deploy:

1. metric gate  — new overall MAE within absolute threshold AND not a regression
                  beyond ``--max-regression`` factor vs the previously deployed model.
2. coverage gate — the model's ``training_route_categories`` (routes actually in the
                  train split, recorded by train.py) must cover every expected route
                  (routes that have data). Guards against ``--sample-rows`` or holdout
                  dropping a route from training while it still shows up in
                  ``route_categories`` (train∪test). See PR #41.

Exit codes: 0 = pass (deploy), 2 = gate failed (keep current), 1 = usage/IO error.

Run: uv run python gate.py --model-dir <new> [--prev-dir <old>] --abs-mae 95 \
        [--expected-routes-file routes.txt] [--max-regression 1.05]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


class GateError(RuntimeError):
    pass


def overall_mae(metrics: dict[str, Any]) -> float:
    try:
        return float(metrics["overall"]["mae"])
    except (KeyError, TypeError, ValueError) as exc:
        raise GateError(f"metrics missing overall.mae: {exc}") from exc


def metric_gate(
    new_mae: float,
    prev_mae: float | None,
    abs_mae: float,
    max_regression: float,
) -> tuple[bool, str]:
    """absolute threshold AND (no prev OR within regression factor)."""
    if new_mae != new_mae:  # NaN
        return False, "new mae is NaN"
    if new_mae > abs_mae:
        return False, f"new mae {new_mae:.2f} > abs threshold {abs_mae:.2f}"
    if prev_mae is not None and prev_mae == prev_mae:
        limit = prev_mae * max_regression
        if new_mae > limit:
            return False, (
                f"new mae {new_mae:.2f} > prev {prev_mae:.2f} * {max_regression} = {limit:.2f}"
            )
    return True, f"mae {new_mae:.2f} ok (abs<={abs_mae:.2f}, prev={prev_mae})"


def coverage_gate(
    training_routes: set[str], expected_routes: set[str]
) -> tuple[bool, set[str]]:
    """training_routes must ⊇ expected_routes. Returns (ok, missing)."""
    missing = expected_routes - training_routes
    return (not missing), missing


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise GateError(f"file not found: {path}")
    return json.loads(path.read_text())


def model_files(model_dir: Path) -> tuple[Path, Path]:
    return model_dir / "metrics.json", model_dir / "training_manifest.json"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Retrain deploy gate.")
    p.add_argument("--model-dir", required=True, help="New experiment dir.")
    p.add_argument("--prev-dir", default=None, help="Previously deployed experiment dir.")
    p.add_argument("--abs-mae", type=float, required=True, help="Absolute MAE ceiling.")
    p.add_argument("--max-regression", type=float, default=1.05)
    p.add_argument(
        "--expected-routes-file",
        default=None,
        help="Newline-separated route ids that must be covered by training.",
    )
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    model_dir = Path(args.model_dir)
    metrics_path, manifest_path = model_files(model_dir)

    try:
        new_mae = overall_mae(load_json(metrics_path))
        prev_mae = None
        if args.prev_dir:
            prev_metrics_path, _ = model_files(Path(args.prev_dir))
            if prev_metrics_path.exists():
                prev_mae = overall_mae(load_json(prev_metrics_path))

        m_ok, m_reason = metric_gate(new_mae, prev_mae, args.abs_mae, args.max_regression)

        c_ok, missing = True, set()
        if args.expected_routes_file:
            manifest = load_json(manifest_path)
            training = set(str(r) for r in manifest.get("training_route_categories", []))
            expected = {
                line.strip()
                for line in Path(args.expected_routes_file).read_text().splitlines()
                if line.strip()
            }
            c_ok, missing = coverage_gate(training, expected)
    except GateError as exc:
        print(json.dumps({"verdict": "error", "reason": str(exc)}))
        return 1

    verdict = {
        "verdict": "pass" if (m_ok and c_ok) else "fail",
        "metric_gate": {"ok": m_ok, "reason": m_reason},
        "coverage_gate": {"ok": c_ok, "missing": sorted(missing)},
    }
    print(json.dumps(verdict))
    return 0 if (m_ok and c_ok) else 2


if __name__ == "__main__":
    sys.exit(main())
