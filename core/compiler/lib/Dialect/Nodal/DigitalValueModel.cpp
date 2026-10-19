#include "nodal/Dialect/Nodal/DigitalValueModel.h"

#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/APFloat.h"
#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/SmallString.h"

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

FailureOr<ParameterIntegerBounds> widthBounds(Type type, Operation *module) {
  if (scalarKind(type).empty())
    return failure();
  auto bounds = inferDigitalWidthBounds(type, module);
  if (failed(bounds) || bounds->lower <= 0 || bounds->upper < bounds->lower)
    return failure();
  return bounds;
}

FailureOr<InstantiatedIntegerExpression> widthExpression(Type type, Operation *module) {
  auto bounds = widthBounds(type, module);
  if (failed(bounds))
    return failure();
  if (auto symbolic = llvm::dyn_cast<ParametricIntegerType>(type)) {
    auto dimension = resolveStaticShapeDimension(module, symbolic.getWidth(), "width");
    if (failed(dimension))
      return failure();
    if (dimension->parameter)
      return resolveOwnedIntegerParameter(module, dimension->parameter);
    if (dimension->expression)
      return resolveOwnedIntegerExpression(module, dimension->expression);
    return makeLiteralIntegerExpression(dimension->literal);
  }
  return makeLiteralIntegerExpression(bounds->lower);
}

bool widthRelated(Type source, Type result, Operation *module, int64_t increment) {
  auto left = widthExpression(source, module);
  auto right = widthExpression(result, module);
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

LogicalResult checkLiteral(Operation *operation) {
  if (!operation || operation->getNumOperands() != 0 || operation->getNumResults() != 1 ||
      !operation->getAttrOfType<DictionaryAttr>("metadata") || operation->getNumRegions() != 0 ||
      operation->getNumSuccessors() != 0)
    return failure();
  Type type = operation->getResult(0).getType();
  auto value = operation->getAttrOfType<IntegerAttr>("value");
  if (operation->hasAttr("unit") || !digitalIntegerAttributeFits(value, type, owner(operation)))
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
  explicit ValueVerifier(Operation *module) : module(module) {}

  FailureOr<Facts> value(Value input, unsigned depth = 0) {
    if (depth > maximumProofDepth || valueOwner(input) != module)
      return failure();
    auto known = facts.find(input);
    if (known != facts.end()) {
      if (depth + known->second.height - 1 > maximumProofDepth)
        return failure();
      return known->second;
    }
    if (++nodes > maximumProofNodes || !active.insert(input).second)
      return failure();
    auto bounds = widthBounds(input.getType(), module);
    if (failed(bounds))
      return failure();
    Facts result;
    result.nonnegative = scalarKind(input.getType()) != "sint";
    if (Operation *definition = input.getDefiningOp()) {
      StringRef name = definition->getName().getStringRef();
      if (name == "nodal.constant" || name == "nodal.const_literal") {
        if (failed(checkLiteral(definition)))
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

  FailureOr<Facts> expression(Operation *operation, unsigned depth = 0) {
    if (!module || owner(operation) != module || depth > maximumProofDepth ||
        operation->getName().getStringRef() != "nodal.digital_expr" ||
        operation->getNumOperands() != 1 || operation->getNumResults() != 1 ||
        operation->getNumRegions() != 0 || operation->getNumSuccessors() != 0 ||
        !operation->getAttrOfType<DictionaryAttr>("metadata")) {
      (void)reject(operation, "digital conversion requires one owned operand/result and metadata");
      return failure();
    }
    Value input = operation->getOperand(0);
    Type sourceType = input.getType(), resultType = operation->getResult(0).getType();
    StringRef sourceKind = scalarKind(sourceType), resultKind = scalarKind(resultType);
    StringRef opcode = text(operation, "operator_name");
    auto sourceWidth = widthBounds(sourceType, module);
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
    auto resultWidth = widthBounds(resultType, module);
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
              widthRelated(sourceType, resultType, module, 1);
    else if (opcode == "digital_to_unsigned")
      valid = sourceKind == "sint" && resultKind == "uint" && source->nonnegative &&
              widthRelated(sourceType, resultType, module, 0);
    else if (opcode == "digital_reinterpret_signed")
      valid = sourceKind == "uint" && resultKind == "sint" &&
              widthRelated(sourceType, resultType, module, 0);
    else if (opcode == "digital_reinterpret_unsigned")
      valid = sourceKind == "sint" && resultKind == "uint" &&
              widthRelated(sourceType, resultType, module, 0);
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
  Operation *module;
  llvm::DenseMap<Value, Facts> facts;
  llvm::DenseSet<Value> active;
  unsigned nodes = 0;
};
} // namespace

bool isDigitalScalarType(Type type) { return type && !scalarKind(type).empty(); }

bool digitalIntegerAttributeFits(IntegerAttr value, Type type, Operation *module) {
  auto carrier = value ? llvm::dyn_cast<IntegerType>(value.getType()) : IntegerType();
  if (!carrier)
    return false;
  auto bounds = widthBounds(type, module);
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

LogicalResult verifyDigitalLiteralOperation(Operation *operation) {
  if (failed(checkLiteral(operation)))
    return reject(operation, "digital literal requires an exact fitting integer and canonical "
                             "spelling over every legal owned width");
  return success();
}

LogicalResult verifyDigitalValueOperation(Operation *operation) {
  ValueVerifier verifier(owner(operation));
  return success(succeeded(verifier.expression(operation)));
}

LogicalResult verifyDigitalValueModel(Operation *module) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return failure();
  ValueVerifier verifier(module);
  auto walk = module->walk<WalkOrder::PreOrder>([&](Operation *operation) {
    if (operation != module && llvm::isa<nodal::ModuleOp>(operation))
      return WalkResult::skip();
    StringRef name = operation->getName().getStringRef();
    if (name == "nodal.digital_expr" && failed(verifier.expression(operation)))
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
