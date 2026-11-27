import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import select_tests as st  # noqa: E402

TREE = {
    # main
    "src/main/java/app/port/GetX.java": "package app.port; public interface GetX { String x(); }",
    "src/main/java/app/service/XService.java": """
        package app.service;
        import app.port.GetX;
        import app.util.Strs;
        @Service public class XService implements GetX { public String x() { return Strs.trim(" x "); } }""",
    "src/main/java/app/web/XController.java": """
        package app.web;
        import app.port.GetX;
        public class XController { GetX getX; // XService mentioned only in a comment
          String s = "XService"; }""",
    "src/main/java/app/util/Strs.java": "package app.util; public final class Strs { public static String trim(String s) { return s.trim(); } }",
    "src/main/java/app/job/CleanupJob.java": "package app.job; public class CleanupJob { @Scheduled void run() {} }",
    "src/main/java/other/service/XService.java": "package other.service; public class XService {}",
    "src/main/java/app/rec/Point.java": "package app.rec; import app.port.GetX; public record Point(int a, int b) implements GetX { public String x() { return \"\"; } }",
    "src/main/java/watoo/grd/nextroute/NextrouteApplication.java": "package watoo.grd.nextroute; public class NextrouteApplication {}",
    # test
    "src/test/java/watoo/grd/nextroute/NextrouteApplicationTests.java": "package watoo.grd.nextroute; class NextrouteApplicationTests { @Test void contextLoads() {} }",
    "src/test/java/app/web/XControllerTest.java": "package app.web; class XControllerTest { XController c; @Test void t() {} }",
    "src/test/java/app/support/AbstractXIT.java": "package app.support; import app.service.XService; public abstract class AbstractXIT { protected XService s; }",
    "src/test/java/app/it/ConcreteXIT.java": "package app.it; import app.support.AbstractXIT; class ConcreteXIT extends AbstractXIT { @Test void t() {} }",
    "src/test/java/app/support/StrsTestsBase.java": "package app.support; import app.util.Strs; public abstract class StrsTestsBase { @Test void trims() { Strs.trim(\"a\"); } }",
    "src/test/java/app/it/InheritsTestsIT.java": "package app.it; import app.support.StrsTestsBase; class InheritsTestsIT extends StrsTestsBase {}",
    "src/test/java/app/fake/FakeGetX.java": "package app.fake; import app.port.GetX; public class FakeGetX implements GetX { public String x() { return \"\"; } }",
    "src/test/java/app/fake/UsesFakeTest.java": "package app.fake; class UsesFakeTest { FakeGetX f; @Test void t() {} }",
    "src/test/java/other/service/OtherXServiceTest.java": "package other.service; class OtherXServiceTest { XService s; @Test void t() {} }",
    "src/test/java/app/job/CleanupNeighbourTest.java": "package app.job; class CleanupNeighbourTest { @Test void t() {} }",
    "src/test/java/app/job/sub/DeepJobTest.java": "package app.job.sub; class DeepJobTest { @Test void t() {} }",
    "src/test/java/app/rec/PointTest.java": "package app.rec; class PointTest { Point p; @Test void t() {} }",
}


class SelectTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.tmp.name)
        for rel, code in TREE.items():
            p = cls.root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(textwrap.dedent(code), encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def pick(self, *paths):
        return st.select(self.root, list(paths))

    def test_service_reaches_callers_through_its_interface(self):
        got = self.pick("src/main/java/app/service/XService.java")
        self.assertIn("app.web.XControllerTest", got)  # controller holds GetX, not XService
        self.assertIn("app.fake.UsesFakeTest", got)    # fake implements GetX: test-side chain

    def test_subclass_of_abstract_base_is_selected_and_base_is_not(self):
        got = self.pick("src/main/java/app/service/XService.java")
        self.assertIn("app.it.ConcreteXIT", got)
        self.assertNotIn("app.support.AbstractXIT", got)

    def test_same_simple_name_in_other_package_is_not_selected(self):
        self.assertNotIn("other.service.OtherXServiceTest", self.pick("src/main/java/app/service/XService.java"))
        self.assertIn("other.service.OtherXServiceTest", self.pick("src/main/java/other/service/XService.java"))

    def test_names_in_comments_and_strings_do_not_count(self):
        idx = st.Index(self.root)
        self.assertNotIn("app.service.XService", idx.files["app.web.XController"].deps)

    def test_tests_inherited_from_abstract_base_make_subclass_runnable(self):
        got = self.pick("src/main/java/app/util/Strs.java")
        self.assertIn("app.it.InheritsTestsIT", got)
        self.assertNotIn("app.support.StrsTestsBase", got)
        self.assertIn("app.web.XControllerTest", got)  # Strs -> XService -> GetX -> XController

    def test_unreferenced_class_adds_its_package_tests(self):
        got = self.pick("src/main/java/app/job/CleanupJob.java")
        self.assertEqual(got, ["app.job.CleanupNeighbourTest", "app.job.sub.DeepJobTest",
                               "watoo.grd.nextroute.NextrouteApplicationTests"])

    def test_record_implements_is_a_supertype(self):
        idx = st.Index(self.root)
        self.assertEqual(idx.files["app.rec.Point"].supers, {"app.port.GetX"})

    def test_context_test_always_included(self):
        self.assertIn("watoo.grd.nextroute.NextrouteApplicationTests", self.pick("src/main/java/app/rec/Point.java"))

    def test_non_java_or_unknown_change_runs_everything(self):
        self.assertIsNone(self.pick("src/main/resources/application.yaml"))
        self.assertIsNone(self.pick("src/main/java/app/Nope.java"))
        self.assertIsNone(self.pick())


if __name__ == "__main__":
    unittest.main()
