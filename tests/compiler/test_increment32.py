#!/usr/bin/env python3
"""Mutation coverage for Increment 32 repository contracts."""

from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/check_increment32.py"
SPEC = importlib.util.spec_from_file_location("check_increment32", CHECKER)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class Increment32ContractTests(unittest.TestCase):
    def test_repository_contract(self) -> None:
        problems = MODULE.validate_files(ROOT)
        self.assertEqual([], problems, "\n".join(str(problem) for problem in problems))

    def test_premature_roadmap_closure_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            clone = Path(temporary) / "repo"
            shutil.copytree(ROOT, clone, ignore=shutil.ignore_patterns("out", ".git"))
            roadmap = clone / MODULE.ROADMAP
            text = roadmap.read_text(encoding="utf-8")
            text = text.replace(
                "- [ ] **Increment 32 — First-class analog equations, blocks, and contribution semantics**",
                "- [x] **Increment 32 — First-class analog equations, blocks, and contribution semantics**",
                1,
            )
            roadmap.write_text(text, encoding="utf-8")
            codes = [problem.code for problem in MODULE.validate_files(clone)]
            self.assertIn("NODAL-INC32-026", codes)

    def test_validated_status_without_evidence_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            clone = Path(temporary) / "repo"
            shutil.copytree(ROOT, clone, ignore=shutil.ignore_patterns("out", ".git"))
            manifest_path = clone / MODULE.MANIFEST
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["status"] = "validated-equation-contribution-semantics"
            manifest["validation"] = {}
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
            roadmap = clone / MODULE.ROADMAP
            text = roadmap.read_text(encoding="utf-8").replace(
                "- [ ] **Increment 32 — First-class analog equations, blocks, and contribution semantics**",
                "- [x] **Increment 32 — First-class analog equations, blocks, and contribution semantics**",
                1,
            )
            roadmap.write_text(text, encoding="utf-8")
            codes = [problem.code for problem in MODULE.validate_files(clone)]
            self.assertIn("NODAL-INC32-029", codes)

    def test_last_writer_wins_mutation_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            clone = Path(temporary) / "repo"
            shutil.copytree(ROOT, clone, ignore=shutil.ignore_patterns("out", ".git"))
            manifest_path = clone / MODULE.MANIFEST
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["semantics"]["last_writer_wins"] = True
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
            codes = [problem.code for problem in MODULE.validate_files(clone)]
            self.assertIn("NODAL-INC32-016", codes)

    def test_temporary_workflow_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            clone = Path(temporary) / "repo"
            shutil.copytree(ROOT, clone, ignore=shutil.ignore_patterns("out", ".git"))
            workflow = clone / ".github/workflows/_increment32_payload.yml"
            workflow.parent.mkdir(parents=True, exist_ok=True)
            workflow.write_text("permissions:\n  contents: write\n", encoding="utf-8")
            codes = [problem.code for problem in MODULE.validate_files(clone)]
            self.assertIn("NODAL-INC32-032", codes)

    def test_increment33_cannot_close_early(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            clone = Path(temporary) / "repo"
            shutil.copytree(ROOT, clone, ignore=shutil.ignore_patterns("out", ".git"))
            roadmap = clone / MODULE.ROADMAP
            text = roadmap.read_text(encoding="utf-8").replace(
                "- [ ] **Increment 33 — Analog variables and procedural assignment**",
                "- [x] **Increment 33 — Analog variables and procedural assignment**",
                1,
            )
            roadmap.write_text(text, encoding="utf-8")
            codes = [problem.code for problem in MODULE.validate_files(clone)]
            self.assertIn("NODAL-INC32-028", codes)


if __name__ == "__main__":
    unittest.main()
