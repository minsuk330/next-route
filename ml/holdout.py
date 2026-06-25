"""Coverage-safe holdout (test-date) selection for cumulative retrain.

Weekly route rotation means a brand-new route can appear only in the most recent
days. A naive "last 7 days = test" split can move every row of such a route into
test, leaving 0 train rows → it drops out of training_route_categories → the
coverage gate fails forever. See docs/plan/rotation-PR5-cumulative-retrain.md.

This picks test dates from the recent end but **never** lets a route end up with
zero train dates: any date whose presence would strand a route is pushed back into
train (dropped from test, newest-first). Coverage beats test recency.

CLI: reads a JSON map {service_date: [route_id, ...]} from --date-routes-file (or
stdin) and prints the chosen test dates as a comma list.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


class HoldoutError(RuntimeError):
    pass


def stranded_routes(date_routes: dict[str, set[str]], test: set[str]) -> set[str]:
    """Routes whose every date is in `test` (→ 0 train rows)."""
    train_dates = [d for d in date_routes if d not in test]
    train_routes: set[str] = set()
    for d in train_dates:
        train_routes |= date_routes[d]
    all_routes: set[str] = set()
    for routes in date_routes.values():
        all_routes |= routes
    return all_routes - train_routes


def select_test_dates(
    date_routes: dict[str, set[str]], max_test_days: int = 7
) -> list[str]:
    dates = sorted(date_routes)
    if len(dates) < 2:
        raise HoldoutError("need >= 2 service dates for a train/test split")

    # start with the most-recent window, always leaving >= 1 train date.
    k = min(max_test_days, len(dates) - 1)
    test = set(dates[-k:])

    # push newest dates back to train until no route is stranded.
    while test and stranded_routes(date_routes, test):
        test.discard(max(test))

    if not test:
        raise HoldoutError(
            "cannot form a coverage-safe holdout (a route appears only on dates "
            "that must stay in train); collect more days or adjust ML_TRAIN_FROM"
        )
    return sorted(test)


def _load(args: argparse.Namespace) -> dict[str, set[str]]:
    raw = (
        Path(args.date_routes_file).read_text()
        if args.date_routes_file
        else sys.stdin.read()
    )
    data = json.loads(raw)
    return {str(d): set(str(r) for r in routes) for d, routes in data.items()}


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Coverage-safe holdout selection.")
    p.add_argument("--date-routes-file", default=None, help="JSON {date: [route...]}.")
    p.add_argument("--max-test-days", type=int, default=7)
    args = p.parse_args(argv)
    try:
        test = select_test_dates(_load(args), args.max_test_days)
    except HoldoutError as exc:
        print(f"holdout error: {exc}", file=sys.stderr)
        return 1
    print(",".join(test))
    return 0


if __name__ == "__main__":
    sys.exit(main())
