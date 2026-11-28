from __future__ import annotations

import copy
from datetime import datetime, timedelta, timezone

from analyzer.dedupe import SeenStore
from analyzer.payload import GrafanaWebhook, dedupe_key, select_new

NOW = datetime(2026, 10, 26, 7, 2, tzinfo=timezone.utc)


def test_parses_real_grafana_payload(grafana_payload):
    payload = GrafanaWebhook.model_validate(grafana_payload)

    alert = payload.alerts[0]
    assert payload.status == "firing"
    assert payload.groupKey.startswith("catcher-")
    assert alert.fingerprint == "140515ca470d0b8b"
    assert alert.labels["alertname"] == "메모리 부족"
    assert alert.annotations["summary"].startswith("VPS 남은 메모리")
    assert alert.values == {"B": 22.0, "C": 1.0}
    # Grafana sends nanoseconds; the extra digits are dropped, not rejected.
    assert alert.startsAt == datetime(2026, 10, 26, 7, 1, 20, 945744, tzinfo=timezone.utc)


def test_resolved_payload_selects_nothing(grafana_payload):
    grafana_payload["status"] = "resolved"
    payload = GrafanaWebhook.model_validate(grafana_payload)

    assert select_new(payload, SeenStore(), NOW) == []


def test_non_firing_alert_in_firing_group_is_skipped(grafana_payload):
    resolved = copy.deepcopy(grafana_payload["alerts"][0])
    resolved["status"] = "resolved"
    resolved["fingerprint"] = "aaaaaaaaaaaaaaaa"
    grafana_payload["alerts"].append(resolved)
    payload = GrafanaWebhook.model_validate(grafana_payload)

    fresh = select_new(payload, SeenStore(), NOW)

    assert [a.fingerprint for a in fresh] == ["140515ca470d0b8b"]


def test_group_resend_of_same_episode_is_skipped(grafana_payload):
    seen = SeenStore()
    payload = GrafanaWebhook.model_validate(grafana_payload)

    assert len(select_new(payload, seen, NOW)) == 1
    assert select_new(payload, seen, NOW + timedelta(minutes=5)) == []


def test_old_episode_is_skipped_even_when_forgotten(grafana_payload):
    # After a restart the seen-store is empty; the hourly repeat must still not
    # trigger a second analysis.
    payload = GrafanaWebhook.model_validate(grafana_payload)

    assert select_new(payload, SeenStore(), NOW + timedelta(hours=1)) == []


def test_datasource_alerts_are_skipped(grafana_payload):
    grafana_payload["alerts"][0]["labels"]["alertname"] = "DatasourceError"
    payload = GrafanaWebhook.model_validate(grafana_payload)

    assert select_new(payload, SeenStore(), NOW) == []


def test_contact_point_test_alert_is_skipped(grafana_payload):
    # The "Test" button sends a fake firing TestAlert to every integration, the analyzer webhook too.
    grafana_payload["alerts"][0]["labels"]["alertname"] = "TestAlert"
    payload = GrafanaWebhook.model_validate(grafana_payload)

    assert select_new(payload, SeenStore(), NOW) == []


def test_own_error_burst_is_skipped(grafana_payload):
    grafana_payload["alerts"][0]["labels"]["app"] = "alert-analyzer"
    payload = GrafanaWebhook.model_validate(grafana_payload)

    assert select_new(payload, SeenStore(), NOW) == []


def test_new_alert_joining_group_is_selected_alone(grafana_payload):
    seen = SeenStore()
    select_new(GrafanaWebhook.model_validate(grafana_payload), seen, NOW)
    joined = copy.deepcopy(grafana_payload["alerts"][0])
    joined["fingerprint"] = "bbbbbbbbbbbbbbbb"
    grafana_payload["alerts"].append(joined)

    fresh = select_new(GrafanaWebhook.model_validate(grafana_payload), seen, NOW + timedelta(minutes=5))

    assert [a.fingerprint for a in fresh] == ["bbbbbbbbbbbbbbbb"]


def test_same_fingerprint_with_new_start_is_a_new_episode(grafana_payload):
    seen = SeenStore()
    select_new(GrafanaWebhook.model_validate(grafana_payload), seen, NOW)
    grafana_payload["alerts"][0]["startsAt"] = "2026-10-26T09:00:00Z"

    fresh = select_new(GrafanaWebhook.model_validate(grafana_payload), seen, NOW + timedelta(hours=2))

    assert len(fresh) == 1
    assert dedupe_key(fresh[0]).endswith("2026-10-26T09:00:00+00:00")
