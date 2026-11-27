import base64
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import apply_edits as ae  # noqa: E402


def enc(obj) -> str:
    return base64.b64encode(json.dumps(obj).encode()).decode()


class DecodeTest(unittest.TestCase):
    def test_valid(self):
        path, edits = ae.decode(enc({"path": "src/main/java/a/B.java", "edits": [{"search": "x\r\n", "replace": "y"}]}))
        self.assertEqual(path, "src/main/java/a/B.java")
        self.assertEqual(edits, [("x\n", "y")])

    def test_rejects_paths_outside_main_java(self):
        for p in ["src/main/java/../../.github/workflows/x.java", ".github/workflows/fix-verify.yml",
                  "ops/ci/run_tests.py", "/etc/passwd", "src/main/java/a/B.kt", "src/main/java/B.java "]:
            with self.subTest(p=p), self.assertRaises(ae.BadInput):
                ae.decode(enc({"path": p, "edits": [{"search": "a", "replace": "b"}]}))

    def test_rejects_bad_shapes(self):
        for raw in ["!!!", base64.b64encode(b"[1]").decode(),
                    enc({"path": "src/main/java/a/B.java", "edits": []}),
                    enc({"path": "src/main/java/a/B.java", "edits": [{"search": "a"}] * 4}),
                    enc({"path": "src/main/java/a/B.java", "edits": [{"search": "  \n", "replace": "x"}]})]:
            with self.subTest(raw=raw[:20]), self.assertRaises(ae.BadInput):
                ae.decode(raw)


class ApplyTest(unittest.TestCase):
    def test_exactly_once_and_no_overlap(self):
        self.assertEqual(ae.apply_edits("a b c", [("b", "B")]), "a B c")
        self.assertIsNone(ae.apply_edits("b b", [("b", "B")]))
        self.assertIsNone(ae.apply_edits("abc", [("ab", "x"), ("bc", "y")]))
        self.assertIsNone(ae.apply_edits("abc", []))

    def test_later_search_checked_against_original(self):
        self.assertIsNone(ae.apply_edits("a", [("a", "b"), ("b", "c")]))

    def test_main_preserves_crlf_and_reports_codes(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d, "src/main/java/a/B.java")
            f.parent.mkdir(parents=True)
            f.write_bytes(b"class B {\r\n  int x = 1;\r\n}\r\n")
            os.environ["EDITS"] = enc({"path": "src/main/java/a/B.java", "edits": [{"search": "  int x = 1;\n", "replace": "  int x = 2;\n"}]})
            self.assertEqual(ae.main(["x", d]), 0)
            self.assertEqual(f.read_bytes(), b"class B {\r\n  int x = 2;\r\n}\r\n")
            self.assertEqual(ae.main(["x", d]), 2)  # SEARCH no longer present
            os.environ["EDITS"] = enc({"path": "src/main/java/a/Missing.java", "edits": [{"search": "a", "replace": "b"}]})
            self.assertEqual(ae.main(["x", d]), 2)
            os.environ["EDITS"] = "not base64!"
            self.assertEqual(ae.main(["x", d]), 3)


if __name__ == "__main__":
    unittest.main()
