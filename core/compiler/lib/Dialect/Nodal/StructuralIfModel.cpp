#include "nodal/Dialect/Nodal/StructuralIfModel.h"

#include "mlir/IR/Verifier.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Dialect/Nodal/StaticPredicateModel.h"

#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"

#include <functional>
using namespace mlir;
namespace nodal {
namespace {
StringRef text(Operation *op, StringRef key) {
  auto a = op ? op->getAttrOfType<StringAttr>(key) : StringAttr();
  return a ? a.getValue() : StringRef();
}
StringRef path(Operation *op) {
  auto m = op ? op->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
  auto a = m ? m.getAs<StringAttr>("semantic_path") : StringAttr();
  return a ? a.getValue() : StringRef();
}
bool canonical(StringRef id) {
  return !id.empty() && id.size() <= 1024 && id == id.trim() &&
         llvm::none_of(id, [](unsigned char c) { return c <= 32 || c == 127; });
}
LogicalResult reject(Operation *op, StringRef reason) {
  return op->emitOpError("NODAL-DIGITAL-055-014: ") << reason;
}
bool containsGeneratedAtom(const InstantiatedIntegerExpression &expression) {
  SmallVector<std::shared_ptr<const InstantiatedIntegerNode>, 8> pending{expression.root};
  llvm::DenseSet<const InstantiatedIntegerNode *> seen;
  while (!pending.empty()) {
    auto node = pending.pop_back_val();
    if (!node || !seen.insert(node.get()).second)
      continue;
    if (seen.size() > 65536)
      return true;
    if (node->kind == InstantiatedIntegerNode::Kind::OpaqueValue) {
      auto arg = llvm::dyn_cast<BlockArgument>(node->identity);
      if (arg && llvm::isa<GenerateOp>(arg.getOwner()->getParentOp()))
        return true;
    }
    pending.append(node->operands.begin(), node->operands.end());
  }
  return false;
}
} // namespace
FailureOr<StructuralIfHeader> inspectStructuralIfHeader(Operation *op) {
  if (!llvm::isa_and_nonnull<StructuralIfOp>(op) || op->getNumRegions() != 2 ||
      op->getNumResults() > 1 || op->getNumOperands() > 8193 || op->getNumSuccessors() ||
      text(op, "contract_version") != "structural-if-v1" || !canonical(text(op, "region_id")) ||
      path(op) != text(op, "region_id"))
    return failure();
  for (NamedAttribute a : op->getAttrs())
    if (!llvm::is_contained(
            ArrayRef<StringRef>{"operandSegmentSizes", "contract_version", "region_id", "metadata"},
            a.getName().getValue()))
      return failure();
  auto sizes = op->getAttrOfType<DenseI32ArrayAttr>("operandSegmentSizes");
  if (!sizes || sizes.size() != 3 || sizes[0] != 1 || sizes[1] < 0 || sizes[2] < 0 ||
      uint64_t(1) + uint64_t(sizes[1]) + uint64_t(sizes[2]) != op->getNumOperands() ||
      !llvm::isa<StaticPredicateType>(op->getOperand(0).getType()))
    return failure();
  Operation *module = op->getParentOfType<ModuleOp>();
  if (!module ||
      (!llvm::isa<ModuleOp>(op->getParentOp()) && !isDigitalRegionOwner(op->getParentOp())))
    return failure();
  unsigned depth = 0;
  for (Operation *parent = op->getParentOp(); parent && parent != module;
       parent = parent->getParentOp())
    if (!isDigitalRegionOwner(parent) || ++depth >= 128)
      return failure();
  StringRef modulePath = path(module);
  if (modulePath.empty())
    modulePath = text(module, "sym_name");
  if (!text(op, "region_id").starts_with((modulePath + ".").str()))
    return failure();
  StructuralIfHeader h;
  h.operation = op;
  h.module = module;
  h.captureOffsets[0] = 1;
  h.captureOffsets[1] = 1 + sizes[1];
  for (unsigned arm = 0; arm < 2; ++arm) {
    Region &region = op->getRegion(arm);
    if (!region.hasOneBlock())
      return failure();
    h.bodies[arm] = &region.front();
    h.captureCounts[arm] = sizes[arm + 1];
    if (h.bodies[arm]->getNumArguments() != h.captureCounts[arm])
      return failure();
  }
  return h;
}
bool areOppositeStructuralArms(Operation *left, Operation *right) {
  if (!left || !right || left == right ||
      left->getParentOfType<ModuleOp>() != right->getParentOfType<ModuleOp>())
    return false;
  llvm::DenseMap<Operation *, unsigned> arms;
  unsigned depth = 0;
  for (Operation *at = left; at && !llvm::isa<ModuleOp>(at); at = at->getParentOp()) {
    if (++depth > 128)
      return false;
    Operation *parent = at->getParentOp();
    if (!llvm::isa_and_nonnull<StructuralIfOp>(parent))
      continue;
    auto header = inspectStructuralIfHeader(parent);
    if (failed(header))
      return false;
    arms.try_emplace(parent, at->getParentRegion()->getRegionNumber());
  }
  depth = 0;
  for (Operation *at = right; at && !llvm::isa<ModuleOp>(at); at = at->getParentOp()) {
    if (++depth > 128)
      return false;
    Operation *parent = at->getParentOp();
    auto found = arms.find(parent);
    if (found != arms.end() && found->second != at->getParentRegion()->getRegionNumber())
      return true;
  }
  return false;
}

FailureOr<Value> structuralIfCaptureSource(BlockArgument arg) {
  auto h = inspectStructuralIfHeader(arg.getOwner()->getParentOp());
  if (failed(h))
    return failure();
  for (unsigned arm = 0; arm < 2; ++arm)
    if (arg.getOwner() == h->bodies[arm] && arg.getArgNumber() < h->captureCounts[arm])
      return h->operation->getOperand(h->captureOffsets[arm] + arg.getArgNumber());
  return failure();
}
bool isStructuralIfFamilyCapture(OpOperand &operand) {
  Operation *op = operand.getOwner();
  Value source = operand.get();
  if (!llvm::isa<GeneratedFamilyType>(source.getType()))
    return false;
  auto h = inspectStructuralIfHeader(op);
  if (failed(h) || failed(verifyDigitalValueScope(source, op)))
    return false;
  unsigned depth = 0;
  for (Operation *scope = op; scope != h->module; scope = scope->getParentOp())
    if (!scope || ++depth > 128 || !llvm::isa<StructuralIfOp, GenerateOp>(scope))
      return false;
  unsigned index = operand.getOperandNumber();
  for (unsigned arm = 0; arm < 2; ++arm)
    if (index >= h->captureOffsets[arm] && index - h->captureOffsets[arm] < h->captureCounts[arm])
      return h->bodies[arm]->getArgument(index - h->captureOffsets[arm]).getType() ==
             source.getType();
  return false;
}
namespace detail {
class StructuralIfIntegerEnvironmentAdmission {
public:
  static FailureOr<ParameterIntegerEnvironment> create(const StructuralIfHeader &h, unsigned arm,
                                                       Operation *use,
                                                       const ParameterIntegerEnvironment *outer) {
    if (arm >= 2 || !use || use->getBlock() != h.bodies[arm])
      return failure();
    SmallVector<ParameterIntegerBinding, 8> bindings;
    for (unsigned i = 0; i < h.captureCounts[arm]; ++i) {
      auto arg = h.bodies[arm]->getArgument(i);
      Value source = h.operation->getOperand(h.captureOffsets[arm] + i);
      if (!arg.getType().isSignlessInteger(64))
        continue;
      if (source.getType() != arg.getType() || failed(verifyDigitalValueScope(source, h.operation)))
        return failure();
      // A procedural selector remains a real capture, without acquiring a
      // static/type binding. Shape and iteration consumers recheck its actual
      // induction ancestry at each read; predicates never use this admission.
      if (succeeded(resolveDigitalIterationIndex(source, h.operation)))
        continue;
      auto expression =
          outer ? resolveOwnedIntegerExpression(h.module, source, *outer,
                                                ParameterIntegerMode::MathematicalSigned64)
                : resolveOwnedIntegerExpression(h.module, source,
                                                ParameterIntegerMode::MathematicalSigned64);
      if (succeeded(expression) && !containsGeneratedAtom(*expression)) {
        bindings.push_back({arg, *expression, source});
      } else {
        auto structural = resolveStructuralIntegerExpression(source, h.operation);
        if (failed(structural))
          return failure();
        // Index facts belong only to predicate/Shape use admission, never types.
        if (structural->expression && !containsGeneratedAtom(*structural->expression))
          bindings.push_back({arg, *structural->expression, source});
      }
    }
    return ParameterIntegerEnvironment::create(
        h.module, h.bodies[arm], use, outer ? outer->instance() : nullptr, bindings, {}, outer);
  }
};
} // namespace detail
FailureOr<SmallVector<ParameterIntegerEnvironment, 4>>
structuralIfIntegerContextsAtUse(Operation *use) {
  auto h = use ? inspectStructuralIfHeader(use->getParentOp())
               : FailureOr<StructuralIfHeader>(failure());
  if (failed(h))
    return failure();
  unsigned arm = use->getBlock() == h->bodies[0] ? 0 : 1;
  SmallVector<ParameterIntegerEnvironment, 4> result;
  if (isDigitalRegionOwner(h->operation->getParentOp())) {
    auto parents = digitalIntegerContextsAtUse(h->operation);
    if (failed(parents))
      return failure();
    for (const auto &parent : *parents) {
      auto context = detail::StructuralIfIntegerEnvironmentAdmission::create(*h, arm, use, &parent);
      if (failed(context) || result.size() >= 65536)
        return failure();
      result.push_back(*context);
    }
  } else {
    auto context = detail::StructuralIfIntegerEnvironmentAdmission::create(*h, arm, use, nullptr);
    if (failed(context))
      return failure();
    result.push_back(*context);
  }
  return result;
}
LogicalResult verifyStructuralYieldOperation(Operation *op) {
  auto h =
      op ? inspectStructuralIfHeader(op->getParentOp()) : FailureOr<StructuralIfHeader>(failure());
  if (failed(h) || !llvm::isa<StructuralYieldOp>(op) || op->getNumRegions() ||
      op->getNumResults() || op->getNumSuccessors() ||
      op->getNumOperands() != h->operation->getNumResults() || op->getBlock()->empty() ||
      &op->getBlock()->back() != op ||
      path(op) != (digitalRegionSemanticPath(op->getBlock()) + ".yield"))
    return reject(op, "arm requires a unique final zero/one structural_yield with actual identity");
  for (NamedAttribute a : op->getAttrs())
    if (a.getName() != "metadata")
      return reject(op, "yield cannot carry caller-asserted proof or effects");
  for (Value value : op->getOperands())
    if (failed(verifyDigitalValueScope(value, op)))
      return reject(op, "yield must retain actual branch-local ownership");
  return success();
}
namespace {
struct Budget {
  unsigned work = 0;
};
LogicalResult verifyIf(Operation *, const ParameterIntegerEnvironment *, Budget &, unsigned);
LogicalResult verifyGenerateBody(Operation *, Budget &, unsigned);
LogicalResult verifyBody(Operation *op, const ParameterIntegerEnvironment &environment, bool pure,
                         Budget &budget, unsigned depth) {
  if (depth >= 128 || ++budget.work > 65536)
    return reject(op, "structural body exceeds its finite proof budget");
  auto at = environment.atUse(op);
  if (failed(at) || op->hasAttr("unit") || failed(verifyDigitalTypeOperation(op, &*at)) ||
      failed(verifyEnumTypeOperation(op, &*at)))
    return failure();
  for (Value operand : op->getOperands())
    if (failed(verifyDigitalValueScope(operand, op)))
      return reject(op, "body operands require actual local SSA or explicit captures");
  auto family = op->getParentOfType<GenerateOp>();
  const bool generatedStorage = family && family->getNumResults() == 1;
  if (!pure && !op->getParentOfType<HardwareLoopOp>()) {
    if (llvm::isa<BehavioralIfOp>(op))
      return verifyBehavioralIfOperation(op);
    if (llvm::isa<SequentialUpdateOp>(op))
      return verifySequentialUpdate(op);
    if (llvm::isa<SequentialSelectOp>(op))
      return verifySequentialSelect(op);
  }
  if (!pure && !op->getParentOfType<HardwareLoopOp>() && llvm::isa<GenerateOp>(op) &&
      op->getNumResults() == 1 &&
      ((generatedStorage && op->getParentOp() == family) ||
       llvm::isa_and_nonnull<StructuralIfOp>(op->getParentOp())))
    return verifyGenerateBody(op, budget, depth + 1);
  if (!pure && !op->getParentOfType<HardwareLoopOp>() && llvm::isa<GeneratedMemberOp>(op))
    return success(succeeded(inspectGeneratedMember(op)));
  if (!pure && generatedStorage && op->getParentOp() == family) {
    if (llvm::isa<InstanceOp>(op))
      return verifyHierarchyInstanceOperation(op);
    if (llvm::isa<ParameterOverrideOp>(op)) {
      auto boundary = resolveHierarchyInstance(op, op->getAttr("instance"));
      auto reference = op->getAttrOfType<FlatSymbolRefAttr>("parameter");
      Operation *parameter = nullptr;
      if (succeeded(boundary) && reference)
        for (Operation &candidate : boundary->module->getRegion(0).front())
          if (llvm::isa<ParameterOp>(candidate) && text(&candidate, "sym_name") == reference.getValue())
            parameter = &candidate;
      auto declared = parameter ? parameter->getAttrOfType<TypeAttr>("type") : TypeAttr();
      for (NamedAttribute attribute : op->getAttrs())
        if (!llvm::is_contained(ArrayRef<StringRef>{"instance", "parameter", "generated_owner",
                                                   "generated_induction", "metadata"},
                                attribute.getName().getValue()))
          return reject(op, "generated parameter override carries an unsupported field");
      if (failed(boundary) || boundary->instance->getParentOp() != family || !parameter ||
          !declared || !declared.getValue().isSignlessInteger(64) || op->getNumOperands() != 1 ||
          op->getOperand(0).getType() != declared.getValue() ||
          !op->getOperand(0).getDefiningOp() ||
          op->getOperand(0).getDefiningOp()->getParentOp() != at->module() ||
          failed(mlir::verify(op, false)) ||
          failed(resolveInstanceIntegerParameter(boundary->instance, parameter)))
        return reject(op, "generated child parameter override requires exact invariant Integer formal/actual proof");
      return success();
    }
    if (llvm::isa<InstanceDomainBindingOp>(op))
      return verifyInstanceDomainBinding(op);
    if (llvm::isa<InstancePortOp>(op))
      return success(succeeded(inspectInstancePort(op)));
    if (llvm::isa<DigitalStorageOp>(op))
      return verifyDigitalStorageOperation(op);
    if (op->getName().getStringRef() == "nodal.generated_export")
      return verifyGeneratedExportOperation(op);
  }
  if (llvm::isa<StructuralIfOp>(op)) {
    if (pure && op->getNumResults() != 1)
      return reject(op, "pure callbacks require value-only structural choices");
    return verifyIf(op, &*at, budget, depth + 1);
  }
  if (llvm::isa<CollectionOp>(op))
    return verifyDigitalCollectionOperation(op, &*at);
  if (llvm::isa<StructuralYieldOp>(op))
    return verifyStructuralYieldOperation(op);
  if (llvm::isa<StaticCompareOp, StaticLogicOp>(op))
    return verifyStaticPredicateOperation(op);
  if (llvm::isa<DigitalAssignOp>(op)) {
    if (pure || (text(op, "kind") != "continuous" &&
                 !(!op->getParentOfType<HardwareLoopOp>() && text(op, "kind") == "register_next")))
      return reject(op, "this body admits no state or procedural effects");
    return verifyDigitalAssignmentOperation(op);
  }
  if (op->getNumRegions() || op->getNumSuccessors() || op->getNumResults() != 1 ||
      !op->getAttrOfType<DictionaryAttr>("metadata"))
    return reject(op, "operation is outside the bounded pure/continuous digital body profile");
  if (llvm::isa<DigitalExprOp>(op))
    return verifyDigitalValueOperation(op, &*at);
  if (llvm::isa<EnumExprOp>(op))
    return verifyEnumValueOperation(op, &*at);
  if (llvm::isa<IterationIndexOp>(op))
    return verifyDigitalIterationIndexOperation(op);
  if (llvm::isa<ShapeIndexOp>(op))
    return verifyShapeIndexMapping(op, &*at);
  if (llvm::isa<ShapeSliceOp>(op))
    return verifyShapeSliceMapping(op, &*at);
  if (llvm::isa<ShapeViewOp>(op))
    return verifyShapeViewMapping(op, &*at);
  if (llvm::isa<ConstantOp, ConstLiteralOp, ConstExprOp, ConstParameterRefOp>(op)) {
    Type type = op->getResult(0).getType();
    if (isDigitalCombinationalValueType(type, environment.module(), &*at)) {
      if (llvm::isa<ConstExprOp, ConstParameterRefOp>(op))
        return reject(op, "runtime values require closed digital producers");
      if (llvm::isa<EnumType>(type))
        return succeeded(verifyEnumLiteralOperation(op, &*at)) ? mlir::verify(op, false)
                                                               : failure();
      return verifyDigitalLiteralOperation(op, &*at);
    }
    if (!type.isSignlessInteger(64))
      return reject(op, "static producers require dimensionless i64");
    Operation *next = op->getNextNode();
    if (!next)
      return succeeded(verifyStructuralIntegerOperation(op))
                 ? mlir::verify(op, false)
                 : reject(op, "static producer has no valid owned Integer proof");
    auto after = environment.atUse(next);
    auto expression =
        succeeded(after)
            ? resolveOwnedIntegerExpression(environment.module(), op->getResult(0), *after,
                                            ParameterIntegerMode::MathematicalSigned64)
            : FailureOr<InstantiatedIntegerExpression>(failure());
    if (failed(expression) && failed(resolveStructuralIntegerExpression(op->getResult(0), next)))
      return reject(op,
                    "static arithmetic is not valid over every legal value in its actual scope");
    return mlir::verify(op, false);
  }
  return reject(op, "unsupported effect or value producer in structural body");
}
// One authored-use inventory for admission and private-candidate rewriting.
// Type-only dependencies are real captures, in the same order as SSA uses.
FailureOr<SmallVector<BlockArgument, 8>> captureUseOrder(Block *body, unsigned &work,
                                                         unsigned maximumWork) {
  SmallVector<BlockArgument, 8> result;
  llvm::DenseSet<Value> seen;
  llvm::StringMap<BlockArgument> tokens;
  for (BlockArgument argument : body->getArguments()) {
    std::string identity = digitalValueSemanticPath(argument);
    if (!identity.empty())
      tokens.try_emplace(identity, argument);
  }
  bool valid = true;
  auto mark = [&](Value value) {
    auto argument = dyn_cast<BlockArgument>(value);
    if (argument && argument.getOwner() == body && seen.insert(argument).second)
      result.push_back(argument);
  };
  auto token = [&](StringRef identity) {
    auto found = tokens.find(identity);
    if (found != tokens.end())
      mark(found->second);
  };
  std::function<void(Type, unsigned)> visitType;
  std::function<void(Attribute, unsigned)> visitAttribute;
  visitType = [&](Type type, unsigned depth) {
    if (depth >= 128 || ++work > maximumWork) {
      valid = false;
      return;
    }
    if (auto scalar = dyn_cast<ParametricIntegerType>(type))
      token(scalar.getWidth());
    if (auto shaped = dyn_cast<ShapedType>(type)) {
      SmallVector<StringRef, 4> dimensions;
      shaped.getDimensions().split(dimensions, ',');
      for (StringRef dimension : dimensions)
        token(dimension);
    }
    type.walkImmediateSubElements([&](Attribute a) { visitAttribute(a, depth + 1); },
                                  [&](Type t) { visitType(t, depth + 1); });
  };
  visitAttribute = [&](Attribute attribute, unsigned depth) {
    if (depth >= 128 || ++work > maximumWork) {
      valid = false;
      return;
    }
    attribute.walkImmediateSubElements([&](Attribute a) { visitAttribute(a, depth + 1); },
                                       [&](Type t) { visitType(t, depth + 1); });
  };
  std::function<LogicalResult(Block *, unsigned)> visitBlock =
      [&](Block *current, unsigned depth) -> LogicalResult {
    if (depth >= 128)
      return failure();
    for (Operation &child : *current) {
      if (++work > maximumWork)
        return failure();
      for (Value operand : child.getOperands()) {
        if (++work > maximumWork)
          return failure();
        mark(operand);
      }
      for (NamedAttribute attribute : child.getAttrs())
        visitAttribute(attribute.getValue(), 0);
      for (Type type : child.getOperandTypes())
        visitType(type, 0);
      for (Type type : child.getResultTypes())
        visitType(type, 0);
      if (!valid)
        return failure();
      // Generate is deliberately non-isolated. Its actual nested body can use
      // this arm's arguments directly; other regions expose explicit captures.
      if (llvm::isa<GenerateOp>(child)) {
        if (child.getNumRegions() != 1 || !llvm::hasSingleElement(child.getRegion(0)) ||
            failed(visitBlock(&child.getRegion(0).front(), depth + 1)))
          return failure();
      }
    }
    return success();
  };
  if (failed(visitBlock(body, 0)))
    return failure();
  return result;
}
LogicalResult verifyIf(Operation *op, const ParameterIntegerEnvironment *outer, Budget &budget,
                       unsigned depth) {
  if (depth >= 128 || ++budget.work > 65536)
    return reject(op, "conditional nesting/work budget exhausted");
  auto h = inspectStructuralIfHeader(op);
  if (failed(h))
    return reject(op, "invalid closed structural-if header, region identity or capture segments");
  if (failed(analyzeStaticPredicate(op->getOperand(0), op, outer)))
    return reject(op, "predicate must be an owned static parameter/actual Generate expression");
  bool pure = op->getParentOfType<CollectionOp>() != nullptr;
  if (op->getNumResults() &&
      !isDigitalCombinationalValueType(op->getResult(0).getType(), h->module, outer))
    return reject(op, "result must be one exact read-only finite digital value type");
  for (unsigned arm = 0; arm < 2; ++arm) {
    Block *body = h->bodies[arm];
    if (body->empty() || !llvm::isa<StructuralYieldOp>(body->back()))
      return reject(op, "both arms require a retained explicit structural_yield");
    auto context =
        detail::StructuralIfIntegerEnvironmentAdmission::create(*h, arm, &body->back(), outer);
    if (failed(context))
      return reject(op, "arm Integer capture substitution is not independently valid");
    llvm::DenseSet<Value> unique;
    for (unsigned i = 0; i < h->captureCounts[arm]; ++i) {
      Value source = op->getOperand(h->captureOffsets[arm] + i);
      auto arg = body->getArgument(i);
      if (!unique.insert(source).second || failed(verifyDigitalValueScope(source, op)))
        return reject(op, "captures must be unique actual dominating values in each arm");
      if (arg.getType().isSignlessInteger(64)) {
        if (source.getType() != arg.getType())
          return reject(op, "Integer capture type mismatch");
      } else if (llvm::isa<StaticPredicateType>(arg.getType())) {
        if (source.getType() != arg.getType() || failed(analyzeStaticPredicate(source, op, outer)))
          return reject(op, "predicate capture must retain its verified actual DAG");
      } else if (llvm::isa<GeneratedFamilyType>(arg.getType())) {
        if (!isStructuralIfFamilyCapture(op->getOpOperand(h->captureOffsets[arm] + i)))
          return reject(op, "family capture requires an exact typed ordinary structural slot");
      } else if (!pure && isDigitalControlBoundaryType(arg.getType())) {
        if (source.getType() != arg.getType())
          return reject(op, "control capture must preserve its exact Clock/Reset capability");
      } else if (!isDigitalCombinationalValueType(source.getType(), h->module, outer) ||
                 !isDigitalCombinationalValueType(arg.getType(), h->module, &*context) ||
                 !equivalentDigitalStorageTypesAcross(source.getType(), outer, arg.getType(),
                                                      &*context, h->module))
        return reject(op, "capture must retain exact kind, sign, width, shape and nominal ABI");
      for (Operation *user : arg.getUsers())
        if (!body->getParent()->isAncestor(user->getParentRegion()))
          return reject(op, "arm arguments cannot escape their actual branch");
    }
    unsigned captureWork = 0;
    auto order = captureUseOrder(body, captureWork, 65536);
    if (failed(order))
      return reject(op, "arm type captures exceed finite first-use inventory budget");
    for (auto [index, argument] : llvm::enumerate(*order))
      if (argument.getArgNumber() != index)
        return reject(op, "arm captures must retain deterministic first actual use order");
    for (Operation &child : *body) {
      if (failed(verifyBody(&child, *context, pure, budget, depth + 1)))
        return failure();
      for (Value value : child.getResults())
        for (Operation *user : value.getUsers())
          if (!body->getParent()->isAncestor(user->getParentRegion()))
            return reject(&child, "branch-local values cannot escape or cross alternatives");
    }
    if (order->size() != h->captureCounts[arm])
      return reject(op, "unused or forged arm capture");
    if (op->getNumResults() &&
        !equivalentDigitalStorageTypesAcross(body->back().getOperand(0).getType(), &*context,
                                             op->getResult(0).getType(), outer, h->module))
      return reject(op, "each actual yield must preserve the exact enclosing result ABI");
  }
  return success();
}
} // namespace
LogicalResult canonicalizeStructuralIfCaptures(Operation *op, unsigned &work,
                                               unsigned maximumWork) {
  auto header = inspectStructuralIfHeader(op);
  if (failed(header))
    return failure();
  SmallVector<BlockArgument, 8> orders[2];
  SmallVector<Value, 8> operands{op->getOperand(0)};
  bool changed = false;
  for (unsigned arm = 0; arm != 2; ++arm) {
    auto order = captureUseOrder(header->bodies[arm], work, maximumWork);
    if (failed(order))
      return reject(op, "normalized capture inventory exceeds finite work budget");
    orders[arm] = std::move(*order);
    changed |= orders[arm].size() != header->captureCounts[arm];
    for (auto [index, argument] : llvm::enumerate(orders[arm])) {
      changed |= argument.getArgNumber() != index;
      operands.push_back(op->getOperand(header->captureOffsets[arm] + argument.getArgNumber()));
    }
  }
  if (!changed)
    return success();
  // All facts are collected before changing either arm. The caller retains the
  // encompassing private transaction and re-verifies before publication.
  for (unsigned arm = 0; arm != 2; ++arm) {
    Block *body = header->bodies[arm];
    for (BlockArgument old : orders[arm]) {
      BlockArgument replacement = body->addArgument(old.getType(), old.getLoc());
      old.replaceAllUsesWith(replacement);
    }
    body->eraseArguments(0, header->captureCounts[arm]);
  }
  op->setOperands(operands);
  op->setAttr("operandSegmentSizes",
              DenseI32ArrayAttr::get(op->getContext(), {1, static_cast<int32_t>(orders[0].size()),
                                                        static_cast<int32_t>(orders[1].size())}));
  return success();
}
LogicalResult verifyStructuralIfOperation(Operation *op, const ParameterIntegerEnvironment *outer) {
  if (!outer && op && isDigitalRegionOwner(op->getParentOp())) {
    auto contexts = digitalIntegerContextsAtUse(op);
    if (failed(contexts))
      return failure();
    Budget budget;
    for (const auto &context : *contexts)
      if (failed(verifyIf(op, &context, budget, 0)))
        return failure();
    return success();
  }
  Budget budget;
  return verifyIf(op, outer, budget, 0);
}
LogicalResult verifyStructuralIfModel(Operation *module) {
  if (!llvm::isa_and_nonnull<ModuleOp>(module))
    return failure();
  SmallVector<std::pair<Operation *, unsigned>, 8> pending{{module, 0}};
  llvm::StringSet<> identities;
  unsigned count = 0;
  while (!pending.empty()) {
    auto [op, depth] = pending.pop_back_val();
    if (depth >= 128 || ++count > 65536)
      return reject(op, "structural inspection budget exhausted");
    if (op != module && llvm::isa<ModuleOp>(op))
      continue;
    if (llvm::isa<StructuralIfOp>(op)) {
      if (!identities.insert(text(op, "region_id")).second)
        return reject(op, "conditional identity is not unique");
      if (failed(verifyStructuralIfOperation(op)))
        return failure();
    }
    if (llvm::isa<StaticCompareOp, StaticLogicOp>(op) && failed(verifyStaticPredicateOperation(op)))
      return failure();
    for (Region &region : op->getRegions())
      for (Block &block : region)
        for (Operation &child : block)
          pending.emplace_back(&child, depth + 1);
  }
  return success();
}
namespace {
LogicalResult verifyGenerateBody(Operation *op, Budget &budget, unsigned depth) {
  if (depth >= 128 || ++budget.work > 65536)
    return reject(op, "structural body exceeds its finite proof budget");
  if (!llvm::isa_and_nonnull<GenerateOp>(op))
    return failure();
  // Legacy attribute-bound Generate has its existing target-neutral contract.
  // Only the real SSA-domain form participates in this new digital admission.
  if (!isDigitalRegionOwner(op))
    return success();
  bool digital = op->getNumResults() == 1;
  for (Operation &child : op->getRegion(0).front())
    digital |= llvm::isa<StructuralIfOp, StaticCompareOp, StaticLogicOp, DigitalAssignOp,
                         DigitalExprOp, CollectionOp>(child);
  if (!digital)
    return success(); // Preserve the separately owned analog/conservative profile.
  auto domain = resolveGeneratedDomain(op);
  if (failed(domain))
    return failure();
  for (Operation &child : op->getRegion(0).front()) {
    auto context = digitalGenerateIntegerContextAtUse(&child);
    if (failed(context) || failed(verifyBody(&child, *context, false, budget, depth)))
      return failure();
  }
  return success();
}
} // namespace
LogicalResult verifyDigitalGenerateBody(Operation *op) {
  Budget budget;
  return verifyGenerateBody(op, budget, 0);
}
} // namespace nodal
