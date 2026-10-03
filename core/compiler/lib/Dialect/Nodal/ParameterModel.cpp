#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinTypes.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"

#include "llvm/ADT/APInt.h"
#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallString.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/Support/Casting.h"

#include <algorithm>
#include <cerrno>
#include <charconv>
#include <cmath>
#include <cstdlib>
#include <initializer_list>
#include <limits>
#include <optional>
#include <string>
#include <system_error>

using namespace mlir;

namespace {

enum class ConstantKind { Invalid, Integer, Real, Boolean };

struct EvaluatedConstant {
  ConstantKind kind = ConstantKind::Invalid;
  int64_t integerValue = 0;
  IntegerAttr exactInteger;
  bool integerIsNarrow = true;
  bool integerSigned = false;
  double realValue = 0.0;
  bool booleanValue = false;
  std::string dimension;
};

bool isNamed(Operation *operation, llvm::StringRef name) {
  return operation && operation->getName().getStringRef() == name;
}

llvm::StringRef textAttr(Operation *operation, llvm::StringRef name) {
  if (auto value = operation->getAttrOfType<StringAttr>(name))
    return value.getValue();
  return {};
}

bool oneOf(llvm::StringRef value, std::initializer_list<llvm::StringRef> choices) {
  return llvm::is_contained(choices, value);
}

bool canonicalText(llvm::StringRef value) {
  if (value.empty() || value != value.trim())
    return false;
  return llvm::all_of(value, [](char character) {
    const unsigned char byte = static_cast<unsigned char>(character);
    return byte >= 0x20 && byte != 0x7f;
  });
}

llvm::StringRef symbolName(Operation *operation) {
  if (auto value = operation->getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName()))
    return value.getValue();
  return {};
}

mlir::ModuleOp enclosingBuiltinModule(Operation *operation) {
  if (!operation)
    return {};
  if (auto module = llvm::dyn_cast<mlir::ModuleOp>(operation))
    return module;
  return operation->getParentOfType<mlir::ModuleOp>();
}

Operation *enclosingNodalModule(Operation *operation) {
  for (Operation *current = operation; current; current = current->getParentOp()) {
    if (isNamed(current, "nodal.module"))
      return current;
  }
  return nullptr;
}

Block *moduleBody(Operation *module) {
  if (!module || !isNamed(module, "nodal.module") || module->getNumRegions() != 1 ||
      module->getRegion(0).empty())
    return nullptr;
  return &module->getRegion(0).front();
}

Operation *findTopLevelSymbol(Operation *scope, llvm::StringRef name,
                              llvm::StringRef operationName) {
  mlir::ModuleOp module = enclosingBuiltinModule(scope);
  if (!module)
    return nullptr;
  for (Operation &candidate : module.getBody()->getOperations()) {
    if (isNamed(&candidate, operationName) && symbolName(&candidate) == name)
      return &candidate;
  }
  return nullptr;
}

Operation *findDirectSymbol(Operation *module, llvm::StringRef name,
                            llvm::StringRef operationName) {
  Block *body = moduleBody(module);
  if (!body)
    return nullptr;
  for (Operation &candidate : *body) {
    if (isNamed(&candidate, operationName) && symbolName(&candidate) == name)
      return &candidate;
  }
  return nullptr;
}

ConstantKind kindForType(Type type) {
  if (type.isF64())
    return ConstantKind::Real;
  if (auto integer = llvm::dyn_cast<IntegerType>(type))
    return integer.getWidth() == 1 ? ConstantKind::Boolean : ConstantKind::Integer;
  if (auto bits = llvm::dyn_cast<nodal::BitsType>(type))
    return bits.getWidth() == 1 ? ConstantKind::Boolean : ConstantKind::Integer;
  if (llvm::isa<nodal::UIntType, nodal::SIntType, nodal::EnumType>(type))
    return ConstantKind::Integer;
  return ConstantKind::Invalid;
}

bool integerTypeIsSigned(Type type) {
  if (llvm::isa<nodal::SIntType>(type))
    return true;
  if (auto integer = llvm::dyn_cast<IntegerType>(type))
    return integer.isSigned();
  return false;
}

llvm::StringRef inferredKind(Type type) {
  switch (kindForType(type)) {
  case ConstantKind::Real:
    return "real";
  case ConstantKind::Integer:
    return "integer";
  case ConstantKind::Boolean:
    return "boolean";
  case ConstantKind::Invalid:
    return {};
  }
  return {};
}

bool integerFits(IntegerAttr value, Type type) {
  unsigned width = 0;
  bool isSigned = false;
  if (auto integer = llvm::dyn_cast<IntegerType>(type)) {
    width = integer.getWidth();
    isSigned = integer.isSigned();
  } else if (auto bits = llvm::dyn_cast<nodal::BitsType>(type)) {
    width = static_cast<unsigned>(bits.getWidth());
  } else if (auto valueType = llvm::dyn_cast<nodal::UIntType>(type)) {
    width = static_cast<unsigned>(valueType.getWidth());
  } else if (auto valueType = llvm::dyn_cast<nodal::SIntType>(type)) {
    width = static_cast<unsigned>(valueType.getWidth());
    isSigned = true;
  } else if (auto valueType = llvm::dyn_cast<nodal::EnumType>(type)) {
    width = static_cast<unsigned>(valueType.getWidth());
  } else {
    return false;
  }
  if (isSigned)
    return value.getValue().isSignedIntN(width);
  return !value.getValue().isNegative() && value.getValue().isIntN(width);
}

bool attributeFits(Attribute value, Type type) {
  if (auto typed = llvm::dyn_cast<TypedAttr>(value)) {
    if (typed.getType() == type)
      return true;
  }
  // BoolAttr also satisfies IntegerAttr; handle it before signed APInt fitting so
  // true remains valid for Boolean custom types such as !nodal.bits<1>.
  if (llvm::isa<BoolAttr>(value))
    return kindForType(type) == ConstantKind::Boolean;
  if (auto integer = llvm::dyn_cast<IntegerAttr>(value))
    return integerFits(integer, type);
  return false;
}

Operation *resolveUnit(Operation *scope, FlatSymbolRefAttr reference) {
  if (!reference)
    return nullptr;
  return findTopLevelSymbol(scope, reference.getValue(), "nodal.unit");
}

double unitScale(Operation *unit) {
  auto scale = unit ? unit->getAttrOfType<FloatAttr>("scale") : FloatAttr();
  return scale ? scale.getValueAsDouble() : 0.0;
}

llvm::StringRef unitDimension(Operation *unit) { return textAttr(unit, "dimension"); }
llvm::StringRef unitSuffix(Operation *unit) { return textAttr(unit, "native_suffix"); }

std::optional<double> suffixScale(llvm::StringRef suffix) {
  if (suffix.empty())
    return 1.0;
  if (suffix == "T")
    return 1.0e12;
  if (suffix == "G")
    return 1.0e9;
  if (suffix == "M")
    return 1.0e6;
  if (suffix == "K" || suffix == "k")
    return 1.0e3;
  if (suffix == "m")
    return 1.0e-3;
  if (suffix == "u")
    return 1.0e-6;
  if (suffix == "n")
    return 1.0e-9;
  if (suffix == "p")
    return 1.0e-12;
  if (suffix == "f")
    return 1.0e-15;
  if (suffix == "a")
    return 1.0e-18;
  return std::nullopt;
}

bool allowedSuffix(llvm::StringRef suffix) { return suffixScale(suffix).has_value(); }

bool splitSpelling(llvm::StringRef spelling, llvm::StringRef suffix, llvm::StringRef &numeric) {
  if (!allowedSuffix(suffix) || !canonicalText(spelling))
    return false;
  if (!suffix.empty()) {
    if (!spelling.ends_with(suffix) || spelling.size() == suffix.size())
      return false;
    numeric = spelling.drop_back(suffix.size());
  } else {
    numeric = spelling;
  }
  return !numeric.empty() && numeric == numeric.trim();
}

std::optional<double> parseReal(llvm::StringRef spelling) {
  std::string storage = spelling.str();
  char *end = nullptr;
  errno = 0;
  double value = std::strtod(storage.c_str(), &end);
  if (errno == ERANGE || end != storage.c_str() + storage.size() || !std::isfinite(value))
    return std::nullopt;
  return value;
}

std::optional<int64_t> parseInteger(llvm::StringRef spelling) {
  int64_t value = 0;
  auto result = std::from_chars(spelling.begin(), spelling.end(), value, 10);
  if (result.ec != std::errc() || result.ptr != spelling.end())
    return std::nullopt;
  return value;
}

bool validNativeDecimal(llvm::StringRef spelling, bool integerOnly) {
  if (spelling.empty())
    return false;
  size_t index = 0;
  if (spelling[index] == '+' || spelling[index] == '-') {
    if (++index == spelling.size())
      return false;
  }
  const size_t integerStart = index;
  while (index < spelling.size() && llvm::isDigit(spelling[index]))
    ++index;
  if (index == integerStart)
    return false;
  if (!integerOnly && index < spelling.size() && spelling[index] == '.') {
    const size_t fractionalStart = ++index;
    while (index < spelling.size() && llvm::isDigit(spelling[index]))
      ++index;
    if (index == fractionalStart)
      return false;
  }
  if (!integerOnly && index < spelling.size() &&
      (spelling[index] == 'e' || spelling[index] == 'E')) {
    ++index;
    if (index < spelling.size() && (spelling[index] == '+' || spelling[index] == '-'))
      ++index;
    const size_t exponentStart = index;
    while (index < spelling.size() && llvm::isDigit(spelling[index]))
      ++index;
    if (index == exponentStart)
      return false;
  }
  return index == spelling.size();
}

bool closeEnough(double lhs, double rhs) {
  const double scale = std::max({1.0, std::fabs(lhs), std::fabs(rhs)});
  return std::fabs(lhs - rhs) <= scale * 1.0e-12;
}

bool checkedNegate(int64_t value, int64_t &result) {
  return !__builtin_sub_overflow(int64_t{0}, value, &result);
}

bool checkedAdd(int64_t lhs, int64_t rhs, int64_t &result) {
  return !__builtin_add_overflow(lhs, rhs, &result);
}

bool checkedSubtract(int64_t lhs, int64_t rhs, int64_t &result) {
  return !__builtin_sub_overflow(lhs, rhs, &result);
}

bool checkedMultiply(int64_t lhs, int64_t rhs, int64_t &result) {
  return !__builtin_mul_overflow(lhs, rhs, &result);
}

FailureOr<EvaluatedConstant> constantFromAttribute(Attribute value, Type type,
                                                   Operation *unit = nullptr) {
  EvaluatedConstant result;
  result.kind = kindForType(type);
  if (result.kind == ConstantKind::Real) {
    auto real = llvm::dyn_cast<FloatAttr>(value);
    if (!real || !std::isfinite(real.getValueAsDouble()))
      return failure();
    result.realValue = real.getValueAsDouble();
    if (unit) {
      result.realValue *= unitScale(unit);
      result.dimension = unitDimension(unit).str();
    }
    return result;
  }
  if (result.kind == ConstantKind::Boolean) {
    if (auto boolean = llvm::dyn_cast<BoolAttr>(value)) {
      result.booleanValue = boolean.getValue();
      return result;
    }
    if (auto integer = llvm::dyn_cast<IntegerAttr>(value)) {
      if (integer.getInt() != 0 && integer.getInt() != 1)
        return failure();
      result.booleanValue = integer.getInt() != 0;
      return result;
    }
    return failure();
  }
  if (result.kind == ConstantKind::Integer) {
    auto integer = llvm::dyn_cast<IntegerAttr>(value);
    if (!integer)
      return failure();
    result.exactInteger = integer;
    result.integerSigned = integerTypeIsSigned(type);
    const llvm::APInt &bits = integer.getValue();
    const bool narrow = result.integerSigned ? bits.isSignedIntN(64) : bits.getActiveBits() <= 63;
    result.integerIsNarrow = narrow;
    if (narrow)
      result.integerValue =
          result.integerSigned ? bits.getSExtValue() : static_cast<int64_t>(bits.getZExtValue());
    return result;
  }
  return failure();
}

Operation *findParameterValue(Operation *module, llvm::StringRef parameter) {
  Block *body = moduleBody(module);
  if (!body)
    return nullptr;
  Operation *found = nullptr;
  for (Operation &operation : *body) {
    if (!isNamed(&operation, "nodal.parameter_value"))
      continue;
    auto reference = operation.getAttrOfType<FlatSymbolRefAttr>("parameter");
    if (!reference || reference.getValue() != parameter)
      continue;
    if (found)
      return nullptr;
    found = &operation;
  }
  return found;
}

bool hasDuplicateParameterValue(Operation *module, llvm::StringRef parameter) {
  Block *body = moduleBody(module);
  if (!body)
    return false;
  unsigned count = 0;
  for (Operation &operation : *body) {
    if (!isNamed(&operation, "nodal.parameter_value"))
      continue;
    auto reference = operation.getAttrOfType<FlatSymbolRefAttr>("parameter");
    if (reference && reference.getValue() == parameter && ++count > 1)
      return true;
  }
  return false;
}

llvm::SmallVector<Operation *, 4> findParameterConstraints(Operation *module,
                                                           llvm::StringRef parameter) {
  llvm::SmallVector<Operation *, 4> constraints;
  Block *body = moduleBody(module);
  if (!body)
    return constraints;
  for (Operation &operation : *body) {
    if (!isNamed(&operation, "nodal.parameter_constraint"))
      continue;
    auto reference = operation.getAttrOfType<FlatSymbolRefAttr>("parameter");
    if (reference && reference.getValue() == parameter)
      constraints.push_back(&operation);
  }
  return constraints;
}

Operation *findParameterEnvelope(Operation *module, llvm::StringRef parameter, bool &duplicate) {
  duplicate = false;
  Block *body = moduleBody(module);
  if (!body)
    return nullptr;
  Operation *found = nullptr;
  for (Operation &operation : *body) {
    if (!isNamed(&operation, "nodal.parameter_envelope"))
      continue;
    auto reference = operation.getAttrOfType<FlatSymbolRefAttr>("parameter");
    if (!reference || reference.getValue() != parameter)
      continue;
    if (found) {
      duplicate = true;
      return found;
    }
    found = &operation;
  }
  return found;
}

FailureOr<EvaluatedConstant> evaluateValue(Value value, Operation *scope,
                                           llvm::DenseSet<Operation *> &parameterStack);
int compareConstants(const EvaluatedConstant &lhs, const EvaluatedConstant &rhs);

FailureOr<EvaluatedConstant> adoptParameterUnit(EvaluatedConstant value, Operation *parameter) {
  Operation *unit = resolveUnit(parameter, parameter->getAttrOfType<FlatSymbolRefAttr>("unit"));
  if (!unit || value.kind != ConstantKind::Real)
    return value;
  llvm::StringRef dimension = unitDimension(unit);
  if (value.dimension.empty()) {
    value.realValue *= unitScale(unit);
    value.dimension = dimension.str();
  } else if (value.dimension != dimension) {
    return failure();
  }
  if (!std::isfinite(value.realValue))
    return failure();
  return value;
}

FailureOr<EvaluatedConstant> evaluateParameterDefault(Operation *parameter,
                                                      llvm::DenseSet<Operation *> &parameterStack) {
  if (!parameter || !parameterStack.insert(parameter).second)
    return failure();
  Operation *module = enclosingNodalModule(parameter);
  Operation *value = findParameterValue(module, symbolName(parameter));
  FailureOr<EvaluatedConstant> result = failure();
  if (value) {
    result = evaluateValue(value->getOperand(0), parameter, parameterStack);
  } else {
    auto type = parameter->getAttrOfType<TypeAttr>("type");
    Attribute defaultValue = parameter->getAttr("default_value");
    Operation *unit = resolveUnit(parameter, parameter->getAttrOfType<FlatSymbolRefAttr>("unit"));
    if (type && defaultValue)
      result = constantFromAttribute(defaultValue, type.getValue(), unit);
  }
  if (succeeded(result))
    result = adoptParameterUnit(*result, parameter);
  parameterStack.erase(parameter);
  return result;
}

FailureOr<EvaluatedConstant> evaluateExpression(Operation *operation, Operation *scope,
                                                llvm::DenseSet<Operation *> &parameterStack) {
  llvm::StringRef name = textAttr(operation, "operator_name");
  const unsigned count = operation->getNumOperands();
  const bool unary = name == "neg" || name == "not";
  if (unary ? count != 1 : count != 2)
    return failure();

  auto lhs = evaluateValue(operation->getOperand(0), scope, parameterStack);
  if (failed(lhs))
    return failure();
  EvaluatedConstant result;
  result.kind = kindForType(operation->getResult(0).getType());

  if (name == "neg") {
    result.dimension = lhs->dimension;
    if (result.kind == ConstantKind::Real) {
      const double value =
          lhs->kind == ConstantKind::Real ? lhs->realValue : static_cast<double>(lhs->integerValue);
      result.realValue = -value;
      return result;
    }
    if (result.kind == ConstantKind::Integer && lhs->kind == ConstantKind::Integer &&
        lhs->integerIsNarrow && checkedNegate(lhs->integerValue, result.integerValue))
      return result;
    return failure();
  }
  if (name == "not") {
    if (result.kind != ConstantKind::Boolean || lhs->kind != ConstantKind::Boolean ||
        !lhs->dimension.empty())
      return failure();
    result.booleanValue = !lhs->booleanValue;
    return result;
  }

  auto rhs = evaluateValue(operation->getOperand(1), scope, parameterStack);
  if (failed(rhs))
    return failure();

  if (name == "gt" || name == "ge" || name == "lt" || name == "le") {
    if (result.kind != ConstantKind::Boolean || lhs->kind != rhs->kind ||
        (lhs->kind != ConstantKind::Real && lhs->kind != ConstantKind::Integer) ||
        lhs->dimension != rhs->dimension)
      return failure();
    int comparison = 0;
    if (lhs->kind == ConstantKind::Real) {
      if (lhs->realValue < rhs->realValue)
        comparison = -1;
      else if (lhs->realValue > rhs->realValue)
        comparison = 1;
    } else {
      comparison = compareConstants(*lhs, *rhs);
    }
    result.booleanValue = name == "gt"   ? comparison > 0
                          : name == "ge" ? comparison >= 0
                          : name == "lt" ? comparison < 0
                                         : comparison <= 0;
    return result;
  }

  if (name == "and" || name == "or") {
    if (result.kind != ConstantKind::Boolean || lhs->kind != ConstantKind::Boolean ||
        rhs->kind != ConstantKind::Boolean || !lhs->dimension.empty() || !rhs->dimension.empty())
      return failure();
    result.booleanValue = name == "and" ? lhs->booleanValue && rhs->booleanValue
                                        : lhs->booleanValue || rhs->booleanValue;
    return result;
  }

  if (name == "add" || name == "sub") {
    if (lhs->dimension != rhs->dimension)
      return failure();
    result.dimension = lhs->dimension;
    if (result.kind == ConstantKind::Real) {
      const double left =
          lhs->kind == ConstantKind::Real ? lhs->realValue : static_cast<double>(lhs->integerValue);
      const double right =
          rhs->kind == ConstantKind::Real ? rhs->realValue : static_cast<double>(rhs->integerValue);
      result.realValue = name == "add" ? left + right : left - right;
      if (!std::isfinite(result.realValue))
        return failure();
      return result;
    }
    if (result.kind == ConstantKind::Integer && lhs->kind == ConstantKind::Integer &&
        rhs->kind == ConstantKind::Integer && lhs->integerIsNarrow && rhs->integerIsNarrow) {
      const bool valid =
          name == "add"
              ? checkedAdd(lhs->integerValue, rhs->integerValue, result.integerValue)
              : checkedSubtract(lhs->integerValue, rhs->integerValue, result.integerValue);
      if (valid)
        return result;
    }
    return failure();
  }

  if (name == "mul" || name == "div") {
    if (!lhs->dimension.empty() && !rhs->dimension.empty())
      return failure();
    if (name == "div" && !rhs->dimension.empty())
      return failure();
    result.dimension = !lhs->dimension.empty() ? lhs->dimension : rhs->dimension;
    const double left =
        lhs->kind == ConstantKind::Real ? lhs->realValue : static_cast<double>(lhs->integerValue);
    const double right =
        rhs->kind == ConstantKind::Real ? rhs->realValue : static_cast<double>(rhs->integerValue);
    if (name == "div" && right == 0.0)
      return failure();
    if (result.kind == ConstantKind::Real) {
      result.realValue = name == "mul" ? left * right : left / right;
      if (!std::isfinite(result.realValue))
        return failure();
      return result;
    }
    if (result.kind == ConstantKind::Integer && lhs->kind == ConstantKind::Integer &&
        rhs->kind == ConstantKind::Integer && lhs->integerIsNarrow && rhs->integerIsNarrow) {
      if (name == "mul") {
        if (checkedMultiply(lhs->integerValue, rhs->integerValue, result.integerValue))
          return result;
      } else {
        if (rhs->integerValue == 0 ||
            (lhs->integerValue == std::numeric_limits<int64_t>::min() && rhs->integerValue == -1))
          return failure();
        result.integerValue = lhs->integerValue / rhs->integerValue;
        return result;
      }
    }
    return failure();
  }

  if (name == "mod") {
    if (result.kind != ConstantKind::Integer || lhs->kind != ConstantKind::Integer ||
        rhs->kind != ConstantKind::Integer || !lhs->integerIsNarrow || !rhs->integerIsNarrow ||
        lhs->dimension != rhs->dimension || rhs->integerValue == 0 ||
        (lhs->integerValue == std::numeric_limits<int64_t>::min() && rhs->integerValue == -1))
      return failure();
    result.dimension = lhs->dimension;
    result.integerValue = lhs->integerValue % rhs->integerValue;
    return result;
  }
  return failure();
}

FailureOr<EvaluatedConstant> evaluateValue(Value value, Operation *scope,
                                           llvm::DenseSet<Operation *> &parameterStack) {
  Operation *operation = value.getDefiningOp();
  if (!operation)
    return failure();
  llvm::StringRef name = operation->getName().getStringRef();
  if (name == "nodal.const_literal") {
    Operation *unit = resolveUnit(operation, operation->getAttrOfType<FlatSymbolRefAttr>("unit"));
    return constantFromAttribute(operation->getAttr("value"), value.getType(), unit);
  }
  if (name == "nodal.const_parameter_ref") {
    auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("parameter");
    Operation *module = enclosingNodalModule(operation);
    Operation *parameter =
        reference ? findDirectSymbol(module, reference.getValue(), "nodal.parameter") : nullptr;
    return evaluateParameterDefault(parameter, parameterStack);
  }
  if (name == "nodal.const_expr")
    return evaluateExpression(operation, scope, parameterStack);
  if (name == "nodal.constant")
    return constantFromAttribute(operation->getAttr("value"), value.getType());
  return failure();
}

FailureOr<EvaluatedConstant> normalizeForParameter(const EvaluatedConstant &value,
                                                   Operation *parameter) {
  EvaluatedConstant result;
  const llvm::StringRef kind = nodal::getParameterKind(parameter);
  Operation *unit = resolveUnit(parameter, parameter->getAttrOfType<FlatSymbolRefAttr>("unit"));
  if (kind == "real") {
    if (value.kind != ConstantKind::Real && value.kind != ConstantKind::Integer)
      return failure();
    double numeric = value.kind == ConstantKind::Real ? value.realValue
                                                      : static_cast<double>(value.integerValue);
    if (unit) {
      if (!value.dimension.empty()) {
        if (value.dimension != unitDimension(unit))
          return failure();
        numeric /= unitScale(unit);
      }
    } else if (!value.dimension.empty()) {
      return failure();
    }
    if (!std::isfinite(numeric))
      return failure();
    result.kind = ConstantKind::Real;
    result.realValue = numeric;
    return result;
  }
  if (kind == "integer") {
    if (value.kind != ConstantKind::Integer || !value.dimension.empty())
      return failure();
    result.kind = ConstantKind::Integer;
    result.integerValue = value.integerValue;
    result.exactInteger = value.exactInteger;
    result.integerIsNarrow = value.integerIsNarrow;
    result.integerSigned = value.integerSigned;
    return result;
  }
  if (kind == "boolean") {
    if (!value.dimension.empty())
      return failure();
    result.kind = ConstantKind::Boolean;
    if (value.kind == ConstantKind::Boolean)
      result.booleanValue = value.booleanValue;
    else if (value.kind == ConstantKind::Integer &&
             (value.integerValue == 0 || value.integerValue == 1))
      result.booleanValue = value.integerValue != 0;
    else
      return failure();
    return result;
  }
  return failure();
}

bool attributeMatches(Attribute attribute, const EvaluatedConstant &value) {
  if (value.kind == ConstantKind::Real) {
    auto real = llvm::dyn_cast<FloatAttr>(attribute);
    return real && closeEnough(real.getValueAsDouble(), value.realValue);
  }
  if (value.kind == ConstantKind::Integer) {
    auto integer = llvm::dyn_cast<IntegerAttr>(attribute);
    if (!integer)
      return false;
    if (value.exactInteger) {
      const unsigned width =
          std::max(integer.getValue().getBitWidth(), value.exactInteger.getValue().getBitWidth());
      llvm::APInt lhs = value.integerSigned ? value.exactInteger.getValue().sextOrTrunc(width)
                                            : value.exactInteger.getValue().zextOrTrunc(width);
      llvm::APInt rhs = value.integerSigned ? integer.getValue().sextOrTrunc(width)
                                            : integer.getValue().zextOrTrunc(width);
      return lhs == rhs;
    }
    return value.integerIsNarrow && integer.getValue().isSignedIntN(64) &&
           integer.getInt() == value.integerValue;
  }
  if (value.kind == ConstantKind::Boolean) {
    if (auto boolean = llvm::dyn_cast<BoolAttr>(attribute))
      return boolean.getValue() == value.booleanValue;
    if (auto integer = llvm::dyn_cast<IntegerAttr>(attribute))
      return integer.getInt() == (value.booleanValue ? 1 : 0);
  }
  return false;
}

int compareConstants(const EvaluatedConstant &lhs, const EvaluatedConstant &rhs) {
  if (lhs.kind == ConstantKind::Real || rhs.kind == ConstantKind::Real) {
    const double left =
        lhs.kind == ConstantKind::Real ? lhs.realValue : static_cast<double>(lhs.integerValue);
    const double right =
        rhs.kind == ConstantKind::Real ? rhs.realValue : static_cast<double>(rhs.integerValue);
    if (closeEnough(left, right))
      return 0;
    return left < right ? -1 : 1;
  }
  if (lhs.kind == ConstantKind::Boolean || rhs.kind == ConstantKind::Boolean) {
    const int left = lhs.kind == ConstantKind::Boolean ? lhs.booleanValue : lhs.integerValue != 0;
    const int right = rhs.kind == ConstantKind::Boolean ? rhs.booleanValue : rhs.integerValue != 0;
    return left == right ? 0 : (left < right ? -1 : 1);
  }
  if (lhs.exactInteger || rhs.exactInteger) {
    const unsigned lhsWidth = lhs.exactInteger ? lhs.exactInteger.getValue().getBitWidth() : 64;
    const unsigned rhsWidth = rhs.exactInteger ? rhs.exactInteger.getValue().getBitWidth() : 64;
    const unsigned width = std::max(lhsWidth, rhsWidth);
    const bool signedCompare = lhs.integerSigned || rhs.integerSigned;
    llvm::APInt left = lhs.exactInteger
                           ? (signedCompare ? lhs.exactInteger.getValue().sextOrTrunc(width)
                                            : lhs.exactInteger.getValue().zextOrTrunc(width))
                           : llvm::APInt(width, static_cast<uint64_t>(lhs.integerValue), true);
    llvm::APInt right = rhs.exactInteger
                            ? (signedCompare ? rhs.exactInteger.getValue().sextOrTrunc(width)
                                             : rhs.exactInteger.getValue().zextOrTrunc(width))
                            : llvm::APInt(width, static_cast<uint64_t>(rhs.integerValue), true);
    if (left == right)
      return 0;
    return signedCompare ? (left.slt(right) ? -1 : 1) : (left.ult(right) ? -1 : 1);
  }
  if (lhs.integerValue == rhs.integerValue)
    return 0;
  return lhs.integerValue < rhs.integerValue ? -1 : 1;
}

LogicalResult checkConstraints(Operation *parameter, const EvaluatedConstant &candidate,
                               llvm::StringRef code, Operation *diagnosticOwner) {
  Operation *module = enclosingNodalModule(parameter);
  for (Operation *constraint : findParameterConstraints(module, symbolName(parameter))) {
    llvm::DenseSet<Operation *> stack;
    llvm::SmallVector<EvaluatedConstant, 2> values;
    for (Value operand : constraint->getOperands()) {
      auto evaluated = evaluateValue(operand, constraint, stack);
      if (failed(evaluated))
        return diagnosticOwner->emitOpError()
               << code << ": constraint expression could not be folded";
      auto normalized = normalizeForParameter(*evaluated, parameter);
      if (failed(normalized))
        return diagnosticOwner->emitOpError()
               << code << ": constraint unit or type is incompatible";
      values.push_back(*normalized);
    }
    llvm::StringRef kind = textAttr(constraint, "constraint_kind");
    if (kind == "range") {
      auto lowerInclusive = constraint->getAttrOfType<BoolAttr>("lower_inclusive");
      auto upperInclusive = constraint->getAttrOfType<BoolAttr>("upper_inclusive");
      const int lower = compareConstants(candidate, values[0]);
      const int upper = compareConstants(candidate, values[1]);
      if ((lowerInclusive.getValue() ? lower < 0 : lower <= 0) ||
          (upperInclusive.getValue() ? upper > 0 : upper >= 0))
        return diagnosticOwner->emitOpError() << code << ": value violates parameter range";
    } else if (kind == "exclude" && compareConstants(candidate, values[0]) == 0) {
      return diagnosticOwner->emitOpError() << code << ": value is explicitly excluded";
    }
  }
  return success();
}

bool hasBoundedRange(Operation *parameter) {
  Operation *module = enclosingNodalModule(parameter);
  for (Operation *constraint : findParameterConstraints(module, symbolName(parameter))) {
    if (textAttr(constraint, "constraint_kind") == "range" && constraint->getNumOperands() == 2)
      return true;
  }
  return false;
}

// Abstract interpretation of the existing constant-expression representation.
// Default folding above answers a different question and must never be used to
// bound an independently overridable parameter. The analysis is read-only and
// memoized per module; it neither expands iterations nor owns another IR graph.
using IntegerBounds = nodal::ParameterIntegerBounds;

llvm::APInt wideInteger(int64_t value) {
  return llvm::APInt(64, static_cast<uint64_t>(value), true).sext(128);
}

FailureOr<int64_t> narrowInteger(const llvm::APInt &value) {
  if (!value.isSignedIntN(64))
    return failure();
  return value.getSExtValue();
}

bool boundsFitType(IntegerBounds bounds, Type type) {
  auto attributeType = IntegerType::get(type.getContext(), 64);
  return integerFits(IntegerAttr::get(attributeType, bounds.lower), type) &&
         integerFits(IntegerAttr::get(attributeType, bounds.upper), type);
}

FailureOr<int64_t> integerBoundLiteral(IntegerAttr attribute) {
  auto type = llvm::dyn_cast_or_null<IntegerType>(attribute ? attribute.getType() : Type());
  if (!type || kindForType(type) != ConstantKind::Integer)
    return failure();
  const llvm::APInt &bits = attribute.getValue();
  if (type.isUnsigned()) {
    if (bits.getActiveBits() > 63)
      return failure();
    return static_cast<int64_t>(bits.getZExtValue());
  }
  // Signless attributes retain the established signed mathematical loop
  // spelling, including -1 : i64; explicit unsigned types are never sign cast.
  if (!bits.isSignedIntN(64))
    return failure();
  return bits.getSExtValue();
}

class IntegerBoundsAnalysis {
public:
  explicit IntegerBoundsAnalysis(Operation *owner) : owner(owner) {}

  FailureOr<IntegerBounds> value(Value input) {
    if (!input)
      return failure();
    auto cached = values.find(input);
    if (cached != values.end()) {
      if (!cached->second)
        return failure();
      return *cached->second;
    }
    if (activeValues.size() + activeParameters.size() >= 512 || !activeValues.insert(input).second)
      return failure();
    auto result = analyzeValue(input);
    activeValues.erase(input);
    values.try_emplace(input,
                       succeeded(result) ? std::optional<IntegerBounds>(*result) : std::nullopt);
    return result;
  }

  FailureOr<IntegerBounds> parameter(Operation *input) {
    if (!input)
      return failure();
    auto cached = parameters.find(input);
    if (cached != parameters.end()) {
      if (!cached->second)
        return failure();
      return *cached->second;
    }
    if (activeValues.size() + activeParameters.size() >= 512 ||
        !activeParameters.insert(input).second)
      return failure();
    auto result = analyzeParameter(input);
    activeParameters.erase(input);
    parameters.try_emplace(input, succeeded(result) ? std::optional<IntegerBounds>(*result)
                                                    : std::nullopt);
    return result;
  }

  FailureOr<IntegerBounds> bound(Attribute attribute) {
    if (auto integer = llvm::dyn_cast_or_null<IntegerAttr>(attribute)) {
      auto number = integerBoundLiteral(integer);
      if (failed(number))
        return failure();
      return IntegerBounds{*number, *number};
    }
    if (auto reference = llvm::dyn_cast_or_null<FlatSymbolRefAttr>(attribute))
      return parameter(findDirectSymbol(owner, reference.getValue(), "nodal.parameter"));
    return failure();
  }

private:
  FailureOr<IntegerBounds> literal(Attribute attribute, Type type) {
    auto constant = constantFromAttribute(attribute, type);
    if (failed(constant) || constant->kind != ConstantKind::Integer || !constant->integerIsNarrow ||
        !constant->dimension.empty())
      return failure();
    IntegerBounds result{constant->integerValue, constant->integerValue};
    if (!boundsFitType(result, type))
      return failure();
    return result;
  }

  FailureOr<IntegerBounds> analyzeParameter(Operation *input) {
    if (!input || !isNamed(input, "nodal.parameter") || input->getParentOp() != owner ||
        nodal::getParameterKind(input) != "integer" || input->hasAttr("unit") ||
        hasDuplicateParameterValue(owner, symbolName(input)))
      return failure();
    auto type = input->getAttrOfType<TypeAttr>("type");
    if (!type || kindForType(type.getValue()) != ConstantKind::Integer)
      return failure();
    if (textAttr(input, "variability") == "fixed") {
      Operation *expression = findParameterValue(owner, symbolName(input));
      if (!expression)
        return literal(input->getAttr("default_value"), type.getValue());
      if (expression->getNumOperands() != 1 ||
          expression->getOperand(0).getType() != type.getValue())
        return failure();
      return value(expression->getOperand(0));
    }
    if (textAttr(input, "variability") != "symbolic")
      return failure();

    std::optional<IntegerBounds> intersection;
    for (Operation *constraint : findParameterConstraints(owner, symbolName(input))) {
      // Exclusions can only shrink a legal set. Keeping their holes in the
      // interval is conservative; it must not certify a zero-containing step.
      if (textAttr(constraint, "constraint_kind") != "range")
        continue;
      if (constraint->getNumOperands() != 2 ||
          constraint->getOperand(0).getType() != type.getValue() ||
          constraint->getOperand(1).getType() != type.getValue())
        return failure();
      auto lowerInclusive = constraint->getAttrOfType<BoolAttr>("lower_inclusive");
      auto upperInclusive = constraint->getAttrOfType<BoolAttr>("upper_inclusive");
      auto lower = value(constraint->getOperand(0));
      auto upper = value(constraint->getOperand(1));
      if (!lowerInclusive || !upperInclusive || failed(lower) || failed(upper))
        return failure();
      // Use outer endpoints of varying constraints, never their defaults or
      // inner endpoints: the result must contain the union of legal settings.
      auto first = narrowInteger(wideInteger(lower->lower) +
                                 llvm::APInt(128, lowerInclusive.getValue() ? 0 : 1));
      auto last = narrowInteger(wideInteger(upper->upper) -
                                llvm::APInt(128, upperInclusive.getValue() ? 0 : 1));
      if (failed(first) || failed(last) || *first > *last)
        return failure();
      IntegerBounds candidate{*first, *last};
      if (intersection) {
        candidate.lower = std::max(candidate.lower, intersection->lower);
        candidate.upper = std::min(candidate.upper, intersection->upper);
      }
      if (candidate.lower > candidate.upper || !boundsFitType(candidate, type.getValue()))
        return failure();
      intersection = candidate;
    }
    if (!intersection)
      return failure();
    return *intersection;
  }

  FailureOr<IntegerBounds> analyzeValue(Value input) {
    Operation *operation = input.getDefiningOp();
    if (!operation || enclosingNodalModule(operation) != owner ||
        kindForType(input.getType()) != ConstantKind::Integer || operation->hasAttr("unit"))
      return failure();
    llvm::StringRef name = operation->getName().getStringRef();
    if (name == "nodal.const_literal" || name == "nodal.constant")
      return literal(operation->getAttr("value"), input.getType());
    if (name == "nodal.const_parameter_ref") {
      auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("parameter");
      Operation *declaration =
          reference ? findDirectSymbol(owner, reference.getValue(), "nodal.parameter") : nullptr;
      auto type = declaration ? declaration->getAttrOfType<TypeAttr>("type") : TypeAttr();
      if (!type || type.getValue() != input.getType())
        return failure();
      return parameter(declaration);
    }
    if (name != "nodal.const_expr")
      return failure();
    llvm::StringRef operationName = textAttr(operation, "operator_name");
    unsigned arity = operationName == "neg" ? 1 : 2;
    if (!oneOf(operationName, {"add", "sub", "mul", "div", "mod", "neg"}) ||
        operation->getNumOperands() != arity)
      return failure();
    llvm::SmallVector<IntegerBounds, 2> operands;
    for (Value operand : operation->getOperands()) {
      if (operand.getType() != input.getType())
        return failure();
      auto interval = value(operand);
      if (failed(interval))
        return failure();
      operands.push_back(*interval);
    }
    const IntegerBounds left = operands[0];
    IntegerBounds result{};
    if (operationName == "neg") {
      auto lower = narrowInteger(-wideInteger(left.upper));
      auto upper = narrowInteger(-wideInteger(left.lower));
      if (failed(lower) || failed(upper))
        return failure();
      result = {*lower, *upper};
    } else {
      const IntegerBounds right = operands[1];
      if (operationName == "div" || operationName == "mod") {
        if ((right.lower <= 0 && right.upper >= 0) ||
            (left.lower == std::numeric_limits<int64_t>::min() && right.lower <= -1 &&
             right.upper >= -1))
          return failure();
      }
      if (operationName == "mod") {
        // C/Verilog integer remainder follows the dividend's sign. An outer
        // magnitude bound is sound even when the divisor itself varies.
        llvm::APInt lowerMagnitude = wideInteger(right.lower).abs();
        llvm::APInt upperMagnitude = wideInteger(right.upper).abs();
        llvm::APInt magnitude =
            lowerMagnitude.ugt(upperMagnitude) ? lowerMagnitude : upperMagnitude;
        auto maximum = narrowInteger(magnitude - llvm::APInt(128, 1));
        if (failed(maximum))
          return failure();
        result = {left.lower < 0 ? std::max(left.lower, -*maximum) : 0,
                  left.upper > 0 ? std::min(left.upper, *maximum) : 0};
      } else if (operationName == "sub" && operation->getOperand(0) == operation->getOperand(1)) {
        result = {0, 0};
      } else {
        bool first = true;
        for (int64_t a : {left.lower, left.upper}) {
          for (int64_t b : {right.lower, right.upper}) {
            llvm::APInt lhs = wideInteger(a);
            llvm::APInt rhs = wideInteger(b);
            llvm::APInt computed = operationName == "add"   ? lhs + rhs
                                   : operationName == "sub" ? lhs - rhs
                                   : operationName == "mul" ? lhs * rhs
                                                            : lhs.sdiv(rhs);
            auto endpoint = narrowInteger(computed);
            if (failed(endpoint))
              return failure();
            if (first)
              result = {*endpoint, *endpoint};
            else {
              result.lower = std::min(result.lower, *endpoint);
              result.upper = std::max(result.upper, *endpoint);
            }
            first = false;
          }
        }
      }
    }
    if (!boundsFitType(result, input.getType()))
      return failure();
    return result;
  }

  Operation *owner;
  llvm::DenseMap<Value, std::optional<IntegerBounds>> values;
  llvm::DenseMap<Operation *, std::optional<IntegerBounds>> parameters;
  llvm::DenseSet<Value> activeValues;
  llvm::DenseSet<Operation *> activeParameters;
};

// Count in mathematical unsigned distance, not signed subtraction: the ordered
// span of two int64_t endpoints fits uint64_t even when it exceeds INT64_MAX.
// Subtract before dividing so rounding up never overflows at UINT64_MAX.
std::optional<uint64_t> positiveGenerateMaximumCount(int64_t lower, int64_t upper,
                                                     int64_t minimumStep, bool identicalBounds) {
  if (minimumStep <= 0)
    return std::nullopt;
  if (identicalBounds || upper <= lower)
    return uint64_t{0};
  const uint64_t distance = static_cast<uint64_t>(upper) - static_cast<uint64_t>(lower);
  return uint64_t{1} + (distance - uint64_t{1}) / static_cast<uint64_t>(minimumStep);
}

bool hasGeneratedCountContract(Operation *operation) {
  auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata");
  return operation->hasAttr("region_id") || operation->hasAttr("induction_path") ||
         (metadata && (metadata.get("maximum_trip_count") || metadata.get("declared_maximum")));
}

LogicalResult verifyGeneratedCountContract(Operation *operation, IntegerBounds lower,
                                           IntegerBounds upper, IntegerBounds step,
                                           bool identicalBounds) {
  auto region = operation->getAttrOfType<StringAttr>("region_id");
  auto induction = operation->getAttrOfType<StringAttr>("induction_path");
  auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata");
  if (!region || !induction || !metadata || region.getValue().trim().empty() ||
      induction.getValue().trim().empty())
    return operation->emitOpError(
        "NODAL-ITERATION-043-001: captured generate count requires its region, induction, and "
        "metadata contract");

  // This is the public positive-step, half-open profile. Legacy native
  // directional loops without this capture contract retain their old rules.
  auto expected =
      positiveGenerateMaximumCount(lower.lower, upper.upper, step.lower, identicalBounds);
  if (!expected)
    return operation->emitOpError(
        "NODAL-ITERATION-043-002: captured hdlRange step must stay positive");
  const uint64_t limit = static_cast<uint64_t>(std::numeric_limits<int32_t>::max());
  if (*expected > limit)
    return operation->emitOpError(
        "NODAL-ITERATION-043-001: captured generate count exceeds 32-bit loop metadata");

  // The bridge emits signless i64 metadata containing a nonnegative Int.
  // Reject malformed or narrowed attributes rather than trusting getInt().
  auto readCount = [&](llvm::StringRef name) -> std::optional<uint64_t> {
    auto value = llvm::dyn_cast_or_null<IntegerAttr>(metadata.get(name));
    auto type = llvm::dyn_cast_or_null<IntegerType>(value ? value.getType() : Type());
    if (!type || !type.isSignless() || type.getWidth() != 64)
      return std::nullopt;
    const int64_t number = value.getInt();
    if (number < 0 || static_cast<uint64_t>(number) > limit)
      return std::nullopt;
    return static_cast<uint64_t>(number);
  };

  auto retained = readCount("maximum_trip_count");
  if (!retained || *retained != *expected)
    return operation->emitOpError(
        "NODAL-ITERATION-043-001: maximum_trip_count must equal the independently proven "
        "generate envelope, not a default-value count");
  if (metadata.get("declared_maximum")) {
    auto declared = readCount("declared_maximum");
    if (!declared || *expected > *declared)
      return operation->emitOpError(
          "NODAL-ITERATION-043-001: declared_maximum must contain the whole generate envelope");
  }
  return success();
}

LogicalResult verifyGeneratedIntegerBounds(Operation *owner) {
  IntegerBoundsAnalysis analysis(owner);
  LogicalResult result = success();
  owner->walk([&](nodal::GenerateOp generated) {
    Operation *operation = generated.getOperation();
    if (failed(result) || enclosingNodalModule(operation) != owner)
      return;
    bool symbolic = false;
    for (llvm::StringRef name :
         {llvm::StringRef("lower"), llvm::StringRef("upper"), llvm::StringRef("step")}) {
      Attribute attribute = operation->getAttr(name);
      if (auto reference = llvm::dyn_cast_or_null<FlatSymbolRefAttr>(attribute)) {
        symbolic = true;
        Operation *parameter = findDirectSymbol(owner, reference.getValue(), "nodal.parameter");
        if (!parameter || !nodal::isStructuralParameter(parameter)) {
          result = operation->emitOpError(
              "NODAL-PARAMETER-STRUCTURAL-001: symbolic generate bounds require structural "
              "parameters");
          return;
        }
      } else {
        auto integer = llvm::dyn_cast_or_null<IntegerAttr>(attribute);
        if (!integer || failed(integerBoundLiteral(integer))) {
          result = operation->emitOpError(
              "NODAL-ITERATION-043-001: generate bounds require representable integer literals "
              "or parameter references");
          return;
        }
      }
    }
    // Legacy concrete loops keep their existing directional contract. Captured
    // hdlRange loops, including literal and empty ones, require a count proof.
    const bool capturedCount = hasGeneratedCountContract(operation);
    if (!symbolic && !capturedCount)
      return;
    auto lower = analysis.bound(operation->getAttr("lower"));
    auto upper = analysis.bound(operation->getAttr("upper"));
    auto step = analysis.bound(operation->getAttr("step"));
    if (failed(lower) || failed(upper) || failed(step)) {
      result = operation->emitOpError(
          "NODAL-ITERATION-043-001: cannot prove finite integer generate bounds without "
          "substituting parameter defaults");
      return;
    }
    if (step->lower <= 0 && step->upper >= 0) {
      result = operation->emitOpError(
          "NODAL-ITERATION-043-002: symbolic generate step can include zero or change sign");
      return;
    }
    bool identicalBounds = operation->getAttr("lower") == operation->getAttr("upper");
    if (!identicalBounds && ((step->lower > 0 && lower->upper > upper->lower) ||
                             (step->upper < 0 && lower->lower < upper->upper))) {
      result = operation->emitOpError(
          "NODAL-ITERATION-043-003: generate direction is not proven for every legal parameter "
          "setting");
      return;
    }
    if (capturedCount)
      result = verifyGeneratedCountContract(operation, *lower, *upper, *step, identicalBounds);
  });
  return result;
}

FailureOr<std::string> renderValue(Value value, llvm::DenseSet<Operation *> &visited) {
  Operation *operation = value.getDefiningOp();
  if (!operation || !visited.insert(operation).second)
    return failure();
  llvm::StringRef name = operation->getName().getStringRef();
  if (name == "nodal.const_literal") {
    auto spelling = operation->getAttrOfType<StringAttr>("spelling");
    visited.erase(operation);
    if (!spelling)
      return failure();
    return spelling.getValue().str();
  }
  if (name == "nodal.const_parameter_ref") {
    auto parameter = operation->getAttrOfType<FlatSymbolRefAttr>("parameter");
    visited.erase(operation);
    if (!parameter)
      return failure();
    return parameter.getValue().str();
  }
  if (name == "nodal.const_expr") {
    llvm::StringRef operatorName = textAttr(operation, "operator_name");
    llvm::SmallVector<std::string, 2> operands;
    for (Value operand : operation->getOperands()) {
      auto rendered = renderValue(operand, visited);
      if (failed(rendered)) {
        visited.erase(operation);
        return failure();
      }
      operands.push_back(*rendered);
    }
    std::string result;
    if (operatorName == "neg")
      result = "(-" + operands[0] + ")";
    else if (operatorName == "not")
      result = "(!" + operands[0] + ")";
    else {
      llvm::StringRef spelling = operatorName == "add"   ? "+"
                                 : operatorName == "sub" ? "-"
                                 : operatorName == "mul" ? "*"
                                 : operatorName == "div" ? "/"
                                 : operatorName == "mod" ? "%"
                                 : operatorName == "gt"  ? ">"
                                 : operatorName == "ge"  ? ">="
                                 : operatorName == "lt"  ? "<"
                                 : operatorName == "le"  ? "<="
                                 : operatorName == "and" ? "&&"
                                 : operatorName == "or"  ? "||"
                                                         : "";
      if (spelling.empty()) {
        visited.erase(operation);
        return failure();
      }
      result = "(" + operands[0] + " " + spelling.str() + " " + operands[1] + ")";
    }
    visited.erase(operation);
    return result;
  }
  if (name == "nodal.constant") {
    Attribute valueAttr = operation->getAttr("value");
    if (auto integer = llvm::dyn_cast<IntegerAttr>(valueAttr)) {
      llvm::SmallString<64> rendered;
      integer.getValue().toString(rendered, 10, integerTypeIsSigned(value.getType()));
      visited.erase(operation);
      return rendered.str().str();
    }
    if (auto boolean = llvm::dyn_cast<BoolAttr>(valueAttr)) {
      visited.erase(operation);
      return boolean.getValue() ? std::string("1") : std::string("0");
    }
  }
  visited.erase(operation);
  return failure();
}

} // namespace

FailureOr<nodal::ParameterIntegerBounds> nodal::inferParameterIntegerBounds(Value value) {
  if (!value)
    return failure();
  Operation *operation = value.getDefiningOp();
  Operation *owner = enclosingNodalModule(operation);
  if (!owner)
    return failure();
  return IntegerBoundsAnalysis(owner).value(value);
}

llvm::StringRef nodal::getParameterKind(Operation *parameter) {
  llvm::StringRef explicitKind = textAttr(parameter, "parameter_kind");
  if (!explicitKind.empty())
    return explicitKind;
  auto type = parameter ? parameter->getAttrOfType<TypeAttr>("type") : TypeAttr();
  return type ? inferredKind(type.getValue()) : llvm::StringRef();
}

llvm::StringRef nodal::getParameterClassification(Operation *parameter) {
  llvm::StringRef explicitClass = textAttr(parameter, "classification");
  return explicitClass.empty() ? llvm::StringRef("ordinary") : explicitClass;
}

bool nodal::isStructuralParameter(Operation *parameter) {
  return textAttr(parameter, "classification") == "structural";
}

std::string nodal::getParameterUnitSymbol(Operation *parameter) {
  Operation *unit = resolveUnit(parameter, parameter->getAttrOfType<FlatSymbolRefAttr>("unit"));
  return unit ? textAttr(unit, "symbol").str() : std::string();
}

std::string nodal::getParameterUnitNativeSuffix(Operation *parameter) {
  Operation *unit = resolveUnit(parameter, parameter->getAttrOfType<FlatSymbolRefAttr>("unit"));
  return unit ? textAttr(unit, "native_suffix").str() : std::string();
}

LogicalResult nodal::verifyParameterDeclaration(Operation *operation) {
  llvm::StringRef variability = textAttr(operation, "variability");
  if (!oneOf(variability, {"fixed", "symbolic"}))
    return operation->emitOpError()
           << "NODAL-PARAMETER-KIND-001: unsupported variability '" << variability << "'";
  auto type = operation->getAttrOfType<TypeAttr>("type");
  Attribute defaultValue = operation->getAttr("default_value");
  if (!type || !defaultValue || !attributeFits(defaultValue, type.getValue()))
    return operation->emitOpError(
        "NODAL-PARAMETER-DEFAULT-001: default value is incompatible with parameter type");

  llvm::StringRef kind = getParameterKind(operation);
  if (!oneOf(kind, {"real", "integer", "boolean"}) || kind != inferredKind(type.getValue()))
    return operation->emitOpError(
        "NODAL-PARAMETER-KIND-001: parameter kind and type are incompatible");
  llvm::StringRef classification = getParameterClassification(operation);
  if (!oneOf(classification, {"ordinary", "structural"}))
    return operation->emitOpError(
        "NODAL-PARAMETER-CLASS-001: classification must be ordinary or structural");
  if (classification == "structural" &&
      (variability != "symbolic" || (kind != "integer" && kind != "boolean")))
    return operation->emitOpError("NODAL-PARAMETER-STRUCTURAL-001: structural parameters must be "
                                  "symbolic integer or boolean values");

  if (auto unitReference = operation->getAttrOfType<FlatSymbolRefAttr>("unit")) {
    Operation *unit = resolveUnit(operation, unitReference);
    if (!unit || kind != "real")
      return operation->emitOpError(
          "NODAL-PARAMETER-UNIT-001: only real parameters may reference a declared unit");
  }
  return success();
}

LogicalResult nodal::UnitOp::verify() {
  if (!getOperation()->getParentOp() || !llvm::isa<mlir::ModuleOp>(getOperation()->getParentOp()) ||
      !canonicalText(symbolName(getOperation())) ||
      !canonicalText(textAttr(getOperation(), "symbol")) ||
      !canonicalText(textAttr(getOperation(), "dimension")))
    return emitOpError(
        "NODAL-UNIT-DECL-001: unit must be a top-level canonical symbol with symbol and dimension");
  auto scale = getOperation()->getAttrOfType<FloatAttr>("scale");
  if (!scale || !std::isfinite(scale.getValueAsDouble()) || scale.getValueAsDouble() <= 0.0)
    return emitOpError("NODAL-UNIT-SCALE-001: unit scale must be positive and finite");
  llvm::StringRef suffix = textAttr(getOperation(), "native_suffix");
  std::optional<double> expectedScale = suffixScale(suffix);
  if (!expectedScale)
    return emitOpError("NODAL-UNIT-SUFFIX-001: unsupported native Verilog scale suffix");
  if (!closeEnough(scale.getValueAsDouble(), *expectedScale))
    return emitOpError(
        "NODAL-UNIT-SUFFIX-001: unit scale must exactly correspond to its native suffix");
  return success();
}

LogicalResult nodal::ConstLiteralOp::verify() {
  if (getOperation()->getNumResults() != 1 ||
      !attributeFits(getOperation()->getAttr("value"), getOperation()->getResult(0).getType()))
    return emitOpError(
        "NODAL-CONSTANT-LITERAL-001: literal value is incompatible with result type");
  Operation *unit =
      resolveUnit(getOperation(), getOperation()->getAttrOfType<FlatSymbolRefAttr>("unit"));
  if (getOperation()->getAttrOfType<FlatSymbolRefAttr>("unit") &&
      (!unit || !getOperation()->getResult(0).getType().isF64()))
    return emitOpError("NODAL-CONSTANT-LITERAL-001: unit-aware literals must be real and reference "
                       "a declared unit");
  llvm::StringRef numeric;
  auto spelling = getOperation()->getAttrOfType<StringAttr>("spelling");
  if (!spelling ||
      !splitSpelling(spelling.getValue(), unit ? unitSuffix(unit) : llvm::StringRef(), numeric))
    return emitOpError(
        "NODAL-CONSTANT-LITERAL-001: literal spelling is not a canonical native numeric spelling");

  ConstantKind kind = kindForType(getOperation()->getResult(0).getType());
  Attribute value = getOperation()->getAttr("value");
  if (kind == ConstantKind::Real) {
    if (!validNativeDecimal(numeric, false))
      return emitOpError("NODAL-CONSTANT-LITERAL-001: real spelling is not native decimal syntax");
    auto parsed = parseReal(numeric);
    auto real = llvm::dyn_cast<FloatAttr>(value);
    if (!parsed || !real || !closeEnough(*parsed, real.getValueAsDouble()))
      return emitOpError(
          "NODAL-CONSTANT-LITERAL-001: real spelling does not reproduce the authored magnitude");
  } else if (kind == ConstantKind::Integer) {
    if (!validNativeDecimal(numeric, true))
      return emitOpError(
          "NODAL-CONSTANT-LITERAL-001: integer spelling is not native decimal syntax");
    auto parsed = parseInteger(numeric);
    auto integer = llvm::dyn_cast<IntegerAttr>(value);
    if (!parsed || !integer || *parsed != integer.getInt())
      return emitOpError(
          "NODAL-CONSTANT-LITERAL-001: integer spelling does not reproduce the authored value");
  } else if (kind == ConstantKind::Boolean) {
    if (numeric != "0" && numeric != "1")
      return emitOpError("NODAL-CONSTANT-LITERAL-001: Boolean native spelling must be 0 or 1");
  } else {
    return emitOpError("NODAL-CONSTANT-LITERAL-001: unsupported literal result type");
  }
  return success();
}

LogicalResult nodal::ConstParameterRefOp::verify() {
  auto reference = getOperation()->getAttrOfType<FlatSymbolRefAttr>("parameter");
  Operation *module = enclosingNodalModule(getOperation());
  Operation *parameter =
      reference ? findDirectSymbol(module, reference.getValue(), "nodal.parameter") : nullptr;
  auto type = parameter ? parameter->getAttrOfType<TypeAttr>("type") : TypeAttr();
  if (!parameter || !type || getOperation()->getNumResults() != 1 ||
      getOperation()->getResult(0).getType() != type.getValue())
    return emitOpError(
        "NODAL-CONSTANT-EXPR-001: parameter reference is missing or has the wrong result type");
  return success();
}

LogicalResult nodal::ConstExprOp::verify() {
  llvm::StringRef name = textAttr(getOperation(), "operator_name");
  if (!oneOf(name, {"add", "sub", "mul", "div", "mod", "neg", "not", "gt", "ge", "lt", "le", "and",
                    "or"}))
    return emitOpError("NODAL-CONSTANT-EXPR-001: unsupported constant-expression operator");
  const unsigned expected = name == "neg" || name == "not" ? 1 : 2;
  if (getOperation()->getNumOperands() != expected || getOperation()->getNumResults() != 1)
    return emitOpError("NODAL-CONSTANT-EXPR-001: operator arity is invalid");
  ConstantKind resultKind = kindForType(getOperation()->getResult(0).getType());
  if (resultKind == ConstantKind::Invalid)
    return emitOpError("NODAL-CONSTANT-EXPR-001: result type is not a supported scalar kind");
  const bool comparison = oneOf(name, {"gt", "ge", "lt", "le"});
  const bool logic = oneOf(name, {"not", "and", "or"});
  const Type firstOperandType = getOperation()->getOperand(0).getType();
  const ConstantKind firstOperand = kindForType(firstOperandType);
  if (comparison) {
    if (resultKind != ConstantKind::Boolean ||
        (firstOperand != ConstantKind::Real && firstOperand != ConstantKind::Integer))
      return emitOpError(
          "NODAL-CONSTANT-EXPR-001: comparison requires like numeric operands and a Boolean "
          "result");
    for (Value operand : getOperation()->getOperands())
      if (operand.getType() != firstOperandType)
        return emitOpError(
            "NODAL-CONSTANT-EXPR-001: comparison operands must have identical numeric types");
  } else if (logic) {
    if (resultKind != ConstantKind::Boolean)
      return emitOpError("NODAL-CONSTANT-EXPR-001: logical operators require Boolean values");
    for (Value operand : getOperation()->getOperands())
      if (operand.getType() != getOperation()->getResult(0).getType())
        return emitOpError("NODAL-CONSTANT-EXPR-001: logical operands must be Boolean");
  } else {
    for (Value operand : getOperation()->getOperands())
      if (kindForType(operand.getType()) != resultKind)
        return emitOpError(
            "NODAL-CONSTANT-EXPR-001: implicit constant-expression promotion is not supported");
    if (resultKind == ConstantKind::Boolean)
      return emitOpError("NODAL-CONSTANT-EXPR-001: arithmetic operators require numeric values");
  }
  if (name == "mod" && resultKind != ConstantKind::Integer)
    return emitOpError("NODAL-CONSTANT-EXPR-001: mod requires integer operands");
  return success();
}

LogicalResult nodal::ParameterValueOp::verify() {
  auto reference = getOperation()->getAttrOfType<FlatSymbolRefAttr>("parameter");
  Operation *parameter = reference ? findDirectSymbol(enclosingNodalModule(getOperation()),
                                                      reference.getValue(), "nodal.parameter")
                                   : nullptr;
  auto type = parameter ? parameter->getAttrOfType<TypeAttr>("type") : TypeAttr();
  if (!parameter || !type || getOperation()->getNumOperands() != 1 ||
      getOperation()->getOperand(0).getType() != type.getValue())
    return emitOpError(
        "NODAL-PARAMETER-DEFAULT-001: parameter default expression has the wrong target or type");
  return success();
}

LogicalResult nodal::ParameterConstraintOp::verify() {
  auto reference = getOperation()->getAttrOfType<FlatSymbolRefAttr>("parameter");
  Operation *parameter = reference ? findDirectSymbol(enclosingNodalModule(getOperation()),
                                                      reference.getValue(), "nodal.parameter")
                                   : nullptr;
  llvm::StringRef kind = textAttr(getOperation(), "constraint_kind");
  const unsigned expected = kind == "range" ? 2 : kind == "exclude" ? 1 : 0;
  if (!parameter || expected == 0 || getOperation()->getNumOperands() != expected)
    return emitOpError(
        "NODAL-PARAMETER-CONSTRAINT-001: constraint target, kind, or arity is invalid");
  auto type = parameter->getAttrOfType<TypeAttr>("type");
  for (Value value : getOperation()->getOperands()) {
    if (!type || value.getType() != type.getValue())
      return emitOpError(
          "NODAL-PARAMETER-CONSTRAINT-001: constraint values must match parameter type");
  }
  return success();
}

LogicalResult nodal::ParameterOverrideOp::verify() {
  if (!getOperation()->getAttrOfType<FlatSymbolRefAttr>("instance") ||
      !getOperation()->getAttrOfType<FlatSymbolRefAttr>("parameter") ||
      getOperation()->getNumOperands() != 1)
    return emitOpError(
        "NODAL-PARAMETER-OVERRIDE-001: override requires instance, parameter, and value");
  return success();
}

LogicalResult nodal::ParameterEnvelopeOp::verify() {
  auto reference = getOperation()->getAttrOfType<FlatSymbolRefAttr>("parameter");
  Operation *parameter = reference ? findDirectSymbol(enclosingNodalModule(getOperation()),
                                                      reference.getValue(), "nodal.parameter")
                                   : nullptr;
  auto effects = getOperation()->getAttrOfType<ArrayAttr>("effects");
  llvm::StringRef policy = textAttr(getOperation(), "policy");
  if (!parameter || !effects || !oneOf(policy, {"fixed_topology", "static_generate"}))
    return emitOpError(
        "NODAL-PARAMETER-ENVELOPE-001: envelope target, effects, or policy is invalid");
  for (Attribute effect : effects) {
    auto text = llvm::dyn_cast<StringAttr>(effect);
    if (!text ||
        !oneOf(text.getValue(), {"topology", "component_count", "equation_count", "shape", "rank"}))
      return emitOpError(
          "NODAL-PARAMETER-ENVELOPE-001: envelope contains an unsupported structural effect");
  }
  return success();
}

LogicalResult nodal::DynamicValueOp::verify() {
  if (getOperation()->getNumOperands() != 1 || getOperation()->getNumResults() != 1 ||
      getOperation()->getOperand(0).getType() != getOperation()->getResult(0).getType() ||
      !canonicalText(textAttr(getOperation(), "origin")))
    return emitOpError("NODAL-DYNAMIC-VALUE-001: dynamic marker requires equal input/result types "
                       "and canonical origin");
  return success();
}

LogicalResult nodal::verifyParameterModel(mlir::ModuleOp module) {
  llvm::StringMap<Operation *> definitions;
  for (Operation &operation : module.getBody()->getOperations()) {
    if (isNamed(&operation, "nodal.module"))
      definitions[symbolName(&operation)] = &operation;
  }

  auto rootReference = module->getAttrOfType<FlatSymbolRefAttr>("nodal.root.module");
  auto rootBindings = module->getAttrOfType<DictionaryAttr>("nodal.root.parameter_bindings");
  Operation *rootModule = rootReference ? definitions.lookup(rootReference.getValue()) : nullptr;
  if (rootModule && rootBindings) {
    for (NamedAttribute binding : rootBindings) {
      Operation *parameter =
          findDirectSymbol(rootModule, binding.getName().getValue(), "nodal.parameter");
      // The staged verifier diagnoses unresolved names and malformed root
      // metadata. Enforce declaration-level contracts here once the target is
      // known, at the same trust boundary as instance bindings.
      if (!parameter)
        continue;
      if (textAttr(parameter, "variability") == "fixed")
        return rootModule->emitOpError(
            "NODAL-PARAMETER-OVERRIDE-001: fixed parameter cannot be bound at the root");
      auto type = parameter->getAttrOfType<TypeAttr>("type");
      FailureOr<EvaluatedConstant> raw = failure();
      if (type)
        raw = constantFromAttribute(binding.getValue(), type.getValue());
      // Passes.cpp retains the existing NODAL-VERIFY-PARAMETER-008
      // diagnostic for a missing or storage-incompatible attribute.
      if (failed(raw))
        continue;
      auto normalized = normalizeForParameter(*raw, parameter);
      if (failed(normalized) ||
          failed(
              checkConstraints(parameter, *normalized, "NODAL-PARAMETER-OVERRIDE-001", rootModule)))
        return rootModule->emitOpError(
            "NODAL-PARAMETER-OVERRIDE-001: root binding violates parameter contract");
    }
  }

  for (const auto &entry : definitions) {
    Operation *owner = entry.getValue();
    Block *body = moduleBody(owner);
    if (!body)
      continue;

    for (Operation &operation : *body) {
      if (isNamed(&operation, "nodal.dynamic_value")) {
        for (Operation *user : operation.getResult(0).getUsers()) {
          llvm::StringRef name = user->getName().getStringRef();
          if (name == "nodal.const_expr" || name == "nodal.parameter_value" ||
              name == "nodal.parameter_constraint" || name == "nodal.parameter_override")
            return user->emitOpError(
                "NODAL-DYNAMIC-VALUE-001: dynamic values cannot enter constant evaluation");
        }
      }
    }

    for (Operation &operation : *body) {
      if (!isNamed(&operation, "nodal.parameter"))
        continue;
      if (hasDuplicateParameterValue(owner, symbolName(&operation)))
        return operation.emitOpError(
            "NODAL-PARAMETER-DEFAULT-001: parameter has multiple default expressions");
      llvm::DenseSet<Operation *> stack;
      auto evaluated = evaluateParameterDefault(&operation, stack);
      if (failed(evaluated))
        return operation.emitOpError(
            "NODAL-CONSTANT-CYCLE-001: parameter default is cyclic or cannot be folded");
      auto normalized = normalizeForParameter(*evaluated, &operation);
      if (failed(normalized))
        return operation.emitOpError(
            "NODAL-PARAMETER-UNIT-001: default expression has incompatible type or dimension");
      if (!attributeMatches(operation.getAttr("default_value"), *normalized))
        return operation.emitOpError(
            "NODAL-PARAMETER-DEFAULT-001: folded default does not match canonical default_value");
      if (failed(checkConstraints(&operation, *normalized, "NODAL-PARAMETER-CONSTRAINT-001",
                                  &operation)))
        return failure();

      bool duplicateEnvelope = false;
      Operation *envelope = findParameterEnvelope(owner, symbolName(&operation), duplicateEnvelope);
      if (duplicateEnvelope)
        return operation.emitOpError(
            "NODAL-PARAMETER-ENVELOPE-001: parameter has multiple structural envelopes");
      if (isStructuralParameter(&operation)) {
        if (!envelope || textAttr(envelope, "policy") != "static_generate" ||
            envelope->getAttrOfType<ArrayAttr>("effects").empty() || !hasBoundedRange(&operation))
          return operation.emitOpError("NODAL-PARAMETER-ENVELOPE-001: structural parameter "
                                       "requires bounded static_generate effects");
      } else if (envelope && (textAttr(envelope, "policy") != "fixed_topology" ||
                              !envelope->getAttrOfType<ArrayAttr>("effects").empty())) {
        return operation.emitOpError(
            "NODAL-PARAMETER-ENVELOPE-001: ordinary parameter cannot change structural identity");
      }
    }

    if (failed(verifyGeneratedIntegerBounds(owner)))
      return failure();

    for (Operation &operation : *body) {
      if (isNamed(&operation, "nodal.generate")) {
        for (llvm::StringRef name :
             {llvm::StringRef("lower"), llvm::StringRef("upper"), llvm::StringRef("step")}) {
          auto reference = operation.getAttrOfType<FlatSymbolRefAttr>(name);
          if (!reference)
            continue;
          Operation *parameter = findDirectSymbol(owner, reference.getValue(), "nodal.parameter");
          if (!parameter || !isStructuralParameter(parameter))
            return operation.emitOpError("NODAL-PARAMETER-STRUCTURAL-001: symbolic generate bounds "
                                         "require structural parameters");
        }
      }

      if (!isNamed(&operation, "nodal.instance"))
        continue;
      auto targetReference = operation.getAttrOfType<FlatSymbolRefAttr>("module");
      Operation *target =
          targetReference ? definitions.lookup(targetReference.getValue()) : nullptr;
      if (!target)
        continue;
      auto bindings = operation.getAttrOfType<DictionaryAttr>("parameter_bindings");
      if (!bindings)
        continue;
      for (NamedAttribute binding : bindings) {
        Operation *parameter =
            findDirectSymbol(target, binding.getName().getValue(), "nodal.parameter");
        if (!parameter)
          continue;
        if (textAttr(parameter, "variability") == "fixed")
          return operation.emitOpError(
              "NODAL-PARAMETER-OVERRIDE-001: fixed parameter cannot be overridden");
        auto type = parameter->getAttrOfType<TypeAttr>("type");
        FailureOr<EvaluatedConstant> raw = failure();
        if (type)
          raw = constantFromAttribute(binding.getValue(), type.getValue());
        FailureOr<EvaluatedConstant> normalized = failure();
        if (!failed(raw))
          normalized = normalizeForParameter(*raw, parameter);
        if (failed(normalized) ||
            failed(checkConstraints(parameter, *normalized, "NODAL-PARAMETER-OVERRIDE-001",
                                    &operation)))
          return operation.emitOpError(
              "NODAL-PARAMETER-OVERRIDE-001: bound override violates parameter contract");
      }
    }

    for (Operation &operation : *body) {
      if (!isNamed(&operation, "nodal.parameter_override"))
        continue;
      auto instanceReference = operation.getAttrOfType<FlatSymbolRefAttr>("instance");
      auto parameterReference = operation.getAttrOfType<FlatSymbolRefAttr>("parameter");
      Operation *instance =
          instanceReference
              ? findDirectSymbol(owner, instanceReference.getValue(), "nodal.instance")
              : nullptr;
      auto targetReference =
          instance ? instance->getAttrOfType<FlatSymbolRefAttr>("module") : FlatSymbolRefAttr();
      Operation *target =
          targetReference ? definitions.lookup(targetReference.getValue()) : nullptr;
      Operation *parameter =
          target && parameterReference
              ? findDirectSymbol(target, parameterReference.getValue(), "nodal.parameter")
              : nullptr;
      if (!parameter)
        return operation.emitOpError(
            "NODAL-PARAMETER-OVERRIDE-001: override target does not resolve");
      if (textAttr(parameter, "variability") == "fixed")
        return operation.emitOpError(
            "NODAL-PARAMETER-OVERRIDE-001: fixed parameter cannot be overridden");
      llvm::DenseSet<Operation *> stack;
      auto evaluated = evaluateValue(operation.getOperand(0), &operation, stack);
      FailureOr<EvaluatedConstant> normalized = failure();
      if (!failed(evaluated))
        normalized = normalizeForParameter(*evaluated, parameter);
      if (failed(normalized) ||
          failed(
              checkConstraints(parameter, *normalized, "NODAL-PARAMETER-OVERRIDE-001", &operation)))
        return operation.emitOpError(
            "NODAL-PARAMETER-OVERRIDE-001: explicit override violates parameter contract");
      auto bindings = instance->getAttrOfType<DictionaryAttr>("parameter_bindings");
      if (bindings) {
        Attribute binding = bindings.get(parameterReference.getValue());
        if (binding && !attributeMatches(binding, *normalized))
          return operation.emitOpError(
              "NODAL-PARAMETER-OVERRIDE-001: lossless override disagrees with canonical binding");
      }
    }
  }
  return success();
}

FailureOr<std::string> nodal::renderParameterConstantExpression(Value value) {
  llvm::DenseSet<Operation *> visited;
  return renderValue(value, visited);
}

FailureOr<std::string> nodal::renderParameterConstantExpression(Value value,
                                                                Operation *targetParameter) {
  auto rendered = renderParameterConstantExpression(value);
  if (failed(rendered) || !targetParameter)
    return rendered;

  Operation *unit =
      resolveUnit(targetParameter, targetParameter->getAttrOfType<FlatSymbolRefAttr>("unit"));
  if (!unit || getParameterKind(targetParameter) != "real")
    return rendered;

  llvm::DenseSet<Operation *> stack;
  auto evaluated = evaluateValue(value, targetParameter, stack);
  if (failed(evaluated))
    return failure();
  if (!evaluated->dimension.empty())
    return rendered;

  llvm::StringRef suffix = unitSuffix(unit);
  if (suffix.empty())
    return rendered;

  Operation *definition = value.getDefiningOp();
  if (definition && isNamed(definition, "nodal.const_literal") &&
      !definition->getAttrOfType<FlatSymbolRefAttr>("unit"))
    return *rendered + suffix.str();

  return (llvm::Twine("(") + *rendered + " * 1" + suffix + ")").str();
}
