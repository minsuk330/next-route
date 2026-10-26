"""Capture the masked context bundle for a past moment, for the eval set.

Needs the observability network, so run it inside the analyzer container:

    docker exec alert-analyzer python -m scripts.capture_fixture \
        --at 2026-10-23T19:01:00+09:00 --name subway-api-503 \
        --alertname "ERROR 로그 급증" --label app=worker > subway-api-503.json

The alert itself is synthetic (these incidents predate the alert rules); only
the logs and metrics are real. Check the output with check_no_secrets.py
before committing it.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
from datetime import datetime, timezone

import httpx2 as httpx

from analyzer.context import gather
from analyzer.masking import mask_bundle
from analyzer.payload import AnalysisJob, GrafanaAlert
from analyzer.sources import LokiClient, PromClient


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--at", required=True, help="alert start, ISO 8601 with offset")
    parser.add_argument("--name", required=True)
    parser.add_argument("--alertname", required=True)
    parser.add_argument("--label", action="append", default=[], help="extra alert label k=v (repeatable)")
    parser.add_argument("--summary", default="")
    parser.add_argument("--loki", default=os.environ.get("ANALYZER_LOKI_URL", "http://loki:3100"))
    parser.add_argument("--prom", default=os.environ.get("ANALYZER_PROM_URL", "http://prometheus:9090"))
    return parser.parse_args(argv)


def build_job(args: argparse.Namespace) -> AnalysisJob:
    at = datetime.fromisoformat(args.at)
    if at.tzinfo is None:
        raise SystemExit("--at needs a UTC offset, e.g. +09:00")
    labels = {"alertname": args.alertname}
    for item in args.label:
        key, _, value = item.partition("=")
        labels[key] = value
    alert = GrafanaAlert(
        status="firing",
        labels=labels,
        annotations={"summary": args.summary} if args.summary else {},
        startsAt=at,
        fingerprint=f"capture-{args.name}",
    )
    return AnalysisJob(group_key=f"capture-{args.name}", alerts=(alert,), received_at=at)


async def capture(args: argparse.Namespace) -> dict:
    job = build_job(args)
    async with httpx.AsyncClient() as http:
        bundle = mask_bundle(await gather(job, LokiClient(args.loki, http), PromClient(args.prom, http)))
    return {
        "name": args.name,
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "synthetic_alert": True,
        "alert": {"labels": job.alerts[0].labels, "starts_at": job.alerts[0].startsAt.isoformat()},
        "bundle": json.loads(bundle.to_json()),
    }


def main(argv: list[str] | None = None) -> None:
    print(json.dumps(asyncio.run(capture(parse_args(argv))), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
