#!/usr/bin/env python3
"""Record accepted Increment 28 implementation evidence and close the roadmap item."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

manifest_path = ROOT / "tests/compiler/fixtures/increment28/manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

if manifest.get("increment") != 28:
    raise RuntimeError("Increment 28 manifest identity mismatch")
if manifest.get("public_api") != "0.3":
    raise RuntimeError("Increment 28 public API identity changed")
if manifest.get("status") != "implemented-awaiting-evidence":
    raise RuntimeError(f"unexpected Increment 28 status: {manifest.get('status')!r}")
if manifest.get("evidence") != {}:
    raise RuntimeError("Increment 28 pre-evidence manifest is not empty")

manifest["status"] = "validated-electrical-connectivity"
manifest["evidence"] = {
    "pull_request": 76,
    "dedicated_run": 33139417848,
    "core_ci_run": 33139417871,
}
manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

roadmap_path = ROOT / "docs/roadmap/nodal-development-todo.md"
roadmap = roadmap_path.read_text(encoding="utf-8")

replacements = (
    ("**Revision:** 1.35", "**Revision:** 1.36"),
    ("**Updated:** 2026-08-27", "**Updated:** 2026-08-28"),
    (
        "- [ ] **Increment 28 — Electrical nodes, nets, and branches**",
        "- [x] **Increment 28 — Electrical nodes, nets, and branches**",
    ),
)
for old, new in replacements:
    if roadmap.count(old) != 1:
        raise RuntimeError(f"expected exactly one roadmap anchor: {old!r}")
    roadmap = roadmap.replace(old, new, 1)

required = (
    "- [x] **Increment 27 — Natures and disciplines**",
    "- [x] **Increment 28 — Electrical nodes, nets, and branches**",
    "- [ ] **Increment 29 — Parameters, constants, ranges, and units**",
)
for fragment in required:
    if fragment not in roadmap:
        raise RuntimeError(f"roadmap closure invariant is missing: {fragment}")

roadmap_path.write_text(roadmap, encoding="utf-8")
