"""Put the methods an alert points at, from the commit that is actually deployed.

Production can run a different commit than main (2026-11-26: api 7acc7a34 while
main was 3fbcc18a), so the code comes from the revision each role reports in
`app_build{revision}` (the jar carries its own 40-char commit, PR #653), fetched
from GitHub as a sparse checkout of src/main/java and cached per revision.

Finding the code, in order (docs/plan/rag-incident-context):
1. stack frames of our package in the alert window's log lines (exact file:line)
2. log tags like "[LeaderLease]" and the alert's uri label (literal search)
Semantic search is deliberately not here: `code_via` is logged so we can count how
often both fail before building it.

No LLM here. Failures never stop the analysis: they become collection errors.
"""

from __future__ import annotations

import asyncio
import logging
import os
import re
import shutil
import signal
import stat
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Literal, Protocol

log = logging.getLogger(__name__)

PACKAGE = "watoo.grd.nextroute."
MAX_SNIPPETS = 5
MAX_FRAMES = 5
METHOD_LINES = 150
BUDGET_CHARS = 24_000
KEYWORD_MAX_FILES = 5
GIT_TIMEOUT_S = 30.0
KEEP_REVISIONS = 4   # deployed commit and main are used together (analyzer.fix)
SOURCE_ROOT = "src/main/java"

Via = Literal["stack", "keyword", "none", "disabled", "version_unknown"]

_FULL_SHA = re.compile(r"[0-9a-f]{40}")
# "at watoo.grd.nextroute.a.B$C.lambda$m$0(B.java:142)" — also inside "Caused by" sections.
_FRAME = re.compile(r"at (watoo\.grd\.nextroute\.[\w.$]+)\.([\w$<>]+)\(([\w$]+\.java):(\d+)\)")
_TAG = re.compile(r"^\[([A-Za-z][\w-]{1,40})\]")


class MetricSource(Protocol):
    async def instant(self, query: str, at: datetime) -> list[dict]: ...


# --- stack frames ------------------------------------------------------------------


@dataclass(frozen=True)
class Frame:
    class_name: str   # watoo.grd.nextroute.a.B$C
    method: str       # m (lambda$m$0 → m)
    file: str         # B.java
    line: int

    @property
    def path(self) -> str:
        package = [p for p in self.class_name.split(".") if p[:1].islower()]
        return "/".join([SOURCE_ROOT, *package, self.file])

    @property
    def symbol(self) -> str:
        return f"{self.class_name.rsplit('.', 1)[-1].split('$')[0]}#{self.method}"

    def __str__(self) -> str:
        return f"{self.class_name}.{self.method}({self.file}:{self.line})"


def frames_from_stack(stack: str, limit: int = MAX_FRAMES) -> list[str]:
    """Our package's frames, top first, across "Caused by" sections; generated proxies dropped."""
    found: list[str] = []
    for cls, method, file, line in _FRAME.findall(stack or ""):
        if "$$" in cls:  # CGLIB / Spring proxies have no source
            continue
        lam = re.match(r"lambda\$(\w+?)\$\d+$", method)
        frame = str(Frame(cls, lam.group(1) if lam else method, file, int(line)))
        if frame not in found:
            found.append(frame)
        if len(found) >= limit:
            break
    return found


def parse_frame(text: str) -> Frame | None:
    m = re.fullmatch(r"(watoo\.grd\.nextroute\.[\w.$]+)\.([\w$<>]+)\(([\w$]+\.java):(\d+)\)", text)
    return Frame(m.group(1), m.group(2), m.group(3), int(m.group(4))) if m else None


# --- java source -------------------------------------------------------------------


def _code_mask(src: str) -> list[bool]:
    """True for characters inside comments, string/char literals and text blocks."""
    hidden = [False] * len(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if two == "//":
            j = src.find("\n", i)
            j = n if j < 0 else j
        elif two == "/*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
        elif c in "\"'":
            j = i + 1
            while j < n and src[j] != c and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            j = min(j + 1, n)
        else:
            i += 1
            continue
        for k in range(i, j):
            hidden[k] = True
        i = j
    return hidden


_KEYWORDS = {"if", "for", "while", "switch", "catch", "synchronized", "return", "try", "else", "do"}
_THROWS = re.compile(r"\s*throws\s+[\w.,\s<>]+$")


def _header_name(flat: str) -> str | None:
    """Name of a method/constructor header ("... name(params) [throws X]"), else None."""
    text = _THROWS.sub("", flat).rstrip()
    # record compact constructor: "public Fare {" (no parameter list)
    compact = re.fullmatch(r"(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|protected|private)\s+)?([A-Z]\w*)", text)
    if compact:
        return compact.group(1)
    if not text.endswith(")") or "->" in text:
        return None
    depth = 0
    for idx in range(len(text) - 1, -1, -1):
        ch = text[idx]
        if ch == ")":
            depth += 1
        elif ch == "(":
            depth -= 1
            if depth == 0:
                before = text[:idx].rstrip()
                m = re.search(r"(\w+)$", before)
                if not m or m.group(1) in _KEYWORDS:
                    return None
                prefix = before[: m.start()]
                # a declaration has a return type, modifier or annotation before the name; a call does not
                if re.search(r"\b(?:class|interface|enum|record)\s+\w|\bnew\s+\w", prefix) or "=" in prefix.split("(")[0]:
                    return None
                # "record Rec(...)" header: the name follows the keyword, so the check above misses it
                if re.search(r"\b(?:record|enum)\s*$", prefix):
                    return None
                # enum constant body "PLUS(\"+\") {": no type or modifier before an all-caps name
                if not prefix.strip() and m.group(1).isupper():
                    return None
                # package-private constructors have nothing before the name
                if not prefix.strip() and not m.group(1)[:1].isupper():
                    return None
                return m.group(1)
    return None


def method_at(source: str, line: int) -> tuple[int, int, str] | None:
    """1-based (start, end, name) of the outermost method or constructor holding `line`."""
    hidden = _code_mask(source)
    line_start = [0]
    for idx, ch in enumerate(source):
        if ch == "\n":
            line_start.append(idx + 1)
    if not 1 <= line <= len(line_start):
        return None
    target = line_start[line - 1]

    blocks: list[tuple[int, int, int]] = []  # (open, close, header_start)
    stack: list[tuple[int, int]] = []
    head = 0
    parens = 0
    for idx, ch in enumerate(source):
        if hidden[idx]:
            continue
        if ch == "(":
            parens += 1
            continue
        if ch == ")":
            parens = max(0, parens - 1)
            continue
        if parens:
            # {..} inside (..): annotation arrays, lambda bodies passed as arguments
            continue
        if ch == "{":
            stack.append((idx, head))
            head = idx + 1
        elif ch == "}":
            if stack:
                open_idx, header_start = stack.pop()
                blocks.append((open_idx, idx, header_start))
            head = idx + 1
        elif ch == ";":
            head = idx + 1

    for open_idx, close_idx, header_start in sorted(
        (b for b in blocks if b[2] <= target <= b[1]), key=lambda b: b[0]
    ):
        header = "".join(" " if hidden[k] else source[k] for k in range(header_start, open_idx))
        name = _header_name(" ".join(header.split()))
        if not name:
            continue
        first = header_start
        while first < open_idx and (hidden[first] or source[first].isspace()):
            first += 1
        return _line_of(line_start, first), _line_of(line_start, close_idx), name
    return None


def enclosing_method(source: str, line: int) -> tuple[int, int] | None:
    found = method_at(source, line)
    return (found[0], found[1]) if found else None


def _line_of(line_start: list[int], offset: int) -> int:
    lo, hi = 0, len(line_start) - 1
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if line_start[mid] <= offset:
            lo = mid
        else:
            hi = mid - 1
    return lo + 1


def class_header(source: str, class_name: str) -> str:
    """"패키지.클래스 — javadoc 첫 줄": what the method's class is for (process() alone says nothing)."""
    pkg = re.search(r"^\s*package\s+([\w.]+)\s*;", source, re.M)
    name = class_name.split("$")[0]
    doc = ""
    m = re.search(
        r"/\*\*(.*?)\*/\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:public|final|abstract|sealed|non-sealed)\s+)*"
        r"(?:class|interface|enum|record)\s+" + re.escape(name) + r"\b",
        source,
        re.S,
    )
    if m:
        for raw in m.group(1).splitlines():
            text = re.sub(r"<[^>]+>|\{@\w+\s+([^}]*)\}", r"\1", raw.strip().lstrip("*").strip())
            if text:
                doc = text[:160]
                break
    qualified = f"{pkg.group(1)}.{name}" if pkg else name
    return f"{qualified} — {doc}" if doc else qualified


HITS_PER_FILE = 3


def find_by_keyword(root: Path, keyword: str, hint: str = "") -> list[tuple[Path, int]]:
    """Literal hits for a log tag or uri. Too many files means the keyword says nothing: drop it.

    A tag is used in many methods of one class ([BusArrival] in execute, reconcile, ...), so
    `hint` (the longest fixed chunk of the log template) picks the line when it matches. A uri
    can map several HTTP methods (GET/DELETE on one path) and the alert has no method label,
    so every matching mapping in the file is returned (up to HITS_PER_FILE).
    """
    needles = _needles(keyword)
    hits: list[tuple[Path, int]] = []
    files = 0
    for path in sorted(root.rglob("*.java")):
        text = path.read_text(encoding="utf-8", errors="replace")
        lines = _hits(text, needles, hint)
        if lines:
            files += 1
            if files > KEYWORD_MAX_FILES:
                return []
            hits.extend((path, line) for line in lines[:HITS_PER_FILE])
    return hits


def _needles(keyword: str) -> list[list[str]]:
    """Each entry is a set of literals that must all appear; the hit line is the last one's."""
    if keyword.startswith("/"):
        options = [[f'"{keyword}"']]
        parts = [p for p in keyword.split("/") if p]
        for cut in range(1, len(parts)):
            options.append([f'"/{"/".join(parts[:cut])}"', f'"/{"/".join(parts[cut:])}"'])
        return options
    return [[f'"{keyword}']]


def _hits(text: str, needles: list[list[str]], hint: str) -> list[int]:
    for group in needles:
        if not all(n in text for n in group):
            continue
        rows = text.splitlines()
        lines = [i for i, row in enumerate(rows, 1) if group[-1] in row]
        if hint:
            hinted = [i for i in lines if hint in rows[i - 1]]
            return hinted or lines[:1]
        return lines
    return []


def template_hint(template: str) -> str:
    """Longest fixed chunk after the tag of a normalized template ("#", "<hex>" mark variables)."""
    rest = _TAG.sub("", template or "")
    chunks = re.split(r"#|<hex>|<uuid>|<redacted>|<email>|\{\}", rest)
    best = max((c.strip(" :=,.-") for c in chunks), key=len, default="")
    return best if len(best) >= 4 else ""


# --- revisions -----------------------------------------------------------------------


@dataclass
class Deployed:
    revision_by_role: dict[str, str | None] = field(default_factory=dict)   # None = unknown
    role_by_instance: dict[str, str] = field(default_factory=dict)


async def deployed(prom: MetricSource, at: datetime) -> Deployed:
    """What each role ran at the alert. Two revisions for one role (both live) or a dirty/short one → unknown."""
    seen: dict[str, set[str | None]] = {}
    out = Deployed()
    # Plain selector, not last_over_time: the instance label stays "api:8081" across deploys, so a
    # range query would also return the previous revision for 5 minutes (staleness markers ignored).
    for series in await prom.instant('app_build{job="nextroute-app"}', at):
        labels = series.get("metric", {})
        role, instance = labels.get("role", ""), labels.get("instance", "")
        if not role:
            continue
        rev = labels.get("revision", "")
        ok = bool(_FULL_SHA.fullmatch(rev)) and labels.get("dirty") != "true"
        seen.setdefault(role, set()).add(rev if ok else None)
        if instance:
            out.role_by_instance[instance] = role
    for role, revs in seen.items():
        out.revision_by_role[role] = next(iter(revs)) if len(revs) == 1 else None
    return out


# --- source cache --------------------------------------------------------------------

_ASKPASS = """#!/bin/sh
case "$1" in
  Username*) echo x-access-token ;;
  *) echo "$ANALYZER_GIT_PASSWORD" ;;
esac
"""


class SourceCache:
    """src/main/java of one commit, fetched once and kept for the last few revisions.

    The token only travels in an env var read by an askpass script: never in the
    remote URL, arguments, logs or error messages.
    """

    def __init__(self, cache_dir: str, repo: str, token: str, *, remote_url: str | None = None):
        self._dir = Path(cache_dir)
        self._remote = remote_url or f"https://github.com/{repo}.git"
        self._token = token
        self._lock = asyncio.Lock()

    async def checkout(self, revision: str) -> Path | None:
        if not _FULL_SHA.fullmatch(revision):
            return None
        target = self._dir / revision
        if (target / ".ok").exists():
            os.utime(target)
            return target / SOURCE_ROOT
        async with self._lock:
            if (target / ".ok").exists():
                return target / SOURCE_ROOT
            try:
                return await self._fetch(revision, target)
            except CodeFetchError:
                shutil.rmtree(target, ignore_errors=True)
                raise
            except OSError as exc:
                shutil.rmtree(target, ignore_errors=True)
                raise CodeFetchError(f"코드 캐시 오류({type(exc).__name__})") from None

    async def resolve(self, ref: str) -> str | None:
        """40-char SHA of a branch on the remote (git ls-remote), or None."""
        if not re.fullmatch(r"[\w./-]{1,100}", ref):
            return None
        self._dir.mkdir(parents=True, exist_ok=True)
        askpass = self._askpass()
        scratch = self._dir / ".ls-remote"
        scratch.mkdir(exist_ok=True)
        try:
            code, out = await self._git(scratch, askpass, ["ls-remote", self._remote, f"refs/heads/{ref}"], capture=True)
        except (CodeFetchError, OSError):
            return None
        first = out.decode(errors="replace").split() if code == 0 else []
        return first[0] if first and _FULL_SHA.fullmatch(first[0]) else None

    def _askpass(self) -> Path:
        askpass = self._dir / "askpass.sh"
        if not askpass.exists():
            askpass.write_text(_ASKPASS)
            askpass.chmod(stat.S_IRWXU)
        return askpass

    async def _fetch(self, revision: str, target: Path) -> Path:
        self._dir.mkdir(parents=True, exist_ok=True)
        askpass = self._askpass()
        shutil.rmtree(target, ignore_errors=True)
        target.mkdir()
        steps = [
            ["init", "-q"],
            ["remote", "add", "origin", self._remote],
            ["sparse-checkout", "set", SOURCE_ROOT],
            ["fetch", "-q", "--depth", "1", "--filter=blob:none", "origin", revision],
            ["checkout", "-q", "FETCH_HEAD"],
        ]
        for args in steps:
            code = await self._git(target, askpass, args)
            if code != 0:
                shutil.rmtree(target, ignore_errors=True)
                raise CodeFetchError(f"git {args[0]} 실패(exit {code})")
        (target / ".ok").touch()
        self._prune()
        return target / SOURCE_ROOT

    async def _git(self, cwd: Path, askpass: Path, args: list[str], capture: bool = False):
        """Exit code, or (exit code, stdout bytes) with capture=True."""
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": str(self._dir),
            "GIT_ASKPASS": str(askpass),
            "GIT_TERMINAL_PROMPT": "0",
            "ANALYZER_GIT_PASSWORD": self._token,
        }
        try:
            proc = await asyncio.create_subprocess_exec(
                "git", "-c", "credential.helper=", "-c", "protocol.file.allow=always",
                # no detached background maintenance: it escapes killpg and the memory limit
                "-c", "maintenance.auto=false", "-c", "gc.auto=0", *args,
                cwd=cwd, env=env,
                stdout=asyncio.subprocess.PIPE if capture else asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
                start_new_session=True,
            )
        except OSError as exc:
            raise CodeFetchError(f"git 실행 실패({type(exc).__name__})") from None
        try:
            if capture:
                out, _ = await asyncio.wait_for(proc.communicate(), GIT_TIMEOUT_S)
                return proc.returncode, out
            return await asyncio.wait_for(proc.wait(), GIT_TIMEOUT_S)
        except asyncio.TimeoutError:
            with _suppress():
                os.killpg(proc.pid, signal.SIGKILL)
            await proc.wait()
            raise CodeFetchError(f"git {args[0]} 시간 초과({GIT_TIMEOUT_S:.0f}초)") from None

    def _prune(self) -> None:
        dirs = [p for p in self._dir.iterdir() if p.is_dir() and not p.name.startswith(".")]
        for half in (p for p in dirs if not (p / ".ok").exists()):
            shutil.rmtree(half, ignore_errors=True)   # left by a crash mid-fetch (runs under the lock)
        done = sorted((p for p in dirs if (p / ".ok").exists()), key=lambda p: p.stat().st_mtime, reverse=True)
        for old in done[KEEP_REVISIONS:]:
            shutil.rmtree(old, ignore_errors=True)


class CodeFetchError(Exception):
    """Safe to show: carries no URL, token or git output."""


class _suppress:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return True


# --- collecting --------------------------------------------------------------------


@dataclass
class CodeSnippet:
    id: str
    revision: str
    role: str
    path: str
    symbol: str
    header: str
    start_line: int
    end_line: int
    body: str
    via: Literal["stack", "keyword"]


@dataclass
class CodeContext:
    snippets: list[CodeSnippet] = field(default_factory=list)
    via: Via = "none"
    errors: list[str] = field(default_factory=list)
    truncated: bool = False

    def as_dicts(self) -> list[dict]:
        return [asdict(s) for s in self.snippets]


class Checkout(Protocol):
    async def checkout(self, revision: str) -> Path | None: ...


@dataclass
class _Candidate:
    role: str
    via: Literal["stack", "keyword"]
    frame: Frame | None = None
    keyword: str | None = None
    hint: str = ""


class CodeCollector:
    def __init__(self, prom: MetricSource, cache: Checkout, *, budget_chars: int = BUDGET_CHARS):
        self._prom = prom
        self._cache = cache
        self._budget = budget_chars

    async def __call__(self, templates: list, alerts: list[dict], at: datetime) -> CodeContext:
        ctx = CodeContext()
        try:
            dep = await deployed(self._prom, at)
        except Exception as exc:  # noqa: BLE001
            ctx.errors.append(f"코드: 배포 커밋 조회 실패({type(exc).__name__})")
            return ctx

        candidates = _candidates(templates, alerts, dep)
        if not candidates:
            ctx.via = "none"
            return ctx

        unknown: set[str] = set()
        seen: set[tuple[str, str, int]] = set()
        used = 0
        for cand in candidates:
            if len(ctx.snippets) >= MAX_SNIPPETS:
                break
            rev = dep.revision_by_role.get(cand.role)
            if not rev:
                unknown.add(cand.role)
                continue
            try:
                root = await self._cache.checkout(rev)
            except CodeFetchError as exc:
                msg = f"코드: {cand.role} {rev[:8]} {exc}"
                if msg not in ctx.errors:
                    ctx.errors.append(msg)
                continue
            if root is None:
                unknown.add(cand.role)
                continue
            for snippet in _snippets_for(cand, root, rev):
                key = (snippet.revision, snippet.path, snippet.start_line)
                if key in seen:
                    continue
                if used + len(snippet.body) > self._budget:
                    ctx.truncated = True
                    break
                seen.add(key)
                used += len(snippet.body)
                snippet.id = f"s:{len(ctx.snippets) + 1}"
                ctx.snippets.append(snippet)
                if len(ctx.snippets) >= MAX_SNIPPETS:
                    break

        for role in sorted(unknown):
            ctx.errors.append(f"코드 버전 불확실({role})")
        if any(s.via == "stack" for s in ctx.snippets):
            ctx.via = "stack"
        elif ctx.snippets:
            ctx.via = "keyword"
        elif unknown:
            ctx.via = "version_unknown"
        else:
            ctx.via = "none"
        return ctx


def _candidates(templates: list, alerts: list[dict], dep: Deployed) -> list[_Candidate]:
    out: list[_Candidate] = []
    for t in templates:
        for text in getattr(t, "frames", []) or []:
            frame = parse_frame(text)
            if frame:
                out.append(_Candidate(role=t.app, via="stack", frame=frame))
    for t in templates:
        tag = _TAG.match(getattr(t, "template", "") or "")
        if tag and t.app in ("api", "worker", "worker2"):
            out.append(_Candidate(role=t.app, via="keyword", keyword=f"[{tag.group(1)}]", hint=template_hint(t.template)))
    for alert in alerts:
        labels = alert.get("labels", {}) or {}
        uri = labels.get("uri", "")
        if uri.startswith("/"):
            role = dep.role_by_instance.get(labels.get("instance", ""), "api")
            out.append(_Candidate(role=role, via="keyword", keyword=uri))
    return out


def _snippets_for(cand: _Candidate, root: Path, rev: str) -> list[CodeSnippet]:
    if cand.frame is not None:
        rel = cand.frame.path[len(SOURCE_ROOT) + 1:]
        path = root / rel
        if not path.is_file():
            return []
        snippet = _snippet(path, root, cand.frame.line, rev, cand.role, "stack", cand.frame.class_name)
        return [snippet] if snippet else []
    out = []
    for path, line in find_by_keyword(root, cand.keyword or "", cand.hint):
        snippet = _snippet(path, root, line, rev, cand.role, "keyword", None)
        if snippet:
            out.append(snippet)
    return out


def _snippet(path: Path, root: Path, line: int, rev: str, role: str, via, class_name: str | None) -> CodeSnippet | None:
    source = path.read_text(encoding="utf-8", errors="replace")
    found = method_at(source, line)
    lines = source.splitlines()
    method = found[2] if found else None
    span = (found[0], found[1]) if found else (max(1, line - 10), min(len(lines), line + 10))
    start, end = span
    if end - start + 1 > METHOD_LINES:
        half = METHOD_LINES // 2
        start, end = max(start, line - half), min(end, line + half)
    body = "\n".join(f"{n:>5} {lines[n - 1]}" for n in range(start, end + 1) if n <= len(lines))
    name = class_name or path.stem
    rel = f"{SOURCE_ROOT}/{path.relative_to(root).as_posix()}"
    return CodeSnippet(
        id="",
        revision=rev,
        role=role,
        path=rel,
        symbol=f"{name.rsplit('.', 1)[-1].split('$')[0]}#{method}" if method else name.rsplit(".", 1)[-1],
        header=class_header(source, name.rsplit(".", 1)[-1]),
        start_line=start,
        end_line=end,
        body=body,
        via=via,
    )

