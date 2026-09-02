#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"missing required file: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def insert_before(text: str, marker: str, addition: str, label: str) -> str:
    if addition.strip() in text:
        return text
    return replace_once(text, marker, addition + marker, label)


def patch_native_repair(root: Path) -> None:
    path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
    text = read(path)
    text = text.replace('(kind + ":").str()', 'kind.str() + ":"')
    write(path, text)


def patch_manifest(root: Path) -> None:
    path = root / "tests/compiler/fixtures/increment34/manifest.json"
    document = json.loads(read(path))
    document["tranche"] = "34c-structured-compiler-ir"
    document["bridge_renderer"] = (
        "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala"
    )
    document["bridge_tests"] = (
        "core/scala/bridge/test/src/nodal/bridge/ScalaToMlirBridgeTests.scala"
    )
    document["native_ops"] = (
        "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
    )
    document["native_verifier"] = (
        "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
    )
    document["native_fixture"] = (
        "core/compiler/test/IR/analog-control-flow.mlir"
    )

    semantics = document["semantics"]
    semantics["typed_control_expression_payloads"] = True
    semantics["structured_ir_regions"] = True
    semantics["structured_source_map_entries"] = True

    integration = document["integration"]
    for key in (
        "scala_to_mlir",
        "first_class_compiler_ir",
        "native_ir_verification",
        "compiler_boundary_diagnostics",
        "source_map_roundtrip",
        "authoritative_serialization",
    ):
        integration[key] = True
    integration["native_branch_sensitive_definite_assignment"] = False
    integration["target_lowering"] = False

    completed = {
        "scala-to-mlir-control-flow",
        "native-control-flow-ir",
        "native-control-flow-verification",
        "compiler-boundary-control-flow-diagnostics",
        "source-map-roundtrip",
        "reproducibility-serialization",
    }
    deferred = [value for value in document["deferred"] if value not in completed]
    if "native-branch-sensitive-definite-assignment" not in deferred:
        deferred.insert(0, "native-branch-sensitive-definite-assignment")
    document["deferred"] = deferred
    document["validation"] = None
    write(path, json.dumps(document, indent=2) + "\n")


def patch_implementation_doc(root: Path) -> None:
    path = root / "docs/implementation/increment34-analog-control-flow.md"
    text = read(path)

    old = '''## Tranche 34c — bridge and native IR

- [x] Add the control-flow tree to the canonical `ConstructionSnapshot`.
- [ ] Add first-class Nodal conditional, case, loop, break, continue, scope, and
  declaration operations and regions.
- [ ] Serialize the Scala statement tree without flattening branches.
- [ ] Implement native structural and branch-sensitive verifiers.
- [ ] Add direct-MLIR positive and negative fixtures.
- [ ] Preserve complete source-map coverage through parse and print.
'''
    new = '''## Tranche 34c — bridge and native IR

- [x] Add the control-flow tree to the canonical `ConstructionSnapshot`.
- [x] Add first-class Nodal conditional, case, loop, break, continue, scope, and
  declaration operations and regions.
- [x] Serialize the Scala statement tree without flattening branches.
- [x] Implement native structural verifiers and stable compiler-boundary
  diagnostics.
- [x] Add direct-MLIR positive and negative fixtures.
- [x] Preserve structured source-map coverage through native parse and print.
- [x] Retain deterministic control-node and typed-expression inventories.
- [ ] Implement native branch-sensitive definite-assignment dataflow over the
  first-class regions.
'''
    if old in text:
        text = text.replace(old, new, 1)
    elif "native branch-sensitive definite-assignment dataflow" not in text:
        raise SystemExit("implementation document 34c checklist was not found")

    old_boundaries = '''The canonical construction checkpoint does not yet:

- expose structured control flow through Scala-to-MLIR serialization;
- add first-class `nodal.analog_if`, `nodal.analog_case`,
  `nodal.analog_loop`, `nodal.analog_break`, or
  `nodal.analog_continue` operations;
- add native compiler verifiers or direct-MLIR fixtures;
- serialize complete control-flow source maps or reproducibility evidence;
- legalize or emit procedural target HDL.

Those items remain active Increment 34 work, not evidence gaps claimed as
complete behavior.
'''
    new_boundaries = '''The structured compiler-IR checkpoint now preserves the canonical Scala tree
through deterministic textual MLIR, first-class native regions, structural
verification, stable diagnostics, direct positive and negative fixtures, and
source-correlated native parse/print.

It does not yet:

- run branch-sensitive definite-assignment as a native dataflow analysis over
  the first-class regions;
- complete the exact-head inherited workflow matrix and fresh review;
- legalize or emit procedural target HDL;
- form solver equations, residuals, or executable analysis schedules.

Those items remain active Increment 34 work, not evidence gaps claimed as
complete behavior.
'''
    if old_boundaries in text:
        text = text.replace(old_boundaries, new_boundaries, 1)
    elif "The structured compiler-IR checkpoint now preserves" not in text:
        raise SystemExit("implementation document boundary section was not found")

    write(path, text)


def patch_readme(root: Path) -> None:
    path = root / "tests/compiler/fixtures/increment34/README.md"
    text = read(path)
    old = '''The checkpoint intentionally does not add canonical construction-snapshot
serialization, native MLIR control-flow operations, a solver, or Verilog-A and
Verilog-AMS lowering. Those remain active tranches of Increment 34.
'''
    new = '''The structured compiler-IR checkpoint additionally proves that the canonical
tree is serialized without flattening into first-class `nodal.analog_if`,
`nodal.analog_case`, `nodal.analog_loop`, `nodal.analog_break`, and
`nodal.analog_continue` operations; native structural diagnostics reject
invalid condition dimensions, duplicate labels, unbounded loops, and loop
exits outside the nearest runtime-bounded loop; and source locations survive
native parse and generic print.

Native branch-sensitive definite-assignment, solver construction, target
legalization, and Verilog-A or Verilog-AMS procedural lowering remain active
Increment 34 work.
'''
    if old in text:
        text = text.replace(old, new, 1)
    elif "The structured compiler-IR checkpoint additionally proves" not in text:
        raise SystemExit("fixture README boundary paragraph was not found")
    write(path, text)


def patch_workflow(root: Path) -> None:
    path = root / ".github/workflows/increment-34-analog-control-flow.yml"
    text = read(path)
    text = text.replace("timeout-minutes: 45", "timeout-minutes: 70", 1)

    filter_marker = "      - 'core/scala/testkit/test/src/nodal/AnalogControlFlowConstructionTests.scala'\n"
    filters = """      - 'core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala'
      - 'core/scala/bridge/test/src/nodal/bridge/ScalaToMlirBridgeTests.scala'
      - 'core/compiler/include/nodal/Dialect/Nodal/NodalOps.td'
      - 'core/compiler/lib/Dialect/Nodal/NodalOps.cpp'
      - 'core/compiler/test/CMakeLists.txt'
      - 'core/compiler/test/IR/analog-control-flow*.mlir'
"""
    if filters.strip() not in text:
        text = replace_once(
            text,
            filter_marker,
            filter_marker + filters,
            "Increment 34 workflow path filters",
        )

    native_step = r'''
      - name: Build and validate structured native control flow
        run: |
          ./nodal bootstrap \
            --mode prebuilt \
            --prefix "${RUNNER_TEMP}/nodal-native-toolchain"
          ./nodal style bootstrap \
            --prefix "${RUNNER_TEMP}/nodal-lint-toolchain"
          ./nodal core native \
            --toolchain "${RUNNER_TEMP}/nodal-native-toolchain" \
            --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain"
          "${RUNNER_TEMP}/nodal-native-toolchain/bin/nodalc" \
            --mlir-print-op-generic \
            --mlir-print-debuginfo \
            core/compiler/test/IR/analog-control-flow.mlir \
            > "${RUNNER_TEMP}/increment34-structured.mlir"
          grep -F '"nodal.analog_if"' "${RUNNER_TEMP}/increment34-structured.mlir"
          grep -F '"nodal.analog_case"' "${RUNNER_TEMP}/increment34-structured.mlir"
          grep -F '"nodal.analog_loop"' "${RUNNER_TEMP}/increment34-structured.mlir"
          grep -F 'AnalogControlFlow.scala' "${RUNNER_TEMP}/increment34-structured.mlir"

'''
    if "Build and validate structured native control flow" not in text:
        text = insert_before(
            text,
            "      - name: Check repository style and documentation\n",
            native_step,
            "Increment 34 native workflow step",
        )

    write(path, text)


def patch_checker(root: Path) -> None:
    path = root / "scripts/check_increment34.py"
    text = read(path)

    old_reads = '''    workflow = read_text(root, ".github/workflows/increment-34-analog-control-flow.yml")
    predecessor = load_json(root, "tests/compiler/fixtures/increment33/manifest.json")
'''
    new_reads = '''    workflow = read_text(root, ".github/workflows/increment-34-analog-control-flow.yml")
    bridge = read_text(
        root, "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala"
    )
    bridge_tests = read_text(
        root, "core/scala/bridge/test/src/nodal/bridge/ScalaToMlirBridgeTests.scala"
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
'''
    if old_reads in text:
        text = text.replace(old_reads, new_reads, 1)
    elif "native_fixture = read_text" not in text:
        raise SystemExit("checker read inventory marker was not found")

    text = text.replace(
        'and manifest.get("tranche") == "34c-canonical-snapshot",',
        'and manifest.get("tranche") == "34c-structured-compiler-ir",',
        1,
    )

    if '"structured_ir_regions",' not in text:
        text = text.replace(
            '        "structured_flattening_prohibited",\n',
            '        "structured_flattening_prohibited",\n'
            '        "typed_control_expression_payloads",\n'
            '        "structured_ir_regions",\n'
            '        "structured_source_map_entries",\n',
            1,
        )

    old_completed = '''    for key in (
        "public_construction_kernel",
        "owner_remapped_source_snapshot",
        "increment33_flat_snapshot_separation",
        "canonical_construction_snapshot",
    ):
'''
    new_completed = '''    for key in (
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
    ):
'''
    if old_completed in text:
        text = text.replace(old_completed, new_completed, 1)

    old_unfinished = '''    for key in (
        "scala_to_mlir",
        "first_class_compiler_ir",
        "native_ir_verification",
        "compiler_boundary_diagnostics",
        "source_map_roundtrip",
        "authoritative_serialization",
        "target_lowering",
    ):
'''
    new_unfinished = '''    for key in (
        "native_branch_sensitive_definite_assignment",
        "target_lowering",
    ):
'''
    if old_unfinished in text:
        text = text.replace(old_unfinished, new_unfinished, 1)

    old_deferred = '''        "canonical-construction-snapshot" not in deferred
        and "scala-to-mlir-control-flow" in deferred
        and "native-control-flow-ir" in deferred,
        "NODAL-INC34-015: completed and deferred integration boundaries are inconsistent",
'''
    new_deferred = '''        "canonical-construction-snapshot" not in deferred
        and "scala-to-mlir-control-flow" not in deferred
        and "native-control-flow-ir" not in deferred
        and "source-map-roundtrip" not in deferred
        and "native-branch-sensitive-definite-assignment" in deferred,
        "NODAL-INC34-015: completed and deferred integration boundaries are inconsistent",
'''
    if old_deferred in text:
        text = text.replace(old_deferred, new_deferred, 1)

    checks = r'''
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

'''
    if "NODAL-INC34-030" not in text:
        text = insert_before(
            text,
            "    forbidden_names = {\n",
            checks,
            "checker structured compiler-IR contracts",
        )

    if '"./nodal core native"' not in text:
        text = text.replace(
            '            "./nodal core scala",\n',
            '            "./nodal core scala",\n'
            '            "Build and validate structured native control flow",\n'
            '            "./nodal core native",\n'
            '            "analog-control-flow.mlir",\n',
            1,
        )

    write(path, text)


def patch_mutation_tests(root: Path) -> None:
    path = root / "tests/compiler/test_increment34.py"
    text = read(path)

    marker = '    "core/scala/testkit/test/src/nodal/AnalogControlFlowConstructionTests.scala",\n'
    additions = '''    "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala",
    "core/scala/bridge/test/src/nodal/bridge/ScalaToMlirBridgeTests.scala",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/IR/analog-control-flow.mlir",
'''
    if additions.strip() not in text:
        text = replace_once(
            text,
            marker,
            marker + additions,
            "mutation fixture file inventory",
        )

    text = text.replace(
        'document["integration"]["first_class_compiler_ir"] = True',
        'document["integration"]["native_branch_sensitive_definite_assignment"] = True',
        1,
    )

    tests = r'''
    def test_structured_bridge_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '"nodal.analog_if"',
                    '"nodal.removed_analog_if"',
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "structured Scala-to-MLIR bridge is missing")

    def test_native_control_op_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    'Nodal_Op<"analog_loop"',
                    'Nodal_Op<"removed_analog_loop"',
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "first-class native control-flow operations is missing")

    def test_native_verifier_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "LogicalResult nodal::AnalogBreakOp::verify()",
                    "LogicalResult nodal::RemovedAnalogBreakOp::verify()",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured control-flow verification is missing")

'''
    if "test_structured_bridge_mutation_is_rejected" not in text:
        text = insert_before(
            text,
            "    def test_write_enabled_workflow_is_rejected(self) -> None:\n",
            tests,
            "structured compiler-IR mutation tests",
        )
    write(path, text)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    patch_native_repair(root)
    patch_manifest(root)
    patch_implementation_doc(root)
    patch_readme(root)
    patch_workflow(root)
    patch_checker(root)
    patch_mutation_tests(root)
    print("Increment 34 structured compiler-IR contracts materialized.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
