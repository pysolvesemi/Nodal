from __future__ import annotations

import datetime as dt
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "dependency_report.py"
SPEC = importlib.util.spec_from_file_location("dependency_report", SCRIPT)
assert SPEC and SPEC.loader
REPORT = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = REPORT
SPEC.loader.exec_module(REPORT)


def fixture_lock() -> dict:
    return {
        "scala": {
            "version": "3.8.4",
            "mill_version": "1.1.7",
            "jvm": "zulu:25",
            "utest_version": "0.9.1",
        },
        "native": {
            "circt": {"release_tag": "firtool-1.154.0"},
            "requirements": {
                "cmake_minimum": "3.20.0",
                "ninja_minimum": "1.10.0",
            },
        },
    }


class DependencyReportTests(unittest.TestCase):
    def fake_fetcher(self, url: str) -> str:
        if url == REPORT.SCALA_METADATA:
            return """
<metadata><versioning><versions>
<version>3.8.4</version>
<version>3.9.0-RC1</version>
<version>3.9.0</version>
</versions></versioning></metadata>
"""
        if url == REPORT.MILL_METADATA:
            return """
<metadata><versioning><versions>
<version>1.1.7</version>
</versions></versioning></metadata>
"""
        if url == REPORT.UTEST_METADATA:
            return """
<metadata><versioning><versions>
<version>0.9.1</version>
</versions></versioning></metadata>
"""
        if url == REPORT.CIRCT_RELEASES:
            return json.dumps(
                [
                    {
                        "tag_name": "firtool-1.155.0",
                        "draft": False,
                        "prerelease": False,
                    },
                    {
                        "tag_name": "firtool-1.156.0-rc1",
                        "draft": False,
                        "prerelease": False,
                    },
                ]
            )
        raise AssertionError(f"unexpected URL: {url}")

    def test_stable_maven_selection_ignores_prerelease(self) -> None:
        self.assertEqual(
            REPORT.latest_maven_version(
                """
<metadata><versioning><versions>
<version>3.8.4</version>
<version>3.9.0-RC1</version>
<version>3.9.0</version>
</versions></versioning></metadata>
"""
            ),
            "3.9.0",
        )

    def test_online_report_identifies_candidates_without_applying_them(self) -> None:
        report = REPORT.build_report(
            fixture_lock(),
            online=True,
            fetcher=self.fake_fetcher,
            generated_at=dt.datetime(
                2026,
                8,
                20,
                tzinfo=dt.timezone.utc,
            ),
        )
        self.assertTrue(report["updates_available"])
        self.assertEqual(report["errors"], [])
        dependencies = {
            item["dependency"]: item for item in report["dependencies"]
        }
        self.assertEqual(
            dependencies["Scala 3"]["candidate"],
            "3.9.0",
        )
        self.assertEqual(
            dependencies["CIRCT"]["candidate"],
            "firtool-1.155.0",
        )
        self.assertEqual(
            dependencies["JDK"]["status"],
            "manual-review",
        )
        self.assertIn("never edits source files", report["policy"])

    def test_fetch_failure_is_reported_not_guessed(self) -> None:
        def failing_fetcher(_url: str) -> str:
            raise REPORT.DependencyReportError("fixture failure")

        report = REPORT.build_report(
            fixture_lock(),
            online=True,
            fetcher=failing_fetcher,
        )
        self.assertFalse(report["updates_available"])
        self.assertEqual(len(report["errors"]), 4)
        self.assertTrue(
            all(
                item["candidate"] is None
                for item in report["dependencies"]
                if item["status"] == "error"
            )
        )

    def test_offline_cli_writes_evidence_and_outputs(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "toolchains").mkdir()
        (root / "toolchains/lock.json").write_text(
            json.dumps(fixture_lock()),
            encoding="utf-8",
        )
        markdown = root / "report.md"
        json_path = root / "report.json"
        output = root / "github-output.txt"
        self.assertEqual(
            REPORT.main(
                [
                    "--offline",
                    "--root",
                    str(root),
                    "--markdown",
                    str(markdown),
                    "--json",
                    str(json_path),
                    "--github-output",
                    str(output),
                ]
            ),
            0,
        )
        self.assertIn("# Nodal dependency report", markdown.read_text(encoding="utf-8"))
        payload = json.loads(json_path.read_text(encoding="utf-8"))
        self.assertEqual(payload["mode"], "offline")
        self.assertIn(
            "updates_available=false",
            output.read_text(encoding="utf-8"),
        )


if __name__ == "__main__":
    unittest.main()
