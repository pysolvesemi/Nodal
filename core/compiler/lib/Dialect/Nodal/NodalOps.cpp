#include "nodal/Dialect/Nodal/NodalOps.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinTypes.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Diagnostics/DiagnosticSupport.h"
#include "nodal/Dialect/Nodal/AnalogEquationModel.h"
#include "nodal/Dialect/Nodal/AnalogEvents.h"
#include "nodal/Dialect/Nodal/AnalogFunctions.h"
#include "nodal/Dialect/Nodal/AnalogNoise.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/AnalogStorageModel.h"
#include "nodal/Dialect/Nodal/AnalogTransfer.h"
#include "nodal/Dialect/Nodal/AnalogUserFunctions.h"
#include "nodal/Dialect/Nodal/ClockModel.h"
#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"
#include "nodal/Dialect/Nodal/CrossingModel.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopPlanningModel.h"
#include "nodal/Dialect/Nodal/DigitalMaterializationModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/EnumSwitchModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/EventModel.h"
#include "nodal/Dialect/Nodal/FlatFSMModel.h"
#include "nodal/Dialect/Nodal/FsmCompositionModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/MemoryModel.h"
#include "nodal/Dialect/Nodal/NatureDiscipline.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"
#include "nodal/Dialect/Nodal/ResetModel.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Dialect/Nodal/StaticPredicateModel.h"
#include "nodal/Dialect/Nodal/StructuralIfModel.h"
#include "nodal/Dialect/Nodal/TimeWaveform.h"
#include "nodal/Dialect/Nodal/WaiverModel.h"
#include "nodal/Support/BackendCapabilityRegistry.h"

#include "llvm/ADT/APInt.h"
#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/ScopeExit.h"
#include "llvm/ADT/SmallPtrSet.h"
#include "llvm/ADT/SmallString.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/ADT/StringSet.h"

#include <initializer_list>
#include <iterator>
#include <limits>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <utility>
#include <vector>

using namespace mlir;

#define GET_OP_CLASSES
#include "nodal/Dialect/Nodal/NodalOps.cpp.inc"

namespace {

llvm::StringRef textAttr(Operation *operation, llvm::StringRef name) {
  if (auto value = operation->getAttrOfType<StringAttr>(name))
    return value.getValue();
  return {};
}

LogicalResult requireText(Operation *operation, llvm::StringRef name, llvm::StringRef label) {
  if (textAttr(operation, name).trim().empty())
    return operation->emitOpError() << label << " must not be empty";
  return success();
}

bool oneOf(llvm::StringRef value, std::initializer_list<llvm::StringRef> choices) {
  for (llvm::StringRef choice : choices) {
    if (value == choice)
      return true;
  }
  return false;
}

LogicalResult requireSingleBlock(Operation *operation) {
  if (operation->getNumRegions() != 1 || operation->getRegion(0).getBlocks().size() != 1)
    return operation->emitOpError("requires exactly one body block");
  return success();
}

std::optional<unsigned> finiteWidth(Type type) {
  if (auto value = llvm::dyn_cast<nodal::BitsType>(type))
    return static_cast<unsigned>(value.getWidth());
  if (auto value = llvm::dyn_cast<nodal::UIntType>(type))
    return static_cast<unsigned>(value.getWidth());
  if (auto value = llvm::dyn_cast<nodal::SIntType>(type))
    return static_cast<unsigned>(value.getWidth());
  if (auto value = llvm::dyn_cast<nodal::EnumType>(type))
    return static_cast<unsigned>(value.getWidth());
  if (auto value = llvm::dyn_cast<IntegerType>(type))
    return value.getWidth();
  return std::nullopt;
}

bool isSignedType(Type type) {
  if (llvm::isa<nodal::SIntType>(type))
    return true;
  if (auto integer = llvm::dyn_cast<IntegerType>(type))
    return integer.isSigned();
  return false;
}

bool integerFits(IntegerAttr value, Type type) {
  const std::optional<unsigned> width = finiteWidth(type);
  if (!width)
    return false;
  if (isSignedType(type))
    return value.getValue().isSignedIntN(*width);
  return !value.getValue().isNegative() && value.getValue().isIntN(*width);
}

bool attributeFits(Attribute value, Type type) {
  if (auto typed = llvm::dyn_cast<TypedAttr>(value)) {
    if (typed.getType() == type)
      return true;
  }
  if (auto integer = llvm::dyn_cast<IntegerAttr>(value))
    return integerFits(integer, type);
  if (llvm::isa<BoolAttr>(value)) {
    if (type.isInteger(1))
      return true;
    if (auto bits = llvm::dyn_cast<nodal::BitsType>(type))
      return bits.getWidth() == 1;
  }
  return false;
}

unsigned shapedRank(llvm::StringRef dimensions) {
  if (dimensions.trim().empty())
    return 0;
  unsigned rank = 1;
  for (char character : dimensions) {
    if (character == ',')
      ++rank;
  }
  return rank;
}

Operation *findDirectModuleParameter(Operation *module, llvm::StringRef symbol) {
  if (!module || module->getNumRegions() != 1 || !llvm::hasSingleElement(module->getRegion(0)))
    return nullptr;
  for (Operation &operation : module->getRegion(0).front()) {
    if (!llvm::isa<nodal::ParameterOp>(operation))
      continue;
    auto name = operation.getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName());
    if (name && name.getValue() == symbol)
      return &operation;
  }
  return nullptr;
}

bool parameterHasShapeEnvelope(Operation *module, llvm::StringRef symbol,
                               llvm::StringRef requiredEffect = "shape") {
  if (!module || module->getNumRegions() != 1 || !llvm::hasSingleElement(module->getRegion(0)))
    return false;
  for (Operation &operation : module->getRegion(0).front()) {
    if (!llvm::isa<nodal::ParameterEnvelopeOp>(operation))
      continue;
    auto parameter = operation.getAttrOfType<FlatSymbolRefAttr>("parameter");
    auto effects = operation.getAttrOfType<ArrayAttr>("effects");
    if (!parameter || parameter.getValue() != symbol || !effects)
      continue;
    for (Attribute effect : effects)
      if (auto value = llvm::dyn_cast<StringAttr>(effect);
          value && value.getValue() == requiredEffect)
        return true;
  }
  return false;
}

FailureOr<int64_t> staticShapeLiteral(Value value) {
  Operation *definition = value ? value.getDefiningOp() : nullptr;
  if (!definition || definition->getNumOperands() || definition->getNumResults() != 1 ||
      definition->hasAttr("unit") ||
      !((llvm::isa<nodal::ConstantOp>(definition) && value.getType().isIndex()) ||
        (llvm::isa<nodal::ConstLiteralOp>(definition) && value.getType().isInteger(64))))
    return failure();
  auto literal = definition->getAttrOfType<IntegerAttr>("value");
  if (!literal || literal.getType() != value.getType() || !literal.getValue().isSignedIntN(64))
    return failure();
  return literal.getValue().getSExtValue();
}

bool hasStaticShapeIndexDependencies(
    Value value, Operation *module, llvm::SmallPtrSetImpl<Operation *> &active, unsigned depth = 0,
    bool directModule = false, llvm::StringRef requiredEffect = "shape",
    const nodal::ParameterIntegerEnvironment *environment = nullptr, unsigned *work = nullptr) {
  if (!value || depth >= 512 || (work && ++*work > nodal::digitalTypeMaximumNodes))
    return false;
  if (environment && value.getDefiningOp() && value.getDefiningOp()->getParentOp() == module)
    environment = nullptr; // Module static dependencies retain their ordinary effect policy.
  if (environment) {
    if (environment->module() != module ||
        failed(nodal::verifyCollectionValueScope(value, environment->use(), environment)))
      return false;
    if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
      if (argument.getOwner() != environment->scope() ||
          (!environment->binding(value) && !environment->domain(value)))
        return false;
      auto capture = nodal::digitalCollectionCaptureSource(argument, environment);
      if (failed(capture))
        return false;
      if (!*capture)
        return true; // Only the sealed admission owner binds width formals.
      Operation *source = capture->getDefiningOp();
      if (source && source->getParentOp() == module)
        return hasStaticShapeIndexDependencies(*capture, module, active, depth + 1, true,
                                               requiredEffect, nullptr, work);
      auto enclosing = environment->enclosingAtUse(argument.getOwner()->getParentOp());
      return succeeded(enclosing) &&
             hasStaticShapeIndexDependencies(*capture, module, active, depth + 1, false,
                                             requiredEffect, &*enclosing, work);
    }
  }
  Operation *operation = value.getDefiningOp();
  if (!operation || operation->getParentOfType<nodal::ModuleOp>() != module || depth >= 512 ||
      (directModule && operation->getParentOp() != module) || !active.insert(operation).second)
    return false;
  const llvm::scope_exit remove([&] { active.erase(operation); });
  if (llvm::isa<nodal::ConstLiteralOp>(operation))
    return true;
  if (llvm::isa<nodal::ConstParameterRefOp>(operation)) {
    auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("parameter");
    Operation *parameter =
        reference ? findDirectModuleParameter(module, reference.getValue()) : nullptr;
    return parameter && nodal::isStructuralParameter(parameter) &&
           parameterHasShapeEnvelope(module, reference.getValue(), requiredEffect);
  }
  if (!llvm::isa<nodal::ConstExprOp>(operation))
    return false;
  return llvm::all_of(operation->getOperands(), [&](Value operand) {
    if (!environment)
      return hasStaticShapeIndexDependencies(operand, module, active, depth + 1, directModule,
                                             requiredEffect, nullptr, work);
    auto atProducer = environment->atUse(operation);
    return succeeded(atProducer) &&
           hasStaticShapeIndexDependencies(operand, module, active, depth + 1, false,
                                           requiredEffect, &*atProducer, work);
  });
}

bool parameterHasPositiveFiniteRange(Operation *module, llvm::StringRef symbol) {
  if (!module || module->getNumRegions() != 1 || !llvm::hasSingleElement(module->getRegion(0)))
    return false;
  for (Operation &operation : module->getRegion(0).front()) {
    if (!llvm::isa<nodal::ParameterConstraintOp>(operation) ||
        textAttr(&operation, "constraint_kind") != "range" || operation.getNumOperands() != 2)
      continue;
    auto parameter = operation.getAttrOfType<FlatSymbolRefAttr>("parameter");
    if (!parameter || parameter.getValue() != symbol)
      continue;
    auto lower = nodal::inferParameterIntegerBounds(operation.getOperand(0));
    auto upper = nodal::inferParameterIntegerBounds(operation.getOperand(1));
    return succeeded(lower) && succeeded(upper) && lower->lower == lower->upper &&
           upper->lower == upper->upper && lower->lower > 0 && lower->lower <= upper->upper;
  }
  return false;
}

llvm::StringRef metadataSemanticPath(Operation *operation) {
  auto metadata =
      operation ? operation->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
  auto path = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
  return path ? path.getValue() : llvm::StringRef();
}

Value findDirectModuleStaticValue(Operation *module, llvm::StringRef sourcePath) {
  if (!module || module->getNumRegions() != 1 || !llvm::hasSingleElement(module->getRegion(0)) ||
      sourcePath.empty())
    return {};
  Value result;
  for (Operation &operation : module->getRegion(0).front()) {
    if (!llvm::isa<nodal::ConstLiteralOp, nodal::ConstParameterRefOp, nodal::ConstExprOp>(
            operation) ||
        metadataSemanticPath(&operation) != sourcePath || operation.getNumResults() != 1)
      continue;
    if (result)
      return {};
    result = operation.getResult(0);
  }
  return result;
}

FailureOr<nodal::ParameterIntegerBounds>
inferShapeDimensionBounds(Operation *module, llvm::StringRef dimension, bool directModule = false,
                          llvm::StringRef requiredEffect = "shape") {
  int64_t literal = 0;
  if (!dimension.getAsInteger(10, literal)) {
    if (literal <= 0)
      return failure();
    return nodal::ParameterIntegerBounds{literal, literal};
  }

  if (Operation *parameter = findDirectModuleParameter(module, dimension)) {
    auto type = parameter->getAttrOfType<TypeAttr>("type");
    auto bounds = nodal::inferParameterIntegerBounds(parameter);
    if (!type || !type.getValue().isInteger(64) || !nodal::isStructuralParameter(parameter) ||
        !parameterHasShapeEnvelope(module, dimension, requiredEffect) ||
        !parameterHasPositiveFiniteRange(module, dimension) || failed(bounds) || bounds->lower <= 0)
      return failure();
    return *bounds;
  }

  Value value = findDirectModuleStaticValue(module, dimension);
  if (!value || !value.getType().isInteger(64) ||
      !llvm::isa_and_nonnull<nodal::ConstExprOp>(value.getDefiningOp()))
    return failure();
  llvm::SmallPtrSet<Operation *, 16> active;
  if (!hasStaticShapeIndexDependencies(value, module, active, 0, directModule, requiredEffect))
    return failure();
  // Derived digital widths are mathematical i64 metadata: an intermediate
  // may be negative even though the final width must be strictly positive.
  // Ordinary shape/index/storage callers retain their existing interpretation.
  auto mode = requiredEffect == "width" ? nodal::ParameterIntegerMode::MathematicalSigned64
                                        : nodal::ParameterIntegerMode::Storage;
  auto bounds = nodal::inferParameterIntegerBounds(value, mode);
  if (failed(bounds) || bounds->lower <= 0)
    return failure();
  return *bounds;
}

FailureOr<int64_t>
inferShapeDimensionMinimum(Operation *module, llvm::StringRef dimension,
                           const nodal::ParameterIntegerEnvironment *environment = nullptr) {
  auto bounds = inferShapeDimensionBounds(module, dimension);
  if (environment) {
    auto proof = nodal::resolveStaticShapeDimension(module, dimension, "shape", environment);
    bounds = succeeded(proof) ? FailureOr<nodal::ParameterIntegerBounds>(proof->bounds)
                              : FailureOr<nodal::ParameterIntegerBounds>(failure());
  }
  if (failed(bounds))
    return failure();
  return bounds->lower;
}

LogicalResult verifyShapedTypeContract(Operation *module, Operation *owner, Type type) {
  auto shaped = llvm::dyn_cast<nodal::ShapedType>(type);
  if (!shaped)
    return success();
  llvm::SmallVector<llvm::StringRef> dimensions;
  shaped.getDimensions().split(dimensions, ',', -1, false);
  for (llvm::StringRef dimension : dimensions) {
    dimension = dimension.trim();
    if (failed(inferShapeDimensionMinimum(module, dimension)))
      return owner->emitOpError(
          "NODAL-SHAPE-043-001: shaped dimension requires an owned positive finite static "
          "Integer proof with structural shape dependencies");
  }
  return verifyShapedTypeContract(module, owner, shaped.getElementType());
}

bool isStorageType(Type type) {
  if (auto shaped = llvm::dyn_cast<nodal::ShapedType>(type))
    type = shaped.getElementType();
  return llvm::isa<nodal::VariableType>(type);
}

LogicalResult
verifyDigitalShapeValueScope(Operation *operation,
                             const nodal::ParameterIntegerEnvironment *environment = nullptr) {
  if (!operation->getNumOperands() ||
      !nodal::isDigitalStorageType(operation->getOperand(0).getType()))
    return success();
  for (Value value : operation->getOperands())
    if (failed(nodal::verifyCollectionValueScope(value, operation, environment)))
      return operation->emitOpError(
          "NODAL-SHAPE-043-002: digital projection requires visible owned SSA values");
  return success();
}

bool isProceduralShapeProjection(Operation *operation) {
  if (!operation->getParentOfType<nodal::AnalogProcedureOp>() || !operation->getNumOperands())
    return false;
  auto input = llvm::dyn_cast<nodal::ShapedType>(operation->getOperand(0).getType());
  return input && llvm::isa<nodal::VariableType, nodal::QuantityType>(input.getElementType());
}

LogicalResult verifyProceduralProjectionIdentity(Operation *operation) {
  auto procedure = operation->getParentOfType<nodal::AnalogProcedureOp>();
  Operation *source = operation->getOperand(0).getDefiningOp();
  auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata");
  auto inputPath = metadata ? metadata.getAs<StringAttr>("input_path") : StringAttr();
  auto storage = metadata ? metadata.getAs<StringAttr>("storage") : StringAttr();
  auto access = metadata ? metadata.getAs<StringAttr>("access") : StringAttr();
  const bool writable = isStorageType(operation->getOperand(0).getType());
  if (!procedure || !source)
    return operation->emitOpError(
        "NODAL-SHAPE-043-003: procedural projection requires an owned source");
  const std::string ownerPrefix = (textAttr(procedure, "owner") + ".").str();
  auto path = metadataSemanticPath(operation);
  auto sourcePath = metadataSemanticPath(source);
  if (!inputPath || inputPath.getValue() != sourcePath || !sourcePath.starts_with(ownerPrefix) ||
      sourcePath.size() <= ownerPrefix.size() || sourcePath != sourcePath.trim() ||
      textAttr(operation, "owner") != textAttr(procedure, "owner") ||
      !path.starts_with(ownerPrefix) || path.size() <= ownerPrefix.size() || path != path.trim() ||
      !storage || storage.getValue() != (writable ? "procedural" : "structural") || !access ||
      access.getValue() != (writable ? "read_write" : "read_only"))
    return operation->emitOpError(
        "NODAL-SHAPE-043-003: procedural projection identity, owner or access is invalid");
  bool duplicate = false;
  procedure->walk([&](Operation *candidate) {
    if (candidate != operation && metadataSemanticPath(candidate) == path)
      duplicate = true;
  });
  if (duplicate)
    return operation->emitOpError(
        "NODAL-SHAPE-043-003: procedural projection identity must be unique");
  return nodal::verifyGeneratedAnalogOwnership(operation);
}

LogicalResult verifyAnalogArrayFillValue(Operation *operation);

bool equalShapeDimensions(Operation *module, llvm::StringRef left, llvm::StringRef right) {
  llvm::SmallVector<llvm::StringRef> lhs, rhs;
  left.split(lhs, ',', -1, true);
  right.split(rhs, ',', -1, true);
  if (lhs.size() != rhs.size())
    return false;
  for (auto [a, b] : llvm::zip(lhs, rhs)) {
    auto x = nodal::resolveStaticShapeDimension(module, a);
    auto y = nodal::resolveStaticShapeDimension(module, b);
    if (failed(x) || failed(y) || x->parameter != y->parameter || x->expression != y->expression ||
        x->literal != y->literal)
      return false;
  }
  return true;
}

bool isSnapshotAssignment(Operation *operation) {
  return textAttr(operation, "value_form") == "snapshot";
}

LogicalResult verifySnapshotCapture(Value value, Operation *consumer) {
  auto resolved = nodal::resolveAnalogArrayValue(value, consumer);
  if (failed(resolved))
    return consumer->emitOpError(
        "NODAL-ANALOG-033-017: shaped payload requires an owned immutable snapshot or fill");
  Operation *producer = resolved->producer;
  if (producer->getBlock() != consumer->getBlock() || !producer->isBeforeInBlock(consumer))
    return consumer->emitOpError(
        "NODAL-ANALOG-033-017: snapshot must be captured in its consuming statement block");
  for (Operation *between = producer->getNextNode(); between != consumer;
       between = between->getNextNode()) {
    if (!between || (!llvm::isa<nodal::ShapeIndexOp, nodal::ShapeViewOp, nodal::ShapeSliceOp,
                                nodal::AnalogVariableReadOp, nodal::AnalogArrayFillOp>(between)))
      return consumer->emitOpError(
          "NODAL-ANALOG-033-017: snapshot cannot cross an ordered statement or scope boundary");
  }
  return success();
}

LogicalResult verifyCapturedScalarBindings(Operation *consumer, ValueRange values,
                                           llvm::StringRef source) {
  auto expression = nodal::parseAnalogSourceExpression(consumer, source);
  if (failed(expression))
    return consumer->emitOpError("NODAL-ANALOG-033-017: invalid captured scalar source expression");
  std::set<std::string> paths;
  for (Value value : values) {
    auto path = nodal::getAnalogCapturedReadPath(value);
    Operation *producer = value.getDefiningOp();
    if (failed(path) || !producer || !paths.insert(*path).second ||
        producer->getBlock() != consumer->getBlock() || !producer->isBeforeInBlock(consumer))
      return consumer->emitOpError(
          "NODAL-ANALOG-033-017: scalar captures require unique dominating owned reads");
    if (llvm::isa<nodal::AnalogVariableReadOp>(producer)) {
      if (failed(nodal::resolveAnalogStorageReference(producer->getOperand(0), consumer)))
        return failure();
    } else if (failed(verifySnapshotCapture(value, consumer))) {
      return failure();
    }
    for (Operation *between = producer->getNextNode(); between != consumer;
         between = between->getNextNode())
      if (!between || !llvm::isa<nodal::ShapeIndexOp, nodal::ShapeViewOp, nodal::ShapeSliceOp,
                                 nodal::AnalogVariableReadOp, nodal::AnalogArrayFillOp>(between))
        return consumer->emitOpError(
            "NODAL-ANALOG-033-017: scalar snapshot crosses an ordered statement boundary");
  }
  if (paths != expression->reads)
    return consumer->emitOpError(
        "NODAL-ANALOG-033-017: scalar capture inventory differs from its expression");
  return success();
}

LogicalResult verifySnapshotAssignment(Operation *operation) {
  auto destination = llvm::dyn_cast<nodal::ShapedType>(operation->getOperand(0).getType());
  auto source = operation->getNumOperands() == 2
                    ? llvm::dyn_cast<nodal::ShapedType>(operation->getOperand(1).getType())
                    : nodal::ShapedType();
  auto leaf = destination ? llvm::dyn_cast<nodal::VariableType>(destination.getElementType())
                          : nodal::VariableType();
  auto quantity =
      source ? llvm::dyn_cast<nodal::QuantityType>(source.getElementType()) : nodal::QuantityType();
  auto module = operation->getParentOfType<nodal::ModuleOp>();
  auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata");
  if (!module || !isSnapshotAssignment(operation) || !leaf || leaf.getKind() != "real" ||
      !quantity || quantity.getKind() != "real" || quantity.getDimension() != leaf.getDimension() ||
      !equalShapeDimensions(module, destination.getDimensions(), source.getDimensions()) ||
      (metadata && metadata.get("value")) || textAttr(operation, "value_kind") != "real" ||
      textAttr(operation, "value_dimension") != leaf.getDimension() ||
      operation->getAttrOfType<BoolAttr>("guard_present").getValue())
    return operation->emitOpError("NODAL-ANALOG-033-012: snapshot assignment requires exact shaped "
                                  "Real type, explicit payload and unguarded form");
  if (failed(nodal::resolveAnalogStorageReference(operation->getOperand(0), operation)) ||
      failed(verifySnapshotCapture(operation->getOperand(1), operation)))
    return failure();
  return success();
}

LogicalResult verifyLoop(Operation *operation) {
  if (failed(requireText(operation, "induction", "induction name")))
    return failure();
  if (auto step = operation->getAttrOfType<IntegerAttr>("step")) {
    if (step.getValue().isZero())
      return operation->emitOpError("step must not be zero");
  }
  return requireSingleBlock(operation);
}

LogicalResult verifyRelation(Operation *operation, llvm::StringRef label,
                             std::initializer_list<llvm::StringRef> kinds) {
  const llvm::StringRef kind = textAttr(operation, "kind");
  if (!oneOf(kind, kinds))
    return operation->emitOpError() << "unsupported " << label << " kind '" << kind << "'";
  auto source = operation->getAttrOfType<FlatSymbolRefAttr>("source");
  auto destination = operation->getAttrOfType<FlatSymbolRefAttr>("destination");
  if (!source || !destination)
    return operation->emitOpError() << label << " requires both endpoints";
  if (source == destination && kind != "alias")
    return operation->emitOpError() << label << " endpoints may match only for alias";
  return success();
}

struct ProceduralValueInfo {
  std::string kind;
  std::string dimension;
};

FailureOr<ProceduralValueInfo> getProceduralValueInfo(Type type) {
  if (type.isInteger(1))
    return ProceduralValueInfo{"boolean", "1"};
  if (type.isF64())
    return ProceduralValueInfo{"real", "1"};
  if (auto quantity = llvm::dyn_cast<nodal::QuantityType>(type))
    return ProceduralValueInfo{quantity.getKind().str(), quantity.getDimension().str()};
  return failure();
}

bool proceduralKindsCompatible(llvm::StringRef source, llvm::StringRef destination) {
  return source == destination || (source == "integer" && destination == "real");
}

bool isCanonicalStructuredCaseLabel(llvm::StringRef label, llvm::StringRef kind) {
  if (kind == "boolean")
    return label == "boolean:true" || label == "boolean:false";
  if (kind != "integer" || !label.consume_front("integer:") || label.empty())
    return false;
  int64_t parsed = 0;
  if (label.getAsInteger(10, parsed))
    return false;
  return label == std::to_string(parsed);
}

bool isProceduralAncestor(Operation *operation) {
  return operation && static_cast<bool>(operation->getParentOfType<nodal::AnalogProcedureOp>());
}

LogicalResult requireProceduralAncestor(Operation *operation, llvm::StringRef code,
                                        llvm::StringRef label) {
  if (!isProceduralAncestor(operation))
    return nodal::emitMappedFailure(
        operation, code, llvm::Twine(label) + " requires an active analog procedural region");
  return success();
}

bool isVisibleFrom(Block *useBlock, Block *declarationBlock) {
  for (Block *current = useBlock; current;) {
    if (current == declarationBlock)
      return true;
    Operation *parent = current->getParentOp();
    current = parent ? parent->getBlock() : nullptr;
  }
  return false;
}

bool isDeclaredBeforeStructuredUse(Operation *declaration, Operation *use) {
  if (!declaration || !use)
    return false;
  Block *declarationBlock = declaration->getBlock();
  Block *currentBlock = use->getBlock();
  Operation *useAnchor = use;
  while (currentBlock && currentBlock != declarationBlock) {
    Operation *parent = currentBlock->getParentOp();
    if (!parent)
      return false;
    useAnchor = parent;
    currentBlock = parent->getBlock();
  }
  return currentBlock == declarationBlock && declaration != useAnchor &&
         declaration->isBeforeInBlock(useAnchor);
}

LogicalResult verifyStringArray(Operation *operation, llvm::StringRef attributeName,
                                llvm::StringRef code, llvm::StringRef label) {
  auto values = operation->getAttrOfType<ArrayAttr>(attributeName);
  if (!values)
    return nodal::emitMappedFailure(operation, code, llvm::Twine(label) + " array is required");
  for (Attribute value : values) {
    auto text = llvm::dyn_cast<StringAttr>(value);
    if (!text || text.getValue().trim().empty())
      return nodal::emitMappedFailure(operation, code,
                                      llvm::Twine(label) + " entries must be non-empty strings");
  }
  return success();
}

LogicalResult verifyAnalysisApplicability(Operation *operation) {
  if (failed(verifyStringArray(operation, "analyses", "NODAL-ANALOG-033-015",
                               "analysis applicability")))
    return failure();
  auto analyses = operation->getAttrOfType<ArrayAttr>("analyses");
  if (analyses.empty())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-015",
        "procedural assignment requires at least one analysis applicability");
  llvm::StringSet<> seen;
  for (Attribute value : analyses) {
    llvm::StringRef analysis = llvm::cast<StringAttr>(value).getValue();
    if (!oneOf(analysis, {"initialization", "operating-point", "dc", "transient", "ac", "noise"}) ||
        !seen.insert(analysis).second)
      return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-015",
                                      llvm::Twine("invalid or duplicate procedural analysis '") +
                                          analysis + "'");
  }
  return success();
}

LogicalResult verifyVariableInitializerShape(Operation *operation,
                                             nodal::VariableType variableType) {
  auto initialized = operation->getAttrOfType<BoolAttr>("initialized");
  if (!initialized)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-004",
                                    "procedural variable requires initialized metadata");

  llvm::StringRef value = textAttr(operation, "initializer_value");
  llvm::StringRef kind = textAttr(operation, "initializer_kind");
  llvm::StringRef dimension = textAttr(operation, "initializer_dimension");
  auto reads = operation->getAttrOfType<ArrayAttr>("initializer_reads");
  if (!reads)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-004",
                                    "procedural initializer read inventory is required");

  if (!initialized.getValue()) {
    if (!value.empty() || !kind.empty() || !dimension.empty() || !reads.empty())
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-033-004",
          "uninitialized procedural variable must not carry initializer metadata");
    return success();
  }

  if (value.empty() || kind.empty() || dimension.empty())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-004",
        "initialized procedural variable requires value, kind, and dimension metadata");
  if (!oneOf(kind, {"integer", "real", "boolean"}) ||
      !proceduralKindsCompatible(kind, variableType.getKind()))
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-004",
        "procedural initializer kind is incompatible with the declared variable kind");
  if (!nodal::isCanonicalDimensionSignature(dimension) || dimension != variableType.getDimension())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-005",
        "procedural initializer dimension does not match the declared variable dimension");
  return verifyStringArray(operation, "initializer_reads", "NODAL-ANALOG-033-017",
                           "initializer read");
}

LogicalResult verifyGuardShape(Operation *operation) {
  auto present = operation->getAttrOfType<BoolAttr>("guard_present");
  if (!present)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-014",
                                    "procedural assignment requires guard metadata");
  llvm::StringRef value = textAttr(operation, "guard_value");
  llvm::StringRef kind = textAttr(operation, "guard_kind");
  llvm::StringRef dimension = textAttr(operation, "guard_dimension");
  auto reads = operation->getAttrOfType<ArrayAttr>("guard_reads");
  if (!reads)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-014",
                                    "procedural guard read inventory is required");
  if (!present.getValue()) {
    if (!value.empty() || !kind.empty() || !dimension.empty() || !reads.empty())
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-033-014",
          "unguarded procedural assignment must not carry guard metadata");
    return success();
  }
  if (value.empty() || kind != "boolean" || dimension != "1")
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-014",
        "procedural assignment guard must be a dimensionless Boolean value");
  return verifyStringArray(operation, "guard_reads", "NODAL-ANALOG-033-017", "guard read");
}

struct ProceduralVariableState {
  Operation *declaration = nullptr;
  Block *declarationBlock = nullptr;
  nodal::VariableType type;
  bool initialized = false;
};

struct ProceduralVerificationState {
  llvm::StringRef owner;
  llvm::DenseMap<Value, ProceduralVariableState> variables;
  llvm::StringMap<Value> variablesByIdentity;
  llvm::StringSet<> statements;
  int64_t nextDeclarationOrder = 0;
  int64_t nextAssignmentOrder = 0;
};

LogicalResult resolveProceduralVariable(Operation *operation, Value value,
                                        ProceduralVerificationState &state,
                                        ProceduralVariableState *&resolved) {
  auto found = state.variables.find(value);
  if (found == state.variables.end())
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-017",
                                    "procedural operation references an unknown variable handle");
  resolved = &found->second;
  if (textAttr(operation, "owner") != state.owner)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-009",
        "procedural operation owner does not match the enclosing component");
  if (!isVisibleFrom(operation->getBlock(), resolved->declarationBlock))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-010",
                                    "procedural variable is outside its lexical declaration scope");
  return success();
}

LogicalResult verifyReadInventory(Operation *operation, ArrayAttr reads,
                                  ProceduralVerificationState &state) {
  for (Attribute attribute : reads) {
    auto identity = llvm::dyn_cast<StringAttr>(attribute);
    if (!identity || identity.getValue().trim().empty())
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-033-017",
          "procedural read inventory entries must be non-empty variable identities");
    auto value = state.variablesByIdentity.find(identity.getValue());
    if (value == state.variablesByIdentity.end())
      return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-017",
                                      llvm::Twine("unknown procedural variable '") +
                                          identity.getValue() + "'");
    ProceduralVariableState *variable = nullptr;
    if (failed(resolveProceduralVariable(operation, value->second, state, variable)))
      return failure();
    if (!variable->initialized)
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-033-011",
          llvm::Twine("procedural variable '") + identity.getValue() +
              "' is read before initialization or an earlier assignment");
  }
  return success();
}

LogicalResult verifyProceduralBlock(Block &block, ProceduralVerificationState &state);

LogicalResult verifyProceduralScope(Operation *operation, ProceduralVerificationState &state) {
  if (failed(requireSingleBlock(operation)))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-016",
                                    "procedural lexical scope requires one body block");
  if (textAttr(operation, "scope_id").trim().empty())
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-016",
                                    "procedural lexical scope identity must be non-empty");
  if (textAttr(operation, "owner") != state.owner)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-009",
        "procedural lexical scope owner does not match the enclosing component");
  return verifyProceduralBlock(operation->getRegion(0).front(), state);
}

LogicalResult verifyProceduralDeclaration(Operation *operation,
                                          ProceduralVerificationState &state) {
  llvm::StringRef identity = textAttr(operation, "identity");
  if (identity.trim().empty())
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-001",
                                    "procedural variable identity must be non-empty");
  if (textAttr(operation, "owner") != state.owner)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-009",
                                    "procedural variable belongs to a different component");
  auto order = operation->getAttrOfType<IntegerAttr>("declaration_order");
  if (!order || order.getInt() != state.nextDeclarationOrder)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-002",
        "procedural variable declaration order must be contiguous and authored");
  if (state.variablesByIdentity.count(identity) != 0)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-002",
                                    llvm::Twine("duplicate procedural variable identity '") +
                                        identity + "'");

  auto variableType = llvm::dyn_cast<nodal::VariableType>(operation->getResult(0).getType());
  if (!variableType)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-019",
                                    "procedural declaration result must use !nodal.variable");
  if (failed(verifyVariableInitializerShape(operation, variableType)))
    return failure();

  auto initializerReads = operation->getAttrOfType<ArrayAttr>("initializer_reads");
  if (failed(verifyReadInventory(operation, initializerReads, state)))
    return failure();

  auto initialized = operation->getAttrOfType<BoolAttr>("initialized");
  state.variables.try_emplace(operation->getResult(0),
                              ProceduralVariableState{operation, operation->getBlock(),
                                                      variableType, initialized.getValue()});
  state.variablesByIdentity[identity] = operation->getResult(0);
  ++state.nextDeclarationOrder;
  return success();
}

LogicalResult verifyProceduralRead(Operation *operation, ProceduralVerificationState &state) {
  ProceduralVariableState *variable = nullptr;
  if (failed(resolveProceduralVariable(operation, operation->getOperand(0), state, variable)))
    return failure();
  if (!variable->initialized)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-011",
        "procedural variable is read before initialization or an earlier assignment");
  auto result = getProceduralValueInfo(operation->getResult(0).getType());
  if (failed(result) || result->kind != variable->type.getKind() ||
      result->dimension != variable->type.getDimension())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-012",
        "procedural read result kind or dimension does not match its variable");
  return success();
}

LogicalResult verifyProceduralAssignment(Operation *operation, ProceduralVerificationState &state) {
  llvm::StringRef statement = textAttr(operation, "statement_id");
  if (statement.trim().empty())
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-006",
                                    "procedural statement identity must be non-empty");
  if (!state.statements.insert(statement).second)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-007",
                                    llvm::Twine("duplicate procedural statement identity '") +
                                        statement + "'");
  auto order = operation->getAttrOfType<IntegerAttr>("authored_order");
  if (!order || order.getInt() != state.nextAssignmentOrder)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-007",
                                    "procedural assignment order must be contiguous and authored");

  ProceduralVariableState *target = nullptr;
  if (failed(resolveProceduralVariable(operation, operation->getOperand(0), state, target)))
    return failure();

  llvm::StringRef valueKind = textAttr(operation, "value_kind");
  llvm::StringRef valueDimension = textAttr(operation, "value_dimension");
  if (!oneOf(valueKind, {"integer", "real", "boolean"}) ||
      !proceduralKindsCompatible(valueKind, target->type.getKind()))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-012",
                                    "assigned value kind is incompatible with the target variable");
  if (!nodal::isCanonicalDimensionSignature(valueDimension) ||
      valueDimension != target->type.getDimension())
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-013",
                                    "assigned value dimension does not match the target variable");

  for (Value value : operation->getOperands().drop_front()) {
    if (!value.getDefiningOp<nodal::AnalogVariableReadOp>())
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-033-017",
          "procedural assignment value operands must be explicit variable reads");
  }

  if (failed(verifyAnalysisApplicability(operation)) || failed(verifyGuardShape(operation)))
    return failure();
  if (auto guardReads = operation->getAttrOfType<ArrayAttr>("guard_reads")) {
    if (failed(verifyReadInventory(operation, guardReads, state)))
      return failure();
  }

  target->initialized = true;
  ++state.nextAssignmentOrder;
  return success();
}

LogicalResult verifyProceduralBlock(Block &block, ProceduralVerificationState &state) {
  for (Operation &operation : block) {
    if (llvm::isa<nodal::AnalogVariableOp>(operation)) {
      if (failed(verifyProceduralDeclaration(&operation, state)))
        return failure();
      continue;
    }
    if (llvm::isa<nodal::AnalogVariableReadOp>(operation)) {
      if (failed(verifyProceduralRead(&operation, state)))
        return failure();
      continue;
    }
    if (llvm::isa<nodal::AnalogAssignOp>(operation)) {
      if (failed(verifyProceduralAssignment(&operation, state)))
        return failure();
      continue;
    }
    if (llvm::isa<nodal::AnalogScopeOp>(operation)) {
      if (failed(verifyProceduralScope(&operation, state)))
        return failure();
      continue;
    }
    if (llvm::isa<nodal::AnalogProcedureOp>(operation))
      return nodal::emitMappedFailure(&operation, "NODAL-ANALOG-033-018",
                                      "nested analog procedural regions are not supported");
    return nodal::emitMappedFailure(&operation, "NODAL-ANALOG-033-008",
                                    "operation is not legal in an analog procedural region");
  }
  return success();
}

LogicalResult verifyStructuredProceduralBlock(Block &block, llvm::StringRef owner) {
  for (Operation &operation : block) {
    // Parent verification visits regions before each child's verifier. Reject
    // malformed empty/multiblock controls before dereferencing their body.
    if (llvm::isa<nodal::AnalogOnOp, nodal::AnalogScopeOp, nodal::AnalogIfOp, nodal::AnalogCaseOp,
                  nodal::AnalogLoopOp>(operation) &&
        failed(requireSingleBlock(&operation)))
      return failure();
    if (nodal::isAnalogEventExpression(&operation) || llvm::isa<nodal::AnalogOnOp>(operation)) {
      if (failed(nodal::verifyAnalogEventOperation(&operation)))
        return failure();
      if (llvm::isa<nodal::AnalogOnOp>(operation) &&
          failed(verifyStructuredProceduralBlock(operation.getRegion(0).front(), owner)))
        return failure();
      continue;
    }
    if (llvm::isa<nodal::AnalogVariableOp, nodal::AnalogVariableReadOp, nodal::AnalogAssignOp,
                  nodal::AnalogBreakOp, nodal::AnalogContinueOp, nodal::AnalogArrayFillOp,
                  nodal::AnalogLiveFillOp, nodal::ShapeIndexOp, nodal::ShapeViewOp,
                  nodal::ShapeSliceOp>(operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured procedural operation owner does not match the enclosing component");
      continue;
    }

    if (auto scope = llvm::dyn_cast<nodal::AnalogScopeOp>(&operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured lexical scope owner does not match the enclosing component");
      if (failed(
              verifyStructuredProceduralBlock(scope.getOperation()->getRegion(0).front(), owner)))
        return failure();
      continue;
    }

    if (auto conditional = llvm::dyn_cast<nodal::AnalogIfOp>(&operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured conditional owner does not match the enclosing component");
      for (Operation &nested : conditional.getOperation()->getRegion(0).front()) {
        auto arm = llvm::dyn_cast<nodal::AnalogIfArmOp>(&nested);
        if (!arm)
          return nodal::emitMappedFailure(
              &nested, "NODAL-ANALOG-034-015",
              "analog conditional body may contain only analog_if_arm operations");
        if (failed(requireSingleBlock(arm.getOperation())) ||
            failed(
                verifyStructuredProceduralBlock(arm.getOperation()->getRegion(0).front(), owner)))
          return failure();
      }
      continue;
    }

    if (auto selection = llvm::dyn_cast<nodal::AnalogCaseOp>(&operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured case owner does not match the enclosing component");
      for (Operation &nested : selection.getOperation()->getRegion(0).front()) {
        auto arm = llvm::dyn_cast<nodal::AnalogCaseArmOp>(&nested);
        if (!arm)
          return nodal::emitMappedFailure(
              &nested, "NODAL-ANALOG-034-015",
              "analog case body may contain only analog_case_arm operations");
        if (failed(requireSingleBlock(arm.getOperation())) ||
            failed(
                verifyStructuredProceduralBlock(arm.getOperation()->getRegion(0).front(), owner)))
          return failure();
      }
      continue;
    }

    if (auto loop = llvm::dyn_cast<nodal::AnalogLoopOp>(&operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured loop owner does not match the enclosing component");
      if (failed(verifyStructuredProceduralBlock(loop.getOperation()->getRegion(0).front(), owner)))
        return failure();
      continue;
    }

    if (llvm::isa<nodal::AnalogIfArmOp, nodal::AnalogCaseArmOp>(operation))
      return nodal::emitMappedFailure(
          &operation, "NODAL-ANALOG-034-015",
          "analog control-flow arm must be nested directly under its owning selection");

    if (llvm::isa<nodal::AnalogProcedureOp>(operation))
      return nodal::emitMappedFailure(&operation, "NODAL-ANALOG-033-018",
                                      "nested analog procedural regions are not supported");

    return nodal::emitMappedFailure(
        &operation, "NODAL-ANALOG-034-014",
        "operation is not legal in structured analog procedural control flow");
  }
  return success();
}

struct StructuredInitializedStorage {
  bool whole = false;
  std::vector<nodal::AnalogStorageReference> regions;
  std::set<int64_t> literalOffsets;
};
using StructuredInitializedSet = std::map<Operation *, StructuredInitializedStorage>;

bool isStructuredStorageInitialized(const StructuredInitializedSet &state,
                                    const nodal::AnalogStorageReference &reference) {
  auto found = state.find(reference.declaration);
  return found != state.end() &&
         (found->second.whole || llvm::any_of(found->second.regions, [&](const auto &region) {
            return nodal::analogStorageReferenceCovers(region, reference);
          }));
}

void initializeStructuredStorage(StructuredInitializedSet &state,
                                 const nodal::AnalogStorageReference &reference) {
  auto &facts = state[reference.declaration];
  if (facts.whole)
    return;
  if (reference.wholeRoot) {
    facts = StructuredInitializedStorage{true, {}, {}};
    return;
  }
  if (!llvm::any_of(facts.regions, [&](const auto &region) {
        return nodal::analogStorageReferenceCovers(region, reference);
      })) {
    facts.regions.erase(std::remove_if(facts.regions.begin(), facts.regions.end(),
                                       [&](const auto &region) {
                                         return nodal::analogStorageReferenceCovers(reference,
                                                                                    region);
                                       }),
                        facts.regions.end());
    facts.regions.push_back(reference);
  }
  if (reference.literalRootFlatOffset)
    facts.literalOffsets.insert(*reference.literalRootFlatOffset);
  // Count only authored, independently proven distinct literal offsets. Never
  // enumerate an extent or promote symbolic interval coincidences to coverage.
  if (reference.exactFixedCardinality &&
      facts.literalOffsets.size() == static_cast<uint64_t>(*reference.exactFixedCardinality))
    facts = StructuredInitializedStorage{true, {}, {}};
}

struct StructuredVariableInfo {
  Operation *declaration = nullptr;
  Block *declarationBlock = nullptr;
};

struct StructuredDataflowContext {
  llvm::StringMap<StructuredVariableInfo> variables;
  llvm::StringSet<> operationIdentities;
  int64_t nextDeclarationOrder = 0;
  int64_t nextAssignmentOrder = 0;
};

struct StructuredFlow {
  std::optional<StructuredInitializedSet> normal;
  std::vector<StructuredInitializedSet> breaks;
  std::vector<StructuredInitializedSet> continues;
};

std::optional<StructuredInitializedSet>
intersectStructuredStates(const std::vector<StructuredInitializedSet> &states) {
  if (states.empty())
    return std::nullopt;
  StructuredInitializedSet result = states.front();
  for (auto state = std::next(states.begin()); state != states.end(); ++state) {
    StructuredInitializedSet intersection;
    for (const auto &[declaration, left] : result) {
      auto right = state->find(declaration);
      if (right == state->end())
        continue;
      if (left.whole) {
        intersection[declaration] = right->second;
      } else if (right->second.whole) {
        intersection[declaration] = left;
      } else {
        for (const auto &a : left.regions)
          for (const auto &b : right->second.regions) {
            if (nodal::analogStorageReferenceCovers(a, b))
              initializeStructuredStorage(intersection, b);
            else if (nodal::analogStorageReferenceCovers(b, a))
              initializeStructuredStorage(intersection, a);
          }
      }
    }
    result = std::move(intersection);
  }
  return result;
}

void appendStructuredEscapes(StructuredFlow &destination, const StructuredFlow &source) {
  destination.breaks.insert(destination.breaks.end(), source.breaks.begin(), source.breaks.end());
  destination.continues.insert(destination.continues.end(), source.continues.begin(),
                               source.continues.end());
}

void removeStructuredLocals(StructuredFlow &flow, const std::vector<Operation *> &locals) {
  auto remove = [&](StructuredInitializedSet &state) {
    for (Operation *declaration : locals)
      state.erase(declaration);
  };
  if (flow.normal)
    remove(*flow.normal);
  for (StructuredInitializedSet &state : flow.breaks)
    remove(state);
  for (StructuredInitializedSet &state : flow.continues)
    remove(state);
}

bool isStructuredIdentityOperation(Operation *operation) {
  if (nodal::isAnalogEventExpression(operation) || llvm::isa<nodal::AnalogOnOp>(operation))
    return true;
  return llvm::isa<nodal::AnalogLiveFillOp, nodal::AnalogVariableOp, nodal::AnalogVariableReadOp,
                   nodal::AnalogAssignOp, nodal::AnalogScopeOp, nodal::AnalogIfOp,
                   nodal::AnalogIfArmOp, nodal::AnalogCaseOp, nodal::AnalogCaseArmOp,
                   nodal::AnalogLoopOp, nodal::AnalogBreakOp, nodal::AnalogContinueOp>(operation);
}

llvm::StringRef structuredOperationIdentity(Operation *operation) {
  if (nodal::isAnalogEventExpression(operation))
    return textAttr(operation, "event_id");
  if (llvm::isa<nodal::AnalogVariableOp, nodal::AnalogLiveFillOp>(operation))
    return textAttr(operation, "identity");
  if (llvm::isa<nodal::AnalogVariableReadOp>(operation))
    return textAttr(operation, "read_id");
  if (llvm::isa<nodal::AnalogScopeOp>(operation))
    return textAttr(operation, "scope_id");
  if (llvm::isa<nodal::AnalogIfArmOp, nodal::AnalogCaseArmOp>(operation))
    return textAttr(operation, "arm_id");
  return textAttr(operation, "statement_id");
}

LogicalResult registerStructuredOperationIdentity(Operation *operation,
                                                  StructuredDataflowContext &context) {
  if (!isStructuredIdentityOperation(operation))
    return success();
  llvm::StringRef identity = structuredOperationIdentity(operation);
  if (identity.trim().empty() || identity.trim() != identity)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-001",
        "structured operation identity must be non-empty and canonical");
  if (!context.operationIdentities.insert(identity).second)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-001",
                                    llvm::Twine("duplicate structured operation identity '") +
                                        identity + "'");
  return success();
}

LogicalResult collectStructuredVariables(Block &block, llvm::StringRef owner,
                                         StructuredDataflowContext &context) {
  for (Operation &operation : block) {
    if (failed(registerStructuredOperationIdentity(&operation, context)))
      return failure();
    if (llvm::isa<nodal::AnalogVariableOp>(operation)) {
      llvm::StringRef identity = textAttr(&operation, "identity");
      if (identity.trim().empty())
        return nodal::emitMappedFailure(&operation, "NODAL-ANALOG-034-014",
                                        "structured variable identity must be non-empty");
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured variable owner does not match the enclosing component");
      auto declarationOrder = operation.getAttrOfType<IntegerAttr>("declaration_order");
      if (!declarationOrder || declarationOrder.getInt() != context.nextDeclarationOrder)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured declaration order must be contiguous and authored");
      ++context.nextDeclarationOrder;
      if (!context.variables
               .try_emplace(identity, StructuredVariableInfo{&operation, operation.getBlock()})
               .second)
        return nodal::emitMappedFailure(&operation, "NODAL-ANALOG-034-014",
                                        llvm::Twine("duplicate structured variable identity '") +
                                            identity + "'");
    }
    if (llvm::isa<nodal::AnalogAssignOp>(operation)) {
      auto authoredOrder = operation.getAttrOfType<IntegerAttr>("authored_order");
      if (!authoredOrder || authoredOrder.getInt() != context.nextAssignmentOrder)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured assignment order must be contiguous and authored");
      ++context.nextAssignmentOrder;
    }
    for (Region &region : operation.getRegions()) {
      for (Block &nested : region) {
        if (failed(collectStructuredVariables(nested, owner, context)))
          return failure();
      }
    }
  }
  return success();
}

LogicalResult requireStructuredReference(Operation *operation, llvm::StringRef identity,
                                         const StructuredDataflowContext &context) {
  if (identity.trim().empty() || identity.trim() != identity)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        "structured variable reference must be non-empty and canonical");
  auto variable = context.variables.find(identity);
  if (variable == context.variables.end())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        llvm::Twine("structured control flow references unknown variable '") + identity + "'");
  if (!isVisibleFrom(operation->getBlock(), variable->second.declarationBlock))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                    llvm::Twine("structured variable '") + identity +
                                        "' is outside its lexical declaration scope");
  if (!isDeclaredBeforeStructuredUse(variable->second.declaration, operation))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                    llvm::Twine("structured variable '") + identity +
                                        "' must be declared before use");
  return success();
}

LogicalResult requireStructuredRead(Operation *operation, llvm::StringRef identity,
                                    const StructuredInitializedSet &initialized,
                                    const StructuredDataflowContext &context) {
  if (failed(requireStructuredReference(operation, identity, context)))
    return failure();
  if (auto found = initialized.find(context.variables.find(identity)->second.declaration);
      found == initialized.end() || !found->second.whole)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-004",
        llvm::Twine("procedural variable '") + identity +
            "' is read before definite initialization on this control-flow path");
  return success();
}

FailureOr<std::string> structuredVariableIdentity(Operation *operation, Value value,
                                                  const StructuredDataflowContext &context,
                                                  bool requireInitialized,
                                                  const StructuredInitializedSet &initialized);

bool typedStorageInventory(Operation *operation, llvm::StringRef attributeName) {
  return operation->hasAttr("reference_form") &&
         (attributeName == "condition_reads" || attributeName == "selector_reads" ||
          attributeName == "bound_reads" || attributeName == "event_reads");
}

LogicalResult requireStructuredReads(Operation *operation, llvm::StringRef attributeName,
                                     const StructuredInitializedSet &initialized,
                                     const StructuredDataflowContext &context) {
  auto reads = operation->getAttrOfType<ArrayAttr>(attributeName);
  if (!reads)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                    llvm::Twine("missing structured read inventory '") +
                                        attributeName + "'");
  if (typedStorageInventory(operation, attributeName)) {
    auto bindings = nodal::resolveAnalogSourceStorageBindings(operation);
    if (failed(bindings))
      return failure();
    for (const auto &binding : *bindings)
      if (failed(structuredVariableIdentity(operation, binding.reference, context,
                                            /*requireInitialized=*/true, initialized)))
        return failure();
    return success();
  }
  for (Attribute attribute : reads) {
    auto identity = llvm::dyn_cast<StringAttr>(attribute);
    if (!identity || identity.getValue().trim().empty())
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-034-014",
          "structured read inventory entries must be non-empty identities");
    if (failed(requireStructuredRead(operation, identity.getValue(), initialized, context)))
      return failure();
  }
  return success();
}

FailureOr<std::string> structuredVariableIdentity(Operation *operation, Value value,
                                                  const StructuredDataflowContext &context,
                                                  bool requireInitialized,
                                                  const StructuredInitializedSet &initialized) {
  auto reference = nodal::resolveAnalogStorageReference(value, operation);
  if (failed(reference)) {
    (void)nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                   "structured operand requires actual owned storage");
    return failure();
  }
  Operation *declaration = reference->declaration;
  llvm::StringRef identity = textAttr(declaration, "identity");
  auto variable = context.variables.find(identity);
  if (variable == context.variables.end() || variable->second.declaration != declaration)
    return failure();
  if (!isVisibleFrom(operation->getBlock(), variable->second.declarationBlock)) {
    (void)nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                   llvm::Twine("structured variable '") + identity +
                                       "' is outside its lexical declaration scope");
    return failure();
  }
  if (!isDeclaredBeforeStructuredUse(variable->second.declaration, operation)) {
    (void)nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                   llvm::Twine("structured variable '") + identity +
                                       "' must be declared before use");
    return failure();
  }
  if (requireInitialized && !isStructuredStorageInitialized(initialized, *reference)) {
    (void)nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-004",
        llvm::Twine("procedural variable '") + identity +
            "' is read before definite initialization on this control-flow path");
    return failure();
  }
  return identity.str();
}

LogicalResult verifyStructuredReferenceInventory(Operation *operation,
                                                 llvm::StringRef attributeName,
                                                 const StructuredDataflowContext &context) {
  auto references = operation->getAttrOfType<ArrayAttr>(attributeName);
  if (!references)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                    llvm::Twine("missing structured reference inventory '") +
                                        attributeName + "'");
  if (typedStorageInventory(operation, attributeName)) {
    auto bindings = nodal::resolveAnalogSourceStorageBindings(operation);
    if (failed(bindings))
      return failure();
    const StructuredInitializedSet noInitializationRequirement;
    for (const auto &binding : *bindings)
      if (failed(structuredVariableIdentity(operation, binding.reference, context,
                                            /*requireInitialized=*/false,
                                            noInitializationRequirement)))
        return failure();
    return success();
  }
  for (Attribute attribute : references) {
    auto identity = llvm::dyn_cast<StringAttr>(attribute);
    if (!identity)
      return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                      "structured reference inventory entries must be strings");
    if (failed(requireStructuredReference(operation, identity.getValue(), context)))
      return failure();
  }
  return success();
}

LogicalResult requireStructuredCapturedValue(Operation *consumer, Value value,
                                             const StructuredDataflowContext &context,
                                             bool requireInitialized,
                                             const StructuredInitializedSet &initialized) {
  if (auto read = value.getDefiningOp<nodal::AnalogVariableReadOp>())
    return success(succeeded(structuredVariableIdentity(consumer, read->getOperand(0), context,
                                                        requireInitialized, initialized)));
  auto array = nodal::resolveAnalogArrayValue(value, consumer);
  if (failed(array))
    return nodal::emitMappedFailure(consumer, "NODAL-ANALOG-034-014",
                                    "assignment operands require explicit captured values");
  if (auto read = llvm::dyn_cast<nodal::AnalogVariableReadOp>(array->producer))
    return success(succeeded(structuredVariableIdentity(consumer, read->getOperand(0), context,
                                                        requireInitialized, initialized)));
  return success();
}

LogicalResult verifyStructuredReferences(Block &block, const StructuredDataflowContext &context) {
  const StructuredInitializedSet noInitializationRequirement;
  for (Operation &operation : block) {
    llvm::StringRef inventory;
    if (llvm::isa<nodal::AnalogVariableOp>(operation))
      inventory = "initializer_reads";
    else if (llvm::isa<nodal::AnalogAssignOp>(operation))
      inventory = "guard_reads";
    else if (llvm::isa<nodal::AnalogIfArmOp>(operation))
      inventory = "condition_reads";
    else if (llvm::isa<nodal::AnalogCaseOp>(operation))
      inventory = "selector_reads";
    else if (llvm::isa<nodal::AnalogLoopOp>(operation))
      inventory = "bound_reads";
    else if (nodal::isAnalogEventExpression(&operation) &&
             !llvm::isa<nodal::AnalogEventOrOp>(operation))
      inventory = "event_reads";

    if (!inventory.empty() &&
        failed(verifyStructuredReferenceInventory(&operation, inventory, context)))
      return failure();

    if (llvm::isa<nodal::AnalogVariableReadOp>(operation)) {
      if (failed(structuredVariableIdentity(&operation, operation.getOperand(0), context,
                                            /*requireInitialized=*/false,
                                            noInitializationRequirement)))
        return failure();
    }

    if (llvm::isa<nodal::AnalogAssignOp>(operation)) {
      if (failed(structuredVariableIdentity(&operation, operation.getOperand(0), context,
                                            /*requireInitialized=*/false,
                                            noInitializationRequirement)))
        return failure();
      for (Value value : operation.getOperands().drop_front())
        if (failed(requireStructuredCapturedValue(&operation, value, context, false,
                                                  noInitializationRequirement)))
          return failure();
    }

    for (Region &region : operation.getRegions()) {
      for (Block &nested : region) {
        if (failed(verifyStructuredReferences(nested, context)))
          return failure();
      }
    }
  }
  return success();
}

FailureOr<StructuredFlow> analyzeStructuredDataflowBlock(Block &block,
                                                         const StructuredInitializedSet &input,
                                                         StructuredDataflowContext &context,
                                                         bool retainLocals);

FailureOr<StructuredFlow> analyzeStructuredIf(nodal::AnalogIfOp conditional,
                                              const StructuredInitializedSet &input,
                                              StructuredDataflowContext &context) {
  StructuredFlow result;
  std::vector<StructuredInitializedSet> normalStates;
  bool unmatchedReachable = true;

  for (Operation &operation : conditional.getOperation()->getRegion(0).front()) {
    auto arm = llvm::cast<nodal::AnalogIfArmOp>(&operation);
    auto isElse = operation.getAttrOfType<BoolAttr>("is_else");
    bool reachable = unmatchedReachable;
    if (isElse.getValue()) {
      unmatchedReachable = false;
    } else {
      llvm::StringRef stage = textAttr(&operation, "stage");
      if (stage == "static") {
        auto value = operation.getAttrOfType<BoolAttr>("static_value");
        reachable = unmatchedReachable && value.getValue();
        if (value.getValue())
          unmatchedReachable = false;
      } else {
        reachable = unmatchedReachable;
      }
    }

    if (!reachable)
      continue;
    if (!isElse.getValue() &&
        failed(requireStructuredReads(&operation, "condition_reads", input, context)))
      return failure();
    auto branch =
        analyzeStructuredDataflowBlock(arm.getOperation()->getRegion(0).front(), input, context,
                                       /*retainLocals=*/false);
    if (failed(branch))
      return failure();
    if (branch->normal)
      normalStates.push_back(*branch->normal);
    appendStructuredEscapes(result, *branch);
  }

  if (unmatchedReachable)
    normalStates.push_back(input);
  result.normal = intersectStructuredStates(normalStates);
  return result;
}

FailureOr<StructuredFlow> analyzeStructuredCase(nodal::AnalogCaseOp selection,
                                                const StructuredInitializedSet &input,
                                                StructuredDataflowContext &context) {
  if (failed(requireStructuredReads(selection.getOperation(), "selector_reads", input, context)))
    return failure();

  StructuredFlow result;
  std::vector<StructuredInitializedSet> normalStates;
  auto staticPresent = selection.getOperation()->getAttrOfType<BoolAttr>("static_value_present");
  llvm::StringRef staticValue = textAttr(selection.getOperation(), "static_value");
  Operation *defaultArm = nullptr;

  if (staticPresent.getValue()) {
    Operation *selected = nullptr;
    for (Operation &operation : selection.getOperation()->getRegion(0).front()) {
      auto isDefault = operation.getAttrOfType<BoolAttr>("is_default");
      if (isDefault.getValue()) {
        defaultArm = &operation;
        continue;
      }
      auto labels = operation.getAttrOfType<ArrayAttr>("labels");
      for (Attribute attribute : labels) {
        if (llvm::cast<StringAttr>(attribute).getValue() == staticValue) {
          selected = &operation;
          break;
        }
      }
      if (selected)
        break;
    }
    Operation *reachable = selected ? selected : defaultArm;
    if (!reachable) {
      result.normal = input;
      return result;
    }
    auto branch = analyzeStructuredDataflowBlock(reachable->getRegion(0).front(), input, context,
                                                 /*retainLocals=*/false);
    if (failed(branch))
      return failure();
    return *branch;
  }

  bool hasDefault = false;
  for (Operation &operation : selection.getOperation()->getRegion(0).front()) {
    auto arm = llvm::cast<nodal::AnalogCaseArmOp>(&operation);
    if (operation.getAttrOfType<BoolAttr>("is_default").getValue())
      hasDefault = true;
    auto branch =
        analyzeStructuredDataflowBlock(arm.getOperation()->getRegion(0).front(), input, context,
                                       /*retainLocals=*/false);
    if (failed(branch))
      return failure();
    if (branch->normal)
      normalStates.push_back(*branch->normal);
    appendStructuredEscapes(result, *branch);
  }
  if (!hasDefault)
    normalStates.push_back(input);
  result.normal = intersectStructuredStates(normalStates);
  return result;
}

FailureOr<StructuredFlow> analyzeStructuredLoop(nodal::AnalogLoopOp loop,
                                                const StructuredInitializedSet &input,
                                                StructuredDataflowContext &context) {
  if (failed(requireStructuredReads(loop.getOperation(), "bound_reads", input, context)))
    return failure();

  llvm::StringRef stage = textAttr(loop.getOperation(), "stage");
  auto minimum = loop.getOperation()->getAttrOfType<IntegerAttr>("minimum_iterations");
  auto staticCount = loop.getOperation()->getAttrOfType<IntegerAttr>("static_trip_count");
  if (stage == "static" && staticCount.getInt() == 0)
    return StructuredFlow{input, {}, {}};

  auto body =
      analyzeStructuredDataflowBlock(loop.getOperation()->getRegion(0).front(), input, context,
                                     /*retainLocals=*/false);
  if (failed(body))
    return failure();

  std::vector<StructuredInitializedSet> exits;
  if (minimum.getInt() == 0)
    exits.push_back(input);
  if (body->normal)
    exits.push_back(*body->normal);
  exits.insert(exits.end(), body->breaks.begin(), body->breaks.end());
  exits.insert(exits.end(), body->continues.begin(), body->continues.end());

  StructuredFlow result;
  result.normal = intersectStructuredStates(exits);
  return result;
}

// Event definitions do not evaluate live fills. A complete OR/on is the
// selected initialization point, including an unused outer event expression.
bool containsLiveEvent(Operation *operation) {
  llvm::SmallVector<Operation *> pending{operation};
  llvm::SmallPtrSet<Operation *, 16> seen;
  while (!pending.empty()) {
    Operation *current = pending.pop_back_val();
    if (!current || !seen.insert(current).second)
      continue;
    if (llvm::isa<nodal::AnalogOnOp>(current)) {
      if (current->getNumOperands())
        pending.push_back(current->getOperand(0).getDefiningOp());
    } else if (nodal::isAnalogEventExpression(current)) {
      if (textAttr(current, "reference_form") == "live")
        return true;
      if (llvm::isa<nodal::AnalogEventOrOp>(current))
        for (Value value : current->getOperands())
          pending.push_back(value.getDefiningOp());
    }
  }
  return false;
}
LogicalResult requireLiveEventConsumerReads(Operation *operation,
                                            const StructuredInitializedSet &initialized,
                                            const StructuredDataflowContext &context) {
  if (!containsLiveEvent(operation))
    return success();
  auto program = nodal::resolveAnalogLiveSourceProgram(operation);
  if (failed(program))
    return failure();
  if (program->consumer != operation)
    return success();
  for (const auto &binding : program->storageBindings)
    if (failed(structuredVariableIdentity(operation, binding.reference, context,
                                          /*requireInitialized=*/true, initialized)))
      return failure();
  return success();
}

FailureOr<StructuredFlow> analyzeStructuredDataflowStatement(Operation *operation,
                                                             const StructuredInitializedSet &input,
                                                             StructuredDataflowContext &context) {
  if (llvm::isa<nodal::AnalogVariableOp>(operation)) {
    StructuredInitializedSet output = input;
    if (failed(requireStructuredReads(operation, "initializer_reads", input, context)))
      return failure();
    for (Value value : operation->getOperands())
      if (failed(requireStructuredCapturedValue(operation, value, context, true, input)))
        return failure();
    if (operation->getAttrOfType<BoolAttr>("initialized").getValue())
      output[operation].whole = true;
    return StructuredFlow{output, {}, {}};
  }

  if (llvm::isa<nodal::AnalogVariableReadOp>(operation)) {
    auto identity = structuredVariableIdentity(operation, operation->getOperand(0), context,
                                               /*requireInitialized=*/true, input);
    if (failed(identity))
      return failure();
    return StructuredFlow{input, {}, {}};
  }

  if (llvm::isa<nodal::AnalogAssignOp>(operation)) {
    StructuredInitializedSet output = input;
    if (failed(requireStructuredReads(operation, "guard_reads", input, context)))
      return failure();
    auto target = structuredVariableIdentity(operation, operation->getOperand(0), context,
                                             /*requireInitialized=*/false, input);
    if (failed(target))
      return failure();
    for (Value value : operation->getOperands().drop_front())
      if (failed(requireStructuredCapturedValue(operation, value, context, true, input)))
        return failure();
    auto reference = nodal::resolveAnalogStorageReference(operation->getOperand(0), operation);
    if (failed(reference))
      return failure();
    auto guarded = operation->getAttrOfType<BoolAttr>("guard_present");
    if (!guarded || !guarded.getValue())
      initializeStructuredStorage(output, *reference);
    return StructuredFlow{output, {}, {}};
  }

  if (llvm::isa<nodal::AnalogLiveFillOp>(operation))
    return StructuredFlow{input, {}, {}};

  if (llvm::isa<nodal::AnalogArrayFillOp>(operation)) {
    for (Value value : operation->getOperands())
      if (failed(requireStructuredCapturedValue(operation, value, context, true, input)))
        return failure();
    return StructuredFlow{input, {}, {}};
  }
  if (llvm::isa<nodal::ShapeIndexOp, nodal::ShapeViewOp, nodal::ShapeSliceOp>(operation))
    return StructuredFlow{input, {}, {}};

  if (nodal::isAnalogEventExpression(operation)) {
    if (failed(requireLiveEventConsumerReads(operation, input, context)))
      return failure();
    if (!llvm::isa<nodal::AnalogEventOrOp>(operation) &&
        textAttr(operation, "reference_form") != "live" &&
        failed(requireStructuredReads(operation, "event_reads", input, context)))
      return failure();
    return StructuredFlow{input, {}, {}};
  }
  if (llvm::isa<nodal::AnalogOnOp>(operation)) {
    if (failed(requireLiveEventConsumerReads(operation, input, context)))
      return failure();
    auto body = analyzeStructuredDataflowBlock(operation->getRegion(0).front(), input, context,
                                               /*retainLocals=*/false);
    if (failed(body))
      return failure();
    if (!body->breaks.empty() || !body->continues.empty()) {
      (void)nodal::emitMappedFailure(operation, "NODAL-ANALOG-037-007",
                                     "loop control cannot escape an event body");
      return failure();
    }
    // An event is not guaranteed to fire; body writes do not initialize following code.
    return StructuredFlow{input, {}, {}};
  }

  if (auto scope = llvm::dyn_cast<nodal::AnalogScopeOp>(operation))
    return analyzeStructuredDataflowBlock(scope.getOperation()->getRegion(0).front(), input,
                                          context,
                                          /*retainLocals=*/false);
  if (auto conditional = llvm::dyn_cast<nodal::AnalogIfOp>(operation))
    return analyzeStructuredIf(conditional, input, context);
  if (auto selection = llvm::dyn_cast<nodal::AnalogCaseOp>(operation))
    return analyzeStructuredCase(selection, input, context);
  if (auto loop = llvm::dyn_cast<nodal::AnalogLoopOp>(operation))
    return analyzeStructuredLoop(loop, input, context);
  if (llvm::isa<nodal::AnalogBreakOp>(operation))
    return StructuredFlow{std::nullopt, {input}, {}};
  if (llvm::isa<nodal::AnalogContinueOp>(operation))
    return StructuredFlow{std::nullopt, {}, {input}};

  (void)nodal::emitMappedFailure(
      operation, "NODAL-ANALOG-034-014",
      "unsupported operation reached structured definite-assignment analysis");
  return failure();
}

FailureOr<StructuredFlow> analyzeStructuredDataflowBlock(Block &block,
                                                         const StructuredInitializedSet &input,
                                                         StructuredDataflowContext &context,
                                                         bool retainLocals) {
  StructuredFlow flow{input, {}, {}};
  std::vector<Operation *> locals;
  for (Operation &operation : block) {
    if (llvm::isa<nodal::AnalogVariableOp>(operation))
      locals.push_back(&operation);
    if (!flow.normal)
      continue;
    auto statement = analyzeStructuredDataflowStatement(&operation, *flow.normal, context);
    if (failed(statement))
      return failure();
    appendStructuredEscapes(flow, *statement);
    flow.normal = statement->normal;
  }
  if (!retainLocals)
    removeStructuredLocals(flow, locals);
  return flow;
}

LogicalResult verifyStructuredDefiniteAssignment(Operation *procedure, llvm::StringRef owner) {
  StructuredDataflowContext context;
  Block &body = procedure->getRegion(0).front();
  if (failed(collectStructuredVariables(body, owner, context)))
    return failure();
  if (failed(verifyStructuredReferences(body, context)))
    return failure();
  auto flow = analyzeStructuredDataflowBlock(body, StructuredInitializedSet{}, context,
                                             /*retainLocals=*/true);
  if (failed(flow))
    return failure();
  if (!flow->breaks.empty())
    return nodal::emitMappedFailure(procedure, "NODAL-ANALOG-034-010",
                                    "break escaped the nearest runtime-bounded analog loop");
  if (!flow->continues.empty())
    return nodal::emitMappedFailure(procedure, "NODAL-ANALOG-034-011",
                                    "continue escaped the nearest runtime-bounded analog loop");
  return success();
}

LogicalResult verifySingleTopLevelProcedurePerScope(Operation *module) {
  llvm::SmallPtrSet<Operation *, 8> seenScopes;
  Operation *duplicateProcedure = nullptr;
  bool duplicateGeneratedScope = false;
  module->walk([&](nodal::AnalogProcedureOp procedure) {
    auto parentModule = procedure->getParentOfType<nodal::ModuleOp>();
    if (!parentModule || parentModule.getOperation() != module ||
        procedure->getParentOfType<nodal::AnalogProcedureOp>())
      return;
    // A structural occurrence owns its own state and procedure. Use actual
    // ancestry, never a claimed generated_owner attribute, for this partition.
    // Each procedure's verifier independently checks generated ownership.
    auto generate = procedure->getParentOfType<nodal::GenerateOp>();
    Operation *scope = generate ? generate.getOperation() : module;
    if (!seenScopes.insert(scope).second && !duplicateProcedure) {
      duplicateProcedure = procedure.getOperation();
      duplicateGeneratedScope = static_cast<bool>(generate);
    }
  });
  if (!duplicateProcedure)
    return success();
  return nodal::emitMappedFailure(
      duplicateProcedure, "NODAL-ANALOG-033-020",
      duplicateGeneratedScope
          ? "multiple analog procedural regions per generated scope are deferred"
          : "multiple analog procedural regions per component are deferred");
}

LogicalResult verifyGeneratedAnalogOwnershipImpl(Operation *operation) {
  auto generatedOwner = operation->getAttrOfType<StringAttr>("generated_owner");
  auto generatedInduction = operation->getAttrOfType<StringAttr>("generated_induction");
  auto generated = operation->getParentOfType<nodal::GenerateOp>();
  if (static_cast<bool>(generatedOwner) != static_cast<bool>(generatedInduction))
    return operation->emitOpError(
        "NODAL-ITERATION-043-004: generated analog ownership requires paired owner and induction "
        "identities");
  if (!generated) {
    if (generatedOwner)
      return operation->emitOpError(
          "NODAL-ITERATION-043-004: ordinary analog operation cannot claim generated ownership");
    return success();
  }
  auto regionId = generated->getAttrOfType<StringAttr>("region_id");
  auto inductionPath = generated->getAttrOfType<StringAttr>("induction_path");
  if (!generatedOwner || !regionId || !inductionPath ||
      generatedOwner.getValue() != regionId.getValue() ||
      generatedInduction.getValue() != inductionPath.getValue())
    return operation->emitOpError(
        "NODAL-ITERATION-043-004: generated analog ownership or induction does not match its "
        "enclosing generate region");
  if (auto analog = operation->getParentOfType<nodal::AnalogOp>()) {
    if (analog.getOperation() != operation &&
        (analog->getAttr("generated_owner") != generatedOwner ||
         analog->getAttr("generated_induction") != generatedInduction))
      return operation->emitOpError(
          "NODAL-ITERATION-043-004: generated procedural operation escapes its analog owner");
  }
  return success();
}

LogicalResult verifyAnalogProcedure(Operation *operation) {
  if (failed(requireSingleBlock(operation)))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-008",
                                    "analog procedural region requires exactly one body block");
  llvm::StringRef owner = textAttr(operation, "owner");
  if (owner.trim().empty() || owner.trim() != owner)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-009",
                                    "analog procedural owner must be non-empty and canonical");
  bool hasStructuredControl = false;
  operation->walk([&](Operation *nested) {
    if (llvm::isa<nodal::AnalogIfOp, nodal::AnalogCaseOp, nodal::AnalogLoopOp, nodal::AnalogBreakOp,
                  nodal::AnalogContinueOp, nodal::AnalogOnOp, nodal::AnalogArrayFillOp,
                  nodal::AnalogLiveFillOp, nodal::ShapeIndexOp, nodal::ShapeViewOp,
                  nodal::ShapeSliceOp>(nested) ||
        (llvm::isa<nodal::AnalogVariableOp>(nested) &&
         (llvm::isa<nodal::ShapedType>(nested->getResult(0).getType()) ||
          nested->hasAttr("initializer_form"))) ||
        nodal::isAnalogEventExpression(nested))
      hasStructuredControl = true;
  });
  if (hasStructuredControl) {
    if (failed(verifyStructuredProceduralBlock(operation->getRegion(0).front(), owner)))
      return failure();
    return verifyStructuredDefiniteAssignment(operation, owner);
  }

  ProceduralVerificationState state;
  state.owner = owner;
  return verifyProceduralBlock(operation->getRegion(0).front(), state);
}

} // namespace

LogicalResult nodal::verifyGeneratedAnalogOwnership(Operation *operation) {
  return verifyGeneratedAnalogOwnershipImpl(operation);
}

bool nodal::hasStaticIntegerEffect(Value value, Operation *module, llvm::StringRef requiredEffect,
                                   const ParameterIntegerEnvironment *environment) {
  if ((requiredEffect != "shape" && requiredEffect != "width") ||
      !llvm::isa_and_nonnull<nodal::ModuleOp>(module) ||
      (environment && environment->module() != module))
    return false;
  llvm::SmallPtrSet<Operation *, 16> active;
  unsigned work = 0;
  return hasStaticShapeIndexDependencies(value, module, active, 0, !environment, requiredEffect,
                                         environment, &work);
}

FailureOr<nodal::StaticShapeDimension>
nodal::resolveStaticShapeDimension(Operation *module, llvm::StringRef dimension,
                                   llvm::StringRef requiredEffect,
                                   const ParameterIntegerEnvironment *environment) {
  if (requiredEffect != "shape" && requiredEffect != "width")
    return failure();
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module) || dimension.empty() ||
      dimension != dimension.trim())
    return failure();
  if (environment) {
    if (environment->module() != module)
      return failure();
    auto expression =
        resolveCollectionIntegerToken(module, dimension, requiredEffect, *environment);
    if (failed(expression) || expression->bounds.lower <= 0 ||
        expression->bounds.upper < expression->bounds.lower)
      return failure();
    StaticShapeDimension result{expression->bounds};
    if (!dimension.getAsInteger(10, result.literal))
      return result;
    result.parameter = findDirectModuleParameter(module, dimension);
    if (!result.parameter) {
      auto value =
          resolveCollectionIntegerTokenValue(module, dimension, requiredEffect, *environment);
      if (failed(value))
        return failure();
      result.expression = *value;
    }
    return result;
  }
  auto bounds = inferShapeDimensionBounds(module, dimension, true, requiredEffect);
  if (failed(bounds))
    return failure();
  StaticShapeDimension result{*bounds};
  if (!dimension.getAsInteger(10, result.literal))
    return result;
  result.parameter = findDirectModuleParameter(module, dimension);
  if (!result.parameter)
    result.expression = findDirectModuleStaticValue(module, dimension);
  return result;
}

FailureOr<int64_t>
nodal::inferStaticShapeMaximumCardinality(Operation *module, llvm::StringRef dimensions,
                                          const ParameterIntegerEnvironment *environment) {
  llvm::SmallVector<llvm::StringRef> tokens;
  dimensions.split(tokens, ',', -1, true);
  llvm::StringMap<ParameterIntegerBounds> proven;
  uint64_t maximum = 1;
  for (llvm::StringRef token : tokens) {
    auto found = proven.find(token);
    if (found == proven.end()) {
      auto dimension = resolveStaticShapeDimension(module, token, "shape", environment);
      if (failed(dimension))
        return failure();
      found = proven.try_emplace(token, dimension->bounds).first;
    }
    const int64_t extent = found->second.upper;
    if (extent <= 0 || maximum > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) /
                                     static_cast<uint64_t>(extent))
      return failure();
    maximum *= static_cast<uint64_t>(extent);
  }
  return static_cast<int64_t>(maximum);
}

FailureOr<nodal::ParameterIntegerBounds>
nodal::inferStaticShapeIndexBounds(Operation *module, Value value,
                                   const ParameterIntegerEnvironment *environment) {
  if (environment) {
    if (environment->module() != module || !value ||
        failed(verifyCollectionValueScope(value, environment->use(), environment)))
      return failure();
    if (value.getType().isIndex()) {
      auto number = staticShapeLiteral(value);
      if (failed(number))
        return failure();
      return ParameterIntegerBounds{*number, *number};
    }
    if (!value.getType().isSignlessInteger(64))
      return failure();
    if (!hasStaticIntegerEffect(value, module, "shape", environment))
      return failure();
    auto expression = resolveOwnedIntegerExpression(module, value, *environment);
    if (failed(expression))
      return failure();
    return expression->bounds;
  }
  Operation *definition = value ? value.getDefiningOp() : nullptr;
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module) || !definition ||
      definition->getParentOp() != module)
    return failure();
  if (value.getType().isIndex()) {
    auto number = staticShapeLiteral(value);
    if (failed(number))
      return failure();
    return ParameterIntegerBounds{*number, *number};
  }
  llvm::SmallPtrSet<Operation *, 16> active;
  if (!value.getType().isInteger(64) ||
      !hasStaticShapeIndexDependencies(value, module, active, 0, true))
    return failure();
  return inferParameterIntegerBounds(value);
}

FailureOr<nodal::StaticShapeIndexIdentity>
nodal::resolveStaticShapeIndexIdentity(Operation *module, Value value,
                                       const ParameterIntegerEnvironment *environment) {
  if (failed(inferStaticShapeIndexBounds(module, value, environment)))
    return failure();
  if (environment)
    if (auto argument = llvm::dyn_cast<BlockArgument>(value))
      return StaticShapeIndexIdentity{StaticShapeIndexIdentity::StaticArgument,
                                      static_cast<int64_t>(argument.getArgNumber()),
                                      argument.getOwner()->getParentOp()};
  Operation *definition = value.getDefiningOp();
  if (llvm::isa<ConstantOp, ConstLiteralOp>(definition)) {
    auto number = staticShapeLiteral(value);
    if (failed(number))
      return failure();
    return StaticShapeIndexIdentity{StaticShapeIndexIdentity::Literal, *number, nullptr};
  }
  if (llvm::isa<ConstParameterRefOp>(definition)) {
    auto name = definition->getAttrOfType<FlatSymbolRefAttr>("parameter");
    Operation *parameter = name ? findDirectModuleParameter(module, name.getValue()) : nullptr;
    if (!parameter)
      return failure();
    return StaticShapeIndexIdentity{StaticShapeIndexIdentity::Parameter, 0, parameter};
  }
  if (!llvm::isa<ConstExprOp>(definition))
    return failure();
  return StaticShapeIndexIdentity{StaticShapeIndexIdentity::Expression, 0, definition};
}

FailureOr<nodal::ShapeIndexProof>
nodal::resolveShapeIndexProof(Value value, Operation *use,
                              const ParameterIntegerEnvironment *environment) {
  if (!value || !use)
    return failure();
  auto module = use->getParentOfType<nodal::ModuleOp>();
  if (!module)
    return failure();
  if (isDigitalRegionOwner(use->getParentOp())) {
    auto affine = resolveStaticShapeIterationIndex(value, use);
    if (succeeded(affine)) {
      auto definition = value.getDefiningOp();
      return ShapeIndexProof{{definition ? StaticShapeIndexIdentity::IterationAffine
                                         : StaticShapeIndexIdentity::Induction,
                              0, definition ? definition : affine->loop},
                             affine->attainedBounds};
    }
    auto integer = resolveDigitalLoopStaticInteger(value, use);
    if (succeeded(integer)) {
      if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
        auto source = digitalLoopCaptureSource(argument);
        if (succeeded(source) && *source)
          return resolveShapeIndexProof(*source, use->getParentOp());
      }
      if (llvm::isa_and_nonnull<ConstExprOp>(value.getDefiningOp()))
        return ShapeIndexProof{{StaticShapeIndexIdentity::Expression, 0, value.getDefiningOp()},
                               integer->bounds};
      if (integer->root->kind == InstantiatedIntegerNode::Kind::Literal)
        return ShapeIndexProof{{StaticShapeIndexIdentity::Literal, integer->root->literal, nullptr},
                               integer->bounds};
    }
    if (isBoundedDigitalLoop(use->getParentOp()))
      return failure();
  }
  if (environment) {
    auto context = environment->atUse(use);
    if (failed(context))
      return failure();
    auto identity = resolveStaticShapeIndexIdentity(module, value, &*context);
    auto bounds = inferStaticShapeIndexBounds(module, value, &*context);
    if (failed(identity) || failed(bounds))
      return failure();
    return ShapeIndexProof{*identity, *bounds};
  }
  if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
    // Captures are lexical aliases, never fresh selector facts. Re-query their
    // actual source at the owner boundary rather than trust a caller's context.
    if (!llvm::isa<GenerateOp>(argument.getOwner()->getParentOp())) {
      Value source = value;
      Operation *boundary = use;
      unsigned depth = 0;
      while (auto captured = llvm::dyn_cast<BlockArgument>(source)) {
        if (llvm::isa<GenerateOp>(captured.getOwner()->getParentOp()))
          break;
        if (++depth > 64 || failed(verifyDigitalValueScope(source, boundary)))
          return failure();
        auto actual = digitalRegionCaptureSource(captured);
        if (failed(actual) || !*actual)
          return failure();
        boundary = captured.getOwner()->getParentOp();
        source = *actual;
      }
      return resolveShapeIndexProof(source, boundary);
    }
    auto generated = llvm::dyn_cast_or_null<nodal::GenerateOp>(argument.getOwner()->getParentOp());
    if (!generated || argument.getArgNumber() != 0 || !value.getType().isInteger(64) ||
        generated->getParentOfType<nodal::ModuleOp>() != module ||
        !generated->isProperAncestor(use))
      return failure();
    auto domain = resolveGeneratedDomain(generated);
    if (failed(domain) || domain->induction != value)
      return failure();
    return ShapeIndexProof{{StaticShapeIndexIdentity::Induction, 0, generated},
                           domain->attainedInductionBounds};
  }
  Operation *definition = value.getDefiningOp();
  if (definition && llvm::isa<GenerateOp>(definition->getParentOp())) {
    // Literal carriers may be emitted beside their generated use. Only the
    // actual pure literal and lexical dominance establish this narrow proof;
    // parameter and compound static dependencies retain module ownership.
    auto number = staticShapeLiteral(value);
    if (failed(number) || definition->getParentOfType<ModuleOp>() != module ||
        !isDeclaredBeforeStructuredUse(definition, use))
      return failure();
    return ShapeIndexProof{{StaticShapeIndexIdentity::Literal, *number, nullptr},
                           ParameterIntegerBounds{*number, *number}};
  }
  auto identity = resolveStaticShapeIndexIdentity(module, value);
  auto bounds = inferStaticShapeIndexBounds(module, value);
  if (failed(identity) || failed(bounds))
    return failure();
  return ShapeIndexProof{*identity, *bounds};
}

LogicalResult nodal::verifyShapeIndexInExtent(Value value, const StaticShapeDimension &extent,
                                              Operation *use,
                                              const ParameterIntegerEnvironment *environment) {
  auto reject = [&]() {
    return use->emitOpError(
        "NODAL-SHAPE-043-002: index is not in bounds for every legal shape and active occurrence");
  };
  if (isDigitalRegionOwner(use->getParentOp())) {
    auto affine = resolveStaticShapeIterationIndex(value, use);
    if (succeeded(affine)) {
      if (extent.bounds.lower <= 0)
        return reject();
      auto lower = composeIntegerExpression("add", {affine->domain.lower, affine->offset});
      auto upper = composeIntegerExpression("add", {affine->domain.upperExclusive, affine->offset});
      if (failed(lower) || failed(upper))
        return reject();
      if (!affine->attainedBounds)
        return success();
      if (lower->bounds.lower < 0)
        return reject();
      if (affine->attainedBounds->upper < extent.bounds.lower)
        return success();
      FailureOr<InstantiatedIntegerExpression> dimension =
          extent.parameter ? resolveOwnedIntegerParameter(affine->domain.module, extent.parameter)
          : extent.expression
              ? resolveOwnedIntegerExpression(affine->domain.module, extent.expression)
              : FailureOr<InstantiatedIntegerExpression>(
                    makeLiteralIntegerExpression(extent.literal));
      return succeeded(dimension) && proveIntegerExpressionLess(*upper, *dimension, true)
                 ? success()
                 : reject();
    }
  }
  auto proof = resolveShapeIndexProof(value, use, environment);
  if (failed(proof) || extent.bounds.lower <= 0)
    return reject();
  if (proof->identity.kind != StaticShapeIndexIdentity::Induction)
    return proof->attainedBounds && proof->attainedBounds->lower >= 0 &&
                   proof->attainedBounds->upper < extent.bounds.lower
               ? success()
               : reject();
  auto domain = resolveGeneratedDomain(proof->identity.root);
  if (failed(domain) || domain->step.bounds.lower <= 0)
    return reject();
  if (!proof->attainedBounds)
    return success(); // Empty domains do not waive the surrounding type/owner checks.
  if (domain->lower.bounds.lower < 0)
    return reject();
  if (domain->upperExclusive.bounds.upper <= extent.bounds.lower)
    return success();
  FailureOr<InstantiatedIntegerExpression> dimension =
      extent.parameter ? resolveOwnedIntegerParameter(domain->module, extent.parameter)
      : extent.expression
          ? resolveOwnedIntegerExpression(domain->module, extent.expression)
          : FailureOr<InstantiatedIntegerExpression>(makeLiteralIntegerExpression(extent.literal));
  return succeeded(dimension) &&
                 equivalentInstantiatedIntegerExpressions(domain->upperExclusive, *dimension)
             ? success()
             : reject();
}

FailureOr<nodal::AnalogArrayValue> nodal::resolveAnalogArrayValue(Value value, Operation *use) {
  if (!value || !use)
    return failure();
  auto procedure = use->getParentOfType<AnalogProcedureOp>();
  if (!procedure)
    return failure();
  AnalogArrayValue result;
  llvm::SmallPtrSet<Operation *, 16> seen;
  Value cursor = value;
  while (Operation *definition = cursor.getDefiningOp()) {
    if (!seen.insert(definition).second || seen.size() > 512 ||
        definition->getParentOfType<AnalogProcedureOp>() != procedure ||
        textAttr(definition, "owner") != textAttr(procedure, "owner") ||
        failed(verifyGeneratedAnalogOwnership(definition)) ||
        !isVisibleFrom(use->getBlock(), definition->getBlock()) ||
        (definition != use && !isDeclaredBeforeStructuredUse(definition, use)))
      return failure();
    if (llvm::isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(definition)) {
      if (isStorageType(cursor.getType()))
        return failure();
      LogicalResult valid =
          llvm::isa<ShapeIndexOp>(definition)  ? verifyShapeIndexMapping(definition)
          : llvm::isa<ShapeViewOp>(definition) ? verifyShapeViewMapping(definition)
                                               : verifyShapeSliceMapping(definition);
      if (failed(valid))
        return failure();
      result.projections.push_back(definition);
      cursor = definition->getOperand(0);
      continue;
    }
    auto shaped = llvm::dyn_cast<ShapedType>(cursor.getType());
    auto quantity = shaped ? llvm::dyn_cast<QuantityType>(shaped.getElementType()) : QuantityType();
    auto module = definition->getParentOfType<nodal::ModuleOp>();
    if (!shaped || !quantity || quantity.getKind() != "real" || !module ||
        failed(inferStaticShapeMaximumCardinality(module, shaped.getDimensions())))
      return failure();
    if (llvm::isa<AnalogVariableReadOp>(definition)) {
      auto expected = getAnalogStorageSnapshotType(definition->getOperand(0), definition);
      if (failed(expected) || *expected != cursor.getType())
        return failure();
    } else if (llvm::isa<AnalogArrayFillOp>(definition)) {
      if (failed(verifyAnalogArrayFillValue(definition)))
        return failure();
    } else {
      return failure();
    }
    result.producer = definition;
    std::reverse(result.projections.begin(), result.projections.end());
    return result;
  }
  return failure();
}

LogicalResult nodal::PlaceholderOp::verify() {
  auto label = (*this)->getAttrOfType<StringAttr>("label");
  if (!label || label.getValue().empty())
    return emitOpError("requires a non-empty 'label' attribute");
  return success();
}

LogicalResult nodal::ModuleOp::verify() {
  if (failed(verifyHierarchyModule(getOperation())))
    return failure();
  if (failed(requireText(getOperation(), mlir::SymbolTable::getSymbolAttrName(), "module symbol")))
    return failure();
  if (failed(requireSingleBlock(getOperation())))
    return failure();
  if (failed(verifyDigitalInterfaceModel(getOperation())) ||
      failed(verifyDigitalTypeModel(getOperation(), false)) ||
      failed(verifyStructuralIfModel(getOperation())) || failed(verifyClockModel(getOperation())) ||
      failed(DomainModel(getOperation()).verifyRelationships()) ||
      failed(verifyCrossingModel(getOperation())) ||
      failed(verifyDigitalStorageModel(getOperation())) ||
      failed(verifySequentialModel(getOperation())) || failed(verifyMemoryModel(getOperation())) ||
      failed(verifyEnumValueModel(getOperation(), false)) ||
      failed(verifyDigitalValueModel(getOperation(), false)) ||
      failed(verifyDigitalCollectionModel(getOperation())) ||
      failed(verifyDigitalMaterializationReport(getOperation())) ||
      failed(verifyDigitalLoopPlanningReport(getOperation())))
    return failure();
  // Captured region and induction identities are module-local semantic keys.
  // Check each once; independent nested module definitions own their own keys.
  llvm::StringSet<> generatedRegions;
  llvm::StringSet<> generatedInductions;
  auto ownership = getOperation()->walk<WalkOrder::PreOrder>([&](Operation *operation) {
    if (operation != getOperation() && llvm::isa<nodal::ModuleOp>(operation))
      return WalkResult::skip();
    if (!llvm::isa<nodal::GenerateOp>(operation) && !isBoundedDigitalLoop(operation))
      return WalkResult::advance();
    for (auto [attribute, identities] : {std::pair{"region_id", &generatedRegions},
                                         std::pair{"induction_path", &generatedInductions}}) {
      auto value = operation->getAttrOfType<StringAttr>(attribute);
      if (value && !identities->insert(value.getValue()).second) {
        operation->emitOpError() << "NODAL-ITERATION-043-004: duplicate generated " << attribute
                                 << " within its owning module";
        return WalkResult::interrupt();
      }
    }
    return WalkResult::advance();
  });
  if (ownership.wasInterrupted())
    return failure();
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    auto port = llvm::dyn_cast<nodal::PortOp>(&operation);
    if (!port)
      continue;
    auto type = operation.getAttrOfType<TypeAttr>("type");
    if (type && failed(verifyShapedTypeContract(getOperation(), &operation, type.getValue())))
      return failure();
  }
  if (failed(verifyAnalogDeadLocalMarkers(getOperation())))
    return failure();
  if (failed(verifyAnalogLiveModuleResources(getOperation())))
    return failure();
  if (failed(verifyNodeArraySelections(getOperation())))
    return failure();
  if (failed(verifyConservativeArrayBindings(getOperation())))
    return failure();
  return verifySingleTopLevelProcedurePerScope(getOperation());
}

LogicalResult nodal::PortOp::verify() {
  const llvm::StringRef direction = textAttr(getOperation(), "direction");
  if (!oneOf(direction, {"input", "output", "inout", "terminal"}))
    return emitOpError() << "unsupported port direction '" << direction << "'";
  auto type = getOperation()->getAttrOfType<TypeAttr>("type");
  auto controlKind = type ? nodal::getDigitalControlBoundaryKind(type.getValue()) : StringRef();
  bool bareClock = direction == "input" && controlKind == "clock";
  if (!type || (!getOperation()->getAttrOfType<FlatSymbolRefAttr>("domain") && !bareClock))
    return emitOpError("requires type and domain unless an owned input Clock");
  return success();
}

LogicalResult nodal::PortValueOp::verify() {
  auto owner = getOperation()->getParentOfType<nodal::ModuleOp>();
  auto reference = getOperation()->getAttrOfType<FlatSymbolRefAttr>("port");
  if (!owner || !reference)
    return emitOpError("NODAL-SHAPE-043-002: requires an owning module and direct port reference");
  Operation *port = nullptr;
  for (Operation &candidate : owner.getBody().front()) {
    if (!llvm::isa<nodal::PortOp>(candidate))
      continue;
    auto name = candidate.getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName());
    if (name && name.getValue() == reference.getValue()) {
      port = &candidate;
      break;
    }
  }
  auto type = port ? port->getAttrOfType<TypeAttr>("type") : TypeAttr();
  if (!port || !type)
    return emitOpError(
        "NODAL-SHAPE-043-002: referenced port is absent or untyped in the owning module");
  if (getOperation()->getResult(0).getType() != type.getValue())
    return emitOpError("NODAL-SHAPE-043-002: result type must match the referenced port type");
  return success();
}

LogicalResult nodal::ParameterOp::verify() {
  return nodal::verifyParameterDeclaration(getOperation());
}

LogicalResult nodal::InstanceOp::verify() {
  auto module = getOperation()->getAttrOfType<FlatSymbolRefAttr>("module");
  if (!module || module.getValue().empty())
    return emitOpError("requires a referenced module symbol");
  return verifyGeneratedStructuralOwnership(getOperation());
}

LogicalResult nodal::InstanceTerminalOp::verify() {
  auto instance = getOperation()->getAttrOfType<FlatSymbolRefAttr>("instance");
  if (!instance || instance.getValue().empty())
    return emitOpError("requires a direct child instance symbol");
  if (failed(requireText(getOperation(), "port", "child terminal name")) ||
      failed(requireText(getOperation(), "name", "parent endpoint name")) ||
      failed(requireText(getOperation(), "source_path", "child terminal source path")))
    return failure();
  const llvm::StringRef direction = textAttr(getOperation(), "direction");
  if (!oneOf(direction, {"input", "output", "inout"}))
    return emitOpError() << "unsupported child terminal direction '" << direction << "'";
  if (textAttr(getOperation(), "flow_orientation") != "out_of_component")
    return emitOpError("child terminal flow must be oriented out of the parent component");
  return verifyGeneratedStructuralOwnership(getOperation());
}

LogicalResult nodal::InstanceTerminalArrayOp::verify() {
  return verifyConservativeInstanceArray(getOperation());
}

LogicalResult nodal::ConnectArrayOp::verify() {
  return success(succeeded(resolveConservativeRelation(getOperation())));
}

LogicalResult nodal::InterfaceOp::verify() {
  if (hasDigitalInterfaceContract(getOperation()))
    return verifyDigitalInterfaceOperation(getOperation());
  if (failed(
          requireText(getOperation(), mlir::SymbolTable::getSymbolAttrName(), "interface symbol")))
    return failure();
  return requireSingleBlock(getOperation());
}

LogicalResult nodal::InterfaceRoleOp::verify() {
  if (hasDigitalInterfaceContract(getOperation()))
    return verifyDigitalInterfaceOperation(getOperation());
  return requireText(getOperation(), "kind", "interface role kind");
}

LogicalResult nodal::InterfaceMemberOp::verify() {
  if (hasDigitalInterfaceContract(getOperation()))
    return verifyDigitalInterfaceOperation(getOperation());
  const llvm::StringRef protocol = textAttr(getOperation(), "protocol");
  if (!oneOf(protocol, {"plain", "valid", "stream", "resolved", "conservative", "signal_flow"}))
    return emitOpError() << "unsupported interface protocol '" << protocol << "'";
  auto type = getOperation()->getAttrOfType<TypeAttr>("type");
  auto roles = getOperation()->getAttrOfType<ArrayAttr>("roles");
  if (!type || !roles || roles.empty())
    return emitOpError("requires a type and at least one role");
  if (protocol == "valid" && !llvm::isa<nodal::ValidType>(type.getValue()))
    return emitOpError("valid protocol requires !nodal.valid type");
  if (protocol == "stream" && !llvm::isa<nodal::StreamType>(type.getValue()))
    return emitOpError("stream protocol requires !nodal.stream type");
  if (protocol == "resolved" && !llvm::isa<nodal::ResolvedType>(type.getValue()))
    return emitOpError("resolved protocol requires !nodal.resolved type");
  return success();
}

LogicalResult nodal::InterfaceInstanceOp::verify() {
  if (hasDigitalInterfaceContract(getOperation()))
    return verifyDigitalInterfaceOperation(getOperation());
  if (failed(requireText(getOperation(), "role", "interface role")))
    return failure();
  if (!getOperation()->getAttrOfType<FlatSymbolRefAttr>("definition"))
    return emitOpError("requires an Interface definition");
  return success();
}

LogicalResult nodal::MemberAccessOp::verify() {
  if (hasDigitalInterfaceContract(getOperation()))
    return verifyDigitalInterfaceOperation(getOperation());
  if (failed(requireText(getOperation(), "path", "member path")))
    return failure();
  if (!getOperation()->getAttrOfType<FlatSymbolRefAttr>("instance"))
    return emitOpError("requires an Interface instance");
  return success();
}

LogicalResult nodal::InterfaceAbiOp::verify() {
  if (hasDigitalInterfaceContract(getOperation()))
    return verifyDigitalInterfaceOperation(getOperation());
  if (failed(requireText(getOperation(), "logical_path", "logical path")) ||
      failed(requireText(getOperation(), "layout_policy", "layout policy")))
    return failure();
  auto members = getOperation()->getAttrOfType<ArrayAttr>("members");
  if (!members || members.empty())
    return emitOpError("requires at least one ABI member");
  return success();
}

LogicalResult nodal::DomainOp::verify() {
  const llvm::StringRef edge = textAttr(getOperation(), "edge");
  const llvm::StringRef reset = textAttr(getOperation(), "reset_policy");
  if (!oneOf(edge, {"rising", "falling", "both", "analog_event"}))
    return emitOpError() << "unsupported domain edge '" << edge << "'";
  if (!oneOf(reset, {"none", "sync", "async", "async_assert_sync_release"}))
    return emitOpError() << "unsupported reset policy '" << reset << "'";
  return success();
}

LogicalResult nodal::DomainRequirementOp::verify() {
  return requireText(getOperation(), mlir::SymbolTable::getSymbolAttrName(),
                     "domain requirement symbol");
}

LogicalResult nodal::DomainBindOp::verify() {
  if (!getOperation()->getAttrOfType<FlatSymbolRefAttr>("requirement") ||
      !getOperation()->getAttrOfType<FlatSymbolRefAttr>("actual"))
    return emitOpError("requires requirement and actual domains");
  return success();
}

LogicalResult nodal::ClockRelationOp::verify() {
  return verifyRelation(
      getOperation(), "clock relation",
      {"alias", "ratio", "synchronous", "mutually_exclusive", "asynchronous", "unknown"});
}

LogicalResult nodal::ClockRelationshipOp::verify() {
  return verifyClockRelationship(getOperation());
}

LogicalResult nodal::ResetRelationOp::verify() {
  return verifyRelation(
      getOperation(), "reset relation",
      {"alias", "synchronous", "async_assert_sync_release", "independent", "unknown"});
}

LogicalResult nodal::ConstantOp::verify() {
  if (failed(verifyEnumLiteralOperation(getOperation())))
    return failure();
  if (getOperation()->getNumResults() == 1 &&
      isDigitalScalarType(getOperation()->getResult(0).getType()))
    return verifyDigitalLiteralOperation(getOperation());
  Attribute value = getOperation()->getAttr("value");
  if (!value || getOperation()->getNumResults() != 1 ||
      !attributeFits(value, getOperation()->getResult(0).getType()))
    return emitOpError("value is incompatible with result type");
  return success();
}

LogicalResult nodal::verifyShapeIndexMapping(Operation *operation,
                                             const ParameterIntegerEnvironment *environment) {
  if (!environment && operation && isDigitalRegionOwner(operation->getParentOp())) {
    auto contexts = collectionIntegerContextsAtUse(operation);
    if (failed(contexts))
      return failure();
    for (const auto &context : *contexts)
      if (failed(verifyShapeIndexMapping(operation, &context)))
        return failure();
    return success();
  }
  std::optional<ParameterIntegerEnvironment> context;
  if (environment) {
    auto scoped = environment->atUse(operation);
    if (failed(scoped))
      return failure();
    context.emplace(*scoped);
    environment = &*context;
  }
  if (failed(verifyDigitalShapeValueScope(operation, environment)))
    return failure();
  auto shaped = llvm::dyn_cast<nodal::ShapedType>(operation->getOperand(0).getType());
  if (!shaped)
    return operation->emitOpError("NODAL-SHAPE-043-002: input must have !nodal.shaped type");
  if (isProceduralShapeProjection(operation) &&
      failed(verifyProceduralProjectionIdentity(operation)))
    return failure();
  const unsigned indices = operation->getNumOperands() - 1;
  const unsigned rank = shapedRank(shaped.getDimensions());
  const bool conservative = llvm::isa<nodal::TerminalType>(shaped.getElementType());
  if (indices == 0 || indices > rank)
    return operation->emitOpError(
        "NODAL-SHAPE-043-002: index rank is empty or exceeds shaped rank");
  llvm::SmallVector<llvm::StringRef> dimensions;
  shaped.getDimensions().split(dimensions, ',', -1, true);
  auto module = operation->getParentOfType<nodal::ModuleOp>();
  const bool digital = nodal::isDigitalStorageType(shaped);
  auto equalElement = [&](Type left, Type right) {
    return digital ? nodal::equivalentDigitalStorageTypes(left, right, module, environment)
                   : left == right;
  };
  if (indices == rank) {
    if (!equalElement(operation->getResult(0).getType(), shaped.getElementType()))
      return operation->emitOpError(
          "NODAL-SHAPE-043-002: full-rank result must match shaped element type");
  } else {
    auto result = llvm::dyn_cast<nodal::ShapedType>(operation->getResult(0).getType());
    llvm::SmallVector<llvm::StringRef> resultDimensions;
    if (!result || !equalElement(result.getElementType(), shaped.getElementType()))
      return operation->emitOpError(
          "NODAL-SHAPE-043-002: prefix result must retain the shaped element type");
    result.getDimensions().split(resultDimensions, ',', -1, true);
    if (resultDimensions.size() != dimensions.size() - indices)
      return operation->emitOpError(
          "NODAL-SHAPE-043-002: prefix result rank must match the unselected input axes");
    for (unsigned axis = 0; axis < resultDimensions.size(); ++axis)
      if (digital ? !nodal::equivalentDigitalStorageTypes(
                        nodal::ShapedType::get(operation->getContext(), resultDimensions[axis],
                                               shaped.getElementType()),
                        nodal::ShapedType::get(operation->getContext(), dimensions[axis + indices],
                                               shaped.getElementType()),
                        module, environment)
                  : resultDimensions[axis].trim() != dimensions[axis + indices].trim())
        return operation->emitOpError(
            "NODAL-SHAPE-043-002: prefix result dimensions must match the unselected input axes");
  }

  auto owner = operation->getParentOfType<nodal::ModuleOp>();
  if (!owner)
    return operation->emitOpError("NODAL-SHAPE-043-002: shaped indexing requires an owning module");
  llvm::StringMap<int64_t> symbolicMinima;
  for (unsigned axis = 0; axis < indices; ++axis) {
    llvm::StringRef dimension = dimensions[axis].trim();
    int64_t minimumExtent = 0;
    auto cached = symbolicMinima.find(dimension);
    if (cached != symbolicMinima.end()) {
      minimumExtent = cached->second;
    } else {
      auto inferred = inferShapeDimensionMinimum(owner, dimension, environment);
      if (failed(inferred))
        return operation->emitOpError("NODAL-SHAPE-043-002: dimension has no proven finite extent");
      minimumExtent = *inferred;
      symbolicMinima.try_emplace(dimension, minimumExtent);
    }
    Value indexValue = operation->getOperand(axis + 1);
    if (conservative) {
      auto extent = resolveStaticShapeDimension(owner, dimension, "shape", environment);
      if (failed(extent) ||
          failed(verifyShapeIndexInExtent(indexValue, *extent, operation, environment)))
        return failure();
      continue;
    }
    if (environment || isBoundedDigitalLoop(operation->getParentOp())) {
      auto extent = resolveStaticShapeDimension(owner, dimension, "shape", environment);
      if (failed(extent) ||
          failed(verifyShapeIndexInExtent(indexValue, *extent, operation, environment)))
        return failure();
      continue;
    }
    Operation *index = indexValue.getDefiningOp();
    if (!index || index->getParentOfType<nodal::ModuleOp>() != owner)
      return operation->emitOpError(
          "NODAL-SHAPE-043-002: index requires direct-module static ownership");

    int64_t lower = 0;
    int64_t upper = 0;
    if (conservative || isStorageType(shaped)) {
      auto bounds = inferStaticShapeIndexBounds(owner, indexValue, environment);
      if (failed(bounds))
        return operation->emitOpError(
            "NODAL-SHAPE-043-002: node-array index requires a directly owned static Integer proof");
      lower = bounds->lower;
      upper = bounds->upper;
    } else if (indexValue.getType().isIndex()) {
      // Preserve the legacy literal form exactly; metadata and runtime values do not prove it.
      auto literal = index->getAttrOfType<IntegerAttr>("value");
      if (!llvm::isa<nodal::ConstantOp>(index) || index->hasAttr("unit") || !literal ||
          !literal.getType().isIndex() || !literal.getValue().isSignedIntN(64))
        return operation->emitOpError(
            "NODAL-SHAPE-043-002: index requires a proven static index literal");
      lower = upper = literal.getValue().getSExtValue();
    } else if (indexValue.getType().isInteger(64)) {
      llvm::SmallPtrSet<Operation *, 16> active;
      if (!hasStaticShapeIndexDependencies(indexValue, owner, active))
        return operation->emitOpError(
            "NODAL-SHAPE-043-002: Integer index requires structural shape dependencies");
      auto bounds = nodal::inferParameterIntegerBounds(indexValue);
      if (failed(bounds))
        return operation->emitOpError(
            "NODAL-SHAPE-043-002: Integer index has no proven static expression bounds");
      lower = bounds->lower;
      upper = bounds->upper;
    } else {
      return operation->emitOpError(
          "NODAL-SHAPE-043-002: index must be an index literal or static i64 expression");
    }
    if (minimumExtent <= 0 || lower < 0 || upper >= minimumExtent)
      return operation->emitOpError(
                 "NODAL-SHAPE-043-002: index is not in bounds for every legal shape at axis ")
             << axis;
  }
  return success();
}

LogicalResult nodal::ShapeIndexOp::verify() {
  Operation *operation = getOperation();
  if (failed(verifyShapeIndexMapping(operation)))
    return failure();
  auto input = llvm::dyn_cast<ShapedType>(operation->getOperand(0).getType());
  if (input && llvm::isa<TerminalType>(input.getElementType()))
    return success(succeeded(resolveConservativeShape(operation->getResult(0), operation)));
  if (isStorageType(operation->getOperand(0).getType()))
    return verifyAnalogStorageProjection(operation);
  if (isProceduralShapeProjection(operation))
    return success(succeeded(resolveAnalogArrayValue(operation->getResult(0), operation)));
  return success();
}

LogicalResult nodal::ShapeFlattenOp::verify() {
  if (!llvm::isa<nodal::ShapedType>(getOperation()->getOperand(0).getType()))
    return emitOpError("input must have !nodal.shaped type");
  return requireText(getOperation(), "layout", "layout policy");
}

LogicalResult nodal::verifyShapeViewMapping(Operation *operation,
                                            const ParameterIntegerEnvironment *environment) {
  if (!environment && operation && isDigitalRegionOwner(operation->getParentOp())) {
    auto contexts = collectionIntegerContextsAtUse(operation);
    if (failed(contexts))
      return failure();
    for (const auto &context : *contexts)
      if (failed(verifyShapeViewMapping(operation, &context)))
        return failure();
    return success();
  }
  std::optional<ParameterIntegerEnvironment> context;
  if (environment) {
    auto scoped = environment->atUse(operation);
    if (failed(scoped))
      return failure();
    context.emplace(*scoped);
    environment = &*context;
  }
  if (failed(verifyDigitalShapeValueScope(operation, environment)))
    return failure();
  if (failed(requireText(operation, "dimensions", "dimensions")) ||
      failed(requireText(operation, "origin", "origin")) ||
      failed(requireText(operation, "materialization", "materialization")) ||
      failed(requireText(operation, "observability", "observability")))
    return failure();

  // Preserve the scalar-input legacy carrier, but never allow a materialization
  // tag to opt a shaped-to-shaped transformation out of reshape verification.
  auto materialization = textAttr(operation, "materialization");
  if (materialization != "view" && materialization != "explicit_view")
    return operation->emitOpError("NODAL-SHAPE-043-003: unsupported shape-view materialization");
  if (materialization == "explicit_view") {
    if (llvm::isa<nodal::ShapedType>(operation->getOperand(0).getType()))
      return operation->emitOpError(
          "NODAL-SHAPE-043-003: shaped input requires verified view materialization");
    return success();
  }

  auto input = llvm::dyn_cast<nodal::ShapedType>(operation->getOperand(0).getType());
  auto result = llvm::dyn_cast<nodal::ShapedType>(operation->getResult(0).getType());
  auto owner = operation->getParentOfType<nodal::ModuleOp>();
  const bool digital = input && nodal::isDigitalStorageType(input);
  if (!input || !result ||
      (digital ? !nodal::equivalentDigitalStorageTypes(input.getElementType(),
                                                       result.getElementType(), owner, environment)
               : input.getElementType() != result.getElementType()))
    return operation->emitOpError(
        "NODAL-SHAPE-043-003: fixed shape view requires shaped input/result with equal element "
        "types");
  if (textAttr(operation, "dimensions") != result.getDimensions())
    return operation->emitOpError(
        "NODAL-SHAPE-043-003: dimensions must exactly match the result shaped type");
  if (textAttr(operation, "observability") != "source_mapped")
    return operation->emitOpError(
        "NODAL-SHAPE-043-003: fixed shape view requires source-mapped observability");
  auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata");
  auto storage = metadata ? metadata.getAs<StringAttr>("storage") : StringAttr();
  const bool procedural = isProceduralShapeProjection(operation);
  if (procedural) {
    if (failed(verifyProceduralProjectionIdentity(operation)))
      return failure();
  } else if (!storage || storage.getValue() != "structural")
    return operation->emitOpError(
        "NODAL-SHAPE-043-003: fixed shape view requires structural storage intent");

  if (!owner)
    return operation->emitOpError("NODAL-SHAPE-043-003: shape view requires an owning module");
  const std::string inputIdentity =
      digital ? nodal::digitalValueSemanticPath(operation->getOperand(0)) : std::string();
  if (!inputIdentity.empty() && textAttr(operation, "origin") != inputIdentity)
    return operation->emitOpError(
        "NODAL-SHAPE-043-003: digital view origin must match its actual input identity");
  if (environment) {
    auto cardinality = [&](llvm::StringRef dimensions) -> FailureOr<InstantiatedIntegerExpression> {
      InstantiatedIntegerExpression result = makeLiteralIntegerExpression(1);
      llvm::SmallVector<llvm::StringRef> tokens;
      dimensions.split(tokens, ',', -1, true);
      if (tokens.empty() || tokens.size() > digitalTypeMaximumNodes)
        return failure();
      for (auto token : tokens) {
        auto extent = resolveStaticShapeDimension(owner, token, "shape", environment);
        auto expression = resolveCollectionIntegerToken(owner, token, "shape", *environment);
        if (failed(extent) || failed(expression))
          return failure();
        auto product = composeIntegerExpression("mul", {result, *expression});
        if (failed(product))
          return failure();
        result = *product;
      }
      return result;
    };
    auto left = cardinality(input.getDimensions()), right = cardinality(result.getDimensions());
    if (failed(left) || failed(right) || !equivalentInstantiatedIntegerExpressions(*left, *right))
      return operation->emitOpError(
          "NODAL-SHAPE-043-003: shape view requires exact equal contextual cardinalities");
    return success();
  }
  auto shapeSignature = [&](llvm::StringRef dimensions)
      -> std::optional<std::pair<uint64_t, std::multiset<std::string>>> {
    llvm::SmallVector<llvm::StringRef> tokens;
    dimensions.split(tokens, ',', -1, true);
    if (tokens.empty())
      return std::nullopt;
    uint64_t literalProduct = 1;
    uint64_t worstCase = 1;
    std::multiset<std::string> symbols;
    llvm::SmallPtrSet<Operation *, 16> active;

    auto multiply = [](uint64_t &product, int64_t factor) {
      if (factor <= 0 || product > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) /
                                       static_cast<uint64_t>(factor))
        return false;
      product *= static_cast<uint64_t>(factor);
      return true;
    };

    auto collectStaticFactor = [&](auto &&self, Value value, unsigned depth) -> bool {
      Operation *operation = value.getDefiningOp();
      if (!operation || depth >= 512 || !active.insert(operation).second ||
          !value.getType().isInteger(64))
        return false;
      const llvm::scope_exit remove([&] { active.erase(operation); });
      llvm::SmallPtrSet<Operation *, 16> dependencyActive;
      if (!hasStaticShapeIndexDependencies(value, owner, dependencyActive))
        return false;
      auto bounds = nodal::inferParameterIntegerBounds(value);
      if (failed(bounds) || bounds->lower <= 0)
        return false;

      if (llvm::isa<nodal::ConstLiteralOp>(operation)) {
        if (bounds->lower != bounds->upper || !multiply(literalProduct, bounds->upper))
          return false;
        return multiply(worstCase, bounds->upper);
      }
      if (llvm::isa<nodal::ConstParameterRefOp>(operation)) {
        auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("parameter");
        Operation *parameter =
            reference ? findDirectModuleParameter(owner, reference.getValue()) : nullptr;
        if (!reference || !parameter)
          return false;
        auto type = parameter->getAttrOfType<TypeAttr>("type");
        auto parameterBounds = nodal::inferParameterIntegerBounds(parameter);
        if (!type || !type.getValue().isInteger(64) || !nodal::isStructuralParameter(parameter) ||
            !parameterHasShapeEnvelope(owner, reference.getValue()) ||
            !parameterHasPositiveFiniteRange(owner, reference.getValue()) ||
            failed(parameterBounds) || parameterBounds->lower <= 0)
          return false;
        symbols.insert(reference.getValue().str());
        return multiply(worstCase, parameterBounds->upper);
      }
      auto expression = llvm::dyn_cast<nodal::ConstExprOp>(operation);
      if (!expression)
        return false;
      if (textAttr(operation, "operator_name") == "mul") {
        if (operation->getNumOperands() != 2)
          return false;
        for (Value operand : operation->getOperands())
          if (!self(self, operand, depth + 1))
            return false;
        return true;
      }
      llvm::StringRef semanticPath = metadataSemanticPath(operation);
      if (semanticPath.empty())
        return false;
      symbols.insert(semanticPath.str());
      return multiply(worstCase, bounds->upper);
    };

    for (llvm::StringRef token : tokens) {
      if (token != token.trim())
        return std::nullopt;
      token = token.trim();
      if (token.empty())
        return std::nullopt;
      int64_t extent = 0;
      if (!token.getAsInteger(10, extent)) {
        if (!multiply(literalProduct, extent) || !multiply(worstCase, extent))
          return std::nullopt;
      } else {
        if (token.contains(' ') || token.contains('\t') || token.contains('\n') ||
            token.contains('\r'))
          return std::nullopt;
        Operation *parameter = findDirectModuleParameter(owner, token);
        if (parameter) {
          auto type = parameter->getAttrOfType<TypeAttr>("type");
          auto bounds = nodal::inferParameterIntegerBounds(parameter);
          if (!type || !type.getValue().isInteger(64) || !nodal::isStructuralParameter(parameter) ||
              !parameterHasShapeEnvelope(owner, token) ||
              !parameterHasPositiveFiniteRange(owner, token) || failed(bounds) ||
              bounds->lower <= 0 || !multiply(worstCase, bounds->upper))
            return std::nullopt;
          symbols.insert(token.str());
        } else {
          Value value = findDirectModuleStaticValue(owner, token);
          if (!value || !value.getType().isInteger(64))
            return std::nullopt;
          auto expression = llvm::dyn_cast<nodal::ConstExprOp>(value.getDefiningOp());
          llvm::SmallPtrSet<Operation *, 16> dependencyActive;
          auto bounds = nodal::inferParameterIntegerBounds(value);
          if (!expression || !hasStaticShapeIndexDependencies(value, owner, dependencyActive) ||
              failed(bounds) || bounds->lower <= 0)
            return std::nullopt;
          if (textAttr(expression, "operator_name") == "mul") {
            if (expression->getNumOperands() != 2)
              return std::nullopt;
            for (Value operand : expression->getOperands())
              if (!collectStaticFactor(collectStaticFactor, operand, 1))
                return std::nullopt;
          } else {
            symbols.insert(token.str());
            if (!multiply(worstCase, bounds->upper))
              return std::nullopt;
          }
        }
      }
    }
    return std::make_pair(literalProduct, std::move(symbols));
  };
  auto inputSignature = shapeSignature(input.getDimensions());
  auto resultSignature = shapeSignature(result.getDimensions());
  if (!inputSignature || !resultSignature || *inputSignature != *resultSignature)
    return operation->emitOpError(
        "NODAL-SHAPE-043-003: shape view requires equal literal products and bounded structural "
        "static-expression root multisets within signed 64-bit counts");
  return success();
}

LogicalResult nodal::ShapeViewOp::verify() {
  Operation *operation = getOperation();
  if (failed(verifyShapeViewMapping(operation)))
    return failure();
  auto input = llvm::dyn_cast<ShapedType>(operation->getOperand(0).getType());
  if (input && llvm::isa<TerminalType>(input.getElementType()))
    return success(succeeded(resolveConservativeShape(operation->getResult(0), operation)));
  if (isStorageType(operation->getOperand(0).getType()))
    return verifyAnalogStorageProjection(operation);
  if (isProceduralShapeProjection(operation))
    return success(succeeded(resolveAnalogArrayValue(operation->getResult(0), operation)));
  return success();
}

LogicalResult nodal::verifyShapeSliceMapping(Operation *operation,
                                             const ParameterIntegerEnvironment *environment) {
  if (!environment && operation && isDigitalRegionOwner(operation->getParentOp())) {
    auto contexts = collectionIntegerContextsAtUse(operation);
    if (failed(contexts))
      return failure();
    for (const auto &context : *contexts)
      if (failed(verifyShapeSliceMapping(operation, &context)))
        return failure();
    return success();
  }
  std::optional<ParameterIntegerEnvironment> context;
  if (environment) {
    auto scoped = environment->atUse(operation);
    if (failed(scoped))
      return failure();
    context.emplace(*scoped);
    environment = &*context;
  }
  if (failed(verifyDigitalShapeValueScope(operation, environment)))
    return failure();
  auto input = llvm::dyn_cast<nodal::ShapedType>(operation->getOperand(0).getType());
  auto result = llvm::dyn_cast<nodal::ShapedType>(operation->getResult(0).getType());
  auto owner = operation->getParentOfType<nodal::ModuleOp>();
  const bool digital = input && nodal::isDigitalStorageType(input);
  if (!input || !result ||
      (digital ? !nodal::equivalentDigitalStorageTypes(input.getElementType(),
                                                       result.getElementType(), owner, environment)
               : input.getElementType() != result.getElementType()))
    return operation->emitOpError(
        "NODAL-SHAPE-043-004: slice requires shaped input/result with equal element types");
  Operation *source = operation->getOperand(0).getDefiningOp();
  const bool structuralSource =
      source &&
      llvm::isa<nodal::PortValueOp, nodal::NodeArrayOp, nodal::TerminalArrayOp, nodal::ShapeIndexOp,
                nodal::ShapeViewOp, nodal::ShapeSliceOp>(source) &&
      (!llvm::isa<nodal::ShapeViewOp>(source) || textAttr(source, "materialization") == "view");
  const bool procedural = isProceduralShapeProjection(operation);
  const bool conservative = llvm::isa<nodal::TerminalType>(input.getElementType());
  if (procedural) {
    if (failed(verifyProceduralProjectionIdentity(operation)))
      return failure();
  } else if (conservative) {
    if (!owner || !source || source->getParentOfType<nodal::ModuleOp>() != owner ||
        !llvm::isa<nodal::ModuleOp, nodal::GenerateOp>(operation->getParentOp()) ||
        !llvm::isa<nodal::NodeArrayOp, nodal::TerminalArrayOp, nodal::InstanceTerminalArrayOp,
                   nodal::ShapeIndexOp, nodal::ShapeViewOp, nodal::ShapeSliceOp>(source) ||
        !isDeclaredBeforeStructuredUse(source, operation) ||
        failed(verifyGeneratedStructuralOwnership(operation)))
      return operation->emitOpError(
          "NODAL-SHAPE-043-004: conservative slice requires an owned visible structural input");
  } else if (digital) {
    // Existing structural roots retain their checked identity. Additional
    // computed roots come from the shared digital capability registry, rather
    // than granting every shaped neutral operation readable-value authority.
    if (!owner ||
        failed(
            nodal::verifyCollectionValueScope(operation->getOperand(0), operation, environment)) ||
        (source && !structuralSource &&
         !nodal::isDigitalBackendOperation(source->getName().getStringRef())))
      return operation->emitOpError(
          "NODAL-SHAPE-043-004: digital slice requires a visible owned readable value");
  } else if (!owner || operation->getParentOp() != owner || !source ||
             source->getParentOp() != owner || !structuralSource)
    return operation->emitOpError(
        "NODAL-SHAPE-043-004: slice input requires a directly owned structural shape value");

  auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata");
  auto storage = metadata ? metadata.getAs<StringAttr>("storage") : StringAttr();
  auto access = metadata ? metadata.getAs<StringAttr>("access") : StringAttr();
  auto inputPath = metadata ? metadata.getAs<StringAttr>("input_path") : StringAttr();
  llvm::StringRef ownerPath = metadataSemanticPath(owner);
  if (ownerPath.empty())
    ownerPath = textAttr(owner, "sym_name");
  const std::string ownerPrefix = (ownerPath + ".").str();
  auto ownedPath = [&](llvm::StringRef path) {
    return path == path.trim() && path.starts_with(ownerPrefix) && path.size() > ownerPrefix.size();
  };
  const std::string sourceIdentity = nodal::isDigitalStorageType(input)
                                         ? nodal::digitalValueSemanticPath(operation->getOperand(0))
                                         : metadataSemanticPath(source).str();
  llvm::StringRef sourcePath = sourceIdentity;
  llvm::StringRef resultPath = metadataSemanticPath(operation);
  if ((!procedural && (!storage || storage.getValue() != "structural" || !access ||
                       access.getValue() != "read_only")) ||
      textAttr(operation, "observability") != "source_mapped" || !inputPath ||
      inputPath.getValue() != sourcePath || textAttr(operation, "origin") != sourcePath ||
      !ownedPath(sourcePath) || !ownedPath(resultPath) || resultPath == sourcePath)
    return operation->emitOpError(
        "NODAL-SHAPE-043-004: slice requires owned source-mapped read-only structural identity");
  for (Operation &candidate : conservative ? *operation->getBlock() : owner->getRegion(0).front())
    if (&candidate != operation && metadataSemanticPath(&candidate) == resultPath)
      return operation->emitOpError("NODAL-SHAPE-043-004: slice semantic identity must be unique");
  if (llvm::isa_and_nonnull<nodal::PortValueOp>(source)) {
    auto port = source->getAttrOfType<FlatSymbolRefAttr>("port");
    Operation *declaration = nullptr;
    for (Operation &candidate : owner->getRegion(0).front())
      if (port && llvm::isa<nodal::PortOp>(candidate) &&
          textAttr(&candidate, "sym_name") == port.getValue())
        declaration = &candidate;
    if (!declaration || metadataSemanticPath(declaration) != sourcePath)
      return operation->emitOpError(
          "NODAL-SHAPE-043-004: slice port input must retain its declaration source identity");
  }
  if (textAttr(operation, "dimensions") != result.getDimensions())
    return operation->emitOpError(
        "NODAL-SHAPE-043-004: dimensions must exactly match the result shaped type");

  llvm::SmallVector<llvm::StringRef> inputDimensions, resultDimensions;
  input.getDimensions().split(inputDimensions, ',', -1, true);
  result.getDimensions().split(resultDimensions, ',', -1, true);
  const int64_t axis = operation->getAttrOfType<IntegerAttr>("axis").getInt();
  if (axis < 0 || static_cast<uint64_t>(axis) >= inputDimensions.size() ||
      inputDimensions.size() != resultDimensions.size())
    return operation->emitOpError("NODAL-SHAPE-043-004: slice axis or result rank is invalid");
  for (unsigned index = 0; index < inputDimensions.size(); ++index) {
    if (index == static_cast<uint64_t>(axis))
      continue;
    bool equal = inputDimensions[index] == resultDimensions[index];
    if (environment) {
      auto left =
          resolveCollectionIntegerToken(owner, inputDimensions[index], "shape", *environment);
      auto right =
          resolveCollectionIntegerToken(owner, resultDimensions[index], "shape", *environment);
      equal = succeeded(left) && succeeded(right) &&
              equivalentInstantiatedIntegerExpressions(*left, *right);
    }
    if (!equal)
      return operation->emitOpError(
          "NODAL-SHAPE-043-004: slice must preserve every unselected axis");
  }

  // Reuse the shape/parameter bounds owner for every dimension. No reshape
  // count-equality rule applies: a slice changes the selected axis length.
  if (failed(inferStaticShapeMaximumCardinality(owner, input.getDimensions(), environment)) ||
      failed(inferStaticShapeMaximumCardinality(owner, result.getDimensions(), environment)))
    return operation->emitOpError(
        "NODAL-SHAPE-043-004: slice dimensions require positive finite structural proofs and "
        "signed 64-bit cardinality");

  auto literal = [](Value value) -> std::optional<int64_t> {
    Operation *definition = value.getDefiningOp();
    if (!llvm::isa_and_nonnull<nodal::ConstLiteralOp>(definition) || !value.getType().isInteger(64))
      return std::nullopt;
    auto attribute = definition->getAttrOfType<IntegerAttr>("value");
    if (!attribute || attribute.getType() != value.getType() ||
        !attribute.getValue().isSignedIntN(64))
      return std::nullopt;
    return attribute.getValue().getSExtValue();
  };
  auto parameter = [&](Value value) -> Operation * {
    Operation *definition = value.getDefiningOp();
    if (!llvm::isa_and_nonnull<nodal::ConstParameterRefOp>(definition))
      return nullptr;
    auto reference = definition->getAttrOfType<FlatSymbolRefAttr>("parameter");
    return reference ? findDirectModuleParameter(owner, reference.getValue()) : nullptr;
  };
  auto sameIdentity = [&](Value left, Value right) {
    if (environment) {
      auto a = resolveOwnedIntegerExpression(owner, left, *environment);
      auto b = resolveOwnedIntegerExpression(owner, right, *environment);
      return succeeded(a) && succeeded(b) && equivalentInstantiatedIntegerExpressions(*a, *b);
    }
    if (auto number = literal(left))
      return literal(right) == number;
    if (Operation *declaration = parameter(left))
      return parameter(right) == declaration;
    return left == right;
  };
  auto dimensionMatches = [&](llvm::StringRef dimension, Value value) {
    if (environment) {
      auto a = resolveCollectionIntegerToken(owner, dimension, "shape", *environment);
      auto b = resolveOwnedIntegerExpression(owner, value, *environment);
      return succeeded(a) && succeeded(b) && equivalentInstantiatedIntegerExpressions(*a, *b);
    }
    if (auto number = literal(value))
      return *number > 0 && dimension == std::to_string(*number);
    if (Operation *declaration = parameter(value))
      return dimension == textAttr(declaration, "sym_name");
    return llvm::isa_and_nonnull<nodal::ConstExprOp>(value.getDefiningOp()) &&
           dimension == metadataSemanticPath(value.getDefiningOp()) &&
           findDirectModuleStaticValue(owner, dimension) == value;
  };
  auto staticBounds = [&](Value value) -> FailureOr<nodal::ParameterIntegerBounds> {
    if (environment)
      return inferStaticShapeIndexBounds(owner, value, environment);
    llvm::SmallPtrSet<Operation *, 16> active;
    const bool localDigitalSelector =
        nodal::isDigitalStorageType(input) &&
        operation->getParentOp()->getName().getStringRef() == "nodal.collection" &&
        llvm::isa_and_nonnull<nodal::ConstLiteralOp>(value.getDefiningOp()) &&
        succeeded(nodal::verifyCollectionValueScope(value, operation, environment));
    if (!value.getType().isInteger(64) ||
        !hasStaticShapeIndexDependencies(value, owner, active, 0, !localDigitalSelector))
      return failure();
    if (llvm::isa<nodal::ConstExprOp>(value.getDefiningOp())) {
      llvm::StringRef path = metadataSemanticPath(value.getDefiningOp());
      if (!ownedPath(path) || findDirectModuleStaticValue(owner, path) != value)
        return failure();
    }
    return nodal::inferParameterIntegerBounds(value);
  };
  Value lower = operation->getOperand(1), upper = operation->getOperand(2),
        length = operation->getOperand(3);
  auto lowerBounds = staticBounds(lower), upperBounds = staticBounds(upper),
       lengthBounds = staticBounds(length);
  if (failed(lowerBounds) || failed(upperBounds) || failed(lengthBounds) ||
      lowerBounds->lower < 0 || lengthBounds->lower <= 0)
    return operation->emitOpError(
        "NODAL-SHAPE-043-004: slice endpoints and positive length require owned static Integer "
        "proofs for every legal parameter setting");

  bool canonicalLength = false;
  auto lowerLiteral = literal(lower), upperLiteral = literal(upper);
  if (lowerLiteral && upperLiteral) {
    // Nonnegative lower and a positive mathematical difference fit i64 once
    // upper > lower; compare before subtracting to avoid signed overflow.
    auto lengthLiteral = literal(length);
    canonicalLength = lengthLiteral && *upperLiteral > *lowerLiteral &&
                      *lengthLiteral == *upperLiteral - *lowerLiteral;
  } else if (lowerLiteral && *lowerLiteral == 0) {
    canonicalLength = sameIdentity(length, upper);
  } else {
    Operation *definition = length.getDefiningOp();
    canonicalLength = llvm::isa<nodal::ConstExprOp>(definition) &&
                      textAttr(definition, "operator_name") == "sub" &&
                      definition->getNumOperands() == 2 &&
                      sameIdentity(definition->getOperand(0), upper) &&
                      sameIdentity(definition->getOperand(1), lower);
  }
  if (!canonicalLength || !dimensionMatches(resultDimensions[axis], length))
    return operation->emitOpError(
        "NODAL-SHAPE-043-004: result extent must retain the exact canonical upper-minus-lower "
        "length identity");
  if (!dimensionMatches(inputDimensions[axis], upper) &&
      upperBounds->upper >
          resolveStaticShapeDimension(owner, inputDimensions[axis], "shape", environment)
              ->bounds.lower)
    return operation->emitOpError(
        "NODAL-SHAPE-043-004: slice upper endpoint exceeds a legal source-axis extent");
  return success();
}

LogicalResult nodal::ShapeSliceOp::verify() {
  Operation *operation = getOperation();
  if (failed(verifyShapeSliceMapping(operation)))
    return failure();
  auto input = llvm::dyn_cast<ShapedType>(operation->getOperand(0).getType());
  if (input && llvm::isa<TerminalType>(input.getElementType()))
    return success(succeeded(resolveConservativeShape(operation->getResult(0), operation)));
  if (isStorageType(operation->getOperand(0).getType()))
    return verifyAnalogStorageProjection(operation);
  if (isProceduralShapeProjection(operation))
    return success(succeeded(resolveAnalogArrayValue(operation->getResult(0), operation)));
  return success();
}

LogicalResult nodal::GenerateOp::verify() {
  if (failed(verifyLoop(getOperation())))
    return failure();

  if (failed(verifyGeneratedBoundForm(getOperation())))
    return failure();

  Block &body = getOperation()->getRegion(0).front();
  if (body.getNumArguments() > 1 ||
      (body.getNumArguments() == 1 && !body.getArgument(0).getType().isInteger(64)))
    return emitOpError("NODAL-ITERATION-043-004: generated body accepts at most one i64 induction");

  auto regionId = getOperation()->getAttrOfType<StringAttr>("region_id");
  auto inductionPath = getOperation()->getAttrOfType<StringAttr>("induction_path");
  if (static_cast<bool>(regionId) != static_cast<bool>(inductionPath))
    return emitOpError(
        "NODAL-ITERATION-043-004: generated region identity and induction path must be provided "
        "together");
  if ((regionId &&
       (regionId.getValue().trim().empty() || regionId.getValue().trim() != regionId.getValue())) ||
      (inductionPath && (inductionPath.getValue().trim().empty() ||
                         inductionPath.getValue().trim() != inductionPath.getValue())))
    return emitOpError(
        "NODAL-ITERATION-043-004: generated region identity and induction path must be canonical");

  Operation *parent = getOperation()->getParentOp();
  auto parentGenerate = llvm::dyn_cast_or_null<nodal::GenerateOp>(parent);
  auto parentIdentity =
      parentGenerate ? parentGenerate->getAttrOfType<StringAttr>("region_id") : StringAttr();
  if (regionId) {
    if (!llvm::isa_and_nonnull<nodal::ModuleOp>(parent) && !parentIdentity)
      return emitOpError(
          "NODAL-ITERATION-043-004: captured generation requires an immediate module or "
          "captured generate parent");
    if (parentIdentity && !regionId.getValue().starts_with((parentIdentity.getValue() + ".").str()))
      return emitOpError(
          "NODAL-ITERATION-043-004: nested generated identity escapes its immediate parent");
  } else if (parentIdentity) {
    return emitOpError(
        "NODAL-ITERATION-043-004: nested captured generation cannot omit ownership identities");
  }
  if (body.getNumArguments()) {
    if (!regionId)
      return emitOpError("NODAL-ITERATION-043-004: induction argument requires a captured domain");
    if (failed(resolveGeneratedDomain(getOperation())))
      return failure();
  }
  if (failed(verifyGeneratedFamilyOperation(getOperation())) ||
      failed(verifyDigitalGenerateBody(getOperation())) ||
      failed(verifyNodeArraySelections(getOperation())))
    return failure();
  return success(succeeded(resolveConservativeStructuralScope(getOperation())));
}

LogicalResult nodal::GeneratedExportOp::verify() {
  return verifyGeneratedExportOperation(getOperation());
}
LogicalResult nodal::GeneratedMemberOp::verify() {
  return success(succeeded(inspectGeneratedMember(getOperation())));
}

LogicalResult nodal::HardwareLoopOp::verify() {
  if (getOperation()->hasAttr("contract_version"))
    return verifyDigitalLoopOperation(getOperation());
  if (getOperation()->getNumOperands() || getOperation()->hasAttr("region_id") ||
      getOperation()->hasAttr("induction_path") || getOperation()->hasAttr("maximum") ||
      !getOperation()->hasAttr("lower") || !getOperation()->hasAttr("upper") ||
      !getOperation()->hasAttr("step") || getOperation()->getRegion(0).front().getNumArguments())
    return emitOpError("legacy hardware loop must retain only its separate attribute-bound form");
  if (failed(verifyLoop(getOperation())))
    return failure();
  const llvm::StringRef policy = textAttr(getOperation(), "effect_policy");
  if (!oneOf(policy, {"pure", "ordered", "state_update"}))
    return emitOpError() << "unsupported effect policy '" << policy << "'";
  return success();
}

LogicalResult nodal::HardwareLoopEndOp::verify() {
  auto header = inspectDigitalLoopHeader(getOperation()->getParentOp());
  if (failed(header) || header->body->empty() || &header->body->back() != getOperation() ||
      getOperation()->getNumOperands() || getOperation()->getNumResults())
    return emitOpError("bounded loop end requires its actual unique final position");
  return success();
}
LogicalResult nodal::IterationIndexOp::verify() {
  return verifyDigitalIterationIndexOperation(getOperation());
}

LogicalResult nodal::ResolvedNetOp::verify() {
  if (hasDigitalInterfaceContract(getOperation()))
    return verifyDigitalInterfaceOperation(getOperation());
  return requireText(getOperation(), "name", "resolved-net name");
}

LogicalResult nodal::ResolvedBoundaryOp::verify() {
  return verifyDigitalInterfaceOperation(getOperation());
}

LogicalResult nodal::NetReadOp::verify() {
  if (hasDigitalInterfaceContract(getOperation()))
    return verifyDigitalInterfaceOperation(getOperation());
  auto net = llvm::cast<nodal::ResolvedType>(getOperation()->getOperand(0).getType());
  if (getOperation()->getResult(0).getType() != net.getElementType())
    return emitOpError("result must match resolved-net element type");
  return success();
}

LogicalResult nodal::NetDriverOp::verify() {
  if (hasDigitalInterfaceContract(getOperation()))
    return verifyDigitalInterfaceOperation(getOperation());
  auto net = llvm::cast<nodal::ResolvedType>(getOperation()->getOperand(0).getType());
  auto driver = llvm::cast<nodal::DriverType>(getOperation()->getResult(0).getType());
  if (driver.getElementType() != net.getElementType())
    return emitOpError("driver must match resolved-net element type");
  return requireText(getOperation(), "driver_id", "driver identity");
}

LogicalResult nodal::NetDriveOp::verify() {
  if (hasDigitalInterfaceContract(getOperation()))
    return verifyDigitalInterfaceOperation(getOperation());
  if (!getOperation()->getOperand(3).getType().isSignlessInteger(1))
    return emitOpError("legacy net drive enable must be i1");
  auto net = llvm::cast<nodal::ResolvedType>(getOperation()->getOperand(0).getType());
  auto driver = llvm::cast<nodal::DriverType>(getOperation()->getOperand(1).getType());
  if (driver.getElementType() != net.getElementType() ||
      getOperation()->getOperand(2).getType() != net.getElementType())
    return emitOpError("driver and value types must match the resolved net");
  if (textAttr(getOperation(), "mode") != net.getDriveMode())
    return emitOpError("drive mode must match the resolved net");
  return success();
}

LogicalResult nodal::TerminalOp::verify() {
  return requireText(getOperation(), "name", "terminal name");
}

LogicalResult nodal::NodeOp::verify() {
  if (failed(requireText(getOperation(), "name", "node name")))
    return failure();

  auto generatedOwner = getOperation()->getAttrOfType<StringAttr>("generated_owner");
  auto generatedInduction = getOperation()->getAttrOfType<StringAttr>("generated_induction");
  auto generated = getOperation()->getParentOfType<nodal::GenerateOp>();
  StringAttr regionId;
  StringAttr inductionPath;
  if (generated) {
    regionId = generated->getAttrOfType<StringAttr>("region_id");
    inductionPath = generated->getAttrOfType<StringAttr>("induction_path");
  }

  const bool hasGeneratedContract =
      generatedOwner || generatedInduction || regionId || inductionPath;
  if (!hasGeneratedContract)
    return success();
  if (!generated || !generatedOwner || !generatedInduction || !regionId || !inductionPath)
    return emitOpError(
        "NODAL-ITERATION-043-004: generated node requires complete enclosing ownership metadata");
  if (generatedOwner.getValue() != regionId.getValue() ||
      generatedInduction.getValue() != inductionPath.getValue())
    return emitOpError(
        "NODAL-ITERATION-043-004: generated node ownership or induction metadata does not match "
        "its enclosing generate region");
  return success();
}

static LogicalResult verifyConservativeArrayDeclaration(Operation *operation, bool boundary) {
  auto owner = operation->getParentOfType<nodal::ModuleOp>();
  Operation *scope = operation->getParentOp();
  const bool generated = llvm::isa_and_nonnull<nodal::GenerateOp>(scope);
  auto shaped = llvm::dyn_cast<nodal::ShapedType>(operation->getResult(0).getType());
  auto terminal =
      shaped ? llvm::dyn_cast<nodal::TerminalType>(shaped.getElementType()) : nodal::TerminalType();
  if (!owner || (scope != owner && (!generated || boundary)) || !terminal ||
      terminal.getDiscipline() != "electrical" ||
      failed(nodal::resolveDisciplineDeclaration(
          operation, FlatSymbolRefAttr::get(operation->getContext(), terminal.getDiscipline()))))
    return operation->emitOpError(
        "NODAL-SHAPE-043-005: node array requires directly owned shaped Electrical terminals");
  if (failed(nodal::verifyGeneratedStructuralOwnership(operation)))
    return failure();
  // Local generated roots are one declaration per captured structural
  // occurrence. Reuse the established domain proof even without an induction
  // block argument; do not classify unrelated legacy analog loops here.
  if (generated && failed(nodal::resolveGeneratedDomain(scope)))
    return failure();
  auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata");
  auto storage = metadata ? metadata.getAs<StringAttr>("storage") : StringAttr();
  llvm::StringRef name = textAttr(operation, "name"), source = textAttr(operation, "source_path");
  llvm::StringRef ownerPath = metadataSemanticPath(owner);
  if (ownerPath.empty())
    ownerPath = textAttr(owner, "sym_name");
  if (name.empty() || name != name.trim() || source != (ownerPath + "." + name).str() ||
      metadataSemanticPath(operation) != source || !storage || storage.getValue() != "structural" ||
      operation->hasAttr("dimensions") || (!boundary && operation->hasAttr("direction")))
    return operation->emitOpError(
        "NODAL-SHAPE-043-005: node array requires canonical structural declaration identity");
  if (boundary && (!oneOf(textAttr(operation, "direction"), {"input", "output", "inout"}) ||
                   textAttr(operation, "flow_orientation") != "into_component"))
    return operation->emitOpError(
        "NODAL-SHAPE-043-008: boundary array requires conservative direction and inward incidence");
  if (failed(inferStaticShapeMaximumCardinality(owner, shaped.getDimensions())))
    return operation->emitOpError(
        "NODAL-SHAPE-043-005: node-array dimensions require positive finite owned "
        "proofs and signed 64-bit cardinality");
  for (Operation &candidate : scope->getRegion(0).front()) {
    if (&candidate != operation &&
        (textAttr(&candidate, "name") == name || metadataSemanticPath(&candidate) == source))
      return operation->emitOpError(
          "NODAL-SHAPE-043-005: node-array declaration identity must be unique");
  }
  // A generated sparse declaration cannot silently satisfy its enclosing
  // module's complete physical-equation contract.
  for (Operation &candidate : owner->getRegion(0).front()) {
    if (llvm::isa<nodal::ComponentContractOp>(candidate) &&
        textAttr(&candidate, "kind") == "concrete")
      return operation->emitOpError(
          "NODAL-SHAPE-043-005: sparse node-array selections do not prove complete "
          "component coverage");
  }
  return success();
}

LogicalResult nodal::NodeArrayOp::verify() {
  return verifyConservativeArrayDeclaration(getOperation(), false);
}

LogicalResult nodal::TerminalArrayOp::verify() {
  return verifyConservativeArrayDeclaration(getOperation(), true);
}

LogicalResult nodal::BranchOp::verify() {
  auto positive = llvm::cast<nodal::TerminalType>(getOperation()->getOperand(0).getType());
  auto negative = llvm::cast<nodal::TerminalType>(getOperation()->getOperand(1).getType());
  auto branch = llvm::cast<nodal::BranchType>(getOperation()->getResult(0).getType());
  if (positive.getDiscipline() == negative.getDiscipline() &&
      positive.getDiscipline() == branch.getDiscipline())
    return success();

  auto positiveRef = FlatSymbolRefAttr::get(getOperation()->getContext(), positive.getDiscipline());
  auto negativeRef = FlatSymbolRefAttr::get(getOperation()->getContext(), negative.getDiscipline());
  auto branchRef = FlatSymbolRefAttr::get(getOperation()->getContext(), branch.getDiscipline());
  FailureOr<bool> endpoints =
      nodal::areDisciplinesCompatible(getOperation(), positiveRef, negativeRef);
  FailureOr<bool> result = nodal::areDisciplinesCompatible(getOperation(), positiveRef, branchRef);
  if (failed(endpoints) || failed(result) || !*endpoints || !*result)
    return emitOpError(
        "NODAL-BRANCH-DISCIPLINE-001: branch terminals and result must use compatible disciplines");
  return success();
}

LogicalResult nodal::AccessOp::verify() {
  return nodal::verifyPotentialFlowAccessOperation(getOperation());
}

LogicalResult nodal::TerminalAccessOp::verify() {
  return nodal::verifyPotentialFlowAccessOperation(getOperation());
}

LogicalResult nodal::PortFlowAccessOp::verify() {
  return nodal::verifyPotentialFlowAccessOperation(getOperation());
}

LogicalResult nodal::ProbeOp::verify() {
  return nodal::verifyPotentialFlowAccessOperation(getOperation());
}

LogicalResult nodal::AnalogUserFunctionOp::verify() {
  return verifyAnalogUserFunctionOperation(getOperation());
}
LogicalResult nodal::AnalogFunctionValueOp::verify() {
  return verifyAnalogUserFunctionOperation(getOperation());
}
LogicalResult nodal::AnalogFunctionReturnOp::verify() {
  return verifyAnalogUserFunctionOperation(getOperation());
}
LogicalResult nodal::AnalogUserCallOp::verify() {
  return verifyAnalogUserFunctionOperation(getOperation());
}

LogicalResult nodal::AnalogOp::verify() {
  if (failed(requireSingleBlock(getOperation())))
    return emitOpError("NODAL-ANALOG-REGION-001: analog region requires one body block");
  if (failed(verifyGeneratedAnalogOwnership(getOperation())))
    return failure();
  const bool generated = static_cast<bool>(getOperation()->getParentOfType<nodal::GenerateOp>());
  bool generatedProcedure = false;
  bool generatedContinuous = false;
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    generatedProcedure |= llvm::isa<nodal::AnalogProcedureOp>(operation);
    generatedContinuous |=
        llvm::isa<nodal::AnalogDdtOp, nodal::AnalogIdtOp, nodal::AnalogTransitionOp,
                  nodal::AnalogSlewOp, nodal::AnalogAbsdelayOp, nodal::AnalogAbstimeOp,
                  nodal::AnalogBoundStepOp, nodal::AnalogTransferOp, nodal::AnalogNoiseOp,
                  nodal::AnalogContextOp, nodal::ContributeOp>(operation);
    if (generated &&
        !llvm::isa<nodal::RealLiteralOp, nodal::ParameterRefOp, nodal::AnalogAddOp,
                   nodal::AnalogSubOp, nodal::AnalogMulOp, nodal::AnalogDivOp, nodal::AnalogNegOp,
                   nodal::AnalogDdtOp, nodal::AnalogIdtOp, nodal::AnalogTransitionOp,
                   nodal::AnalogSlewOp, nodal::AnalogAbsdelayOp, nodal::AnalogAbstimeOp,
                   nodal::AnalogBoundStepOp, nodal::AnalogTransferOp, nodal::AnalogNoiseOp,
                   nodal::AnalogContextOp, nodal::AccessOp, nodal::TerminalAccessOp,
                   nodal::PortFlowAccessOp, nodal::ContributeOp, nodal::AnalogProcedureOp>(
            operation))
      return operation.emitOpError(
          "NODAL-ITERATION-043-004: generated analog regions support only owned ddt/idt or "
          "transition/slew/absdelay/abstime/boundStep/laplaceNd/ziNd/noise/context "
          "expressions, additive contributions, or generated procedural storage");
    if (llvm::isa<nodal::ConstLiteralOp>(operation) && operation.getNumResults() == 1 &&
        operation.getResult(0).getType().isInteger(1))
      continue;
    if (!llvm::isa<nodal::RealLiteralOp, nodal::AnalogIntegerLiteralOp, nodal::ParameterRefOp,
                   nodal::AnalogFunctionOp, nodal::AnalogUserCallOp, nodal::AnalogAnalysisOp,
                   nodal::AnalogContextOp, nodal::AnalogNoiseOp, nodal::AnalogTransferOp,
                   nodal::AnalogAddOp, nodal::AnalogSubOp, nodal::AnalogMulOp, nodal::AnalogDivOp,
                   nodal::AnalogNegOp, nodal::AnalogCompareOp, nodal::AnalogLogicOp,
                   nodal::AnalogSelectOp, nodal::AnalogDdtOp, nodal::AnalogIdtOp,
                   nodal::AnalogTransitionOp, nodal::AnalogSlewOp, nodal::AnalogAbsdelayOp,
                   nodal::AnalogAbstimeOp, nodal::AnalogBoundStepOp, nodal::AnalogHeldReadOp,
                   nodal::AccessOp, nodal::TerminalAccessOp, nodal::PortFlowAccessOp,
                   nodal::ContributeOp, nodal::AnalogProcedureOp, nodal::AnalogUnknownOp,
                   nodal::AnalogEquationOp>(operation))
      return operation.emitOpError(
          "NODAL-ANALOG-REGION-002: operation is not legal in the analog numeric region");
  }
  if (generated && generatedProcedure == generatedContinuous)
    return emitOpError(
        "NODAL-ITERATION-043-004: generated analog region requires exactly one procedural or "
        "continuous ownership profile");
  return success();
}

LogicalResult nodal::AnalogTransitionOp::verify() {
  return verifyTimeWaveformOperation(getOperation());
}
LogicalResult nodal::AnalogSlewOp::verify() { return verifyTimeWaveformOperation(getOperation()); }
LogicalResult nodal::AnalogAbsdelayOp::verify() {
  return verifyTimeWaveformOperation(getOperation());
}
LogicalResult nodal::AnalogAbstimeOp::verify() {
  return verifyTimeWaveformOperation(getOperation());
}
LogicalResult nodal::AnalogBoundStepOp::verify() {
  return verifyTimeWaveformOperation(getOperation());
}

LogicalResult nodal::AnalogProcedureOp::verify() {
  if (failed(verifyGeneratedAnalogOwnership(getOperation())))
    return failure();
  if (getOperation()->getParentOfType<nodal::GenerateOp>()) {
    Operation *unsupported = nullptr;
    getOperation()->walk([&](Operation *nested) {
      if (nested == getOperation() || unsupported)
        return;
      if (llvm::isa<nodal::AnalogLiveFillOp>(nested)) {
        if (failed(verifyAnalogLiveFillOperation(nested)))
          unsupported = nested;
        return;
      }
      if (llvm::isa<nodal::AnalogArrayFillOp>(nested)) {
        if (failed(verifyAnalogArrayFillValue(nested)))
          unsupported = nested;
        return;
      }
      if (llvm::isa<nodal::ShapeIndexOp, nodal::ShapeViewOp, nodal::ShapeSliceOp>(nested)) {
        const bool storage =
            nested->getNumResults() == 1 && isStorageType(nested->getResult(0).getType());
        if (nested->getNumResults() != 1 ||
            (storage ? failed(resolveAnalogStorageReference(nested->getResult(0), nested))
                     : failed(resolveAnalogArrayValue(nested->getResult(0), nested))))
          unsupported = nested;
        return;
      }
      if (!llvm::isa<nodal::AnalogVariableOp, nodal::AnalogVariableReadOp, nodal::AnalogAssignOp,
                     nodal::AnalogScopeOp, nodal::AnalogIfOp, nodal::AnalogIfArmOp,
                     nodal::AnalogCaseOp, nodal::AnalogCaseArmOp, nodal::AnalogLoopOp,
                     nodal::AnalogBreakOp, nodal::AnalogContinueOp, nodal::AnalogCrossOp,
                     nodal::AnalogAboveOp, nodal::AnalogTimerOp, nodal::AnalogInitialStepOp,
                     nodal::AnalogFinalStepOp, nodal::AnalogEventOrOp, nodal::AnalogOnOp>(nested))
        unsupported = nested;
    });
    if (unsupported)
      return unsupported->emitOpError(
          "NODAL-ITERATION-043-004: generated analog procedures do not support continuous or "
          "unowned effects");
  }
  if (failed(verifyAnalogProcedure(getOperation())))
    return failure();
  return verifyAnalogProceduralExpressions(getOperation());
}

LogicalResult nodal::AnalogScopeOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-033-008",
                                       "procedural lexical scope")))
    return failure();
  if (failed(requireSingleBlock(getOperation())))
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-016",
                             "procedural lexical scope requires one body block");
  if (textAttr(getOperation(), "scope_id").trim().empty())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-016",
                             "procedural lexical scope identity must be non-empty");
  return requireText(getOperation(), "owner", "procedural lexical scope owner");
}

LogicalResult nodal::AnalogIfOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014",
                                       "structured analog conditional")))
    return failure();
  if (failed(requireText(getOperation(), "statement_id", "conditional statement identity")) ||
      failed(requireText(getOperation(), "owner", "conditional owner")) ||
      failed(requireSingleBlock(getOperation())))
    return failure();

  llvm::StringSet<> armIds;
  bool seenConditionalArm = false;
  bool seenElse = false;
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    auto arm = llvm::dyn_cast<nodal::AnalogIfArmOp>(&operation);
    if (!arm)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                               "analog_if body may contain only analog_if_arm operations");
    if (textAttr(&operation, "owner") != textAttr(getOperation(), "owner"))
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-014",
                               "conditional arm owner does not match its parent");
    llvm::StringRef armId = textAttr(&operation, "arm_id");
    if (armId.trim().empty() || !armIds.insert(armId).second)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-001",
                               "conditional arm identity must be non-empty and unique");
    const bool isElse = operation.getAttrOfType<BoolAttr>("is_else").getValue();
    if (isElse) {
      if (seenElse)
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                                 "analog conditional permits only one else arm");
      seenElse = true;
    } else {
      if (seenElse)
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                                 "conditional arm cannot follow the else arm");
      seenConditionalArm = true;
    }
  }
  if (!seenConditionalArm)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "analog conditional requires at least one condition arm");
  return success();
}

LogicalResult nodal::AnalogIfArmOp::verify() {
  if (failed(resolveAnalogSourceStorageBindings(getOperation())))
    return failure();
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014",
                                       "analog conditional arm")) ||
      failed(requireSingleBlock(getOperation())) ||
      failed(requireText(getOperation(), "arm_id", "conditional arm identity")) ||
      failed(requireText(getOperation(), "owner", "conditional arm owner")))
    return failure();
  if (getOperation()->getRegion(0).front().empty())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "conditional arm must contain at least one statement");
  if (failed(verifyStringArray(getOperation(), "condition_reads", "NODAL-ANALOG-034-002",
                               "condition read")))
    return failure();

  auto isElse = getOperation()->getAttrOfType<BoolAttr>("is_else");
  auto staticPresent = getOperation()->getAttrOfType<BoolAttr>("static_value_present");
  auto staticValue = getOperation()->getAttrOfType<BoolAttr>("static_value");
  if (!isElse || !staticPresent || !staticValue)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                             "conditional arm requires explicit staging metadata");

  const llvm::StringRef stage = textAttr(getOperation(), "stage");
  const llvm::StringRef value = textAttr(getOperation(), "condition_value");
  const llvm::StringRef kind = textAttr(getOperation(), "condition_kind");
  const llvm::StringRef dimension = textAttr(getOperation(), "condition_dimension");
  auto reads = getOperation()->getAttrOfType<ArrayAttr>("condition_reads");
  if (isElse.getValue()) {
    if (stage != "else" || !value.empty() || !kind.empty() || !dimension.empty() ||
        !reads.empty() || staticPresent.getValue() || staticValue.getValue())
      return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                               "else arm must not carry a condition or static value");
    return success();
  }

  if (!oneOf(stage, {"static", "runtime"}) || value.trim().empty() || kind != "boolean" ||
      dimension != "1")
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-002",
                             "conditional arm requires a dimensionless Boolean condition");
  if (stage == "static" && (!staticPresent.getValue() || !reads.empty()))
    return emitMappedFailure(
        getOperation(), "NODAL-ANALOG-034-003",
        "static conditional arm requires a compile-time value without dynamic reads");
  if (stage == "runtime" && (staticPresent.getValue() || staticValue.getValue()))
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                             "runtime conditional arm cannot carry a compile-time selected value");
  return success();
}

LogicalResult nodal::AnalogCaseOp::verify() {
  if (failed(resolveAnalogSourceStorageBindings(getOperation())))
    return failure();
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014",
                                       "structured analog case")) ||
      failed(requireText(getOperation(), "statement_id", "case statement identity")) ||
      failed(requireText(getOperation(), "owner", "case owner")) ||
      failed(requireSingleBlock(getOperation())) ||
      failed(verifyStringArray(getOperation(), "selector_reads", "NODAL-ANALOG-034-005",
                               "case selector read")))
    return failure();

  const llvm::StringRef kind = textAttr(getOperation(), "selector_kind");
  const llvm::StringRef dimension = textAttr(getOperation(), "selector_dimension");
  if (!oneOf(kind, {"integer", "boolean"}) || dimension != "1" ||
      textAttr(getOperation(), "selector_value").trim().empty())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-005",
                             "case selector must be a dimensionless integer or Boolean value");

  auto staticPresent = getOperation()->getAttrOfType<BoolAttr>("static_value_present");
  auto reads = getOperation()->getAttrOfType<ArrayAttr>("selector_reads");
  const llvm::StringRef staticValue = textAttr(getOperation(), "static_value");
  if (!staticPresent)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                             "case selector requires explicit staging metadata");
  if (staticPresent.getValue()) {
    if (staticValue.empty() || !reads.empty())
      return emitMappedFailure(
          getOperation(), "NODAL-ANALOG-034-003",
          "static case selector requires one exact value without dynamic reads");
    if (!isCanonicalStructuredCaseLabel(staticValue, kind))
      return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-007",
                               "static case selector value does not match selector kind");
  } else if (!staticValue.empty()) {
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                             "runtime case selector cannot carry a static value");
  }

  llvm::StringSet<> armIds;
  llvm::StringSet<> labels;
  bool seenOrdinary = false;
  bool seenDefault = false;
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    auto arm = llvm::dyn_cast<nodal::AnalogCaseArmOp>(&operation);
    if (!arm)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                               "analog_case body may contain only analog_case_arm operations");
    if (textAttr(&operation, "owner") != textAttr(getOperation(), "owner"))
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-014",
                               "case arm owner does not match its parent");
    llvm::StringRef armId = textAttr(&operation, "arm_id");
    if (armId.trim().empty() || !armIds.insert(armId).second)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-001",
                               "case arm identity must be non-empty and unique");
    auto isDefault = operation.getAttrOfType<BoolAttr>("is_default");
    auto armLabels = operation.getAttrOfType<ArrayAttr>("labels");
    if (isDefault.getValue()) {
      if (seenDefault || !armLabels.empty())
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                                 "case permits one label-free default arm");
      seenDefault = true;
      continue;
    }
    if (seenDefault)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                               "case arm cannot follow the default arm");
    seenOrdinary = true;
    for (Attribute attribute : armLabels) {
      auto label = llvm::dyn_cast<StringAttr>(attribute);
      if (!label || !isCanonicalStructuredCaseLabel(label.getValue(), kind))
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-007",
                                 "case label kind does not match the selector");
      if (!labels.insert(label.getValue()).second)
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-006", "duplicate case label");
    }
  }
  if (!seenOrdinary)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "analog case requires at least one labeled arm");
  return success();
}

LogicalResult nodal::AnalogCaseArmOp::verify() {
  if (failed(
          requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014", "analog case arm")) ||
      failed(requireSingleBlock(getOperation())) ||
      failed(requireText(getOperation(), "arm_id", "case arm identity")) ||
      failed(requireText(getOperation(), "owner", "case arm owner")) ||
      failed(verifyStringArray(getOperation(), "labels", "NODAL-ANALOG-034-007", "case label")))
    return failure();
  if (getOperation()->getRegion(0).front().empty())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "case arm must contain at least one statement");
  auto isDefault = getOperation()->getAttrOfType<BoolAttr>("is_default");
  auto labels = getOperation()->getAttrOfType<ArrayAttr>("labels");
  if (!isDefault)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "case arm requires explicit default metadata");
  if (isDefault.getValue() != labels.empty())
    return emitMappedFailure(
        getOperation(), "NODAL-ANALOG-034-015",
        "default case arm must be label-free and ordinary arms require labels");
  return success();
}

LogicalResult nodal::AnalogLoopOp::verify() {
  if (failed(resolveAnalogSourceStorageBindings(getOperation())))
    return failure();
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014",
                                       "structured analog loop")) ||
      failed(requireSingleBlock(getOperation())) ||
      failed(requireText(getOperation(), "statement_id", "loop statement identity")) ||
      failed(requireText(getOperation(), "owner", "loop owner")) ||
      failed(requireText(getOperation(), "bound_value", "loop bound value")) ||
      failed(verifyStringArray(getOperation(), "bound_reads", "NODAL-ANALOG-034-008",
                               "loop bound read")))
    return failure();
  if (getOperation()->getRegion(0).front().empty())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "analog loop body must contain at least one statement");

  const llvm::StringRef stage = textAttr(getOperation(), "stage");
  const llvm::StringRef kind = textAttr(getOperation(), "bound_kind");
  const llvm::StringRef dimension = textAttr(getOperation(), "bound_dimension");
  auto minimum = getOperation()->getAttrOfType<IntegerAttr>("minimum_iterations");
  auto maximum = getOperation()->getAttrOfType<IntegerAttr>("maximum_iterations");
  auto staticPresent = getOperation()->getAttrOfType<BoolAttr>("static_trip_count_present");
  auto staticCount = getOperation()->getAttrOfType<IntegerAttr>("static_trip_count");
  auto reads = getOperation()->getAttrOfType<ArrayAttr>("bound_reads");
  if (!oneOf(stage, {"static", "runtime"}) || kind != "integer" || dimension != "1" || !minimum ||
      !maximum || !staticPresent || !staticCount)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-008",
                             "loop requires a dimensionless integer finite envelope");
  const int64_t minimumValue = minimum.getInt();
  const int64_t maximumValue = maximum.getInt();
  if (minimumValue < 0 || maximumValue < minimumValue)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-008",
                             "loop envelope must be finite, ordered, and non-negative");

  if (stage == "static") {
    if (!staticPresent.getValue() || staticCount.getInt() != minimumValue ||
        minimumValue != maximumValue || !reads.empty())
      return emitMappedFailure(
          getOperation(), "NODAL-ANALOG-034-009",
          "static loop requires one exact non-negative compile-time trip count");
  } else {
    if (maximumValue == 0 || staticPresent.getValue() || staticCount.getInt() != 0)
      return emitMappedFailure(
          getOperation(), "NODAL-ANALOG-034-008",
          "runtime loop requires a positive finite maximum and no static trip count");
  }
  return success();
}

LogicalResult nodal::AnalogBreakOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-010", "analog break")) ||
      failed(requireText(getOperation(), "statement_id", "break statement identity")) ||
      failed(requireText(getOperation(), "owner", "break owner")))
    return failure();
  auto loop = getOperation()->getParentOfType<nodal::AnalogLoopOp>();
  if (!loop || textAttr(loop.getOperation(), "stage") != "runtime")
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-010",
                             "break is legal only in the nearest runtime-bounded analog loop");
  return success();
}

LogicalResult nodal::AnalogContinueOp::verify() {
  if (failed(
          requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-011", "analog continue")) ||
      failed(requireText(getOperation(), "statement_id", "continue statement identity")) ||
      failed(requireText(getOperation(), "owner", "continue owner")))
    return failure();
  auto loop = getOperation()->getParentOfType<nodal::AnalogLoopOp>();
  if (!loop || textAttr(loop.getOperation(), "stage") != "runtime")
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-011",
                             "continue is legal only in the nearest runtime-bounded analog loop");
  return success();
}

LogicalResult nodal::AnalogVariableOp::verify() {
  if (failed(verifyGeneratedAnalogOwnership(getOperation())))
    return failure();
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-033-003",
                                       "procedural variable declaration")))
    return failure();
  if (failed(requireText(getOperation(), "identity", "procedural variable identity")) ||
      failed(requireText(getOperation(), "owner", "procedural variable owner")))
    return failure();
  Operation *operation = getOperation();
  if (operation->hasAttr("initializer_form")) {
    auto form = operation->getAttrOfType<StringAttr>("initializer_form");
    if (!form || form.getValue() != "expression")
      return emitMappedFailure(operation, "NODAL-ANALOG-033-004",
                               "unknown procedural initializer form");
    auto variable = llvm::dyn_cast<nodal::VariableType>(operation->getResult(0).getType());
    auto initialized = operation->getAttrOfType<BoolAttr>("initialized");
    auto reads = operation->getAttrOfType<ArrayAttr>("initializer_reads");
    if (!variable || !initialized || !initialized.getValue() || !reads || !reads.empty() ||
        llvm::isa<nodal::AnalogProcedureOp>(operation->getParentOp()))
      return emitMappedFailure(operation, "NODAL-ANALOG-033-004",
                               "expression initializer requires initialized local scalar storage "
                               "and empty textual read inventory");
    if (failed(verifyVariableInitializerShape(operation, variable)) ||
        failed(verifyCapturedScalarBindings(operation, operation->getOperands(),
                                            textAttr(operation, "initializer_value"))))
      return failure();
    auto source = parseAnalogSourceExpression(operation, textAttr(operation, "initializer_value"));
    if (failed(source) || source->kind != textAttr(operation, "initializer_kind") ||
        source->dimension != textAttr(operation, "initializer_dimension"))
      return emitMappedFailure(operation, "NODAL-ANALOG-033-004",
                               "expression initializer source differs from its scalar type");
    return success();
  }
  if (auto shaped = llvm::dyn_cast<nodal::ShapedType>(operation->getResult(0).getType())) {
    auto leaf = llvm::dyn_cast<nodal::VariableType>(shaped.getElementType());
    auto module = operation->getParentOfType<nodal::ModuleOp>();
    auto initialized = operation->getAttrOfType<BoolAttr>("initialized");
    auto reads = operation->getAttrOfType<ArrayAttr>("initializer_reads");
    if (!leaf || leaf.getKind() != "real" || !module || !initialized || !reads || !reads.empty() ||
        operation->getNumOperands() > 1 ||
        initialized.getValue() != (operation->getNumOperands() == 1) ||
        !textAttr(operation, "initializer_value").empty() ||
        !textAttr(operation, "initializer_kind").empty() ||
        !textAttr(operation, "initializer_dimension").empty() ||
        failed(inferStaticShapeMaximumCardinality(module, shaped.getDimensions())))
      return emitMappedFailure(operation, "NODAL-ANALOG-033-004",
                               "shaped Real declaration requires owned positive shape and "
                               "exclusive snapshot initializer");
    if (!initialized.getValue())
      return success();
    auto source = llvm::dyn_cast<nodal::ShapedType>(operation->getOperand(0).getType());
    auto quantity = source ? llvm::dyn_cast<nodal::QuantityType>(source.getElementType())
                           : nodal::QuantityType();
    if (!quantity || quantity.getKind() != "real" ||
        quantity.getDimension() != leaf.getDimension() ||
        !equalShapeDimensions(module, shaped.getDimensions(), source.getDimensions()) ||
        failed(verifySnapshotCapture(operation->getOperand(0), operation)))
      return emitMappedFailure(operation, "NODAL-ANALOG-033-004",
                               "shaped initializer snapshot type or ordering is invalid");
    if (llvm::isa<nodal::AnalogProcedureOp>(operation->getParentOp())) {
      auto value = resolveAnalogArrayValue(operation->getOperand(0), operation);
      auto fill = succeeded(value) ? llvm::dyn_cast<nodal::AnalogArrayFillOp>(value->producer)
                                   : nodal::AnalogArrayFillOp();
      auto expression = fill ? parseAnalogSourceExpression(fill, textAttr(fill, "value"))
                             : FailureOr<AnalogSourceExpression>(failure());
      if (!fill || fill->getNumOperands() || failed(expression) ||
          !isStaticAnalogSourceExpression(*expression))
        return emitMappedFailure(
            operation, "NODAL-ANALOG-037-009",
            "persistent shaped initialization requires static fill without mutable reads");
    }
    return success();
  }
  auto variableType = llvm::dyn_cast<nodal::VariableType>(operation->getResult(0).getType());
  if (!variableType)
    return emitMappedFailure(operation, "NODAL-ANALOG-033-019",
                             "scalar procedural variable requires !nodal.variable");
  if (operation->getNumOperands()) {
    auto reads = operation->getAttrOfType<ArrayAttr>("initializer_reads");
    auto initialized = operation->getAttrOfType<BoolAttr>("initialized");
    auto quantity = operation->getNumOperands() == 1
                        ? llvm::dyn_cast<nodal::QuantityType>(operation->getOperand(0).getType())
                        : nodal::QuantityType();
    if (variableType.getKind() != "real" || !quantity || quantity.getKind() != "real" ||
        quantity.getDimension() != variableType.getDimension() || !initialized ||
        !initialized.getValue() || !reads || !reads.empty() ||
        !textAttr(operation, "initializer_value").empty() ||
        !textAttr(operation, "initializer_kind").empty() ||
        !textAttr(operation, "initializer_dimension").empty())
      return emitMappedFailure(operation, "NODAL-ANALOG-033-004",
                               "scalar snapshot initializer requires exclusive typed Real capture");
    if (llvm::isa<nodal::AnalogProcedureOp>(operation->getParentOp()))
      return emitMappedFailure(operation, "NODAL-ANALOG-037-009",
                               "persistent scalar initialization cannot capture mutable storage");
    auto path = getAnalogCapturedReadPath(operation->getOperand(0));
    if (failed(path) ||
        failed(verifyCapturedScalarBindings(operation, operation->getOperands(), *path)))
      return failure();
    return success();
  }
  return verifyVariableInitializerShape(operation, variableType);
}

LogicalResult nodal::AnalogVariableReadOp::verify() {
  if (failed(verifyGeneratedAnalogOwnership(getOperation())))
    return failure();
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-033-008",
                                       "procedural variable read")))
    return failure();
  if (failed(requireText(getOperation(), "read_id", "procedural read identity")) ||
      failed(requireText(getOperation(), "owner", "procedural read owner")))
    return failure();
  auto reference = resolveAnalogStorageReference(getOperation()->getOperand(0), getOperation());
  if (failed(reference))
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-017",
                             "read requires actual owned procedural storage");
  if (llvm::isa<nodal::ShapedType>(getOperation()->getOperand(0).getType())) {
    auto expected = getAnalogStorageSnapshotType(getOperation()->getOperand(0), getOperation());
    if (failed(expected) || *expected != getOperation()->getResult(0).getType())
      return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-012",
                               "shaped read requires exact immutable quantity snapshot type");
    return success();
  }
  auto variableType = llvm::dyn_cast<nodal::VariableType>(getOperation()->getOperand(0).getType());
  auto result = getProceduralValueInfo(getOperation()->getResult(0).getType());
  if (!variableType || failed(result) || result->kind != variableType.getKind() ||
      result->dimension != variableType.getDimension())
    return emitMappedFailure(
        getOperation(), "NODAL-ANALOG-033-012",
        "procedural read result kind or dimension does not match its variable");
  return success();
}

LogicalResult nodal::AnalogAssignOp::verify() {
  if (failed(verifyGeneratedAnalogOwnership(getOperation())))
    return failure();
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-033-008",
                                       "procedural assignment")))
    return failure();
  if (failed(requireText(getOperation(), "statement_id", "procedural statement identity")) ||
      failed(requireText(getOperation(), "owner", "procedural assignment owner")))
    return failure();
  if (llvm::isa<nodal::ShapedType>(getOperation()->getOperand(0).getType())) {
    if (failed(verifySnapshotAssignment(getOperation())) ||
        failed(verifyAnalysisApplicability(getOperation())))
      return failure();
    return verifyGuardShape(getOperation());
  }
  if (!textAttr(getOperation(), "value_form").empty() &&
      textAttr(getOperation(), "value_form") != "source")
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-012",
                             "unknown scalar assignment payload form");
  if (failed(resolveAnalogStorageReference(getOperation()->getOperand(0), getOperation())))
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-017",
                             "assignment requires actual owned procedural storage");
  auto variableType = llvm::dyn_cast<nodal::VariableType>(getOperation()->getOperand(0).getType());
  if (!variableType)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-019",
                             "procedural assignment target requires !nodal.variable");
  llvm::StringRef valueKind = textAttr(getOperation(), "value_kind");
  llvm::StringRef valueDimension = textAttr(getOperation(), "value_dimension");
  if (!oneOf(valueKind, {"integer", "real", "boolean"}) ||
      !proceduralKindsCompatible(valueKind, variableType.getKind()))
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-012",
                             "assigned value kind is incompatible with the target variable");
  if (!nodal::isCanonicalDimensionSignature(valueDimension) ||
      valueDimension != variableType.getDimension())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-013",
                             "assigned value dimension does not match the target variable");
  bool selectedCapture = !getOperation()->getOperand(0).getDefiningOp<nodal::AnalogVariableOp>();
  for (Value value : getOperation()->getOperands().drop_front()) {
    auto read = value.getDefiningOp<nodal::AnalogVariableReadOp>();
    selectedCapture |= !read || !read->getOperand(0).getDefiningOp<nodal::AnalogVariableOp>();
  }
  if (selectedCapture) {
    auto metadata = getOperation()->getAttrOfType<DictionaryAttr>("metadata");
    auto source = metadata ? metadata.getAs<StringAttr>("value") : StringAttr();
    if (!source ||
        failed(verifyCapturedScalarBindings(
            getOperation(), getOperation()->getOperands().drop_front(), source.getValue())))
      return failure();
    auto expression = parseAnalogSourceExpression(getOperation(), source.getValue());
    if (failed(expression) || expression->dimension != valueDimension ||
        !proceduralKindsCompatible(expression->kind, variableType.getKind()))
      return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-012",
                               "selected assignment expression type disagrees with payload");
  }
  if (failed(verifyAnalysisApplicability(getOperation())))
    return failure();
  return verifyGuardShape(getOperation());
}

namespace {
LogicalResult verifyAnalogArrayFillValue(Operation *operation) {
  auto procedure = operation->getParentOfType<nodal::AnalogProcedureOp>();
  auto module = operation->getParentOfType<nodal::ModuleOp>();
  auto shaped = llvm::dyn_cast<nodal::ShapedType>(operation->getResult(0).getType());
  auto element =
      shaped ? llvm::dyn_cast<nodal::QuantityType>(shaped.getElementType()) : nodal::QuantityType();
  if (!procedure || !module || !element || element.getKind() != "real" ||
      textAttr(operation, "owner") != textAttr(procedure, "owner") ||
      failed(nodal::verifyGeneratedAnalogOwnership(operation)) ||
      failed(nodal::inferStaticShapeMaximumCardinality(module, shaped.getDimensions())))
    return operation->emitOpError(
        "NODAL-ANALOG-033-012: fill requires owned shaped Real value type");
  auto expression = nodal::parseAnalogSourceExpression(operation, textAttr(operation, "value"));
  if (failed(expression) || expression->kind != "real" ||
      expression->dimension != element.getDimension())
    return operation->emitOpError(
        "NODAL-ANALOG-033-012: fill expression does not match Real element dimension");
  if (failed(verifyCapturedScalarBindings(operation, operation->getOperands(),
                                          textAttr(operation, "value"))))
    return failure();
  return success();
}
} // namespace

LogicalResult nodal::AnalogLiveFillOp::verify() {
  return verifyAnalogLiveFillOperation(getOperation());
}

LogicalResult nodal::AnalogArrayFillOp::verify() {
  return verifyAnalogArrayFillValue(getOperation());
}

LogicalResult nodal::RealLiteralOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}

LogicalResult nodal::AnalogIntegerLiteralOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}

LogicalResult nodal::ParameterRefOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}

LogicalResult nodal::AnalogAddOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogSubOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogMulOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogDivOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogNegOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogCompareOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogLogicOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogSelectOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogDdtOp::verify() {
  if (failed(verifyGeneratedAnalogOwnership(getOperation())))
    return failure();
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogIdtOp::verify() {
  if (failed(verifyGeneratedAnalogOwnership(getOperation())))
    return failure();
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::ContributeOp::verify() {
  if (failed(verifyGeneratedAnalogOwnership(getOperation())))
    return failure();
  return nodal::verifyAnalogNumericOperation(getOperation());
}

LogicalResult nodal::BridgeOp::verify() {
  if (failed(requireText(getOperation(), "kind", "bridge kind")) ||
      failed(requireText(getOperation(), "source_domain", "source domain")) ||
      failed(requireText(getOperation(), "destination_domain", "destination domain")))
    return failure();
  return success();
}

LogicalResult nodal::ClockGateOp::verify() { return verifyClockProducer(getOperation()); }
LogicalResult nodal::ClockMuxOp::verify() { return verifyClockProducer(getOperation()); }
LogicalResult nodal::CrossingOp::verify() { return verifyCrossingOperation(getOperation()); }
LogicalResult nodal::WaiverOp::verify() { return verifyWaiverOperation(getOperation()); }
LogicalResult nodal::AsyncFifoOp::verify() { return verifyCrossingOperation(getOperation()); }

LogicalResult nodal::StateOwnerOp::verify() {
  if (!getOperation()->getAttrOfType<SymbolRefAttr>("state") ||
      !getOperation()->getAttrOfType<FlatSymbolRefAttr>("domain"))
    return emitOpError("requires state and domain references");
  return success();
}

LogicalResult nodal::TimingProvenanceOp::verify() {
  if (!getOperation()->getAttrOfType<SymbolRefAttr>("owner"))
    return emitOpError("requires an owner reference");
  return requireText(getOperation(), "relationship", "timing relationship");
}

LogicalResult nodal::EnumOp::verify() {
  auto underlying = getOperation()->getAttrOfType<TypeAttr>("underlying_type");
  auto uintType =
      underlying ? llvm::dyn_cast<nodal::UIntType>(underlying.getValue()) : nodal::UIntType();
  if (!uintType || uintType.getWidth() < 1)
    return emitOpError("underlying_type must be !nodal.uint with width at least one bit");
  if (failed(requireText(getOperation(), mlir::SymbolTable::getSymbolAttrName(), "enum symbol")))
    return failure();
  const llvm::StringRef encoding = textAttr(getOperation(), "encoding");
  if (!oneOf(encoding, {"sequential", "one_hot", "gray", "custom"}))
    return emitOpError("unsupported enum encoding");
  if (failed(requireSingleBlock(getOperation())))
    return failure();
  llvm::StringSet<> names;
  llvm::DenseSet<llvm::APInt> values;
  uint64_t cases = 0;
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    auto enumCase = llvm::dyn_cast<nodal::EnumCaseOp>(&operation);
    if (!enumCase)
      return operation.emitOpError("enum body accepts only enum_case operations");
    // Check cases here as well as independently: direct native callers must not
    // rely on an earlier recursive verifier or on frontend validation.
    if (failed(enumCase.verify()))
      return failure();
    if (!names.insert(textAttr(&operation, mlir::SymbolTable::getSymbolAttrName())).second)
      return enumCase.emitOpError("duplicates an existing enum case name");
    const llvm::APInt &code = operation.getAttrOfType<IntegerAttr>("value").getValue();
    const unsigned activeBits = code.getActiveBits();
    if (!values.insert(code.zextOrTrunc(activeBits ? activeBits : 1)).second)
      return enumCase.emitOpError("duplicates an existing enum value");

    // Work with the supplied magnitude and the bounded declaration index, not
    // an allocation or shift sized by the enum's potentially very large width.
    if (encoding == "one_hot") {
      if (!code.isPowerOf2() || code.logBase2() != cases)
        return enumCase.emitOpError("one_hot code must equal 1 shifted by its declaration index");
    } else if (encoding != "custom") {
      llvm::APInt expected(64, cases);
      if (encoding == "gray")
        expected ^= expected.lshr(1);
      if (!llvm::APInt::isSameValue(code, expected))
        return enumCase.emitOpError() << encoding << " code does not match its declaration index";
    }
    if (cases == std::numeric_limits<uint64_t>::max())
      return emitOpError("enum case count exceeds the supported declaration index");
    ++cases;
  }
  if (cases == 0)
    return emitOpError("requires at least one enum case");
  return verifyEnumDefinitionAbi(getOperation());
}

LogicalResult nodal::DigitalStorageOp::verify() {
  return verifyDigitalStorageOperation(getOperation());
}

LogicalResult nodal::MemoryOp::verify() { return verifyDigitalMemoryOperation(getOperation()); }
LogicalResult nodal::MemoryReadOp::verify() { return verifyMemoryPort(getOperation()); }
LogicalResult nodal::MemoryWriteOp::verify() { return verifyMemoryPort(getOperation()); }
LogicalResult nodal::MemoryWriteEffectOp::verify() { return verifyMemoryPort(getOperation()); }

LogicalResult nodal::DigitalAssignOp::verify() {
  return verifyDigitalAssignmentOperation(getOperation());
}

LogicalResult nodal::CollectionOp::verify() {
  return verifyDigitalCollectionOperation(getOperation());
}

LogicalResult nodal::CollectionYieldOp::verify() {
  return verifyDigitalCollectionYield(getOperation());
}

LogicalResult nodal::DigitalExprOp::verify() { return verifyDigitalValueOperation(getOperation()); }

LogicalResult nodal::EnumExprOp::verify() { return verifyEnumValueOperation(getOperation()); }

LogicalResult nodal::EnumCaseOp::verify() {
  if (failed(
          requireText(getOperation(), mlir::SymbolTable::getSymbolAttrName(), "enum case symbol")))
    return failure();
  auto value = getOperation()->getAttrOfType<IntegerAttr>("value");
  if (!value)
    return emitOpError("requires an integer value");
  auto parent = llvm::dyn_cast_or_null<nodal::EnumOp>(getOperation()->getParentOp());
  if (!parent)
    return emitOpError("requires a direct enum parent");
  auto underlying = parent->getAttrOfType<TypeAttr>("underlying_type");
  auto uintType =
      underlying ? llvm::dyn_cast<nodal::UIntType>(underlying.getValue()) : nodal::UIntType();
  if (!uintType || uintType.getWidth() < 1)
    return emitOpError("enum parent requires !nodal.uint with width at least one bit");
  auto carrier = llvm::dyn_cast<IntegerType>(value.getType());
  const llvm::APInt &code = value.getValue();
  // Signless and signed attributes represent nonnegative numeric codes;
  // explicitly unsigned carriers may also use their highest bit.
  if (((!carrier || !carrier.isUnsigned()) && code.isNegative()) ||
      static_cast<int64_t>(code.getActiveBits()) > uintType.getWidth())
    return emitOpError("case value does not fit unsigned enum width");
  return success();
}

LogicalResult nodal::FsmOp::verify() {
  auto stateType = getOperation()->getAttrOfType<TypeAttr>("state_type");
  if (!stateType || !llvm::isa<nodal::EnumType>(stateType.getValue()))
    return emitOpError("state_type must be !nodal.enum");
  if (!getOperation()->getAttrOfType<FlatSymbolRefAttr>("domain"))
    return emitOpError("requires one owning domain");
  if (!oneOf(textAttr(getOperation(), "encoding"),
             {"compact", "one_hot", "gray", "custom", "auto"}))
    return emitOpError("unsupported FSM encoding");
  if (!oneOf(textAttr(getOperation(), "illegal_policy"), {"error", "recover", "hold", "trap"}))
    return emitOpError("unsupported illegal-state policy");
  if (failed(requireSingleBlock(getOperation())))
    return failure();

  llvm::StringSet<> states;
  unsigned initialStates = 0;
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    auto state = llvm::dyn_cast<nodal::FsmStateOp>(&operation);
    if (!state)
      continue;
    if (auto name = operation.getAttrOfType<StringAttr>(mlir::SymbolTable::getSymbolAttrName()))
      states.insert(name.getValue());
    if (auto initial = operation.getAttrOfType<BoolAttr>("initial")) {
      if (initial.getValue())
        ++initialStates;
    }
  }
  if (states.empty())
    return emitOpError("requires at least one FSM state");
  if (initialStates != 1)
    return emitOpError("requires exactly one initial FSM state");

  for (Operation &operation : getOperation()->getRegion(0).front()) {
    auto state = llvm::dyn_cast<nodal::FsmStateOp>(&operation);
    if (!state)
      continue;
    for (Operation &nested : operation.getRegion(0).front()) {
      auto transition = llvm::dyn_cast<nodal::FsmTransitionOp>(&nested);
      if (!transition)
        continue;
      auto destination = nested.getAttrOfType<FlatSymbolRefAttr>("destination");
      if (!destination || states.find(destination.getValue()) == states.end())
        return transition.emitOpError("references an unknown destination state");
    }
  }
  return success();
}

LogicalResult nodal::FsmStateOp::verify() { return requireSingleBlock(getOperation()); }

LogicalResult nodal::FsmTransitionOp::verify() {
  auto priority = getOperation()->getAttrOfType<IntegerAttr>("priority");
  if (!priority || priority.getValue().isNegative())
    return emitOpError("priority must be non-negative");
  return requireText(getOperation(), "condition", "condition");
}

LogicalResult nodal::FsmActionOp::verify() {
  if (!oneOf(textAttr(getOperation(), "phase"), {"entry", "active", "exit", "transition"}))
    return emitOpError("unsupported FSM action phase");
  return requireText(getOperation(), "effect", "action effect");
}

LogicalResult nodal::FsmCompletionOp::verify() {
  auto source = getOperation()->getAttrOfType<FlatSymbolRefAttr>("source");
  auto destination = getOperation()->getAttrOfType<FlatSymbolRefAttr>("destination");
  if (!source || !destination || source == destination)
    return emitOpError("requires distinct completion endpoints");
  return requireText(getOperation(), "kind", "completion kind");
}

LogicalResult nodal::AnalogCrossOp::verify() { return verifyAnalogEventOperation(*this); }
LogicalResult nodal::AnalogAboveOp::verify() { return verifyAnalogEventOperation(*this); }
LogicalResult nodal::AnalogTimerOp::verify() { return verifyAnalogEventOperation(*this); }
LogicalResult nodal::AnalogInitialStepOp::verify() { return verifyAnalogEventOperation(*this); }
LogicalResult nodal::AnalogFinalStepOp::verify() { return verifyAnalogEventOperation(*this); }
LogicalResult nodal::AnalogEventOrOp::verify() { return verifyAnalogEventOperation(*this); }
LogicalResult nodal::AnalogOnOp::verify() { return verifyAnalogEventOperation(*this); }

LogicalResult nodal::AnalogHeldReadOp::verify() { return verifyAnalogHeldRead(getOperation()); }

LogicalResult nodal::AnalogTransferOp::verify() {
  if (failed(verifyGeneratedAnalogOwnership(getOperation())))
    return failure();
  return nodal::verifyAnalogTransferOperation(getOperation());
}

LogicalResult nodal::AnalogNoiseOp::verify() {
  if (failed(verifyGeneratedAnalogOwnership(getOperation())))
    return failure();
  return nodal::verifyAnalogNoiseOperation(getOperation());
}

LogicalResult nodal::AnalogFunctionOp::verify() { return verifyAnalogFunctionOperation(*this); }
LogicalResult nodal::AnalogAnalysisOp::verify() { return verifyAnalogFunctionOperation(*this); }
LogicalResult nodal::AnalogContextOp::verify() { return verifyAnalogFunctionOperation(*this); }

LogicalResult nodal::AnalogUnknownOp::verify() {
  return verifyAnalogUnknownOperation(getOperation());
}
LogicalResult nodal::AnalogEquationOp::verify() {
  return verifyAnalogEquationOperation(getOperation());
}

// F54 finite typed Interface and resolved-net contract.
LogicalResult nodal::InterfaceBoundaryOp::verify() {
  return verifyDigitalInterfaceOperation(getOperation());
}
LogicalResult nodal::InterfaceConnectOp::verify() {
  return verifyDigitalInterfaceOperation(getOperation());
}
LogicalResult nodal::InterfaceDriveOp::verify() {
  return verifyDigitalInterfaceOperation(getOperation());
}
LogicalResult nodal::InterfaceObserveOp::verify() {
  return verifyDigitalInterfaceOperation(getOperation());
}
LogicalResult nodal::NetReleaseOp::verify() {
  return verifyDigitalInterfaceOperation(getOperation());
}
LogicalResult nodal::NetDriveLevelOp::verify() {
  return verifyDigitalInterfaceOperation(getOperation());
}
LogicalResult nodal::NetPassThroughOp::verify() {
  return verifyDigitalInterfaceOperation(getOperation());
}
LogicalResult nodal::NetSplitOp::verify() {
  return verifyDigitalInterfaceOperation(getOperation());
}

// Preserve the ordinary entry-point ABI for existing non-contextual consumers.
namespace nodal {
FailureOr<StaticShapeDimension>
resolveStaticShapeDimension(Operation *module, llvm::StringRef dimension, llvm::StringRef effect) {
  return resolveStaticShapeDimension(module, dimension, effect, nullptr);
}
FailureOr<int64_t> inferStaticShapeMaximumCardinality(Operation *module,
                                                      llvm::StringRef dimensions) {
  return inferStaticShapeMaximumCardinality(module, dimensions, nullptr);
}
FailureOr<ParameterIntegerBounds> inferStaticShapeIndexBounds(Operation *module, Value value) {
  return inferStaticShapeIndexBounds(module, value, nullptr);
}
FailureOr<StaticShapeIndexIdentity> resolveStaticShapeIndexIdentity(Operation *module,
                                                                    Value value) {
  return resolveStaticShapeIndexIdentity(module, value, nullptr);
}
FailureOr<ShapeIndexProof> resolveShapeIndexProof(Value value, Operation *use) {
  return resolveShapeIndexProof(value, use, nullptr);
}
LogicalResult verifyShapeIndexInExtent(Value value, const StaticShapeDimension &extent,
                                       Operation *use) {
  return verifyShapeIndexInExtent(value, extent, use, nullptr);
}
LogicalResult verifyShapeIndexMapping(Operation *operation) {
  return verifyShapeIndexMapping(operation, nullptr);
}
LogicalResult verifyShapeViewMapping(Operation *operation) {
  return verifyShapeViewMapping(operation, nullptr);
}
LogicalResult verifyShapeSliceMapping(Operation *operation) {
  return verifyShapeSliceMapping(operation, nullptr);
}
} // namespace nodal

LogicalResult nodal::StaticCompareOp::verify() {
  return verifyStaticPredicateOperation(getOperation());
}
LogicalResult nodal::StaticLogicOp::verify() {
  return verifyStaticPredicateOperation(getOperation());
}
LogicalResult nodal::StructuralIfOp::verify() {
  return verifyStructuralIfOperation(getOperation());
}
LogicalResult nodal::StructuralYieldOp::verify() {
  return verifyStructuralYieldOperation(getOperation());
}

LogicalResult nodal::LocalDomainBindingOp::verify() {
  return verifyLocalDomainBinding(getOperation());
}
LogicalResult nodal::BehavioralPredicateOp::verify() {
  return verifyBehavioralPredicate(getOperation());
}
LogicalResult nodal::BehavioralIfOp::verify() {
  return verifyBehavioralIfOperation(getOperation());
}
LogicalResult nodal::SequentialSelectOp::verify() { return verifySequentialSelect(getOperation()); }
LogicalResult nodal::SequentialUpdateOp::verify() { return verifySequentialUpdate(getOperation()); }

LogicalResult nodal::FsmElapsedOp::verify() { return verifyFsmTimingOperation(getOperation()); }
LogicalResult nodal::FsmTransferOp::verify() { return verifyFsmTimingOperation(getOperation()); }
LogicalResult nodal::FsmJoinOp::verify() { return verifyFsmCompositionOperation(getOperation()); }
LogicalResult nodal::FsmActivationOp::verify() {
  return verifyFsmCompositionOperation(getOperation());
}

LogicalResult nodal::FlatFSMOp::verify() { return verifyFlatFSMOperation(getOperation()); }
LogicalResult nodal::EnumSwitchOp::verify() { return verifyEnumSwitchOperation(getOperation()); }
LogicalResult nodal::ResetAssertedOp::verify() { return verifyResetAsserted(getOperation()); }
LogicalResult nodal::SequentialAssertOp::verify() {
  return verifySequentialAssertion(getOperation());
}

LogicalResult nodal::ResetBoundaryOp::verify() { return verifyResetProducer(getOperation()); }
LogicalResult nodal::ResetSyncOp::verify() { return verifyResetProducer(getOperation()); }
LogicalResult nodal::ResetCombineOp::verify() { return verifyResetProducer(getOperation()); }

LogicalResult nodal::RequirementClockOp::verify() {
  return verifyRequirementProjection(getOperation());
}
LogicalResult nodal::RequirementResetOp::verify() {
  return verifyRequirementProjection(getOperation());
}
LogicalResult nodal::InstanceDomainBindingOp::verify() {
  return verifyInstanceDomainBinding(getOperation());
}
LogicalResult nodal::InstancePortOp::verify() {
  return success(succeeded(inspectInstancePort(getOperation())));
}

LogicalResult nodal::EventProcessOp::verify() {
  return success(succeeded(inspectEventProcess(getOperation())));
}
