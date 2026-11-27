"""Title and body for the Draft PR the create-pr job opens (stage 7, docs/plan/fix-approval-pr).

summary.json comes from the test job (model-written code ran there) and LABEL from the alert, so both are
untrusted: lengths are cut, backticks dropped and @mentions defused before they reach GitHub.

Usage: pr_body.py <summary.json or missing> <title out> <body out>   (env: LABEL, RUN_URL)
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

TITLE_CHARS = 100
ZERO_WIDTH_SPACE = chr(0x200B)


def safe(value: object, limit: int) -> str:
    text = value if isinstance(value, str) else ""
    text = " ".join(text.split())[:limit]
    # No code spans to break out of, no pings: "@team" becomes "@" + zero-width space + "team".
    return (text.replace("`", "'").replace("@", "@" + ZERO_WIDTH_SPACE)
            .replace("<", "&lt;").replace(">", "&gt;"))


def count(value: object) -> int:
    return value if isinstance(value, int) and not isinstance(value, bool) and 0 <= value < 10**7 else 0


def load(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8")[:200_000])
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def build(summary: dict, label: str, run_url: str) -> tuple[str, str]:
    name = safe(label, 80) or "알 수 없는 장애"
    title = f"fix: {name} (AI 제안)"[:TITLE_CHARS]
    selected = summary.get("selected")
    scope = "전체 테스트" if selected == "ALL" else f"테스트 클래스 {count(selected)}개"
    baseline = [f for f in summary.get("failures") or [] if isinstance(f, dict) and f.get("baseline") is True]
    lines = [
        "> ⚠️ **AI 가 만든 수정 제안입니다. 머지 전에 diff 를 직접 리뷰하세요.**",
        "> 장애 분석기가 제안하고, fix-verify 가 영향 테스트를 돌린 뒤, 사람이 승인해서 만든 Draft PR 입니다.",
        "",
        f"- **장애**: {name}",
        f"- **고친 곳**: `{safe(summary.get('path'), 200)}`",
        f"- **테스트**: 통과 {count(summary.get('passed'))} · 실패 {count(summary.get('failed'))}"
        f"(전부 수정 전에도 실패하던 기존 실패 {len(baseline)}) · {scope}",
        f"- **fix-verify 실행**: {safe(run_url, 300)}",
        "",
        "테스트 결과는 수정 코드가 같은 job 에서 돈 결과라 참고 신호입니다(위조 가능). 판단은 diff 로 하세요.",
    ]
    return title, "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    summary = load(Path(argv[1]))
    title, body = build(summary, os.environ.get("LABEL", ""), os.environ.get("RUN_URL", ""))
    Path(argv[2]).write_text(title, encoding="utf-8")
    Path(argv[3]).write_text(body, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
