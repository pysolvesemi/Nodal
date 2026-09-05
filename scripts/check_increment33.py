#!/usr/bin/env python3
"""Validate the Increment 33 analog procedural-assignment checkpoint."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


# Immutable accepted Increment 35 closure, not values supplied by the manifest.
INCREMENT35_CLOSURE_HEAD = "39915b984707f0396777cc69030dfec29aa2befe"
INCREMENT35_CLOSURE_RUN = 33916159555


class CheckFailure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CheckFailure(message)


def read_text(root: Path, relative: str) -> str:
    path = root / relative
    require(path.is_file(), f"NODAL-INC33-001: missing required file {relative}")
    return path.read_text(encoding="utf-8")


def read_json(root: Path, relative: str) -> dict[str, object]:
    try:
        return json.loads(read_text(root, relative))
    except json.JSONDecodeError as error:
        raise CheckFailure(
            f"NODAL-INC33-002: invalid JSON in {relative}: {error}"
        ) from error


def run(root: Path, command: list[str], code: str) -> None:
    completed = subprocess.run(
        command,
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if completed.returncode != 0:
        raise CheckFailure(f"{code}: command failed: {' '.join(command)}\n{completed.stdout}")


def repository_files(root: Path) -> tuple[Path, ...]:
    completed = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if completed.returncode == 0:
        return tuple(
            root / relative
            for relative in completed.stdout.split(chr(0))
            if relative
        )
    return tuple(
        path
        for path in root.rglob("*")
        if path.is_file() and "__pycache__" not in path.parts
    )


def check_repository(root: Path, compile_witnesses: bool = False) -> None:
    manifest_path = "tests/compiler/fixtures/increment33/manifest.json"
    manifest = read_json(root, manifest_path)
    increment34 = read_json(root, "tests/compiler/fixtures/increment34/manifest.json")
    increment35 = read_json(root, "tests/compiler/fixtures/increment35/manifest.json")
    require(manifest.get("schema") == 1, "NODAL-INC33-003: manifest schema must be 1")
    require(manifest.get("increment") == 33, "NODAL-INC33-004: manifest increment must be 33")
    status = manifest.get("status")
    require(
        status in {
            "implementation-in-progress",
            "validated-analog-procedural-assignment",
        },
        f"NODAL-INC33-005: unsupported manifest status: {status}",
    )
    validation = manifest.get("validation")
    if status == "implementation-in-progress":
        require(
            validation is None,
            "NODAL-INC33-006: validation must be null before evidence closure",
        )
    else:
        required_validation = (
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
            isinstance(validation, dict)
            and all(validation.get(field) for field in required_validation),
            "NODAL-INC33-006: validated manifest lacks complete evidence",
        )
        require(
            validation.get("implementation_pull_request") == 102
            and validation.get("accepted_head")
            == "ea7f7da51e85ba275dac71db7823ba0223f8d4ac"
            and validation.get("dedicated_boundary_workflow_run") == 33592719238
            and validation.get("implementation_merge")
            == "2e0ff291b8d6c0f6dcc4b4c8e27cc33984cff1b8"
            and validation.get("post_merge_core_ci_run") == 33605996500
            and validation.get("exact_post_merge_validation_run") == 33714669557,
            "NODAL-INC33-006: validated manifest evidence does not match accepted implementation",
        )

    baseline = manifest.get("baseline")
    require(isinstance(baseline, dict), "NODAL-INC33-007: manifest baseline must be an object")
    require(
        baseline.get("dev_commit") == "b1d927772c2a33a535f7d7fbe44a3891900c2fa2",
        "NODAL-INC33-008: Increment 33 must use the validated Increment 32 closure baseline",
    )
    require(
        baseline.get("increment_32_manifest") == "validated-equation-contribution-semantics",
        "NODAL-INC33-009: Increment 32 predecessor status is not validated",
    )

    predecessor = read_json(root, "tests/compiler/fixtures/increment32/manifest.json")
    require(
        predecessor.get("status") == "validated-equation-contribution-semantics",
        "NODAL-INC33-010: repository Increment 32 manifest is not validated",
    )
    require(
        isinstance(predecessor.get("validation"), dict),
        "NODAL-INC33-011: Increment 32 validation evidence is missing",
    )

    semantics = manifest.get("semantics")
    require(isinstance(semantics, dict), "NODAL-INC33-012: semantics must be an object")
    required_true = {
        "component_local_variables",
        "optional_initializers",
        "ordered_assignment",
        "repeated_writes_preserved",
        "lexical_scopes",
        "component_ownership",
        "straight_line_read_before_write",
        "single_procedural_region_per_component",
        "physical_dimension_checking",
        "comparison_operand_dimension_checking",
        "boolean_guards",
        "analysis_applicability",
        "source_provenance",
        "equation_assignment_separation",
        "contribution_assignment_separation",
        "connection_assignment_separation",
        "deterministic_snapshots",
        "public_construction_kernel_integration",
        "construction_snapshot_retention",
        "first_class_compiler_ir",
        "native_ir_verification",
        "compiler_boundary_diagnostics",
        "source_map_roundtrip",
        "authoritative_serialization",
    }
    missing_semantics = sorted(name for name in required_true if semantics.get(name) is not True)
    require(
        not missing_semantics,
        "NODAL-INC33-013: required semantic flags are not true: " + ", ".join(missing_semantics),
    )
    require(
        semantics.get("last_writer_wins_source_model") is False,
        "NODAL-INC33-014: source semantics must not be last-writer-wins",
    )
    require(
        semantics.get("implicit_real_to_integer_narrowing") is False,
        "NODAL-INC33-015: implicit real-to-integer narrowing must remain disabled",
    )

    deferred = manifest.get("deferred")
    require(isinstance(deferred, list), "NODAL-INC33-016: deferred must be a list")
    for item in (
        "analog-control-flow",
        "multiple-procedural-regions",
        "residual-dae-construction",
        "solver-execution",
        "target-legalization",
        "verilog-a-lowering",
        "verilog-ams-lowering",
    ):
        require(item in deferred, f"NODAL-INC33-017: missing deferred boundary {item}")

    design_gate = read_text(root, "docs/design-gates/NodalAnalogProceduralAssignment-DG-v0.1.md")
    implementation = read_text(root, "docs/implementation/increment33-analog-variables-procedural-assignment.md")
    scala_runtime = read_text(root, "core/scala/api/src/nodal/AnalogProceduralRuntime.scala")
    candidate_api = read_text(root, "core/scala/api/src/nodal/CandidateApi.scala")
    continuous_api = read_text(
        root, "core/scala/api/src/nodal/ContinuousTimeCandidateApi.scala"
    )
    native_runtime = read_text(root, "core/native/include/nodal/AnalogProceduralRuntime.h")
    scala_witness = read_text(
        root,
        "examples/continuousTimeApi/src/nodal/increment33fixture/Increment33RuntimeCheck.scala",
    )
    native_witness = read_text(
        root,
        "tests/compiler/fixtures/increment33/analog_procedural_runtime_test.cpp",
    )

    for token in ("===", "<+", ":=", "read-before-write", "lexical", "last-writer-wins"):
        require(token in design_gate, f"NODAL-INC33-018: design gate is missing {token!r}")
    require(
        "## Final implementation matrix" in implementation,
        "NODAL-INC33-019: implementation note must contain the final implementation matrix",
    )

    scala_tokens = (
        "final class Recorder",
        "def procedure",
        "def scope",
        "def declare",
        "def read",
        "def assign",
        "authoredOrder",
        "operationOrder",
        "NODAL-ANALOG-033-011",
        "targetState.initialized = true",
        "private var procedureSeen = false",
        "if procedureSeen then",
        "NODAL-ANALOG-033-020",
        "assignments.toVector",
    )
    for token in scala_tokens:
        require(token in scala_runtime, f"NODAL-INC33-020: Scala runtime is missing {token!r}")

    native_tokens = (
        "class Recorder final",
        "declareVariable",
        "void assign",
        "authoredOrder",
        "operationOrder",
        "NODAL-ANALOG-033-011",
        "targetState.initialized = true",
        "procedureSeen_",
        "NODAL-ANALOG-033-020",
        "result.assignments = assignments_",
    )
    for token in native_tokens:
        require(token in native_runtime, f"NODAL-INC33-021: native runtime is missing {token!r}")

    for code in range(1, 21):
        diagnostic = f"NODAL-ANALOG-033-{code:03d}"
        require(
            diagnostic in design_gate or diagnostic in scala_runtime or diagnostic in native_runtime,
            f"NODAL-INC33-022: stable diagnostic {diagnostic} is not represented",
        )

    require(
        "snapshot.assignments.map(_.authoredOrder) == Vector(0, 1, 2, 3, 4)" in scala_witness,
        "NODAL-INC33-023: Scala witness does not prove exact assignment order",
    )
    require(
        "snapshot.assignments[index].authoredOrder == index" in native_witness,
        "NODAL-INC33-024: native witness does not prove exact assignment order",
    )
    for code in (
        "NODAL-ANALOG-033-008",
        "NODAL-ANALOG-033-009",
        "NODAL-ANALOG-033-010",
        "NODAL-ANALOG-033-011",
        "NODAL-ANALOG-033-013",
    ):
        require(code in scala_witness, f"NODAL-INC33-025: Scala witness lacks {code}")
        require(code in native_witness, f"NODAL-INC33-026: native witness lacks {code}")

    types = read_text(root, "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td")
    operations = read_text(root, "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td")
    operation_verifiers = read_text(root, "core/compiler/lib/Dialect/Nodal/NodalOps.cpp")
    bridge = read_text(root, "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala")
    procedural_bridge = read_text(
        root, "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala"
    )
    bridge_tests = read_text(
        root,
        "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala",
    )
    diagnostics = read_text(root, "core/compiler/diagnostics-v0.1.json")
    compiler_tests = read_text(root, "core/compiler/test/CMakeLists.txt")
    invalid_variable_kind = read_text(
        root, "core/compiler/test/IR/analog-procedural-invalid-variable-kind.mlir"
    )
    invalid_multiple_procedures = read_text(
        root, "core/compiler/test/IR/analog-procedural-invalid-multiple.mlir"
    )
    for token in ("Nodal_VariableType", "NODAL-ANALOG-033-019"):
        require(token in types, f"NODAL-INC33-036: compiler type model is missing {token!r}")
    require(
        '!nodal.variable<"string", "1">' in invalid_variable_kind,
        "NODAL-INC33-042: invalid variable-kind fixture does not cross the type parser boundary",
    )
    require(
        "nodal.native.analog-procedural-rejects-variable-kind" in compiler_tests
        and "NODAL-ANALOG-033-019" in compiler_tests,
        "NODAL-INC33-043: native type-boundary test does not require diagnostic 019",
    )
    for token in (
        "Nodal_AnalogProcedureOp",
        "Nodal_AnalogScopeOp",
        "Nodal_AnalogVariableOp",
        "Nodal_AnalogVariableReadOp",
        "Nodal_AnalogAssignOp",
    ):
        require(token in operations, f"NODAL-INC33-037: compiler IR is missing {token!r}")
    for token in (
        "verifyAnalogProcedure",
        "verifySingleTopLevelProcedurePerModule",
        "NODAL-ANALOG-033-020",
        "NODAL-ANALOG-033-011",
        "NODAL-ANALOG-033-012",
        "NODAL-ANALOG-033-013",
        "NODAL-ANALOG-033-014",
        "NODAL-ANALOG-033-015",
    ):
        require(token in operation_verifiers, f"NODAL-INC33-038: native verifier is missing {token!r}")
    require(
        "AnalogProceduralMlir.renderModule" in bridge,
        "NODAL-INC33-039: authoritative bridge does not serialize procedural IR",
    )
    require(
        "nodal.analog_variable_read" in procedural_bridge
        and "nodal.analog_assign" in procedural_bridge,
        "NODAL-INC33-040: procedural bridge operations are incomplete",
    )
    for token in (
        's"${program.owner}.analogProcedural"',
        's"${program.owner}.analogProcedure"',
        'scopePaths',
        's"${record.identity}.read_$index"',
    ):
        require(
            token in procedural_bridge,
            f"NODAL-INC33-044: procedural source-map coverage is incomplete: {token!r}",
        )
    for token in (
        "expectedSourcePaths",
        "occurrences(first.text",
        "locked nodalc parses procedural bridge MLIR when configured",
        "nodal.bridge.source_map",
    ):
        require(
            token in bridge_tests,
            f"NODAL-INC33-045: procedural bridge/source-map test is missing {token!r}",
        )
    for code in range(1, 21):
        require(
            f"NODAL-ANALOG-033-{code:03d}" in diagnostics,
            f"NODAL-INC33-041: compiler diagnostic inventory is missing code {code:03d}",
        )

    construction_tests = read_text(
        root,
        "core/scala/testkit/test/src/nodal/AnalogProceduralConstructionTests.scala",
    )
    require(
        "public compound dimensionless assignment to a voltage variable is rejected"
        in construction_tests,
        "NODAL-INC33-060: compound dimension regression is missing",
    )
    require(
        "nested procedural scopes preserve declaration and assignment chronology"
        in bridge_tests,
        "NODAL-INC33-061: nested chronology regression is missing",
    )
    require(
        "public incompatible compound dimensions are rejected without read fallback"
        in construction_tests,
        "NODAL-INC33-062: compound read-dimension regression is missing",
    )
    require(
        "initializing assignments precede dependent declarations independent of provenance"
        in bridge_tests,
        "NODAL-INC33-063: initializer dependency chronology regression is missing",
    )
    require(
        "operation_order" in procedural_bridge,
        "NODAL-INC33-064: combined procedural operation order is not serialized",
    )
    require(
        "snapshot.variables.map(_.operationOrder)" in scala_witness,
        "NODAL-INC33-065: Scala witness does not prove combined operation order",
    )
    require(
        "snapshot.variables[0].operationOrder" in native_witness,
        "NODAL-INC33-066: native witness does not prove combined operation order",
    )

    construction_kernel = read_text(
        root,
        "core/scala/api/src/nodal/ElaborationConstructionKernel.scala",
    )
    compatible_add_start = construction_kernel.index(
        "  def compatibleAdd(other: AnalogDimension): AnalogDimension =\n"
    )
    compatible_add_end = construction_kernel.index(
        "\n  def canonical: String =", compatible_add_start
    )
    compatible_add = construction_kernel[compatible_add_start:compatible_add_end]
    require(
        "if isUnknown || other.isUnknown then AnalogDimension.Unknown"
        in compatible_add,
        "NODAL-INC33-067: recursive dimension mismatches are not sticky",
    )
    require(
        "public nested incompatible compound dimensions remain unknown"
        in construction_tests,
        "NODAL-INC33-068: nested compound-dimension regression is missing",
    )

    require(
        "def booleanExpr(" in candidate_api
        and 'resultType = Some(KernelTypeDescriptor("Bool"))' in candidate_api
        and '"real_gt"' in candidate_api
        and '"bool_and"' in candidate_api
        and "CandidateRuntime.booleanExpr" in continuous_api,
        "NODAL-INC33-070: Boolean expression result metadata is not retained",
    )
    require(
        'expression.resultType.exists(_.kind == "Bool")' in construction_kernel
        and "inferBooleanExpressionDimension(expression)" in construction_kernel,
        "NODAL-INC33-071: Boolean expression dimension inference is not delegated",
    )
    require(
        "public Boolean comparison assignment retains result type and dimension"
        in construction_tests,
        "NODAL-INC33-072: Boolean comparison assignment regression is missing",
    )
    require(
        "private var procedureSeen = false" in scala_runtime
        and "if procedureSeen then" in scala_runtime
        and "procedureSeen_" in native_runtime
        and "NODAL-ANALOG-033-020" in scala_runtime
        and "NODAL-ANALOG-033-020" in native_runtime,
        "NODAL-INC33-073: single procedural-region guard is missing",
    )
    require(
        "multiple top-level analogProcedure regions are rejected"
        in construction_tests,
        "NODAL-INC33-074: multiple procedural-region regression is missing",
    )

    require(
        "private def inferBooleanExpressionDimension" in construction_kernel
        and '"real_gt"' in construction_kernel
        and "compatibleAdd(inferAnalogDimension(right))" in construction_kernel
        and '"bool_and"' in construction_kernel
        and "dimensions.forall(isDimensionlessBoolean)" in construction_kernel,
        "NODAL-INC33-075: Boolean comparison operand dimensions are not validated recursively",
    )
    require(
        "public comparison rejects incompatible operand dimensions through Boolean logic"
        in construction_tests,
        "NODAL-INC33-076: incompatible comparison-dimension regression is missing",
    )
    require(
        "verifySingleTopLevelProcedurePerModule" in operation_verifiers
        and "getParentOfType<nodal::AnalogProcedureOp>" in operation_verifiers
        and "return verifySingleTopLevelProcedurePerModule(getOperation())"
        in operation_verifiers,
        "NODAL-INC33-077: native module boundary does not reject multiple top-level procedures",
    )
    require(
        invalid_multiple_procedures.count('"nodal.analog"') == 2
        and invalid_multiple_procedures.count('"nodal.analog_procedure"') == 2
        and "nodal.native.analog-procedural-rejects-multiple" in compiler_tests
        and "analog-procedural-invalid-multiple.mlir" in compiler_tests
        and '"-DDIAGNOSTIC=NODAL-ANALOG-033-020"' in compiler_tests,
        "NODAL-INC33-078: exact native multiple-procedure diagnostic fixture is missing",
    )
    require(
        semantics.get("comparison_operand_dimension_checking") is True
        and semantics.get("single_procedural_region_per_component") is True
        and "multiple-procedural-regions" in deferred,
        "NODAL-INC33-079: manifest does not retain final review boundaries",
    )

    developer_commands = read_text(root, "scripts/nodal.py")
    require(
        '"NODAL_NODALC": str(nodalc)' in developer_commands
        and "core.scala.testkit.test.testOnly" in developer_commands
        and "nodal.internal.testkit.ScalaToMlirBridgeTests" in developer_commands,
        "NODAL-INC33-069: native command does not execute Scala bridge regressions against nodalc",
    )

    roadmap = read_text(root, "docs/roadmap/nodal-development-todo.md")
    require(
        "- [x] **Increment 32 — First-class analog equations" in roadmap,
        "NODAL-INC33-028: Increment 32 must be checked before Increment 33",
    )
    increment33_open = (
        "- [ ] **Increment 33 — Analog variables and procedural assignment**"
    )
    increment33_closed = (
        "- [x] **Increment 33 — Analog variables and procedural assignment**"
    )
    increment34_open = "- [ ] **Increment 34 — Analog control flow**"
    increment34_closed = "- [x] **Increment 34 — Analog control flow**"
    increment35_open = "- [ ] **Increment 35 — Differential and integral operators**"
    increment35_closed = "- [x] **Increment 35 — Differential and integral operators**"
    require(
        (increment33_open in roadmap) != (increment33_closed in roadmap),
        "NODAL-INC33-029: Increment 33 roadmap state is missing or ambiguous",
    )
    require(
        (increment34_open in roadmap) != (increment34_closed in roadmap),
        "NODAL-INC33-081: Increment 34 roadmap state is missing or ambiguous",
    )
    require(
        (increment35_open in roadmap) != (increment35_closed in roadmap),
        "NODAL-INC33-085: Increment 35 roadmap state is missing or ambiguous",
    )
    if status == "implementation-in-progress":
        require(
            "**Revision:** 1.43" in roadmap and increment33_open in roadmap,
            "NODAL-INC33-027: implementation state requires roadmap revision 1.43 with Increment 33 open",
        )
    else:
        require(
            increment33_closed in roadmap,
            "NODAL-INC33-027: validated Increment 33 must remain checked",
        )
        successor_status = increment34.get("status")
        increment35_status = increment35.get("status")
        if successor_status == "implementation-in-progress":
            require(
                increment34.get("validation") is None
                and increment35_status == "implementation-in-progress"
                and increment35.get("validation") is None
                and "**Revision:** 1.44" in roadmap
                and increment34_open in roadmap
                and increment35_open in roadmap,
                "NODAL-INC33-081: open Increment 34 requires roadmap revision 1.44 and no closed successor",
            )
        elif successor_status == "validated-analog-control-flow":
            successor_validation = increment34.get("validation")
            required_successor_evidence = (
                "implementation_pull_request",
                "accepted_head",
                "exact_head_workflow_count",
                "exact_head_core_ci_run",
                "implementation_merge",
                "post_merge_core_ci_run",
                "exact_post_merge_validation_run",
                "closure_pull_request",
                "closure_validation_head",
                "closure_validation_run",
            )
            require(
                isinstance(successor_validation, dict)
                and all(
                    successor_validation.get(field)
                    for field in required_successor_evidence
                ),
                "NODAL-INC33-082: validated Increment 34 lacks complete evidence",
            )
            require(
                successor_validation.get("implementation_pull_request") == 109
                and successor_validation.get("accepted_head")
                == "207fd1b580e9428e9948cd4e4bd8f2060fde4b79"
                and successor_validation.get("implementation_merge")
                == "a9d3ec50799953c41e7b9cf1d8bd6a2c5c9afd49"
                and successor_validation.get("closure_pull_request") == 111
                and increment34_closed in roadmap,
                "NODAL-INC33-083: validated Increment 34 successor evidence is inconsistent",
            )

            increment35_validation = increment35.get("validation")
            if increment35_status == "implementation-in-progress":
                require(
                    increment35.get("tranche")
                    == "35a-differential-integral-operator-contract"
                    and increment35_validation is None
                    and "**Revision:** 1.45" in roadmap
                    and increment35_open in roadmap,
                    "NODAL-INC33-086: open Increment 35 requires roadmap revision 1.45",
                )
            elif increment35_status == "evidence-closure-candidate":
                required_increment35_evidence = (
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
                    isinstance(increment35_validation, dict)
                    and all(
                        increment35_validation.get(field)
                        for field in required_increment35_evidence
                    )
                    and increment35_validation.get("closure_validation_head") is None
                    and increment35_validation.get("closure_validation_run") is None,
                    "NODAL-INC33-087: Increment 35 closure candidate evidence is incomplete",
                )
                require(
                    increment35_validation.get("implementation_pull_request") == 113
                    and increment35_validation.get("accepted_head")
                    == "d3410f6f64dc66df27d9c7f545c9e78f62695f2e"
                    and increment35_validation.get("exact_head_workflow_count") == 25
                    and increment35_validation.get("exact_head_core_ci_run") == 33890457304
                    and increment35_validation.get("implementation_merge")
                    == "7763e1524f31e4c2c41b11acb200670c360f0fde"
                    and increment35_validation.get("post_merge_core_ci_run") == 33892575717
                    and increment35_validation.get("exact_post_merge_validation_run")
                    == 33892632854
                    and increment35_validation.get("closure_pull_request") == 114
                    and "**Revision:** 1.46" in roadmap
                    and increment35_closed in roadmap,
                    "NODAL-INC33-088: Increment 35 closure candidate evidence is inconsistent",
                )
            elif increment35_status == "validated-differential-integral-operators":
                required_increment35_evidence = (
                    "implementation_pull_request",
                    "accepted_head",
                    "exact_head_workflow_count",
                    "exact_head_core_ci_run",
                    "implementation_merge",
                    "post_merge_core_ci_run",
                    "exact_post_merge_validation_run",
                    "closure_pull_request",
                    "closure_validation_head",
                    "closure_validation_run",
                )
                require(
                    isinstance(increment35_validation, dict)
                    and all(
                        increment35_validation.get(field)
                        for field in required_increment35_evidence
                    ),
                    "NODAL-INC33-089: validated Increment 35 lacks complete evidence",
                )
                closure_head = increment35_validation.get("closure_validation_head")
                closure_run = increment35_validation.get("closure_validation_run")
                require(
                    increment35_validation.get("implementation_pull_request") == 113
                    and increment35_validation.get("accepted_head")
                    == "d3410f6f64dc66df27d9c7f545c9e78f62695f2e"
                    and increment35_validation.get("exact_head_workflow_count") == 25
                    and increment35_validation.get("exact_head_core_ci_run") == 33890457304
                    and increment35_validation.get("implementation_merge")
                    == "7763e1524f31e4c2c41b11acb200670c360f0fde"
                    and increment35_validation.get("post_merge_core_ci_run") == 33892575717
                    and increment35_validation.get("exact_post_merge_validation_run")
                    == 33892632854
                    and increment35_validation.get("closure_pull_request") == 114
                    and isinstance(closure_head, str)
                    and len(closure_head) == 40
                    and all(character in "0123456789abcdef" for character in closure_head)
                    and isinstance(closure_run, int)
                    and closure_run > 0
                    and closure_head == INCREMENT35_CLOSURE_HEAD
                    and closure_run == INCREMENT35_CLOSURE_RUN
                    and "**Revision:** 1.46" in roadmap
                    and increment35_closed in roadmap,
                    "NODAL-INC33-090: validated Increment 35 evidence is inconsistent",
                )
            else:
                raise CheckFailure(
                    f"NODAL-INC33-091: unsupported Increment 35 successor status: {increment35_status}"
                )
        else:
            raise CheckFailure(
                f"NODAL-INC33-084: unsupported Increment 34 successor status: {successor_status}"
            )
        evidence = read_text(
            root,
            "docs/implementation/increment33-evidence-closure.md",
        )
        for token in (
            "**Implementation PR:** #102",
            "**Accepted implementation head:** `ea7f7da51e85ba275dac71db7823ba0223f8d4ac`",
            "**Implementation merge:** `2e0ff291b8d6c0f6dcc4b4c8e27cc33984cff1b8`",
            "**Exact post-merge validation:** `33714669557`",
            f"**Closure PR:** #{validation['closure_pull_request']}",
            f"**Closure validation head:** `{validation['closure_validation_head']}`",
            f"**Closure validation run:** `{validation['closure_validation_run']}`",
        ):
            require(
                token in evidence,
                f"NODAL-INC33-080: evidence-closure record is missing {token!r}",
            )

    workflow_path = root / ".github/workflows/increment-33-analog-procedural-assignment.yml"
    if workflow_path.exists():
        workflow = workflow_path.read_text(encoding="utf-8")
        require("contents: read" in workflow, "NODAL-INC33-030: workflow must be read-only")
        for forbidden in ("contents: write", "pull-requests: write", "git push", "gh pr merge"):
            require(forbidden not in workflow, f"NODAL-INC33-031: workflow contains {forbidden!r}")

    temporary = []
    for path in repository_files(root):
        if not path.is_file():
            continue
        lowered = path.name.lower()
        if (
            lowered.startswith("_inc33")
            or "increment33_payload" in lowered
            or "increment33_materializer" in lowered
            or "increment33_finalizer" in lowered
            or lowered.endswith((".pyc", ".pyo"))
        ):
            temporary.append(str(path.relative_to(root)))
    require(
        not temporary,
        "NODAL-INC33-032: temporary/generated files remain: " + ", ".join(sorted(temporary)),
    )

    if compile_witnesses:
        run(
            root,
            ["./mill", "examples.continuousTimeApi.compile"],
            "NODAL-INC33-033",
        )
        output = root / "out" / "increment33-native-witness"
        output.parent.mkdir(parents=True, exist_ok=True)
        run(
            root,
            [
                "c++",
                "-std=c++20",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-Icore/native/include",
                "tests/compiler/fixtures/increment33/analog_procedural_runtime_test.cpp",
                "-o",
                str(output),
            ],
            "NODAL-INC33-034",
        )
        run(root, [str(output)], "NODAL-INC33-035")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--compile", action="store_true")
    arguments = parser.parse_args()
    try:
        check_repository(arguments.root.resolve(), arguments.compile)
    except CheckFailure as error:
        print(error, file=sys.stderr)
        return 1
    print("Increment 33 analog procedural-assignment checkpoint passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
