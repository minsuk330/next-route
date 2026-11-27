"""Strip secrets and personal data before anything leaves the VPS.

Every string that goes to the LLM or Slack passes through `mask`. A WARN/ERROR
sample of 300 production lines (2026-10-26) had none of these patterns, so this
is a safety net, not a filter we expect to fire often. Order matters: specific
token shapes run before the generic key=value rule.
"""

from __future__ import annotations

import re
from dataclasses import replace
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from analyzer.context import ContextBundle

_RULES: list[tuple[str, re.Pattern[str], str]] = [
    ("slack", re.compile(r"xox[abprs]-[A-Za-z0-9-]+|xapp-[A-Za-z0-9-]+|hooks\.slack\.com/services/[A-Za-z0-9/_-]+"), "<slack-secret>"),
    ("anthropic", re.compile(r"sk-ant-[A-Za-z0-9_-]+"), "<anthropic-key>"),
    ("jwt", re.compile(r"eyJ[\w-]+\.[\w-]+\.[\w-]+"), "<jwt>"),
    ("bearer", re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+"), "Bearer <redacted>"),
    ("api_key", re.compile(r"(?i)\b(servicekey|apikey|api_key|key|token|secret|password)=[^&\s\"']{6,}"), r"\1=<redacted>"),
    # Seoul open-data APIs put the key in the path (/api/subway/{KEY}/json/…,
    # :8088/{KEY}/…) and SeoulSubwayApiAdapter logs uri.getPath(). Production
    # Loki had the arrival key in 112 of 200 such lines (2026-10-26). Keys mix
    # letters and digits; long endpoint names (realtimeStationArrival) do not.
    (
        "url_path_secret",
        re.compile(r"(?<=/)(?=[A-Za-z0-9]*\d)(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{16,}(?=[/?#&\"'\s\]]|$)"),
        "<redacted>",
    ),
    ("toss_key", re.compile(r"(?i)\b(tossUserKey)[\"']?\s*[=:]\s*[\"']?[\w-]+"), r"\1=<redacted>"),
    ("email", re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "<email>"),
    ("phone", re.compile(r"\b01[016789]-?\d{3,4}-?\d{4}\b"), "<phone>"),
    # Korean latitude 33–38, longitude 124–132: keep two decimals (about 1 km).
    ("coord", re.compile(r"\b(3[3-8]|12[4-9]|13[0-2])\.(\d{2})\d{2,}"), r"\1.\2"),
    ("uuid", re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"), "<uuid>"),
]

RULE_NAMES = [name for name, _, _ in _RULES]
# Source code: secrets only. Coordinates, uuids and the like are code literals there,
# and rewriting them would make the snippet lie.
_CODE_RULES = [r for r in _RULES if r[0] in {"slack", "anthropic", "jwt", "api_key"}] + [
    # Only token-shaped values: `BEARER = "Bearer ";` in JwtAuthFilter must stay as written.
    ("bearer", re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{20,}"), "Bearer <redacted>"),
]


def mask_code(text: str) -> str:
    for _, pattern, replacement in _CODE_RULES:
        text = pattern.sub(replacement, text)
    return text


def mask(text: str) -> str:
    for _, pattern, replacement in _RULES:
        text = pattern.sub(replacement, text)
    return text


def _mask_obj(value: object) -> object:
    if isinstance(value, str):
        return mask(value)
    if isinstance(value, dict):
        return {k: _mask_obj(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_mask_obj(v) for v in value]
    return value


def mask_bundle(bundle: "ContextBundle") -> "ContextBundle":
    return replace(
        bundle,
        alerts=_mask_obj(bundle.alerts),
        templates=[
            replace(t, template=mask(t.template), samples=[mask(s) for s in t.samples])
            for t in bundle.templates
        ],
        quiet_summary=[mask(s) for s in bundle.quiet_summary],
        collection_errors=[mask(s) for s in bundle.collection_errors],
        code=[{**c, "body": mask_code(c.get("body", ""))} for c in bundle.code],
    )
