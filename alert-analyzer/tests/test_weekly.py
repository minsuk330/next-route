from __future__ import annotations

import json
from collections import Counter
from datetime import datetime, timezone

import httpx2 as httpx
import pytest

from analyzer import weekly
from analyzer.weekly import (
    AlertWeek,
    DigestError,
    Prom,
    WeeklySettings,
    frame_rows,
    panel_sql,
    parse_alert_message,
    render,
    score,
    slow_endpoints,
    synthetic_probe,
)

END = datetime(2026, 11, 30, 0, 0, tzinfo=timezone.utc)  # Mon 09:00 KST
START = END - weekly.WEEK


def test_frame_rows_turns_columns_into_rows():
    result = {"frames": [{"schema": {"fields": [{"name": "a"}, {"name": "b"}]},
                          "data": {"values": [[1, 2], ["x", "y"]]}}]}
    assert frame_rows(result) == [{"a": 1, "b": "x"}, {"a": 2, "b": "y"}]


def test_frame_rows_empty_and_error():
    assert frame_rows({"frames": []}) == []
    with pytest.raises(DigestError, match="permission denied"):
        frame_rows({"error": "pq: permission denied for table journey_notification"})


def test_panel_sql_reads_the_dashboard_definition():
    dash = {"panels": [{"type": "row", "title": "r"}, {"title": "정시 / 전체", "targets": [{"rawSql": "select 1"}]}]}
    assert panel_sql(dash, "정시 / 전체") == "select 1"
    with pytest.raises(DigestError, match="not found"):
        panel_sql(dash, "없는 패널")


def test_parse_alert_message_counts_firing_only():
    firing = {"text": "", "attachments": [{"title": "[FIRING:1] 디스크 사용률 높음 nextroute (node)",
                                            "text": "**Firing**\n...\nfp: abc123 def456 "}]}
    resolved = {"attachments": [{"title": "[RESOLVED] 디스크 사용률 높음 nextroute (node)", "text": "fp: abc123"}]}
    digest = {"text": "*[주간] 제때 관측 요약*"}
    assert parse_alert_message(firing) == [("디스크 사용률 높음", ["abc123", "def456"])]
    assert parse_alert_message(resolved) == []
    assert parse_alert_message(digest) == []


def test_parse_alert_message_reads_the_new_title_too():
    # templates.yaml titles; Slack may store the emoji as a shortcode.
    for title in ("🟡 ERROR 로그 급증 · worker2", ":red_circle: 합성 확인 실패", "🔴 외부 API 호출 실패 · ws.bus.go.kr"):
        msg = {"attachments": [{"title": title, "text": "worker2 ERROR 로그 80건\nfp: ab12 cd34 "}]}
        assert parse_alert_message(msg) == [(title.split(" ", 1)[1].split(" · ")[0], ["ab12", "cd34"])], title
    for resolved in ("🟢 해결 · ERROR 로그 급증 · worker2", ":large_green_circle: 해결 · 합성 확인 실패"):
        assert parse_alert_message({"attachments": [{"title": resolved, "text": "fp: ab12"}]}) == []


def test_settings_need_grafana_token_and_slack_unless_dry_run():
    with pytest.raises(DigestError, match="GRAFANA_API_TOKEN"):
        WeeklySettings.from_env({}, dry_run=True)
    with pytest.raises(DigestError, match="SLACK_BOT_TOKEN"):
        WeeklySettings.from_env({"GRAFANA_API_TOKEN": "t"})
    s = WeeklySettings.from_env({"GRAFANA_API_TOKEN": "secret-token"}, dry_run=True)
    assert "secret-token" not in repr(s)
    assert s.github_token == ""


def fake_prom(over_share: str):
    def handler(request: httpx.Request) -> httpx.Response:
        q = request.url.params["query"]
        if q.startswith("1 - sum"):
            return httpx.Response(200, json={"status": "success", "data": {"result": [{"metric": {}, "value": [0, over_share]}]}})
        if q.startswith("histogram_quantile"):
            rows = [("/api/route/search", "5.0"), ("/api/places/search", "2.39"), ("/api/journeys", "0.8"),
                    ("/api/arrivals/subway", "+Inf"), ("/api/me/push-token", "0.3"), ("/api/rare", "9")]
        else:
            rows = [("/api/route/search", "147"), ("/api/places/search", "277"), ("/api/journeys", "25"),
                    ("/api/arrivals/subway", "30"), ("/api/me/push-token", "100"), ("/api/rare", "3")]
        body = {"status": "success", "data": {"result": [{"metric": {"uri": u}, "value": [0, v]} for u, v in rows]}}
        return httpx.Response(200, json=body)
    return httpx.MockTransport(handler)


@pytest.mark.anyio
async def test_slow_endpoints_relaxes_only_route_search():
    async with httpx.AsyncClient(transport=fake_prom("0.054")) as http:
        slow = await slow_endpoints(Prom("http://prometheus:9090", http), END)
    uris = [s["uri"] for s in slow]
    # OTP route search at its usual 5.4 % over 5 s is accepted; places search is NOT relaxed.
    assert "/api/route/search" not in uris
    assert uris == ["/api/arrivals/subway", "/api/places/search", "/api/journeys"]  # by p95, rare skipped


@pytest.mark.anyio
async def test_route_search_reported_when_share_over_5s_doubles():
    async with httpx.AsyncClient(transport=fake_prom("0.12")) as http:
        slow = await slow_endpoints(Prom("http://prometheus:9090", http), END)
    assert slow[0] == {"uri": "/api/route/search", "over_share": 0.12, "count": 147.0}
    text = render(START, END, {"slow": slow}, dashboard_url="u")
    assert "`/api/route/search` 5초 초과 12% (기준 10%, 평소 약 5%, 147건)" in text


def fake_synthetic_prom(runs, slow):
    def handler(request: httpx.Request) -> httpx.Response:
        q = request.url.params["query"]
        rows = runs if "synthetic_probe_runs_total" in q else slow
        return httpx.Response(200, json={"status": "success", "data": {"result": [
            {"metric": m, "value": [0, v]} for m, v in rows]}})
    return httpx.MockTransport(handler)


@pytest.mark.anyio
async def test_synthetic_probe_counts_runs_by_outcome_and_slow_searches():
    runs = [({"scenario": "subway", "outcome": "0"}, "140.2"), ({"scenario": "subway", "outcome": "2"}, "1"),
            ({"scenario": "bus", "outcome": "0"}, "138"), ({"scenario": "bus", "outcome": "1"}, "3.1"),
            ({"scenario": "bus", "outcome": "3"}, "5")]  # unknown outcome ignored
    slow = [({"scenario": "subway"}, "2")]
    async with httpx.AsyncClient(transport=fake_synthetic_prom(runs, slow)) as http:
        syn = await synthetic_probe(Prom("http://prometheus:9090", http), END)
    assert syn == {"subway": {"ok": 140.2, "degraded": 0.0, "failed": 1.0, "slow": 2.0},
                   "bus": {"ok": 138.0, "degraded": 3.1, "failed": 0.0, "slow": 0.0}}
    text = render(START, END, {"synthetic": syn}, dashboard_url="u")
    assert "• ✅ " not in text
    assert "⚠️ bus: 정상 138 · 이상 3 · 실패 0 · 검색 10초 이상 0" in text
    assert "⚠️ subway: 정상 140 · 이상 0 · 실패 1 · 검색 10초 이상 2" in text


def test_render_synthetic_ok_empty_and_failed():
    ok = {"subway": {"ok": 140.0, "degraded": 0.0, "failed": 0.0, "slow": 0.0}}
    assert "✅ subway: 정상 140 · 이상 0 · 실패 0 · 검색 10초 이상 0" in render(START, END, {"synthetic": ok}, dashboard_url="u")
    assert "기록 없음 (호출기 꺼짐?)" in render(START, END, {"synthetic": {}}, dashboard_url="u")
    failed = render(START, END, {"synthetic": "DigestError: prometheus: HTTP 503"}, dashboard_url="u")
    assert "*합성 확인*" in failed and "조회 실패: DigestError: prometheus: HTTP 503" in failed


def test_api_selector_excludes_synthetic_traffic():
    for q in (weekly.SLOW_P95, weekly.SLOW_COUNT, weekly.SEARCH_OVER):
        assert 'synthetic!="true"' in q


def full_data() -> dict:
    return {
        "north_star": {
            "summary": [{"정시 / 전체": "22 / 22", "정시율": "100.0%"}],
            "problems": [{"늦음": 0, "유실": 0, "지연 중": 0}],
            "delay": [{"p50 (초)": 0.6, "p95 (초)": 1.2, "최대 (초)": 2.8}],
            "checkpoints": [{"checkpoint": "TRANSFER_RISK_AT_RISK", "정시 / 전체": "12 / 12", "p95 지연(초)": 1.0}],
            "ring": [{"정시 / 전체": "651 / 651", "정시율": "100.0%", "p50": "1.05s", "p95": "2.25s"}],
        },
        "slow": [{"uri": "/api/journeys", "p95": 0.8, "target": 0.5, "count": 40.0},
                 {"uri": "/api/arrivals/subway", "p95": float("inf"), "target": 0.5, "count": 30.0}],
        "funnel": {"searches": 139.4, "starts": 31, "notified": 7, "completed": 6},
        "alerts": AlertWeek(counts=Counter({"디스크 사용률 높음": 1, "api 5xx 발생": 2}),
                            scores=Counter(real=1, false=1, unscored=1, hit=1)),
        "issues": 3,
    }


def test_render_full_digest():
    text = render(START, END, full_data(), dashboard_url="https://g/d/nextroute-north-star")
    assert text.startswith("*[주간] 제때 관측 요약* 11/23–11/30 09:00")
    assert "✅ 여정 알림 22 / 22 (100.0%) · 늦음 0 · 유실 0 · 지연 중 0 · p95 1.2s" in text
    assert "TRANSFER_RISK_AT_RISK: 12 / 12, p95 1s" in text
    assert "잠금화면 갱신 651 / 651 (100.0%) · p50 1.05s · p95 2.25s" in text
    assert "`/api/arrivals/subway` p95 5s 초과" in text
    assert "경로 검색 139 → 여정 시작 31 → 알림 받은 여정 7 → 완료 6" in text
    assert "지난주 3건: api 5xx 발생 2, 디스크 사용률 높음 1" in text
    assert "채점: 진짜 1 · 오탐 1 · 미채점 1 ← 채점해 주세요" in text
    assert "분석 가설: 맞음 1 · 틀림 0" in text
    assert "장애 훈련" not in text  # 11/30 is not the first week
    assert "열린 issue 3" in text
    assert text.endswith("<https://g/d/nextroute-north-star|북극성 대시보드>")


def test_render_marks_problems_and_survives_failed_sections():
    data = full_data()
    data["north_star"]["problems"] = [{"늦음": 1, "유실": 0, "지연 중": 0}]
    data["north_star"]["summary"] = [{"정시 / 전체": "5 / 6", "정시율": "표본 6건 (20건부터 %)"}]
    data["slow"] = "DigestError: prometheus: HTTP 503"
    data["funnel"] = "DigestError: sql: permission denied"
    data["alerts"] = AlertWeek()
    del data["issues"]
    text = render(START, END, data, dashboard_url="u")
    assert "⚠️ 여정 알림 5 / 6 (표본 적음, 20건부터 %)" in text
    assert "조회 실패: DigestError: prometheus: HTTP 503" in text
    assert "조회 실패: DigestError: sql: permission denied" in text
    assert "지난주 0건 ✅" in text
    assert "할 일" not in text


def test_score_unions_reactions_and_false_wins():
    names = {"fp1": "api 5xx 발생", "fp2": "api 5xx 발생", "fp3": "디스크 사용률 높음", "fp4": "OTP 호출 실패"}
    reactions = {"fp1": {"white_check_mark", "dart"}, "fp2": {"x", "white_check_mark"},
                 "fp3": {"shrug"}, "fp4": set()}
    week = score(names, reactions)
    assert week.counts == Counter({"api 5xx 발생": 2, "디스크 사용률 높음": 1, "OTP 호출 실패": 1})
    assert week.scores == Counter(real=1, false=1, unscored=2, hit=1, miss=1)


@pytest.mark.anyio
async def test_alerts_fired_reads_reactions_from_history():
    firing = {"ts": "1", "attachments": [{"title": "[FIRING:1] api 5xx 발생 nextroute (warning)", "text": "fp: aa11"}],
              "reactions": [{"name": "white_check_mark", "count": 1}]}
    repeat = {"ts": "2", "attachments": [{"title": "[FIRING:1] api 5xx 발생 nextroute (warning)", "text": "fp: aa11"}],
              "reactions": [{"name": "dart", "count": 1}]}
    resolved = {"ts": "3", "attachments": [{"title": "[RESOLVED] api 5xx 발생 nextroute (warning)", "text": "fp: aa11"}],
                "reactions": [{"name": "x", "count": 1}]}

    class FakeSlack:
        async def history(self, oldest, *, cursor=None, limit=200):
            return {"messages": [resolved, repeat, firing]}

    week = await weekly.alerts_fired(FakeSlack(), START)
    assert week.counts == Counter({"api 5xx 발생": 1})
    # reaction on the RESOLVED message is ignored; FIRING + repeat are unioned
    assert week.scores == Counter(real=1, hit=1)


def test_render_adds_drill_checklist_in_first_week():
    first_monday = datetime(2026, 12, 7, 0, 0, tzinfo=timezone.utc)  # Mon 09:00 KST, day 7
    text = render(first_monday - weekly.WEEK, first_monday, {"alerts": AlertWeek()}, dashboard_url="u")
    assert "*이번 달 장애 훈련*" in text
    assert text.count("• ☐ ") == 3
