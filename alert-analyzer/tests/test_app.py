from __future__ import annotations

import copy
import json
import logging
import threading
import time
from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient

from analyzer.app import create_app
from analyzer.config import Settings
from analyzer.context import ContextBundle
from analyzer.worker import Live

NOW = datetime(2026, 10, 26, 7, 2, tzinfo=timezone.utc)
AUTH = {"Authorization": "Bearer test-token"}


def settings(**overrides) -> Settings:
    return Settings(webhook_token="test-token", **overrides)


@pytest.fixture
def client():
    app = create_app(settings(queue_max=1), start_worker=False, clock=lambda: NOW)
    with TestClient(app) as c:
        yield c


def post(client: TestClient, payload: dict, headers: dict | None = AUTH):
    return client.post("/webhook/grafana", content=json.dumps(payload), headers=headers or {})


@pytest.mark.parametrize(
    "headers",
    [None, {"Authorization": "Bearer wrong"}, {"Authorization": "Basic test-token"}],
    ids=["missing", "wrong-token", "wrong-scheme"],
)
def test_rejects_unauthorized(client, grafana_payload, headers):
    response = post(client, grafana_payload, headers=headers)

    assert response.status_code == 401
    assert client.app.state.queue.qsize() == 0


def test_rejects_malformed_payload(client):
    response = client.post("/webhook/grafana", content=b'{"status": "firing"}', headers=AUTH)

    assert response.status_code == 400


def test_queues_firing_alert(client, grafana_payload):
    response = post(client, grafana_payload)

    assert response.status_code == 202
    assert response.json() == {"queued": 1, "skipped": 0}
    job = client.app.state.queue.get_nowait()
    assert job.alerts[0].fingerprint == "140515ca470d0b8b"


def test_repeat_is_skipped(client, grafana_payload):
    post(client, grafana_payload)
    client.app.state.queue.get_nowait()

    assert post(client, grafana_payload).json() == {"queued": 0, "skipped": 1}


def test_resolved_is_ignored(client, grafana_payload):
    grafana_payload["status"] = "resolved"

    assert post(client, grafana_payload).json() == {"queued": 0, "skipped": 1}


def test_full_queue_drops_and_allows_retry(client, grafana_payload):
    post(client, grafana_payload)  # fills the queue (max 1)
    other = copy.deepcopy(grafana_payload)
    other["alerts"][0]["fingerprint"] = "bbbbbbbbbbbbbbbb"

    assert post(client, other).json() == {"queued": 0, "skipped": 1}

    client.app.state.queue.get_nowait()
    assert post(client, other).json() == {"queued": 1, "skipped": 0}


def test_healthz(client):
    response = client.get("/healthz")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "queue": 0}


def test_worker_picks_up_job(grafana_payload):
    done = threading.Event()
    seen_jobs = []

    async def fake_gather(job):
        seen_jobs.append(job)
        done.set()
        return ContextBundle(
            alerts=[],
            window_start=NOW,
            window_end=NOW,
            templates=[],
            quiet_summary=[],
            metrics=[],
            restarts=[],
            collection_errors=[],
        )

    app = create_app(settings(), gather_fn=fake_gather, clock=lambda: NOW)
    with TestClient(app) as c:
        assert post(c, grafana_payload).status_code == 202
        assert done.wait(timeout=5)

    assert seen_jobs[0].alerts[0].fingerprint == "140515ca470d0b8b"


def test_settings_require_token():
    with pytest.raises(RuntimeError, match="ANALYZER_WEBHOOK_TOKEN"):
        Settings.from_env({})


LIVE_ENV = {
    "ANALYZER_WEBHOOK_TOKEN": "t",
    "ANALYZER_DRY_RUN": "false",
    "ANTHROPIC_API_KEY": "sk-ant-secret",
    "SLACK_BOT_TOKEN": "xoxb-secret",
    "SLACK_CHANNEL_ID": "C0123",
}


@pytest.mark.parametrize("missing", ["ANTHROPIC_API_KEY", "SLACK_BOT_TOKEN", "SLACK_CHANNEL_ID"])
def test_live_mode_refuses_to_start_without_its_keys(missing):
    env = {k: v for k, v in LIVE_ENV.items() if k != missing}

    with pytest.raises(RuntimeError, match=f"ANALYZER_DRY_RUN=false needs {missing}"):
        Settings.from_env(env)


def test_live_mode_settings_hide_secrets_from_repr():
    s = Settings.from_env(LIVE_ENV)

    assert (s.dry_run, s.slack_channel_id) == (False, "C0123")
    assert "secret" not in repr(s)
    assert "'t'" not in repr(s)


def test_dry_run_needs_no_keys():
    assert Settings.from_env({"ANALYZER_WEBHOOK_TOKEN": "t"}).anthropic_api_key == ""


def test_dry_run_wins_over_injected_live_stages(grafana_payload, caplog):
    caplog.set_level(logging.INFO, logger="analyzer.worker")
    calls = []

    async def fake_gather(job):
        return ContextBundle(
            alerts=[], window_start=NOW, window_end=NOW, templates=[], quiet_summary=[],
            metrics=[], restarts=[], collection_errors=[],
        )

    async def record(*args):
        calls.append(args)
        return True

    live = Live(analyze=record, notifier=type("N", (), {"reply": staticmethod(record)})())
    app = create_app(settings(), gather_fn=fake_gather, live=live, clock=lambda: NOW)
    with TestClient(app) as c:
        assert post(c, grafana_payload).status_code == 202
        deadline = time.monotonic() + 5
        while "dry-run" not in caplog.text and time.monotonic() < deadline:
            time.sleep(0.01)

    assert "dry-run" in caplog.text
    assert calls == []
