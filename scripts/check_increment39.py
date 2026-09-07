#!/usr/bin/env python3
"""Guard Increment 39's open implementation state and permanent qualification wiring."""
from __future__ import annotations
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = "tests/compiler/fixtures/increment39/manifest.json"
REQUIRED = (
    "core/scala/api/src/nodal/AnalogNoiseContract.scala",
    "core/compiler/include/nodal/Dialect/Nodal/AnalogNoise.h",
    "core/compiler/lib/Dialect/Nodal/AnalogNoise.cpp",
    "core/scala/testkit/test/src/nodal/AnalogNoiseTests.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/Increment39MlirCheck.scala",
    "examples/continuousTimeApi/src/nodal/increment39fixture/Increment39ConstructionCheck.scala",
    "tests/compiler/fixtures/increment39/run_native_matrix.py",
    "docs/design-gates/NodalNoiseOperators-DG-v0.1.md",
    "docs/implementation/increment39-noise-operators.md",
    ".github/workflows/increment-39-noise-operators.yml",
)


def require(condition: bool, message: str):
    if not condition:
        raise AssertionError(f"NODAL-INC39: {message}")


def check_repository(root: Path = ROOT):
    for path in REQUIRED:
        require((root / path).is_file(), f"missing {path}")
    manifest = json.loads((root / MANIFEST).read_text())
    require(manifest.get("schema") == 1 and manifest.get("increment") == 39, "invalid manifest identity")
    require(manifest.get("contract_version") == "1" and manifest.get("operators") == ["white", "flicker", "table"], "changed operator profile")
    require(manifest.get("analyses") == ["noise"], "unqualified analysis capability")
    require(manifest.get("status") == "implementation-in-progress" and
            manifest.get("accepted_evidence") is None and bool(manifest.get("remaining")),
            "completion requires a separately reviewed accepted-evidence closure")
    roadmap = (root / "docs/roadmap/nodal-development-todo.md").read_text()
    require(roadmap.count("- [ ] **Increment 39 — Noise operators**") == 1 and
            "- [x] **Increment 39 — Noise operators**" not in roadmap, "premature roadmap closure")
    gate = (root / "docs/design-gates/NodalNoiseOperators-DG-v0.1.md").read_text()
    require("**Status:** Approved" in gate and "**Scope:** public-api" in gate, "missing approved public API gate")
    require("increment39/run_native_matrix.py" in (root / "core/compiler/test/CMakeLists.txt").read_text(), "native matrix not registered")
    workflow = (root / ".github/workflows/increment-39-noise-operators.yml").read_text()
    require("contents: read" in workflow and "contents: write" not in workflow, "qualification must be read-only")
    for step in ("test_increment39.py", "./nodal core scala", "./nodal core native", "Increment39MlirCheck", "run_native_matrix.py", "--source", "actions/upload-artifact"):
        require(step in workflow, f"missing qualification step: {step}")


if __name__ == "__main__":
    check_repository()
    print("Increment 39 repository contract: PASS (implementation remains open)")
