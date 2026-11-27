import base64
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_tests as rt  # noqa: E402

XML = """<?xml version="1.0"?>
<testsuite name="a.BTest" tests="3" skipped="1" failures="1" errors="0">
  <testcase name="ok" classname="a.BTest"/>
  <testcase name="skip" classname="a.BTest"><skipped/></testcase>
  <testcase name="bad" classname="a.BTest$Inner"><failure message="expected: &lt;1&gt; but was: &lt;2&gt;">trace</failure></testcase>
</testsuite>"""

DRIFT = """<?xml version="1.0"?>
<testsuite name="a.Ctx" tests="1" failures="0" errors="1">
  <testcase name="contextLoads" classname="a.Ctx"><error message="Failed to load ApplicationContext">org.flywaydb.core.api.exception.FlywayValidateException: Validate failed: Migrations have failed validation</error></testcase>
</testsuite>"""


class ResultsTest(unittest.TestCase):
    def test_counts_and_failures(self):
        with tempfile.TemporaryDirectory() as d:
            r = Path(d, "test-results/test")
            r.mkdir(parents=True)
            (r / "TEST-a.BTest.xml").write_text(XML)
            counts, failures = rt.results(Path(d))
        self.assertEqual(counts, {"tests": 3, "failed": 1, "skipped": 1})
        self.assertEqual(failures[0]["test"], "a.BTest$Inner#bad")
        self.assertEqual(failures[0]["message"], "expected: <1> but was: <2>")
        self.assertFalse(failures[0]["drift"])

    def test_flyway_drift_detected(self):
        with tempfile.TemporaryDirectory() as d:
            r = Path(d, "test-results/test")
            r.mkdir(parents=True)
            (r / "TEST-a.Ctx.xml").write_text(DRIFT)
            _, failures = rt.results(Path(d))
        self.assertTrue(failures[0]["drift"])

    def test_compile_errors_and_args(self):
        log = "> Task :compileJava\n/w/target/src/B.java:3: error: ';' expected\n  int x = 1\n  /w/target/src/B.java:3: error: ';' expected\n1 error"
        self.assertEqual(rt.compile_errors(log, Path("/w/target")), ["src/B.java:3: error: ';' expected"])
        self.assertEqual(rt.test_args(["ALL"]), ["test"])
        self.assertEqual(rt.test_args(["a.B", "a.C"]), ["test", "--tests", "a.B", "--tests", "a.C"])


class RunDecisionTest(unittest.TestCase):
    """run() status table with gradle/results/baseline stubbed."""

    def setUp(self):
        self.saved = (rt.gradle, rt.results, rt.baseline)
        self.tmp = tempfile.TemporaryDirectory()
        self.out = Path(self.tmp.name, "out")
        self.out.mkdir()
        (self.out / "tests.txt").write_text("a.BTest\n")
        (self.out / "path.txt").write_text("src/main/java/a/B.java")

    def tearDown(self):
        rt.gradle, rt.results, rt.baseline = self.saved
        self.tmp.cleanup()

    def go(self, compile_=(0, ""), test=(0, ""), counts=(1, 0, 0), failures=(), base=None):
        calls = iter([compile_, test])
        rt.gradle = lambda cwd, *tasks, **kw: next(calls)
        rt.results = lambda build: ({"tests": counts[0], "failed": counts[1], "skipped": counts[2]},
                                    [dict(f) for f in failures])
        rt.baseline = lambda target, fs: base
        rt.run(Path(self.tmp.name, "target"), self.out)
        return json.loads((self.out / "summary.json").read_text())

    def f(self, test, drift=False):
        return {"test": test, "message": "m", "drift": drift}

    def test_table(self):
        cases = [
            ("passed", dict(), ("passed", "")),
            ("compile error lines", dict(compile_=(1, "/t/B.java:1: error: x")), ("compile_failed", "")),
            ("compile infra failure", dict(compile_=(1, "Could not resolve dependency")), ("error", "gradle_failed")),
            ("compile timeout", dict(compile_=(None, "")), ("error", "timeout")),
            ("test timeout", dict(test=(None, "")), ("error", "timeout")),
            ("zero tests ran", dict(counts=(0, 0, 0)), ("error", "no_tests_ran")),
            ("zero tests, gradle failed", dict(test=(1, ""), counts=(0, 0, 0)), ("error", "gradle_failed")),
            ("non-zero exit, no failures", dict(test=(1, ""), counts=(3, 0, 0)), ("error", "gradle_failed")),
            ("drift wins", dict(test=(1, ""), counts=(1, 1, 0), failures=[self.f("a.Ctx#c", drift=True)]), ("error", "schema_drift")),
            ("failed, no baseline", dict(test=(1, ""), counts=(2, 1, 0), failures=[self.f("a.B#x")]), ("failed", "")),
        ]
        for name, kw, want in cases:
            with self.subTest(name):
                s = self.go(**kw)
                self.assertEqual((s["status"], s["reason"]), want)

    def test_baseline_marks_and_orders_new_first(self):
        s = self.go(test=(1, ""), counts=(3, 2, 0), failures=[self.f("a.Old#x"), self.f("a.New#y")], base={"a.Old#x"})
        self.assertTrue(s["baseline_checked"])
        self.assertEqual([(f["test"], f["baseline"]) for f in s["failures"]], [("a.New#y", False), ("a.Old#x", True)])
        self.assertNotIn("drift", s["failures"][0])

    def test_baseline_unavailable_keeps_unchecked(self):
        s = self.go(test=(1, ""), counts=(2, 1, 0), failures=[self.f("a.B#x")], base=None)
        self.assertFalse(s["baseline_checked"])
        self.assertFalse(s["failures"][0]["baseline"])


class BaselineTest(unittest.TestCase):
    def setUp(self):
        self.saved = (rt.git, rt.gradle, rt.results)

    def tearDown(self):
        rt.git, rt.gradle, rt.results = self.saved

    def test_none_when_worktree_or_run_fails(self):
        def boom(*a):
            raise subprocess.CalledProcessError(1, "git")
        rt.git = boom
        self.assertIsNone(rt.baseline(Path("/x/target"), [{"test": "a.B#x"}]))
        rt.git = lambda *a: ""
        rt.gradle = lambda cwd, *t, **kw: (None, "")
        rt.results = lambda b: ({"tests": 0, "failed": 0, "skipped": 0}, [])
        self.assertIsNone(rt.baseline(Path("/x/target"), [{"test": "a.B$Inner#x"}]))

    def test_reruns_outer_classes(self):
        seen = {}
        rt.git = lambda *a: ""
        rt.gradle = lambda cwd, *t, **kw: (seen.setdefault("args", t), (1, ""))[1]
        rt.results = lambda b: ({"tests": 2, "failed": 1, "skipped": 0}, [{"test": "a.B$Inner#x"}])
        self.assertEqual(rt.baseline(Path("/x/target"), [{"test": "a.B$Inner#x"}, {"test": "a.B#y"}]), {"a.B$Inner#x"})
        self.assertEqual(seen["args"], ("test", "--tests", "a.B"))


class PrepareTest(unittest.TestCase):
    def setUp(self):
        self.env = dict(os.environ)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.env)

    def repo(self, d: Path) -> Path:
        t = d / "target"
        f = t / "src/main/java/a/B.java"
        f.parent.mkdir(parents=True)
        f.write_text("package a;\npublic class B {\n  int x = 1;\n}\n")
        tf = t / "src/test/java/a/BTest.java"
        tf.parent.mkdir(parents=True)
        tf.write_text("package a;\nclass BTest { B b; @Test void t() {} }\n")
        for cmd in (["init", "-q"], ["add", "."], ["-c", "user.email=ci@x", "-c", "user.name=ci", "commit", "-qm", "init"]):
            subprocess.run(["git", "-C", str(t), *cmd], check=True)
        return t

    def edits(self, search, replace):
        return base64.b64encode(json.dumps({"path": "src/main/java/a/B.java",
                                            "edits": [{"search": search, "replace": replace}]}).encode()).decode()

    def test_applies_writes_patch_and_tests(self):
        with tempfile.TemporaryDirectory() as d:
            t = self.repo(Path(d))
            os.environ["EDITS"] = self.edits("  int x = 1;\n", "  int x = 2;\n")
            out = Path(d, "out")
            self.assertEqual(rt.prepare(t, out), 0)
            self.assertIn("+  int x = 2;", (out / "fix.patch").read_text())
            self.assertEqual((out / "tests.txt").read_text().split(), ["a.BTest"])
            self.assertFalse((out / "skip").exists())

    def test_patch_keeps_crlf_bytes(self):
        """create-pr compares `git diff >` raw bytes with fix.patch; text mode would fold CR and never match."""
        with tempfile.TemporaryDirectory() as d:
            t = self.repo(Path(d))
            f = t / "src/main/java/a/B.java"
            f.write_bytes(b"package a;\r\npublic class B {\r\n  int x = 1;\r\n}\r\n")
            subprocess.run(["git", "-C", str(t), "-c", "user.email=ci@x", "-c", "user.name=ci", "commit", "-qam", "crlf"], check=True)
            os.environ["EDITS"] = self.edits("  int x = 1;\n", "  int x = 2;\n")
            out = Path(d, "out")
            rt.prepare(t, out)
            raw = subprocess.run(["git", "-C", str(t), "diff"], capture_output=True, check=True).stdout
            self.assertIn(b"\r", raw)
            self.assertEqual((out / "fix.patch").read_bytes(), raw)

    def test_mismatch_writes_apply_failed_summary(self):
        with tempfile.TemporaryDirectory() as d:
            t = self.repo(Path(d))
            os.environ["EDITS"] = self.edits("nope\n", "x\n")
            out = Path(d, "out")
            self.assertEqual(rt.prepare(t, out), 0)
            self.assertTrue((out / "skip").exists())
            self.assertEqual(json.loads((out / "summary.json").read_text())["status"], "apply_failed")

    def test_exception_still_leaves_summary(self):
        saved = rt.select_tests.select

        def boom(*a):
            raise ValueError("x")
        rt.select_tests.select = boom
        try:
            with tempfile.TemporaryDirectory() as d:
                t = self.repo(Path(d))
                out = Path(d, "out")
                os.environ["EDITS"] = self.edits("  int x = 1;\n", "  int x = 2;\n")
                self.assertEqual(rt.main(["x", "prepare", str(t), str(out)]), 0)
                s = json.loads((out / "summary.json").read_text())
                self.assertEqual((s["status"], s["reason"]), ("error", "exception:ValueError"))
                self.assertTrue((out / "skip").exists())
        finally:
            rt.select_tests.select = saved

    def test_bad_input_is_error(self):
        with tempfile.TemporaryDirectory() as d:
            t = self.repo(Path(d))
            os.environ["EDITS"] = "zzz"
            out = Path(d, "out")
            rt.prepare(t, out)
            s = json.loads((out / "summary.json").read_text())
            self.assertEqual((s["status"], s["reason"]), ("error", "bad_input"))


if __name__ == "__main__":
    unittest.main()
