"""Background worker: one analysis at a time, so Loki and the LLM see no bursts."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from datetime import datetime, timedelta, timezone

from analyzer.config import Settings
from analyzer.context import ContextBundle
from analyzer.dedupe import DailyBudget
from analyzer.masking import mask_bundle
from analyzer.payload import AnalysisJob

log = logging.getLogger(__name__)

# A reply that shows up long after the alert is noise; drop work that queued too long.
MAX_JOB_WAIT = timedelta(minutes=10)

GatherFn = Callable[[AnalysisJob], Awaitable[ContextBundle]]


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def summarize(job: AnalysisJob, bundle: ContextBundle) -> str:
    names = sorted({a.labels.get("alertname", "?") for a in job.alerts})
    notable = sorted(
        {f"{t.app}/{t.logger.rsplit('.', 1)[-1]}:{t.status}:{t.logger_total}" for t in bundle.templates if t.status != "NORMAL"}
    )
    restarts = [r.instance for r in bundle.restarts]
    return (
        f"group={job.group_key} alerts={names} templates={len(bundle.templates)} "
        f"notable={notable} restarts={restarts} errors={bundle.collection_errors} "
        f"truncated={bundle.truncated} chars={len(bundle.to_json())}"
    )


async def process(
    job: AnalysisJob,
    *,
    settings: Settings,
    budget: DailyBudget,
    gather_fn: GatherFn,
    now: datetime,
) -> ContextBundle | None:
    if now - job.received_at > MAX_JOB_WAIT:
        log.info("dropped stale job group=%s waited=%s", job.group_key, now - job.received_at)
        return None
    if not budget.try_spend(now):
        if budget.exhausted_notice_due(now):
            log.warning("daily analysis limit %d reached; skipping until midnight KST", settings.daily_limit)
        else:
            log.info("daily analysis limit reached, skipped group=%s", job.group_key)
        return None
    bundle = mask_bundle(await gather_fn(job))
    # Dry run is the only mode until the LLM stage lands: record what would be sent.
    log.info("dry-run %s", summarize(job, bundle))
    return bundle


async def run_worker(
    queue: asyncio.Queue[AnalysisJob],
    *,
    settings: Settings,
    budget: DailyBudget,
    gather_fn: GatherFn,
    clock: Callable[[], datetime] = utcnow,
) -> None:
    while True:
        job = await queue.get()
        try:
            await process(job, settings=settings, budget=budget, gather_fn=gather_fn, now=clock())
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("analysis failed group=%s", job.group_key)
        finally:
            queue.task_done()
