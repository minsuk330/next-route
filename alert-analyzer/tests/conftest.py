"""Shared fixtures. Run: uv run --extra dev pytest -q (from alert-analyzer/)."""

from __future__ import annotations

import json
from collections.abc import Callable
from datetime import datetime
from pathlib import Path

import pytest

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures"


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture
def grafana_payload() -> dict:
    return json.loads((FIXTURES / "grafana_webhook_firing.json").read_text(encoding="utf-8"))


class FakeLogs:
    """Loki stand-in. Each callable receives the query (and times) and returns a Loki `result`."""

    def __init__(
        self,
        instant: Callable[[str, datetime], list[dict]] | None = None,
        range_: Callable[[str, datetime, datetime, int], list[dict]] | None = None,
    ):
        self._instant = instant
        self._range = range_
        self.calls: list[tuple] = []

    async def instant(self, query: str, at: datetime) -> list[dict]:
        self.calls.append(("instant", query, at))
        return self._instant(query, at) if self._instant else []

    async def range(self, query: str, start: datetime, end: datetime, limit: int) -> list[dict]:
        self.calls.append(("range", query, start, end, limit))
        return self._range(query, start, end, limit) if self._range else []


class FakeMetrics:
    def __init__(self, instant: Callable[[str, datetime], list[dict]] | None = None):
        self._instant = instant
        self.calls: list[tuple] = []

    async def instant(self, query: str, at: datetime) -> list[dict]:
        self.calls.append(("instant", query, at))
        return self._instant(query, at) if self._instant else []


def stream(lines: list[dict], at: datetime, labels: dict | None = None) -> dict:
    """A Loki stream whose entries are 1 second apart, newest first."""
    base = int(at.timestamp() * 1_000_000_000)
    return {
        "stream": labels or {},
        "values": [[str(base - i * 1_000_000_000), json.dumps(line, ensure_ascii=False)] for i, line in enumerate(lines)],
    }


def vector(metric: dict, value: float) -> dict:
    return {"metric": metric, "value": [0, str(value)]}
