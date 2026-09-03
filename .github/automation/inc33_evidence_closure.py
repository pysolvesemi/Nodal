#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


IMPLEMENTATION_HEAD = "ea7f7da51e85ba275dac71db7823ba0223f8d4ac"
IMPLEMENTATION_MERGE = "2e0ff291b8d6c0f6dcc4b4c8e27cc33984cff1b8"
DEDICATED_RUN = 33592719238
POST_MERGE_CORE_RUN = 33605996500
EXACT_POST_MERGE_RUN = 33714669557


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"missing required file: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old == new:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def patch_manifest(
    root: Path,
    closure_pr: int,
    validation_head: str,
    validation_run: int,
) -> None:
    path = root / "tests/compiler/fixtures/increment33/manifest.json"
    document = json.loads(read(path))
    if document.get("increment") != 33:
        raise SystemExit("Increment 33 manifest identity is invalid")
    document["status"] = "validated-analog-procedural-assignment"
    document["validation"] = {
        "implementation_pull_request": 102,
        "accepted_head": IMPLEMENTATION_HEAD,
        "dedicated_boundary_workflow_run": DEDICATED_RUN,
        "implementation_merge": IMPLEMENTATION_MERGE,
        "post_merge_core_ci_run": POST_MERGE_CORE_RUN,
        "exact_post_merge_validation_run": EXACT_POST_MERGE_RUN,
        "closure_pull_request": closure_pr,
        "closure_validation_head": validation_head,
        "closure_validation_run": validation_run,
    }
    write(path, json.dumps(document, indent=2) + "\n")


def patch_increment33_checker(root: Path) -> None:
    path = root / "scripts/check_increment33.py"
    text = read(path)

    old = '''    require(
        manifest.get("status") == "implementation-in-progress",
        "NODAL-INC33-005: checkpoint manifest must remain implementation-in-progress",
    )
    require(manifest.get("validation") is None, "NODAL-INC33-006: validation must be null before evidence closure")
'''
    new = '''    status = manifest.get("status")
    require(
        status in {
            "implementation-in-progress",
            "validated-analog-procedural-assignment",
        },
        f"NODAL-INC33-005: unsupported manifest status: {status}",
    )
    validation = manifest.get("validation")
    if status == "implementation-in-progress":
        require(
            validation is None,
            "NODAL-INC33-006: validation must be null before evidence closure",
        )
    else:
        required_validation = (
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
            isinstance(validation, dict)
            and all(validation.get(field) for field in required_validation),
            "NODAL-INC33-006: validated manifest lacks complete evidence",
        )
        require(
            validation.get("implementation_pull_request") == 102
            and validation.get("accepted_head")
            == "ea7f7da51e85ba275dac71db7823ba0223f8d4ac"
            and validation.get("dedicated_boundary_workflow_run") == 33592719238
            and validation.get("implementation_merge")
            == "2e0ff291b8d6c0f6dcc4b4c8e27cc33984cff1b8"
            and validation.get("post_merge_core_ci_run") == 33605996500
            and validation.get("exact_post_merge_validation_run") == 33714669557,
            "NODAL-INC33-006: validated manifest evidence does not match accepted implementation",
        )
'''
    if old in text:
        text = text.replace(old, new, 1)
    elif "validated-analog-procedural-assignment" not in text:
        raise SystemExit("Increment 33 manifest-state checker anchor was not found")

    old = '''    roadmap = read_text(root, "docs/roadmap/nodal-development-todo.md")
    require(
        "**Revision:** 1.43" in roadmap,
        "NODAL-INC33-027: roadmap revision must remain 1.43 during implementation",
    )
    require(
        "- [x] **Increment 32 — First-class analog equations" in roadmap,
        "NODAL-INC33-028: Increment 32 must be checked before Increment 33",
    )
    require(
        "- [ ] **Increment 33 — Analog variables and procedural assignment**" in roadmap,
        "NODAL-INC33-029: Increment 33 must remain unchecked before evidence closure",
    )
'''
    new = '''    roadmap = read_text(root, "docs/roadmap/nodal-development-todo.md")
    require(
        "- [x] **Increment 32 — First-class analog equations" in roadmap,
        "NODAL-INC33-028: Increment 32 must be checked before Increment 33",
    )
    increment33_open = (
        "- [ ] **Increment 33 — Analog variables and procedural assignment**"
    )
    increment33_closed = (
        "- [x] **Increment 33 — Analog variables and procedural assignment**"
    )
    increment34_open = "- [ ] **Increment 34 — Analog control flow**"
    require(
        (increment33_open in roadmap) != (increment33_closed in roadmap),
        "NODAL-INC33-029: Increment 33 roadmap state is missing or ambiguous",
    )
    if status == "implementation-in-progress":
        require(
            "**Revision:** 1.43" in roadmap and increment33_open in roadmap,
            "NODAL-INC33-027: implementation state requires roadmap revision 1.43 with Increment 33 open",
        )
    else:
        require(
            "**Revision:** 1.44" in roadmap and increment33_closed in roadmap,
            "NODAL-INC33-027: validated state requires roadmap revision 1.44 with Increment 33 closed",
        )
        require(
            increment34_open in roadmap,
            "NODAL-INC33-029: Increment 34 must remain unchecked during Increment 33 closure",
        )
        evidence = read_text(
            root,
            "docs/implementation/increment33-evidence-closure.md",
        )
        for token in (
            "**Implementation PR:** #102",
            "**Accepted implementation head:** `ea7f7da51e85ba275dac71db7823ba0223f8d4ac`",
            "**Implementation merge:** `2e0ff291b8d6c0f6dcc4b4c8e27cc33984cff1b8`",
            "**Exact post-merge validation:** `33714669557`",
            f"**Closure PR:** #{validation['closure_pull_request']}",
            f"**Closure validation head:** `{validation['closure_validation_head']}`",
            f"**Closure validation run:** `{validation['closure_validation_run']}`",
        ):
            require(
                token in evidence,
                f"NODAL-INC33-080: evidence-closure record is missing {token!r}",
            )
'''
    if old in text:
        text = text.replace(old, new, 1)
    elif "Increment 33 roadmap state is missing or ambiguous" not in text:
        raise SystemExit("Increment 33 roadmap-state checker anchor was not found")

    write(path, text)


def patch_increment32_checker(root: Path) -> None:
    path = root / "scripts/check_increment32.py"
    text = read(path)

    old = 'INCREMENT133 = Path("tests/api/fixtures/increment133/manifest.json")\n'
    new = old + 'INCREMENT33 = Path("tests/compiler/fixtures/increment33/manifest.json")\n'
    if "INCREMENT33 =" not in text:
        text = replace_once(text, old, new, "Increment 32 successor path")

    old = '''    increment133 = load_json(root, INCREMENT133, problems, "NODAL-INC32-011")
    if problems:
'''
    new = '''    increment133 = load_json(root, INCREMENT133, problems, "NODAL-INC32-011")
    increment33 = load_json(root, INCREMENT33, problems, "NODAL-INC32-052")
    if problems:
'''
    if "increment33 = load_json" not in text:
        text = replace_once(text, old, new, "Increment 32 successor manifest load")

    old = '''    open33 = "- [ ] **Increment 33 — Analog variables and procedural assignment**"
    if status == "implemented-awaiting-evidence" and open32 not in roadmap:
'''
    new = '''    open33 = "- [ ] **Increment 33 — Analog variables and procedural assignment**"
    closed33 = "- [x] **Increment 33 — Analog variables and procedural assignment**"
    if status == "implemented-awaiting-evidence" and open32 not in roadmap:
'''
    if "closed33 =" not in text:
        text = replace_once(text, old, new, "Increment 32 successor roadmap identities")

    old = '''    if open33 not in roadmap:
        problems.append(
            Problem("NODAL-INC32-028", "Increment 33 must remain unchecked during closure")
        )
'''
    new = '''    successor_status = increment33.get("status")
    if (open33 in roadmap) == (closed33 in roadmap):
        problems.append(
            Problem("NODAL-INC32-028", "Increment 33 roadmap state is missing or ambiguous")
        )
    elif successor_status == "implementation-in-progress":
        if open33 not in roadmap:
            problems.append(
                Problem("NODAL-INC32-028", "pre-evidence Increment 33 must remain unchecked")
            )
    elif successor_status == "validated-analog-procedural-assignment":
        validation33 = increment33.get("validation")
        required33 = (
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
        if closed33 not in roadmap:
            problems.append(
                Problem("NODAL-INC32-028", "validated Increment 33 must be checked in the roadmap")
            )
        if not isinstance(validation33, dict) or any(
            not validation33.get(field) for field in required33
        ):
            problems.append(
                Problem("NODAL-INC32-028", "validated Increment 33 lacks complete evidence")
            )
    else:
        problems.append(
            Problem(
                "NODAL-INC32-028",
                f"unsupported Increment 33 successor status: {successor_status}",
            )
        )
'''
    if old in text:
        text = text.replace(old, new, 1)
    elif "validated Increment 33 lacks complete evidence" not in text:
        raise SystemExit("Increment 32 successor-state checker anchor was not found")

    write(path, text)


def patch_increment33_tests(root: Path) -> None:
    path = root / "tests/compiler/test_increment33.py"
    text = read(path)
    if "import json\n" not in text:
        text = text.replace("import importlib.util\n", "import importlib.util\nimport json\n", 1)

    marker = '    "docs/implementation/increment33-analog-variables-procedural-assignment.md",\n'
    addition = '    "docs/implementation/increment33-evidence-closure.md",\n'
    if addition not in text:
        text = replace_once(text, marker, marker + addition, "Increment 33 evidence test fixture")

    old = '''    def test_premature_roadmap_closure_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "docs/roadmap/nodal-development-todo.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "- [ ] **Increment 33 — Analog variables and procedural assignment**",
                    "- [x] **Increment 33 — Analog variables and procedural assignment**",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "must remain unchecked")
'''
    new = '''    def test_premature_roadmap_closure_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            manifest_path = root / "tests/compiler/fixtures/increment33/manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["status"] = "implementation-in-progress"
            manifest["validation"] = None
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\\n",
                encoding="utf-8",
            )
            path = root / "docs/roadmap/nodal-development-todo.md"
            path.write_text(
                path.read_text(encoding="utf-8")
                .replace("**Revision:** 1.44", "**Revision:** 1.43", 1)
                .replace(
                    "- [x] **Increment 33 — Analog variables and procedural assignment**",
                    "- [ ] **Increment 33 — Analog variables and procedural assignment**",
                )
                .replace(
                    "- [ ] **Increment 33 — Analog variables and procedural assignment**",
                    "- [x] **Increment 33 — Analog variables and procedural assignment**",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "implementation state requires roadmap revision 1.43")

    def test_validated_closure_requires_complete_evidence(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment33/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["validation"]["closure_validation_run"] = None
            path.write_text(json.dumps(document, indent=2) + "\\n", encoding="utf-8")
            self.assert_rejected(root, "validated manifest lacks complete evidence")

    def test_validated_closure_keeps_increment34_open(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "docs/roadmap/nodal-development-todo.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "- [ ] **Increment 34 — Analog control flow**",
                    "- [x] **Increment 34 — Analog control flow**",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "Increment 34 must remain unchecked")
'''
    if old in text:
        text = text.replace(old, new, 1)
    elif "test_validated_closure_requires_complete_evidence" not in text:
        raise SystemExit("Increment 33 closure mutation-test anchor was not found")

    write(path, text)


def patch_increment32_tests(root: Path) -> None:
    path = root / "tests/compiler/test_increment32.py"
    text = read(path)
    marker = "\n\nif __name__ == \"__main__\":\n"
    addition = '''
    def test_validated_increment33_requires_complete_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            clone = Path(temporary) / "repo"
            shutil.copytree(ROOT, clone, ignore=shutil.ignore_patterns("out", ".git"))
            manifest_path = clone / MODULE.INCREMENT33
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["validation"]["closure_validation_run"] = None
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\\n",
                encoding="utf-8",
            )
            problems = MODULE.validate_files(clone)
            self.assertIn("NODAL-INC32-028", [problem.code for problem in problems])
'''
    if "test_validated_increment33_requires_complete_evidence" not in text:
        text = replace_once(text, marker, addition + marker, "Increment 32 successor evidence test")
    write(path, text)


def patch_roadmap(root: Path) -> None:
    path = root / "docs/roadmap/nodal-development-todo.md"
    text = read(path)
    text = text.replace("**Revision:** 1.43", "**Revision:** 1.44", 1)
    text = text.replace("**Updated:** 2026-08-31", "**Updated:** 2026-09-03", 1)
    old = "- [ ] **Increment 33 — Analog variables and procedural assignment**"
    new = "- [x] **Increment 33 — Analog variables and procedural assignment**"
    if old in text:
        text = text.replace(old, new, 1)
    elif new not in text:
        raise SystemExit("Increment 33 roadmap entry was not found")
    evidence = (
        "  - Evidence: implementation PR [#102](https://github.com/pysolvesemi/Nodal/pull/102), "
        "accepted head [`ea7f7da5`](https://github.com/pysolvesemi/Nodal/commit/"
        "ea7f7da51e85ba275dac71db7823ba0223f8d4ac), dedicated boundary run "
        "[33592719238](https://github.com/pysolvesemi/Nodal/actions/runs/33592719238), "
        "merge commit [`2e0ff291`](https://github.com/pysolvesemi/Nodal/commit/"
        "2e0ff291b8d6c0f6dcc4b4c8e27cc33984cff1b8), post-merge Core CI run "
        "[33605996500](https://github.com/pysolvesemi/Nodal/actions/runs/33605996500), "
        "and exact post-merge validation run [33714669557]"
        "(https://github.com/pysolvesemi/Nodal/actions/runs/33714669557).\n"
    )
    if evidence.strip() not in text:
        entry = new
        start = text.index(entry)
        next_entry = text.find("\n- [ ] **Increment 34", start)
        if next_entry < 0:
            raise SystemExit("Increment 34 roadmap successor was not found")
        text = text[:next_entry] + "\n" + evidence + text[next_entry:]
    write(path, text)


def patch_docs(
    root: Path,
    closure_pr: int,
    validation_head: str,
    validation_run: int,
) -> None:
    implementation_path = root / "docs/implementation/increment33-analog-variables-procedural-assignment.md"
    text = read(implementation_path)
    old = '''Implementation is complete and undergoing exact-head acceptance. Public
construction, ordered source-semantic recording, Scala-to-MLIR serialization,
native IR verification, compiler-boundary diagnostics, and source-map coverage
are present. The roadmap remains open until the implementation is merged and a
separate evidence-closure pull request records immutable validation evidence.
'''
    new = f'''Implementation and evidence closure are complete. Public construction,
ordered source-semantic recording, Scala-to-MLIR serialization, native IR
verification, compiler-boundary diagnostics, and source-map coverage are
validated through implementation PR #102 and evidence-closure PR #{closure_pr}.
'''
    if old in text:
        text = text.replace(old, new, 1)
    elif "Implementation and evidence closure are complete" not in text:
        raise SystemExit("Increment 33 implementation status paragraph was not found")
    old = '''Increment 33 remains unchecked in the roadmap until the complete implementation
passes its exact-head matrix, is merged, and a separate evidence-closure pull
request records immutable evidence.
'''
    new = '''Increment 33 is checked in roadmap revision 1.44 after implementation merge,
post-merge Core CI, and the separate evidence-closure validation. Increment 34
remains unchecked until its own implementation and evidence closure complete.
'''
    if old in text:
        text = text.replace(old, new, 1)
    elif "roadmap revision 1.44" not in text:
        raise SystemExit("Increment 33 implementation closure paragraph was not found")
    write(implementation_path, text)

    evidence_path = root / "docs/implementation/increment33-evidence-closure.md"
    write(
        evidence_path,
        f'''# Increment 33 — Accepted-evidence closure

**Status:** Evidence-stamped closure candidate
**Implementation PR:** #102
**Accepted implementation head:** `{IMPLEMENTATION_HEAD}`
**Dedicated boundary workflow:** `{DEDICATED_RUN}`
**Implementation merge:** `{IMPLEMENTATION_MERGE}`
**Post-merge Core CI:** `{POST_MERGE_CORE_RUN}`
**Exact post-merge validation:** `{EXACT_POST_MERGE_RUN}`
**Closure PR:** #{closure_pr}
**Closure validation head:** `{validation_head}`
**Closure validation run:** `{validation_run}`

This evidence-only change closes Increment 33 after its implementation, final
review hardening, squash merge, post-merge Core CI, and exact post-merge
validation completed. It advances the roadmap and manifest together while
leaving Increment 34 unchecked.

The implementation retains component-local analog variables, exact authored
procedural ordering, lexical scope, ownership, type and physical-dimension
checking, explicit reads, compiler-boundary diagnostics, source-map round trip,
and authoritative Scala-to-MLIR serialization.

Analog control flow, branch-sensitive definite assignment, residual/DAE
construction, solver execution, target legalization, and Verilog-A or
Verilog-AMS lowering remain assigned to later increments.
''',
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--closure-pr", type=int, required=True)
    parser.add_argument("--validation-head", required=True)
    parser.add_argument("--validation-run", type=int, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    patch_manifest(root, args.closure_pr, args.validation_head, args.validation_run)
    patch_increment33_checker(root)
    patch_increment32_checker(root)
    patch_increment33_tests(root)
    patch_increment32_tests(root)
    patch_roadmap(root)
    patch_docs(root, args.closure_pr, args.validation_head, args.validation_run)
    print("Increment 33 evidence closure state applied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
