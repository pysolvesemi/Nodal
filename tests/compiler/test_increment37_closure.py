"""Mutation tests for exact event acceptance and successor-safe predecessor checks."""
from __future__ import annotations

import importlib.util
import json
import re
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(number: int):
    spec = importlib.util.spec_from_file_location(f"closure{number}", ROOT / f"scripts/check_increment{number}.py")
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CHECK = load(37)
PREDECESSOR = load(36)
MANIFEST = "tests/compiler/fixtures/increment37/manifest.json"
EVIDENCE = "docs/implementation/increment37-accepted-evidence.json"
ROADMAP = "docs/roadmap/nodal-development-todo.md"
IMPLEMENTATION = "docs/implementation/increment37-analog-events.md"


class Increment37ClosureTests(unittest.TestCase):
    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in set(CHECK.REQUIRED) | set(PREDECESSOR.FILES):
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)
        self.addCleanup(temporary.cleanup)
        return root

    def mutate_manifest(self, root, change):
        path = root / MANIFEST
        document = json.loads(path.read_text())
        change(document)
        path.write_text(json.dumps(document, indent=2) + "\n")

    def reopen(self, root):
        self.mutate_manifest(root, lambda data: data.update(
            status="implementation-in-progress", validation=None,
            remaining=["exact-head-ci-and-evidence-closure"]))
        path = root / ROADMAP
        path.write_text(path.read_text().replace(CHECK.CLOSED, CHECK.OPEN))
        path = root / IMPLEMENTATION
        path.write_text(path.read_text().replace("**Status:** Validated compiler/event profile",
                                              "**Status:** Implementation in progress"))

    def test_accepted_repository_and_predecessor(self):
        CHECK.check_repository(ROOT)
        PREDECESSOR.check_repository(ROOT)

    def test_open_state_preserves_no_acceptance_claim(self):
        root = self.fixture()
        self.reopen(root)
        CHECK.check_repository(root)
        PREDECESSOR.check_repository(root)
        self.mutate_manifest(root, lambda data: data.update(validation={"accepted_head": "0" * 40}))
        with self.assertRaisesRegex(AssertionError, "cannot claim accepted"):
            CHECK.check_repository(root)

    def test_rejects_each_missing_evidence_field(self):
        accepted = json.loads((ROOT / EVIDENCE).read_text())
        for field in accepted:
            with self.subTest(field=field):
                root = self.fixture()
                self.mutate_manifest(root, lambda data: data["validation"].pop(field))
                with self.assertRaisesRegex(AssertionError, "evidence differs"):
                    CHECK.check_repository(root)

    def test_all_exact_workflow_identities_are_pinned(self):
        accepted = json.loads((ROOT / EVIDENCE).read_text())
        self.assertEqual(len(accepted["exact_head_workflows"]), 27)
        for name in accepted["exact_head_workflows"]:
            with self.subTest(workflow=name):
                root = self.fixture()
                self.mutate_manifest(root, lambda data: data["validation"]["exact_head_workflows"].update({name: 1}))
                with self.assertRaisesRegex(AssertionError, "evidence differs"):
                    CHECK.check_repository(root)

    def test_coordinated_evidence_replacement_is_rejected_by_both_checkers(self):
        mutations = (
            lambda data: data.update(accepted_head="0" * 40),
            lambda data: data.update(implementation_merge="0" * 40),
            lambda data: data.update(post_merge_core_ci_run=1),
            lambda data: data.update(post_merge_increment37_run=1),
            lambda data: data.update(post_merge_witness_matches_accepted=False),
            lambda data: data["exact_head_workflows"].update({"Core CI": 1}),
            lambda data: data["source_witness_sha256"].update({"increment37-source.mlir": "0" * 64}),
        )
        for index, change in enumerate(mutations):
            with self.subTest(mutation=index):
                root = self.fixture()
                for relative in (MANIFEST, EVIDENCE):
                    path = root / relative
                    document = json.loads(path.read_text())
                    change(document.get("validation", document))
                    path.write_text(json.dumps(document, indent=2) + "\n")
                with self.assertRaisesRegex(AssertionError, "checksum changed"):
                    CHECK.check_repository(root)
                with self.assertRaisesRegex(PREDECESSOR.CheckFailure, "checksum changed"):
                    PREDECESSOR.check_repository(root)

    def test_closed_successor_requires_accepted_manifest(self):
        root = self.fixture()
        self.mutate_manifest(root, lambda data: data.update(status="implementation-in-progress", validation=None))
        with self.assertRaisesRegex(PREDECESSOR.CheckFailure, "successor closure lacks"):
            PREDECESSOR.check_repository(root)

    def test_each_semantic_obligation_is_mandatory(self):
        for name in CHECK.SEMANTICS:
            with self.subTest(semantics=name):
                root = self.fixture()
                self.mutate_manifest(root, lambda data: data["semantics"].update({name: False}))
                with self.assertRaisesRegex(AssertionError, "semantic obligation"):
                    CHECK.check_repository(root)

    def test_unknown_states_and_outstanding_work_are_rejected(self):
        for state in (None, False, {}, [], "complete", "evidence-closure-candidate"):
            with self.subTest(state=state):
                root = self.fixture()
                self.mutate_manifest(root, lambda data: data.update(status=state))
                with self.assertRaisesRegex(AssertionError, "unknown implementation state"):
                    CHECK.check_repository(root)
        root = self.fixture()
        self.mutate_manifest(root, lambda data: data.update(remaining=["unverified"]))
        with self.assertRaisesRegex(AssertionError, "outstanding scope"):
            CHECK.check_repository(root)

    def test_revision_is_unique_valid_and_monotonic(self):
        for replacement in ("", "**Revision:** malformed", "**Revision:** 1.47",
                            "**Revision:** 1.48\n**Revision:** 1.48",
                            "**Revision:** 1.48\n**Revision:** malformed"):
            with self.subTest(revision=replacement):
                root = self.fixture()
                path = root / ROADMAP
                old = re.search(r"^\*\*Revision:\*\* .*?$", path.read_text(), re.M).group()
                path.write_text(path.read_text().replace(old, replacement, 1))
                with self.assertRaisesRegex(AssertionError, "revision"):
                    CHECK.check_repository(root)
        root = self.fixture()
        path = root / ROADMAP
        path.write_text(re.sub(r"^\*\*Revision:\*\* .*?$", "**Revision:** 2.0", path.read_text(), count=1, flags=re.M))
        CHECK.check_repository(root)

    def test_roadmap_and_human_evidence_cannot_diverge(self):
        for replacement in (CHECK.OPEN, "", CHECK.CLOSED + "\n" + CHECK.OPEN):
            root = self.fixture()
            path = root / ROADMAP
            path.write_text(path.read_text().replace(CHECK.CLOSED, replacement, 1))
            with self.assertRaises(AssertionError):
                CHECK.check_repository(root)
        root = self.fixture()
        path = root / "docs/implementation/increment37-evidence-closure.md"
        accepted = json.loads((root / EVIDENCE).read_text())
        path.write_text(path.read_text().replace(accepted["implementation_merge"], "0" * 40))
        with self.assertRaisesRegex(AssertionError, "record omits"):
            CHECK.check_repository(root)

    def test_native_direction_gate_and_deferred_boundaries_are_required(self):
        root = self.fixture()
        path = root / "core/compiler/test/CMakeLists.txt"
        path.write_text(path.read_text().replace("increment37/run_direction_matrix.py", "missing.py"))
        with self.assertRaisesRegex(AssertionError, "registration"):
            CHECK.check_repository(root)
        root = self.fixture()
        self.mutate_manifest(root, lambda data: data.update(deferred=[]))
        with self.assertRaisesRegex(AssertionError, "deferred capability"):
            CHECK.check_repository(root)


if __name__ == "__main__":
    unittest.main()
