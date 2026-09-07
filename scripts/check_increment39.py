#!/usr/bin/env python3
"""Validate Increment 39 acceptance without rewriting historical evidence."""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = "docs/implementation/increment39-accepted-evidence.json"
MANIFEST = "tests/compiler/fixtures/increment39/manifest.json"
ROADMAP = "docs/roadmap/nodal-development-todo.md"
IMPLEMENTATION = "docs/implementation/increment39-noise-operators.md"
CLOSURE = "docs/implementation/increment39-evidence-closure.md"
PREDECESSOR = "docs/implementation/increment38-accepted-evidence.json"
ACCEPTED_EVIDENCE_SHA256 = "ee082e6f8a0bf2c9b13f48dfbbc77037f0438709b630682d8603f6aa110d9f15"
PREDECESSOR_EVIDENCE_SHA256 = "0f36a914f9ae31c5659f259fe934665478c723e14c7e22f1c08148ce37545d9c"
OPEN = "- [ ] **Increment 39 — Noise operators**"
CLOSED = "- [x] **Increment 39 — Noise operators**"
VALIDATED = "validated-noise-operators"
SEMANTICS = (
    "effectful_owned_source_identity", "independent_type_arity_dimension_checks",
    "proven_constant_domain_checks", "symbolic_parameter_preservation",
    "independent_reporting_labels", "single_evaluation_for_shared_sources",
    "unused_zero_source_retention", "forged_fold_rejection",
    "deterministic_normalization", "source_map_retention",
    "independent_target_reparse", "no_partial_hdl_on_error",
)
DEFERRED = (
    "numerical-solver-qualification", "general-verilog-ams-qualification",
    "transient-noise", "correlation-groups", "symbolic-table-points",
    "file-and-log-tables", "procedural-and-equation-noise",
    "scala-local-lexical-naming-foundation-153-157-backends-65-72",
)
REQUIRED = (
    EVIDENCE, MANIFEST, ROADMAP, IMPLEMENTATION, CLOSURE, PREDECESSOR,
    "tests/compiler/fixtures/increment38/manifest.json",
    "core/scala/api/src/nodal/AnalogNoiseContract.scala",
    "core/compiler/include/nodal/Dialect/Nodal/AnalogNoise.h",
    "core/compiler/lib/Dialect/Nodal/AnalogNoise.cpp",
    "core/scala/testkit/test/src/nodal/AnalogNoiseTests.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/Increment39MlirCheck.scala",
    "examples/continuousTimeApi/src/nodal/increment39fixture/Increment39ConstructionCheck.scala",
    "tests/compiler/fixtures/increment39/run_native_matrix.py",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/Unit/CMakeLists.txt",
    "core/compiler/test/Unit/AnalogNoiseReparseTest.cpp",
    "docs/design-gates/NodalNoiseOperators-DG-v0.1.md",
    ".github/workflows/increment-39-noise-operators.yml",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(f"NODAL-INC39: {message}")


def check_repository(root: Path = ROOT) -> None:
    for path in REQUIRED:
        require((root / path).is_file(), f"missing {path}")
    evidence = (root / EVIDENCE).read_bytes()
    require(hashlib.sha256(evidence).hexdigest() == ACCEPTED_EVIDENCE_SHA256,
            "accepted-evidence checksum changed")
    accepted = json.loads(evidence)
    require(hashlib.sha256((root / PREDECESSOR).read_bytes()).hexdigest() ==
            PREDECESSOR_EVIDENCE_SHA256, "accepted predecessor evidence changed")
    prior = json.loads((root / "tests/compiler/fixtures/increment38/manifest.json").read_text())
    require(prior.get("status") == "validated-mathematical-simulator-functions" and
            prior.get("accepted_evidence") ==
            {"path": PREDECESSOR, "sha256": PREDECESSOR_EVIDENCE_SHA256},
            "predecessor acceptance regressed")
    manifest = json.loads((root / MANIFEST).read_text())
    require(manifest.get("schema") == 1 and manifest.get("increment") == 39,
            "invalid manifest identity")
    require(manifest.get("contract_version") == "1" and
            manifest.get("operators") == ["white", "flicker", "table"], "changed operator profile")
    require(manifest.get("analyses") == ["noise"], "unqualified analysis capability")
    require(manifest.get("semantics") == dict.fromkeys(SEMANTICS, True),
            "semantic obligation disabled or missing")
    require(manifest.get("deferred") == list(DEFERRED), "deferred capability boundary changed")
    roadmap = (root / ROADMAP).read_text()
    require(roadmap.count(OPEN) + roadmap.count(CLOSED) == 1, "ambiguous roadmap state")
    implementation = (root / IMPLEMENTATION).read_text()
    status = manifest.get("status")
    require(status in ("implementation-in-progress", VALIDATED), "unknown implementation state")
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
                "**Status:** Validated" in implementation, "inconsistent validated state")
        revisions = re.findall(r"^\*\*Revision:\*\* ([0-9]+)\.([0-9]+)$", roadmap, re.M)
        require(len(re.findall(r"^\*\*Revision:\*\*", roadmap, re.M)) == 1 and
                len(revisions) == 1 and tuple(map(int, revisions[0])) >= (1, 50),
                "validated Increment 39 requires roadmap revision 1.50 or later")
        closure = (root / CLOSURE).read_text()
        for token in (accepted["accepted_head"], accepted["accepted_tree"],
                      accepted["implementation_merge"], str(accepted["post_merge_core_ci_run"]),
                      str(accepted["post_merge_increment39_run"]), "PR #128"):
            require(token in closure, f"closure omits accepted identity {token}")
        for name, digest in accepted["source_witness_sha256"].items():
            require(name in closure and digest in closure, f"closure omits witness {name}")
        for token in ("Actual generated Verilog-A", "direct implementation-agent review",
                      "not independent automated review", "Foundation 153–157", "65/72",
                      "not numerical noise simulation", "noise_N"):
            require(token in closure, f"closure omits required qualification: {token}")
        for language, hashes in accepted["demonstration_sha256"].items():
            blocks = re.findall(r"^```" + language + r"\n(.*?)^```", closure, re.M | re.S)
            observed = [hashlib.sha256(block.strip().encode()).hexdigest() for block in blocks]
            require(observed == hashes, f"accepted {language} demonstration changed")
    gate = (root / "docs/design-gates/NodalNoiseOperators-DG-v0.1.md").read_text()
    require("**Status:** Approved" in gate and "**Scope:** public-api" in gate,
            "missing approved public API gate")
    require("increment39/run_native_matrix.py" in
            (root / "core/compiler/test/CMakeLists.txt").read_text(), "native matrix not registered")
    require("AnalogNoiseReparseTest.cpp" in
            (root / "core/compiler/test/Unit/CMakeLists.txt").read_text(), "target parser not registered")
    workflow = (root / ".github/workflows/increment-39-noise-operators.yml").read_text()
    require("contents: read" in workflow and "contents: write" not in workflow,
            "qualification must be read-only")
    for step in ("test_increment39.py", "./nodal core scala", "./nodal core native",
                 "Increment39MlirCheck", "run_native_matrix.py", "--source", "actions/upload-artifact"):
        require(step in workflow, f"missing qualification step: {step}")


if __name__ == "__main__":
    check_repository()
    print("Increment 39 repository contract: PASS")
