#!/usr/bin/env python3
"""Unit coverage for the Increment 133 repository contract checker."""

from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/check_increment133.py"
SPEC = importlib.util.spec_from_file_location("check_increment133", CHECKER)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class Increment133ContractTests(unittest.TestCase):
    def test_repository_contract(self) -> None:
        problems = MODULE.validate_files(ROOT)
        self.assertEqual([], problems, "\n".join(str(problem) for problem in problems))

    def test_premature_validated_status_requires_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            clone = Path(temporary) / "repo"
            shutil.copytree(ROOT, clone, ignore=shutil.ignore_patterns("out", ".git"))
            manifest_path = clone / MODULE.MANIFEST
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["status"] = "validated-analog-semantic-api"
            validation = manifest.setdefault("validation", {})
            validation["post_merge_core_ci_run"] = None
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
            problems = MODULE.validate_files(clone)
            self.assertIn("NODAL-INC133-037", [problem.code for problem in problems])

    def test_temporary_workflow_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            clone = Path(temporary) / "repo"
            shutil.copytree(ROOT, clone, ignore=shutil.ignore_patterns("out", ".git"))
            temporary_workflow = clone / ".github/workflows/_increment133_payload.yml"
            temporary_workflow.parent.mkdir(parents=True, exist_ok=True)
            temporary_workflow.write_text("permissions:\n  contents: write\n", encoding="utf-8")
            problems = MODULE.validate_files(clone)
            self.assertIn("NODAL-INC133-033", [problem.code for problem in problems])

    def test_validated_increment32_requires_complete_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            clone = Path(temporary) / "repo"
            shutil.copytree(ROOT, clone, ignore=shutil.ignore_patterns("out", ".git"))
            manifest_path = clone / MODULE.INCREMENT32
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["validation"]["closure_core_ci_run"] = None
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
            )
            problems = MODULE.validate_files(clone)
            self.assertIn("NODAL-INC133-034", [problem.code for problem in problems])


if __name__ == "__main__":
    unittest.main()
