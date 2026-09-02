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


def require_tokens(content: str, tokens: tuple[str, ...], code: str, label: str) -> None:
    for token in tokens:
        require(token in content, f"{code}: {label} is missing {token!r}")


def check_repository(root: Path) -> None:
    roadmap = read_text(root, "docs/roadmap/nodal-development-todo.md")
    design_gate = read_text(root, "docs/design-gates/NodalAnalogControlFlow-DG-v0.1.md")
    implementation = read_text(
        root, "docs/implementation/increment34-analog-control-flow.md"
    )
    public_api = read_text(root, "core/scala/api/src/nodal/AnalogControlFlowApi.scala")
    runtime = read_text(root, "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala")
    construction = read_text(
        root, "core/scala/api/src/nodal/AnalogControlFlowConstruction.scala"
    )
    procedural = read_text(
        root, "core/scala/api/src/nodal/AnalogProceduralConstruction.scala"
    )
    construction_tests = read_text(
        root,
        "core/scala/testkit/test/src/nodal/AnalogControlFlowConstructionTests.scala",
    )
    runtime_witness = read_text(
        root,
        "examples/continuousTimeApi/src/nodal/increment34fixture/Increment34RuntimeCheck.scala",
    )
    construction_witness = read_text(
        root,
        "examples/continuousTimeApi/src/nodal/increment34fixture/Increment34ConstructionCheck.scala",
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
        and manifest.get("tranche") == "34b-public-construction",
        "NODAL-INC34-010: manifest identity or tranche is invalid",
    )
    require(
        manifest.get("design_gate")
        == "docs/design-gates/NodalAnalogControlFlow-DG-v0.1.md"
        and manifest.get("public_api")
        == "core/scala/api/src/nodal/AnalogControlFlowApi.scala"
        and manifest.get("scala_runtime")
        == "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
        and manifest.get("construction_bridge")
        == "core/scala/api/src/nodal/AnalogControlFlowConstruction.scala"
        and manifest.get("public_construction_tests")
        == "core/scala/testkit/test/src/nodal/AnalogControlFlowConstructionTests.scala",
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
        "unreachable_read_retention",
        "block_local_declarations",
        "unbounded_loop_rejection",
        "structured_flattening_prohibited",
        "conditional_branch_order_validation",
        "safe_owner_rendered_remap",
        "late_declaration_inventory_retention",
        "pre_control_lexical_scope_retention",
        "static_loop_diagnostic_separation",
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
        "owner_remapped_source_snapshot",
        "increment33_flat_snapshot_separation",
    ):
        require(
            integration.get(key) is True,
            f"NODAL-INC34-016: completed integration {key!r} is not recorded",
        )
    for key in (
        "canonical_construction_snapshot",
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
            f"NODAL-INC34-017: unfinished integration {key!r} must not be claimed complete",
        )

    require(
        manifest.get("stable_diagnostic_prefix") == "NODAL-ANALOG-034-",
        "NODAL-INC34-018: diagnostic prefix is invalid",
    )
    deferred = manifest.get("deferred")
    require(isinstance(deferred, list), "NODAL-INC34-019: deferred must be a list")
    for item in (
        "canonical-construction-snapshot",
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
            f"NODAL-INC34-020: deferred boundary {item!r} is missing",
        )
    require(
        manifest.get("validation") is None,
        "NODAL-INC34-021: validation evidence must remain null before exact-head acceptance",
    )

    require_tokens(
        design_gate,
        (
            "**Status:** Approved for staged implementation",
            "**Scope:** public-api",
            "**Increment:** 34",
            "analogConditional",
            "analogStaticWhen",
            "analogCaseArm",
            "analogRepeat",
            "analogLoop",
            "first-match semantics",
            "intersection of the definitely initialized sets",
            "minimumIterations == 0",
            "runtime-bounded loop",
            "no fall-through",
            "A later `analogWhen` cannot restart the same chain",
            "non-negative exact compile-time trip count",
            "Once an explicit Increment 34 control construct appears",
            "NODAL-ANALOG-034-001",
            "NODAL-ANALOG-034-015",
            "The roadmap item remains unchecked",
        ),
        "NODAL-INC34-022",
        "design gate",
    )

    require_tokens(
        implementation,
        (
            "**Status:** In progress",
            "Tranche 34a — source-semantic foundation",
            "Tranche 34b — public construction",
            "- [x] Bind the accepted spelling to `analogProcedure` construction.",
            "Tranche 34c — bridge and native IR",
            "assignments from the published flat Increment 33 snapshot",
            "runtime branch from being misrepresented",
            "Reject malformed conditional-chain ordering",
            "Preserve pre-control lexical scopes and late declarations",
            "Remap semantic owner paths without replacing incidental text substrings",
        ),
        "NODAL-INC34-023",
        "implementation record",
    )

    require_tokens(
        public_api,
        (
            "def analogConditional",
            "def analogWhen",
            "def analogElseWhen",
            "def analogStaticWhen",
            "def analogStaticElseWhen",
            "def analogOtherwise",
            "def analogCase(selector: Expr[Integer])",
            '@targetName("analogBooleanCase")',
            "def analogStaticCase(selector: Int)",
            "def analogCaseArm(first: Int",
            "def analogCaseDefault",
            "def analogRepeat",
            "def analogLoop",
            "def analogBreak",
            "def analogContinue",
        ),
        "NODAL-INC34-024",
        "public control-flow API",
    )

    require_tokens(
        runtime,
        (
            "private[nodal] object AnalogControlFlowRuntime",
            "enum Stage:",
            "enum LoopStage:",
            "enum CaseLabel:",
            "final case class Declare",
            "final case class Scope",
            "final case class IfThenElse",
            "final case class CaseStatement",
            "final case class Loop",
            "final case class Break",
            "final case class Continue",
            "condition.staticValue.isEmpty || condition.reads.nonEmpty",
            "static condition requires a compile-time Boolean value without dynamic reads",
            "duplicate case label",
            "static loop requires one non-negative exact compile-time trip count",
            '"NODAL-ANALOG-034-009"',
            "runtime loop requires 0 <= minimum <= positive finite maximum",
            "break is legal only in the nearest runtime-bounded loop",
            "continue is legal only in the nearest runtime-bounded loop",
            "private def removeLocals",
            "flow.breaks.map(_ -- locals)",
            "states.tail.foldLeft(first)(_ intersect _)",
            "if loop.minimumIterations == 0 then exits += input",
            "exits ++= body.breaks",
            "exits ++= body.continues",
            "NODAL-ANALOG-034-004",
            "NODAL-ANALOG-034-010",
            "NODAL-ANALOG-034-011",
        ),
        "NODAL-INC34-025",
        "Scala control-flow runtime",
    )

    require(
        "value.replace(owner, canonical)" not in construction,
        "NODAL-INC34-026: owner remapping must not replace incidental substrings",
    )

    require_tokens(
        construction,
        (
            "private[nodal] object AnalogControlFlowConstruction",
            "final case class Snapshot",
            "def remapOwner",
            "source control-flow owner must be non-empty and canonical",
            "def remapRendered",
            "leftBoundary",
            "rightBoundary",
            "final class Builder",
            "def conditionalBranch",
            "analogElseWhen requires a preceding analogWhen branch",
            "analogWhen must be the first branch in analogConditional",
            "if first && frame.branches.nonEmpty then",
            "if !first && frame.branches.isEmpty then",
            "val rightBoundary = end == value.length || value.charAt(end) == '.'",
            "def caseSelection",
            "def caseArm",
            "def loop",
            "def breakStatement",
            "def continueStatement",
            "def finish",
            "AnalogControlFlowRuntime.analyze(frozen)",
            "private[nodal] object AnalogControlFlowInspection",
        ),
        "NODAL-INC34-026",
        "control-flow construction bridge",
    )

    require_tokens(
        procedural,
        (
            "val variableRecords",
            "var controlBuilder",
            "var controlSnapshot",
            "var controlScope",
            "materializeWithControlFlow",
            "captureDeclaration",
            "builder.appendAssignment",
            "if !builder.hasStructuredControl then",
            "builder.lexicalScope(source): builderIdentity =>\n"
            "        if builder.hasStructuredControl then",
            "module.controlScope = module.controlScope :+ stableScope",
            "def conditionalBranch",
            "def integerCase",
            "def runtimeLoop",
            "def breakStatement",
            "def continueStatement",
            "module.variableRecords.toVector",
            "Vector.empty",
            "def controlSnapshots",
        ),
        "NODAL-INC34-027",
        "procedural construction integration",
    )

    require_tokens(
        construction_tests,
        (
            "public conditional retains branches and establishes definite assignment",
            "public conditional missing else preserves the unmatched incoming path",
            "public case with default establishes definite assignment",
            "public case without default preserves the unmatched incoming path",
            "static false branch remains retained",
            "zero-minimum runtime loop does not establish initialization",
            "continue path participates in conservative loop exit intersection",
            "break after assignment preserves the assigned exit state",
            "branch-local declaration remains nested",
            "child control-flow snapshot resolves to the authored instance path",
            "structured branch assignment rejects a foreign component variable",
            "analogElseWhen before analogWhen is rejected",
            "a second analogWhen cannot restart one conditional chain",
            "negative exact repetition uses the static-loop diagnostic",
            "declarations after structured control remain in the flat variable inventory",
            "a lexical scope authored before control remains nested and local",
            "owner remapping changes semantic paths but not incidental substrings",
            "Stopwatch + Parent.child.variable_0 + Topical",
            "assignments.isEmpty",
        ),
        "NODAL-INC34-028",
        "public construction tests",
    )

    require_tokens(
        runtime_witness,
        (
            'expect("NODAL-ANALOG-034-004")',
            'expect("NODAL-ANALOG-034-006")',
            'expect("NODAL-ANALOG-034-009")',
            'expect("NODAL-ANALOG-034-010")',
            'expect("NODAL-ANALOG-034-011")',
            "minimumIterations = 0",
            "minimumIterations = 1",
            "Statement.Continue",
            "Condition.static(false)",
            "Condition.static(true)",
            "static-dead-read",
            "conditional_definite=",
            "case_definite=",
            "loop_definite=",
            "static_definite=",
            "static_loop=NODAL-ANALOG-034-009",
        ),
        "NODAL-INC34-029",
        "source-semantic witness",
    )

    require_tokens(
        construction_witness,
        (
            "Increment34ConditionalFixture",
            "analogConditional",
            "analogCase(mode)",
            "AnalogControlFlowInspection.inspect",
            'missingElse == "NODAL-ANALOG-034-004"',
            'malformedBranchOrder == "NODAL-ANALOG-034-015"',
            "assignments.isEmpty",
            "public_conditional_snapshots=",
            "public_case_snapshots=",
            "public_branch_order=",
        ),
        "NODAL-INC34-030",
        "public construction witness",
    )

    require_tokens(
        readme,
        (
            "both conditional arms",
            "duplicate case labels",
            "zero-minimum loop",
            "`break` and `continue` outside",
            "block-local declarations",
            "authored instance paths",
            "false flat Increment 33",
            "cannot precede `analogWhen`",
            "negative exact repetition",
            "declarations authored after structured control",
            "incidental text substrings",
        ),
        "NODAL-INC34-031",
        "fixture README",
    )

    require(
        "contents: write" not in workflow
        and "pull-requests: write" not in workflow
        and "actions: write" not in workflow,
        "NODAL-INC34-032: Increment 34 workflow must remain read-only",
    )
    require_tokens(
        workflow,
        (
            "name: Increment 34 Analog Control Flow",
            "actions/checkout@v6",
            "AnalogControlFlowApi.scala",
            "AnalogControlFlowConstruction.scala",
            "AnalogControlFlowConstructionTests.scala",
            "python3 scripts/check_increment33.py",
            "python3 scripts/check_increment34.py",
            "test_increment34.py",
            "nodal.increment34fixture.Increment34RuntimeCheck",
            "nodal.increment34fixture.Increment34ConstructionCheck",
            "static_loop=NODAL-ANALOG-034-009",
            "public_branch_order=NODAL-ANALOG-034-015",
            "./nodal core scala",
            "./nodal style check",
            "contents: read",
        ),
        "NODAL-INC34-033",
        "permanent workflow",
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
            f"NODAL-INC34-034: temporary/generated files remain: {path}",
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
