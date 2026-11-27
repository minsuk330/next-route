"""Post the fix-verify result to the incident's Slack thread (docs/plan/pipeline-fix-verify).

Runs in the notify job: a fresh VM, main's code, no access to the patched checkout. summary.json
came from the test job, where model-written code ran, so it is untrusted data: every field is
type-checked, cut to length, masked for secret shapes and escaped for Slack mrkdwn. Nothing here
is ever passed to a shell.

Env: SLACK_BOT_TOKEN, CHANNEL, THREAD_TS, LABEL, TEST_RESULT (needs.test.result), RUN_URL.
Usage: notify_slack.py <summary.json path, may be missing> [--dry-run]
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.request
from pathlib import Path

CHANNEL = re.compile(r"^[CG][A-Z0-9]{6,}$")
THREAD_TS = re.compile(r"^\d{9,11}\.\d{1,6}$")
STATUSES = {"passed", "failed", "apply_failed", "compile_failed", "error"}
MAX_TEXT = 3000
SHOW_FAILURES = 5
# Same secret shapes as alert-analyzer/analyzer/masking.py (_CODE_RULES) plus GitHub tokens.
_MASKS = [
    (re.compile(r"xox[abprs]-[A-Za-z0-9-]+|xapp-[A-Za-z0-9-]+|hooks\.slack\.com/services/[A-Za-z0-9/_-]+"), "<slack-secret>"),
    (re.compile(r"sk-ant-[A-Za-z0-9_-]+"), "<anthropic-key>"),
    (re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}"), "<github-token>"),
    (re.compile(r"eyJ[\w-]+\.[\w-]+\.[\w-]+"), "<jwt>"),
    (re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{20,}"), "Bearer <redacted>"),
    (re.compile(r"(?i)\b(servicekey|apikey|api_key|key|token|secret|password)=[^&\s\"']{6,}"), r"\1=<redacted>"),
]


def clean(value: object, limit: int) -> str:
    text = value if isinstance(value, str) else ""
    text = " ".join(text.split())[:limit]
    for pattern, repl in _MASKS:
        text = pattern.sub(repl, text)
    # Backticks would close our code spans/blocks and let the rest render as mrkdwn.
    return text.replace("`", "'").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def count(value: object) -> int:
    return value if isinstance(value, int) and not isinstance(value, bool) and 0 <= value < 10**7 else 0


def duration(seconds: int) -> str:
    m, s = divmod(seconds, 60)
    return f"{m}분 {s}초" if m else f"{s}초"


def load(path: Path) -> dict | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8")[:200_000])
    except (OSError, ValueError):
        return None
    return data if isinstance(data, dict) else None


def render(summary: dict | None, label: str, test_result: str, run_url: str) -> str:
    head = f":test_tube: *수정 검증*{' — ' + label if label else ''}"
    link = f"<{run_url}|실행 보기>"
    if summary is None or summary.get("status") not in STATUSES:
        return f"{head}\n:warning: 결과 없음 — 테스트 job 이 {clean(test_result, 20) or '?'} 로 끝남 · {link}"
    status = summary["status"]
    path = clean(summary.get("path"), 160)
    took = duration(count(summary.get("duration_s")))
    if status == "apply_failed":
        return f"{head}\n:warning: 수정 블록이 main 코드와 안 맞아 적용 못 함(main 이 바뀌었을 수 있음) · {link}"
    if status == "compile_failed":
        lines = [clean(l, 200) for l in (summary.get("compile_errors") or [])[:10] if isinstance(l, str)]
        body = "\n".join(lines) or "(오류 줄 없음)"
        return f"{head}\n:x: 컴파일 실패 · `{path}` · {took} · {link}\n```\n{body}\n```"
    if status == "error":
        return f"{head}\n:warning: 테스트를 끝까지 못 돌림({clean(summary.get('reason'), 40) or '원인 모름'}) · {took} · {link}"

    failures = [f for f in (summary.get("failures") or []) if isinstance(f, dict)][:20]
    new = [f for f in failures if f.get("baseline") is not True]
    old = [f for f in failures if f.get("baseline") is True]
    selected = summary.get("selected")
    scope = "전체 테스트" if selected == "ALL" else f"클래스 {count(selected)}개"
    nums = f"통과 {count(summary.get('passed'))} · 실패 {count(summary.get('failed'))}"
    if failures:
        checked = summary.get("baseline_checked") is True
        nums += f"(새 실패 {len(new)}, 기존 실패 {len(old)})" if checked else "(수정 전 코드로 재확인 못 함)"
    icon = ":white_check_mark:" if status == "passed" else (":x:" if new else ":large_yellow_circle:")
    lines = [head, f"{icon} {nums} · {scope} · {took} · `{path}`"]
    for f in (new or failures)[:SHOW_FAILURES]:
        lines.append(f"    • {clean(f.get('test'), 160)} — `{clean(f.get('message'), 200)}`")
    lines.append(f"{link} · 수정본은 실행의 artifact `fix-patch`(90일)")
    text = "\n".join(lines)
    return text if len(text) <= MAX_TEXT else text[:MAX_TEXT - 2] + " …"


def post(token: str, channel: str, thread_ts: str, text: str) -> None:
    body = json.dumps({"channel": channel, "thread_ts": thread_ts, "text": text,
                       "unfurl_links": False, "unfurl_media": False}).encode("utf-8")
    req = urllib.request.Request("https://slack.com/api/chat.postMessage", data=body, method="POST",
                                 headers={"Authorization": f"Bearer {token}",
                                          "Content-Type": "application/json; charset=utf-8"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    if not data.get("ok"):
        raise RuntimeError(f"slack error: {data.get('error')}")


def main(argv: list[str]) -> int:
    channel, thread_ts = os.environ.get("CHANNEL", ""), os.environ.get("THREAD_TS", "")
    if not CHANNEL.match(channel) or not THREAD_TS.match(thread_ts):
        print("channel/thread_ts 형식 아님 — 보내지 않음", file=sys.stderr)
        return 1
    paths = [a for a in argv[1:] if not a.startswith("--")]
    summary = load(Path(paths[0])) if paths else None
    text = render(summary, clean(os.environ.get("LABEL", ""), 80), os.environ.get("TEST_RESULT", ""),
                  os.environ.get("RUN_URL", ""))
    if "--dry-run" in argv:
        print(text)
        return 0
    token = os.environ.get("SLACK_BOT_TOKEN", "")
    if not token:
        print("SLACK_BOT_TOKEN 없음", file=sys.stderr)
        return 1
    post(token, channel, thread_ts, text)
    print("posted")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
