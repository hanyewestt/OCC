"""Exercise the report gate with synthetic Maven reports."""

from pathlib import Path
import tempfile
import unittest

from check_test_reports import check_reports


class ReportCheckTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.service = Path(self.directory.name)
        self.source("ExampleTest")
        self.report("ExampleTest", "surefire-reports")

    def source(self, name):
        path = self.service / "src/test/java/example" / f"{name}.java"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch()

    def report(self, name, folder, **attributes):
        path = self.service / "target" / folder / f"TEST-example.{name}.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        values = dict(name=f"example.{name}", tests=1, failures=0, errors=0, skipped=0)
        values.update(attributes)
        text = " ".join(f'{key}="{value}"' for key, value in values.items())
        path.write_text(f"<testsuite {text}/>", encoding="utf-8")

    def test_reports_coverage_gap_without_claiming_integration_execution(self):
        summary, errors = check_reports(self.service)
        self.assertFalse(errors)
        self.assertIn("Coverage gap", summary)

    def test_accepts_executed_integration_tests(self):
        self.source("DatabaseIT")
        self.report("DatabaseIT", "failsafe-reports")
        self.assertFalse(check_reports(self.service)[1])

    def test_rejects_skipped_integration_tests(self):
        self.source("DatabaseIT")
        self.report("DatabaseIT", "failsafe-reports", skipped=1)
        self.assertTrue(check_reports(self.service)[1])

    def test_rejects_missing_integration_report(self):
        self.source("DatabaseIT")
        self.assertTrue(check_reports(self.service)[1])

    def test_rejects_test_failures(self):
        self.report("ExampleTest", "surefire-reports", failures=1)
        self.assertTrue(check_reports(self.service)[1])

    def test_rejects_empty_test_execution(self):
        self.report("ExampleTest", "surefire-reports", tests=0)
        self.assertTrue(check_reports(self.service)[1])

    def test_rejects_malformed_report(self):
        path = self.service / "target/surefire-reports/TEST-example.ExampleTest.xml"
        path.write_text("broken", encoding="utf-8")
        self.assertTrue(check_reports(self.service)[1])
