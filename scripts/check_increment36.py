#!/usr/bin/env python3
"""Check the Increment 36 source/native contract and its honest closure state."""
from __future__ import annotations

import argparse
import hashlib
import re
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILES = (
    "docs/implementation/increment36-accepted-evidence.json",
    "docs/implementation/increment36-evidence-closure.md",
    "build.mill",
    "core/scala/testkit/test/src/nodal/internal/testkit/Increment36MlirCheck.scala",
    "core/scala/api/src/nodal/CandidateApi.scala",
    "core/scala/api/src/nodal/ElaborationConstructionKernel.scala",
    "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
    "core/compiler/include/nodal/Dialect/Nodal/TimeWaveform.h",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/lib/Dialect/Nodal/TimeWaveform.cpp",
    "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    "core/compiler/test/IR/analog-time-waveform.mlir",
    "tests/compiler/fixtures/increment36/run_native_matrix.py",
    "core/scala/testkit/test/src/nodal/TimeWaveformConstructionTests.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/TimeWaveformBridgeTests.scala",
    "examples/continuousTimeApi/src/nodal/increment36fixture/Increment36ConstructionCheck.scala",
    ".github/workflows/increment-36-time-waveform-operators.yml",
    "docs/design-gates/NodalTimeWaveformOperators-DG-v0.1.md",
    "docs/implementation/increment36-time-waveform-operators.md",
    "docs/roadmap/nodal-development-todo.md",
    "tests/compiler/fixtures/increment35/manifest.json",
    "tests/compiler/fixtures/increment36/manifest.json",
    "core/compiler/diagnostics-v0.1.json",
)


ACCEPTED_EVIDENCE_SHA256 = "fac45088ac2a5e45a99fa7370533eade02d8fee9abea3ed19c578b7a4d17fdba"


class CheckFailure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CheckFailure(f"NODAL-INC36: {message}")


def check_repository(root: Path) -> None:
    texts = {}
    for path in FILES:
        require((root / path).is_file(), f"missing required file {path}")
        texts[path] = (root / path).read_text(encoding="utf-8")
    manifest = json.loads(texts["tests/compiler/fixtures/increment36/manifest.json"])
    predecessor = json.loads(texts["tests/compiler/fixtures/increment35/manifest.json"])
    require(predecessor["status"] == "validated-differential-integral-operators", "Increment 35 is not closed")
    require(predecessor["validation"]["accepted_head"] == manifest["baseline"]["accepted_head"] ==
            "d3410f6f64dc66df27d9c7f545c9e78f62695f2e", "predecessor identity changed")
    require(manifest["schema"] == 1 and manifest["increment"] == 36, "incorrect manifest identity")
    status = manifest.get("status")
    require(isinstance(status, str) and status in {"implementation-in-progress", "validated-time-waveform-operators"},
            "unsupported Increment 36 closure status")
    record_text = texts["docs/implementation/increment36-accepted-evidence.json"]
    require(hashlib.sha256(record_text.encode("utf-8")).hexdigest() == ACCEPTED_EVIDENCE_SHA256,
            "accepted closure evidence checksum changed")
    accepted = json.loads(record_text)
    roadmap = texts["docs/roadmap/nodal-development-todo.md"]
    opened = "- [ ] **Increment 36 — Time and waveform operators**"
    closed = "- [x] **Increment 36 — Time and waveform operators**"
    require(roadmap.count(opened) + roadmap.count(closed) == 1,
            "missing or ambiguous Increment 36 roadmap state")
    implementation = texts["docs/implementation/increment36-time-waveform-operators.md"]
    if status == "implementation-in-progress":
        require(manifest.get("validation") is None,
                "implementation cannot claim closure evidence")
        require(opened in roadmap, "premature roadmap closure")
        require("**Status:** Implementation in progress" in implementation,
                "open implementation has a closed status record")
    else:
        require(json.dumps(manifest.get("validation"), sort_keys=True) ==
                json.dumps(accepted, sort_keys=True),
                "closure evidence differs from the accepted implementation")
        require(closed in roadmap, "validated implementation must remain checked")
        versions = re.findall(r"^\*\*Revision:\*\* ([0-9]+)\.([0-9]+)$", roadmap, re.M)
        require(len(versions) == 1 and tuple(map(int, versions[0])) >= (1, 47),
                "validated Increment 36 requires roadmap revision 1.47 or later")
        require("**Status:** Validated" in implementation,
                "validated implementation lacks its status record")
        record = texts["docs/implementation/increment36-evidence-closure.md"]
        for token in (accepted["accepted_head"], accepted["implementation_merge"],
                      str(accepted["post_merge_core_ci_run"]),
                      str(accepted["post_merge_increment36_run"]), "PR #118"):
            require(token in record, f"closure record omits accepted identity {token}")
    require(roadmap.count("- [ ] **Increment 37 — Analog events**") == 1 and
            "- [x] **Increment 37 — Analog events**" not in roadmap,
            "Increment 37 remains outside this closure")
    require(all(value is True for value in manifest["semantics"].values()), "semantic obligation disabled")
    require(len(manifest["semantics"]) == 14, "semantic obligation missing")
    require(manifest["operators"] == {"transition": [1, 2, 3, 4, 5], "slew": [1, 2, 3],
                                     "absdelay": [2, 3], "abstime": [0], "bound_step": [1]}, "operator arity contract changed")
    roadmap = texts["docs/roadmap/nodal-development-todo.md"]
    require("- [x] **Increment 35 — Differential and integral operators**" in roadmap, "closed predecessor regressed")
    gate = texts["docs/design-gates/NodalTimeWaveformOperators-DG-v0.1.md"]
    require("**Status:** Approved" in gate and "**Scope:** public-api" in gate, "public API gate is not approved")
    native = texts["core/compiler/lib/Dialect/Nodal/TimeWaveform.cpp"]
    registry = json.loads(texts["core/compiler/diagnostics-v0.1.json"])
    codes = [f"NODAL-ANALOG-036-{index:03d}" for index in range(1, 9)]
    require(manifest["diagnostics"] == registry["families"]["time-waveform"] == codes, "diagnostic inventory changed")
    for code in codes:
        require(code in native, f"native diagnostic missing: {code}")
    for operator in manifest["operators"]:
        require(f'"analog_{operator}"' in texts["core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"], f"native operation missing: {operator}")
        require(f'"nodal.analog_{operator}"' in texts["core/compiler/lib/Backend/AnalogVerticalSlice.cpp"], f"backend operation missing: {operator}")
    require("verifyTimeWaveformOperation" in texts["core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp"], "numeric verifier does not dispatch waveform verification")
    require("waveformNames" in texts["core/compiler/lib/Backend/AnalogVerticalSlice.cpp"], "stateful backend materialization missing")
    workflow = texts[".github/workflows/increment-36-time-waveform-operators.yml"]
    require("contents: read" in workflow and "contents: write" not in workflow, "permanent workflow must be read-only")
    for command in ("./nodal core scala", "./nodal core native", "run_native_matrix.py", "--source", "check_increment35.py"):
        require(command in workflow, f"validation workflow missing {command}")
    example = texts["examples/continuousTimeApi/src/nodal/increment36fixture/Increment36ConstructionCheck.scala"]
    require("nodal.internal" not in example, "public example depends on compiler internals")
    require("core.scala.testkit.test.runMain" in workflow and "Increment36MlirCheck" in workflow,
            "separate compiler-side source witness missing")
    require("numerical-solver-execution" in manifest["deferred"], "solver boundary removed")
    require("full-verilog-ams-lowering" in manifest["deferred"], "full AMS boundary removed")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    arguments = parser.parse_args()
    check_repository(arguments.root)
    print("Increment 36 repository contract: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
