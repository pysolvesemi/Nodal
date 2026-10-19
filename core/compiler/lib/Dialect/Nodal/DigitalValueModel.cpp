#include "nodal/Dialect/Nodal/DigitalValueModel.h"

#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalMaterializationModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/StructuralIfModel.h"

#include "llvm/ADT/APFloat.h"
#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/ScopeExit.h"
#include "llvm/ADT/SmallString.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringSwitch.h"

#include <algorithm>
#include <optional>

using namespace mlir;

namespace nodal {
namespace {
constexpr unsigned maximumProofDepth = 128;
constexpr unsigned maximumProofNodes = 65536;
constexpr unsigned maximumProofBits = 4096;

StringRef text(Operation *operation, StringRef name) {
  auto attribute = operation->getAttrOfType<StringAttr>(name);
  return attribute ? attribute.getValue() : StringRef();
}

LogicalResult reject(Operation *operation, StringRef message) {
  return operation->emitOpError("NODAL-DIGITAL-054-008: ") << message;
}

StringRef scalarKind(Type type) {
  if (llvm::isa<BitsType>(type))
    return "bits";
  if (llvm::isa<UIntType>(type))
    return "uint";
  if (llvm::isa<SIntType>(type))
    return "sint";
  if (auto symbolic = llvm::dyn_cast<ParametricIntegerType>(type))
    if (symbolic.getKind() == "bits" || symbolic.getKind() == "uint" ||
        symbolic.getKind() == "sint")
      return symbolic.getKind();
  return {};
}

Operation *owner(Operation *operation) {
  if (!operation)
    return nullptr;
  if (llvm::isa<nodal::ModuleOp>(operation))
    return operation;
  return operation->getParentOfType<nodal::ModuleOp>();
}

Operation *valueOwner(Value value) {
  if (auto argument = llvm::dyn_cast<BlockArgument>(value))
    return owner(argument.getOwner()->getParentOp());
  return owner(value.getDefiningOp());
}

/// Normalize only the actual mathematical magnitude, never the declared width.
/// A signed APInt with one sign bit is the query-local exact value representation.
FailureOr<llvm::APInt> integerValue(IntegerAttr attribute) {
  if (!attribute)
    return failure();
  auto carrier = llvm::dyn_cast<IntegerType>(attribute.getType());
  if (!carrier)
    return failure();
  const llvm::APInt &bits = attribute.getValue();
  const bool unsignedValue = carrier.isUnsigned() || llvm::isa<BoolAttr>(attribute);
  unsigned required = unsignedValue ? bits.getActiveBits() + 1 : bits.getSignificantBits();
  required = std::max(1u, required);
  if (required > maximumProofBits + 1)
    return failure();
  return unsignedValue ? bits.zextOrTrunc(required) : bits.sextOrTrunc(required);
}

bool fits(const llvm::APInt &value, StringRef kind, int64_t width) {
  if (width <= 0)
    return false;
  if (kind == "sint")
    return static_cast<int64_t>(value.getSignificantBits()) <= width;
  return !value.isNegative() && static_cast<int64_t>(value.getActiveBits()) <= width;
}

FailureOr<ParameterIntegerBounds> widthBounds(Type type, Operation *module,
                                              const ParameterIntegerEnvironment *environment) {
  if (scalarKind(type).empty())
    return failure();
  auto bounds = inferDigitalWidthBounds(type, module, environment);
  if (failed(bounds) || bounds->lower <= 0 || bounds->upper < bounds->lower)
    return failure();
  return bounds;
}

bool widthRelated(Type source, Type result, Operation *module, int64_t increment,
                  const ParameterIntegerEnvironment *environment) {
  auto left = inferDigitalWidthExpression(source, module, environment);
  auto right = inferDigitalWidthExpression(result, module, environment);
  if (failed(left) || failed(right))
    return false;
  if (increment) {
    auto next = composeIntegerExpression("add", {*left, makeLiteralIntegerExpression(increment)});
    if (failed(next))
      return false;
    left = std::move(next);
  }
  return equivalentInstantiatedIntegerExpressions(*left, *right);
}

bool numericKind(StringRef kind) { return kind == "uint" || kind == "sint"; }

bool isBool(Type type, Operation *module, const ParameterIntegerEnvironment *environment) {
  if (auto bits = llvm::dyn_cast<BitsType>(type))
    return bits.getWidth() == 1;
  if (!environment || scalarKind(type) != "bits")
    return false;
  auto width = inferDigitalWidthExpression(type, module, environment);
  return succeeded(width) &&
         equivalentInstantiatedIntegerExpressions(*width, makeLiteralIntegerExpression(1));
}

unsigned expressionArity(StringRef opcode) {
  return llvm::StringSwitch<unsigned>(opcode)
      .Case("digital_alias", 1)
      .Cases({"digital_extend", "digital_truncate", "digital_wrap"}, 1)
      .Cases({"digital_saturate", "digital_resize_checked", "digital_to_signed"}, 1)
      .Cases({"digital_to_unsigned", "digital_reinterpret_signed", "digital_reinterpret_unsigned"},
             1)
      .Cases({"digital_to_real", "digital_neg", "digital_not", "bool_not"}, 1)
      .Cases({"digital_add", "digital_sub", "digital_mul"}, 2)
      .Cases({"digital_div", "digital_rem", "digital_min", "digital_max"}, 2)
      .Cases({"digital_eq", "digital_ne", "digital_lt", "digital_le"}, 2)
      .Cases({"digital_gt", "digital_ge", "digital_and", "digital_or"}, 2)
      .Cases({"digital_xor", "digital_concat", "digital_shl", "digital_shr"}, 2)
      .Cases({"digital_logical_shr", "bool_and", "bool_or"}, 2)
      .Cases({"digital_select", "digital_extract"}, 3)
      .Default(0);
}

LogicalResult checkLiteral(Operation *operation, const ParameterIntegerEnvironment *environment);

/// A static index is an actual, owned UInt literal SSA value. An invariant
/// hardware expression or a parameter's default is not a static literal.
std::optional<int64_t> staticIndex(Value value, const ParameterIntegerEnvironment *environment) {
  if (scalarKind(value.getType()) != "uint")
    return std::nullopt;
  Operation *definition = value.getDefiningOp();
  if (!definition || failed(checkLiteral(definition, environment)))
    return std::nullopt;
  auto exact = integerValue(definition->getAttrOfType<IntegerAttr>("value"));
  if (failed(exact) || exact->isNegative() || exact->getActiveBits() > 63)
    return std::nullopt;
  return exact->getZExtValue();
}

LogicalResult checkLiteral(Operation *operation, const ParameterIntegerEnvironment *environment) {
  std::optional<ParameterIntegerEnvironment> context;
  if (environment) {
    auto scoped = environment->atUse(operation);
    if (failed(scoped))
      return failure();
    context.emplace(*scoped);
    environment = &*context;
  }
  if (!operation || operation->getNumOperands() != 0 || operation->getNumResults() != 1 ||
      !operation->getAttrOfType<DictionaryAttr>("metadata") || operation->getNumRegions() != 0 ||
      operation->getNumSuccessors() != 0)
    return failure();
  Type type = operation->getResult(0).getType();
  auto value = operation->getAttrOfType<IntegerAttr>("value");
  if (operation->hasAttr("unit") ||
      !digitalIntegerAttributeFits(value, type, owner(operation), environment))
    return failure();
  auto carrier = llvm::cast<IntegerType>(value.getType());
  const llvm::APInt &bits = value.getValue();
  const bool unsignedValue = carrier.isUnsigned() || llvm::isa<BoolAttr>(value);
  const StringRef name = operation->getName().getStringRef();
  if (name == "nodal.const_literal") {
    auto spelling = operation->getAttrOfType<StringAttr>("spelling");
    llvm::SmallString<64> exact;
    bits.toString(exact, 10, !unsignedValue);
    if (!spelling)
      return failure();
    StringRef authored = spelling.getValue();
    const bool authoredNegative = authored.consume_front("-");
    if (authored.empty() ||
        !llvm::all_of(authored, [](char digit) { return digit >= '0' && digit <= '9'; }))
      return failure();
    authored = authored.ltrim('0');
    if (authored.empty())
      authored = "0";
    StringRef expected(exact);
    const bool expectedNegative = expected.consume_front("-");
    if (authored != expected || (authored != "0" && authoredNegative != expectedNegative))
      return failure();
    if (auto bitsType = llvm::dyn_cast<BitsType>(type))
      if (bitsType.getWidth() == 1 && spelling.getValue() != "0" && spelling.getValue() != "1")
        return failure();
  } else if (name != "nodal.constant") {
    return failure();
  }
  return success();
}

struct Facts {
  std::optional<llvm::APInt> constant;
  bool nonnegative = false;
  unsigned height = 1;
};

std::optional<llvm::APInt> boundedConstant(llvm::APInt value) {
  unsigned bits = std::max(1u, value.getSignificantBits());
  if (bits > maximumProofBits + 1)
    return std::nullopt;
  return value.sextOrTrunc(bits);
}

std::optional<llvm::APInt> binaryConstant(StringRef opcode, const llvm::APInt &left,
                                          const llvm::APInt &right) {
  unsigned bits = std::max(left.getBitWidth(), right.getBitWidth());
  if (opcode == "digital_add" || opcode == "digital_sub" || opcode == "digital_div" ||
      opcode == "digital_rem")
    // Division needs an extra sign bit for the signed minimum divided by -1,
    // including srem's internal quotient. These are proof widths, not storage widths.
    ++bits;
  else if (opcode == "digital_mul")
    bits = left.getBitWidth() + right.getBitWidth();
  // At most two bounded mathematical magnitudes are allocated, regardless of
  // hardware width. A result beyond the proof budget becomes unknown.
  llvm::APInt a = left.sext(bits), b = right.sext(bits);
  if (opcode == "digital_add")
    return boundedConstant(a + b);
  if (opcode == "digital_sub")
    return boundedConstant(a - b);
  if (opcode == "digital_mul")
    return boundedConstant(a * b);
  if (opcode == "digital_div" || opcode == "digital_rem") {
    if (b.isZero())
      return std::nullopt;
    return boundedConstant(opcode == "digital_div" ? a.sdiv(b) : a.srem(b));
  }
  if (opcode == "digital_min")
    return boundedConstant(a.slt(b) ? a : b);
  if (opcode == "digital_max")
    return boundedConstant(a.sgt(b) ? a : b);
  if (opcode == "digital_and" || opcode == "bool_and")
    return boundedConstant(a & b);
  if (opcode == "digital_or" || opcode == "bool_or")
    return boundedConstant(a | b);
  if (opcode == "digital_xor")
    return boundedConstant(a ^ b);
  bool result;
  if (opcode == "digital_eq")
    result = a == b;
  else if (opcode == "digital_ne")
    result = a != b;
  else if (opcode == "digital_lt")
    result = a.slt(b);
  else if (opcode == "digital_le")
    result = a.sle(b);
  else if (opcode == "digital_gt")
    result = a.sgt(b);
  else if (opcode == "digital_ge")
    result = a.sge(b);
  else
    return std::nullopt;
  return llvm::APInt(2, result);
}

std::optional<llvm::APInt> shiftedConstant(StringRef opcode, StringRef kind,
                                           ParameterIntegerBounds width, const llvm::APInt &source,
                                           int64_t amount) {
  if (source.isZero() || !amount)
    return source;
  if (opcode == "digital_shl") {
    if (amount > maximumProofBits)
      return std::nullopt;
    return boundedConstant(source.sext(source.getBitWidth() + amount).shl(amount));
  }
  if (opcode == "digital_logical_shr" && kind == "sint" && source.isNegative()) {
    if (width.lower != width.upper || width.upper > maximumProofBits)
      return std::nullopt;
    unsigned bits = static_cast<unsigned>(width.upper);
    if (amount >= bits)
      return llvm::APInt(1, 0);
    return boundedConstant(source.sextOrTrunc(bits).lshr(amount).zext(bits + 1));
  }
  if (amount >= source.getBitWidth())
    return llvm::APInt(1, source.isNegative());
  return boundedConstant(source.ashr(amount));
}

std::optional<llvm::APInt> convertedConstant(StringRef opcode, StringRef kind,
                                             ParameterIntegerBounds width,
                                             const llvm::APInt &source) {
  if (opcode == "digital_to_real")
    return std::nullopt;
  if (opcode == "digital_extend" || opcode == "digital_resize_checked" ||
      opcode == "digital_to_signed" || opcode == "digital_to_unsigned" ||
      fits(source, kind, width.lower))
    return source;
  // A symbolic result may produce different literal values at different legal
  // widths. Do not choose its default. Bound temporary bit-pattern allocation.
  if (width.lower != width.upper || width.upper > maximumProofBits)
    return std::nullopt;
  unsigned bits = static_cast<unsigned>(width.upper);
  if (opcode == "digital_saturate") {
    llvm::APInt lower(bits + 1, 0);
    llvm::APInt upper = llvm::APInt::getLowBitsSet(bits + 1, bits);
    if (kind == "sint") {
      lower = -llvm::APInt::getOneBitSet(bits + 1, bits - 1);
      upper = llvm::APInt::getLowBitsSet(bits + 1, bits - 1);
    }
    unsigned common = std::max(source.getBitWidth(), bits + 1);
    if (source.sext(common).slt(lower.sext(common)))
      return lower;
    return upper;
  }
  llvm::APInt encoded = source.sextOrTrunc(bits);
  return kind == "sint" ? encoded.sext(bits + 1) : encoded.zext(bits + 1);
}

class ValueVerifier {
public:
  explicit ValueVerifier(Operation *module,
                         const ParameterIntegerEnvironment *environment = nullptr)
      : module(module), environment(environment) {}

  FailureOr<Facts> value(Value input, unsigned depth = 0) {
    // Captured producer facts use the sealed enclosing context, never the child
    // binding namespace. Each such query has independent context-local caches.
    if (environment) {
      if (auto argument = llvm::dyn_cast<BlockArgument>(input);
          argument && argument.getOwner() != environment->scope()) {
        auto enclosing = environment->enclosingAtUse(environment->scope()->getParentOp());
        if (failed(enclosing))
          return failure();
        return ValueVerifier(module, &*enclosing).value(input, depth);
      }
      Operation *definition = input.getDefiningOp();
      if (definition && definition->getBlock() != environment->scope()) {
        if (definition->getParentOp() == module)
          return ValueVerifier(module).value(input, depth);
        auto enclosing = environment->enclosingAtUse(definition);
        if (failed(enclosing))
          return failure();
        return ValueVerifier(module, &*enclosing).value(input, depth);
      }
    }
    if (depth > maximumProofDepth || valueOwner(input) != module)
      return failure();
    auto known = facts.find(input);
    if (known != facts.end()) {
      if (depth + known->second.height - 1 > maximumProofDepth)
        return failure();
      return known->second;
    }
    if ((visited.insert(input).second && ++nodes > maximumProofNodes) ||
        !active.insert(input).second)
      return failure();
    if (!validValueType(input.getType()))
      return failure();
    Facts result;
    result.nonnegative = scalarKind(input.getType()) != "sint";
    if (auto argument = llvm::dyn_cast<BlockArgument>(input)) {
      auto source = digitalCollectionCaptureSource(argument, environment);
      if (failed(source))
        return failure();
      if (*source) {
        auto captured = value(*source, depth + 1);
        if (failed(captured))
          return failure();
        result = std::move(*captured);
        ++result.height;
        if (depth + result.height - 1 > maximumProofDepth)
          return failure();
      }
    } else if (Operation *definition = input.getDefiningOp()) {
      StringRef name = definition->getName().getStringRef();
      if ((name == "nodal.constant" || name == "nodal.const_literal") &&
          llvm::isa<EnumType>(input.getType())) {
        // Enum data remains opaque to scalar arithmetic, but its exact declared
        // code must still be independently validated before selecting it.
        if (failed(verifyEnumLiteralOperation(definition, environment)))
          return failure();
      } else if (name == "nodal.constant" || name == "nodal.const_literal") {
        if (failed(checkLiteral(definition, environment)))
          return failure();
        auto attribute = definition->getAttrOfType<IntegerAttr>("value");
        auto exact = integerValue(attribute);
        if (succeeded(exact))
          result.constant = std::move(*exact);
        auto carrier = llvm::cast<IntegerType>(attribute.getType());
        result.nonnegative = carrier.isUnsigned() || llvm::isa<BoolAttr>(attribute) ||
                             !attribute.getValue().isNegative();
      } else if (name == "nodal.digital_expr") {
        auto converted = expression(definition, depth);
        if (failed(converted))
          return failure();
        result = std::move(*converted);
      }
    }
    active.erase(input);
    facts.try_emplace(input, result);
    return result;
  }

  FailureOr<Facts> structure(Operation *operation, unsigned depth) {
    auto schemaAttribute = operation->getAttrOfType<TypeAttr>("struct_schema");
    auto schema =
        schemaAttribute ? llvm::dyn_cast<StructType>(schemaAttribute.getValue()) : nullptr;
    auto ordinal = operation->getAttrOfType<IntegerAttr>("field_ordinal");
    auto name = operation->getAttrOfType<StringAttr>("field_name");
    const bool get = text(operation, "operator_name") == "digital_struct_get";
    Type resultType = operation->getResult(0).getType();
    auto actual = llvm::dyn_cast<StructType>(
        get && operation->getNumOperands() == 1 ? operation->getOperand(0).getType() : resultType);
    auto invalid = [&]() -> FailureOr<Facts> {
      (void)reject(operation, "Struct operation requires independently verified schema family, "
                              "ordered operands and exact actual field types");
      return failure();
    };
    if (!schema || !actual || !isDigitalCombinationalValueType(resultType, module, environment) ||
        !sameDigitalStorageWidthFamily(schema, actual, module, environment))
      return invalid();
    if (get) {
      if (operation->getNumOperands() != 1 || !ordinal ||
          !ordinal.getType().isSignlessInteger(64) || !name || ordinal.getInt() < 0 ||
          static_cast<uint64_t>(ordinal.getInt()) >= actual.getFields().size())
        return invalid();
      auto field = llvm::cast<DictionaryAttr>(actual.getFields()[ordinal.getInt()]);
      if (field.getAs<StringAttr>("name") != name ||
          !equivalentDigitalStorageTypes(field.getAs<TypeAttr>("type").getValue(), resultType,
                                         module, environment))
        return invalid();
    } else {
      if (operation->hasAttr("field_ordinal") || operation->hasAttr("field_name") ||
          operation->getNumOperands() != actual.getFields().size())
        return invalid();
      for (unsigned index = 0; index < actual.getFields().size(); ++index) {
        auto field = llvm::cast<DictionaryAttr>(actual.getFields()[index]);
        if (!equivalentDigitalStorageTypes(field.getAs<TypeAttr>("type").getValue(),
                                           operation->getOperand(index).getType(), module,
                                           environment))
          return invalid();
      }
    }
    Facts result;
    for (Value input : operation->getOperands()) {
      auto proof = value(input, depth + 1);
      if (failed(proof))
        return invalid();
      result.height = std::max(result.height, proof->height + 1);
    }
    if (depth + result.height - 1 > maximumProofDepth)
      return invalid();
    result.nonnegative = scalarKind(resultType) != "sint";
    return result;
  }

  FailureOr<Facts> expression(Operation *operation, unsigned depth = 0) {
    std::optional<ParameterIntegerEnvironment> context;
    const auto *previous = environment;
    auto clearTypeCaches = [&] {
      widthFacts.clear();
      validTypes.clear();
      storageEqualities.clear();
    };
    const llvm::scope_exit restore([&] {
      environment = previous;
      if (context)
        clearTypeCaches();
    });
    if (environment) {
      auto scoped = environment->atUse(operation);
      if (failed(scoped))
        return failure();
      context.emplace(*scoped);
      environment = &*context;
      clearTypeCaches();
    }
    if (!module || owner(operation) != module || depth > maximumProofDepth ||
        operation->getName().getStringRef() != "nodal.digital_expr" ||
        operation->getNumResults() != 1 || operation->getNumRegions() != 0 ||
        operation->getNumSuccessors() != 0 ||
        !operation->getAttrOfType<DictionaryAttr>("metadata")) {
      (void)reject(operation, "digital expression requires one owned result and metadata");
      return failure();
    }
    if (visited.insert(operation->getResult(0)).second && ++nodes > maximumProofNodes) {
      (void)reject(operation, "digital expression graph exceeds its finite node budget");
      return failure();
    }
    for (Value operand : operation->getOperands())
      if (failed(verifyCollectionValueScope(operand, operation, environment))) {
        (void)reject(operation,
                     "digital operands require visible owned values and explicit region captures");
        return failure();
      }
    if (failed(verifyDigitalMaterializationIntent(operation)))
      return failure();
    StringRef opcode = text(operation, "operator_name");
    if (opcode == "digital_struct_get" || opcode == "digital_struct_make")
      return structure(operation, depth);
    if (operation->hasAttr("struct_schema") || operation->hasAttr("field_ordinal") ||
        operation->hasAttr("field_name")) {
      (void)reject(operation, "only Struct value operations carry schema and member evidence");
      return failure();
    }
    unsigned arity = expressionArity(opcode);
    if (!arity || operation->getNumOperands() != arity) {
      (void)reject(operation, "digital expression requires a supported opcode and exact arity");
      return failure();
    }
    if (arity != 1 || opcode == "digital_alias" || opcode == "digital_neg" ||
        opcode == "digital_not" || opcode == "bool_not")
      return combinational(operation, depth);
    Value input = operation->getOperand(0);
    Type sourceType = input.getType(), resultType = operation->getResult(0).getType();
    StringRef sourceKind = scalarKind(sourceType), resultKind = scalarKind(resultType);
    auto sourceWidth = scalarBounds(sourceType);
    auto source = value(input, depth + 1);
    if (failed(sourceWidth) || failed(source)) {
      (void)reject(operation, "digital operand requires an owned, valid scalar width/value proof");
      return failure();
    }
    Facts result;
    result.height = source->height + 1;
    if (depth + result.height - 1 > maximumProofDepth) {
      (void)reject(operation, "digital conversion graph exceeds its finite depth budget");
      return failure();
    }
    if (opcode == "digital_to_real") {
      bool finite = sourceWidth->upper <= 1023;
      if (!finite && source->constant && !source->constant->isNegative()) {
        llvm::APFloat converted(llvm::APFloat::IEEEdouble());
        (void)converted.convertFromAPInt(*source->constant, true,
                                         llvm::APFloat::rmNearestTiesToEven);
        finite = converted.isFinite();
      }
      if (sourceKind != "uint" || !resultType.isF64() || !finite) {
        (void)reject(operation, "numeric Real conversion requires UInt and finite binary64 proof");
        return failure();
      }
      return result;
    }
    auto resultWidth = scalarBounds(resultType);
    if (failed(resultWidth)) {
      (void)reject(operation, "digital result requires an exact positive scalar width");
      return failure();
    }
    bool valid = false;
    if (opcode == "digital_extend")
      valid = sourceKind == resultKind && resultWidth->lower >= sourceWidth->upper;
    else if (opcode == "digital_truncate")
      valid = sourceKind == resultKind && resultWidth->upper <= sourceWidth->lower;
    else if (opcode == "digital_wrap" || opcode == "digital_saturate")
      valid = sourceKind == resultKind;
    else if (opcode == "digital_resize_checked")
      valid = sourceKind == resultKind &&
              (resultWidth->lower >= sourceWidth->upper ||
               (source->constant && fits(*source->constant, resultKind, resultWidth->lower)));
    else if (opcode == "digital_to_signed")
      valid = sourceKind == "uint" && resultKind == "sint" &&
              widthRelated(sourceType, resultType, module, 1, environment);
    else if (opcode == "digital_to_unsigned")
      valid = sourceKind == "sint" && resultKind == "uint" && source->nonnegative &&
              widthRelated(sourceType, resultType, module, 0, environment);
    else if (opcode == "digital_reinterpret_signed")
      valid = sourceKind == "uint" && resultKind == "sint" &&
              widthRelated(sourceType, resultType, module, 0, environment);
    else if (opcode == "digital_reinterpret_unsigned")
      valid = sourceKind == "sint" && resultKind == "uint" &&
              widthRelated(sourceType, resultType, module, 0, environment);
    if (!valid) {
      (void)reject(operation,
                   "unsupported conversion or invalid exact kind, width or checked proof");
      return failure();
    }
    if (source->constant)
      result.constant = convertedConstant(opcode, resultKind, *resultWidth, *source->constant);
    result.nonnegative = resultKind != "sint" || opcode == "digital_to_signed" ||
                         ((opcode == "digital_extend" || opcode == "digital_resize_checked" ||
                           opcode == "digital_saturate") &&
                          source->nonnegative) ||
                         (result.constant && !result.constant->isNegative());
    return result;
  }

private:
  FailureOr<InstantiatedIntegerExpression> scalarWidth(Type type) {
    if (!isDigitalScalarType(type))
      return failure();
    auto found = widthFacts.find(type);
    if (found != widthFacts.end())
      return found->second;
    auto expression = inferDigitalWidthExpression(type, module, environment);
    if (succeeded(expression))
      widthFacts.try_emplace(type, *expression);
    return expression;
  }

  FailureOr<ParameterIntegerBounds> scalarBounds(Type type) {
    auto expression = scalarWidth(type);
    if (failed(expression))
      return failure();
    return expression->bounds;
  }

  bool validValueType(Type type) {
    auto found = validTypes.find(type);
    if (found != validTypes.end())
      return found->second;
    // Select may carry owned enum/aggregate data. Analog scalar roots and
    // capabilities remain with their distinct semantic owners.
    bool valid = isDigitalCombinationalValueType(type, module, environment);
    validTypes.try_emplace(type, valid);
    return valid;
  }

  bool matchesWidth(Type result, const InstantiatedIntegerExpression &expected) {
    auto actual = scalarWidth(result);
    return succeeded(actual) && equivalentInstantiatedIntegerExpressions(expected, *actual);
  }

  bool sameStorageType(Type left, Type right) {
    // Operand/result storage validity is checked before this query. The cache
    // belongs to this actual module, so aliases cannot borrow another owner's proof.
    if (left == right)
      return true;
    const auto pair = std::make_pair(left, right);
    auto found = storageEqualities.find(pair);
    if (found != storageEqualities.end())
      return found->second;
    bool equal = equivalentDigitalStorageTypes(left, right, module, environment);
    storageEqualities.try_emplace(pair, equal);
    return equal;
  }

  FailureOr<Facts> combinational(Operation *operation, unsigned depth) {
    StringRef opcode = text(operation, "operator_name");
    Type resultType = operation->getResult(0).getType();
    llvm::SmallVector<Facts, 3> inputs;
    Facts result;
    for (Value operand : operation->getOperands()) {
      auto proof = value(operand, depth + 1);
      if (failed(proof)) {
        (void)reject(operation, "digital expression requires valid owned operand proofs");
        return failure();
      }
      result.height = std::max(result.height, proof->height + 1);
      inputs.push_back(std::move(*proof));
    }
    if (depth + result.height - 1 > maximumProofDepth) {
      (void)reject(operation, "digital expression graph exceeds its finite depth budget");
      return failure();
    }
    if (opcode == "digital_alias") {
      if (!validValueType(resultType) ||
          !sameStorageType(operation->getOperand(0).getType(), resultType)) {
        (void)reject(operation, "digital alias requires the exact complete input value type");
        return failure();
      }
      result.constant = inputs.front().constant;
      result.nonnegative = inputs.front().nonnegative;
      return result;
    }
    if (opcode == "digital_select") {
      Type branchType = operation->getOperand(1).getType();
      if (!isBool(operation->getOperand(0).getType(), module, environment) ||
          !validValueType(resultType) || !sameStorageType(branchType, resultType) ||
          !sameStorageType(branchType, operation->getOperand(2).getType())) {
        (void)reject(operation,
                     "digital select requires Bits<1> and identical complete storage types");
        return failure();
      }
      result.nonnegative = inputs[1].nonnegative && inputs[2].nonnegative;
      if (inputs[0].constant) {
        const Facts &selected = inputs[inputs[0].constant->isZero() ? 2 : 1];
        result.constant = selected.constant;
        result.nonnegative = selected.nonnegative;
      } else if (inputs[1].constant && inputs[2].constant) {
        auto equal = binaryConstant("digital_eq", *inputs[1].constant, *inputs[2].constant);
        if (equal && !equal->isZero())
          result.constant = inputs[1].constant;
      }
      return result;
    }
    llvm::SmallVector<ParameterIntegerBounds, 3> widths;
    llvm::SmallVector<InstantiatedIntegerExpression, 3> expressions;
    for (Value operand : operation->getOperands()) {
      auto expression = scalarWidth(operand.getType());
      if (failed(expression)) {
        (void)reject(operation, "digital scalar operation requires exact owned scalar widths");
        return failure();
      }
      widths.push_back(expression->bounds);
      expressions.push_back(std::move(*expression));
    }
    auto resultWidth = scalarBounds(resultType);
    if (failed(resultWidth)) {
      (void)reject(operation, "digital scalar result requires an exact positive width");
      return failure();
    }
    StringRef kind = scalarKind(operation->getOperand(0).getType());
    StringRef resultKind = scalarKind(resultType);
    bool sameKinds = inputs.size() == 1 || kind == scalarKind(operation->getOperand(1).getType());
    bool sameWidths = inputs.size() == 1 ||
                      equivalentInstantiatedIntegerExpressions(expressions[0], expressions[1]);
    bool valid = false;
    bool comparison = opcode == "digital_eq" || opcode == "digital_ne" || opcode == "digital_lt" ||
                      opcode == "digital_le" || opcode == "digital_gt" || opcode == "digital_ge";
    bool bitwise = opcode == "digital_and" || opcode == "digital_or" || opcode == "digital_xor" ||
                   opcode == "digital_not";
    bool boolean = opcode == "bool_and" || opcode == "bool_or" || opcode == "bool_not";
    std::optional<int64_t> amount, lower, upper;
    if (opcode == "digital_add" || opcode == "digital_sub") {
      auto maximum = composeIntegerExpression("max", {expressions[0], expressions[1]});
      auto expected =
          succeeded(maximum)
              ? composeIntegerExpression("add", {*maximum, makeLiteralIntegerExpression(1)})
              : FailureOr<InstantiatedIntegerExpression>(failure());
      valid = numericKind(kind) && sameKinds &&
              resultKind == (opcode == "digital_sub" ? StringRef("sint") : kind) &&
              succeeded(expected) && matchesWidth(resultType, *expected);
    } else if (opcode == "digital_mul" || opcode == "digital_concat") {
      auto expected = composeIntegerExpression("add", {expressions[0], expressions[1]});
      valid = succeeded(expected) && matchesWidth(resultType, *expected) &&
              (opcode == "digital_concat" ? resultKind == "bits"
                                          : numericKind(kind) && sameKinds && resultKind == kind);
    } else if (opcode == "digital_div" || opcode == "digital_rem") {
      if (!inputs[1].constant || inputs[1].constant->isZero()) {
        (void)reject(operation,
                     "division/remainder require a bounded exact nonzero divisor from actual SSA");
        return failure();
      }
      auto expected =
          opcode == "digital_div" && kind == "sint"
              ? composeIntegerExpression("add", {expressions[0], makeLiteralIntegerExpression(1)})
              : FailureOr<InstantiatedIntegerExpression>(expressions[0]);
      valid = numericKind(kind) && sameKinds && resultKind == kind && succeeded(expected) &&
              matchesWidth(resultType, *expected);
    } else if (opcode == "digital_min" || opcode == "digital_max") {
      auto expected = composeIntegerExpression("max", {expressions[0], expressions[1]});
      valid = numericKind(kind) && sameKinds && resultKind == kind && succeeded(expected) &&
              matchesWidth(resultType, *expected);
    } else if (opcode == "digital_neg") {
      auto expected =
          composeIntegerExpression("add", {expressions[0], makeLiteralIntegerExpression(1)});
      valid = numericKind(kind) && resultKind == "sint" && succeeded(expected) &&
              matchesWidth(resultType, *expected);
    } else if (comparison) {
      bool equality = opcode == "digital_eq" || opcode == "digital_ne";
      valid = sameKinds && isBool(resultType, module, environment) &&
              (numericKind(kind) || (equality && kind == "bits" && sameWidths));
    } else if (bitwise || boolean) {
      valid =
          sameKinds && sameWidths && resultKind == kind && matchesWidth(resultType, expressions[0]);
      if (boolean)
        valid &= isBool(resultType, module, environment) &&
                 llvm::all_of(operation->getOperandTypes(),
                              [&](Type type) { return isBool(type, module, environment); });
    } else if (opcode == "digital_shl" || opcode == "digital_shr" ||
               opcode == "digital_logical_shr") {
      amount = staticIndex(operation->getOperand(1), environment);
      auto expected = opcode == "digital_shl" && amount && *amount != 0
                          ? composeIntegerExpression(
                                "add", {expressions[0], makeLiteralIntegerExpression(*amount)})
                          : FailureOr<InstantiatedIntegerExpression>(expressions[0]);
      valid = amount && kind == resultKind && succeeded(expected) &&
              matchesWidth(resultType, *expected);
    } else if (opcode == "digital_extract") {
      lower = staticIndex(operation->getOperand(1), environment);
      upper = staticIndex(operation->getOperand(2), environment);
      valid = lower && upper && *upper > *lower && *upper <= widths[0].lower &&
              resultKind == "bits" &&
              matchesWidth(resultType, makeLiteralIntegerExpression(*upper - *lower));
    }
    if (!valid) {
      (void)reject(operation, "invalid exact digital kind, width, static index or result proof");
      return failure();
    }
    result.nonnegative = resultKind != "sint";
    if ((opcode == "digital_add" || opcode == "digital_mul") && inputs[0].nonnegative &&
        inputs[1].nonnegative)
      result.nonnegative = true;
    if ((opcode == "digital_shl" || opcode == "digital_shr") && inputs[0].nonnegative)
      result.nonnegative = true;
    if (opcode == "digital_logical_shr" && (*amount > 0 || inputs[0].nonnegative))
      result.nonnegative = true;
    if (!inputs[0].constant)
      return result;
    const llvm::APInt &first = *inputs[0].constant;
    if (opcode == "digital_neg") {
      result.constant = boundedConstant(-first.sext(first.getBitWidth() + 1));
    } else if (opcode == "digital_not" || opcode == "bool_not") {
      if (kind == "sint")
        result.constant = boundedConstant(~first);
      else if (resultWidth->lower == resultWidth->upper && resultWidth->upper <= maximumProofBits) {
        unsigned bits = static_cast<unsigned>(resultWidth->upper);
        result.constant = boundedConstant((~first.sextOrTrunc(bits)).zext(bits + 1));
      }
    } else if (amount) {
      result.constant = shiftedConstant(opcode, kind, widths[0], first, *amount);
    } else if (opcode == "digital_extract" && *upper - *lower <= maximumProofBits) {
      unsigned bits = static_cast<unsigned>(*upper - *lower);
      llvm::APInt shifted =
          *lower >= first.getBitWidth() ? llvm::APInt(1, first.isNegative()) : first.ashr(*lower);
      result.constant = boundedConstant(shifted.sextOrTrunc(bits).zext(bits + 1));
    } else if (inputs.size() == 2 && inputs[1].constant) {
      if (opcode == "digital_concat") {
        if (widths[0].lower == widths[0].upper && widths[1].lower == widths[1].upper &&
            widths[0].upper <= maximumProofBits && widths[1].upper <= maximumProofBits) {
          unsigned bits = static_cast<unsigned>(resultWidth->upper);
          auto left = first.sextOrTrunc(widths[0].upper).zext(bits + 1).shl(widths[1].upper);
          auto right = inputs[1].constant->sextOrTrunc(widths[1].upper).zext(bits + 1);
          result.constant = boundedConstant(left | right);
        }
      } else {
        result.constant = binaryConstant(opcode, first, *inputs[1].constant);
      }
    }
    if (result.constant)
      result.nonnegative = !result.constant->isNegative();
    return result;
  }

  Operation *module;
  const ParameterIntegerEnvironment *environment;
  llvm::DenseMap<Value, Facts> facts;
  llvm::DenseMap<Type, bool> validTypes;
  llvm::DenseMap<Type, InstantiatedIntegerExpression> widthFacts;
  llvm::DenseMap<std::pair<Type, Type>, bool> storageEqualities;
  llvm::DenseSet<Value> visited;
  llvm::DenseSet<Value> active;
  unsigned nodes = 0;
};
} // namespace

bool isDigitalScalarType(Type type) { return type && !scalarKind(type).empty(); }

bool isDigitalCombinationalValueType(Type type, Operation *module,
                                     const ParameterIntegerEnvironment *environment) {
  if (!type || !llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return false;
  return isDigitalScalarType(type)
             ? succeeded(inferDigitalWidthExpression(type, module, environment))
             : llvm::isa<EnumType, ShapedType, StructType>(type) && isDigitalStorageType(type) &&
                   succeeded(inferDigitalStorageMaximumBits(
                       type, environment ? environment->use() : module, environment));
}

bool digitalIntegerAttributeFits(IntegerAttr value, Type type, Operation *module,
                                 const ParameterIntegerEnvironment *environment) {
  auto carrier = value ? llvm::dyn_cast<IntegerType>(value.getType()) : IntegerType();
  if (!carrier)
    return false;
  auto bounds = widthBounds(type, module, environment);
  if (failed(bounds))
    return false;
  const llvm::APInt &bits = value.getValue();
  const bool unsignedValue = carrier.isUnsigned() || llvm::isa<BoolAttr>(value);
  const bool negative = !unsignedValue && bits.isNegative();
  const int64_t required = scalarKind(type) == "sint"
                               ? (unsignedValue ? static_cast<int64_t>(bits.getActiveBits()) + 1
                                                : bits.getSignificantBits())
                               : std::max(1u, bits.getActiveBits());
  return (scalarKind(type) == "sint" || !negative) && required <= bounds->lower;
}

FailureOr<std::optional<llvm::APInt>>
inferDigitalConstantInteger(Value value, Operation *use,
                            const ParameterIntegerEnvironment *environment) {
  if (!value || !use || !isDigitalScalarType(value.getType()) ||
      failed(verifyDigitalValueScope(value, use)))
    return failure();
  if (!environment && isDigitalRegionOwner(use->getParentOp())) {
    auto contexts = digitalIntegerContextsAtUse(use);
    if (failed(contexts) || contexts->size() != 1)
      return failure();
    return inferDigitalConstantInteger(value, use, &contexts->front());
  }
  auto facts = ValueVerifier(owner(use), environment).value(value);
  return failed(facts) ? FailureOr<std::optional<llvm::APInt>>(failure())
                       : FailureOr<std::optional<llvm::APInt>>(facts->constant);
}

LogicalResult verifyDigitalLiteralOperation(Operation *operation,
                                            const ParameterIntegerEnvironment *environment) {
  if (!environment && operation && isDigitalRegionOwner(operation->getParentOp())) {
    auto contexts = collectionIntegerContextsAtUse(operation);
    if (failed(contexts))
      return failure();
    for (const auto &context : *contexts)
      if (failed(verifyDigitalLiteralOperation(operation, &context)))
        return failure();
    return success();
  }
  if (failed(checkLiteral(operation, environment)))
    return reject(operation, "digital literal requires an exact fitting integer and canonical "
                             "spelling over every legal owned width");
  return success();
}

LogicalResult verifyDigitalValueOperation(Operation *operation,
                                          const ParameterIntegerEnvironment *environment) {
  if (!environment && operation && isDigitalRegionOwner(operation->getParentOp())) {
    auto contexts = collectionIntegerContextsAtUse(operation);
    if (failed(contexts))
      return failure();
    for (const auto &context : *contexts)
      if (failed(verifyDigitalValueOperation(operation, &context)))
        return failure();
    return success();
  }
  ValueVerifier verifier(owner(operation), environment);
  return success(succeeded(verifier.expression(operation)));
}

LogicalResult verifyDigitalValueModel(Operation *module, bool includeCollections) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return failure();
  ValueVerifier verifier(module);
  auto walk = module->walk<WalkOrder::PreOrder>([&](Operation *operation) {
    if (operation != module && llvm::isa<nodal::ModuleOp>(operation))
      return WalkResult::skip();
    if (llvm::isa<StructuralIfOp>(operation)) {
      if (includeCollections && failed(verifyStructuralIfOperation(operation)))
        return WalkResult::interrupt();
      return WalkResult::skip();
    }
    if (llvm::isa<CollectionOp>(operation)) {
      if (includeCollections && failed(verifyDigitalCollectionOperation(operation)))
        return WalkResult::interrupt();
      return WalkResult::skip();
    }
    StringRef name = operation->getName().getStringRef();
    if (name == "nodal.digital_expr" &&
        failed(isDigitalRegionOwner(operation->getParentOp())
                   ? verifyDigitalValueOperation(operation)
                   : success(succeeded(verifier.expression(operation)))))
      return WalkResult::interrupt();
    if ((name == "nodal.constant" || name == "nodal.const_literal") &&
        operation->getNumResults() == 1 && isDigitalScalarType(operation->getResult(0).getType()) &&
        failed(verifyDigitalLiteralOperation(operation)))
      return WalkResult::interrupt();
    return WalkResult::advance();
  });
  return failure(walk.wasInterrupted());
}
} // namespace nodal

// Preserve the ordinary entry-point ABI for existing non-contextual consumers.
namespace nodal {
bool isDigitalCombinationalValueType(Type type, Operation *module) {
  return isDigitalCombinationalValueType(type, module, nullptr);
}
bool digitalIntegerAttributeFits(IntegerAttr value, Type type, Operation *module) {
  return digitalIntegerAttributeFits(value, type, module, nullptr);
}
LogicalResult verifyDigitalLiteralOperation(Operation *operation) {
  return verifyDigitalLiteralOperation(operation, nullptr);
}
LogicalResult verifyDigitalValueOperation(Operation *operation) {
  return verifyDigitalValueOperation(operation, nullptr);
}
} // namespace nodal

namespace nodal {
LogicalResult verifyDigitalValueModel(Operation *module) {
  return verifyDigitalValueModel(module, true);
}
} // namespace nodal
