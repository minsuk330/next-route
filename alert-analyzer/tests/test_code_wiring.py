"""How the code context is wired into gather, masking, the evidence check, Slack and settings."""

from __future__ import annotations

import json
from dataclasses import replace
from datetime import datetime, timedelta, timezone

import pytest

from analyzer.code import CodeContext, CodeSnippet
from analyzer.config import Settings
from analyzer.context import ContextBundle, TemplateStat, fit_budget, gather
from analyzer.llm import Analysis, Hypothesis, check_evidence
from analyzer.masking import mask_bundle
from analyzer.payload import AnalysisJob, GrafanaAlert
from analyzer.slack import _describe, explore_url
from tests.conftest import FakeLogs, FakeMetrics, stream, vector

pytestmark = pytest.mark.anyio

STARTS = datetime(2026, 11, 27, 5, 0, tzinfo=timezone.utc)
LOGGER = "watoo.grd.nextroute.infrastructure.adapter.out.api.otp.OtpRoutingAdapter"
SHA = "f07e1f5920bad7d789a137fbf1c18ae0c37f10bd"
# 16 library rows first: the sample keeps 15, so our frame only exists past the cut.
LIBRARY = "\n".join(f"\tat org.springframework.web.client.RestTemplate.m{i}(RestTemplate.java:{i})" for i in range(16))
STACK = (
    "org.springframework.web.client.ResourceAccessException: I/O error\n" + LIBRARY +
    "\nCaused by: java.net.SocketTimeoutException: Read timed out\n"
    f"\tat {LOGGER}.search(OtpRoutingAdapter.java:142)\n"
)


def job() -> AnalysisJob:
    alert = GrafanaAlert(
        status="firing",
        labels={"alertname": "api 5xx 발생", "uri": "/api/route/search", "instance": "api:8081"},
        annotations={"summary": "5xx"},
        startsAt=STARTS,
        fingerprint="f",
    )
    return AnalysisJob(group_key="g", alerts=(alert,), received_at=STARTS)


def logs(app: str = "api", stack: str = STACK) -> FakeLogs:
    def instant(query, at):
        if 'app="otp"' in query:
            return []
        if "[11m]" in query:
            return [vector({"app": app, "level": "ERROR", "logger_name": LOGGER}, 3)]
        return []

    def range_(query, start, end, limit):
        if end <= STARTS - timedelta(minutes=10):
            return []
        if "line_format" in query:
            return [{"stream": {}, "values": [[str(int(end.timestamp() * 1e9)), "OTP 호출 실패: Read timed out"]]}]
        return [stream([{"message": "OTP 호출 실패: Read timed out", "stack_trace": stack}], end)]

    return FakeLogs(instant=instant, range_=range_)


def snippet(n: int = 1, body: str = "  142         return client.post(body);") -> CodeSnippet:
    return CodeSnippet(
        id=f"s:{n}", revision=SHA, role="api", path="src/main/java/X.java", symbol="OtpRoutingAdapter#search",
        header="watoo.grd.nextroute.X", start_line=130, end_line=158, body=body, via="stack",
    )


class RecordingCollector:
    def __init__(self, result: CodeContext | Exception):
        self.result, self.seen = result, None

    async def __call__(self, templates, alerts, at):
        self.seen = (templates, alerts, at)
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


async def test_frames_come_from_full_stack_past_the_sample_cut():
    bundle = await gather(job(), logs(), FakeMetrics())
    t = bundle.templates[0]
    assert t.frames == [f"{LOGGER}.search(OtpRoutingAdapter.java:142)"]
    assert "OtpRoutingAdapter.java:142" not in "\n".join(t.samples)   # the sample alone would have missed it
    assert bundle.code_via == "disabled" and bundle.code == []


async def test_worker2_logs_are_collected():
    source = logs(app="worker2")
    bundle = await gather(job(), source, FakeMetrics())
    assert [t.app for t in bundle.templates] == ["worker2"]
    assert any('app=~"api|worker|worker2"' in call[1] for call in source.calls)


async def test_collector_sees_templates_and_alerts_and_its_result_lands_in_bundle():
    collector = RecordingCollector(CodeContext(snippets=[snippet()], via="stack", errors=["코드 버전 불확실(worker)"]))
    bundle = await gather(job(), logs(), FakeMetrics(), collector)
    templates, alerts, at = collector.seen
    assert templates[0].frames and alerts[0]["labels"]["uri"] == "/api/route/search" and at == STARTS
    assert bundle.code_via == "stack" and bundle.code[0]["id"] == "s:1"
    assert "s:1" in bundle.ids()
    assert "코드 버전 불확실(worker)" in bundle.collection_errors


async def test_collector_crash_is_recorded_and_analysis_goes_on():
    bundle = await gather(job(), logs(), FakeMetrics(), RecordingCollector(RuntimeError("boom")))
    assert bundle.code_via == "none" and bundle.templates
    assert any(e.startswith("code: RuntimeError") for e in bundle.collection_errors)


def test_fit_budget_never_squeezes_code_out():
    big = TemplateStat(
        id="t:1", app="api", level="ERROR", logger=LOGGER, template="x", status="NEW", logger_total=1,
        logger_baseline=0, sample_count=1, sample_capped=False, baseline_seen=False, samples=["y" * 5000] * 2,
    )
    bundle = ContextBundle(
        alerts=[], window_start=STARTS, window_end=STARTS, templates=[replace(big, id=f"t:{i}") for i in range(20)],
        quiet_summary=[], metrics=[], restarts=[], collection_errors=[],
        code=[vars(snippet(1, "z" * 20_000))], code_via="stack",
    )
    fitted = fit_budget(bundle, limit=40_000)
    assert fitted.code == bundle.code
    assert len(replace(fitted, code=[]).to_json()) <= 40_000


def test_old_bundle_json_still_loads():
    bundle = ContextBundle(
        alerts=[], window_start=STARTS, window_end=STARTS, templates=[], quiet_summary=[], metrics=[],
        restarts=[], collection_errors=[],
    )
    data = json.loads(bundle.to_json())
    for key in ("code", "code_via"):
        data.pop(key)
    loaded = ContextBundle.from_dict(data)
    assert loaded.code == [] and loaded.code_via == "disabled"


def test_code_body_masks_secrets_but_keeps_coordinates():
    body = '  10   String k = "sk-ant-abc123DEF456"; double lat = 37.554712;'
    bundle = ContextBundle(
        alerts=[], window_start=STARTS, window_end=STARTS, templates=[], quiet_summary=[], metrics=[],
        restarts=[], collection_errors=[], code=[vars(snippet(1, body))],
    )
    masked = mask_bundle(bundle).code[0]["body"]
    assert "sk-ant-" not in masked and "37.554712" in masked


def test_code_ids_pass_the_evidence_check_and_invented_ones_do_not():
    bundle = ContextBundle(
        alerts=[], window_start=STARTS, window_end=STARTS, templates=[], quiet_summary=[], metrics=[],
        restarts=[], collection_errors=[], code=[vars(snippet(1))],
    )
    h = Hypothesis(cause="c", category="app_bug", confidence="high", evidence=["s:1", "s:9"], next_check="n")
    kept, invalid, total, top = check_evidence(Analysis(headline="h", hypotheses=[h]), bundle.ids())
    assert kept[0].evidence == ["s:1"] and invalid == 1 and total == 2 and top


def test_code_masking_keeps_a_bearer_prefix_constant():
    from analyzer.masking import mask_code
    assert mask_code('private static final String BEARER = "Bearer ";') == 'private static final String BEARER = "Bearer ";'
    assert mask_code('h = "Bearer abcdefghijklmnopqrstuvwxyz0123"') == 'h = "Bearer <redacted>"'


def test_slack_describes_code_evidence_and_explores_worker2():
    bundle = ContextBundle(
        alerts=[], window_start=STARTS, window_end=STARTS, templates=[], quiet_summary=[], metrics=[],
        restarts=[], collection_errors=[], code=[vars(snippet(1))],
    )
    assert _describe("s:1", bundle) == "`s:1` OtpRoutingAdapter#search L130-158 @f07e1f59"
    assert "worker2" in explore_url("https://g", bundle).replace("%7C", "|")


def test_settings_need_a_token_when_code_context_is_on():
    base = {"ANALYZER_WEBHOOK_TOKEN": "t"}
    assert Settings.from_env(base).code_context_enabled is False
    with pytest.raises(RuntimeError, match="ANALYZER_GITHUB_TOKEN"):
        Settings.from_env({**base, "ANALYZER_CODE_CONTEXT_ENABLED": "true"})
    s = Settings.from_env({**base, "ANALYZER_CODE_CONTEXT_ENABLED": "true", "ANALYZER_GITHUB_TOKEN": "ghp_x"})
    assert s.code_context_enabled and "ghp_x" not in repr(s)


def test_fix_proposal_needs_the_code_context():
    base = {"ANALYZER_WEBHOOK_TOKEN": "t"}
    with pytest.raises(RuntimeError, match="ANALYZER_CODE_CONTEXT_ENABLED"):
        Settings.from_env({**base, "ANALYZER_FIX_PROPOSAL_ENABLED": "true"})
    s = Settings.from_env({**base, "ANALYZER_FIX_PROPOSAL_ENABLED": "true", "ANALYZER_CODE_CONTEXT_ENABLED": "true",
                           "ANALYZER_GITHUB_TOKEN": "g"})
    assert s.fix_proposal_enabled


async def test_complete_returns_text_and_cost_without_iterations():
    from types import SimpleNamespace
    from analyzer.llm import LlmFailure, complete

    class Client:
        def __init__(self, stop="end_turn"):
            self.messages = self
            self.stop = stop

        async def create(self, **kw):
            return SimpleNamespace(stop_reason=self.stop, model="claude-sonnet-5-5",
                                   content=[SimpleNamespace(type="thinking"), SimpleNamespace(type="text", text="블록")],
                                   usage=SimpleNamespace(input_tokens=1_000_000, output_tokens=0))

    text, cost = await complete(Client(), "s", "u")
    assert text == "블록" and cost == 2.0
    with pytest.raises(LlmFailure):
        await complete(Client("max_tokens"), "s", "u")
