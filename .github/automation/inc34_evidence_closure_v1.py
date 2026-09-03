#!/usr/bin/env python3
"""Materialize the separate Increment 34 evidence-only closure."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

IMPLEMENTATION_PR = 109
ACCEPTED_HEAD = "207fd1b580e9428e9948cd4e4bd8f2060fde4b79"
FINAL_REVIEW_HEAD = "54d8523715a86e1780263b6f5227def2f0977833"
EXACT_HEAD_WORKFLOW_COUNT = 26
EXACT_HEAD_CORE_CI_RUN = 33732864482
IMPLEMENTATION_MERGE = "a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49"
POST_MERGE_CORE_CI_RUN = 33758905273
EXACT_POST_MERGE_VALIDATION_RUN = 33759112770
CLOSURE_PR = 111


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


def replace_method(text: str, name: str, replacement: str) -> str:
    start_marker = f"    def {name}(self) -> None:\n"
    start = text.find(start_marker)
    if start < 0:
        if replacement.strip() in text:
            return text
        raise SystemExit(f"missing method: {name}")
    next_method = text.find("\n    def ", start + len(start_marker))
    if next_method < 0:
        next_method = text.find("\n\nif __name__", start)
    if next_method < 0:
        raise SystemExit(f"cannot find end of method: {name}")
    return text[:start] + replacement.rstrip() + "\n" + text[next_method + 1 :]


def patch_manifest(root: Path) -> None:
    path = root / "tests/compiler/fixtures/increment34/manifest.json"
    document = json.loads(read(path))
    if document.get("increment") != 34:
        raise SystemExit("Increment 34 manifest identity is invalid")
    document["status"] = "validated-analog-control-flow"
    document["tranche"] = "34d-closure"
    document["validation"] = {
        "implementation_pull_request": IMPLEMENTATION_PR,
        "accepted_head": ACCEPTED_HEAD,
        "final_review_head": FINAL_REVIEW_HEAD,
        "exact_head_workflow_count": EXACT_HEAD_WORKFLOW_COUNT,
        "exact_head_core_ci_run": EXACT_HEAD_CORE_CI_RUN,
        "implementation_merge": IMPLEMENTATION_MERGE,
        "post_merge_core_ci_run": POST_MERGE_CORE_CI_RUN,
        "exact_post_merge_validation_run": EXACT_POST_MERGE_VALIDATION_RUN,
        "closure_pull_request": CLOSURE_PR,
        "closure_validation_head": "0" * 40,
        "closure_validation_run": 0,
    }
    write(path, json.dumps(document, indent=2) + "\n")


def patch_roadmap(root: Path) -> None:
    path = root / "docs/roadmap/nodal-development-todo.md"
    text = read(path)
    text = replace_once(
        text,
        "**Revision:** 1.44",
        "**Revision:** 1.45",
        "roadmap revision",
    )
    open_item = "- [ ] **Increment 34 — Analog control flow**"
    closed_item = "- [x] **Increment 34 — Analog control flow**"
    text = replace_once(text, open_item, closed_item, "Increment 34 roadmap state")
    description = (
        "  - Implement conditionals, case, bounded loops, break/continue where "
        "supported, and static/runtime legality.\n"
    )
    evidence = (
        "\n  - Evidence: implementation PR [#109](https://github.com/pysolvesemi/Nodal/pull/109), "
        "accepted head [`207fd1b5`](https://github.com/pysolvesemi/Nodal/commit/"
        f"{ACCEPTED_HEAD}), 26-workflow exact-head matrix, Core CI run "
        f"[{EXACT_HEAD_CORE_CI_RUN}](https://github.com/pysolvesemi/Nodal/actions/runs/"
        f"{EXACT_HEAD_CORE_CI_RUN}), merge commit [`a9d3ec50`](https://github.com/"
        f"pysolvesemi/Nodal/commit/{IMPLEMENTATION_MERGE}), post-merge Core CI run "
        f"[{POST_MERGE_CORE_CI_RUN}](https://github.com/pysolvesemi/Nodal/actions/runs/"
        f"{POST_MERGE_CORE_CI_RUN}), exact post-merge validation run "
        f"[{EXACT_POST_MERGE_VALIDATION_RUN}](https://github.com/pysolvesemi/Nodal/"
        f"actions/runs/{EXACT_POST_MERGE_VALIDATION_RUN}), and evidence closure PR "
        f"[#{CLOSURE_PR}](https://github.com/pysolvesemi/Nodal/pull/{CLOSURE_PR}).\n"
    )
    if "evidence closure PR [#111]" not in text:
        text = replace_once(
            text,
            closed_item + "\n" + description,
            closed_item + "\n" + description + evidence,
            "Increment 34 roadmap evidence",
        )
    write(path, text)


def patch_implementation(root: Path) -> None:
    path = root / "docs/implementation/increment34-analog-control-flow.md"
    text = read(path)
    text = replace_once(
        text,
        "**Status:** In progress",
        "**Status:** Validated",
        "implementation status",
    )
    replacements = {
        "- [ ] Pass the exact-head Increment 34 workflow and all inherited workflows.":
            "- [x] Pass the exact-head Increment 34 workflow and all inherited workflows.",
        "- [ ] Complete review of the public-construction exact head.":
            "- [x] Complete review of the public-construction exact head.",
        "- [ ] Complete deterministic reproducibility serialization.":
            "- [x] Complete deterministic reproducibility serialization.",
        "- [ ] Run the full inherited workflow matrix on one exact head.":
            "- [x] Run the full inherited workflow matrix on one exact head.",
        "- [ ] Perform a fresh review and repair all findings.":
            "- [x] Perform a fresh review and repair all findings.",
        "- [ ] Merge after Increment 33 is closed.":
            "- [x] Merge after Increment 33 is closed.",
        "- [ ] Run post-merge Core CI and dedicated Increment 34 validation.":
            "- [x] Run post-merge Core CI and dedicated Increment 34 validation.",
        "- [ ] Record immutable evidence and mark the roadmap item complete in a separate\n  closure pull request.":
            "- [x] Record immutable evidence and mark the roadmap item complete in a separate\n  closure pull request.",
    }
    for old, new in replacements.items():
        text = replace_once(text, old, new, f"implementation checklist: {old}")
    boundary = (
        "It does not yet:\n\n"
        "- complete the exact-head inherited workflow matrix and fresh review;\n"
        "- legalize or emit procedural target HDL;\n"
        "- form solver equations, residuals, or executable analysis schedules.\n\n"
        "Those items remain active Increment 34 work, not evidence gaps claimed as\n"
        "complete behavior."
    )
    replacement = (
        "The Increment 34 source, construction, bridge, native-IR, verifier, diagnostics,\n"
        "source-map, deterministic-serialization, and evidence obligations are complete.\n\n"
        "Procedural target-HDL legalization and emission, residual/DAE construction, solver\n"
        "execution, and executable analysis scheduling remain assigned to later increments."
    )
    if boundary in text:
        text = text.replace(boundary, replacement, 1)
    write(path, text)


def patch_exact_head_record(root: Path) -> None:
    path = root / "docs/implementation/increment34-exact-head-validation.md"
    text = read(path)
    text = replace_once(
        text,
        "**Status:** Final reviewed implementation; owner-authored exact-head matrix in progress",
        "**Status:** Accepted implementation; exact-head and post-merge validation complete",
        "exact-head record status",
    )
    marker = f"**Final reviewed implementation head:** `{FINAL_REVIEW_HEAD}`\n"
    addition = marker + (
        f"**Accepted exact head:** `{ACCEPTED_HEAD}`  \n"
        f"**Exact-head workflow count:** {EXACT_HEAD_WORKFLOW_COUNT} successful workflows  \n"
        f"**Exact-head Core CI:** `{EXACT_HEAD_CORE_CI_RUN}`  \n"
        f"**Implementation merge:** `{IMPLEMENTATION_MERGE}`  \n"
        f"**Post-merge Core CI:** `{POST_MERGE_CORE_CI_RUN}`  \n"
        f"**Exact post-merge validation:** `{EXACT_POST_MERGE_VALIDATION_RUN}`\n"
    )
    text = replace_once(text, marker, addition, "exact-head accepted evidence")
    old_tail = (
        "Increment 34 remains unchecked until implementation merge, post-merge\n"
        "validation, and a separate evidence-closure pull request have completed."
    )
    new_tail = (
        "Implementation PR #109 was squash-merged after the accepted exact-head matrix.\n"
        "Post-merge Core CI and the independent Increment 34 validator then passed on the\n"
        "exact merge commit. Separate evidence closure PR #111 records the final roadmap\n"
        "and manifest transition."
    )
    text = replace_once(text, old_tail, new_tail, "exact-head record closure note")
    write(path, text)


def patch_increment34_checker(root: Path) -> None:
    path = root / "scripts/check_increment34.py"
    text = read(path)
    old = '''    require(
        "- [ ] **Increment 34 — Analog control flow**" in roadmap
        and "- [x] **Increment 34 — Analog control flow**" not in roadmap,
        "NODAL-INC34-004: Increment 34 must remain unchecked until evidence closure",
    )
    predecessor_validation = predecessor.get("validation")
'''
    new = '''    status = manifest.get("status")
    validated = status == "validated-analog-control-flow"
    increment34_open = "- [ ] **Increment 34 — Analog control flow**"
    increment34_closed = "- [x] **Increment 34 — Analog control flow**"
    require(
        (increment34_open in roadmap) != (increment34_closed in roadmap),
        "NODAL-INC34-004: Increment 34 roadmap state is missing or ambiguous",
    )
    if validated:
        require(
            "**Revision:** 1.45" in roadmap and increment34_closed in roadmap,
            "NODAL-INC34-004: validated Increment 34 requires roadmap revision 1.45 and a checked item",
        )
    else:
        require(
            "**Revision:** 1.44" in roadmap and increment34_open in roadmap,
            "NODAL-INC34-004: unvalidated Increment 34 must remain open on roadmap revision 1.44",
        )
    predecessor_validation = predecessor.get("validation")
'''
    text = replace_once(text, old, new, "Increment 34 roadmap state checker")

    old = '''    require(
        manifest.get("schema") == 1
        and manifest.get("increment") == 34
        and manifest.get("status") == "implementation-in-progress"
        and manifest.get("tranche") == "34c-native-branch-sensitive-dataflow",
        "NODAL-INC34-008: manifest identity or tranche is invalid",
    )
'''
    new = '''    require(
        manifest.get("schema") == 1
        and manifest.get("increment") == 34
        and status
        in {"implementation-in-progress", "validated-analog-control-flow"},
        "NODAL-INC34-008: manifest identity or status is invalid",
    )
    if validated:
        require(
            manifest.get("tranche") == "34d-closure",
            "NODAL-INC34-008: validated Increment 34 must use the 34d closure tranche",
        )
    else:
        require(
            manifest.get("tranche") == "34c-native-branch-sensitive-dataflow",
            "NODAL-INC34-008: open Increment 34 must use the 34c implementation tranche",
        )
'''
    text = replace_once(text, old, new, "Increment 34 manifest state checker")

    old = '''    require(manifest.get("validation") is None, "NODAL-INC34-015: validation must remain null")

    require_tokens(
'''
    new = '''    validation = manifest.get("validation")
    if validated:
        required_validation = (
            "implementation_pull_request",
            "accepted_head",
            "final_review_head",
            "exact_head_workflow_count",
            "exact_head_core_ci_run",
            "implementation_merge",
            "post_merge_core_ci_run",
            "exact_post_merge_validation_run",
            "closure_pull_request",
            "closure_validation_head",
            "closure_validation_run",
        )
        require(
            isinstance(validation, dict)
            and all(validation.get(field) for field in required_validation),
            "NODAL-INC34-046: validated manifest lacks complete closure evidence",
        )
        closure_head = validation.get("closure_validation_head")
        require(
            validation.get("implementation_pull_request") == 109
            and validation.get("accepted_head")
            == "207fd1b580e9428e9948cd4e4bd8f2060fde4b79"
            and validation.get("final_review_head")
            == "54d8523715a86e1780263b6f5227def2f0977833"
            and validation.get("exact_head_workflow_count") == 26
            and validation.get("exact_head_core_ci_run") == 33732864482
            and validation.get("implementation_merge")
            == "a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49"
            and validation.get("post_merge_core_ci_run") == 33758905273
            and validation.get("exact_post_merge_validation_run") == 33759112770
            and validation.get("closure_pull_request") == 111
            and isinstance(closure_head, str)
            and len(closure_head) == 40
            and all(character in "0123456789abcdef" for character in closure_head)
            and isinstance(validation.get("closure_validation_run"), int)
            and validation.get("closure_validation_run") > 0,
            "NODAL-INC34-046: closure evidence does not match the accepted implementation",
        )
        evidence = read_text(
            root, "docs/implementation/increment34-evidence-closure.md"
        )
        for token in (
            "**Implementation PR:** #109",
            "**Accepted implementation head:** `207fd1b580e9428e9948cd4e4bd8f2060fde4b79`",
            "**Implementation merge:** `a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49`",
            "**Post-merge Core CI:** `33758905273`",
            "**Exact post-merge validation:** `33759112770`",
            "**Closure PR:** #111",
            f"**Closure validation head:** `{closure_head}`",
            f"**Closure validation run:** `{validation['closure_validation_run']}`",
        ):
            require(
                token in evidence,
                f"NODAL-INC34-047: evidence-closure record is missing {token!r}",
            )
    else:
        require(
            validation is None,
            "NODAL-INC34-015: validation must remain null before evidence closure",
        )

    expected_implementation_status = (
        "**Status:** Validated" if validated else "**Status:** In progress"
    )

    require_tokens(
'''
    text = replace_once(text, old, new, "Increment 34 closure evidence checker")
    text = replace_once(
        text,
        '''            "**Status:** In progress",
''',
        '''            expected_implementation_status,
''',
        "Increment 34 implementation status token",
    )
    write(path, text)


def patch_increment33_checker(root: Path) -> None:
    path = root / "scripts/check_increment33.py"
    text = read(path)
    old = '''    predecessor = read_json(root, "tests/compiler/fixtures/increment32/manifest.json")
'''
    new = old + '''    successor = read_json(root, "tests/compiler/fixtures/increment34/manifest.json")
'''
    text = replace_once(text, old, new, "Increment 33 successor manifest load")

    old = '''    increment34_open = "- [ ] **Increment 34 — Analog control flow**"
'''
    new = old + '''    increment34_closed = "- [x] **Increment 34 — Analog control flow**"
'''
    text = replace_once(text, old, new, "Increment 33 successor roadmap symbols")

    old = '''    else:
        require(
            "**Revision:** 1.44" in roadmap and increment33_closed in roadmap,
            "NODAL-INC33-027: validated state requires roadmap revision 1.44 with Increment 33 closed",
        )
        require(
            increment34_open in roadmap,
            "NODAL-INC33-029: Increment 34 must remain unchecked during Increment 33 closure",
        )
        evidence = read_text(
'''
    new = '''    else:
        successor_status = successor.get("status")
        if successor_status == "implementation-in-progress":
            require(
                "**Revision:** 1.44" in roadmap
                and increment33_closed in roadmap
                and increment34_open in roadmap,
                "NODAL-INC33-027: open Increment 34 requires roadmap revision 1.44",
            )
        elif successor_status == "validated-analog-control-flow":
            successor_validation = successor.get("validation")
            required_successor_evidence = (
                "implementation_pull_request",
                "accepted_head",
                "implementation_merge",
                "post_merge_core_ci_run",
                "exact_post_merge_validation_run",
                "closure_pull_request",
                "closure_validation_head",
                "closure_validation_run",
            )
            require(
                isinstance(successor_validation, dict)
                and all(
                    successor_validation.get(field)
                    for field in required_successor_evidence
                )
                and successor_validation.get("implementation_pull_request") == 109
                and successor_validation.get("accepted_head")
                == "207fd1b580e9428e9948cd4e4bd8f2060fde4b79"
                and successor_validation.get("implementation_merge")
                == "a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49"
                and successor_validation.get("closure_pull_request") == 111,
                "NODAL-INC33-029: validated Increment 34 lacks complete evidence",
            )
            require(
                "**Revision:** 1.45" in roadmap
                and increment33_closed in roadmap
                and increment34_closed in roadmap,
                "NODAL-INC33-029: validated Increment 34 must be checked on roadmap revision 1.45",
            )
        else:
            raise CheckFailure(
                f"NODAL-INC33-029: unsupported Increment 34 successor status: {successor_status}"
            )
        evidence = read_text(
'''
    text = replace_once(text, old, new, "Increment 33 successor-aware roadmap checker")
    write(path, text)


def patch_increment34_tests(root: Path) -> None:
    path = root / "tests/compiler/test_increment34.py"
    text = read(path)
    marker = '''    "docs/implementation/increment34-analog-control-flow.md",
'''
    addition = marker + '''    "docs/implementation/increment34-evidence-closure.md",
'''
    text = replace_once(text, marker, addition, "Increment 34 evidence test fixture")

    replacement = '''    def test_validated_roadmap_reopen_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "docs/roadmap/nodal-development-todo.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "- [x] **Increment 34 — Analog control flow**",
                    "- [ ] **Increment 34 — Analog control flow**",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "validated Increment 34 requires roadmap")
'''
    text = replace_method(
        text,
        "test_premature_roadmap_closure_is_rejected",
        replacement,
    )

    marker = '''    def test_baseline_head_mutation_is_rejected(self) -> None:
'''
    addition = '''    def test_validated_closure_requires_complete_evidence(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["validation"]["closure_validation_run"] = None
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "lacks complete closure evidence")

    def test_validated_closure_identity_is_locked(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["validation"]["implementation_merge"] = "0" * 40
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "does not match the accepted implementation")

'''
    if "test_validated_closure_requires_complete_evidence" not in text:
        text = replace_once(
            text,
            marker,
            addition + marker,
            "Increment 34 closure mutation tests",
        )
    write(path, text)


def patch_increment33_tests(root: Path) -> None:
    path = root / "tests/compiler/test_increment33.py"
    text = read(path)
    marker = '''    "tests/compiler/fixtures/increment33/manifest.json",
'''
    addition = marker + '''    "tests/compiler/fixtures/increment34/manifest.json",
'''
    text = replace_once(text, marker, addition, "Increment 33 successor test fixture")

    premature = '''    def test_premature_roadmap_closure_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            manifest_path = root / "tests/compiler/fixtures/increment33/manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["status"] = "implementation-in-progress"
            manifest["validation"] = None
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )
            path = root / "docs/roadmap/nodal-development-todo.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "**Revision:** 1.45", "**Revision:** 1.43", 1
                ),
                encoding="utf-8",
            )
            self.assert_rejected(
                root, "implementation state requires roadmap revision 1.43"
            )
'''
    text = replace_method(text, "test_premature_roadmap_closure_is_rejected", premature)

    successor = '''    def test_validated_successor_must_remain_checked(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "docs/roadmap/nodal-development-todo.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "- [x] **Increment 34 — Analog control flow**",
                    "- [ ] **Increment 34 — Analog control flow**",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "validated Increment 34 must be checked")

    def test_unvalidated_successor_cannot_close_early(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["status"] = "implementation-in-progress"
            document["tranche"] = "34c-native-branch-sensitive-dataflow"
            document["validation"] = None
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "open Increment 34 requires roadmap revision 1.44")
'''
    text = replace_method(
        text,
        "test_validated_closure_keeps_increment34_open",
        successor,
    )
    write(path, text)


def write_evidence(root: Path) -> None:
    for path in (root / "docs/implementation").glob("increment34-*closure*.md"):
        if path.name != "increment34-evidence-closure.md":
            path.unlink()
    path = root / "docs/implementation/increment34-evidence-closure.md"
    text = f'''# Increment 34 — Accepted-evidence closure

**Status:** Validated evidence-closure candidate  
**Implementation PR:** #109  
**Accepted implementation head:** `{ACCEPTED_HEAD}`  
**Final reviewed implementation head:** `{FINAL_REVIEW_HEAD}`  
**Exact-head workflow matrix:** {EXACT_HEAD_WORKFLOW_COUNT} successful workflows  
**Exact-head Core CI:** `{EXACT_HEAD_CORE_CI_RUN}`  
**Implementation merge:** `{IMPLEMENTATION_MERGE}`  
**Post-merge Core CI:** `{POST_MERGE_CORE_CI_RUN}`  
**Exact post-merge validation:** `{EXACT_POST_MERGE_VALIDATION_RUN}`  
**Closure PR:** #111  
**Closure validation head:** `PENDING_CLOSURE_VALIDATION_HEAD`  
**Closure validation run:** `PENDING_CLOSURE_VALIDATION_RUN`

This evidence-only change closes Increment 34 after public construction, canonical
snapshot retention, deterministic Scala-to-MLIR serialization, first-class native
control-flow IR, native structural and branch-sensitive verification, exact-head
review, implementation merge, and two independent post-merge validations.

The accepted implementation retains conditionals, exact case selection, static
and finite runtime-bounded loops, nearest-loop `break` and `continue`, lexical
scope, declaration-before-reference rules, definite-assignment intersections,
owner-remapped source provenance, stable diagnostics, direct MLIR fixtures, and
source-map round trips.

Residual/DAE construction, solver execution, executable analysis scheduling,
target legalization, and Verilog-A or Verilog-AMS procedural lowering remain
assigned to later increments and are not claimed by this closure.
'''
    write(path, text)


def stamp(root: Path, closure_head: str, closure_run: int) -> None:
    if len(closure_head) != 40 or any(
        character not in "0123456789abcdef" for character in closure_head
    ):
        raise SystemExit("closure validation head must be a 40-hex commit")
    if closure_run <= 0:
        raise SystemExit("closure validation run must be positive")
    manifest_path = root / "tests/compiler/fixtures/increment34/manifest.json"
    manifest = json.loads(read(manifest_path))
    validation = manifest.get("validation")
    if not isinstance(validation, dict):
        raise SystemExit("closure candidate manifest lacks validation object")
    validation["closure_validation_head"] = closure_head
    validation["closure_validation_run"] = closure_run
    write(manifest_path, json.dumps(manifest, indent=2) + "\n")

    evidence_path = root / "docs/implementation/increment34-evidence-closure.md"
    evidence = read(evidence_path)
    evidence = evidence.replace("PENDING_CLOSURE_VALIDATION_HEAD", closure_head)
    evidence = evidence.replace("PENDING_CLOSURE_VALIDATION_RUN", str(closure_run))
    write(evidence_path, evidence)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--phase", choices=("candidate", "stamp"), required=True)
    parser.add_argument("--closure-head")
    parser.add_argument("--closure-run", type=int)
    args = parser.parse_args()
    root = args.root.resolve()
    if args.phase == "candidate":
        patch_manifest(root)
        patch_roadmap(root)
        patch_implementation(root)
        patch_exact_head_record(root)
        patch_increment34_checker(root)
        patch_increment33_checker(root)
        patch_increment34_tests(root)
        patch_increment33_tests(root)
        write_evidence(root)
        print("Increment 34 evidence closure candidate materialized.")
    else:
        if args.closure_head is None or args.closure_run is None:
            raise SystemExit("stamp phase requires --closure-head and --closure-run")
        stamp(root, args.closure_head, args.closure_run)
        print("Increment 34 evidence closure validation identity stamped.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
