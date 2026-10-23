#include "nodal/Dialect/Nodal/StaticPredicateModel.h"

#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/StructuralIfModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/SmallVector.h"

#include <algorithm>
using namespace mlir;
namespace nodal {
namespace {
using Activity = StaticPredicateActivity;
constexpr unsigned maximumGuardWork = 65536;
constexpr unsigned maximumGuardDepth = 128;
bool consume(unsigned &remainingWork, size_t amount = 1) {
  if (amount > remainingWork)
    return false;
  remainingWork -= amount;
  return true;
}

// Charge the actual SSA graph before delegating to an existing semantic owner.
// This is only a work preflight, never an alternative interpretation of values.
LogicalResult chargeValueGraph(Value value, unsigned &remainingWork, llvm::DenseSet<Value> &visited,
                               unsigned depth = 0) {
  if (!consume(remainingWork) || depth >= maximumGuardDepth || !value)
    return failure();
  if (!visited.insert(value).second)
    return success();
  if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
    auto source = digitalRegionCaptureSource(argument);
    if (failed(source))
      return failure();
    return *source ? chargeValueGraph(*source, remainingWork, visited, depth + 1) : success();
  }
  Operation *op = value.getDefiningOp();
  if (!op)
    return failure();
  // A stored value is a runtime leaf, not its initializer or its writers.
  if (llvm::isa<DigitalStorageOp>(op))
    return success();
  for (Value operand : op->getOperands())
    if (failed(chargeValueGraph(operand, remainingWork, visited, depth + 1)))
      return failure();
  return success();
}

// The parameter owner remains authoritative for equality. Preflight the aligned
// DAG pairs it may visit, bounding traversal/storage before its equality query.
LogicalResult chargeIntegerPairs(
    const InstantiatedIntegerNode *left, const InstantiatedIntegerNode *right,
    unsigned &remainingWork,
    llvm::DenseSet<std::pair<const InstantiatedIntegerNode *, const InstantiatedIntegerNode *>>
        &visited,
    unsigned depth = 0) {
  if (!consume(remainingWork) || depth >= maximumGuardDepth)
    return failure();
  if (left == right || !left || !right || !visited.insert({left, right}).second ||
      left->operands.size() != right->operands.size())
    return success();
  for (size_t index = 0; index < left->operands.size(); ++index)
    if (failed(chargeIntegerPairs(left->operands[index].get(), right->operands[index].get(),
                                  remainingWork, visited, depth + 1)))
      return failure();
  return success();
}

struct ComparisonFacts {
  InstantiatedIntegerExpression left, right;
};
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
LogicalResult chargeIntegerGraph(const InstantiatedIntegerExpression &expression,
                                 unsigned &budget) {
  llvm::DenseSet<const InstantiatedIntegerNode *> visited;
  SmallVector<std::pair<const InstantiatedIntegerNode *, unsigned>, 8> pending{
      {expression.root.get(), 0}};
  while (!pending.empty()) {
    auto [node, depth] = pending.pop_back_val();
    if (!consume(budget) || !node || depth >= maximumGuardDepth)
      return failure();
    if (!visited.insert(node).second)
      continue;
    for (const auto &operand : node->operands)
      pending.emplace_back(operand.get(), depth + 1);
  }
  return success();
}
LogicalResult
validateSelectedCoordinates(ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> substitutions,
                            Operation *scope, unsigned &budget) {
  if (!scope || substitutions.size() > maximumGuardDepth)
    return failure();
  llvm::DenseSet<Value> identities;
  for (const auto &[identity, replacement] : substitutions) {
    auto induction = llvm::dyn_cast<BlockArgument>(identity);
    Operation *family = induction ? induction.getOwner()->getParentOp() : nullptr;
    if (!consume(budget) || !induction || induction.getArgNumber() != 0 ||
        !llvm::isa_and_nonnull<GenerateOp>(family) || !family->isProperAncestor(scope) ||
        !identities.insert(identity).second || !replacement.root ||
        replacement.bounds.lower != replacement.root->bounds.lower ||
        replacement.bounds.upper != replacement.root->bounds.upper)
      return failure();
    auto domain = resolveGeneratedDomain(family);
    if (failed(domain) || domain->induction != identity || !domain->attainedInductionBounds ||
        replacement.bounds.lower < domain->attainedInductionBounds->lower ||
        replacement.bounds.upper > domain->attainedInductionBounds->upper)
      return failure();
    // Charge each retained replacement node before canonical substitution.
    if (failed(chargeIntegerGraph(replacement, budget)))
      return failure();
  }
  return success();
}

// Work preflight only: the Parameter owner still interprets every binding and
// validates exact declaration/provenance. Charge original parameter SSA inputs
// before invoking its existing independently bounded selected-instance query.
LogicalResult chargeInstanceInputs(Operation *instance, Operation *child, unsigned &budget) {
  llvm::DenseSet<Value> visited;
  for (Block *block : {instance->getBlock(), &child->getRegion(0).front()})
    for (Operation &operation : *block) {
      if (!consume(budget))
        return failure();
      StringRef name = operation.getName().getStringRef();
      if (name != "nodal.parameter_override" && name != "nodal.parameter_value" &&
          name != "nodal.parameter_constraint")
        continue;
      for (Value operand : operation.getOperands())
        if (failed(chargeValueGraph(operand, budget, visited)))
          return failure();
    }
  return success();
}

class Query {
public:
  explicit Query(bool behavioral = false, unsigned *remainingWork = nullptr,
                 ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> substitutions = {},
                 Operation *instance = nullptr,
                 ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> childCoordinates = {},
                 Operation *rootModule = nullptr, ArrayRef<SelectedIntegerInstanceStep> path = {})
      : behavioral(behavioral), remainingWork(remainingWork), substitutions(substitutions),
        instance(instance), childCoordinates(childCoordinates), rootModule(rootModule), path(path) {
  }
  FailureOr<Activity> value(Value input, Operation *use, unsigned depth = 0) {
    if (!step() || depth >= 128 || !input || !llvm::isa<StaticPredicateType>(input.getType()) ||
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
    if (!step() || depth >= 128 || !op || op->getNumResults() != 1 ||
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
      if (remainingWork)
        for (Value input : op->getOperands()) {
          llvm::DenseSet<Value> visited;
          if (failed(chargeValueGraph(input, *remainingWork, visited)))
            return failure();
        }
      auto left = behavioral ? resolveBehavioralIntegerExpression(op->getOperand(0), op)
                             : resolveStructuralIntegerExpression(op->getOperand(0), op);
      auto right = behavioral ? resolveBehavioralIntegerExpression(op->getOperand(1), op)
                              : resolveStructuralIntegerExpression(op->getOperand(1), op);
      if (failed(left) || failed(right))
        return failure();
      if (!left->expression || !right->expression)
        return Activity::Unreachable;
      auto a = *left->expression;
      auto b = *right->expression;
      if (instance || !path.empty()) {
        if (!remainingWork)
          return failure();
        auto resolve = [&](Value input) {
          return path.empty() ? resolveSelectedInstanceStructuralIntegerExpression(
                                    instance, input, op, substitutions, *remainingWork)
                              : resolveSelectedInstancePathStructuralIntegerExpression(
                                    rootModule, path, input, op, *remainingWork);
        };
        auto selectedLeft = resolve(op->getOperand(0));
        auto selectedRight = resolve(op->getOperand(1));
        if (failed(selectedLeft) || failed(selectedRight))
          return failure();
        if (!selectedLeft->expression || !selectedRight->expression)
          return Activity::Unreachable;
        a = *selectedLeft->expression;
        b = *selectedRight->expression;
      }
      auto localCoordinates = instance || !path.empty() ? childCoordinates : substitutions;
      if (!localCoordinates.empty()) {
        if (!remainingWork || failed(chargeIntegerGraph(a, *remainingWork)) ||
            failed(chargeIntegerGraph(b, *remainingWork)))
          return failure();
        auto selectedLeft = substituteIntegerExpression(a, localCoordinates);
        auto selectedRight = substituteIntegerExpression(b, localCoordinates);
        if (failed(selectedLeft) || failed(selectedRight))
          return failure();
        a = *selectedLeft;
        b = *selectedRight;
      }
      if (remainingWork) {
        llvm::DenseSet<std::pair<const InstantiatedIntegerNode *, const InstantiatedIntegerNode *>>
            visited;
        if (failed(chargeIntegerPairs(a.root.get(), b.root.get(), *remainingWork, visited)))
          return failure();
        comparisons.try_emplace(op->getResult(0), ComparisonFacts{a, b});
      }
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

  const ComparisonFacts *comparison(Value value) const {
    auto found = comparisons.find(value);
    return found == comparisons.end() ? nullptr : &found->second;
  }

private:
  bool step() { return ++work <= maximumGuardWork && (!remainingWork || consume(*remainingWork)); }
  bool behavioral;
  unsigned *remainingWork;
  ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> substitutions;
  Operation *instance;
  ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> childCoordinates;
  Operation *rootModule;
  ArrayRef<SelectedIntegerInstanceStep> path;
  unsigned work = 0;
  llvm::DenseMap<Value, Activity> memo;
  llvm::DenseSet<Value> active;
  llvm::DenseMap<Value, ComparisonFacts> comparisons;
};

struct GuardLiteral {
  Value value;
  bool negated;
};
using GuardCube = SmallVector<GuardLiteral, 4>;
using GuardDnf = SmallVector<GuardCube, 4>;

// A finite Boolean expansion, not a solver: conjunction cubes contradict only
// through an actual atom/complement or equivalent checked static comparisons.
// Each visit, cube pair, literal pair and copied element consumes shared work.
class GuardQuery {
public:
  explicit GuardQuery(unsigned &remainingWork)
      : remainingWork(remainingWork), predicates(true, &remainingWork) {}

  FailureOr<bool> disjoint(Value left, Value right, Operation *use) {
    if (!boolean(left) || !boolean(right) || !consume(remainingWork))
      return failure();
    auto a = formula(left, use, false, 0);
    auto b = formula(right, use, false, 0);
    if (failed(a) || failed(b))
      return failure();
    for (const GuardCube &x : *a)
      for (const GuardCube &y : *b) {
        auto contradiction = contradict(x, y);
        if (failed(contradiction))
          return failure();
        if (!*contradiction)
          return false;
      }
    return true;
  }

  // Reuse the same bounded Boolean/canonical-comparison owner for lexical
  // structural assumptions. This proves an ordering, never changes a type domain.
  FailureOr<bool> impliesOrdering(Value predicate, Operation *use, bool negated,
                                  const InstantiatedIntegerExpression &left,
                                  const InstantiatedIntegerExpression &right, bool inclusive) {
    if (failed(Query(false, &remainingWork).value(predicate, use)))
      return failure();
    auto cubes = formula(predicate, use, negated, 0);
    if (failed(cubes))
      return failure();
    if (cubes->empty())
      return false; // No vacuous permission for an unreachable authored selection.
    unsigned wanted = inclusive ? 3 : 1;
    for (const GuardCube &cube : *cubes) {
      bool proved = false;
      for (GuardLiteral literal : cube) {
        if (!consume(remainingWork))
          return failure();
        const auto *comparison = predicates.comparison(literal.value);
        if (!comparison)
          continue;
        auto ll = equal(left, comparison->left), rr = equal(right, comparison->right);
        auto lr = equal(left, comparison->right), rl = equal(right, comparison->left);
        if (failed(ll) || failed(rr) || failed(lr) || failed(rl))
          return failure();
        unsigned original = outcomes(literal);
        unsigned mask = original;
        if (*lr && *rl)
          mask = ((mask & 1) << 2) | (mask & 2) | ((mask & 4) >> 2);
        else if (!(*ll && *rr))
          mask = 7;
        if ((mask & ~wanted) == 0) {
          proved = true;
          continue;
        }
        // Reuse checked identity and interval bounds around one authored comparison.
        // L <= a, a < b, b <= R proves L < R without narrowing any domain.
        auto boundedLess = [&](const InstantiatedIntegerExpression &a,
                               const InstantiatedIntegerExpression &b,
                               bool allowEqual) -> FailureOr<bool> {
          auto same = equal(a, b);
          if (failed(same))
            return failure();
          return *same ? allowEqual
                       : (allowEqual ? a.bounds.upper <= b.bounds.lower
                                     : a.bounds.upper < b.bounds.lower);
        };
        auto through = [&](const InstantiatedIntegerExpression &a,
                           const InstantiatedIntegerExpression &b,
                           unsigned relation) -> FailureOr<bool> {
          auto same = equal(a, b);
          if (failed(same))
            return failure();
          unsigned attainable =
              *same
                  ? 2
                  : (a.bounds.lower < b.bounds.upper ? 1 : 0) |
                        (a.bounds.lower <= b.bounds.upper && b.bounds.lower <= a.bounds.upper ? 2
                                                                                              : 0) |
                        (a.bounds.upper > b.bounds.lower ? 4 : 0);
          unsigned possible = relation & attainable;
          if (!possible || (possible & ~3))
            return false;
          auto before = boundedLess(left, a, true), after = boundedLess(b, right, true);
          if (failed(before) || failed(after))
            return failure();
          if (!*before || !*after)
            return false;
          if (inclusive || (possible & ~1) == 0)
            return true;
          auto strictBefore = boundedLess(left, a, false),
               strictAfter = boundedLess(b, right, false);
          if (failed(strictBefore) || failed(strictAfter))
            return failure();
          return *strictBefore || *strictAfter;
        };
        auto forward = through(comparison->left, comparison->right, original);
        auto backward = through(comparison->right, comparison->left,
                                ((original & 1) << 2) | (original & 2) | ((original & 4) >> 2));
        if (failed(forward) || failed(backward))
          return failure();
        proved |= *forward || *backward;
      }
      if (!proved)
        return false;
    }
    return true;
  }

private:
  bool boolean(Value value) {
    auto type = value ? llvm::dyn_cast<BitsType>(value.getType()) : BitsType();
    return type && type.getWidth() == 1;
  }

  FailureOr<GuardDnf> constant(bool value) {
    GuardDnf result;
    if (value) {
      if (!consume(remainingWork))
        return failure();
      result.emplace_back();
    }
    return result;
  }

  FailureOr<GuardDnf> atom(Value value, bool negated) {
    if (!consume(remainingWork, 2))
      return failure();
    GuardDnf result;
    result.push_back(GuardCube{{value, negated}});
    return result;
  }

  LogicalResult runtimeOperation(Operation *op) {
    if (!consume(remainingWork))
      return failure();
    if (validated.contains(op))
      return success();
    llvm::DenseSet<Value> visited;
    if (failed(chargeValueGraph(op->getResult(0), remainingWork, visited)))
      return failure();
    LogicalResult valid = failure();
    if (llvm::isa<DigitalExprOp>(op))
      valid = verifyDigitalValueOperation(op);
    else if (llvm::isa<ConstantOp, ConstLiteralOp>(op))
      valid = verifyDigitalLiteralOperation(op);
    else if (llvm::isa<PortValueOp, DigitalStorageOp, EnumExprOp, ShapeIndexOp, ShapeFlattenOp,
                       ShapeViewOp, ShapeSliceOp>(op))
      // Invoke the existing registered owner, including its generated invariant
      // checks, rather than reimplementing port, storage, enum or shape policy.
      valid = op->getName().verifyInvariants(op);
    if (succeeded(valid))
      validated.insert(op);
    return valid;
  }

  FailureOr<GuardDnf> formula(Value value, Operation *use, bool negated, unsigned depth) {
    if (!consume(remainingWork) || depth >= maximumGuardDepth || !value ||
        (!boolean(value) && !llvm::isa<StaticPredicateType>(value.getType())) ||
        failed(verifyDigitalValueScope(value, use)))
      return failure();
    if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
      auto source = digitalRegionCaptureSource(argument);
      if (failed(source) || !*source || source->getType() != value.getType())
        return failure();
      return formula(*source, argument.getOwner()->getParentOp(), negated, depth + 1);
    }
    Operation *op = value.getDefiningOp();
    if (!op || op->getNumResults() != 1 || op->getNumRegions() || op->getNumSuccessors())
      return failure();
    if (llvm::isa<StaticPredicateType>(value.getType())) {
      auto activity = predicates.value(value, use);
      if (failed(activity))
        return failure();
      if (*activity == Activity::Unreachable)
        return constant(false);
      if (*activity != Activity::Either)
        return constant((*activity == Activity::AlwaysTrue) != negated);
      if (llvm::isa<StaticCompareOp>(op))
        return atom(value, negated);
      return logic(op, text(op, "operator_name"), negated, depth);
    }
    if (llvm::isa<BehavioralPredicateOp>(op)) {
      // A narrow Bool/static bridge; deliberately no SequentialModel dependency.
      if (op->getNumOperands() != 1 || !closed(op, {"metadata"}) ||
          !llvm::isa<StaticPredicateType>(op->getOperand(0).getType()))
        return failure();
      return formula(op->getOperand(0), op, negated, depth + 1);
    }
    if (failed(runtimeOperation(op)))
      return failure();
    if (llvm::isa<ConstantOp, ConstLiteralOp>(op)) {
      auto value = op->getAttrOfType<IntegerAttr>("value");
      return constant((!value.getValue().isZero()) != negated);
    }
    if (llvm::isa<DigitalExprOp>(op)) {
      StringRef opcode = text(op, "operator_name");
      if (opcode.consume_front("bool_"))
        return logic(op, opcode, negated, depth);
    }
    return atom(value, negated);
  }

  FailureOr<GuardDnf> logic(Operation *op, StringRef opcode, bool negated, unsigned depth) {
    if (opcode == "not" && op->getNumOperands() == 1)
      return formula(op->getOperand(0), op, !negated, depth + 1);
    if ((opcode != "and" && opcode != "or") || op->getNumOperands() != 2)
      return failure();
    // Visit both children eagerly, even if one already determines the truth.
    auto left = formula(op->getOperand(0), op, negated, depth + 1);
    auto right = formula(op->getOperand(1), op, negated, depth + 1);
    if (failed(left) || failed(right))
      return failure();
    const bool conjunction = (opcode == "and") != negated;
    if (!conjunction) {
      for (GuardCube &cube : *right) {
        if (!consume(remainingWork, 1 + cube.size()))
          return failure();
        left->push_back(std::move(cube));
      }
      return std::move(*left);
    }
    GuardDnf result;
    for (const GuardCube &a : *left)
      for (const GuardCube &b : *right) {
        auto contradiction = contradict(a, b);
        if (failed(contradiction))
          return failure();
        if (*contradiction)
          continue;
        if (!consume(remainingWork, 1 + a.size() + b.size()))
          return failure();
        GuardCube cube;
        cube.append(a.begin(), a.end());
        cube.append(b.begin(), b.end());
        result.push_back(std::move(cube));
      }
    return result;
  }

  FailureOr<bool> equal(const InstantiatedIntegerExpression &a,
                        const InstantiatedIntegerExpression &b) {
    if (!consume(remainingWork))
      return failure();
    auto key = std::make_pair(a.root.get(), b.root.get());
    if (auto found = equalities.find(key); found != equalities.end())
      return found->second;
    llvm::DenseSet<std::pair<const InstantiatedIntegerNode *, const InstantiatedIntegerNode *>>
        visited;
    if (failed(chargeIntegerPairs(key.first, key.second, remainingWork, visited)))
      return failure();
    bool result = equivalentInstantiatedIntegerExpressions(a, b);
    equalities.try_emplace(key, result);
    return result;
  }

  // Bits denote the three exact Integer outcomes: less, equal, greater.
  unsigned outcomes(GuardLiteral literal) {
    StringRef cmp = text(literal.value.getDefiningOp(), "comparison");
    unsigned mask = cmp == "lt"   ? 1
                    : cmp == "eq" ? 2
                    : cmp == "le" ? 3
                    : cmp == "gt" ? 4
                    : cmp == "ne" ? 5
                                  : 6;
    return literal.negated ? mask ^ 7 : mask;
  }

  FailureOr<bool> conflict(GuardLiteral a, GuardLiteral b) {
    if (!consume(remainingWork))
      return failure();
    if (a.value == b.value)
      return a.negated != b.negated;
    const ComparisonFacts *x = predicates.comparison(a.value);
    const ComparisonFacts *y = predicates.comparison(b.value);
    if (!x || !y)
      return false;
    unsigned leftMask = outcomes(a), rightMask = outcomes(b);
    auto ll = equal(x->left, y->left);
    if (failed(ll))
      return failure();
    if (*ll) {
      auto rr = equal(x->right, y->right);
      if (failed(rr))
        return failure();
      if (*rr)
        return (leftMask & rightMask) == 0;
    }
    auto lr = equal(x->left, y->right);
    if (failed(lr))
      return failure();
    if (!*lr)
      return false;
    auto rl = equal(x->right, y->left);
    if (failed(rl))
      return failure();
    unsigned reversed = ((rightMask & 1) << 2) | (rightMask & 2) | ((rightMask & 4) >> 2);
    return *rl && (leftMask & reversed) == 0;
  }

  FailureOr<bool> contradict(const GuardCube &a, const GuardCube &b) {
    if (!consume(remainingWork))
      return failure();
    for (GuardLiteral left : a)
      for (GuardLiteral right : b) {
        auto result = conflict(left, right);
        if (failed(result) || *result)
          return result;
      }
    return false;
  }

  unsigned &remainingWork;
  Query predicates;
  llvm::DenseSet<Operation *> validated;
  llvm::DenseMap<std::pair<const InstantiatedIntegerNode *, const InstantiatedIntegerNode *>, bool>
      equalities;
};
} // namespace
FailureOr<StaticPredicateFacts> analyzeStaticPredicate(Value predicate, Operation *use,
                                                       const ParameterIntegerEnvironment *) {
  auto result = Query().value(predicate, use);
  return failed(result) ? FailureOr<StaticPredicateFacts>(failure())
                        : FailureOr<StaticPredicateFacts>(StaticPredicateFacts{predicate, *result});
}
FailureOr<StaticPredicateFacts>
analyzeStaticPredicate(Value predicate, Operation *use,
                       ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> substitutions,
                       unsigned &remainingWork) {
  unsigned available = std::min(remainingWork, maximumGuardWork), budget = available;
  auto finish = [&](FailureOr<Activity> result) -> FailureOr<StaticPredicateFacts> {
    remainingWork -= available - budget;
    return failed(result)
               ? FailureOr<StaticPredicateFacts>(failure())
               : FailureOr<StaticPredicateFacts>(StaticPredicateFacts{predicate, *result});
  };
  // Never use a narrowed coordinate to excuse an invalid original predicate.
  auto original = Query(false, &budget).value(predicate, use);
  if (failed(original) || !use || substitutions.size() > maximumGuardDepth)
    return finish(failure());
  if (failed(validateSelectedCoordinates(substitutions, use, budget)))
    return finish(failure());
  if (substitutions.empty())
    return finish(original);
  return finish(Query(false, &budget, substitutions).value(predicate, use));
}
FailureOr<StaticPredicateFacts> analyzeSelectedInstanceStaticPredicate(
    Value predicate, Operation *use, Operation *instance,
    ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> coordinates,
    unsigned &remainingWork) {
  return analyzeSelectedInstanceStaticPredicate(predicate, use, instance, coordinates, {},
                                                remainingWork);
}
FailureOr<StaticPredicateFacts> analyzeSelectedInstanceStaticPredicate(
    Value predicate, Operation *use, Operation *instance,
    ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> coordinates,
    ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> childCoordinates,
    unsigned &remainingWork) {
  unsigned available = std::min(remainingWork, maximumGuardWork), budget = available;
  auto finish = [&](FailureOr<Activity> result) -> FailureOr<StaticPredicateFacts> {
    remainingWork -= available - budget;
    return failed(result)
               ? FailureOr<StaticPredicateFacts>(failure())
               : FailureOr<StaticPredicateFacts>(StaticPredicateFacts{predicate, *result});
  };
  auto original = Query(false, &budget).value(predicate, use);
  Operation *child = instance ? resolveInstanceModuleDefinition(instance) : nullptr;
  if (failed(original) || !use || !child || !child->isProperAncestor(use) ||
      !isa_and_nonnull<GenerateOp>(instance->getParentOp()) || coordinates.empty() ||
      failed(validateSelectedCoordinates(coordinates, instance, budget)) ||
      failed(validateSelectedCoordinates(childCoordinates, use, budget)) ||
      failed(chargeInstanceInputs(instance, child, budget)))
    return finish(failure());
  return finish(
      Query(false, &budget, coordinates, instance, childCoordinates).value(predicate, use));
}
FailureOr<StaticPredicateFacts> analyzeSelectedInstancePathStaticPredicate(
    Value predicate, Operation *use, Operation *rootModule,
    ArrayRef<SelectedIntegerInstanceStep> path,
    ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> childCoordinates,
    unsigned &remainingWork) {
  unsigned available = std::min(remainingWork, maximumGuardWork), budget = available;
  auto finish = [&](FailureOr<Activity> result) -> FailureOr<StaticPredicateFacts> {
    remainingWork -= available - budget;
    return failed(result)
               ? FailureOr<StaticPredicateFacts>(failure())
               : FailureOr<StaticPredicateFacts>(StaticPredicateFacts{predicate, *result});
  };
  auto original = Query(false, &budget).value(predicate, use);
  Operation *outer = rootModule ? rootModule->getParentOp() : nullptr;
  auto selector =
      outer ? outer->getAttrOfType<FlatSymbolRefAttr>("nodal.root.module") : FlatSymbolRefAttr();
  if (failed(original) || !use || !isa_and_nonnull<ModuleOp>(rootModule) ||
      !isa_and_nonnull<mlir::ModuleOp>(outer) || !selector ||
      SymbolTable::lookupSymbolIn(outer, selector) != rootModule || path.empty() ||
      path.size() > maximumGuardDepth ||
      failed(validateSelectedCoordinates(childCoordinates, use, budget)))
    return finish(failure());
  Operation *expected = rootModule;
  llvm::DenseSet<Operation *> modules{rootModule}, edges;
  bool selected = !childCoordinates.empty();
  for (const auto &step : path) {
    Operation *instance = step.instance;
    Operation *child = instance ? resolveInstanceModuleDefinition(instance) : nullptr;
    if (!consume(budget) || !instance || !child ||
        instance->getParentOfType<ModuleOp>() != expected || !edges.insert(instance).second ||
        !modules.insert(child).second ||
        failed(validateSelectedCoordinates(step.coordinates, instance, budget)) ||
        failed(chargeInstanceInputs(instance, child, budget)))
      return finish(failure());
    if (failed(verifyGeneratedStructuralOwnership(instance)))
      return finish(failure());
    for (Operation *scope = instance->getParentOp(); scope && scope != expected;
         scope = scope->getParentOp()) {
      if (!consume(budget) || !isa<GenerateOp>(scope))
        return finish(failure());
      auto domain = resolveGeneratedDomain(scope);
      if (failed(domain) || !domain->attainedInductionBounds)
        return finish(failure());
    }
    selected |= !step.coordinates.empty();
    expected = child;
  }
  if (!selected || use->getParentOfType<ModuleOp>() != expected)
    return finish(failure());
  return finish(
      Query(false, &budget, {}, nullptr, childCoordinates, rootModule, path).value(predicate, use));
}
FailureOr<StaticPredicateFacts> analyzeBehavioralStaticPredicate(Value predicate, Operation *use) {
  auto result = Query(true).value(predicate, use);
  return failed(result) ? FailureOr<StaticPredicateFacts>(failure())
                        : FailureOr<StaticPredicateFacts>(StaticPredicateFacts{predicate, *result});
}
FailureOr<bool> proveBehavioralGuardsDisjoint(Value left, Value right, Operation *use,
                                              unsigned &remainingWork) {
  unsigned available = std::min(remainingWork, maximumGuardWork);
  unsigned budget = available;
  auto result = GuardQuery(budget).disjoint(left, right, use);
  remainingWork -= available - budget;
  return result;
}
FailureOr<bool> proveStructuralIntegerLessAtUse(const InstantiatedIntegerExpression &left,
                                                const InstantiatedIntegerExpression &right,
                                                Operation *use, bool inclusive) {
  if (!use || !left.root || !right.root)
    return failure();
  unsigned budget = maximumGuardWork;
  llvm::DenseSet<std::pair<const InstantiatedIntegerNode *, const InstantiatedIntegerNode *>> pairs;
  if (failed(chargeIntegerPairs(left.root.get(), right.root.get(), budget, pairs)))
    return failure();
  if (proveIntegerExpressionLess(left, right, inclusive))
    return true;
  GuardQuery query(budget);
  unsigned depth = 0;
  for (Operation *at = use; at && !llvm::isa<ModuleOp>(at); at = at->getParentOp()) {
    if (++depth > maximumGuardDepth || !consume(budget))
      return failure();
    Operation *parent = at->getParentOp();
    if (!llvm::isa_and_nonnull<StructuralIfOp>(parent))
      continue;
    auto header = inspectStructuralIfHeader(parent);
    if (failed(header))
      return failure();
    unsigned arm = at->getParentRegion()->getRegionNumber();
    if (arm >= 2 || at->getBlock() != header->bodies[arm])
      return failure();
    auto result =
        query.impliesOrdering(parent->getOperand(0), parent, arm == 1, left, right, inclusive);
    if (failed(result) || *result)
      return result;
  }
  return false;
}

LogicalResult verifyStaticPredicateOperation(Operation *operation) {
  if (failed(Query(true).operation(operation)))
    return operation->emitOpError("NODAL-DIGITAL-055-013: static predicate requires a closed owned "
                                  "parameter/Generate Integer DAG valid over every legal value");
  return success();
}
} // namespace nodal
