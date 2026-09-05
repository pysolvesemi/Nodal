#include "nodal/Dialect/Nodal/TimeWaveform.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogEvents.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringSet.h"

#include <cmath>
#include <optional>
#include <string>

using namespace mlir;

namespace nodal {
namespace {
llvm::StringRef text(Operation *op, llvm::StringRef key) {
  if (auto attr = op->getAttrOfType<StringAttr>(key))
    return attr.getValue();
  return {};
}
llvm::StringRef name(Operation *op) {
  return op ? op->getName().getStringRef() : llvm::StringRef();
}
Operation *owner(Operation *op) {
  for (Operation *p = op->getParentOp(); p; p = p->getParentOp())
    if (name(p) == "nodal.module")
      return p;
  return nullptr;
}
Operation *parameter(Value value) {
  Operation *op = value.getDefiningOp();
  if (name(op) != "nodal.parameter_ref")
    return nullptr;
  auto ref = op->getAttrOfType<FlatSymbolRefAttr>("parameter");
  return ref ? SymbolTable::lookupNearestSymbolFrom(op, ref) : nullptr;
}

// Legacy f64 retains canonical source units in metadata. Reconstruct dimensions
// from definitions, never from a waveform operation's claimed contract.
FailureOr<std::string> unitDimension(llvm::StringRef unit) {
  if (unit.empty())
    return std::string("1");
  if (unit == "s")
    return std::string("time");
  if (unit == "V")
    return std::string("voltage");
  if (unit == "A")
    return std::string("current");
  if (unit == "Ohm")
    return std::string("current^-1*voltage");
  if (unit == "F")
    return std::string("current*time*voltage^-1");
  return failure();
}
llvm::StringRef metadataUnit(Operation *op) {
  if (auto metadata = op->getAttrOfType<DictionaryAttr>("metadata"))
    if (auto unit = metadata.getAs<StringAttr>("unit"))
      return unit.getValue();
  return {};
}
FailureOr<std::string> dimension(Value value, unsigned depth = 0) {
  if (depth > 256)
    return failure();
  auto type = getAnalogNumericTypeInfo(value.getType());
  if (failed(type) || type->kind != AnalogNumericKind::Real)
    return failure();
  if (!type->legacyF64)
    return type->dimension;
  Operation *op = value.getDefiningOp();
  if (!op)
    return failure();
  auto operationName = name(op);
  if (operationName == "nodal.analog_held_read") {
    auto variable = resolveAnalogHeldVariable(op);
    if (failed(variable))
      return failure();
    return llvm::cast<VariableType>((*variable)->getResult(0).getType()).getDimension().str();
  }
  if (operationName == "nodal.real_literal")
    return unitDimension(metadataUnit(op));
  if (Operation *declaration = parameter(value)) {
    auto unit = getParameterUnitSymbol(declaration);
    return unitDimension(unit.empty() ? metadataUnit(declaration) : llvm::StringRef(unit));
  }
  if (operationName == "nodal.analog_abstime")
    return std::string("time");
  if (operationName == "nodal.access" && op->getNumOperands() == 1) {
    auto branch = llvm::dyn_cast<BranchType>(op->getOperand(0).getType());
    if (!branch || branch.getDiscipline() != "electrical")
      return failure();
    if (text(op, "kind") == "potential")
      return std::string("voltage");
    if (text(op, "kind") == "flow")
      return std::string("current");
    return failure();
  }
  if (operationName == "nodal.analog_select" && op->getNumOperands() == 3) {
    auto a = dimension(op->getOperand(1), depth + 1);
    auto b = dimension(op->getOperand(2), depth + 1);
    if (failed(a) || failed(b) || *a != *b)
      return failure();
    return *a;
  }
  if (op->getNumOperands() == 0)
    return failure();
  auto a = dimension(op->getOperand(0), depth + 1);
  if (failed(a))
    return failure();
  if (isStatefulWaveformOperation(op) || operationName == "nodal.analog_neg")
    return *a;
  if (operationName == "nodal.analog_ddt" || operationName == "nodal.analog_idt")
    return combineAnalogDimensions(*a, "time", operationName == "nodal.analog_ddt");
  if (op->getNumOperands() != 2)
    return failure();
  auto b = dimension(op->getOperand(1), depth + 1);
  if (failed(b))
    return failure();
  if (operationName == "nodal.analog_mul" || operationName == "nodal.analog_div")
    return combineAnalogDimensions(*a, *b, operationName == "nodal.analog_div");
  if ((operationName == "nodal.analog_add" || operationName == "nodal.analog_sub") && *a == *b)
    return *a;
  return failure();
}

bool staticExpression(Value value, unsigned depth = 0) {
  if (depth > 256)
    return false;
  Operation *op = value.getDefiningOp();
  auto n = name(op);
  if (n == "nodal.real_literal" || n == "nodal.analog_integer_literal")
    return true;
  if (parameter(value))
    return true;
  if (n != "nodal.analog_add" && n != "nodal.analog_sub" && n != "nodal.analog_mul" &&
      n != "nodal.analog_div" && n != "nodal.analog_neg")
    return false;
  return llvm::all_of(op->getOperands(),
                      [&](Value input) { return staticExpression(input, depth + 1); });
}
std::string continuity(Value value, unsigned depth = 0) {
  if (depth > 256)
    return "unknown";
  if (staticExpression(value))
    return "constant";
  Operation *op = value.getDefiningOp();
  auto n = name(op);
  if (n == "nodal.analog_held_read" && succeeded(resolveAnalogHeldVariable(op)))
    return "piecewise-constant";
  if (n == "nodal.analog_transition" || n == "nodal.analog_abstime")
    return "continuous";
  if (n == "nodal.analog_slew" && op->getNumOperands() > 1)
    return "continuous";
  if (n == "nodal.analog_slew" && op->getNumOperands() == 1)
    return continuity(op->getOperand(0), depth + 1);
  if (n == "nodal.analog_select" && op->getNumOperands() == 3 &&
      staticExpression(op->getOperand(1)) && staticExpression(op->getOperand(2)))
    return "piecewise-constant";
  return "unknown";
}
} // namespace

bool isStatefulWaveformOperation(Operation *op) {
  auto n = name(op);
  return n == "nodal.analog_transition" || n == "nodal.analog_slew" || n == "nodal.analog_absdelay";
}
bool isTimeWaveformOperation(Operation *op) {
  return isStatefulWaveformOperation(op) || name(op) == "nodal.analog_abstime" ||
         name(op) == "nodal.analog_bound_step";
}

LogicalResult verifyTimeWaveformOperation(Operation *op) {
  if (!isTimeWaveformOperation(op))
    return success();
  auto n = name(op);
  const bool effect = n == "nodal.analog_bound_step";
  const bool clock = n == "nodal.analog_abstime";
  const bool slew = n == "nodal.analog_slew";
  const bool delay = n == "nodal.analog_absdelay";
  const bool transition = n == "nodal.analog_transition";
  const unsigned count = op->getNumOperands();
  if ((transition && (count < 1 || count > 5)) || (slew && (count < 1 || count > 3)) ||
      (delay && (count < 2 || count > 3)) || (clock && count != 0) || (effect && count != 1) ||
      op->getNumResults() != (effect ? 0u : 1u))
    return emitMappedFailure(op, "NODAL-ANALOG-036-002",
                             "invalid waveform operator arity or result count");
  auto context = text(op, "context");
  if (name(op->getParentOp()) != "nodal.analog" ||
      (context != "legacy-analog" && context != "equation" && context != "contribution"))
    return emitMappedFailure(op, "NODAL-ANALOG-036-001",
                             "waveform operators require an unconditional continuous region");
  Operation *module = owner(op);
  auto ownerId = text(op, "owner");
  auto id = text(op, "operator_id");
  std::string actualOwner = module ? text(module, "sym_name").str() : std::string();
  if (module)
    if (auto metadata = module->getAttrOfType<DictionaryAttr>("metadata"))
      if (auto path = metadata.getAs<StringAttr>("semantic_path"))
        actualOwner = path.getValue().str();
  if (text(op, "operator_contract") != "increment36" || ownerId.empty() || ownerId != actualOwner ||
      !id.starts_with((ownerId + ".").str()) || id.size() <= ownerId.size() + 1)
    return emitMappedFailure(op, "NODAL-ANALOG-036-002",
                             "invalid waveform contract, identity, or owner");
  // Even the local verifier rejects duplicate identities; no optional pass is required.
  for (Operation &region : module->getRegion(0).front()) {
    if (name(&region) != "nodal.analog")
      continue;
    for (Operation &other : region.getRegion(0).front()) {
      if (&other != op && text(&other, "operator_id") == id)
        return emitMappedFailure(op, "NODAL-ANALOG-036-002",
                                 "waveform operator identity must be unique");
    }
  }
  if ((isStatefulWaveformOperation(op) && text(op, "state_id") != (id + ".state").str()) ||
      (!isStatefulWaveformOperation(op) && op->getAttr("state_id")))
    return emitMappedFailure(op, "NODAL-ANALOG-036-006", "invalid waveform state ownership");
  for (NamedAttribute attr : op->getAttrs()) {
    auto key = attr.getName().getValue();
    if (key.starts_with("nodal.folded") || key.starts_with("nodal.simplif"))
      return emitMappedFailure(op, "NODAL-ANALOG-036-006",
                               "waveform history, time, and effects cannot be folded");
  }
  auto analyses = op->getAttrOfType<ArrayAttr>("analyses");
  const llvm::SmallVector<llvm::StringRef> expectedAnalyses =
      effect ? llvm::SmallVector<llvm::StringRef>{"transient"}
             : llvm::SmallVector<llvm::StringRef>{
                   "ac", "dc", "initialization", "noise", "operating-point", "transient"};
  if (!analyses || analyses.size() != expectedAnalyses.size())
    return emitMappedFailure(op, "NODAL-ANALOG-036-008",
                             "waveform analysis inventory is incomplete");
  for (unsigned i = 0; i < analyses.size(); ++i) {
    auto attr = llvm::dyn_cast<StringAttr>(analyses[i]);
    if (!attr || attr.getValue() != expectedAnalyses[i])
      return emitMappedFailure(op, "NODAL-ANALOG-036-008",
                               "waveform analysis inventory must be canonical");
  }
  auto supplied = op->getAttrOfType<ArrayAttr>("operand_dimensions");
  if (!supplied || supplied.size() != count)
    return emitMappedFailure(op, "NODAL-ANALOG-036-003",
                             "waveform operand dimension inventory is incomplete");
  llvm::SmallVector<std::string> dimensions;
  for (unsigned i = 0; i < count; ++i) {
    Value value = op->getOperand(i);
    if (Operation *definition = value.getDefiningOp())
      if (owner(definition) != module)
        return emitMappedFailure(op, "NODAL-ANALOG-036-002",
                                 "waveform operand belongs to another Module");
    auto derived = dimension(value);
    auto declared = llvm::dyn_cast<StringAttr>(supplied[i]);
    if (failed(derived) || !declared || declared.getValue() != *derived)
      return emitMappedFailure(op, "NODAL-ANALOG-036-003",
                               "waveform operand dimension differs from its definition");
    dimensions.push_back(*derived);
  }
  std::string resultDimension = effect ? "none" : clock ? "time" : dimensions.front();
  if (text(op, "result_dimension") != resultDimension)
    return emitMappedFailure(op, "NODAL-ANALOG-036-003", "incorrect waveform result dimension");
  if (!effect) {
    auto resultType = getAnalogNumericTypeInfo(op->getResult(0).getType());
    if (failed(resultType) || resultType->kind != AnalogNumericKind::Real ||
        (!resultType->legacyF64 && resultType->dimension != resultDimension))
      return emitMappedFailure(op, "NODAL-ANALOG-036-003",
                               "waveform result must be real with the inferred dimension");
  }
  for (unsigned i = 0; i < count; ++i) {
    const bool timing = effect || (!slew && i > 0);
    const bool rate = slew && i > 0;
    auto constant = getAnalogConstantRealValue(op->getOperand(i));
    if (failed(constant))
      return emitMappedFailure(op, "NODAL-ANALOG-036-004",
                               "waveform argument has invalid constant arithmetic");
    auto number = *constant;
    std::string expected = dimensions[i];
    if (timing)
      expected = "time";
    else if (rate) {
      auto rateDimension = combineAnalogDimensions(dimensions.front(), "time", true);
      if (failed(rateDimension))
        return emitMappedFailure(op, "NODAL-ANALOG-036-003", "invalid slew-rate dimension");
      expected = *rateDimension;
    }
    bool zeroTime = timing && dimensions[i] == "1" && number && *number == 0.0;
    if (!zeroTime && dimensions[i] != expected)
      return emitMappedFailure(op, "NODAL-ANALOG-036-003",
                               "timing arguments require seconds; rates require input/time");
    if (number) {
      bool invalid = rate               ? (i == 1 ? *number <= 0.0 : *number >= 0.0)
                     : (delay && i > 0) ? *number <= 0.0
                                        : timing && *number < 0.0;
      if (!std::isfinite(*number) || invalid)
        return emitMappedFailure(op, "NODAL-ANALOG-036-004",
                                 "invalid waveform timing or rate range");
    }
  }
  if (delay && count == 3 && !staticExpression(op->getOperand(2)))
    return emitMappedFailure(op, "NODAL-ANALOG-036-007",
                             "maximum delay requires a constant expression");
  auto inputContinuity = (clock || effect) ? std::string("none") : continuity(op->getOperand(0));
  auto outputContinuity = effect ? std::string("none") : continuity(op->getResult(0));
  if (text(op, "input_continuity") != inputContinuity ||
      text(op, "output_continuity") != outputContinuity ||
      (transition && inputContinuity != "constant" && inputContinuity != "piecewise-constant"))
    return emitMappedFailure(
        op, "NODAL-ANALOG-036-005",
        "unproven or forged waveform continuity; transition needs piecewise-constant input");
  return success();
}
} // namespace nodal
