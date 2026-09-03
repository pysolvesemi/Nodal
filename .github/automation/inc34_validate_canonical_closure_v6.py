#!/usr/bin/env python3
"""Validate the complete, canonical Increment 34 closure state."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

IMPLEMENTATION_HEAD = "207fd1b580e9428e9948cd4e4bd8f2060fde4b79"
FINAL_REVIEW_HEAD = "54d8523715a86e1780263b6f5227def2f0977833"
IMPLEMENTATION_MERGE = "a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49"
DEDICATED_BOUNDARY_RUN = 33732868285
EXACT_HEAD_CORE_CI_RUN = 33732864482
POST_MERGE_CORE_CI_RUN = 33758905273
EXACT_POST_MERGE_RUN = 33759112770


def read(root: Path, relative: str) -> str:
    path = root / relative
    if not path.is_file():
        raise SystemExit(f"missing canonical closure file: {relative}")
    return path.read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()

    manifest = json.loads(
        read(root, "tests/compiler/fixtures/increment34/manifest.json")
    )
    roadmap = read(root, "docs/roadmap/nodal-development-todo.md")
    evidence = read(root, "docs/implementation/increment34-evidence-closure.md")
    implementation = read(root, "docs/implementation/increment34-analog-control-flow.md")
    exact_head = read(root, "docs/implementation/increment34-exact-head-validation.md")
    design_gate = read(root, "docs/design-gates/NodalAnalogControlFlow-DG-v0.1.md")
    fixture_readme = read(root, "tests/compiler/fixtures/increment34/README.md")

    require(manifest.get("schema") == 1, "Increment 34 closure schema is invalid")
    require(manifest.get("increment") == 34, "Increment 34 manifest identity is invalid")
    require(
        manifest.get("status") == "validated-analog-control-flow",
        "Increment 34 manifest is not validated",
    )
    require(
        manifest.get("tranche") == "34d-closure",
        "Increment 34 manifest is not in the closure tranche",
    )
    validation = manifest.get("validation")
    require(isinstance(validation, dict), "Increment 34 validation evidence is absent")

    expected = {
        "implementation_pull_request": 109,
        "accepted_head": IMPLEMENTATION_HEAD,
        "dedicated_boundary_workflow_run": DEDICATED_BOUNDARY_RUN,
        "final_review_head": FINAL_REVIEW_HEAD,
        "exact_head_workflow_count": 26,
        "exact_head_core_ci_run": EXACT_HEAD_CORE_CI_RUN,
        "implementation_merge": IMPLEMENTATION_MERGE,
        "post_merge_core_ci_run": POST_MERGE_CORE_CI_RUN,
        "exact_post_merge_validation_run": EXACT_POST_MERGE_RUN,
        "closure_pull_request": 111,
    }
    for key, value in expected.items():
        require(
            validation.get(key) == value,
            f"Increment 34 closure evidence mismatch: {key}",
        )

    closure_head = validation.get("closure_validation_head")
    closure_run = validation.get("closure_validation_run")
    require(
        isinstance(closure_head, str)
        and re.fullmatch(r"[0-9a-f]{40}", closure_head) is not None
        and closure_head != "0" * 40,
        "Increment 34 closure validation head is invalid",
    )
    require(
        isinstance(closure_run, int) and closure_run > 0,
        "Increment 34 closure validation run is invalid",
    )

    require(
        roadmap.count("**Revision:** 1.45") == 1,
        "roadmap revision 1.45 is missing or ambiguous",
    )
    require(
        roadmap.count("- [x] **Increment 34 — Analog control flow**") == 1,
        "Increment 34 checked roadmap entry is missing or ambiguous",
    )
    require(
        "- [ ] **Increment 34 — Analog control flow**" not in roadmap,
        "an unchecked Increment 34 roadmap entry remains",
    )
    for token in (
        "evidence closure PR [#111]",
        str(DEDICATED_BOUNDARY_RUN),
        str(EXACT_HEAD_CORE_CI_RUN),
        str(POST_MERGE_CORE_CI_RUN),
        str(EXACT_POST_MERGE_RUN),
    ):
        require(token in roadmap, f"roadmap evidence is missing {token!r}")

    evidence_tokens = (
        "**Status:** Validated evidence closure",
        "**Implementation PR:** #109",
        f"**Accepted implementation head:** `{IMPLEMENTATION_HEAD}`",
        f"**Final reviewed implementation head:** `{FINAL_REVIEW_HEAD}`",
        f"**Dedicated boundary workflow:** `{DEDICATED_BOUNDARY_RUN}`",
        f"**Exact-head Core CI:** `{EXACT_HEAD_CORE_CI_RUN}`",
        f"**Implementation merge:** `{IMPLEMENTATION_MERGE}`",
        f"**Post-merge Core CI:** `{POST_MERGE_CORE_CI_RUN}`",
        f"**Exact post-merge validation:** `{EXACT_POST_MERGE_RUN}`",
        "**Closure PR:** #111",
        f"**Closure validation head:** `{closure_head}`",
        f"**Closure validation run:** `{closure_run}`",
    )
    for token in evidence_tokens:
        require(token in evidence, f"closure evidence document is missing {token!r}")

    require(
        "**Status:** Validated" in implementation,
        "implementation record does not report validated status",
    )
    for item in (
        "Complete deterministic reproducibility serialization.",
        "Run the full inherited workflow matrix on one exact head.",
        "Perform a fresh review and repair all findings.",
        "Merge after Increment 33 is closed.",
        "Run post-merge Core CI and dedicated Increment 34 validation.",
    ):
        require(
            f"- [x] {item}" in implementation,
            f"implementation closure checklist is missing {item!r}",
        )
    require(
        "Implementation PR #109 was accepted at exact head" in implementation,
        "implementation record lacks immutable closure evidence",
    )
    require(
        "**Status:** Accepted implementation; exact-head and post-merge validation complete"
        in exact_head,
        "exact-head validation record is stale",
    )
    require(
        "The completed Increment 34 implementation retains" in design_gate,
        "design gate still describes compiler integration as unfinished",
    )
    require(
        "separate evidence closure PR #111 are retained" in fixture_readme,
        "fixture documentation still describes Increment 34 as open",
    )

    extra_closure_docs = sorted(
        path.name
        for path in (root / "docs/implementation").glob("increment34-*closure*.md")
        if path.name != "increment34-evidence-closure.md"
    )
    require(
        not extra_closure_docs,
        f"duplicate Increment 34 closure documents remain: {extra_closure_docs}",
    )

    result = {
        "increment": 34,
        "state": "validated-analog-control-flow",
        "accepted_head": IMPLEMENTATION_HEAD,
        "implementation_merge": IMPLEMENTATION_MERGE,
        "closure_validation_head": closure_head,
        "closure_validation_run": closure_run,
        "roadmap_revision": "1.45",
        "roadmap_checked": True,
        "canonical": True,
    }
    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    else:
        print("Increment 34 canonical closure state is valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
