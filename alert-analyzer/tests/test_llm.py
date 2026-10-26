from __future__ import annotations

import json
from collections.abc import Callable

import anthropic
import httpx2 as httpx
import pytest

from analyzer.context import ContextBundle
from analyzer.llm import (
    FALLBACK_BETA,
    MODEL,
    SYSTEM_PROMPT,
    Analysis,
    Analyzer,
    Hypothesis,
    LlmFailure,
    check_evidence,
)
from tests.conftest import FIXTURES

pytestmark = pytest.mark.anyio

SUBWAY_ERROR = "t:0366fb74"  # worker ERROR SeoulSubwayApiAdapter, NEW, in subway-api-503


def incident(name: str) -> ContextBundle:
    data = json.loads((FIXTURES / "incidents" / f"{name}.json").read_text(encoding="utf-8"))
    return ContextBundle.from_dict(data["bundle"])


def hypothesis(evidence: list[str], category: str = "external_api") -> dict:
    return {
        "cause": "서울 지하철 도착 API 가 503 을 돌려줌",
        "category": category,
        "confidence": "high",
        "evidence": evidence,
        "next_check": "같은 시각 다른 노선 호출 결과",
    }


def message(text: str | None, *, stop_reason: str = "end_turn", **extra) -> dict:
    return {
        "id": "msg_test",
        "type": "message",
        "role": "assistant",
        "model": MODEL,
        "content": [] if text is None else [{"type": "text", "text": text}],
        "stop_reason": stop_reason,
        "stop_sequence": None,
        "usage": {"input_tokens": 3100, "output_tokens": 420},
        **extra,
    }


def analyzer(handler: Callable[[httpx.Request], httpx.Response], seen: list | None = None) -> Analyzer:
    def recording(request: httpx.Request) -> httpx.Response:
        if seen is not None:
            seen.append(request)
        return handler(request)

    client = anthropic.AsyncAnthropic(
        api_key="test-key",
        max_retries=0,
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(recording)),
    )
    return Analyzer(client)


def replying(body: dict, status: int = 200) -> Callable[[httpx.Request], httpx.Response]:
    return lambda request: httpx.Response(status, json=body, headers={"request-id": "req_test"})


def answer(headline: str, hypotheses: list[dict]) -> str:
    return json.dumps({"headline": headline, "hypotheses": hypotheses}, ensure_ascii=False)


def test_check_evidence_drops_invented_ids_then_empty_hypotheses():
    analysis = Analysis(
        headline="x",
        hypotheses=[
            Hypothesis(**hypothesis([SUBWAY_ERROR, "t:deadbeef"])),
            Hypothesis(**hypothesis(["t:cafebabe"], category="app_bug")),
        ],
    )

    kept, invalid, total = check_evidence(analysis, {SUBWAY_ERROR, "m:up"})

    assert [h.evidence for h in kept] == [[SUBWAY_ERROR]]
    assert (invalid, total) == (2, 3)


def test_check_evidence_keeps_at_most_three_and_dedupes():
    analysis = Analysis(headline="x", hypotheses=[Hypothesis(**hypothesis(["m:up", " m:up"])) for _ in range(5)])

    kept, invalid, total = check_evidence(analysis, {"m:up"})

    assert len(kept) == 3
    assert kept[0].evidence == ["m:up"]
    assert (invalid, total) == (0, 10)


async def test_request_follows_contract():
    seen: list = []
    bundle = incident("subway-api-503")
    body = message(answer("서울 지하철 도착 API 장애", [hypothesis([SUBWAY_ERROR, "m:client_fail_10m"])]))

    verdict = await analyzer(replying(body), seen).analyze(bundle)

    request = seen[0]
    sent = json.loads(request.content)
    assert request.url.path == "/v1/messages"
    assert FALLBACK_BETA in request.headers["anthropic-beta"]
    assert sent["model"] == "claude-sonnet-5-5"
    assert sent["fallbacks"] == "default"
    assert sent["output_config"]["effort"] == "medium"
    assert sent["output_config"]["format"]["type"] == "json_schema"
    assert sent["system"] == SYSTEM_PROMPT
    user = sent["messages"][0]["content"]
    assert bundle.to_json() in user
    assert SUBWAY_ERROR in user.rsplit("근거로 쓸 수 있는 id:", 1)[1]

    assert verdict.headline == "서울 지하철 도착 API 장애"
    assert verdict.hypotheses[0].evidence == [SUBWAY_ERROR, "m:client_fail_10m"]
    assert (verdict.unknown, verdict.deploy_related) == (False, False)
    assert (verdict.input_tokens, verdict.output_tokens) == (3100, 420)
    assert verdict.cost_usd == pytest.approx(3100 * 2e-6 + 420 * 10e-6)


async def test_deploy_flag_comes_from_restarts_not_from_model():
    body = message(answer("외부 API 문제", [hypothesis(["m:client_fail_10m"])]))

    verdict = await analyzer(replying(body)).analyze(incident("api-5xx-deploy"))

    assert verdict.deploy_related is True
    assert verdict.hypotheses[0].category == "external_api"


async def test_all_evidence_invented_means_unknown():
    body = message(answer("원인 불명", [hypothesis(["t:00000000"])]))

    verdict = await analyzer(replying(body)).analyze(incident("quiet"))

    assert verdict.unknown is True
    assert verdict.hypotheses == []
    assert (verdict.invalid_evidence, verdict.total_evidence) == (1, 1)


async def test_refusal_is_a_failure():
    body = message(None, stop_reason="refusal", stop_details={"type": "refusal", "category": "cyber", "explanation": None})

    with pytest.raises(LlmFailure, match="거절.*cyber"):
        await analyzer(replying(body)).analyze(incident("quiet"))


async def test_cut_off_json_is_a_failure():
    body = message('{"headline": "서울 지하철', stop_reason="max_tokens")

    with pytest.raises(LlmFailure, match="응답 형식 오류"):
        await analyzer(replying(body)).analyze(incident("quiet"))


async def test_timeout_is_a_failure():
    def timing_out(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("read timed out", request=request)

    with pytest.raises(LlmFailure, match="시간 초과"):
        await analyzer(timing_out).analyze(incident("quiet"))


async def test_api_error_is_a_failure_without_leaking_the_key():
    body = {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}

    with pytest.raises(LlmFailure, match="HTTP 529") as caught:
        await analyzer(replying(body, status=529)).analyze(incident("quiet"))

    assert "test-key" not in caught.value.reason
