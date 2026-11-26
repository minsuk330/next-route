from __future__ import annotations

import asyncio
from datetime import datetime, timezone

import httpx2 as httpx
import pytest

from analyzer import probe
from analyzer.probe import (
    BUS, DEGRADED, FAILED, OK, SKIPPED, SUBWAY, WALK,
    ProbeState, Scenario, StepResult, bus_target, in_service_hours, probe_loop, probe_once,
    render_metrics, run_scenario, subway_target,
)

pytestmark = pytest.mark.anyio

BASE = "https://api.test"
SUB = Scenario("subway", "subway", (126.97, 37.55), (127.02, 37.49), "서울역", "강남역")
BUSSC = Scenario("bus", "bus", (127.02, 37.51), (126.98, 37.56), "신사역", "소공동")
NOON = datetime(2026, 11, 26, 3, 0, tzinfo=timezone.utc)  # 12:00 KST

SUBWAY_SEG = {"trafficType": SUBWAY, "wayCode": 1, "startX": 126.97, "startY": 37.55,
              "lanes": [{"subwayCode": 4, "name": "수도권 4호선"}]}
BUS_SEG = {"trafficType": BUS, "startLocalStationID": "121000123",
           "lanes": [{"busNo": "4212", "busLocalBlID": "100100999"}],
           "transferArrivals": [{"laneIndex": 0, "routeId": "100100555"}]}
WALK_SEG = {"trafficType": WALK, "endX": 126.9, "endY": 37.5}


def search_body(*segments) -> dict:
    return {"paths": [{"subPaths": list(segments)}]}


class Fake:
    """Routes by path. Each value is a Response, an exception, or a list consumed one per call."""

    def __init__(self, routes: dict):
        self.routes = routes
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        for prefix, answer in self.routes.items():
            if request.url.path.startswith(prefix):
                if isinstance(answer, list):
                    answer = answer.pop(0)
                if isinstance(answer, Exception):
                    raise answer
                return answer
        return httpx.Response(404)


def client(fake: Fake) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(fake))


def ok(json) -> httpx.Response:
    return httpx.Response(200, json=json)


async def run(fake: Fake, sc: Scenario = SUB) -> dict[str, StepResult]:
    async with client(fake) as http:
        return await run_scenario(http, BASE, sc)


async def test_subway_happy_path_calls_arrivals_like_the_app():
    fake = Fake({"/api/route/search": ok(search_body(WALK_SEG, SUBWAY_SEG)), "/api/arrivals/subway": ok([])})

    results = await run(fake)

    assert results["search"].outcome == OK and results["arrival"].outcome == OK
    arrival = fake.requests[1]
    assert arrival.url.params["lat"] == "37.55" and arrival.url.params["lon"] == "126.97"
    assert arrival.url.params["wayCode"] == "1" and arrival.url.params["line_id"] == "1004"


async def test_bus_uses_live_route_id_and_boarding_stop():
    fake = Fake({"/api/route/search": ok(search_body(BUS_SEG)), "/api/arrivals/bus/": ok([{"arrivalMsg1": "도착정보없음"}])})

    results = await run(fake, BUSSC)

    assert results == {"search": StepResult(OK, results["search"].duration_s), "arrival": StepResult(OK, results["arrival"].duration_s)}
    assert fake.requests[1].url.path == "/api/arrivals/bus/121000123/100100555"


async def test_every_request_is_marked_synthetic():
    fake = Fake({"/api/route/search": ok(search_body(SUBWAY_SEG)), "/api/arrivals/subway": ok([])})

    await run(fake)

    assert len(fake.requests) == 2
    assert all(r.headers["X-Nextroute-Synthetic"] == "1" for r in fake.requests)
    assert all(r.headers["User-Agent"] == "nextroute-synthetic/1" for r in fake.requests)


async def test_search_5xx_fails_and_skips_arrival():
    fake = Fake({"/api/route/search": httpx.Response(503)})

    results = await run(fake)

    assert results["search"] == StepResult(FAILED, results["search"].duration_s, "http_503")
    assert results["arrival"].outcome == SKIPPED
    assert len(fake.requests) == 1


async def test_search_timeout_fails():
    fake = Fake({"/api/route/search": httpx.ReadTimeout("slow")})

    results = await run(fake)

    assert (results["search"].outcome, results["search"].reason) == (FAILED, "timeout")


async def test_no_paths_is_degraded():
    results = await run(Fake({"/api/route/search": ok({"paths": []})}))

    assert (results["search"].outcome, results["search"].reason) == (DEGRADED, "no_paths")
    assert results["arrival"].outcome == SKIPPED


async def test_walk_only_in_service_hours_is_degraded():
    results = await run(Fake({"/api/route/search": ok(search_body(WALK_SEG))}))

    assert (results["search"].outcome, results["search"].reason) == (DEGRADED, "no_subway_segment")


async def test_empty_arrivals_are_normal():
    fake = Fake({"/api/route/search": ok(search_body(SUBWAY_SEG)), "/api/arrivals/subway": ok([])})

    assert (await run(fake))["arrival"].outcome == OK


async def test_arrival_500_fails():
    fake = Fake({"/api/route/search": ok(search_body(SUBWAY_SEG)), "/api/arrivals/subway": httpx.Response(500)})

    results = await run(fake)

    assert results["search"].outcome == OK
    assert (results["arrival"].outcome, results["arrival"].reason) == (FAILED, "http_500")


async def test_arrival_not_a_list_is_degraded():
    fake = Fake({"/api/route/search": ok(search_body(SUBWAY_SEG)), "/api/arrivals/subway": ok({"error": True})})

    assert (await run(fake))["arrival"].reason == "bad_body"


async def test_missing_arrival_target_is_degraded_without_calling():
    broken = {**BUS_SEG, "startLocalStationID": None}
    fake = Fake({"/api/route/search": ok(search_body(broken))})

    results = await run(fake, BUSSC)

    assert (results["arrival"].outcome, results["arrival"].reason) == (DEGRADED, "no_bus_target")
    assert len(fake.requests) == 1


def test_bus_target_falls_back_to_lane_route_id():
    seg = {**BUS_SEG, "transferArrivals": []}

    assert bus_target(seg) == ("121000123", "100100999")


def test_subway_target_falls_back_to_first_station_then_previous_end():
    no_start = {**SUBWAY_SEG, "startX": None, "startY": None, "stations": [{"x": 127.0, "y": 37.6}]}
    assert subway_target(no_start, None)["lat"] == 37.6
    nothing = {**SUBWAY_SEG, "startX": None, "startY": None}
    assert subway_target(nothing, WALK_SEG)["lon"] == 126.9
    assert subway_target({**SUBWAY_SEG, "wayCode": None}, None) is None
    assert "line_id" not in subway_target({**SUBWAY_SEG, "lanes": [{"subwayCode": 104}]}, None)


@pytest.mark.parametrize(
    "kst, expected",
    [((5, 29), False), ((5, 30), True), ((23, 59), True), ((0, 0), False), ((3, 0), False)],
)
def test_service_hours_kst(kst, expected):
    hour, minute = kst
    utc = datetime(2026, 11, 26, (hour - 9) % 24, minute, tzinfo=timezone.utc)

    assert in_service_hours(utc) is expected


async def test_retry_after_a_minute_publishes_the_retry():
    fake = Fake({
        "/api/route/search": [httpx.Response(502), ok(search_body(SUBWAY_SEG))],
        "/api/arrivals/subway": ok([]),
    })
    sleeps: list[float] = []

    async def sleep(seconds):
        sleeps.append(seconds)

    state = ProbeState()
    async with client(fake) as http:
        await probe_once(http, BASE, SUB, state, clock=lambda: NOON, sleep=sleep)

    assert sleeps == [60.0]
    assert state.results[("subway", "search")].outcome == OK
    assert state.runs[("subway", OK)] == 1
    assert state.last_run["subway"] == NOON.timestamp()


async def test_failure_twice_is_published():
    fake = Fake({"/api/route/search": [httpx.Response(502), httpx.Response(502)]})

    async def sleep(_):
        pass

    state = ProbeState()
    async with client(fake) as http:
        await probe_once(http, BASE, SUB, state, clock=lambda: NOON, sleep=sleep)

    assert state.results[("subway", "search")] .outcome == FAILED
    assert state.results[("subway", "arrival")].outcome == SKIPPED
    assert state.runs[("subway", FAILED)] == 1


async def test_ok_run_does_not_retry():
    fake = Fake({"/api/route/search": ok(search_body(SUBWAY_SEG)), "/api/arrivals/subway": ok([])})
    sleeps: list[float] = []

    async def sleep(seconds):
        sleeps.append(seconds)

    async with client(fake) as http:
        await probe_once(http, BASE, SUB, ProbeState(), clock=lambda: NOON, sleep=sleep)

    assert sleeps == []


class Stop(Exception):
    pass


async def test_loop_off_hours_only_beats_and_clears_results():
    night = datetime(2026, 11, 26, 17, 0, tzinfo=timezone.utc)  # 02:00 KST
    fake = Fake({})
    state = ProbeState(results={("subway", "search"): StepResult(FAILED, 1.0, "http_503")})

    async def sleep(_):
        raise Stop

    async with client(fake) as http:
        with pytest.raises(Stop):
            await probe_loop(http, BASE, 600, state, clock=lambda: night, sleep=sleep)

    assert fake.requests == []
    assert state.results == {} and state.active is False
    assert state.heartbeat == night.timestamp()


async def test_loop_survives_a_crashing_scenario(monkeypatch):
    async def boom(*args, **kwargs):
        raise RuntimeError("bug")

    monkeypatch.setattr(probe, "probe_once", boom)
    calls: list[float] = []

    async def sleep(seconds):
        calls.append(seconds)
        raise Stop

    state = ProbeState()
    async with client(Fake({})) as http:
        with pytest.raises(Stop):
            await probe_loop(http, BASE, 600, state, clock=lambda: NOON, sleep=sleep, scenarios=(SUB, BUSSC))

    assert calls == [600] and state.active is True


def test_render_metrics_text():
    state = ProbeState(
        results={("bus", "search"): StepResult(FAILED, 2.5, "http_503"), ("bus", "arrival"): StepResult(SKIPPED, 0, "search_failed")},
        last_run={"bus": 1764126000.0},
        heartbeat=1764126001.0,
        active=True,
    )
    state.runs[("bus", FAILED)] += 1
    state.slow["subway"] += 2

    text = render_metrics(state)

    assert 'synthetic_probe_outcome{scenario="bus",step="search",reason="http_503"} 2' in text
    assert 'synthetic_probe_duration_seconds{scenario="bus",step="search"} 2.5' in text
    assert 'synthetic_probe_heartbeat_timestamp_seconds 1.76413e+09' not in text
    assert "synthetic_probe_heartbeat_timestamp_seconds 1764126001" in text
    assert "synthetic_probe_active 1" in text
    assert 'synthetic_probe_runs_total{scenario="bus",outcome="2"} 1' in text
    assert 'synthetic_probe_slow_total{scenario="subway"} 2' in text
    assert text.endswith("\n")


async def test_connect_error_fails():
    results = await run(Fake({"/api/route/search": httpx.ConnectError("refused")}))

    assert (results["search"].outcome, results["search"].reason) == (FAILED, "connect")


async def test_search_not_json_is_degraded():
    results = await run(Fake({"/api/route/search": httpx.Response(200, text="<html>bad gateway page</html>")}))

    assert (results["search"].outcome, results["search"].reason) == (DEGRADED, "bad_body")


async def test_bus_scenario_without_bus_segment_is_degraded():
    results = await run(Fake({"/api/route/search": ok(search_body(WALK_SEG, SUBWAY_SEG))}), BUSSC)

    assert results["search"].reason == "no_bus_segment"


async def test_malformed_shape_is_degraded_not_a_crash():
    weird = {**BUS_SEG, "transferArrivals": [None]}
    results = await run(Fake({"/api/route/search": ok({"paths": [None, {"subPaths": [weird]}]})}), BUSSC)

    assert (results["search"].outcome, results["search"].reason) == (DEGRADED, "bad_body")


async def test_slow_successful_search_is_counted_but_ok(monkeypatch):
    fake = Fake({"/api/route/search": ok(search_body(SUBWAY_SEG)), "/api/arrivals/subway": ok([])})
    ticks = iter([0.0, 12.0, 12.0, 12.5, 12.5, 12.5])
    original = probe.run_scenario

    async def timed(http, base, sc):
        return await original(http, base, sc, monotonic=lambda: next(ticks))

    monkeypatch.setattr(probe, "run_scenario", timed)

    async def sleep(_):
        pass

    state = ProbeState()
    async with client(fake) as http:
        await probe_once(http, BASE, SUB, state, clock=lambda: NOON, sleep=sleep)

    assert state.results[("subway", "search")].outcome == OK
    assert state.slow["subway"] == 1
