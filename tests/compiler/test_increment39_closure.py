"""Mutation tests for Increment 39's separately retained acceptance record."""
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
SPEC = importlib.util.spec_from_file_location("increment39_closure", ROOT / "scripts/check_increment39.py")
assert SPEC and SPEC.loader
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class Increment39ClosureTests(unittest.TestCase):
    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        for relative in CHECK.REQUIRED:
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
        with self.assertRaisesRegex(AssertionError, "NODAL-INC39:"):
            CHECK.check_repository(root)

    def test_checked_repository(self):
        CHECK.check_repository(ROOT)

    def test_accepted_identity_and_witness_mutations(self):
        mutations = (
            lambda x: x.update(accepted_head="0" * 40),
            lambda x: x.update(accepted_tree="0" * 40),
            lambda x: x.update(implementation_merge="0" * 40),
            lambda x: x.update(post_merge_core_ci_run=1),
            lambda x: x.update(post_merge_increment39_run=1),
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
        for relative in (CHECK.EVIDENCE, "core/compiler/test/Unit/AnalogNoiseReparseTest.cpp"):
            root = self.fixture()
            (root / relative).unlink()
            self.rejected(root)
        for relative, token in (("core/compiler/test/CMakeLists.txt", "increment39/run_native_matrix.py"),
                                ("core/compiler/test/Unit/CMakeLists.txt", "AnalogNoiseReparseTest.cpp")):
            root = self.fixture()
            path = root / relative
            path.write_text(path.read_text().replace(token, "missing"))
            self.rejected(root)

    def test_manifest_scope_and_capability_mutations(self):
        mutations = (
            lambda x: x["accepted_evidence"].update(sha256="0" * 64),
            lambda x: x.update(remaining=["not validated"]),
            lambda x: x.update(status="almost-done"),
            lambda x: x.update(operators=["white"]),
            lambda x: x.update(analyses=["noise", "transient"]),
            lambda x: x.update(deferred=[]),
            lambda x: x["semantics"].update(single_evaluation_for_shared_sources=False),
            lambda x: x["semantics"].pop("source_map_retention"),
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
        self.json_change(root, "tests/compiler/fixtures/increment38/manifest.json",
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
            (CHECK.CLOSURE, "c22846da1812ef8b30ee8299ad89906bcd5171f2", "unknown-head"),
            (CHECK.CLOSURE, "```verilog", "```text"),
            (CHECK.CLOSURE, "shared + shared", "shared + independent"),
            (CHECK.CLOSURE, "(noise_0 + noise_0)", "(noise_0 + noise_1)"),
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
        path.write_text(re.sub(r"^\*\*Revision:\*\* .*", "**Revision:** 1.49", text, flags=re.M))
        self.rejected(root)
        path.write_text(re.sub(r"^\*\*Revision:\*\* .*", "**Revision:** 2.0", text, flags=re.M))
        CHECK.check_repository(root)
        path.write_text(text + "\n**Revision:** 1.50\n")
        self.rejected(root)
        path.write_text(text + "\n" + CHECK.CLOSED + "\n")
        self.rejected(root)

    def test_qualification_cannot_be_disabled(self):
        for token in ("./nodal core scala", "./nodal core native", "--source", "contents: read"):
            root = self.fixture()
            path = root / ".github/workflows/increment-39-noise-operators.yml"
            path.write_text(path.read_text().replace(token, "removed"))
            self.rejected(root)


if __name__ == "__main__":
    unittest.main()
