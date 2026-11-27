"""Pipeline stage 4: propose the code change (docs/plan/pipeline-fix-proposal).

Only for an app bug the model is at least fairly sure of, at a place it cited with
real code (s:). The model answers with SEARCH/REPLACE blocks (11-27 test on 18 real
fixes: applied 18 vs 16 for unified diffs, right place 16 vs 13). Each block must
match the deployed commit exactly once, then main:
  both match       → proposed, shown as a diff against main
  deployed only    → main_changed: main moved since the deploy, maybe already fixed
  neither          → mismatch: the model misquoted the code, dropped (log only)
Nothing is pushed: the result is a Slack preview. Failures never touch the analysis.
"""

from __future__ import annotations

import difflib
import logging
import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal, Protocol

from analyzer.masking import mask_code

log = logging.getLogger(__name__)

PROMPT = (Path(__file__).parent / "prompts" / "fix.md").read_text(encoding="utf-8")
MAX_EDITS = 3
MAX_COMMITS = 5
CONFIDENT = {"high", "medium"}

Status = Literal["proposed", "main_changed", "mismatch", "skipped", "failed"]
_BLOCK = re.compile(r"<<<<<<< SEARCH\n(.*?)\n=======\n(.*?)\n?>>>>>>> REPLACE", re.S)

Complete = Callable[[str, str], Awaitable[tuple[str, float]]]
Compare = Callable[[str, str, str], Awaitable[list[str]]]


class Cache(Protocol):
    async def checkout(self, revision: str) -> Path | None: ...
    async def resolve(self, ref: str) -> str | None: ...


@dataclass(frozen=True)
class Edit:
    search: str
    replace: str


@dataclass
class FixProposal:
    status: Status
    target: dict | None = None
    main_sha: str | None = None
    diff: str = ""
    main_commits: list[str] = field(default_factory=list)
    reason: str = ""
    cost_usd: float = 0.0


def pick_target(verdict, bundle):
    """(code snippet, fix candidate) worth a proposal, or None."""
    if not any(h.category == "app_bug" and h.confidence in CONFIDENT for h in verdict.hypotheses):
        return None
    code = {c["id"]: c for c in bundle.code}
    for fix in verdict.fix_candidates:
        for ref in fix.refs:
            if ref in code:
                return code[ref], fix
    return None


def parse_edits(text: str) -> list[Edit]:
    return [Edit(s, r) for s, r in _BLOCK.findall(text or "")][:MAX_EDITS]


def apply_edits(source: str, edits: list[Edit]) -> str | None:
    """Every SEARCH must occur exactly once (in order of application); otherwise None."""
    if not edits:
        return None
    for e in edits:
        if not e.search or source.count(e.search) != 1:
            return None
        source = source.replace(e.search, e.replace, 1)
    return source


def user_message(target: dict, fix, verdict, bundle) -> str:
    cards = {c["id"]: c for c in bundle.cards}
    past = [f"- {cards[r]['id']}: {cards[r].get('조치', '')}" for r in fix.refs if r in cards]
    causes = [h.cause for h in verdict.hypotheses if h.category == "app_bug" and h.confidence in CONFIDENT]
    return (
        f"파일: {target['path']}\n대상: {target['symbol']} (배포 커밋 {target['revision'][:8]})\n\n"
        f"원인 가설:\n- " + "\n- ".join(causes) + "\n\n"
        f"고칠 곳 후보: {fix.place} — {fix.why}\n"
        + ("과거에 비슷한 장애를 이렇게 고쳤다:\n" + "\n".join(past) + "\n" if past else "")
        + f"\n메서드 원문(각 줄 앞 숫자는 파일의 줄 번호, 코드 아님):\n{target['body']}\n"
    )


async def propose(verdict, bundle, *, complete: Complete, cache: Cache, compare: Compare) -> FixProposal:
    picked = pick_target(verdict, bundle)
    if picked is None:
        return FixProposal("skipped")
    target, fix = picked
    try:
        text, cost = await complete(PROMPT, user_message(target, fix, verdict, bundle))
        edits = [Edit(mask_code(e.search), mask_code(e.replace)) for e in parse_edits(text)]
        if not edits:
            return FixProposal("skipped", target, reason="모델이 수정 블록을 쓰지 않음", cost_usd=cost)
        rel = target["path"].split("src/main/java/", 1)[-1]
        deployed_root = await cache.checkout(target["revision"])
        if deployed_root is None:
            return FixProposal("failed", target, reason="배포 커밋 코드를 못 받음", cost_usd=cost)
        deployed = (deployed_root / rel).read_text(encoding="utf-8")
        if apply_edits(deployed, edits) is None:
            return FixProposal("mismatch", target, reason=f"블록 {len(edits)}개가 배포 코드와 안 맞음", cost_usd=cost)
        main_sha = await cache.resolve("main")
        if main_sha is None:
            return FixProposal("failed", target, reason="main 커밋 조회 실패", cost_usd=cost)
        if main_sha == target["revision"]:
            main = deployed
        else:
            main_root = await cache.checkout(main_sha)
            main_file = main_root / rel if main_root else None
            if main_file is None or not main_file.exists():
                return FixProposal("main_changed", target, main_sha, reason="main 에 이 파일이 없음",
                                   main_commits=await _safe_compare(compare, target["revision"], main_sha, target["path"]),
                                   cost_usd=cost)
            main = main_file.read_text(encoding="utf-8")
        changed = apply_edits(main, edits)
        if changed is None:
            commits = await _safe_compare(compare, target["revision"], main_sha, target["path"])
            return FixProposal("main_changed", target, main_sha, main_commits=commits, cost_usd=cost)
        diff = "".join(difflib.unified_diff(
            main.splitlines(keepends=True), changed.splitlines(keepends=True),
            fromfile=f"a/{target['path']}", tofile=f"b/{target['path']}", n=3,
        ))
        return FixProposal("proposed", target, main_sha, diff=mask_code(diff), cost_usd=cost)
    except Exception as exc:  # noqa: BLE001 — a proposal must never break the analysis
        log.warning("fix proposal failed: %s", type(exc).__name__)
        return FixProposal("failed", target, reason=type(exc).__name__)


async def _safe_compare(compare: Compare, base: str, head: str, path: str) -> list[str]:
    try:
        return (await compare(base, head, path))[:MAX_COMMITS]
    except Exception:  # noqa: BLE001
        return []


def github_compare(http, repo: str, token: str) -> Compare:
    """Commits between two SHAs that touched one file (GitHub REST, read-only token)."""

    async def compare(base: str, head: str, path: str) -> list[str]:
        r = await http.get(
            f"https://api.github.com/repos/{repo}/compare/{base}...{head}",
            headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"},
            timeout=10,
        )
        r.raise_for_status()
        data = r.json()
        if path not in {f.get("filename") for f in data.get("files", [])}:
            return []
        out = []
        for c in reversed(data.get("commits", [])):
            out.append(f"{c['sha'][:8]} {c['commit']['message'].splitlines()[0][:80]}")
        return out

    return compare
