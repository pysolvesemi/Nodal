#include "nodal/Dialect/Nodal/StaticPredicateModel.h"

#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
using namespace mlir;
namespace nodal {
namespace {
using Activity = StaticPredicateActivity;
StringRef text(Operation *op, StringRef key) {
  auto value = op->getAttrOfType<StringAttr>(key);
  return value ? value.getValue() : StringRef();
}
bool closed(Operation *op, ArrayRef<StringRef> keys) {
  for (NamedAttribute a : op->getAttrs())
    if (!llvm::is_contained(keys, a.getName().getValue()))
      return false;
  auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
  auto path = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
  return path && !path.getValue().empty() && path.getValue().size() <= 4096 &&
         path.getValue() == path.getValue().trim();
}
class Query {
public:
  explicit Query(bool behavioral = false) : behavioral(behavioral) {}
  FailureOr<Activity> value(Value input, Operation *use, unsigned depth = 0) {
    if (++work > 65536 || depth >= 128 || !input ||
        !llvm::isa<StaticPredicateType>(input.getType()) ||
        failed(verifyDigitalValueScope(input, use)))
      return failure();
    if (auto known = memo.find(input); known != memo.end())
      return known->second;
    if (!active.insert(input).second)
      return failure();
    FailureOr<Activity> result = failure();
    if (auto arg = llvm::dyn_cast<BlockArgument>(input)) {
      auto capture = digitalRegionCaptureSource(arg);
      if (succeeded(capture) && *capture)
        result = value(*capture, arg.getOwner()->getParentOp(), depth + 1);
    } else
      result = operation(input.getDefiningOp(), depth + 1);
    active.erase(input);
    if (succeeded(result))
      memo.try_emplace(input, *result);
    return result;
  }
  FailureOr<Activity> operation(Operation *op, unsigned depth = 0) {
    if (++work > 65536 || depth >= 128 || !op || op->getNumResults() != 1 ||
        !llvm::isa<StaticPredicateType>(op->getResult(0).getType()) || op->getNumRegions() ||
        op->getNumSuccessors())
      return failure();
    Operation *parent = op->getParentOp();
    if (!llvm::isa_and_nonnull<ModuleOp>(parent) && !isDigitalRegionOwner(parent))
      return failure();
    if (llvm::isa<StaticCompareOp>(op)) {
      if (!closed(op, {"comparison", "metadata"}) || op->getNumOperands() != 2)
        return failure();
      StringRef cmp = text(op, "comparison");
      if (cmp != "eq" && cmp != "ne" && cmp != "lt" && cmp != "le" && cmp != "gt" && cmp != "ge")
        return failure();
      auto left = behavioral ? resolveBehavioralIntegerExpression(op->getOperand(0), op)
                             : resolveStructuralIntegerExpression(op->getOperand(0), op);
      auto right = behavioral ? resolveBehavioralIntegerExpression(op->getOperand(1), op)
                              : resolveStructuralIntegerExpression(op->getOperand(1), op);
      if (failed(left) || failed(right))
        return failure();
      if (!left->expression || !right->expression)
        return Activity::Unreachable;
      auto &a = *left->expression;
      auto &b = *right->expression;
      bool equal = equivalentInstantiatedIntegerExpressions(a, b);
      bool less = proveIntegerExpressionLess(a, b), greater = proveIntegerExpressionLess(b, a);
      if (cmp == "eq")
        return equal             ? Activity::AlwaysTrue
               : less || greater ? Activity::AlwaysFalse
                                 : Activity::Either;
      if (cmp == "ne")
        return equal             ? Activity::AlwaysFalse
               : less || greater ? Activity::AlwaysTrue
                                 : Activity::Either;
      if (cmp == "gt" || cmp == "ge") {
        std::swap(less, greater);
      }
      bool inclusive = cmp == "le" || cmp == "ge";
      if (less || (inclusive && equal))
        return Activity::AlwaysTrue;
      if (greater || (!inclusive && equal))
        return Activity::AlwaysFalse;
      return Activity::Either;
    }
    if (!llvm::isa<StaticLogicOp>(op) || !closed(op, {"operator_name", "metadata"}))
      return failure();
    StringRef opcode = text(op, "operator_name");
    unsigned arity = opcode == "not" ? 1 : opcode == "and" || opcode == "or" ? 2 : 0;
    if (!arity || op->getNumOperands() != arity)
      return failure();
    SmallVector<Activity, 2> facts;
    for (Value input : op->getOperands()) {
      auto fact = value(input, op, depth + 1);
      if (failed(fact))
        return failure();
      facts.push_back(*fact);
    }
    if (llvm::is_contained(facts, Activity::Unreachable))
      return Activity::Unreachable;
    if (opcode == "not")
      return facts[0] == Activity::Either       ? Activity::Either
             : facts[0] == Activity::AlwaysTrue ? Activity::AlwaysFalse
                                                : Activity::AlwaysTrue;
    if (opcode == "and") {
      if (llvm::is_contained(facts, Activity::AlwaysFalse))
        return Activity::AlwaysFalse;
      return facts[0] == Activity::AlwaysTrue && facts[1] == Activity::AlwaysTrue
                 ? Activity::AlwaysTrue
                 : Activity::Either;
    }
    if (llvm::is_contained(facts, Activity::AlwaysTrue))
      return Activity::AlwaysTrue;
    return facts[0] == Activity::AlwaysFalse && facts[1] == Activity::AlwaysFalse
               ? Activity::AlwaysFalse
               : Activity::Either;
  }

private:
  bool behavioral;
  unsigned work = 0;
  llvm::DenseMap<Value, Activity> memo;
  llvm::DenseSet<Value> active;
};
} // namespace
FailureOr<StaticPredicateFacts> analyzeStaticPredicate(Value predicate, Operation *use,
                                                       const ParameterIntegerEnvironment *) {
  auto result = Query().value(predicate, use);
  return failed(result) ? FailureOr<StaticPredicateFacts>(failure())
                        : FailureOr<StaticPredicateFacts>(StaticPredicateFacts{predicate, *result});
}
FailureOr<StaticPredicateFacts> analyzeBehavioralStaticPredicate(Value predicate, Operation *use) {
  auto result = Query(true).value(predicate, use);
  return failed(result) ? FailureOr<StaticPredicateFacts>(failure())
                        : FailureOr<StaticPredicateFacts>(StaticPredicateFacts{predicate, *result});
}
LogicalResult verifyStaticPredicateOperation(Operation *operation) {
  if (failed(Query(true).operation(operation)))
    return operation->emitOpError("NODAL-DIGITAL-055-013: static predicate requires a closed owned "
                                  "parameter/Generate Integer DAG valid over every legal value");
  return success();
}
} // namespace nodal
