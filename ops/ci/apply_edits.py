"""Apply the analyzer's SEARCH/REPLACE edits to the target checkout (docs/plan/pipeline-fix-verify).

Same rules as alert-analyzer/analyzer/fix.py apply_edits: every SEARCH occurs exactly once in the
original file, blocks do not overlap, CRLF is normalised. Input comes from the EDITS env var as
base64(JSON {"path": "src/main/java/...java", "edits": [{"search": ..., "replace": ...}]}).

The path is untrusted (it travels through workflow inputs): only a plain src/main/java/**/*.java
path is accepted, so an edit can never touch the workflow, ops/ci or anything outside the repo.

Exit codes: 0 applied, 2 edits do not match the file, 3 bad input.
"""

from __future__ import annotations

import base64
import binascii
import json
import os
import re
import sys
from pathlib import Path

MAX_EDITS = 3
PATH = re.compile(r"^src/main/java/(?:[A-Za-z_$][A-Za-z0-9_$]*/)+[A-Za-z_$][A-Za-z0-9_$]*\.java$")


class BadInput(Exception):
    pass


def decode(raw: str) -> tuple[str, list[tuple[str, str]]]:
    try:
        data = json.loads(base64.b64decode(raw, validate=True).decode("utf-8"))
    except (binascii.Error, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BadInput(f"edits 해독 실패: {type(exc).__name__}") from exc
    if not isinstance(data, dict):
        raise BadInput("edits 가 객체가 아님")
    path = data.get("path")
    if not isinstance(path, str) or not PATH.match(path):
        raise BadInput("path 가 src/main/java/**/*.java 형식이 아님")
    edits = data.get("edits")
    if not isinstance(edits, list) or not 1 <= len(edits) <= MAX_EDITS:
        raise BadInput(f"edits 는 1~{MAX_EDITS}개")
    out = []
    for e in edits:
        if not isinstance(e, dict) or not isinstance(e.get("search"), str) or not isinstance(e.get("replace"), str):
            raise BadInput("edit 항목 형식 오류")
        if not e["search"].strip():
            raise BadInput("빈 SEARCH")  # not "main changed": the input itself is wrong
        out.append((e["search"].replace("\r\n", "\n"), e["replace"].replace("\r\n", "\n")))
    return path, out


def apply_edits(source: str, edits: list[tuple[str, str]]) -> str | None:
    """Checked against the original: a later SEARCH must not be satisfied by an earlier REPLACE."""
    if not edits:
        return None
    spans = []
    for search, replace in edits:
        if not search or source.count(search) != 1:
            return None
        start = source.index(search)
        spans.append((start, start + len(search), replace))
    spans.sort()
    if any(a[1] > b[0] for a, b in zip(spans, spans[1:])):
        return None
    for start, end, replacement in reversed(spans):
        source = source[:start] + replacement + source[end:]
    return source


def main(argv: list[str]) -> int:
    root = Path(argv[1]).resolve()
    try:
        path, edits = decode(os.environ.get("EDITS", ""))
    except BadInput as exc:
        print(exc, file=sys.stderr)
        return 3
    target = (root / path).resolve()
    if root not in target.parents or not target.is_file():
        print(f"파일 없음: {path}", file=sys.stderr)
        return 2
    original = target.read_bytes().decode("utf-8")  # read_text would fold CRLF and lose it
    crlf = "\r\n" in original
    changed = apply_edits(original.replace("\r\n", "\n"), edits)
    if changed is None:
        print(f"블록 {len(edits)}개가 {path} 와 안 맞음", file=sys.stderr)
        return 2
    target.write_bytes((changed.replace("\n", "\r\n") if crlf else changed).encode("utf-8"))
    print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
