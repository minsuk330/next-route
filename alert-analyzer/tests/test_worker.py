from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone

import pytest

from analyzer.config import Settings
from analyzer.context import ContextBundle, TemplateStat
from analyzer.dedupe import DailyBudget
from analyzer.payload import AnalysisJob, GrafanaAlert
from analyzer.worker import process, run_worker

pytestmark = pytest.mark.anyio

NOW = datetime(2026, 10, 26, 7, 2, tzinfo=timezone.utc)
SETTINGS = Settings(webhook_token="t", daily_limit=1)


def job(received_at: datetime = NOW, key: str = "g") -> AnalysisJob:
    alert = GrafanaAlert(status="firing", labels={"alertname": "x"}, startsAt=NOW, fingerprint="f")
    return AnalysisJob(group_key=key, alerts=(alert,), received_at=received_at)


def bundle_with_secret() -> ContextBundle:
    return ContextBundle(
        alerts=[],
        window_start=NOW,
        window_end=NOW,
        templates=[
            TemplateStat(
                id="t:1",
                app="worker",
                level="ERROR",
                logger="a.B",
                template="x",
                status="NEW",
                logger_total=1,
                logger_baseline=0,
                sample_count=1,
                sample_capped=False,
                baseline_seen=False,
                samples=["GET /api/subway/4a6b7c8d9e0f1a2b3c4d5e6f7a/json failed"],
            )
        ],
        quiet_summary=[],
        metrics=[],
        restarts=[],
        collection_errors=[],
    )


async def test_process_masks_collected_bundle():
    async def gather(_):
        return bundle_with_secret()

    result = await process(job(), settings=SETTINGS, budget=DailyBudget(5), gather_fn=gather, now=NOW)

    assert "4a6b7c8d9e0f1a2b3c4d5e6f7a" not in result.templates[0].samples[0]


async def test_process_skips_when_daily_limit_reached(caplog):
    calls = []

    async def gather(j):
        calls.append(j)
        return bundle_with_secret()

    budget = DailyBudget(1)
    await process(job(), settings=SETTINGS, budget=budget, gather_fn=gather, now=NOW)
    with caplog.at_level(logging.WARNING):
        assert await process(job(), settings=SETTINGS, budget=budget, gather_fn=gather, now=NOW) is None

    assert len(calls) == 1
    assert "daily analysis limit 1 reached" in caplog.text


async def test_process_drops_stale_job():
    async def gather(_):
        raise AssertionError("stale job must not be gathered")

    stale = job(received_at=NOW - timedelta(minutes=11))

    assert await process(stale, settings=SETTINGS, budget=DailyBudget(5), gather_fn=gather, now=NOW) is None


async def test_worker_survives_failing_job(caplog):
    done = []

    async def gather(j):
        if j.group_key == "bad":
            raise RuntimeError("boom")
        done.append(j.group_key)
        return bundle_with_secret()

    queue: asyncio.Queue = asyncio.Queue()
    queue.put_nowait(job(key="bad"))
    queue.put_nowait(job(key="good"))
    task = asyncio.create_task(
        run_worker(queue, settings=Settings(webhook_token="t"), budget=DailyBudget(5), gather_fn=gather, clock=lambda: NOW)
    )
    with caplog.at_level(logging.ERROR):
        await asyncio.wait_for(queue.join(), timeout=5)
    task.cancel()

    assert done == ["good"]
    assert "analysis failed group=bad" in caplog.text
