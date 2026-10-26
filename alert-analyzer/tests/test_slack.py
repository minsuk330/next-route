from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from urllib.parse import parse_qs, urlsplit

import httpx2 as httpx
import pytest

from analyzer.context import ContextBundle
from analyzer.llm import Hypothesis, Verdict
from analyzer.payload import AnalysisJob, GrafanaAlert
from analyzer.slack import (
    MAX_TEXT,
    SlackClient,
    SlackError,
    escape,
    explore_url,
    render,
    render_failure,
)
from tests.conftest import FIXTURES

pytestmark = pytest.mark.anyio

STARTS = datetime(2026, 10, 26, 9, 20, 20, tzinfo=timezone.utc)
FP = "140515ca470d0b8b"
LINK = "https://grafana.example/explore?a=1&b=2"


def incident(name: str) -> ContextBundle:
    data = json.loads((FIXTURES / "incidents" / f"{name}.json").read_text(encoding="utf-8"))
    return ContextBundle.from_dict(data["bundle"])


def verdict(**overrides) -> Verdict:
    base = Verdict(
        headline="서울 지하철 도착 API 장애",
        hypotheses=[
            Hypothesis(
                cause="도착 API 가 503 을 돌려줌",
                category="external_api",
                confidence="high",
                evidence=["t:0366fb74", "m:client_fail_10m"],
                next_check="열린데이터 공지 확인",
            )
        ],
        deploy_related=False,
        unknown=False,
        invalid_evidence=1,
        total_evidence=3,
        model="claude-sonnet-5-5",
        input_tokens=3100,
        output_tokens=420,
        elapsed_s=12.34,
    )
    return replace(base, **overrides)


def job() -> AnalysisJob:
    alert = GrafanaAlert(status="firing", labels={"alertname": "ERROR 로그 급증"}, startsAt=STARTS, fingerprint=FP)
    return AnalysisJob(group_key="g", alerts=(alert,), received_at=STARTS)


class FakeSlack:
    """Answers conversations.history from a list of pages, records every call."""

    def __init__(self, pages: list[list[dict]], post_body: dict | None = None):
        self.pages = pages
        self.post_body = post_body or {"ok": True, "ts": "1761470500.000200"}
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if request.url.path.endswith("conversations.history"):
            page = self.pages.pop(0) if self.pages else []
            return httpx.Response(200, json={"ok": True, "messages": page})
        return httpx.Response(200, json=self.post_body)

    def posts(self) -> list[dict]:
        return [json.loads(r.content) for r in self.requests if r.url.path.endswith("chat.postMessage")]

    def histories(self) -> list[httpx.Request]:
        return [r for r in self.requests if r.url.path.endswith("conversations.history")]


def slack(fake: Callable[[httpx.Request], httpx.Response], sleeps: list | None = None) -> SlackClient:
    async def sleep(seconds: float) -> None:
        if sleeps is not None:
            sleeps.append(seconds)

    http = httpx.AsyncClient(transport=httpx.MockTransport(fake))
    return SlackClient("xoxb-test-token", "C0123", http, sleep=sleep)


ALERT_MESSAGE = {
    "ts": "1761470430.000100",
    "bot_id": "B1",
    "attachments": [{"title": "[FIRING:1] ERROR 로그 급증", "text": f"**Firing** ...\nfp: {FP} "}],
}
OTHER_MESSAGE = {"ts": "1761470440.000100", "text": "unrelated"}


def test_escape_neutralises_mentions_and_links():
    assert escape("<!channel> a&b <https://x|y>") == "&lt;!channel&gt; a&amp;b &lt;https://x|y&gt;"


def test_render_escapes_everything_from_logs_and_model():
    v = verdict(headline="<!channel> 장애", hypotheses=[verdict().hypotheses[0].model_copy(update={"cause": "a<b>&c"})])

    text = render(v, incident("subway-api-503"), link=LINK)

    assert "<!channel>" not in text
    assert "&lt;!channel&gt; 장애" in text
    assert "a&lt;b&gt;&amp;c" in text
    assert "<https://grafana.example/explore?a=1&amp;b=2|로그 보기 (Grafana Explore)>" in text


def test_render_explains_each_evidence_id():
    text = render(verdict(), incident("subway-api-503"), link=LINK)

    assert "`t:0366fb74` worker ERROR SeoulSubwayApiAdapter NEW 16건(직전 60분 0)" in text
    assert "`m:client_fail_10m` swopenAPI.seoul.go.kr/503 23 (30분 전 없음)" in text
    assert "없는 근거 1/3개 제거" in text
    assert "확신 높음" in text


def test_render_marks_restart_and_unknown():
    bundle = incident("api-5xx-deploy")

    text = render(verdict(deploy_related=True, unknown=True, hypotheses=[]), bundle, link=LINK)

    assert ":recycle: 직전 재시작: otp:9404 12:22:20, api:8081 12:23:24" in text
    assert "원인 가설이 없음" in text


def test_render_never_exceeds_slack_limit():
    long = verdict().hypotheses[0].model_copy(update={"cause": "&" * 5000})

    text = render(verdict(hypotheses=[long]), incident("subway-api-503"), link=LINK)

    assert len(text) <= MAX_TEXT
    assert text.endswith("토큰 · 12.3초 · 없는 근거 1/3개 제거_")
    assert "&amp…" not in text and "&am…" not in text


def test_render_failure_is_escaped():
    assert render_failure("HTTP <500>", link=LINK).startswith(":warning: AI 원인 분석 실패: HTTP &lt;500&gt;")


def test_explore_url_points_at_alert_window():
    bundle = incident("subway-api-503")

    url = urlsplit(explore_url("https://grafana.example/", bundle))
    pane = json.loads(parse_qs(url.query)["panes"][0])["a"]

    assert url.path == "/explore"
    assert pane["queries"][0]["expr"] == '{app=~"worker", level=~"WARN|ERROR"}'
    assert pane["range"] == {
        "from": str(int(bundle.window_start.timestamp() * 1000)),
        "to": str(int(bundle.window_end.timestamp() * 1000)),
    }


async def test_reply_goes_into_the_alert_thread():
    fake = FakeSlack([[OTHER_MESSAGE, ALERT_MESSAGE]])

    threaded = await slack(fake).reply(job(), "분석")

    assert threaded is True
    history = fake.histories()[0]
    assert history.headers["authorization"] == "Bearer xoxb-test-token"
    assert history.url.params["channel"] == "C0123"
    assert history.url.params["limit"] == "15"
    assert history.url.params["oldest"] == f"{(STARTS - timedelta(seconds=120)).timestamp():.6f}"
    assert fake.posts() == [
        {
            "channel": "C0123",
            "text": "분석",
            "unfurl_links": False,
            "unfurl_media": False,
            "thread_ts": ALERT_MESSAGE["ts"],
        }
    ]


async def test_reply_waits_for_a_late_alert_message():
    sleeps: list = []
    fake = FakeSlack([[OTHER_MESSAGE], [ALERT_MESSAGE, OTHER_MESSAGE]])

    assert await slack(fake, sleeps).reply(job(), "분석") is True

    assert sleeps == [10.0]
    assert fake.posts()[0]["thread_ts"] == ALERT_MESSAGE["ts"]


async def test_reply_falls_back_to_a_standalone_message():
    sleeps: list = []
    fake = FakeSlack([[OTHER_MESSAGE], [], []])

    assert await slack(fake, sleeps).reply(job(), "분석") is False

    assert len(fake.histories()) == 3
    assert sleeps == [10.0, 10.0]
    assert "thread_ts" not in fake.posts()[0]


async def test_slack_error_names_the_method_not_the_token():
    fake = FakeSlack([[ALERT_MESSAGE]], post_body={"ok": False, "error": "not_in_channel"})

    with pytest.raises(SlackError, match="chat.postMessage: not_in_channel") as caught:
        await slack(fake).reply(job(), "분석")

    assert "xoxb" not in str(caught.value)


async def test_http_failure_is_a_slack_error():
    def down(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(SlackError, match="conversations.history: ConnectError"):
        await slack(down).reply(job(), "분석")
