from __future__ import annotations

from datetime import datetime, timezone

import pytest

from analyzer.context import ContextBundle, TemplateStat
from analyzer.masking import RULE_NAMES, mask, mask_bundle

CASES = [
    ("slack", "url=https://hooks.slack.com/services/T0/B0/abcDEF123", "<slack-secret>"),
    ("anthropic", "key sk-ant-api03-abcDEF_123-xyz", "<anthropic-key>"),
    ("jwt", "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl", "<jwt>"),
    ("bearer", "Authorization: Bearer abc.def-123", "Bearer <redacted>"),
    ("api_key", "GET /api?serviceKey=AbCdEf123456&x=1", "serviceKey=<redacted>&x=1"),
    (
        "url_path_secret",
        "Subway arrival API non-success [/api/subway/4a6b7c8d9e0f1a2b3c4d5e6f7a/json/realtimeStationArrival/0/5/강남]",
        "/api/subway/<redacted>/json/realtimeStationArrival/0/5/강남",
    ),
    ("toss_key", '{"tossUserKey": "u-123abc"}', "tossUserKey=<redacted>"),
    ("email", "sent to someone@example.com", "<email>"),
    ("phone", "call 010-1234-5678", "<phone>"),
    ("coord", "x=127.0276123 y=37.4979456", "x=127.02 y=37.49"),
    ("uuid", "device 123e4567-e89b-12d3-a456-426614174000", "<uuid>"),
]


def test_every_rule_has_a_case():
    assert {name for name, _, _ in CASES} == set(RULE_NAMES)


@pytest.mark.parametrize(("name", "raw", "expected"), CASES, ids=[c[0] for c in CASES])
def test_rule_masks(name, raw, expected):
    masked = mask(raw)

    assert expected in masked


def test_ordinary_log_line_is_unchanged():
    line = "[TrainTransition] 중복 열차 — lineId=1002, 최신 채택 3건, 격리 1건"

    assert mask(line) == line


@pytest.mark.parametrize(
    "raw",
    [
        'I/O error on GET request for "http://swopenAPI.seoul.go.kr/api/subway/4a6b7c8d9e0f1a2b3c4d5e6f7a/json/x": timeout',
        "http://openapi.seoul.go.kr:8088/4a6b7c8d9e0f1a2b3c4d5e6f7a/json/SearchSTNBySubwayLineInfo/1/5",
        "path=/api/subway/4a6b7c8d9e0f1a2b3c4d5e6f7a",
    ],
)
def test_seoul_key_in_url_path_is_removed(raw):
    assert "4a6b7c8d9e0f1a2b3c4d5e6f7a" not in mask(raw)


def test_ordinary_api_path_is_unchanged():
    line = "GET /api/journeys/track 503"

    assert mask(line) == line


def test_slack_token_is_not_left_partly_visible():
    masked = mask("token xoxb-1234-5678-AbCdEf")

    assert "1234" not in masked
    assert "AbCdEf" not in masked


def test_bundle_samples_alerts_and_errors_are_masked():
    bundle = ContextBundle(
        alerts=[{"alertname": "x", "summary": "user someone@example.com", "labels": {}}],
        window_start=datetime(2026, 10, 26, tzinfo=timezone.utc),
        window_end=datetime(2026, 10, 26, tzinfo=timezone.utc),
        templates=[
            TemplateStat(
                id="t:1",
                app="api",
                level="ERROR",
                logger="x.Y",
                template="call serviceKey=AbCdEf123456",
                status="NEW",
                logger_total=1,
                logger_baseline=0,
                sample_count=1,
                sample_capped=False,
                baseline_seen=False,
                samples=["Bearer secret-token-value"],
            )
        ],
        quiet_summary=["010-1234-5678"],
        metrics=[],
        restarts=[],
        collection_errors=["loki: 401 sk-ant-api03-zzz"],
    )

    masked = mask_bundle(bundle)

    assert masked.alerts[0]["summary"] == "user <email>"
    assert masked.templates[0].template == "call serviceKey=<redacted>"
    assert masked.templates[0].samples == ["Bearer <redacted>"]
    assert masked.quiet_summary == ["<phone>"]
    assert "sk-ant" not in masked.collection_errors[0]
