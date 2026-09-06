#!/usr/bin/env python3
"""Check that Increment 37 is resumable without making a premature completion claim."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    "core/scala/api/src/nodal/AnalogEventContract.scala",
    "core/scala/api/src/nodal/AnalogEventRuntime.scala",
    "core/scala/testkit/test/src/nodal/AnalogEventConstructionTests.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/AnalogEventBridgeTests.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/Increment37MlirCheck.scala",
    "core/compiler/include/nodal/Dialect/Nodal/AnalogEvents.h",
    "core/compiler/lib/Dialect/Nodal/AnalogEvents.cpp",
    "core/compiler/test/IR/analog-events.mlir",
    "examples/continuousTimeApi/src/nodal/increment37fixture/Increment37ConstructionCheck.scala",
    "tests/compiler/fixtures/increment37/run_native_matrix.py",
    "tests/compiler/fixtures/increment37/run_review_matrix.py",
    "core/compiler/lib/Backend/AnalogEventBackend.cpp",
    "core/compiler/lib/Backend/AnalogEventReparse.cpp",
    "core/compiler/test/IR/analog-events-held.mlir",
    "docs/design-gates/NodalAnalogEvents-DG-v0.1.md",
    "docs/implementation/increment37-analog-events.md",
    ".github/workflows/increment-37-analog-events.yml",
)


def check_repository(root: Path = ROOT) -> None:
    for name in REQUIRED:
        if not (root / name).is_file():
            raise AssertionError(f"NODAL-INC37: missing {name}")
    manifest = json.loads((root / "tests/compiler/fixtures/increment37/manifest.json").read_text())
    if manifest["increment"] != 37 or manifest["status"] != "implementation-in-progress":
        raise AssertionError("NODAL-INC37: unknown implementation state")
    if manifest["validation"] is not None or not manifest["remaining"]:
        raise AssertionError("NODAL-INC37: foundation cannot claim accepted closure")
    roadmap = (root / "docs/roadmap/nodal-development-todo.md").read_text()
    if roadmap.count("- [ ] **Increment 37 — Analog events**") != 1:
        raise AssertionError("NODAL-INC37: premature or ambiguous roadmap closure")
    native = (root / "core/compiler/lib/Dialect/Nodal/AnalogEvents.cpp").read_text()
    for required in ("ExpressionParser", "getParameterUnitSymbol", "isBeforeInBlock", "allReads", "std::isfinite"):
        if required not in native:
            raise AssertionError(f"NODAL-INC37: missing independent native check {required}")
    matrix = (root / "tests/compiler/fixtures/increment37/run_native_matrix.py").read_text()
    if 'run_review_matrix.py' not in matrix or 'subprocess.run(review, check=True' not in matrix:
        raise AssertionError("NODAL-INC37: missing mandatory lowering review matrix")
    if "NODAL-BACKEND-CAPABILITY-001" not in matrix or "PIPELINE" not in matrix:
        raise AssertionError("NODAL-INC37: missing optimizer or fail-closed backend gate")


if __name__ == "__main__":
    check_repository()
    print("Increment 37 foundation repository contract: PASS (increment remains open)")
