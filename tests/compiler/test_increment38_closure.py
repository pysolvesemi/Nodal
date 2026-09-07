"""Mutation tests for Increment 38's separate, immutable acceptance record."""
from __future__ import annotations

import importlib.util
import json
import re
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("increment38_closure", ROOT / "scripts/check_increment38.py")
assert SPEC and SPEC.loader
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class Increment38ClosureTests(unittest.TestCase):
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
        with self.assertRaisesRegex(AssertionError, "NODAL-INC38:"):
            CHECK.check_repository(root)

    def test_checked_repository(self):
        CHECK.check_repository(ROOT)

    def test_accepted_identity_and_witness_mutations(self):
        mutations = [
            lambda x: x.update(accepted_head="0" * 40),
            lambda x: x.update(accepted_tree="0" * 40),
            lambda x: x.update(implementation_merge="0" * 40),
            lambda x: x.update(post_merge_core_ci_run=1),
            lambda x: x["source_witness_sha256"].update({"public.va": "0" * 64}),
            lambda x: x["independent_review"].update(result="pending"),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                root = self.fixture()
                self.json_change(root, CHECK.EVIDENCE, mutate)
                self.rejected(root)

    def test_missing_evidence_and_native_registration(self):
        root = self.fixture()
        (root / CHECK.EVIDENCE).unlink()
        self.rejected(root)
        root = self.fixture()
        path = root / "core/compiler/test/CMakeLists.txt"
        path.write_text(path.read_text().replace("increment38/run_native_matrix.py", "missing.py"))
        self.rejected(root)

    def test_manifest_reference_and_remaining_scope(self):
        for mutate in (lambda x: x["accepted_evidence"].update(sha256="0" * 64),
                       lambda x: x.update(remaining=["not validated"]),
                       lambda x: x["semantics"].update(dynamic_analysis_queries=False),
                       lambda x: x.update(deferred=[]),
                       lambda x: x.update(status="almost-done")):
            root = self.fixture()
            self.json_change(root, CHECK.MANIFEST, mutate)
            self.rejected(root)

    def test_predecessor_acceptance_cannot_be_rewritten(self):
        root = self.fixture()
        self.json_change(root, CHECK.PREDECESSOR, lambda x: x.update(accepted_head="0" * 40))
        self.rejected(root)
        root = self.fixture()
        self.json_change(root, "tests/compiler/fixtures/increment37/manifest.json",
                         lambda x: x.update(status="implementation-in-progress"))
        self.rejected(root)

    def test_open_state_requires_all_records_to_be_open(self):
        root = self.fixture()
        self.json_change(root, CHECK.MANIFEST, lambda x: x.update(
            status="implementation-in-progress", accepted_evidence=None, remaining=["validation"]))
        self.rejected(root)
        roadmap = root / CHECK.ROADMAP
        roadmap.write_text(roadmap.read_text().replace(CHECK.CLOSED, CHECK.OPEN))
        implementation = root / CHECK.IMPLEMENTATION
        implementation.write_text(implementation.read_text().replace(
            "**Status:** Validated", "**Status:** Implementation in progress"))
        CHECK.check_repository(root)

    def test_roadmap_and_human_evidence_must_agree(self):
        for relative, old, new in (
            (CHECK.ROADMAP, CHECK.CLOSED, CHECK.OPEN),
            (CHECK.IMPLEMENTATION, "**Status:** Validated", "**Status:** Candidate"),
            (CHECK.CLOSURE, "05047f4bb511ef19a812de6e8f08fc2e709cb5c8", "unknown-head"),
            (CHECK.CLOSURE, "```verilog", "```text"),
            (CHECK.CLOSURE, 'analysis("tran")', 'analysis("dc")'),
        ):
            root = self.fixture()
            path = root / relative
            self.assertIn(old, path.read_text())
            path.write_text(path.read_text().replace(old, new))
            self.rejected(root)

    def test_roadmap_revision_has_lower_bound_not_upper_bound(self):
        root = self.fixture()
        path = root / CHECK.ROADMAP
        text = path.read_text()
        path.write_text(re.sub(r"^\*\*Revision:\*\* .*", "**Revision:** 1.48", text, flags=re.M))
        self.rejected(root)
        path.write_text(re.sub(r"^\*\*Revision:\*\* .*", "**Revision:** 2.0", text, flags=re.M))
        CHECK.check_repository(root)

    def test_duplicate_roadmap_and_disabled_workflow(self):
        root = self.fixture()
        path = root / CHECK.ROADMAP
        path.write_text(path.read_text() + "\n" + CHECK.CLOSED + "\n")
        self.rejected(root)
        root = self.fixture()
        path = root / ".github/workflows/increment-38-mathematical-functions.yml"
        path.write_text(path.read_text().replace("contents: read", "contents: write"))
        self.rejected(root)


if __name__ == "__main__":
    unittest.main()
