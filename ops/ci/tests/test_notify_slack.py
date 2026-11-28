import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import notify_slack as ns  # noqa: E402

URL = "https://github.com/o/r/actions/runs/1"


def summary(**kw):
    base = {"status": "passed", "path": "src/main/java/a/B.java", "selected": 12, "passed": 40, "failed": 0,
            "skipped": 0, "duration_s": 512, "failures": [], "compile_errors": [], "baseline_checked": False}
    base.update(kw)
    return base


class RenderTest(unittest.TestCase):
    def test_passed(self):
        text = ns.render(summary(), "SubwayRing 5xx", "success", URL)
        self.assertIn("수정 검증", text)
        self.assertIn("통과 40 · 실패 0 · 클래스 12개 · 8분 32초", text)
        self.assertIn(f"<{URL}|실행 보기>", text)
        self.assertIn("fix-patch", text)

    def test_failed_splits_new_and_baseline(self):
        f = [{"test": "a.BTest#x", "message": "boom", "baseline": False},
             {"test": "a.CIT#y", "message": "timeout", "baseline": True}]
        text = ns.render(summary(status="failed", failed=2, failures=f, baseline_checked=True), "", "success", URL)
        self.assertIn("(새 실패 1, 기존 실패 1)", text)
        self.assertIn("a.BTest#x — `boom`", text)
        self.assertNotIn("a.CIT#y", text)  # only new failures listed when there are any

    def test_failed_without_baseline_check(self):
        f = [{"test": "a.BTest#x", "message": "boom", "baseline": False}]
        text = ns.render(summary(status="failed", failed=1, failures=f), "", "success", URL)
        self.assertIn("재확인 못 함", text)

    def test_other_statuses(self):
        self.assertIn("적용 못 함", ns.render(summary(status="apply_failed"), "", "success", URL))
        text = ns.render(summary(status="compile_failed", compile_errors=["B.java:3: error: ';' expected"]), "", "success", URL)
        self.assertIn("컴파일 실패", text)
        self.assertIn("error: ';' expected", text)
        self.assertIn("schema_drift", ns.render(summary(status="error", reason="schema_drift"), "", "success", URL))

    def test_missing_or_unknown_summary(self):
        self.assertIn("결과 없음", ns.render(None, "", "failure", URL))
        self.assertIn("failure", ns.render(None, "", "failure", URL))
        self.assertIn("결과 없음", ns.render({"status": "hacked"}, "", "success", URL))

    def test_untrusted_fields_are_escaped_masked_and_typed(self):
        f = [{"test": "<!channel> a#b", "message": "token=supersecretvalue xoxb-123-456 <https://evil|x>", "baseline": False}]
        s = summary(status="failed", failed="9999", passed=True, selected=[1], failures=f, path="<@U1>")
        text = ns.render(s, "", "success", URL)
        self.assertNotIn("<!channel>", text)
        self.assertNotIn("<https://evil", text)
        self.assertNotIn("supersecretvalue", text)
        self.assertNotIn("xoxb-123", text)
        self.assertNotIn("<@U1>", text)
        self.assertIn("통과 0 · 실패 0", text)  # non-int counts fall back to 0

    def test_backticks_cannot_close_code_blocks(self):
        s = summary(status="compile_failed", compile_errors=["B.java:1: error: ```\n<!here> *bold*"], path="a`b")
        text = ns.render(s, "", "success", URL)
        self.assertEqual(text.count("```"), 2)  # only our own fence
        self.assertNotIn("<!here>", text)

    def test_length_cap(self):
        f = [{"test": "t" * 500, "message": "m" * 500, "baseline": False}] * 20
        self.assertLessEqual(len(ns.render(summary(status="failed", failures=f), "x" * 500, "success", URL)), ns.MAX_TEXT)


class MainTest(unittest.TestCase):
    def setUp(self):
        self.env = dict(os.environ)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.env)

    def test_refuses_bad_channel_or_thread(self):
        for ch, ts in [("C123; rm", "1700000000.000100"), ("C0123ABCD", "1700000000.0001x"), ("", "")]:
            os.environ.update({"CHANNEL": ch, "THREAD_TS": ts})
            with self.subTest(ch=ch, ts=ts):
                self.assertEqual(ns.main(["x", "--dry-run"]), 1)

    def test_dry_run_with_missing_file(self):
        os.environ.update({"CHANNEL": "C0123ABCD", "THREAD_TS": "1700000000.000100", "RUN_URL": URL, "TEST_RESULT": "cancelled"})
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(ns.main(["x", str(Path(d, "nope.json")), "--dry-run"]), 0)
            p = Path(d, "s.json")
            p.write_text(json.dumps(summary()))
            self.assertEqual(ns.main(["x", str(p), "--dry-run"]), 0)




class ApprovableTest(unittest.TestCase):
    def f(self, baseline=True):
        return {"test": "a.B#x", "message": "m", "baseline": baseline}

    def test_table(self):
        cases = [
            ("passed", summary(), True),
            ("passed but failed count", summary(failed=1), False),
            ("all baseline", summary(status="failed", failed=2, failures=[self.f(), self.f()], baseline_checked=True), True),
            ("one new", summary(status="failed", failed=2, failures=[self.f(), self.f(False)], baseline_checked=True), False),
            ("baseline not checked", summary(status="failed", failed=1, failures=[self.f()], baseline_checked=False), False),
            ("list cut at 20", summary(status="failed", failed=25, failures=[self.f()] * 20, baseline_checked=True), False),
            ("compile_failed", summary(status="compile_failed"), False),
            ("apply_failed", summary(status="apply_failed"), False),
            ("error", summary(status="error"), False),
            ("missing", None, False),
            ("forged types", summary(status="failed", failed="1", failures=[self.f()], baseline_checked=True), False),
            ("baseline truthy not True", summary(status="failed", failed=1, failures=[{"baseline": 1}], baseline_checked=True), False),
        ]
        for name, s, want in cases:
            with self.subTest(name):
                self.assertIs(ns.approvable(s), want)

    def test_render_shows_approval_link_only_when_approvable(self):
        self.assertIn("승인하면 Draft PR 생성", ns.render(summary(), "", "success", URL))
        bad = summary(status="failed", failed=1, failures=[self.f(False)], baseline_checked=True)
        self.assertNotIn("승인하면", ns.render(bad, "", "success", URL))


class ModesTest(unittest.TestCase):
    def setUp(self):
        self.env = dict(os.environ)
        self.tmp = tempfile.TemporaryDirectory()
        os.environ.update({"CHANNEL": "C0123ABCD", "THREAD_TS": "1700000000.000100", "RUN_URL": URL})
        os.environ.pop("SLACK_BOT_TOKEN", None)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.env)
        self.tmp.cleanup()

    def test_approvable_output_is_written_even_when_slack_cannot_post(self):
        out = Path(self.tmp.name, "out")
        os.environ["GITHUB_OUTPUT"] = str(out)
        p = Path(self.tmp.name, "s.json")
        p.write_text(json.dumps(summary()))
        self.assertEqual(ns.main(["x", str(p), "--approvable-output"]), 1)  # no token: Slack fails
        self.assertEqual(out.read_text(), "approvable=true\n")
        os.environ["CHANNEL"] = "bad"
        out.write_text("")
        self.assertEqual(ns.main(["x", str(Path(self.tmp.name, "nope.json")), "--approvable-output"]), 1)
        self.assertEqual(out.read_text(), "approvable=false\n")

    def test_pr_url_mode(self):
        self.assertEqual(ns.main(["x", "--pr-url", "https://github.com/o/r/pull/12", "--dry-run"]), 0)
        for bad in ["https://evil.example/o/r/pull/1", "https://github.com/o/r/pull/1<!channel>", "", "https://github.com/o/r/issues/1"]:
            with self.subTest(bad=bad):
                self.assertEqual(ns.main(["x", "--pr-url", bad, "--dry-run"]), 1)

    def test_pr_failed_mode(self):
        self.assertEqual(ns.main(["x", "--pr-failed", "--dry-run"]), 0)


if __name__ == "__main__":
    unittest.main()
