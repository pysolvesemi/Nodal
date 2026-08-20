from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = (
    REPOSITORY_ROOT
    / "scripts"
    / "check_ci_baseline.py"
)
SPEC = importlib.util.spec_from_file_location(
    "check_ci_baseline",
    SCRIPT,
)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class CiBaselineCheckTests(unittest.TestCase):
    def problem_codes(
        self,
        root: Path,
    ) -> set[str]:
        return {
            problem.code
            for problem
            in CHECKER.check_repository(root)
        }

    def temporary_repository(
        self,
    ) -> tuple[
        tempfile.TemporaryDirectory[str],
        Path,
    ]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in CHECKER.EXPECTED_FILES:
            source = REPOSITORY_ROOT / relative
            destination = root / relative
            destination.parent.mkdir(
                parents=True,
                exist_ok=True,
            )
            shutil.copy2(source, destination)
        return temporary, root

    def test_current_repository_passes(
        self,
    ) -> None:
        self.assertEqual(
            CHECKER.check_repository(
                REPOSITORY_ROOT
            ),
            [],
        )

    def test_rejects_generated_output_cache(
        self,
    ) -> None:
        temporary, root = (
            self.temporary_repository()
        )
        self.addCleanup(temporary.cleanup)
        path = (
            root
            / ".github"
            / "workflows"
            / "ci.yml"
        )
        path.write_text(
            path.read_text(
                encoding="utf-8"
            ).replace(
                "~/.cache/coursier",
                "out",
            ),
            encoding="utf-8",
        )
        codes = self.problem_codes(root)
        self.assertIn(
            "NODAL-CI-010",
            codes,
        )
        self.assertIn(
            "NODAL-CI-011",
            codes,
        )

    def test_rejects_dependency_content_write_permission(
        self,
    ) -> None:
        temporary, root = (
            self.temporary_repository()
        )
        self.addCleanup(temporary.cleanup)
        path = (
            root
            / ".github"
            / "workflows"
            / "dependency-report.yml"
        )
        path.write_text(
            path.read_text(
                encoding="utf-8"
            ).replace(
                "contents: read",
                "contents: write",
            ),
            encoding="utf-8",
        )
        self.assertIn(
            "NODAL-CI-014",
            self.problem_codes(root),
        )

    def test_rejects_dependency_workflow_git_push(
        self,
    ) -> None:
        temporary, root = (
            self.temporary_repository()
        )
        self.addCleanup(temporary.cleanup)
        path = (
            root
            / ".github"
            / "workflows"
            / "dependency-report.yml"
        )
        path.write_text(
            (
                path.read_text(
                    encoding="utf-8"
                )
                + "\n# git push origin dev\n"
            ),
            encoding="utf-8",
        )
        self.assertIn(
            "NODAL-CI-014",
            self.problem_codes(root),
        )

    def test_rejects_direct_push_to_dev(
        self,
    ) -> None:
        temporary, root = (
            self.temporary_repository()
        )
        self.addCleanup(temporary.cleanup)
        path = (
            root
            / ".github"
            / "branch-policy.json"
        )
        path.write_text(
            path.read_text(
                encoding="utf-8"
            ).replace(
                '"direct_push": false',
                '"direct_push": true',
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn(
            "NODAL-CI-017",
            self.problem_codes(root),
        )

    def test_rejects_main_source_other_than_dev(
        self,
    ) -> None:
        temporary, root = (
            self.temporary_repository()
        )
        self.addCleanup(temporary.cleanup)
        path = (
            root
            / ".github"
            / "branch-policy.json"
        )
        path.write_text(
            path.read_text(
                encoding="utf-8"
            ).replace(
                '"allowed_source_branch": "dev"',
                (
                    '"allowed_source_branch": '
                    '"increment/**"'
                ),
            ),
            encoding="utf-8",
        )
        self.assertIn(
            "NODAL-CI-017",
            self.problem_codes(root),
        )

    def test_rejects_missing_required_aggregate_job(
        self,
    ) -> None:
        temporary, root = (
            self.temporary_repository()
        )
        self.addCleanup(temporary.cleanup)
        path = (
            root
            / ".github"
            / "workflows"
            / "ci.yml"
        )
        path.write_text(
            path.read_text(
                encoding="utf-8"
            ).replace(
                "name: required",
                "name: optional",
            ),
            encoding="utf-8",
        )
        self.assertIn(
            "NODAL-CI-008",
            self.problem_codes(root),
        )


if __name__ == "__main__":
    unittest.main()
