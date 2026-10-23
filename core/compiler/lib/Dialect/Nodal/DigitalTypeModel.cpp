#include "nodal/Dialect/Nodal/DigitalTypeModel.h"

#include "nodal/Dialect/Nodal/ClockModel.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalMaterializationModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
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
#include <map>
#include <tuple>

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
                    const ParameterIntegerEnvironment *environment, Operation *instance = nullptr,
                    llvm::ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> selected = {},
                    bool generatedTemplate = false) {
  if (!instance && environment && isGeneratedTypeUse(environment->use()))
    return resolveGeneratedTypeDimensionExpression(environment->use(), token, effect);
  auto dimension = resolveStaticShapeDimension(module, token, effect, environment);
  if (failed(dimension))
    return failure();
  if (instance) {
    if (environment || resolveInstanceModuleDefinition(instance) != module)
      return failure();
    if (generatedTemplate) {
      if (!selected.empty())
        return failure();
      auto mode = effect == "width" ? ParameterIntegerMode::MathematicalSigned64
                                    : ParameterIntegerMode::Storage;
      if (dimension->parameter)
        return resolveGeneratedInstanceIntegerParameter(instance, dimension->parameter, mode);
      if (dimension->expression)
        return resolveGeneratedInstanceIntegerExpression(instance, dimension->expression, mode);
      return makeLiteralIntegerExpression(dimension->literal);
    }
    if (dimension->parameter)
      return selected.empty() ? resolveInstanceIntegerParameter(instance, dimension->parameter)
                              : resolveSelectedInstanceIntegerParameter(
                                    instance, dimension->parameter, selected,
                                    effect == "width" ? ParameterIntegerMode::MathematicalSigned64
                                                      : ParameterIntegerMode::Storage);
    if (dimension->expression)
      return !selected.empty()
                 ? resolveSelectedInstanceIntegerExpression(
                       instance, dimension->expression, selected,
                       effect == "width" ? ParameterIntegerMode::MathematicalSigned64
                                         : ParameterIntegerMode::Storage)
                 : resolveInstanceIntegerExpression(instance, dimension->expression,
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
  bool unattained = false;
  unsigned height = 1;
};

class ModuleTypeVerifier {
public:
  explicit ModuleTypeVerifier(Operation *module,
                              const ParameterIntegerEnvironment *environment = nullptr,
                              Operation *typeInstance = nullptr)
      : module(module), rightModule(module), environment(environment),
        rightEnvironment(environment), typeInstance(typeInstance) {}

  FailureOr<DigitalStorageTypeFacts> storageTypeFacts(Type type, Operation *use) {
    currentUse = use;
    if (failed(storage.check(type))) {
      (void)reject("storage requires a bounded directionless recursive digital type");
      return failure();
    }
    auto result = check(type, 1, true);
    if (failed(result) || (!result->unattained && result->maximumBits <= 0))
      return failure();
    return DigitalStorageTypeFacts{result->unattained
                                       ? std::optional<int64_t>()
                                       : std::optional<int64_t>(result->maximumBits)};
  }
  FailureOr<int64_t> storageMaximumBits(Type type, Operation *use) {
    auto facts = storageTypeFacts(type, use);
    return succeeded(facts) && facts->maximumBits ? FailureOr<int64_t>(*facts->maximumBits)
                                                  : FailureOr<int64_t>(failure());
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
  bool equivalentInstanceStorageTypes(
      Type left, Operation *leftContext, Type right, Operation *rightOwner, Operation *rightContext,
      llvm::ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> leftSelected = {},
      llvm::ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> rightSelected = {},
      const ParameterIntegerEnvironment *rightTypeContext = nullptr) {
    ModuleTypeVerifier other(rightOwner, rightTypeContext);
    if (failed(storageMaximumBits(left, environment ? environment->use() : module)) ||
        failed(other.storageMaximumBits(right,
                                        rightTypeContext ? rightTypeContext->use() : rightOwner)))
      return false;
    leftSubstitutions = leftSelected;
    rightSubstitutions = rightSelected;
    leftInstance = leftContext;
    rightInstance = rightContext;
    rightModule = rightOwner;
    rightEnvironment = rightTypeContext;
    return equivalentStoragePair(left, right, 1, false);
  }

  bool equivalentGeneratedInstanceStorageTypes(Operation *instance, Type formal, Type actual,
                                               Operation *use) {
    if (!instance || !use || !isa_and_nonnull<GenerateOp>(instance->getParentOp()) ||
        instance->getParentOp() != use->getParentOp() ||
        failed(verifyHierarchyInstanceOperation(instance)))
      return false;
    auto context = digitalGenerateIntegerContextAtUse(use);
    Operation *parent = use->getParentOfType<ModuleOp>();
    if (failed(context) || resolveInstanceModuleDefinition(instance) != module)
      return false;
    ModuleTypeVerifier other(parent, &*context);
    // Keep the original child declaration valid independently of occurrence
    // activity, then check every instantiated field/axis with optional facts.
    // Neither an empty sibling nor one absent dimension can skip another field.
    ModuleTypeVerifier instantiated(module, nullptr, instance);
    if (failed(storageMaximumBits(formal, module)) ||
        failed(instantiated.storageTypeFacts(formal, instance)) ||
        failed(other.storageTypeFacts(actual, use)))
      return false;
    unsigned depth = 0;
    Operation *scope = instance->getParentOp();
    for (; scope && !isa<ModuleOp>(scope); scope = scope->getParentOp()) {
      if (++depth > digitalTypeMaximumDepth)
        return false;
      if (isa<GenerateOp>(scope)) {
        auto domain = resolveGeneratedDomain(scope);
        if (failed(domain))
          return false;
        allowUnattainedComparison |= !domain->attainedInductionBounds;
      }
    }
    if (!scope)
      return false;
    leftInstance = instance;
    leftGeneratedTemplate = true;
    rightModule = parent;
    rightEnvironment = &*context;
    return equivalentStoragePair(formal, actual, 1, false);
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
    for (OpOperand &operand : use->getOpOperands())
      if (!isStructuralIfFamilyCapture(operand) && failed(verifyType(operand.get().getType())))
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
                 environment == rightEnvironment && leftSubstitutions.empty() &&
                 rightSubstitutions.empty();
    StringRef kind = scalarKind(left);
    if (!equal && !kind.empty() && kind == scalarKind(right)) {
      if (allowUnattainedComparison) {
        auto lhs = contextualWidthFacts(left, module, environment, leftInstance, leftSubstitutions,
                                        leftGeneratedTemplate);
        auto rhs = contextualWidthFacts(right, rightModule, rightEnvironment, rightInstance,
                                        rightSubstitutions);
        equal = equivalentDimensionFacts(lhs, rhs);
      } else {
        auto lhs = widthExpression(left);
        auto rhs = contextualWidth(right, rightModule, rightEnvironment, rightInstance,
                                   rightSubstitutions);
        equal = succeeded(lhs) && succeeded(rhs) &&
                (widthFamily || equivalentInstantiatedIntegerExpressions(*lhs, *rhs));
      }
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
            auto x = checkedDimension(module, a[index], "shape", environment, leftInstance,
                                      leftSubstitutions, leftGeneratedTemplate);
            auto y = checkedDimension(rightModule, b[index], "shape", rightEnvironment,
                                      rightInstance, rightSubstitutions);
            equal = equivalentDimensionFacts(x, y);
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

  bool equivalentDimensionFacts(const FailureOr<StructuralIntegerFacts> &left,
                                const FailureOr<StructuralIntegerFacts> &right) {
    if (failed(left) || failed(right))
      return false;
    if (!left->expression || !right->expression)
      // This flag is set only after both complete original types were checked
      // at the same exact generated Instance occurrence and its empty ancestry.
      return allowUnattainedComparison;
    return equivalentInstantiatedIntegerExpressions(*left->expression, *right->expression);
  }

  FailureOr<StructuralIntegerFacts>
  checkedDimension(Operation *owner, StringRef token, StringRef effect,
                   const ParameterIntegerEnvironment *context, Operation *instance,
                   llvm::ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> selected,
                   bool generatedTemplate = false) {
    const DimensionKey key{owner,
                           context,
                           context ? context->use() : nullptr,
                           instance,
                           selected.data(),
                           selected.size(),
                           generatedTemplate,
                           token.str(),
                           effect.str()};
    if (auto found = dimensions.find(key); found != dimensions.end())
      return found->second ? FailureOr<StructuralIntegerFacts>(*found->second)
                           : FailureOr<StructuralIntegerFacts>(failure());
    if (dimensions.size() >= digitalTypeMaximumNodes)
      return failure();
    FailureOr<StructuralIntegerFacts> value = failure();
    if (!instance && context && isGeneratedTypeUse(context->use())) {
      value = resolveGeneratedTypeDimensionFacts(context->use(), token, effect);
    } else if (instance && generatedTemplate && !context && selected.empty() &&
               resolveInstanceModuleDefinition(instance) == owner) {
      auto dimension = resolveStaticShapeDimension(owner, token, effect);
      auto mode = effect == "width" ? ParameterIntegerMode::MathematicalSigned64
                                    : ParameterIntegerMode::Storage;
      if (succeeded(dimension)) {
        if (dimension->parameter)
          value =
              resolveGeneratedInstanceIntegerParameterFacts(instance, dimension->parameter, mode);
        else if (dimension->expression)
          value =
              resolveGeneratedInstanceIntegerExpressionFacts(instance, dimension->expression, mode);
        else
          value = StructuralIntegerFacts{makeLiteralIntegerExpression(dimension->literal)};
      }
    } else {
      auto expression =
          dimensionExpression(owner, token, effect, context, instance, selected, generatedTemplate);
      if (succeeded(expression))
        value = StructuralIntegerFacts{*expression};
    }
    if (succeeded(value) && value->expression && !selected.empty()) {
      auto specialized = substituteIntegerExpression(*value->expression, selected);
      value = succeeded(specialized)
                  ? FailureOr<StructuralIntegerFacts>(StructuralIntegerFacts{*specialized})
                  : FailureOr<StructuralIntegerFacts>(failure());
    }
    dimensions.emplace(key, succeeded(value) ? std::optional<StructuralIntegerFacts>(*value)
                                             : std::nullopt);
    return value;
  }
  FailureOr<StructuralIntegerFacts>
  contextualWidthFacts(Type type, Operation *owner, const ParameterIntegerEnvironment *context,
                       Operation *instance,
                       llvm::ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> selected,
                       bool generatedTemplate = false) {
    if (auto symbolic = dyn_cast<ParametricIntegerType>(type))
      return checkedDimension(owner, symbolic.getWidth(), "width", context, instance, selected,
                              generatedTemplate);
    auto expression = inferDigitalWidthExpression(type, owner, context);
    return succeeded(expression)
               ? FailureOr<StructuralIntegerFacts>(StructuralIntegerFacts{*expression})
               : FailureOr<StructuralIntegerFacts>(failure());
  }
  FailureOr<InstantiatedIntegerExpression>
  contextualWidth(Type type, Operation *owner, const ParameterIntegerEnvironment *context,
                  Operation *instance,
                  llvm::ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> selected,
                  bool generatedTemplate = false) {
    auto facts = contextualWidthFacts(type, owner, context, instance, selected, generatedTemplate);
    return succeeded(facts) && facts->expression
               ? FailureOr<InstantiatedIntegerExpression>(*facts->expression)
               : FailureOr<InstantiatedIntegerExpression>(failure());
  }

  FailureOr<InstantiatedIntegerExpression> widthExpression(Type type) {
    auto known = widthExpressions.find(type);
    if (known != widthExpressions.end())
      return known->second;
    auto expression = contextualWidth(type, module, environment, leftInstance, leftSubstitutions,
                                      leftGeneratedTemplate);
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
         !llvm::isa<GenerateOp, GeneratedMemberOp, GeneratedExportOp, InstancePortOp,
                    InterfaceBoundaryOp>(currentUse)))
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
    FailureOr<ParameterIntegerBounds> width = failure();
    if (auto symbolic = dyn_cast<ParametricIntegerType>(type)) {
      auto expression =
          validWidthKind(symbolic.getKind()) && isCanonicalDigitalWidthToken(symbolic.getWidth())
              ? checkedDimension(module, symbolic.getWidth(), "width", environment, typeInstance,
                                 {}, typeInstance != nullptr)
              : FailureOr<StructuralIntegerFacts>(failure());
      if (succeeded(expression)) {
        if (!expression->expression)
          result.unattained = true;
        else if (expression->expression->bounds.lower > 0)
          width = expression->expression->bounds;
      }
    } else {
      width = inferDigitalWidthBounds(type, module, environment);
    }
    if (succeeded(width) || result.unattained) {
      result.containsDigital =
          storageContext ||
          llvm::isa<BitsType, UIntType, SIntType, ParametricIntegerType, EnumType>(type);
      if (succeeded(width))
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
        result.unattained |= child->unattained;
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
                ? inferStaticShapeMaximumCardinalityFacts(
                      shaped.getDimensions(),
                      [&](StringRef token) -> FailureOr<std::optional<ParameterIntegerBounds>> {
                        auto dimension =
                            checkedDimension(module, token, "shape", environment, typeInstance, {},
                                             typeInstance != nullptr);
                        if (failed(dimension))
                          return failure();
                        return dimension->expression ? std::optional<ParameterIntegerBounds>(
                                                           dimension->expression->bounds)
                                                     : std::optional<ParameterIntegerBounds>();
                      })
                : FailureOr<std::optional<int64_t>>(failure());
        if (failed(cardinality))
          return reject("recursive digital shape requires canonical owned positive finite "
                        "dimensions and a signed-i64 cardinality proof");
        if (*cardinality) {
          if (result.maximumBits > std::numeric_limits<int64_t>::max() / **cardinality)
            return reject("recursive digital bit footprint exceeds positive signed-i64 metadata");
          result.maximumBits *= **cardinality;
        } else {
          result.unattained = true;
        }
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
              result.unattained |= child->unattained;
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

  // One verifier has one admitted actual environment. Still retain actual use,
  // complete context and substitution identity in keys so sibling/dual-context
  // comparisons cannot share a token spelling proof accidentally.
  using DimensionKey =
      std::tuple<Operation *, const ParameterIntegerEnvironment *, Operation *, Operation *,
                 const void *, size_t, bool, std::string, std::string>;
  std::map<DimensionKey, std::optional<StructuralIntegerFacts>> dimensions;
  Operation *module;
  Operation *rightModule;
  llvm::ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> leftSubstitutions,
      rightSubstitutions;
  Operation *leftInstance = nullptr;
  bool leftGeneratedTemplate = false;
  bool allowUnattainedComparison = false;
  Operation *rightInstance = nullptr;
  const ParameterIntegerEnvironment *environment;
  const ParameterIntegerEnvironment *rightEnvironment;
  // Immutable for this checker; cached facts never cross Instance contexts.
  Operation *typeInstance = nullptr;
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
    auto dimension =
        environment && isGeneratedTypeUse(environment->use())
            ? resolveGeneratedTypeDimension(environment->use(), scalar.getWidth(), "width")
            : resolveStaticShapeDimension(module, scalar.getWidth(), "width", environment);
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
static FailureOr<InstantiatedIntegerExpression>
instanceDigitalWidthExpression(Type type, Operation *instance, bool generatedTemplate) {
  Operation *module = resolveInstanceModuleDefinition(instance);
  if (!module || failed(verifyHierarchyInstanceOperation(instance)) ||
      (generatedTemplate && !isa_and_nonnull<GenerateOp>(instance->getParentOp())))
    return failure();
  if (failed(inferDigitalWidthBounds(type, module)))
    return failure();
  if (auto symbolic = dyn_cast<ParametricIntegerType>(type))
    return dimensionExpression(module, symbolic.getWidth(), "width", nullptr, instance, {},
                               generatedTemplate);
  return inferDigitalWidthExpression(type, module);
}
FailureOr<InstantiatedIntegerExpression> inferInstanceDigitalWidthExpression(Type type,
                                                                             Operation *instance) {
  return instanceDigitalWidthExpression(type, instance, false);
}
FailureOr<InstantiatedIntegerExpression>
inferGeneratedInstanceDigitalWidthExpression(Type type, Operation *instance) {
  return instanceDigitalWidthExpression(type, instance, true);
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
bool equivalentDigitalStorageTypesAt(Type left, Operation *leftUse, Type right,
                                     Operation *rightUse) {
  auto owner = [](Operation *use) -> Operation * {
    return !use                              ? nullptr
           : llvm::isa<nodal::ModuleOp>(use) ? use
                                             : use->getParentOfType<nodal::ModuleOp>();
  };
  Operation *module = owner(leftUse);
  if (!module || owner(rightUse) != module)
    return false;
  auto context = [&](Operation *use,
                     std::optional<ParameterIntegerEnvironment> &result) -> LogicalResult {
    if (use == module || use->getParentOp() == module)
      return success();
    auto contexts = digitalIntegerContextsAtUse(use);
    if (failed(contexts) || contexts->size() != 1)
      return failure();
    result = contexts->front();
    return success();
  };
  std::optional<ParameterIntegerEnvironment> leftContext, rightContext;
  if (failed(context(leftUse, leftContext)) || failed(context(rightUse, rightContext)))
    return false;
  return equivalentDigitalStorageTypesAcross(left, leftContext ? &*leftContext : nullptr, right,
                                             rightContext ? &*rightContext : nullptr, module);
}
namespace {
FailureOr<SmallVector<std::pair<Value, InstantiatedIntegerExpression>, 2>>
selectedTypePrefix(Operation *selection, Operation *instance) {
  SmallVector<std::pair<Value, InstantiatedIntegerExpression>, 2> result;
  if (!selection)
    return result;
  if (!isa<InstancePortOp, InterfaceBoundaryOp>(selection) || selection->getNumOperands() != 2)
    return failure();
  auto member = selection->getAttrOfType<StringAttr>("member");
  auto proof = member
                   ? inspectGeneratedSelection(selection->getOperand(0), selection->getOperand(1),
                                               member.getValue(), selection)
                   : FailureOr<GeneratedSelectionProof>(failure());
  if (failed(proof))
    return failure();
  auto target = inspectGeneratedExportSymbol(proof->exportOperation);
  if (failed(target) || (instance ? *target != instance : !isa<InterfaceInstanceOp>(*target)))
    return failure();
  for (const auto &entry : proof->selections)
    result.emplace_back(entry.family->getRegion(0).front().getArgument(0), entry.coordinate);
  return result;
}
} // namespace
bool equivalentSelectedDigitalStorageTypesAcross(Operation *leftSelection, Operation *leftInstance,
                                                 Type left, Operation *rightSelection,
                                                 Operation *rightInstance, Type right) {
  Operation *anchor = leftSelection ? leftSelection : rightSelection;
  Operation *parent = anchor ? anchor->getParentOfType<nodal::ModuleOp>() : nullptr;
  if (!parent || (leftSelection && leftSelection->getParentOfType<nodal::ModuleOp>() != parent) ||
      (rightSelection && rightSelection->getParentOfType<nodal::ModuleOp>() != parent))
    return false;
  auto owner = [&](Operation *instance) -> Operation * {
    return !instance ? parent
           : instance->getParentOfType<nodal::ModuleOp>() == parent &&
                   succeeded(verifyHierarchyInstanceOperation(instance))
               ? resolveInstanceModuleDefinition(instance)
               : nullptr;
  };
  auto a = selectedTypePrefix(leftSelection, leftInstance);
  auto b = selectedTypePrefix(rightSelection, rightInstance);
  auto leftOwner = owner(leftInstance), rightOwner = owner(rightInstance);
  return leftOwner && rightOwner && succeeded(a) && succeeded(b) &&
         ModuleTypeVerifier(leftOwner).equivalentInstanceStorageTypes(
             left, leftInstance, right, rightOwner, rightInstance, *a, *b);
}
bool equivalentSelectedDigitalStorageTypes(Operation *selection, Operation *instance, Type formal,
                                           Type actual) {
  return equivalentSelectedDigitalStorageTypesAcross(selection, instance, formal, nullptr, nullptr,
                                                     actual);
}

bool equivalentSelectedDigitalStorageTypesAt(Operation *selection, Operation *formalUse,
                                             Type formal, Type actual) {
  // The direct exported Interface owns two distinct original type anchors:
  // member payloads at their declaration and outer dimensions at the endpoint.
  // Neither may acquire authority from a later selected use or another lane.
  auto boundary = inspectDigitalInterfaceBoundary(selection);
  if (failed(boundary) || boundary->instance || !boundary->selection)
    return false;
  auto definition = resolveDigitalInterfaceDefinition(boundary->endpoint);
  if (failed(definition) || (formalUse != boundary->endpoint && formalUse != *definition))
    return false;
  Operation *parent = boundary->module;
  auto prefix = selectedTypePrefix(selection, nullptr);
  if (failed(prefix))
    return false;
  auto context = [&](Operation *use,
                     std::optional<ParameterIntegerEnvironment> &result) -> LogicalResult {
    if (use->getParentOp() == parent)
      return success();
    auto contexts = digitalIntegerContextsAtUse(use);
    if (failed(contexts) || contexts->size() != 1)
      return failure();
    result = contexts->front();
    return success();
  };
  std::optional<ParameterIntegerEnvironment> formalContext, actualContext;
  if (failed(context(formalUse, formalContext)) || failed(context(selection, actualContext)))
    return false;
  return ModuleTypeVerifier(parent, formalContext ? &*formalContext : nullptr)
      .equivalentInstanceStorageTypes(formal, nullptr, actual, parent, nullptr, *prefix, {},
                                      actualContext ? &*actualContext : nullptr);
}

bool equivalentGeneratedInstanceDigitalStorageTypes(Operation *instance, Type formal, Type actual,
                                                    Operation *use) {
  Operation *definition = instance ? resolveInstanceModuleDefinition(instance) : nullptr;
  return definition && ModuleTypeVerifier(definition)
                           .equivalentGeneratedInstanceStorageTypes(instance, formal, actual, use);
}
bool equivalentInstanceDigitalStorageTypes(Operation *instance, Type formal, Type actual) {
  return instance &&
         equivalentDigitalStorageTypesAcrossInstances(instance->getParentOfType<nodal::ModuleOp>(),
                                                      formal, instance, actual, nullptr);
}
bool equivalentDigitalStorageTypesAcrossInstances(Operation *parent, Type left,
                                                  Operation *leftInstance, Type right,
                                                  Operation *rightInstance) {
  if (!isa_and_nonnull<nodal::ModuleOp>(parent))
    return false;
  auto owner = [&](Operation *instance) -> Operation * {
    if (!instance)
      return parent;
    return instance->getParentOfType<nodal::ModuleOp>() == parent &&
                   succeeded(verifyHierarchyInstanceOperation(instance))
               ? resolveInstanceModuleDefinition(instance)
               : nullptr;
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
  if (llvm::isa<InterfaceRoleOp, InterfaceMemberOp>(operation) &&
      hasDigitalInterfaceContract(operation)) {
    Operation *definition = operation->getParentOp();
    if (failed(verifyDigitalInterfaceOperation(operation)))
      return failure();
    if (definition->getParentOp() != module) {
      auto contexts = digitalIntegerContextsAtUse(definition);
      if (failed(contexts) || contexts->empty())
        return failure();
      for (const auto &context : *contexts)
        if (failed(ModuleTypeVerifier(module, &context).verify(operation)))
          return failure();
      return success();
    }
    // A Module definition never inherits a regional endpoint's type context.
    return ModuleTypeVerifier(module).verify(operation);
  }
  if (llvm::isa<InterfaceOp>(operation) && hasDigitalInterfaceContract(operation)) {
    if (failed(verifyDigitalInterfaceOperation(operation)))
      return failure();
    for (Region &region : operation->getRegions())
      for (Block &block : region)
        for (Operation &child : block)
          if (failed(verifyDigitalTypeOperation(&child)))
            return failure();
  }
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
    if (failed((isDigitalRegionOwner(operation->getParentOp()) ||
                llvm::isa<InterfaceOp, InterfaceRoleOp, InterfaceMemberOp>(operation))
                   ? verifyDigitalTypeOperation(operation)
                   : verifier.verify(operation)))
      return WalkResult::interrupt();
    if (llvm::isa<InterfaceOp>(operation) && hasDigitalInterfaceContract(operation))
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
    return WalkResult::advance();
  });
  return failure(result.wasInterrupted());
}

FailureOr<DigitalStorageTypeFacts>
inspectDigitalStorageType(Type type, Operation *use,
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
    std::optional<int64_t> maximum;
    for (const auto &scoped : *contexts) {
      auto bits = inspectDigitalStorageType(type, use, &scoped);
      if (failed(bits))
        return failure();
      if (bits->maximumBits)
        maximum = std::max(maximum.value_or(0), *bits->maximumBits);
    }
    return DigitalStorageTypeFacts{maximum};
  }
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return failure();
  return ModuleTypeVerifier(module, environment).storageTypeFacts(type, use);
}
FailureOr<DigitalStorageTypeFacts> inspectGeneratedInstanceDigitalStorageType(Type formal,
                                                                              Operation *instance) {
  if (!instance || !isa_and_nonnull<GenerateOp>(instance->getParentOp()) ||
      failed(verifyHierarchyInstanceOperation(instance)))
    return failure();
  Operation *definition = resolveInstanceModuleDefinition(instance);
  if (!definition || failed(ModuleTypeVerifier(definition).storageMaximumBits(formal, definition)))
    return failure();
  return ModuleTypeVerifier(definition, nullptr, instance).storageTypeFacts(formal, instance);
}

FailureOr<int64_t> inferDigitalStorageMaximumBits(Type type, Operation *use,
                                                  const ParameterIntegerEnvironment *environment) {
  auto facts = inspectDigitalStorageType(type, use, environment);
  return succeeded(facts) && facts->maximumBits ? FailureOr<int64_t>(*facts->maximumBits)
                                                : FailureOr<int64_t>(failure());
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
