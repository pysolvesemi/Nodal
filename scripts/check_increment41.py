#!/usr/bin/env python3
"""Guard the accepted analog-function profile and immutable historical evidence."""
from __future__ import annotations
import hashlib
import json
import re
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

EVIDENCE = "docs/implementation/increment41-accepted-evidence.json"
ROADMAP = "docs/roadmap/nodal-development-todo.md"
IMPLEMENTATION = "docs/implementation/increment41-analog-functions.md"
CLOSURE = "docs/implementation/increment41-evidence-closure.md"
PREDECESSOR = "docs/implementation/increment40-accepted-evidence.json"
PREDECESSOR_MANIFEST = "tests/compiler/fixtures/increment40/manifest.json"
ACCEPTED_EVIDENCE_SHA256 = "177082f5edac85be80541384d94529c6fd8f82fe1cdbc39cd87b771398d81f32"
PREDECESSOR_EVIDENCE_SHA256 = "8de26c0bdfe4cba9e0aea79167454a7c298737d2e64a79d6738482d8448ce37f"
OPEN = "- [ ] **Increment 41 — User-defined analog functions**"
CLOSED = "- [x] **Increment 41 — User-defined analog functions**"
VALIDATED = "validated-analog-functions"
SEMANTICS = (
    "module_local_definition_ownership",
    "pure_scalar_real_integer_profile",
    "typed_dimensioned_arguments_and_return",
    "initialized_immutable_locals",
    "single_total_return",
    "recursion_and_overload_rejection",
    "implicit_capture_and_effect_rejection",
    "call_operation_identity_preservation",
    "forged_call_attribute_rejection",
    "source_map_namespace_isolation",
    "deterministic_dependency_ordered_emission",
    "target_reparse",
    "no_partial_hdl_on_error",
)
DEFERRED = (
    "mutable locals, loops and early returns",
    "output/inout, string and array arguments/results",
    "equation/procedural/event calls and cross-module calls",
    "interprocedural optimization, numerical simulation, general Verilog-AMS and synthesis",
)
REQUIRED += (EVIDENCE, ROADMAP, IMPLEMENTATION, CLOSURE, PREDECESSOR,
             PREDECESSOR_MANIFEST, "core/compiler/test/CMakeLists.txt",
             "core/compiler/test/Unit/CMakeLists.txt")


def require(condition: bool, message: str):
    if not condition:
        raise AssertionError(f"NODAL-INC41: {message}")


def check_repository(root: Path = ROOT):
    for path in REQUIRED:
        require((root / path).is_file(), f"missing {path}")
    manifest = json.loads((root / MANIFEST).read_text())
    require(manifest.get("schema") == 1 and manifest.get("increment") == 41,
            "invalid manifest identity")
    require(manifest.get("contract_version") == "1" and
            manifest.get("profile") == "pure-scalar-module-local-total-return",
            "changed function profile")
    require(manifest.get("operations") == [
        "nodal.analog_user_function", "nodal.analog_function_value",
        "nodal.analog_user_call", "nodal.analog_function_return"],
        "changed operation set")
    require(manifest.get("argument_kinds") == ["real", "integer"],
            "changed argument kinds")
    require(manifest.get("recursion") == "rejected" and
            manifest.get("overloads") == "rejected", "changed resolution policy")
    require(manifest.get("qualification") ==
            "compiler-structural-not-numerical-simulation",
            "unsupported simulation claim")
    require(manifest.get("deferred") == list(DEFERRED), "deferred boundary changed")
    require(manifest.get("semantics") == dict.fromkeys(SEMANTICS, True),
            "semantic obligation disabled or missing")

    raw = (root / EVIDENCE).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == ACCEPTED_EVIDENCE_SHA256,
            "accepted-evidence checksum changed")
    accepted = json.loads(raw)
    require(hashlib.sha256((root / PREDECESSOR).read_bytes()).hexdigest() ==
            PREDECESSOR_EVIDENCE_SHA256, "accepted predecessor evidence changed")
    prior = json.loads((root / PREDECESSOR_MANIFEST).read_text())
    require(prior.get("status") == "validated-transfer-operators" and
            prior.get("accepted_evidence") ==
            {"path": PREDECESSOR, "sha256": PREDECESSOR_EVIDENCE_SHA256},
            "predecessor acceptance regressed")

    roadmap = (root / ROADMAP).read_text()
    require(roadmap.count(OPEN) + roadmap.count(CLOSED) == 1,
            "ambiguous roadmap state")
    implementation = (root / IMPLEMENTATION).read_text()
    status = manifest.get("status")
    require(status in ("implementation-in-progress", VALIDATED),
            "unknown acceptance state requires accepted-evidence closure")
    if status == "implementation-in-progress":
        require(manifest.get("accepted_evidence") is None and
                isinstance(manifest.get("remaining"), list) and bool(manifest["remaining"]),
                "open implementation cannot claim accepted-evidence")
        require(OPEN in roadmap and "**Status:** Implementation in progress" in implementation,
                "premature roadmap or implementation closure")
    else:
        require(manifest.get("accepted_evidence") ==
                {"path": EVIDENCE, "sha256": ACCEPTED_EVIDENCE_SHA256},
                "manifest does not reference accepted-evidence")
        require(manifest.get("remaining") == [] and CLOSED in roadmap and
                "**Status:** Validated" in implementation,
                "premature roadmap or inconsistent validated state")
        revisions = re.findall(r"^\*\*Revision:\*\* (\d+)\.(\d+)$", roadmap, re.M)
        require(len(re.findall(r"^\*\*Revision:\*\*", roadmap, re.M)) == 1 and
                len(revisions) == 1 and tuple(map(int, revisions[0])) >= (1, 52),
                "validated Increment 41 requires roadmap revision 1.52 or later")
        closure = (root / CLOSURE).read_text()
        for token in (accepted["accepted_head"], accepted["accepted_tree"],
                      accepted["implementation_merge"],
                      str(accepted["post_merge_core_ci_run"]),
                      str(accepted["post_merge_increment41_run"]),
                      "PR #132", ACCEPTED_EVIDENCE_SHA256):
            require(token in closure, f"closure omits accepted identity {token}")
        for name, digest in accepted["source_witness_sha256"].items():
            require(name in closure and digest in closure,
                    f"closure omits witness {name}")
        for token in ("Actual generated Verilog-A", "direct implementation-agent review",
                      "not independent automated review", "not numerical analog simulation",
                      "module-local pure scalar", "forged call attributes"):
            require(token in closure, f"closure omits required qualification: {token}")
        for language, hashes in accepted["demonstration_sha256"].items():
            blocks = re.findall(r"^```" + language + r"\n(.*?)^```",
                                closure, re.M | re.S)
            observed = [hashlib.sha256(block.strip().encode()).hexdigest()
                        for block in blocks]
            require(observed == hashes, f"accepted {language} demonstration changed")

    gate = (root / "docs/design-gates/NodalAnalogUserFunctions-DG-v0.1.md").read_text()
    require("**Status:** Approved" in gate and "**Scope:** public-api" in gate,
            "missing approved public API gate")
    require("increment41/run_native_matrix.py" in
            (root / "core/compiler/test/CMakeLists.txt").read_text(),
            "native matrix not registered")
    require("AnalogUserFunctionReparseTest.cpp" in
            (root / "core/compiler/test/Unit/CMakeLists.txt").read_text(),
            "reparser unit test not registered")
    workflow = (root / ".github/workflows/increment-41-analog-functions.yml").read_text()
    require("contents: read" in workflow and "contents: write" not in workflow,
            "qualification must be read-only")
    for step in ("test_increment41.py", "./nodal core scala", "./nodal core native",
                 "Increment41MlirCheck", "run_native_matrix.py", "--source",
                 "actions/upload-artifact"):
        require(step in workflow, f"missing qualification step: {step}")


if __name__ == "__main__":
    check_repository()
    print("Increment 41 repository contract: PASS")
