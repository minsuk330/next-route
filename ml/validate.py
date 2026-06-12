from __future__ import annotations

import argparse
from datetime import date


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate bus ML archives and generated dataset parquet."
    )
    parser.add_argument("service_date", help="Service date in YYYY-MM-DD format.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    date.fromisoformat(args.service_date)
    raise NotImplementedError("Phase 3: validate.py is not implemented yet.")


if __name__ == "__main__":
    raise SystemExit(main())
