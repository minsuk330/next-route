from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import pytest

from analyzer.context import (
    BASELINE_LINES,
    WINDOW_LINES,
    classify,
    fit_budget,
    gather,
    normalize_template,
    template_id,
)
from analyzer.payload import AnalysisJob, GrafanaAlert
from analyzer.sources import SourceError
from tests.conftest import FakeLogs, FakeMetrics, stream, vector

pytestmark = pytest.mark.anyio

# Shaped after 2026-10-23 19:00: the Seoul arrival API returned 503 for every line.
STARTS = datetime(2026, 10, 23, 10, 1, tzinfo=timezone.utc)
WINDOW_START = STARTS - timedelta(minutes=10)
WINDOW_END = STARTS + timedelta(minutes=1)
ADAPTER = "watoo.grd.nextroute.infrastructure.adapter.out.api.subway.SeoulSubwayApiAdapter"
TRACKER = "watoo.grd.nextroute.application.subway.service.SubwayTrainTransitionTracker"


def job(starts: datetime = STARTS) -> AnalysisJob:
    alert = GrafanaAlert(
        status="firing",
        labels={"alertname": "ERROR 로그 급증", "app": "worker", "grafana_folder": "nextroute"},
        annotations={"summary": "worker ERROR 로그 21건 (5분)"},
        startsAt=starts,
        fingerprint="f00dfeedf00dfeed",
        values={"A": 21.0, "C": 1.0},
    )
    return AnalysisJob(group_key="g", alerts=(alert,), received_at=starts)


def subway_logs() -> FakeLogs:
    def instant(query: str, at: datetime) -> list[dict]:
        if 'app="otp"' in query:
            return []
        if "[11m]" in query:
            return [
                vector({"app": "worker", "level": "ERROR", "logger_name": ADAPTER}, 11),
                vector({"app": "worker", "level": "WARN", "logger_name": TRACKER}, 160),
            ]
        if "[60m]" in query:
            return [vector({"app": "worker", "level": "WARN", "logger_name": TRACKER}, 900)]
        raise AssertionError(f"unexpected instant query {query}")

    def range_(query: str, start: datetime, end: datetime, limit: int) -> list[dict]:
        assert TRACKER not in query, "NORMAL WARN loggers must not be read line by line"
        if ADAPTER not in query:
            return []
        if end <= WINDOW_START:
            return []  # nothing from this logger in the hour before
        if "line_format" in query:
            lines = [f"Subway arrival API failed after 3 retries: 503 lineId={1001 + i}" for i in range(11)]
            return [{"stream": {}, "values": [[str(int(end.timestamp() * 1e9) - i), m] for i, m in enumerate(lines)]}]
        full = [
            {
                "message": "Subway arrival API failed after 3 retries: 503 lineId=1001",
                "traceId": "abc123",
                "stack_trace": "org.springframework.web.client.HttpServerErrorException$ServiceUnavailable: 503\n\tat x.Y",
            }
        ]
        return [stream(full, end)]

    return FakeLogs(instant=instant, range_=range_)


def subway_metrics() -> FakeMetrics:
    restart = (STARTS - timedelta(minutes=3)).timestamp()
    old = (STARTS - timedelta(days=3)).timestamp()

    def instant(query: str, at: datetime) -> list[dict]:
        if query == "process_start_time_seconds":
            assert at == WINDOW_END
            return [vector({"instance": "worker:8081"}, restart), vector({"instance": "api:8081"}, old)]
        if "MemAvailable" in query:
            return [vector({"instance": "node-exporter:9100"}, 1700 if at == STARTS else 1800)]
        if "http_client_requests" in query and at == STARTS:
            return [vector({"client_name": "swopenAPI.seoul.go.kr", "status": "503"}, 33)]
        return []

    return FakeMetrics(instant=instant)


@pytest.mark.parametrize(
    ("message", "expected"),
    [
        ("[SubwayPosition] 3회 재시도 후 실패 — lineId=1065", "[SubwayPosition] #회 재시도 후 실패 — lineId=#"),
        ("job 123e4567-e89b-12d3-a456-426614174000 done", "job <uuid> done"),
        ("trace 140515ca470d0b8b failed", "trace <hex> failed"),
        ("count 12345678   items", "count # items"),
    ],
)
def test_normalize_template(message, expected):
    assert normalize_template(message) == expected


def test_template_id_is_stable_and_prefixed():
    assert template_id("worker", "a.B", "x #") == template_id("worker", "a.B", "x #")
    assert template_id("worker", "a.B", "x #").startswith("t:")
    assert template_id("worker", "a.B", "x #") != template_id("api", "a.B", "x #")


@pytest.mark.parametrize(
    ("window", "baseline", "expected"),
    [
        (0, 0, "NORMAL"),
        (1, 0, "NEW"),
        (5, 5, "SPIKE"),  # 5/11 per min vs 3 × 5/60
        (4, 1, "NORMAL"),  # below the 5-line floor
        (160, 900, "NORMAL"),  # the TrainTransition background noise
    ],
)
def test_classify(window, baseline, expected):
    assert classify(window, baseline, 11, 60) == expected


async def test_gather_surfaces_new_error_logger_and_quiets_noise():
    bundle = await gather(job(), subway_logs(), subway_metrics())

    top = bundle.templates[0]
    assert top.level == "ERROR"
    assert top.status == "NEW"
    assert top.logger_total == 11
    assert top.logger_baseline == 0
    assert top.sample_count == 11
    assert top.baseline_seen is False
    assert top.template == "Subway arrival API failed after # retries: # lineId=#"
    assert "HttpServerErrorException" in top.samples[0]
    assert top.trace_ids == ["abc123"]
    assert any("SubwayTrainTransitionTracker" in q and "160건" in q for q in bundle.quiet_summary)
    assert bundle.collection_errors == []


async def test_gather_reports_restart_and_metrics():
    bundle = await gather(job(), subway_logs(), subway_metrics())

    assert [r.instance for r in bundle.restarts] == ["worker:8081"]
    memory = next(m for m in bundle.metrics if m.key == "m:memory_available_mib")
    assert (memory.at_alert, memory.before_30m) == (1700.0, 1800.0)
    failing = next(m for m in bundle.metrics if m.key == "m:client_fail_10m")
    assert failing.labels == {"client_name": "swopenAPI.seoul.go.kr", "status": "503"}
    assert failing.before_30m is None
    assert {"m:restarts", "m:memory_available_mib", bundle.templates[0].id} <= bundle.ids()


async def test_gather_window_follows_alert_start():
    logs = subway_logs()

    bundle = await gather(job(), logs, subway_metrics())

    assert bundle.window_start == WINDOW_START
    assert bundle.window_end == WINDOW_END
    assert any(c[0] == "instant" and c[2] == WINDOW_END and "[11m]" in c[1] for c in logs.calls)


async def test_loki_failure_is_recorded_and_metrics_still_collected():
    def broken(query: str, at: datetime) -> list[dict]:
        raise SourceError("loki HTTP 502")

    bundle = await gather(job(), FakeLogs(instant=broken), subway_metrics())

    assert bundle.templates == []
    assert any(e.startswith("loki app logs: SourceError") for e in bundle.collection_errors)
    assert any(m.key == "m:memory_available_mib" for m in bundle.metrics)


async def test_template_unknown_when_baseline_sample_capped():
    def instant(query: str, at: datetime) -> list[dict]:
        if "[11m]" in query and 'app="otp"' not in query:
            return [vector({"app": "api", "level": "WARN", "logger_name": "a.Busy"}, 400)]
        if "[60m]" in query and 'app="otp"' not in query:
            return [vector({"app": "api", "level": "WARN", "logger_name": "a.Busy"}, 600)]
        return []

    def range_(query: str, start: datetime, end: datetime, limit: int) -> list[dict]:
        if "line_format" not in query:
            return []
        if end <= WINDOW_START:
            msgs = ["old thing happened"] * BASELINE_LINES
        else:
            msgs = ["brand new failure"] * WINDOW_LINES
        return [{"stream": {}, "values": [[str(int(end.timestamp() * 1e9) - i), m] for i, m in enumerate(msgs)]}]

    bundle = await gather(job(), FakeLogs(instant=instant, range_=range_), FakeMetrics())

    stat = bundle.templates[0]
    assert stat.status == "SPIKE"
    assert stat.sample_capped is True
    assert stat.baseline_seen is None


def test_fit_budget_cuts_least_useful_parts_first():
    from analyzer.context import ContextBundle, TemplateStat

    templates = [
        TemplateStat(
            id=f"t:{i:08x}",
            app="worker",
            level="WARN",
            logger="a.B",
            template=f"template {i}",
            status="NORMAL",
            logger_total=1,
            logger_baseline=1,
            sample_count=1,
            sample_capped=False,
            baseline_seen=True,
            samples=["x" * 400, "y" * 400],
        )
        for i in range(15)
    ]
    bundle = ContextBundle(
        alerts=[],
        window_start=WINDOW_START,
        window_end=WINDOW_END,
        templates=templates,
        quiet_summary=["q" * 300],
        metrics=[],
        restarts=[],
        collection_errors=[],
    )

    fitted = fit_budget(bundle, limit=6_000)

    assert len(fitted.to_json()) <= 6_000
    assert fitted.truncated[:2] == ["quiet_summary", "samples>1"]
    assert fitted.templates[0].id == "t:00000000"
    json.loads(fitted.to_json())


async def test_key_in_raw_message_never_reaches_template():
    key = "4a6b7c8d9e0f1a2b3c4d5e6f7a"
    raw = f"Subway arrival API non-success [/api/subway/{key}/json/realtimeStationArrival/0/5/강남]: INFO-200"

    def instant(query: str, at: datetime) -> list[dict]:
        if "[11m]" in query and 'app="otp"' not in query:
            return [vector({"app": "worker", "level": "WARN", "logger_name": ADAPTER}, 3)]
        return []

    def range_(query: str, start: datetime, end: datetime, limit: int) -> list[dict]:
        if end <= WINDOW_START:
            return []
        if "line_format" in query:
            return [{"stream": {}, "values": [[str(int(end.timestamp() * 1e9)), raw]]}]
        return [stream([{"message": raw}], end)]

    bundle = await gather(job(), FakeLogs(instant=instant, range_=range_), FakeMetrics())

    text = bundle.to_json()
    assert key not in text
    # Not even the letters survive (templating turns digits into "#").
    letters_only = "".join(c for c in key if c.isalpha())
    assert letters_only not in "".join(c for c in text if c.isalpha())
    assert "/api/subway/<redacted>/json" in bundle.templates[0].template
