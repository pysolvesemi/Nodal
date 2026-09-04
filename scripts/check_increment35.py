#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]


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


def check_repository(root: Path) -> None:
    roadmap = read_text(root, "docs/roadmap/nodal-development-todo.md")
    predecessor = load_json(root, "tests/compiler/fixtures/increment34/manifest.json")
    manifest = load_json(root, "tests/compiler/fixtures/increment35/manifest.json")

    require(
        "**Revision:** 1.45" in roadmap
        and "- [x] **Increment 34 — Analog control flow**" in roadmap
        and "- [ ] **Increment 35 — Differential and integral operators**" in roadmap,
        "NODAL-INC35-005: roadmap does not preserve the validated predecessor and open Increment 35 state",
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
        == "a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49"
        and predecessor_validation.get("exact_post_merge_validation_run") == 33759112770
        and predecessor_validation.get("closure_pull_request") == 111
        and predecessor_validation.get("closure_validation_head")
        == "b59ed10f423d4a66e7e47d66ec764b7ff22531e7"
        and predecessor_validation.get("closure_validation_run") == 33761024228,
        "NODAL-INC35-006: Increment 34 lacks the accepted validation and closure evidence",
    )

    require(
        manifest.get("schema") == 1
        and manifest.get("increment") == 35
        and manifest.get("status") == "implementation-in-progress"
        and manifest.get("tranche") == "35a-differential-integral-operator-contract"
        and manifest.get("validation") is None,
        "NODAL-INC35-007: Increment 35 manifest identity or open state is invalid",
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
            '"nodal.analog_idt"',
            '"state_id" -> quoted',
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
        ('name == "nodal.analog_idt"', '"nodal.analog_idt",', 'llvm::Twine("idt(")', "nodal.simplified"),
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
    implementation = read_text(root, "docs/implementation/increment35-differential-integral-operators.md")
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
            "**Status:** In progress",
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
