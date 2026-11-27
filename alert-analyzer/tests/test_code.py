from __future__ import annotations

import subprocess
from datetime import datetime, timezone
from pathlib import Path

import pytest

from analyzer import code as code_mod
from analyzer.code import (
    CodeCollector,
    CodeFetchError,
    SourceCache,
    deployed,
    enclosing_method,
    find_by_keyword,
    frames_from_stack,
    method_at,
    parse_frame,
)
from tests.conftest import FakeMetrics, vector

pytestmark = pytest.mark.anyio

JAVA = Path(__file__).resolve().parent.parent / "fixtures" / "java"
SHA = "f07e1f5920bad7d789a137fbf1c18ae0c37f10bd"
SHA2 = "47d81acf4cdf9eefcf1dc3a0324f8f3947f800d0"
AT = datetime(2026, 11, 27, 5, 0, tzinfo=timezone.utc)


# --- frames ---------------------------------------------------------------------------

STACK = """org.springframework.web.client.ResourceAccessException: I/O error
\tat org.springframework.web.client.RestTemplate.doExecute(RestTemplate.java:900)
\tat org.springframework.web.client.RestTemplate.execute(RestTemplate.java:800)
Caused by: java.net.SocketTimeoutException: Read timed out
\tat java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:288)
\tat watoo.grd.nextroute.infrastructure.adapter.out.api.otp.OtpRoutingAdapter.lambda$search$0(OtpRoutingAdapter.java:142)
\tat watoo.grd.nextroute.infrastructure.adapter.out.api.otp.OtpRoutingAdapter$$SpringCGLIB$$0.search(<generated>)
\tat watoo.grd.nextroute.infrastructure.adapter.out.api.otp.OtpRoutingAdapter.search(OtpRoutingAdapter.java:142)
\tat watoo.grd.nextroute.application.route.service.RouteSearchService$Budget.run(RouteSearchService.java:88)
"""


def test_frames_come_from_caused_by_too_and_skip_library_and_proxies():
    assert frames_from_stack(STACK) == [
        "watoo.grd.nextroute.infrastructure.adapter.out.api.otp.OtpRoutingAdapter.search(OtpRoutingAdapter.java:142)",
        "watoo.grd.nextroute.application.route.service.RouteSearchService$Budget.run(RouteSearchService.java:88)",
    ]


def test_frames_limit_and_empty():
    many = "\n".join(f"\tat watoo.grd.nextroute.a.C{i}.m(C{i}.java:{i})" for i in range(9))
    assert len(frames_from_stack(many)) == 5
    assert frames_from_stack("") == []
    assert frames_from_stack(None) == []


def test_inner_class_frame_maps_to_outer_file():
    f = parse_frame("watoo.grd.nextroute.application.route.service.RouteSearchService$Budget.run(RouteSearchService.java:88)")
    assert f.path == "src/main/java/watoo/grd/nextroute/application/route/service/RouteSearchService.java"
    assert f.symbol == "RouteSearchService#run"
    assert parse_frame("org.x.Y.m(Y.java:1)") is None


# --- methods --------------------------------------------------------------------------

TRICKY = '''package watoo.grd.nextroute.x;

/** 까다로운 경우 모음. */
public class Tricky {

    private final Runnable field = () -> {
        System.out.println("{ not a block");
    };

    Tricky(String a) {
        this.a = a; // } brace in comment
    }

    @ExceptionHandler(IllegalStateException.class)
    public String handle(IllegalStateException e) {
        String s = "}{";
        char c = '}';
        String block = """
            { "json": } inside a text block
            """;
        Runnable r = () -> {
            work();
        };
        new Thread() {
            @Override
            public void run() {
                inner();
            }
        }.start();
        if (s.isEmpty()) {
            return "x";
        }
        return s;
    }
}
'''


def test_method_at_handles_strings_comments_text_blocks_lambdas_and_anonymous_classes():
    lines = TRICKY.splitlines()
    def at(text):
        return lines.index(next(l for l in lines if text in l)) + 1
    handle = method_at(TRICKY, at('return "x"'))
    assert handle[2] == "handle" and handle[0] == at("@ExceptionHandler")
    assert method_at(TRICKY, at("work();"))[2] == "handle"      # lambda inside the method
    assert method_at(TRICKY, at("inner();"))[2] == "handle"     # anonymous class: outermost method
    assert method_at(TRICKY, at("this.a = a"))[2] == "Tricky"   # package-private constructor
    assert method_at(TRICKY, at("not a block")) is None          # field lambda is not a method
    assert method_at(TRICKY, at("private final Runnable")) is None
    assert enclosing_method(TRICKY, 9999) is None


def test_method_at_on_real_controller():
    src = (JAVA / "RouteSearchController.java").read_text(encoding="utf-8")
    lines = src.splitlines()
    record = next(i for i, l in enumerate(lines, 1) if "searchEventRecorder.ifAvailable" in l)
    start, end, name = method_at(src, record)
    assert name == "search" and "@GetMapping" in lines[start - 1] and lines[end - 1].strip() == "}"
    switch = next(i for i, l in enumerate(lines, 1) if "case TIMEOUT" in l)
    assert method_at(src, switch)[2] == "handleOdSayApiException"


def test_method_at_on_real_global_handler():
    src = (JAVA / "GlobalExceptionHandler.java").read_text(encoding="utf-8")
    lines = src.splitlines()
    line = next(i for i, l in enumerate(lines, 1) if '"Unhandled exception. method={}, path={}"' in l)
    assert method_at(src, line)[2] == "handleUnexpectedException"


# --- keyword --------------------------------------------------------------------------


def _tree(tmp_path: Path, files: dict[str, str]) -> Path:
    for rel, text in files.items():
        p = tmp_path / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
    return tmp_path


def test_uri_matches_class_and_method_mapping_split(tmp_path):
    root = _tree(tmp_path, {
        "a/RouteSearchController.java": '@RequestMapping("/api/route")\nclass R {\n  @GetMapping("/search")\n  X s() { return null; }\n}\n',
        "a/Other.java": 'class O { String s = "/search"; }\n',
    })
    hits = find_by_keyword(root, "/api/route/search")
    assert [(p.name, line) for p, line in hits] == [("RouteSearchController.java", 3)]


def test_tag_literal_and_too_many_files_dropped(tmp_path):
    root = _tree(tmp_path, {"a/L.java": 'class L { void m() { log.warn("[LeaderLease] x"); } }\n'})
    assert [p.name for p, _ in find_by_keyword(root, "[LeaderLease]")] == ["L.java"]
    root2 = _tree(tmp_path / "many", {f"a/C{i}.java": 'class C { String t = "[Common] x"; }\n' for i in range(6)})
    assert find_by_keyword(root2, "[Common]") == []


# --- deployed revisions -----------------------------------------------------------------


def _build(role, rev, dirty="false", instance=None):
    return vector({"role": role, "instance": instance or f"{role}:8081", "revision": rev, "dirty": dirty}, 1)


async def test_deployed_reads_roles_and_marks_ambiguous_dirty_or_short_unknown():
    series = [
        _build("api", SHA),
        _build("worker", SHA), _build("worker", SHA2),     # mid-deploy: two revisions
        _build("worker2", SHA, dirty="true"),
        _build("otp", "47d81acf"),
    ]
    dep = await deployed(FakeMetrics(lambda q, at: series), AT)
    assert dep.revision_by_role == {"api": SHA, "worker": None, "worker2": None, "otp": None}
    assert dep.role_by_instance["api:8081"] == "api"


# --- collector --------------------------------------------------------------------------


class FakeCache:
    def __init__(self, root: Path | None, error: str | None = None):
        self.root, self.error, self.calls = root, error, []

    async def checkout(self, revision):
        self.calls.append(revision)
        if self.error:
            raise CodeFetchError(self.error)
        return self.root


class T:
    def __init__(self, app, frames=(), template="x"):
        self.app, self.frames, self.template = app, list(frames), template


def _src_root(tmp_path: Path) -> Path:
    body = "\n".join(f"        step{i}();" for i in range(40))
    return _tree(tmp_path / "src", {
        "watoo/grd/nextroute/a/Svc.java": (
            "package watoo.grd.nextroute.a;\n\n/** 서비스 설명. */\npublic class Svc {\n"
            "    public void run() {\n" + body + "\n    }\n}\n"
        ),
        "watoo/grd/nextroute/a/Lease.java": 'package watoo.grd.nextroute.a;\nclass Lease {\n  void m() {\n    log.warn("[LeaderLease] lost");\n  }\n}\n',
    })


def _prom(role_revs):
    return FakeMetrics(lambda q, at: [_build(r, v) for r, v in role_revs.items()])


async def test_collect_prefers_stack_and_builds_snippet(tmp_path):
    root = _src_root(tmp_path)
    collector = CodeCollector(_prom({"worker": SHA}), FakeCache(root))
    ctx = await collector(
        [T("worker", ["watoo.grd.nextroute.a.Svc.run(Svc.java:10)"], "[LeaderLease] lost")], [], AT
    )
    assert ctx.via == "stack"
    first = ctx.snippets[0]
    assert (first.id, first.symbol, first.start_line, first.revision, first.role) == ("s:1", "Svc#run", 5, SHA, "worker")
    assert first.header == "watoo.grd.nextroute.a.Svc — 서비스 설명."
    assert "   10         step4();" in first.body
    assert [s.via for s in ctx.snippets] == ["stack", "keyword"]


async def test_collect_uri_role_from_instance_and_keyword_only(tmp_path):
    root = _tree(tmp_path / "src", {"x/R.java": '@RequestMapping("/api/route")\nclass R {\n  @GetMapping("/search")\n  X s() {\n    return null;\n  }\n}\n'})
    cache = FakeCache(root)
    alerts = [{"labels": {"uri": "/api/route/search", "instance": "api:8081"}}]
    ctx = await CodeCollector(_prom({"api": SHA}), cache)([], alerts, AT)
    assert ctx.via == "keyword" and ctx.snippets[0].symbol == "R#s" and cache.calls == [SHA]


async def test_collect_version_unknown_does_not_fetch(tmp_path):
    cache = FakeCache(_src_root(tmp_path))
    ctx = await CodeCollector(_prom({"worker": "manual"}), cache)(
        [T("worker", ["watoo.grd.nextroute.a.Svc.run(Svc.java:10)"])], [], AT
    )
    assert ctx.via == "version_unknown" and ctx.snippets == [] and cache.calls == []
    assert ctx.errors == ["코드 버전 불확실(worker)"]


async def test_collect_fetch_failure_is_an_error_not_a_crash(tmp_path):
    ctx = await CodeCollector(_prom({"worker": SHA}), FakeCache(None, error="git fetch 실패(exit 128)"))(
        [T("worker", ["watoo.grd.nextroute.a.Svc.run(Svc.java:10)"])], [], AT
    )
    assert ctx.via == "none" and ctx.errors == [f"코드: worker {SHA[:8]} git fetch 실패(exit 128)"]


async def test_collect_respects_budget_and_max_snippets(tmp_path):
    root = _src_root(tmp_path)
    frames = [f"watoo.grd.nextroute.a.Svc.run(Svc.java:{n})" for n in (10, 20)]
    small = await CodeCollector(_prom({"worker": SHA}), FakeCache(root), budget_chars=100)([T("worker", frames)], [], AT)
    assert small.snippets == [] and small.truncated
    same = await CodeCollector(_prom({"worker": SHA}), FakeCache(root))([T("worker", frames)], [], AT)
    assert len(same.snippets) == 1   # two frames in one method → one snippet


async def test_collect_no_candidates_is_none():
    ctx = await CodeCollector(_prom({"api": SHA}), FakeCache(None))([T("api")], [], AT)
    assert ctx.via == "none" and ctx.errors == []


# --- source cache (real git, local repo) -----------------------------------------------------


def _git(cwd, *args):
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args], cwd=cwd, check=True, capture_output=True)


def _repo(tmp_path: Path, n: int) -> tuple[Path, list[str]]:
    work = tmp_path / "work"
    (work / "src/main/java/a").mkdir(parents=True)
    (work / "docs").mkdir()
    _git(tmp_path, "init", "-q", str(work))
    shas = []
    for i in range(n):
        (work / "src/main/java/a/A.java").write_text(f"class A {{ int v = {i}; }}\n")
        (work / "docs/big.txt").write_text("x" * 100)
        _git(work, "add", "-A")
        _git(work, "commit", "-q", "-m", f"c{i}")
        shas.append(subprocess.run(["git", "rev-parse", "HEAD"], cwd=work, capture_output=True, text=True).stdout.strip())
    bare = tmp_path / "bare.git"
    _git(tmp_path, "clone", "-q", "--bare", str(work), str(bare))
    _git(bare, "config", "uploadpack.allowAnySHA1InWant", "true")
    _git(bare, "config", "uploadpack.allowFilter", "true")
    return bare, shas


async def test_source_cache_checks_out_only_src_of_that_commit_and_keeps_a_few(tmp_path, monkeypatch):
    bare, shas = _repo(tmp_path, code_mod.KEEP_REVISIONS + 1)
    cache = SourceCache(str(tmp_path / "cache"), "o/r", "secret-token-123", remote_url=f"file://{bare}")
    first = await cache.checkout(shas[0])
    assert (first / "a/A.java").read_text() == "class A { int v = 0; }\n"
    assert not (first.parent.parent.parent / "docs").exists()
    for sha in shas[1:]:
        await cache.checkout(sha)
    kept = sorted(p.name for p in (tmp_path / "cache").iterdir() if p.is_dir())
    assert len(kept) == code_mod.KEEP_REVISIONS and shas[0] not in kept
    assert await cache.checkout("manual") is None


async def test_source_cache_error_never_carries_the_token(tmp_path):
    cache = SourceCache(str(tmp_path / "cache"), "o/r", "secret-token-123", remote_url=f"file://{tmp_path}/missing.git")
    with pytest.raises(CodeFetchError) as err:
        await cache.checkout(SHA)
    assert "secret-token-123" not in str(err.value) and "missing" not in str(err.value)
    assert not (tmp_path / "cache" / SHA).exists()


# --- review fixes (2026-11-27) ---------------------------------------------------------------

RECORDS = '''package watoo.grd.nextroute.x;

public record Rec(String a, int b) {
    public Rec {
        if (b < 0) {
            throw new IllegalArgumentException();
        }
    }

    int twice() {
        return b * 2;
    }
}

enum Op {
    PLUS("+") {
        int apply(int x, int y) {
            return x + y;
        }
    };
    Op(String s) {
        this.s = s;
    }
}

class Ann {
    @Operation(responses = {@ApiResponse(responseCode = "200")})
    @GetMapping(produces = {"application/json"})
    public String get() {
        return "x";
    }
}
'''


def _line(src, text):
    return next(i for i, l in enumerate(src.splitlines(), 1) if text in l)


def test_record_and_enum_bodies_are_not_methods():
    assert method_at(RECORDS, _line(RECORDS, "return b * 2"))[2] == "twice"
    assert method_at(RECORDS, _line(RECORDS, "throw new IllegalArgumentException"))[2] == "Rec"   # compact constructor
    assert method_at(RECORDS, _line(RECORDS, "return x + y"))[2] == "apply"
    assert method_at(RECORDS, _line(RECORDS, "this.s = s"))[2] == "Op"


def test_annotation_arrays_do_not_cut_the_method_start():
    start, _, name = method_at(RECORDS, _line(RECORDS, 'return "x"'))
    assert name == "get" and start == _line(RECORDS, "@Operation")


def test_tag_hint_picks_the_method_that_logs_this_template(tmp_path):
    src = (
        "class S {\n"
        "  void a() { log.warn(\"[BusArrival] start {}\", n); }\n"
        "  void b() { log.warn(\"[BusArrival] reconcile failed rows={}\", n); }\n"
        "}\n"
    )
    root = _tree(tmp_path, {"S.java": src})
    assert code_mod.template_hint("[BusArrival] reconcile failed rows=#") == "reconcile failed rows"
    assert [l for _, l in find_by_keyword(root, "[BusArrival]", "reconcile failed rows")] == [3]
    assert [l for _, l in find_by_keyword(root, "[BusArrival]", "nothing like it")] == [2]


def test_uri_with_several_http_methods_returns_each_mapping(tmp_path):
    src = '@RequestMapping("/api/journeys")\nclass J {\n  @GetMapping("/{journeyId}")\n  X detail() { return null; }\n  @DeleteMapping("/{journeyId}")\n  X cancel() { return null; }\n}\n'
    root = _tree(tmp_path, {"J.java": src})
    assert [l for _, l in find_by_keyword(root, "/api/journeys/{journeyId}")] == [3, 5]


async def test_deployed_uses_a_plain_selector_so_old_revisions_go_stale():
    metrics = FakeMetrics(lambda q, at: [_build("api", SHA)])
    await deployed(metrics, AT)
    assert metrics.calls == [("instant", 'app_build{job="nextroute-app"}', AT)]


async def test_uri_without_known_instance_falls_back_to_api(tmp_path):
    root = _tree(tmp_path / "src", {"x/R.java": '@RequestMapping("/api/a")\nclass R {\n  @GetMapping("/b")\n  X s() {\n    return null;\n  }\n}\n'})
    cache = FakeCache(root)
    ctx = await CodeCollector(_prom({"api": SHA, "worker": SHA2}), cache)([], [{"labels": {"uri": "/api/a/b"}}], AT)
    assert ctx.snippets[0].role == "api" and cache.calls == [SHA]


async def test_collect_stops_at_max_snippets(tmp_path):
    files = {f"watoo/grd/nextroute/a/C{i}.java": f"package watoo.grd.nextroute.a;\nclass C{i} {{\n  void m() {{\n    x();\n  }}\n}}\n" for i in range(7)}
    root = _tree(tmp_path / "src", files)
    frames = [f"watoo.grd.nextroute.a.C{i}.m(C{i}.java:4)" for i in range(7)]
    ctx = await CodeCollector(_prom({"api": SHA}), FakeCache(root))([T("api", frames)], [], AT)
    assert [s.id for s in ctx.snippets] == ["s:1", "s:2", "s:3", "s:4", "s:5"]


async def test_token_only_travels_in_the_askpass_env(tmp_path, monkeypatch):
    seen = []

    class Proc:
        pid = 1
        async def wait(self):
            return 0

    async def fake_exec(*args, **kwargs):
        seen.append((args, kwargs["env"]))
        if "init" in args:
            (kwargs["cwd"]).mkdir(exist_ok=True)
        return Proc()

    monkeypatch.setattr(code_mod.asyncio, "create_subprocess_exec", fake_exec)
    cache = SourceCache(str(tmp_path / "cache"), "o/r", "secret-token-123")
    await cache.checkout(SHA)
    for args, env in seen:
        assert not any("secret-token-123" in a for a in args)
        assert env["ANALYZER_GIT_PASSWORD"] == "secret-token-123" and env["GIT_TERMINAL_PROMPT"] == "0"
        assert "credential.helper=" in args and "maintenance.auto=false" in args
    assert any("https://github.com/o/r.git" in args for args, _ in seen)


async def test_git_missing_becomes_a_safe_fetch_error(tmp_path, monkeypatch):
    async def no_git(*args, **kwargs):
        raise FileNotFoundError("git")

    monkeypatch.setattr(code_mod.asyncio, "create_subprocess_exec", no_git)
    with pytest.raises(CodeFetchError, match="git 실행 실패"):
        await SourceCache(str(tmp_path / "cache"), "o/r", "t").checkout(SHA)
    assert not (tmp_path / "cache" / SHA).exists()


async def test_half_fetched_dirs_are_pruned(tmp_path):
    bare, shas = _repo(tmp_path, 1)
    (tmp_path / "cache" / SHA2).mkdir(parents=True)   # crash leftover, no .ok
    await SourceCache(str(tmp_path / "cache"), "o/r", "t", remote_url=f"file://{bare}").checkout(shas[0])
    assert not (tmp_path / "cache" / SHA2).exists()


async def test_resolve_reads_a_branch_sha_from_the_remote(tmp_path):
    bare, shas = _repo(tmp_path, 2)
    cache = SourceCache(str(tmp_path / "cache"), "o/r", "t", remote_url=f"file://{bare}")
    branch = subprocess.run(["git", "symbolic-ref", "--short", "HEAD"], cwd=bare, capture_output=True, text=True).stdout.strip()
    assert await cache.resolve(branch) == shas[-1]
    assert await cache.resolve("no-such-branch") is None
    assert await cache.resolve("bad ref; rm -rf") is None
    await cache.checkout(shas[0])
    assert (tmp_path / "cache" / ".ls-remote").exists()   # not pruned as a half fetch
