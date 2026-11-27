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

    def test_mismatch_writes_apply_failed_summary(self):
        with tempfile.TemporaryDirectory() as d:
            t = self.repo(Path(d))
            os.environ["EDITS"] = self.edits("nope\n", "x\n")
            out = Path(d, "out")
            self.assertEqual(rt.prepare(t, out), 0)
            self.assertTrue((out / "skip").exists())
            self.assertEqual(json.loads((out / "summary.json").read_text())["status"], "apply_failed")

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
