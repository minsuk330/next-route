"""Post the analysis as a reply in the Slack thread of the alert it explains.

The Grafana alert message carries an "fp: <fingerprint>" line (PR-3), so the
thread is found by looking for that fingerprint among the channel's recent
messages. If it is not there after a few tries, the reply goes out as its own
message rather than being lost.

Everything that came from logs or the model is escaped before it is posted:
a log line like "<!channel>" must not ping anyone.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from urllib.parse import quote

import httpx2 as httpx

from analyzer.context import ContextBundle, MetricPoint
from analyzer.dedupe import KST
from analyzer.llm import Verdict
from analyzer.payload import AnalysisJob

log = logging.getLogger(__name__)

SLACK_API = "https://slack.com/api"
TIMEOUT_S = 10.0
# One page. Internal (non-Marketplace) apps may ask for up to 999; 50 covers an alert storm.
HISTORY_LIMIT = 50
# The alert message is posted after startsAt (group_wait); look a little earlier too.
LOOKBACK = timedelta(seconds=120)
FIND_ATTEMPTS = 3
FIND_INTERVAL_S = 10.0
THREAD_CACHE_MAX = 64
MAX_TEXT = 3_000

CATEGORY_LABEL = {
    "deploy_restart": "배포/재시작",
    "external_api": "외부 API",
    "resource_exhaustion": "자원 고갈",
    "app_bug": "앱 결함",
    "infra": "인프라",
}
CONFIDENCE_LABEL = {"high": "높음", "medium": "중간", "low": "낮음"}


class SlackError(Exception):
    pass


class SlackApiError(SlackError):
    """Slack answered ok=false: nothing was posted."""


def escape(text: str) -> str:
    """Slack mrkdwn control characters. Nothing else needs escaping."""
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


# --- rendering -----------------------------------------------------------------


def explore_url(grafana_url: str, bundle: ContextBundle) -> str:
    """Grafana Explore on the alert window, WARN/ERROR of the apps involved."""
    apps = sorted({t.app for t in bundle.templates if t.app in ("api", "worker", "worker2")}) or ["api", "worker", "worker2"]
    expr = f'{{app=~"{"|".join(apps)}", level=~"WARN|ERROR"}}'
    pane = {
        "datasource": "loki",
        "queries": [{"refId": "A", "expr": expr, "datasource": {"type": "loki", "uid": "loki"}}],
        "range": {"from": _ms(bundle.window_start), "to": _ms(bundle.window_end)},
    }
    panes = quote(json.dumps({"a": pane}, separators=(",", ":")), safe="")
    return f"{grafana_url.rstrip('/')}/explore?schemaVersion=1&panes={panes}&orgId=1"


# Block Kit limits (docs.slack.dev): header plain_text 150, section/context text 3000 (min 1),
# button text 75, button url 3000, 50 blocks per message.
HEADER_CHARS = 150
SECTION_CHARS = 3_000
CONTEXT_CHARS = 2_000
BUTTON_CHARS = 75
URL_CHARS = 3_000
MAX_BLOCKS = 50
BRIEF_TEMPLATE_CHARS = 50
OTHER_CAUSE_CHARS = 120
EVIDENCE_SHOWN = 2
FIXES_SHOWN = 2
CIRCLED = "①②③④⑤⑥⑦⑧⑨"
# URL buttons open the link with no interactivity endpoint. If Slack ever shows a warning on click,
# flip this: links become plain "<url|label>" text in a context block instead.
LINKS_AS_BUTTONS = True


@dataclass(frozen=True)
class SlackMessage:
    """One chat.postMessage: blocks for the channel, text for push notifications, search, and the
    plain retry when Slack rejects the blocks."""

    text: str
    blocks: list[dict] = field(default_factory=list)

    def visible(self) -> str:
        """Every string a reader can see (tests, logs)."""
        return json.dumps(self.blocks, ensure_ascii=False) + "\n" + self.text


def _cut(text: str, limit: int) -> str:
    if len(text) <= limit:
        return text
    # Do not leave half an escape sequence like "&am" behind.
    return re.sub(r"&[a-z]*$", "", text[: limit - 1]) + "…"


def header(text: str) -> dict:
    # plain_text: Slack does not parse mentions or links here, so it is shown as written.
    return {"type": "header", "text": {"type": "plain_text", "text": _cut(text, HEADER_CHARS) or "-", "emoji": True}}


def section(mrkdwn: str) -> dict:
    return {"type": "section", "text": {"type": "mrkdwn", "text": _cut(mrkdwn, SECTION_CHARS) or "-"}}


def context(mrkdwn: str) -> dict:
    return {"type": "context", "elements": [{"type": "mrkdwn", "text": _cut(mrkdwn, CONTEXT_CHARS) or "-"}]}


def divider() -> dict:
    return {"type": "divider"}


def links(pairs: Iterable[tuple[str, str | None]]) -> list[dict]:
    """Buttons (or link text) for the https URLs among pairs; an empty or odd URL gets no button."""
    good = [(label, url) for label, url in pairs if url and url.startswith("https://") and len(url) <= URL_CHARS]
    if not good:
        return []
    if not LINKS_AS_BUTTONS:
        return [context(" · ".join(f"<{_link(url)}|{escape(label)}>" for label, url in good))]
    return [{
        "type": "actions",
        "elements": [
            {"type": "button", "text": {"type": "plain_text", "text": label[:BUTTON_CHARS]}, "url": url,
             "action_id": f"link-{i}"}
            for i, (label, url) in enumerate(good)
        ],
    }]


def message(text: str, blocks: list[dict]) -> SlackMessage:
    return SlackMessage(_cut(text, MAX_TEXT), blocks[:MAX_BLOCKS])


def code_span(text: str) -> str:
    """Inside `...`: a backtick from the model would close the span."""
    clean = text.replace("`", "'")
    return f"`{escape(clean)}`"


def render(verdict: Verdict, bundle: ContextBundle, *, link: str, repo: str | None = None) -> SlackMessage:
    note = "AI 분석 · 참고용, 틀릴 수 있음"
    if verdict.deploy_related and bundle.restarts:
        note += f" · ♻️ 직전 재시작 {escape(_restarts(bundle))}"
    blocks = [header(f"🔍 {verdict.headline}"), context(note)]
    for n, h in enumerate(verdict.hypotheses, 1):
        label = escape(f"{CATEGORY_LABEL.get(h.category, h.category)} · 확신 {CONFIDENCE_LABEL.get(h.confidence, h.confidence)}")
        mark = CIRCLED[n - 1] if n <= len(CIRCLED) else f"{n}."
        if n == 1:
            blocks.append(section(f"*{mark} {label}*\n{escape(h.cause)}"))
            briefs = _briefs(h.evidence, bundle)
            if briefs:
                blocks.append(context("근거  " + " · ".join(briefs)))
            if h.next_check:
                blocks.append(section(f"*확인*  {escape(h.next_check)}"))
        else:
            blocks.append(context(f"*{mark}* {label} — {escape(_cut(h.cause, OTHER_CAUSE_CHARS))}"))
    if not verdict.hypotheses:
        blocks.append(section("원인을 좁힐 근거가 부족함 — 로그를 직접 확인"))
    if bundle.collection_errors:
        blocks.append(context(f"⚠️ 수집 실패: {escape('; '.join(bundle.collection_errors))}"))
    if verdict.fix_candidates:
        blocks.append(divider())
        for fix in verdict.fix_candidates[:FIXES_SHOWN]:
            blocks.append(section(f"🔧 *고칠 곳*  {code_span(fix.place)}\n{escape(fix.why)}"))
        cards = [c for c in (_card(r, bundle) for f in verdict.fix_candidates[:FIXES_SHOWN] for r in f.refs) if c]
        if cards:
            blocks.append(context("비슷한 장애  " + " · ".join(dict.fromkeys(cards))))
    blocks += links([("로그 보기", link), ("코드 보기", _code_url(verdict, bundle, repo))])
    return message(_fallback(verdict, link), blocks)


def _fallback(verdict: Verdict, link: str) -> str:
    """Shown in push notifications, and posted alone if Slack rejects the blocks: keep it usable."""
    lines = [f"🔍 AI 분석(참고용): {escape(verdict.headline)}"]
    if verdict.hypotheses:
        h = verdict.hypotheses[0]
        lines += [escape(h.cause), f"확인: {escape(h.next_check)}"]
    lines += [f"고칠 곳: {escape(f.place)} — {escape(f.why)}" for f in verdict.fix_candidates[:FIXES_SHOWN]]
    if link.startswith("https://"):
        lines.append(f"<{_link(link)}|로그 보기>")
    return "\n".join(lines)


def render_failure(reason: str, *, link: str) -> SlackMessage:
    return message(
        f"⚠️ AI 원인 분석 실패: {escape(reason)}",
        [section(f"⚠️ *AI 원인 분석 실패*  {escape(reason)}"), *links([("로그 보기", link)])],
    )


def render_fix(p, verify=None) -> SlackMessage | None:
    """Second thread reply for analyzer.fix. None for statuses that stay in the logs.

    verify: analyzer.verify.VerifyResult when stage 6 ran — one line at the end of a proposal.
    """
    t = p.target or {}
    symbol = code_span(t.get("symbol", "?"))
    where = f"배포 {escape(t.get('revision', '')[:8])} · main {escape((p.main_sha or '')[:8])}"
    if p.status == "proposed":
        fence = "```\n\n```"
        diff = escape(p.diff)
        room = SECTION_CHARS - len(fence) - len("\n… 생략")
        if len(diff) > room:
            diff = re.sub(r"&[a-z]*$", "", diff[:room]) + "\n… 생략"
        blocks = [
            section(f"🛠 *수정 제안*  {symbol}"),
            context(f"{where} · 참고용, 사람이 검토 후 적용"),
            section(f"```\n{diff}\n```"),
        ]
        # The text alone must still carry the change if Slack rejects the blocks.
        text = f"🛠 수정 제안: {escape(t.get('symbol', '?'))} ({where})\n```\n{_cut(escape(p.diff), MAX_TEXT - 400)}\n```"
        if verify is not None and verify.dispatched:
            blocks += [context("⏳ 테스트 요청함 — 결과는 이 스레드에 답글로"), *links([("진행 보기", verify.url)])]
            text += f"\n테스트 요청함 · <{_link(verify.url)}|진행 보기>" if verify.url.startswith("https://") else "\n테스트 요청함"
        elif verify is not None:
            blocks.append(context(f"⚠️ 테스트 요청 실패({escape(verify.reason)})"))
            text += f"\n테스트 요청 실패({escape(verify.reason)})"
        return message(text, blocks)
    if p.status == "main_changed":
        commits = "\n".join(f"• {escape(c)}" for c in p.main_commits) or "• (바꾼 커밋 목록을 못 가져옴)"
        return message(
            f"⚠️ 수정 제안 보류: {escape(t.get('symbol', '?'))} — 배포 뒤 main 에서 이 파일이 바뀜",
            [
                section(f"⚠️ *수정 제안 보류*  {symbol}\n배포 뒤 main 에서 이 파일이 바뀌었다. 이미 고쳐졌는지 먼저 확인"),
                context(where),
                section(commits),
            ],
        )
    return None


def render_budget_notice(limit: int) -> SlackMessage:
    text = f"ℹ️ 오늘 AI 분석 상한({limit}건)에 도달해 자정(KST)까지 분석을 건너뜁니다."
    return message(text, [context(text)])


def _briefs(evidence: Iterable[str], bundle: ContextBundle) -> list[str]:
    """Evidence in words: no ids, commits or line numbers. Log templates of one logger merge into one."""
    out: list[str] = []
    seen_loggers: dict[tuple, int] = {}
    for evidence_id in evidence:
        if evidence_id == "m:restarts":
            out.append(f"재시작 {escape(_restarts(bundle))}")
            continue
        code = next((c for c in bundle.code if c.get("id") == evidence_id), None)
        if code is not None:
            out.append(code_span(str(code.get("symbol", "?"))))
            continue
        tmpl = next((t for t in bundle.templates if t.id == evidence_id), None)
        if tmpl is not None:
            key = (tmpl.app, tmpl.level, tmpl.logger)
            if key in seen_loggers:
                i = seen_loggers[key]
                out[i] = re.sub(r"(?: 외 (\d+))?$", lambda m: f" 외 {int(m.group(1) or 0) + 1}", out[i], count=1)
                continue
            seen_loggers[key] = len(out)
            out.append(
                f"{escape(tmpl.app)} {escape(tmpl.logger.rsplit('.', 1)[-1])} {escape(tmpl.level)} "
                f"{tmpl.logger_total}건(직전 60분 {tmpl.logger_baseline}) — {escape(_cut(tmpl.template, BRIEF_TEMPLATE_CHARS))}"
            )
            continue
        points = [m for m in bundle.metrics if m.key == evidence_id]
        if points:
            out.append(escape(", ".join(_point(m) for m in points[:EVIDENCE_SHOWN])))
    return out[:EVIDENCE_SHOWN]


def _card(ref: str, bundle: ContextBundle) -> str | None:
    for c in bundle.cards:
        if c.get("id") == ref:
            symptom = _cut(str(c.get("증상", "")), 40)
            return f"{escape(symptom)}({escape(str(c.get('날짜', ''))[5:])})"
    return None


def _code_url(verdict: Verdict, bundle: ContextBundle, repo: str | None) -> str | None:
    """GitHub link to the first code snippet a fix candidate (or the top hypothesis) points at."""
    if not repo:
        return None
    refs = [r for f in verdict.fix_candidates for r in f.refs]
    if verdict.hypotheses:
        refs += verdict.hypotheses[0].evidence
    for ref in refs:
        for c in bundle.code:
            if c.get("id") == ref and c.get("path") and c.get("revision"):
                return (f"https://github.com/{repo}/blob/{quote(str(c['revision']))}/{quote(str(c['path']))}"
                        f"#L{c.get('start_line', 1)}-L{c.get('end_line', c.get('start_line', 1))}")
    return None


def _point(m: MetricPoint) -> str:
    where = m.labels.get("instance") or "/".join(v for k, v in sorted(m.labels.items()) if k in ("client_name", "status"))
    return f"{where or '-'} {_num(m.at_alert)} (30분 전 {_num(m.before_30m)})"


def _restarts(bundle: ContextBundle) -> str:
    return ", ".join(f"{r.instance} {r.started_at.astimezone(KST):%H:%M:%S}" for r in bundle.restarts)


def _link(url: str) -> str:
    # Inside <url|text> only & < > matter, and the URL is already percent-encoded.
    return escape(url)


def _num(value: float | None) -> str:
    return "없음" if value is None else f"{value:g}"


def _ms(at: datetime) -> str:
    return str(int(at.timestamp() * 1000))


# --- posting -------------------------------------------------------------------


class SlackClient:
    def __init__(
        self,
        token: str,
        channel: str,
        http: httpx.AsyncClient,
        *,
        base_url: str = SLACK_API,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
        attempts: int = FIND_ATTEMPTS,
        interval_s: float = FIND_INTERVAL_S,
    ):
        self._token = token
        self._channel = channel
        self._http = http
        self._base = base_url.rstrip("/")
        self._sleep = sleep
        self._attempts = attempts
        self._interval = interval_s
        # One job asks for its thread up to three times (analysis, verify, proposal). Remember the
        # answer — including "none" — so a missing thread costs one round of retries, not three.
        self._threads: dict[tuple, str | None] = {}

    async def reply(self, job: AnalysisJob, msg: SlackMessage | str) -> bool:
        """Post into the alert's thread if it can be found. True when threaded."""
        thread_ts = await self.thread_of(job)
        await self.post(msg, thread_ts)
        return thread_ts is not None

    async def thread_of(self, job: AnalysisJob) -> str | None:
        """ts of the alert's first Slack message, or None (retries: Grafana may post after the webhook)."""
        fingerprints = {a.fingerprint for a in job.alerts if a.fingerprint}
        oldest = min(a.startsAt for a in job.alerts) - LOOKBACK
        key = (frozenset(fingerprints), oldest)
        if fingerprints and key in self._threads:
            return self._threads[key]
        thread_ts = None
        if fingerprints:
            for attempt in range(self._attempts):
                try:
                    thread_ts = await self.find_thread(fingerprints, oldest)
                except SlackError as exc:
                    # Missing history scope, rate limit, outage: still post, just not threaded.
                    log.warning("thread lookup failed (attempt %d): %s", attempt + 1, exc)
                if thread_ts or attempt == self._attempts - 1:
                    break
                await self._sleep(self._interval)
            if len(self._threads) >= THREAD_CACHE_MAX:
                self._threads.pop(next(iter(self._threads)))
            self._threads[key] = thread_ts
        return thread_ts

    async def find_thread(self, fingerprints: Iterable[str], oldest: datetime) -> str | None:
        body = await self._call(
            "GET",
            "conversations.history",
            params={"channel": self._channel, "oldest": f"{oldest.timestamp():.6f}", "limit": HISTORY_LIMIT},
        )
        # Newest first; the first notification for the alert is the oldest match.
        for message in reversed(body.get("messages", [])):
            raw = json.dumps(message, ensure_ascii=False)
            if any(fp in raw for fp in fingerprints):
                return message.get("ts")
        return None

    async def history(self, oldest: datetime, *, cursor: str | None = None, limit: int = 200) -> dict:
        """One page of channel history (newest first). Needs channels:history."""
        params = {"channel": self._channel, "oldest": f"{oldest.timestamp():.6f}", "limit": limit}
        if cursor:
            params["cursor"] = cursor
        return await self._call("GET", "conversations.history", params=params)

    async def post(self, msg: SlackMessage | str, thread_ts: str | None = None) -> None:
        msg = msg if isinstance(msg, SlackMessage) else SlackMessage(msg)
        payload = {"channel": self._channel, "text": msg.text, "unfurl_links": False, "unfurl_media": False}
        if thread_ts:
            payload["thread_ts"] = thread_ts
        if not msg.blocks:
            await self._call("POST", "chat.postMessage", json=payload)
            return
        try:
            await self._call("POST", "chat.postMessage", json={**payload, "blocks": msg.blocks})
        except SlackApiError as exc:
            # ok=false means nothing was posted (invalid_blocks, msg_too_long, ...): the reply must not
            # vanish over a layout problem, so send the plain text once.
            log.warning("blocks rejected, posting text only: %s", exc)
            await self._call("POST", "chat.postMessage", json=payload)

    async def _call(self, verb: str, method: str, *, params: dict | None = None, json: dict | None = None) -> dict:
        headers = {"Authorization": f"Bearer {self._token}"}
        if json is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
        try:
            response = await self._http.request(
                verb, f"{self._base}/{method}", params=params, json=json, headers=headers, timeout=TIMEOUT_S
            )
        except httpx.HTTPError as exc:
            raise SlackError(f"slack {method}: {type(exc).__name__}") from None
        if response.status_code != 200:
            raise SlackError(f"slack {method}: HTTP {response.status_code}")
        try:
            body = response.json()
        except ValueError:
            raise SlackError(f"slack {method}: response is not JSON") from None
        # Slack reports most failures as HTTP 200 with ok=false.
        if not body.get("ok"):
            raise SlackApiError(f"slack {method}: {body.get('error', 'unknown error')}")
        return body
