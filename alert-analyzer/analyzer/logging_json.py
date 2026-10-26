"""One-line JSON logs in the same shape as the Spring apps.

Alloy lifts the JSON `level` field into the Loki `level` label, so the existing
"ERROR 로그 급증" Grafana rule covers this service too. Python's WARNING is
written as WARN to match the Spring level values.
"""

from __future__ import annotations

import json
import logging
import sys
from datetime import datetime, timezone

_LEVELS = {"WARNING": "WARN", "CRITICAL": "ERROR"}


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "@timestamp": datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat(),
            "level": _LEVELS.get(record.levelname, record.levelname),
            "logger_name": record.name,
            "message": record.getMessage(),
        }
        if record.exc_info:
            payload["stack_trace"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


def configure_logging(level: int = logging.INFO) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level)
    for name in ("uvicorn", "uvicorn.error"):
        logger = logging.getLogger(name)
        logger.handlers[:] = []
        logger.propagate = True
    # uvicorn decides whether to write access lines by asking the logger for
    # handlers (including inherited ones), so propagating to root would undo
    # --no-access-log and send every 30 s healthcheck to Loki.
    access = logging.getLogger("uvicorn.access")
    access.handlers[:] = []
    access.propagate = False
