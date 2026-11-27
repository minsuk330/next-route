import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pr_body  # noqa: E402


class PrBodyTest(unittest.TestCase):
    def test_title_and_body(self):
        s = {"status": "passed", "path": "forged/Other.java", "selected": 12, "passed": 40, "failed": 0, "failures": []}
        title, body = pr_body.build(s, "SubwayRing 5xx · Bx", "https://github.com/o/r/actions/runs/1", "src/main/java/a/B.java")
        self.assertEqual(title, "fix: SubwayRing 5xx · Bx (AI 제안)")
        self.assertNotIn("forged", body)  # the path comes from create-pr, not the untrusted summary
        self.assertIn("AI 가 만든 수정 제안", body)
        self.assertIn("`src/main/java/a/B.java`", body)
        self.assertIn("통과 40 · 실패 0", body)
        self.assertIn("테스트 클래스 12개", body)
        self.assertIn("https://github.com/o/r/actions/runs/1", body)

    def test_untrusted_values_are_defused(self):
        s = {"passed": "999", "failures": [{"baseline": True}] * 3, "failed": 3}
        title, body = pr_body.build(s, "@here " + "L" * 300, "u", "x``` @everyone <script>")
        self.assertLessEqual(len(title), pr_body.TITLE_CHARS)
        self.assertNotIn("@here", title)
        self.assertNotIn("@everyone", body)
        self.assertNotIn("```", body)
        self.assertNotIn("<script>", body)
        self.assertIn("통과 0", body)  # non-int count falls back to 0
        self.assertIn("기존 실패 3", body)

    def test_issue_links_images_and_secrets_are_defused(self):
        _, body = pr_body.build({}, "closes #12 other/repo#3 ![x](http://t.example/p.png) [link](http://e) xoxb-1234-5678-AbCd", "u", "p")
        self.assertNotIn("#12", body)
        self.assertNotIn("repo#3", body)
        self.assertNotIn("![", body)
        self.assertNotIn("](", body)
        self.assertNotIn("xoxb-1234", body)

    def test_main_with_missing_summary(self):
        with tempfile.TemporaryDirectory() as d:
            os.environ["LABEL"] = "A"
            try:
                self.assertEqual(pr_body.main(["x", str(Path(d, "nope.json")), str(Path(d, "t")), str(Path(d, "b"))]), 0)
            finally:
                del os.environ["LABEL"]
            self.assertEqual(Path(d, "t").read_text(), "fix: A (AI 제안)")


if __name__ == "__main__":
    unittest.main()
