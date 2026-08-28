from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment28.py"
SPEC = importlib.util.spec_from_file_location("check_increment28", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT = ("docs/roadmap/nodal-development-todo.md",)


class Increment28CheckerTests(unittest.TestCase):
    def temporary_repository(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in dict.fromkeys(CHECKER.EXPECTED_FILES + SUPPORT):
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def test_repository_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(ROOT), [])

    def test_rejects_missing_stable_connection_identity(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ConservativeConnectivity.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace("stableHash", "unstableCounter"),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC28-005", self.codes(root))

    def test_rejects_missing_compatible_discipline_selection(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ConservativeConnectivity.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "endpoints[index].operation, info.discipline, endpoints[index].discipline",
                "endpoints[index].operation, info.discipline, info.discipline",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC28-005", self.codes(root))

    def test_rejects_discardable_generated_attributes(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ConservativeConnectivity.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "state.propertiesAttr = builder.getDictionaryAttr",
                "state.addAttributes",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC28-005", self.codes(root))

    def test_rejects_causal_port_direction_lowering(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ConservativeConnectivity.cpp"
        text = path.read_text(encoding="utf-8")
        text = text.replace(
            'endpoint.flowOrientation == "into_component" ? -1 : 1',
            'textAttr(endpoint.operation, "direction") == "output" ? -1 : 1',
            1,
        )
        path.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC28-005", self.codes(root))

    def test_rejects_user_authored_normalized_operations(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ConservativeConnectivity.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "normalized connectivity operations are compiler-owned",
                "normalized connectivity operations are accepted from source",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC28-005", self.codes(root))

    def test_rejects_missing_oriented_flow_provenance_check(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ConservativeConnectivity.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "flow provenance disagrees with its oriented source term",
                "flow provenance was not checked",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC28-005", self.codes(root))

    def test_rejects_missing_diagnostic(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/diagnostics-v0.1.json"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                '      "NODAL-BRANCH-IMPLICIT-001",\n', "", 1
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC28-016", self.codes(root))

    def test_rejects_writable_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-28-electrical-connectivity.yml"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "contents: read", "contents: write", 1
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC28-015", self.codes(root))

    def test_rejects_premature_roadmap_closure(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)

        manifest_path = root / "tests/compiler/fixtures/increment28/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "implemented-awaiting-evidence"
        manifest["evidence"] = {}
        manifest_path.write_text(
            json.dumps(manifest, indent=2) + "\n",
            encoding="utf-8",
        )

        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        text = roadmap.read_text(encoding="utf-8")
        if "- [ ] **Increment 28 — Electrical nodes, nets, and branches**" in text:
            text = text.replace(
                "- [ ] **Increment 28 — Electrical nodes, nets, and branches**",
                "- [x] **Increment 28 — Electrical nodes, nets, and branches**",
                1,
            )
        roadmap.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC28-019", self.codes(root))

    def test_accepts_validated_state(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest_path = root / "tests/compiler/fixtures/increment28/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "validated-electrical-connectivity"
        manifest["evidence"] = {
            "pull_request": 1,
            "dedicated_run": 2,
            "core_ci_run": 3,
        }
        manifest_path.write_text(
            json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
        )
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        text = roadmap.read_text(encoding="utf-8")
        text = text.replace("**Revision:** 1.35", "**Revision:** 1.36", 1)
        text = text.replace(
            "- [ ] **Increment 28 — Electrical nodes, nets, and branches**",
            "- [x] **Increment 28 — Electrical nodes, nets, and branches**",
            1,
        )
        roadmap.write_text(text, encoding="utf-8")
        self.assertEqual(CHECKER.check_repository(root), [])


    def test_accepts_validated_increment29_successor(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest_path = root / "tests/compiler/fixtures/increment29/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "validated-parameter-constant-unit"
        manifest["evidence"] = {
            "pull_request": 1,
            "dedicated_run": 2,
            "core_ci_run": 3,
        }
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        text = roadmap.read_text(encoding="utf-8")
        text = text.replace("**Revision:** 1.36", "**Revision:** 1.37", 1)
        text = text.replace(
            "- [ ] **Increment 29 — Parameters, constants, ranges, and units**",
            "- [x] **Increment 29 — Parameters, constants, ranges, and units**",
            1,
        )
        roadmap.write_text(text, encoding="utf-8")
        self.assertEqual(CHECKER.check_repository(root), [])


if __name__ == "__main__":
    unittest.main()
