#!/usr/bin/env python3
"""Mutation tests for the Increment 31 implementation contract."""

from __future__ import annotations

import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CHECKER_PATH = ROOT / "scripts" / "check_increment31.py"
SPEC = importlib.util.spec_from_file_location("check_increment31", CHECKER_PATH)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class Increment31ContractTest(unittest.TestCase):
    def temporary_repository(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name) / "Nodal"
        shutil.copytree(ROOT, root)
        return temporary, root

    def codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def test_current_repository_passes(self) -> None:
        self.assertEqual(CHECKER.check_repository(ROOT), [])

    def test_rejects_closed_roadmap_item_before_evidence(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "docs/roadmap/nodal-development-todo.md"
        text = path.read_text(encoding="utf-8").replace(
            "- [ ] **Increment 31 — Potential and flow access functions**",
            "- [x] **Increment 31 — Potential and flow access functions**",
            1,
        )
        path.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC31-006", self.codes(root))

    def test_rejects_scaffold_manifest_status(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment31/manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["status"] = "implementation-started"
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC31-007", self.codes(root))

    def test_rejects_missing_port_flow_form(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment31/access-surface.json"
        surface = json.loads(path.read_text(encoding="utf-8"))
        del surface["forms"]["portFlow"]
        path.write_text(json.dumps(surface, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC31-008", self.codes(root))

    def test_rejects_missing_named_branch_isolation(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment31/access-surface.json"
        surface = json.loads(path.read_text(encoding="utf-8"))
        del surface["normalization"]["namedBranchIsolation"]
        path.write_text(json.dumps(surface, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC31-008", self.codes(root))

    def test_rejects_writable_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-31-potential-flow-access.yml"
        text = path.read_text(encoding="utf-8").replace(
            "contents: read",
            "contents: write",
            1,
        )
        path.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC31-005", self.codes(root))

    def test_rejects_unvalidated_predecessor(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment30/manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["status"] = "implemented-awaiting-evidence"
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC31-009", self.codes(root))

    def test_rejects_missing_design_gate_contract(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "docs/design-gates/NodalPotentialFlowAccess-DG-v1.0.md"
        text = path.read_text(encoding="utf-8").replace(
            "backend hard-coding of `V` or `I` is prohibited",
            "backend selects conventional electrical access names",
            1,
        )
        path.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC31-003", self.codes(root))

    def test_rejects_missing_native_resolver(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/PotentialFlowAccess.cpp"
        text = path.read_text(encoding="utf-8").replace(
            "resolvePotentialFlowAccessNature",
            "resolveRemovedAccessNature",
        )
        path.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC31-010", self.codes(root))

    def test_rejects_backend_without_normalization(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
        text = path.read_text(encoding="utf-8").replace(
            "normalizePotentialFlowAccess(module)",
            "verifyPotentialFlowAccessModel(module)",
            1,
        )
        path.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC31-010", self.codes(root))

    def test_rejects_missing_diagnostic_catalog_entry(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/diagnostics-v0.1.json"
        catalog = json.loads(path.read_text(encoding="utf-8"))
        catalog["families"]["potential-flow-access"].remove(
            "NODAL-ACCESS-PORT-001"
        )
        path.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC31-012", self.codes(root))

    def test_rejects_python_bytecode_artifact(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "scripts/__pycache__/increment31.cpython-312.pyc"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"not-bytecode")
        subprocess.run(
            [
                "git",
                "-C",
                str(root),
                "add",
                "-f",
                path.relative_to(root).as_posix(),
            ],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self.assertIn("NODAL-INC31-002", self.codes(root))


if __name__ == "__main__":
    unittest.main()
