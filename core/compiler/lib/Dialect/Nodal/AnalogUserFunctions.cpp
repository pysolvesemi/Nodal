#include "nodal/Dialect/Nodal/AnalogUserFunctions.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogFunctions.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/TimeWaveform.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringSet.h"

#include <cmath>
#include <limits>
#include <optional>
#include <string>

using namespace mlir;
namespace nodal {
namespace {
llvm::StringRef text(Operation *op, llvm::StringRef key) {
  auto value = op->getAttrOfType<StringAttr>(key);
  return value ? value.getValue() : llvm::StringRef();
}
bool named(Operation *op, llvm::StringRef name) {
  return op && op->getName().getStringRef() == name;
}
Operation *moduleOf(Operation *op) {
  for (Operation *parent = op->getParentOp(); parent; parent = parent->getParentOp())
    if (named(parent, "nodal.module"))
      return parent;
  return nullptr;
}
bool identifier(llvm::StringRef value) {
  return !value.empty() && (llvm::isAlpha(value.front()) || value.front() == '_') &&
         llvm::all_of(value, [](char c) { return llvm::isAlnum(c) || c == '_'; });
}
LogicalResult error(Operation *op, llvm::StringRef code, const llvm::Twine &message) {
  return emitMappedFailure(op, code, message);
}
FailureOr<AnalogNumericTypeInfo> bodyType(Type type) {
  auto info = getAnalogNumericTypeInfo(type);
  if (failed(info) || info->legacyF64 ||
      (info->kind == AnalogNumericKind::Integer && info->dimension != "1"))
    return failure();
  return info;
}
bool scalar(Type type) {
  auto info = bodyType(type);
  return succeeded(info) &&
         (info->kind == AnalogNumericKind::Real || info->kind == AnalogNumericKind::Integer);
}
FailureOr<AnalogNumericTypeInfo> argumentType(Value value) {
  auto info = getAnalogNumericTypeInfo(value.getType());
  if (failed(info))
    return failure();
  if (info->kind == AnalogNumericKind::Real) {
    auto dimension = getAnalogRealDimension(value);
    if (failed(dimension))
      return failure();
    info->dimension = *dimension;
  }
  return info;
}
bool equalType(const AnalogNumericTypeInfo &a, const AnalogNumericTypeInfo &b) {
  return a.kind == b.kind && a.dimension == b.dimension;
}
bool forbiddenAnnotations(Operation *op) {
  for (NamedAttribute attribute : op->getAttrs()) {
    auto name = attribute.getName().getValue();
    if (name.starts_with("nodal.fold") || name.starts_with("nodal.simplif") ||
        name.starts_with("nodal.continuous_simpl") || name.starts_with("fold") ||
        name.starts_with("simplif") || name.starts_with("constant_") ||
        name.starts_with("continuous_simpl"))
      return true;
  }
  return false;
}
llvm::SmallVector<Operation *> arguments(Operation *function) {
  llvm::SmallVector<Operation *> result;
  if (function->getNumRegions() == 1 && llvm::hasSingleElement(function->getRegion(0)))
    for (Operation &op : function->getRegion(0).front())
      if (named(&op, "nodal.analog_function_value") && text(&op, "kind") == "input")
        result.push_back(&op);
  return result;
}
LogicalResult verifyCall(Operation *call) {
  if (text(call, "contract_version") != "1")
    return error(call, "NODAL-ANALOG-041-002", "unknown user-function call contract");
  if (!named(call->getParentOp(), "nodal.analog") &&
      !named(call->getParentOp(), "nodal.analog_user_function"))
    return error(call, "NODAL-ANALOG-041-001", "call requires an analog or pure function body");
  auto resolved = resolveAnalogUserCall(call);
  if (failed(resolved))
    return error(call, "NODAL-ANALOG-041-005", "unknown or non-function module-local callee");
  auto expected = arguments(*resolved);
  if (expected.empty() || expected.size() != call->getNumOperands() || call->getNumResults() != 1)
    return error(call, "NODAL-ANALOG-041-005", "function call argument count mismatch");
  auto result = (*resolved)->getAttrOfType<TypeAttr>("return_type");
  bool legacyResult = result && named(call->getParentOp(), "nodal.analog") &&
                      call->getResult(0).getType().isF64() &&
                      llvm::isa<QuantityType>(result.getValue()) &&
                      llvm::cast<QuantityType>(result.getValue()).getKind() == "real";
  if (!result || (!legacyResult && call->getResult(0).getType() != result.getValue()))
    return error(call, "NODAL-ANALOG-041-003", "function call result type mismatch");
  for (auto [value, input] : llvm::zip(call->getOperands(), expected)) {
    if (input->getNumResults() != 1)
      return error(call, "NODAL-ANALOG-041-003", "malformed function argument declaration");
    auto actual = argumentType(value);
    auto required = bodyType(input->getResult(0).getType());
    if (failed(actual) || failed(required) || !equalType(*actual, *required))
      return error(call, "NODAL-ANALOG-041-003",
                   "argument scalar type or physical dimension mismatch");
    // SSA visibility alone must not permit a sibling module's value to leak in.
    Operation *definition = value.getDefiningOp();
    if (!definition || moduleOf(definition) != moduleOf(call))
      return error(call, "NODAL-ANALOG-041-005", "call operand crosses its owning module");
  }
  return success();
}

LogicalResult verifyValue(Operation *op) {
  if (!named(op->getParentOp(), "nodal.analog_user_function") || op->getNumResults() != 1)
    return error(op, "NODAL-ANALOG-041-001", "value requires one result in a pure function");
  auto result = bodyType(op->getResult(0).getType());
  if (failed(result))
    return error(op, "NODAL-ANALOG-041-003", "function body requires a typed scalar quantity");
  auto kind = text(op, "kind");
  bool input = kind == "input", local = kind == "local", literal = kind == "literal";
  if ((input || local) != op->hasAttr("name") || input != op->hasAttr("argument_index") ||
      literal != op->hasAttr("value") || (kind == "math") != op->hasAttr("function_id"))
    return error(op, "NODAL-ANALOG-041-002",
                 "function value attributes disagree with operation kind");
  if ((input || local) && (!identifier(text(op, "name")) || !scalar(op->getResult(0).getType())))
    return error(op, "NODAL-ANALOG-041-003",
                 "argument/local requires a valid name and Real or Integer type");
  llvm::SmallVector<AnalogNumericTypeInfo> types;
  for (Value value : op->getOperands()) {
    auto info = bodyType(value.getType());
    if (failed(info))
      return error(op, "NODAL-ANALOG-041-003", "invalid pure function operand type");
    types.push_back(*info);
  }
  auto numeric = [&]() {
    return !types.empty() && llvm::all_of(types, [&](const auto &type) {
      return (type.kind == AnalogNumericKind::Real || type.kind == AnalogNumericKind::Integer) &&
             type.kind == types.front().kind;
    });
  };
  auto same = [&]() {
    return !types.empty() &&
           llvm::all_of(types, [&](const auto &type) { return equalType(type, types.front()); });
  };
  bool valid = false;
  if (input) {
    auto index = op->getAttrOfType<IntegerAttr>("argument_index");
    valid = types.empty() && index && index.getInt() >= 0;
  } else if (literal) {
    Attribute value = op->getAttr("value");
    if (result->kind == AnalogNumericKind::Real) {
      auto number = llvm::dyn_cast<FloatAttr>(value);
      valid = number && number.getType().isF64() && std::isfinite(number.getValueAsDouble());
    } else if (result->kind == AnalogNumericKind::Integer) {
      auto number = llvm::dyn_cast<IntegerAttr>(value);
      valid = number && number.getType().isInteger(32);
    } else if (result->kind == AnalogNumericKind::Boolean) {
      auto number = llvm::dyn_cast<BoolAttr>(value);
      valid = static_cast<bool>(number);
    }
    valid &= types.empty();
  } else if (local || kind == "neg") {
    valid = types.size() == 1 && numeric() && equalType(*result, types[0]);
  } else if (kind == "add" || kind == "sub") {
    valid = types.size() == 2 && numeric() && same() && equalType(*result, types[0]);
  } else if (kind == "mul" || kind == "div") {
    if (types.size() == 2 && numeric() &&
        (kind != "div" || types[0].kind == AnalogNumericKind::Real)) {
      auto dimension =
          combineAnalogDimensions(types[0].dimension, types[1].dimension, kind == "div");
      valid =
          succeeded(dimension) && result->kind == types[0].kind && result->dimension == *dimension;
    }
  } else if (kind == "gt" || kind == "ge" || kind == "lt" || kind == "le") {
    valid = types.size() == 2 && numeric() && same() && result->kind == AnalogNumericKind::Boolean;
  } else if (kind == "and" || kind == "or" || kind == "not") {
    valid = types.size() == (kind == "not" ? 1U : 2U) &&
            result->kind == AnalogNumericKind::Boolean && llvm::all_of(types, [](const auto &type) {
              return type.kind == AnalogNumericKind::Boolean;
            });
  } else if (kind == "select") {
    valid = types.size() == 3 && types[0].kind == AnalogNumericKind::Boolean &&
            equalType(types[1], types[2]) && equalType(*result, types[1]);
  } else if (kind == "math") {
    auto *entry = lookupAnalogFunction(text(op, "function_id"));
    if (entry && types.size() == entry->arity && result->kind == AnalogNumericKind::Real &&
        llvm::all_of(types,
                     [](const auto &type) { return type.kind == AnalogNumericKind::Real; })) {
      llvm::SmallVector<std::string> dimensions;
      llvm::SmallVector<std::optional<double>> constants;
      for (auto [operand, type] : llvm::zip(op->getOperands(), types)) {
        dimensions.push_back(type.dimension);
        Operation *definition = operand.getDefiningOp();
        auto value = definition && text(definition, "kind") == "literal"
                         ? definition->getAttrOfType<FloatAttr>("value")
                         : FloatAttr();
        constants.push_back(value ? std::optional<double>(value.getValueAsDouble()) : std::nullopt);
      }
      auto dimension = analogFunctionDimension(*entry, dimensions);
      valid = succeeded(dimension) && *dimension == result->dimension &&
              succeeded(evaluateAnalogFunction(*entry, constants));
    }
  }
  if (!valid)
    return error(op, "NODAL-ANALOG-041-003",
                 "invalid function expression arity, scalar type, dimension or literal");
  return success();
}

// Analyze constants only to reject known-invalid expressions. Do not rewrite body SSA or
// infer constants across calls; target declarations and call identity remain observable.
FailureOr<std::optional<double>>
constant(Operation *op, const llvm::DenseMap<Value, std::optional<double>> &known) {
  auto kind = text(op, "kind");
  llvm::SmallVector<std::optional<double>> values;
  for (Value operand : op->getOperands()) {
    auto found = known.find(operand);
    values.push_back(found == known.end() ? std::nullopt : found->second);
  }
  if (kind == "div" && values.size() == 2 && values[1] && *values[1] == 0.0)
    return failure();
  std::optional<double> result;
  if (kind == "literal") {
    if (auto real = op->getAttrOfType<FloatAttr>("value"))
      result = real.getValueAsDouble();
    else if (auto boolean = op->getAttrOfType<BoolAttr>("value"))
      result = boolean.getValue() ? 1.0 : 0.0;
    else if (auto integer = op->getAttrOfType<IntegerAttr>("value"))
      result = static_cast<double>(integer.getInt());
  } else if (kind == "math") {
    auto *entry = lookupAnalogFunction(text(op, "function_id"));
    if (!entry)
      return failure();
    return evaluateAnalogFunction(*entry, values);
  } else if (kind == "local" && values.size() == 1) {
    result = values[0];
  } else if (kind == "select" && values.size() == 3 && values[0]) {
    result = values[*values[0] != 0.0 ? 1 : 2];
  } else if (values.size() == 1 && values[0]) {
    if (kind == "neg")
      result = -*values[0];
    else if (kind == "not")
      result = *values[0] == 0.0 ? 1.0 : 0.0;
  } else if (values.size() == 2 && values[0] && values[1]) {
    double a = *values[0], b = *values[1];
    if (kind == "add")
      result = a + b;
    else if (kind == "sub")
      result = a - b;
    else if (kind == "mul")
      result = a * b;
    else if (kind == "div")
      result = a / b;
    else if (kind == "lt")
      result = a < b ? 1.0 : 0.0;
    else if (kind == "le")
      result = a <= b ? 1.0 : 0.0;
    else if (kind == "gt")
      result = a > b ? 1.0 : 0.0;
    else if (kind == "ge")
      result = a >= b ? 1.0 : 0.0;
    else if (kind == "and")
      result = a != 0.0 && b != 0.0 ? 1.0 : 0.0;
    else if (kind == "or")
      result = a != 0.0 || b != 0.0 ? 1.0 : 0.0;
  }
  if (result && !std::isfinite(*result))
    return failure();
  if (op->getNumResults() == 1 && result) {
    auto type = bodyType(op->getResult(0).getType());
    if (succeeded(type) && type->kind == AnalogNumericKind::Integer &&
        (*result < std::numeric_limits<int32_t>::min() ||
         *result > std::numeric_limits<int32_t>::max()))
      return failure();
  }
  return result;
}

LogicalResult verifyDefinition(Operation *function) {
  auto type = function->getAttrOfType<TypeAttr>("return_type");
  if (text(function, "contract_version") != "1" || !identifier(text(function, "sym_name")) ||
      !type || !scalar(type.getValue()) || function->getNumRegions() != 1 ||
      !llvm::hasSingleElement(function->getRegion(0)) ||
      function->getRegion(0).front().getNumArguments() != 0 ||
      !named(function->getParentOp(), "nodal.module"))
    return error(function, "NODAL-ANALOG-041-002", "invalid typed analog function declaration");
  llvm::StringSet<> names;
  names.insert(text(function, "sym_name"));
  llvm::DenseSet<Value> available;
  llvm::DenseMap<Value, std::optional<double>> known;
  unsigned nextInput = 0;
  bool bodyStarted = false, returned = false;
  for (Operation &op : function->getRegion(0).front()) {
    if (returned)
      return error(&op, "NODAL-ANALOG-041-004", "return must be the final function operation");
    for (Value operand : op.getOperands())
      if (!available.contains(operand))
        return error(&op, "NODAL-ANALOG-041-006",
                     "function captures an external or unavailable value");
    if (named(&op, "nodal.analog_function_value")) {
      if (failed(verifyValue(&op)))
        return failure();
      auto kind = text(&op, "kind");
      if (kind == "input") {
        auto index = op.getAttrOfType<IntegerAttr>("argument_index");
        if (bodyStarted || !index || index.getInt() != nextInput++)
          return error(&op, "NODAL-ANALOG-041-002",
                       "function arguments require consecutive declaration order");
      } else {
        bodyStarted = true;
      }
      if ((kind == "input" || kind == "local") && !names.insert(text(&op, "name")).second)
        return error(&op, "NODAL-ANALOG-041-002", "duplicate function argument/local/return name");
    } else if (named(&op, "nodal.analog_user_call")) {
      bodyStarted = true;
      if (failed(verifyCall(&op)))
        return failure();
    } else if (named(&op, "nodal.analog_function_return")) {
      if (op.getNumOperands() != 1 || op.getNumResults() != 0 || op.getNumRegions() != 0 ||
          op.getOperand(0).getType() != type.getValue())
        return error(&op, "NODAL-ANALOG-041-004",
                     "function return disagrees with declared result type");
      returned = true;
    } else {
      return error(&op, "NODAL-ANALOG-041-001",
                   "state, effects and arbitrary operations are forbidden in functions");
    }
    if (forbiddenAnnotations(&op))
      return error(&op, "NODAL-ANALOG-FOLD-001",
                   "function body cannot carry unproved folding annotations");
    if (op.getNumResults() == 1) {
      auto value = constant(&op, known);
      if (failed(value))
        return error(&op, "NODAL-ANALOG-041-008",
                     "invalid constant function domain, denominator, overflow or nonfinite result");
      known[op.getResult(0)] = *value;
    }
    available.insert(op.getResults().begin(), op.getResults().end());
  }
  if (!nextInput || !returned)
    return error(function, "NODAL-ANALOG-041-004", "function requires inputs and one total return");
  if (failed(orderedAnalogUserFunctions(function->getParentOp())))
    return error(function, "NODAL-ANALOG-041-007",
                 "unresolved or recursive analog function call graph");
  return success();
}
} // namespace

FailureOr<Operation *> resolveAnalogUserCall(Operation *call) {
  auto symbol = call->getAttrOfType<FlatSymbolRefAttr>("callee");
  auto *module = moduleOf(call);
  if (!symbol || !module)
    return failure();
  auto *definition = SymbolTable::lookupSymbolIn(module, symbol);
  if (!named(definition, "nodal.analog_user_function") || definition->getParentOp() != module)
    return failure();
  return definition;
}

FailureOr<llvm::SmallVector<Operation *>> orderedAnalogUserFunctions(Operation *module) {
  if (!named(module, "nodal.module") || module->getNumRegions() != 1 ||
      !llvm::hasSingleElement(module->getRegion(0)))
    return failure();
  llvm::SmallVector<Operation *> pending, result;
  for (Operation &op : module->getRegion(0).front())
    if (named(&op, "nodal.analog_user_function"))
      pending.push_back(&op);
  llvm::sort(pending,
             [](Operation *a, Operation *b) { return text(a, "sym_name") < text(b, "sym_name"); });
  llvm::DenseSet<Operation *> emitted;
  // Iterative dependency ordering rejects cycles without recursing on a malicious call graph.
  while (!pending.empty()) {
    bool progress = false;
    for (auto iterator = pending.begin(); iterator != pending.end();) {
      Operation *function = *iterator;
      bool ready = true, invalid = false;
      function->walk([&](AnalogUserCallOp call) {
        auto callee = resolveAnalogUserCall(call.getOperation());
        if (failed(callee))
          invalid = true;
        else if (!emitted.contains(*callee))
          ready = false;
      });
      if (invalid)
        return failure();
      if (!ready) {
        ++iterator;
        continue;
      }
      result.push_back(function);
      emitted.insert(function);
      iterator = pending.erase(iterator);
      progress = true;
    }
    if (!progress)
      return failure();
  }
  return result;
}

LogicalResult verifyAnalogUserFunctionOperation(Operation *op) {
  if (forbiddenAnnotations(op))
    return error(op, "NODAL-ANALOG-FOLD-001",
                 "unproved function folding annotations are forbidden");
  if (named(op, "nodal.analog_user_function"))
    return verifyDefinition(op);
  if (named(op, "nodal.analog_function_value"))
    return verifyValue(op);
  if (named(op, "nodal.analog_user_call"))
    return verifyCall(op);
  if (named(op, "nodal.analog_function_return")) {
    auto *function = op->getParentOp();
    auto type = function ? function->getAttrOfType<TypeAttr>("return_type") : TypeAttr();
    if (!named(function, "nodal.analog_user_function") || !type || op->getNumOperands() != 1 ||
        op->getOperand(0).getType() != type.getValue())
      return error(op, "NODAL-ANALOG-041-004", "invalid function return");
    return success();
  }
  return error(op, "NODAL-ANALOG-041-001", "unknown analog function operation");
}
} // namespace nodal
