from __future__ import annotations

import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class Increment35HardeningTests(unittest.TestCase):
    def text(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8")

    def data(self, relative: str) -> dict[str, object]:
        return json.loads(self.text(relative))

    def test_temporary_bootstrap_artifacts_are_absent(self) -> None:
        temporary = (
            ".github/workflows/increment-35-bootstrap.yml",
            "scripts/apply_increment35_repair.py",
            "scripts/increment35-repair.trigger",
            "scripts/bootstrap_increment35.part00",
            "scripts/bootstrap_increment35.part01",
            "scripts/bootstrap_increment35.part02",
            "scripts/bootstrap_increment35.part03",
            "scripts/bootstrap_increment35.part04",
            "scripts/bootstrap_increment35.part05",
            "scripts/bootstrap_increment35.part06",
            "tests/compiler/fixtures/increment35/bootstrap-revision.txt",
        )
        for relative in temporary:
            with self.subTest(relative=relative):
                self.assertFalse((ROOT / relative).exists())

    def test_manifest_and_diagnostic_catalog_record_hardened_contracts(self) -> None:
        manifest = self.data("tests/compiler/fixtures/increment35/manifest.json")
        catalog = self.data("core/compiler/diagnostics-v0.1.json")
        semantics = manifest["semantics"]
        integration = manifest["integration"]
        expected = [f"NODAL-ANALOG-035-{index:03d}" for index in range(1, 9)]

        for key in (
            "legacy_ddt_diagnostic_compatibility",
            "native_operator_identity_uniqueness",
            "operator_identity_owner_qualified",
            "contribution_context_retained",
            "typed_nonzero_initial_condition",
            "exact_analysis_inventory",
            "deterministic_unique_state_identity",
        ):
            with self.subTest(key=key):
                self.assertIs(semantics[key], True)
        self.assertIs(integration["bootstrap_scaffolding_removed"], True)
        self.assertIs(integration["hardening_regressions"], True)
        self.assertEqual(manifest["diagnostics"], expected)
        self.assertEqual(catalog["families"]["analog-differential-integral"], expected)
        self.assertIn("NODAL-ANALOG-035-", catalog["preserved_prefixes"])

    def test_native_verifier_preserves_legacy_ddt_and_checks_identity(self) -> None:
        verifier = self.text("core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp")
        for token in (
            '#include "llvm/ADT/StringSet.h"',
            "NODAL-ANALOG-DDT-001",
            "operatorIdentity.starts_with(ownerIdentity)",
            "continuousOperatorIds",
            "continuousStateIds",
            "continuous-time operator identity must be unique",
            "integral state identity must be unique",
        ):
            with self.subTest(token=token):
                self.assertIn(token, verifier)

    def test_hardened_native_fixtures_are_present(self) -> None:
        fixtures = (
            "analog-differential-integral-invalid-contract.mlir",
            "analog-differential-integral-invalid-owner.mlir",
            "analog-differential-integral-invalid-dimension.mlir",
            "analog-differential-integral-invalid-analysis.mlir",
            "analog-differential-integral-invalid-duplicate-identity.mlir",
        )
        for name in fixtures:
            with self.subTest(name=name):
                self.assertTrue((ROOT / "core/compiler/test/IR" / name).is_file())

    def test_dedicated_workflow_executes_hardened_matrix(self) -> None:
        workflow = self.text(
            ".github/workflows/increment-35-differential-integral-operators.yml"
        )
        for token in (
            "python3 scripts/check_increment24.py",
            "test_increment35*.py",
            "analysis_inventory_exact=true",
            "owner_qualified=true",
            "contribution_context=true",
            "typed_initial_dimension=current*time",
            "state_ids_unique=true",
            "state_ids_stable=true",
            "check_invalid contract NODAL-ANALOG-035-002",
            "check_invalid owner NODAL-ANALOG-035-002",
            "check_invalid dimension NODAL-ANALOG-035-003",
            "check_invalid analysis NODAL-ANALOG-035-006",
            "check_invalid_with_pipeline duplicate-identity NODAL-ANALOG-035-002",
        ):
            with self.subTest(token=token):
                self.assertIn(token, workflow)


if __name__ == "__main__":
    unittest.main()
