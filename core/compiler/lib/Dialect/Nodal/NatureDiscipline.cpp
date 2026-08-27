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
