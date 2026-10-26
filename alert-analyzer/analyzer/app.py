"""HTTP entry point. Grafana posts alerts here; the analysis runs in the background.

The webhook answers immediately so Grafana never waits on Loki or the LLM, and
the Slack alert from the same contact point is unaffected by this service.
"""

from __future__ import annotations

import asyncio
import hmac
import logging
from collections.abc import Callable
from contextlib import asynccontextmanager, suppress
from datetime import datetime

import httpx2 as httpx
from fastapi import FastAPI, HTTPException, Request
from pydantic import ValidationError

from analyzer.config import Settings
from analyzer.context import gather
from analyzer.dedupe import DailyBudget, SeenStore
from analyzer.logging_json import configure_logging
from analyzer.payload import AnalysisJob, GrafanaWebhook, dedupe_key, select_new
from analyzer.sources import LokiClient, PromClient
from analyzer.worker import GatherFn, run_worker, utcnow

log = logging.getLogger(__name__)


def create_app(
    settings: Settings | None = None,
    *,
    gather_fn: GatherFn | None = None,
    start_worker: bool = True,
    clock: Callable[[], datetime] = utcnow,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if settings is None:
            configure_logging()
        resolved = settings or Settings.from_env()
        app.state.settings = resolved
        app.state.queue = asyncio.Queue(maxsize=resolved.queue_max)
        app.state.seen = SeenStore()
        app.state.budget = DailyBudget(resolved.daily_limit)

        http = httpx.AsyncClient()
        loki = LokiClient(resolved.loki_url, http)
        prom = PromClient(resolved.prom_url, http)
        fn = gather_fn or (lambda job: gather(job, loki, prom))

        worker = None
        if start_worker:
            worker = asyncio.create_task(
                run_worker(app.state.queue, settings=resolved, budget=app.state.budget, gather_fn=fn, clock=clock)
            )
        log.info("alert-analyzer started dry_run=%s daily_limit=%d", resolved.dry_run, resolved.daily_limit)
        try:
            yield
        finally:
            if worker is not None:
                worker.cancel()
                with suppress(asyncio.CancelledError):
                    await worker
            await http.aclose()

    app = FastAPI(title="nextroute alert-analyzer", lifespan=lifespan)

    @app.post("/webhook/grafana", status_code=202)
    async def grafana_webhook(request: Request) -> dict:
        state = request.app.state
        if not _authorized(request.headers.get("authorization"), state.settings.webhook_token):
            raise HTTPException(status_code=401, detail="unauthorized")
        try:
            payload = GrafanaWebhook.model_validate_json(await request.body())
        except ValidationError:
            raise HTTPException(status_code=400, detail="invalid Grafana webhook payload") from None

        now = clock()
        fresh = select_new(payload, state.seen, now)
        if not fresh:
            return {"queued": 0, "skipped": len(payload.alerts)}
        try:
            state.queue.put_nowait(AnalysisJob(payload.groupKey, tuple(fresh), now))
        except asyncio.QueueFull:
            # Forget them again so the hourly repeat notification can retry.
            for alert in fresh:
                state.seen.discard(dedupe_key(alert))
            log.warning("queue full, dropped group=%s alerts=%d", payload.groupKey, len(fresh))
            return {"queued": 0, "skipped": len(payload.alerts)}
        return {"queued": len(fresh), "skipped": len(payload.alerts) - len(fresh)}

    @app.get("/healthz")
    async def healthz(request: Request) -> dict:
        return {"status": "ok", "queue": request.app.state.queue.qsize()}

    return app


def _authorized(header: str | None, token: str) -> bool:
    if not header:
        return False
    scheme, _, value = header.partition(" ")
    return scheme.lower() == "bearer" and hmac.compare_digest(value.strip().encode(), token.encode())


app = create_app()
