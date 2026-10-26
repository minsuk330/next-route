"""Runtime settings, read once from the environment."""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass


def _bool(raw: str | None, default: bool) -> bool:
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    webhook_token: str
    loki_url: str = "http://loki:3100"
    prom_url: str = "http://prometheus:9090"
    grafana_url: str = "https://grafana.refit-100.site"
    dry_run: bool = True
    daily_limit: int = 30
    queue_max: int = 20

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "Settings":
        env = os.environ if env is None else env
        token = env.get("ANALYZER_WEBHOOK_TOKEN", "").strip()
        if not token:
            raise RuntimeError("ANALYZER_WEBHOOK_TOKEN is required")
        dry_run = _bool(env.get("ANALYZER_DRY_RUN"), True)
        # LLM analysis and Slack posting arrive in the next PR. Until then the only
        # supported mode is dry run, so refuse to start rather than silently ignore.
        if not dry_run:
            raise RuntimeError("ANALYZER_DRY_RUN=false is not supported yet (no LLM stage)")
        return cls(
            webhook_token=token,
            loki_url=env.get("ANALYZER_LOKI_URL", cls.loki_url),
            prom_url=env.get("ANALYZER_PROM_URL", cls.prom_url),
            grafana_url=env.get("ANALYZER_GRAFANA_URL", cls.grafana_url),
            dry_run=dry_run,
            daily_limit=int(env.get("ANALYZER_DAILY_LIMIT", cls.daily_limit)),
            queue_max=int(env.get("ANALYZER_QUEUE_MAX", cls.queue_max)),
        )
