"""Thin HTTP clients for Loki and Prometheus.

Both are unauthenticated and only reachable on the observability network. Every
call has a short timeout; the caller records failures instead of retrying.
"""

from __future__ import annotations

from datetime import datetime

import httpx2 as httpx

TIMEOUT = httpx.Timeout(5.0)


class SourceError(Exception):
    pass


def _result(response: httpx.Response, source: str) -> list[dict]:
    if response.status_code != 200:
        raise SourceError(f"{source} HTTP {response.status_code}: {response.text[:200]}")
    body = response.json()
    if body.get("status") != "success":
        raise SourceError(f"{source} status {body.get('status')}: {body.get('error', '')[:200]}")
    return body["data"]["result"]


class LokiClient:
    def __init__(self, base_url: str, client: httpx.AsyncClient):
        self._base = base_url.rstrip("/")
        self._client = client

    async def instant(self, query: str, at: datetime) -> list[dict]:
        response = await self._client.get(
            f"{self._base}/loki/api/v1/query",
            params={"query": query, "time": _ns(at)},
            timeout=TIMEOUT,
        )
        return _result(response, "loki")

    async def range(self, query: str, start: datetime, end: datetime, limit: int) -> list[dict]:
        response = await self._client.get(
            f"{self._base}/loki/api/v1/query_range",
            params={
                "query": query,
                "start": _ns(start),
                "end": _ns(end),
                "limit": limit,
                "direction": "backward",
            },
            timeout=TIMEOUT,
        )
        return _result(response, "loki")


class PromClient:
    def __init__(self, base_url: str, client: httpx.AsyncClient):
        self._base = base_url.rstrip("/")
        self._client = client

    async def instant(self, query: str, at: datetime) -> list[dict]:
        response = await self._client.get(
            f"{self._base}/api/v1/query",
            params={"query": query, "time": f"{at.timestamp():.3f}"},
            timeout=TIMEOUT,
        )
        return _result(response, "prometheus")


def _ns(at: datetime) -> str:
    return str(int(at.timestamp() * 1_000_000_000))
