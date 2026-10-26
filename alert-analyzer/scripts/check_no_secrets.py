"""Fail if any value from the given env files appears in the given files.

Run on the VPS before committing captured fixtures (standard library only):

    python3 scripts/check_no_secrets.py \
        --env '/root/apps/nextroute/env/*.env' --env '/root/apps/observability/env/*.env' \
        fixtures/incidents/*.json

Only variable names are printed, never values. Short values, booleans,
numbers and filesystem paths are not secrets and are skipped; --ignore drops
other known non-secret variables (for example a public base URL).
"""

from __future__ import annotations

import argparse
import glob
import sys
from pathlib import Path

MIN_LENGTH = 12


def load_values(patterns: list[str], ignore: set[str]) -> dict[str, str]:
    values: dict[str, str] = {}
    for pattern in patterns:
        for path in sorted(glob.glob(pattern)):
            for raw in Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
                line = raw.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                name, _, value = line.partition("=")
                name = name.strip()
                value = value.strip().strip("\"'")
                if name in ignore or not looks_secret(value):
                    continue
                values[name] = value
    return values


def looks_secret(value: str) -> bool:
    if len(value) < MIN_LENGTH:
        return False
    if value.lower() in {"true", "false"} or value.replace(".", "").isdigit():
        return False
    return not value.startswith(("/", "file:"))


def find(values: dict[str, str], files: list[str]) -> list[tuple[str, str]]:
    hits = []
    for path in files:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
        hits.extend((name, path) for name, value in values.items() if value in text)
    return hits


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--env", action="append", required=True, help="glob of env files (repeatable)")
    parser.add_argument("--ignore", action="append", default=[], help="variable name to skip (repeatable)")
    parser.add_argument("files", nargs="+")
    args = parser.parse_args(argv)

    values = load_values(args.env, set(args.ignore))
    hits = find(values, args.files)
    for name, path in hits:
        print(f"FOUND {name} in {path}")
    print(f"checked {len(values)} env values against {len(args.files)} files: {len(hits)} hits")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
