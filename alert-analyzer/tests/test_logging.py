from __future__ import annotations

import json
import logging

from analyzer.logging_json import JsonFormatter, configure_logging


def test_json_line_uses_spring_level_names():
    record = logging.LogRecord("analyzer.worker", logging.WARNING, __file__, 1, "queue full %s", ("g",), None)

    line = json.loads(JsonFormatter().format(record))

    assert line["level"] == "WARN"
    assert line["logger_name"] == "analyzer.worker"
    assert line["message"] == "queue full g"


def test_access_log_stays_off_after_configuring():
    root = logging.getLogger()
    saved = (root.handlers[:], root.level)
    try:
        configure_logging()

        access = logging.getLogger("uvicorn.access")
        # uvicorn writes access lines only when this returns True.
        assert not access.hasHandlers()
        assert logging.getLogger("uvicorn.error").hasHandlers()
    finally:
        root.handlers[:], level = saved
        root.setLevel(level)
