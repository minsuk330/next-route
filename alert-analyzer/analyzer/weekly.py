"""Weekly digest: one Slack message every Monday 09:00 KST (VPS cron).

    docker compose run --rm --no-deps alert-analyzer python -m analyzer.weekly [--dry-run]

What it reports (docs/plan/observability-north-star/PLAN.md, decided 2026-11-26):
- North star: on-time journey notifications (n/m), late / lost / overdue, per checkpoint;
  lock-screen ring updates. The SQL is read from the provisioned Grafana dashboard
  `nextroute-north-star`, so the digest and the dashboard can never disagree.
- Slow endpoints: 7-day p95 over target (route search 2.5 s, everything else 500 ms).
- Funnel: searches -> journeys started -> journeys notified -> completed.
- Alerts fired in the alert channel last week (Grafana Slack messages carry an `fp:` line).
- Open `observability-weekly` GitHub issues, when a token is configured.

Each section fails on its own: a broken source becomes one line in the message, the rest still goes out.
Postgres is reached through Grafana's /api/ds/query with a Viewer service-account token
(datasource `nextroute-pg`, read-only role `grafana_ro`); the analyzer itself has no DB access.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import httpx2 as httpx

from analyzer.slack import SlackClient, SlackError

KST = ZoneInfo("Asia/Seoul")
WEEK = timedelta(days=7)
TIMEOUT = httpx.Timeout(15.0)
PG = {"type": "grafana-postgresql-datasource", "uid": "nextroute-pg"}
NORTH_STAR_UID = "nextroute-north-star"

ROUTE_SEARCH_URI = "/api/route/search"
ROUTE_SEARCH_TARGET_S = 2.5
DEFAULT_TARGET_S = 0.5
MIN_REQUESTS = 20  # below this a weekly p95 is noise
SLOW_LIMIT = 8

# Toss (in-app) users are excluded from usage numbers (operator decision 2026-11-03, same as usage dashboard).
NOT_TOSS = "user_id not in (select t.user_id from auth_identity t where t.provider = 'TOSS' and t.user_id is not null)"
FUNNEL_SQL = f"""
select
  (select count(*) from journey_session
     where $__timeFilter(started_at) and deleted_at is null and {NOT_TOSS}) as starts,
  (select count(distinct n.journey_id) from journey_notification n
     join journey_session s on s.id = n.journey_id
     where n.channel = 'PUSH' and n.status = 'SENT' and $__timeFilter(s.started_at)
       and s.deleted_at is null and s.{NOT_TOSS}) as notified,
  (select count(*) from journey_session
     where $__timeFilter(started_at) and status = 'COMPLETED' and deleted_at is null and {NOT_TOSS}) as completed
"""

# "/**" = unmatched paths (404 scans), UNKNOWN = no handler; neither is ours to speed up.
API = 'job="nextroute-app", role="api", uri!~"/actuator.*|UNKNOWN", uri!="/**"'
SLOW_P95 = f"histogram_quantile(0.95, sum by (le, uri) (increase(http_server_requests_seconds_bucket{{{API}}}[7d])))"
SLOW_COUNT = f"sum by (uri) (increase(http_server_requests_seconds_count{{{API}}}[7d]))"
SEARCH_COUNT = 'sum(increase(route_search_duration_seconds_count[7d]))'

FP_LINE = re.compile(r"fp:\s*([0-9a-f ]+)")
TITLE = re.compile(r"\[(FIRING|RESOLVED)(?::\d+)?\]\s*(.+?)(?:\s+\(|$)")


class DigestError(Exception):
    pass


# --- settings -----------------------------------------------------------------


@dataclass(frozen=True)
class WeeklySettings:
    slack_bot_token: str = field(repr=False)
    slack_channel_id: str
    grafana_token: str = field(repr=False)
    grafana_api_url: str = "http://grafana:3000"
    grafana_public_url: str = "https://grafana.refit-100.site"
    prom_url: str = "http://prometheus:9090"
    github_token: str = field(default="", repr=False)
    github_repo: str = "minsuk330/next-route"

    @classmethod
    def from_env(cls, env=None, *, dry_run: bool = False) -> "WeeklySettings":
        env = os.environ if env is None else env
        need = ["GRAFANA_API_TOKEN"] + ([] if dry_run else ["SLACK_BOT_TOKEN", "SLACK_CHANNEL_ID"])
        missing = [n for n in need if not env.get(n, "").strip()]
        if missing:
            raise DigestError(f"weekly digest needs {', '.join(missing)}")
        return cls(
            slack_bot_token=env.get("SLACK_BOT_TOKEN", "").strip(),
            slack_channel_id=env.get("SLACK_CHANNEL_ID", "").strip(),
            grafana_token=env["GRAFANA_API_TOKEN"].strip(),
            grafana_api_url=env.get("ANALYZER_GRAFANA_API_URL", cls.grafana_api_url),
            grafana_public_url=env.get("ANALYZER_GRAFANA_URL", cls.grafana_public_url),
            prom_url=env.get("ANALYZER_PROM_URL", cls.prom_url),
            github_token=env.get("GITHUB_TOKEN", "").strip(),
            github_repo=env.get("WEEKLY_GITHUB_REPO", cls.github_repo),
        )


# --- sources ------------------------------------------------------------------


class Grafana:
    def __init__(self, base_url: str, token: str, http: httpx.AsyncClient):
        self._base = base_url.rstrip("/")
        self._headers = {"Authorization": f"Bearer {token}"}
        self._http = http

    async def dashboard(self, uid: str) -> dict:
        r = await self._http.get(f"{self._base}/api/dashboards/uid/{uid}", headers=self._headers, timeout=TIMEOUT)
        if r.status_code != 200:
            raise DigestError(f"grafana dashboard {uid}: HTTP {r.status_code}")
        return r.json()["dashboard"]

    async def sql(self, raw_sql: str, start: datetime, end: datetime) -> list[dict]:
        body = {
            "from": str(int(start.timestamp() * 1000)),
            "to": str(int(end.timestamp() * 1000)),
            "queries": [{"refId": "A", "datasource": PG, "rawSql": raw_sql, "format": "table", "rawQuery": True}],
        }
        r = await self._http.post(f"{self._base}/api/ds/query", headers=self._headers, json=body, timeout=TIMEOUT)
        if r.status_code != 200:
            raise DigestError(f"grafana query: HTTP {r.status_code} {r.text[:160]}")
        return frame_rows(r.json()["results"]["A"])


def frame_rows(result: dict) -> list[dict]:
    """Grafana data frame (columnar) -> list of row dicts."""
    if result.get("error"):
        raise DigestError(f"sql: {result['error'][:200]}")
    frames = result.get("frames") or []
    if not frames:
        return []
    names = [f["name"] for f in frames[0]["schema"]["fields"]]
    columns = frames[0]["data"].get("values") or [[] for _ in names]
    return [dict(zip(names, row)) for row in zip(*columns)]


def panel_sql(dashboard: dict, title: str) -> str:
    for p in dashboard.get("panels", []):
        if p.get("title") == title and p.get("targets"):
            return p["targets"][0]["rawSql"]
    raise DigestError(f"dashboard panel '{title}' not found")


class Prom:
    def __init__(self, base_url: str, http: httpx.AsyncClient):
        self._base = base_url.rstrip("/")
        self._http = http

    async def vector(self, query: str, at: datetime) -> list[dict]:
        r = await self._http.get(
            f"{self._base}/api/v1/query", params={"query": query, "time": f"{at.timestamp():.3f}"}, timeout=TIMEOUT
        )
        body = r.json() if r.status_code == 200 else {}
        if body.get("status") != "success":
            raise DigestError(f"prometheus: HTTP {r.status_code} {str(body.get('error', ''))[:160]}")
        return body["data"]["result"]


# --- sections (data) ----------------------------------------------------------


async def north_star(grafana: Grafana, start: datetime, end: datetime) -> dict:
    dash = await grafana.dashboard(NORTH_STAR_UID)
    rows = {}
    for key, title in {
        "summary": "정시 / 전체",
        "problems": "늦음 · 유실 · 지연 중",
        "delay": "발송 지연 p50 / p95 (정시·늦음)",
        "checkpoints": "checkpoint 별",
        "ring": "정시 / 전체 · 지연",
    }.items():
        rows[key] = await grafana.sql(panel_sql(dash, title), start, end)
    return rows


async def slow_endpoints(prom: Prom, end: datetime) -> list[dict]:
    p95 = {r["metric"].get("uri", "?"): float(r["value"][1]) for r in await prom.vector(SLOW_P95, end)}
    count = {r["metric"].get("uri", "?"): float(r["value"][1]) for r in await prom.vector(SLOW_COUNT, end)}
    slow = []
    for uri, value in p95.items():
        n = count.get(uri, 0.0)
        if n < MIN_REQUESTS or math.isnan(value):
            continue
        target = ROUTE_SEARCH_TARGET_S if uri == ROUTE_SEARCH_URI else DEFAULT_TARGET_S
        if value > target:
            slow.append({"uri": uri, "p95": value, "target": target, "count": n})
    return sorted(slow, key=lambda s: s["p95"] / s["target"], reverse=True)


async def funnel(prom: Prom, grafana: Grafana, start: datetime, end: datetime) -> dict:
    searches = await prom.vector(SEARCH_COUNT, end)
    rows = await grafana.sql(FUNNEL_SQL, start, end)
    out = {"searches": float(searches[0]["value"][1]) if searches else 0.0}
    out.update(rows[0] if rows else {})
    return out


async def alerts_fired(slack: SlackClient, start: datetime) -> Counter:
    """Alert name -> number of distinct alerts that fired (unique fingerprints), from channel history."""
    seen: dict[str, str] = {}
    cursor = None
    for _ in range(5):  # 5 x 200 messages is far more than a week here
        body = await slack.history(start, cursor=cursor)
        for message in body.get("messages", []):
            for name, fps in parse_alert_message(message):
                for fp in fps:
                    seen.setdefault(fp, name)
        cursor = (body.get("response_metadata") or {}).get("next_cursor")
        if not cursor:
            break
    return Counter(seen.values())


def parse_alert_message(message: dict) -> list[tuple[str, list[str]]]:
    """Grafana Slack notification -> [(alert name, fingerprints)] for FIRING messages only."""
    raw = json.dumps(message, ensure_ascii=False)
    fp = FP_LINE.search(raw.replace("\\n", "\n"))
    if not fp:
        return []
    titles = [a.get("title", "") for a in message.get("attachments", [])] or [message.get("text", "")]
    out = []
    for title in titles:
        m = TITLE.search(title)
        if m and m.group(1) == "FIRING":
            # Title = "<alertname> <grafana_folder> (<severity>)"; every rule lives in folder "nextroute".
            name = m.group(2).strip().removesuffix(" nextroute")
            out.append((name, fp.group(1).split()))
    return out


async def open_issues(http: httpx.AsyncClient, token: str, repo: str) -> int:
    r = await http.get(
        f"https://api.github.com/repos/{repo}/issues",
        params={"labels": "observability-weekly", "state": "open", "per_page": 100},
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"},
        timeout=TIMEOUT,
    )
    if r.status_code != 200:
        raise DigestError(f"github: HTTP {r.status_code}")
    return len([i for i in r.json() if "pull_request" not in i])


# --- rendering ----------------------------------------------------------------


def _n(v) -> str:
    if v is None:
        return "-"
    if isinstance(v, float) and v.is_integer():
        return str(int(v))
    return str(v)


def render(start: datetime, end: datetime, data: dict, *, dashboard_url: str) -> str:
    s, e = start.astimezone(KST), end.astimezone(KST)
    lines = [f"*[주간] 제때 관측 요약* {s:%m/%d}–{e:%m/%d %H:%M}"]

    lines.append("\n*정시 알림 (북극성)* · 예정 시각 + 60초 이내 FCM 전달")
    ns = data.get("north_star")
    if isinstance(ns, str):
        lines.append(f"• 조회 실패: {ns}")
    elif ns:
        summary = (ns["summary"] or [{}])[0]
        problems = (ns["problems"] or [{}])[0]
        delay = (ns["delay"] or [{}])[0]
        bad = sum(int(problems.get(k) or 0) for k in ("늦음", "유실", "지연 중"))
        mark = "⚠️ " if bad else "✅ "
        rate = str(summary.get("정시율") or "")
        rate = f" ({rate})" if rate.endswith("%") else " (표본 적음, 20건부터 %)"
        lines.append(
            f"• {mark}여정 알림 {_n(summary.get('정시 / 전체'))}{rate} · "
            f"늦음 {_n(problems.get('늦음'))} · 유실 {_n(problems.get('유실'))} · 지연 중 {_n(problems.get('지연 중'))} · "
            f"p95 {_n(delay.get('p95 (초)'))}s"
        )
        for c in ns["checkpoints"]:
            lines.append(f"    ◦ {c.get('checkpoint')}: {_n(c.get('정시 / 전체'))}, p95 {_n(c.get('p95 지연(초)'))}s")
        ring = (ns["ring"] or [{}])[0]
        lines.append(
            f"• 잠금화면 갱신 {_n(ring.get('정시 / 전체'))} ({_n(ring.get('정시율'))}) · "
            f"p50 {_n(ring.get('p50'))} · p95 {_n(ring.get('p95'))}"
        )

    lines.append(f"\n*느린 엔드포인트* · p95 목표 검색 {ROUTE_SEARCH_TARGET_S}s / 그 외 {DEFAULT_TARGET_S}s, 주 {MIN_REQUESTS}건 이상")
    slow = data.get("slow")
    if isinstance(slow, str):
        lines.append(f"• 조회 실패: {slow}")
    elif not slow:
        lines.append("• 없음 ✅")
    else:
        for x in slow[:SLOW_LIMIT]:
            p95 = "5s 초과" if math.isinf(x["p95"]) else f"{x['p95']:.2f}s"
            lines.append(f"• `{x['uri']}` p95 {p95} (목표 {x['target']}s, {int(x['count'])}건)")
        if len(slow) > SLOW_LIMIT:
            lines.append(f"• … 외 {len(slow) - SLOW_LIMIT}개")

    lines.append("\n*퍼널* · 검색은 전체, 나머지는 토스 사용자 제외")
    f = data.get("funnel")
    if isinstance(f, str):
        lines.append(f"• 조회 실패: {f}")
    elif f:
        lines.append(
            f"• 경로 검색 {_n(round(f.get('searches', 0)))} → 여정 시작 {_n(f.get('starts'))} → "
            f"알림 받은 여정 {_n(f.get('notified'))} → 완료 {_n(f.get('completed'))}"
        )

    lines.append("\n*알림*")
    a = data.get("alerts")
    if isinstance(a, str):
        lines.append(f"• 조회 실패: {a}")
    elif not a:
        lines.append("• 지난주 0건 ✅")
    else:
        top = ", ".join(f"{name} {n}" for name, n in a.most_common(6))
        lines.append(f"• 지난주 {sum(a.values())}건: {top}")

    issues = data.get("issues")
    if issues is not None:
        lines.append(f"\n*할 일* · `observability-weekly` 열린 issue {issues}" if not isinstance(issues, str)
                     else f"\n*할 일* · 조회 실패: {issues}")

    lines.append(f"\n<{dashboard_url}|북극성 대시보드>")
    return "\n".join(lines)


# --- main ---------------------------------------------------------------------


async def collect(settings: WeeklySettings, http: httpx.AsyncClient, slack: SlackClient | None,
                  start: datetime, end: datetime) -> dict:
    grafana = Grafana(settings.grafana_api_url, settings.grafana_token, http)
    prom = Prom(settings.prom_url, http)
    data: dict = {}

    async def section(key, coro):
        try:
            data[key] = await coro
        except (DigestError, SlackError, httpx.HTTPError, KeyError, ValueError) as exc:
            data[key] = f"{type(exc).__name__}: {exc}"[:200]

    await section("north_star", north_star(grafana, start, end))
    await section("slow", slow_endpoints(prom, end))
    await section("funnel", funnel(prom, grafana, start, end))
    if slack is not None:
        await section("alerts", alerts_fired(slack, start))
    if settings.github_token:
        await section("issues", open_issues(http, settings.github_token, settings.github_repo))
    return data


async def run(dry_run: bool, now: datetime | None = None) -> str:
    settings = WeeklySettings.from_env(dry_run=dry_run)
    end = now or datetime.now(timezone.utc)
    start = end - WEEK
    async with httpx.AsyncClient() as http:
        slack = SlackClient(settings.slack_bot_token, settings.slack_channel_id, http) if settings.slack_bot_token else None
        data = await collect(settings, http, slack, start, end)
        text = render(start, end, data,
                      dashboard_url=f"{settings.grafana_public_url.rstrip('/')}/d/{NORTH_STAR_UID}")
        if dry_run:
            print(text)
        else:
            await slack.post(text)
            print("weekly digest posted", file=sys.stderr)
    return text


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="analyzer.weekly")
    parser.add_argument("--dry-run", action="store_true", help="print instead of posting to Slack")
    args = parser.parse_args(argv)
    try:
        asyncio.run(run(args.dry_run))
    except DigestError as exc:
        print(f"weekly digest failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
