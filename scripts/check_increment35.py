#!/usr/bin/env python3
from __future_ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]

IMPLEMENTATION_PR = 113
ACCEPTED_HEAD = "d3410f6f64dc66df27d9c7f545c9e78f62695f2e"
EXACT_HEAD_WORKFLOW_COUNT = 25
EXACT_HEAD_CORE_CI_RUN = 33890457304
IMPLEMENTATION_MERGE = "7763e1524f31e4c2c41b11acb200670c360f0fde"
POST_MERGE_CORE_CI_RUN = 33892575717
EXACT_POST_MERGE_VALIDATION_RUN = 33892632854
CLOSURE_PR = 114

OPEN_STATUS = "implementation-in-progress"
CANDIDATE_STATUS = "evidence-closure-candidate"
VALIDATED_STATUS = "validated-differential-integral-operators"


class CheckFailure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CheckFailure(message)


def read_text(root: Path, relative: str) -> str:
    path = root / relative
    require(path.is_file(), f"NODAL-INC35-001: missing required file {relative}")
    return path.read_text(encoding="utf-8")


def load_json(root: Path, relative: str) -> dict[str, Any]:
    try:
        value = json.loads(read_text(root, relative))
    except json.JSONDecodeError as error:
        raise CheckFailure(f"NODAL-INC35-002: invalid JSON in {relative}: {error}") from error
    require(isinstance(value, dict), f"NODAL-INC35-003: {relative} must contain an object")
    return value


def require_tokens(content: str, tokens: tuple[str, ...], label: str) -> None:
    for token in tokens:
        require(token in content, f"NODAL-INC35-004: {label} is missing {token!r}")


def valid_sha(value: object) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 40
        and all(character in "0123456789abcdef" for character in value)
     )


def require_accepted_implementation(validation: object) -> dict[str, Any]:
    require(
        isinstance(validation, dict),
        "NODAL-INC35-015: closure state requires an evidence object",
    )
    assert isinstance(validation, dict)
    required = (
        "implementation_pull_request",
        "accepted_head",
        "exact_head_workflow_count",
        "exact_head_core_ci_run",
        "implementation_merge",
        "post_merge_core_ci_run",
        "exact_post_merge_validation_run",
        "closure_pull_request",
    )
    require(
        all(validation.get(field) for field in required),
        "NODAL-INC35-015: closure evidence lacks the accepted implementation identity",
    )
    require(
        validation.get("implementation_pull_request") == IMPLEMENTATION_PR
        and validation.get("accepted_head") == ACCEPTED_HEAD
        and validation.get("exact_head_workflow_count") == EXACT_HEAD_WORKFLOW_COUNT
        and validation.get("exact_head_core_ci_run") == EXACT_HEAD_CORE_CI_RUN
        and validation.get("implementation_merge") == IMPLEMENTATION_MERGE
        and validation.get("post_merge_core_ci_run") == POST_MERGE_CORE_CI_RUN
        and validation.get("exact_post_merge_validation_run")
        == EXACT_POST_MERGE_VALIDATION_RUN
        and validation.get("closure_pull_request") == CLOSURE_PR,
        "NODAL-INC35-016: closure evidence does not match the accepted implementation",
    )
    return validation


def check_repository(root: Path) -> None:
    roadmap = read_text(root, "docs/roadmap/nodal-development-todo.md")
    predecessor = load_json(root, "tests/compiler/fixtures/increment34/manifest.json")
    manifest = load_json(root, "tests/compiler/fixtures/increment35/manifest.json")
    implementation = read_text(
        root, "docs/implementation/increment35-differential-integral-operators.md"
    )

    increment35_open = "- [ ] **Increment 35 — Differential and integral operators**"
    increment35_closed = "- [x] **Increment 35 — Differential and integral operators**"
    require(
        (increment35_open in roadmap) != (increment35_closed in roadmap),
        "NODAL-INC35-005: Increment 35 roadmap state is missing or ambiguous",
    )
    require(
        "- [x] **Increment 34 — Analog control flow**" in roadmap,
        "NODAL-INC35-005: roadmap does not preserve the validated predecessor",
    )

    predecessor_validation = predecessor.get("validation")
    require(
        predecessor.get("increment") == 34
        and predecessor.get("status") == "validated-analog-control-flow"
        and isinstance(predecessor_validation, dict)
        and predecessor_validation.get("implementation_pull_request") == 109
        and predecessor_validation.get("accepted_head")
        == "207fd1b580e9428e9948cd4e4bd8f2060fde4b79"
        and predecessor_validation.get("implementation_merge")
        == "a9d3ec507f9953c41e7b9cf1d8bd6a2c5c9afd49"
        and predecessor_validation.get("exact_post_merge_validation_run") == 33759112770
        and predecessor_validation.get("closure_pull_request") == 111
        and predecessor_validation.get("closure_validation_head")
        == "b59ed10f423d4a66e7e47d66ec764b7ff22531e7"
        and predecessor_validation.get("closure_validation_run") == 33761024228,
        "NODAL-INC35-006: Increment 34 lacks the accepted validation and closure evidence",
    )

    status = manifest.get("status")
    require(
        manifest.get("schema") == 1
        and manifest.get("increment") == 35
        and status in {OPEN_STATUS, CANDIDATE_STATUS, VALIDATED_STATUS},
        "NODAL-INC35-007: Increment 35 manifest identity or status is invalid",
    )
    validation = manifest.get("validation")
    if status == OPEN_STATUS:
        require(
            manifest.get("tranche") == "35a-differential-integral-operator-contract"
            and validation is None,
            "NODAL-INC35-007: open Increment 35 manifest state is invalid",
        )
        require(
            "**Revision:** 1.45" in roadmap
            and increment35_open in roadmap
            and "**Status:** In progress" in implementation,
            "NODAL-INC35-017: open state requires roadmap revision 1.45 and in-progress records",
        )
    elif status == CANDIDATE_STATUS:
        accepted = require_accepted_implementation(validation)
        require(
            manifest.get("tranche") == "35b-evidence-closure"
            and accepted.get("closure_validation_head") is None
            and accepted.get("closure_validation_run") is None,
            "NODAL-INC35-018: closure candidate must not claim its own validation",
        )
        require(
            "**Revision:** 1.46" in roadmap
            and increment35_closed in roadmap
            and "**Status:** Closure candidate" in implementation,
            "NODAL-INC35-019: closure candidate requires revision 1.46 and closed candidate records",
        )
        evidence = read_text(root, "docs/implementation/increment35-evidence-closure.md")
        require_tokens(
            evidence,
            (
                "**Status:** Closure candidate awaiting exact-head validation",
                "**Implementation PR:** #113",
                f**Accepted implementation head:** `{ACCEPTED_HEAD}`",
                "**Exact-head workflow matrix:** 25 successful workflows",
                f"**Exact-head Core CI:** `{EXACT_HEAD_CORE_CI_RUN}`",
                f"**Implementation merge:** `{IMPLEMENTATION_MERGE}`",
                f**Post-merge Core CI:** `{POST_MERGE_CORE_CI_RUN}`",
                f"**Exact post-merge validation:** `{EXACT_POST_MERGE_VALIDATION_RUN}`",
                "**Closure PR:** #114",
                "**Closure validation head:** pending",
                "**Closure validation run:** pending",
            ),
            "closure-candidate evidence record",
        )
    else:
        accepted = require_accepted_implementation(validation)
        require(
            manifest.get("tranche") == "35b-evidence-closure"
            and valid_sha(accepted.get("closure_validation_head"))
            and isinstance(accepted.get("closure_validation_run"), int)
            and accepted.get("closure_validation_run") > 0,
            "NODAL-INC35-020: validated closure lacks exact candidate evidence",
        )
        require(
            "**Revision:** 1.46" in roadmap
            and increment35_closed in roadmap
            and "**Status:** Validated" in implementation,
            "NODAL-INC35-021: validated state requires roadmap revision 1.46 and closed records",
        )
        evidence = read_text(root, "docs/implementation/increment35-evidence-closure.md")
        require_tokens(
            evidence,
            (
                "**Status:** Validated evidence closure",
                "**Implementation PR:* #113",
                f"**Accepted implementation head:** `{ACCEPTED_HEAD}`",
                "**Exact-head workflow matrix:** 25 successful workflows",
                f"**Exact-head Core CI:** `{EXACT_HEAD_CORE_CI_RUN}`",
                f**Implementation merge:** `{IMPLEMENTATION_MERGE}`",
                f"**Post-merge Core CI:** `{POST_MERGE_CORE_CI_RUN}`",
                f**Exact post-merge validation:** `{EXACT_POST_MERGE_VALIDATION_RUN}`",
                "**Closure PR:* #114",
                f"**Closure validation head:** `{accepted['closure_validation_head']}`",
                f*"*Closure validation run:** `{accepted['closure_validation_run']}`",
            ),
            "validated evidence-closure record",
        )

    baseline = manifest.get("baseline")
    require(
        isinstance(baseline, dict)
        and baseline.get("stacked_on_increment") == 34
        and baseline.get("increment_34_head") == predecessor_validation.get("accepted_head")
        and baseline.get("increment_34_manifest") == predecessor.get("status")
        and baseline.get("increment_34_implementation_merge")
        == predecessor_validation.get("implementation_merge")
        and baseline.get("increment_34_exact_post_merge_validation_run")
        == predecessor_validation.get("exact_post_merge_validation_run")
        and baseline.get("increment_34_closure_pr")
        == predecessor_validation.get("closure_pull_request")
        and baseline.get("increment_34_closure_validation_head")
        == predecessor_validation.get("closure_validation_head")
        and baseline.get("increment_34_closure_validation_run")
        == predecessor_validation.get("closure_validation_run")
        and baseline.get("increment_34_dev_head")
        == "4c669a514e1fca42a254c4d842c8e1ad999e0e88"
        and baseline.get("roadmap_revision") == "1.45",
        "NODAL-INC35-008: Increment 35 is not pinned to the validated Increment 34 baseline",
    )

    semantics = manifest.get("semantics")
    for key in (
        "ddt_subtracts_time_dimension",
        "idt_adds_time_dimension",
        "idt_owns_stable_state",
        "fixed_initial_condition",
        "solver_selected_initial_condition",
        "dimension_polymorphic_exact_zero_initial",
        "declarative_context_only",
        "initial_equation_context_rejected",
        "procedural_context_rejected",
        "analysis_applicability_explicit",
        "ddt_time_invariant_zero_annotation",
        "ddt_authored_operation_retained",
        "idt_folding_prohibited",
    ):
        require(
            isinstance(semantics, dict) and semantics.get(key) is True,
            f"NODAL-INC35-009: semantic contract {key!r} is not enabled",
        )
    require(
        semantics.get("inverse_operator_cancellation") is False
        and semantics.get("operator_distribution") is False,
        "NODAL-INC35-010: unsafe algebraic transforms were enabled",
    )

    integration = manifest.get("integration")
    for key in (
        "public_scala_api",
        "construction_snapshot",
        "source_map",
        "scala_to_mlir_inventory",
        "first_class_ddt_ir",
        "first_class_idt_ir",
        "native_ir_verification",
        "compiler_boundary_diagnostics",
        "constant_pass_integration",
        "verilog_a_vertical_slice",
    ):
        require(
            isinstance(integration, dict) and integration.get(key) is True,
            f"NODAL-INC35-011: integration contract {key!r} is not enabled",
        )
    require(
        integration.get("full_dae_solver_lowering") is False,
        "NODAL-INC35-012: full DAE solver lowering must remain deferred",
    )

    public_api = read_text(root, "core/scala/api/src/nodal/CandidateApi.scala")
    require_tokens(
        public_api,
        (
            'continuousOperator("analog_ddt"',
            'continuousOperator("analog_idt"',
            "initialValue: Option[Expr[Real]]",
            "ConstructionKernel.continuousOperator",
        ),
        "public differential/integral API",
    )
    require("unsupported_idt" not in public_api, "NODAL-INC35-013: the idt placeholder remains")

    construction = read_text(root, "core/scala/api/src/nodal/ElaborationConstructionKernel.scala")
    require_tokens(
        construction,
        (
            "KernelContinuousOperatorSnapshot",
            "ContinuousOperatorRecord",
            "def registerContinuousOperator",
            "NODAL-ANALOG-035-001",
            "NODAL-ANALOG-035-004",
            "inputDimension.multiply(AnalogDimension.Time)",
            'Some(s"$path.state")',
            "continuousOperators = continuousOperatorSnapshots",
        ),
        "construction kernel",
    )

    bridge = read_text(root, "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala")
    require_tokens(
        bridge,
        (
            "nodal.bridge.continuous_operators",
            'operator_contract" -> quoted("increment35")',
            '".nodal.analog_idt"',
            ''state_id" -> quoted',
            "continuousOperatorAttributes",
        ),
        "Scala-to-MLIR bridge",
    )

    ops = read_text(root, "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td")
    verifier = read_text(root, "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp")
    op_verifier = read_text(root, "core/compiler/lib/Dialect/Nodal/NodalOps.cpp")
    backend = read_text(root, "core/compiler/lib/Backend/AnalogVerticalSlice.cpp")
    require_tokens(
        ops,
        (
            'Nodal_AnalogIdtOp : Nodal_Op<"analog_idt"',
            "OptionalAttr<StrAttr>:$operator_contract",
            "OptionalAttr<StrAttr>:$state_id",
            "OptionalAttr<ArrayAttr>:$analyses",
        ),
        "native operation definitions",
    )
    require_tokens(
        op_verifier,
        ("AnalogDdtOp::verify", "AnalogIdtOp::verify"),
        "native operation verifier hooks",
    )
    require_tokens(
        verifier,
        (
            "verifyContinuousContract",
            "verifyContinuousAnalyses",
            "verifyDdtSimplification",
            "verifyIdt",
            "ddt-time-invariant-zero",
            "stateful idt cannot be folded",
            "NODAL-ANALOG-035-008",
        ),
        "native differential/integral verifier",
    )
    require_tokens(
        backend,
        (
            'name == "nodal.analog_idt"',
            '"nodal.analog_idt",',
            'llvm::Twime("idt(")',
            "nodal.simplified",
        ),
        "Verilog-A vertical slice",
    )

    required_files = (
        ".github/workflows/increment-35-differential-integral-operators.yml",
        "core/scala/testkit/test/src/nodal/DifferentialIntegralConstructionTests.scala",
        "core/scala/testkit/test/src/nodal/internal/testkit/DifferentialIntegralBridgeTests.scala",
        "examples/continuousTimeApi/src/nodal/increment35fixture/Increment35ConstructionCheck.scala",
        "core/compiler/test/IR/analog-differential-integral.mlir",
        "core/compiler/test/IR/analog-differential-integral-backend.mlir",
        "core/compiler/test/IR/analog-differential-integral-invalid-context.mlir",
        "core/compiler/test/IR/analog-differential-integral-invalid-initial.mlir",
        "core/compiler/test/IR/analog-differential-integral-invalid-state.mlir",
        "core/compiler/test/IR/analog-differential-integral-invalid-simplification.mlir",
        "core/compiler/test/IR/analog-differential-integral-invalid-idt-fold.mlir",
        "docs/design-gates/NodalDifferentialIntegralOperators-DG-v0.1.md",
        "docs/implementation/increment35-differential-integral-operators.md",
        "tests/compiler/fixtures/increment35/README.md",
        "tests/compiler/test_increment35.py",
     )
    for relative in required_files:
        read_text(root, relative)

    cmake = read_text(root, "core/compiler/test/CMakeLists.txt")
    workflow = read_text(root, ".github/workflows/increment-35-differential-integral-operators.yml")
    gate = read_text(root, "docs/design-gates/NodalDifferentialIntegralOperators-DG-v0.1.md")
    require_tokens(
        cmake,
        (
            "analog-differential-integral-roundtrip",
            "analog-differential-integral-retains-idt",
            "analog-differential-integral-backend",
            "analog-differential-integral-rejects-${_fixture}",
        ),
        "native CMake matrix",
    )
    require_tokens(
        workflow,
        (
            "check_increment35.py",
            "DifferentialIntegralConstructionTests",
            "Increment35ConstructionCheck",
            "nodal-fold-analog-constants",
            "NODAL-ANALOG-035-008",
        ),
        "dedicated Increment 35 workflow",
    )
    require_tokens(
        gate,
        (
            "**Increment:** 35",
            "solver-selected initialization",
            "dimension-polymorphic",
            "ddt-time-invariant-zero",
            "NODAL-ANALOG-035-001",
            "NODAL-ANALOG-035-008",
        ),
        "design gate",
    )
    require_tokens(
        implementation,
        (
            "first-class `nodal.analog_ddt` and `nodal.analog_idt`",
            "full declarative residual-DAE lowering remains a later increment",
        ),
        "implementation record",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate Increment 35 repository contracts")
    parser.add_argument("--root", type=Path, default=ROOT)
    arguments = parser.parse_args()
    try:
        check_repository(arguments.root.resolve())
    except CheckFailure as error:
        print(error)
        return 1
    print("Increment 35 repository contract: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
