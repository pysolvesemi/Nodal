#!/usr/bin/env python3
"""Temporarily activate the approved direct dev-bootstrap policy."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def update_policy() -> None:
    path = ROOT / ".github" / "branch-policy.json"
    value = json.loads(path.read_text(encoding="utf-8"))
    current = value.get("bootstrap", {})
    if current.get("method") == "direct-ref":
        return
    if current.get("create_dev_from") != "main":
        raise RuntimeError("unexpected existing bootstrap policy")
    value["bootstrap"] = {
        "when": "after Increment 8 is checked and the full Core CI gate succeeds",
        "method": "direct-ref",
        "create_dev_from": "increment/8-ci-baseline",
        "require_identical_tree": True,
        "bootstrap_pull_request_required": False,
        "reason": (
            "Create dev at the exact validated Increment 8 head. The increment "
            "stack is linear, so this preserves every independently validated "
            "commit without adding an artificial bootstrap merge commit."
        ),
    }
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def update_branching_document() -> None:
    path = ROOT / "docs" / "development" / "branching.md"
    text = path.read_text(encoding="utf-8")
    heading = "## Bootstrap integration after Increment 8\n"
    next_heading = "## Normal increment flow\n"
    start = text.index(heading)
    end = text.index(next_heading)
    replacement = """## Bootstrap integration after Increment 8

Increments 1–8 form one linear, independently validated stack above the
original `main` roadmap commit. The one-time bootstrap therefore creates `dev`
directly at the exact validated head of `increment/8-ci-baseline`.

After Increment 8 is checked and the complete Core CI gate succeeds:

1. create `dev` from `increment/8-ci-baseline` at its exact validated commit;
2. verify that `dev` and the Increment 8 head have identical commits and trees;
3. change the GitHub default branch from `main` to `dev`;
4. enable the documented protection rules on `dev` and `main`.

No bootstrap pull request or merge commit is added. A merge would add no source
content because the history is already linear, while direct ref creation
preserves every independently validated increment commit exactly. This direct
creation is a one-time bootstrap exception only; it does not permit later
direct pushes to `dev`.

"""
    path.write_text(text[:start] + replacement + text[end:], encoding="utf-8")


def update_ci_checker() -> None:
    path = ROOT / "scripts" / "check_ci_baseline.py"
    text = path.read_text(encoding="utf-8")
    old = """            (\"bootstrap\", \"pull_request_head\"):
                \"increment/8-ci-baseline\",
            (\"bootstrap\", \"pull_request_base\"): \"dev\",
            (\"bootstrap\", \"merge_method\"): \"merge\",
"""
    new = """            (\"bootstrap\", \"method\"): \"direct-ref\",
            (\"bootstrap\", \"create_dev_from\"):
                \"increment/8-ci-baseline\",
            (\"bootstrap\", \"require_identical_tree\"): True,
            (\"bootstrap\", \"bootstrap_pull_request_required\"):
                False,
"""
    if new in text:
        return
    if text.count(old) != 1:
        raise RuntimeError("unexpected CI bootstrap checker block")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def update_ci_tests() -> None:
    path = ROOT / "tests" / "ci" / "test_ci_baseline.py"
    text = path.read_text(encoding="utf-8")
    test_name = "test_rejects_bootstrap_source_other_than_increment8"
    if test_name in text:
        return
    marker = """    def test_rejects_missing_required_aggregate_job(
        self,
    ) -> None:
"""
    addition = """    def test_rejects_bootstrap_source_other_than_increment8(
        self,
    ) -> None:
        temporary, root = (
            self.temporary_repository()
        )
        self.addCleanup(temporary.cleanup)
        path = (
            root
            / \".github\"
            / \"branch-policy.json\"
        )
        path.write_text(
            path.read_text(
                encoding=\"utf-8\"
            ).replace(
                '\"create_dev_from\": \"increment/8-ci-baseline\"',
                '\"create_dev_from\": \"main\"',
            ),
            encoding=\"utf-8\",
        )
        self.assertIn(
            \"NODAL-CI-017\",
            self.problem_codes(root),
        )

"""
    if text.count(marker) != 1:
        raise RuntimeError("unexpected CI aggregate-test marker")
    path.write_text(text.replace(marker, addition + marker, 1), encoding="utf-8")


def main() -> int:
    update_policy()
    update_branching_document()
    update_ci_checker()
    update_ci_tests()
    print("direct dev bootstrap policy activated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
