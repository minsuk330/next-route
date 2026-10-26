from __future__ import annotations

from datetime import datetime, timezone

import httpx2 as httpx
import pytest

from analyzer.sources import LokiClient, PromClient, SourceError

pytestmark = pytest.mark.anyio

AT = datetime(2026, 10, 23, 10, 1, tzinfo=timezone.utc)


def client_returning(status: int, body: dict, seen: list) -> httpx.AsyncClient:
    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(status, json=body)

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_loki_range_sends_window_in_nanoseconds():
    seen: list = []
    body = {"status": "success", "data": {"result": [{"stream": {}, "values": []}]}}
    async with client_returning(200, body, seen) as http:
        result = await LokiClient("http://loki:3100/", http).range('{app="worker"}', AT, AT, 100)

    request = seen[0]
    assert request.url.path == "/loki/api/v1/query_range"
    assert request.url.params["start"] == str(int(AT.timestamp() * 1_000_000_000))
    assert request.url.params["direction"] == "backward"
    assert request.url.params["limit"] == "100"
    assert result == [{"stream": {}, "values": []}]


async def test_prometheus_instant_sends_unix_seconds():
    seen: list = []
    body = {"status": "success", "data": {"result": []}}
    async with client_returning(200, body, seen) as http:
        await PromClient("http://prometheus:9090", http).instant("up", AT)

    assert seen[0].url.path == "/api/v1/query"
    assert seen[0].url.params["time"] == f"{AT.timestamp():.3f}"


async def test_http_error_raises_source_error():
    async with client_returning(502, {"status": "error"}, []) as http:
        with pytest.raises(SourceError, match="loki HTTP 502"):
            await LokiClient("http://loki:3100", http).instant("x", AT)


async def test_query_error_raises_source_error():
    body = {"status": "error", "error": "parse error"}
    async with client_returning(200, body, []) as http:
        with pytest.raises(SourceError, match="parse error"):
            await PromClient("http://prometheus:9090", http).instant("x(", AT)
