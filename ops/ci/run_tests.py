"""Apply the proposed fix, pick tests, run them and write summary.json (docs/plan/pipeline-fix-verify).

Two steps so the workflow can upload the patch before any patched code runs:

  run_tests.py prepare <target> <out>   apply EDITS, write out/fix.patch and out/tests.txt
  run_tests.py test    <target> <out>   compile, run the picked tests, rerun failures on the
                                        unpatched commit, write out/summary.json

The result is a hint, not a safety gate: the patched code runs in this job and could forge it.

summary.json contract (read by notify_slack.py, which treats it as untrusted):
  status   passed | failed | apply_failed | compile_failed | error
  reason   short machine reason for error/apply_failed (bad_input, schema_drift, no_tests, ...)
  path, selected ("ALL" or class count), passed, failed, skipped, duration_s,
  failures [{test, message, baseline}] (new failures first, at most 20),
  compile_errors [line] (at most 20), baseline_checked
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import apply_edits  # noqa: E402
import select_tests  # noqa: E402

INIT = HERE / "ci-test.gradle"
MAX_FAILURES = 20
MESSAGE_CHARS = 200
# Wall-clock budget from the job start (JOB_STARTED, set by the workflow's first step) so checkout,
# schema restore and setup-java count too. The job timeout is 60 min; summary.json must be written
# before that or Slack only hears "결과 없음".
JOB_BUDGET_S = 55 * 60
BASELINE_MIN_LEFT_S = 10 * 60
DRIFT_MARKERS = ("FlywayValidateException", "Validate failed: Migrations have failed validation")


def write_summary(out: Path, **fields) -> None:
    base = {"status": "error", "reason": "", "path": "", "selected": 0, "passed": 0, "failed": 0,
            "skipped": 0, "duration_s": 0, "failures": [], "compile_errors": [], "baseline_checked": False}
    base.update(fields)
    (out / "summary.json").write_text(json.dumps(base, ensure_ascii=False, indent=1), encoding="utf-8")


def git(target: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(target), *args], check=True, capture_output=True, text=True).stdout


def prepare(target: Path, out: Path) -> int:
    out.mkdir(parents=True, exist_ok=True)
    code = apply_edits.main(["apply_edits.py", str(target)])
    if code != 0:
        write_summary(out, status="error" if code == 3 else "apply_failed",
                      reason="bad_input" if code == 3 else "edits_do_not_match")
        (out / "skip").write_text("1")
        return 0
    (out / "fix.patch").write_text(git(target, "diff"), encoding="utf-8")
    changed = [p for p in git(target, "diff", "--name-only").split("\n") if p]
    picked = select_tests.select(target, changed)
    (out / "tests.txt").write_text("ALL\n" if picked is None else "\n".join(picked) + "\n", encoding="utf-8")
    (out / "path.txt").write_text(",".join(changed), encoding="utf-8")
    print(f"changed={changed} selected={'ALL' if picked is None else len(picked)}")
    return 0


def remaining() -> float:
    started = float(os.environ.get("JOB_STARTED") or _STARTED)
    return started + JOB_BUDGET_S - time.time()


_STARTED = time.time()


def gradle(cwd: Path, *tasks: str, reserve: int = 120) -> tuple[int | None, str]:
    """(exit code, output). None = ran out of time (killed) or no time left to start."""
    timeout = remaining() - reserve
    if timeout < 60:
        return None, "no time left"
    cmd = ["./gradlew", "--no-daemon", "--console=plain", "--init-script", str(INIT), *tasks]
    log_path = cwd.parent / f"gradle-{int(time.time() * 1000)}.log"
    with log_path.open("w", encoding="utf-8") as fh:
        try:
            code: int | None = subprocess.run(cmd, cwd=cwd, stdout=fh, stderr=subprocess.STDOUT, timeout=timeout).returncode
        except subprocess.TimeoutExpired:
            code = None
    log = log_path.read_text(encoding="utf-8", errors="replace")
    sys.stdout.write(log[-20000:])
    return code, log


def results(build: Path) -> tuple[dict, list[dict]]:
    counts = {"tests": 0, "failed": 0, "skipped": 0}
    failures = []
    for xml in sorted((build / "test-results" / "test").glob("TEST-*.xml")):
        try:
            suite = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        for case in suite.iter("testcase"):
            counts["tests"] += 1
            if case.find("skipped") is not None:
                counts["skipped"] += 1
            bad = case.find("failure")
            if bad is None:
                bad = case.find("error")
            if bad is not None:
                counts["failed"] += 1
                text = (bad.get("message") or "") + "\n" + (bad.text or "")
                failures.append({
                    "test": f"{case.get('classname', '?')}#{case.get('name', '?')}",
                    "message": " ".join((bad.get("message") or (bad.text or "").strip().split("\n")[0]).split())[:MESSAGE_CHARS],
                    "drift": any(m in text for m in DRIFT_MARKERS),
                })
    return counts, failures


def compile_errors(log: str, root: Path | None = None) -> list[str]:
    """javac error lines, repo-relative, each once (Gradle prints them twice)."""
    prefix = f"{root}/" if root else None
    lines = [l.strip().replace(prefix, "") if prefix else l.strip() for l in log.splitlines()]
    picked = [l for l in lines if ": error:" in l or l.startswith("e: ")]
    picked = list(dict.fromkeys(picked))
    return (picked or [l for l in lines if l][-20:])[:20]


def test_args(tests: list[str]) -> list[str]:
    if tests == ["ALL"]:
        return ["test"]
    args = ["test"]
    for t in tests:
        args += ["--tests", t]
    return args


def run(target: Path, out: Path) -> int:
    started = time.monotonic()
    tests = [t for t in (out / "tests.txt").read_text(encoding="utf-8").split() if t]
    path = (out / "path.txt").read_text(encoding="utf-8")
    selected = "ALL" if tests == ["ALL"] else len(tests)
    common = {"path": path, "selected": selected}
    if not tests:
        write_summary(out, reason="no_tests", **common)
        return 0

    code, log = gradle(target, "compileJava", "compileTestJava")
    if code is None:
        write_summary(out, reason="timeout", duration_s=int(time.monotonic() - started), **common)
        return 0
    if code != 0:
        errors = compile_errors(log, target)
        if any(": error:" in l or l.startswith("e: ") for l in errors):
            write_summary(out, status="compile_failed", compile_errors=errors,
                          duration_s=int(time.monotonic() - started), **common)
        else:  # wrapper/dependency download and the like: not the fix's fault
            write_summary(out, reason="gradle_failed", compile_errors=errors,
                          duration_s=int(time.monotonic() - started), **common)
        return 0

    code, log = gradle(target, *test_args(tests))
    counts, failures = results(target / "build")
    elapsed = int(time.monotonic() - started)
    if code is None:
        write_summary(out, reason="timeout", passed=counts["tests"] - counts["failed"] - counts["skipped"],
                      failed=counts["failed"], skipped=counts["skipped"], duration_s=elapsed,
                      failures=[{k: v for k, v in f.items() if k != "drift"} | {"baseline": False}
                                for f in failures[:MAX_FAILURES]], **common)
        return 0
    if counts["tests"] == 0:
        write_summary(out, reason="no_tests_ran" if code == 0 else "gradle_failed",
                      compile_errors=[l for l in log.splitlines() if l.strip()][-20:], duration_s=elapsed, **common)
        return 0
    if any(f["drift"] for f in failures):
        write_summary(out, reason="schema_drift", passed=counts["tests"] - counts["failed"] - counts["skipped"],
                      failed=counts["failed"], skipped=counts["skipped"], duration_s=elapsed, **common)
        return 0

    for f in failures:
        f.pop("drift", None)
        f["baseline"] = False

    def final(checked: bool) -> None:
        failures.sort(key=lambda f: f["baseline"])
        write_summary(
            out,
            status="failed" if failures else ("passed" if code == 0 else "error"),
            reason="" if failures or code == 0 else "gradle_failed",
            passed=counts["tests"] - counts["failed"] - counts["skipped"],
            failed=counts["failed"], skipped=counts["skipped"],
            duration_s=int(time.monotonic() - started),
            failures=failures[:MAX_FAILURES], baseline_checked=checked, **common,
        )

    final(False)  # written first: if the baseline rerun hangs, this one still reaches Slack
    if failures and remaining() > BASELINE_MIN_LEFT_S:
        base_failed = baseline(target, failures)
        if base_failed is not None:
            for f in failures:
                f["baseline"] = f["test"] in base_failed
            final(True)
    return 0


def baseline(target: Path, failures: list[dict]) -> set[str] | None:
    """Rerun the failing classes on the unpatched commit in a separate worktree."""
    base = target.parent / "baseline"
    try:
        git(target, "worktree", "add", "--detach", str(base), "HEAD")
    except subprocess.CalledProcessError:
        return None
    classes = sorted({f["test"].split("#")[0].split("$")[0] for f in failures})
    code, _ = gradle(base, *test_args(classes))
    counts, base_failures = results(base / "build")
    if code is None or counts["tests"] == 0:
        return None
    return {f["test"] for f in base_failures}


def main(argv: list[str]) -> int:
    mode, target, out = argv[1], Path(argv[2]).resolve(), Path(argv[3]).resolve()
    if mode not in {"prepare", "test"}:
        print(f"unknown mode {mode}", file=sys.stderr)
        return 3
    try:
        return prepare(target, out) if mode == "prepare" else run(target, out)
    except Exception as exc:  # noqa: BLE001 — every path must leave a summary for Slack
        traceback.print_exc()
        out.mkdir(parents=True, exist_ok=True)
        write_summary(out, reason=f"exception:{type(exc).__name__}")
        if mode == "prepare":
            (out / "skip").write_text("1")
        return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
