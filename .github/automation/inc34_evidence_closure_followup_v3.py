#!/usr/bin/env python3
"""Add the canonical dedicated-boundary evidence field to Increment 34 closure."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

DEDICATED_BOUNDARY_RUN = 33732868285


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()

    manifest_path = root / "tests/compiler/fixtures/increment34/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validation = manifest.get("validation")
    if not isinstance(validation, dict):
        raise SystemExit("Increment 34 closure validation object is absent")
    validation["dedicated_boundary_workflow_run"] = DEDICATED_BOUNDARY_RUN
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    evidence_path = root / "docs/implementation/increment34-evidence-closure.md"
    evidence = evidence_path.read_text(encoding="utf-8")
    marker = "**Exact-head Core CI:** `33732864482`  \n"
    addition = marker + f"**Dedicated boundary workflow:** `{DEDICATED_BOUNDARY_RUN}`  \n"
    evidence = replace_once(evidence, marker, addition, "closure dedicated evidence")
    evidence_path.write_text(evidence, encoding="utf-8")

    roadmap_path = root / "docs/roadmap/nodal-development-todo.md"
    roadmap = roadmap_path.read_text(encoding="utf-8")
    old = "26-workflow exact-head matrix, Core CI run "
    new = (
        "26-workflow exact-head matrix, dedicated boundary run "
        f"[{DEDICATED_BOUNDARY_RUN}](https://github.com/pysolvesemi/Nodal/actions/runs/"
        f"{DEDICATED_BOUNDARY_RUN}), Core CI run "
    )
    roadmap = replace_once(roadmap, old, new, "roadmap dedicated evidence")
    roadmap_path.write_text(roadmap, encoding="utf-8")

    checker_path = root / "scripts/check_increment34.py"
    checker = checker_path.read_text(encoding="utf-8")
    checker = replace_once(
        checker,
        '''            "accepted_head",
            "final_review_head",
''',
        '''            "accepted_head",
            "dedicated_boundary_workflow_run",
            "final_review_head",
''',
        "Increment 34 dedicated required field",
    )
    checker = replace_once(
        checker,
        '''            and validation.get("final_review_head")
            == "54d8523715a86e1780263b6f5227def2f0977833"
''',
        '''            and validation.get("dedicated_boundary_workflow_run") == 33732868285
            and validation.get("final_review_head")
            == "54d8523715a86e1780263b6f5227def2f0977833"
''',
        "Increment 34 dedicated exact identity",
    )
    checker = replace_once(
        checker,
        '''            "**Accepted implementation head:** `207fd1b580e9428e9948cd4e4bd8f2060fde4b79`",
            "**Implementation merge:** `a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49`",
''',
        '''            "**Accepted implementation head:** `207fd1b580e9428e9948cd4e4bd8f2060fde4b79`",
            "**Dedicated boundary workflow:** `33732868285`",
            "**Implementation merge:** `a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49`",
''',
        "Increment 34 dedicated evidence token",
    )
    checker_path.write_text(checker, encoding="utf-8")

    predecessor_path = root / "scripts/check_increment33.py"
    predecessor = predecessor_path.read_text(encoding="utf-8")
    predecessor = replace_once(
        predecessor,
        '''                "accepted_head",
                "implementation_merge",
''',
        '''                "accepted_head",
                "dedicated_boundary_workflow_run",
                "implementation_merge",
''',
        "Increment 33 successor dedicated field",
    )
    predecessor = replace_once(
        predecessor,
        '''                and successor_validation.get("implementation_merge")
                == "a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49"
''',
        '''                and successor_validation.get("dedicated_boundary_workflow_run")
                == 33732868285
                and successor_validation.get("implementation_merge")
                == "a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49"
''',
        "Increment 33 successor dedicated identity",
    )
    predecessor_path.write_text(predecessor, encoding="utf-8")

    tests_path = root / "tests/compiler/test_increment34.py"
    tests = tests_path.read_text(encoding="utf-8")
    marker = '''    def test_baseline_head_mutation_is_rejected(self) -> None:
'''
    addition = '''    def test_dedicated_boundary_evidence_is_locked(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["validation"]["dedicated_boundary_workflow_run"] = 1
            path.write_text(json.dumps(document, indent=2) + "\\n", encoding="utf-8")
            self.assert_rejected(root, "does not match the accepted implementation")

'''
    if "test_dedicated_boundary_evidence_is_locked" not in tests:
        tests = replace_once(
            tests,
            marker,
            addition + marker,
            "dedicated boundary mutation test",
        )
    tests_path.write_text(tests, encoding="utf-8")

    print("Increment 34 dedicated boundary workflow evidence retained.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
