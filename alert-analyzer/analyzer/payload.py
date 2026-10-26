"""Grafana webhook payload (contact point type `webhook`, version "1").

Only the fields the analyzer reads are modelled; the rest is ignored. The shape
was captured from Grafana 12.0 on the VPS (fixtures/grafana_webhook_firing.json).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta

from pydantic import BaseModel, ConfigDict, field_validator

from analyzer.dedupe import SeenStore

# Grafana sends nanosecond precision ("…20.945744196Z"); datetime keeps microseconds.
_FRACTION = re.compile(r"(\.\d{6})\d+")


class GrafanaAlert(BaseModel):
    model_config = ConfigDict(extra="ignore")

    status: str
    labels: dict[str, str] = {}
    annotations: dict[str, str] = {}
    startsAt: datetime
    fingerprint: str
    generatorURL: str | None = None
    values: dict[str, float | None] | None = None

    @field_validator("startsAt", mode="before")
    @classmethod
    def _trim_nanoseconds(cls, raw: object) -> object:
        if isinstance(raw, str):
            return _FRACTION.sub(r"\1", raw)
        return raw


class GrafanaWebhook(BaseModel):
    model_config = ConfigDict(extra="ignore")

    status: str
    groupKey: str
    externalURL: str | None = None
    alerts: list[GrafanaAlert] = []


@dataclass(frozen=True)
class AnalysisJob:
    group_key: str
    alerts: tuple[GrafanaAlert, ...]
    received_at: datetime


# Grafana's own "the query failed / returned nothing" alerts: the data source is
# the broken part, so reading it for context is pointless.
SKIPPED_ALERTNAMES = frozenset({"DatasourceError", "DatasourceNoData"})
SELF_APP = "alert-analyzer"
# Older alerts are hourly repeats of an episode that was already analysed (or
# missed while this service was down); analysing them late helps nobody.
MAX_ALERT_AGE = timedelta(minutes=10)


def dedupe_key(alert: GrafanaAlert) -> str:
    # The same alert keeps its fingerprint and startsAt across repeat
    # notifications, so this pair identifies one firing episode.
    return f"{alert.fingerprint}|{alert.startsAt.isoformat()}"


def skip_reason(alert: GrafanaAlert, now: datetime) -> str | None:
    if alert.status != "firing":
        return "not firing"
    if alert.labels.get("alertname") in SKIPPED_ALERTNAMES:
        return "datasource alert"
    # The ERROR-burst rule counts every app, this one included: never analyse ourselves.
    if alert.labels.get("app") == SELF_APP:
        return "own alert"
    if now - alert.startsAt > MAX_ALERT_AGE:
        return "too old"
    return None


def select_new(payload: GrafanaWebhook, seen: SeenStore, now: datetime) -> list[GrafanaAlert]:
    """Firing alerts worth analysing that this process has not taken yet. Marks them as seen."""
    if payload.status != "firing":
        return []
    fresh = []
    for alert in payload.alerts:
        if skip_reason(alert, now) is not None:
            continue
        if seen.check_and_add(dedupe_key(alert), now):
            fresh.append(alert)
    return fresh
