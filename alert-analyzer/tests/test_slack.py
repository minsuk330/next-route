from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from urllib.parse import parse_qs, urlsplit

import httpx2 as httpx
import pytest

from analyzer.context import ContextBundle
from analyzer.llm import FixCandidate, Hypothesis, Verdict
from analyzer.payload import AnalysisJob, GrafanaAlert
from analyzer.slack import (
    HEADER_CHARS,
    MAX_BLOCKS,
    MAX_TEXT,
    SECTION_CHARS,
    SlackClient,
    SlackError,
    SlackMessage,
    escape,
    explore_url,
    links,
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

    msg = render(v, incident("subway-api-503"), link=LINK)
    mrkdwn = [b["text"]["text"] for b in msg.blocks if b["type"] == "section"]
    mrkdwn += [e["text"] for b in msg.blocks if b["type"] == "context" for e in b["elements"]]

    assert msg.blocks[0] == {"type": "header", "text": {"type": "plain_text", "text": "🔍 <!channel> 장애", "emoji": True}}
    assert not any("<!channel>" in t for t in mrkdwn)
    assert any("a&lt;b&gt;&amp;c" in t for t in mrkdwn)
    assert "<!channel>" not in msg.text and "&lt;!channel&gt; 장애" in msg.text
    assert msg.blocks[-1]["elements"][0] == {"type": "button", "text": {"type": "plain_text", "text": "로그 보기"},
                                             "url": LINK, "action_id": "link-0"}


def test_render_shows_evidence_in_words_without_ids_or_meta():
    msg = render(verdict(), incident("subway-api-503"), link=LINK)
    shown = msg.visible()

    assert "worker SeoulSubwayApiAdapter ERROR 16건(직전 60분 0)" in shown
    assert "swopenAPI.seoul.go.kr/503 23 (30분 전 없음)" in shown
    assert "확신 높음" in shown and "*확인*  열린데이터 공지 확인" in shown
    for hidden in ("t:0366fb74", "m:client_fail_10m", "claude-sonnet", "토큰", "12.3초", "없는 근거"):
        assert hidden not in shown, hidden


def test_same_logger_templates_merge_into_one_evidence_line():
    bundle = incident("subway-api-503")
    first = bundle.templates[0]
    twin = first.model_copy(update={"id": "t:twin", "template": "other template"}) if hasattr(first, "model_copy") \
        else replace(first, id="t:twin", template="other template")
    bundle = replace(bundle, templates=[first, twin, *bundle.templates[1:]])
    h = verdict().hypotheses[0].model_copy(update={"evidence": [first.id, "t:twin", "m:client_fail_10m"]})

    shown = render(verdict(hypotheses=[h]), bundle, link=LINK).visible()

    assert f"{first.logger_total}건(직전 60분 {first.logger_baseline})" in shown and " 외 1" in shown
    assert "other template" not in shown
    assert "swopenAPI.seoul.go.kr/503" in shown  # the merge freed the second slot for the metric


def test_only_the_first_hypothesis_is_detailed():
    first = verdict().hypotheses[0]
    second = first.model_copy(update={"cause": "둘째 원인 " + "x" * 300, "confidence": "low", "next_check": "둘째 확인"})

    msg = render(verdict(hypotheses=[first, second]), incident("subway-api-503"), link=LINK)
    contexts = [e["text"] for b in msg.blocks if b["type"] == "context" for e in b["elements"]]

    line = next(t for t in contexts if t.startswith("*②*"))
    assert "확신 낮음 — 둘째 원인" in line and len(line) < 200
    assert "둘째 확인" not in msg.visible()


def test_render_marks_restart():
    bundle = incident("api-5xx-deploy")

    msg = render(verdict(deploy_related=True, unknown=True, hypotheses=[]), bundle, link=LINK)

    assert "♻️ 직전 재시작 otp:9404 12:22:20, api:8081 12:23:24" in msg.blocks[1]["elements"][0]["text"]
    assert "근거가 부족함" in msg.visible()


def test_render_respects_block_kit_limits():
    long = verdict().hypotheses[0].model_copy(update={"cause": "&" * 5000})
    msg = render(verdict(headline="h" * 400, hypotheses=[long] * 20), incident("subway-api-503"), link=LINK)

    assert len(msg.blocks) <= MAX_BLOCKS
    assert len(msg.blocks[0]["text"]["text"]) <= HEADER_CHARS
    for b in msg.blocks:
        if b["type"] == "section":
            assert 1 <= len(b["text"]["text"]) <= SECTION_CHARS
            assert not b["text"]["text"].endswith(("&amp…", "&am…"))
    assert len(msg.text) <= MAX_TEXT


def test_code_button_links_the_snippet_and_is_skipped_without_repo_or_path():
    bundle = replace(incident("subway-api-503"), code=[{"id": "s:1", "symbol": "A#b", "path": "src/main/java/A.java",
                                                      "revision": "f" * 40, "start_line": 3, "end_line": 9}])
    fix = FixCandidate(place="A#b", why="w", refs=["s:1"])

    msg = render(verdict(fix_candidates=[fix]), bundle, link=LINK, repo="o/r")

    buttons = msg.blocks[-1]["elements"]
    assert buttons[1]["url"] == f"https://github.com/o/r/blob/{'f' * 40}/src/main/java/A.java#L3-L9"
    assert len(render(verdict(fix_candidates=[fix]), bundle, link=LINK).blocks[-1]["elements"]) == 1
    no_path = replace(bundle, code=[{"id": "s:1", "symbol": "A#b", "revision": "f" * 40}])
    assert len(render(verdict(fix_candidates=[fix]), no_path, link=LINK, repo="o/r").blocks[-1]["elements"]) == 1


def test_links_skip_empty_or_non_https_urls():
    assert links([("a", ""), ("b", None), ("c", "javascript:x")]) == []
    assert links([("a", "https://x")])[0]["elements"][0]["url"] == "https://x"


def test_fallback_text_is_usable_alone():
    fix = FixCandidate(place="A#b", why="w", refs=[])
    text = render(verdict(fix_candidates=[fix]), incident("subway-api-503"), link=LINK).text
    assert "도착 API 가 503" in text and "고칠 곳: A#b" in text and "|로그 보기>" in text


def test_render_failure_is_escaped():
    msg = render_failure("HTTP <500>", link=LINK)
    assert msg.text == "⚠️ AI 원인 분석 실패: HTTP &lt;500&gt;"
    assert msg.blocks[0]["text"]["text"] == "⚠️ *AI 원인 분석 실패*  HTTP &lt;500&gt;"


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
    assert history.url.params["limit"] == "50"
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


async def test_history_failure_still_posts_a_standalone_message(caplog):
    posts: list = []

    def no_history_scope(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("conversations.history"):
            return httpx.Response(200, json={"ok": False, "error": "missing_scope"})
        posts.append(json.loads(request.content))
        return httpx.Response(200, json={"ok": True})

    assert await slack(no_history_scope).reply(job(), "분석") is False

    assert len(posts) == 1 and "thread_ts" not in posts[0]
    assert "conversations.history: missing_scope" in caplog.text


async def test_http_failure_is_a_slack_error():
    def down(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(SlackError, match="chat.postMessage: ConnectError"):
        await slack(down).reply(job(), "분석")


async def test_non_json_answer_is_a_slack_error():
    def html(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="<html>maintenance</html>")

    with pytest.raises(SlackError, match="chat.postMessage: response is not JSON"):
        await slack(html).reply(job(), "분석")


async def test_thread_lookup_is_remembered_per_job_including_a_miss():
    sleeps: list = []
    found = FakeSlack([[ALERT_MESSAGE]])
    client = slack(found, sleeps)
    await client.reply(job(), "분석")
    assert await client.thread_of(job()) == ALERT_MESSAGE["ts"]
    await client.reply(job(), "수정 제안")
    assert len(found.histories()) == 1  # analysis, verify and proposal share one lookup

    missing = FakeSlack([[OTHER_MESSAGE], [], []])
    client = slack(missing, sleeps)
    assert await client.thread_of(job()) is None
    assert await client.thread_of(job()) is None
    assert len(missing.histories()) == 3  # one round of retries, not two


async def test_blocks_go_out_with_the_fallback_text():
    fake = FakeSlack([[ALERT_MESSAGE]])
    msg = SlackMessage("대체", [{"type": "section", "text": {"type": "mrkdwn", "text": "본문"}}])

    await slack(fake).reply(job(), msg)

    assert fake.posts()[0]["text"] == "대체" and fake.posts()[0]["blocks"] == msg.blocks


async def test_rejected_blocks_fall_back_to_text_once(caplog):
    posts: list = []

    def picky(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("conversations.history"):
            return httpx.Response(200, json={"ok": True, "messages": [ALERT_MESSAGE]})
        body = json.loads(request.content)
        posts.append(body)
        return httpx.Response(200, json={"ok": "blocks" not in body, "error": "invalid_blocks"})

    msg = SlackMessage("대체", [{"type": "section", "text": {"type": "mrkdwn", "text": ""}}])
    assert await slack(picky).reply(job(), msg) is True

    assert len(posts) == 2 and "blocks" in posts[0] and "blocks" not in posts[1]
    assert posts[1]["text"] == "대체" and posts[1]["thread_ts"] == ALERT_MESSAGE["ts"]
    assert "blocks rejected" in caplog.text


async def test_transport_failure_is_not_retried_as_text():
    calls: list = []

    def down(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(SlackError):
        await slack(down).post(SlackMessage("t", [{"type": "divider"}]))
    assert len(calls) == 1


async def test_new_grafana_alert_message_is_found_by_fingerprint():
    # Shape of a Grafana 12.0.0 slack notification with templates.yaml (local receivers/test, 2026-11-28).
    new = {"ts": "1761470431.000100", "attachments": [{"title": "🟡 ERROR 로그 급증 · worker2", "title_link": "https://g",
           "text": f"worker2 ERROR 로그 80건 (5분)\n<https://g|규칙 보기> · <https://g/s|끄기>\n채점 ▸ ✅\nfp: {FP} ",
           "fallback": "🟡 ERROR 로그 급증 · worker2", "footer": "Grafana v12.0.0"}]}
    fake = FakeSlack([[OTHER_MESSAGE, new]])

    assert await slack(fake).reply(job(), "분석") is True
    assert fake.posts()[0]["thread_ts"] == new["ts"]
