"""Pick the tests a change can affect (docs/plan/pipeline-fix-verify, stage 5).

Decision (user, 11-27): err on the wide side — missing a broken test is worse than running extra.

1. Main side: reverse-dependency closure over src/main/java starting at the changed classes.
   A file depends on a type when it names it and can see it (same package, import, wildcard import,
   or fully qualified). Supertypes (extends/implements on class, interface, record, enum) of every
   member join too: callers usually hold the port interface, not the implementation.
2. Test side: test files that depend on the closure, then test files that depend on those
   (abstract bases, fakes, fixtures), until nothing changes.
3. Output runnable top-level test classes only: not abstract, not an interface, and with a test
   annotation of its own or inherited from a test-side supertype.
4. A changed class nothing points at (directly or through its supertypes) — @Scheduled jobs,
   @EventListener, @Configuration, filters, controllers — adds every test in its own package and
   below. (The plan said "first five segments"; measured on the repo that turns one controller in
   infrastructure.adapter.in.api into 130 test classes — every adapter — so it is the own package.)
5. The context-load test always runs (bean wiring breaks show up there).
6. A changed path that is not a main Java source prints ALL (run everything).

Usage: select_tests.py <repo root> <changed path>...   → one FQN per line, or ALL
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

CONTEXT_TEST = "watoo.grd.nextroute.NextrouteApplicationTests"
MAIN = "src/main/java/"
TEST = "src/test/java/"

_TEXT_BLOCK = re.compile(r'"""[\s\S]*?"""')
_LITERAL_OR_COMMENT = re.compile(r'/\*[\s\S]*?\*/|//[^\n]*|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'')
_PACKAGE = re.compile(r"(?:^|(?<=;))\s*package\s+([\w.]+)\s*;", re.M)
_IMPORT = re.compile(r"(?:^|(?<=;))\s*import\s+(?:static\s+)?([\w.]+)(\.\*)?\s*;", re.M)
_TOKEN = re.compile(r"\b[A-Z][A-Za-z0-9_]*\b")
_TEST_ANNOTATION = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")


def strip(code: str) -> str:
    """Drop comments and literals so names inside them are not counted as references."""
    code = _TEXT_BLOCK.sub('""', code)
    out, i = [], 0
    # One pass so a // inside a string or a quote inside a comment does not confuse the other rule.
    for m in _LITERAL_OR_COMMENT.finditer(code):
        out.append(code[i:m.start()])
        out.append('""' if m.group(0)[0] in "\"'" else " ")
        i = m.end()
    out.append(code[i:])
    return "".join(out)


def _drop_nested(text: str, open_: str, close: str) -> str:
    out, depth = [], 0
    for ch in text:
        if ch == open_:
            depth += 1
        elif ch == close and depth:
            depth -= 1
        elif not depth:
            out.append(ch)
    return "".join(out)


@dataclass
class Src:
    path: str
    fqn: str
    pkg: str
    name: str
    is_test: bool
    body: str
    tokens: set[str]
    imports: set[str]
    star_imports: set[str]
    super_names: list[str]
    abstract: bool
    interface: bool
    own_tests: bool
    supers: set[str] = field(default_factory=set)
    deps: set[str] = field(default_factory=set)


def parse(root: Path, rel: str) -> Src:
    raw = (root / rel).read_text(encoding="utf-8", errors="replace")
    code = strip(raw)
    pkg_m = _PACKAGE.search(code)
    pkg = pkg_m.group(1) if pkg_m else ""
    name = Path(rel).stem
    imports, stars = set(), set()
    for m in _IMPORT.finditer(code):
        (stars if m.group(2) else imports).add(m.group(1))
    body = _IMPORT.sub("", _PACKAGE.sub("", code))
    decl = re.search(rf"\b(class|interface|record|enum|@interface)\s+{re.escape(name)}\b", body)
    super_names: list[str] = []
    abstract = interface = False
    if decl:
        kind = decl.group(1)
        interface = kind in {"interface", "@interface"}
        line_start = body.rfind("\n", 0, decl.start()) + 1
        abstract = bool(re.search(r"\babstract\b", body[line_start:decl.start()]))
        brace = body.find("{", decl.end())
        header = body[decl.end(): brace if brace >= 0 else len(body)]
        header = _drop_nested(_drop_nested(header, "<", ">"), "(", ")")
        for clause in re.findall(r"\b(?:extends|implements)\s+(.+?)(?=\bimplements\b|\bpermits\b|$)", header, re.S):
            super_names += [n.strip().split(".")[-1] for n in clause.split(",") if n.strip()]
    return Src(rel, f"{pkg}.{name}" if pkg else name, pkg, name, rel.startswith(TEST), body,
               set(_TOKEN.findall(body)), imports, stars, super_names, abstract, interface,
               bool(_TEST_ANNOTATION.search(raw)))


class Index:
    def __init__(self, root: Path):
        self.files: dict[str, Src] = {}
        for base in (MAIN, TEST):
            for p in sorted((root / base).rglob("*.java")):
                s = parse(root, p.relative_to(root).as_posix())
                self.files.setdefault(s.fqn, s)
        self.by_simple: dict[str, list[Src]] = {}
        for s in self.files.values():
            self.by_simple.setdefault(s.name, []).append(s)
        for s in self.files.values():
            s.deps = self._refs(s)
            s.supers = {t.fqn for n in s.super_names for t in self.by_simple.get(n, []) if self._visible(s, t)}

    @staticmethod
    def _visible(src: Src, target: Src) -> bool:
        return (target.pkg == src.pkg or target.fqn in src.imports or target.pkg in src.star_imports
                or target.fqn in src.body)

    def _refs(self, src: Src) -> set[str]:
        out = set()
        # import a.b.Type / a.b.Type.Nested / static a.b.Type.member / static a.b.Type.* (a star on a type)
        for imp in src.imports | src.star_imports:
            parts = imp.split(".")
            for i in range(len(parts), 0, -1):
                if ".".join(parts[:i]) in self.files:
                    out.add(".".join(parts[:i]))
                    break
        for tok in src.tokens:
            for t in self.by_simple.get(tok, []):
                if t.fqn != src.fqn and self._visible(src, t):
                    out.add(t.fqn)
        out.discard(src.fqn)
        return out

    def ancestors(self, fqn: str) -> set[str]:
        seen, todo = set(), [fqn]
        while todo:
            for s in self.files[todo.pop()].supers:
                if s not in seen:
                    seen.add(s)
                    todo.append(s)
        return seen


def select(root: Path, changed: list[str]) -> list[str] | None:
    """FQNs to run, or None for everything."""
    if not changed or any(not (c.startswith(MAIN) and c.endswith(".java")) for c in changed):
        return None
    idx = Index(root)
    by_path = {s.path: s for s in idx.files.values()}
    start = {by_path[c].fqn for c in changed if c in by_path}
    if len(start) != len(changed):
        return None  # a changed file we cannot index: do not guess

    main = [s for s in idx.files.values() if not s.is_test]
    tests = [s for s in idx.files.values() if s.is_test]

    closure = set(start)
    frontier = set(start)
    while frontier:
        up = set().union(*(idx.ancestors(f) for f in frontier)) - closure
        up = {u for u in up if not idx.files[u].is_test}
        closure |= up
        reach = frontier | up
        nxt = {m.fqn for m in main if m.fqn not in closure and m.deps & reach}
        closure |= nxt
        frontier = nxt

    picked = {t.fqn for t in tests if t.deps & closure}
    while True:
        more = {t.fqn for t in tests if t.fqn not in picked and t.deps & picked}
        if not more:
            break
        picked |= more

    for c in start:
        targets = {c} | idx.ancestors(c)
        # Only main-side references count: one unit test of its own must not switch the fallback off
        # (measured: 26 @Scheduled/@EventListener classes are referenced only by their own tests).
        if not any(s.deps & targets for s in idx.files.values() if s.fqn != c and not s.is_test):
            prefix = idx.files[c].pkg
            picked |= {t.fqn for t in tests if t.pkg == prefix or t.pkg.startswith(prefix + ".")}

    def runnable(fqn: str) -> bool:
        s = idx.files[fqn]
        if s.abstract or s.interface:
            return False
        return s.own_tests or any(idx.files[a].own_tests for a in idx.ancestors(fqn) if idx.files[a].is_test)

    out = {f for f in picked if runnable(f)}
    if CONTEXT_TEST in idx.files:
        out.add(CONTEXT_TEST)
    return sorted(out)


def main(argv: list[str]) -> int:
    result = select(Path(argv[1]), argv[2:])
    print("ALL" if result is None else "\n".join(result))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
