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
from datetime import datetime, timedelta
from urllib.parse import quote

import httpx2 as httpx

from analyzer.context import ContextBundle, MetricPoint
from analyzer.dedupe import KST
from analyzer.llm import FixCandidate, Hypothesis, Verdict
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
MAX_TEXT = 3_000
TEMPLATE_CHARS = 120
METRIC_POINTS = 3

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


def render(verdict: Verdict, bundle: ContextBundle, *, link: str) -> str:
    lines = [":mag: *AI 원인 분석* _(참고용 — 틀릴 수 있음)_", f"*{escape(verdict.headline)}*"]
    if verdict.deploy_related:
        lines.append(f":recycle: 직전 재시작: {escape(_restarts(bundle))}")
    for n, hypothesis in enumerate(verdict.hypotheses, 1):
        lines.extend(_hypothesis(n, hypothesis, bundle))
    if bundle.collection_errors:
        lines.append(f":warning: 수집 실패: {escape('; '.join(bundle.collection_errors))}")

    footer = [f"<{_link(link)}|로그 보기 (Grafana Explore)>", f"_{escape(_meta(verdict))}_"]
    if verdict.fix_candidates:
        # In the footer, which _fit never cuts: long hypotheses must not push these out.
        footer = [":wrench: *고칠 곳 후보*", *(_fix(f, bundle) for f in verdict.fix_candidates), *footer]
    return _fit("\n".join(lines), "\n".join(footer))


def render_failure(reason: str, *, link: str) -> str:
    return f":warning: AI 원인 분석 실패: {escape(reason)}\n<{_link(link)}|로그 보기 (Grafana Explore)>"


def render_budget_notice(limit: int) -> str:
    return f":information_source: 오늘 AI 분석 상한({limit}건)에 도달해 자정(KST)까지 분석을 건너뜁니다."


def _hypothesis(n: int, h: Hypothesis, bundle: ContextBundle) -> list[str]:
    label = f"{CATEGORY_LABEL.get(h.category, h.category)} · 확신 {CONFIDENCE_LABEL.get(h.confidence, h.confidence)}"
    lines = [f"{n}. *[{escape(label)}]* {escape(h.cause)}"]
    lines.extend(f"    • {_describe(e, bundle)}" for e in h.evidence)
    lines.append(f"    다음 확인: {escape(h.next_check)}")
    return lines


def _fix(fix: FixCandidate, bundle: ContextBundle) -> str:
    refs = ", ".join(_ref(r, bundle) for r in fix.refs)
    return f"    • `{escape(fix.place)}` — {escape(fix.why)} ({refs})"


def _ref(ref: str, bundle: ContextBundle) -> str:
    for c in bundle.cards:
        if c.get("id") == ref:
            symptom = str(c.get("증상", ""))
            symptom = symptom if len(symptom) <= 40 else symptom[:40] + "…"
            return f"`{escape(ref)}` {escape(symptom)}({escape(str(c.get('날짜', ''))[5:])})"
    for c in bundle.code:
        if c.get("id") == ref:
            return f"`{escape(ref)}` {escape(c['symbol'])}"
    return f"`{escape(ref)}`"


def _describe(evidence_id: str, bundle: ContextBundle) -> str:
    code = f"`{escape(evidence_id)}`"
    if evidence_id == "m:restarts":
        return f"{code} {escape(_restarts(bundle))}"
    for c in bundle.code:
        if c.get("id") == evidence_id:
            return f"{code} {escape(c['symbol'])} L{c['start_line']}-{c['end_line']} @{escape(c['revision'][:8])}"
    for t in bundle.templates:
        if t.id == evidence_id:
            template = t.template if len(t.template) <= TEMPLATE_CHARS else t.template[:TEMPLATE_CHARS] + "…"
            return (
                f"{code} {escape(t.app)} {escape(t.level)} {escape(t.logger.rsplit('.', 1)[-1])} "
                f"{t.status} {t.logger_total}건(직전 60분 {t.logger_baseline}) — {escape(template)}"
            )
    points = [m for m in bundle.metrics if m.key == evidence_id]
    shown = ", ".join(_point(m) for m in points[:METRIC_POINTS])
    more = f" 외 {len(points) - METRIC_POINTS}개" if len(points) > METRIC_POINTS else ""
    return f"{code} {escape(shown)}{more}"


def _point(m: MetricPoint) -> str:
    where = m.labels.get("instance") or "/".join(v for k, v in sorted(m.labels.items()) if k in ("client_name", "status"))
    return f"{where or '-'} {_num(m.at_alert)} (30분 전 {_num(m.before_30m)})"


def _restarts(bundle: ContextBundle) -> str:
    return ", ".join(f"{r.instance} {r.started_at.astimezone(KST):%H:%M:%S}" for r in bundle.restarts)


def _meta(v: Verdict) -> str:
    parts = [v.model, f"입력 {v.input_tokens:,} / 출력 {v.output_tokens:,} 토큰", f"{v.elapsed_s:.1f}초"]
    if v.invalid_evidence:
        parts.append(f"없는 근거 {v.invalid_evidence}/{v.total_evidence}개 제거")
    return " · ".join(parts)


def _fit(body: str, footer: str) -> str:
    room = MAX_TEXT - len(footer) - 2
    if len(body) > room:
        # Do not leave half an escape sequence like "&am" behind.
        body = re.sub(r"&[a-z]*$", "", body[: room - 1]) + "…"
    return f"{body}\n{footer}"


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

    async def reply(self, job: AnalysisJob, text: str) -> bool:
        """Post into the alert's thread if it can be found. True when threaded."""
        fingerprints = {a.fingerprint for a in job.alerts if a.fingerprint}
        oldest = min(a.startsAt for a in job.alerts) - LOOKBACK
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
        await self.post(text, thread_ts)
        return thread_ts is not None

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

    async def post(self, text: str, thread_ts: str | None = None) -> None:
        payload = {"channel": self._channel, "text": text, "unfurl_links": False, "unfurl_media": False}
        if thread_ts:
            payload["thread_ts"] = thread_ts
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
            raise SlackError(f"slack {method}: {body.get('error', 'unknown error')}")
        return body
