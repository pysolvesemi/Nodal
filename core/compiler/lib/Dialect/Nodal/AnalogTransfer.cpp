#include "nodal/Dialect/Nodal/AnalogTransfer.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/TimeWaveform.h"

#include "llvm/ADT/SmallPtrSet.h"
#include "llvm/ADT/SmallVector.h"

#include <cmath>
#include <cstdint>
#include <optional>

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
std::string coefficientDimension(bool sampled, unsigned index) {
  if (sampled || index == 0)
    return "1";
  return index == 1 ? "time" : "time^" + std::to_string(index);
}
} // namespace

LogicalResult verifyAnalogTransferOperation(Operation *op) {
  if (name(op) != "nodal.analog_transfer")
    return success();
  if (text(op, "contract_version") != "1")
    return emitMappedFailure(op, "NODAL-ANALOG-040-002", "unknown transfer contract version");
  if (name(op->getParentOp()) != "nodal.analog" || !owner(op))
    return emitMappedFailure(op, "NODAL-ANALOG-040-001",
                             "transfer state requires an unconditional analog region");
  Operation *module = owner(op);
  auto id = text(op, "operator_id");
  auto ownerId = text(op, "owner");
  auto actualOwner = semanticPath(module);
  if (actualOwner.empty())
    actualOwner = text(module, "sym_name");
  if (ownerId.empty() || ownerId != actualOwner || !id.starts_with((ownerId + ".").str()) ||
      id.size() <= ownerId.size() + 1 || id != semanticPath(op) ||
      text(op, "state_id") != (id + ".state").str())
    return emitMappedFailure(op, "NODAL-ANALOG-040-002",
                             "invalid transfer identity or state owner");
  if (module->getNumRegions() != 1 || !llvm::hasSingleElement(module->getRegion(0)))
    return emitMappedFailure(op, "NODAL-ANALOG-040-002", "transfer owner requires one body block");
  for (Operation &region : module->getRegion(0).front()) {
    if (name(&region) != "nodal.analog")
      continue;
    if (region.getNumRegions() != 1 || !llvm::hasSingleElement(region.getRegion(0)))
      return emitMappedFailure(op, "NODAL-ANALOG-040-002", "malformed transfer inventory region");
    for (Operation &other : region.getRegion(0).front())
      if (&other != op && name(&other) == "nodal.analog_transfer" &&
          text(&other, "operator_id") == id)
        return emitMappedFailure(op, "NODAL-ANALOG-040-002", "transfer identity must be unique");
  }
  for (NamedAttribute attr : op->getAttrs())
    if (attr.getName().getValue().starts_with("nodal.folded") ||
        attr.getName().getValue().starts_with("nodal.simplif"))
      return emitMappedFailure(op, "NODAL-ANALOG-FOLD-001",
                               "transfer state cannot carry fold claims");
  auto kind = text(op, "transfer_kind");
  bool sampled = kind == "zi_nd";
  auto nAttr = op->getAttrOfType<IntegerAttr>("numerator_size");
  auto dAttr = op->getAttrOfType<IntegerAttr>("denominator_size");
  const unsigned count = op->getNumOperands();
  // Check segment lengths before any indexing; arithmetic cannot wrap on hostile IR.
  if (op->getNumResults() != 1 || (kind != "laplace_nd" && !sampled) || !nAttr || !dAttr ||
      nAttr.getInt() <= 0 || dAttr.getInt() <= 0 ||
      static_cast<uint64_t>(nAttr.getInt()) >= count ||
      static_cast<uint64_t>(dAttr.getInt()) >= count - static_cast<uint64_t>(nAttr.getInt()))
    return emitMappedFailure(op, "NODAL-ANALOG-040-002",
                             "invalid transfer kind or coefficient arrays");
  unsigned numerator = static_cast<unsigned>(nAttr.getInt());
  unsigned denominator = static_cast<unsigned>(dAttr.getInt());
  unsigned timingStart = 1 + numerator + denominator;
  unsigned timingCount = count - timingStart;
  if ((!sampled && timingCount != 0) || (sampled && (timingCount < 1 || timingCount > 3)))
    return emitMappedFailure(op, "NODAL-ANALOG-040-002", "invalid transfer timing arity");
  if (text(op, "coefficient_order") != (sampled ? "ascending_z_inverse" : "ascending_s") ||
      text(op, "initialization") != "simulator-default" || op->hasAttr("epsilon") ||
      op->hasAttr("initial_value"))
    return emitMappedFailure(op, "NODAL-ANALOG-040-006", "unsupported transfer policy or option");
  llvm::SmallVector<std::string> dimensions;
  llvm::SmallVector<std::optional<double>> constants;
  llvm::SmallPtrSet<Operation *, 32> visited;
  llvm::SmallVector<Operation *> pending;
  for (Value operand : op->getOperands()) {
    auto dimension = getAnalogRealDimension(operand);
    if (failed(dimension) || *dimension == "unknown")
      return emitMappedFailure(op, "NODAL-ANALOG-040-003",
                               "transfer operands require dimensioned reals");
    dimensions.push_back(*dimension);
    auto constant = getAnalogConstantRealValue(operand);
    if (failed(constant) || (*constant && !std::isfinite(**constant)))
      return emitMappedFailure(op, "NODAL-ANALOG-040-004", "transfer constants must be finite");
    constants.push_back(*constant);
    if (auto *definition = operand.getDefiningOp())
      pending.push_back(definition);
    else
      return emitMappedFailure(op, "NODAL-ANALOG-040-002",
                               "transfer input has no owned definition");
  }
  while (!pending.empty()) {
    Operation *definition = pending.pop_back_val();
    if (!visited.insert(definition).second)
      continue;
    if (owner(definition) != module)
      return emitMappedFailure(op, "NODAL-ANALOG-040-002",
                               "transfer input belongs to another Module");
    for (Value operand : definition->getOperands())
      if (auto *parent = operand.getDefiningOp())
        pending.push_back(parent);
      else
        return emitMappedFailure(op, "NODAL-ANALOG-040-002",
                                 "transfer input has no owned definition");
  }
  for (unsigned index = 1; index < timingStart; ++index) {
    unsigned power = index <= numerator ? index - 1 : index - 1 - numerator;
    if (dimensions[index] != coefficientDimension(sampled, power))
      return emitMappedFailure(op, "NODAL-ANALOG-040-003",
                               "coefficient dimension disagrees with its power");
    if (!isAnalogStaticExpression(op->getOperand(index)))
      return emitMappedFailure(op, "NODAL-ANALOG-040-005",
                               "transfer coefficient must be static during analysis");
  }
  if (!constants[1 + numerator])
    return emitMappedFailure(op, "NODAL-ANALOG-040-006",
                             "denominator d0 requires a proven nonzero constant");
  if (*constants[1 + numerator] == 0.0)
    return emitMappedFailure(op, "NODAL-ANALOG-040-004",
                             "denominator d0 must be nonzero in this profile");
  for (unsigned index = timingStart; index < count; ++index) {
    if (dimensions[index] != "time")
      return emitMappedFailure(op, "NODAL-ANALOG-040-003", "sample timing requires time units");
    if (!constants[index])
      return emitMappedFailure(op, "NODAL-ANALOG-040-006",
                               "sample timing requires proven constants");
    if ((index - timingStart < 2 && *constants[index] <= 0.0) ||
        (index - timingStart == 2 && *constants[index] < 0.0))
      return emitMappedFailure(op, "NODAL-ANALOG-040-004",
                               "interval/transition must be positive and start nonnegative");
  }
  auto resultType = getAnalogNumericTypeInfo(op->getResult(0).getType());
  if (failed(resultType) || resultType->kind != AnalogNumericKind::Real ||
      text(op, "result_dimension") != dimensions[0] ||
      (!resultType->legacyF64 && resultType->dimension != dimensions[0]))
    return emitMappedFailure(op, "NODAL-ANALOG-040-003",
                             "transfer result must preserve input dimensions");
  return success();
}
} // namespace nodal
