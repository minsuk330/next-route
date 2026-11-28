from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone

import pytest

from analyzer.config import Settings
from analyzer.context import ContextBundle, TemplateStat
from analyzer.dedupe import DailyBudget
from analyzer.llm import Hypothesis, LlmFailure, Verdict
from analyzer.payload import AnalysisJob, GrafanaAlert
from analyzer.slack import SlackError
from analyzer.worker import Live, process, run_worker

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


LIVE_SETTINGS = Settings(webhook_token="t", dry_run=False, daily_limit=1)


class FakeNotifier:
    def __init__(self, error: Exception | None = None):
        self.texts: list[str] = []
        self.error = error

    async def reply(self, j: AnalysisJob, msg) -> bool:
        # Everything a reader would see (blocks + fallback text), so assertions read like the message.
        self.texts.append(msg.visible() if hasattr(msg, "visible") else msg)
        if self.error:
            raise self.error
        return True

    async def thread_of(self, j: AnalysisJob) -> str | None:
        return getattr(self, "thread", "1795782729.655779")


def verdict() -> Verdict:
    return Verdict(
        headline="새 ERROR 로거 1개",
        hypotheses=[
            Hypothesis(cause="c", category="app_bug", confidence="low", evidence=["t:1"], next_check="n")
        ],
        deploy_related=False,
        unknown=False,
        invalid_evidence=0,
        total_evidence=1,
        model="claude-sonnet-5-5",
        input_tokens=10,
        output_tokens=5,
        elapsed_s=1.0,
    )


async def gather_secret(_):
    return bundle_with_secret()


async def test_live_posts_the_analysis_of_the_masked_bundle():
    seen = []

    async def analyze(bundle):
        seen.append(bundle)
        return verdict()

    notifier = FakeNotifier()
    await process(
        job(), settings=LIVE_SETTINGS, budget=DailyBudget(5), gather_fn=gather_secret, now=NOW,
        live=Live(analyze=analyze, notifier=notifier),
    )

    assert "4a6b7c8d9e0f1a2b3c4d5e6f7a" not in seen[0].templates[0].samples[0]
    assert len(notifier.texts) == 1
    assert "새 ERROR 로거 1개" in notifier.texts[0]
    assert "worker B ERROR 1건" in notifier.texts[0]
    assert "t:1" not in notifier.texts[0]  # evidence ids stay out of the message


async def test_llm_failure_posts_a_failure_line(caplog):
    async def analyze(_):
        raise LlmFailure("시간 초과")

    notifier = FakeNotifier()
    with caplog.at_level(logging.WARNING):
        bundle = await process(
            job(), settings=LIVE_SETTINGS, budget=DailyBudget(5), gather_fn=gather_secret, now=NOW,
            live=Live(analyze=analyze, notifier=notifier),
        )

    assert bundle is not None
    assert "AI 원인 분석 실패" in notifier.texts[0] and "시간 초과" in notifier.texts[0]
    assert "analysis failed group=g reason=시간 초과" in caplog.text


async def test_slack_failure_is_logged_not_raised(caplog):
    async def analyze(_):
        return verdict()

    with caplog.at_level(logging.WARNING):
        await process(
            job(), settings=LIVE_SETTINGS, budget=DailyBudget(5), gather_fn=gather_secret, now=NOW,
            live=Live(analyze=analyze, notifier=FakeNotifier(SlackError("slack chat.postMessage: not_in_channel"))),
        )

    assert "slack post failed group=g: slack chat.postMessage: not_in_channel" in caplog.text


async def test_daily_limit_is_announced_once_in_slack():
    async def analyze(_):
        return verdict()

    notifier = FakeNotifier()
    live = Live(analyze=analyze, notifier=notifier)
    budget = DailyBudget(1)
    for _ in range(3):
        await process(job(), settings=LIVE_SETTINGS, budget=budget, gather_fn=gather_secret, now=NOW, live=live)

    assert len(notifier.texts) == 2
    assert "오늘 AI 분석 상한(1건)" in notifier.texts[1]


async def _run_with_fix(proposal):
    from analyzer.fix import FixProposal  # noqa: F401
    calls = []

    async def analyze(_):
        return verdict()

    async def fix(v, b):
        calls.append((v, b))
        return proposal

    notifier = FakeNotifier()
    await process(
        job(), settings=LIVE_SETTINGS, budget=DailyBudget(5), gather_fn=gather_secret, now=NOW,
        live=Live(analyze=analyze, notifier=notifier, fix=fix),
    )
    return notifier, calls


async def test_fix_proposal_is_a_second_reply_only_when_it_has_something_to_show(caplog):
    import logging
    from analyzer.fix import FixProposal
    caplog.set_level(logging.INFO, logger="analyzer.worker")
    target = {"symbol": "Svc#ok", "revision": "d" * 40}
    proposed, calls = await _run_with_fix(FixProposal("proposed", target, "e" * 40, diff="+x\n"))
    assert len(proposed.texts) == 2 and "수정 제안" in proposed.texts[1] and len(calls) == 1
    for status in ("skipped", "mismatch", "failed"):
        quiet, _ = await _run_with_fix(FixProposal(status, target))
        assert len(quiet.texts) == 1
    assert "fix_proposal" in caplog.text and "status=mismatch" in caplog.text


async def test_slow_fix_proposal_times_out_and_the_analysis_still_went_out(monkeypatch):
    import asyncio
    from analyzer import worker as worker_mod

    monkeypatch.setattr(worker_mod, "FIX_TIMEOUT_S", 0.05)

    async def analyze(_):
        return verdict()

    async def slow_fix(v, b):
        await asyncio.sleep(1)

    notifier = FakeNotifier()
    await process(
        job(), settings=LIVE_SETTINGS, budget=DailyBudget(5), gather_fn=gather_secret, now=NOW,
        live=Live(analyze=analyze, notifier=notifier, fix=slow_fix),
    )
    assert len(notifier.texts) == 1


async def _run_with_verify(proposal, verify, *, thread="1795782729.655779"):
    async def analyze(_):
        return verdict()

    async def fix(v, b):
        return proposal

    notifier = FakeNotifier()
    notifier.thread = thread
    await process(
        job(), settings=LIVE_SETTINGS, budget=DailyBudget(5), gather_fn=gather_secret, now=NOW,
        live=Live(analyze=analyze, notifier=notifier, fix=fix, verify=verify),
    )
    return notifier


async def test_verify_dispatches_for_a_proposal_and_adds_one_line(caplog):
    import logging
    from analyzer.fix import Edit, FixProposal
    from analyzer.verify import VerifyResult
    caplog.set_level(logging.INFO, logger="analyzer.worker")
    calls = []

    async def verify(p, ts, label):
        calls.append((p.status, ts, label))
        return VerifyResult(True, url="https://github.com/o/r/actions/runs/1")

    p = FixProposal("proposed", {"symbol": "Svc#ok", "revision": "d" * 40}, "e" * 40, diff="+x\n",
                    edits=[Edit("a\n", "b\n")], repo_path="src/main/java/a/Svc.java")
    n = await _run_with_verify(p, verify)
    assert calls and calls[0][0] == "proposed" and calls[0][1] == "1795782729.655779"
    assert calls[0][2].endswith(" · Svc#ok")  # result reply stands alone
    assert len(n.texts) == 2 and "테스트 요청함" in n.texts[1]
    assert "fix_verify" in caplog.text and "dispatched=True" in caplog.text


async def test_verify_not_called_for_other_statuses_or_without_thread():
    from analyzer.fix import FixProposal
    from analyzer.verify import VerifyResult
    calls = []

    async def verify(p, ts, label):
        calls.append(p)
        return VerifyResult(True, url="u")

    await _run_with_verify(FixProposal("main_changed", {"symbol": "s", "revision": "d" * 40}, "e" * 40), verify)
    assert calls == []
    n = await _run_with_verify(FixProposal("proposed", {"symbol": "s", "revision": "d" * 40}, "e" * 40, diff="+x\n"),
                               verify, thread=None)
    assert calls == [] and "테스트 요청 실패(스레드 못 찾음)" in n.texts[1]


async def test_verify_failure_never_blocks_the_proposal():
    from analyzer.fix import FixProposal

    async def boom(p, ts, label):
        raise RuntimeError("x")

    n = await _run_with_verify(FixProposal("proposed", {"symbol": "s", "revision": "d" * 40}, "e" * 40, diff="+x\n"), boom)
    assert len(n.texts) == 2 and "수정 제안" in n.texts[1] and "테스트 요청 실패(RuntimeError)" in n.texts[1]


async def test_verify_timeout_only_changes_the_line(monkeypatch):
    import asyncio
    from analyzer import worker as worker_mod
    from analyzer.fix import FixProposal
    monkeypatch.setattr(worker_mod, "VERIFY_TIMEOUT_S", 0.05)

    async def slow(p, ts, label):
        await asyncio.sleep(1)

    n = await _run_with_verify(FixProposal("proposed", {"symbol": "s", "revision": "d" * 40}, "e" * 40, diff="+x\n"), slow)
    assert len(n.texts) == 2 and "테스트 요청 실패(시간 초과)" in n.texts[1]
