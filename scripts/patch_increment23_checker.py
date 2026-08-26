#!/usr/bin/env python3
"""Prepare the accepted Increment 23 evidence-closure candidate.

The historical materialization workflow invokes this script after reconstructing
its fixed implementation payload. For evidence closure, discard that temporary
worktree state, start from the latest merged dev branch, and apply only the
manifest and roadmap edits. The remainder of the historical workflow then runs
the full native, Scala, checker, mutation, and exact-artifact validation before
publishing anything.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path

IMPLEMENTATION_PR = 61
CLOSURE_PR = 63
DEDICATED_RUN = 32966834961
CORE_CI_RUN = 32966835105


def run_git(root: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=root, check=True)


def close_manifest(root: Path) -> None:
    path = root / "tests/compiler/fixtures/increment23/manifest.json"
    data = json.loads(path.read_text(encoding="utf-8"))

    if data.get("increment") != 23:
        raise RuntimeError("Increment 23 manifest identity mismatch")
    if data.get("status") not in {
        "implemented-awaiting-evidence",
        "validated-backend-framework",
    }:
        raise RuntimeError(f"unexpected Increment 23 status: {data.get('status')!r}")

    data["status"] = "validated-backend-framework"
    data["evidence"] = {
        "pull_request": IMPLEMENTATION_PR,
        "dedicated_run": DEDICATED_RUN,
        "core_ci_run": CORE_CI_RUN,
    }
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def close_roadmap(root: Path) -> None:
    path = root / "docs/roadmap/nodal-development-todo.md"
    text = path.read_text(encoding="utf-8")

    revision = re.search(r"\*\*Revision:\*\* (\d+)\.(\d+)", text)
    if revision is None:
        raise RuntimeError("roadmap revision is missing")
    major, minor = map(int, revision.groups())
    if major != 1 or minor < 28:
        raise RuntimeError(f"unexpected roadmap revision: {major}.{minor}")

    heading_open = "- [ ] **Increment 23 — Backend framework and capability profiles**"
    heading_closed = "- [x] **Increment 23 — Backend framework and capability profiles**"
    description = (
        "  - Add translation registration, deterministic output handling, "
        "profile-owned shaped-value layouts, expression materialization/naming "
        "and CheckProfile configuration, transactional target verification/reparse "
        "hooks, `verilog-a`/`verilog-ams` profiles, and explicit unsupported-feature errors."
    )
    evidence = (
        "  - Evidence: [`NodalBackendFramework-DG-v1.0.md`](../design-gates/"
        "NodalBackendFramework-DG-v1.0.md), [`increment23-backend-framework.md`]"
        "(../implementation/increment23-backend-framework.md), implementation PR "
        f"[#{IMPLEMENTATION_PR}](https://github.com/pysolvesemi/Nodal/pull/"
        f"{IMPLEMENTATION_PR}), closure PR [#{CLOSURE_PR}]"
        f"(https://github.com/pysolvesemi/Nodal/pull/{CLOSURE_PR}), dedicated "
        f"validation run [{DEDICATED_RUN}]"
        f"(https://github.com/pysolvesemi/Nodal/actions/runs/{DEDICATED_RUN}), and "
        f"Core CI run [{CORE_CI_RUN}]"
        f"(https://github.com/pysolvesemi/Nodal/actions/runs/{CORE_CI_RUN})."
    )

    if heading_closed in text:
        raise RuntimeError("Increment 23 roadmap entry is already closed")
    expected = f"{heading_open}\n{description}\n"
    replacement = f"{heading_closed}\n{description}\n{evidence}\n"
    if text.count(expected) != 1:
        raise RuntimeError("Increment 23 roadmap block does not match exactly once")

    text = (
        text[: revision.start()]
        + f"**Revision:** {major}.{minor + 1}"
        + text[revision.end() :]
    )
    text = text.replace(expected, replacement, 1)

    required_unchanged = (
        "- [ ] **Increment 24 — Minimal analog expression and contribution IR**",
        "- [ ] **Foundation Increment 153 — Function-local lexical naming and alias contract**",
        "- [ ] **Foundation Increment 157 — Name preservation, generated namespaces, and verifier closure**",
    )
    for marker in required_unchanged:
        if marker not in text:
            raise RuntimeError(f"roadmap preservation marker is missing: {marker}")

    path.write_text(text, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    run_git(root, "fetch", "--no-tags", "origin", "dev")
    run_git(root, "reset", "--hard", "origin/dev")
    run_git(root, "clean", "-fd")

    close_manifest(root)
    close_roadmap(root)

    checker = (root / "scripts/check_increment23.py").read_text(encoding="utf-8")
    required_checker_contracts = (
        "validated-backend-framework",
        "validated manifest lacks integer evidence field",
        "Increment 24 must remain unchecked",
    )
    for contract in required_checker_contracts:
        if contract not in checker:
            raise RuntimeError(f"merged Increment 23 checker contract is missing: {contract}")


if __name__ == "__main__":
    main()
