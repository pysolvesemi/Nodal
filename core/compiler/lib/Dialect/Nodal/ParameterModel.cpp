#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinTypes.h"
#include "mlir/IR/Builders.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/IR/Verifier.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/StructuralIfModel.h"

#include "llvm/ADT/APInt.h"
#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/STLFunctionalExtras.h"
#include "llvm/ADT/ScopeExit.h"
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
#include <functional>
#include <initializer_list>
#include <limits>
#include <optional>
#include <string>
#include <system_error>

using namespace mlir;

namespace {

// One invocation-local budget spans generated domains and structural Integer queries.
// Re-entry must not reset work/depth limits; no state survives the public query.
struct GeneratedQueryContext {
  unsigned work = 0;
  unsigned maximumWork = 65536;
  bool sharedWork = false;
  llvm::DenseSet<Operation *> activeDomains;
  llvm::DenseMap<Operation *, nodal::GeneratedDomain> domains;
  bool charge() { return ++work <= maximumWork; }
};
FailureOr<nodal::GeneratedDomain> generatedDomain(Operation *, GeneratedQueryContext &);
FailureOr<nodal::StructuralIntegerFacts> structuralInteger(Value, Operation *,
                                                           GeneratedQueryContext &);

// Purpose controls dependency eligibility, never the Integer arithmetic engine.
enum class IntegerQueryPurpose { Structural, Behavioral, ConstructorBinding };
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

// These operators belong to the dimensionless static Integer DAG, never to
// runtime digital arithmetic. Keep arity shared by every independent query.
unsigned integerOperationArity(llvm::StringRef opcode) {
  if (opcode == "choose_zero")
    return 3;
  if (opcode == "neg" || opcode == "ceil_log2")
    return 1;
  return oneOf(opcode, {"add", "sub", "mul", "div", "mod", "max"}) ? 2 : 0;
}

bool requiresStaticI64(llvm::StringRef opcode) {
  return oneOf(opcode, {"max", "ceil_log2", "choose_zero"});
}

// The caller proves positivity first. x - 1 and every shift are representable,
// including x == INT64_MAX; the result is the bit width of the unsigned x - 1.
int64_t positiveCeilLog2(int64_t value) {
  uint64_t remaining = static_cast<uint64_t>(value - 1);
  int64_t result = 0;
  while (remaining) {
    remaining >>= 1;
    ++result;
  }
  return result;
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

Block *structuralBody(Operation *scope) {
  if (!scope || (!isNamed(scope, "nodal.module") && !isNamed(scope, "nodal.generate")) ||
      scope->getNumRegions() != 1 || !llvm::hasSingleElement(scope->getRegion(0)))
    return nullptr;
  return &scope->getRegion(0).front();
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
  if (llvm::isa<nodal::UIntType, nodal::SIntType, nodal::ParametricIntegerType, nodal::EnumType>(
          type))
    return ConstantKind::Integer;
  return ConstantKind::Invalid;
}

bool hasEnumScalarEdge(Operation *operation) {
  auto isEnum = [](Type type) { return llvm::isa<nodal::EnumType>(type); };
  return llvm::any_of(operation->getOperandTypes(), isEnum) ||
         llvm::any_of(operation->getResultTypes(), isEnum);
}

bool hasSymbolicDigitalEdge(Operation *operation) {
  auto symbolic = [](Type type) { return llvm::isa<nodal::ParametricIntegerType>(type); };
  return llvm::any_of(operation->getOperandTypes(), symbolic) ||
         llvm::any_of(operation->getResultTypes(), symbolic);
}

bool integerTypeIsSigned(Type type) {
  if (llvm::isa<nodal::SIntType>(type))
    return true;
  if (auto symbolic = llvm::dyn_cast<nodal::ParametricIntegerType>(type))
    return symbolic.getKind() == "sint";
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

bool integerFits(IntegerAttr value, Type type, Operation *module = nullptr) {
  if (!value || !type)
    return false;
  if (nodal::isDigitalScalarType(type))
    return nodal::digitalIntegerAttributeFits(value, type, module);
  int64_t width = 0;
  bool isSigned = false;
  if (auto integer = llvm::dyn_cast<IntegerType>(type)) {
    width = integer.getWidth();
    isSigned = integer.isSigned();
  } else if (auto bits = llvm::dyn_cast<nodal::BitsType>(type)) {
    width = bits.getWidth();
  } else if (auto valueType = llvm::dyn_cast<nodal::UIntType>(type)) {
    width = valueType.getWidth();
  } else if (auto valueType = llvm::dyn_cast<nodal::SIntType>(type)) {
    width = valueType.getWidth();
    isSigned = true;
  } else if (auto valueType = llvm::dyn_cast<nodal::EnumType>(type)) {
    width = valueType.getWidth();
  } else {
    return false;
  }
  if (width <= 0)
    return false;
  auto carrier = llvm::dyn_cast<IntegerType>(value.getType());
  const bool unsignedCarrier = carrier && carrier.isUnsigned();
  const llvm::APInt &bits = value.getValue();
  if (isSigned)
    return unsignedCarrier ? static_cast<int64_t>(bits.getActiveBits()) < width
                           : static_cast<int64_t>(bits.getSignificantBits()) <= width;
  return (unsignedCarrier || !bits.isNegative()) &&
         static_cast<int64_t>(bits.getActiveBits()) <= width;
}

bool attributeFits(Attribute value, Type type, Operation *module = nullptr) {
  if (!value || !type)
    return false;
  if (auto typed = llvm::dyn_cast<TypedAttr>(value)) {
    if (typed.getType() == type)
      return true;
  }
  // BoolAttr also satisfies IntegerAttr; handle it before signed APInt fitting so
  // true remains valid for Boolean custom types such as !nodal.bits<1>.
  if (llvm::isa<BoolAttr>(value))
    return kindForType(type) == ConstantKind::Boolean;
  if (auto integer = llvm::dyn_cast<IntegerAttr>(value))
    return integerFits(integer, type, module);
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
  if (!value || !type)
    return failure();
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
    // A uiN carrier is a mathematical nonnegative magnitude even when a signed
    // result has room for its high bit; never sign-extend that carrier.
    auto carrier = llvm::dyn_cast<IntegerType>(integer.getType());
    // The language's mathematical Integer is signless i64, unlike packed
    // UInt/Bits and explicit unsigned carriers. Keep that provenance distinction.
    result.integerSigned = (integerTypeIsSigned(type) || type.isSignlessInteger(64)) &&
                           !(carrier && carrier.isUnsigned());
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

FailureOr<EvaluatedConstant>
evaluateExpression(Operation *operation,
                   llvm::function_ref<FailureOr<EvaluatedConstant>(Value)> evaluateOperand) {
  if (operation->getNumResults() != 1 || hasEnumScalarEdge(operation) ||
      hasSymbolicDigitalEdge(operation) ||
      llvm::any_of(operation->getOperands(), [&](Value operand) {
        return enclosingNodalModule(operand.getDefiningOp()) != enclosingNodalModule(operation);
      }))
    return failure();
  llvm::StringRef name = textAttr(operation, "operator_name");
  const unsigned count = operation->getNumOperands();
  const bool unary = name == "neg" || name == "not" || name == "ceil_log2";
  if (count != (name == "choose_zero" ? 3u : unary ? 1u : 2u))
    return failure();
  if (requiresStaticI64(name) &&
      (!operation->getResult(0).getType().isSignlessInteger(64) || operation->hasAttr("unit") ||
       llvm::any_of(operation->getOperands(), [&](Value operand) {
         return operand.getType() != operation->getResult(0).getType();
       })))
    return failure();

  auto lhs = evaluateOperand(operation->getOperand(0));
  if (failed(lhs))
    return failure();
  EvaluatedConstant result;
  result.kind = kindForType(operation->getResult(0).getType());

  if (name == "ceil_log2") {
    if (lhs->kind != ConstantKind::Integer || !lhs->integerIsNarrow || !lhs->dimension.empty() ||
        lhs->integerValue <= 0)
      return failure();
    result.integerValue = positiveCeilLog2(lhs->integerValue);
    return result;
  }
  if (name == "choose_zero") {
    // Eagerly validate both branch DAGs, even for a literal test. A selected
    // branch cannot mask division by zero, overflow, units or malformed input.
    auto whenZero = evaluateOperand(operation->getOperand(1));
    auto whenNonzero = evaluateOperand(operation->getOperand(2));
    if (failed(whenZero) || failed(whenNonzero))
      return failure();
    for (const auto *operand : {&*lhs, &*whenZero, &*whenNonzero})
      if (operand->kind != ConstantKind::Integer || !operand->integerIsNarrow ||
          !operand->dimension.empty())
        return failure();
    return lhs->integerValue == 0 ? *whenZero : *whenNonzero;
  }

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

  auto rhs = evaluateOperand(operation->getOperand(1));
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

  if (name == "max") {
    if (!operation->getResult(0).getType().isSignlessInteger(64) ||
        operation->getOperand(0).getType() != operation->getResult(0).getType() ||
        operation->getOperand(1).getType() != operation->getResult(0).getType() ||
        lhs->kind != ConstantKind::Integer || rhs->kind != ConstantKind::Integer ||
        !lhs->integerIsNarrow || !rhs->integerIsNarrow || !lhs->dimension.empty() ||
        !rhs->dimension.empty())
      return failure();
    result.integerValue = std::max(lhs->integerValue, rhs->integerValue);
    return result;
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
  Operation *operation = value ? value.getDefiningOp() : nullptr;
  if (!operation || operation->getNumResults() != 1 || parameterStack.size() >= 512 ||
      !parameterStack.insert(operation).second)
    return failure();
  auto finish = [&](FailureOr<EvaluatedConstant> result) {
    parameterStack.erase(operation);
    return result;
  };
  llvm::StringRef name = operation->getName().getStringRef();
  if (name == "nodal.const_expr")
    return finish(evaluateExpression(
        operation, [&](Value operand) { return evaluateValue(operand, scope, parameterStack); }));
  if (operation->getNumOperands() != 0)
    return finish(failure());
  if (name == "nodal.const_literal") {
    Operation *unit = resolveUnit(operation, operation->getAttrOfType<FlatSymbolRefAttr>("unit"));
    if (operation->hasAttr("unit") && !unit)
      return finish(failure());
    return finish(constantFromAttribute(operation->getAttr("value"), value.getType(), unit));
  }
  if (name == "nodal.const_parameter_ref") {
    auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("parameter");
    Operation *module = enclosingNodalModule(operation);
    Operation *parameter =
        reference ? findDirectSymbol(module, reference.getValue(), "nodal.parameter") : nullptr;
    auto type = parameter ? parameter->getAttrOfType<TypeAttr>("type") : TypeAttr();
    if (!type || type.getValue() != value.getType() || operation->hasAttr("unit"))
      return finish(failure());
    return finish(evaluateParameterDefault(parameter, parameterStack));
  }
  if (name == "nodal.constant")
    return finish(constantFromAttribute(operation->getAttr("value"), value.getType()));
  return finish(failure());
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
    auto declaredType = parameter->getAttrOfType<TypeAttr>("type");
    if (declaredType && nodal::isDigitalScalarType(declaredType.getValue())) {
      if (!value.exactInteger && !value.integerIsNarrow)
        return failure();
      IntegerAttr exact =
          value.exactInteger
              ? value.exactInteger
              : IntegerAttr::get(IntegerType::get(parameter->getContext(), 64), value.integerValue);
      if (!nodal::digitalIntegerAttributeFits(exact, declaredType.getValue(),
                                              enclosingNodalModule(parameter)))
        return failure();
    }
    if (declaredType && llvm::isa<nodal::EnumType>(declaredType.getValue())) {
      if (!value.exactInteger && !value.integerIsNarrow)
        return failure();
      IntegerAttr exact =
          value.exactInteger
              ? value.exactInteger
              : IntegerAttr::get(IntegerType::get(parameter->getContext(), 64), value.integerValue);
      if (failed(nodal::verifyEnumParameterValue(parameter, exact)))
        return failure();
    }
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
          std::max(integer.getValue().getBitWidth(), value.exactInteger.getValue().getBitWidth()) +
          1;
      llvm::APInt lhs = value.integerSigned ? value.exactInteger.getValue().sextOrTrunc(width)
                                            : value.exactInteger.getValue().zextOrTrunc(width);
      auto carrier = llvm::cast<IntegerType>(integer.getType());
      llvm::APInt rhs = value.integerSigned && !carrier.isUnsigned()
                            ? integer.getValue().sextOrTrunc(width)
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
    const unsigned width = std::max(lhsWidth, rhsWidth) + 1;
    const bool signedCompare = lhs.integerSigned || rhs.integerSigned;
    llvm::APInt left = lhs.exactInteger
                           ? (lhs.integerSigned ? lhs.exactInteger.getValue().sextOrTrunc(width)
                                                : lhs.exactInteger.getValue().zextOrTrunc(width))
                           : llvm::APInt(width, static_cast<uint64_t>(lhs.integerValue), true);
    llvm::APInt right = rhs.exactInteger
                            ? (rhs.integerSigned ? rhs.exactInteger.getValue().sextOrTrunc(width)
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

bool satisfiesConstraint(Operation *constraint, const EvaluatedConstant &candidate,
                         llvm::ArrayRef<EvaluatedConstant> values) {
  if (textAttr(constraint, "constraint_kind") == "range") {
    const bool lowerInclusive = constraint->getAttrOfType<BoolAttr>("lower_inclusive").getValue();
    const bool upperInclusive = constraint->getAttrOfType<BoolAttr>("upper_inclusive").getValue();
    const int lower = compareConstants(candidate, values[0]);
    const int upper = compareConstants(candidate, values[1]);
    return (lowerInclusive ? lower >= 0 : lower > 0) && (upperInclusive ? upper <= 0 : upper < 0);
  }
  return textAttr(constraint, "constraint_kind") != "exclude" ||
         compareConstants(candidate, values[0]) != 0;
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
    if (!satisfiesConstraint(constraint, candidate, values))
      return diagnosticOwner->emitOpError() << code
                                            << (textAttr(constraint, "constraint_kind") == "range"
                                                    ? ": value violates parameter range"
                                                    : ": value is explicitly excluded");
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

// Queries can be called directly on mutated IR before operation verification.
// A dead choose_zero branch must not hide a malformed retained literal spelling.
bool validIntegerLiteralSpelling(Operation *operation) {
  if (!isNamed(operation, "nodal.const_literal"))
    return true;
  auto spelling = operation->getAttrOfType<StringAttr>("spelling");
  auto attribute = operation->getAttrOfType<IntegerAttr>("value");
  if (!spelling || !attribute || !validNativeDecimal(spelling.getValue(), true))
    return false;
  auto parsed = parseInteger(spelling.getValue());
  auto number = integerBoundLiteral(attribute);
  return parsed && succeeded(number) && *parsed == *number;
}

// One checked arithmetic policy serves both ordinary envelopes and substituted
// instance expressions. Exact identity is supplied by each caller's owned DAG.
FailureOr<IntegerBounds> integerOperationBounds(llvm::StringRef operationName,
                                                llvm::ArrayRef<IntegerBounds> operands,
                                                bool sameOperands) {
  const unsigned arity = integerOperationArity(operationName);
  if (!arity || operands.size() != arity ||
      llvm::any_of(operands, [](IntegerBounds bounds) { return bounds.lower > bounds.upper; }))
    return failure();
  const IntegerBounds left = operands[0];
  IntegerBounds result{};
  if (operationName == "ceil_log2") {
    if (left.lower <= 0)
      return failure();
    result = {positiveCeilLog2(left.lower), positiveCeilLog2(left.upper)};
  } else if (operationName == "choose_zero") {
    const auto whenZero = operands[1], whenNonzero = operands[2];
    if (left.lower == 0 && left.upper == 0)
      result = whenZero;
    else if (left.upper < 0 || left.lower > 0)
      result = whenNonzero;
    else
      result = {std::min(whenZero.lower, whenNonzero.lower),
                std::max(whenZero.upper, whenNonzero.upper)};
  } else if (operationName == "neg") {
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
    if (operationName == "max") {
      // Maximum is monotone in both operands. Keep the entire legal envelope;
      // neither equal defaults nor equal intervals identify independent symbols.
      result = {std::max(left.lower, right.lower), std::max(left.upper, right.upper)};
    } else if (operationName == "mod") {
      // C/Verilog integer remainder follows the dividend's sign. An outer
      // magnitude bound is sound even when the divisor itself varies.
      llvm::APInt lowerMagnitude = wideInteger(right.lower).abs();
      llvm::APInt upperMagnitude = wideInteger(right.upper).abs();
      llvm::APInt magnitude = lowerMagnitude.ugt(upperMagnitude) ? lowerMagnitude : upperMagnitude;
      auto maximum = narrowInteger(magnitude - llvm::APInt(128, 1));
      if (failed(maximum))
        return failure();
      result = {left.lower < 0 ? std::max(left.lower, -*maximum) : 0,
                left.upper > 0 ? std::min(left.upper, *maximum) : 0};
    } else if (operationName == "sub" && sameOperands) {
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
  return result;
}

class IntegerBoundsAnalysis {
public:
  explicit IntegerBoundsAnalysis(Operation *owner, bool generatedI64 = false,
                                 GeneratedQueryContext *query = nullptr)
      : owner(owner), generatedI64(generatedI64), query(query) {}

  FailureOr<IntegerBounds> value(Value input) {
    if (!input || (query && (!query->charge() || !moduleBody(owner))))
      return failure();
    auto cached = values.find(input);
    if (cached != values.end()) {
      if (!cached->second)
        return failure();
      return *cached->second;
    }
    if (activeValues.size() + activeParameters.size() >= 512 || !activeValues.insert(input).second)
      return failure();
    if (query && isNamed(input.getDefiningOp(), "nodal.const_parameter_ref"))
      for (Operation &unused : *moduleBody(owner))
        if (!query->charge())
          return failure();
    auto result = analyzeValue(input);
    activeValues.erase(input);
    values.try_emplace(input,
                       succeeded(result) ? std::optional<IntegerBounds>(*result) : std::nullopt);
    return result;
  }

  FailureOr<IntegerBounds> parameter(Operation *input) {
    if (!input || (query && (!query->charge() || !moduleBody(owner))))
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
    // Existing duplicate/default/constraint inventories remain in this owner.
    // Charge their bounded module scans, then each actual recursively visited DAG node.
    if (query)
      for (Operation &unused : *moduleBody(owner))
        if (!query->charge() || !query->charge())
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
  bool fits(IntegerBounds bounds, Type type) const {
    auto integer = llvm::dyn_cast<IntegerType>(type);
    // Only the generated-bound profile interprets public signless i64 as
    // mathematical signed endpoints. Existing parameter/storage rules remain.
    return (generatedI64 && integer && integer.isSignless() && integer.getWidth() == 64) ||
           boundsFitType(bounds, type);
  }

  FailureOr<IntegerBounds> literal(Attribute attribute, Type type) {
    auto integer = llvm::dyn_cast<IntegerType>(type);
    if (generatedI64 && integer && integer.isSignless() && integer.getWidth() == 64) {
      auto attr = llvm::dyn_cast_or_null<IntegerAttr>(attribute);
      if (!attr || attr.getType() != type)
        return failure();
      auto number = integerBoundLiteral(attr);
      if (failed(number))
        return failure();
      return IntegerBounds{*number, *number};
    }
    auto constant = constantFromAttribute(attribute, type);
    if (failed(constant) || constant->kind != ConstantKind::Integer || !constant->integerIsNarrow ||
        !constant->dimension.empty())
      return failure();
    IntegerBounds result{constant->integerValue, constant->integerValue};
    if (!fits(result, type))
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
      if (candidate.lower > candidate.upper || !fits(candidate, type.getValue()))
        return failure();
      intersection = candidate;
    }
    if (!intersection) {
      // An unconstrained mathematical i64 parameter still has its signed-i64
      // domain. Arithmetic must prove safety over this entire domain; a default
      // remains unrelated to this proof. Storage mode retains its old policy.
      if (generatedI64 && type.getValue().isSignlessInteger(64))
        return IntegerBounds{std::numeric_limits<int64_t>::min(),
                             std::numeric_limits<int64_t>::max()};
      return failure();
    }
    return *intersection;
  }

  FailureOr<IntegerBounds> analyzeValue(Value input) {
    Operation *operation = input.getDefiningOp();
    if (!operation || enclosingNodalModule(operation) != owner || operation->getNumResults() != 1 ||
        kindForType(input.getType()) != ConstantKind::Integer || operation->hasAttr("unit"))
      return failure();
    llvm::StringRef name = operation->getName().getStringRef();
    if (name == "nodal.const_literal" || name == "nodal.constant") {
      if (operation->getNumOperands() != 0 || !validIntegerLiteralSpelling(operation))
        return failure();
      return literal(operation->getAttr("value"), input.getType());
    }
    if (name == "nodal.const_parameter_ref") {
      if (operation->getNumOperands() != 0)
        return failure();
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
    if (requiresStaticI64(operationName) && !input.getType().isSignlessInteger(64))
      return failure();
    const unsigned arity = integerOperationArity(operationName);
    if (!arity || operation->getNumOperands() != arity)
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
    auto computed = integerOperationBounds(
        operationName, operands,
        operation->getNumOperands() == 2 && operation->getOperand(0) == operation->getOperand(1));
    if (failed(computed))
      return failure();
    const IntegerBounds result = *computed;
    if (!fits(result, input.getType()))
      return failure();
    return result;
  }

  Operation *owner;
  bool generatedI64;
  GeneratedQueryContext *query;
  llvm::DenseMap<Value, std::optional<IntegerBounds>> values;
  llvm::DenseMap<Operation *, std::optional<IntegerBounds>> parameters;
  llvm::DenseSet<Value> activeValues;
  llvm::DenseSet<Operation *> activeParameters;
};

using InstanceNode = nodal::InstantiatedIntegerNode;
using InstanceExpression = nodal::InstantiatedIntegerExpression;
using InstanceNodePtr = std::shared_ptr<const InstanceNode>;

struct StructuralIntegerLeaves {
  llvm::function_ref<FailureOr<InstanceExpression>(Value)> value;
  llvm::function_ref<FailureOr<InstanceExpression>(Operation *)> parameter;
};
FailureOr<nodal::StructuralIntegerFacts>
structuralIntegerWithLeaves(Value, Operation *, GeneratedQueryContext &,
                            nodal::ParameterIntegerMode, const StructuralIntegerLeaves *,
                            IntegerQueryPurpose = IntegerQueryPurpose::Structural);

bool sameIntegerNodes(InstanceNodePtr left, InstanceNodePtr right) {
  llvm::SmallVector<std::pair<const InstanceNode *, const InstanceNode *>, 16> pending;
  llvm::DenseSet<std::pair<const InstanceNode *, const InstanceNode *>> visited;
  pending.emplace_back(left.get(), right.get());
  while (!pending.empty()) {
    auto pair = pending.pop_back_val();
    if (pair.first == pair.second)
      continue;
    if (!pair.first || !pair.second || !visited.insert(pair).second) {
      if (!pair.first || !pair.second)
        return false;
      continue;
    }
    const auto &a = *pair.first;
    const auto &b = *pair.second;
    if (a.kind != b.kind || a.literal != b.literal || a.parameter != b.parameter ||
        a.identity != b.identity || a.opcode != b.opcode || a.operands.size() != b.operands.size())
      return false;
    for (size_t index = 0; index < a.operands.size(); ++index)
      pending.emplace_back(a.operands[index].get(), b.operands[index].get());
  }
  return true;
}

InstanceNodePtr literalInstanceNode(int64_t value, Operation *origin = nullptr) {
  auto node = std::make_shared<InstanceNode>();
  node->kind = InstanceNode::Kind::Literal;
  node->literal = value;
  node->origin = origin;
  node->bounds = {value, value};
  return node;
}

// This query adds finite facts to the parameter owner, not another interval
// engine. The cached default is only a validity witness; `invariant` determines
// whether it may also be used as a value proof. All reachable constant DAG nodes
// are checked once, with the same bounded recursion policy as integer bounds.
using ZeroAdmissibility = nodal::ParameterZeroAdmissibility;
struct CheckedParameterConstant {
  EvaluatedConstant value;
  bool invariant = false;
  ZeroAdmissibility zero = ZeroAdmissibility::Unknown;
};

ZeroAdmissibility constantZeroAdmissibility(const EvaluatedConstant &value) {
  if (value.kind == ConstantKind::Real)
    return value.realValue == 0.0 ? ZeroAdmissibility::ZeroPermitted
                                  : ZeroAdmissibility::ZeroExcluded;
  if (value.kind == ConstantKind::Integer) {
    const bool zero =
        value.exactInteger ? value.exactInteger.getValue().isZero() : value.integerValue == 0;
    return zero ? ZeroAdmissibility::ZeroPermitted : ZeroAdmissibility::ZeroExcluded;
  }
  return ZeroAdmissibility::Unknown;
}

class ParameterZeroAnalysis {
public:
  explicit ParameterZeroAnalysis(Operation *owner) : owner(owner), integerBounds(owner) {}

  FailureOr<CheckedParameterConstant> value(Value input) {
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
    values.try_emplace(input, succeeded(result) ? std::optional<CheckedParameterConstant>(*result)
                                                : std::nullopt);
    return result;
  }

  FailureOr<CheckedParameterConstant> parameter(Operation *input) {
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
    parameters.try_emplace(
        input, succeeded(result) ? std::optional<CheckedParameterConstant>(*result) : std::nullopt);
    return result;
  }

private:
  LogicalResult checkUnit(Operation *input) {
    if (!input->hasAttr("unit"))
      return success();
    Operation *unit = resolveUnit(input, input->getAttrOfType<FlatSymbolRefAttr>("unit"));
    return success(unit && succeeded(verify(unit, false)));
  }

  Operation *referencedParameter(Operation *input) {
    auto reference = input->getAttrOfType<FlatSymbolRefAttr>("parameter");
    return reference ? findDirectSymbol(owner, reference.getValue(), "nodal.parameter") : nullptr;
  }

  FailureOr<CheckedParameterConstant> analyzeValue(Value input) {
    Operation *operation = input.getDefiningOp();
    if (!operation || enclosingNodalModule(operation) != owner ||
        failed(verify(operation, false)) || failed(checkUnit(operation)))
      return failure();
    const auto name = operation->getName().getStringRef();
    if (name == "nodal.const_parameter_ref" || name == "nodal.parameter_ref")
      return parameter(referencedParameter(operation));
    if (name == "nodal.const_literal" || name == "nodal.constant") {
      auto literal = constantFromAttribute(
          operation->getAttr("value"), input.getType(),
          resolveUnit(operation, operation->getAttrOfType<FlatSymbolRefAttr>("unit")));
      if (failed(literal) ||
          (literal->kind == ConstantKind::Real && !std::isfinite(literal->realValue)))
        return failure();
      return CheckedParameterConstant{*literal, true, constantZeroAdmissibility(*literal)};
    }
    if (name != "nodal.const_expr")
      return failure();
    bool invariant = true;
    llvm::SmallVector<CheckedParameterConstant, 3> operands;
    auto evaluated =
        evaluateExpression(operation, [&](Value operand) -> FailureOr<EvaluatedConstant> {
          auto fact = value(operand);
          if (failed(fact))
            return failure();
          invariant &= fact->invariant;
          operands.push_back(*fact);
          return fact->value;
        });
    // Evaluation above eagerly validates every branch. Only an invariant test,
    // never its overridable default, may discard a branch's variability.
    if (succeeded(evaluated) && textAttr(operation, "operator_name") == "choose_zero" &&
        operands.size() == 3 && operands[0].invariant)
      invariant = operands[operands[0].value.integerValue == 0 ? 1 : 2].invariant;
    if (failed(evaluated) ||
        (evaluated->kind == ConstantKind::Integer && evaluated->integerIsNarrow &&
         !boundsFitType({evaluated->integerValue, evaluated->integerValue}, input.getType())))
      return failure();
    CheckedParameterConstant result{*evaluated, invariant};
    if (invariant) {
      result.zero = constantZeroAdmissibility(*evaluated);
    } else if (auto bounds = integerBounds.value(input);
               succeeded(bounds) && (bounds->lower > 0 || bounds->upper < 0)) {
      result.zero = ZeroAdmissibility::ZeroExcluded;
    }
    return result;
  }

  FailureOr<CheckedParameterConstant> analyzeParameter(Operation *input) {
    if (!isNamed(input, "nodal.parameter") || input->getParentOp() != owner ||
        !structuralBody(owner) || failed(verify(input, false)) || failed(checkUnit(input)) ||
        hasDuplicateParameterValue(owner, symbolName(input)))
      return failure();
    unsigned identities = 0;
    for (Operation &candidate : *moduleBody(owner))
      identities +=
          isNamed(&candidate, "nodal.parameter") && symbolName(&candidate) == symbolName(input);
    if (identities != 1)
      return failure();
    const Type type = input->getAttrOfType<TypeAttr>("type").getValue();
    Operation *expression = findParameterValue(owner, symbolName(input));
    FailureOr<CheckedParameterConstant> initial = failure();
    if (expression) {
      if (failed(verify(expression, false)))
        return failure();
      initial = value(expression->getOperand(0));
    } else {
      auto constant = constantFromAttribute(
          input->getAttr("default_value"), type,
          resolveUnit(input, input->getAttrOfType<FlatSymbolRefAttr>("unit")));
      if (succeeded(constant))
        initial = CheckedParameterConstant{*constant, true};
    }
    if (failed(initial))
      return failure();
    auto physical = adoptParameterUnit(initial->value, input);
    if (failed(physical))
      return failure();
    auto normalized = normalizeForParameter(*physical, input);
    if (failed(normalized) || !attributeMatches(input->getAttr("default_value"), *normalized))
      return failure();
    CheckedParameterConstant result{*physical, textAttr(input, "variability") == "fixed" &&
                                                   initial->invariant};
    EvaluatedConstant zero;
    zero.kind = normalized->kind;
    bool constrained = false, allInvariant = true, zeroExcluded = false;
    for (Operation *constraint : findParameterConstraints(owner, symbolName(input))) {
      if (failed(verify(constraint, false)))
        return failure();
      constrained = true;
      bool invariant = true;
      llvm::SmallVector<EvaluatedConstant, 2> operands;
      for (Value operand : constraint->getOperands()) {
        auto fact = value(operand);
        if (failed(fact))
          return failure();
        auto normalizedOperand = normalizeForParameter(fact->value, input);
        if (failed(normalizedOperand))
          return failure();
        invariant &= fact->invariant;
        operands.push_back(*normalizedOperand);
      }
      // An accepted default witnesses a nonempty legal set. Never infer safety
      // from contradictory constraints or a default that violates its contract.
      if (!satisfiesConstraint(constraint, *normalized, operands))
        return failure();
      allInvariant &= invariant;
      zeroExcluded |= invariant && !satisfiesConstraint(constraint, zero, operands);
    }
    if (normalized->kind != ConstantKind::Integer && normalized->kind != ConstantKind::Real)
      return result;
    if (result.invariant)
      result.zero = constantZeroAdmissibility(*physical);
    else if (zeroExcluded)
      result.zero = ZeroAdmissibility::ZeroExcluded;
    else if (auto bounds = integerBounds.parameter(input);
             succeeded(bounds) && (bounds->lower > 0 || bounds->upper < 0))
      result.zero = ZeroAdmissibility::ZeroExcluded;
    else if (textAttr(input, "variability") == "symbolic" && constrained && allInvariant)
      result.zero = ZeroAdmissibility::ZeroPermitted;
    else if (textAttr(input, "variability") == "fixed" && expression &&
             (allInvariant || initial->zero == ZeroAdmissibility::ZeroExcluded))
      result.zero = initial->zero;
    return result;
  }

  Operation *owner;
  IntegerBoundsAnalysis integerBounds;
  llvm::DenseMap<Value, std::optional<CheckedParameterConstant>> values;
  llvm::DenseMap<Operation *, std::optional<CheckedParameterConstant>> parameters;
  llvm::DenseSet<Value> activeValues;
  llvm::DenseSet<Operation *> activeParameters;
};

// This deliberately accepts only an actual single-block use, not a path label
// or an ancestor's apparent bounds. Isolated nested scopes need explicit capture.
bool integerValueDominates(Value value, Operation *use) {
  if (!value || !use || value.getParentBlock() != use->getBlock())
    return false;
  if (llvm::isa<BlockArgument>(value))
    return true;
  Operation *definition = value.getDefiningOp();
  return definition && definition != use && definition->isBeforeInBlock(use);
}

bool scopedIntegerUse(const nodal::ParameterIntegerEnvironment &environment) {
  Block *scope = environment.scope();
  Operation *use = environment.use();
  Operation *collection = scope ? scope->getParentOp() : nullptr;
  return scope && use && use->getBlock() == scope &&
         succeeded(nodal::inspectDigitalRegion(scope)) &&
         enclosingNodalModule(collection) == environment.module() &&
         (!environment.instance() ||
          nodal::resolveInstanceModuleDefinition(environment.instance()) == environment.module());
}

// Reuse the declaration's original default/envelope contract when a generated
// instance has no attained value. Absence never waives independent formals.
LogicalResult verifyParameterDefaultContract(Operation *parameter) {
  Operation *owner = parameter->getParentOp();
  if (hasDuplicateParameterValue(owner, symbolName(parameter)))
    return parameter->emitOpError(
        "NODAL-PARAMETER-DEFAULT-001: parameter has multiple default expressions");
  llvm::DenseSet<Operation *> stack;
  auto evaluated = evaluateParameterDefault(parameter, stack);
  if (failed(evaluated))
    return parameter->emitOpError(
        "NODAL-CONSTANT-CYCLE-001: parameter default is cyclic or cannot be folded");
  auto normalized = normalizeForParameter(*evaluated, parameter);
  if (failed(normalized))
    return parameter->emitOpError(
        "NODAL-PARAMETER-UNIT-001: default expression has incompatible type or dimension");
  if (!attributeMatches(parameter->getAttr("default_value"), *normalized))
    return parameter->emitOpError(
        "NODAL-PARAMETER-DEFAULT-001: folded default does not match canonical default_value");
  if (failed(checkConstraints(parameter, *normalized, "NODAL-PARAMETER-CONSTRAINT-001", parameter)))
    return failure();

  bool duplicateEnvelope = false;
  Operation *envelope = findParameterEnvelope(owner, symbolName(parameter), duplicateEnvelope);
  if (duplicateEnvelope)
    return parameter->emitOpError(
        "NODAL-PARAMETER-ENVELOPE-001: parameter has multiple structural envelopes");
  if (envelope && failed(llvm::cast<nodal::ParameterEnvelopeOp>(envelope).verify()))
    return failure();
  auto effects = envelope ? envelope->getAttrOfType<ArrayAttr>("effects") : ArrayAttr();
  if (nodal::isStructuralParameter(parameter)) {
    if (!envelope || textAttr(envelope, "policy") != "static_generate" || effects.empty() ||
        !hasBoundedRange(parameter))
      return parameter->emitOpError("NODAL-PARAMETER-ENVELOPE-001: structural parameter "
                                    "requires bounded static_generate effects");
  } else if (envelope && (textAttr(envelope, "policy") != "fixed_topology" || !effects.empty())) {
    return parameter->emitOpError(
        "NODAL-PARAMETER-ENVELOPE-001: ordinary parameter cannot change structural identity");
  }
  return success();
}

// This is a read-only interpretation of the existing parameter SSA graph, not
// another parameter registry. Each query owns its memoization and active sets.
class InstanceIntegerAnalysis {
public:
  explicit InstanceIntegerAnalysis(
      Operation *owner, Operation *instance = nullptr, bool evaluateParentDefaults = false,
      bool generatedI64 = false, const nodal::ParameterIntegerEnvironment *environment = nullptr,
      llvm::ArrayRef<std::pair<Value, nodal::InstantiatedIntegerExpression>> selected = {},
      bool structuralOverrides = false, bool allowUnattained = false,
      GeneratedQueryContext *query = nullptr, InstanceIntegerAnalysis *enclosing = nullptr,
      InstanceIntegerAnalysis *original = nullptr)
      : parent(owner), instance(instance), evaluateParentDefaults(evaluateParentDefaults),
        generatedI64(generatedI64),
        parentBounds(owner, generatedI64, query && query->sharedWork ? query : nullptr),
        environment(environment), selected(selected), structuralOverrides(structuralOverrides),
        allowUnattained(allowUnattained), query(query), enclosing(enclosing), original(original) {
    if (!moduleBody(parent))
      return;
    if (!instance) {
      valid = true;
      return;
    }
    if (!isNamed(instance, "nodal.instance") || enclosingNodalModule(instance) != parent ||
        !structuralBody(instance->getParentOp()))
      return;
    auto target = instance->getAttrOfType<FlatSymbolRefAttr>("module");
    child = target ? findTopLevelSymbol(parent, target.getValue(), "nodal.module") : nullptr;
    valid = moduleBody(child) != nullptr && child != parent;
  }

  FailureOr<InstanceNodePtr> parameter(Operation *input) {
    if (!valid || !input || (query && !query->charge()))
      return failure();
    if (original && input->getParentOp() == child) {
      auto allLane = original->parameter(input);
      if (failed(allLane) || !*allLane)
        return failure();
    }
    if (environment && input->getParentOp() == environment->module()) {
      if (!scopedIntegerUse(*environment))
        return failure();
      auto *resolved = environment->parameter(
          input, generatedI64 ? nodal::ParameterIntegerMode::MathematicalSigned64
                              : nodal::ParameterIntegerMode::Storage);
      return resolved ? FailureOr<InstanceNodePtr>(resolved->root)
                      : FailureOr<InstanceNodePtr>(failure());
    }
    if (auto found = parameters.find(input); found != parameters.end())
      return found->second ? FailureOr<InstanceNodePtr>(*found->second)
                           : FailureOr<InstanceNodePtr>(failure());
    // Bound the declaration/default/constraint lookup scans as well as DAG
    // visits. Cached parameters do not repeat these module-wide inventories.
    if (query) {
      Block *body = moduleBody(input->getParentOp());
      if (!body)
        return failure();
      for (Operation &unused : *body)
        if (!query->charge())
          return failure();
    }
    if ((allowUnattained && ++scopedWork > 65536) ||
        activeParameters.size() + activeValues.size() >= 512 ||
        !activeParameters.insert(input).second)
      return failure();
    auto result = analyzeParameter(input);
    activeParameters.erase(input);
    parameters.try_emplace(input, succeeded(result) ? std::optional<InstanceNodePtr>(*result)
                                                    : std::nullopt);
    return result;
  }

  FailureOr<InstanceNodePtr> value(Value input) {
    if (!valid || !input || (query && !query->charge()))
      return failure();
    if (auto found = values.find(input); found != values.end())
      return found->second ? FailureOr<InstanceNodePtr>(*found->second)
                           : FailureOr<InstanceNodePtr>(failure());
    if (query && isNamed(input.getDefiningOp(), "nodal.const_parameter_ref")) {
      Block *body = moduleBody(enclosingNodalModule(input.getDefiningOp()));
      if (!body)
        return failure();
      for (Operation &unused : *body)
        if (!query->charge())
          return failure();
    }
    if (((environment || allowUnattained) && ++scopedWork > 65536) ||
        activeParameters.size() + activeValues.size() >= 512 || !activeValues.insert(input).second)
      return failure();
    auto result = analyzeValue(input);
    activeValues.erase(input);
    values.try_emplace(input,
                       succeeded(result) ? std::optional<InstanceNodePtr>(*result) : std::nullopt);
    return result;
  }

  Operation *childModule() const { return child; }

private:
  bool fits(IntegerBounds bounds, Type type) const {
    auto integer = llvm::dyn_cast<IntegerType>(type);
    return (generatedI64 && integer && integer.isSignless() && integer.getWidth() == 64) ||
           boundsFitType(bounds, type);
  }

  FailureOr<InstanceNodePtr> literal(Attribute attribute, Type type, Operation *origin) {
    if (generatedI64 && type.isSignlessInteger(64)) {
      auto integer = llvm::dyn_cast_or_null<IntegerAttr>(attribute);
      if (!integer || integer.getType() != type)
        return failure();
      auto number = integerBoundLiteral(integer);
      return failed(number) ? FailureOr<InstanceNodePtr>(failure())
                            : FailureOr<InstanceNodePtr>(literalInstanceNode(*number, origin));
    }
    auto number = constantFromAttribute(attribute, type);
    if (failed(number) || number->kind != ConstantKind::Integer || !number->integerIsNarrow ||
        !number->dimension.empty() || !fits({number->integerValue, number->integerValue}, type))
      return failure();
    return literalInstanceNode(number->integerValue, origin);
  }

  FailureOr<InstanceNodePtr> defaultValue(Operation *input, Type type) {
    Operation *owner = input->getParentOp();
    auto *definition = findParameterValue(owner, symbolName(input));
    if (!definition)
      return literal(input->getAttr("default_value"), type, input);
    if (definition->getNumOperands() != 1 || definition->getOperand(0).getType() != type ||
        enclosingNodalModule(definition->getOperand(0).getDefiningOp()) != owner)
      return failure();
    return value(definition->getOperand(0));
  }

  bool provesLess(InstanceNodePtr left, InstanceNodePtr right, bool inclusive) {
    if (inclusive && sameIntegerNodes(left, right))
      return true;
    return inclusive ? left->bounds.upper <= right->bounds.lower
                     : left->bounds.upper < right->bounds.lower;
  }

  FailureOr<bool> constraints(Operation *input, InstanceNodePtr candidate, Type type) {
    if ((!candidate && !allowUnattained) || (candidate && !fits(candidate->bounds, type)))
      return failure();
    bool unattained = !candidate;
    for (Operation *constraint : findParameterConstraints(child, symbolName(input))) {
      if (query && !query->charge())
        return failure();
      if (allowUnattained && ++scopedWork > 65536)
        return failure();
      llvm::SmallVector<InstanceNodePtr, 2> operands;
      bool emptyOperand = false;
      for (Value operand : constraint->getOperands()) {
        if (operand.getType() != type)
          return failure();
        auto result = value(operand);
        if (failed(result))
          return failure();
        emptyOperand |= !*result;
        operands.push_back(*result);
      }
      const auto kind = textAttr(constraint, "constraint_kind");
      if (kind == "range") {
        auto lower = constraint->getAttrOfType<BoolAttr>("lower_inclusive");
        auto upper = constraint->getAttrOfType<BoolAttr>("upper_inclusive");
        if (operands.size() != 2 || !lower || !upper ||
            (candidate && !emptyOperand &&
             (!provesLess(operands[0], candidate, lower.getValue()) ||
              !provesLess(candidate, operands[1], upper.getValue()))))
          return failure();
      } else if (kind == "exclude") {
        if (operands.size() != 1 || (candidate && !emptyOperand &&
                                     !(candidate->bounds.upper < operands[0]->bounds.lower ||
                                       candidate->bounds.lower > operands[0]->bounds.upper)))
          return failure();
      } else {
        return failure();
      }
      unattained |= emptyOperand;
    }
    if (unattained && (!allowUnattained || failed(nodal::verifyParameterDeclaration(input)) ||
                       failed(verifyParameterDefaultContract(input))))
      return failure();
    return unattained;
  }

  FailureOr<InstanceNodePtr> analyzeParameter(Operation *input) {
    Operation *owner = input->getParentOp();
    if (!isNamed(input, "nodal.parameter") || (owner != parent && owner != child) ||
        nodal::getParameterKind(input) != "integer" || input->hasAttr("unit") ||
        hasDuplicateParameterValue(owner, symbolName(input)))
      return failure();
    auto type = input->getAttrOfType<TypeAttr>("type");
    if (!type || kindForType(type.getValue()) != ConstantKind::Integer ||
        !oneOf(textAttr(input, "variability"), {"fixed", "symbolic"}))
      return failure();
    if (owner == parent) {
      if (enclosing)
        return enclosing->parameter(input);
      if (evaluateParentDefaults || textAttr(input, "variability") == "fixed")
        return defaultValue(input, type.getValue());
      auto bounds = parentBounds.parameter(input);
      if (failed(bounds))
        return failure();
      auto node = std::make_shared<InstanceNode>();
      node->kind = InstanceNode::Kind::ParentParameter;
      node->parameter = input;
      node->origin = input;
      node->bounds = *bounds;
      return InstanceNodePtr(node);
    }
    auto bindings = instance->getAttrOfType<DictionaryAttr>("parameter_bindings");
    Attribute binding = bindings ? bindings.get(symbolName(input)) : Attribute();
    Operation *override = nullptr;
    for (Operation &operation : *structuralBody(instance->getParentOp())) {
      if (query && !query->charge())
        return failure();
      if (!isNamed(&operation, "nodal.parameter_override"))
        continue;
      auto instanceRef = operation.getAttrOfType<FlatSymbolRefAttr>("instance");
      auto parameterRef = operation.getAttrOfType<FlatSymbolRefAttr>("parameter");
      if (!instanceRef || !parameterRef || instanceRef.getValue() != symbolName(instance) ||
          parameterRef.getValue() != symbolName(input))
        continue;
      if (override || operation.getNumOperands() != 1 ||
          failed(nodal::verifyGeneratedStructuralOwnership(&operation)) ||
          operation.getOperand(0).getType() != type.getValue() ||
          (!structuralOverrides &&
           enclosingNodalModule(operation.getOperand(0).getDefiningOp()) != parent))
        return failure();
      override = &operation;
    }
    if ((binding || override) && textAttr(input, "variability") == "fixed")
      return failure();
    FailureOr<InstanceNodePtr> result = failure();
    if (override) {
      Operation *actual = override->getOperand(0).getDefiningOp();
      if (!structuralOverrides || (actual && actual->getParentOp() == parent))
        result = value(override->getOperand(0));
      else {
        auto mode = generatedI64 ? nodal::ParameterIntegerMode::MathematicalSigned64
                                 : nodal::ParameterIntegerMode::Storage;
        auto valueLeaf = [&](Value input) -> FailureOr<InstanceExpression> {
          auto result = enclosing->value(input);
          return failed(result) || !*result ? FailureOr<InstanceExpression>(failure())
                                            : FailureOr<InstanceExpression>(
                                                  InstanceExpression{*result, (*result)->bounds});
        };
        auto parameterLeaf = [&](Operation *input) -> FailureOr<InstanceExpression> {
          auto result = enclosing->parameter(input);
          return failed(result) || !*result ? FailureOr<InstanceExpression>(failure())
                                            : FailureOr<InstanceExpression>(
                                                  InstanceExpression{*result, (*result)->bounds});
        };
        StructuralIntegerLeaves leaves{valueLeaf, parameterLeaf};
        GeneratedQueryContext localQuery;
        auto purpose = nodal::isStructuralParameter(input)
                           ? IntegerQueryPurpose::Structural
                           : IntegerQueryPurpose::ConstructorBinding;
        auto structural = structuralIntegerWithLeaves(override->getOperand(0), override,
                                                      query ? *query : localQuery, mode,
                                                      enclosing ? &leaves : nullptr, purpose);
        if (succeeded(structural)) {
          if (structural->expression) {
            auto mapped = nodal::substituteIntegerExpression(*structural->expression, selected);
            if (succeeded(mapped))
              result = mapped->root;
          } else if (allowUnattained && selected.empty()) {
            result = InstanceNodePtr();
          }
        }
      }
    }
    if (binding) {
      auto bound = literal(binding, type.getValue(), instance);
      if (failed(bound) ||
          (override && (failed(result) || !*result || !sameIntegerNodes(*result, *bound))))
        return failure();
      result = bound;
    } else if (!override) {
      result = defaultValue(input, type.getValue());
    }
    if (failed(result))
      return failure();
    auto checked = constraints(input, *result, type.getValue());
    if (failed(checked))
      return failure();
    return *checked ? FailureOr<InstanceNodePtr>(InstanceNodePtr()) : result;
  }

  FailureOr<InstanceNodePtr> analyzeValue(Value input) {
    if (environment && llvm::isa<BlockArgument>(input)) {
      if (!scopedIntegerUse(*environment) || input.getParentBlock() != environment->scope() ||
          !input.getType().isSignlessInteger(64))
        return failure();
      const auto *bound = environment->binding(input);
      return bound && fits(bound->bounds, input.getType()) ? FailureOr<InstanceNodePtr>(bound->root)
                                                           : FailureOr<InstanceNodePtr>(failure());
    }
    Operation *operation = input.getDefiningOp();
    Operation *owner = operation ? operation->getParentOp() : nullptr;
    const bool local = environment && operation && operation->getBlock() == environment->scope();
    if (local && (!scopedIntegerUse(*environment) || !input.getType().isSignlessInteger(64) ||
                  !integerValueDominates(input, environment->use())))
      return failure();
    if (!operation || (!local && owner != parent && owner != child) ||
        operation->getNumResults() != 1 || operation->getNumRegions() ||
        operation->getNumSuccessors() || kindForType(input.getType()) != ConstantKind::Integer ||
        operation->hasAttr("unit"))
      return failure();
    const auto name = operation->getName().getStringRef();
    if (name == "nodal.const_literal" || name == "nodal.constant") {
      if (operation->getNumOperands() != 0 || !validIntegerLiteralSpelling(operation))
        return failure();
      return literal(operation->getAttr("value"), input.getType(), operation);
    }
    if (name == "nodal.const_parameter_ref") {
      if (operation->getNumOperands() != 0)
        return failure();
      auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("parameter");
      auto *declaration = reference ? findDirectSymbol(local ? environment->module() : owner,
                                                       reference.getValue(), "nodal.parameter")
                                    : nullptr;
      auto type = declaration ? declaration->getAttrOfType<TypeAttr>("type") : TypeAttr();
      if (!type || type.getValue() != input.getType())
        return failure();
      return parameter(declaration);
    }
    if (name != "nodal.const_expr")
      return failure();
    const auto opcode = textAttr(operation, "operator_name");
    if (requiresStaticI64(opcode) && !input.getType().isSignlessInteger(64))
      return failure();
    const unsigned arity = integerOperationArity(opcode);
    if (!arity || operation->getNumOperands() != arity)
      return failure();
    auto node = std::make_shared<InstanceNode>();
    node->kind = InstanceNode::Kind::Apply;
    node->opcode = opcode.str();
    node->origin = operation;
    llvm::SmallVector<IntegerBounds, 2> bounds;
    bool unattained = false;
    for (Value operand : operation->getOperands()) {
      if (operand.getType() != input.getType() ||
          (local ? !integerValueDominates(operand, operation)
                 : enclosingNodalModule(operand.getDefiningOp()) != owner))
        return failure();
      auto result = value(operand);
      if (failed(result))
        return failure();
      if (!*result) {
        unattained = true;
      } else {
        node->operands.push_back(*result);
        bounds.push_back((*result)->bounds);
      }
    }
    if (unattained)
      return allowUnattained ? FailureOr<InstanceNodePtr>(InstanceNodePtr())
                             : FailureOr<InstanceNodePtr>(failure());
    const bool sameOperands = arity == 2 && sameIntegerNodes(node->operands[0], node->operands[1]);
    auto result = integerOperationBounds(opcode, bounds, sameOperands);
    if (failed(result) || !fits(*result, input.getType()))
      return failure();
    // Use the arithmetic owner's same canonical composer for SSA resolution and
    // derived proof formulas. This changes query products only, never raw SSA.
    llvm::SmallVector<nodal::InstantiatedIntegerExpression, 3> expressions;
    for (const auto &operand : node->operands)
      expressions.push_back({operand, operand->bounds});
    auto composed = nodal::composeIntegerExpression(opcode, expressions);
    return succeeded(composed) ? FailureOr<InstanceNodePtr>(composed->root)
                               : FailureOr<InstanceNodePtr>(failure());
  }

  Operation *parent;
  Operation *instance;
  Operation *child = nullptr;
  bool valid = false;
  bool evaluateParentDefaults;
  bool generatedI64;
  IntegerBoundsAnalysis parentBounds;
  const nodal::ParameterIntegerEnvironment *environment;
  llvm::ArrayRef<std::pair<Value, nodal::InstantiatedIntegerExpression>> selected;
  bool structuralOverrides;
  bool allowUnattained;
  GeneratedQueryContext *query;
  InstanceIntegerAnalysis *enclosing;
  InstanceIntegerAnalysis *original;
  unsigned scopedWork = 0;
  // nullopt caches failure; a present null node records checked unattainment.
  llvm::DenseMap<Operation *, std::optional<InstanceNodePtr>> parameters;
  llvm::DenseMap<Value, std::optional<InstanceNodePtr>> values;
  llvm::DenseSet<Operation *> activeParameters;
  llvm::DenseSet<Value> activeValues;
};

FailureOr<InstanceExpression> instanceExpression(FailureOr<InstanceNodePtr> node) {
  if (failed(node) || !*node)
    return failure();
  return InstanceExpression{*node, (*node)->bounds};
}

// Declaration defaults are not an instance environment. Preserve the legacy
// broad scalar/default profile, but resolve dependent Integer constraints under
// finalized overrides before checking an instance's evaluated default. Array
// consumers separately request the whole-envelope proof above.
LogicalResult checkInstanceConstraints(Operation *parameter, const EvaluatedConstant &candidate,
                                       Operation *instance, Operation *diagnosticOwner) {
  bool dependent = false;
  llvm::DenseSet<Value> visited;
  llvm::SmallVector<Value, 8> pending;
  for (Operation *constraint :
       findParameterConstraints(parameter->getParentOp(), symbolName(parameter)))
    llvm::append_range(pending, constraint->getOperands());
  while (!pending.empty()) {
    Value value = pending.pop_back_val();
    if (!visited.insert(value).second)
      continue;
    Operation *operation = value.getDefiningOp();
    if (!operation)
      continue;
    dependent |= isNamed(operation, "nodal.const_parameter_ref");
    llvm::append_range(pending, operation->getOperands());
  }
  if (dependent && nodal::getParameterKind(parameter) == "integer" && !parameter->hasAttr("unit")) {
    InstanceIntegerAnalysis analysis(enclosingNodalModule(instance), instance, true);
    if (failed(analysis.parameter(parameter)))
      return diagnosticOwner->emitOpError(
          "NODAL-PARAMETER-OVERRIDE-001: instantiated dependent Integer constraint is invalid");
    return success();
  }
  return checkConstraints(parameter, candidate, "NODAL-PARAMETER-OVERRIDE-001", diagnosticOwner);
}

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

template <typename Query> auto sharedRegionWork(GeneratedQueryContext &context, Query query) {
  unsigned remaining = context.maximumWork - std::min(context.work, context.maximumWork);
  const unsigned before = remaining;
  auto result = query(remaining);
  context.work += before - remaining;
  return result;
}
FailureOr<nodal::DigitalRegionHeader> integerRegionHeader(Block *body,
                                                          GeneratedQueryContext &query) {
  return query.sharedWork ? sharedRegionWork(query,
                                             [&](unsigned &remaining) {
                                               return nodal::inspectDigitalRegion(body, remaining);
                                             })
                          : nodal::inspectDigitalRegion(body);
}
LogicalResult integerValueScope(Value value, Operation *use, GeneratedQueryContext &query) {
  return query.sharedWork
             ? sharedRegionWork(query,
                                [&](unsigned &remaining) {
                                  return nodal::verifyDigitalValueScope(value, use, remaining);
                                })
             : nodal::verifyDigitalValueScope(value, use);
}
FailureOr<Value> integerCaptureSource(BlockArgument argument, GeneratedQueryContext &query) {
  return query.sharedWork
             ? sharedRegionWork(query,
                                [&](unsigned &remaining) {
                                  return nodal::digitalRegionCaptureSource(argument, remaining);
                                })
             : nodal::digitalRegionCaptureSource(argument);
}

LogicalResult verifyGeneratedBoundDependencies(Value value, Operation *owner, Operation *diagnostic,
                                               llvm::DenseSet<Value> &active,
                                               llvm::DenseSet<Value> &visited,
                                               GeneratedQueryContext &query,
                                               bool requireStructural = true,
                                               bool requireFiniteRange = false) {
  if (!value || !query.charge())
    return diagnostic->emitOpError(
        "NODAL-ITERATION-043-001: generated SSA bound has no value or exceeds query work budget");
  // Cached dependency facts never substitute for the actual use's lexical scope.
  if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
    auto header = integerRegionHeader(argument.getOwner(), query);
    if (failed(header) || header->module != owner ||
        failed(integerValueScope(value, diagnostic, query)) ||
        (header->kind == nodal::DigitalRegionKind::Generate &&
         (argument.getArgNumber() != 0 || !header->operation->isProperAncestor(diagnostic))))
      return diagnostic->emitOpError(
          "NODAL-ITERATION-043-001: generated bound argument escapes its actual use scope");
  }
  if (Operation *definition = value.getDefiningOp();
      definition && definition != diagnostic && definition->getParentOp() != owner &&
      failed(integerValueScope(value, diagnostic, query)))
    return diagnostic->emitOpError(
        "NODAL-ITERATION-043-001: generated bound expression escapes its actual use scope");
  if (visited.contains(value))
    return success();
  if (active.size() >= 512 || !active.insert(value).second)
    return diagnostic->emitOpError("NODAL-ITERATION-043-001: generated SSA bound expression is "
                                   "cyclic or exceeds the depth limit");

  auto finish = [&](LogicalResult result) {
    active.erase(value);
    if (succeeded(result))
      visited.insert(value);
    return result;
  };

  auto type = llvm::dyn_cast<IntegerType>(value.getType());
  if (!type || !type.isSignless() || type.getWidth() != 64)
    return finish(diagnostic->emitOpError(
        "NODAL-ITERATION-043-001: generated SSA bounds require signless i64 values"));

  if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
    auto header = integerRegionHeader(argument.getOwner(), query);
    if (succeeded(header) && header->kind == nodal::DigitalRegionKind::Generate &&
        header->module == owner && argument.getArgNumber() == 0 &&
        header->operation->isProperAncestor(diagnostic) &&
        succeeded(integerValueScope(value, diagnostic, query))) {
      auto domain = generatedDomain(header->operation, query);
      return finish(success(succeeded(domain) && domain->induction == argument));
    }
    auto source = integerCaptureSource(argument, query);
    if (failed(header) || header->kind != nodal::DigitalRegionKind::StructuralArm ||
        header->module != owner || failed(source) || !*source ||
        failed(integerValueScope(value, diagnostic, query)))
      return finish(diagnostic->emitOpError(
          "NODAL-ITERATION-043-001: generated bound requires an exact structural capture"));
    return finish(verifyGeneratedBoundDependencies(*source, owner, header->operation, active,
                                                   visited, query, requireStructural,
                                                   requireFiniteRange));
  }
  Operation *operation = value.getDefiningOp();
  bool structuralScope = operation && enclosingNodalModule(operation) == owner;
  unsigned scopeDepth = 0;
  for (Operation *scope = operation ? operation->getParentOp() : nullptr;
       structuralScope && scope != owner; scope = scope ? scope->getParentOp() : nullptr)
    structuralScope = scope && (!query.sharedWork || query.charge()) && ++scopeDepth <= 128 &&
                      (isNamed(scope, "nodal.generate") || isNamed(scope, "nodal.structural_if"));
  if (!structuralScope || operation->hasAttr("unit") ||
      (operation->getParentOp() != owner && failed(integerValueScope(value, diagnostic, query))))
    return finish(diagnostic->emitOpError(
        "NODAL-ITERATION-043-001: generated SSA bound escapes its owning module"));

  llvm::StringRef name = operation->getName().getStringRef();
  if (name == "nodal.const_literal" || name == "nodal.constant")
    return finish(success());

  if (name == "nodal.const_parameter_ref") {
    if (query.sharedWork)
      for (Operation &unused : *moduleBody(owner))
        if (!query.charge())
          return finish(failure());
    auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("parameter");
    Operation *parameter =
        reference ? findDirectSymbol(owner, reference.getValue(), "nodal.parameter") : nullptr;
    if (!parameter || (requireStructural && textAttr(parameter, "variability") != "fixed" &&
                       !nodal::isStructuralParameter(parameter)))
      return finish(diagnostic->emitOpError("NODAL-PARAMETER-STRUCTURAL-001: generated SSA bounds "
                                            "require structural parameter dependencies"));
    if (requireFiniteRange && textAttr(parameter, "variability") != "fixed") {
      for (Operation &operation : *moduleBody(owner))
        if (!query.charge())
          return failure();
      if (!hasBoundedRange(parameter))
        return finish(diagnostic->emitOpError("NODAL-PARAMETER-OVERRIDE-001: ordinary constructor "
                                              "actual requires an explicit finite range"));
    }
    auto declaredType = parameter->getAttrOfType<TypeAttr>("type");
    if (!declaredType || declaredType.getValue() != value.getType() || parameter->hasAttr("unit") ||
        hasDuplicateParameterValue(owner, symbolName(parameter)))
      return finish(diagnostic->emitOpError(
          "NODAL-ITERATION-043-001: generated parameter dependency has an invalid type or value"));
    // A fixed derived declaration does not erase its symbolic dependencies.
    if (textAttr(parameter, "variability") == "fixed") {
      Operation *definition = findParameterValue(owner, symbolName(parameter));
      if (definition) {
        if (definition->getNumOperands() != 1 ||
            definition->getOperand(0).getType() != value.getType())
          return finish(diagnostic->emitOpError(
              "NODAL-ITERATION-043-001: fixed generated parameter has an invalid expression"));
        if (failed(verifyGeneratedBoundDependencies(definition->getOperand(0), owner, diagnostic,
                                                    active, visited, query, requireStructural,
                                                    requireFiniteRange)))
          return finish(failure());
      }
    }
    return finish(success());
  }

  if (name != "nodal.const_expr")
    return finish(diagnostic->emitOpError(
        "NODAL-ITERATION-043-001: generated SSA bound contains a non-static value"));

  llvm::StringRef operatorName = textAttr(operation, "operator_name");
  const unsigned arity = integerOperationArity(operatorName);
  if (!arity || operation->getNumOperands() != arity)
    return finish(diagnostic->emitOpError(
        "NODAL-ITERATION-043-001: generated SSA bound has unsupported integer arithmetic"));

  for (Value operand : operation->getOperands()) {
    if (operand.getType() != value.getType())
      return finish(diagnostic->emitOpError(
          "NODAL-ITERATION-043-001: generated SSA bound arithmetic type mismatch"));
    if (failed(verifyGeneratedBoundDependencies(operand, owner, diagnostic, active, visited, query,
                                                requireStructural, requireFiniteRange)))
      return finish(failure());
  }
  return finish(success());
}

struct CheckedGeneratedBounds {
  IntegerBounds lower, upper, step;
  bool identicalBounds;
};

LogicalResult analyzeGeneratedBounds(Operation *operation, Operation *owner,
                                     IntegerBoundsAnalysis &analysis,
                                     IntegerBoundsAnalysis &generatedAnalysis,
                                     llvm::DenseSet<Value> &verifiedDependencies,
                                     CheckedGeneratedBounds &out, GeneratedQueryContext &query) {
  if (failed(nodal::verifyGeneratedBoundForm(operation))) {
    return failure();
  }
  const unsigned boundOperands = operation->getNumOperands();
  const bool operandForm = boundOperands != 0;
  bool symbolic = operandForm;
  FailureOr<IntegerBounds> lower = failure();
  FailureOr<IntegerBounds> upper = failure();
  FailureOr<IntegerBounds> step = failure();
  bool identicalBounds = false;

  if (operandForm) {
    if (boundOperands != 3) {
      return operation->emitOpError(
          "NODAL-ITERATION-043-001: generated SSA bounds require exactly three operands");
    }
    llvm::DenseSet<Value> active;
    for (Value bound : operation->getOperands()) {
      if (failed(verifyGeneratedBoundDependencies(bound, owner, operation, active,
                                                  verifiedDependencies, query))) {
        return failure();
      }
    }
    auto bound = [&](unsigned index) -> FailureOr<IntegerBounds> {
      if (operation->getParentOp() == owner)
        return generatedAnalysis.value(operation->getOperand(index));
      auto facts = structuralInteger(operation->getOperand(index), operation, query);
      return succeeded(facts) && facts->expression
                 ? FailureOr<IntegerBounds>(facts->expression->bounds)
                 : FailureOr<IntegerBounds>(failure());
    };
    lower = bound(0);
    upper = bound(1);
    step = bound(2);
    identicalBounds = operation->getOperand(0) == operation->getOperand(1);
    if (!hasGeneratedCountContract(operation)) {
      return operation->emitOpError(
          "NODAL-ITERATION-043-001: generated SSA bounds require the captured count contract");
    }
  } else {
    for (llvm::StringRef name :
         {llvm::StringRef("lower"), llvm::StringRef("upper"), llvm::StringRef("step")}) {
      Attribute attribute = operation->getAttr(name);
      if (auto reference = llvm::dyn_cast_or_null<FlatSymbolRefAttr>(attribute)) {
        symbolic = true;
        Operation *parameter = findDirectSymbol(owner, reference.getValue(), "nodal.parameter");
        if (!parameter || !nodal::isStructuralParameter(parameter)) {
          return operation->emitOpError(
              "NODAL-PARAMETER-STRUCTURAL-001: symbolic generate bounds require structural "
              "parameters");
        }
      } else {
        auto integer = llvm::dyn_cast_or_null<IntegerAttr>(attribute);
        if (!integer || failed(integerBoundLiteral(integer))) {
          return operation->emitOpError(
              "NODAL-ITERATION-043-001: generate bounds require representable integer literals "
              "or parameter references");
        }
      }
    }
    lower = analysis.bound(operation->getAttr("lower"));
    upper = analysis.bound(operation->getAttr("upper"));
    step = analysis.bound(operation->getAttr("step"));
    identicalBounds = operation->getAttr("lower") == operation->getAttr("upper");
  }

  const bool capturedCount = hasGeneratedCountContract(operation);

  if (failed(lower) || failed(upper) || failed(step)) {
    return operation->emitOpError(
        "NODAL-ITERATION-043-001: cannot prove finite integer generate bounds without "
        "substituting parameter defaults");
  }
  if (step->lower <= 0 && step->upper >= 0) {
    return operation->emitOpError(
        "NODAL-ITERATION-043-002: symbolic generate step can include zero or change sign");
  }
  if ((symbolic || capturedCount) && !identicalBounds &&
      ((step->lower > 0 && lower->upper > upper->lower) ||
       (step->upper < 0 && lower->lower < upper->upper))) {
    return operation->emitOpError(
        "NODAL-ITERATION-043-003: generate direction is not proven for every legal parameter "
        "setting");
  }
  if (capturedCount &&
      failed(verifyGeneratedCountContract(operation, *lower, *upper, *step, identicalBounds)))
    return failure();
  out = {*lower, *upper, *step, identicalBounds};
  return success();
}

LogicalResult verifyGeneratedIntegerBounds(Operation *owner) {
  GeneratedQueryContext query;
  IntegerBoundsAnalysis analysis(owner);
  IntegerBoundsAnalysis generatedAnalysis(owner, true);
  llvm::DenseSet<Value> verifiedDependencies;
  LogicalResult result = success();
  owner->walk([&](nodal::GenerateOp generated) {
    Operation *operation = generated.getOperation();
    if (failed(result) || enclosingNodalModule(operation) != owner)
      return;
    CheckedGeneratedBounds bounds;
    result = analyzeGeneratedBounds(operation, owner, analysis, generatedAnalysis,
                                    verifiedDependencies, bounds, query);
  });
  return result;
}

llvm::StringRef parameterBinarySpelling(llvm::StringRef opcode) {
  return opcode == "add"   ? "+"
         : opcode == "sub" ? "-"
         : opcode == "mul" ? "*"
         : opcode == "div" ? "/"
         : opcode == "mod" ? "%"
         : opcode == "gt"  ? ">"
         : opcode == "ge"  ? ">="
         : opcode == "lt"  ? "<"
         : opcode == "le"  ? "<="
         : opcode == "and" ? "&&"
         : opcode == "or"  ? "||"
                           : "";
}

constexpr size_t kMaximumParameterRenderedBytes = 1024 * 1024;

// Generic Verilog-style constant syntax; backend admission remains separate.
// Explicit 64-bit positive thresholds avoid host overflow and unsized-literal
// ambiguity. Current analog targets intentionally reject these new primitives.
std::string ceilLog2Threshold(unsigned exponent) {
  return "64'd" + std::to_string(uint64_t{1} << exponent);
}

size_t ceilLog2RenderOverhead() {
  size_t result = 2; // final 63
  for (unsigned exponent = 0; exponent != 63; ++exponent)
    result += 14 + ceilLog2Threshold(exponent).size() + std::to_string(exponent).size();
  return result;
}

// Check expanded output before allocating it. Facts are memoized by the owned
// SSA identity, so a shared maximum DAG is checked once per node even though
// portable conditional syntax prints each operand twice. This is a bounded
// rendering profile, not a claim of unbounded textual expression support.
class ParameterRenderBudget {
public:
  LogicalResult check(Value value) { return success(succeeded(visit(value))); }

private:
  struct Fact {
    size_t bytes;
    unsigned depth;
  };

  FailureOr<Fact> visit(Value value) {
    if (!value)
      return failure();
    if (auto cached = facts.find(value); cached != facts.end())
      return cached->second;
    Operation *operation = value.getDefiningOp();
    if (!operation || operation->getNumResults() != 1 || active.size() >= 512 ||
        facts.size() + active.size() >= 65536 || !active.insert(operation).second)
      return failure();
    auto result = analyze(value);
    active.erase(operation);
    if (failed(result) || result->bytes > kMaximumParameterRenderedBytes || result->depth > 512)
      return failure();
    facts.try_emplace(value, *result);
    return result;
  }

  FailureOr<Fact> analyze(Value value) {
    Operation *operation = value.getDefiningOp();
    const auto name = operation->getName().getStringRef();
    if (name != "nodal.const_expr") {
      if (operation->getNumOperands() != 0)
        return failure();
      if (name == "nodal.const_literal") {
        auto spelling = operation->getAttrOfType<StringAttr>("spelling");
        if (!spelling)
          return failure();
        return Fact{spelling.getValue().size(), 1};
      }
      if (name == "nodal.const_parameter_ref") {
        auto parameter = operation->getAttrOfType<FlatSymbolRefAttr>("parameter");
        if (!parameter)
          return failure();
        return Fact{parameter.getValue().size(), 1};
      }
      if (name == "nodal.constant") {
        if (auto integer = operation->getAttrOfType<IntegerAttr>("value")) {
          const bool isSigned = integerTypeIsSigned(value.getType());
          const auto &bits = integer.getValue();
          const uint64_t digits =
              (uint64_t(isSigned ? bits.getSignificantBits() : bits.getActiveBits()) * 30103) /
                  100000 +
              2;
          if (digits > kMaximumParameterRenderedBytes)
            return failure();
          llvm::SmallString<64> rendered;
          bits.toString(rendered, 10, isSigned);
          return Fact{rendered.size(), 1};
        }
      }
      return failure();
    }

    const auto opcode = textAttr(operation, "operator_name");
    const bool unary = opcode == "neg" || opcode == "not" || opcode == "ceil_log2";
    const auto spelling = parameterBinarySpelling(opcode);
    if ((!unary && !requiresStaticI64(opcode) && spelling.empty()) ||
        operation->getNumOperands() != (opcode == "choose_zero" ? 3u
                                        : unary                 ? 1u
                                                                : 2u))
      return failure();
    if (requiresStaticI64(opcode) &&
        (!value.getType().isSignlessInteger(64) || operation->hasAttr("unit") ||
         llvm::any_of(operation->getOperands(),
                      [&](Value operand) { return operand.getType() != value.getType(); })))
      return failure();
    Fact result{opcode == "ceil_log2"     ? ceilLog2RenderOverhead()
                : opcode == "choose_zero" ? 19u
                : unary                   ? 3u
                : opcode == "max"         ? 11u
                                          : 4u + spelling.size(),
                1};
    const size_t occurrences = opcode == "ceil_log2" ? 63 : opcode == "max" ? 2 : 1;
    for (Value operand : operation->getOperands()) {
      auto child = visit(operand);
      if (failed(child) ||
          child->bytes > (kMaximumParameterRenderedBytes - result.bytes) / occurrences)
        return failure();
      result.bytes += child->bytes * occurrences;
      result.depth = std::max(result.depth, child->depth + (opcode == "ceil_log2" ? 63 : 1));
    }
    return result;
  }

  llvm::DenseMap<Value, Fact> facts;
  llvm::DenseSet<Operation *> active;
};

// One formatter shared by authored parameter SSA and checked proof DAGs.
// Arithmetic/type/provenance validation remains with their canonical owners.
FailureOr<std::string> formatParameterOperator(llvm::StringRef operatorName,
                                               ArrayRef<std::string> operands) {
  unsigned arity = operatorName == "choose_zero" ? 3
                   : oneOf(operatorName, {"neg", "not", "ceil_log2"}) ? 1 : 2;
  if (operands.size() != arity)
    return failure();
  if (operatorName == "neg")
    return "(-" + operands[0] + ")";
  if (operatorName == "not")
    return "(!" + operands[0] + ")";
  if (operatorName == "choose_zero")
    return "((" + operands[0] + ") == 0 ? (" + operands[1] + ") : (" + operands[2] + "))";
  if (operatorName == "ceil_log2") {
    std::string result;
    for (unsigned exponent = 0; exponent != 63; ++exponent)
      result += "((" + operands[0] + ") <= " + ceilLog2Threshold(exponent) + " ? " +
                std::to_string(exponent) + " : ";
    result += "63";
    result.append(63, ')');
    return result;
  }
  if (operatorName == "max")
    return "(" + operands[0] + " > " + operands[1] + " ? " + operands[0] + " : " + operands[1] + ")";
  llvm::StringRef spelling = parameterBinarySpelling(operatorName);
  if (spelling.empty())
    return failure();
  return "(" + operands[0] + " " + spelling.str() + " " + operands[1] + ")";
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
    if (operatorName == "max" &&
        (operands.size() != 2 || !value.getType().isSignlessInteger(64) ||
         llvm::any_of(operation->getOperands(),
                      [&](Value operand) { return operand.getType() != value.getType(); }))) {
      visited.erase(operation);
      return failure();
    }
    auto result = formatParameterOperator(operatorName, operands);
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

struct nodal::ParameterIntegerEnvironment::Storage {
  Operation *module;
  Operation *instance;
  Block *scope;
  llvm::DenseMap<Value, InstantiatedIntegerExpression> bindings;
  llvm::DenseMap<Value, InstantiatedIntegerExpression> domains;
  std::shared_ptr<const Storage> enclosing;
  llvm::DenseMap<Operation *, InstantiatedIntegerExpression> storageParameters;
  llvm::DenseMap<Operation *, InstantiatedIntegerExpression> mathematicalParameters;
};

nodal::ParameterIntegerEnvironment::ParameterIntegerEnvironment(
    std::shared_ptr<const Storage> storage, Operation *use)
    : storage(std::move(storage)), lexicalUse(use) {}
Operation *nodal::ParameterIntegerEnvironment::module() const { return storage->module; }
Operation *nodal::ParameterIntegerEnvironment::instance() const { return storage->instance; }
Block *nodal::ParameterIntegerEnvironment::scope() const { return storage->scope; }
Operation *nodal::ParameterIntegerEnvironment::use() const { return lexicalUse; }
const nodal::InstantiatedIntegerExpression *
nodal::ParameterIntegerEnvironment::binding(Value value) const {
  auto found = storage->bindings.find(value);
  return found == storage->bindings.end() ? nullptr : &found->second;
}
const nodal::InstantiatedIntegerExpression *
nodal::ParameterIntegerEnvironment::domain(Value value) const {
  for (auto current = storage; current; current = current->enclosing) {
    auto found = current->domains.find(value);
    if (found != current->domains.end())
      return &found->second;
  }
  return nullptr;
}
const nodal::InstantiatedIntegerExpression *
nodal::ParameterIntegerEnvironment::parameter(Operation *value, ParameterIntegerMode mode) const {
  const auto &parameters = mode == ParameterIntegerMode::Storage ? storage->storageParameters
                                                                 : storage->mathematicalParameters;
  auto found = parameters.find(value);
  return found == parameters.end() ? nullptr : &found->second;
}
FailureOr<nodal::ParameterIntegerEnvironment>
nodal::ParameterIntegerEnvironment::atUse(Operation *use) const {
  if (!use || use->getBlock() != scope())
    return failure();
  ParameterIntegerEnvironment result(storage, use);
  return scopedIntegerUse(result) ? FailureOr<ParameterIntegerEnvironment>(result)
                                  : FailureOr<ParameterIntegerEnvironment>(failure());
}

FailureOr<nodal::ParameterIntegerEnvironment>
nodal::ParameterIntegerEnvironment::enclosingAtUse(Operation *use) const {
  if (!storage->enclosing || !use || use->getBlock() != storage->enclosing->scope)
    return failure();
  ParameterIntegerEnvironment result(storage->enclosing, use);
  return scopedIntegerUse(result) ? FailureOr<ParameterIntegerEnvironment>(result)
                                  : FailureOr<ParameterIntegerEnvironment>(failure());
}

namespace {
// Recheck the supplied immutable DAG with the existing checked composer. Copy
// every node so a retained mutable shared_ptr alias cannot mutate an admission.
// No new interval algebra or caller-supplied proof/equality flag is introduced.
class ScopedIntegerBindingCheck {
public:
  ScopedIntegerBindingCheck(Operation *module, Block *scope, Operation *instance,
                            ArrayRef<nodal::ParameterIntegerBinding> bindings,
                            ArrayRef<nodal::ParameterIntegerFormalDomain> domains,
                            const nodal::ParameterIntegerEnvironment *enclosing)
      : module(module), scope(scope), instance(instance),
        parent(instance ? enclosingNodalModule(instance) : module),
        analysis(parent, instance, false, true), enclosing(enclosing) {
    for (const auto &domain : domains)
      anchors.try_emplace(domain.argument,
                          nodal::makeOpaqueIntegerExpression(domain.argument, domain.bounds));
    for (const auto &binding : bindings)
      inputs.try_emplace(binding.argument, &binding);
  }

  FailureOr<InstanceExpression> binding(Value argument) {
    if (auto found = anchors.find(argument); found != anchors.end())
      return found->second;
    if (auto found = finishedBindings.find(argument); found != finishedBindings.end())
      return found->second;
    auto found = inputs.find(argument);
    if (found == inputs.end() || activeBindings.size() >= 512 ||
        !activeBindings.insert(argument).second)
      return failure();
    const auto &input = *found->second;
    auto result = expression(input.expression);
    if (succeeded(result) && input.captureSource) {
      Operation *collection = scope->getParentOp();
      if (!llvm::is_contained(collection->getOperands(), input.captureSource) ||
          !input.captureSource.getType().isSignlessInteger(64) ||
          failed(nodal::verifyDigitalValueScope(input.captureSource, collection)))
        return failure();
      FailureOr<InstanceExpression> source = failure();
      if (enclosing) {
        auto atCollection = enclosing->atUse(collection);
        if (failed(atCollection))
          return failure();
        source =
            nodal::resolveOwnedIntegerExpression(module, input.captureSource, *atCollection,
                                                 nodal::ParameterIntegerMode::MathematicalSigned64);
      } else if (input.captureSource.getDefiningOp() &&
                 input.captureSource.getDefiningOp()->getParentOp() == module) {
        source = instanceExpression(analysis.value(input.captureSource));
      } else if (!instance && nodal::isBoundedDigitalLoop(collection->getParentOp())) {
        source = nodal::resolveDigitalLoopStaticInteger(input.captureSource, collection);
      }
      if (failed(source) || !nodal::equivalentInstantiatedIntegerExpressions(*source, *result) ||
          !sameBounds(source->bounds, result->bounds))
        return failure();
    }
    activeBindings.erase(argument);
    if (succeeded(result))
      finishedBindings.try_emplace(argument, *result);
    return result;
  }

private:
  static bool sameBounds(IntegerBounds a, IntegerBounds b) {
    return a.lower == b.lower && a.upper == b.upper;
  }

  bool originAllowed(Operation *origin) const {
    if (!origin)
      return true;
    if (origin == instance)
      return true;
    Operation *owner = origin->getParentOp();
    if (origin->getBlock() == scope) {
      return !scope->empty() && origin != &scope->back() &&
             origin->isBeforeInBlock(&scope->back()) &&
             (isNamed(origin, "nodal.const_literal") ||
              isNamed(origin, "nodal.const_parameter_ref") || isNamed(origin, "nodal.const_expr"));
    }
    if (enclosing && origin->getBlock() == enclosing->scope()) {
      Operation *collection = scope->getParentOp();
      return origin != collection && origin->isBeforeInBlock(collection) &&
             (isNamed(origin, "nodal.const_literal") ||
              isNamed(origin, "nodal.const_parameter_ref") || isNamed(origin, "nodal.const_expr"));
    }
    if (owner != module && owner != parent)
      return false;
    if (isNamed(origin, "nodal.parameter"))
      return true;
    Operation *use = owner == module ? scope->getParentOp() : instance;
    // A nested collection uses only module symbolic declarations, or explicitly
    // admitted captures. Module SSA literals in a proof can dominate its nearest
    // enclosing collection even when the active collection is nested.
    while (use && use->getParentOp() != owner)
      use = use->getParentOp();
    return use && origin->getBlock() == use->getBlock() && origin != use &&
           origin->isBeforeInBlock(use);
  }

  FailureOr<InstanceExpression> expression(const InstanceExpression &input) {
    if (!input.root || !sameBounds(input.bounds, input.root->bounds))
      return failure();
    return node(input.root);
  }

  FailureOr<InstanceExpression> node(InstanceNodePtr input) {
    if (!input)
      return failure();
    if (auto found = finishedNodes.find(input.get()); found != finishedNodes.end())
      return found->second;
    if (++work > 65536 || activeNodes.size() + activeBindings.size() >= 512 ||
        !activeNodes.insert(input.get()).second || input->bounds.lower > input->bounds.upper ||
        !originAllowed(input->origin))
      return failure();
    FailureOr<InstanceExpression> result = failure();
    switch (input->kind) {
    case InstanceNode::Kind::Literal:
      if (!input->parameter && !input->identity && input->operands.empty() && input->opcode.empty())
        result = nodal::makeLiteralIntegerExpression(input->literal);
      break;
    case InstanceNode::Kind::ParentParameter:
      if (input->parameter && input->parameter->getParentOp() == parent && !input->identity &&
          input->operands.empty() && input->opcode.empty()) {
        result = instanceExpression(analysis.parameter(input->parameter));
        if (succeeded(result) && !sameIntegerNodes(input, result->root))
          result = failure();
      }
      break;
    case InstanceNode::Kind::OpaqueValue:
      if (!input->parameter && input->identity && input->operands.empty() &&
          input->opcode.empty() && llvm::isa<BlockArgument>(input->identity)) {
        if (llvm::cast<BlockArgument>(input->identity).getOwner() == scope)
          result = binding(input->identity);
        else if (enclosing) {
          const auto *anchor = enclosing->domain(input->identity);
          if (anchor && sameIntegerNodes(input, anchor->root) &&
              sameBounds(input->bounds, anchor->bounds))
            result = nodal::makeOpaqueIntegerExpression(input->identity, anchor->bounds);
        }
      }
      break;
    case InstanceNode::Kind::Apply: {
      if (input->parameter || input->identity ||
          input->operands.size() != integerOperationArity(input->opcode))
        break;
      llvm::SmallVector<InstanceExpression, 3> operands;
      for (const auto &operand : input->operands) {
        auto checked = node(operand);
        if (failed(checked))
          return failure();
        operands.push_back(*checked);
      }
      result = nodal::composeIntegerExpression(input->opcode, operands);
      break;
    }
    }
    activeNodes.erase(input.get());
    if (failed(result) || !sameBounds(input->bounds, result->bounds))
      return failure();
    finishedNodes.try_emplace(input.get(), *result);
    return result;
  }

  Operation *module;
  Block *scope;
  Operation *instance;
  Operation *parent;
  InstanceIntegerAnalysis analysis;
  const nodal::ParameterIntegerEnvironment *enclosing;
  llvm::DenseMap<Value, InstanceExpression> anchors;
  unsigned work = 0;
  llvm::DenseMap<Value, const nodal::ParameterIntegerBinding *> inputs;
  llvm::DenseMap<Value, InstanceExpression> finishedBindings;
  llvm::DenseMap<const InstanceNode *, InstanceExpression> finishedNodes;
  llvm::DenseSet<Value> activeBindings;
  llvm::DenseSet<const InstanceNode *> activeNodes;
};
} // namespace

FailureOr<nodal::ParameterIntegerEnvironment> nodal::ParameterIntegerEnvironment::create(
    Operation *module, Block *scope, Operation *use, Operation *instance,
    ArrayRef<ParameterIntegerBinding> bindings, ArrayRef<ParameterIntegerFormalDomain> domains,
    const ParameterIntegerEnvironment *enclosing) {
  if (!isNamed(module, "nodal.module") || !moduleBody(module) || !scope || !use ||
      use->getBlock() != scope || bindings.size() > 65536 ||
      domains.size() > 65536 - bindings.size() || scope->getNumArguments() > 65536)
    return failure();
  Operation *collection = scope->getParentOp();
  const bool loop = nodal::isBoundedDigitalLoop(collection);
  auto region = nodal::inspectDigitalRegion(scope);
  if (failed(region) || region->module != module ||
      (loop && (instance || enclosing || !domains.empty())) ||
      (region->kind != nodal::DigitalRegionKind::Collection && !domains.empty()))
    return failure();
  unsigned depth = 0;
  for (Operation *owner = collection; owner != module; owner = owner->getParentOp())
    if (!owner || !nodal::isDigitalRegionOwner(owner) || ++depth > 128)
      return failure();
  Operation *parent = instance ? enclosingNodalModule(instance) : module;
  InstanceIntegerAnalysis storageAnalysis(parent, instance);
  InstanceIntegerAnalysis mathematicalAnalysis(parent, instance, false, true);
  if (instance && (!parent || resolveInstanceModuleDefinition(instance) != module ||
                   storageAnalysis.childModule() != module ||
                   failed(verifyGeneratedStructuralOwnership(instance))))
    return failure();
  if (enclosing && (enclosing->module() != module || enclosing->instance() != instance ||
                    failed(enclosing->atUse(collection))))
    return failure();
  auto result = std::make_shared<Storage>();
  if (enclosing)
    result->enclosing = enclosing->storage;
  result->module = module;
  result->instance = instance;
  result->scope = scope;
  llvm::DenseSet<Value> unique;
  for (const auto &binding : bindings)
    if (!binding.argument || binding.argument.getOwner() != scope ||
        !binding.argument.getType().isSignlessInteger(64) ||
        !unique.insert(binding.argument).second)
      return failure();
  for (const auto &domain : domains) {
    if (!domain.argument || domain.argument.getOwner() != scope ||
        !domain.argument.getType().isSignlessInteger(64) || domain.bounds.lower <= 0 ||
        domain.bounds.upper < domain.bounds.lower || !unique.insert(domain.argument).second)
      return failure();
    auto anchor = makeOpaqueIntegerExpression(domain.argument, domain.bounds);
    result->domains.try_emplace(domain.argument, anchor);
    result->bindings.try_emplace(domain.argument, anchor);
  }
  if (loop) {
    unsigned expected = 0;
    for (BlockArgument argument : scope->getArguments().drop_front())
      expected += argument.getType().isSignlessInteger(64);
    if (bindings.size() != expected)
      return failure();
    for (const auto &binding : bindings) {
      auto source = nodal::digitalLoopCaptureSource(binding.argument);
      if (!binding.argument.getArgNumber() || failed(source) || !*source ||
          binding.captureSource != *source)
        return failure();
    }
  }
  if (region->kind == nodal::DigitalRegionKind::StructuralArm ||
      region->kind == nodal::DigitalRegionKind::BehavioralArm ||
      region->kind == nodal::DigitalRegionKind::FlatFSMAction ||
      region->kind == nodal::DigitalRegionKind::EnumSwitchArm) {
    for (const auto &binding : bindings) {
      auto source = nodal::digitalRegionCaptureSource(binding.argument);
      if (failed(source) || !*source || binding.captureSource != *source)
        return failure();
    }
  }
  ScopedIntegerBindingCheck checker(module, scope, instance, bindings, domains, enclosing);
  for (const auto &binding : bindings) {
    auto checked = checker.binding(binding.argument);
    if (failed(checked))
      return failure();
    result->bindings.try_emplace(binding.argument, *checked);
  }
  // Resolve under both policies once. A query cannot observe a different mutable
  // instance binding through a cache keyed only by the child parameter/value.
  if (enclosing) {
    result->storageParameters = enclosing->storage->storageParameters;
    result->mathematicalParameters = enclosing->storage->mathematicalParameters;
    return ParameterIntegerEnvironment(result, use);
  }
  unsigned parameters = 0;
  for (Operation &operation : *moduleBody(module)) {
    if (!isNamed(&operation, "nodal.parameter"))
      continue;
    if (++parameters > 65536)
      return failure();
    if (auto value = instanceExpression(storageAnalysis.parameter(&operation)); succeeded(value))
      result->storageParameters.try_emplace(&operation, *value);
    if (auto value = instanceExpression(mathematicalAnalysis.parameter(&operation));
        succeeded(value))
      result->mathematicalParameters.try_emplace(&operation, *value);
  }
  return ParameterIntegerEnvironment(result, use);
}

namespace {
FailureOr<InstanceExpression>
scopedIntegerExpression(Value value, const nodal::ParameterIntegerEnvironment &environment,
                        nodal::ParameterIntegerMode mode) {
  if (!value || !scopedIntegerUse(environment))
    return failure();
  bool dominates = integerValueDominates(value, environment.use());
  if (!dominates) {
    Operation *definition = value.getDefiningOp();
    Operation *anchor = environment.scope()->getParentOp();
    while (anchor && anchor->getParentOp() != environment.module())
      anchor = anchor->getParentOp();
    dominates = definition && definition->getParentOp() == environment.module() && anchor &&
                definition != anchor && definition->isBeforeInBlock(anchor) &&
                (isNamed(definition, "nodal.const_literal") ||
                 isNamed(definition, "nodal.const_parameter_ref") ||
                 isNamed(definition, "nodal.const_expr"));
  }
  if (!dominates)
    return failure();
  Operation *parent =
      environment.instance() ? enclosingNodalModule(environment.instance()) : environment.module();
  InstanceIntegerAnalysis analysis(parent, environment.instance(), false,
                                   mode == nodal::ParameterIntegerMode::MathematicalSigned64,
                                   &environment);
  return instanceExpression(analysis.value(value));
}
} // namespace

FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveOwnedIntegerExpression(Operation *module, Value expression,
                                     const ParameterIntegerEnvironment &environment,
                                     ParameterIntegerMode mode) {
  if (environment.module() != module || environment.instance())
    return failure();
  return scopedIntegerExpression(expression, environment, mode);
}
FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveInstanceIntegerExpression(Operation *instance, Value expression,
                                        const ParameterIntegerEnvironment &environment,
                                        ParameterIntegerMode mode) {
  if (!instance || environment.instance() != instance ||
      resolveInstanceModuleDefinition(instance) != environment.module())
    return failure();
  return scopedIntegerExpression(expression, environment, mode);
}
FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveGeneratedInstanceIntegerParameter(Operation *instance, Operation *parameter,
                                                ParameterIntegerMode mode) {
  Operation *scope = instance ? instance->getParentOp() : nullptr;
  if (!isNamed(scope, "nodal.generate") || failed(resolveGeneratedDomain(scope)))
    return failure();
  InstanceIntegerAnalysis analysis(enclosingNodalModule(instance), instance, false,
                                   mode == ParameterIntegerMode::MathematicalSigned64, nullptr, {},
                                   true);
  if (!parameter || parameter->getParentOp() != analysis.childModule())
    return failure();
  return instanceExpression(analysis.parameter(parameter));
}

FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveGeneratedInstanceIntegerExpression(Operation *instance, Value expression,
                                                 ParameterIntegerMode mode) {
  Operation *scope = instance ? instance->getParentOp() : nullptr;
  if (!isNamed(scope, "nodal.generate") || failed(resolveGeneratedDomain(scope)))
    return failure();
  InstanceIntegerAnalysis analysis(enclosingNodalModule(instance), instance, false,
                                   mode == ParameterIntegerMode::MathematicalSigned64, nullptr, {},
                                   true);
  if (!expression || enclosingNodalModule(expression.getDefiningOp()) != analysis.childModule())
    return failure();
  return instanceExpression(analysis.value(expression));
}

FailureOr<nodal::StructuralIntegerFacts>
nodal::resolveGeneratedInstanceIntegerParameterFacts(Operation *instance, Operation *parameter,
                                                     ParameterIntegerMode mode) {
  Operation *scope = instance ? instance->getParentOp() : nullptr;
  if (!isNamed(scope, "nodal.generate") || failed(resolveGeneratedDomain(scope)) ||
      failed(verifyGeneratedStructuralOwnership(instance)))
    return failure();
  InstanceIntegerAnalysis analysis(enclosingNodalModule(instance), instance, false,
                                   mode == ParameterIntegerMode::MathematicalSigned64, nullptr, {},
                                   true, true);
  if (!parameter || parameter->getParentOp() != analysis.childModule())
    return failure();
  auto result = analysis.parameter(parameter);
  if (failed(result))
    return failure();
  return *result ? StructuralIntegerFacts{InstanceExpression{*result, (*result)->bounds}}
                 : StructuralIntegerFacts{};
}

FailureOr<nodal::StructuralIntegerFacts>
nodal::resolveGeneratedInstanceIntegerExpressionFacts(Operation *instance, Value expression,
                                                      ParameterIntegerMode mode) {
  Operation *scope = instance ? instance->getParentOp() : nullptr;
  if (!isNamed(scope, "nodal.generate") || failed(resolveGeneratedDomain(scope)) ||
      failed(verifyGeneratedStructuralOwnership(instance)))
    return failure();
  InstanceIntegerAnalysis analysis(enclosingNodalModule(instance), instance, false,
                                   mode == ParameterIntegerMode::MathematicalSigned64, nullptr, {},
                                   true, true);
  if (!expression || enclosingNodalModule(expression.getDefiningOp()) != analysis.childModule())
    return failure();
  auto result = analysis.value(expression);
  if (failed(result))
    return failure();
  return *result ? StructuralIntegerFacts{InstanceExpression{*result, (*result)->bounds}}
                 : StructuralIntegerFacts{};
}

FailureOr<nodal::InstantiatedIntegerExpression> nodal::resolveSelectedInstanceIntegerParameter(
    Operation *instance, Operation *parameter,
    llvm::ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> substitutions,
    ParameterIntegerMode mode) {
  InstanceIntegerAnalysis analysis(enclosingNodalModule(instance), instance, false,
                                   mode == ParameterIntegerMode::MathematicalSigned64, nullptr,
                                   substitutions, true);
  if (!instance || !parameter || parameter->getParentOp() != analysis.childModule())
    return failure();
  if (failed(resolveGeneratedInstanceIntegerParameter(instance, parameter, mode)))
    return failure();
  return instanceExpression(analysis.parameter(parameter));
}
FailureOr<nodal::InstantiatedIntegerExpression> nodal::resolveSelectedInstanceIntegerExpression(
    Operation *instance, Value expression,
    llvm::ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> substitutions,
    ParameterIntegerMode mode) {
  InstanceIntegerAnalysis analysis(enclosingNodalModule(instance), instance, false,
                                   mode == ParameterIntegerMode::MathematicalSigned64, nullptr,
                                   substitutions, true);
  if (!instance || !expression ||
      enclosingNodalModule(expression.getDefiningOp()) != analysis.childModule())
    return failure();
  if (failed(resolveGeneratedInstanceIntegerExpression(instance, expression, mode)))
    return failure();
  return instanceExpression(analysis.value(expression));
}
FailureOr<nodal::InstantiatedIntegerExpression> nodal::substituteIntegerExpression(
    const InstantiatedIntegerExpression &expression,
    llvm::ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> substitutions) {
  using Node = InstantiatedIntegerNode;
  if (substitutions.size() > 128)
    return failure();
  llvm::DenseMap<Value, InstantiatedIntegerExpression> replacements;
  for (const auto &[identity, replacement] : substitutions)
    if (!identity || !replacement.root || !replacements.try_emplace(identity, replacement).second)
      return failure();
  llvm::DenseMap<const Node *, InstantiatedIntegerExpression> memo;
  unsigned work = 65536;
  auto map = [&](auto &&self, const std::shared_ptr<const Node> &node,
                 unsigned depth) -> FailureOr<InstantiatedIntegerExpression> {
    if (!node || depth > 128 || !work)
      return failure();
    --work;
    if (auto found = memo.find(node.get()); found != memo.end())
      return found->second;
    InstantiatedIntegerExpression result{node, node->bounds};
    if (node->kind == Node::Kind::OpaqueValue) {
      if (auto found = replacements.find(node->identity); found != replacements.end()) {
        result = found->second;
        if (result.bounds.lower < node->bounds.lower || result.bounds.upper > node->bounds.upper)
          return failure();
      }
    } else if (node->kind == Node::Kind::Apply) {
      llvm::SmallVector<InstantiatedIntegerExpression, 4> operands;
      for (const auto &operand : node->operands) {
        auto child = self(self, operand, depth + 1);
        if (failed(child))
          return failure();
        operands.push_back(*child);
      }
      auto composed = composeIntegerExpression(node->opcode, operands);
      if (failed(composed))
        return failure();
      result = *composed;
    }
    memo.try_emplace(node.get(), result);
    return result;
  };
  return map(map, expression.root, 0);
}

FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveOwnedIntegerParameter(Operation *module, Operation *parameter,
                                    const ParameterIntegerEnvironment &environment,
                                    ParameterIntegerMode mode) {
  if (!parameter || environment.module() != module || environment.instance() ||
      parameter->getParentOp() != module || !scopedIntegerUse(environment))
    return failure();
  auto *value = environment.parameter(parameter, mode);
  return value ? FailureOr<InstantiatedIntegerExpression>(*value)
               : FailureOr<InstantiatedIntegerExpression>(failure());
}
FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveInstanceIntegerParameter(Operation *instance, Operation *parameter,
                                       const ParameterIntegerEnvironment &environment,
                                       ParameterIntegerMode mode) {
  if (!instance || !parameter || environment.instance() != instance ||
      resolveInstanceModuleDefinition(instance) != environment.module() ||
      parameter->getParentOp() != environment.module() || !scopedIntegerUse(environment))
    return failure();
  auto *value = environment.parameter(parameter, mode);
  return value ? FailureOr<InstantiatedIntegerExpression>(*value)
               : FailureOr<InstantiatedIntegerExpression>(failure());
}
FailureOr<nodal::ParameterIntegerBounds>
nodal::inferParameterIntegerBounds(Value value, const ParameterIntegerEnvironment &environment,
                                   ParameterIntegerMode mode) {
  auto result = scopedIntegerExpression(value, environment, mode);
  return failed(result) ? FailureOr<ParameterIntegerBounds>(failure())
                        : FailureOr<ParameterIntegerBounds>(result->bounds);
}
FailureOr<nodal::ParameterIntegerBounds>
nodal::inferParameterIntegerBounds(Operation *parameter,
                                   const ParameterIntegerEnvironment &environment,
                                   ParameterIntegerMode mode) {
  auto result =
      environment.instance()
          ? resolveInstanceIntegerParameter(environment.instance(), parameter, environment, mode)
          : resolveOwnedIntegerParameter(environment.module(), parameter, environment, mode);
  return failed(result) ? FailureOr<ParameterIntegerBounds>(failure())
                        : FailureOr<ParameterIntegerBounds>(result->bounds);
}

LogicalResult nodal::verifyGeneratedStructuralOwnership(Operation *operation) {
  if (!operation || !structuralBody(operation->getParentOp()))
    return failure();
  auto owner = operation->getAttrOfType<StringAttr>("generated_owner");
  auto induction = operation->getAttrOfType<StringAttr>("generated_induction");
  auto generated = llvm::dyn_cast<GenerateOp>(operation->getParentOp());
  if (!generated)
    return !owner && !induction
               ? success()
               : operation->emitOpError(
                     "NODAL-ITERATION-043-004: module operation cannot claim generated ownership");
  auto region = generated->getAttrOfType<StringAttr>("region_id");
  auto path = generated->getAttrOfType<StringAttr>("induction_path");
  if (!owner || !induction || !region || !path || owner != region || induction != path)
    return operation->emitOpError("NODAL-ITERATION-043-004: structural operation requires its "
                                  "exact generated owner and induction");
  return success();
}

Operation *nodal::resolveStructuralInstance(Operation *use, FlatSymbolRefAttr reference) {
  if (!use || !reference)
    return nullptr;
  Block *body = structuralBody(use->getParentOp());
  if (!body)
    return nullptr;
  Operation *result = nullptr;
  for (Operation &candidate : *body) {
    if (symbolName(&candidate) != reference.getValue())
      continue;
    if (result || !isNamed(&candidate, "nodal.instance"))
      return nullptr;
    result = &candidate;
  }
  return result;
}

Operation *nodal::resolveInstanceModuleDefinition(Operation *instance) {
  if (!isNamed(instance, "nodal.instance") || !structuralBody(instance->getParentOp()))
    return nullptr;
  auto reference = instance->getAttrOfType<FlatSymbolRefAttr>("module");
  return reference ? findTopLevelSymbol(instance, reference.getValue(), "nodal.module") : nullptr;
}

LogicalResult nodal::verifyGeneratedBoundForm(Operation *operation) {
  const unsigned boundOperands = operation->getNumOperands();
  const bool hasLower = static_cast<bool>(operation->getAttr("lower"));
  const bool hasUpper = static_cast<bool>(operation->getAttr("upper"));
  const bool hasStep = static_cast<bool>(operation->getAttr("step"));
  if (boundOperands == 0) {
    if (!hasLower || !hasUpper || !hasStep)
      return operation->emitOpError(
          "NODAL-ITERATION-043-001: attribute-form generate requires lower, upper, and step");
  } else if (boundOperands == 3) {
    if (hasLower || hasUpper || hasStep)
      return operation->emitOpError(
          "NODAL-ITERATION-043-001: SSA-bound generate must not duplicate bound attributes");
  } else {
    return operation->emitOpError(
        "NODAL-ITERATION-043-001: generated SSA bounds require exactly lower, upper, and step");
  }

  return success();
}

FailureOr<nodal::GeneratedScopeActivity>
nodal::analyzeGeneratedScopeActivity(Operation *operation) {
  GeneratedQueryContext query;
  using Activity = GeneratedScopeActivity;
  if (!operation)
    return failure();
  Operation *module = enclosingNodalModule(operation);
  Block *body = structuralBody(operation);
  if (!isNamed(operation, "nodal.generate") || !module || !body || body->getNumArguments() > 1 ||
      (body->getNumArguments() == 1 && !body->getArgument(0).getType().isSignlessInteger(64))) {
    operation->emitOpError(
        "NODAL-ITERATION-043-004: generated activity requires an owned single-block Generate");
    return failure();
  }
  IntegerBoundsAnalysis analysis(module), generatedAnalysis(module, true);
  llvm::DenseSet<Value> dependencies;
  CheckedGeneratedBounds bounds;
  if (failed(analyzeGeneratedBounds(operation, module, analysis, generatedAnalysis, dependencies,
                                    bounds, query)))
    return failure();
  if (body->getNumArguments() && bounds.step.lower <= 0) {
    operation->emitOpError(
        "NODAL-ITERATION-043-002: induction domain requires a proven positive step");
    return failure();
  }
  if (bounds.identicalBounds)
    return Activity::AlwaysEmpty;
  if (bounds.step.lower > 0) {
    if (bounds.lower.lower >= bounds.upper.upper)
      return Activity::AlwaysEmpty;
    if (bounds.lower.upper < bounds.upper.lower)
      return Activity::AlwaysNonempty;
  } else {
    if (bounds.lower.upper <= bounds.upper.lower)
      return Activity::AlwaysEmpty;
    if (bounds.lower.lower > bounds.upper.upper)
      return Activity::AlwaysNonempty;
  }
  return Activity::MaybeEmpty;
}

namespace {
FailureOr<nodal::GeneratedDomain> generatedDomain(Operation *operation,
                                                  GeneratedQueryContext &query) {
  using namespace nodal;
  if (!operation || !query.charge())
    return failure();
  if (auto found = query.domains.find(operation); found != query.domains.end())
    return found->second;
  if (query.activeDomains.size() >= 128 || !query.activeDomains.insert(operation).second) {
    operation->emitOpError(
        "NODAL-ITERATION-043-001: generated domain query is cyclic or exceeds depth budget");
    return failure();
  }
  auto release = llvm::scope_exit([&] { query.activeDomains.erase(operation); });
  auto reject = [&](llvm::StringRef code, llvm::StringRef message) -> FailureOr<GeneratedDomain> {
    operation->emitOpError() << code << ": " << message;
    return failure();
  };
  Operation *module = enclosingNodalModule(operation);
  Block *body = structuralBody(operation);
  if (!isNamed(operation, "nodal.generate") || !module || !body || body->getNumArguments() > 1 ||
      (body->getNumArguments() == 1 && !body->getArgument(0).getType().isSignlessInteger(64)))
    return reject(
        "NODAL-ITERATION-043-004",
        "generated domain requires an owned single-block Generate and optional i64 induction");
  IntegerBoundsAnalysis analysis(module), generatedAnalysis(module, true);
  llvm::DenseSet<Value> dependencies;
  CheckedGeneratedBounds bounds;
  if (failed(analyzeGeneratedBounds(operation, module, analysis, generatedAnalysis, dependencies,
                                    bounds, query)))
    return failure();
  if (bounds.step.lower <= 0)
    return reject("NODAL-ITERATION-043-002", "induction domain requires a proven positive step");
  auto count = positiveGenerateMaximumCount(bounds.lower.lower, bounds.upper.upper,
                                            bounds.step.lower, bounds.identicalBounds);
  if (!count)
    return reject("NODAL-ITERATION-043-002",
                  "induction domain requires a representable positive-step count");
  InstanceIntegerAnalysis expressions(module, nullptr, false, true);
  auto expression = [&](unsigned index, llvm::StringRef name) -> FailureOr<InstanceExpression> {
    if (operation->getNumOperands()) {
      if (operation->getParentOp() != module) {
        auto facts = structuralInteger(operation->getOperand(index), operation, query);
        return succeeded(facts) && facts->expression
                   ? FailureOr<InstanceExpression>(*facts->expression)
                   : FailureOr<InstanceExpression>(failure());
      }
      return instanceExpression(expressions.value(operation->getOperand(index)));
    }
    Attribute attribute = operation->getAttr(name);
    if (auto reference = llvm::dyn_cast_or_null<FlatSymbolRefAttr>(attribute))
      return instanceExpression(
          expressions.parameter(findDirectSymbol(module, reference.getValue(), "nodal.parameter")));
    auto integer = llvm::dyn_cast_or_null<IntegerAttr>(attribute);
    if (!integer)
      return failure();
    auto value = integerBoundLiteral(integer);
    return failed(value) ? FailureOr<InstanceExpression>(failure())
                         : FailureOr<InstanceExpression>(makeLiteralIntegerExpression(*value));
  };
  auto lower = expression(0, "lower"), upper = expression(1, "upper"), step = expression(2, "step");
  if (failed(lower) || failed(upper) || failed(step))
    return reject("NODAL-ITERATION-043-001",
                  "generated bounds require a canonical owned Integer expression DAG");
  GeneratedDomain domain;
  domain.module = module;
  if (body->getNumArguments())
    domain.induction = body->getArgument(0);
  domain.lower = *lower;
  domain.upperExclusive = *upper;
  domain.step = *step;
  domain.maximumTripCount = *count;
  // Retain stride holes but do not invent a final attained value above the
  // largest count/step envelope. Widen before multiplying: legal count and
  // step envelopes can exceed signed i64 intermediates independently.
  if (*count) {
    llvm::APInt last(128, static_cast<uint64_t>(bounds.lower.upper), true);
    last +=
        llvm::APInt(128, *count - 1) * llvm::APInt(128, static_cast<uint64_t>(bounds.step.upper));
    llvm::APInt exclusiveLast(128, static_cast<uint64_t>(bounds.upper.upper - 1), true);
    if (exclusiveLast.slt(last))
      last = exclusiveLast;
    domain.attainedInductionBounds =
        ParameterIntegerBounds{bounds.lower.lower, last.getSExtValue()};
  }
  query.domains.try_emplace(operation, domain);
  return domain;
}
} // namespace

FailureOr<nodal::GeneratedDomain> nodal::resolveGeneratedDomain(Operation *operation) {
  GeneratedQueryContext query;
  return generatedDomain(operation, query);
}

FailureOr<nodal::GeneratedDomain> nodal::resolveGeneratedDomain(Operation *operation,
                                                                unsigned &remainingWork) {
  GeneratedQueryContext query;
  query.maximumWork = std::min(remainingWork, 65536u);
  query.sharedWork = true;
  auto release =
      llvm::scope_exit([&] { remainingWork -= std::min(query.work, query.maximumWork); });
  return generatedDomain(operation, query);
}

FailureOr<nodal::GeneratedDomain> nodal::resolveBoundedIterationDomain(Operation *operation) {
  GeneratedQueryContext query;
  auto reject = [&](llvm::StringRef message) -> FailureOr<GeneratedDomain> {
    if (operation)
      operation->emitOpError("NODAL-DIGITAL-055-011: ") << message;
    return failure();
  };
  Operation *module = enclosingNodalModule(operation);
  if (!operation || !isNamed(operation, "nodal.hardware_loop") ||
      textAttr(operation, "contract_version") != "bounded-digital-v1" || !module ||
      operation->getParentOp() != module || operation->getNumOperands() < 3 ||
      operation->getNumRegions() != 1 || !operation->getRegion(0).hasOneBlock() ||
      operation->getRegion(0).front().getNumArguments() != operation->getNumOperands() - 2 ||
      operation->hasAttr("lower") || operation->hasAttr("upper") || operation->hasAttr("step"))
    return reject("bounded domain requires the actual exclusive SSA loop header");
  Block &body = operation->getRegion(0).front();
  if (!body.getArgument(0).getType().isSignlessInteger(64))
    return reject("bounded induction must be signless i64 with mathematical signed semantics");
  llvm::DenseSet<Value> active, visited;
  for (Value bound : operation->getOperands().take_front(3))
    if (failed(verifyGeneratedBoundDependencies(bound, module, operation, active, visited, query,
                                                false)))
      return failure();
  InstanceIntegerAnalysis expressions(module, nullptr, false, true);
  auto lower = instanceExpression(expressions.value(operation->getOperand(0)));
  auto upper = instanceExpression(expressions.value(operation->getOperand(1)));
  auto step = instanceExpression(expressions.value(operation->getOperand(2)));
  if (failed(lower) || failed(upper) || failed(step))
    return reject(
        "bounds need finite lawful owned Integer envelopes; a maximum is not a constraint");
  // Literal/fully fixed DAGs are invariant. An overridable singleton parameter
  // remains an atom and cannot manufacture an unrolled/default specialization.
  SmallVector<std::shared_ptr<const InstantiatedIntegerNode>, 8> pending{step->root};
  unsigned work = 0;
  while (!pending.empty()) {
    auto node = pending.pop_back_val();
    if (!node || ++work > 65536 || node->kind == InstantiatedIntegerNode::Kind::ParentParameter ||
        node->kind == InstantiatedIntegerNode::Kind::OpaqueValue)
      return reject("step must be a positive invariant Integer DAG");
    llvm::append_range(pending, node->operands);
  }
  if (step->bounds.lower <= 0 || step->bounds.lower != step->bounds.upper)
    return reject("step must be a positive invariant Integer DAG");
  bool identical = equivalentInstantiatedIntegerExpressions(*lower, *upper);
  bool literals = lower->root->kind == InstantiatedIntegerNode::Kind::Literal &&
                  upper->root->kind == InstantiatedIntegerNode::Kind::Literal;
  if (!identical && !literals && lower->bounds.upper > upper->bounds.lower)
    return reject("symbolic direction must hold for every legal parameter setting");
  auto count = positiveGenerateMaximumCount(lower->bounds.lower, upper->bounds.upper,
                                            step->bounds.lower, identical);
  if (!count || *count > static_cast<uint64_t>(std::numeric_limits<int32_t>::max()))
    return reject("finite trip envelope exceeds the checked 32-bit iteration contract");
  if (auto maximum = operation->getAttrOfType<IntegerAttr>("maximum")) {
    if (!maximum.getType().isSignlessInteger(64) || maximum.getInt() < 0 ||
        *count > static_cast<uint64_t>(maximum.getInt()))
      return reject("declared maximum does not contain the independently proved trip envelope");
  } else if (operation->hasAttr("maximum"))
    return reject("declared maximum must be a nonnegative i64");
  GeneratedDomain domain;
  domain.module = module;
  domain.induction = body.getArgument(0);
  domain.lower = *lower;
  domain.upperExclusive = *upper;
  domain.step = *step;
  domain.maximumTripCount = *count;
  if (*count) {
    int64_t attainedUpper = upper->bounds.upper - 1;
    if (lower->bounds.lower == lower->bounds.upper && upper->bounds.lower == upper->bounds.upper) {
      uint64_t distance =
          static_cast<uint64_t>(upper->bounds.upper) - static_cast<uint64_t>(lower->bounds.lower);
      attainedUpper -=
          static_cast<int64_t>((distance - 1) % static_cast<uint64_t>(step->bounds.lower));
    }
    domain.attainedInductionBounds = ParameterIntegerBounds{lower->bounds.lower, attainedUpper};
  }
  return domain;
}

FailureOr<nodal::ParameterIntegerBounds> nodal::inferParameterIntegerBounds(Value value) {
  return inferParameterIntegerBounds(value, ParameterIntegerMode::Storage);
}

FailureOr<nodal::ParameterIntegerBounds>
nodal::inferParameterIntegerBounds(Value value, ParameterIntegerMode mode) {
  if (!value)
    return failure();
  Operation *operation = value.getDefiningOp();
  Operation *owner = enclosingNodalModule(operation);
  if (!owner)
    return failure();
  return IntegerBoundsAnalysis(owner, mode == ParameterIntegerMode::MathematicalSigned64)
      .value(value);
}

FailureOr<nodal::ParameterIntegerBounds> nodal::inferParameterIntegerBounds(Operation *parameter) {
  return inferParameterIntegerBounds(parameter, ParameterIntegerMode::Storage);
}

FailureOr<nodal::ParameterIntegerBounds>
nodal::inferParameterIntegerBounds(Operation *parameter, ParameterIntegerMode mode) {
  Operation *owner = enclosingNodalModule(parameter);
  if (!owner)
    return failure();
  return IntegerBoundsAnalysis(owner, mode == ParameterIntegerMode::MathematicalSigned64)
      .parameter(parameter);
}

FailureOr<nodal::ParameterIntegerBounds>
nodal::inferParameterIntegerBounds(Value value, unsigned &remainingWork,
                                   ParameterIntegerMode mode) {
  GeneratedQueryContext query;
  query.maximumWork = std::min(remainingWork, 65536u);
  query.sharedWork = true;
  auto release =
      llvm::scope_exit([&] { remainingWork -= std::min(query.work, query.maximumWork); });
  if (!query.charge() || !value)
    return failure();
  Operation *owner = enclosingNodalModule(value.getDefiningOp());
  if (!owner)
    return failure();
  return IntegerBoundsAnalysis(owner, mode == ParameterIntegerMode::MathematicalSigned64, &query)
      .value(value);
}
FailureOr<nodal::ParameterIntegerBounds>
nodal::inferParameterIntegerBounds(Operation *parameter, unsigned &remainingWork,
                                   ParameterIntegerMode mode) {
  GeneratedQueryContext query;
  query.maximumWork = std::min(remainingWork, 65536u);
  query.sharedWork = true;
  auto release =
      llvm::scope_exit([&] { remainingWork -= std::min(query.work, query.maximumWork); });
  if (!query.charge())
    return failure();
  Operation *owner = enclosingNodalModule(parameter);
  if (!owner)
    return failure();
  return IntegerBoundsAnalysis(owner, mode == ParameterIntegerMode::MathematicalSigned64, &query)
      .parameter(parameter);
}

FailureOr<nodal::ParameterZeroAdmissibility>
nodal::analyzeParameterZeroAdmissibility(Operation *parameter) {
  if (!parameter)
    return failure();
  ScopedDiagnosticHandler silence(parameter->getContext(), [](Diagnostic &) { return success(); });
  auto fact = ParameterZeroAnalysis(enclosingNodalModule(parameter)).parameter(parameter);
  if (failed(fact))
    return failure();
  return fact->zero;
}

FailureOr<nodal::ParameterZeroAdmissibility> nodal::analyzeParameterZeroAdmissibility(Value value) {
  if (!value)
    return failure();
  Operation *operation = value.getDefiningOp();
  if (!operation)
    return ParameterZeroAdmissibility::Unknown;
  ScopedDiagnosticHandler silence(operation->getContext(), [](Diagnostic &) { return success(); });
  const auto name = operation->getName().getStringRef();
  if (name == "nodal.real_literal" || name == "nodal.analog_integer_literal") {
    if (failed(verify(operation, false)))
      return failure();
    if (auto real = operation->getAttrOfType<FloatAttr>("value"))
      return real.getValueAsDouble() == 0.0 ? ParameterZeroAdmissibility::ZeroPermitted
                                            : ParameterZeroAdmissibility::ZeroExcluded;
    auto integer = operation->getAttrOfType<IntegerAttr>("value");
    if (!integer)
      return failure();
    return integer.getValue().isZero() ? ParameterZeroAdmissibility::ZeroPermitted
                                       : ParameterZeroAdmissibility::ZeroExcluded;
  }
  if (!oneOf(name, {"nodal.const_literal", "nodal.constant", "nodal.const_parameter_ref",
                    "nodal.parameter_ref", "nodal.const_expr"}))
    return ParameterZeroAdmissibility::Unknown;
  auto fact = ParameterZeroAnalysis(enclosingNodalModule(operation)).value(value);
  if (failed(fact))
    return failure();
  return fact->zero;
}

FailureOr<std::optional<Attribute>> nodal::getInvariantParameterDefault(Operation *parameter) {
  if (!parameter)
    return failure();
  ScopedDiagnosticHandler silence(parameter->getContext(), [](Diagnostic &) { return success(); });
  auto fact = ParameterZeroAnalysis(enclosingNodalModule(parameter)).parameter(parameter);
  if (failed(fact))
    return failure();
  if (!fact->invariant)
    return std::optional<Attribute>{};
  auto normalized = normalizeForParameter(fact->value, parameter);
  if (failed(normalized))
    return failure();
  MLIRContext *context = parameter->getContext();
  if (normalized->kind == ConstantKind::Real)
    return std::optional<Attribute>{
        FloatAttr::get(Float64Type::get(context), normalized->realValue)};
  if (normalized->kind == ConstantKind::Boolean)
    return std::optional<Attribute>{BoolAttr::get(context, normalized->booleanValue)};
  if (normalized->kind == ConstantKind::Integer) {
    if (normalized->exactInteger)
      return std::optional<Attribute>{normalized->exactInteger};
    if (normalized->integerIsNarrow)
      return std::optional<Attribute>{IntegerAttr::get(
          IntegerType::get(context, 64, IntegerType::Signed), normalized->integerValue)};
  }
  return failure();
}

FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveInstanceIntegerParameter(Operation *instance, Operation *childParameter) {
  return resolveInstanceIntegerParameter(instance, childParameter, ParameterIntegerMode::Storage);
}

FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveInstanceIntegerParameter(Operation *instance, Operation *childParameter,
                                       ParameterIntegerMode mode) {
  Operation *parent = enclosingNodalModule(instance);
  InstanceIntegerAnalysis analysis(parent, instance, false,
                                   mode == ParameterIntegerMode::MathematicalSigned64);
  if (!instance || !childParameter || childParameter->getParentOp() != analysis.childModule())
    return failure();
  return instanceExpression(analysis.parameter(childParameter));
}

FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveInstanceIntegerExpression(Operation *instance, Value childExpression) {
  return resolveInstanceIntegerExpression(instance, childExpression, ParameterIntegerMode::Storage);
}

FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveInstanceIntegerExpression(Operation *instance, Value childExpression,
                                        ParameterIntegerMode mode) {
  Operation *parent = enclosingNodalModule(instance);
  InstanceIntegerAnalysis analysis(parent, instance, false,
                                   mode == ParameterIntegerMode::MathematicalSigned64);
  if (!instance || !childExpression ||
      enclosingNodalModule(childExpression.getDefiningOp()) != analysis.childModule())
    return failure();
  return instanceExpression(analysis.value(childExpression));
}

FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveOwnedIntegerParameter(Operation *module, Operation *parameter) {
  return resolveOwnedIntegerParameter(module, parameter, ParameterIntegerMode::Storage);
}

FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveOwnedIntegerParameter(Operation *module, Operation *parameter,
                                    ParameterIntegerMode mode) {
  if (!module || !parameter || parameter->getParentOp() != module)
    return failure();
  return instanceExpression(
      InstanceIntegerAnalysis(module, nullptr, false,
                              mode == ParameterIntegerMode::MathematicalSigned64)
          .parameter(parameter));
}

FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveOwnedIntegerExpression(Operation *module, Value expression) {
  return resolveOwnedIntegerExpression(module, expression, ParameterIntegerMode::Storage);
}

FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveOwnedIntegerExpression(Operation *module, Value expression,
                                     ParameterIntegerMode mode) {
  if (!module || !expression || enclosingNodalModule(expression.getDefiningOp()) != module)
    return failure();
  return instanceExpression(
      InstanceIntegerAnalysis(module, nullptr, false,
                              mode == ParameterIntegerMode::MathematicalSigned64)
          .value(expression));
}

FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveOwnedIntegerParameter(Operation *module, Operation *parameter,
                                    unsigned &remainingWork, ParameterIntegerMode mode) {
  GeneratedQueryContext query;
  query.maximumWork = std::min(remainingWork, 65536u);
  query.sharedWork = true;
  auto release =
      llvm::scope_exit([&] { remainingWork -= std::min(query.work, query.maximumWork); });
  if (!query.charge() || !module || !parameter || parameter->getParentOp() != module)
    return failure();
  return instanceExpression(
      InstanceIntegerAnalysis(module, nullptr, false,
                              mode == ParameterIntegerMode::MathematicalSigned64, nullptr, {},
                              false, false, &query)
          .parameter(parameter));
}
FailureOr<nodal::InstantiatedIntegerExpression>
nodal::resolveOwnedIntegerExpression(Operation *module, Value expression, unsigned &remainingWork,
                                     ParameterIntegerMode mode) {
  GeneratedQueryContext query;
  query.maximumWork = std::min(remainingWork, 65536u);
  query.sharedWork = true;
  auto release =
      llvm::scope_exit([&] { remainingWork -= std::min(query.work, query.maximumWork); });
  if (!query.charge() || !module || !expression ||
      enclosingNodalModule(expression.getDefiningOp()) != module)
    return failure();
  return instanceExpression(
      InstanceIntegerAnalysis(module, nullptr, false,
                              mode == ParameterIntegerMode::MathematicalSigned64, nullptr, {},
                              false, false, &query)
          .value(expression));
}

nodal::InstantiatedIntegerExpression nodal::makeLiteralIntegerExpression(int64_t value) {
  return {literalInstanceNode(value), {value, value}};
}

nodal::InstantiatedIntegerExpression
nodal::makeOpaqueIntegerExpression(Value identity, ParameterIntegerBounds bounds) {
  auto node = std::make_shared<InstanceNode>();
  node->kind = InstanceNode::Kind::OpaqueValue;
  node->identity = identity;
  node->origin = identity.getDefiningOp();
  node->bounds = bounds;
  return {node, bounds};
}

FailureOr<nodal::InstantiatedIntegerExpression>
nodal::composeIntegerExpression(llvm::StringRef opcode,
                                llvm::ArrayRef<InstantiatedIntegerExpression> operands) {
  llvm::SmallVector<IntegerBounds, 2> bounds;
  bool allLiteral = true;
  for (const auto &operand : operands) {
    if (!operand.root || operand.bounds.lower != operand.root->bounds.lower ||
        operand.bounds.upper != operand.root->bounds.upper)
      return failure();
    bounds.push_back(operand.bounds);
    allLiteral &= operand.root->kind == InstanceNode::Kind::Literal;
  }
  const bool sameOperands =
      operands.size() == 2 && sameIntegerNodes(operands[0].root, operands[1].root);
  auto result = integerOperationBounds(opcode, bounds, sameOperands);
  if (failed(result))
    return failure();
  if (opcode == "max" && sameOperands)
    return operands[0];
  if (opcode == "choose_zero") {
    if (operands[0].root->kind == InstanceNode::Kind::Literal)
      return operands[operands[0].root->literal == 0 ? 1 : 2];
    if (sameIntegerNodes(operands[1].root, operands[2].root))
      return operands[1];
  }
  if (allLiteral) {
    int64_t value =
        opcode == "mod" ? operands[0].root->literal % operands[1].root->literal : result->lower;
    return makeLiteralIntegerExpression(value);
  }
  auto isLiteral = [&](unsigned index, int64_t value) {
    return operands[index].root->kind == InstanceNode::Kind::Literal &&
           operands[index].root->literal == value;
  };
  if (operands.size() == 2) {
    if ((opcode == "add" || opcode == "sub") && isLiteral(1, 0))
      return operands[0];
    if (opcode == "add" && isLiteral(0, 0))
      return operands[1];
    if (opcode == "mul" && (isLiteral(0, 0) || isLiteral(1, 0)))
      return makeLiteralIntegerExpression(0);
    if ((opcode == "mul" || opcode == "div") && isLiteral(1, 1))
      return operands[0];
    if (opcode == "mul" && isLiteral(0, 1))
      return operands[1];
    if ((opcode == "div" || opcode == "mod") && isLiteral(0, 0))
      return makeLiteralIntegerExpression(0);
    if (opcode == "mod" && isLiteral(1, 1))
      return makeLiteralIntegerExpression(0);
    if (opcode == "sub" && sameIntegerNodes(operands[0].root, operands[1].root))
      return makeLiteralIntegerExpression(0);
    // Exact adjacent-map cancellation, not a general algebra solver. These
    // rules preserve source atoms and avoid retaining spurious envelope width.
    for (unsigned first = 0; first != 2; ++first) {
      const auto &node = operands[first].root;
      if (opcode == "add" && node->kind == InstanceNode::Kind::Apply && node->opcode == "sub" &&
          node->operands.size() == 2 &&
          sameIntegerNodes(node->operands[1], operands[1 - first].root))
        return InstanceExpression{node->operands[0], node->operands[0]->bounds};
    }
    const auto &left = operands[0].root;
    if (opcode == "sub" && left->kind == InstanceNode::Kind::Apply && left->opcode == "add" &&
        left->operands.size() == 2)
      for (unsigned index = 0; index != 2; ++index)
        if (sameIntegerNodes(left->operands[index], operands[1].root))
          return InstanceExpression{left->operands[1 - index], left->operands[1 - index]->bounds};
  }
  auto node = std::make_shared<InstanceNode>();
  node->kind = InstanceNode::Kind::Apply;
  node->opcode = opcode.str();
  node->bounds = *result;
  for (const auto &operand : operands)
    node->operands.push_back(operand.root);
  return InstanceExpression{node, *result};
}

bool nodal::proveIntegerExpressionLess(const InstantiatedIntegerExpression &left,
                                       const InstantiatedIntegerExpression &right, bool inclusive) {
  if (!left.root || !right.root)
    return false;
  if (inclusive && sameIntegerNodes(left.root, right.root))
    return true;
  if (inclusive ? left.bounds.upper <= right.bounds.lower : left.bounds.upper < right.bounds.lower)
    return true;
  auto difference = composeIntegerExpression("sub", {right, left});
  return succeeded(difference) &&
         (inclusive ? difference->bounds.lower >= 0 : difference->bounds.lower > 0);
}

bool nodal::equivalentInstantiatedIntegerExpressions(const InstantiatedIntegerExpression &left,
                                                     const InstantiatedIntegerExpression &right) {
  return left.root && right.root && sameIntegerNodes(left.root, right.root);
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
  if (!type || !defaultValue ||
      !attributeFits(defaultValue, type.getValue(), enclosingNodalModule(operation)))
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
  return nodal::verifyEnumParameterDefault(operation);
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
  if (failed(nodal::verifyEnumLiteralOperation(getOperation())))
    return failure();
  if (getOperation()->getNumResults() == 1 &&
      nodal::isDigitalScalarType(getOperation()->getResult(0).getType()))
    return nodal::verifyDigitalLiteralOperation(getOperation());
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
  if (!integerOperationArity(name) && !oneOf(name, {"not", "gt", "ge", "lt", "le", "and", "or"}))
    return emitOpError("NODAL-CONSTANT-EXPR-001: unsupported constant-expression operator");
  const unsigned expected = integerOperationArity(name) ? integerOperationArity(name)
                            : name == "not"             ? 1
                                                        : 2;
  if (getOperation()->getNumOperands() != expected || getOperation()->getNumResults() != 1)
    return emitOpError("NODAL-CONSTANT-EXPR-001: operator arity is invalid");
  if (hasEnumScalarEdge(getOperation()))
    return emitOpError("NODAL-ENUM-054-020: enum values require closed enum operations; "
                       "generic integer arithmetic cannot construct or convert enums");
  if (hasSymbolicDigitalEdge(getOperation()))
    return emitOpError("NODAL-CONSTANT-EXPR-001: symbolic digital values require closed digital "
                       "operations; generic constant arithmetic is unsupported");
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
  if (requiresStaticI64(name) && (!getOperation()->getResult(0).getType().isSignlessInteger(64) ||
                                  llvm::any_of(getOperation()->getOperands(),
                                               [&](Value operand) {
                                                 return operand.getType() !=
                                                        getOperation()->getResult(0).getType();
                                               }) ||
                                  getOperation()->hasAttr("unit")))
    return emitOpError(
        "NODAL-CONSTANT-EXPR-001: static operator requires dimensionless i64 Integer operands");
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
  Operation *instance = nodal::resolveStructuralInstance(
      getOperation(), getOperation()->getAttrOfType<FlatSymbolRefAttr>("instance"));
  Operation *target = instance ? nodal::resolveInstanceModuleDefinition(instance) : nullptr;
  Operation *parameter = findDirectSymbol(
      target, getOperation()->getAttrOfType<FlatSymbolRefAttr>("parameter").getValue(),
      "nodal.parameter");
  auto declared = parameter ? parameter->getAttrOfType<TypeAttr>("type") : TypeAttr();
  Type actual = getOperation()->getOperand(0).getType();
  if (declared &&
      (llvm::isa<nodal::EnumType>(declared.getValue()) || llvm::isa<nodal::EnumType>(actual)) &&
      declared.getValue() != actual)
    return emitOpError("NODAL-ENUM-054-020: enum override requires the exact semantic enum type");
  return verifyGeneratedStructuralOwnership(getOperation());
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
    if (!text || !oneOf(text.getValue(), {"topology", "component_count", "equation_count", "shape",
                                          "rank", "width"}))
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
      if (failed(verifyParameterDefaultContract(&operation)))
        return failure();
    }

    llvm::SmallVector<Operation *> structuralOperations;
    owner->walk([&](Operation *operation) {
      if (operation != owner && enclosingNodalModule(operation) == owner &&
          structuralBody(operation->getParentOp()))
        structuralOperations.push_back(operation);
    });
    if (failed(verifyGeneratedIntegerBounds(owner)))
      return failure();

    for (Operation *structuralOperation : structuralOperations) {
      Operation &operation = *structuralOperation;
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
            failed(checkInstanceConstraints(parameter, *normalized, &operation, &operation)))
          return operation.emitOpError(
              "NODAL-PARAMETER-OVERRIDE-001: bound override violates parameter contract");
      }
    }

    for (Operation *structuralOperation : structuralOperations) {
      Operation &operation = *structuralOperation;
      if (!isNamed(&operation, "nodal.parameter_override"))
        continue;
      auto instanceReference = operation.getAttrOfType<FlatSymbolRefAttr>("instance");
      auto parameterReference = operation.getAttrOfType<FlatSymbolRefAttr>("parameter");
      Operation *instance = resolveStructuralInstance(&operation, instanceReference);
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
      if (isNamed(operation.getParentOp(), "nodal.generate") &&
          instance->getParentOp() == operation.getParentOp()) {
        auto declared = parameter->getAttrOfType<TypeAttr>("type");
        if (!declared || !declared.getValue().isSignlessInteger(64) ||
            operation.getNumOperands() != 1 ||
            operation.getOperand(0).getType() != declared.getValue() ||
            failed(resolveGeneratedInstanceIntegerParameterFacts(
                instance, parameter,
                isStructuralParameter(parameter) ? ParameterIntegerMode::Storage
                                                 : ParameterIntegerMode::MathematicalSigned64)))
          return operation.emitOpError(
              "NODAL-PARAMETER-OVERRIDE-001: generated override lacks all-lane formal proof");
        continue;
      }
      Operation *actual =
          operation.getNumOperands() == 1 ? operation.getOperand(0).getDefiningOp() : nullptr;
      if (!actual || actual->getParentOp() != owner)
        return operation.emitOpError(
            "NODAL-PARAMETER-OVERRIDE-001: override requires an invariant module-owned expression");
      auto declared = parameter->getAttrOfType<TypeAttr>("type");
      Type actualType = operation.getOperand(0).getType();
      if (declared &&
          (llvm::isa<nodal::EnumType>(declared.getValue()) ||
           llvm::isa<nodal::EnumType>(actualType)) &&
          declared.getValue() != actualType)
        return operation.emitOpError(
            "NODAL-ENUM-054-020: enum override requires the exact semantic enum type");
      llvm::DenseSet<Operation *> stack;
      auto evaluated = evaluateValue(operation.getOperand(0), &operation, stack);
      FailureOr<EvaluatedConstant> normalized = failure();
      if (!failed(evaluated))
        normalized = normalizeForParameter(*evaluated, parameter);
      if (failed(normalized) ||
          failed(checkInstanceConstraints(parameter, *normalized, instance, &operation)))
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
  if (failed(ParameterRenderBudget().check(value)))
    return failure();
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

  if (suffix.size() > kMaximumParameterRenderedBytes - 7 ||
      rendered->size() > kMaximumParameterRenderedBytes - 7 - suffix.size())
    return failure();
  Operation *definition = value.getDefiningOp();
  if (definition && isNamed(definition, "nodal.const_literal") &&
      !definition->getAttrOfType<FlatSymbolRefAttr>("unit"))
    return *rendered + suffix.str();

  return (llvm::Twine("(") + *rendered + " * 1" + suffix + ")").str();
}

namespace nodal {
namespace {
/// Admission for structural predicates lives beside Integer DAG arithmetic.
/// Neither contextual width formals nor procedural loop induction are static.
class StructuralIntegerQuery {
public:
  explicit StructuralIntegerQuery(
      GeneratedQueryContext &query, IntegerQueryPurpose purpose = IntegerQueryPurpose::Structural,
      bool captureProbe = false,
      ParameterIntegerMode mode = ParameterIntegerMode::MathematicalSigned64,
      Operation *instance = nullptr,
      ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> parentCoordinates = {},
      const StructuralIntegerLeaves *leaves = nullptr)
      : query(query), purpose(purpose), captureProbe(captureProbe), mode(mode), instance(instance),
        parentCoordinates(parentCoordinates), leaves(leaves) {}
  FailureOr<StructuralIntegerFacts> operation(Operation *op) {
    if (!op || op->getNumResults() != 1 || !op->getResult(0).getType().isSignlessInteger(64))
      return failure();
    if (!query.charge())
      return failure();
    return compute(op->getResult(0), op, 0);
  }
  FailureOr<StructuralIntegerFacts> resolve(Value value, Operation *use, unsigned depth = 0) {
    if (depth >= 128 || !query.charge() || !value || !value.getType().isSignlessInteger(64) ||
        !use || failed(integerValueScope(value, use, query)))
      return failure();
    if (auto found = memo.find(value); found != memo.end())
      return found->second;
    if (!active.insert(value).second)
      return failure();
    auto result = compute(value, use, depth);
    active.erase(value);
    if (succeeded(result) && mode == ParameterIntegerMode::Storage && result->expression &&
        result->expression->bounds.lower < 0)
      return failure();
    if (succeeded(result))
      memo.try_emplace(value, *result);
    return result;
  }

private:
  FailureOr<StructuralIntegerFacts> compute(Value value, Operation *use, unsigned depth) {
    Operation *module = use->getParentOfType<ModuleOp>();
    auto ownedValue = [&](Value original) -> FailureOr<InstantiatedIntegerExpression> {
      if (!query.sharedWork)
        return resolveOwnedIntegerExpression(module, original, mode);
      return instanceExpression(
          InstanceIntegerAnalysis(module, nullptr, false,
                                  mode == ParameterIntegerMode::MathematicalSigned64, nullptr, {},
                                  false, false, &query)
              .value(original));
    };
    auto ownedParameter = [&](Operation *original) -> FailureOr<InstantiatedIntegerExpression> {
      if (!query.sharedWork)
        return resolveOwnedIntegerParameter(module, original, mode);
      return instanceExpression(
          InstanceIntegerAnalysis(module, nullptr, false,
                                  mode == ParameterIntegerMode::MathematicalSigned64, nullptr, {},
                                  false, false, &query)
              .parameter(original));
    };
    if (auto arg = llvm::dyn_cast<BlockArgument>(value)) {
      auto h = integerRegionHeader(arg.getOwner(), query);
      if (failed(h))
        return failure();
      if (h->kind == DigitalRegionKind::Generate) {
        // Capturing an induction preserves its actual Shape/predicate identity,
        // but never turns it (or arithmetic containing it) into a type binding.
        if (captureProbe)
          return failure();
        auto domain = generatedDomain(h->operation, query);
        if (failed(domain) || domain->induction != arg)
          return failure();
        if (!domain->attainedInductionBounds)
          return StructuralIntegerFacts{};
        return StructuralIntegerFacts{
            makeOpaqueIntegerExpression(arg, *domain->attainedInductionBounds)};
      }
      auto source = integerCaptureSource(arg, query);
      if (failed(source) || !*source)
        return failure();
      return resolve(*source, h->operation, depth + 1);
    }
    Operation *op = value.getDefiningOp();
    if (!op || op->hasAttr("unit") || op->getNumResults() != 1 || op->getNumRegions() ||
        op->getNumSuccessors())
      return failure();
    if (op->getParentOp() == module) {
      FailureOr<InstantiatedIntegerExpression> expression = failure();
      if (captureProbe) {
        // Capture classification must not diagnose a valid runtime Integer as
        // an invalid static bound. The actual static-use path below is unchanged.
        expression = ownedValue(value);
        if (failed(expression))
          return failure();
      }
      llvm::DenseSet<Value> activeDependencies, visited;
      if (failed(
              verifyGeneratedBoundDependencies(value, module, use, activeDependencies, visited,
                                               query, purpose == IntegerQueryPurpose::Structural,
                                               purpose == IntegerQueryPurpose::ConstructorBinding)))
        return failure();
      if (!captureProbe)
        expression = leaves     ? leaves->value(value)
                     : instance ? resolveSelectedInstanceIntegerExpression(instance, value,
                                                                           parentCoordinates, mode)
                                : ownedValue(value);
      return failed(expression)
                 ? FailureOr<StructuralIntegerFacts>(failure())
                 : FailureOr<StructuralIntegerFacts>(StructuralIntegerFacts{*expression});
    }
    if (!isDigitalRegionOwner(op->getParentOp()))
      return failure();
    StringRef name = op->getName().getStringRef();
    if (name == "nodal.const_parameter_ref") {
      auto ref = op->getAttrOfType<FlatSymbolRefAttr>("parameter");
      Operation *parameter =
          ref ? findDirectSymbol(module, ref.getValue(), "nodal.parameter") : nullptr;
      if (op->getNumOperands() || !parameter || parameter->hasAttr("unit") ||
          !parameter->getAttrOfType<TypeAttr>("type") ||
          parameter->getAttrOfType<TypeAttr>("type").getValue() != value.getType() ||
          (purpose == IntegerQueryPurpose::Structural &&
           textAttr(parameter, "variability") != "fixed" && !isStructuralParameter(parameter)))
        return failure();
      if (purpose == IntegerQueryPurpose::ConstructorBinding &&
          textAttr(parameter, "variability") != "fixed") {
        for (Operation &operation : *moduleBody(module))
          if (!query.charge())
            return failure();
        if (!hasBoundedRange(parameter))
          return failure();
      }
      if (auto definition = findParameterValue(module, symbolName(parameter))) {
        if (textAttr(parameter, "variability") == "fixed") {
          if (definition->getNumOperands() != 1)
            return failure();
          llvm::DenseSet<Value> activeDependencies, visited;
          if (failed(verifyGeneratedBoundDependencies(
                  definition->getOperand(0), module, use, activeDependencies, visited, query,
                  purpose == IntegerQueryPurpose::Structural,
                  purpose == IntegerQueryPurpose::ConstructorBinding)))
            return failure();
        }
      }
      auto expression = leaves     ? leaves->parameter(parameter)
                        : instance ? resolveSelectedInstanceIntegerParameter(
                                         instance, parameter, parentCoordinates, mode)
                                   : ownedParameter(parameter);
      return failed(expression)
                 ? FailureOr<StructuralIntegerFacts>(failure())
                 : FailureOr<StructuralIntegerFacts>(StructuralIntegerFacts{*expression});
    }
    if (name == "nodal.const_literal" || name == "nodal.constant") {
      auto literal = op->getAttrOfType<IntegerAttr>("value");
      if (op->getNumOperands() || !literal || literal.getType() != value.getType() ||
          !literal.getValue().isSignedIntN(64) || !validIntegerLiteralSpelling(op))
        return failure();
      return StructuralIntegerFacts{
          makeLiteralIntegerExpression(literal.getValue().getSExtValue())};
    }
    if (name != "nodal.const_expr")
      return failure();
    StringRef opcode = textAttr(op, "operator_name");
    unsigned arity = integerOperationArity(opcode);
    if (!arity || op->getNumOperands() != arity)
      return failure();
    SmallVector<InstantiatedIntegerExpression, 3> operands;
    bool unreachable = false;
    for (Value operand : op->getOperands()) {
      auto result = resolve(operand, op, depth + 1);
      if (failed(result))
        return failure();
      if (result->expression)
        operands.push_back(*result->expression);
      else
        unreachable = true;
    }
    if (unreachable)
      return StructuralIntegerFacts{};
    auto expression = composeIntegerExpression(opcode, operands);
    return failed(expression)
               ? FailureOr<StructuralIntegerFacts>(failure())
               : FailureOr<StructuralIntegerFacts>(StructuralIntegerFacts{*expression});
  }
  GeneratedQueryContext &query;
  IntegerQueryPurpose purpose;
  bool captureProbe;
  ParameterIntegerMode mode;
  Operation *instance;
  ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> parentCoordinates;
  const StructuralIntegerLeaves *leaves;
  llvm::DenseMap<Value, StructuralIntegerFacts> memo;
  llvm::DenseSet<Value> active;
};
} // namespace
LogicalResult verifyStructuralIntegerOperation(Operation *op) {
  GeneratedQueryContext query;
  return success(succeeded(StructuralIntegerQuery(query).operation(op)));
}
namespace {
LogicalResult verifyConstructorBindingIntegerUses(Value root) {
  GeneratedQueryContext query;
  // Resolve only real structural capture ancestry, preserving the original
  // producer's complete use inventory instead of proving one convenient alias.
  unsigned depth = 0;
  while (auto argument = llvm::dyn_cast<BlockArgument>(root)) {
    if (++depth >= 128 || !query.charge())
      return failure();
    auto source = digitalRegionCaptureSource(argument);
    if (failed(source) || !*source)
      return failure();
    root = *source;
  }
  Operation *operation = root ? root.getDefiningOp() : nullptr;
  if (!operation || operation->getNumResults() != 1 || !root.getType().isSignlessInteger(64) ||
      !isDigitalRegionOwner(operation->getParentOp()))
    return failure();
  // This admission is structural-only even when called independently of the
  // enclosing operation verifier. Runtime/procedural ancestry cannot acquire a
  // static binding purpose merely by containing a generated sink.
  Operation *module = enclosingNodalModule(operation);
  for (Operation *scope = operation->getParentOp(); scope != module; scope = scope->getParentOp())
    if (!scope || ++depth >= 128 || !query.charge() ||
        (!isNamed(scope, "nodal.generate") && !isNamed(scope, "nodal.structural_if")))
      return failure();
  if (!module)
    return failure();
  llvm::DenseSet<Value> visited, active;
  llvm::DenseSet<Operation *> sinks;
  std::function<LogicalResult(Value, unsigned)> visit = [&](Value value, unsigned depth) {
    if (depth >= 128 || !query.charge() || !value || !value.getType().isSignlessInteger(64) ||
        value.use_empty())
      return failure();
    if (visited.contains(value))
      return success();
    if (!active.insert(value).second)
      return failure();
    auto release = llvm::scope_exit([&] { active.erase(value); });
    for (OpOperand &use : value.getUses()) {
      Operation *user = use.getOwner();
      if (!query.charge() || failed(verifyDigitalValueScope(value, user)))
        return failure();
      if (isNamed(user, "nodal.const_expr")) {
        if (user->getNumResults() != 1 || user->getNumRegions() || user->getNumSuccessors() ||
            failed(visit(user->getResult(0), depth + 1)))
          return failure();
        continue;
      }
      if (isNamed(user, "nodal.structural_if")) {
        bool captured = false;
        for (Region &region : user->getRegions()) {
          if (!region.hasOneBlock())
            return failure();
          auto header = inspectDigitalRegion(&region.front());
          if (failed(header) || header->kind != DigitalRegionKind::StructuralArm)
            return failure();
          for (BlockArgument argument : region.front().getArguments()) {
            if (!query.charge())
              return failure();
            auto source = digitalRegionCaptureSource(argument);
            if (failed(source))
              return failure();
            if (*source != value)
              continue;
            captured = true;
            // Each declared alias must have a real path to an ordinary sink.
            // Neither a separate direct sink nor a live counterpart arm can
            // justify an unused retained capture.
            if (failed(visit(argument, depth + 1)))
              return failure();
          }
        }
        if (!captured)
          return failure();
        continue;
      }
      if (!isNamed(user, "nodal.parameter_override") || user->getNumOperands() != 1 ||
          !isNamed(user->getParentOp(), "nodal.generate") ||
          failed(verifyGeneratedStructuralOwnership(user)))
        return failure();
      Operation *instance =
          resolveStructuralInstance(user, user->getAttrOfType<FlatSymbolRefAttr>("instance"));
      Operation *child = resolveInstanceModuleDefinition(instance);
      Block *childBody = moduleBody(child);
      if (!childBody)
        return failure();
      // Preflight the original declaration/default/constraint lookup inventory
      // before any of those scans, sharing the DAG/domain query's finite work.
      for (Operation &unused : *childBody)
        if (!query.charge())
          return failure();
      auto reference = user->getAttrOfType<FlatSymbolRefAttr>("parameter");
      Operation *formal = child && reference
                              ? findDirectSymbol(child, reference.getValue(), "nodal.parameter")
                              : nullptr;
      auto type = formal ? formal->getAttrOfType<TypeAttr>("type") : TypeAttr();
      if (!instance || instance->getParentOp() != user->getParentOp() || !formal || !type ||
          !type.getValue().isSignlessInteger(64) ||
          getParameterClassification(formal) != "ordinary" ||
          textAttr(formal, "variability") != "symbolic" ||
          failed(verifyParameterDeclaration(formal)) ||
          failed(verifyParameterDefaultContract(formal)) ||
          failed(verifyGeneratedStructuralOwnership(instance)))
        return failure();
      if (sinks.insert(user).second) {
        if (failed(generatedDomain(instance->getParentOp(), query)))
          return failure();
        InstanceIntegerAnalysis analysis(enclosingNodalModule(instance), instance, false, true,
                                         nullptr, {}, true, true, &query);
        if (failed(analysis.parameter(formal)))
          return failure();
      }
    }
    visited.insert(value);
    return success();
  };
  return success(succeeded(visit(root, 0)) && !sinks.empty());
}
} // namespace
LogicalResult verifyConstructorBindingIntegerOperation(Operation *operation) {
  return operation && operation->getNumResults() == 1
             ? verifyConstructorBindingIntegerUses(operation->getResult(0))
             : failure();
}
LogicalResult verifyConstructorBindingIntegerCapture(Value value, Operation *use) {
  return succeeded(verifyDigitalValueScope(value, use)) ? verifyConstructorBindingIntegerUses(value)
                                                        : failure();
}
FailureOr<StructuralIntegerFacts> resolveStructuralIntegerExpression(Value value, Operation *use) {
  GeneratedQueryContext query;
  return structuralInteger(value, use, query);
}
FailureOr<StructuralIntegerFacts> resolveStructuralIntegerExpression(Value value, Operation *use,
                                                                     ParameterIntegerMode mode) {
  GeneratedQueryContext query;
  return StructuralIntegerQuery(query, IntegerQueryPurpose::Structural, false, mode)
      .resolve(value, use);
}
FailureOr<StructuralIntegerFacts> resolveStructuralIntegerExpression(Value value, Operation *use,
                                                                     unsigned &remainingWork,
                                                                     ParameterIntegerMode mode) {
  GeneratedQueryContext query;
  query.maximumWork = std::min(remainingWork, 65536u);
  query.sharedWork = true;
  auto release =
      llvm::scope_exit([&] { remainingWork -= std::min(query.work, query.maximumWork); });
  return StructuralIntegerQuery(query, IntegerQueryPurpose::Structural, false, mode)
      .resolve(value, use);
}
FailureOr<StructuralIntegerFacts> resolveSelectedInstancePathStructuralIntegerExpression(
    Operation *rootModule, ArrayRef<SelectedIntegerInstanceStep> path, Value value, Operation *use,
    unsigned &remainingWork, ParameterIntegerMode mode) {
  GeneratedQueryContext query;
  query.maximumWork = std::min(remainingWork, 65536u);
  query.sharedWork = true;
  auto release =
      llvm::scope_exit([&] { remainingWork -= std::min(query.work, query.maximumWork); });
  Operation *outer = rootModule ? rootModule->getParentOp() : nullptr;
  auto selector =
      outer ? outer->getAttrOfType<FlatSymbolRefAttr>("nodal.root.module") : FlatSymbolRefAttr();
  if (!isNamed(rootModule, "nodal.module") || !isa_and_nonnull<mlir::ModuleOp>(outer) ||
      !selector || SymbolTable::lookupSymbolIn(outer, selector) != rootModule || path.empty() ||
      path.size() > 128 || !use)
    return failure();
  Operation *expected = rootModule;
  llvm::DenseSet<Operation *> seenModules{rootModule}, seenEdges;
  SmallVector<std::unique_ptr<InstanceIntegerAnalysis>, 4> originals, actuals;
  for (const auto &step : path) {
    Operation *instance = step.instance;
    Operation *child = resolveInstanceModuleDefinition(instance);
    if (!query.charge() || !instance || !child || enclosingNodalModule(instance) != expected ||
        !seenEdges.insert(instance).second || !seenModules.insert(child).second ||
        failed(verifyGeneratedStructuralOwnership(instance)))
      return failure();
    // Every original enclosing Generate must be attained. A local empty body may
    // still return absent facts, but it cannot be selected as an ancestor edge.
    for (Operation *scope = instance->getParentOp(); scope && scope != expected;
         scope = scope->getParentOp()) {
      if (!query.charge() || !isNamed(scope, "nodal.generate"))
        return failure();
      auto domain = generatedDomain(scope, query);
      if (failed(domain) || !domain->attainedInductionBounds)
        return failure();
    }
    originals.push_back(std::make_unique<InstanceIntegerAnalysis>(
        expected, instance, false, mode == ParameterIntegerMode::MathematicalSigned64, nullptr,
        ArrayRef<std::pair<Value, InstantiatedIntegerExpression>>{}, true, false, &query));
    auto *parent = actuals.empty() ? nullptr : actuals.back().get();
    actuals.push_back(std::make_unique<InstanceIntegerAnalysis>(
        expected, instance, false, mode == ParameterIntegerMode::MathematicalSigned64, nullptr,
        step.coordinates, true, false, &query, parent, originals.back().get()));
    expected = child;
  }
  if (enclosingNodalModule(use) != expected ||
      failed(StructuralIntegerQuery(query, IntegerQueryPurpose::Structural, false, mode)
                 .resolve(value, use)))
    return failure();
  auto valueLeaf = [&](Value input) { return instanceExpression(actuals.back()->value(input)); };
  auto parameterLeaf = [&](Operation *input) {
    return instanceExpression(actuals.back()->parameter(input));
  };
  StructuralIntegerLeaves leaves{valueLeaf, parameterLeaf};
  return StructuralIntegerQuery(query, IntegerQueryPurpose::Structural, false, mode, nullptr, {},
                                &leaves)
      .resolve(value, use);
}
FailureOr<StructuralIntegerFacts> resolveSelectedInstanceStructuralIntegerExpression(
    Operation *instance, Value value, Operation *use,
    ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> parentCoordinates,
    unsigned &remainingWork, ParameterIntegerMode mode) {
  GeneratedQueryContext query;
  query.maximumWork = std::min(remainingWork, 65536u);
  query.sharedWork = true;
  auto release =
      llvm::scope_exit([&] { remainingWork -= std::min(query.work, query.maximumWork); });
  Operation *child = instance ? resolveInstanceModuleDefinition(instance) : nullptr;
  Operation *family = instance ? instance->getParentOp() : nullptr;
  if (!child || !use || enclosingNodalModule(use) != child || !isNamed(family, "nodal.generate") ||
      failed(verifyGeneratedStructuralOwnership(instance)))
    return failure();
  auto domain = generatedDomain(family, query);
  if (failed(domain) || !domain->attainedInductionBounds)
    return failure();
  // Check every original dependency before contextual resolution, including
  // those inside an empty child region. Absence never bypasses binding checks.
  if (failed(StructuralIntegerQuery(query, IntegerQueryPurpose::Structural, false, mode)
                 .resolve(value, use)))
    return failure();
  return StructuralIntegerQuery(query, IntegerQueryPurpose::Structural, false, mode, instance,
                                parentCoordinates)
      .resolve(value, use);
}
FailureOr<StructuralIntegerFacts> resolveBehavioralIntegerExpression(Value value, Operation *use) {
  GeneratedQueryContext query;
  return StructuralIntegerQuery(query, IntegerQueryPurpose::Behavioral).resolve(value, use);
}
FailureOr<StructuralIntegerFacts> probeBehavioralIntegerCapture(Value value, Operation *use) {
  GeneratedQueryContext query;
  return StructuralIntegerQuery(query, IntegerQueryPurpose::Behavioral, true).resolve(value, use);
}
} // namespace nodal

namespace {
FailureOr<nodal::StructuralIntegerFacts> structuralInteger(Value value, Operation *use,
                                                           GeneratedQueryContext &query) {
  return nodal::StructuralIntegerQuery(query).resolve(value, use);
}
FailureOr<nodal::StructuralIntegerFacts>
structuralIntegerWithLeaves(Value value, Operation *use, GeneratedQueryContext &query,
                            nodal::ParameterIntegerMode mode, const StructuralIntegerLeaves *leaves,
                            IntegerQueryPurpose purpose) {
  auto original = nodal::StructuralIntegerQuery(query, purpose, false, mode).resolve(value, use);
  if (failed(original) || !leaves)
    return original;
  return nodal::StructuralIntegerQuery(query, purpose, false, mode, nullptr, {}, leaves)
      .resolve(value, use);
}
} // namespace

namespace nodal {
namespace {
using EmissionStatus = IntegerExpressionEmissionStatus;
struct CheckedIntegerEmission {
  Operation *use;
  ArrayRef<Value> bindings;
  unsigned &work;
  EmissionStatus status = EmissionStatus::Proved;
  llvm::DenseMap<const InstantiatedIntegerNode *, unsigned> states;
  llvm::DenseMap<const InstantiatedIntegerNode *, InstantiatedIntegerExpression> checked;
  llvm::DenseSet<Value> used;
  SmallVector<const InstantiatedIntegerNode *, 16> order;
  CheckedIntegerEmission(Operation *use, ArrayRef<Value> bindings, unsigned &work)
      : use(use), bindings(bindings), work(work) {}

  bool reject(EmissionStatus why) { status = why; return false; }
  bool charge() {
    if (!work)
      return reject(EmissionStatus::ResourceLimit);
    --work;
    return true;
  }
  bool visit(const InstantiatedIntegerNode *node, unsigned depth = 0) {
    if (!charge())
      return false;
    if (!node || depth > 128)
      return reject(node ? EmissionStatus::ResourceLimit : EmissionStatus::Invalid);
    if (states.lookup(node) == 2)
      return true;
    if (states.lookup(node) == 1)
      return reject(EmissionStatus::Invalid);
    states[node] = 1;
    InstantiatedIntegerExpression value;
    switch (node->kind) {
    case InstantiatedIntegerNode::Kind::Literal:
      if (!node->operands.empty())
        return reject(EmissionStatus::Invalid);
      value = makeLiteralIntegerExpression(node->literal);
      break;
    case InstantiatedIntegerNode::Kind::OpaqueValue: {
      if (!node->identity || !node->identity.getType().isSignlessInteger(64) ||
          !node->operands.empty() || !llvm::is_contained(bindings, node->identity))
        return reject(EmissionStatus::Invalid);
      auto facts = resolveStructuralIntegerExpression(node->identity, use, work);
      if (failed(facts) || !facts->expression)
        return reject(work ? EmissionStatus::Invalid : EmissionStatus::ResourceLimit);
      value = *facts->expression;
      used.insert(node->identity);
      if (!equivalentInstantiatedIntegerExpressions(
              value, makeOpaqueIntegerExpression(node->identity, node->bounds)))
        return reject(EmissionStatus::Invalid);
      break;
    }
    case InstantiatedIntegerNode::Kind::ParentParameter:
      return reject(EmissionStatus::Unsupported);
    case InstantiatedIntegerNode::Kind::Apply: {
      if (!oneOf(node->opcode, {"add", "sub", "mul", "div"}))
        return reject(EmissionStatus::Unsupported);
      if (node->operands.size() != 2)
        return reject(EmissionStatus::Invalid);
      SmallVector<InstantiatedIntegerExpression, 2> operands;
      for (const auto &operand : node->operands) {
        if (!visit(operand.get(), depth + 1))
          return false;
        operands.push_back(checked.lookup(operand.get()));
      }
      auto composed = composeIntegerExpression(node->opcode, operands);
      if (failed(composed))
        return reject(EmissionStatus::Invalid);
      value = *composed;
      break;
    }
    }
    if (value.bounds.lower != node->bounds.lower || value.bounds.upper != node->bounds.upper)
      return reject(EmissionStatus::Invalid);
    checked[node] = value;
    states[node] = 2;
    order.push_back(node);
    return true;
  }
  bool run(const InstantiatedIntegerExpression &expression) {
    if (!use || !use->getBlock() || !expression.root)
      return reject(EmissionStatus::Invalid);
    llvm::DenseSet<Value> unique;
    for (Value value : bindings) {
      if (!charge())
        return false;
      if (!value || !unique.insert(value).second)
        return reject(EmissionStatus::Invalid);
    }
    if (!visit(expression.root.get()))
      return false;
    if (expression.bounds.lower != expression.root->bounds.lower ||
        expression.bounds.upper != expression.root->bounds.upper || used.size() != unique.size())
      return reject(EmissionStatus::Invalid);
    return true;
  }
};
} // namespace

IntegerExpressionEmissionStatus materializeStructuralIntegerExpression(
    const InstantiatedIntegerExpression &expression, Operation *use,
    ArrayRef<Value> bindings, llvm::StringRef prefix, Value &result, unsigned &work) {
  CheckedIntegerEmission proof{use, bindings, work};
  if (!proof.run(expression))
    return proof.status;
  if (prefix.empty())
    return EmissionStatus::Invalid;
  if (prefix.size() > 4096)
    return EmissionStatus::ResourceLimit;
  const std::string pathPrefix = (prefix + ".integer_").str();
  for (Operation &operation : *use->getBlock()) {
    if (!proof.charge())
      return proof.status;
    auto metadata = operation.getAttrOfType<DictionaryAttr>("metadata");
    auto path = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
    if (path && path.getValue().starts_with(pathPrefix))
      return EmissionStatus::Invalid;
  }
  OpBuilder builder(use);
  SmallVector<Operation *, 16> created;
  bool commit = false;
  auto rollback = llvm::scope_exit([&] {
    if (!commit)
      for (Operation *operation : llvm::reverse(created))
        operation->erase();
  });
  llvm::DenseMap<const InstantiatedIntegerNode *, Value> values;
  for (const auto *node : proof.order) {
    if (!proof.charge())
      return proof.status;
    if (node->kind == InstantiatedIntegerNode::Kind::OpaqueValue) {
      values[node] = node->identity;
      continue;
    }
    OperationState state(use->getLoc(), node->kind == InstantiatedIntegerNode::Kind::Literal
                                           ? ConstLiteralOp::getOperationName()
                                           : ConstExprOp::getOperationName());
    state.addTypes(builder.getI64Type());
    state.addAttribute("metadata", builder.getDictionaryAttr({builder.getNamedAttr(
        "semantic_path", builder.getStringAttr(pathPrefix + std::to_string(created.size())))}));
    if (node->kind == InstantiatedIntegerNode::Kind::Literal) {
      state.addAttribute("value", builder.getI64IntegerAttr(node->literal));
      state.addAttribute("spelling", builder.getStringAttr(std::to_string(node->literal)));
    } else {
      state.addAttribute("operator_name", builder.getStringAttr(node->opcode));
      for (const auto &operand : node->operands)
        state.addOperands(values.lookup(operand.get()));
    }
    Operation *operation = builder.create(state);
    created.push_back(operation);
    values[node] = operation->getResult(0);
  }
  Value candidate = values.lookup(expression.root.get());
  auto facts = resolveStructuralIntegerExpression(candidate, use, work);
  if (failed(facts) || !facts->expression ||
      !equivalentInstantiatedIntegerExpressions(*facts->expression, expression) ||
      facts->expression->bounds.lower != expression.bounds.lower ||
      facts->expression->bounds.upper != expression.bounds.upper)
    return work ? EmissionStatus::Invalid : EmissionStatus::ResourceLimit;
  result = candidate;
  commit = true;
  return EmissionStatus::Proved;
}

IntegerExpressionEmissionStatus renderStructuralIntegerExpression(
    const InstantiatedIntegerExpression &expression, Operation *use,
    ArrayRef<std::pair<Value, std::string>> bindings, std::string &result,
    unsigned &work, uint64_t maximumBytes) {
  if (bindings.size() > work)
    return EmissionStatus::ResourceLimit;
  SmallVector<Value, 4> originals;
  llvm::DenseMap<Value, StringRef> names;
  for (const auto &binding : bindings) {
    originals.push_back(binding.first);
    names[binding.first] = binding.second;
  }
  CheckedIntegerEmission proof{use, originals, work};
  if (!proof.run(expression))
    return proof.status;
  llvm::DenseMap<const InstantiatedIntegerNode *, std::string> text;
  uint64_t bytes = 0;
  for (const auto *node : proof.order) {
    if (!proof.charge())
      return proof.status;
    std::string value;
    if (node->kind == InstantiatedIntegerNode::Kind::Literal) {
      value = std::to_string(node->literal);
    } else if (node->kind == InstantiatedIntegerNode::Kind::OpaqueValue) {
      StringRef name = names.lookup(node->identity);
      if (name.empty())
        return EmissionStatus::Invalid;
      if (name.size() > maximumBytes - bytes)
        return EmissionStatus::ResourceLimit;
      value = name.str();
    } else {
      SmallVector<std::string, 2> operands;
      uint64_t required = 5; // binary formatter punctuation and one-char operator
      for (const auto &operand : node->operands) {
        const auto &child = text[operand.get()];
        if (child.size() > maximumBytes - bytes ||
            required > maximumBytes - bytes - child.size())
          return EmissionStatus::ResourceLimit;
        required += child.size();
        operands.push_back(child);
      }
      auto formatted = formatParameterOperator(node->opcode, operands);
      if (failed(formatted))
        return EmissionStatus::Unsupported;
      value = std::move(*formatted);
    }
    if (value.size() > maximumBytes - bytes)
      return EmissionStatus::ResourceLimit;
    bytes += value.size();
    text[node] = std::move(value);
  }
  result = text[expression.root.get()];
  return EmissionStatus::Proved;
}
} // namespace nodal
