"""Collect what was happening around an alert, without any LLM.

The bundle is meant to be small and honest: which log templates are new or
spiking compared with the hour before, a few metric values, and whether a
process restarted. Raw log volume is large (worker WARN ~22k lines/day, almost
all one template), so lines are grouped into templates and counted, never
copied wholesale.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
from dataclasses import asdict, dataclass, field, replace
from datetime import datetime, timedelta, timezone
from typing import Literal, Protocol

from analyzer.cards import Card
from analyzer.cards import select as select_cards
from analyzer.code import MAX_FRAMES, frames_from_stack
from analyzer.masking import mask
from analyzer.payload import AnalysisJob, GrafanaAlert

Status = Literal["NEW", "SPIKE", "NORMAL"]

WINDOW_BEFORE = timedelta(minutes=10)
WINDOW_AFTER = timedelta(minutes=1)
BASELINE = timedelta(minutes=60)
RESTART_LOOKBACK = timedelta(minutes=15)
METRIC_LOOKBACK = timedelta(minutes=30)
MAX_TEMPLATES = 15
MAX_LINE_LOGGERS = 8
# Message-only lines (line_format drops stack traces), so these stay small even
# for noisy loggers. Full JSON lines are fetched only for the two samples.
WINDOW_LINES = 100
BASELINE_LINES = 300
FULL_SAMPLE_LINES = 2
OTP_LINES = 20
SAMPLES = 2
SAMPLE_CHARS = 500
STACK_LINES = 15
QUIET_LINES = 5
MAX_BUNDLE_CHARS = 40_000

# Spring apps log JSON with a `level` label (Alloy). OTP logs are plain text.
APP_SELECTOR = 'app=~"api|worker|worker2"'
OTP_TEMPLATE = "OTP 로그의 ERROR/Exception 줄"

METRIC_QUERIES = {
    "m:memory_available_mib": "node_memory_MemAvailable_bytes / 1048576",
    "m:up": 'up{job=~"nextroute-app|otp|node"}',
    "m:http_5xx_10m": 'sum by (instance) (increase(http_server_requests_seconds_count{status=~"5.."}[10m]))',
    "m:client_fail_10m": 'sum by (client_name, status) (increase(http_client_requests_seconds_count{outcome!="SUCCESS"}[10m]))',
    "m:heap_used_mib": 'sum by (instance) (jvm_memory_used_bytes{area="heap"}) / 1048576',
}


class LogSource(Protocol):
    async def instant(self, query: str, at: datetime) -> list[dict]: ...

    async def range(self, query: str, start: datetime, end: datetime, limit: int) -> list[dict]: ...


class MetricSource(Protocol):
    async def instant(self, query: str, at: datetime) -> list[dict]: ...


@dataclass
class TemplateStat:
    id: str
    app: str
    level: str
    logger: str
    template: str
    # Decided on exact per-logger counts from Loki, never on the capped samples.
    status: Status
    logger_total: int        # WARN/ERROR lines of the whole logger in the window (exact)
    logger_baseline: int     # same, in the hour before the window (exact)
    sample_count: int        # lines of this template among the window sample
    sample_capped: bool      # the window sample hit its limit, so sample_count is a lower bound
    # Whether this template also appeared in the baseline sample. None means
    # "unknown": the baseline sample was capped and did not contain it.
    baseline_seen: bool | None
    samples: list[str] = field(default_factory=list)
    trace_ids: list[str] = field(default_factory=list)
    # Our package's frames from the untruncated stack traces (Caused by included), top first.
    # Samples keep only the first STACK_LINES rows, often all library frames.
    frames: list[str] = field(default_factory=list)


@dataclass
class MetricPoint:
    key: str
    labels: dict[str, str]
    at_alert: float | None
    before_30m: float | None


@dataclass
class Restart:
    instance: str
    started_at: datetime


@dataclass
class ContextBundle:
    alerts: list[dict]
    window_start: datetime
    window_end: datetime
    templates: list[TemplateStat]
    quiet_summary: list[str]
    metrics: list[MetricPoint]
    restarts: list[Restart]
    collection_errors: list[str]
    truncated: list[str] = field(default_factory=list)
    # Deployed-commit code (analyzer.code.CodeSnippet as dicts). Ids "s:1".., citable as evidence.
    code: list[dict] = field(default_factory=list)
    code_via: str = "disabled"
    # Past incidents (analyzer.cards), ids "k:K01". Only fix candidates may cite them.
    cards: list[dict] = field(default_factory=list)

    def ids(self) -> set[str]:
        """Evidence ids the LLM may cite."""
        ids = {t.id for t in self.templates}
        ids.update(m.key for m in self.metrics)
        if self.restarts:
            ids.add("m:restarts")
        ids.update(c["id"] for c in self.code)
        return ids

    def fix_ids(self) -> set[str]:
        """Ids a fix candidate may cite: code and past incident cards."""
        return {c["id"] for c in self.code} | {c["id"] for c in self.cards}

    def to_json(self) -> str:
        return json.dumps(asdict(self), default=_json_default, ensure_ascii=False)

    @classmethod
    def from_dict(cls, data: dict) -> "ContextBundle":
        """Inverse of to_json (after json.loads), for the eval fixtures."""
        return cls(
            alerts=data["alerts"],
            window_start=datetime.fromisoformat(data["window_start"]),
            window_end=datetime.fromisoformat(data["window_end"]),
            templates=[TemplateStat(**t) for t in data["templates"]],
            quiet_summary=data["quiet_summary"],
            metrics=[MetricPoint(**m) for m in data["metrics"]],
            restarts=[Restart(r["instance"], datetime.fromisoformat(r["started_at"])) for r in data["restarts"]],
            collection_errors=data["collection_errors"],
            truncated=data.get("truncated", []),
            code=data.get("code", []),
            code_via=data.get("code_via", "disabled"),
            cards=data.get("cards", []),
        )


# --- templates -----------------------------------------------------------------

_UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
# Hex ids need at least one letter and one digit, otherwise plain numbers would
# turn into <hex> instead of #.
_HEX = re.compile(r"\b(?=[0-9a-fA-F]*[a-fA-F])(?=[0-9a-fA-F]*\d)[0-9a-fA-F]{8,}\b")
_NUM = re.compile(r"\d+")
_WS = re.compile(r"\s+")


def normalize_template(message: str) -> str:
    text = _UUID.sub("<uuid>", message)
    text = _HEX.sub("<hex>", text)
    text = _NUM.sub("#", text)
    return _WS.sub(" ", text).strip()[:200]


def template_id(app: str, logger: str, template: str) -> str:
    digest = hashlib.sha1(f"{app}|{logger}|{template}".encode()).hexdigest()
    return f"t:{digest[:8]}"


def classify(window_count: int, baseline_count: int, window_min: float, baseline_min: float) -> Status:
    if window_count <= 0:
        return "NORMAL"
    if baseline_count == 0:
        return "NEW"
    if window_count >= 5 and window_count / window_min >= 3 * baseline_count / baseline_min:
        return "SPIKE"
    return "NORMAL"


# --- gathering -----------------------------------------------------------------


class CodeSource(Protocol):
    async def __call__(self, templates: list[TemplateStat], alerts: list[dict], at: datetime): ...


async def gather(
    job: AnalysisJob,
    logs: LogSource,
    metrics: MetricSource,
    code: CodeSource | None = None,
    cards: list[Card] | None = None,
    cards_error: str | None = None,
) -> ContextBundle:
    starts = [a.startsAt for a in job.alerts]
    first, last = min(starts), max(starts)
    window_start = first - WINDOW_BEFORE
    window_end = last + WINDOW_AFTER
    window_min = _minutes(window_end - window_start)
    errors: list[str] = []

    # Each source is independent: one failure is recorded and the rest still run.
    templates: list[TemplateStat] = []
    quiet: list[str] = []
    try:
        templates, quiet = await _app_templates(logs, window_start, window_end, window_min)
    except Exception as exc:  # noqa: BLE001
        errors.append(_describe("loki app logs", exc))
    try:
        otp = await _otp_template(logs, window_start, window_end, window_min)
        if otp is not None:
            templates.append(otp)
    except Exception as exc:  # noqa: BLE001
        errors.append(_describe("loki otp logs", exc))

    templates = sorted(templates, key=_rank)
    if len(templates) > MAX_TEMPLATES:
        quiet.append(f"그 외 템플릿 {len(templates) - MAX_TEMPLATES}개 (순위 밖)")
        templates = templates[:MAX_TEMPLATES]

    points: list[MetricPoint] = []
    for key, query in METRIC_QUERIES.items():
        try:
            points.extend(await _metric_points(metrics, key, query, first))
        except Exception as exc:  # noqa: BLE001
            errors.append(_describe(f"prometheus {key}", exc))

    restarts: list[Restart] = []
    try:
        restarts = await _restarts(metrics, first, window_end)
    except Exception as exc:  # noqa: BLE001
        errors.append(_describe("prometheus restarts", exc))

    alerts = [_alert_view(a) for a in job.alerts]
    # Before fit_budget: the frames and templates it may cut are what finds the code.
    snippets: list[dict] = []
    code_via = "disabled"
    truncated: list[str] = []
    if code is not None:
        try:
            found = await code(templates, alerts, first)
            snippets, code_via = found.as_dicts(), found.via
            errors.extend(found.errors)
            if found.truncated:
                truncated.append("code")
        except Exception as exc:  # noqa: BLE001
            errors.append(_describe("code", exc))
            code_via = "none"

    chosen = select_cards(cards, code=snippets, templates=templates, alerts=alerts) if cards else []
    if cards_error:
        errors.append(f"cards: {cards_error}")

    bundle = ContextBundle(
        alerts=alerts,
        window_start=window_start,
        window_end=window_end,
        templates=templates,
        quiet_summary=quiet,
        metrics=points,
        restarts=restarts,
        collection_errors=errors,
        truncated=truncated,
        code=snippets,
        code_via=code_via,
        cards=[c.as_dict() for c in chosen],
    )
    return fit_budget(bundle)


async def _app_templates(
    logs: LogSource, start: datetime, end: datetime, window_min: int
) -> tuple[list[TemplateStat], list[str]]:
    # Stage 1: exact counts per logger (cheap metric query, ~10 ms on the VPS).
    by_logger = 'sum by (app, level, logger_name) (count_over_time({%s, level=~"WARN|ERROR"} | json logger_name [%dm]))'
    window_counts = _logger_counts(await logs.instant(by_logger % (APP_SELECTOR, window_min), end))
    baseline_counts = _logger_counts(await logs.instant(by_logger % (APP_SELECTOR, _minutes(BASELINE)), start))

    loggers = []
    for key, count in window_counts.items():
        base = baseline_counts.get(key, 0)
        loggers.append((key, count, base, classify(count, base, window_min, _minutes(BASELINE))))
    loggers.sort(key=lambda item: (_level_rank(item[0][1]), _status_rank(item[3]), -item[1]))

    # Stage 2: fetch lines only for loggers worth reading.
    chosen = [item for item in loggers if item[0][1] == "ERROR" or item[3] != "NORMAL"][:MAX_LINE_LOGGERS]
    rest = [item for item in loggers if item not in chosen]

    templates: list[TemplateStat] = []
    for (app, level, logger), total, base, status in chosen:
        templates.extend(await _logger_templates(logs, app, level, logger, total, base, status, start, end))

    quiet = [
        f"{app} {level} {_short(logger)}: {count}건 (직전 60분 {base}건, {status})"
        for (app, level, logger), count, base, status in rest[:QUIET_LINES]
    ]
    if len(rest) > QUIET_LINES:
        quiet.append(f"그 외 로거 {len(rest) - QUIET_LINES}개")
    return templates, quiet


async def _logger_templates(
    logs: LogSource,
    app: str,
    level: str,
    logger: str,
    total: int,
    base: int,
    status: Status,
    start: datetime,
    end: datetime,
) -> list[TemplateStat]:
    stream = '{app="%s", level="%s"}' % (_escape(app), _escape(level))
    messages = '%s | json logger_name, message | logger_name="%s" | line_format "{{.message}}"' % (
        stream,
        _escape(logger),
    )
    full = '%s | json logger_name | logger_name="%s"' % (stream, _escape(logger))

    # Mask raw text before templating: normalize_template turns digits into "#",
    # which would hide a key from the masking rules and leak its letters.
    window = [mask(m) for m in _raw_lines(await logs.range(messages, start, end, WINDOW_LINES))]
    baseline = [mask(m) for m in _raw_lines(await logs.range(messages, start - BASELINE, start, BASELINE_LINES))]
    full_lines = _lines(await logs.range(full, start, end, FULL_SAMPLE_LINES))
    logger_frames: list[str] = []
    for line in full_lines:
        for frame in frames_from_stack(str(line.get("stack_trace") or "")):
            if frame not in logger_frames:
                logger_frames.append(frame)

    baseline_templates = {normalize_template(m) for m in baseline}
    baseline_capped = len(baseline) >= BASELINE_LINES
    groups: dict[str, list[str]] = {}
    for message in window:
        groups.setdefault(normalize_template(message), []).append(message)
    # Stack traces and trace ids only exist on the full lines; attach them to their template.
    full_by_template: dict[str, list[dict]] = {}
    for line in full_lines:
        full_by_template.setdefault(normalize_template(mask(str(line.get("message", "")))), []).append(line)

    stats = []
    frames_given = False
    for template, lines in groups.items():
        full_matches = full_by_template.get(template, [])
        frames: list[str] = []
        for line in full_matches:
            for frame in frames_from_stack(str(line.get("stack_trace") or "")):
                if frame not in frames:
                    frames.append(frame)
        frames_given = frames_given or bool(frames)
        samples = [_sample(line) for line in full_matches[:SAMPLES]]
        samples += [m[:SAMPLE_CHARS] for m in lines[: SAMPLES - len(samples)]]
        seen: bool | None = template in baseline_templates
        if not seen and baseline_capped:
            seen = None
        stats.append(
            TemplateStat(
                id=template_id(app, logger, template),
                app=app,
                level=level,
                logger=logger,
                template=template,
                status=status,
                logger_total=total,
                logger_baseline=base,
                sample_count=len(lines),
                sample_capped=len(window) >= WINDOW_LINES,
                baseline_seen=seen,
                samples=samples,
                trace_ids=_trace_ids(full_matches),
                frames=frames[:MAX_FRAMES],
            )
        )
    # The full lines' message may template differently from the window sample; keep
    # their frames anyway, on the logger's biggest template.
    if logger_frames and not frames_given and stats:
        biggest = max(stats, key=lambda s: s.sample_count)
        biggest.frames = logger_frames[:MAX_FRAMES]
    return stats


async def _otp_template(logs: LogSource, start: datetime, end: datetime, window_min: int) -> TemplateStat | None:
    selector = '{app="otp"} |~ "ERROR|Exception"'
    window_result = await logs.instant(f"sum(count_over_time({selector} [{window_min}m]))", end)
    total = _scalar(window_result)
    if total == 0:
        return None
    base = _scalar(await logs.instant(f"sum(count_over_time({selector} [{_minutes(BASELINE)}m]))", start))
    lines = _lines(await logs.range(selector, start, end, OTP_LINES))
    return TemplateStat(
        id=template_id("otp", "otp", OTP_TEMPLATE),
        app="otp",
        level="ERROR",
        logger="otp",
        template=OTP_TEMPLATE,
        status=classify(total, base, window_min, _minutes(BASELINE)),
        logger_total=total,
        logger_baseline=base,
        sample_count=len(lines),
        sample_capped=len(lines) >= OTP_LINES,
        baseline_seen=base > 0,
        samples=[_sample(line) for line in lines[:SAMPLES]],
    )


async def _metric_points(metrics: MetricSource, key: str, query: str, at: datetime) -> list[MetricPoint]:
    now = _series(await metrics.instant(query, at))
    before = _series(await metrics.instant(query, at - METRIC_LOOKBACK))
    points = []
    for labels in sorted(now.keys() | before.keys()):
        points.append(
            MetricPoint(
                key=key,
                labels=dict(labels),
                at_alert=now.get(labels),
                before_30m=before.get(labels),
            )
        )
    return points


async def _restarts(metrics: MetricSource, first: datetime, window_end: datetime) -> list[Restart]:
    found = []
    for series in await metrics.instant("process_start_time_seconds", window_end):
        started = datetime.fromtimestamp(float(series["value"][1]), tz=timezone.utc)
        if first - RESTART_LOOKBACK <= started <= window_end:
            found.append(Restart(instance=series["metric"].get("instance", "?"), started_at=started))
    return sorted(found, key=lambda r: r.started_at)


def fit_budget(bundle: ContextBundle, limit: int = MAX_BUNDLE_CHARS) -> ContextBundle:
    """Shrink the bundle until it fits, least useful parts first. Records what was cut.

    Code has its own budget (analyzer.code.BUDGET_CHARS), applied when it is
    collected; this limit is for everything else, so a big incident's samples
    never squeeze the code out.
    """
    code, cards = bundle.code, bundle.cards
    bundle = replace(bundle, code=[], cards=[])
    fitted = _fit_rest(bundle, limit)
    return replace(fitted, code=code, cards=cards)


def _fit_rest(bundle: ContextBundle, limit: int) -> ContextBundle:
    if len(bundle.to_json()) <= limit:
        return bundle
    truncated = list(bundle.truncated)
    if bundle.quiet_summary:
        bundle = replace(bundle, quiet_summary=[])
        truncated.append("quiet_summary")
    for keep in (1, 0):
        if len(bundle.to_json()) <= limit:
            break
        bundle = replace(bundle, templates=[replace(t, samples=t.samples[:keep]) for t in bundle.templates])
        truncated.append(f"samples>{keep}")
    dropped = 0
    templates = list(bundle.templates)
    while templates and len(replace(bundle, templates=templates).to_json()) > limit:
        templates.pop()
        dropped += 1
    if dropped:
        truncated.append(f"templates-{dropped}")
    return replace(bundle, templates=templates, truncated=truncated)


# --- helpers -------------------------------------------------------------------


def _alert_view(alert: GrafanaAlert) -> dict:
    return {
        "alertname": alert.labels.get("alertname", ""),
        "labels": {k: v for k, v in alert.labels.items() if k not in ("alertname", "grafana_folder")},
        "summary": alert.annotations.get("summary", ""),
        "values": alert.values or {},
        "starts_at": alert.startsAt.isoformat(),
        "fingerprint": alert.fingerprint,
    }


def _logger_counts(result: list[dict]) -> dict[tuple[str, str, str], int]:
    counts = {}
    for series in result:
        metric = series.get("metric", {})
        key = (metric.get("app", ""), metric.get("level", ""), metric.get("logger_name", ""))
        counts[key] = int(float(series["value"][1]))
    return counts


def _lines(result: list[dict]) -> list[dict]:
    """Loki stream values → parsed lines, newest first. Plain-text lines become {"message": line}."""
    entries = []
    for stream in result:
        for ts, raw in stream.get("values", []):
            try:
                parsed = json.loads(raw)
                line = parsed if isinstance(parsed, dict) else {"message": raw}
            except json.JSONDecodeError:
                line = {"message": raw}
            entries.append((int(ts), line))
    entries.sort(key=lambda e: e[0], reverse=True)
    return [line for _, line in entries]


def _raw_lines(result: list[dict]) -> list[str]:
    """Loki stream values as plain strings, newest first (for line_format'ed queries)."""
    entries = [(int(ts), raw) for stream in result for ts, raw in stream.get("values", [])]
    entries.sort(key=lambda e: e[0], reverse=True)
    return [raw for _, raw in entries]


def _scalar(result: list[dict]) -> int:
    return int(float(result[0]["value"][1])) if result else 0


def _series(result: list[dict]) -> dict[tuple[tuple[str, str], ...], float | None]:
    series = {}
    for item in result:
        labels = tuple(sorted((k, v) for k, v in item.get("metric", {}).items() if k != "__name__"))
        value = float(item["value"][1])
        series[labels] = None if math.isnan(value) else round(value, 1)
    return series


def _sample(line: dict) -> str:
    text = str(line.get("message", ""))[:SAMPLE_CHARS]
    stack = line.get("stack_trace")
    if stack:
        text += "\n" + "\n".join(row[:200] for row in str(stack).splitlines()[:STACK_LINES])
    return mask(text)


def _trace_ids(lines: list[dict]) -> list[str]:
    ids: list[str] = []
    for line in lines:
        trace = line.get("traceId")
        if trace and trace not in ids:
            ids.append(trace)
        if len(ids) == 3:
            break
    return ids


def _rank(t: TemplateStat) -> tuple[int, int, int, int]:
    # Within a logger, a template never seen in the hour before comes first.
    unseen = 0 if t.baseline_seen is False else 1
    return (_level_rank(t.level), _status_rank(t.status), unseen, -t.sample_count)


def _level_rank(level: str) -> int:
    return 0 if level == "ERROR" else 1


def _status_rank(status: str) -> int:
    return {"NEW": 0, "SPIKE": 1}.get(status, 2)


def _minutes(delta: timedelta) -> int:
    return max(1, math.ceil(delta.total_seconds() / 60))


def _short(logger: str) -> str:
    return logger.rsplit(".", 1)[-1]


def _escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


def _describe(source: str, exc: Exception) -> str:
    return f"{source}: {type(exc).__name__}: {str(exc)[:160]}"


def _json_default(value: object) -> str:
    if isinstance(value, datetime):
        return value.isoformat()
    raise TypeError(f"not serializable: {type(value).__name__}")
