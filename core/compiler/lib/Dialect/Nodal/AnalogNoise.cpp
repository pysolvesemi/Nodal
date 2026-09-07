#include "nodal/Dialect/Nodal/AnalogNoise.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogFunctions.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/TimeWaveform.h"

#include "llvm/ADT/SmallPtrSet.h"
#include "llvm/ADT/SmallVector.h"

#include <cmath>
#include <optional>
#include <set>

using namespace mlir;
namespace nodal {
namespace {
llvm::StringRef name(Operation *op) {
  return op ? op->getName().getStringRef() : llvm::StringRef();
}
llvm::StringRef text(Operation *op, llvm::StringRef key) {
  auto attr = op->getAttrOfType<StringAttr>(key);
  return attr ? attr.getValue() : llvm::StringRef();
}
Operation *owner(Operation *op) {
  for (auto *parent = op->getParentOp(); parent; parent = parent->getParentOp())
    if (name(parent) == "nodal.module")
      return parent;
  return nullptr;
}
llvm::StringRef semanticPath(Operation *op) {
  if (auto metadata = op->getAttrOfType<DictionaryAttr>("metadata"))
    if (auto path = metadata.getAs<StringAttr>("semantic_path"))
      return path.getValue();
  return {};
}
} // namespace

FailureOr<std::string> analogNoiseResultDimension(llvm::StringRef spectralDensity) {
  if (!isCanonicalDimensionSignature(spectralDensity) || spectralDensity == "unknown")
    return failure();
  auto variance = combineAnalogDimensions(spectralDensity, "time", true);
  if (failed(variance))
    return failure();
  llvm::SmallVector<std::string> dimensions{*variance};
  return analogFunctionDimension(*lookupAnalogFunction("sqrt"), dimensions);
}

LogicalResult verifyAnalogNoiseOperation(Operation *op) {
  if (name(op) != "nodal.analog_noise")
    return success();
  if (text(op, "contract_version") != "1")
    return emitMappedFailure(op, "NODAL-ANALOG-039-002", "unknown noise contract version");
  if (name(op->getParentOp()) != "nodal.analog" || !owner(op))
    return emitMappedFailure(op, "NODAL-ANALOG-039-001",
                             "noise sources require an unconditional analog region");
  Operation *module = owner(op);
  auto id = text(op, "source_id");
  auto ownerId = text(op, "owner");
  auto actualOwner = semanticPath(module);
  if (actualOwner.empty())
    actualOwner = text(module, "sym_name");
  if (ownerId.empty() || ownerId != actualOwner || !id.starts_with((ownerId + ".").str()) ||
      id.size() <= ownerId.size() + 1 || id != semanticPath(op))
    return emitMappedFailure(op, "NODAL-ANALOG-039-002", "invalid noise identity, path, or owner");
  // Identity is independent of the reporting label. Reject duplicates even in a
  // bare dialect parse, not only when the optional compiler pipeline is run.
  for (Operation &region : module->getRegion(0).front()) {
    if (name(&region) != "nodal.analog")
      continue;
    for (Operation &other : region.getRegion(0).front())
      if (&other != op && name(&other) == "nodal.analog_noise" && text(&other, "source_id") == id)
        return emitMappedFailure(op, "NODAL-ANALOG-039-002",
                                 "noise source identity must be unique");
  }
  auto label = text(op, "noise_name");
  if (label.empty() ||
      llvm::any_of(label, [](char c) { return c < ' ' || c > '~' || c == '"' || c == '\\'; }))
    return emitMappedFailure(op, "NODAL-ANALOG-039-005", "invalid portable noise reporting label");
  auto analyses = op->getAttrOfType<ArrayAttr>("analyses");
  auto analysis =
      analyses && analyses.size() == 1 ? llvm::dyn_cast<StringAttr>(analyses[0]) : StringAttr();
  if (text(op, "correlation") != "independent" || !analysis || analysis.getValue() != "noise")
    return emitMappedFailure(op, "NODAL-ANALOG-039-006",
                             "only independent small-signal noise sources are supported");
  for (NamedAttribute attr : op->getAttrs())
    if (attr.getName().getValue().starts_with("nodal.folded") ||
        attr.getName().getValue().starts_with("nodal.simplif"))
      return emitMappedFailure(op, "NODAL-ANALOG-FOLD-001",
                               "noise sources cannot carry fold claims");
  auto kind = text(op, "noise_kind");
  const unsigned count = op->getNumOperands();
  if (op->getNumResults() != 1 ||
      !((kind == "white" && count == 1) || (kind == "flicker" && count == 2) ||
        (kind == "table" && count > 0 && count % 2 == 0)))
    return emitMappedFailure(op, "NODAL-ANALOG-039-002", "unknown noise kind or invalid arity");
  llvm::SmallVector<std::string> dimensions;
  llvm::SmallVector<std::optional<double>> constants;
  llvm::SmallPtrSet<Operation *, 32> visited;
  llvm::SmallVector<Operation *> pending;
  for (Value operand : op->getOperands()) {
    auto dimension = getAnalogRealDimension(operand);
    if (failed(dimension))
      return emitMappedFailure(op, "NODAL-ANALOG-039-003",
                               "noise operands require dimensioned reals");
    dimensions.push_back(*dimension);
    auto constant = getAnalogConstantRealValue(operand);
    if (failed(constant) || (*constant && !std::isfinite(**constant)))
      return emitMappedFailure(op, "NODAL-ANALOG-039-004", "noise arguments must be finite");
    constants.push_back(*constant);
    if (auto *definition = operand.getDefiningOp())
      pending.push_back(definition);
  }
  // Inspect definitions rather than trusting defaults or supplied annotations.
  while (!pending.empty()) {
    Operation *definition = pending.pop_back_val();
    if (!visited.insert(definition).second)
      continue;
    if (owner(definition) != module)
      return emitMappedFailure(op, "NODAL-ANALOG-039-002",
                               "noise operand belongs to another Module");
    if (name(definition) == "nodal.analog_noise")
      return emitMappedFailure(op, "NODAL-ANALOG-039-006", "noise-modulated noise is unsupported");
    for (Value operand : definition->getOperands())
      if (auto *parent = operand.getDefiningOp())
        pending.push_back(parent);
  }
  unsigned firstDensity = kind == "table" ? 1 : 0;
  for (unsigned i = firstDensity; i < count; i += kind == "table" ? 2 : count) {
    if (dimensions[i] != dimensions[firstDensity])
      return emitMappedFailure(op, "NODAL-ANALOG-039-003", "table density dimensions must agree");
    if (constants[i] && *constants[i] < 0.0)
      return emitMappedFailure(op, "NODAL-ANALOG-039-004", "spectral density must be nonnegative");
  }
  if (kind == "flicker") {
    if (dimensions[1] != "1")
      return emitMappedFailure(op, "NODAL-ANALOG-039-003",
                               "flicker exponent must be dimensionless");
    if (!constants[1])
      return emitMappedFailure(op, "NODAL-ANALOG-039-006", "flicker exponent must be constant");
  }
  if (kind == "table") {
    std::set<double> frequencies;
    for (unsigned i = 0; i < count; i += 2) {
      if (dimensions[i] != "time^-1")
        return emitMappedFailure(op, "NODAL-ANALOG-039-003", "table frequencies require hertz");
      if (!constants[i] || !constants[i + 1])
        return emitMappedFailure(op, "NODAL-ANALOG-039-006",
                                 "table points must be proven constants");
      if (*constants[i] < 0.0 || !frequencies.insert(*constants[i]).second)
        return emitMappedFailure(op, "NODAL-ANALOG-039-004",
                                 "table frequencies must be nonnegative and unique");
    }
  }
  auto dimension = analogNoiseResultDimension(dimensions[firstDensity]);
  auto resultType = getAnalogNumericTypeInfo(op->getResult(0).getType());
  if (failed(dimension) || text(op, "result_dimension") != *dimension || failed(resultType) ||
      resultType->kind != AnalogNumericKind::Real ||
      (!resultType->legacyF64 && resultType->dimension != *dimension))
    return emitMappedFailure(
        op, "NODAL-ANALOG-039-003",
        "noise result must have the square root of spectral-density/time dimensions");
  return success();
}
} // namespace nodal
