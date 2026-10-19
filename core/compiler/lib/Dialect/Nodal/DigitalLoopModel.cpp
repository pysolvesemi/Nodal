#include "nodal/Dialect/Nodal/DigitalLoopModel.h"

#include "mlir/IR/Verifier.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/ScopeExit.h"

using namespace mlir;
namespace nodal {
namespace {
StringRef text(Operation *operation, StringRef name) {
  auto attribute = operation ? operation->getAttrOfType<StringAttr>(name) : StringAttr();
  return attribute ? attribute.getValue() : StringRef();
}
StringRef path(Operation *operation) {
  auto metadata =
      operation ? operation->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
  auto value = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
  return value ? value.getValue() : StringRef();
}
LogicalResult reject(Operation *operation, StringRef message) {
  return operation->emitOpError("NODAL-DIGITAL-055-011: ") << message;
}
bool canonical(StringRef value) {
  return !value.empty() && value.size() <= digitalTypeMaximumIdentifierBytes &&
         value == value.trim() && !value.contains(' ') && !value.contains('\t') &&
         !value.contains('\n') && !value.contains('\r');
}
struct LoopIntegerQuery {
  unsigned work = 0;
  llvm::DenseMap<Value, InstantiatedIntegerExpression> staticMemo;
  llvm::DenseMap<Value, DigitalIterationIndex> indexMemo;
  llvm::DenseSet<Value> staticActive, indexActive;
  FailureOr<InstantiatedIntegerExpression> staticInteger(Value value, Operation *use,
                                                         unsigned depth) {
    if (depth > 128 || ++work > 65536 || !value || !value.getType().isSignlessInteger(64) || !use ||
        failed(verifyCollectionValueScope(value, use)))
      return failure();
    if (auto found = staticMemo.find(value); found != staticMemo.end())
      return found->second;
    if (!staticActive.insert(value).second)
      return failure();
    llvm::scope_exit cleanup([&] { staticActive.erase(value); });
    auto compute = [&]() -> FailureOr<InstantiatedIntegerExpression> {
      Operation *module = use->getParentOfType<ModuleOp>();
      if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
        auto source = digitalLoopCaptureSource(argument);
        if (failed(source) || !*source)
          return failure();
        return staticInteger(*source, argument.getOwner()->getParentOp(), depth + 1);
      }
      Operation *definition = value.getDefiningOp();
      if (!definition || definition->hasAttr("unit"))
        return failure();
      if (definition->getParentOp() == module)
        return resolveOwnedIntegerExpression(module, value,
                                             ParameterIntegerMode::MathematicalSigned64);
      if (isBoundedDigitalLoop(definition->getParentOp()) && llvm::isa<ConstExprOp>(definition)) {
        if (definition->getNumRegions() || definition->getNumSuccessors() ||
            definition->getNumResults() != 1 || failed(mlir::verify(definition, false)))
          return failure();
        SmallVector<InstantiatedIntegerExpression, 2> operands;
        for (Value operand : definition->getOperands()) {
          auto fact = staticInteger(operand, definition, depth + 1);
          if (failed(fact))
            return failure();
          operands.push_back(*fact);
        }
        return composeIntegerExpression(text(definition, "operator_name"), operands);
      }
      if (!isBoundedDigitalLoop(definition->getParentOp()) ||
          !llvm::isa<ConstantOp, ConstLiteralOp>(definition) || definition->getNumOperands() ||
          definition->getNumRegions() || definition->getNumResults() != 1 ||
          failed(mlir::verify(definition, false)))
        return failure();
      auto integer = definition->getAttrOfType<IntegerAttr>("value");
      if (!integer || !integer.getValue().isSignedIntN(64))
        return failure();
      return makeLiteralIntegerExpression(integer.getValue().getSExtValue());
    };
    auto result = compute();
    if (succeeded(result))
      staticMemo.try_emplace(value, *result);
    return result;
  }
  FailureOr<DigitalIterationIndex> iterationIndex(Value value, Operation *use, unsigned depth) {
    if (depth > 128 || ++work > 65536 || !value || !use || !value.getType().isSignlessInteger(64) ||
        failed(verifyCollectionValueScope(value, use)))
      return failure();
    if (auto found = indexMemo.find(value); found != indexMemo.end())
      return found->second;
    if (!indexActive.insert(value).second)
      return failure();
    llvm::scope_exit cleanup([&] { indexActive.erase(value); });
    auto compute = [&]() -> FailureOr<DigitalIterationIndex> {
      if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
        auto header = inspectDigitalLoopHeader(argument.getOwner()->getParentOp());
        if (failed(header) || header->body != argument.getOwner() || argument != header->induction)
          return failure();
        auto domain = resolveBoundedIterationDomain(header->operation);
        if (failed(domain))
          return failure();
        return DigitalIterationIndex{header->operation, *domain, makeLiteralIntegerExpression(0),
                                     domain->attainedInductionBounds};
      }
      Operation *operation = value.getDefiningOp();
      if (!operation || !llvm::isa<IterationIndexOp>(operation) ||
          operation->getNumOperands() != 2 || operation->getNumResults() != 1 ||
          operation->getNumRegions() || operation->getNumSuccessors() ||
          operation->hasAttr("unit") || !isBoundedDigitalLoop(operation->getParentOp()) ||
          (text(operation, "operator_name") != "add" && text(operation, "operator_name") != "sub"))
        return failure();
      // Subtraction preserves its authored operand order. Addition may use either
      // position, but exactly one operand must carry the actual induction.
      auto affine = iterationIndex(operation->getOperand(0), operation, depth + 1);
      auto offset = staticInteger(operation->getOperand(1), operation, depth + 1);
      if (failed(affine) || failed(offset)) {
        if (text(operation, "operator_name") != "add")
          return failure();
        affine = iterationIndex(operation->getOperand(1), operation, depth + 1);
        offset = staticInteger(operation->getOperand(0), operation, depth + 1);
      }
      if (failed(affine) || failed(offset) || affine->loop != operation->getParentOp())
        return failure();
      auto composed =
          composeIntegerExpression(text(operation, "operator_name"), {affine->offset, *offset});
      if (failed(composed))
        return failure();
      affine->offset = *composed;
      // Check the entire endpoint envelope even for an empty range. Empty activity
      // suppresses accesses, never malformed/overflowing affine arithmetic.
      auto lower = composeIntegerExpression("add", {affine->domain.lower, affine->offset});
      auto upper = composeIntegerExpression("add", {affine->domain.upperExclusive, affine->offset});
      if (failed(lower) || failed(upper))
        return failure();
      if (affine->attainedBounds) {
        auto attained = composeIntegerExpression(
            "add", {makeOpaqueIntegerExpression(affine->domain.induction,
                                                *affine->domain.attainedInductionBounds),
                    affine->offset});
        if (failed(attained))
          return failure();
        affine->attainedBounds = attained->bounds;
      }
      return *affine;
    };
    auto result = compute();
    if (succeeded(result))
      indexMemo.try_emplace(value, *result);
    return result;
  }
};
} // namespace

bool isBoundedDigitalLoop(Operation *operation) {
  return operation && operation->getName().getStringRef() == "nodal.hardware_loop" &&
         text(operation, "contract_version") == "bounded-digital-v1";
}
FailureOr<DigitalLoopHeader> inspectDigitalLoopHeader(Operation *operation) {
  if (!isBoundedDigitalLoop(operation) || operation->getNumRegions() != 1 ||
      !operation->getRegion(0).hasOneBlock() || operation->getNumOperands() < 3 ||
      operation->getNumOperands() > 4099 || operation->getNumResults() ||
      operation->getNumSuccessors() || operation->hasAttr("unit") || operation->hasAttr("lower") ||
      operation->hasAttr("upper") || operation->hasAttr("step") ||
      text(operation, "effect_policy") != "ordered" ||
      !operation->getAttrOfType<DictionaryAttr>("metadata") ||
      !llvm::isa_and_nonnull<ModuleOp>(operation->getParentOp()))
    return failure();
  Operation *module = operation->getParentOp();
  Block &body = operation->getRegion(0).front();
  if (body.getNumArguments() != operation->getNumOperands() - 2 ||
      !body.getArgument(0).getType().isSignlessInteger(64))
    return failure();
  for (Value bound : operation->getOperands().take_front(3))
    if (!bound.getType().isSignlessInteger(64) ||
        failed(verifyCollectionValueScope(bound, operation)))
      return failure();
  llvm::DenseSet<Value> captures;
  for (unsigned i = 3; i < operation->getNumOperands(); ++i) {
    Value value = operation->getOperand(i);
    if (value.getType() != body.getArgument(i - 2).getType() || !captures.insert(value).second ||
        failed(verifyCollectionValueScope(value, operation)))
      return failure();
  }
  StringRef id = text(operation, "region_id"), induction = text(operation, "induction_path");
  StringRef moduleId = path(module);
  if (moduleId.empty())
    moduleId = text(module, "sym_name");
  if (!canonical(id) || !canonical(induction) || !canonical(text(operation, "induction")) ||
      !id.starts_with((moduleId + ".").str()) || !induction.starts_with((id + ".").str()) ||
      path(operation) != id)
    return failure();
  return DigitalLoopHeader{operation, module, &body, body.getArgument(0)};
}
FailureOr<Value> digitalLoopCaptureSource(BlockArgument argument) {
  auto header = inspectDigitalLoopHeader(argument.getOwner()->getParentOp());
  if (failed(header) || header->body != argument.getOwner())
    return failure();
  if (!argument.getArgNumber())
    return Value();
  return header->operation->getOperand(argument.getArgNumber() + 2);
}
FailureOr<InstantiatedIntegerExpression> resolveDigitalLoopStaticInteger(Value value,
                                                                         Operation *use) {
  return LoopIntegerQuery().staticInteger(value, use, 0);
}
FailureOr<DigitalIterationIndex> resolveDigitalIterationIndex(Value value, Operation *use) {
  return LoopIntegerQuery().iterationIndex(value, use, 0);
}
LogicalResult verifyDigitalIterationIndexOperation(Operation *operation) {
  if (!operation || operation->getNumResults() != 1)
    return failure();
  // Resolve at a later synthetic-free actual use when available; the recursive
  // verifier cannot ask that a value dominate its own definition.
  if (!isBoundedDigitalLoop(operation->getParentOp()) || operation->getNumOperands() != 2)
    return reject(operation, "iteration index requires the actual bounded loop scope");
  Operation *next = operation->getNextNode();
  if (!next || failed(resolveDigitalIterationIndex(operation->getResult(0), next)))
    return reject(operation, "iteration index requires one actual induction and one static offset");
  return success();
}
namespace detail {
class LoopIntegerEnvironmentAdmission {
public:
  static FailureOr<ParameterIntegerEnvironment> create(Operation *use) {
    auto header = use ? inspectDigitalLoopHeader(use->getParentOp())
                      : FailureOr<DigitalLoopHeader>(failure());
    if (failed(header) || use->getBlock() != header->body)
      return failure();
    SmallVector<ParameterIntegerBinding, 8> bindings;
    for (BlockArgument argument : header->body->getArguments().drop_front()) {
      if (!argument.getType().isSignlessInteger(64))
        continue;
      auto source = digitalLoopCaptureSource(argument);
      auto expression = succeeded(source) && *source
                            ? resolveDigitalLoopStaticInteger(*source, header->operation)
                            : FailureOr<InstantiatedIntegerExpression>(failure());
      if (failed(expression))
        return failure();
      bindings.push_back({argument, *expression, *source});
    }
    return ParameterIntegerEnvironment::create(header->module, header->body, use, nullptr, bindings,
                                               {});
  }
};
} // namespace detail
FailureOr<ParameterIntegerEnvironment> digitalLoopIntegerContextAtUse(Operation *use) {
  return detail::LoopIntegerEnvironmentAdmission::create(use);
}
LogicalResult verifyDigitalLoopOperation(Operation *operation) {
  auto header = inspectDigitalLoopHeader(operation);
  if (failed(header) || failed(resolveBoundedIterationDomain(operation)))
    return reject(operation, "invalid bounded loop header, capture provenance or finite domain");
  if (header->body->empty() || !llvm::isa<HardwareLoopEndOp>(header->body->back()))
    return reject(operation, "bounded loop requires exactly one explicit final hardware_loop_end");
  for (Value capture : operation->getOperands().drop_front(3)) {
    if (capture.getType().isSignlessInteger(64)) {
      if (failed(resolveDigitalLoopStaticInteger(capture, operation)))
        return reject(operation,
                      "Integer captures must be independently owned analysis-static values");
    } else if (!isDigitalCombinationalValueType(capture.getType(), header->module))
      return reject(operation, "loop captures must retain invariant complete digital value types");
  }
  unsigned ordinal = 0, work = 0;
  llvm::DenseSet<Value> usedCaptures;
  unsigned expectedCapture = 1;
  for (Operation &body : *header->body) {
    if (++work > 65536)
      return reject(operation, "bounded loop exceeds its authored operation proof budget");
    if (llvm::isa<HardwareLoopEndOp>(body)) {
      if (&body != &header->body->back() || body.getNumOperands() || body.getNumResults() ||
          body.getNumRegions() || body.getNumSuccessors())
        return reject(&body, "loop end must be the unique empty final terminator");
      continue;
    }
    for (Value operand : body.getOperands()) {
      if (failed(verifyCollectionValueScope(operand, &body)))
        return reject(&body, "loop body must use actual local SSA or explicit matching captures");
      if (auto arg = llvm::dyn_cast<BlockArgument>(operand))
        if (arg.getOwner() == header->body && arg.getArgNumber() && usedCaptures.insert(arg).second)
          if (arg.getArgNumber() != expectedCapture++)
            return reject(&body, "captures must retain deterministic first actual use order");
    }
    auto context = digitalLoopIntegerContextAtUse(&body);
    if (failed(context) || failed(verifyDigitalTypeOperation(&body, &*context)))
      return failure();
    StringRef name = body.getName().getStringRef();
    if (llvm::isa<DigitalAssignOp>(body)) {
      if (text(&body, "kind") != "continuous" || failed(verifyDigitalAssignmentOperation(&body)))
        return reject(&body, "bounded loop supports only exact continuous wire/output writes");
      auto metadata = body.getAttrOfType<DictionaryAttr>("metadata");
      if (auto retained = metadata ? metadata.getAs<IntegerAttr>("source_ordinal") : IntegerAttr())
        if (!retained.getType().isSignlessInteger(64) || retained.getInt() != ordinal)
          return reject(&body, "assignment source ordinal differs from its actual body position");
      ++ordinal;
    } else if (llvm::isa<CollectionOp>(body)) {
      if (failed(verifyDigitalCollectionOperation(&body)))
        return failure();
    } else {
      const bool pure = llvm::isa<IterationIndexOp, ShapeIndexOp, ShapeViewOp, ShapeSliceOp,
                                  ConstantOp, ConstLiteralOp, ConstExprOp>(body) ||
                        name == "nodal.digital_expr" || name == "nodal.enum_expr";
      if (!pure || body.getNumRegions() || body.getNumSuccessors() || body.getNumResults() != 1 ||
          body.hasAttr("unit"))
        return reject(&body, "body is outside the pure invariant-type combinational profile");
      if (failed(mlir::verify(&body, false)))
        return failure();
    }
    for (Value result : body.getResults()) {
      if (!isDigitalCombinationalValueType(result.getType(), header->module, &*context) &&
          !(result.getType().isSignlessInteger(64) &&
            (llvm::isa<IterationIndexOp>(body) ||
             succeeded(resolveDigitalLoopStaticInteger(result, body.getNextNode())))))
        return reject(&body,
                      "loop body results must have invariant digital or verified index types");
      for (Operation *user : result.getUsers())
        if (!operation->isProperAncestor(user))
          return reject(&body, "loop-local values cannot escape their lexical template");
    }
  }
  if (expectedCapture != header->body->getNumArguments())
    return reject(operation, "bounded loop contains an unused or forged capture argument");
  for (BlockArgument argument : header->body->getArguments())
    for (Operation *user : argument.getUsers())
      if (!operation->isProperAncestor(user))
        return reject(operation, "loop argument cannot escape its actual region");
  return success();
}
} // namespace nodal
