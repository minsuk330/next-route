from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

import pytest

from analyzer.context import ContextBundle
from analyzer.fix import FixProposal, apply_edits, Edit, parse_edits, pick_target, propose
from analyzer.llm import FixCandidate, Hypothesis, Verdict
from analyzer.slack import MAX_TEXT, SECTION_CHARS, render_fix

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
    # stage 6 input: raw blocks and the repo-root path
    assert p.repo_path.startswith("src/main/java/") and p.repo_path.endswith(PATH.split("src/main/java/")[-1])
    assert len(p.edits) == 1 and "IDS.contains(id);" in p.edits[0].search
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
    msg = render_fix(long)
    code = msg.blocks[2]["text"]["text"]
    assert len(code) <= SECTION_CHARS and "… 생략" in code and code.endswith("```")
    assert "참고용" in msg.visible() and len(msg.text) <= MAX_TEXT
    held = render_fix(FixProposal("main_changed", target, M, main_commits=["abc12345 fix"])).visible()
    assert "수정 제안 보류" in held and "abc12345 fix" in held
    assert render_fix(FixProposal("skipped")) is None and render_fix(FixProposal("failed", target)) is None


def test_render_fix_adds_one_verify_line_and_still_fits():
    from analyzer.verify import VerifyResult
    target = {"symbol": "Svc#ok", "revision": D}
    ok = render_fix(FixProposal("proposed", target, M, diff="+x\n" * 3000),
                    VerifyResult(True, url="https://github.com/o/r/actions/runs/1"))
    assert ok.blocks[-1]["elements"][0]["url"] == "https://github.com/o/r/actions/runs/1"
    assert "테스트 요청함" in ok.visible() and "… 생략" in ok.visible()
    no_url = render_fix(FixProposal("proposed", target, M, diff="+x\n"), VerifyResult(True, url=""))
    assert all(b["type"] != "actions" for b in no_url.blocks)  # an empty url never becomes a button
    bad = render_fix(FixProposal("proposed", target, M, diff="+x\n"), VerifyResult(False, reason="GitHub 403 <x>"))
    assert "테스트 요청 실패(GitHub 403 &lt;x&gt;)" in bad.visible()
    assert "테스트" not in render_fix(FixProposal("proposed", target, M, diff="+x\n")).visible()


# --- review fixes (2026-11-27) ---------------------------------------------------------------


def test_apply_edits_checks_every_search_against_the_original():
    src = "a\nx=1;\nb\n"
    chained = [Edit("x=1;\n", "x=2;\n"), Edit("x=2;\n", "x=3;\n")]
    assert apply_edits(src, chained) is None                       # second SEARCH is not in the source
    overlap = [Edit("a\nx=1;\n", "A\n"), Edit("x=1;\nb\n", "B\n")]
    assert apply_edits(src, overlap) is None
    makes_dup = [Edit("a\n", "b\n"), Edit("b\n", "c\n")]         # first REPLACE creates a second "b"
    assert apply_edits(src, makes_dup) == "b\nx=1;\nc\n"


def test_parse_edits_handles_crlf_and_line_deletion():
    crlf = BLOCK.replace("\n", "\r\n")
    assert len(parse_edits(crlf)) == 1
    delete = "<<<<<<< SEARCH\n  // remove me\n=======\n>>>>>>> REPLACE"
    assert apply_edits("a\n  // remove me\nb\n", parse_edits(delete)) == "a\nb\n"


async def test_secret_shaped_source_line_still_matches_and_the_diff_is_masked(tmp_path):
    src = SRC.replace("    return IDS.contains(id);", '    String k = "sk-ant-abcdef123456";\n    return IDS.contains(id);')
    block = BLOCK.replace("    return IDS.contains(id);\n=======", '    String k = "sk-ant-abcdef123456";\n    return IDS.contains(id);\n=======')
    block = block.replace("=======\n    return id != null", '=======\n    String k = "sk-ant-abcdef123456";\n    return id != null')
    p = await propose(verdict(), bundle(), complete=llm(block), cache=Cache(tmp_path, src, None, main_sha=D), compare=no_compare)
    assert p.status == "proposed" and "sk-ant-" not in p.diff and "id != null" in p.diff


async def test_failure_after_the_llm_call_keeps_cost_and_a_readable_reason(tmp_path):
    from analyzer.llm import LlmFailure

    async def refused(system, user):
        raise LlmFailure("응답 중단(max_tokens)")
    p = await propose(verdict(), bundle(), complete=refused, cache=Cache(tmp_path, SRC, SRC), compare=no_compare)
    assert p.status == "failed" and p.reason == "응답 중단(max_tokens)"

    class Broken(Cache):
        async def resolve(self, ref):
            raise RuntimeError("boom")
    p2 = await propose(verdict(), bundle(), complete=llm(BLOCK), cache=Broken(tmp_path, SRC, SRC), compare=no_compare)
    assert p2.status == "failed" and p2.cost_usd == 0.03


async def test_github_compare_lists_only_commits_in_range_that_touched_the_file():
    from types import SimpleNamespace
    from analyzer.fix import github_compare

    class Http:
        def __init__(self):
            self.calls = []

        async def get(self, url, params=None, headers=None, timeout=None):
            self.calls.append((url, params, headers))
            if "/compare/" in url:
                body = {"commits": [{"sha": "a" * 40}, {"sha": "b" * 40}, {"sha": "c" * 40}]}
            else:   # newest first, path-filtered, includes one older than the deploy
                body = [
                    {"sha": "c" * 40, "commit": {"message": "fix(x): newest\nbody"}},
                    {"sha": "a" * 40, "commit": {"message": "feat(x): older"}},
                    {"sha": "9" * 40, "commit": {"message": "before the deploy"}},
                ]
            return SimpleNamespace(raise_for_status=lambda: None, json=lambda: body)

    http = Http()
    out = await github_compare(http, "o/r", "tok")(D, M, PATH)
    assert out == ["cccccccc fix(x): newest", "aaaaaaaa feat(x): older"]
    assert http.calls[1][1] == {"sha": M, "path": PATH, "per_page": 30}
    assert all(h["Authorization"] == "Bearer tok" for _, _, h in http.calls)



async def test_edits_stay_raw_while_the_diff_is_masked(tmp_path):
    secret = 'String key = "sk-ant-abcdefghijklmnop";'
    src = SRC.replace("class Svc {", "class Svc {\n  " + secret)
    block = (
        "<<<<<<< SEARCH\n  " + secret + "\n=======\n  " + secret + " // keep\n>>>>>>> REPLACE\n"
    )
    p = await propose(verdict(), bundle(), complete=llm(block), cache=Cache(tmp_path, src, None, main_sha=D), compare=no_compare)
    assert p.status == "proposed"
    assert "sk-ant-abcdefghijklmnop" in p.edits[0].search  # a masked SEARCH would not match in the workflow
    assert "sk-ant-abcdefghijklmnop" not in p.diff
