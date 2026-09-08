"""Permanent transfer integration and acceptance-state contracts."""
from __future__ import annotations
import hashlib
import importlib.util
import re
import json
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


CHECK = load("scripts/check_increment40.py", "check_increment40")
MATRIX = load("tests/compiler/fixtures/increment40/run_native_matrix.py", "matrix_increment40")


class Increment40Tests(unittest.TestCase):
    def test_repository_contract(self):
        CHECK.check_repository()

    def test_native_fixture_carries_state_and_order(self):
        for kind, order in [("laplace_nd", "ascending_s"), ("zi_nd", "ascending_z_inverse")]:
            text = MATRIX.transfer(kind)
            self.assertIn(f'coefficient_order = "{order}"', text)
            self.assertIn('state_id = "TransferTop.filter.state"', text)
            self.assertIn('operator_id = "TransferTop.filter"', text)
        self.assertIn('numerator_size = 0 : i64', MATRIX.transfer(numerator=[]))

    def test_state_has_no_pure_trait(self):
        text = (ROOT / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td").read_text()
        definition = text.split("def Nodal_AnalogTransferOp", 1)[1].split("\ndef ", 1)[0]
        self.assertNotIn("Pure", definition)
        self.assertIn("let hasVerifier = 1", definition)

    def test_public_example_does_not_use_internal_apis(self):
        source = (ROOT / CHECK.REQUIRED[7]).read_text()
        self.assertNotIn("internal", source)
        self.assertNotIn("ConstructionKernel", source)
        self.assertIn("laplaceNd", source)
        self.assertIn("ziNd", source)

    def test_unqualified_claims_cannot_close_increment(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = (*CHECK.REQUIRED, CHECK.MANIFEST, "docs/roadmap/nodal-development-todo.md",
                     "core/compiler/test/CMakeLists.txt", "core/compiler/test/Unit/CMakeLists.txt")
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
            roadmap = root / "docs/roadmap/nodal-development-todo.md"
            roadmap.write_text(roadmap.read_text().replace(CHECK.CLOSED, CHECK.OPEN))
            with self.assertRaisesRegex(AssertionError, "premature roadmap"):
                CHECK.check_repository(root)


class Increment40ClosureTests(unittest.TestCase):
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
        with self.assertRaisesRegex(AssertionError, "NODAL-INC40:"):
            CHECK.check_repository(root)

    def test_accepted_identity_and_witness_mutations(self):
        mutations = (
            lambda x: x.update(accepted_head="0" * 40),
            lambda x: x.update(accepted_tree="0" * 40),
            lambda x: x.update(implementation_merge="0" * 40),
            lambda x: x.update(post_merge_core_ci_run=1),
            lambda x: x.update(post_merge_increment40_run=1),
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

    def test_missing_evidence_and_native_registration(self):
        for relative in (CHECK.EVIDENCE, "core/compiler/test/Unit/AnalogTransferReparseTest.cpp"):
            root = self.fixture()
            (root / relative).unlink()
            self.rejected(root)
        for relative, token in (("core/compiler/test/CMakeLists.txt", "increment40/run_native_matrix.py"),
                                ("core/compiler/test/Unit/CMakeLists.txt", "AnalogTransferReparseTest.cpp")):
            root = self.fixture()
            path = root / relative
            self.assertIn(token, path.read_text())
            path.write_text(path.read_text().replace(token, "missing"))
            self.rejected(root)

    def test_manifest_scope_and_capability_mutations(self):
        mutations = (
            lambda x: x["accepted_evidence"].update(sha256="0" * 64),
            lambda x: x.update(remaining=["not validated"]),
            lambda x: x.update(status="almost-done"),
            lambda x: x.update(operators=["laplace_nd", "zi_nd", "laplace_zp"]),
            lambda x: x.update(stateful=False),
            lambda x: x.update(qualification="numerical-simulation"),
            lambda x: x.update(deferred=[]),
            lambda x: x["coefficient_order"].update(laplace_nd="descending_s"),
            lambda x: x["semantics"].update(shared_state_single_evaluation=False),
            lambda x: x["semantics"].pop("recursive_forged_fold_rejection"),
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

    def test_historical_open_state_must_be_consistent(self):
        root = self.fixture()
        self.json_change(root, CHECK.MANIFEST, lambda x: x.update(
            status="implementation-in-progress", accepted_evidence=None, remaining=["validation"]))
        self.rejected(root)
        path = root / CHECK.ROADMAP
        path.write_text(path.read_text().replace(CHECK.CLOSED, CHECK.OPEN))
        path = root / CHECK.IMPLEMENTATION
        path.write_text(path.read_text().replace("**Status:** Validated", "**Status:** Implementation in progress"))
        CHECK.check_repository(root)
        self.json_change(root, CHECK.MANIFEST, lambda x: x.update(remaining=[]))
        self.rejected(root)

    def test_roadmap_demonstration_and_review_agree(self):
        for relative, old, new in (
            (CHECK.ROADMAP, CHECK.CLOSED, CHECK.OPEN),
            (CHECK.IMPLEMENTATION, "**Status:** Validated", "**Status:** Candidate"),
            (CHECK.CLOSURE, "14860e5cfbbaacdc866fdfc6f61f91ffb93d33a1", "unknown-head"),
            (CHECK.CLOSURE, "```verilog", "```text"),
            (CHECK.CLOSURE, "shared + shared", "shared + discrete"),
            (CHECK.CLOSURE, "(transfer_0 + transfer_0)", "(transfer_0 + transfer_1)"),
            (CHECK.CLOSURE, "not independent automated review", "independent automated review"),
            (CHECK.CLOSURE, "Foundation 153–157", "future work"),
        ):
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
        path.write_text(re.sub(r"^\*\*Revision:\*\* .*", "**Revision:** 1.50", text, flags=re.M))
        self.rejected(root)
        path.write_text(re.sub(r"^\*\*Revision:\*\* .*", "**Revision:** 2.0", text, flags=re.M))
        CHECK.check_repository(root)
        path.write_text(text + "\n**Revision:** 1.51\n")
        self.rejected(root)
        path.write_text(text + "\n" + CHECK.CLOSED + "\n")
        self.rejected(root)

    def test_qualification_cannot_be_disabled(self):
        for token in ("./nodal core scala", "./nodal core native", "--source", "contents: read"):
            root = self.fixture()
            path = root / ".github/workflows/increment-40-transfer-operators.yml"
            self.assertIn(token, path.read_text())
            path.write_text(path.read_text().replace(token, "removed"))
            self.rejected(root)

    def test_gate_and_record_reference_are_required(self):
        root = self.fixture()
        gate = root / "docs/design-gates/NodalTransferOperators-DG-v0.1.md"
        gate.write_text(gate.read_text().replace("**Status:** Approved", "**Status:** Candidate"))
        self.rejected(root)
        root = self.fixture()
        self.json_change(root, CHECK.MANIFEST, lambda x: x.update(accepted_evidence=None))
        self.rejected(root)


if __name__ == "__main__":
    unittest.main()
