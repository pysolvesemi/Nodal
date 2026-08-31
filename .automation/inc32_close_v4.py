#!/usr/bin/env python3
"""Materialize the validated Increment 32 evidence closure in a target checkout."""

from __future__ import annotations

import json
import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: inc32_close_v4.py <repository-root>")
root = Path(sys.argv[1]).resolve()

manifest_path = root / "tests/compiler/fixtures/increment32/manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if manifest.get("status") != "implemented-awaiting-evidence":
    raise SystemExit(f"unexpected Increment 32 status: {manifest.get('status')}")
manifest["status"] = "validated-equation-contribution-semantics"
manifest["validation"] = {
    "implementation_pull_request": 97,
    "accepted_head": "6a76516aba541ead97205e937118bb0f689fcd98",
    "dedicated_workflow_run": 33370821599,
    "core_ci_run": 33370821561,
    "implementation_merge": "e9ea39e823d5a226a65b952e176d3bb90ecda0aa",
    "post_merge_core_ci_run": 33372029305,
    "closure_pull_request": 99,
    "closure_validation_head": "e9ea39e823d5a226a65b952e176d3bb90ecda0aa",
    "closure_core_ci_run": 33372560008,
    "inherited_workflow_runs": {
        "increment13": 33370821567,
        "increment14": 33370821570,
        "increment15": 33370821526,
        "increment16": 33370821577,
        "increment17": 33370821478,
        "increment18": 33370821573,
        "increment19": 33370821640,
        "increment20": 33370821531,
        "increment21": 33370821491,
        "increment22": 33370821464,
        "increment23": 33370821584,
        "increment24": 33370821639,
        "increment25": 33370821560,
        "increment26": 33370821426,
        "increment27": 33370821569,
        "increment28": 33370821433,
        "increment29": 33370821375,
        "increment30": 33370821364,
        "increment31": 33370821488,
        "increment133": 33370821558,
    },
}
manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

roadmap_path = root / "docs/roadmap/nodal-development-todo.md"
roadmap = roadmap_path.read_text(encoding="utf-8")
if "**Revision:** 1.42" not in roadmap or "**Updated:** 2026-08-29" not in roadmap:
    raise SystemExit("unexpected roadmap header")
roadmap = roadmap.replace("**Revision:** 1.42", "**Revision:** 1.43", 1)
roadmap = roadmap.replace("**Updated:** 2026-08-29", "**Updated:** 2026-08-31", 1)
open_line = "- [ ] **Increment 32 — First-class analog equations, blocks, and contribution semantics**"
closed_line = "- [x] **Increment 32 — First-class analog equations, blocks, and contribution semantics**"
if roadmap.count(open_line) != 1:
    raise SystemExit("unexpected Increment 32 roadmap state")
roadmap = roadmap.replace(open_line, closed_line, 1)
evidence_anchor = (
    "  - Require the approved equation/component checkpoint from Increment 133 "
    "before implementation begins.\n"
)
evidence_line = (
    "  - Evidence: implementation PR [#97](https://github.com/pysolvesemi/Nodal/pull/97), "
    "accepted head [`6a76516a`](https://github.com/pysolvesemi/Nodal/commit/6a76516aba541ead97205e937118bb0f689fcd98), "
    "dedicated validation run [33370821599](https://github.com/pysolvesemi/Nodal/actions/runs/33370821599), "
    "merge commit [`e9ea39e8`](https://github.com/pysolvesemi/Nodal/commit/e9ea39e823d5a226a65b952e176d3bb90ecda0aa), "
    "and post-merge Core CI run [33372029305](https://github.com/pysolvesemi/Nodal/actions/runs/33372029305).\n"
)
if roadmap.count(evidence_anchor) != 1:
    raise SystemExit("Increment 32 evidence anchor missing")
roadmap = roadmap.replace(evidence_anchor, evidence_anchor + evidence_line, 1)
if "- [ ] **Increment 33 — Analog variables and procedural assignment**" not in roadmap:
    raise SystemExit("Increment 33 must remain unchecked")
roadmap_path.write_text(roadmap, encoding="utf-8")

test32_path = root / "tests/compiler/test_increment32.py"
test32 = test32_path.read_text(encoding="utf-8")
method = "    def test_premature_roadmap_closure_is_rejected(self) -> None:\n"
start = test32.index(method)
roadmap_anchor = "            roadmap = clone / MODULE.ROADMAP\n"
position = test32.index(roadmap_anchor, start)
insertion = (
    "            manifest_path = clone / MODULE.MANIFEST\n"
    "            manifest = json.loads(manifest_path.read_text(encoding=\"utf-8\"))\n"
    "            manifest[\"status\"] = \"implemented-awaiting-evidence\"\n"
    "            manifest[\"validation\"] = None\n"
    "            manifest_path.write_text(\n"
    "                json.dumps(manifest, indent=2) + \"\\n\", encoding=\"utf-8\"\n"
    "            )\n"
)
test32_path.write_text(
    test32[:position] + insertion + test32[position:], encoding="utf-8"
)

checker_path = root / "scripts/check_increment133.py"
checker = checker_path.read_text(encoding="utf-8")
constant_anchor = 'SEMANTIC = Path("tests/api/fixtures/increment133/semantic-contracts.json")\n'
load_anchor = '    semantic = load_json(root / SEMANTIC, problems, "NODAL-INC133-012")\n'
if constant_anchor not in checker or load_anchor not in checker:
    raise SystemExit("Increment 133 checker anchors missing")
checker = checker.replace(
    constant_anchor,
    constant_anchor
    + 'INCREMENT32 = Path("tests/compiler/fixtures/increment32/manifest.json")\n',
    1,
)
checker = checker.replace(
    load_anchor,
    load_anchor
    + "    increment32_manifest = load_json(\n"
    + '        root / INCREMENT32, problems, "NODAL-INC133-043"\n'
    + "    )\n",
    1,
)
old_successor = (
    '    increment32 = "- [ ] **Increment 32 — First-class analog equations, blocks, and contribution semantics**"\n'
    "    if increment32 not in roadmap:\n"
    '        problems.append(Problem("NODAL-INC133-034", "Increment 32 must remain unchecked until Increment 133 evidence closure merges"))\n'
)
if old_successor not in checker:
    raise SystemExit("Increment 133 successor gate missing")
new_successor = '''    increment32_open = "- [ ] **Increment 32 — First-class analog equations, blocks, and contribution semantics**"
    increment32_closed = "- [x] **Increment 32 — First-class analog equations, blocks, and contribution semantics**"
    successor_status = (
        increment32_manifest.get("status")
        if isinstance(increment32_manifest, dict)
        else None
    )
    if (increment32_open in roadmap) == (increment32_closed in roadmap):
        problems.append(
            Problem(
                "NODAL-INC133-034",
                "Increment 32 roadmap state is missing or ambiguous",
            )
        )
    elif successor_status in {
        "implementation-started",
        "implemented-awaiting-evidence",
    }:
        if increment32_open not in roadmap:
            problems.append(
                Problem(
                    "NODAL-INC133-034",
                    "pre-evidence Increment 32 must remain unchecked",
                )
            )
    elif successor_status == "validated-equation-contribution-semantics":
        validation = increment32_manifest.get("validation")
        required_successor_evidence = (
            "implementation_pull_request",
            "accepted_head",
            "dedicated_workflow_run",
            "core_ci_run",
            "implementation_merge",
            "post_merge_core_ci_run",
            "closure_pull_request",
            "closure_validation_head",
            "closure_core_ci_run",
        )
        if increment32_closed not in roadmap:
            problems.append(
                Problem(
                    "NODAL-INC133-034",
                    "validated Increment 32 must be checked in the roadmap",
                )
            )
        if not isinstance(validation, dict) or any(
            not validation.get(field) for field in required_successor_evidence
        ):
            problems.append(
                Problem(
                    "NODAL-INC133-034",
                    "validated Increment 32 lacks complete evidence",
                )
            )
    else:
        problems.append(
            Problem(
                "NODAL-INC133-034",
                f"unsupported Increment 32 successor status: {successor_status}",
            )
        )
'''
checker_path.write_text(
    checker.replace(old_successor, new_successor, 1), encoding="utf-8"
)

test133_path = root / "tests/api/test_increment133.py"
test133 = test133_path.read_text(encoding="utf-8")
marker = '\n\nif __name__ == "__main__":\n'
if marker not in test133:
    raise SystemExit("Increment 133 test marker missing")
test_case = '''
    def test_validated_increment32_requires_complete_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            clone = Path(temporary) / "repo"
            shutil.copytree(ROOT, clone, ignore=shutil.ignore_patterns("out", ".git"))
            manifest_path = clone / MODULE.INCREMENT32
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["validation"]["closure_core_ci_run"] = None
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\\n", encoding="utf-8"
            )
            problems = MODULE.validate_files(clone)
            self.assertIn("NODAL-INC133-034", [problem.code for problem in problems])
'''
test133_path.write_text(
    test133.replace(marker, test_case + marker, 1), encoding="utf-8"
)

implementation_path = (
    root / "docs/implementation/increment32-equation-contribution-semantics.md"
)
implementation = implementation_path.read_text(encoding="utf-8")
implementation = implementation.replace(
    "Implemented on the increment branch and awaiting exact-head validation and\n"
    "accepted-evidence closure.",
    "Implemented on the increment branch and validated through implementation\n"
    "PR #97 and the separate evidence-closure PR #99.",
    1,
)
implementation = implementation.replace(
    "The Increment 32 roadmap item remains unchecked until the implementation is\n"
    "merged, post-merge validation passes, and a separate evidence-closure change\n"
    "records immutable evidence. Increment 33 remains unchecked throughout this\n"
    "implementation PR.",
    "The Increment 32 roadmap item is checked in roadmap revision 1.43 after\n"
    "implementation merge `e9ea39e823d5a226a65b952e176d3bb90ecda0aa` and\n"
    "post-merge Core CI run `33372029305`. Increment 33 remains unchecked until its\n"
    "own implementation and evidence closure complete.",
    1,
)
implementation_path.write_text(implementation, encoding="utf-8")

closure_path = root / "docs/implementation/increment32-evidence-closure.md"
closure = closure_path.read_text(encoding="utf-8")
closure = closure.replace(
    "**Status:** Closure staged", "**Status:** Validated closure candidate", 1
)
closure += (
    "\n**Closure PR:** #99\n"
    "**Pre-stamp closure validation baseline:** "
    "`e9ea39e823d5a226a65b952e176d3bb90ecda0aa`\n"
    "**Pre-stamp closure Core CI:** `33372560008`\n"
)
closure_path.write_text(closure, encoding="utf-8")

print("Increment 32 closure content materialized")
