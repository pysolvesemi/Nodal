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
    require(path.is_file(), f"NODAL-INC34-001: missing required file {relative}")
    return path.read_text(encoding="utf-8")


def load_json(root: Path, relative: str) -> dict[str, Any]:
    try:
        value = json.loads(read_text(root, relative))
    except json.JSONDecodeError as error:
        raise CheckFailure(
            f"NODAL-INC34-002: invalid JSON in {relative}: {error}"
        ) from error
    require(isinstance(value, dict), f"NODAL-INC34-003: {relative} must contain an object")
    return value


def check_repository(root: Path) -> None:
    roadmap = read_text(root, "docs/roadmap/nodal-development-todo.md")
    design_gate = read_text(root, "docs/design-gates/NodalAnalogControlFlow-DG-v0.1.md")
    implementation = read_text(
        root, "docs/implementation/increment34-analog-control-flow.md"
    )
    runtime = read_text(root, "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala")
    witness = read_text(
        root,
        "examples/continuousTimeApi/src/nodal/increment34fixture/Increment34RuntimeCheck.scala",
    )
    readme = read_text(root, "tests/compiler/fixtures/increment34/README.md")
    workflow = read_text(
        root, ".github/workflows/increment-34-analog-control-flow.yml"
    )
    predecessor = load_json(root, "tests/compiler/fixtures/increment33/manifest.json")
    manifest = load_json(root, "tests/compiler/fixtures/increment34/manifest.json")

    require(
        "- [ ] **Increment 33 — Analog variables and procedural assignment**" in roadmap,
        "NODAL-INC34-004: stacked predecessor Increment 33 must remain unchecked",
    )
    require(
        "- [ ] **Increment 34 — Analog control flow**" in roadmap,
        "NODAL-INC34-005: Increment 34 must remain unchecked until evidence closure",
    )
    require(
        "- [x] **Increment 34 — Analog control flow**" not in roadmap,
        "NODAL-INC34-006: premature Increment 34 roadmap closure",
    )

    require(
        predecessor.get("increment") == 33
        and predecessor.get("status") == "implementation-in-progress",
        "NODAL-INC34-007: stacked Increment 33 manifest is not the implementation baseline",
    )
    baseline = manifest.get("baseline")
    require(isinstance(baseline, dict), "NODAL-INC34-008: baseline must be an object")
    require(
        baseline.get("stacked_on_increment") == 33
        and baseline.get("increment_33_head")
        == "ea7f7da51e85ba275dac71db7823ba0223f8d4ac"
        and baseline.get("increment_33_manifest") == "implementation-in-progress"
        and baseline.get("roadmap_revision") == "1.43",
        "NODAL-INC34-009: Increment 34 is not pinned to the accepted stacked baseline",
    )
    require(
        manifest.get("schema") == 1
        and manifest.get("increment") == 34
        and manifest.get("status") == "implementation-in-progress"
        and manifest.get("tranche") == "34a-source-semantic-foundation",
        "NODAL-INC34-010: manifest identity or tranche is invalid",
    )
    require(
        manifest.get("design_gate")
        == "docs/design-gates/NodalAnalogControlFlow-DG-v0.1.md"
        and manifest.get("scala_runtime")
        == "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
        and manifest.get("scala_witness")
        == "examples/continuousTimeApi/src/nodal/increment34fixture/Increment34RuntimeCheck.scala",
        "NODAL-INC34-011: manifest artifact paths are invalid",
    )

    semantics = manifest.get("semantics")
    require(isinstance(semantics, dict), "NODAL-INC34-012: semantics must be an object")
    for key in (
        "structured_statement_tree",
        "stable_control_identities",
        "static_runtime_conditions",
        "first_match_conditionals",
        "non_fallthrough_case",
        "integer_boolean_case_selectors",
        "duplicate_case_label_rejection",
        "static_exact_loops",
        "runtime_finite_maximum_loops",
        "break_nearest_runtime_loop",
        "continue_nearest_runtime_loop",
        "branch_sensitive_definite_assignment",
        "missing_else_incoming_path",
        "missing_default_incoming_path",
        "zero_trip_loop_conservative",
        "first_iteration_exit_intersection",
        "reachable_read_before_write",
        "unbounded_loop_rejection",
    ):
        require(
            semantics.get(key) is True,
            f"NODAL-INC34-013: semantic contract {key!r} is not enabled",
        )
    for key in ("case_fallthrough", "labeled_loop_exit"):
        require(
            semantics.get(key) is False,
            f"NODAL-INC34-014: deferred semantic contract {key!r} was enabled",
        )

    integration = manifest.get("integration")
    require(
        isinstance(integration, dict),
        "NODAL-INC34-015: integration state must be an object",
    )
    for key in (
        "public_construction_kernel",
        "compiler_owned_snapshot",
        "scala_to_mlir",
        "first_class_compiler_ir",
        "native_ir_verification",
        "compiler_boundary_diagnostics",
        "source_map_roundtrip",
        "authoritative_serialization",
        "target_lowering",
    ):
        require(
            integration.get(key) is False,
            f"NODAL-INC34-016: unfinished integration {key!r} must not be claimed complete",
        )

    require(
        manifest.get("stable_diagnostic_prefix") == "NODAL-ANALOG-034-",
        "NODAL-INC34-017: diagnostic prefix is invalid",
    )
    deferred = manifest.get("deferred")
    require(isinstance(deferred, list), "NODAL-INC34-018: deferred must be a list")
    for item in (
        "public-construction-integration",
        "scala-to-mlir-control-flow",
        "native-control-flow-ir",
        "native-control-flow-verification",
        "source-map-roundtrip",
        "reproducibility-serialization",
        "residual-dae-construction",
        "solver-execution",
        "target-legalization",
        "verilog-a-lowering",
        "verilog-ams-lowering",
    ):
        require(
            item in deferred,
            f"NODAL-INC34-019: deferred boundary {item!r} is missing",
        )
    require(
        manifest.get("validation") is None,
        "NODAL-INC34-020: validation evidence must remain null before exact-head acceptance",
    )

    for token in (
        "**Status:** Approved for staged implementation",
        "**Increment:** 34",
        "first-match semantics",
        "intersection of the definitely initialized sets",
        "minimumIterations == 0",
        "runtime-bounded loop",
        "no fall-through",
        "NODAL-ANALOG-034-001",
        "NODAL-ANALOG-034-015",
        "The roadmap item remains unchecked",
    ):
        require(
            token in design_gate,
            f"NODAL-INC34-021: design gate is missing {token!r}",
        )

    for token in (
        "**Status:** In progress",
        "Tranche 34a — source-semantic foundation",
        "- [x] Implement branch-sensitive definite-assignment analyzer.",
        "Tranche 34b — public construction",
        "Tranche 34c — bridge and native IR",
        "This checkpoint is deliberately stacked",
    ):
        require(
            token in implementation,
            f"NODAL-INC34-022: implementation record is missing {token!r}",
        )

    for token in (
        "private[nodal] object AnalogControlFlowRuntime",
        "enum Stage:",
        "enum LoopStage:",
        "enum CaseLabel:",
        "sealed trait Statement:",
        "final case class IfThenElse",
        "final case class CaseStatement",
        "final case class Loop",
        "final case class Break",
        "final case class Continue",
        "validateCondition",
        "validateSelector",
        "duplicate case label",
        "static loop requires one exact compile-time trip count",
        "break is legal only in the nearest runtime-bounded loop",
        "continue is legal only in the nearest runtime-bounded loop",
        "private def intersectAll",
        "states.tail.foldLeft(first)(_ intersect _)",
        "Flow(Some(input), Vector.empty, Vector.empty)",
        "if loop.minimumIterations == 0 then exits += input",
        "exits ++= body.breaks",
        "exits ++= body.continues",
        "NODAL-ANALOG-034-004",
        "NODAL-ANALOG-034-010",
        "NODAL-ANALOG-034-011",
    ):
        require(
            token in runtime,
            f"NODAL-INC34-023: Scala control-flow runtime is missing {token!r}",
        )

    for token in (
        'expect("NODAL-ANALOG-034-004")',
        'expect("NODAL-ANALOG-034-006")',
        'expect("NODAL-ANALOG-034-010")',
        'expect("NODAL-ANALOG-034-011")',
        "minimumIterations = 0",
        "minimumIterations = 1",
        "Statement.Continue",
        "Condition.static(false)",
        "Condition.static(true)",
        "conditional_definite=",
        "case_definite=",
        "loop_definite=",
        "static_definite=",
    ):
        require(
            token in witness,
            f"NODAL-INC34-024: executable witness is missing {token!r}",
        )

    for token in (
        "both conditional arms",
        "duplicate case labels",
        "zero-minimum loop",
        "`break` and `continue` outside",
    ):
        require(
            token in readme,
            f"NODAL-INC34-025: fixture README is missing {token!r}",
        )

    require(
        "contents: write" not in workflow
        and "pull-requests: write" not in workflow
        and "actions: write" not in workflow,
        "NODAL-INC34-027: Increment 34 workflow must remain read-only",
    )
    for token in (
        "name: Increment 34 Analog Control Flow",
        "actions/checkout@v6",
        "python3 scripts/check_increment33.py",
        "python3 scripts/check_increment34.py",
        "test_increment34.py",
        "nodal.increment34fixture.Increment34RuntimeCheck",
        "./nodal core scala",
        "./nodal style check",
        "contents: read",
    ):
        require(
            token in workflow,
            f"NODAL-INC34-026: permanent workflow is missing {token!r}",
        )

    forbidden_names = {
        "_inc34_materializer.yml",
        "_increment34_materializer.yml",
        "increment34_materialize.py",
    }
    for path in root.rglob("*"):
        if "__pycache__" in path.parts or path.suffix == ".pyc":
            continue
        require(
            path.name not in forbidden_names,
            f"NODAL-INC34-028: temporary/generated files remain: {path}",
        )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate the Increment 34 analog control-flow checkpoint"
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=ROOT,
        help="repository root to validate",
    )
    arguments = parser.parse_args()
    check_repository(arguments.root.resolve())
    print("Increment 34 analog control-flow checkpoint is structurally valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
