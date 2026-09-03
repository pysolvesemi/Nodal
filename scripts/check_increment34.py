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
        raise CheckFailure(f"NODAL-INC34-002: invalid JSON in {relative}: {error}") from error
    require(isinstance(value, dict), f"NODAL-INC34-003: {relative} must contain an object")
    return value


def require_tokens(content: str, tokens: tuple[str, ...], code: str, label: str) -> None:
    for token in tokens:
        require(token in content, f"{code}: {label} is missing {token!r}")


def check_repository(root: Path) -> None:
    roadmap = read_text(root, "docs/roadmap/nodal-development-todo.md")
    gate = read_text(root, "docs/design-gates/NodalAnalogControlFlow-DG-v0.1.md")
    implementation = read_text(root, "docs/implementation/increment34-analog-control-flow.md")
    public_api = read_text(root, "core/scala/api/src/nodal/AnalogControlFlowApi.scala")
    runtime = read_text(root, "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala")
    procedural_runtime = read_text(root, "core/scala/api/src/nodal/AnalogProceduralRuntime.scala")
    construction = read_text(root, "core/scala/api/src/nodal/AnalogControlFlowConstruction.scala")
    procedural = read_text(root, "core/scala/api/src/nodal/AnalogProceduralConstruction.scala")
    construction_tests = read_text(
        root, "core/scala/testkit/test/src/nodal/AnalogControlFlowConstructionTests.scala"
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
    workflow = read_text(root, ".github/workflows/increment-34-analog-control-flow.yml")
    bridge = read_text(
        root, "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala"
    )
    bridge_tests = read_text(
        root, "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala"
    )
    native_ops = read_text(
        root, "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
    )
    native_verifier = read_text(
        root, "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
    )
    native_cmake = read_text(root, "core/compiler/test/CMakeLists.txt")
    native_fixture = read_text(
        root, "core/compiler/test/IR/analog-control-flow.mlir"
    )
    predecessor = load_json(root, "tests/compiler/fixtures/increment33/manifest.json")
    manifest = load_json(root, "tests/compiler/fixtures/increment34/manifest.json")

    require(
        "- [ ] **Increment 34 — Analog control flow**" in roadmap
        and "- [x] **Increment 34 — Analog control flow**" not in roadmap,
        "NODAL-INC34-004: Increment 34 must remain unchecked until evidence closure",
    )
    predecessor_validation = predecessor.get("validation")
    required_predecessor_evidence = (
        "implementation_pull_request",
        "accepted_head",
        "dedicated_boundary_workflow_run",
        "implementation_merge",
        "post_merge_core_ci_run",
        "exact_post_merge_validation_run",
        "closure_pull_request",
        "closure_validation_head",
        "closure_validation_run",
    )
    require(
        predecessor.get("increment") == 33
        and predecessor.get("status")
        == "validated-analog-procedural-assignment"
        and isinstance(predecessor_validation, dict)
        and all(
            predecessor_validation.get(field)
            for field in required_predecessor_evidence
        ),
        "NODAL-INC34-005: Increment 33 lacks validated predecessor evidence",
    )
    require(
        predecessor_validation.get("implementation_pull_request") == 102
        and predecessor_validation.get("accepted_head")
        == "ea7f7da51e85ba275dac71db7823ba0223f8d4ac"
        and predecessor_validation.get("implementation_merge")
        == "2e0ff291b8d6c0f6dcc4b4c8e27cc33984cff1b8"
        and predecessor_validation.get("exact_post_merge_validation_run")
        == 33714669557
        and predecessor_validation.get("closure_pull_request") == 110,
        "NODAL-INC34-005: Increment 33 predecessor evidence does not match the accepted implementation",
    )
    baseline = manifest.get("baseline")
    require(isinstance(baseline, dict), "NODAL-INC34-006: baseline must be an object")
    require(
        baseline.get("stacked_on_increment") == 33
        and baseline.get("increment_33_head")
        == predecessor_validation.get("accepted_head")
        and baseline.get("increment_33_manifest") == predecessor.get("status")
        and baseline.get("increment_33_implementation_merge")
        == predecessor_validation.get("implementation_merge")
        and baseline.get("increment_33_exact_post_merge_validation_run")
        == predecessor_validation.get("exact_post_merge_validation_run")
        and baseline.get("increment_33_closure_pr")
        == predecessor_validation.get("closure_pull_request")
        and baseline.get("increment_33_closure_validation_head")
        == predecessor_validation.get("closure_validation_head")
        and baseline.get("increment_33_closure_validation_run")
        == predecessor_validation.get("closure_validation_run")
        and isinstance(baseline.get("increment_33_dev_head"), str)
        and len(baseline.get("increment_33_dev_head")) == 40
        and all(
            character in "0123456789abcdef"
            for character in baseline.get("increment_33_dev_head")
        )
        and baseline.get("roadmap_revision") == "1.44",
        "NODAL-INC34-007: Increment 34 is not pinned to the validated Increment 33 baseline",
    )
    require(
        "**Revision:** 1.44" in roadmap
        and "- [x] **Increment 33 — Analog variables and procedural assignment**"
        in roadmap,
        "NODAL-INC34-007: roadmap does not contain the validated Increment 33 predecessor",
    )
    require(
        manifest.get("schema") == 1
        and manifest.get("increment") == 34
        and manifest.get("status") == "implementation-in-progress"
        and manifest.get("tranche") == "34c-native-branch-sensitive-dataflow",
        "NODAL-INC34-008: manifest identity or tranche is invalid",
    )

    semantics = manifest.get("semantics")
    require(isinstance(semantics, dict), "NODAL-INC34-009: semantics must be an object")
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
        "typed_control_expression_payloads",
        "structured_ir_regions",
        "structured_source_map_entries",
        "native_continue_exit_intersection",
        "native_zero_trip_loop_conservative",
        "native_missing_default_intersection",
        "native_missing_else_intersection",
        "canonical_control_owner_identity",
        "pre_control_lexical_scope_alignment",
    ):
        require(
            semantics.get(key) is True,
            f"NODAL-INC34-010: semantic contract {key!r} is not enabled",
        )
    require(
        semantics.get("case_fallthrough") is False
        and semantics.get("labeled_loop_exit") is False,
        "NODAL-INC34-011: deferred control-flow semantics were enabled",
    )

    for key in (
        "native_procedure_wide_identity_uniqueness",
        "native_authored_order_verification",
        "native_guard_read_definite_assignment",
        "native_canonical_case_labels",
        "nested_declaration_locality",
        "native_unreachable_structural_reference_validation",
    ):
        require(
            semantics.get(key) is True,
            f"NODAL-INC34-039: native hardening semantic {key!r} is not enabled",
        )

    for key in (
        "native_canonical_condition_sentinels",
        "native_canonical_loop_sentinels",
    ):
        require(
            semantics.get(key) is True,
            f"NODAL-INC34-043: native staging semantic {key!r} is not enabled",
        )

    integration = manifest.get("integration")
    require(isinstance(integration, dict), "NODAL-INC34-012: integration must be an object")
    for key in (
        "public_construction_kernel",
        "owner_remapped_source_snapshot",
        "increment33_flat_snapshot_separation",
        "canonical_construction_snapshot",
        "scala_to_mlir",
        "first_class_compiler_ir",
        "native_ir_verification",
        "compiler_boundary_diagnostics",
        "source_map_roundtrip",
        "authoritative_serialization",
        "native_branch_sensitive_definite_assignment",
    ):
        require(
            integration.get(key) is True,
            f"NODAL-INC34-013: completed integration {key!r} is not recorded",
        )
    for key in (
        "target_lowering",
    ):
        require(
            integration.get(key) is False,
            f"NODAL-INC34-014: unfinished integration {key!r} must not be claimed complete",
        )
    deferred = manifest.get("deferred")
    require(isinstance(deferred, list), "NODAL-INC34-015: deferred must be a list")
    require(
        "canonical-construction-snapshot" not in deferred
        and "scala-to-mlir-control-flow" not in deferred
        and "native-control-flow-ir" not in deferred
        and "source-map-roundtrip" not in deferred
        and "native-branch-sensitive-definite-assignment" not in deferred,
        "NODAL-INC34-015: completed and deferred integration boundaries are inconsistent",
    )
    require(manifest.get("validation") is None, "NODAL-INC34-015: validation must remain null")

    require_tokens(
        gate,
        (
            "**Increment:** 34",
            "analogConditional",
            "analogCaseArm",
            "analogRepeat",
            "analogLoop",
            "first-match semantics",
            "intersection of the definitely initialized sets",
            "NODAL-ANALOG-034-001",
            "NODAL-ANALOG-034-015",
        ),
        "NODAL-INC34-016",
        "design gate",
    )
    require_tokens(
        implementation,
        (
            "**Status:** In progress",
            "Tranche 34a — source-semantic foundation",
            "Tranche 34b — public construction",
            "Tranche 34c — bridge and native IR",
            "runtime branch from being misrepresented",
            "- [x] Add the control-flow tree to the canonical `ConstructionSnapshot`.",
        ),
        "NODAL-INC34-017",
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
            "def analogCaseArm(first: Int",
            "def analogCaseDefault",
            "def analogRepeat",
            "def analogLoop",
            "def analogBreak",
            "def analogContinue",
        ),
        "NODAL-INC34-018",
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
            "break is legal only in the nearest runtime-bounded loop",
            "continue is legal only in the nearest runtime-bounded loop",
            "flow.breaks.map(_ -- locals)",
            "nested declaration must be block-local",
            "states.tail.foldLeft(first)(_ intersect _)",
            "if loop.minimumIterations == 0 then exits += input",
            "exits ++= body.breaks",
            "exits ++= body.continues",
            "NODAL-ANALOG-034-004",
            "NODAL-ANALOG-034-010",
            "NODAL-ANALOG-034-011",
        ),
        "NODAL-INC34-019",
        "Scala control-flow runtime",
    )
    require_tokens(
        procedural_runtime,
        (
            "Canonical procedural program retained by the construction snapshot",
            "controlFlow: Option[AnalogControlFlowConstruction.Snapshot] = None",
            "Structured Increment 34 programs",
        ),
        "NODAL-INC34-020",
        "canonical procedural snapshot",
    )
    require_tokens(
        construction,
        (
            "private[nodal] object AnalogControlFlowConstruction",
            "final case class Snapshot",
            "def remapOwner",
            "final class Builder",
            "private def requireCanonicalOwner",
            "control-flow source owner",
            "control-flow destination owner",
            "private val canonicalOwner",
            "identityOverride: Option[String] = None",
            "def conditionalBranch",
            "def caseSelection",
            "def caseArm",
            "def loop",
            "def breakStatement",
            "def continueStatement",
            "def finish",
            "AnalogControlFlowRuntime.analyze(frozen)",
            "private[nodal] object AnalogControlFlowInspection",
        ),
        "NODAL-INC34-021",
        "control-flow construction bridge",
    )
    require_tokens(
        procedural,
        (
            "var controlBuilder",
            "var controlSnapshot",
            "materializeWithControlFlow",
            "captureDeclaration",
            "builder.appendAssignment",
            "if !builder.hasStructuredControl then",
            "builder.lexicalScope(source, Some(builderIdentity))",
            "module.controlScope :+ stableScope",
            "def conditionalBranch",
            "def integerCase",
            "def runtimeLoop",
            "def breakStatement",
            "def continueStatement",
            "controlFlow = snapshot.controlFlow.map(_.remapOwner(owner))",
            "module.controlSnapshot",
            "retained.controlFlow.nonEmpty",
            "def controlSnapshots",
        ),
        "NODAL-INC34-022",
        "procedural construction integration",
    )
    require(
        "module.variableRecords.toVector" in procedural and "Vector.empty" in procedural,
        "NODAL-INC34-022: procedural construction integration is missing flat-snapshot separation",
    )
    require_tokens(
        construction_tests,
        (
            "public conditional retains branches and establishes definite assignment",
            "public conditional missing else preserves the unmatched incoming path",
            "public case with default establishes definite assignment",
            "public case without default preserves the unmatched incoming path",
            "zero-minimum runtime loop does not establish initialization",
            "continue path participates in conservative loop exit intersection",
            "branch-local declaration remains nested",
            "child control-flow snapshot resolves to the authored instance path",
            "structured branch assignment rejects a foreign component variable",
            "lexical scope before first control retains one aligned semantic path",
            "control-flow owners are non-empty and canonical",
            "assignments.isEmpty",
            "controlFlow.contains(snapshot)",
        ),
        "NODAL-INC34-023",
        "public construction tests",
    )
    require_tokens(
        runtime_witness,
        (
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
            "nested_nonlocal=NODAL-ANALOG-034-014",
        ),
        "NODAL-INC34-024",
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
            "assignments.isEmpty",
            "public_conditional_snapshots=",
            "public_case_snapshots=",
            "precontrol_scope_aligned=",
            "empty_owner=",
            "padded_owner=",
        ),
        "NODAL-INC34-025",
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
            "canonical owner identities",
            "pre-control lexical scopes",
            "unreachable native regions",
        ),
        "NODAL-INC34-026",
        "fixture README",
    )

    require(
        "contents: write" not in workflow
        and "pull-requests: write" not in workflow
        and "actions: write" not in workflow,
        "NODAL-INC34-027: Increment 34 workflow must remain read-only",
    )
    require_tokens(
        workflow,
        (
            "name: Increment 34 Analog Control Flow",
            "actions/checkout@v6",
            "core/scala/api/src/nodal/AnalogProceduralRuntime.scala",
            "python3 scripts/check_increment33.py",
            "python3 scripts/check_increment34.py",
            "test_increment34.py",
            "nodal.increment34fixture.Increment34RuntimeCheck",
            "nodal.increment34fixture.Increment34ConstructionCheck",
            "./nodal core scala",
            "Build and validate structured native control flow",
            "./nodal core native",
            "analog-control-flow.mlir",
            "./nodal style check",
            "contents: read",
        ),
        "NODAL-INC34-028",
        "permanent workflow",
    )


    require_tokens(
        bridge,
        (
            "private def structuredInventory",
            "private def structuredSourceMapEntries",
            "private def renderStructuredProgram",
            '"nodal.analog_if"',
            '"nodal.analog_if_arm"',
            '"nodal.analog_case"',
            '"nodal.analog_case_arm"',
            '"nodal.analog_loop"',
            '"nodal.analog_break"',
            '"nodal.analog_continue"',
            'expression.identity == value.identity',
            'expression.role == "assignment-value"',
        ),
        "NODAL-INC34-030",
        "structured Scala-to-MLIR bridge",
    )
    require_tokens(
        bridge_tests,
        (
            "BridgeStructuredProceduralTop",
            "structured analog control flow serializes without flattening",
            'first.text.contains("\\"nodal.analog_if\\"")',
            'first.text.contains("\\"nodal.analog_case\\"")',
            'first.text.contains("\\"nodal.analog_loop\\"")',
            "program.assignments.isEmpty",
            "program.controlFlow.nonEmpty",
        ),
        "NODAL-INC34-031",
        "structured bridge tests",
    )
    require_tokens(
        native_ops,
        (
            'Nodal_Op<"analog_if"',
            'Nodal_Op<"analog_if_arm"',
            'Nodal_Op<"analog_case"',
            'Nodal_Op<"analog_case_arm"',
            'Nodal_Op<"analog_loop"',
            'Nodal_Op<"analog_break"',
            'Nodal_Op<"analog_continue"',
        ),
        "NODAL-INC34-032",
        "first-class native control-flow operations",
    )
    require_tokens(
        native_verifier,
        (
            "verifyStructuredProceduralBlock",
            "LogicalResult nodal::AnalogIfOp::verify()",
            "LogicalResult nodal::AnalogCaseOp::verify()",
            "LogicalResult nodal::AnalogLoopOp::verify()",
            "LogicalResult nodal::AnalogBreakOp::verify()",
            "LogicalResult nodal::AnalogContinueOp::verify()",
            '"NODAL-ANALOG-034-002"',
            '"NODAL-ANALOG-034-006"',
            '"NODAL-ANALOG-034-008"',
            '"NODAL-ANALOG-034-010"',
            '"NODAL-ANALOG-034-011"',
        ),
        "NODAL-INC34-033",
        "native structured control-flow verification",
    )
    require_tokens(
        native_cmake,
        (
            "nodal.native.analog-control-flow-roundtrip",
            "nodal.native.analog-control-flow-source-map-roundtrip",
            "analog-control-flow-invalid-${_fixture}.mlir",
        ),
        "NODAL-INC34-034",
        "native structured control-flow tests",
    )
    require_tokens(
        native_fixture,
        (
            '"nodal.analog_if"',
            '"nodal.analog_if_arm"',
            '"nodal.analog_case"',
            '"nodal.analog_case_arm"',
            '"nodal.analog_loop"',
            '"nodal.analog_break"',
            '"nodal.analog_continue"',
            "AnalogControlFlow.scala",
        ),
        "NODAL-INC34-035",
        "native structured control-flow fixture",
    )


    require_tokens(
        native_verifier,
        (
            "verifyStructuredDefiniteAssignment",
            "analyzeStructuredIf",
            "analyzeStructuredCase",
            "analyzeStructuredLoop",
            "intersectStructuredStates",
            '"NODAL-ANALOG-034-004"',
            "body->continues.begin()",
            "minimum.getInt() == 0",
            "unmatchedReachable",
        ),
        "NODAL-INC34-036",
        "native branch-sensitive definite assignment",
    )
    for fixture in (
        "missing-else",
        "missing-default",
        "zero-trip",
        "continue-path",
    ):
        require(
            (root / f"core/compiler/test/IR/analog-control-flow-invalid-{fixture}.mlir").is_file(),
            f"NODAL-INC34-037: missing native dataflow fixture {fixture}",
        )
    require_tokens(
        native_cmake,
        (
            "NAME nodal.native.analog-control-flow-rejects-${_fixture}",
            "foreach(_fixture IN ITEMS missing-else missing-default zero-trip continue-path)",
            "NODAL-ANALOG-034-004",
        ),
        "NODAL-INC34-038",
        "native branch-sensitive dataflow tests",
    )

    require_tokens(
        native_verifier,
        (
            "registerStructuredOperationIdentity",
            "int64_t nextDeclarationOrder = 0",
            "int64_t nextAssignmentOrder = 0",
            '"guard_reads", input, context',
            "isCanonicalStructuredCaseLabel",
            "verifyStructuredReferenceInventory",
            "verifyStructuredReferences",
            "structured declaration order must be contiguous and authored",
            "structured assignment order must be contiguous and authored",
        ),
        "NODAL-INC34-040",
        "native structured verifier hardening",
    )
    for fixture in (
        "duplicate-identity",
        "order",
        "guard-read",
        "case-label",
        "unreachable-reference",
    ):
        require(
            (root / f"core/compiler/test/IR/analog-control-flow-invalid-{fixture}.mlir").is_file(),
            f"NODAL-INC34-041: missing native hardening fixture {fixture}",
        )
    require_tokens(
        native_cmake,
        (
            "analog-control-flow-rejects-duplicate-identity",
            "analog-control-flow-rejects-order",
            "analog-control-flow-rejects-guard-read",
            "analog-control-flow-rejects-case-label",
            "analog-control-flow-rejects-unreachable-reference",
        ),
        "NODAL-INC34-042",
        "native structured hardening tests",
    )

    require_tokens(
        native_verifier,
        (
            "staticPresent.getValue() || staticValue.getValue()",
            "staticPresent.getValue() || staticCount.getInt() != 0",
        ),
        "NODAL-INC34-044",
        "native canonical staging sentinels",
    )
    for fixture in (
        "runtime-static-sentinel",
        "else-static-sentinel",
        "loop-static-sentinel",
    ):
        require(
            (root / f"core/compiler/test/IR/analog-control-flow-invalid-{fixture}.mlir").is_file(),
            f"NODAL-INC34-045: missing staging sentinel fixture {fixture}",
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
            f"NODAL-INC34-029: temporary/generated files remain: {path}",
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate Increment 34 analog control flow")
    parser.add_argument("--root", type=Path, default=ROOT)
    arguments = parser.parse_args()
    check_repository(arguments.root.resolve())
    print("Increment 34 analog control-flow checkpoint is structurally valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
