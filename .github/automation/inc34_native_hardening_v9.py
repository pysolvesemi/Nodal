#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"missing required file: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
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


def patch_native_verifier(root: Path) -> None:
    path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
    text = read(path)

    canonical_labels = '''bool isCanonicalStructuredCaseLabel(llvm::StringRef label,
                                          llvm::StringRef kind) {
  if (kind == "boolean")
    return label == "boolean:true" || label == "boolean:false";
  if (kind != "integer" || !label.consume_front("integer:") || label.empty())
    return false;
  int64_t parsed = 0;
  if (label.getAsInteger(10, parsed))
    return false;
  return label == std::to_string(parsed);
}

'''
    text = insert_before(
        text,
        "bool isProceduralAncestor(Operation *operation) {",
        canonical_labels,
        "canonical structured case-label helper",
    )

    old_context = '''struct StructuredDataflowContext {
  llvm::StringMap<StructuredVariableInfo> variables;
};
'''
    new_context = '''struct StructuredDataflowContext {
  llvm::StringMap<StructuredVariableInfo> variables;
  llvm::StringSet<> operationIdentities;
  int64_t nextDeclarationOrder = 0;
  int64_t nextAssignmentOrder = 0;
};
'''
    if old_context in text:
        text = text.replace(old_context, new_context, 1)
    elif "operationIdentities" not in text:
        raise SystemExit("structured dataflow context was not found")

    identity_helpers = '''bool isStructuredIdentityOperation(Operation *operation) {
  return llvm::isa<nodal::AnalogVariableOp, nodal::AnalogVariableReadOp,
                   nodal::AnalogAssignOp, nodal::AnalogScopeOp, nodal::AnalogIfOp,
                   nodal::AnalogIfArmOp, nodal::AnalogCaseOp,
                   nodal::AnalogCaseArmOp, nodal::AnalogLoopOp,
                   nodal::AnalogBreakOp, nodal::AnalogContinueOp>(operation);
}

llvm::StringRef structuredOperationIdentity(Operation *operation) {
  if (llvm::isa<nodal::AnalogVariableOp>(operation))
    return textAttr(operation, "identity");
  if (llvm::isa<nodal::AnalogVariableReadOp>(operation))
    return textAttr(operation, "read_id");
  if (llvm::isa<nodal::AnalogScopeOp>(operation))
    return textAttr(operation, "scope_id");
  if (llvm::isa<nodal::AnalogIfArmOp, nodal::AnalogCaseArmOp>(operation))
    return textAttr(operation, "arm_id");
  return textAttr(operation, "statement_id");
}

LogicalResult registerStructuredOperationIdentity(
    Operation *operation, StructuredDataflowContext &context) {
  if (!isStructuredIdentityOperation(operation))
    return success();
  llvm::StringRef identity = structuredOperationIdentity(operation);
  if (identity.trim().empty() || identity.trim() != identity)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-001",
        "structured operation identity must be non-empty and canonical");
  if (!context.operationIdentities.insert(identity).second)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-001",
        llvm::Twine("duplicate structured operation identity '") + identity + "'");
  return success();
}

'''
    text = insert_before(
        text,
        "LogicalResult collectStructuredVariables(",
        identity_helpers,
        "procedure-wide structured identity helpers",
    )

    start = text.index("LogicalResult collectStructuredVariables(")
    end = text.index("LogicalResult requireStructuredRead(", start)
    prefix, body, suffix = text[:start], text[start:end], text[end:]

    old_loop = '''  for (Operation &operation : block) {
    if (llvm::isa<nodal::AnalogVariableOp>(operation)) {
'''
    new_loop = '''  for (Operation &operation : block) {
    if (failed(registerStructuredOperationIdentity(&operation, context)))
      return failure();
    if (llvm::isa<nodal::AnalogVariableOp>(operation)) {
'''
    if old_loop in body:
        body = body.replace(old_loop, new_loop, 1)
    elif "registerStructuredOperationIdentity(&operation, context)" not in body:
        raise SystemExit("structured identity registration point was not found")

    order_anchor = '''      if (!context.variables.try_emplace(identity, StructuredVariableInfo{operation.getBlock()})
               .second)
'''
    declaration_order = '''      auto declarationOrder = operation.getAttrOfType<IntegerAttr>("declaration_order");
      if (!declarationOrder || declarationOrder.getInt() != context.nextDeclarationOrder)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured declaration order must be contiguous and authored");
      ++context.nextDeclarationOrder;
'''
    if declaration_order.strip() not in body:
        body = replace_once(
            body,
            order_anchor,
            declaration_order + order_anchor,
            "structured declaration order validation",
        )

    region_anchor = "    for (Region &region : operation.getRegions()) {\n"
    assignment_order = '''    if (llvm::isa<nodal::AnalogAssignOp>(operation)) {
      auto authoredOrder = operation.getAttrOfType<IntegerAttr>("authored_order");
      if (!authoredOrder || authoredOrder.getInt() != context.nextAssignmentOrder)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured assignment order must be contiguous and authored");
      ++context.nextAssignmentOrder;
    }
'''
    if assignment_order.strip() not in body:
        body = replace_once(
            body,
            region_anchor,
            assignment_order + region_anchor,
            "structured assignment order validation",
        )

    text = prefix + body + suffix

    guard_anchor = '''  if (llvm::isa<nodal::AnalogAssignOp>(operation)) {
    StructuredInitializedSet output = input;
'''
    guard_check = '''  if (llvm::isa<nodal::AnalogAssignOp>(operation)) {
    StructuredInitializedSet output = input;
    if (failed(requireStructuredReads(operation, "guard_reads", input, context)))
      return failure();
'''
    if guard_anchor in text:
        text = text.replace(guard_anchor, guard_check, 1)
    elif '"guard_reads", input, context' not in text:
        raise SystemExit("structured assignment guard-read analysis point was not found")

    text = text.replace(
        'if (!staticValue.starts_with(kind.str() + ":"))',
        "if (!isCanonicalStructuredCaseLabel(staticValue, kind))",
        1,
    )
    text = text.replace(
        'if (!label || !label.getValue().starts_with(kind.str() + ":"))',
        "if (!label || !isCanonicalStructuredCaseLabel(label.getValue(), kind))",
        1,
    )
    if text.count("isCanonicalStructuredCaseLabel") < 3:
        raise SystemExit("canonical case-label checks were not installed")

    write(path, text)


def invalid_fixtures(root: Path) -> None:
    directory = root / "core/compiler/test/IR"
    valid = read(directory / "analog-control-flow.mlir")

    ids = list(re.finditer(r'statement_id = "([^"]+)"', valid))
    if len(ids) < 2:
        raise SystemExit("valid control-flow fixture lacks two statement identities")
    duplicate = (
        valid[: ids[1].start(1)]
        + ids[0].group(1)
        + valid[ids[1].end(1) :]
    )
    write(directory / "analog-control-flow-invalid-duplicate-identity.mlir", duplicate)

    if "authored_order = 0 : i64" not in valid:
        raise SystemExit("valid control-flow fixture lacks authored order zero")
    invalid_order = valid.replace(
        "authored_order = 0 : i64",
        "authored_order = 7 : i64",
        1,
    )
    write(directory / "analog-control-flow-invalid-order.mlir", invalid_order)

    label = re.search(r'labels = \["integer:[^"]+"', valid)
    if not label:
        raise SystemExit("valid control-flow fixture lacks an integer case label")
    malformed_label = valid[: label.start()] + 'labels = ["integer:+1"' + valid[label.end() :]
    write(directory / "analog-control-flow-invalid-case-label.mlir", malformed_label)

    lines = valid.splitlines()
    target_handle = None
    target_identity = None
    for line in lines:
        if '"nodal.analog_variable"' not in line or "initialized = false" not in line:
            continue
        handle = re.match(r"\s*(%[^ ]+)\s*=", line)
        identity = re.search(r'identity = "([^"]+)"', line)
        if handle and identity:
            candidate = handle.group(1)
            if any(
                '"nodal.analog_assign"' in assignment
                and f"({candidate}" in assignment
                for assignment in lines
            ):
                target_handle = candidate
                target_identity = identity.group(1)
                break
    if not target_handle or not target_identity:
        raise SystemExit("valid control-flow fixture lacks an assignable uninitialized variable")

    guard_bundle = (
        'guard_dimension = "", guard_kind = "", guard_present = false, '
        'guard_reads = [], guard_value = ""'
    )
    replacement = (
        'guard_dimension = "1", guard_kind = "boolean", guard_present = true, '
        f'guard_reads = ["{target_identity}"], guard_value = "uninitialized-guard"'
    )
    guarded = None
    for index, line in enumerate(lines):
        if '"nodal.analog_assign"' in line and f"({target_handle}" in line:
            if guard_bundle not in line:
                continue
            updated = list(lines)
            updated[index] = line.replace(guard_bundle, replacement, 1)
            guarded = "\n".join(updated) + "\n"
            break
    if guarded is None:
        raise SystemExit("valid control-flow fixture lacks an unguarded target assignment")
    write(directory / "analog-control-flow-invalid-guard-read.mlir", guarded)


def patch_native_tests(root: Path) -> None:
    path = root / "core/compiler/test/CMakeLists.txt"
    text = read(path)
    addition = '''add_test(
  NAME nodal.native.analog-control-flow-rejects-duplicate-identity
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-duplicate-identity.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-001"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

add_test(
  NAME nodal.native.analog-control-flow-rejects-order
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-order.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-014"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

add_test(
  NAME nodal.native.analog-control-flow-rejects-guard-read
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-guard-read.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-004"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

add_test(
  NAME nodal.native.analog-control-flow-rejects-case-label
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-case-label.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-007"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

'''
    text = insert_before(
        text,
        "add_custom_target(check-nodal-native\n",
        addition,
        "native hardening CMake tests",
    )
    write(path, text)


def patch_manifest_and_docs(root: Path) -> None:
    manifest_path = root / "tests/compiler/fixtures/increment34/manifest.json"
    manifest = json.loads(read(manifest_path))
    semantics = manifest["semantics"]
    semantics["native_procedure_wide_identity_uniqueness"] = True
    semantics["native_authored_order_verification"] = True
    semantics["native_guard_read_definite_assignment"] = True
    semantics["native_canonical_case_labels"] = True
    write(manifest_path, json.dumps(manifest, indent=2) + "\n")

    implementation_path = root / "docs/implementation/increment34-analog-control-flow.md"
    implementation = read(implementation_path)
    marker = '''- [x] Implement native branch-sensitive definite-assignment dataflow over the
  first-class regions, including unmatched selection and bounded-loop exits.
'''
    addition = '''- [x] Enforce procedure-wide native operation-identity uniqueness and contiguous
  declaration and assignment order.
- [x] Include assignment guard dependencies in native definite-assignment
  analysis.
- [x] Reject non-canonical integer and Boolean case-label spellings at the
  compiler boundary.
'''
    if addition.strip() not in implementation:
        implementation = replace_once(
            implementation,
            marker,
            marker + addition,
            "implementation hardening checklist",
        )
    write(implementation_path, implementation)

    readme_path = root / "tests/compiler/fixtures/increment34/README.md"
    readme = read(readme_path)
    marker = '''Native branch-sensitive definite-assignment now intersects all reachable
normal, unmatched, `break`, and `continue` exits and rejects reachable reads
after incomplete conditionals, cases, and zero-minimum loops. Solver
construction, target legalization, and Verilog-A or Verilog-AMS procedural
lowering remain deferred to their owning increments.
'''
    replacement = '''Native branch-sensitive definite-assignment now intersects all reachable
normal, unmatched, `break`, and `continue` exits and rejects reachable reads
after incomplete conditionals, cases, and zero-minimum loops. The native
boundary also enforces procedure-wide operation identities, contiguous authored
orders, assignment-guard dependencies, and canonical integer and Boolean case
labels. Solver construction, target legalization, and Verilog-A or Verilog-AMS
procedural lowering remain deferred to their owning increments.
'''
    if marker in readme:
        readme = readme.replace(marker, replacement, 1)
    elif "procedure-wide operation identities" not in readme:
        raise SystemExit("fixture README hardening paragraph was not found")
    write(readme_path, readme)


def patch_checker(root: Path) -> None:
    path = root / "scripts/check_increment34.py"
    text = read(path)

    semantic_checks = '''    for key in (
        "native_procedure_wide_identity_uniqueness",
        "native_authored_order_verification",
        "native_guard_read_definite_assignment",
        "native_canonical_case_labels",
    ):
        require(
            semantics.get(key) is True,
            f"NODAL-INC34-039: native hardening semantic {key!r} is not enabled",
        )

'''
    text = insert_before(
        text,
        "    integration = manifest.get(\"integration\")\n",
        semantic_checks,
        "manifest native hardening checks",
    )

    hardening = '''    require_tokens(
        native_verifier,
        (
            "registerStructuredOperationIdentity",
            "int64_t nextDeclarationOrder = 0",
            "int64_t nextAssignmentOrder = 0",
            '"guard_reads", input, context',
            "isCanonicalStructuredCaseLabel",
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
        ),
        "NODAL-INC34-042",
        "native structured hardening tests",
    )

'''
    text = insert_before(
        text,
        "    forbidden_names = {\n",
        hardening,
        "native hardening checker block",
    )
    write(path, text)


def patch_mutations(root: Path) -> None:
    path = root / "tests/compiler/test_increment34.py"
    text = read(path)

    required_marker = '''    "core/compiler/test/IR/analog-control-flow-invalid-continue-path.mlir",
'''
    required_addition = '''    "core/compiler/test/IR/analog-control-flow-invalid-duplicate-identity.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-order.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-guard-read.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-case-label.mlir",
'''
    if required_addition.strip() not in text:
        text = replace_once(
            text,
            required_marker,
            required_marker + required_addition,
            "mutation fixture inventory",
        )

    methods = '''    def test_native_global_identity_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "registerStructuredOperationIdentity",
                    "removedRegisterStructuredOperationIdentity",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured verifier hardening is missing")

    def test_native_assignment_order_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "int64_t nextAssignmentOrder = 0;",
                    "int64_t removedNextAssignmentOrder = 0;",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured verifier hardening is missing")

    def test_native_guard_read_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '\"guard_reads\", input, context',
                    '\"removed_guard_reads\", input, context',
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured verifier hardening is missing")

    def test_native_case_label_canonicality_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "isCanonicalStructuredCaseLabel",
                    "removedCanonicalStructuredCaseLabel",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured verifier hardening is missing")

    def test_native_hardening_manifest_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["semantics"]["native_guard_read_definite_assignment"] = False
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "native hardening semantic")

'''
    text = insert_before(
        text,
        "    def test_write_enabled_workflow_is_rejected(self) -> None:\n",
        methods,
        "native hardening mutation tests",
    )
    write(path, text)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()

    patch_native_verifier(root)
    invalid_fixtures(root)
    patch_native_tests(root)
    patch_manifest_and_docs(root)
    patch_checker(root)
    patch_mutations(root)
    print("Increment 34 native verifier hardening v9 applied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
