#!/usr/bin/env python3
"""Guard transfer scope, qualification wiring, and honest acceptance state."""
from __future__ import annotations
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = "tests/compiler/fixtures/increment40/manifest.json"
REQUIRED = (
    "core/scala/api/src/nodal/AnalogTransferApi.scala",
    "core/scala/api/src/nodal/AnalogTransferContract.scala",
    "core/compiler/include/nodal/Dialect/Nodal/AnalogTransfer.h",
    "core/compiler/lib/Dialect/Nodal/AnalogTransfer.cpp",
    "core/compiler/test/Unit/AnalogTransferReparseTest.cpp",
    "core/scala/testkit/test/src/nodal/AnalogTransferTests.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/Increment40MlirCheck.scala",
    "examples/continuousTimeApi/src/nodal/increment40fixture/Increment40ConstructionCheck.scala",
    "tests/compiler/fixtures/increment40/run_native_matrix.py",
    "docs/design-gates/NodalTransferOperators-DG-v0.1.md",
    "docs/implementation/increment40-transfer-operators.md",
    ".github/workflows/increment-40-transfer-operators.yml",
)


def require(condition: bool, message: str):
    if not condition:
        raise AssertionError(f"NODAL-INC40: {message}")


def check_repository(root: Path = ROOT):
    for path in REQUIRED:
        require((root / path).is_file(), f"missing {path}")
    manifest = json.loads((root / MANIFEST).read_text())
    require(manifest.get("schema") == 1 and manifest.get("increment") == 40, "invalid manifest identity")
    require(manifest.get("contract_version") == "1" and manifest.get("operators") == ["laplace_nd", "zi_nd"], "changed transfer profile")
    require(manifest.get("stateful") is True, "transfer state cannot be pure")
    require(manifest.get("qualification") == "compiler-structural-not-numerical-simulation", "unsupported simulation claim")
    require(manifest.get("status") == "implementation-in-progress" and
            manifest.get("accepted_evidence") is None and bool(manifest.get("remaining")),
            "completion requires a separately reviewed accepted-evidence closure")
    roadmap = (root / "docs/roadmap/nodal-development-todo.md").read_text()
    require(roadmap.count("- [ ] **Increment 40 — Laplace and discrete transfer operators**") == 1 and
            "- [x] **Increment 40 — Laplace and discrete transfer operators**" not in roadmap,
            "premature roadmap closure")
    gate = (root / "docs/design-gates/NodalTransferOperators-DG-v0.1.md").read_text()
    require("**Status:** Approved" in gate and "**Scope:** public-api" in gate, "missing approved public API gate")
    require("increment40/run_native_matrix.py" in (root / "core/compiler/test/CMakeLists.txt").read_text(), "native matrix not registered")
    require("AnalogTransferReparseTest.cpp" in (root / "core/compiler/test/Unit/CMakeLists.txt").read_text(), "reparser unit test not registered")
    workflow = (root / ".github/workflows/increment-40-transfer-operators.yml").read_text()
    require("contents: read" in workflow and "contents: write" not in workflow, "qualification must be read-only")
    for step in ("test_increment40.py", "./nodal core scala", "./nodal core native", "Increment40MlirCheck", "run_native_matrix.py", "--source", "actions/upload-artifact"):
        require(step in workflow, f"missing qualification step: {step}")


if __name__ == "__main__":
    check_repository()
    print("Increment 40 repository contract: PASS (implementation remains open)")
