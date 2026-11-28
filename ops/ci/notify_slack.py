"""Post the fix-verify result to the incident's Slack thread (docs/plan/pipeline-fix-verify).

Runs in the notify job: a fresh VM, main's code, no access to the patched checkout. summary.json
came from the test job, where model-written code ran, so it is untrusted data: every field is
type-checked, cut to length, masked for secret shapes and escaped for Slack mrkdwn. Nothing here
is ever passed to a shell.

Env: SLACK_BOT_TOKEN, CHANNEL, THREAD_TS, LABEL, TEST_RESULT (needs.test.result), RUN_URL.
Usage:
  notify_slack.py <summary.json, may be missing> [--approvable-output] [--dry-run]   result reply
  notify_slack.py --pr-url <url> [--dry-run]                                          Draft PR link reply
  notify_slack.py --pr-failed [--dry-run]                                             Draft PR failure reply

--approvable-output writes approvable=true|false to $GITHUB_OUTPUT before anything is posted, so the
create-pr job can wait for approval even if Slack fails (stage 7, docs/plan/fix-approval-pr).
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
PR_URL = re.compile(r"^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/pull/\d+$")
RUN_URL = re.compile(r"^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/actions/runs/\d+$")
SECTION_CHARS = 3000  # Block Kit section/context text limit
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
    # Mask before cutting: a token split at the limit would be too short for its pattern and leak in part.
    text = " ".join(text.split()[:2000])
    for pattern, repl in _MASKS:
        text = pattern.sub(repl, text)
    text = text[:limit]
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


def approvable(summary: dict | None) -> bool:
    """Stage 7 gate: no new failures. Untrusted input, so every field is checked, not assumed.

    passed with zero failures, or failed where the unpatched commit failed the same tests: baseline was
    checked, the list is complete (len == failed, not cut at 20) and every entry is a baseline failure.
    """
    if not isinstance(summary, dict):
        return False
    status = summary.get("status")
    failed = summary.get("failed")
    if not isinstance(failed, int) or isinstance(failed, bool):
        return False
    if status == "passed":
        return failed == 0
    if status != "failed" or summary.get("baseline_checked") is not True:
        return False
    failures = summary.get("failures")
    if not isinstance(failures, list) or failed <= 0 or len(failures) != failed:
        return False
    return all(isinstance(f, dict) and f.get("baseline") is True for f in failures)


def _cut(text: str, limit: int) -> str:
    if len(text) <= limit:
        return text
    # Do not leave half an escape sequence like "&am" behind.
    return re.sub(r"&[a-z]*$", "", text[: limit - 2]) + " …"


def _lines_within(lines: list[str], limit: int) -> str:
    """Whole lines only, so a cut never leaves a code span open."""
    out, size = [], 0
    for line in lines:
        if size + len(line) + 1 > limit:
            break
        out.append(line)
        size += len(line) + 1
    return "\n".join(out)


def section(mrkdwn: str) -> dict:
    return {"type": "section", "text": {"type": "mrkdwn", "text": _cut(mrkdwn, SECTION_CHARS) or "-"}}


def context(mrkdwn: str) -> dict:
    return {"type": "context", "elements": [{"type": "mrkdwn", "text": _cut(mrkdwn, SECTION_CHARS) or "-"}]}


def links(pairs: list[tuple[str, str]]) -> list[dict]:
    """URL buttons, only for URLs that passed RUN_URL / PR_URL. An empty url button would make Slack reject
    the whole message."""
    good = [(label, url) for label, url in pairs if RUN_URL.fullmatch(url) or PR_URL.fullmatch(url)]
    if not good:
        return []
    return [{"type": "actions", "elements": [
        {"type": "button", "text": {"type": "plain_text", "text": label}, "url": url, "action_id": f"link-{i}"}
        for i, (label, url) in enumerate(good)]}]


def render(summary: dict | None, label: str, test_result: str, run_url: str) -> tuple[str, list[dict]]:
    """(fallback text, blocks). label is already clean()ed."""
    title = f"🧪 *수정 검증*{' — ' + label if label else ''}"
    plain = f"🧪 수정 검증{' — ' + label if label else ''}"
    run = links([("실행 보기", run_url)])
    if summary is None or summary.get("status") not in STATUSES:
        why = f"결과 없음 — 테스트 job 이 {clean(test_result, 20) or '?'} 로 끝남"
        return f"{plain}: {why}", [section(f"{title}\n⚠️ {why}"), *run]
    status = summary["status"]
    path = clean(summary.get("path"), 160)
    took = duration(count(summary.get("duration_s")))
    if status == "apply_failed":
        why = "수정 블록이 main 코드와 안 맞아 적용 못 함(main 이 바뀌었을 수 있음)"
        return f"{plain}: {why}", [section(f"{title}\n⚠️ {why}"), *run]
    if status == "compile_failed":
        lines = [clean(l, 200) for l in (summary.get("compile_errors") or [])[:10] if isinstance(l, str)]
        body = _lines_within(lines, SECTION_CHARS - len("```\n\n```")) or "(오류 줄 없음)"
        return f"{plain}: 컴파일 실패", [
            section(f"{title}\n❌ 컴파일 실패"), context(f"`{path}` · {took}"), section(f"```\n{body}\n```"), *run]
    if status == "error":
        why = f"테스트를 끝까지 못 돌림({clean(summary.get('reason'), 40) or '원인 모름'})"
        return f"{plain}: {why}", [section(f"{title}\n⚠️ {why}"), context(took), *run]

    failures = [f for f in (summary.get("failures") or []) if isinstance(f, dict)][:20]
    new = [f for f in failures if f.get("baseline") is not True]
    old = [f for f in failures if f.get("baseline") is True]
    checked = summary.get("baseline_checked") is True
    selected = summary.get("selected")
    scope = "전체 테스트" if selected == "ALL" else f"클래스 {count(selected)}개"
    nums = f"통과 {count(summary.get('passed'))} · 실패 {count(summary.get('failed'))}"
    if failures:
        nums += f"(새 실패 {len(new)}, 기존 실패 {len(old)})" if checked else "(수정 전 코드로 재확인 못 함)"
    ok = approvable(summary)
    if status == "passed" or ok:
        icon, verdict = "✅", "새 실패 없음" + ("" if status == "passed" else " (기존 실패만)")
    elif checked and new:
        icon, verdict = "❌", f"새 실패 {len(new)}개"
    else:
        icon, verdict = "🟡", "실패 — 새 실패인지 확인 못 함"
    blocks = [section(f"{title}\n{icon} {verdict}"), context(f"{nums} · {scope} · {took} · `{path}`")]
    shown = (new or failures)[:SHOW_FAILURES]
    if shown:
        blocks.append(section(_lines_within(
            [f"• {clean(f.get('test'), 160)} — `{clean(f.get('message'), 200)}`" for f in shown], SECTION_CHARS)))
    note = "수정본은 실행의 artifact `fix-patch`(90일)"
    if ok:
        note = "artifact `fix-patch` diff 를 보고 승인하면 Draft PR 생성 · " + note
    blocks.append(context(note))
    blocks += links(([("diff 보고 승인", run_url)] if ok else []) + [("실행 보기", run_url)])
    first = f" · {clean(new[0].get('test'), 160)}" if new else ""
    see = f" · <{run_url}|실행 보기>" if RUN_URL.fullmatch(run_url) else ""
    return f"{plain}: {verdict} · {nums}{first}{see}", blocks


def post(token: str, channel: str, thread_ts: str, text: str, blocks: list[dict]) -> None:
    base = {"channel": channel, "thread_ts": thread_ts, "text": text, "unfurl_links": False, "unfurl_media": False}
    data = _post(token, {**base, "blocks": blocks} if blocks else base)
    if not data.get("ok") and blocks:
        # ok=false: nothing was posted. A layout problem must not swallow the result, so send the text once.
        print(f"blocks rejected ({data.get('error')}), posting text only", file=sys.stderr)
        data = _post(token, base)
    if not data.get("ok"):
        raise RuntimeError(f"slack error: {data.get('error')}")


def _post(token: str, payload: dict) -> dict:
    req = urllib.request.Request("https://slack.com/api/chat.postMessage", data=json.dumps(payload).encode("utf-8"),
                                 method="POST", headers={"Authorization": f"Bearer {token}",
                                                         "Content-Type": "application/json; charset=utf-8"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def write_output(name: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(f"{name}={value}\n")


def main(argv: list[str]) -> int:
    args = argv[1:]
    flags = {a for a in args if a.startswith("--")}
    positional = [a for i, a in enumerate(args) if not a.startswith("--") and (i == 0 or args[i - 1] != "--pr-url")]
    run_url = os.environ.get("RUN_URL", "")
    label = clean(os.environ.get("LABEL", ""), 80)
    title = f"🧪 *수정 검증*{' — ' + label if label else ''}"
    plain = f"🧪 수정 검증{' — ' + label if label else ''}"

    if "--pr-url" in flags:
        i = args.index("--pr-url")
        url = args[i + 1] if i + 1 < len(args) else ""
        if not PR_URL.fullmatch(url):
            print("PR url 형식 아님 — 보내지 않음", file=sys.stderr)
            return 1
        text = f"{plain}: Draft PR 만듦 {url}"
        blocks = [section(f"{title}\n➡️ *Draft PR 만듦* (AI 제안, 리뷰 후 머지)"), *links([("PR 보기", url)])]
    elif "--pr-failed" in flags:
        text = f"{plain}: Draft PR 만들기 실패"
        blocks = [section(f"{title}\n⚠️ Draft PR 만들기 실패"), *links([("실행 보기", run_url)])]
    else:
        summary = load(Path(positional[0])) if positional else None
        if "--approvable-output" in flags:
            # Before any Slack call: a Slack failure must not hide the approval gate.
            write_output("approvable", "true" if approvable(summary) else "false")
        text, blocks = render(summary, label, os.environ.get("TEST_RESULT", ""), run_url)

    channel, thread_ts = os.environ.get("CHANNEL", ""), os.environ.get("THREAD_TS", "")
    if not CHANNEL.fullmatch(channel) or not THREAD_TS.fullmatch(thread_ts):
        print("channel/thread_ts 형식 아님 — 보내지 않음", file=sys.stderr)
        return 1
    if "--dry-run" in argv:
        print(json.dumps({"text": text, "blocks": blocks}, ensure_ascii=False))
        return 0
    token = os.environ.get("SLACK_BOT_TOKEN", "")
    if not token:
        print("SLACK_BOT_TOKEN 없음", file=sys.stderr)
        return 1
    post(token, channel, thread_ts, text, blocks)
    print("posted")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
