"""Runtime settings, read once from the environment."""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass, field

# Needed only when the analyzer actually calls Claude and posts to Slack.
LIVE_ONLY = ("ANTHROPIC_API_KEY", "SLACK_BOT_TOKEN", "SLACK_CHANNEL_ID")


def _bool(raw: str | None, default: bool) -> bool:
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    # Secrets stay out of repr so a logged Settings never leaks them.
    webhook_token: str = field(repr=False)
    loki_url: str = "http://loki:3100"
    prom_url: str = "http://prometheus:9090"
    grafana_url: str = "https://grafana.jettae.site"
    dry_run: bool = True
    daily_limit: int = 30
    queue_max: int = 20
    anthropic_api_key: str = field(default="", repr=False)
    slack_bot_token: str = field(default="", repr=False)
    slack_channel_id: str = ""
    # Synthetic monitoring (analyzer.probe). Off unless asked: it calls the production API.
    probe_enabled: bool = False
    probe_base_url: str = "https://api.jettae.site"
    probe_interval_s: float = 600.0
    # Deployed-commit code in the analysis (analyzer.code). Off until a GitHub read token is set.
    code_context_enabled: bool = False
    github_repo: str = "minsuk330/next-route"
    github_token: str = field(default="", repr=False)
    code_cache_dir: str = "/tmp/code-cache"
    # Stage 4: propose a code change in the thread (analyzer.fix). Needs the code context.
    fix_proposal_enabled: bool = False

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "Settings":
        env = os.environ if env is None else env
        token = env.get("ANALYZER_WEBHOOK_TOKEN", "").strip()
        if not token:
            raise RuntimeError("ANALYZER_WEBHOOK_TOKEN is required")
        dry_run = _bool(env.get("ANALYZER_DRY_RUN"), True)
        live = {name: env.get(name, "").strip() for name in LIVE_ONLY}
        missing = [name for name, value in live.items() if not value]
        # Fail at startup, not on the first alert, when live mode lacks a key.
        if not dry_run and missing:
            raise RuntimeError(f"ANALYZER_DRY_RUN=false needs {', '.join(missing)}")
        code_enabled = _bool(env.get("ANALYZER_CODE_CONTEXT_ENABLED"), False)
        github_token = env.get("ANALYZER_GITHUB_TOKEN", "").strip()
        if code_enabled and not github_token:
            raise RuntimeError("ANALYZER_CODE_CONTEXT_ENABLED=true needs ANALYZER_GITHUB_TOKEN")
        fix_enabled = _bool(env.get("ANALYZER_FIX_PROPOSAL_ENABLED"), False)
        if fix_enabled and not code_enabled:
            raise RuntimeError("ANALYZER_FIX_PROPOSAL_ENABLED=true needs ANALYZER_CODE_CONTEXT_ENABLED=true")
        return cls(
            webhook_token=token,
            loki_url=env.get("ANALYZER_LOKI_URL", cls.loki_url),
            prom_url=env.get("ANALYZER_PROM_URL", cls.prom_url),
            grafana_url=env.get("ANALYZER_GRAFANA_URL", cls.grafana_url),
            dry_run=dry_run,
            daily_limit=int(env.get("ANALYZER_DAILY_LIMIT", cls.daily_limit)),
            queue_max=int(env.get("ANALYZER_QUEUE_MAX", cls.queue_max)),
            anthropic_api_key=live["ANTHROPIC_API_KEY"],
            slack_bot_token=live["SLACK_BOT_TOKEN"],
            slack_channel_id=live["SLACK_CHANNEL_ID"],
            probe_enabled=_bool(env.get("ANALYZER_PROBE_ENABLED"), False),
            probe_base_url=env.get("ANALYZER_PROBE_BASE_URL", cls.probe_base_url),
            probe_interval_s=float(env.get("ANALYZER_PROBE_INTERVAL_S", cls.probe_interval_s)),
            code_context_enabled=code_enabled,
            github_repo=env.get("ANALYZER_GITHUB_REPO", cls.github_repo),
            github_token=github_token,
            code_cache_dir=env.get("ANALYZER_CODE_CACHE_DIR", cls.code_cache_dir),
            fix_proposal_enabled=fix_enabled,
        )
