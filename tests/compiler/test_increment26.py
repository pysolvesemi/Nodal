from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment26.py"
SPEC = importlib.util.spec_from_file_location("check_increment26", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT_FILES = (
    "docs/roadmap/nodal-development-todo.md",
)


class Increment26CheckerTests(unittest.TestCase):
    def temporary_repository(
        self,
    ) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in dict.fromkeys(CHECKER.EXPECTED_FILES + SUPPORT_FILES):
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def test_repository_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(ROOT), [])

    def test_rejects_missing_contract(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        (
            root
            / "core/scala/bridge/src/nodal/bridge/ReproducibilityContract.scala"
        ).unlink()
        self.assertIn("NODAL-INC26-001", self.codes(root))

    def test_rejects_missing_construction_failure_channel(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = (
            root
            / "core/scala/bridge/src/nodal/bridge/ReproducibilityContract.scala"
        )
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "case exception: ConstructionException",
                "case exception: IllegalArgumentException",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC26-003", self.codes(root))

    def test_rejects_time_dependent_manifest(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = (
            root
            / "core/scala/bridge/src/nodal/bridge/ReproducibilityContract.scala"
        )
        path.write_text(
            path.read_text(encoding="utf-8") + "\n// Instant.now\n",
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC26-003", self.codes(root))

    def test_rejects_semantic_operand_sorting(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = (
            root
            / "core/scala/bridge/src/nodal/bridge/ReproducibilityContract.scala"
        )
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "expression.operands.map(string)",
                "expression.operands.sorted.map(string)",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC26-003", self.codes(root))

    def test_rejects_writable_permanent_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-26-reproducibility-contract.yml"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "contents: read",
                "contents: write",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC26-007", self.codes(root))

    def test_rejects_premature_roadmap_closure(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(
            roadmap.read_text(encoding="utf-8").replace(
                "- [ ] **Increment 26 — Deterministic output and reproducibility contract**",
                "- [x] **Increment 26 — Deterministic output and reproducibility contract**",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC26-008", self.codes(root))

    def test_accepts_validated_successor_state(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest_path = root / "tests/compiler/fixtures/increment26/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "validated-reproducibility-contract"
        manifest["evidence"] = {
            "pull_request": 1,
            "dedicated_run": 2,
            "core_ci_run": 3,
        }
        manifest_path.write_text(
            json.dumps(manifest, indent=2) + "\n",
            encoding="utf-8",
        )

        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(
            roadmap.read_text(encoding="utf-8")
            .replace("**Revision:** 1.31", "**Revision:** 1.33", 1)
            .replace(
                "- [ ] **Increment 26 — Deterministic output and reproducibility contract**",
                "- [x] **Increment 26 — Deterministic output and reproducibility contract**",
                1,
            ),
            encoding="utf-8",
        )
        self.assertEqual(CHECKER.check_repository(root), [])


if __name__ == "__main__":
    unittest.main()
