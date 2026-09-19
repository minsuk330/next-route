#!/usr/bin/env python3
"""Probe an OTP-enabled canary API without sending production traffic to OTP."""

from __future__ import annotations

import argparse
import json
import secrets
import subprocess
import tempfile
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode


CASES: dict[str, tuple[tuple[float, float], tuple[float, float]]] = {
    "cityhall-gangnam": ((37.5663, 126.9779), (37.4979, 127.0276)),
    "cheonggye-sadang": ((37.569147, 126.978647), (37.487272, 126.983399)),
    "hongdae-jamsil": ((37.5572, 126.9254), (37.5133, 127.1000)),
    "wangsimni-guro": ((37.5610, 127.0384), (37.4970, 126.8846)),
}


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def cgroup_cpu_usec(container: str) -> int | None:
    result = subprocess.run(["docker", "exec", container, "cat", "/sys/fs/cgroup/cpu.stat"],
                            text=True, capture_output=True, check=False)
    if result.returncode:
        return None
    for line in result.stdout.splitlines():
        key, value = line.split(maxsplit=1)
        if key == "usage_usec":
            return int(value)
    return None


def metrics(container: str) -> list[str]:
    result = subprocess.run(
        ["docker", "exec", container, "wget", "-qO-", "http://localhost:8081/actuator/prometheus"],
        text=True, capture_output=True, check=False,
    )
    if result.returncode:
        return [f"SCRAPE_FAILED:{result.stderr[-200:]}"]
    return [
        line for line in result.stdout.splitlines()
        if line.startswith("http_client_requests_seconds") and 'client_name="otp"' in line
        or line.startswith("http_server_requests_seconds") and 'uri="/api/route/search"' in line
    ]


def stats_collector(stop: threading.Event, output: Path, interval: float, containers: list[str]) -> None:
    with output.open("a", encoding="utf-8") as file:
        while not stop.is_set():
            stamp = now()
            result = subprocess.run(
                ["docker", "stats", "--no-stream", "--format", "{{json .}}", *containers],
                text=True, capture_output=True, check=False,
            )
            for line in result.stdout.splitlines():
                try:
                    file.write(json.dumps({"at": stamp, "dockerStats": json.loads(line)}, ensure_ascii=False) + "\n")
                except json.JSONDecodeError:
                    file.write(json.dumps({"at": stamp, "dockerStatsRaw": line}, ensure_ascii=False) + "\n")
            file.flush()
            stop.wait(interval)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", choices=tuple(CASES), required=True)
    parser.add_argument("--when", required=True)
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--api-host", default="nextroute-api-otp-probe")
    parser.add_argument("--api-container", default="nextroute-api-otp-probe")
    parser.add_argument("--otp-container", default="nextroute-otp-1")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--stats-out", type=Path)
    parser.add_argument("--stats-interval", type=float, default=0.25)
    args = parser.parse_args()

    origin, destination = CASES[args.case]
    params = {
        "startX": origin[1], "startY": origin[0],
        "endX": destination[1], "endY": destination[0],
        "startName": args.case + "-origin", "endName": args.case + "-destination",
        "departureAt": args.when,
    }
    url = f"http://{args.api_host}:8080/api/route/search?{urlencode(params)}"
    args.out.parent.mkdir(parents=True, exist_ok=True)
    if args.stats_out:
        args.stats_out.parent.mkdir(parents=True, exist_ok=True)

    stop = threading.Event()
    thread: threading.Thread | None = None
    if args.stats_out:
        thread = threading.Thread(
            target=stats_collector,
            args=(stop, args.stats_out, args.stats_interval, [args.otp_container, args.api_container]),
            daemon=True,
        )
        thread.start()

    try:
        with tempfile.TemporaryDirectory(prefix="otp-app-probe-") as temp_dir:
            temp = Path(temp_dir)
            temp.chmod(0o777)
            for ordinal in range(1, args.warmup + args.repeat + 1):
                response = temp / f"response-{ordinal}.json"
                trace_id, parent_id = secrets.token_hex(16), secrets.token_hex(8)
                before_otp = cgroup_cpu_usec(args.otp_container)
                before_api = cgroup_cpu_usec(args.api_container)
                before_metrics = metrics(args.api_container)
                started = now()
                command = [
                    "docker", "run", "--rm", "--network", "nextroute_default",
                    "--mount", f"type=bind,src={temp},dst=/probe/out",
                    "curlimages/curl:latest", "--silent", "--show-error", "--max-time", "10",
                    "--output", f"/probe/out/{response.name}", "--write-out", "%{json}\\n",
                    "--header", f"traceparent: 00-{trace_id}-{parent_id}-01",
                    "--request", "GET", url,
                ]
                result = subprocess.run(command, text=True, capture_output=True, check=False)
                finished = now()
                after_metrics = metrics(args.api_container)
                after_api = cgroup_cpu_usec(args.api_container)
                after_otp = cgroup_cpu_usec(args.otp_container)
                try:
                    curl = json.loads(result.stdout.strip().splitlines()[-1])
                except (IndexError, json.JSONDecodeError):
                    curl = {"parse_error": True, "raw": result.stdout[-500:]}
                try:
                    payload = json.loads(response.read_text(encoding="utf-8"))
                except (OSError, json.JSONDecodeError):
                    payload = None
                record = {
                    "recorded": ordinal > args.warmup,
                    "ordinal": ordinal - args.warmup if ordinal > args.warmup else ordinal,
                    "case": args.case, "url": url, "when": args.when,
                    "startedAt": started, "finishedAt": finished,
                    "traceId": trace_id,
                    "exitCode": result.returncode, "stderr": result.stderr[-500:], "curl": curl,
                    "responseShape": None if payload is None else {
                        "error": payload.get("error"), "paths": len(payload.get("paths", [])),
                        "meta": payload.get("meta"),
                    },
                    "metricsBefore": before_metrics, "metricsAfter": after_metrics,
                    "apiCpuDeltaUsec": None if before_api is None or after_api is None else after_api - before_api,
                    "otpCpuDeltaUsec": None if before_otp is None or after_otp is None else after_otp - before_otp,
                }
                with args.out.open("a", encoding="utf-8") as file:
                    file.write(json.dumps(record, ensure_ascii=False) + "\n")
                print(json.dumps(record, ensure_ascii=False))
    finally:
        stop.set()
        if thread:
            thread.join(timeout=max(1.0, args.stats_interval * 3))


if __name__ == "__main__":
    main()
