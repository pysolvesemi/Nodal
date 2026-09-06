#!/usr/bin/env python3
"""Check analog-event implementation contracts and immutable accepted evidence."""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    "docs/implementation/increment37-accepted-evidence.json",
    "docs/implementation/increment37-evidence-closure.md",
    "docs/implementation/increment37-review-hardening.md",
    "core/scala/testkit/test/src/nodal/AnalogEventReviewTests.scala",
    "tests/compiler/fixtures/increment37/run_direction_matrix.py",
    "tests/compiler/fixtures/increment37/manifest.json",
    "tests/compiler/fixtures/increment36/manifest.json",
    "docs/implementation/increment36-accepted-evidence.json",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/diagnostics-v0.1.json",
    "docs/roadmap/nodal-development-todo.md",
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


ACCEPTED_EVIDENCE_SHA256 = "395957ad825060eb4d03047c8597a465a61ef778e19cdfd66efc9dcdadd8bcbb"
PREDECESSOR_EVIDENCE_SHA256 = "fac45088ac2a5e45a99fa7370533eade02d8fee9abea3ed19c578b7a4d17fdba"
OPEN = "- [ ] **Increment 37 — Analog events**"
CLOSED = "- [x] **Increment 37 — Analog events**"
VALIDATED = "validated-analog-events"
OPERATORS = {"cross": [1, 2, 3, 4, 5], "above": [1, 2, 3, 4],
             "timer": [1, 2, 3, 4], "initial_step": [0], "final_step": [0]}
SEMANTICS = (
    "owned_event_identity", "closed_cross_direction", "dimensional_tolerances",
    "exact_argument_omission", "analysis_filters", "ordered_event_composition",
    "controlled_statement_order", "event_writes_not_unconditional_initialization",
    "captured_read_order", "initialized_event_held_storage", "continuous_transition_after_events",
    "static_monitor_histories", "runtime_bound_checks", "generated_local_state_rejected",
    "digital_event_compatibility", "optimizer_state_preservation", "no_partial_hdl_on_error",
)
DEFERRED = (
    "per-generated-instance-lexical-storage", "ordinary-event-free-procedure-lowering",
    "numerical-solver-execution", "full-verilog-ams-digital-processes", "analog-digital-cosimulation",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(f"NODAL-INC37: {message}")


def check_repository(root: Path = ROOT) -> None:
    for name in REQUIRED:
        require((root / name).is_file(), f"missing {name}")
    manifest = json.loads((root / "tests/compiler/fixtures/increment37/manifest.json").read_text())
    require(manifest.get("schema") == 1 and manifest.get("increment") == 37,
            "unknown manifest identity")
    require(manifest.get("baseline") == "f6e11c5b3f92ee43b4a6d4fc6af21d478249b961",
            "implementation baseline changed")
    predecessor_bytes = (root / "docs/implementation/increment36-accepted-evidence.json").read_bytes()
    require(hashlib.sha256(predecessor_bytes).hexdigest() == PREDECESSOR_EVIDENCE_SHA256,
            "accepted predecessor evidence changed")
    predecessor = json.loads((root / "tests/compiler/fixtures/increment36/manifest.json").read_text())
    require(predecessor.get("status") == "validated-time-waveform-operators" and
            predecessor.get("validation") == json.loads(predecessor_bytes),
            "Increment 36 predecessor acceptance regressed")
    status = manifest.get("status")
    require(isinstance(status, str) and status in ("implementation-in-progress", VALIDATED),
            "unknown implementation state")
    record_bytes = (root / "docs/implementation/increment37-accepted-evidence.json").read_bytes()
    require(hashlib.sha256(record_bytes).hexdigest() == ACCEPTED_EVIDENCE_SHA256,
            "accepted closure evidence checksum changed")
    accepted = json.loads(record_bytes)
    roadmap = (root / "docs/roadmap/nodal-development-todo.md").read_text()
    require(roadmap.count(OPEN) + roadmap.count(CLOSED) == 1,
            "premature or ambiguous roadmap closure")
    implementation = (root / "docs/implementation/increment37-analog-events.md").read_text()
    if status == "implementation-in-progress":
        require(manifest.get("validation") is None and
                isinstance(manifest.get("remaining"), list) and bool(manifest["remaining"]),
                "open implementation cannot claim accepted closure")
        require(OPEN in roadmap, "premature roadmap closure")
        require("**Status:** Implementation in progress" in implementation,
                "open implementation has a closed status record")
    else:
        require(manifest.get("validation") == accepted,
                "closure evidence differs from accepted implementation")
        require(manifest.get("remaining") == [], "validated implementation has outstanding scope")
        require(CLOSED in roadmap, "validated implementation must remain checked")
        require("**Status:** Validated" in implementation, "validated status record is missing")
        revisions = re.findall(r"^\*\*Revision:\*\* ([0-9]+)\.([0-9]+)$", roadmap, re.M)
        require(len(re.findall(r"^\*\*Revision:\*\*", roadmap, re.M)) == 1 and
                len(revisions) == 1 and tuple(map(int, revisions[0])) >= (1, 48),
                "validated Increment 37 requires roadmap revision 1.48 or later")
        closure = (root / "docs/implementation/increment37-evidence-closure.md").read_text()
        for token in (accepted["accepted_head"], accepted["accepted_tree"],
                      accepted["implementation_merge"], str(accepted["post_merge_core_ci_run"]),
                      str(accepted["post_merge_increment37_run"]), "PR #124"):
            require(token in closure, f"closure record omits accepted identity {token}")
    require(manifest.get("operators") == OPERATORS, "event arity contract changed")
    require(manifest.get("semantics") == dict.fromkeys(SEMANTICS, True),
            "semantic obligation disabled or missing")
    require(manifest.get("deferred") == list(DEFERRED), "deferred capability boundary changed")
    gate = (root / "docs/design-gates/NodalAnalogEvents-DG-v0.1.md").read_text()
    require("**Status:** Approved" in gate and "**Scope:** public-api" in gate,
            "public API design gate is not approved")
    workflow = (root / ".github/workflows/increment-37-analog-events.yml").read_text()
    require("contents: read" in workflow and "contents: write" not in workflow,
            "permanent validation workflow must remain read-only")
    for command in ("./nodal core scala", "./nodal core native", "run_native_matrix.py",
                    "Increment37MlirCheck", "--source", "actions/upload-artifact"):
        require(command in workflow, f"missing validation command {command}")
    cmake = (root / "core/compiler/test/CMakeLists.txt").read_text()
    require("increment37/run_native_matrix.py" in cmake and
            "increment37/run_direction_matrix.py" in cmake,
            "mandatory native event/direction matrix registration is missing")
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
    print("Increment 37 repository contract: PASS")
