#!/usr/bin/env python3
"""Guard the accepted transfer profile and immutable historical evidence."""
from __future__ import annotations
import hashlib
import json
import re
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


EVIDENCE = "docs/implementation/increment40-accepted-evidence.json"
ROADMAP = "docs/roadmap/nodal-development-todo.md"
IMPLEMENTATION = "docs/implementation/increment40-transfer-operators.md"
CLOSURE = "docs/implementation/increment40-evidence-closure.md"
PREDECESSOR = "docs/implementation/increment39-accepted-evidence.json"
PREDECESSOR_MANIFEST = "tests/compiler/fixtures/increment39/manifest.json"
ACCEPTED_EVIDENCE_SHA256 = "8de26c0bdfe4cba9e0aea79167454a7c298737d2e64a79d6738482d8448ce37f"
PREDECESSOR_EVIDENCE_SHA256 = "ee082e6f8a0bf2c9b13f48dfbbc77037f0438709b630682d8603f6aa110d9f15"
OPEN = "- [ ] **Increment 40 — Laplace and discrete transfer operators**"
CLOSED = "- [x] **Increment 40 — Laplace and discrete transfer operators**"
VALIDATED = "validated-transfer-operators"
SEMANTICS = ('effectful_owned_state_identity', 'independent_native_verification', 'immutable_coefficient_order', 'physical_dimension_validation', 'analysis_static_coefficients', 'proven_nonzero_d0', 'positive_sample_timing', 'symbolic_scalar_parameter_preservation', 'omitted_timing_preservation', 'shared_state_single_evaluation', 'independent_cascaded_zero_unused_state_retention', 'recursive_forged_fold_rejection', 'deterministic_roundtrip_and_source_maps', 'target_names_and_reparse', 'no_partial_hdl_on_error')
DEFERRED = ('zero-pole, zero-denominator and numerator-pole forms', 'vector parameter carriers and symbolic coefficient-array length', 'symbolic d0/timing envelope proofs and closed-loop d0=0 filters', 'explicit zero transition, Laplace tolerance and initialization', 'general Verilog-AMS, numerical solver qualification and synthesis', 'scala-local-lexical-naming-foundation-153-157', 'filter-stability-and-numerical-conditioning-proofs')
REQUIRED += (EVIDENCE, ROADMAP, IMPLEMENTATION, CLOSURE, PREDECESSOR,
             PREDECESSOR_MANIFEST, "core/compiler/test/CMakeLists.txt",
             "core/compiler/test/Unit/CMakeLists.txt")


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
    require(manifest.get("coefficient_order") ==
            {"laplace_nd": "ascending_s", "zi_nd": "ascending_z_inverse"},
            "changed coefficient order")
    require(manifest.get("semantics") == dict.fromkeys(SEMANTICS, True),
            "semantic obligation disabled or missing")
    require(manifest.get("deferred") == list(DEFERRED), "deferred boundary changed")
    raw = (root / EVIDENCE).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == ACCEPTED_EVIDENCE_SHA256,
            "accepted-evidence checksum changed")
    accepted = json.loads(raw)
    require(hashlib.sha256((root / PREDECESSOR).read_bytes()).hexdigest() ==
            PREDECESSOR_EVIDENCE_SHA256, "accepted predecessor evidence changed")
    prior = json.loads((root / PREDECESSOR_MANIFEST).read_text())
    require(prior.get("status") == "validated-noise-operators" and
            prior.get("accepted_evidence") ==
            {"path": PREDECESSOR, "sha256": PREDECESSOR_EVIDENCE_SHA256},
            "predecessor acceptance regressed")
    roadmap = (root / ROADMAP).read_text()
    require(roadmap.count(OPEN) + roadmap.count(CLOSED) == 1, "ambiguous roadmap state")
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
                len(revisions) == 1 and tuple(map(int, revisions[0])) >= (1, 51),
                "validated Increment 40 requires roadmap revision 1.51 or later")
        closure = (root / CLOSURE).read_text()
        for token in (accepted["accepted_head"], accepted["accepted_tree"],
                      accepted["implementation_merge"], str(accepted["post_merge_core_ci_run"]),
                      str(accepted["post_merge_increment40_run"]), "PR #130",
                      ACCEPTED_EVIDENCE_SHA256):
            require(token in closure, f"closure omits accepted identity {token}")
        for name, digest in accepted["source_witness_sha256"].items():
            require(name in closure and digest in closure, f"closure omits witness {name}")
        for token in ("Actual generated Verilog-A", "direct implementation-agent review",
                      "not independent automated review", "Foundation 153–157",
                      "not numerical analog simulation", "transfer_N"):
            require(token in closure, f"closure omits required qualification: {token}")
        for language, hashes in accepted["demonstration_sha256"].items():
            blocks = re.findall(r"^```" + language + r"\n(.*?)^```", closure, re.M | re.S)
            observed = [hashlib.sha256(block.strip().encode()).hexdigest() for block in blocks]
            require(observed == hashes, f"accepted {language} demonstration changed")
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
    print("Increment 40 repository contract: PASS")
