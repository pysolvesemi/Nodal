#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


IMPLEMENTATION_HEAD = "ea7f7da51e85ba275dac71db7823ba0223f8d4ac"
IMPLEMENTATION_MERGE = "2e0ff291b8d6c0f6dcc4b4c8e27cc33984cff1b8"
CLOSURE_PR = 110
EXACT_POSTMERGE_RUN = 33714669557


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"missing required file: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def validate_predecessor(root: Path, dev_head: str) -> dict:
    predecessor = json.loads(
        read(root / "tests/compiler/fixtures/increment33/manifest.json")
    )
    validation = predecessor.get("validation")
    required = (
        "implementation_pull_request",
        "accepted_head",
        "dedicated_boundary_workflow_run",
        "implementation_merge",
        "post_merge_core_ci_run",
        "exact_post_merge_validation_run",
        "closure_pull_request",
        "closure_validation_head",
        "closure_validation_run",
    )
    if (
        predecessor.get("increment") != 33
        or predecessor.get("status") != "validated-analog-procedural-assignment"
        or not isinstance(validation, dict)
        or any(not validation.get(field) for field in required)
    ):
        raise SystemExit("Increment 33 is not in the validated evidence-closure state")
    if (
        validation["implementation_pull_request"] != 102
        or validation["accepted_head"] != IMPLEMENTATION_HEAD
        or validation["implementation_merge"] != IMPLEMENTATION_MERGE
        or validation["exact_post_merge_validation_run"] != EXACT_POSTMERGE_RUN
        or validation["closure_pull_request"] != CLOSURE_PR
    ):
        raise SystemExit("Increment 33 evidence identities do not match the accepted predecessor")
    if not re.fullmatch(r"[0-9a-f]{40}", dev_head):
        raise SystemExit("validated predecessor dev head must be a 40-hex commit")
    roadmap = read(root / "docs/roadmap/nodal-development-todo.md")
    if (
        "**Revision:** 1.44" not in roadmap
        or "- [x] **Increment 33 — Analog variables and procedural assignment**"
        not in roadmap
        or "- [ ] **Increment 34 — Analog control flow**" not in roadmap
    ):
        raise SystemExit("roadmap does not contain the validated Increment 33 transition")
    return predecessor


def patch_manifest(root: Path, predecessor: dict, dev_head: str) -> None:
    path = root / "tests/compiler/fixtures/increment34/manifest.json"
    document = json.loads(read(path))
    validation = predecessor["validation"]
    document["baseline"] = {
        "stacked_on_increment": 33,
        "increment_33_head": IMPLEMENTATION_HEAD,
        "increment_33_manifest": predecessor["status"],
        "increment_33_implementation_merge": IMPLEMENTATION_MERGE,
        "increment_33_exact_post_merge_validation_run": EXACT_POSTMERGE_RUN,
        "increment_33_closure_pr": CLOSURE_PR,
        "increment_33_closure_validation_head": validation[
            "closure_validation_head"
        ],
        "increment_33_closure_validation_run": validation[
            "closure_validation_run"
        ],
        "increment_33_dev_head": dev_head,
        "roadmap_revision": "1.44",
    }
    write(path, json.dumps(document, indent=2) + "\n")


def patch_checker(root: Path) -> None:
    path = root / "scripts/check_increment34.py"
    text = read(path)
    old = '''    require(
        predecessor.get("increment") == 33
        and predecessor.get("status") == "implementation-in-progress",
        "NODAL-INC34-005: stacked Increment 33 manifest is not the implementation baseline",
    )
    baseline = manifest.get("baseline")
    require(isinstance(baseline, dict), "NODAL-INC34-006: baseline must be an object")
    require(
        baseline.get("stacked_on_increment") == 33
        and baseline.get("increment_33_head")
        == "ea7f7da51e85ba275dac71db7823ba0223f8d4ac"
        and baseline.get("increment_33_manifest") == "implementation-in-progress"
        and baseline.get("roadmap_revision") == "1.43",
        "NODAL-INC34-007: Increment 34 is not pinned to the accepted stacked baseline",
    )
'''
    new = '''    predecessor_validation = predecessor.get("validation")
    required_predecessor_evidence = (
        "implementation_pull_request",
        "accepted_head",
        "dedicated_boundary_workflow_run",
        "implementation_merge",
        "post_merge_core_ci_run",
        "exact_post_merge_validation_run",
        "closure_pull_request",
        "closure_validation_head",
        "closure_validation_run",
    )
    require(
        predecessor.get("increment") == 33
        and predecessor.get("status")
        == "validated-analog-procedural-assignment"
        and isinstance(predecessor_validation, dict)
        and all(
            predecessor_validation.get(field)
            for field in required_predecessor_evidence
        ),
        "NODAL-INC34-005: Increment 33 lacks validated predecessor evidence",
    )
    require(
        predecessor_validation.get("implementation_pull_request") == 102
        and predecessor_validation.get("accepted_head")
        == "ea7f7da51e85ba275dac71db7823ba0223f8d4ac"
        and predecessor_validation.get("implementation_merge")
        == "2e0ff291b8d6c0f6dcc4b4c8e27cc33984cff1b8"
        and predecessor_validation.get("exact_post_merge_validation_run")
        == 33714669557
        and predecessor_validation.get("closure_pull_request") == 110,
        "NODAL-INC34-005: Increment 33 predecessor evidence does not match the accepted implementation",
    )
    baseline = manifest.get("baseline")
    require(isinstance(baseline, dict), "NODAL-INC34-006: baseline must be an object")
    require(
        baseline.get("stacked_on_increment") == 33
        and baseline.get("increment_33_head")
        == predecessor_validation.get("accepted_head")
        and baseline.get("increment_33_manifest") == predecessor.get("status")
        and baseline.get("increment_33_implementation_merge")
        == predecessor_validation.get("implementation_merge")
        and baseline.get("increment_33_exact_post_merge_validation_run")
        == predecessor_validation.get("exact_post_merge_validation_run")
        and baseline.get("increment_33_closure_pr")
        == predecessor_validation.get("closure_pull_request")
        and baseline.get("increment_33_closure_validation_head")
        == predecessor_validation.get("closure_validation_head")
        and baseline.get("increment_33_closure_validation_run")
        == predecessor_validation.get("closure_validation_run")
        and isinstance(baseline.get("increment_33_dev_head"), str)
        and len(baseline.get("increment_33_dev_head")) == 40
        and all(
            character in "0123456789abcdef"
            for character in baseline.get("increment_33_dev_head")
        )
        and baseline.get("roadmap_revision") == "1.44",
        "NODAL-INC34-007: Increment 34 is not pinned to the validated Increment 33 baseline",
    )
    require(
        "**Revision:** 1.44" in roadmap
        and "- [x] **Increment 33 — Analog variables and procedural assignment**"
        in roadmap,
        "NODAL-INC34-007: roadmap does not contain the validated Increment 33 predecessor",
    )
'''
    text = replace_once(text, old, new, "Increment 34 predecessor checker")
    write(path, text)


def patch_tests(root: Path) -> None:
    path = root / "tests/compiler/test_increment34.py"
    text = read(path)
    text = text.replace(
        'self.assert_rejected(root, "accepted stacked baseline")',
        'self.assert_rejected(root, "validated Increment 33 baseline")',
        1,
    )
    methods = '''    def test_unvalidated_predecessor_manifest_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment33/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["status"] = "implementation-in-progress"
            document["validation"] = None
            path.write_text(json.dumps(document, indent=2) + "\\n", encoding="utf-8")
            self.assert_rejected(root, "lacks validated predecessor evidence")

    def test_predecessor_closure_identity_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["baseline"]["increment_33_closure_pr"] = 999
            path.write_text(json.dumps(document, indent=2) + "\\n", encoding="utf-8")
            self.assert_rejected(root, "validated Increment 33 baseline")

    def test_predecessor_roadmap_regression_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "docs/roadmap/nodal-development-todo.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "- [x] **Increment 33 — Analog variables and procedural assignment**",
                    "- [ ] **Increment 33 — Analog variables and procedural assignment**",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "roadmap does not contain the validated")

'''
    marker = "    def test_branch_intersection_mutation_is_rejected(self) -> None:\n"
    if methods.strip() not in text:
        text = replace_once(text, marker, methods + marker, "predecessor mutation tests")
    write(path, text)


def patch_docs(root: Path, predecessor: dict, dev_head: str) -> None:
    path = root / "docs/implementation/increment34-analog-control-flow.md"
    text = read(path)
    validation = predecessor["validation"]
    old = '''**Baseline:** stacked on Increment 33 head
`ea7f7da51e85ba275dac71db7823ba0223f8d4ac`
'''
    new = f'''**Baseline:** validated Increment 33 implementation head
`{IMPLEMENTATION_HEAD}`, merged as `{IMPLEMENTATION_MERGE}` and closed by
PR #{CLOSURE_PR}; synchronized `dev` head `{dev_head}`.
'''
    text = replace_once(text, old, new, "Increment 34 baseline documentation")
    marker = "## Objective\n"
    note = f'''## Predecessor synchronization

Increment 34 is validated against roadmap revision 1.44 and the completed
Increment 33 evidence state. The predecessor record retains exact post-merge run
`{EXACT_POSTMERGE_RUN}`, closure PR #{CLOSURE_PR}, closure validation head
`{validation['closure_validation_head']}`, and closure validation run
`{validation['closure_validation_run']}`. Increment 34 itself remains unchecked
until its separate evidence closure.

'''
    if note.strip() not in text:
        text = replace_once(text, marker, note + marker, "predecessor synchronization note")
    write(path, text)

    readme_path = root / "tests/compiler/fixtures/increment34/README.md"
    readme = read(readme_path)
    note = '''
The Increment 34 checkpoint is pinned to the validated Increment 33 evidence
state and roadmap revision 1.44. Increment 34 remains open until its own
implementation merge, post-merge validation, and separate evidence closure.
'''
    if note.strip() not in readme:
        readme = readme.rstrip() + "\n" + note
    write(readme_path, readme)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--dev-head", required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    predecessor = validate_predecessor(root, args.dev_head)
    patch_manifest(root, predecessor, args.dev_head)
    patch_checker(root)
    patch_tests(root)
    patch_docs(root, predecessor, args.dev_head)
    print("Increment 34 synchronized to the validated Increment 33 baseline.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
