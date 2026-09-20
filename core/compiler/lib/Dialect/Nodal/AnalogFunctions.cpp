#include "nodal/Dialect/Nodal/AnalogFunctions.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/TimeWaveform.h"

#include "llvm/ADT/SmallPtrSet.h"
#include "llvm/ADT/SmallVector.h"

#include <cmath>

using namespace mlir;
namespace nodal {
namespace {
#include "nodal/Dialect/Nodal/AnalogFunctionRegistry.inc"
llvm::StringRef text(Operation *op, llvm::StringRef key) {
  auto attr = op->getAttrOfType<StringAttr>(key);
  return attr ? attr.getValue() : llvm::StringRef();
}
// An annotation on an enclosing expression must not launder a simulator read
// into an alleged constant. Walk definitions, not advisory fold attributes.
bool dependsOnSimulatorState(Operation *operation) {
  llvm::SmallVector<Operation *, 8> pending{operation};
  llvm::SmallPtrSet<Operation *, 32> visited;
  while (!pending.empty()) {
    Operation *current = pending.pop_back_val();
    if (!visited.insert(current).second)
      continue;
    if (current->getName().getStringRef() == "nodal.analog_analysis" ||
        current->getName().getStringRef() == "nodal.analog_noise" ||
        current->getName().getStringRef() == "nodal.analog_transfer" ||
        current->getName().getStringRef() == "nodal.analog_user_call")
      return true;
    for (Value operand : current->getOperands())
      if (Operation *definition = operand.getDefiningOp())
        pending.push_back(definition);
  }
  return false;
}
} // namespace

const AnalogFunctionEntry *lookupAnalogFunction(llvm::StringRef id) {
  for (const auto &entry : functionEntries)
    if (entry.id == id)
      return &entry;
  return nullptr;
}
const AnalogFunctionEntry *lookupAnalogFunctionTarget(llvm::StringRef spelling) {
  for (const auto &entry : functionEntries)
    if (entry.verilogA == spelling)
      return &entry;
  return nullptr;
}
const AnalogAnalysisEntry *lookupAnalogAnalysis(llvm::StringRef id) {
  for (const auto &entry : analysisEntries)
    if (entry.id == id)
      return &entry;
  return nullptr;
}
bool isAnalogAnalysisTarget(llvm::StringRef spelling) {
  for (const auto &entry : analysisEntries)
    if (entry.verilogA == spelling)
      return true;
  return false;
}

FailureOr<std::string> analogFunctionDimension(const AnalogFunctionEntry &entry,
                                               llvm::ArrayRef<std::string> dimensions) {
  if (dimensions.size() != entry.arity)
    return failure();
  for (const auto &dimension : dimensions)
    if (dimension == "unknown" || !isCanonicalDimensionSignature(dimension))
      return failure();
  auto rule = entry.dimensionRule;
  if (rule == "preserve")
    return dimensions[0];
  if (rule == "same" || rule == "ratio") {
    if (dimensions[0] != dimensions[1])
      return failure();
    return rule == "ratio" ? std::string("1") : dimensions[0];
  }
  if (rule == "sqrt") {
    if (dimensions[0] == "1")
      return dimensions[0];
    llvm::SmallVector<llvm::StringRef> factors;
    llvm::StringRef(dimensions[0]).split(factors, '*');
    std::string result;
    for (auto factor : factors) {
      auto parts = factor.split('^');
      int64_t exponent = 1;
      if (!parts.second.empty() && parts.second.getAsInteger(10, exponent))
        return failure();
      if (exponent % 2 != 0)
        return failure();
      if (!result.empty())
        result += '*';
      result += parts.first.str();
      if (exponent / 2 != 1)
        result += "^" + std::to_string(exponent / 2);
    }
    return result;
  }
  if (rule != "dimensionless")
    return failure();
  for (const auto &dimension : dimensions)
    if (dimension != "1")
      return failure();
  return std::string("1");
}

FailureOr<std::optional<double>>
evaluateAnalogFunction(const AnalogFunctionEntry &entry,
                       llvm::ArrayRef<std::optional<double>> arguments) {
  if (arguments.size() != entry.arity)
    return failure();
  for (auto value : arguments)
    if (value && !std::isfinite(*value))
      return failure();
  auto id = entry.id;
  auto a = arguments[0];
  auto b = arguments.size() > 1 ? arguments[1] : std::optional<double>(0.0);
  // Reject independently provable domain failures, even when another input is symbolic.
  if (a && ((id == "sqrt" && *a < 0.0) || ((id == "ln" || id == "log10") && *a <= 0.0) ||
            ((id == "asin" || id == "acos") && std::abs(*a) > 1.0) || (id == "acosh" && *a < 1.0) ||
            (id == "atanh" && std::abs(*a) >= 1.0)))
    return failure();
  if (id == "pow" && a && b && ((*a == 0.0 && *b <= 0.0) || (*a < 0.0 && *b != std::floor(*b))))
    return failure();
  if (!a || !b)
    return std::optional<double>();
  double result = 0.0;
  if (id == "abs")
    result = std::abs(*a);
  else if (id == "min")
    result = *a < *b ? *a : *b;
  else if (id == "max")
    result = *a > *b ? *a : *b;
  else if (id == "sqrt")
    result = std::sqrt(*a);
  else if (id == "hypot")
    result = std::hypot(*a, *b);
  else if (id == "atan2")
    result = std::atan2(*a, *b);
  else if (id == "pow")
    result = std::pow(*a, *b);
  else if (id == "exp")
    result = std::exp(*a);
  else if (id == "ln")
    result = std::log(*a);
  else if (id == "log10")
    result = std::log10(*a);
  else if (id == "sin")
    result = std::sin(*a);
  else if (id == "cos")
    result = std::cos(*a);
  else if (id == "tan")
    result = std::tan(*a);
  else if (id == "asin")
    result = std::asin(*a);
  else if (id == "acos")
    result = std::acos(*a);
  else if (id == "atan")
    result = std::atan(*a);
  else if (id == "sinh")
    result = std::sinh(*a);
  else if (id == "cosh")
    result = std::cosh(*a);
  else if (id == "tanh")
    result = std::tanh(*a);
  else if (id == "asinh")
    result = std::asinh(*a);
  else if (id == "acosh")
    result = std::acosh(*a);
  else if (id == "atanh")
    result = std::atanh(*a);
  else if (id == "floor")
    result = std::floor(*a);
  else if (id == "ceil")
    result = std::ceil(*a);
  else
    return failure();
  if (!std::isfinite(result))
    return failure();
  return std::optional<double>(result);
}

LogicalResult verifyAnalogFunctionOperation(Operation *op) {
  auto name = op->getName().getStringRef();
  bool query = name == "nodal.analog_analysis";
  if (!query) {
    bool annotated = false;
    for (NamedAttribute attribute : op->getAttrs())
      annotated |= attribute.getName().getValue().starts_with("nodal.folded") ||
                   attribute.getName().getValue().starts_with("nodal.simplif");
    if (annotated && dependsOnSimulatorState(op))
      return emitMappedFailure(
          op, "NODAL-ANALOG-FOLD-001",
          "simulator-state-dependent expressions cannot carry fold or simplification claims");
  }
  if (!query && name != "nodal.analog_function")
    return success();
  if (text(op, "registry_version") != analogFunctionRegistryVersion)
    return emitMappedFailure(op, "NODAL-ANALOG-038-001",
                             "unknown analog function registry version");
  if (op->getNumResults() != 1)
    return emitMappedFailure(op, "NODAL-ANALOG-038-002", "a function requires exactly one result");
  auto resultType = getAnalogNumericTypeInfo(op->getResult(0).getType());
  if (query) {
    if (!lookupAnalogAnalysis(text(op, "analysis_kind")))
      return emitMappedFailure(op, "NODAL-ANALOG-038-001", "unknown analysis query");
    if (op->getNumOperands() != 0)
      return emitMappedFailure(op, "NODAL-ANALOG-038-002",
                               "analysis queries take no numeric operands");
    if (failed(resultType) || resultType->kind != AnalogNumericKind::Boolean)
      return emitMappedFailure(op, "NODAL-ANALOG-038-003",
                               "analysis queries return Boolean values");
    return success();
  }
  const auto *entry = lookupAnalogFunction(text(op, "function_id"));
  if (!entry)
    return emitMappedFailure(op, "NODAL-ANALOG-038-001", "unknown mathematical function");
  if (op->getNumOperands() != entry->arity)
    return emitMappedFailure(op, "NODAL-ANALOG-038-002", "incorrect mathematical function arity");
  if (failed(resultType) || resultType->kind != AnalogNumericKind::Real)
    return emitMappedFailure(op, "NODAL-ANALOG-038-003",
                             "mathematical functions return real values");
  llvm::SmallVector<std::string> dimensions;
  llvm::SmallVector<std::optional<double>> constants;
  for (Value operand : op->getOperands()) {
    auto dimension = getAnalogRealDimension(operand);
    if (failed(dimension))
      return emitMappedFailure(op, "NODAL-ANALOG-038-003",
                               "function operand must be a dimensioned real");
    dimensions.push_back(*dimension);
    auto constant = getAnalogConstantRealValue(operand);
    if (failed(constant))
      return emitMappedFailure(op, "NODAL-ANALOG-038-004", "invalid constant function argument");
    constants.push_back(*constant);
  }
  auto dimension = analogFunctionDimension(*entry, dimensions);
  if (failed(dimension) || (!resultType->legacyF64 && resultType->dimension != *dimension))
    return emitMappedFailure(op, "NODAL-ANALOG-038-003",
                             "function physical dimensions do not match");
  if (failed(evaluateAnalogFunction(*entry, constants)))
    return emitMappedFailure(op, "NODAL-ANALOG-038-004",
                             "function domain error or non-finite result");
  return success();
}
} // namespace nodal
