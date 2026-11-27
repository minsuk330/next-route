"""Stage 6: ask GitHub Actions to test the proposed fix (docs/plan/pipeline-fix-verify).

The analyzer only dispatches the fix-verify workflow and adds one line to the proposal reply.
The workflow applies the edits to main_sha, picks the affected tests, and its own notify job
replies in the same thread — the analyzer does not poll (user decision: keep the analyzer small).

Needs a token with Actions: Read and write on the repo. A failure here never breaks the analysis.
"""

from __future__ import annotations

import base64
import json
import logging
import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

log = logging.getLogger(__name__)

WORKFLOW = "fix-verify.yml"
TIMEOUT_S = 10.0
# workflow_dispatch caps the inputs payload at 65,535 characters; leave room for the other inputs.
MAX_INPUT_CHARS = 60_000
LABEL_CHARS = 80
# Same shapes the workflow (ops/ci/notify_slack.py, fix-verify.yml) accepts: catch here what it would reject
# after an hour of tests.
CHANNEL = re.compile(r"^[CG][A-Z0-9]{6,}$")
THREAD_TS = re.compile(r"^\d{9,11}\.\d{1,6}$")
SHA = re.compile(r"^[0-9a-f]{40}$")


@dataclass(frozen=True)
class VerifyResult:
    dispatched: bool
    url: str = ""
    reason: str = ""


Verify = Callable[["FixProposal", str, str], Awaitable[VerifyResult]]  # (proposal, thread_ts, label)


def inputs(proposal, channel: str, thread_ts: str, label: str) -> dict[str, str] | None:
    """Workflow inputs, or None when the proposal is not something the workflow can apply."""
    if proposal.status != "proposed" or not proposal.edits or not proposal.repo_path:
        return None
    if not SHA.match(proposal.main_sha or "") or not CHANNEL.match(channel) or not THREAD_TS.match(thread_ts):
        return None
    blob = json.dumps(
        {"path": proposal.repo_path, "edits": [{"search": e.search, "replace": e.replace} for e in proposal.edits]},
        ensure_ascii=False,
    )
    values = {
        "ref": proposal.main_sha,
        "edits": base64.b64encode(blob.encode("utf-8")).decode("ascii"),
        "channel": channel,
        "thread_ts": thread_ts,
        "label": " ".join(label.split())[:LABEL_CHARS],
    }
    if sum(len(v) for v in values.values()) > MAX_INPUT_CHARS:
        return None
    return values


def github_dispatch(http, repo: str, token: str, channel: str) -> Verify:
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}
    url = f"https://api.github.com/repos/{repo}/actions/workflows/{WORKFLOW}/dispatches"
    runs = f"https://github.com/{repo}/actions/workflows/{WORKFLOW}"
    run_prefix = f"https://github.com/{repo}/actions/runs/"

    async def dispatch(proposal, thread_ts: str, label: str) -> VerifyResult:
        values = inputs(proposal, channel, thread_ts, label)
        if values is None:
            return VerifyResult(False, reason="입력이 형식에 안 맞거나 너무 큼")
        try:
            # ref=main: the workflow and its scripts always come from main; inputs.ref is what gets tested.
            response = await http.post(
                url, headers=headers, timeout=TIMEOUT_S,
                json={"ref": "main", "inputs": values, "return_run_details": True},
            )
        except Exception as exc:  # noqa: BLE001 — network trouble must not break the analysis
            return VerifyResult(False, reason=type(exc).__name__)
        if response.status_code == 204:
            return VerifyResult(True, url=runs)
        if response.status_code == 200:
            try:
                link = response.json().get("html_url") or runs
            except ValueError:
                link = runs
            return VerifyResult(True, url=link if re.fullmatch(re.escape(run_prefix) + r"\d+", str(link)) else runs)
        return VerifyResult(False, reason=f"GitHub {response.status_code}")

    return dispatch
