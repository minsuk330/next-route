#!/usr/bin/env python3
"""Capture direct OTP HTTP timings and OTP-container CPU deltas on a VPS.

The probe deliberately executes the same GraphQL document used by the app.
``host`` uses the published loopback port; ``internal`` runs curl in a short
lived container on the Docker network.  One JSON line is emitted per request,
including curl's detailed timing fields and cgroup CPU use accrued by OTP.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
import threading
import time
from datetime import datetime, timezone
from pathlib import Path


CASES: dict[str, tuple[tuple[float, float], tuple[float, float]]] = {
    "cityhall-gangnam": ((37.5663, 126.9779), (37.4979, 127.0276)),
    "cheonggye-sadang": ((37.569147, 126.978647), (37.487272, 126.983399)),
    "hongdae-jamsil": ((37.5572, 126.9254), (37.5133, 127.1000)),
    "wangsimni-guro": ((37.5610, 127.0384), (37.4970, 126.8846)),
}

GRAPHQL_PATH = "/otp/routers/default/index/graphql"


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def cpu_usage_usec(container: str) -> int | None:
    result = subprocess.run(
        ["docker", "exec", container, "cat", "/sys/fs/cgroup/cpu.stat"],
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode:
        return None
    for line in result.stdout.splitlines():
        key, value = line.split(maxsplit=1)
        if key == "usage_usec":
            return int(value)
    return None


def make_body(kind: str, query: str | None, case: str | None, when: str) -> dict:
    if kind == "feeds":
        return {"query": "{feeds{feedId}}"}
    assert query is not None and case is not None
    origin, destination = CASES[case]
    return {
        "query": query,
        "variables": {
            "origin": {"location": {"coordinate": {"latitude": origin[0], "longitude": origin[1]}}},
            "destination": {"location": {"coordinate": {"latitude": destination[0], "longitude": destination[1]}}},
            "when": when,
            "first": 5,
        },
    }


def curl_command(mode: str, url: str, body_file: Path, response_file: Path) -> list[str]:
    curl_args = [
        "curl", "--silent", "--show-error", "--max-time", "15",
        "--output", str(response_file),
        "--write-out", "%{json}\\n",
        "--request", "POST", url,
        "--header", "Content-Type: application/json",
        "--header", "Accept: application/json",
        "--data-binary", f"@{body_file}",
    ]
    if mode == "host":
        return curl_args
    # The image is pre-cached on the VPS.  Mounting only the two temporary
    # files keeps this a disposable network probe rather than an app request.
    return [
        "docker", "run", "--rm", "--network", "nextroute_default",
        "--mount", f"type=bind,src={body_file},dst=/probe/request.json,readonly",
        "--mount", f"type=bind,src={response_file.parent},dst=/probe/out",
        "curlimages/curl:latest",
        "--silent", "--show-error", "--max-time", "15",
        "--output", f"/probe/out/{response_file.name}",
        "--write-out", "%{json}\\n",
        "--request", "POST", url,
        "--header", "Content-Type: application/json",
        "--header", "Accept: application/json",
        "--data-binary", "@/probe/request.json",
    ]


def stats_collector(stop: threading.Event, output: Path, interval: float) -> None:
    with output.open("a", encoding="utf-8") as file:
        while not stop.is_set():
            started = iso_now()
            result = subprocess.run(
                ["docker", "stats", "--no-stream", "--format", "{{json .}}",
                 "nextroute-otp-1", "nextroute-api-1"],
                text=True,
                capture_output=True,
                check=False,
            )
            for line in result.stdout.splitlines():
                try:
                    file.write(json.dumps({"at": started, "dockerStats": json.loads(line)}, ensure_ascii=False) + "\n")
                except json.JSONDecodeError:
                    file.write(json.dumps({"at": started, "dockerStatsRaw": line}, ensure_ascii=False) + "\n")
            file.flush()
            stop.wait(interval)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("host", "internal"), required=True)
    parser.add_argument("--kind", choices=("feeds", "plan"), required=True)
    parser.add_argument("--case", choices=tuple(CASES), help="required for --kind plan")
    parser.add_argument("--when", required=True)
    parser.add_argument("--query-file", type=Path, help="the app's planConnection.graphql")
    parser.add_argument("--repeat", type=int, default=5)
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--stats-out", type=Path)
    parser.add_argument("--stats-interval", type=float, default=0.25)
    parser.add_argument("--otp-container", default="nextroute-otp-1")
    args = parser.parse_args()

    if args.kind == "plan" and not args.case:
        parser.error("--case is required for --kind plan")
    if args.kind == "plan" and not args.query_file:
        parser.error("--query-file is required for --kind plan")

    query = args.query_file.read_text(encoding="utf-8") if args.query_file else None
    body = make_body(args.kind, query, args.case, args.when)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    if args.stats_out:
        args.stats_out.parent.mkdir(parents=True, exist_ok=True)

    stop = threading.Event()
    collector: threading.Thread | None = None
    if args.stats_out:
        collector = threading.Thread(
            target=stats_collector, args=(stop, args.stats_out, args.stats_interval), daemon=True
        )
        collector.start()

    url = "http://127.0.0.1:18080" + GRAPHQL_PATH if args.mode == "host" else "http://otp:8080" + GRAPHQL_PATH
    try:
        with tempfile.TemporaryDirectory(prefix="otp-overhead-") as temp_dir:
            temp = Path(temp_dir)
            # curlimages/curl runs as an unprivileged user.  This directory is
            # short-lived and contains only this request/response pair.
            temp.chmod(0o777)
            body_file = temp / "request.json"
            body_file.write_text(json.dumps(body, separators=(",", ":")), encoding="utf-8")
            for ordinal in range(1, args.warmup + args.repeat + 1):
                response_file = temp / f"response-{ordinal}.json"
                before = cpu_usage_usec(args.otp_container)
                started = iso_now()
                result = subprocess.run(
                    curl_command(args.mode, url, body_file, response_file),
                    text=True,
                    capture_output=True,
                    check=False,
                )
                finished = iso_now()
                after = cpu_usage_usec(args.otp_container)
                try:
                    curl = json.loads(result.stdout.strip().splitlines()[-1])
                except (IndexError, json.JSONDecodeError):
                    curl = {"parse_error": True, "raw": result.stdout[-500:]}
                try:
                    response = json.loads(response_file.read_text(encoding="utf-8"))
                    graphql_errors = response.get("errors", [])
                except (OSError, json.JSONDecodeError):
                    graphql_errors = ["response-not-json"]
                record = {
                    "recorded": ordinal > args.warmup,
                    "ordinal": ordinal - args.warmup if ordinal > args.warmup else ordinal,
                    "kind": args.kind,
                    "case": args.case or "baseline",
                    "path": args.mode,
                    "url": url,
                    "when": args.when,
                    "startedAt": started,
                    "finishedAt": finished,
                    "exitCode": result.returncode,
                    "stderr": result.stderr[-500:],
                    "curl": curl,
                    "graphqlErrors": graphql_errors,
                    "otpCpuUsecBefore": before,
                    "otpCpuUsecAfter": after,
                    "otpCpuDeltaUsec": None if before is None or after is None else after - before,
                }
                with args.out.open("a", encoding="utf-8") as file:
                    file.write(json.dumps(record, ensure_ascii=False) + "\n")
                print(json.dumps(record, ensure_ascii=False))
    finally:
        stop.set()
        if collector:
            collector.join(timeout=max(1.0, args.stats_interval * 3))


if __name__ == "__main__":
    main()
