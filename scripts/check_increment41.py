#!/usr/bin/env python3
"""Guard function scope, qualification wiring, and honest acceptance state."""
from __future__ import annotations
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = "tests/compiler/fixtures/increment41/manifest.json"
REQUIRED = (
    "core/scala/api/src/nodal/AnalogUserFunctionApi.scala",
    "core/scala/api/src/nodal/AnalogUserFunctionRuntime.scala",
    "core/compiler/include/nodal/Dialect/Nodal/AnalogUserFunctions.h",
    "core/compiler/lib/Dialect/Nodal/AnalogUserFunctions.cpp",
    "core/compiler/test/Unit/AnalogUserFunctionReparseTest.cpp",
    "core/scala/testkit/test/src/nodal/AnalogUserFunctionTests.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/Increment41MlirCheck.scala",
    "examples/continuousTimeApi/src/nodal/increment41fixture/Increment41ConstructionCheck.scala",
    "tests/compiler/fixtures/increment41/run_native_matrix.py",
    "docs/design-gates/NodalAnalogUserFunctions-DG-v0.1.md",
    "docs/implementation/increment41-analog-functions.md",
    ".github/workflows/increment-41-analog-functions.yml",
)


def require(condition: bool, message: str):
    if not condition:
        raise AssertionError(f"NODAL-INC41: {message}")


def check_repository(root: Path = ROOT):
    for path in REQUIRED:
        require((root / path).is_file(), f"missing {path}")
    manifest = json.loads((root / MANIFEST).read_text())
    require(manifest.get("schema") == 1 and manifest.get("increment") == 41, "invalid manifest identity")
    require(manifest.get("contract_version") == "1" and manifest.get("profile") == "pure-scalar-module-local-total-return", "changed function profile")
    require(manifest.get("recursion") == "rejected" and manifest.get("overloads") == "rejected", "changed resolution policy")
    require(manifest.get("qualification") == "compiler-structural-not-numerical-simulation", "unsupported simulation claim")
    require(manifest.get("status") == "implementation-in-progress" and
            manifest.get("accepted_evidence") is None and bool(manifest.get("remaining")),
            "completion requires a separately reviewed accepted-evidence closure")
    roadmap = (root / "docs/roadmap/nodal-development-todo.md").read_text()
    require(roadmap.count("- [ ] **Increment 41 — User-defined analog functions**") == 1 and
            "- [x] **Increment 41 — User-defined analog functions**" not in roadmap,
            "premature roadmap closure")
    gate = (root / "docs/design-gates/NodalAnalogUserFunctions-DG-v0.1.md").read_text()
    require("**Status:** Approved" in gate and "**Scope:** public-api" in gate, "missing approved public API gate")
    require("increment41/run_native_matrix.py" in (root / "core/compiler/test/CMakeLists.txt").read_text(), "native matrix not registered")
    require("AnalogUserFunctionReparseTest.cpp" in (root / "core/compiler/test/Unit/CMakeLists.txt").read_text(), "reparser unit test not registered")
    workflow = (root / ".github/workflows/increment-41-analog-functions.yml").read_text()
    require("contents: read" in workflow and "contents: write" not in workflow, "qualification must be read-only")
    for step in ("test_increment41.py", "./nodal core scala", "./nodal core native", "Increment41MlirCheck", "run_native_matrix.py", "--source", "actions/upload-artifact"):
        require(step in workflow, f"missing qualification step: {step}")


if __name__ == "__main__":
    check_repository()
    print("Increment 41 repository contract: PASS (implementation remains open)")
