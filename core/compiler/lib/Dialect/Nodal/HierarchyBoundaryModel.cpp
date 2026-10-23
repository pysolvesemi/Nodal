#include "nodal/Dialect/Nodal/HierarchyBoundaryModel.h"

#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/DenseMap.h"

#include <optional>

using namespace mlir;
namespace nodal {
namespace {
class BoundaryQuery {
public:
  BoundaryQuery(Type type, Operation *owner, Operation *instance, unsigned &work,
                Operation *actualUse = nullptr)
      : result{type, owner, instance, {}}, work(work), actualUse(actualUse ? actualUse : owner) {}
  FailureOr<HierarchyBoundaryType> run() {
    if (!isa_and_nonnull<nodal::ModuleOp>(result.declarationOwner) ||
        (result.instance &&
         (!isa_and_nonnull<nodal::ModuleOp, GenerateOp>(result.instance->getParentOp()) ||
          failed(verifyHierarchyInstanceOperation(result.instance)) ||
          resolveInstanceModuleDefinition(result.instance) != result.declarationOwner)) ||
        failed(inferDigitalBitPatternMaximumBits(result.type, actualUse)))
      return failure();
    if (actualUse != result.declarationOwner &&
        actualUse->getParentOp() != result.declarationOwner) {
      auto contexts = digitalIntegerContextsAtUse(actualUse);
      if (failed(contexts) || contexts->size() != 1)
        return failure();
      context = contexts->front();
    }
    if (failed(visit(result.type, 1)))
      return failure();
    return std::move(result);
  }

private:
  bool generatedInstance() const {
    return result.instance && isa<GenerateOp>(result.instance->getParentOp());
  }
  LogicalResult charge(unsigned amount = 1) {
    if (work > digitalTypeMaximumNodes || amount > digitalTypeMaximumNodes - work)
      return failure();
    work += amount;
    return success();
  }
  FailureOr<InstantiatedIntegerExpression> extent(StringRef token) {
    if (!result.instance && isGeneratedTypeUse(actualUse))
      return resolveGeneratedTypeDimensionExpression(actualUse, token);
    auto dimension = resolveStaticShapeDimension(result.declarationOwner, token, "shape",
                                                 context ? &*context : nullptr);
    if (failed(dimension))
      return failure();
    if (dimension->parameter && generatedInstance())
      return resolveGeneratedInstanceIntegerParameter(result.instance, dimension->parameter);
    if (dimension->expression && generatedInstance())
      return resolveGeneratedInstanceIntegerExpression(result.instance, dimension->expression);
    if (dimension->parameter)
      return result.instance
                 ? resolveInstanceIntegerParameter(result.instance, dimension->parameter)
             : context
                 ? resolveOwnedIntegerParameter(result.declarationOwner, dimension->parameter,
                                                *context)
                 : resolveOwnedIntegerParameter(result.declarationOwner, dimension->parameter);
    if (dimension->expression)
      return result.instance
                 ? resolveInstanceIntegerExpression(result.instance, dimension->expression)
             : context
                 ? resolveOwnedIntegerExpression(result.declarationOwner, dimension->expression,
                                                 *context)
                 : resolveOwnedIntegerExpression(result.declarationOwner, dimension->expression);
    return makeLiteralIntegerExpression(dimension->literal);
  }
  LogicalResult visit(Type type, unsigned depth) {
    if (depth > digitalTypeMaximumDepth || failed(charge()))
      return failure();
    if (auto shaped = dyn_cast<nodal::ShapedType>(type)) {
      HierarchyBoundaryDimensions group;
      group.fieldDepth = path.size();
      SmallVector<StringRef, 4> tokens;
      shaped.getDimensions().split(tokens, ',');
      for (StringRef token : tokens) {
        if (failed(charge()))
          return failure();
        auto expression = extent(token);
        if (failed(expression))
          return failure();
        group.extents.push_back(*expression);
      }
      dimensions.push_back(std::move(group));
      auto status = visit(shaped.getElementType(), depth + 1);
      dimensions.pop_back();
      return status;
    }
    if (auto structure = dyn_cast<StructType>(type)) {
      for (Attribute raw : structure.getFields()) {
        auto field = cast<DictionaryAttr>(raw);
        path.push_back({StringAttr::get(type.getContext(), structure.getName()),
                        field.getAs<StringAttr>("name")});
        auto status = visit(field.getAs<TypeAttr>("type").getValue(), depth + 1);
        path.pop_back();
        if (failed(status))
          return failure();
      }
      return success();
    }
    unsigned copied = path.size() + dimensions.size();
    for (const auto &group : dimensions)
      copied += group.extents.size();
    if (failed(charge(copied)))
      return failure();
    auto width = generatedInstance()
                     ? inferGeneratedInstanceDigitalWidthExpression(type, result.instance)
                 : result.instance ? inferInstanceDigitalWidthExpression(type, result.instance)
                                   : inferDigitalWidthExpression(type, result.declarationOwner,
                                                                 context ? &*context : nullptr);
    if (failed(width))
      return failure();
    Operation *enumDefinition = nullptr;
    if (auto enumeration = dyn_cast<EnumType>(type)) {
      auto definition = inspectCanonicalEnumDefinition(
          result.declarationOwner,
          FlatSymbolRefAttr::get(type.getContext(), enumeration.getSymbol()));
      if (failed(definition))
        return failure();
      enumDefinition = definition->operation;
    }
    result.leaves.push_back({type, path, dimensions, *width, enumDefinition});
    return success();
  }
  HierarchyBoundaryType result;
  unsigned &work;
  Operation *actualUse;
  std::optional<ParameterIntegerEnvironment> context;
  SmallVector<HierarchyBoundaryField, 4> path;
  SmallVector<HierarchyBoundaryDimensions, 2> dimensions;
};
} // namespace
FailureOr<HierarchyBoundaryType>
inspectHierarchyBoundaryType(Type type, Operation *declarationOwner, Operation *instance) {
  unsigned work = 0;
  return BoundaryQuery(type, declarationOwner, instance, work).run();
}
FailureOr<SmallVector<DigitalInterfaceBoundaryType, 4>>
inspectDigitalInterfaceBoundaryTypes(Operation *module,
                                     ArrayRef<std::pair<Operation *, Operation *>> seeds) {
  if (!isa_and_nonnull<nodal::ModuleOp>(module) || seeds.size() > digitalTypeMaximumNodes)
    return failure();
  unsigned work = 0;
  SmallVector<DigitalInterfaceEndpoint> endpoints;
  if (failed(collectDigitalInterfaceEndpoints(module, endpoints, nullptr, work)))
    return failure();
  DenseMap<Operation *, const DigitalInterfaceEndpoint *> inventory;
  DenseMap<std::pair<Operation *, Operation *>, const DigitalInterfaceLeaf *> members;
  for (const auto &endpoint : endpoints) {
    if (++work > digitalTypeMaximumNodes)
      return failure();
    if (!endpoint.instance && !endpoint.selection && endpoint.operation == endpoint.declaration) {
      inventory.try_emplace(endpoint.operation, &endpoint);
      for (const auto &leaf : endpoint.leaves) {
        if (++work > digitalTypeMaximumNodes)
          return failure();
        members.try_emplace(std::make_pair(endpoint.operation, leaf.declaration), &leaf);
      }
    }
  }
  SmallVector<DigitalInterfaceBoundaryType, 4> result;
  DenseMap<std::pair<Operation *, Operation *>, unsigned> memo;
  DenseMap<Operation *, SmallVector<InstantiatedIntegerExpression, 2>> outerMemo;
  for (auto seed : seeds) {
    if (++work > digitalTypeMaximumNodes)
      return failure();
    auto known = memo.find(seed);
    if (known != memo.end()) {
      const auto &previous = result[known->second];
      for (const auto &leaf : previous.payload.leaves) {
        work += 1 + leaf.path.size() + leaf.dimensions.size();
        for (const auto &dimensions : leaf.dimensions)
          work += dimensions.extents.size();
      }
      work += previous.outerDimensions.size();
      if (work > digitalTypeMaximumNodes)
        return failure();
      result.push_back(previous);
      continue;
    }
    auto found = inventory.find(seed.first);
    if (found == inventory.end() || !seed.second ||
        seed.second->getParentOp() != found->second->definition)
      return failure();
    const auto &endpoint = *found->second;
    auto member = members.find(seed);
    if (member == members.end())
      return failure();
    auto payload =
        BoundaryQuery(member->second->type, module, nullptr, work, endpoint.definition).run();
    if (failed(payload))
      return failure();
    auto outer = outerMemo.find(endpoint.operation);
    if (outer == outerMemo.end()) {
      SmallVector<InstantiatedIntegerExpression, 2> dimensions;
      for (Attribute raw : endpoint.dimensions) {
        if (++work > digitalTypeMaximumNodes)
          return failure();
        auto token = dyn_cast<StringAttr>(raw);
        if (!token)
          return failure();
        FailureOr<InstantiatedIntegerExpression> expression = failure();
        if (isGeneratedTypeUse(endpoint.operation))
          expression =
              resolveGeneratedTypeDimensionExpression(endpoint.operation, token.getValue());
        else {
          auto dimension = resolveStaticShapeDimension(module, token.getValue());
          if (failed(dimension))
            return failure();
          expression =
              dimension->parameter    ? resolveOwnedIntegerParameter(module, dimension->parameter)
              : dimension->expression ? resolveOwnedIntegerExpression(module, dimension->expression)
                                      : makeLiteralIntegerExpression(dimension->literal);
        }
        if (failed(expression))
          return failure();
        dimensions.push_back(*expression);
      }
      outer = outerMemo.try_emplace(endpoint.operation, std::move(dimensions)).first;
    }
    work += outer->second.size();
    if (work > digitalTypeMaximumNodes)
      return failure();
    memo.try_emplace(seed, result.size());
    result.push_back(
        {endpoint.operation, endpoint.definition, seed.second, std::move(*payload), outer->second});
  }
  return result;
}
} // namespace nodal
