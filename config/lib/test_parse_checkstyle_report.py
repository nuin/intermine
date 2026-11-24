import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


PARSER = Path(__file__).with_name("parse_checkstyle_report.py")
BUILD_ROOTS = (
    "intermine",
    "bio",
    "bio/sources",
    "bio/postprocess",
    "bio/postprocess-test",
)


class CheckstyleReportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.workspace = Path(self.temp.name)
        for root in BUILD_ROOTS:
            self.manifest(root, [])

    def manifest(self, root, reports):
        path = self.workspace / root / "build/checkstyle-reports.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"reports": reports}))

    def report(self, root, sources=("/source/Gene.java",), content=None):
        relative = "module/build/reports/checkstyle/main.xml"
        path = self.workspace / root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        if content is None:
            content = '<checkstyle><file name="/source/Gene.java"/></checkstyle>'
        path.write_text(content)
        self.manifest(root, [{"path": relative, "sources": list(sources)}])
        return path

    def run_parser(self):
        return subprocess.run(
            [sys.executable, str(PARSER), str(self.workspace)],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_fixture_cannot_replace_production_reports(self):
        self.report("config/checkstyle/verification")
        self.assertNotEqual(self.run_parser().returncode, 0)

    def test_missing_production_report_fails_despite_other_reports(self):
        self.report("intermine")
        self.report("bio").unlink()
        self.assertNotEqual(self.run_parser().returncode, 0)

    def test_missing_build_manifest_fails(self):
        self.report("intermine")
        (self.workspace / "bio/build/checkstyle-reports.json").unlink()
        self.assertNotEqual(self.run_parser().returncode, 0)

    def test_report_must_cover_every_expected_source(self):
        self.report("intermine", sources=("/source/Gene.java", "/source/Protein.java"))
        self.assertNotEqual(self.run_parser().returncode, 0)

    def test_genuine_no_source_projects_are_allowed(self):
        self.report("intermine")
        result = self.run_parser()
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)

    def test_malformed_report_fails(self):
        self.report("intermine", content="<checkstyle>")
        self.assertNotEqual(self.run_parser().returncode, 0)

    def test_reported_violation_fails(self):
        self.report(
            "intermine",
            content=(
                '<checkstyle><file name="/source/Gene.java">'
                '<error severity="warning" line="1" column="1" message="Bad style"/>'
                "</file></checkstyle>"
            ),
        )
        self.assertNotEqual(self.run_parser().returncode, 0)


if __name__ == "__main__":
    unittest.main()
