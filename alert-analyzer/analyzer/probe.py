"""Synthetic monitoring: call the app the way a rider does, every 10 minutes in service hours.

Real traffic is too thin (about 20 route searches a day, no 5xx in a week) for the request-based
rules to notice a broken feature, and when riders give up the error count drops and the alert
resolves on its own. So this loop plays the rider: search a route, take the first ride leg of the
scenario's kind, and ask for that leg's live arrivals with the exact parameters the app would send
(nextroute-mobile src/features/transit/route-live-arrivals.ts). Results become Prometheus gauges
that Grafana rules turn into Slack alerts (docs/plan/synthetic-monitoring).

Outcome per step: 0 ok, 1 degraded (200 but unusable), 2 failed (HTTP error, timeout), 3 skipped
(the search before it did not succeed). "No arrival info" is a normal answer, not a failure.
A failed or degraded run is retried once after a minute; only the retry is published.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections import Counter
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime, time as dtime
from typing import Literal

import httpx2 as httpx

from analyzer.dedupe import KST

log = logging.getLogger(__name__)

OK, DEGRADED, FAILED, SKIPPED = 0, 1, 2, 3

HEADER = "X-Nextroute-Synthetic"  # api: common/observability/SyntheticRequest.java
HEADERS = {HEADER: "1", "User-Agent": "nextroute-synthetic/1"}
SEARCH_TIMEOUT_S = 15.0
ARRIVAL_TIMEOUT_S = 10.0
SLOW_S = 10.0
RETRY_DELAY_S = 60.0
SERVICE_START = dtime(5, 30)  # until midnight KST

SUBWAY, BUS, WALK = 1, 2, 3  # trafficType in the search response

Kind = Literal["subway", "bus"]


@dataclass(frozen=True)
class Scenario:
    name: str
    kind: Kind
    start: tuple[float, float]  # (lon, lat)
    end: tuple[float, float]
    start_name: str
    end_name: str


# Chosen so the kind's ride shows up in the results through the day (checked against production,
# see implementation_note.md). Seoul stops only, so the bus run spends no Gyeonggi/TAGO quota.
SCENARIOS: tuple[Scenario, ...] = (
    Scenario("subway", "subway", (126.9707, 37.5547), (127.0276, 37.4979), "서울역", "강남역"),
    Scenario("bus", "bus", (127.0201, 37.5163), (126.9800, 37.5640), "신사역", "소공동"),
)


@dataclass(frozen=True)
class StepResult:
    outcome: int
    duration_s: float = 0.0
    reason: str = ""


@dataclass
class ProbeState:
    results: dict[tuple[str, str], StepResult] = field(default_factory=dict)
    last_run: dict[str, float] = field(default_factory=dict)
    heartbeat: float | None = None
    active: bool = False
    runs: Counter = field(default_factory=Counter)  # (scenario, worst outcome) -> count
    slow: Counter = field(default_factory=Counter)  # scenario -> searches at or over SLOW_S


def in_service_hours(now: datetime) -> bool:
    return now.astimezone(KST).time() >= SERVICE_START


def worst(results: dict[str, StepResult]) -> int:
    """The outcome that decides retry and the run counter. Skipped never wins over a real failure."""
    real = [r.outcome for r in results.values() if r.outcome != SKIPPED]
    return max(real, default=OK)


# --- reading the search response (same rules as the app) ------------------------------------------


def find_ride(paths: list, kind: Kind) -> tuple[dict, dict | None] | None:
    """First path that has a ride of this kind: (that segment, the one before it)."""
    want = SUBWAY if kind == "subway" else BUS
    for path in paths:
        segments = (path or {}).get("subPaths") or []
        for index, segment in enumerate(segments):
            if (segment or {}).get("trafficType") == want:
                return segment, segments[index - 1] if index > 0 else None
    return None


def bus_target(segment: dict) -> tuple[str, str] | None:
    """route-live-arrivals.ts getBusArrivalTargets, first lane only."""
    stop_id = segment.get("startLocalStationID")
    lanes = segment.get("lanes") or []
    if not stop_id or not lanes:
        return None
    live = next((a for a in segment.get("transferArrivals") or [] if a.get("laneIndex") == 0), None)
    route_id = (live or {}).get("routeId") or lanes[0].get("busLocalBlID")
    return (str(stop_id), str(route_id)) if route_id else None


def _finite(*values) -> bool:
    return all(isinstance(v, (int, float)) and v == v and abs(v) != float("inf") for v in values)


def subway_target(segment: dict, previous: dict | None) -> dict | None:
    """route-live-arrivals.ts getSubwayArrivalTarget: start, else first station, else previous end."""
    way_code = segment.get("wayCode")
    if way_code is None:
        return None
    stations = segment.get("stations") or []
    first = stations[0] if stations else {}
    candidates = [
        (segment.get("startY"), segment.get("startX")),
        (first.get("y"), first.get("x")),
        ((previous or {}).get("endY"), (previous or {}).get("endX")),
    ]
    coords = next(((lat, lon) for lat, lon in candidates if _finite(lat, lon)), None)
    if coords is None:
        return None
    params = {"lat": coords[0], "lon": coords[1], "wayCode": way_code}
    code = ((segment.get("lanes") or [{}])[0] or {}).get("subwayCode")
    if isinstance(code, int) and 1 <= code <= 9:
        params["line_id"] = str(1000 + code)
    return params


# --- calling ---------------------------------------------------------------------------------------


async def _get(http, url: str, params: dict, timeout: float, monotonic) -> tuple[StepResult | None, object, float]:
    """(failure or None, parsed JSON, seconds). Only transport and status problems are failures here."""
    started = monotonic()
    try:
        # httpx timeouts are per phase (connect, each read); this caps the whole request.
        async with asyncio.timeout(timeout):
            response = await http.get(url, params=params, headers=HEADERS, timeout=timeout)
    except (httpx.TimeoutException, TimeoutError):
        return StepResult(FAILED, monotonic() - started, "timeout"), None, monotonic() - started
    except httpx.HTTPError:
        return StepResult(FAILED, monotonic() - started, "connect"), None, monotonic() - started
    elapsed = monotonic() - started
    if not 200 <= response.status_code < 300:
        return StepResult(FAILED, elapsed, f"http_{response.status_code}"), None, elapsed
    try:
        return None, response.json(), elapsed
    except ValueError:
        return StepResult(DEGRADED, elapsed, "bad_body"), None, elapsed


async def run_scenario(http, base_url: str, sc: Scenario, monotonic=time.monotonic) -> dict[str, StepResult]:
    try:
        return await _run_scenario(http, base_url, sc, monotonic)
    except (AttributeError, TypeError, KeyError, IndexError) as exc:
        # A response shaped differently from the contract (null where an object was expected...).
        # Published as degraded instead of crashing the run, which would leave the previous outcome up.
        log.warning("probe %s unexpected response shape: %r", sc.name, exc)
        return {"search": StepResult(DEGRADED, 0.0, "bad_body"), "arrival": StepResult(SKIPPED, 0.0, "search_failed")}


async def _run_scenario(http, base_url: str, sc: Scenario, monotonic) -> dict[str, StepResult]:
    base = base_url.rstrip("/")
    params = {
        "startX": sc.start[0], "startY": sc.start[1], "endX": sc.end[0], "endY": sc.end[1],
        "startName": sc.start_name, "endName": sc.end_name,
    }
    failure, body, elapsed = await _get(http, f"{base}/api/route/search", params, SEARCH_TIMEOUT_S, monotonic)
    if failure is not None:
        return {"search": failure, "arrival": StepResult(SKIPPED, 0.0, "search_failed")}
    paths = (body or {}).get("paths") if isinstance(body, dict) else None
    if not paths:
        return {"search": StepResult(DEGRADED, elapsed, "no_paths"), "arrival": StepResult(SKIPPED, 0.0, "search_failed")}
    ride = find_ride(paths, sc.kind)
    if ride is None:
        return {
            "search": StepResult(DEGRADED, elapsed, f"no_{sc.kind}_segment"),
            "arrival": StepResult(SKIPPED, 0.0, "search_failed"),
        }
    search = StepResult(OK, elapsed)

    segment, previous = ride
    if sc.kind == "bus":
        target = bus_target(segment)
        url = f"{base}/api/arrivals/bus/{target[0]}/{target[1]}" if target else None
        query: dict = {}
    else:
        query = subway_target(segment, previous) or {}
        url = f"{base}/api/arrivals/subway" if query else None
    if url is None:
        # The search answered but without what the app needs to ask for arrivals: a broken contract (#627).
        return {"search": search, "arrival": StepResult(DEGRADED, 0.0, f"no_{sc.kind}_target")}
    failure, body, elapsed = await _get(http, url, query, ARRIVAL_TIMEOUT_S, monotonic)
    if failure is not None:
        return {"search": search, "arrival": failure}
    if not isinstance(body, list):
        return {"search": search, "arrival": StepResult(DEGRADED, elapsed, "bad_body")}
    return {"search": search, "arrival": StepResult(OK, elapsed)}


Sleep = Callable[[float], Awaitable[None]]


async def probe_once(http, base_url: str, sc: Scenario, state: ProbeState, *, clock, sleep: Sleep) -> None:
    results = await run_scenario(http, base_url, sc)
    if worst(results) in (DEGRADED, FAILED):
        log.info("probe %s failed once, retrying in %.0fs: %s", sc.name, RETRY_DELAY_S, _describe(results))
        await sleep(RETRY_DELAY_S)
        results = await run_scenario(http, base_url, sc)
    for step, result in results.items():
        state.results[(sc.name, step)] = result
    state.last_run[sc.name] = clock().timestamp()
    outcome = worst(results)
    state.runs[(sc.name, outcome)] += 1
    if results["search"].outcome == OK and results["search"].duration_s >= SLOW_S:
        state.slow[sc.name] += 1
    level = logging.INFO if outcome == OK else logging.WARNING
    log.log(level, "probe %s outcome=%d %s", sc.name, outcome, _describe(results))


async def probe_loop(http, base_url: str, interval_s: float, state: ProbeState, *, clock, sleep: Sleep,
                     scenarios: tuple[Scenario, ...] = SCENARIOS) -> None:
    while True:
        now = clock()
        state.heartbeat = now.timestamp()
        state.active = in_service_hours(now)
        if state.active:
            for sc in scenarios:
                try:
                    await probe_once(http, base_url, sc, state, clock=clock, sleep=sleep)
                except asyncio.CancelledError:
                    raise
                except Exception:  # noqa: BLE001 — one bad run must not stop the loop
                    log.exception("probe %s crashed", sc.name)
                state.heartbeat = clock().timestamp()
        else:
            # Off hours: drop last day's results so a stale failure cannot fire at 05:30 before the first run.
            state.results.clear()
        await sleep(interval_s)


def _describe(results: dict[str, StepResult]) -> str:
    return " ".join(f"{step}={r.outcome}:{r.reason or 'ok'}:{r.duration_s:.1f}s" for step, r in results.items())


# --- exposition ------------------------------------------------------------------------------------


def _labels(**labels: str) -> str:
    inner = ",".join(f'{k}="{str(v).replace(chr(92), chr(92) * 2).replace(chr(34), chr(92) + chr(34))}"'
                     for k, v in labels.items())
    return "{" + inner + "}"


def _num(value: float) -> str:
    """Full precision: '%g' would turn a unix timestamp into 1.76413e+09."""
    value = float(value)
    return str(int(value)) if value.is_integer() else repr(value)


def render_metrics(state: ProbeState) -> str:
    out: list[str] = []

    def block(name: str, kind: str, help_: str, samples: list[tuple[str, float]]) -> None:
        out.append(f"# HELP {name} {help_}")
        out.append(f"# TYPE {name} {kind}")
        out.extend(f"{name}{labels} {_num(value)}" for labels, value in samples)

    block("synthetic_probe_outcome", "gauge", "Last published outcome: 0 ok, 1 degraded, 2 failed, 3 skipped.",
          [(_labels(scenario=s, step=st, reason=r.reason), r.outcome) for (s, st), r in sorted(state.results.items())])
    block("synthetic_probe_duration_seconds", "gauge", "Duration of the last published attempt.",
          [(_labels(scenario=s, step=st), r.duration_s) for (s, st), r in sorted(state.results.items())])
    block("synthetic_probe_last_run_timestamp_seconds", "gauge", "When a scenario last finished (unix).",
          [(_labels(scenario=s), t) for s, t in sorted(state.last_run.items())])
    block("synthetic_probe_heartbeat_timestamp_seconds", "gauge", "When the loop last woke up (unix), off hours too.",
          [("", state.heartbeat)] if state.heartbeat is not None else [])
    block("synthetic_probe_active", "gauge", "1 in service hours (05:30-24:00 KST).", [("", 1 if state.active else 0)])
    block("synthetic_probe_runs_total", "counter", "Published runs by worst outcome.",
          [(_labels(scenario=s, outcome=str(o)), n) for (s, o), n in sorted(state.runs.items())])
    block("synthetic_probe_slow_total", "counter", f"Successful searches that took {SLOW_S:g}s or more.",
          [(_labels(scenario=s), n) for s, n in sorted(state.slow.items())])
    return "\n".join(out) + "\n"
