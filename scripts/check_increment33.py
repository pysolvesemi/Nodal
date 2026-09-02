#!/usr/bin/env python3
"""Validate the Increment 33 analog procedural-assignment checkpoint."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


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
    require(manifest.get("schema") == 1, "NODAL-INC33-003: manifest schema must be 1")
    require(manifest.get("increment") == 33, "NODAL-INC33-004: manifest increment must be 33")
    require(
        manifest.get("status") == "implementation-in-progress",
        "NODAL-INC33-005: checkpoint manifest must remain implementation-in-progress",
    )
    require(manifest.get("validation") is None, "NODAL-INC33-006: validation must be null before evidence closure")

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
        "physical_dimension_checking",
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
        "result.assignments = assignments_",
    )
    for token in native_tokens:
        require(token in native_runtime, f"NODAL-INC33-021: native runtime is missing {token!r}")

    for code in range(1, 20):
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
    for code in range(1, 20):
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

    roadmap = read_text(root, "docs/roadmap/nodal-development-todo.md")
    require(
        "**Revision:** 1.43" in roadmap,
        "NODAL-INC33-027: roadmap revision must remain 1.43 during implementation",
    )
    require(
        "- [x] **Increment 32 — First-class analog equations" in roadmap,
        "NODAL-INC33-028: Increment 32 must be checked before Increment 33",
    )
    require(
        "- [ ] **Increment 33 — Analog variables and procedural assignment**" in roadmap,
        "NODAL-INC33-029: Increment 33 must remain unchecked before evidence closure",
    )

    workflow_path = root / ".github/workflows/increment-33-analog-procedural-assignment.yml"
    if workflow_path.exists():
        workflow = workflow_path.read_text(encoding="utf-8")
        require(
            'NODAL_NODALC="$PWD/out/native/release/bin/nodalc"' in workflow
            and "core.scala.testkit.test.testOnly" in workflow
            and "ScalaToMlirBridgeTests" in workflow,
            "NODAL-INC33-069: Scala bridge regressions do not execute against nodalc",
        )
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
