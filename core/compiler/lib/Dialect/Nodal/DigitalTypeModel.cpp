#include "nodal/Dialect/Nodal/DigitalTypeModel.h"

#include "nodal/Dialect/Nodal/ClockModel.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalMaterializationModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/EnumSwitchModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/FlatFSMModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/MemoryModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ResetModel.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Dialect/Nodal/StructuralIfModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringSet.h"

#include <algorithm>
#include <limits>

using namespace mlir;

namespace nodal {
namespace {
bool identifier(StringRef value) {
  if (value.empty() || value.size() > digitalTypeMaximumIdentifierBytes)
    return false;
  auto initial = [](char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
  };
  if (!initial(value.front()))
    return false;
  return llvm::all_of(value.drop_front(),
                      [&](char c) { return initial(c) || (c >= '0' && c <= '9'); });
}

bool validWidthKind(StringRef kind) { return kind == "bits" || kind == "uint" || kind == "sint"; }

StringRef scalarKind(Type type) {
  if (llvm::isa<BitsType>(type))
    return "bits";
  if (llvm::isa<UIntType>(type))
    return "uint";
  if (llvm::isa<SIntType>(type))
    return "sint";
  if (auto symbolic = llvm::dyn_cast<ParametricIntegerType>(type))
    if (validWidthKind(symbolic.getKind()))
      return symbolic.getKind();
  return {};
}

FailureOr<InstantiatedIntegerExpression>
dimensionExpression(Operation *module, StringRef token, StringRef effect,
                    const ParameterIntegerEnvironment *environment, Operation *instance = nullptr) {
  auto dimension = resolveStaticShapeDimension(module, token, effect, environment);
  if (failed(dimension))
    return failure();
  if (instance) {
    if (environment || resolveInstanceModuleDefinition(instance) != module)
      return failure();
    if (dimension->parameter)
      return resolveInstanceIntegerParameter(instance, dimension->parameter);
    if (dimension->expression)
      return resolveInstanceIntegerExpression(instance, dimension->expression,
                                              effect == "width"
                                                  ? ParameterIntegerMode::MathematicalSigned64
                                                  : ParameterIntegerMode::Storage);
    return makeLiteralIntegerExpression(dimension->literal);
  }
  if (dimension->parameter)
    return environment ? resolveOwnedIntegerParameter(module, dimension->parameter, *environment)
                       : resolveOwnedIntegerParameter(module, dimension->parameter);
  if (dimension->expression) {
    auto mode = effect == "width" ? ParameterIntegerMode::MathematicalSigned64
                                  : ParameterIntegerMode::Storage;
    return environment
               ? resolveOwnedIntegerExpression(module, dimension->expression, *environment, mode)
               : resolveOwnedIntegerExpression(module, dimension->expression, mode);
  }
  return makeLiteralIntegerExpression(dimension->literal);
}

bool shapeSpelling(StringRef dimensions) {
  if (dimensions.empty())
    return false;
  unsigned count = 0;
  while (true) {
    auto [token, rest] = dimensions.split(',');
    if (++count > digitalTypeMaximumNodes || token.empty() || token != token.trim() ||
        token.size() > digitalTypeMaximumWidthTokenBytes)
      return false;
    int64_t literal = 0;
    if (token.front() >= '0' && token.front() <= '9') {
      if (token.front() == '0' || token.getAsInteger(10, literal) || literal <= 0)
        return false;
    } else if (!isCanonicalDigitalWidthToken(token)) {
      return false;
    }
    if (rest.empty())
      return !dimensions.ends_with(",");
    dimensions = rest;
  }
}

bool fieldSchema(StringRef name, ArrayAttr fields) {
  if (!identifier(name) || !fields || fields.empty() || fields.size() > digitalTypeMaximumNodes)
    return false;
  llvm::StringSet<> names;
  for (Attribute field : fields) {
    auto dictionary = llvm::dyn_cast<DictionaryAttr>(field);
    if (!dictionary || dictionary.size() != 2)
      return false;
    auto fieldName = dictionary.getAs<StringAttr>("name");
    auto fieldType = dictionary.getAs<TypeAttr>("type");
    if (!fieldName || !identifier(fieldName.getValue()) || !fieldType ||
        !names.insert(fieldName.getValue()).second)
      return false;
  }
  return true;
}

// The memo stores subtree height, so sharing a previously checked subtree cannot
// hide an over-deep new path. No storage is expanded by the dimensions/bit width.
class StorageTypeChecker {
public:
  FailureOr<unsigned> check(Type type, unsigned depth = 1) {
    if (!type || depth > digitalTypeMaximumDepth || ++work > digitalTypeMaximumNodes)
      return failure();
    auto known = heights.find(type);
    if (known != heights.end())
      return depth - 1 + known->second <= digitalTypeMaximumDepth
                 ? FailureOr<unsigned>(known->second)
                 : FailureOr<unsigned>(failure());
    if (!active.insert(type).second)
      return failure();
    unsigned height = 1;
    bool valid = true;
    if (auto scalar = llvm::dyn_cast<BitsType>(type))
      valid = scalar.getWidth() > 0;
    else if (auto scalar = llvm::dyn_cast<UIntType>(type))
      valid = scalar.getWidth() > 0;
    else if (auto scalar = llvm::dyn_cast<SIntType>(type))
      valid = scalar.getWidth() > 0;
    else if (auto scalar = llvm::dyn_cast<ParametricIntegerType>(type))
      valid = validWidthKind(scalar.getKind()) && isCanonicalDigitalWidthToken(scalar.getWidth());
    else if (auto scalar = llvm::dyn_cast<EnumType>(type))
      valid = !scalar.getSymbol().trim().empty() && scalar.getWidth() > 0;
    else if (auto scalar = llvm::dyn_cast<IntegerType>(type))
      valid = scalar.getWidth() == 1 || scalar.getWidth() == 64;
    else if (llvm::isa<Float64Type>(type)) {
      // Integer/Real are ordinary storable digital values in the frozen surface.
    } else if (auto shaped = llvm::dyn_cast<nodal::ShapedType>(type)) {
      auto element = check(shaped.getElementType(), depth + 1);
      valid = shapeSpelling(shaped.getDimensions()) && succeeded(element);
      if (valid)
        height += *element;
    } else if (auto structure = llvm::dyn_cast<StructType>(type)) {
      auto fields = checkFields(structure.getName(), structure.getFields(), depth);
      valid = succeeded(fields);
      if (valid)
        height = *fields;
    } else {
      valid = false;
    }
    active.erase(type);
    if (!valid)
      return failure();
    heights.try_emplace(type, height);
    return height;
  }

  FailureOr<unsigned> checkFields(StringRef name, ArrayAttr fields, unsigned depth = 1) {
    if (depth > digitalTypeMaximumDepth || !fieldSchema(name, fields))
      return failure();
    unsigned height = 1;
    for (Attribute field : fields) {
      auto fieldType = llvm::cast<DictionaryAttr>(field).getAs<TypeAttr>("type").getValue();
      auto child = check(fieldType, depth + 1);
      if (failed(child))
        return failure();
      height = std::max(height, 1 + *child);
    }
    return height;
  }

private:
  unsigned work = 0;
  llvm::DenseMap<Type, unsigned> heights;
  llvm::DenseSet<Type> active;
};

struct TypeFacts {
  bool containsDigital = false;
  bool containsControlCapability = false;
  int64_t maximumBits = 0;
  unsigned height = 1;
};

class ModuleTypeVerifier {
public:
  explicit ModuleTypeVerifier(Operation *module,
                              const ParameterIntegerEnvironment *environment = nullptr)
      : module(module), rightModule(module), environment(environment),
        rightEnvironment(environment) {}

  FailureOr<int64_t> storageMaximumBits(Type type, Operation *use) {
    currentUse = use;
    if (failed(storage.check(type))) {
      (void)reject("storage requires a bounded directionless recursive digital type");
      return failure();
    }
    auto result = check(type, 1, true);
    if (failed(result) || result->maximumBits <= 0)
      return failure();
    return result->maximumBits;
  }

  bool equivalentStorageTypes(Type left, Type right, bool widthFamily = false) {
    if (failed(storageMaximumBits(left, environment ? environment->use() : module)) ||
        failed(storageMaximumBits(right, environment ? environment->use() : module)))
      return false;
    return equivalentStoragePair(left, right, 1, widthFamily);
  }

  bool equivalentStorageTypesAcross(Type left, Type right,
                                    const ParameterIntegerEnvironment *rightContext) {
    ModuleTypeVerifier other(module, rightContext);
    if (failed(storageMaximumBits(left, environment ? environment->use() : module)) ||
        failed(other.storageMaximumBits(right, rightContext ? rightContext->use() : module)))
      return false;
    rightEnvironment = rightContext;
    return equivalentStoragePair(left, right, 1, false);
  }
  bool equivalentInstanceStorageTypes(Type left, Operation *leftContext, Type right,
                                      Operation *rightOwner, Operation *rightContext) {
    ModuleTypeVerifier other(rightOwner);
    if (failed(storageMaximumBits(left, module)) ||
        failed(other.storageMaximumBits(right, rightOwner)))
      return false;
    leftInstance = leftContext;
    rightInstance = rightContext;
    rightModule = rightOwner;
    return equivalentStoragePair(left, right, 1, false);
  }

  LogicalResult verify(Operation *use) {
    currentUse = use;
    // Diagnose malformed isolated ingress before descending into block types;
    // otherwise a failed scoped environment can hide the actual header error.
    if (llvm::isa<FlatFSMOp>(use) && failed(inspectFlatFSMHeader(use)))
      return verifyFlatFSMOperation(use);
    if (llvm::isa<EnumSwitchOp>(use) && failed(inspectEnumSwitchHeader(use)))
      return verifyEnumSwitchOperation(use);
    bool capability = false;
    auto verifyType = [&](Type type) {
      auto result = check(type);
      if (succeeded(result))
        capability |= result->containsControlCapability;
      return success(succeeded(result));
    };
    for (Type type : use->getOperandTypes())
      if (failed(verifyType(type)))
        return failure();
    for (Type type : use->getResultTypes())
      if (failed(verifyType(type)))
        return failure();
    // A collection owns dependent block-argument types; its coordinator checks
    // them under each admitted body context.
    if (!llvm::isa<CollectionOp, StructuralIfOp>(use))
      for (Region &region : use->getRegions())
        for (Block &block : region)
          for (BlockArgument argument : block.getArguments())
            if (failed(verifyType(argument.getType())))
              return failure();
    for (NamedAttribute attribute : use->getAttrs()) {
      if (use == module && attribute.getName() == "nodal.digital.materialization") {
        // This inventory retains exact types from their actual lexical uses.
        // Its owner recomputes every record (including type and region) under
        // those scoped contexts; module-scope reinterpretation loses that proof.
        if (failed(verifyDigitalMaterializationReport(module)))
          return failure();
        continue;
      }
      auto result = checkAttribute(attribute.getValue());
      if (failed(result))
        return failure();
      capability |= *result;
    }
    if (!capability)
      return success();
    // A finite boundary/projection contract preserves clock/reset provenance.
    // No generic value producer, arithmetic, selection, or storage operation
    // may manufacture or reinterpret either capability.
    if (llvm::isa<PortOp>(use)) {
      auto direction = use->getAttrOfType<StringAttr>("direction");
      if (!direction || (direction.getValue() != "input" && direction.getValue() != "output"))
        return success(
            succeeded(reject("Clock/Reset ports require explicit input/output direction")));
      auto portType = use->getAttrOfType<TypeAttr>("type");
      auto controlKind =
          portType ? getDigitalControlBoundaryKind(portType.getValue()) : StringRef();
      if (!use->getAttr("domain") && direction.getValue() == "input" && controlKind == "clock")
        return success();
      auto reference = use->getAttrOfType<FlatSymbolRefAttr>("domain");
      Operation *domain = reference ? SymbolTable::lookupSymbolIn(module, reference) : nullptr;
      if (!llvm::isa_and_nonnull<DomainOp, DomainRequirementOp>(domain) ||
          domain->getParentOp() != module)
        return success(succeeded(reject("Clock/Reset port requires an actual local domain")));
      return success();
    }
    if (llvm::isa<RequirementClockOp, RequirementResetOp>(use))
      return verifyRequirementProjection(use);
    if (llvm::isa<InstanceDomainBindingOp>(use))
      return verifyInstanceDomainBinding(use);
    if (llvm::isa<ClockRelationshipOp>(use))
      return verifyClockRelationship(use);
    if (llvm::isa<ClockGateOp, ClockMuxOp>(use))
      return verifyClockProducer(use);
    if (llvm::isa<ResetBoundaryOp, ResetSyncOp, ResetCombineOp>(use))
      return verifyResetProducer(use);
    if (llvm::isa<LocalDomainBindingOp>(use))
      return verifyLocalDomainBinding(use);
    if (llvm::isa<MemoryWriteEffectOp>(use))
      return verifyMemoryPort(use);
    if (llvm::isa<SequentialUpdateOp>(use))
      return verifySequentialUpdate(use);
    if (llvm::isa<StructuralIfOp>(use))
      return verifyStructuralIfOperation(use);
    if (llvm::isa<ResetAssertedOp>(use))
      return verifyResetAsserted(use);
    if (llvm::isa<SequentialAssertOp>(use))
      return verifySequentialAssertion(use);
    if (llvm::isa<PortValueOp, ShapeIndexOp, ShapeSliceOp, ShapeFlattenOp, ShapeViewOp,
                  DigitalAssignOp>(use))
      return success();
    if (llvm::isa<InterfaceMemberOp, MemberAccessOp, InterfaceAbiOp, InterfaceConnectOp,
                  InterfaceDriveOp, InterfaceObserveOp>(use))
      return verifyDigitalInterfaceOperation(use);
    return success(succeeded(reject("Clock/Reset capabilities support only exact boundary "
                                    "transport and structural projections")));
  }

private:
  bool equivalentStoragePair(Type left, Type right, unsigned depth, bool widthFamily) {
    if (depth > digitalTypeMaximumDepth || ++comparisonWork > digitalTypeMaximumNodes)
      return false;
    const auto pair = std::make_pair(left, right);
    if (equivalentPairs.contains(pair))
      return true;
    bool equal = left == right && module == rightModule && !leftInstance && !rightInstance &&
                 environment == rightEnvironment;
    StringRef kind = scalarKind(left);
    if (!equal && !kind.empty() && kind == scalarKind(right)) {
      auto lhs = widthExpression(left);
      auto rhs = rightInstance ? inferInstanceDigitalWidthExpression(right, rightInstance)
                               : inferDigitalWidthExpression(right, rightModule, rightEnvironment);
      equal = succeeded(lhs) && succeeded(rhs) &&
              (widthFamily || equivalentInstantiatedIntegerExpressions(*lhs, *rhs));
    } else if (!equal) {
      if (auto lhs = llvm::dyn_cast<nodal::ShapedType>(left)) {
        auto rhs = llvm::dyn_cast<nodal::ShapedType>(right);
        if (rhs) {
          llvm::SmallVector<StringRef, 4> a, b;
          lhs.getDimensions().split(a, ',');
          rhs.getDimensions().split(b, ',');
          equal = a.size() == b.size() &&
                  equivalentStoragePair(lhs.getElementType(), rhs.getElementType(), depth + 1,
                                        widthFamily);
          for (unsigned index = 0; equal && index < a.size(); ++index) {
            if (++comparisonWork > digitalTypeMaximumNodes)
              return false;
            auto x = dimensionExpression(module, a[index], "shape", environment, leftInstance);
            auto y = dimensionExpression(rightModule, b[index], "shape", rightEnvironment,
                                         rightInstance);
            equal =
                succeeded(x) && succeeded(y) && equivalentInstantiatedIntegerExpressions(*x, *y);
          }
        }
      } else if (auto lhs = llvm::dyn_cast<StructType>(left)) {
        auto rhs = llvm::dyn_cast<StructType>(right);
        equal = rhs && lhs.getName() == rhs.getName() &&
                lhs.getFields().size() == rhs.getFields().size();
        for (unsigned index = 0; equal && index < lhs.getFields().size(); ++index) {
          auto a = llvm::cast<DictionaryAttr>(lhs.getFields()[index]);
          auto b = llvm::cast<DictionaryAttr>(rhs.getFields()[index]);
          equal =
              a.getAs<StringAttr>("name") == b.getAs<StringAttr>("name") &&
              equivalentStoragePair(a.getAs<TypeAttr>("type").getValue(),
                                    b.getAs<TypeAttr>("type").getValue(), depth + 1, widthFamily);
        }
      } else {
        equal = left == right;
      }
    }
    if (equal)
      equivalentPairs.insert(pair);
    return equal;
  }

  FailureOr<InstantiatedIntegerExpression> widthExpression(Type type) {
    auto known = widthExpressions.find(type);
    if (known != widthExpressions.end())
      return known->second;
    auto expression = leftInstance ? inferInstanceDigitalWidthExpression(type, leftInstance)
                                   : inferDigitalWidthExpression(type, module, environment);
    if (succeeded(expression))
      widthExpressions.try_emplace(type, *expression);
    return expression;
  }

  FailureOr<TypeFacts> reject(StringRef message) {
    currentUse->emitOpError("NODAL-DIGITAL-054-004: ") << message;
    return failure();
  }

  FailureOr<bool> checkAttribute(Attribute attribute, unsigned depth = 0) {
    if (!attribute || depth > digitalTypeMaximumDepth)
      return failure();
    auto known = attributes.find(attribute);
    if (known != attributes.end())
      return known->second;
    if (attributes.size() >= digitalTypeMaximumNodes)
      return failure();
    bool valid = true, capability = false;
    attribute.walkImmediateSubElements(
        [&](Attribute child) {
          auto result = checkAttribute(child, depth + 1);
          valid &= succeeded(result);
          if (succeeded(result))
            capability |= *result;
        },
        [&](Type child) {
          auto result = check(child);
          valid &= succeeded(result);
          if (succeeded(result))
            capability |= result->containsControlCapability;
        });
    if (!valid)
      return failure();
    attributes.try_emplace(attribute, capability);
    return capability;
  }

  FailureOr<TypeFacts> check(Type type, unsigned depth = 1, bool storageContext = false) {
    if (!type)
      return reject("missing recursive digital type");
    if (depth > digitalTypeMaximumDepth)
      return reject("recursive type nesting exceeds the finite metadata limit");
    // Token admissibility depends on the actual consumer, so it must be
    // checked before the structural-type memo (which is shared across uses).
    if (llvm::isa<StaticPredicateType>(type) &&
        (storageContext ||
         !llvm::isa<StaticCompareOp, StaticLogicOp, StructuralIfOp, BehavioralPredicateOp,
                    BehavioralIfOp, SequentialSelectOp>(currentUse)))
      return reject(
          "static predicate tokens cannot be stored or manufactured by runtime operations");
    if (llvm::isa<GeneratedFamilyType>(type) &&
        (depth != 1 || storageContext ||
         !llvm::isa<GenerateOp, GeneratedMemberOp, GeneratedExportOp>(currentUse)))
      return reject("generated family capabilities support only their exact declaration and "
                    "checked member projection, never payload storage");
    const auto key = std::make_pair(type, static_cast<unsigned>(storageContext));
    auto found = facts.find(key);
    if (found != facts.end()) {
      if (depth - 1 + found->second.height > digitalTypeMaximumDepth)
        return reject("recursive type nesting exceeds the finite metadata limit");
      return found->second;
    }
    if (++work > digitalTypeMaximumNodes || !active.insert(key).second)
      return reject("recursive type graph exceeds the finite metadata limit or contains a cycle");
    TypeFacts result;
    if (llvm::isa<StaticPredicateType, GeneratedFamilyType>(type)) {
      active.erase(key);
      facts.try_emplace(key, result);
      return result;
    }
    auto width = inferDigitalWidthBounds(type, module, environment);
    if (succeeded(width)) {
      result.containsDigital =
          storageContext ||
          llvm::isa<BitsType, UIntType, SIntType, ParametricIntegerType, EnumType>(type);
      result.maximumBits = width->upper;
    } else if (llvm::isa<BitsType, UIntType, SIntType, ParametricIntegerType, EnumType>(type)) {
      return reject("digital width requires an owned positive finite static Integer proof with "
                    "structural width dependencies; defaults are not bounds");
    } else if (auto control = llvm::dyn_cast<ControlCapabilityType>(type)) {
      if (storageContext || (control.getKind() != "clock" && control.getKind() != "reset"))
        return reject("Clock/Reset capabilities are not scalar payloads or storage values");
      result.containsDigital = true;
      result.containsControlCapability = true;
      result.maximumBits = 1;
    } else if (auto structure = llvm::dyn_cast<StructType>(type)) {
      if (failed(storage.check(type)))
        return reject("Struct requires bounded ordered unique named fields with directionless "
                      "digital storage types");
      result.containsDigital = true;
      for (Attribute field : structure.getFields()) {
        auto fieldType = llvm::cast<DictionaryAttr>(field).getAs<TypeAttr>("type").getValue();
        auto child = check(fieldType, depth + 1, true);
        if (failed(child))
          return failure();
        if (child->maximumBits > std::numeric_limits<int64_t>::max() - result.maximumBits)
          return reject("recursive digital bit footprint exceeds positive signed-i64 metadata");
        result.maximumBits += child->maximumBits;
        result.height = std::max(result.height, 1 + child->height);
      }
    } else if (auto shaped = llvm::dyn_cast<nodal::ShapedType>(type)) {
      auto child = check(shaped.getElementType(), depth + 1, storageContext);
      if (failed(child))
        return failure();
      result = *child;
      ++result.height;
      if (result.containsDigital) {
        auto cardinality =
            shapeSpelling(shaped.getDimensions())
                ? inferStaticShapeMaximumCardinality(module, shaped.getDimensions(), environment)
                : FailureOr<int64_t>(failure());
        if (failed(cardinality))
          return reject("recursive digital shape requires canonical owned positive finite "
                        "dimensions and a signed-i64 cardinality proof");
        if (result.maximumBits > std::numeric_limits<int64_t>::max() / *cardinality)
          return reject("recursive digital bit footprint exceeds positive signed-i64 metadata");
        result.maximumBits *= *cardinality;
      }
    } else {
      // Protocol and resolved-net wrappers retain their own access policy. Walk
      // their actual payload, as well as generic container/function types, so a
      // symbolic width cannot evade ownership checks inside an attribute/type.
      bool valid = true;
      type.walkImmediateSubElements(
          [&](Attribute child) { valid &= succeeded(checkAttribute(child)); },
          [&](Type childType) {
            auto child = check(
                childType, depth + 1,
                storageContext || llvm::isa<ValidType, StreamType, ResolvedType, DriverType>(type));
            valid &= succeeded(child);
            if (succeeded(child)) {
              if (child->containsControlCapability) {
                (void)reject(
                    "Clock/Reset capabilities cannot be embedded in arbitrary value containers");
                valid = false;
              }
              result.containsDigital |= child->containsDigital;
              result.height = std::max(result.height, 1 + child->height);
              if (child->maximumBits > std::numeric_limits<int64_t>::max() - result.maximumBits) {
                (void)reject(
                    "recursive digital bit footprint exceeds positive signed-i64 metadata");
                valid = false;
              } else {
                result.maximumBits += child->maximumBits;
              }
            }
          });
      if (!valid)
        return failure();
    }
    active.erase(key);
    facts.try_emplace(key, result);
    return result;
  }

  Operation *module;
  Operation *rightModule;
  Operation *leftInstance = nullptr;
  Operation *rightInstance = nullptr;
  const ParameterIntegerEnvironment *environment;
  const ParameterIntegerEnvironment *rightEnvironment;
  Operation *currentUse = nullptr;
  unsigned work = 0;
  unsigned comparisonWork = 0;
  StorageTypeChecker storage;
  llvm::DenseMap<std::pair<Type, unsigned>, TypeFacts> facts;
  llvm::DenseSet<std::pair<Type, unsigned>> active;
  llvm::DenseMap<Attribute, bool> attributes;
  llvm::DenseSet<std::pair<Type, Type>> equivalentPairs;
  llvm::DenseMap<Type, InstantiatedIntegerExpression> widthExpressions;
};
} // namespace

bool isCanonicalDigitalWidthToken(StringRef token) {
  if (token.empty() || token.size() > digitalTypeMaximumWidthTokenBytes || token != token.trim())
    return false;
  while (true) {
    auto [segment, rest] = token.split('.');
    if (!identifier(segment))
      return false;
    if (rest.empty())
      return !token.ends_with(".");
    token = rest;
  }
}

LogicalResult verifyDigitalStructFields(llvm::function_ref<InFlightDiagnostic()> emitError,
                                        StringRef name, ArrayAttr fields) {
  StorageTypeChecker checker;
  if (failed(checker.checkFields(name, fields)))
    return emitError() << "NODAL-DIGITAL-054-004: Struct requires a portable name and a nonempty "
                          "ordered array of unique {name, type} fields with bounded recursive "
                          "directionless digital storage types";
  return success();
}

bool isDigitalStorageType(Type type) {
  StorageTypeChecker checker;
  return succeeded(checker.check(type));
}

StringRef getDigitalControlBoundaryKind(Type type) {
  unsigned depth = 0;
  while (auto shaped = llvm::dyn_cast_if_present<nodal::ShapedType>(type)) {
    if (++depth > digitalTypeMaximumDepth)
      return {};
    type = shaped.getElementType();
  }
  auto control = llvm::dyn_cast_if_present<ControlCapabilityType>(type);
  return control && (control.getKind() == "clock" || control.getKind() == "reset")
             ? control.getKind()
             : StringRef();
}
bool isDigitalControlBoundaryType(Type type) {
  return !getDigitalControlBoundaryKind(type).empty();
}

FailureOr<int64_t>
inferDigitalControlMaximumElements(Type type, Operation *module,
                                   const ParameterIntegerEnvironment *environment) {
  if (!isDigitalControlBoundaryType(type))
    return failure();
  int64_t count = 1;
  while (auto shaped = llvm::dyn_cast<nodal::ShapedType>(type)) {
    auto cardinality =
        inferStaticShapeMaximumCardinality(module, shaped.getDimensions(), environment);
    if (failed(cardinality) || count > std::numeric_limits<int64_t>::max() / *cardinality)
      return failure();
    count *= *cardinality;
    type = shaped.getElementType();
  }
  return count;
}

FailureOr<ParameterIntegerBounds>
inferDigitalWidthBounds(Type type, Operation *module,
                        const ParameterIntegerEnvironment *environment) {
  if (!type)
    return failure();
  int64_t width = 0;
  if (auto scalar = llvm::dyn_cast<BitsType>(type))
    width = scalar.getWidth();
  else if (auto scalar = llvm::dyn_cast<UIntType>(type))
    width = scalar.getWidth();
  else if (auto scalar = llvm::dyn_cast<SIntType>(type))
    width = scalar.getWidth();
  else if (auto scalar = llvm::dyn_cast<EnumType>(type))
    width = scalar.getWidth();
  else if (auto scalar = llvm::dyn_cast<IntegerType>(type))
    width = scalar.getWidth();
  else if (auto scalar = llvm::dyn_cast<FloatType>(type))
    width = scalar.getWidth();
  else if (auto scalar = llvm::dyn_cast<ParametricIntegerType>(type)) {
    if (!validWidthKind(scalar.getKind()) || !isCanonicalDigitalWidthToken(scalar.getWidth()))
      return failure();
    auto dimension = resolveStaticShapeDimension(module, scalar.getWidth(), "width", environment);
    if (failed(dimension))
      return failure();
    return dimension->bounds;
  }
  if (width <= 0)
    return failure();
  return ParameterIntegerBounds{width, width};
}

FailureOr<InstantiatedIntegerExpression>
inferDigitalWidthExpression(Type type, Operation *module,
                            const ParameterIntegerEnvironment *environment) {
  auto bounds = inferDigitalWidthBounds(type, module, environment);
  if (failed(bounds) || bounds->lower <= 0 || bounds->upper < bounds->lower)
    return failure();
  if (auto symbolic = llvm::dyn_cast<ParametricIntegerType>(type))
    return dimensionExpression(module, symbolic.getWidth(), "width", environment);
  return makeLiteralIntegerExpression(bounds->lower);
}
FailureOr<InstantiatedIntegerExpression> inferInstanceDigitalWidthExpression(Type type,
                                                                             Operation *instance) {
  Operation *module = resolveInstanceModuleDefinition(instance);
  if (!module || !isa_and_nonnull<nodal::ModuleOp>(instance->getParentOp()))
    return failure();
  if (failed(inferDigitalWidthBounds(type, module)))
    return failure();
  if (auto symbolic = dyn_cast<ParametricIntegerType>(type))
    return dimensionExpression(module, symbolic.getWidth(), "width", nullptr, instance);
  return inferDigitalWidthExpression(type, module);
}

bool equivalentDigitalStorageTypes(Type left, Type right, Operation *module,
                                   const ParameterIntegerEnvironment *environment) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return false;
  ModuleTypeVerifier verifier(module, environment);
  return verifier.equivalentStorageTypes(left, right);
}

bool sameDigitalStorageWidthFamily(Type base, Type instance, Operation *module,
                                   const ParameterIntegerEnvironment *environment) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return false;
  ModuleTypeVerifier verifier(module, environment);
  return verifier.equivalentStorageTypes(base, instance, true);
}

bool equivalentDigitalStorageTypesAcross(Type left, const ParameterIntegerEnvironment *leftContext,
                                         Type right,
                                         const ParameterIntegerEnvironment *rightContext,
                                         Operation *module) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return false;
  return ModuleTypeVerifier(module, leftContext)
      .equivalentStorageTypesAcross(left, right, rightContext);
}
bool equivalentInstanceDigitalStorageTypes(Operation *instance, Type formal, Type actual) {
  return instance && equivalentDigitalStorageTypesAcrossInstances(instance->getParentOp(), formal,
                                                                  instance, actual, nullptr);
}
bool equivalentDigitalStorageTypesAcrossInstances(Operation *parent, Type left,
                                                  Operation *leftInstance, Type right,
                                                  Operation *rightInstance) {
  if (!isa_and_nonnull<nodal::ModuleOp>(parent))
    return false;
  auto owner = [&](Operation *instance) -> Operation * {
    if (!instance)
      return parent;
    return instance->getParentOp() == parent ? resolveInstanceModuleDefinition(instance) : nullptr;
  };
  Operation *leftOwner = owner(leftInstance), *rightOwner = owner(rightInstance);
  return leftOwner && rightOwner &&
         ModuleTypeVerifier(leftOwner).equivalentInstanceStorageTypes(left, leftInstance, right,
                                                                      rightOwner, rightInstance);
}

LogicalResult verifyDigitalTypeOperation(Operation *operation,
                                         const ParameterIntegerEnvironment *environment) {
  if (!operation)
    return failure();
  Operation *module = llvm::isa<nodal::ModuleOp>(operation)
                          ? operation
                          : operation->getParentOfType<nodal::ModuleOp>();
  if (!module)
    return failure();
  if (!environment && isDigitalRegionOwner(operation->getParentOp())) {
    auto contexts = collectionIntegerContextsAtUse(operation);
    if (failed(contexts))
      return failure();
    for (const auto &context : *contexts)
      if (failed(verifyDigitalTypeOperation(operation, &context)))
        return failure();
    return success();
  }
  if (environment) {
    auto context = environment->atUse(operation);
    if (failed(context) || context->module() != module)
      return failure();
    return ModuleTypeVerifier(module, &*context).verify(operation);
  }
  return ModuleTypeVerifier(module).verify(operation);
}

LogicalResult verifyDigitalTypeModel(Operation *module, bool includeCollections) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return failure();
  ModuleTypeVerifier verifier(module);
  auto result = module->walk<WalkOrder::PreOrder>([&](Operation *operation) {
    if (operation != module && llvm::isa<nodal::ModuleOp>(operation))
      return WalkResult::skip();
    if (failed(isDigitalRegionOwner(operation->getParentOp())
                   ? verifyDigitalTypeOperation(operation)
                   : verifier.verify(operation)))
      return WalkResult::interrupt();
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
    return WalkResult::advance();
  });
  return failure(result.wasInterrupted());
}

FailureOr<int64_t> inferDigitalStorageMaximumBits(Type type, Operation *use,
                                                  const ParameterIntegerEnvironment *environment) {
  if (!use)
    return failure();
  Operation *module =
      llvm::isa<nodal::ModuleOp>(use) ? use : use->getParentOfType<nodal::ModuleOp>();
  std::optional<ParameterIntegerEnvironment> context;
  if (environment) {
    if (environment->module() != module)
      return failure();
    if (use != module) {
      auto scoped = environment->atUse(use);
      if (failed(scoped))
        return failure();
      context.emplace(*scoped);
      environment = &*context;
    }
  }
  if (!environment && use != module && use->getParentOp() != module) {
    // Scoped type proofs belong to the neutral region owner. In particular,
    // a generated induction is not silently promoted into a width parameter.
    auto contexts = digitalIntegerContextsAtUse(use);
    if (failed(contexts) || contexts->empty())
      return failure();
    int64_t maximum = 0;
    for (const auto &scoped : *contexts) {
      auto bits = inferDigitalStorageMaximumBits(type, use, &scoped);
      if (failed(bits))
        return failure();
      maximum = std::max(maximum, *bits);
    }
    return maximum;
  }
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return failure();
  return ModuleTypeVerifier(module, environment).storageMaximumBits(type, use);
}
FailureOr<int64_t> inferDigitalBitPatternMaximumBits(Type type, Operation *use) {
  // Validate complete structure, nominal ownership, parameter bounds and depth
  // with the existing type owner before applying the bit-pattern leaf policy.
  auto bits = inferDigitalStorageMaximumBits(type, use);
  if (failed(bits))
    return failure();
  SmallVector<Type> pending{type};
  llvm::DenseSet<Type> visited;
  unsigned work = 0;
  while (!pending.empty()) {
    Type current = pending.pop_back_val();
    if (++work > digitalTypeMaximumNodes)
      return failure();
    if (!visited.insert(current).second)
      continue;
    if (isa<BitsType, UIntType, SIntType, ParametricIntegerType>(current))
      continue;
    if (auto enumeration = dyn_cast<EnumType>(current)) {
      auto definition = inspectCanonicalEnumDefinition(
          use, FlatSymbolRefAttr::get(use->getContext(), enumeration.getSymbol()));
      if (failed(definition) || definition->type != current)
        return failure();
      continue;
    }
    if (auto shaped = dyn_cast<nodal::ShapedType>(current)) {
      pending.push_back(shaped.getElementType());
      continue;
    }
    if (auto structure = dyn_cast<StructType>(current)) {
      for (Attribute field : structure.getFields())
        pending.push_back(cast<DictionaryAttr>(field).getAs<TypeAttr>("type").getValue());
      continue;
    }
    return failure();
  }
  return *bits;
}

FailureOr<int64_t> inferDigitalBitPatternFixedBits(Type type, Operation *use) {
  auto maximum = inferDigitalBitPatternMaximumBits(type, use);
  if (failed(maximum))
    return failure();
  Operation *module = isa<nodal::ModuleOp>(use) ? use : use->getParentOfType<nodal::ModuleOp>();
  SmallVector<Type> pending{type};
  llvm::DenseSet<Type> visited;
  unsigned work = 0;
  while (!pending.empty()) {
    Type current = pending.pop_back_val();
    if (++work > digitalTypeMaximumNodes)
      return failure();
    if (!visited.insert(current).second)
      continue;
    if (isa<ParametricIntegerType>(current)) {
      auto bounds = inferDigitalWidthBounds(current, module);
      if (failed(bounds) || bounds->lower != bounds->upper)
        return failure();
    } else if (auto shaped = dyn_cast<nodal::ShapedType>(current)) {
      SmallVector<StringRef> dimensions;
      shaped.getDimensions().split(dimensions, ',');
      for (StringRef dimension : dimensions) {
        if (++work > digitalTypeMaximumNodes)
          return failure();
        auto extent = resolveStaticShapeDimension(module, dimension);
        if (failed(extent) || extent->bounds.lower != extent->bounds.upper)
          return failure();
      }
      pending.push_back(shaped.getElementType());
    } else if (auto structure = dyn_cast<StructType>(current)) {
      for (Attribute field : structure.getFields())
        pending.push_back(cast<DictionaryAttr>(field).getAs<TypeAttr>("type").getValue());
    }
  }
  // All component extents are singleton facts, so the canonical worst-case
  // footprint is the exact width. No second arithmetic/type-layout engine.
  return *maximum;
}

} // namespace nodal

// Preserve the ordinary entry-point ABI for existing non-contextual consumers.
namespace nodal {
FailureOr<int64_t> inferDigitalControlMaximumElements(Type type, Operation *module) {
  return inferDigitalControlMaximumElements(type, module, nullptr);
}
FailureOr<ParameterIntegerBounds> inferDigitalWidthBounds(Type type, Operation *module) {
  return inferDigitalWidthBounds(type, module, nullptr);
}
FailureOr<InstantiatedIntegerExpression> inferDigitalWidthExpression(Type type, Operation *module) {
  return inferDigitalWidthExpression(type, module, nullptr);
}
bool equivalentDigitalStorageTypes(Type left, Type right, Operation *module) {
  return equivalentDigitalStorageTypes(left, right, module, nullptr);
}
bool sameDigitalStorageWidthFamily(Type base, Type instance, Operation *module) {
  return sameDigitalStorageWidthFamily(base, instance, module, nullptr);
}
FailureOr<int64_t> inferDigitalStorageMaximumBits(Type type, Operation *use) {
  return inferDigitalStorageMaximumBits(type, use, nullptr);
}
} // namespace nodal

namespace nodal {
LogicalResult verifyDigitalTypeModel(Operation *module) {
  return verifyDigitalTypeModel(module, true);
}
} // namespace nodal
