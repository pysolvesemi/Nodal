"""Mutation tests for Foundation 160 accepted-evidence closure."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "check_increment160", ROOT / "scripts/check_increment160.py")
assert SPEC and SPEC.loader
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)

FILES = (
    CHECK.EVIDENCE,
    CHECK.CLOSURE,
    CHECK.READINESS,
    CHECK.PLAN,
    CHECK.ROADMAP,
    CHECK.MANIFEST,
    CHECK.EXPERIMENT,
    CHECK.SCALA_SOURCE,
)


class Increment160ClosureTests(unittest.TestCase):
    def fixture(self) -> Path:
        temporary = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, temporary)
        for relative in FILES:
            destination = temporary / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, destination)
        return temporary

    def rejected(self, root: Path) -> None:
        with self.assertRaises(RuntimeError):
            CHECK.check_repository(root)

    def rewrite_json(self, root: Path, relative: str, mutate) -> None:
        path = root / relative
        document = json.loads(path.read_text())
        mutate(document)
        path.write_text(json.dumps(document, indent=2) + "\n")

    def rehash_manifest_entry(self, root: Path, entry: str) -> None:
        manifest_path = root / CHECK.MANIFEST
        manifest = json.loads(manifest_path.read_text())
        target = root / manifest[entry]["path"]
        manifest[entry]["sha256"] = hashlib.sha256(target.read_bytes()).hexdigest()
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")

    def test_current_repository_passes(self):
        CHECK.check_repository(ROOT)

    def test_evidence_digest_is_bound(self):
        root = self.fixture()
        path = root / CHECK.EVIDENCE
        path.write_text(path.read_text().replace('"status": "validated"',
                                                 '"status": "changed"', 1))
        self.rejected(root)

    def test_semantic_evidence_mutation_is_rejected_after_rehash(self):
        root = self.fixture()
        self.rewrite_json(
            root, CHECK.EVIDENCE,
            lambda value: value["final_parity"].__setitem__("case_count", 54))
        self.rehash_manifest_entry(root, "accepted_evidence")
        self.rejected(root)

    def test_merge_tree_cannot_differ_from_qualified_tree(self):
        root = self.fixture()
        self.rewrite_json(
            root, CHECK.EVIDENCE,
            lambda value: value.__setitem__(
                "implementation_merge_tree", "0" * 40))
        self.rehash_manifest_entry(root, "accepted_evidence")
        self.rejected(root)

    def test_post_merge_skip_cannot_be_relabelled_pass(self):
        root = self.fixture()
        self.rewrite_json(
            root, CHECK.EVIDENCE,
            lambda value: value["post_merge_ci"].__setitem__("status", "passed"))
        self.rehash_manifest_entry(root, "accepted_evidence")
        self.rejected(root)

    def test_capability_overclaim_is_rejected(self):
        root = self.fixture()
        self.rewrite_json(
            root, CHECK.EVIDENCE,
            lambda value: value["capability_claims"].__setitem__(
                "numerical_simulator_execution", True))
        self.rehash_manifest_entry(root, "accepted_evidence")
        self.rejected(root)

    def test_experiment_bytes_are_immutable(self):
        root = self.fixture()
        path = root / CHECK.EXPERIMENT
        path.write_text(path.read_text() + "\n")
        self.rejected(root)

    def test_public_scala_demonstration_is_source_bound(self):
        root = self.fixture()
        path = root / CHECK.SCALA_SOURCE
        path.write_text(path.read_text().replace("on(initialStep)", "on(initialStepChanged)", 1))
        self.rejected(root)

    def test_verilog_demonstration_cannot_change_after_report_rehash(self):
        root = self.fixture()
        path = root / CHECK.CLOSURE
        path.write_text(path.read_text().replace("@(initial_step)", "@(final_step)", 1))
        self.rehash_manifest_entry(root, "closure_report")
        self.rejected(root)

    def test_all_twenty_boxes_must_stay_closed(self):
        root = self.fixture()
        path = root / CHECK.PLAN
        path.write_text(path.read_text().replace(
            "- [x] **F-160.G.2**", "- [ ] **F-160.G.2**", 1))
        self.rejected(root)

    def test_increment43_must_remain_open_and_unstarted(self):
        root = self.fixture()
        path = root / CHECK.ROADMAP
        path.write_text(path.read_text().replace(
            "- [ ] **Increment 43", "- [x] **Increment 43", 1))
        self.rejected(root)


if __name__ == "__main__":
    unittest.main()

