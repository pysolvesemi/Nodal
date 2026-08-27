#!/usr/bin/env python3
"""Materialize Increment 27 implementation on its feature branch."""

from __future__ import annotations

import json
from pathlib import Path
from textwrap import dedent

ROOT = Path(__file__).resolve().parents[1]


def write(relative: str, content: str) -> None:
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(dedent(content).lstrip("\n"), encoding="utf-8")


def replace_once(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise RuntimeError(f"expected one anchor in {relative}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


ops_section = r'''
//===----------------------------------------------------------------------===//
// Analog natures, disciplines, and normalized imports
//===----------------------------------------------------------------------===//

def Nodal_NatureOp : Nodal_Op<"nature", [Symbol]> {
  let summary = "Canonical analog nature declaration";
  let description = [{
    A nature records a canonical unit spelling, one access-function identity,
    and a positive finite absolute tolerance. Nature identity remains nominal;
    imports resolve to one canonical declaration before compatibility checks.
  }];
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    StrAttr:$units,
    StrAttr:$access,
    F64Attr:$abstol,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_NatureImportOp : Nodal_Op<"nature_import", [Symbol]> {
  let summary = "Hash-pinned local alias of a canonical nature declaration";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    FlatSymbolRefAttr:$target,
    StrAttr:$source,
    StrAttr:$definition_hash,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_DisciplineOp : Nodal_Op<"discipline", [Symbol]> {
  let summary = "Analog discipline declaration";
  let description = [{
    A discipline selects a continuous or discrete domain, one potential
    nature, and an optional flow nature. Absence of flow denotes a
    signal-flow discipline; presence of both denotes a conservative
    discipline.
  }];
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    StrAttr:$domain,
    FlatSymbolRefAttr:$potential,
    OptionalAttr<FlatSymbolRefAttr>:$flow,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_DisciplineImportOp : Nodal_Op<"discipline_import", [Symbol]> {
  let summary = "Hash-pinned local alias of a canonical discipline declaration";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    FlatSymbolRefAttr:$target,
    StrAttr:$source,
    StrAttr:$definition_hash,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

'''
replace_once(
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "//===----------------------------------------------------------------------===//\n// Conservative connectivity and explicit mixed-signal bridges\n//===----------------------------------------------------------------------===//\n",
    ops_section
    + "//===----------------------------------------------------------------------===//\n// Conservative connectivity and explicit mixed-signal bridges\n//===----------------------------------------------------------------------===//\n",
)

write(
    "core/compiler/include/nodal/Dialect/Nodal/NatureDiscipline.h",
    r'''
#ifndef NODAL_DIALECT_NODAL_NATUREDISCIPLINE_H
#define NODAL_DIALECT_NODAL_NATUREDISCIPLINE_H

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

namespace nodal {

/// Resolve a nature declaration through zero or more hash-pinned import aliases.
mlir::FailureOr<mlir::Operation *>
resolveNatureDeclaration(mlir::Operation *scope,
                         mlir::FlatSymbolRefAttr reference);

/// Resolve a discipline declaration through zero or more hash-pinned aliases.
mlir::FailureOr<mlir::Operation *>
resolveDisciplineDeclaration(mlir::Operation *scope,
                             mlir::FlatSymbolRefAttr reference);

/// Compare canonical domain, potential nature, and optional flow nature.
/// Distinct symbols are compatible only when all three canonical identities
/// match after import resolution.
mlir::FailureOr<bool>
areDisciplinesCompatible(mlir::Operation *scope,
                         mlir::FlatSymbolRefAttr lhs,
                         mlir::FlatSymbolRefAttr rhs);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_NATUREDISCIPLINE_H
''',
)

write(
    "core/compiler/lib/Dialect/Nodal/NatureDiscipline.cpp",
    r'''
#include "nodal/Dialect/Nodal/NatureDiscipline.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/Support/Casting.h"

#include <cmath>

using namespace mlir;

namespace {

mlir::ModuleOp enclosingBuiltinModule(Operation *operation) {
  if (!operation)
    return {};
  if (auto module = llvm::dyn_cast<mlir::ModuleOp>(operation))
    return module;
  return operation->getParentOfType<mlir::ModuleOp>();
}

Operation *findTopLevelSymbol(mlir::ModuleOp module, llvm::StringRef symbol) {
  if (!module)
    return nullptr;
  for (Operation &candidate : module.getBody()->getOperations()) {
    auto name = candidate.getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName());
    if (name && name.getValue() == symbol)
      return &candidate;
  }
  return nullptr;
}

FailureOr<Operation *> resolveDeclaration(Operation *scope,
                                          FlatSymbolRefAttr reference,
                                          llvm::StringRef declarationName,
                                          llvm::StringRef importName) {
  mlir::ModuleOp module = enclosingBuiltinModule(scope);
  if (!module || !reference || reference.getValue().empty())
    return failure();

  llvm::DenseSet<Operation *> visited;
  llvm::StringRef symbol = reference.getValue();
  while (true) {
    Operation *candidate = findTopLevelSymbol(module, symbol);
    if (!candidate)
      return failure();
    llvm::StringRef name = candidate->getName().getStringRef();
    if (name == declarationName)
      return candidate;
    if (name != importName || !visited.insert(candidate).second)
      return failure();
    auto target = candidate->getAttrOfType<FlatSymbolRefAttr>("target");
    if (!target || target.getValue().empty())
      return failure();
    symbol = target.getValue();
  }
}

bool isTopLevelSymbol(Operation *operation) {
  return operation && operation->getParentOp() &&
         llvm::isa<mlir::ModuleOp>(operation->getParentOp());
}

bool hasCanonicalSymbol(Operation *operation) {
  auto symbol =
      operation->getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName());
  return symbol && !symbol.getValue().trim().empty() &&
         symbol.getValue() == symbol.getValue().trim();
}

bool isIdentifier(llvm::StringRef value) {
  if (value.empty() || !(llvm::isAlpha(value.front()) || value.front() == '_'))
    return false;
  return llvm::all_of(value.drop_front(), [](char character) {
    return llvm::isAlnum(character) || character == '_' || character == '$';
  });
}

bool isCanonicalText(llvm::StringRef value) {
  if (value.empty() || value != value.trim())
    return false;
  return llvm::all_of(value, [](char character) {
    const unsigned char byte = static_cast<unsigned char>(character);
    return byte >= 0x20 && byte != 0x7f;
  });
}

bool isSha256(llvm::StringRef value) {
  return value.size() == 64 && llvm::all_of(value, [](char character) {
           return llvm::isDigit(character) ||
                  (character >= 'a' && character <= 'f');
         });
}

LogicalResult verifyImportedAlias(Operation *operation,
                                  llvm::StringRef kind,
                                  llvm::StringRef importCode,
                                  llvm::StringRef provenanceCode,
                                  FailureOr<Operation *> (*resolver)(
                                      Operation *, FlatSymbolRefAttr)) {
  if (!isTopLevelSymbol(operation) || !hasCanonicalSymbol(operation))
    return operation->emitOpError()
           << importCode << ": " << kind
           << " import must be a top-level canonical symbol";

  auto source = operation->getAttrOfType<StringAttr>("source");
  auto hash = operation->getAttrOfType<StringAttr>("definition_hash");
  if (!source || !isCanonicalText(source.getValue()) || !hash ||
      !isSha256(hash.getValue()))
    return operation->emitOpError()
           << provenanceCode << ": " << kind
           << " import requires canonical source text and lowercase SHA-256";

  auto target = operation->getAttrOfType<FlatSymbolRefAttr>("target");
  if (!target || failed(resolver(operation, target)))
    return operation->emitOpError()
           << importCode << ": " << kind
           << " import target is missing, cyclic, or has the wrong kind";
  return success();
}

} // namespace

FailureOr<Operation *>
nodal::resolveNatureDeclaration(Operation *scope,
                                FlatSymbolRefAttr reference) {
  return resolveDeclaration(scope, reference, "nodal.nature",
                            "nodal.nature_import");
}

FailureOr<Operation *>
nodal::resolveDisciplineDeclaration(Operation *scope,
                                    FlatSymbolRefAttr reference) {
  return resolveDeclaration(scope, reference, "nodal.discipline",
                            "nodal.discipline_import");
}

FailureOr<bool>
nodal::areDisciplinesCompatible(Operation *scope, FlatSymbolRefAttr lhs,
                                FlatSymbolRefAttr rhs) {
  FailureOr<Operation *> left = resolveDisciplineDeclaration(scope, lhs);
  FailureOr<Operation *> right = resolveDisciplineDeclaration(scope, rhs);
  if (failed(left) || failed(right))
    return failure();
  if (*left == *right)
    return true;

  auto leftDomain = (*left)->getAttrOfType<StringAttr>("domain");
  auto rightDomain = (*right)->getAttrOfType<StringAttr>("domain");
  if (!leftDomain || !rightDomain || leftDomain != rightDomain)
    return false;

  auto leftPotential = (*left)->getAttrOfType<FlatSymbolRefAttr>("potential");
  auto rightPotential = (*right)->getAttrOfType<FlatSymbolRefAttr>("potential");
  FailureOr<Operation *> canonicalLeftPotential =
      resolveNatureDeclaration(*left, leftPotential);
  FailureOr<Operation *> canonicalRightPotential =
      resolveNatureDeclaration(*right, rightPotential);
  if (failed(canonicalLeftPotential) || failed(canonicalRightPotential))
    return failure();
  if (*canonicalLeftPotential != *canonicalRightPotential)
    return false;

  auto leftFlow = (*left)->getAttrOfType<FlatSymbolRefAttr>("flow");
  auto rightFlow = (*right)->getAttrOfType<FlatSymbolRefAttr>("flow");
  if (static_cast<bool>(leftFlow) != static_cast<bool>(rightFlow))
    return false;
  if (!leftFlow)
    return true;

  FailureOr<Operation *> canonicalLeftFlow =
      resolveNatureDeclaration(*left, leftFlow);
  FailureOr<Operation *> canonicalRightFlow =
      resolveNatureDeclaration(*right, rightFlow);
  if (failed(canonicalLeftFlow) || failed(canonicalRightFlow))
    return failure();
  return *canonicalLeftFlow == *canonicalRightFlow;
}

LogicalResult nodal::NatureOp::verify() {
  if (!isTopLevelSymbol(getOperation()) || !hasCanonicalSymbol(getOperation()))
    return emitOpError(
        "NODAL-NATURE-DECL-001: nature must be a top-level canonical symbol");

  auto units = getOperation()->getAttrOfType<StringAttr>("units");
  if (!units || !isCanonicalText(units.getValue()))
    return emitOpError(
        "NODAL-NATURE-UNITS-001: units must be non-empty canonical text");

  auto access = getOperation()->getAttrOfType<StringAttr>("access");
  if (!access || !isIdentifier(access.getValue()))
    return emitOpError(
        "NODAL-NATURE-ACCESS-001: access must be a canonical identifier");

  auto tolerance = getOperation()->getAttrOfType<FloatAttr>("abstol");
  if (!tolerance || !std::isfinite(tolerance.getValueAsDouble()) ||
      tolerance.getValueAsDouble() <= 0.0)
    return emitOpError(
        "NODAL-NATURE-TOLERANCE-001: abstol must be positive and finite");

  mlir::ModuleOp module = enclosingBuiltinModule(getOperation());
  for (Operation &candidate : module.getBody()->getOperations()) {
    if (&candidate == getOperation() ||
        candidate.getName().getStringRef() != "nodal.nature")
      continue;
    auto other = candidate.getAttrOfType<StringAttr>("access");
    if (other && other.getValue() == access.getValue())
      return emitOpError()
             << "NODAL-NATURE-ACCESS-002: access function '"
             << access.getValue() << "' is owned by another nature";
  }
  return success();
}

LogicalResult nodal::NatureImportOp::verify() {
  return verifyImportedAlias(getOperation(), "nature",
                             "NODAL-NATURE-IMPORT-001",
                             "NODAL-NATURE-IMPORT-002",
                             nodal::resolveNatureDeclaration);
}

LogicalResult nodal::DisciplineOp::verify() {
  if (!isTopLevelSymbol(getOperation()) || !hasCanonicalSymbol(getOperation()))
    return emitOpError(
        "NODAL-DISCIPLINE-DECL-001: discipline must be a top-level canonical symbol");

  auto domain = getOperation()->getAttrOfType<StringAttr>("domain");
  if (!domain || (domain.getValue() != "continuous" &&
                  domain.getValue() != "discrete"))
    return emitOpError(
        "NODAL-DISCIPLINE-DOMAIN-001: domain must be continuous or discrete");

  auto potential =
      getOperation()->getAttrOfType<FlatSymbolRefAttr>("potential");
  FailureOr<Operation *> potentialNature =
      nodal::resolveNatureDeclaration(getOperation(), potential);
  if (!potential || failed(potentialNature))
    return emitOpError(
        "NODAL-DISCIPLINE-POTENTIAL-001: potential must resolve to a nature");

  if (auto flow = getOperation()->getAttrOfType<FlatSymbolRefAttr>("flow")) {
    FailureOr<Operation *> flowNature =
        nodal::resolveNatureDeclaration(getOperation(), flow);
    if (failed(flowNature) || *flowNature == *potentialNature)
      return emitOpError(
          "NODAL-DISCIPLINE-FLOW-001: flow must resolve to a distinct nature");
  }
  return success();
}

LogicalResult nodal::DisciplineImportOp::verify() {
  return verifyImportedAlias(getOperation(), "discipline",
                             "NODAL-DISCIPLINE-IMPORT-001",
                             "NODAL-DISCIPLINE-IMPORT-002",
                             nodal::resolveDisciplineDeclaration);
}
''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
    "  NodalDialect.cpp\n  NodalOps.cpp\n  NodalTypes.cpp\n",
    "  NodalDialect.cpp\n  NatureDiscipline.cpp\n  NodalOps.cpp\n  NodalTypes.cpp\n",
)

write(
    "core/compiler/test/Unit/NatureDisciplineTest.cpp",
    r'''
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NatureDiscipline.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/Casting.h"
#include "llvm/Support/raw_ostream.h"

namespace {

int fail(llvm::StringRef message) {
  llvm::errs() << "NatureDisciplineTest: " << message << '\n';
  return 1;
}

constexpr llvm::StringLiteral kValid = R"mlir(
module {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-3 : f64, access = "Temp", metadata = {}, sym_name = "Temperature", units = "K"}> : () -> ()
  "nodal.nature_import"() <{definition_hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", metadata = {}, source = "std://disciplines/electrical@2023", sym_name = "VoltageImported", target = @Voltage}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @VoltageImported, sym_name = "electrical"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical_monitor"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", metadata = {}, potential = @Temperature, sym_name = "thermal_signal"}> : () -> ()
  "nodal.discipline_import"() <{definition_hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", metadata = {}, source = "std://disciplines/electrical@2023", sym_name = "electrical_imported", target = @electrical}> : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "Fixture"}> ({
  ^bb0:
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kInvalidTolerance = R"mlir(
module {
  "nodal.nature"() <{abstol = 0.0 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({ ^bb0: }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kInvalidAssociation = R"mlir(
module {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Missing, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({ ^bb0: }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kImportCycle = R"mlir(
module {
  "nodal.nature_import"() <{definition_hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", metadata = {}, source = "pkg://a", sym_name = "A", target = @B}> : () -> ()
  "nodal.nature_import"() <{definition_hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", metadata = {}, source = "pkg://b", sym_name = "B", target = @A}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({ ^bb0: }) : () -> ()
}
)mlir";

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();

  auto valid = mlir::parseSourceString<mlir::ModuleOp>(kValid, &context);
  if (!valid || mlir::failed(mlir::verify(*valid)))
    return fail("valid nature/discipline inventory did not verify");

  unsigned natures = 0;
  unsigned disciplines = 0;
  valid->walk([&](mlir::Operation *operation) {
    natures += llvm::isa<nodal::NatureOp>(operation);
    disciplines += llvm::isa<nodal::DisciplineOp>(operation);
  });
  if (natures != 3 || disciplines != 3)
    return fail("typed nature/discipline inventory is incorrect");

  auto electrical = mlir::FlatSymbolRefAttr::get(&context, "electrical");
  auto monitor = mlir::FlatSymbolRefAttr::get(&context, "electrical_monitor");
  auto imported = mlir::FlatSymbolRefAttr::get(&context, "electrical_imported");
  auto thermal = mlir::FlatSymbolRefAttr::get(&context, "thermal_signal");

  auto compatible = nodal::areDisciplinesCompatible(
      valid->getOperation(), electrical, monitor);
  auto aliasCompatible = nodal::areDisciplinesCompatible(
      valid->getOperation(), electrical, imported);
  auto incompatible = nodal::areDisciplinesCompatible(
      valid->getOperation(), electrical, thermal);
  if (mlir::failed(compatible) || !*compatible ||
      mlir::failed(aliasCompatible) || !*aliasCompatible ||
      mlir::failed(incompatible) || *incompatible)
    return fail("canonical discipline compatibility is incorrect");

  if (mlir::parseSourceString<mlir::ModuleOp>(kInvalidTolerance, &context))
    return fail("non-positive nature tolerance was accepted");
  if (mlir::parseSourceString<mlir::ModuleOp>(kInvalidAssociation, &context))
    return fail("unknown flow nature was accepted");
  if (mlir::parseSourceString<mlir::ModuleOp>(kImportCycle, &context))
    return fail("cyclic nature import was accepted");

  return 0;
}
''',
)

unit_block = r'''

add_executable(nodal-nature-discipline-unit-tests
  NatureDisciplineTest.cpp
)

llvm_update_compile_flags(nodal-nature-discipline-unit-tests)

target_link_libraries(nodal-nature-discipline-unit-tests
  PRIVATE
    NodalDialect
    MLIRIR
    MLIRParser
    MLIRSupport
    LLVMSupport
)

add_test(
  NAME nodal.native.nature-discipline-unit
  COMMAND nodal-nature-discipline-unit-tests
)
'''
path = ROOT / "core/compiler/test/Unit/CMakeLists.txt"
text = path.read_text(encoding="utf-8")
if "nodal-nature-discipline-unit-tests" in text:
    raise RuntimeError("unit target already present")
path.write_text(text.rstrip() + dedent(unit_block) + "\n", encoding="utf-8")

write(
    "core/compiler/test/IR/natures-disciplines.mlir",
    r'''
module attributes {
  nodal.target.profile = "analog",
  nodal.verify.analog_topology = true,
  nodal.verify.assignment_coverage = true,
  nodal.verify.cdc_rdc_safe = true,
  nodal.verify.clock_reset_domains = true,
  nodal.verify.combinational_acyclic = true,
  nodal.verify.construction_closed = true,
  nodal.verify.driver_coverage = true,
  nodal.verify.enum_fsm = true,
  nodal.verify.hierarchy_closed = true,
  nodal.verify.latch_free = true,
  nodal.verify.layout_storage = true,
  nodal.verify.memory_effects = true,
  nodal.verify.mixed_signal_bridges = true,
  nodal.verify.parameters_complete = true,
  nodal.verify.protocol_pipeline = true,
  nodal.verify.target_capability = true,
  nodal.verify.width_sign_shape = true
} {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {semantic_path = "std.Voltage"}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", metadata = {semantic_path = "std.Current"}, sym_name = "Current", units = "A"}> : () -> ()
  "nodal.nature_import"() <{definition_hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", metadata = {semantic_path = "imports.Voltage"}, source = "std://disciplines/electrical@2023", sym_name = "VoltageImported", target = @Voltage}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {kind = "conservative"}, potential = @VoltageImported, sym_name = "electrical"}> : () -> ()
  "nodal.discipline_import"() <{definition_hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", metadata = {semantic_path = "imports.electrical"}, source = "std://disciplines/electrical@2023", sym_name = "electricalImported", target = @electrical}> : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "NatureDisciplineFixture"}> ({
  ^bb0:
  }) : () -> ()
}
''',
)

write(
    "core/compiler/test/IR/natures-disciplines-invalid-tolerance.mlir",
    r'''
module {
  "nodal.nature"() <{abstol = 0.0 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "InvalidTolerance"}> ({ ^bb0: }) : () -> ()
}
''',
)

write(
    "core/compiler/test/IR/natures-disciplines-invalid-association.mlir",
    r'''
module {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Missing, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "InvalidAssociation"}> ({ ^bb0: }) : () -> ()
}
''',
)

write(
    "core/compiler/test/IR/natures-disciplines-invalid-import-cycle.mlir",
    r'''
module {
  "nodal.nature_import"() <{definition_hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", metadata = {}, source = "pkg://a", sym_name = "A", target = @B}> : () -> ()
  "nodal.nature_import"() <{definition_hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", metadata = {}, source = "pkg://b", sym_name = "B", target = @A}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "InvalidImport"}> ({ ^bb0: }) : () -> ()
}
''',
)

test_block = r'''
add_test(
  NAME nodal.native.natures-disciplines-roundtrip
  COMMAND nodalc
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/natures-disciplines.mlir"
)
set_tests_properties(
  nodal.native.natures-disciplines-roundtrip
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]discipline_import"
)

add_test(
  NAME nodal.native.natures-disciplines-generic
  COMMAND nodalc
    --mlir-print-op-generic
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/natures-disciplines.mlir"
)
set_tests_properties(
  nodal.native.natures-disciplines-generic
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]nature_import"
)

foreach(_fixture IN ITEMS tolerance association import-cycle)
  add_test(
    NAME nodal.native.natures-disciplines-rejects-${_fixture}
    COMMAND nodalc
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/natures-disciplines-invalid-${_fixture}.mlir"
  )
  set_tests_properties(
    nodal.native.natures-disciplines-rejects-${_fixture}
    PROPERTIES
      WILL_FAIL TRUE
  )
endforeach()

'''
replace_once(
    "core/compiler/test/CMakeLists.txt",
    "add_custom_target(check-nodal-native\n",
    dedent(test_block) + "add_custom_target(check-nodal-native\n",
)
replace_once(
    "core/compiler/test/CMakeLists.txt",
    "    nodal-analog-expression-unit-tests\n",
    "    nodal-analog-expression-unit-tests\n    nodal-nature-discipline-unit-tests\n",
)

catalog_path = ROOT / "core/compiler/diagnostics-v0.1.json"
catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
codes = [
    "NODAL-NATURE-DECL-001",
    "NODAL-NATURE-UNITS-001",
    "NODAL-NATURE-ACCESS-001",
    "NODAL-NATURE-ACCESS-002",
    "NODAL-NATURE-TOLERANCE-001",
    "NODAL-NATURE-IMPORT-001",
    "NODAL-NATURE-IMPORT-002",
    "NODAL-DISCIPLINE-DECL-001",
    "NODAL-DISCIPLINE-DOMAIN-001",
    "NODAL-DISCIPLINE-POTENTIAL-001",
    "NODAL-DISCIPLINE-FLOW-001",
    "NODAL-DISCIPLINE-IMPORT-001",
    "NODAL-DISCIPLINE-IMPORT-002",
]
catalog["families"]["nature-discipline"] = codes
for prefix in ("NODAL-NATURE-", "NODAL-DISCIPLINE-"):
    if prefix not in catalog["preserved_prefixes"]:
        catalog["preserved_prefixes"].append(prefix)
catalog_path.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")

write(
    "docs/design-gates/NodalNatureDiscipline-DG-v1.0.md",
    r'''
# Nodal natures and disciplines design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** compiler-ir
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Increment 27 adds the first complete normalized declaration model for analog
natures and disciplines without assigning source-language or target-HDL
spelling to the public API.

A `nodal.nature` is a top-level symbol containing canonical unit text, one
access-function identifier, and a positive finite absolute tolerance. A
`nodal.discipline` is a top-level symbol containing a `continuous` or
`discrete` domain, one potential-nature reference, and an optional distinct
flow-nature reference. Missing flow denotes signal-flow semantics; potential
plus flow denotes conservative semantics.

`nodal.nature_import` and `nodal.discipline_import` are normalized local aliases.
Each alias carries canonical source provenance and a lowercase SHA-256 of the
resolved external definition. Import chains resolve to a direct declaration;
missing targets, wrong-kind targets, and cycles fail verification.

## Compatibility contract

- Nature identity is nominal after import resolution.
- Discipline aliases are transparent.
- Two discipline declarations are compatible only when their domains match,
  their canonical potential natures match, and either both omit flow or their
  canonical flow natures match.
- Equal unit strings or tolerances do not make distinct nature declarations
  compatible.
- Access-function identifiers are globally unique across direct nature
  declarations in one normalized MLIR module.
- Stable `NODAL-NATURE-*` and `NODAL-DISCIPLINE-*` diagnostics cover declaration,
  unit, access, tolerance, domain, association, import, provenance, and cycle
  failures.

## Explicitly deferred

- Scala public syntax, standard-library packaging, and external package loading;
- binding terminals, nodes, nets, and branches to declarations (Increment 28);
- unit-aware parameters, constants, and ranges (Increment 29);
- potential/flow access evaluation (Increment 31) and contribution semantics
  (Increment 32);
- Verilog-A/Verilog-AMS declaration emission and backend capability support;
- nature inheritance, derived natures, connect rules, and simulator-specific
  tolerance policy.

The existing Increment 25 RC vertical slice remains unchanged and fail-closed
for these new declaration operations until a later backend increment explicitly
owns their emission.
''',
)

write(
    "docs/implementation/increment27-natures-disciplines.md",
    r'''
# Increment 27 — Natures and disciplines

Increment 27 extends the private `nodal` MLIR dialect with symbolized analog
nature and discipline declarations while keeping public API v0.3 unchanged.

## Implemented vocabulary

- `nodal.nature`: canonical units, access-function identity, and positive finite
  `abstol`.
- `nodal.nature_import`: source- and SHA-256-pinned alias resolving to a canonical
  nature declaration.
- `nodal.discipline`: continuous/discrete domain, required potential nature, and
  optional distinct flow nature.
- `nodal.discipline_import`: source- and SHA-256-pinned alias resolving to a
  canonical discipline declaration.

The compiler library exposes deterministic import resolution and discipline
compatibility helpers. Compatibility compares canonical domain, potential, and
optional flow identity; it does not infer equivalence from matching text.

## Verification

TableGen registration, operation-local verification, native compatibility unit
tests, positive/generic MLIR round trips, negative tolerance/association/import
fixtures, stable diagnostic catalog entries, checker mutation tests, and a
read-only dedicated workflow provide the Increment 27 evidence surface.

Node/branch binding, source lowering, standard-library loading, unit-aware
values, access evaluation, and HDL emission remain deferred to their named
roadmap increments. The existing RC backend therefore remains fail-closed for
these declaration operations.
''',
)

manifest = {
    "increment": 27,
    "title": "Natures and disciplines",
    "public_api": "0.3",
    "status": "implemented-awaiting-evidence",
    "operations": [
        "nodal.nature",
        "nodal.nature_import",
        "nodal.discipline",
        "nodal.discipline_import",
    ],
    "nature_fields": ["units", "access", "abstol"],
    "discipline_fields": ["domain", "potential", "flow"],
    "domains": ["continuous", "discrete"],
    "imports": {
        "normalized_alias": True,
        "source_provenance": True,
        "definition_hash": "sha256-lowercase",
        "cycle_rejection": True,
    },
    "compatibility": "canonical-domain-potential-optional-flow",
    "diagnostics": codes,
    "evidence": {},
}
write(
    "tests/compiler/fixtures/increment27/manifest.json",
    json.dumps(manifest, indent=2) + "\n",
)

write(
    "scripts/check_increment27.py",
    r'''
#!/usr/bin/env python3
"""Validate Increment 27: natures and disciplines."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Problem:
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


EXPECTED_FILES = (
    "core/compiler/include/nodal/Dialect/Nodal/NatureDiscipline.h",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/lib/Dialect/Nodal/NatureDiscipline.cpp",
    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/Unit/CMakeLists.txt",
    "core/compiler/test/Unit/NatureDisciplineTest.cpp",
    "core/compiler/test/IR/natures-disciplines.mlir",
    "core/compiler/test/IR/natures-disciplines-invalid-tolerance.mlir",
    "core/compiler/test/IR/natures-disciplines-invalid-association.mlir",
    "core/compiler/test/IR/natures-disciplines-invalid-import-cycle.mlir",
    "core/compiler/diagnostics-v0.1.json",
    "docs/design-gates/NodalNatureDiscipline-DG-v1.0.md",
    "docs/implementation/increment27-natures-disciplines.md",
    "tests/compiler/fixtures/increment27/manifest.json",
    "tests/compiler/test_increment27.py",
    "scripts/check_increment26.py",
    "tests/compiler/test_increment26.py",
    "scripts/check_increment27.py",
    ".github/workflows/increment-27-natures-disciplines.yml",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment27.py",
    "scripts/finalize_increment27.py",
    "scripts/close_increment27.py",
    ".github/workflows/increment-27-materialize.yml",
    ".github/workflows/increment-27-finalize.yml",
    ".github/workflows/increment-27-close.yml",
)

OPERATIONS = [
    "nodal.nature",
    "nodal.nature_import",
    "nodal.discipline",
    "nodal.discipline_import",
]

CODES = [
    "NODAL-NATURE-DECL-001",
    "NODAL-NATURE-UNITS-001",
    "NODAL-NATURE-ACCESS-001",
    "NODAL-NATURE-ACCESS-002",
    "NODAL-NATURE-TOLERANCE-001",
    "NODAL-NATURE-IMPORT-001",
    "NODAL-NATURE-IMPORT-002",
    "NODAL-DISCIPLINE-DECL-001",
    "NODAL-DISCIPLINE-DOMAIN-001",
    "NODAL-DISCIPLINE-POTENTIAL-001",
    "NODAL-DISCIPLINE-FLOW-001",
    "NODAL-DISCIPLINE-IMPORT-001",
    "NODAL-DISCIPLINE-IMPORT-002",
]


def read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


def require(text: str, fragments: tuple[str, ...], problems: list[Problem], code: str, subject: str) -> None:
    for fragment in fragments:
        if fragment not in text:
            problems.append(Problem(code, f"{subject} lacks: {fragment}"))


def revision(text: str) -> tuple[int, ...]:
    values = re.findall(r"^\*\*Revision:\*\* ([0-9.]+)$", text, re.MULTILINE)
    if len(values) != 1:
        return ()
    return tuple(int(part) for part in values[0].split("."))


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC27-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(Problem("NODAL-INC27-002", f"temporary file remains: {relative}"))

    td = read(root / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td", problems, "NODAL-INC27-003")
    header = read(root / "core/compiler/include/nodal/Dialect/Nodal/NatureDiscipline.h", problems, "NODAL-INC27-004")
    source = read(root / "core/compiler/lib/Dialect/Nodal/NatureDiscipline.cpp", problems, "NODAL-INC27-005")
    dialect_cmake = read(root / "core/compiler/lib/Dialect/Nodal/CMakeLists.txt", problems, "NODAL-INC27-006")
    test_cmake = read(root / "core/compiler/test/CMakeLists.txt", problems, "NODAL-INC27-007")
    unit_cmake = read(root / "core/compiler/test/Unit/CMakeLists.txt", problems, "NODAL-INC27-008")
    unit = read(root / "core/compiler/test/Unit/NatureDisciplineTest.cpp", problems, "NODAL-INC27-009")
    positive = read(root / "core/compiler/test/IR/natures-disciplines.mlir", problems, "NODAL-INC27-010")
    gate = read(root / "docs/design-gates/NodalNatureDiscipline-DG-v1.0.md", problems, "NODAL-INC27-011")
    implementation = read(root / "docs/implementation/increment27-natures-disciplines.md", problems, "NODAL-INC27-012")
    workflow = read(root / ".github/workflows/increment-27-natures-disciplines.yml", problems, "NODAL-INC27-013")
    catalog = read(root / "core/compiler/diagnostics-v0.1.json", problems, "NODAL-INC27-014")
    predecessor = read(root / "scripts/check_increment26.py", problems, "NODAL-INC27-015")
    roadmap = read(root / "docs/roadmap/nodal-development-todo.md", problems, "NODAL-INC27-016")

    require(td, (
        "def Nodal_NatureOp", "StrAttr:$units", "StrAttr:$access", "F64Attr:$abstol",
        "def Nodal_NatureImportOp", "def Nodal_DisciplineOp", "StrAttr:$domain",
        "FlatSymbolRefAttr:$potential", "OptionalAttr<FlatSymbolRefAttr>:$flow",
        "def Nodal_DisciplineImportOp",
    ), problems, "NODAL-INC27-003", "TableGen declarations")
    require(header, (
        "resolveNatureDeclaration", "resolveDisciplineDeclaration",
        "areDisciplinesCompatible",
    ), problems, "NODAL-INC27-004", "compatibility API")
    require(source, (
        "resolveDeclaration", "isSha256", "NatureOp::verify()",
        "NatureImportOp::verify()", "DisciplineOp::verify()",
        "DisciplineImportOp::verify()", "areDisciplinesCompatible",
        "canonicalLeftPotential", "canonicalLeftFlow",
    ) + tuple(CODES), problems, "NODAL-INC27-005", "nature/discipline implementation")
    require(dialect_cmake, ("NatureDiscipline.cpp",), problems, "NODAL-INC27-006", "dialect build")
    require(test_cmake, (
        "natures-disciplines-roundtrip", "natures-disciplines-generic",
        "natures-disciplines-rejects-${_fixture}",
        "nodal-nature-discipline-unit-tests",
    ), problems, "NODAL-INC27-007", "native CTest integration")
    require(unit_cmake, ("nodal-nature-discipline-unit-tests", "NatureDisciplineTest.cpp"), problems, "NODAL-INC27-008", "unit target")
    require(unit, (
        "canonical discipline compatibility is incorrect",
        "non-positive nature tolerance was accepted",
        "unknown flow nature was accepted", "cyclic nature import was accepted",
    ), problems, "NODAL-INC27-009", "native compatibility tests")
    require(positive, tuple(OPERATIONS) + (
        'domain = "continuous"', "flow = @Current", "potential = @VoltageImported",
        "definition_hash", 'nodal.target.profile = "analog"',
    ), problems, "NODAL-INC27-010", "positive fixture")
    require(gate, (
        "**Status:** Approved", "**Scope:** compiler-ir", "**Public API:** unchanged at 0.3",
        "Nature identity is nominal", "canonical potential natures match",
        "Increment 28", "Increment 31", "fail-closed",
    ), problems, "NODAL-INC27-011", "design gate")
    require(implementation, tuple(OPERATIONS) + (
        "Compatibility compares canonical domain", "public API v0.3 unchanged",
        "remains fail-closed",
    ), problems, "NODAL-INC27-012", "implementation note")
    require(workflow, (
        "increment-27/natures-disciplines", "check_increment27.py",
        "./nodal core native", "natures-disciplines.mlir",
        "NODAL-NATURE-TOLERANCE-001", "NODAL-DISCIPLINE-FLOW-001",
        "NODAL-NATURE-IMPORT-001", "permissions:\n  contents: read",
    ), problems, "NODAL-INC27-013", "permanent workflow")
    if "contents: write" in workflow or "materialize_increment27" in workflow:
        problems.append(Problem("NODAL-INC27-013", "permanent workflow must be read-only"))
    for code in CODES:
        if code not in catalog:
            problems.append(Problem("NODAL-INC27-014", f"diagnostic catalog lacks {code}"))
    require(predecessor, (
        "increment27_done", "validated-natures-disciplines",
        "tests/compiler/fixtures/increment27/manifest.json",
    ), problems, "NODAL-INC27-015", "Increment 26 successor handling")

    manifest_path = root / "tests/compiler/fixtures/increment27/manifest.json"
    try:
        manifest = json.loads(read(manifest_path, problems, "NODAL-INC27-016"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC27-016", f"invalid manifest: {exc}"))
        manifest = {}
    if manifest.get("increment") != 27 or manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC27-016", "manifest identity/public API mismatch"))
    if manifest.get("operations") != OPERATIONS:
        problems.append(Problem("NODAL-INC27-016", "manifest operation inventory mismatch"))
    if manifest.get("nature_fields") != ["units", "access", "abstol"]:
        problems.append(Problem("NODAL-INC27-016", "manifest nature fields mismatch"))
    if manifest.get("discipline_fields") != ["domain", "potential", "flow"]:
        problems.append(Problem("NODAL-INC27-016", "manifest discipline fields mismatch"))
    if manifest.get("diagnostics") != CODES:
        problems.append(Problem("NODAL-INC27-016", "manifest diagnostics mismatch"))

    rev = revision(roadmap)
    increment26_done = "- [x] **Increment 26 — Deterministic output and reproducibility contract**" in roadmap
    increment27_open = "- [ ] **Increment 27 — Natures and disciplines**" in roadmap
    increment27_done = "- [x] **Increment 27 — Natures and disciplines**" in roadmap
    increment28_open = "- [ ] **Increment 28 — Electrical nodes, nets, and branches**" in roadmap
    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    if not increment26_done:
        problems.append(Problem("NODAL-INC27-016", "Increment 26 prerequisite is not closed"))
    if status == "implemented-awaiting-evidence":
        if not increment27_open or rev < (1, 32):
            problems.append(Problem("NODAL-INC27-016", "pre-evidence state must leave Increment 27 unchecked at revision 1.32 or later"))
    elif status == "validated-natures-disciplines":
        if not increment27_done or rev < (1, 33):
            problems.append(Problem("NODAL-INC27-016", "validated state must close Increment 27 at revision 1.33 or later"))
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(Problem("NODAL-INC27-016", f"validated manifest lacks integer evidence field: {field}"))
    else:
        problems.append(Problem("NODAL-INC27-016", f"unexpected manifest status: {status!r}"))
    if not increment28_open:
        problems.append(Problem("NODAL-INC27-016", "Increment 28 must remain unchecked"))
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 27 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 27 natures and disciplines check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
''',
)

write(
    "tests/compiler/test_increment27.py",
    r'''
from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment27.py"
SPEC = importlib.util.spec_from_file_location("check_increment27", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT = ("docs/roadmap/nodal-development-todo.md",)


class Increment27CheckerTests(unittest.TestCase):
    def temporary_repository(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in dict.fromkeys(CHECKER.EXPECTED_FILES + SUPPORT):
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def test_repository_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(ROOT), [])

    def test_rejects_missing_compatibility_helper(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/include/nodal/Dialect/Nodal/NatureDiscipline.h"
        path.write_text(path.read_text(encoding="utf-8").replace("areDisciplinesCompatible", "missingCompatibility", 1), encoding="utf-8")
        self.assertIn("NODAL-INC27-004", self.codes(root))

    def test_rejects_missing_import_cycle_resolution(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/NatureDiscipline.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("resolveDeclaration", "missingResolver", 1), encoding="utf-8")
        self.assertIn("NODAL-INC27-005", self.codes(root))

    def test_rejects_missing_diagnostic(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/diagnostics-v0.1.json"
        path.write_text(path.read_text(encoding="utf-8").replace('      "NODAL-NATURE-TOLERANCE-001",\n', "", 1), encoding="utf-8")
        self.assertIn("NODAL-INC27-014", self.codes(root))

    def test_rejects_writable_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-27-natures-disciplines.yml"
        path.write_text(path.read_text(encoding="utf-8").replace("contents: read", "contents: write", 1), encoding="utf-8")
        self.assertIn("NODAL-INC27-013", self.codes(root))

    def test_rejects_premature_roadmap_closure(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(roadmap.read_text(encoding="utf-8").replace("- [ ] **Increment 27 — Natures and disciplines**", "- [x] **Increment 27 — Natures and disciplines**", 1), encoding="utf-8")
        self.assertIn("NODAL-INC27-016", self.codes(root))

    def test_accepts_validated_state(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest_path = root / "tests/compiler/fixtures/increment27/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "validated-natures-disciplines"
        manifest["evidence"] = {"pull_request": 1, "dedicated_run": 2, "core_ci_run": 3}
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(roadmap.read_text(encoding="utf-8").replace("**Revision:** 1.32", "**Revision:** 1.33", 1).replace("- [ ] **Increment 27 — Natures and disciplines**", "- [x] **Increment 27 — Natures and disciplines**", 1), encoding="utf-8")
        self.assertEqual(CHECKER.check_repository(root), [])


if __name__ == "__main__":
    unittest.main()
''',
)

write(
    ".github/workflows/increment-27-natures-disciplines.yml",
    r'''
name: Increment 27 Natures and Disciplines

on:
  push:
    branches:
      - increment/27-natures-disciplines
  pull_request:
    branches:
      - dev
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: increment-27-${{ github.ref }}
  cancel-in-progress: true

jobs:
  nature-discipline:
    name: increment-27/natures-disciplines
    runs-on: ubuntu-24.04
    timeout-minutes: 120

    steps:
      - name: Check out repository
        uses: actions/checkout@v6
        with:
          fetch-depth: 0

      - name: Restore locked native and Scala caches
        uses: actions/cache@v5
        with:
          path: |
            ~/.cache/nodal/downloads
            ~/.cache/coursier
            ~/.cache/nodal/mill
          key: nodal-inc27-${{ runner.os }}-${{ hashFiles('build.mill', '.mill-version', 'mill', 'toolchains/lock.json', 'toolchains/checksums/*.sha256', 'toolchains/lint-lock.json') }}
          restore-keys: |
            nodal-inc27-${{ runner.os }}-
            nodal-inc26-${{ runner.os }}-
            nodal-inc25-${{ runner.os }}-
            nodal-scala-${{ runner.os }}-

      - name: Install locked native and lint toolchains
        run: |
          ./nodal bootstrap \
            --mode prebuilt \
            --prefix "${RUNNER_TEMP}/nodal-native-toolchain"
          ./nodal style bootstrap \
            --prefix "${RUNNER_TEMP}/nodal-lint-toolchain"

      - name: Validate contracts and mutation tests
        env:
          PYTHONDONTWRITEBYTECODE: '1'
        run: |
          for increment in $(seq 18 27); do
            python3 "scripts/check_increment${increment}.py"
          done
          python3 -m unittest discover \
            -s tests/compiler \
            -p 'test_*.py'
          ./nodal check \
            --contracts-only \
            --online-toolchain \
            --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain" \
            --base-ref origin/dev
          git diff --check

      - name: Build lint and test native core
        run: |
          ./nodal core native \
            --toolchain "${RUNNER_TEMP}/nodal-native-toolchain" \
            --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain"

      - name: Prove declarations imports compatibility and stable rejections
        run: |
          set -euo pipefail
          compiler="${PWD}/out/native/release/bin/nodalc"
          "${compiler}" \
            --pass-pipeline='builtin.module(nodal-gate-default)' \
            core/compiler/test/IR/natures-disciplines.mlir \
            | tee /tmp/natures-disciplines.mlir
          grep -F 'nodal.nature_import' /tmp/natures-disciplines.mlir
          grep -F 'nodal.discipline_import' /tmp/natures-disciplines.mlir

          if "${compiler}" core/compiler/test/IR/natures-disciplines-invalid-tolerance.mlir >/tmp/tolerance.out 2>/tmp/tolerance.err; then
            echo 'non-positive nature tolerance was accepted' >&2
            exit 1
          fi
          grep -F 'NODAL-NATURE-TOLERANCE-001' /tmp/tolerance.err

          if "${compiler}" core/compiler/test/IR/natures-disciplines-invalid-association.mlir >/tmp/association.out 2>/tmp/association.err; then
            echo 'unknown discipline flow nature was accepted' >&2
            exit 1
          fi
          grep -F 'NODAL-DISCIPLINE-FLOW-001' /tmp/association.err

          if "${compiler}" core/compiler/test/IR/natures-disciplines-invalid-import-cycle.mlir >/tmp/import.out 2>/tmp/import.err; then
            echo 'cyclic nature import was accepted' >&2
            exit 1
          fi
          grep -F 'NODAL-NATURE-IMPORT-001' /tmp/import.err
''',
)

# Teach the completed Increment 26 checker to accept a validated Increment 27 successor.
replace_once(
    "scripts/check_increment26.py",
    '    increment27_open = "- [ ] **Increment 27 — Natures and disciplines**" in roadmap\n',
    '    increment27_open = "- [ ] **Increment 27 — Natures and disciplines**" in roadmap\n'
    '    increment27_done = "- [x] **Increment 27 — Natures and disciplines**" in roadmap\n',
)
replace_once(
    "scripts/check_increment26.py",
    '    if not increment27_open:\n        problems.append(Problem("NODAL-INC26-008", "Increment 27 must remain unchecked"))\n\n    return problems\n',
    '''    if increment27_open:\n        pass\n    elif increment27_done:\n        successor_path = root / "tests/compiler/fixtures/increment27/manifest.json"\n        try:\n            successor = json.loads(read(successor_path, problems, "NODAL-INC26-008"))\n        except json.JSONDecodeError as exc:\n            problems.append(Problem("NODAL-INC26-008", f"invalid Increment 27 successor manifest: {exc}"))\n            successor = {}\n        if successor.get("increment") != 27 or successor.get("public_api") != "0.3":\n            problems.append(Problem("NODAL-INC26-008", "Increment 27 successor identity/public API mismatch"))\n        if successor.get("status") != "validated-natures-disciplines":\n            problems.append(Problem("NODAL-INC26-008", "checked Increment 27 lacks validated successor evidence"))\n        successor_evidence = successor.get("evidence", {})\n        for field in ("pull_request", "dedicated_run", "core_ci_run"):\n            if not isinstance(successor_evidence.get(field), int):\n                problems.append(Problem("NODAL-INC26-008", f"Increment 27 successor lacks integer evidence field: {field}"))\n        if revision < (1, 33):\n            problems.append(Problem("NODAL-INC26-008", "checked Increment 27 requires roadmap revision 1.33 or later"))\n    else:\n        problems.append(Problem("NODAL-INC26-008", "Increment 27 roadmap state is neither open nor validated"))\n\n    return problems\n''',
)

replace_once(
    "tests/compiler/test_increment26.py",
    'SUPPORT_FILES = (\n    "docs/roadmap/nodal-development-todo.md",\n)\n',
    'SUPPORT_FILES = (\n    "docs/roadmap/nodal-development-todo.md",\n    "tests/compiler/fixtures/increment27/manifest.json",\n)\n',
)
replace_once(
    "tests/compiler/test_increment26.py",
    '\n\nif __name__ == "__main__":\n    unittest.main()\n',
    r'''

    def test_accepts_validated_increment27_successor(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        successor_path = root / "tests/compiler/fixtures/increment27/manifest.json"
        successor = json.loads(successor_path.read_text(encoding="utf-8"))
        successor["status"] = "validated-natures-disciplines"
        successor["evidence"] = {
            "pull_request": 1,
            "dedicated_run": 2,
            "core_ci_run": 3,
        }
        successor_path.write_text(
            json.dumps(successor, indent=2) + "\n",
            encoding="utf-8",
        )
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(
            roadmap.read_text(encoding="utf-8")
            .replace("**Revision:** 1.32", "**Revision:** 1.33", 1)
            .replace(
                "- [ ] **Increment 27 — Natures and disciplines**",
                "- [x] **Increment 27 — Natures and disciplines**",
                1,
            ),
            encoding="utf-8",
        )
        self.assertEqual(CHECKER.check_repository(root), [])


if __name__ == "__main__":
    unittest.main()
''',
)

# Temporary self-removal is part of the permanent Increment 27 contract.
for relative in (
    "scripts/materialize_increment27.py",
    ".github/workflows/increment-27-materialize.yml",
):
    path = ROOT / relative
    if path.exists():
        path.unlink()

print("Increment 27 implementation materialized")
