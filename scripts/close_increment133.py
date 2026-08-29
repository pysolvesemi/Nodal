#!/usr/bin/env python3
"""Materialize the accepted Increment 133 evidence state on its closure branch."""

from __future__ import annotations

import json
import re
from pathlib import Path

IMPLEMENTATION_PR = 91
SUPERSEDED_DRAFT_PR = 90
ACCEPTED_HEAD = "a9c236384b32140d1b0a213cfbdb5c5512baab24"
DEDICATED_RUN = 33262841010
CORE_CI_RUN = 33262188693
MERGE_COMMIT = "4ca5d230bc5d3e3f985b9b4ed24386c69f74b539"
POST_MERGE_CORE_CI_RUN = 33263135167


def close_markdown_increment(path: Path, title: str, evidence: str) -> None:
    text = path.read_text(encoding="utf-8")
    opened = f"- [ ] **{title}**"
    closed = f"- [x] **{title}**"
    if text.count(opened) == 1:
        text = text.replace(opened, closed, 1)
    elif text.count(closed) != 1:
        raise RuntimeError(f"cannot locate unique increment heading in {path}: {title}")
    if evidence not in text:
        start = text.index(closed)
        match = re.search(r"(?m)^- \[[ x]\] \*\*Increment \d+", text[start + len(closed) :])
        end = len(text) if match is None else start + len(closed) + match.start()
        block = text[start:end].rstrip()
        text = text[:start] + block + "\n" + evidence + "\n\n" + text[end:].lstrip("\n")
    path.write_text(text, encoding="utf-8")


def main() -> None:
    roadmap_path = Path("docs/roadmap/nodal-development-todo.md")
    roadmap = roadmap_path.read_text(encoding="utf-8")
    if "**Revision:** 1.41" in roadmap:
        roadmap = roadmap.replace("**Revision:** 1.41", "**Revision:** 1.42", 1)
    elif "**Revision:** 1.42" not in roadmap:
        raise RuntimeError("unexpected main-roadmap revision")
    roadmap_path.write_text(roadmap, encoding="utf-8")
    close_markdown_increment(
        roadmap_path,
        "Increment 133 — Analog semantic API and analysis contract design gate",
        "  - Evidence: approved `NodalEquationComponentApi-DG-v0.1` and `NodalContinuousTimeApi-DG-v0.1`; implementation PR #91 (superseding draft PR #90); exact accepted head `a9c236384b32140d1b0a213cfbdb5c5512baab24`; dedicated run `33262841010`; accepted Core CI run `33262188693`; squash merge `4ca5d230bc5d3e3f985b9b4ed24386c69f74b539`; post-merge Core CI run `33263135167`.",
    )
    if "- [ ] **Increment 32 — First-class analog equations, blocks, and contribution semantics**" not in roadmap_path.read_text(encoding="utf-8"):
        raise RuntimeError("Increment 32 must remain unchecked during Increment 133 closure")

    close_markdown_increment(
        Path("docs/roadmap/continuous-time-ams-v0.1-plan.md"),
        "Increment 133 — Analog semantic API and analysis contract design gate",
        "  - Evidence: implementation PR #91; exact accepted head `a9c236384b32140d1b0a213cfbdb5c5512baab24`; dedicated run `33262841010`; merge commit `4ca5d230bc5d3e3f985b9b4ed24386c69f74b539`; post-merge Core CI run `33263135167`.",
    )

    architecture_path = Path("docs/roadmap/continuous-time-ams-v0.1-surface.json")
    architecture = json.loads(architecture_path.read_text(encoding="utf-8"))
    architecture["status"] = "public-api-validated"
    architecture.setdefault("implementation_status", {})["public_api_frozen"] = True
    architecture["validation"] = {
        "implementation_pull_request": IMPLEMENTATION_PR,
        "superseded_draft_pull_request": SUPERSEDED_DRAFT_PR,
        "accepted_head": ACCEPTED_HEAD,
        "dedicated_workflow_run": DEDICATED_RUN,
        "accepted_core_ci_run": CORE_CI_RUN,
        "merge_commit": MERGE_COMMIT,
        "post_merge_core_ci_run": POST_MERGE_CORE_CI_RUN,
    }
    entries = [entry for entry in architecture.get("roadmap", []) if entry.get("increment") == 133]
    if len(entries) != 1:
        raise RuntimeError("continuous-time architecture must contain exactly one Increment 133 entry")
    entries[0]["status"] = "complete"
    architecture_path.write_text(json.dumps(architecture, indent=2) + "\n", encoding="utf-8")

    surface_path = Path("core/scala/api/public-api-continuous-time-v0.1.json")
    surface = json.loads(surface_path.read_text(encoding="utf-8"))
    surface["status"] = "validated-analog-semantic-api"
    surface["validation"] = {
        "implementation_pull_request": IMPLEMENTATION_PR,
        "accepted_head": ACCEPTED_HEAD,
        "dedicated_workflow_run": DEDICATED_RUN,
        "core_ci_run": CORE_CI_RUN,
        "merge_commit": MERGE_COMMIT,
        "post_merge_core_ci_run": POST_MERGE_CORE_CI_RUN,
    }
    surface_path.write_text(json.dumps(surface, indent=2) + "\n", encoding="utf-8")

    manifest_path = Path("tests/api/fixtures/increment133/manifest.json")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["status"] = "validated-analog-semantic-api"
    manifest["validation"] = {
        "implementation_pull_request": IMPLEMENTATION_PR,
        "superseded_draft_pull_request": SUPERSEDED_DRAFT_PR,
        "accepted_head": ACCEPTED_HEAD,
        "dedicated_workflow_run": DEDICATED_RUN,
        "core_ci_run": CORE_CI_RUN,
        "merge_commit": MERGE_COMMIT,
        "post_merge_core_ci_run": POST_MERGE_CORE_CI_RUN,
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
