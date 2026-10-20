#include "nodal/Dialect/Nodal/HierarchyBoundaryModel.h"

#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

using namespace mlir;
namespace nodal {
namespace {
class BoundaryQuery {
public:
  BoundaryQuery(Type type, Operation *owner, Operation *instance)
      : result{type, owner, instance, {}} {}
  FailureOr<HierarchyBoundaryType> run() {
    if (!isa_and_nonnull<nodal::ModuleOp>(result.declarationOwner) ||
        (result.instance &&
         (!isa_and_nonnull<nodal::ModuleOp>(result.instance->getParentOp()) ||
          resolveInstanceModuleDefinition(result.instance) != result.declarationOwner)) ||
        failed(inferDigitalBitPatternMaximumBits(result.type, result.declarationOwner)) ||
        failed(visit(result.type, 1)))
      return failure();
    return std::move(result);
  }

private:
  LogicalResult charge(unsigned amount = 1) {
    if (amount > digitalTypeMaximumNodes - work)
      return failure();
    work += amount;
    return success();
  }
  FailureOr<InstantiatedIntegerExpression> extent(StringRef token) {
    auto dimension = resolveStaticShapeDimension(result.declarationOwner, token);
    if (failed(dimension))
      return failure();
    if (dimension->parameter)
      return result.instance
                 ? resolveInstanceIntegerParameter(result.instance, dimension->parameter)
                 : resolveOwnedIntegerParameter(result.declarationOwner, dimension->parameter);
    if (dimension->expression)
      return result.instance
                 ? resolveInstanceIntegerExpression(result.instance, dimension->expression)
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
    auto width = result.instance ? inferInstanceDigitalWidthExpression(type, result.instance)
                                 : inferDigitalWidthExpression(type, result.declarationOwner);
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
  unsigned work = 0;
  SmallVector<HierarchyBoundaryField, 4> path;
  SmallVector<HierarchyBoundaryDimensions, 2> dimensions;
};
} // namespace
FailureOr<HierarchyBoundaryType>
inspectHierarchyBoundaryType(Type type, Operation *declarationOwner, Operation *instance) {
  return BoundaryQuery(type, declarationOwner, instance).run();
}
} // namespace nodal
