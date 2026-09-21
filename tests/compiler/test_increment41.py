"""Permanent function integration and accepted-evidence contracts."""
from __future__ import annotations
import hashlib
import importlib.util
import json
import re
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CHECK = load("scripts/check_increment41.py", "check_increment41")
MATRIX = load("tests/compiler/fixtures/increment41/run_native_matrix.py", "matrix_increment41")


class Increment41Tests(unittest.TestCase):
    def test_repository_contract(self):
        CHECK.check_repository()

    def test_native_fixture_has_first_class_definitions_calls_and_return(self):
        text = MATRIX.fixture()
        for operation in ("nodal.analog_user_function", "nodal.analog_function_value",
                          "nodal.analog_user_call", "nodal.analog_function_return"):
            self.assertIn(operation, text)
        self.assertIn('return_type = ' + MATRIX.VOLTAGE, text)
        self.assertIn('callee = @scaleSignal', text)

    def test_calls_do_not_claim_unproved_folding_or_purity(self):
        text = (ROOT / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td").read_text()
        definition = text.split("def Nodal_AnalogUserCallOp", 1)[1].split("\ndef ", 1)[0]
        self.assertNotIn("Pure", definition)
        self.assertIn("let hasVerifier = 1", definition)

    def test_public_example_does_not_use_internal_apis(self):
        source = (ROOT / CHECK.REQUIRED[7]).read_text()
        self.assertNotIn("internal", source)
        self.assertNotIn("ConstructionKernel", source)
        self.assertIn("AnalogFunction", source)
        self.assertIn("PhysicalDimension.Voltage", source)

    def test_unqualified_claims_cannot_close_increment(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = (*CHECK.REQUIRED, CHECK.MANIFEST)
            for path in paths:
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / path, root / path)
            CHECK.check_repository(root)
            manifest = root / CHECK.MANIFEST
            original = manifest.read_text()
            data = json.loads(original)
            data["status"] = "complete"
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(AssertionError, "accepted-evidence"):
                CHECK.check_repository(root)
            manifest.write_text(original)
            roadmap = root / CHECK.ROADMAP
            roadmap.write_text(roadmap.read_text().replace(CHECK.CLOSED, CHECK.OPEN))
            with self.assertRaisesRegex(AssertionError, "premature roadmap"):
                CHECK.check_repository(root)


class Increment41ClosureTests(unittest.TestCase):
    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        for relative in (*CHECK.REQUIRED, CHECK.MANIFEST):
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, destination)
        return root

    def json_change(self, root, path, mutate):
        target = root / path
        data = json.loads(target.read_text())
        mutate(data)
        target.write_text(json.dumps(data, indent=2) + "\n")

    def rejected(self, root):
        with self.assertRaisesRegex(AssertionError, "NODAL-INC41:"):
            CHECK.check_repository(root)

    def test_accepted_identity_and_witness_mutations(self):
        mutations = (
            lambda x: x.update(accepted_head="0" * 40),
            lambda x: x.update(accepted_tree="0" * 40),
            lambda x: x.update(implementation_merge="0" * 40),
            lambda x: x.update(post_merge_core_ci_run=1),
            lambda x: x.update(post_merge_increment41_run=1),
            lambda x: x["source_witness_sha256"].update({"public.va": "0" * 64}),
            lambda x: x["review"].update(kind="independent-automated"),
            lambda x: x.update(numerical_simulator_execution_claimed=True),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                root = self.fixture()
                self.json_change(root, CHECK.EVIDENCE, mutate)
                self.rejected(root)

    def test_coordinated_record_and_manifest_replacement_rejected(self):
        root = self.fixture()
        self.json_change(root, CHECK.EVIDENCE, lambda x: x.update(accepted_head="0" * 40))
        digest = hashlib.sha256((root / CHECK.EVIDENCE).read_bytes()).hexdigest()
        self.json_change(root, CHECK.MANIFEST,
                         lambda x: x["accepted_evidence"].update(sha256=digest))
        self.rejected(root)

    def test_manifest_scope_and_capability_mutations(self):
        mutations = (
            lambda x: x["accepted_evidence"].update(sha256="0" * 64),
            lambda x: x.update(remaining=["not validated"]),
            lambda x: x.update(status="almost-done"),
            lambda x: x.update(profile="broader-functions"),
            lambda x: x.update(recursion="allowed"),
            lambda x: x.update(qualification="numerical-simulation"),
            lambda x: x.update(deferred=[]),
            lambda x: x["semantics"].update(forged_call_attribute_rejection=False),
            lambda x: x["semantics"].pop("source_map_namespace_isolation"),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                root = self.fixture()
                self.json_change(root, CHECK.MANIFEST, mutate)
                self.rejected(root)

    def test_predecessor_acceptance_is_immutable(self):
        root = self.fixture()
        self.json_change(root, CHECK.PREDECESSOR, lambda x: x.update(accepted_head="0" * 40))
        self.rejected(root)
        root = self.fixture()
        self.json_change(root, CHECK.PREDECESSOR_MANIFEST,
                         lambda x: x.update(status="implementation-in-progress"))
        self.rejected(root)

    def test_roadmap_demonstration_and_review_agree(self):
        changes = (
            (CHECK.ROADMAP, CHECK.CLOSED, CHECK.OPEN),
            (CHECK.IMPLEMENTATION, "**Status:** Validated", "**Status:** Candidate"),
            (CHECK.CLOSURE, "3ef30a40e2f81c5938e37cfbad7ad38c9146c3c2", "unknown-head"),
            (CHECK.CLOSURE, "```verilog", "```text"),
            (CHECK.CLOSURE, "scaled + offset", "scaled - offset"),
            (CHECK.CLOSURE, "(scaled + offset)", "(scaled - offset)"),
            (CHECK.CLOSURE, "not independent automated review", "independent automated review"),
        )
        for relative, old, new in changes:
            with self.subTest(old=old):
                root = self.fixture()
                path = root / relative
                self.assertIn(old, path.read_text())
                path.write_text(path.read_text().replace(old, new))
                self.rejected(root)

    def test_revision_lower_bound_and_duplicate_guards(self):
        root = self.fixture()
        path = root / CHECK.ROADMAP
        text = path.read_text()
        path.write_text(re.sub(r"^\*\*Revision:\*\* .*", "**Revision:** 1.51", text, flags=re.M))
        self.rejected(root)
        path.write_text(re.sub(r"^\*\*Revision:\*\* .*", "**Revision:** 2.0", text, flags=re.M))
        CHECK.check_repository(root)
        path.write_text(text + "\n**Revision:** 1.52\n")
        self.rejected(root)
        path.write_text(text + "\n" + CHECK.CLOSED + "\n")
        self.rejected(root)

    def test_qualification_cannot_be_disabled(self):
        for token in ("./nodal core scala", "./nodal core native", "--source", "contents: read"):
            root = self.fixture()
            path = root / ".github/workflows/increment-41-analog-functions.yml"
            self.assertIn(token, path.read_text())
            path.write_text(path.read_text().replace(token, "removed"))
            self.rejected(root)

    def test_gate_and_record_reference_are_required(self):
        root = self.fixture()
        gate = root / "docs/design-gates/NodalAnalogUserFunctions-DG-v0.1.md"
        gate.write_text(gate.read_text().replace("**Status:** Approved", "**Status:** Candidate"))
        self.rejected(root)
        root = self.fixture()
        self.json_change(root, CHECK.MANIFEST, lambda x: x.update(accepted_evidence=None))
        self.rejected(root)


if __name__ == "__main__":
    unittest.main()
