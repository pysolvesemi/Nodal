"""Mutation tests for Increment 42 accepted-evidence closure."""
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


CHECK = load("scripts/check_increment42.py", "check_increment42")


class Increment42ClosureTests(unittest.TestCase):
    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        for relative in CHECK.REQUIRED:
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        return root

    def json_change(self, root, relative, mutate):
        target = root / relative
        data = json.loads(target.read_text())
        mutate(data)
        target.write_text(json.dumps(data, indent=2) + "\n")

    def rejected(self, root, pattern="NODAL-INC42:"):
        with self.assertRaisesRegex(AssertionError, pattern):
            CHECK.check_repository(root)

    def test_repository_contract(self):
        CHECK.check_repository()

    def test_accepted_identity_and_merge_mutations(self):
        mutations = (
            lambda x: x.update(accepted_head="0" * 40),
            lambda x: x.update(accepted_tree="0" * 40),
            lambda x: x.update(implementation_merge="0" * 40),
            lambda x: x.update(implementation_merge_tree="0" * 40),
            lambda x: x.update(preserved_dev_parent="0" * 40),
            lambda x: x["qualification"].update(successful_job_count=37),
            lambda x: x["review"].update(unresolved_threads=1),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                root = self.fixture()
                self.json_change(root, CHECK.EVIDENCE, mutate)
                self.rejected(root)

    def test_post_merge_skip_cannot_be_relabelled_or_fabricated(self):
        mutations = (
            lambda x: x["post_merge_ci"].update(status="success"),
            lambda x: x["post_merge_ci"].update(reason="ordinary-post-merge"),
            lambda x: x["post_merge_ci"].update(unexpected_runs=[1]),
            lambda x: x["post_merge_ci"].update(run_id=1),
            lambda x: x["post_merge_ci"].update(inventory_pages=[1]),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                root = self.fixture()
                self.json_change(root, CHECK.EVIDENCE, mutate)
                self.rejected(root)

    def test_witness_artifact_output_matrix_and_claim_mutations(self):
        mutations = (
            lambda x: x["closure_witness"].update(artifact_id=1),
            lambda x: x["closure_witness"].update(artifact_sha256="0" * 64),
            lambda x: x["closure_witness"]["output_sha256"].update(
                {"increment42-public.va": "0" * 64}),
            lambda x: x["closure_witness"]["native_matrix"].update(case_count=571),
            lambda x: x["closure_witness"]["native_matrix"].update(failure_count=1),
            lambda x: x.update(numerical_simulator_execution_claimed=True),
            lambda x: x.update(independent_openvaf_execution_claimed=True),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                root = self.fixture()
                self.json_change(root, CHECK.EVIDENCE, mutate)
                self.rejected(root)

    def test_coordinated_evidence_and_manifest_replacement_is_rejected(self):
        root = self.fixture()
        self.json_change(root, CHECK.EVIDENCE,
                         lambda x: x.update(accepted_head="0" * 40))
        changed = hashlib.sha256((root / CHECK.EVIDENCE).read_bytes()).hexdigest()
        self.json_change(root, CHECK.MANIFEST,
                         lambda x: x["accepted_evidence"].update(sha256=changed))
        self.rejected(root)

    def test_manifest_scope_and_semantics_mutations(self):
        mutations = (
            lambda x: x.update(status="almost-validated"),
            lambda x: x.update(profile="general-verilog-ams"),
            lambda x: x.update(remaining=["open"]),
            lambda x: x.update(root_actual_policy="discard-non-default"),
            lambda x: x.update(deferred=[]),
            lambda x: x["semantics"].update(target_reparse=False),
            lambda x: x["semantics"].pop("source_located_hierarchy_rejection"),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                root = self.fixture()
                self.json_change(root, CHECK.MANIFEST, mutate)
                self.rejected(root)

    def test_predecessor_and_closure_owned_source_are_immutable(self):
        root = self.fixture()
        self.json_change(root, CHECK.PREDECESSOR,
                         lambda x: x.update(accepted_head="0" * 40))
        self.rejected(root)
        for relative in CHECK.LIVE_SOURCE_SHA256:
            with self.subTest(relative=relative):
                root = self.fixture()
                target = root / relative
                target.write_text(target.read_text() + "\n")
                self.rejected(root)

    def test_historical_workflow_hash_does_not_pin_live_shared_workflow(self):
        root = self.fixture()
        target = root / CHECK.WORKFLOW
        target.write_text(target.read_text() + "\n# unrelated future maintenance\n")
        CHECK.check_repository(root)

    def test_roadmap_document_and_demonstrations_agree(self):
        changes = (
            (CHECK.ROADMAP,
             "- [x] **Increment 42 — Analog hierarchy and parameterized instances**",
             "- [ ] **Increment 42 — Analog hierarchy and parameterized instances**"),
            (CHECK.ROADMAP, "**Revision:** 1.57", "**Revision:** 1.55"),
            (CHECK.IMPLEMENTATION, "**Status:** Validated", "**Status:** Candidate"),
            (CHECK.AMENDMENT, "Increment 42 compiler profile is accepted",
             "Increment 42 compiler profile is pending"),
            (CHECK.CLOSURE, "```verilog", "```text"),
            (CHECK.CLOSURE, ".gain(rootGain)", ".gain(4)"),
            (CHECK.CLOSURE, "not independent OpenVAF", "independent OpenVAF"),
        )
        for relative, old, new in changes:
            with self.subTest(relative=relative, old=old):
                root = self.fixture()
                target = root / relative
                self.assertIn(old, target.read_text())
                target.write_text(target.read_text().replace(old, new, 1))
                self.rejected(root)

    def test_workflow_cannot_drop_target_production_or_retention(self):
        for token in ("Increment42MlirCheck", "nodal-gate-default",
                      "--nodal-to-verilog-a", "sha256sum",
                      "actions/upload-artifact", "contents: read"):
            with self.subTest(token=token):
                root = self.fixture()
                target = root / CHECK.WORKFLOW
                self.assertIn(token, target.read_text())
                target.write_text(target.read_text().replace(token, "removed"))
                self.rejected(root)


if __name__ == "__main__":
    unittest.main()
