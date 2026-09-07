#!/usr/bin/env python3
"""Validate Increment 38 acceptance without rewriting historical evidence."""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = "docs/implementation/increment38-accepted-evidence.json"
MANIFEST = "tests/compiler/fixtures/increment38/manifest.json"
ROADMAP = "docs/roadmap/nodal-development-todo.md"
IMPLEMENTATION = "docs/implementation/increment38-mathematical-simulator-functions.md"
CLOSURE = "docs/implementation/increment38-evidence-closure.md"
PREDECESSOR = "docs/implementation/increment37-accepted-evidence.json"
ACCEPTED_EVIDENCE_SHA256 = "0f36a914f9ae31c5659f259fe934665478c723e14c7e22f1c08148ce37545d9c"
PREDECESSOR_EVIDENCE_SHA256 = "395957ad825060eb4d03047c8597a465a61ef778e19cdfd66efc9dcdadd8bcbb"
OPEN = "- [ ] **Increment 38 — Mathematical and simulator functions**"
CLOSED = "- [x] **Increment 38 — Mathematical and simulator functions**"
VALIDATED = "validated-mathematical-simulator-functions"
SEMANTICS = (
    "closed_versioned_registry", "independent_type_arity_dimension_checks",
    "provable_constant_domain_checks", "symbolic_parameter_preservation",
    "dynamic_analysis_queries", "forged_fold_rejection", "source_map_retention",
    "ordered_event_reads", "deterministic_normalization",
    "registry_controlled_verilog_a_spelling", "no_partial_hdl_on_error",
)
DEFERRED = (
    "numerical-solver-qualification", "general-verilog-ams-qualification",
    "ordinary-event-free-procedure-lowering", "stateful-limexp-and-simulator-tasks",
    "noise-and-transfer-operators", "user-defined-functions-and-environment-access",
)
REQUIRED = (
    EVIDENCE, MANIFEST, ROADMAP, IMPLEMENTATION, CLOSURE, PREDECESSOR,
    "tests/compiler/fixtures/increment37/manifest.json",
    "core/compiler/analog-functions-v1.json",
    "core/compiler/test/CMakeLists.txt",
    "tests/compiler/fixtures/increment38/run_native_matrix.py",
    "core/scala/testkit/test/src/nodal/AnalogFunctionTests.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/Increment38MlirCheck.scala",
    "examples/continuousTimeApi/src/nodal/increment38fixture/Increment38ConstructionCheck.scala",
    "docs/design-gates/NodalAnalogFunctions-DG-v0.1.md",
    ".github/workflows/increment-38-mathematical-functions.yml",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(f"NODAL-INC38: {message}")


def check_repository(root: Path = ROOT) -> None:
    for relative in REQUIRED:
        require((root / relative).is_file(), f"missing {relative}")
    evidence = (root / EVIDENCE).read_bytes()
    require(hashlib.sha256(evidence).hexdigest() == ACCEPTED_EVIDENCE_SHA256,
            "accepted evidence checksum changed")
    accepted = json.loads(evidence)
    predecessor = (root / PREDECESSOR).read_bytes()
    require(hashlib.sha256(predecessor).hexdigest() == PREDECESSOR_EVIDENCE_SHA256,
            "accepted predecessor evidence changed")
    prior = json.loads((root / "tests/compiler/fixtures/increment37/manifest.json").read_text())
    require(prior.get("status") == "validated-analog-events" and
            prior.get("validation") == json.loads(predecessor), "predecessor acceptance regressed")
    manifest = json.loads((root / MANIFEST).read_text())
    require(manifest.get("schema") == 1 and manifest.get("increment") == 38,
            "unknown manifest identity")
    require(manifest.get("registry_version") == "1" and manifest.get("function_count") == 24 and
            manifest.get("analysis_query_count") == 6, "accepted registry scope changed")
    require(manifest.get("semantics") == dict.fromkeys(SEMANTICS, True),
            "semantic obligation disabled or missing")
    require(manifest.get("deferred") == list(DEFERRED), "deferred capability boundary changed")
    registry = json.loads((root / "core/compiler/analog-functions-v1.json").read_text())
    require(registry.get("registry_version") == "1" and len(registry.get("functions", [])) == 24 and
            len(registry.get("analyses", [])) == 6, "registry identity changed")
    roadmap = (root / ROADMAP).read_text()
    require(roadmap.count(OPEN) + roadmap.count(CLOSED) == 1, "ambiguous roadmap state")
    implementation = (root / IMPLEMENTATION).read_text()
    status = manifest.get("status")
    require(status in ("implementation-in-progress", VALIDATED), "unknown implementation state")
    if status == "implementation-in-progress":
        require(manifest.get("accepted_evidence") is None and
                isinstance(manifest.get("remaining"), list) and bool(manifest["remaining"]),
                "open implementation cannot claim accepted evidence")
        require(OPEN in roadmap and "**Status:** Implementation in progress" in implementation,
                "premature roadmap or implementation closure")
    else:
        require(manifest.get("accepted_evidence") ==
                {"path": EVIDENCE, "sha256": ACCEPTED_EVIDENCE_SHA256},
                "manifest does not reference accepted evidence")
        require(manifest.get("remaining") == [] and CLOSED in roadmap and
                "**Status:** Validated" in implementation, "inconsistent validated state")
        revisions = re.findall(r"^\*\*Revision:\*\* ([0-9]+)\.([0-9]+)$", roadmap, re.M)
        require(len(re.findall(r"^\*\*Revision:\*\*", roadmap, re.M)) == 1 and
                len(revisions) == 1 and tuple(map(int, revisions[0])) >= (1, 49),
                "validated Increment 38 requires roadmap revision 1.49 or later")
        closure = (root / CLOSURE).read_text()
        for token in (accepted["accepted_head"], accepted["accepted_tree"],
                      accepted["implementation_merge"], str(accepted["post_merge_core_ci_run"]),
                      str(accepted["post_merge_increment38_run"]), "PR #126"):
            require(token in closure, f"closure omits accepted identity {token}")
        for name, digest in accepted["source_witness_sha256"].items():
            require(name in closure and digest in closure, f"closure omits witness {name}")
        require("```scala" in closure and "```verilog" in closure and
                "Actual generated Verilog-A" in closure, "completion demonstration is missing")
        for language, hashes in accepted["demonstration_sha256"].items():
            blocks = re.findall(r"^```" + language + r"\n(.*?)^```", closure, re.M | re.S)
            observed = [hashlib.sha256(block.strip().encode()).hexdigest() for block in blocks]
            require(observed == hashes, f"accepted {language} demonstration changed")
    gate = (root / "docs/design-gates/NodalAnalogFunctions-DG-v0.1.md").read_text()
    require("**Status:** Approved" in gate and "**Scope:** public-api" in gate,
            "public API gate is not approved")
    workflow = (root / ".github/workflows/increment-38-mathematical-functions.yml").read_text()
    require("contents: read" in workflow and "contents: write" not in workflow,
            "permanent qualification must remain read-only")
    for token in ("generate_analog_function_registry.py --check", "test_increment38.py",
                  "./nodal core scala", "./nodal core native", "Increment38MlirCheck",
                  "run_native_matrix.py", "--source", "actions/upload-artifact"):
        require(token in workflow, f"missing qualification step {token}")
    cmake = (root / "core/compiler/test/CMakeLists.txt").read_text()
    require("increment38/run_native_matrix.py" in cmake, "mandatory native matrix is missing")


if __name__ == "__main__":
    check_repository()
    print("Increment 38 repository contract: PASS")
