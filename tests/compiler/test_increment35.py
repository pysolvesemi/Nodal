from __future__ import annotations

import importlib.util
import json
import re
import shutil
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CURRENT_REVISION = re.search(
    r"^\*\*Revision:\*\* ([0-9]+\.[0-9]+)$",
    (ROOT / "docs/roadmap/nodal-development-todo.md").read_text(), re.M
).group(1)
CHECKER_PATH = ROOT / "scripts" / "check_increment35.py"
SPEC = importlib.util.spec_from_file_location("check_increment35", CHECKER_PATH)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


REQUIRED = (
    ".github/workflows/increment-35-differential-integral-operators.yml",
    "core/scala/api/src/nodal/CandidateApi.scala",
    "core/scala/api/src/nodal/ElaborationConstructionKernel.scala",
    "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
    "core/scala/testkit/test/src/nodal/DifferentialIntegralConstructionTests.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/DifferentialIntegralBridgeTests.scala",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/IR/analog-differential-integral.mlir",
    "core/compiler/test/IR/analog-differential-integral-backend.mlir",
    "core/compiler/test/IR/analog-differential-integral-invalid-context.mlir",
    "core/compiler/test/IR/analog-differential-integral-invalid-initial.mlir",
    "core/compiler/test/IR/analog-differential-integral-invalid-state.mlir",
    "core/compiler/test/IR/analog-differential-integral-invalid-simplification.mlir",
    "core/compiler/test/IR/analog-differential-integral-invalid-idt-fold.mlir",
    "docs/design-gates/NodalDifferentialIntegralOperators-DG-v0.1.md",
    "docs/implementation/increment35-differential-integral-operators.md",
    "docs/implementation/increment35-evidence-closure.md",
    "docs/roadmap/nodal-development-todo.md",
    "examples/continuousTimeApi/src/nodal/increment35fixture/Increment35ConstructionCheck.scala",
    "scripts/check_increment35.py",
    "tests/compiler/fixtures/increment34/manifest.json",
    "tests/compiler/fixtures/increment35/README.md",
    "tests/compiler/fixtures/increment35/manifest.json",
    "tests/compiler/test_increment35.py",
)


class Increment35ContractTests(unittest.TestCase):
    def fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in REQUIRED:
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def assert_rejected(self, root: Path, fragment: str) -> None:
        with self.assertRaises(CHECKER.CheckFailure) as captured:
            CHECKER.check_repository(root)
        self.assertIn(fragment, str(captured.exception))

    def read_manifest(self, root: Path) -> tuple[Path, dict[str, object]]:
        path = root / "tests/compiler/fixtures/increment35/manifest.json"
        return path, json.loads(path.read_text(encoding="utf-8"))

    def write_manifest(self, path: Path, document: dict[str, object]) -> None:
        path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")

    def replace_file(self, root: Path, relative: str, old: str, new: str) -> None:
        path = root / relative
        text = path.read_text(encoding="utf-8")
        self.assertEqual(text.count(old), 1, f"expected one occurrence of {old!r}")
        path.write_text(text.replace(old, new, 1), encoding="utf-8")

    def test_repository_checkpoint_passes(self) -> None:
        CHECKER.check_repository(ROOT)

    def test_current_manifest_is_candidate_or_validated_with_complete_identity(self) -> None:
        manifest = json.loads(
            (ROOT / "tests/compiler/fixtures/increment35/manifest.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertIn(
            manifest["status"],
            {
                "evidence-closure-candidate",
                "validated-differential-integral-operators",
            },
        )
        validation = manifest["validation"]
        self.assertIsInstance(validation, dict)
        self.assertEqual(validation["implementation_pull_request"], 113)
        self.assertEqual(
            validation["accepted_head"],
            "d3410f6f64dc66df27d9c7f545c9e78f62695f2e",
        )
        if manifest["status"] == "evidence-closure-candidate":
            self.assertIsNone(validation["closure_validation_head"])
            self.assertIsNone(validation["closure_validation_run"])
        else:
            self.assertTrue(validation["closure_validation_head"])
            self.assertTrue(validation["closure_validation_run"])

    def test_open_state_remains_supported(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path, document = self.read_manifest(root)
            document["status"] = "implementation-in-progress"
            document["tranche"] = "35a-differential-integral-operator-contract"
            document["validation"] = None
            self.write_manifest(path, document)
            self.replace_file(
                root,
                "docs/roadmap/nodal-development-todo.md",
                f"**Revision:** {CURRENT_REVISION}",
                "**Revision:** 1.45",
            )
            self.replace_file(
                root,
                "docs/roadmap/nodal-development-todo.md",
                "- [x] **Increment 35 — Differential and integral operators**",
                "- [ ] **Increment 35 — Differential and integral operators**",
            )
            implementation = (
                root
                / "docs/implementation/increment35-differential-integral-operators.md"
            )
            text = implementation.read_text(encoding="utf-8")
            text = text.replace("**Status:** Closure candidate", "**Status:** In progress", 1)
            text = text.replace("**Status:** Validated", "**Status:** In progress", 1)
            implementation.write_text(text, encoding="utf-8")
            CHECKER.check_repository(root)

    def test_candidate_state_remains_supported(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path, document = self.read_manifest(root)
            validation = document["validation"]
            assert isinstance(validation, dict)
            roadmap = root / "docs/roadmap/nodal-development-todo.md"
            roadmap.write_text(re.sub(
                r"^\*\*Revision:\*\* .*", "**Revision:** 1.46",
                roadmap.read_text(), count=1, flags=re.M
            ))
            document["status"] = "evidence-closure-candidate"
            document["tranche"] = "35b-evidence-closure"
            validation["closure_validation_head"] = None
            validation["closure_validation_run"] = None
            self.write_manifest(path, document)

            implementation = (
                root
                / "docs/implementation/increment35-differential-integral-operators.md"
            )
            text = implementation.read_text(encoding="utf-8").replace(
                "**Status:** Validated", "**Status:** Closure candidate", 1
            )
            implementation.write_text(text, encoding="utf-8")

            evidence = root / "docs/implementation/increment35-evidence-closure.md"
            text = evidence.read_text(encoding="utf-8")
            if "**Status:** Validated evidence closure" in text:
                head = next(
                    line.split("`", 2)[1]
                    for line in text.splitlines()
                    if line.startswith("**Closure validation head:** `")
                )
                run = next(
                    line.split("`", 2)[1]
                    for line in text.splitlines()
                    if line.startswith("**Closure validation run:** `")
                )
                text = text.replace(
                    "**Status:** Validated evidence closure",
                    "**Status:** Closure candidate awaiting exact-head validation",
                    1,
                )
                text = text.replace(
                    f"**Closure validation head:** `{head}`",
                    "**Closure validation head:** pending",
                    1,
                )
                text = text.replace(
                    f"**Closure validation run:** `{run}`",
                    "**Closure validation run:** pending",
                    1,
                )
                evidence.write_text(text, encoding="utf-8")
            CHECKER.check_repository(root)

    def test_validated_state_remains_supported(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path, document = self.read_manifest(root)
            validation = document["validation"]
            assert isinstance(validation, dict)
            if document["status"] == "validated-differential-integral-operators":
                CHECKER.check_repository(root)
                return
            document["status"] = "validated-differential-integral-operators"
            document["tranche"] = "35b-evidence-closure"
            validation["closure_validation_head"] = "1" * 40
            validation["closure_validation_run"] = 1
            self.write_manifest(path, document)

            implementation = (
                root
                / "docs/implementation/increment35-differential-integral-operators.md"
            )
            text = implementation.read_text(encoding="utf-8").replace(
                "**Status:** Closure candidate", "**Status:** Validated", 1
            )
            implementation.write_text(text, encoding="utf-8")

            evidence = root / "docs/implementation/increment35-evidence-closure.md"
            text = evidence.read_text(encoding="utf-8")
            text = text.replace(
                "**Status:** Closure candidate awaiting exact-head validation",
                "**Status:** Validated evidence closure",
                1,
            )
            text = text.replace(
                "**Closure validation head:** pending",
                f"**Closure validation head:** `{'1' * 40}`",
                1,
            )
            text = text.replace(
                "**Closure validation run:** pending",
                "**Closure validation run:** `1`",
                1,
            )
            evidence.write_text(text, encoding="utf-8")
            CHECKER.check_repository(root)

    def test_closed_roadmap_regression_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            self.replace_file(
                root,
                "docs/roadmap/nodal-development-todo.md",
                "- [x] **Increment 35 — Differential and integral operators**",
                "- [ ] **Increment 35 — Differential and integral operators**",
            )
            self.assert_rejected(root, "revision 1.46")

    def test_accepted_implementation_identity_is_locked(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path, document = self.read_manifest(root)
            validation = document["validation"]
            assert isinstance(validation, dict)
            validation["accepted_head"] = "0" * 40
            self.write_manifest(path, document)
            self.assert_rejected(root, "does not match the accepted implementation")

    def test_candidate_cannot_claim_its_own_validation(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path, document = self.read_manifest(root)
            validation = document["validation"]
            assert isinstance(validation, dict)
            document["status"] = "evidence-closure-candidate"
            validation["closure_validation_head"] = "1" * 40
            validation["closure_validation_run"] = 1
            self.write_manifest(path, document)
            self.assert_rejected(root, "must not claim its own validation")

    def test_open_state_cannot_carry_closure_evidence(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path, document = self.read_manifest(root)
            document["status"] = "implementation-in-progress"
            document["tranche"] = "35a-differential-integral-operator-contract"
            self.write_manifest(path, document)
            self.assert_rejected(root, "open Increment 35 manifest state is invalid")

    def test_evidence_record_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            self.replace_file(
                root,
                "docs/implementation/increment35-evidence-closure.md",
                "**Implementation PR:** #113",
                "**Implementation PR:** #999",
            )
            self.assert_rejected(root, "is missing '**Implementation PR:** #113'")

    def test_all_stable_diagnostics_are_declared(self) -> None:
        manifest = json.loads(
            (ROOT / "tests/compiler/fixtures/increment35/manifest.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(
            manifest["diagnostics"],
            [f"NODAL-ANALOG-035-{index:03d}" for index in range(1, 9)],
        )

    def test_deferred_algebraic_transforms_remain_disabled(self) -> None:
        manifest = json.loads(
            (ROOT / "tests/compiler/fixtures/increment35/manifest.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertFalse(manifest["semantics"]["inverse_operator_cancellation"])
        self.assertFalse(manifest["semantics"]["operator_distribution"])
        self.assertFalse(manifest["integration"]["full_dae_solver_lowering"])


class AcceptedRoadmapRevisionTests(unittest.TestCase):
    def test_later_revision_preserves_accepted_predecessor(self):
        self.assertTrue(CHECKER.accepted_roadmap_revision("**Revision:** 1.47"))
        self.assertTrue(CHECKER.accepted_roadmap_revision("**Revision:** 1.100"))

    def test_missing_ambiguous_and_regressed_revisions_fail(self):
        for text in ("", "**Revision:** 1.45", "**Revision:** bad",
                     "**Revision:** 1.46\n**Revision:** 1.47"):
            with self.subTest(text=text):
                self.assertFalse(CHECKER.accepted_roadmap_revision(text))


if __name__ == "__main__":
    unittest.main()
