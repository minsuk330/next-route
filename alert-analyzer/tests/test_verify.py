"""Stage 6 dispatch (analyzer.verify)."""

import base64
import json

import pytest

from analyzer.fix import Edit, FixProposal
from analyzer.verify import MAX_INPUT_CHARS, github_dispatch, inputs

pytestmark = pytest.mark.anyio

M = "e" * 40
TS = "1795782729.655779"


def proposal(**kw):
    base = dict(status="proposed", target={"symbol": "Svc#ok"}, main_sha=M, diff="+x\n",
                edits=[Edit("  return a;\n", "  return b;\n")], repo_path="src/main/java/a/Svc.java")
    base.update(kw)
    return FixProposal(**base)


def test_inputs_carry_raw_edits_and_main_sha():
    v = inputs(proposal(), "C0123ABCD", TS, "  SubwayRing   5xx  ")
    assert v["ref"] == M and v["channel"] == "C0123ABCD" and v["thread_ts"] == TS and v["label"] == "SubwayRing 5xx"
    blob = json.loads(base64.b64decode(v["edits"]))
    assert blob == {"path": "src/main/java/a/Svc.java", "edits": [{"search": "  return a;\n", "replace": "  return b;\n"}]}


def test_inputs_refuse_what_the_workflow_cannot_apply():
    assert inputs(proposal(status="main_changed"), "C1", TS, "") is None
    assert inputs(proposal(edits=[]), "C1", TS, "") is None
    assert inputs(proposal(main_sha=None), "C1", TS, "") is None
    big = [Edit("x" * MAX_INPUT_CHARS, "y")]
    assert inputs(proposal(edits=big), "C1", TS, "") is None
    assert len(inputs(proposal(), "C1", TS, "L" * 500)["label"]) == 80


class Resp:
    def __init__(self, status, body=None):
        self.status_code = status
        self._body = body

    def json(self):
        if self._body is None:
            raise ValueError("no body")
        return self._body


class Http:
    def __init__(self, result):
        self.result = result
        self.calls = []

    async def post(self, url, **kw):
        self.calls.append((url, kw))
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


async def test_dispatch_posts_to_main_and_links_the_run():
    http = Http(Resp(200, {"html_url": "https://github.com/o/r/actions/runs/9"}))
    r = await github_dispatch(http, "o/r", "tok", "C0123ABCD")(proposal(), TS, "A")
    assert r.dispatched and r.url == "https://github.com/o/r/actions/runs/9"
    url, kw = http.calls[0]
    assert url == "https://api.github.com/repos/o/r/actions/workflows/fix-verify.yml/dispatches"
    assert kw["json"]["ref"] == "main" and kw["json"]["return_run_details"] is True
    assert kw["json"]["inputs"]["ref"] == M and kw["headers"]["Authorization"] == "Bearer tok"


@pytest.mark.parametrize("resp, dispatched, url_end, reason", [
    (Resp(204), True, "/actions/workflows/fix-verify.yml", ""),
    (Resp(200, {"html_url": "https://evil.example/x"}), True, "/actions/workflows/fix-verify.yml", ""),
    (Resp(200), True, "/actions/workflows/fix-verify.yml", ""),
    (Resp(403, {"message": "Resource not accessible"}), False, "", "GitHub 403"),
    (TimeoutError(), False, "", "TimeoutError"),
])
async def test_dispatch_outcomes(resp, dispatched, url_end, reason):
    r = await github_dispatch(Http(resp), "o/r", "tok", "C1")(proposal(), TS, "")
    assert r.dispatched is dispatched and r.url.endswith(url_end) and r.reason == reason


async def test_dispatch_skips_without_calling_github():
    http = Http(Resp(204))
    r = await github_dispatch(http, "o/r", "tok", "C1")(proposal(edits=[]), TS, "")
    assert not r.dispatched and http.calls == []
