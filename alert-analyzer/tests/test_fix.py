from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

import pytest

from analyzer.context import ContextBundle
from analyzer.fix import FixProposal, apply_edits, Edit, parse_edits, pick_target, propose
from analyzer.llm import FixCandidate, Hypothesis, Verdict
from analyzer.slack import MAX_TEXT, render_fix

pytestmark = pytest.mark.anyio
STARTS = datetime(2026, 11, 27, 5, 0, tzinfo=timezone.utc)
D = "d" * 40
M = "e" * 40
PATH = "src/main/java/watoo/grd/nextroute/a/Svc.java"
SRC = "package watoo.grd.nextroute.a;\nclass Svc {\n  boolean ok(String id) {\n    return IDS.contains(id);\n  }\n}\n"
BLOCK = "<<<<<<< SEARCH\n    return IDS.contains(id);\n=======\n    return id != null && IDS.contains(id);\n>>>>>>> REPLACE"


def hyp(category="app_bug", confidence="medium"):
    return Hypothesis(cause="null 을 Set.contains 에 넘김", category=category, confidence=confidence, evidence=["s:1"], next_check="n")


def verdict(h=None, refs=("s:1",)):
    return Verdict(headline="h", hypotheses=[h or hyp()], deploy_related=False, unknown=False, invalid_evidence=0,
                   total_evidence=1, model="m", input_tokens=1, output_tokens=1, elapsed_s=1.0,
                   fix_candidates=[FixCandidate(place="Svc", why="null 처리", refs=list(refs))])


def bundle(code_ids=("s:1",), cards=()):
    return ContextBundle(
        alerts=[], window_start=STARTS, window_end=STARTS, templates=[], quiet_summary=[], metrics=[], restarts=[],
        collection_errors=[],
        code=[{"id": i, "path": PATH, "symbol": "Svc#ok", "revision": D, "body": "    4     return IDS.contains(id);"} for i in code_ids],
        cards=list(cards),
    )


class Cache:
    def __init__(self, tmp: Path, deployed: str, main: str | None, main_sha: str | None = M):
        self.roots = {}
        for sha, text in ((D, deployed), (M, main)):
            if text is None:
                continue
            f = tmp / sha / PATH
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(text)
            self.roots[sha] = tmp / sha / "src/main/java"
        self.main_sha = main_sha

    async def checkout(self, rev):
        return self.roots.get(rev)

    async def resolve(self, ref):
        return self.main_sha


def llm(text, seen=None):
    async def complete(system, user):
        if seen is not None:
            seen.append(user)
        return text, 0.03
    return complete


async def no_compare(base, head, path):
    return []


# --- choosing a target ---------------------------------------------------------------------


def test_pick_target_needs_a_confident_app_bug_and_a_code_ref():
    assert pick_target(verdict(), bundle())[0]["id"] == "s:1"
    assert pick_target(verdict(hyp(confidence="high")), bundle()) is not None
    assert pick_target(verdict(hyp(confidence="low")), bundle()) is None
    assert pick_target(verdict(hyp(category="external_api")), bundle()) is None
    assert pick_target(verdict(refs=("k:K11",)), bundle()) is None          # card only: no real code to edit
    assert pick_target(verdict(refs=("s:2",)), bundle()) is None            # cited code not in the bundle


def test_parse_and_apply_edits():
    assert parse_edits("no blocks") == []
    assert len(parse_edits("\n".join([BLOCK] * 5))) == 3
    edits = parse_edits(BLOCK)
    assert apply_edits(SRC, edits).count("id != null") == 1
    assert apply_edits(SRC.replace("IDS.contains(id)", "X"), edits) is None              # zero matches
    assert apply_edits(SRC + "  //    return IDS.contains(id);\n", edits) is None       # two matches
    assert apply_edits(SRC, []) is None


# --- the three outcomes ----------------------------------------------------------------------


async def test_same_commit_on_main_proposes_a_diff(tmp_path):
    seen = []
    cards = [{"id": "k:K21", "조치": "null 이면 NO_REALTIME_LINE"}]
    p = await propose(verdict(refs=("s:1", "k:K21")), bundle(cards=cards), complete=llm(BLOCK, seen),
                      cache=Cache(tmp_path, SRC, None, main_sha=D), compare=no_compare)
    assert p.status == "proposed" and p.main_sha == D and p.cost_usd == 0.03
    assert "+    return id != null && IDS.contains(id);" in p.diff and p.diff.startswith(f"--- a/{PATH}")
    assert "k:K21: null 이면 NO_REALTIME_LINE" in seen[0] and "배포 커밋 dddddddd" in seen[0]


async def test_main_ahead_but_untouched_still_proposes(tmp_path):
    main = SRC.replace("class Svc {", "class Svc {\n  // unrelated change on main")
    p = await propose(verdict(), bundle(), complete=llm(BLOCK), cache=Cache(tmp_path, SRC, main), compare=no_compare)
    assert p.status == "proposed" and p.main_sha == M and "unrelated change" in p.diff


async def test_main_changed_reports_the_commits_instead_of_proposing(tmp_path):
    main = SRC.replace("return IDS.contains(id);", "return id != null && IDS.contains(id);")
    calls = []

    async def compare(base, head, path):
        calls.append((base, head, path))
        return ["abc12345 fix(subway): null line id"]

    p = await propose(verdict(), bundle(), complete=llm(BLOCK), cache=Cache(tmp_path, SRC, main), compare=compare)
    assert p.status == "main_changed" and p.main_commits == ["abc12345 fix(subway): null line id"] and p.diff == ""
    assert calls == [(D, M, PATH)]


async def test_block_not_in_deployed_code_is_a_mismatch(tmp_path):
    bad = BLOCK.replace("return IDS.contains(id);", "return INVENTED.contains(id);", 1)
    p = await propose(verdict(), bundle(), complete=llm(bad), cache=Cache(tmp_path, SRC, SRC), compare=no_compare)
    assert p.status == "mismatch" and render_fix(p) is None


async def test_no_blocks_or_no_target_is_skipped(tmp_path):
    p = await propose(verdict(), bundle(), complete=llm("고칠 수 없다"), cache=Cache(tmp_path, SRC, SRC), compare=no_compare)
    assert p.status == "skipped" and p.cost_usd == 0.03
    calls = []
    p2 = await propose(verdict(hyp(confidence="low")), bundle(), complete=llm(BLOCK, calls), cache=Cache(tmp_path, SRC, SRC), compare=no_compare)
    assert p2.status == "skipped" and calls == []          # no LLM call when the gate fails


async def test_llm_or_cache_errors_become_failed_not_exceptions(tmp_path):
    async def boom(system, user):
        raise RuntimeError("secret-token in message")
    p = await propose(verdict(), bundle(), complete=boom, cache=Cache(tmp_path, SRC, SRC), compare=no_compare)
    assert p.status == "failed" and p.reason == "RuntimeError"
    p2 = await propose(verdict(), bundle(), complete=llm(BLOCK), cache=Cache(tmp_path, SRC, SRC, main_sha=None), compare=no_compare)
    assert p2.status == "failed" and "main" in p2.reason


async def test_secrets_in_the_model_output_are_masked(tmp_path):
    leaky = BLOCK.replace("return id != null && IDS.contains(id);", 'String k = "sk-ant-abcdef123456"; return id != null && IDS.contains(id);')
    p = await propose(verdict(), bundle(), complete=llm(leaky), cache=Cache(tmp_path, SRC, SRC, main_sha=D), compare=no_compare)
    assert "sk-ant-" not in p.diff


# --- Slack ------------------------------------------------------------------------------------


def test_render_fix_fits_and_marks_cuts():
    target = {"symbol": "Svc#ok", "revision": D}
    long = FixProposal("proposed", target, M, diff="+x\n" * 3000)
    text = render_fix(long)
    assert len(text) <= MAX_TEXT and "… 생략" in text and "참고용" in text and text.endswith("```")
    held = render_fix(FixProposal("main_changed", target, M, main_commits=["abc12345 fix"]))
    assert "수정 제안 보류" in held and "abc12345 fix" in held
    assert render_fix(FixProposal("skipped")) is None and render_fix(FixProposal("failed", target)) is None
