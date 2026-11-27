"""Background worker: one analysis at a time, so Loki and the LLM see no bursts."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Protocol

from analyzer.config import Settings
from analyzer.context import ContextBundle
from analyzer.dedupe import DailyBudget
from analyzer.fix import FixProposal
from analyzer.llm import LlmFailure, Verdict
from analyzer.masking import mask_bundle
from analyzer.payload import AnalysisJob
from analyzer.slack import SlackError, explore_url, render, render_budget_notice, render_failure, render_fix

log = logging.getLogger(__name__)

# A reply that shows up long after the alert is noise; drop work that queued too long.
MAX_JOB_WAIT = timedelta(minutes=10)

GatherFn = Callable[[AnalysisJob], Awaitable[ContextBundle]]
AnalyzeFn = Callable[[ContextBundle], Awaitable[Verdict]]


class Notifier(Protocol):
    async def reply(self, job: AnalysisJob, text: str) -> bool: ...


@dataclass(frozen=True)
class Live:
    """The LLM and Slack stages. Absent in dry run."""

    analyze: AnalyzeFn
    notifier: Notifier
    # Stage 4 (analyzer.fix): propose a code change after the analysis. None = off.
    fix: Callable[[Verdict, ContextBundle], Awaitable["FixProposal"]] | None = None


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
        f"truncated={bundle.truncated} chars={len(bundle.to_json())} "
        f"code_via={bundle.code_via} code={[c['symbol'] for c in bundle.code]}"
    )


async def process(
    job: AnalysisJob,
    *,
    settings: Settings,
    budget: DailyBudget,
    gather_fn: GatherFn,
    now: datetime,
    live: Live | None = None,
) -> ContextBundle | None:
    if now - job.received_at > MAX_JOB_WAIT:
        log.info("dropped stale job group=%s waited=%s", job.group_key, now - job.received_at)
        return None
    if not budget.try_spend(now):
        if budget.exhausted_notice_due(now):
            log.warning("daily analysis limit %d reached; skipping until midnight KST", settings.daily_limit)
            if live is not None:
                await _notify(live.notifier, job, render_budget_notice(settings.daily_limit))
        else:
            log.info("daily analysis limit reached, skipped group=%s", job.group_key)
        return None
    bundle = mask_bundle(await gather_fn(job))
    if live is None:
        # Dry run: record what would be sent, call nothing outside.
        log.info("dry-run %s", summarize(job, bundle))
        return bundle

    link = explore_url(settings.grafana_url, bundle)
    try:
        verdict = await live.analyze(bundle)
    except LlmFailure as exc:
        log.warning("analysis failed group=%s reason=%s", job.group_key, exc.reason)
        await _notify(live.notifier, job, render_failure(exc.reason, link=link))
        return bundle
    threaded = await _notify(live.notifier, job, render(verdict, bundle, link=link))
    log.info(
        "analyzed group=%s categories=%s unknown=%s invalid_evidence=%d/%d tokens=%d/%d cost_usd=%.4f "
        "elapsed_s=%.1f threaded=%s request_id=%s code_via=%s code=%d cards=%d fix=%d",
        job.group_key,
        [h.category for h in verdict.hypotheses],
        verdict.unknown,
        verdict.invalid_evidence,
        verdict.total_evidence,
        verdict.input_tokens,
        verdict.output_tokens,
        verdict.cost_usd,
        verdict.elapsed_s,
        threaded,
        verdict.request_id,
        bundle.code_via,
        len(bundle.code),
        len(bundle.cards),
        len(verdict.fix_candidates),
    )
    if live.fix is not None:
        proposal = await live.fix(verdict, bundle)
        text = render_fix(proposal)
        if text:
            await _notify(live.notifier, job, text)
        log.info(
            "fix_proposal group=%s status=%s target=%s reason=%s cost_usd=%.4f",
            job.group_key,
            proposal.status,
            (proposal.target or {}).get("symbol"),
            proposal.reason,
            proposal.cost_usd,
        )
    return bundle


async def _notify(notifier: Notifier, job: AnalysisJob, text: str) -> bool | None:
    """Post to Slack. A Slack failure is logged, never raised: the alert itself already went out."""
    try:
        return await notifier.reply(job, text)
    except SlackError as exc:
        log.warning("slack post failed group=%s: %s", job.group_key, exc)
        return None


async def run_worker(
    queue: asyncio.Queue[AnalysisJob],
    *,
    settings: Settings,
    budget: DailyBudget,
    gather_fn: GatherFn,
    live: Live | None = None,
    clock: Callable[[], datetime] = utcnow,
) -> None:
    while True:
        job = await queue.get()
        try:
            await process(job, settings=settings, budget=budget, gather_fn=gather_fn, now=clock(), live=live)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("analysis failed group=%s", job.group_key)
        finally:
            queue.task_done()
