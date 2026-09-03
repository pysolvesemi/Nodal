#!/usr/bin/env python3
"""Apply Increment 34 fresh-review structural validation hardening."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)


def patch_scala_runtime(root: Path) -> None:
    path = root / "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
    text = read(path)
    old = '''        if isRoot && declaration.local then
          fail(
            "NODAL-ANALOG-034-014",
            "root declaration cannot be marked local",
            Some(declaration.identity)
          )
'''
    new = old + '''        if !isRoot && !declaration.local then
          fail(
            "NODAL-ANALOG-034-014",
            "nested declaration must be block-local",
            Some(declaration.identity)
          )
'''
    text = replace_once(text, old, new, "nested declaration locality")
    write(path, text)


def patch_runtime_witness(root: Path) -> None:
    path = root / "examples/continuousTimeApi/src/nodal/increment34fixture/Increment34RuntimeCheck.scala"
    text = read(path)
    marker = '''    val lines = Vector(
'''
    addition = '''    expect("NODAL-ANALOG-034-014"):
      AnalogControlFlowRuntime.analyze(
        Block(
          "nested-nonlocal-root",
          Vector(
            Statement.Scope(
              "nested-nonlocal-scope",
              Block(
                "nested-nonlocal-body",
                Vector(
                  Statement.Declare(
                    "nested-nonlocal-declaration",
                    "nested-local",
                    initialized = true,
                    local = false
                  )
                )
              )
            )
          )
        )
      )

'''
    if "nested-nonlocal-declaration" not in text:
        text = replace_once(text, marker, addition + marker, "runtime locality witness")
    old_line = '''      "continue_scope=NODAL-ANALOG-034-011"
'''
    new_line = '''      "continue_scope=NODAL-ANALOG-034-011",
      "nested_nonlocal=NODAL-ANALOG-034-014"
'''
    text = replace_once(text, old_line, new_line, "runtime locality report")
    write(path, text)


def patch_native_verifier(root: Path) -> None:
    path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
    text = read(path)
    old = '''LogicalResult requireStructuredRead(Operation *operation, llvm::StringRef identity,
                                    const StructuredInitializedSet &initialized,
                                    const StructuredDataflowContext &context) {
  auto variable = context.variables.find(identity);
  if (variable == context.variables.end())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        llvm::Twine("structured control flow references unknown variable '") + identity + "'");
  if (!isVisibleFrom(operation->getBlock(), variable->second.declarationBlock))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                    llvm::Twine("structured variable '") + identity +
                                        "' is outside its lexical declaration scope");
  if (initialized.find(identity.str()) == initialized.end())
'''
    new = '''LogicalResult requireStructuredReference(Operation *operation, llvm::StringRef identity,
                                         const StructuredDataflowContext &context) {
  if (identity.trim().empty() || identity.trim() != identity)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        "structured variable reference must be non-empty and canonical");
  auto variable = context.variables.find(identity);
  if (variable == context.variables.end())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        llvm::Twine("structured control flow references unknown variable '") + identity + "'");
  if (!isVisibleFrom(operation->getBlock(), variable->second.declarationBlock))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                    llvm::Twine("structured variable '") + identity +
                                        "' is outside its lexical declaration scope");
  return success();
}

LogicalResult requireStructuredRead(Operation *operation, llvm::StringRef identity,
                                    const StructuredInitializedSet &initialized,
                                    const StructuredDataflowContext &context) {
  if (failed(requireStructuredReference(operation, identity, context)))
    return failure();
  if (initialized.find(identity.str()) == initialized.end())
'''
    text = replace_once(text, old, new, "native structural reference helper")

    marker = '''  return identity.str();
}

FailureOr<StructuredFlow> analyzeStructuredDataflowBlock(Block &block,
'''
    helpers = '''  return identity.str();
}

LogicalResult verifyStructuredReferenceInventory(Operation *operation,
                                                 llvm::StringRef attributeName,
                                                 const StructuredDataflowContext &context) {
  auto references = operation->getAttrOfType<ArrayAttr>(attributeName);
  if (!references)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                    llvm::Twine("missing structured reference inventory '") +
                                        attributeName + "'");
  for (Attribute attribute : references) {
    auto identity = llvm::dyn_cast<StringAttr>(attribute);
    if (!identity)
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-034-014",
          "structured reference inventory entries must be strings");
    if (failed(requireStructuredReference(operation, identity.getValue(), context)))
      return failure();
  }
  return success();
}

LogicalResult verifyStructuredReferences(Block &block,
                                         const StructuredDataflowContext &context) {
  const StructuredInitializedSet noInitializationRequirement;
  for (Operation &operation : block) {
    llvm::StringRef inventory;
    if (llvm::isa<nodal::AnalogVariableOp>(operation))
      inventory = "initializer_reads";
    else if (llvm::isa<nodal::AnalogAssignOp>(operation))
      inventory = "guard_reads";
    else if (llvm::isa<nodal::AnalogIfArmOp>(operation))
      inventory = "condition_reads";
    else if (llvm::isa<nodal::AnalogCaseOp>(operation))
      inventory = "selector_reads";
    else if (llvm::isa<nodal::AnalogLoopOp>(operation))
      inventory = "bound_reads";

    if (!inventory.empty() &&
        failed(verifyStructuredReferenceInventory(&operation, inventory, context)))
      return failure();

    if (llvm::isa<nodal::AnalogVariableReadOp>(operation)) {
      if (failed(structuredVariableIdentity(&operation, operation.getOperand(0), context,
                                            /*requireInitialized=*/false,
                                            noInitializationRequirement)))
        return failure();
    }

    if (llvm::isa<nodal::AnalogAssignOp>(operation)) {
      if (failed(structuredVariableIdentity(&operation, operation.getOperand(0), context,
                                            /*requireInitialized=*/false,
                                            noInitializationRequirement)))
        return failure();
      for (Value value : operation.getOperands().drop_front()) {
        auto read = value.getDefiningOp<nodal::AnalogVariableReadOp>();
        if (!read)
          return nodal::emitMappedFailure(
              &operation, "NODAL-ANALOG-034-014",
              "structured assignment operands must be explicit variable reads");
        if (failed(structuredVariableIdentity(
                &operation, read.getOperation()->getOperand(0), context,
                /*requireInitialized=*/false, noInitializationRequirement)))
          return failure();
      }
    }

    for (Region &region : operation.getRegions()) {
      for (Block &nested : region) {
        if (failed(verifyStructuredReferences(nested, context)))
          return failure();
      }
    }
  }
  return success();
}

FailureOr<StructuredFlow> analyzeStructuredDataflowBlock(Block &block,
'''
    text = replace_once(text, marker, helpers, "native all-path structural reference pass")

    old = '''  if (failed(collectStructuredVariables(body, owner, context)))
    return failure();
  auto flow = analyzeStructuredDataflowBlock(body, StructuredInitializedSet{}, context,
'''
    new = '''  if (failed(collectStructuredVariables(body, owner, context)))
    return failure();
  if (failed(verifyStructuredReferences(body, context)))
    return failure();
  auto flow = analyzeStructuredDataflowBlock(body, StructuredInitializedSet{}, context,
'''
    text = replace_once(text, old, new, "native structural pass invocation")
    write(path, text)


def patch_native_fixture(root: Path) -> None:
    path = root / "core/compiler/test/IR/analog-control-flow-invalid-unreachable-reference.mlir"
    write(
        path,
        '''module {
  "nodal.module"() <{metadata = {}, sym_name = "ControlTop"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {semantic_path = "ControlTop.analogProcedural"}}> ({
    ^bb0:
      "nodal.analog_procedure"() <{metadata = {semantic_path = "ControlTop.analogProcedure"}, owner = "ControlTop"}> ({
      ^bb0:
        %value = "nodal.analog_variable"() <{declaration_order = 0 : i64, identity = "ControlTop.procedure.value", initialized = false, initializer_dimension = "", initializer_kind = "", initializer_reads = [], initializer_value = "", metadata = {semantic_path = "ControlTop.procedure.value"}, owner = "ControlTop"}> : () -> !nodal.variable<"real", "1">
        "nodal.analog_if"() <{metadata = {semantic_path = "ControlTop.if_0"}, owner = "ControlTop", statement_id = "ControlTop.if_0"}> ({
        ^bb0:
          "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_0.branch_0", condition_dimension = "1", condition_kind = "boolean", condition_reads = [], condition_value = "false", is_else = false, metadata = {semantic_path = "ControlTop.if_0.condition_0"}, owner = "ControlTop", stage = "static", static_value = false, static_value_present = true}> ({
          ^bb0:
            "nodal.analog_assign"(%value) <{analyses = ["dc", "transient"], authored_order = 0 : i64, guard_dimension = "1", guard_kind = "boolean", guard_present = true, guard_reads = ["ControlTop.procedure.missing"], guard_value = "missing_guard", metadata = {semantic_path = "ControlTop.statement_0", value = "1.0"}, owner = "ControlTop", statement_id = "ControlTop.statement_0", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">) -> ()
          }) : () -> ()
          "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_0.branch_1", condition_dimension = "1", condition_kind = "boolean", condition_reads = [], condition_value = "true", is_else = false, metadata = {semantic_path = "ControlTop.if_0.condition_1"}, owner = "ControlTop", stage = "static", static_value = true, static_value_present = true}> ({
          ^bb0:
            "nodal.analog_assign"(%value) <{analyses = ["dc", "transient"], authored_order = 1 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", metadata = {semantic_path = "ControlTop.statement_1", value = "2.0"}, owner = "ControlTop", statement_id = "ControlTop.statement_1", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">) -> ()
          }) : () -> ()
        }) : () -> ()
        %final_read = "nodal.analog_variable_read"(%value) <{metadata = {semantic_path = "ControlTop.final_read"}, owner = "ControlTop", read_id = "ControlTop.final_read"}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1">
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
''',
    )


def patch_native_cmake(root: Path) -> None:
    path = root / "core/compiler/test/CMakeLists.txt"
    text = read(path)
    marker = '''add_test(
  NAME nodal.native.analog-control-flow-rejects-case-label
'''
    addition = '''add_test(
  NAME nodal.native.analog-control-flow-rejects-unreachable-reference
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-unreachable-reference.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-014"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

'''
    if "analog-control-flow-rejects-unreachable-reference" not in text:
        text = replace_once(text, marker, addition + marker, "native unreachable reference test")
    write(path, text)


def patch_manifest(root: Path) -> None:
    path = root / "tests/compiler/fixtures/increment34/manifest.json"
    document = json.loads(read(path))
    semantics = document.setdefault("semantics", {})
    semantics["nested_declaration_locality"] = True
    semantics["native_unreachable_structural_reference_validation"] = True
    write(path, json.dumps(document, indent=2) + "\n")


def patch_checker(root: Path) -> None:
    path = root / "scripts/check_increment34.py"
    text = read(path)
    old = '''        "native_canonical_case_labels",
    ):
'''
    new = '''        "native_canonical_case_labels",
        "nested_declaration_locality",
        "native_unreachable_structural_reference_validation",
    ):
'''
    text = replace_once(text, old, new, "fresh-review manifest semantics")

    old = '''            "flow.breaks.map(_ -- locals)",
            "states.tail.foldLeft(first)(_ intersect _)",
'''
    new = '''            "flow.breaks.map(_ -- locals)",
            "nested declaration must be block-local",
            "states.tail.foldLeft(first)(_ intersect _)",
'''
    text = replace_once(text, old, new, "runtime locality checker token")

    old = '''            "static_definite=",
        ),
'''
    new = '''            "static_definite=",
            "nested_nonlocal=NODAL-ANALOG-034-014",
        ),
'''
    text = replace_once(text, old, new, "runtime locality witness token")

    old = '''            "false flat Increment 33",
        ),
'''
    new = '''            "false flat Increment 33",
            "statically unreachable native regions",
        ),
'''
    text = replace_once(text, old, new, "fixture README structural token")

    old = '''            "isCanonicalStructuredCaseLabel",
            "structured declaration order must be contiguous and authored",
'''
    new = '''            "isCanonicalStructuredCaseLabel",
            "verifyStructuredReferenceInventory",
            "verifyStructuredReferences",
            "structured declaration order must be contiguous and authored",
'''
    text = replace_once(text, old, new, "native structural checker tokens")

    old = '''        "case-label",
    ):
'''
    new = '''        "case-label",
        "unreachable-reference",
    ):
'''
    text = replace_once(text, old, new, "native fresh-review fixture inventory")

    old = '''            "analog-control-flow-rejects-case-label",
        ),
'''
    new = '''            "analog-control-flow-rejects-case-label",
            "analog-control-flow-rejects-unreachable-reference",
        ),
'''
    text = replace_once(text, old, new, "native fresh-review CMake token")
    write(path, text)


def patch_contract_tests(root: Path) -> None:
    path = root / "tests/compiler/test_increment34.py"
    text = read(path)
    old = '''    "core/compiler/test/IR/analog-control-flow-invalid-guard-read.mlir",
'''
    new = old + '''    "core/compiler/test/IR/analog-control-flow-invalid-unreachable-reference.mlir",
'''
    text = replace_once(text, old, new, "required fresh-review fixture")

    marker = '''    def test_write_enabled_workflow_is_rejected(self) -> None:
'''
    addition = '''    def test_nested_declaration_locality_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "nested declaration must be block-local",
                    "removed nested declaration locality",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "Scala control-flow runtime is missing")

    def test_unreachable_structural_reference_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "verifyStructuredReferenceInventory",
                    "removedVerifyStructuredReferenceInventory",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured verifier hardening is missing")

    def test_fresh_review_manifest_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["semantics"][
                "native_unreachable_structural_reference_validation"
            ] = False
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "native hardening semantic")

'''
    if "test_unreachable_structural_reference_mutation_is_rejected" not in text:
        text = replace_once(text, marker, addition + marker, "fresh-review mutation tests")
    write(path, text)


def patch_readme(root: Path) -> None:
    path = root / "tests/compiler/fixtures/increment34/README.md"
    text = read(path)
    old = '''orders, assignment-guard dependencies, canonical integer and Boolean case labels,
and canonical absent-value sentinels for runtime conditions and loops. Solver
'''
    new = '''orders, assignment-guard dependencies, canonical integer and Boolean case labels,
and canonical absent-value sentinels for runtime conditions and loops. Nested
source declarations are required to remain block-local, and statically
unreachable native regions still validate every variable reference for identity,
ownership, and lexical visibility without emitting read-before-write errors.
Solver
'''
    text = replace_once(text, old, new, "fixture README fresh-review evidence")
    write(path, text)


def patch_implementation_record(root: Path) -> None:
    path = root / "docs/implementation/increment34-analog-control-flow.md"
    text = read(path)
    old = '''- [x] Reject hidden static values on runtime or else conditions and hidden
  static trip counts on runtime loops.
'''
    new = old + '''- [x] Validate variable-reference identity and lexical visibility in statically
  unreachable native regions without applying reachable-path read-before-write
  diagnostics.
- [x] Reject nested source-semantic declarations that are not marked block-local.
'''
    text = replace_once(text, old, new, "implementation fresh-review findings")
    write(path, text)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    patch_scala_runtime(root)
    patch_runtime_witness(root)
    patch_native_verifier(root)
    patch_native_fixture(root)
    patch_native_cmake(root)
    patch_manifest(root)
    patch_checker(root)
    patch_contract_tests(root)
    patch_readme(root)
    patch_implementation_record(root)
    print("Increment 34 fresh-review structural hardening applied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
