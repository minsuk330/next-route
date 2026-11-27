"""Stage 6 dispatch (analyzer.verify)."""

import base64
import importlib.util
import json
from pathlib import Path

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
    assert inputs(proposal(status="main_changed"), "C0123ABCD", TS, "") is None
    assert inputs(proposal(edits=[]), "C0123ABCD", TS, "") is None
    assert inputs(proposal(main_sha=None), "C0123ABCD", TS, "") is None
    big = [Edit("x" * MAX_INPUT_CHARS, "y")]
    assert inputs(proposal(edits=big), "C0123ABCD", TS, "") is None
    assert len(inputs(proposal(), "C0123ABCD", TS, "L" * 500)["label"]) == 80
    # shapes the workflow would reject after an hour of tests
    assert inputs(proposal(main_sha="main"), "C0123ABCD", TS, "") is None
    assert inputs(proposal(), "alerts", TS, "") is None
    assert inputs(proposal(), "C0123ABCD", "123", "") is None


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
    (Resp(200, {"html_url": "https://github.com/other/repo/actions/runs/1"}), True, "/actions/workflows/fix-verify.yml", ""),
    (Resp(200), True, "/actions/workflows/fix-verify.yml", ""),
    (Resp(403, {"message": "Resource not accessible"}), False, "", "GitHub 403"),
    (TimeoutError(), False, "", "TimeoutError"),
])
async def test_dispatch_outcomes(resp, dispatched, url_end, reason):
    r = await github_dispatch(Http(resp), "o/r", "tok", "C0123ABCD")(proposal(), TS, "")
    assert r.dispatched is dispatched and r.url.endswith(url_end) and r.reason == reason


async def test_dispatch_skips_without_calling_github():
    http = Http(Resp(204))
    r = await github_dispatch(http, "o/r", "tok", "C0123ABCD")(proposal(edits=[]), TS, "")
    assert not r.dispatched and http.calls == []


# --- contract with the workflow side (ops/ci) --------------------------------------------------

OPS_CI = Path(__file__).resolve().parents[2] / "ops" / "ci"


def _load(name):
    if not (OPS_CI / f"{name}.py").exists():
        pytest.skip("ops/ci not mounted")
    spec = importlib.util.spec_from_file_location(f"ops_ci_{name}", OPS_CI / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


async def test_workflow_applies_exactly_what_the_analyzer_proposed(tmp_path):
    """propose() → inputs() → ops/ci/apply_edits.decode/apply gives the same file as fix.apply_edits."""
    from analyzer.fix import apply_edits as fix_apply
    ops = _load("apply_edits")
    src = "package a;\r\nclass Svc {\r\n  int x = 1;\r\n}\r\n"
    edits = [Edit("  int x = 1;\n", "  int x = 2;\n")]
    p = proposal(edits=edits, repo_path="src/main/java/watoo/grd/nextroute/a/Svc.java")
    path, decoded = ops.decode(inputs(p, "C0123ABCD", TS, "")["edits"])
    assert path == p.repo_path and ops.PATH.match(path)
    normal = src.replace("\r\n", "\n")
    assert ops.apply_edits(normal, decoded) == fix_apply(normal, edits)
    f = tmp_path / path
    f.parent.mkdir(parents=True)
    f.write_bytes(src.encode())
    import os
    os.environ["EDITS"] = inputs(p, "C0123ABCD", TS, "")["edits"]
    try:
        assert ops.main(["x", str(tmp_path)]) == 0
    finally:
        del os.environ["EDITS"]
    assert f.read_bytes() == b"package a;\r\nclass Svc {\r\n  int x = 2;\r\n}\r\n"


def test_channel_and_thread_shapes_match_notify():
    from analyzer import verify
    notify = _load("notify_slack")
    assert verify.CHANNEL.pattern == notify.CHANNEL.pattern
    assert verify.THREAD_TS.pattern == notify.THREAD_TS.pattern
