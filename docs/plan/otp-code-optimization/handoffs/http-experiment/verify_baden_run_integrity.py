#!/usr/bin/env python3
"""Check that one Baden HTTP run has one ready listener and complete records."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def status_fields(path: Path) -> dict[str, list[str]]:
    fields: dict[str, list[str]] = {}
    for line in path.read_text(errors="ignore").splitlines() if path.exists() else []:
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        fields.setdefault(key, []).append(value)
    return fields


def verify(run: Path) -> dict:
    status = status_fields(run / "status.txt")
    server = (run / "server.log").read_text(errors="ignore") if (run / "server.log").exists() else ""
    request_count = 0
    seqs: set[int] = set()
    statuses: set[int] = set()
    if (run / "requests.jsonl").exists():
        for line in (run / "requests.jsonl").read_text(errors="ignore").splitlines():
            row = json.loads(line)
            if "seq" in row:
                request_count += 1
                seqs.add(int(row["seq"]))
                statuses.add(int(row["httpStatus"]))
    issues: list[str] = []
    if status.get("ready") != ["1"]:
        issues.append(f"ready={status.get('ready')}")
    if "Cannot bind to port 18080" in server:
        issues.append("port_bind_failure")
    if len(status.get("serverPid", [])) != 1:
        issues.append("serverPid_not_unique")
    if len(status.get("generatorExit", [])) != 1 or status.get("generatorExit") != ["0"]:
        issues.append(f"generatorExit={status.get('generatorExit')}")
    if len(status.get("serverExit", [])) != 1 or status.get("serverExit") != ["0"]:
        issues.append(f"serverExit={status.get('serverExit')}")
    if "finishedAt" not in status:
        issues.append("not_finished")
    if request_count and (len(seqs) != request_count or max(seqs) != request_count):
        issues.append("request_sequence_not_complete")
    if request_count and statuses != {200}:
        issues.append(f"http_statuses={sorted(statuses)}")
    return {"run": run.name, "valid": not issues, "issues": issues, "requestCount": request_count, "httpStatuses": sorted(statuses)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("run", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps({run.name: verify(run) for run in args.run}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
