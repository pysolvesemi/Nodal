#!/usr/bin/env python3
"""Close Increment 23 only after accepted implementation evidence exists."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "tests/compiler/fixtures/increment23/manifest.json"
ROADMAP = ROOT / "docs/roadmap/nodal-development-todo.md"

IMPLEMENTATION_PR_NUMBER = 61
CLOSURE_PR_NUMBER = 63
DEDICATED_RUN = 32966834961
CORE_CI_RUN = 32966835105


def close_manifest() -> None:
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if data.get("increment") != 23:
        raise RuntimeError("Increment 23 manifest identity mismatch")
    if data.get("status") not in {
        "implemented-awaiting-evidence",
        "validated-backend-framework",
    }:
        raise RuntimeError(f"unexpected Increment 23 status: {data.get('status')!r}")

    data["status"] = "validated-backend-framework"
    data["evidence"] = {
        "pull_request": IMPLEMENTATION_PR_NUMBER,
        "dedicated_run": DEDICATED_RUN,
        "core_ci_run": CORE_CI_RUN,
    }
    MANIFEST.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def close_roadmap() -> None:
    text = ROADMAP.read_text(encoding="utf-8")

    revision = re.search(r"\*\*Revision:\*\* (\d+)\.(\d+)", text)
    if revision is None:
        raise RuntimeError("roadmap revision is missing")
    major, minor = map(int, revision.groups())
    if major != 1 or minor < 28:
        raise RuntimeError(f"unexpected roadmap revision: {major}.{minor}")
    text = (
        text[: revision.start()]
        + f"**Revision:** {major}.{minor + 1}"
        + text[revision.end() :]
    )

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
        f"[#{IMPLEMENTATION_PR_NUMBER}](https://github.com/pysolvesemi/Nodal/pull/"
        f"{IMPLEMENTATION_PR_NUMBER}), closure PR [#{CLOSURE_PR_NUMBER}]"
        f"(https://github.com/pysolvesemi/Nodal/pull/{CLOSURE_PR_NUMBER}), dedicated "
        f"validation run [{DEDICATED_RUN}]"
        f"(https://github.com/pysolvesemi/Nodal/actions/runs/{DEDICATED_RUN}), and "
        f"Core CI run [{CORE_CI_RUN}]"
        f"(https://github.com/pysolvesemi/Nodal/actions/runs/{CORE_CI_RUN})."
    )

    if heading_closed in text:
        raise RuntimeError("Increment 23 roadmap entry is already closed")
    expected = f"{heading_open}\n{description}\n"
    replacement = f"{heading_closed}\n{description}\n{evidence}\n"
    if expected not in text:
        raise RuntimeError("Increment 23 roadmap block does not match the accepted contract")
    text = text.replace(expected, replacement, 1)

    if "- [ ] **Increment 24 — Minimal analog expression and contribution IR**" not in text:
        raise RuntimeError("Increment 24 must remain unchecked")

    ROADMAP.write_text(text, encoding="utf-8")


def main() -> None:
    close_manifest()
    close_roadmap()


if __name__ == "__main__":
    main()
