#include "nodal/Dialect/Nodal/FsmCompositionModel.h"

#include "mlir/IR/Builders.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/FlatFSMModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallString.h"
#include "llvm/ADT/StringMap.h"

#include <optional>
using namespace mlir;
namespace nodal {
namespace {
bool join(Operation *op) { return op && op->getName().getStringRef() == "nodal.fsm_join"; }
bool activation(Operation *op) {
  return op && op->getName().getStringRef() == "nodal.fsm_activation";
}
StringRef text(Operation *op, StringRef key) {
  auto value = op->getAttrOfType<StringAttr>(key);
  return value ? value.getValue() : StringRef();
}
LogicalResult reject(Operation *op, StringRef reason) {
  return op->emitOpError("NODAL-FSM-058-001: ") << reason;
}
bool bits(Value value, unsigned width) {
  return value.getType() == BitsType::get(value.getContext(), width);
}
struct Owner {
  FlatFSMGraphSummary graph;
  std::optional<unsigned> parent;
  SmallVector<unsigned, 4> children;
  SmallVector<Operation *, 4> joins;
  llvm::StringMap<unsigned> activations;
  bool canComplete = false;
};
struct Activation {
  Operation *operation;
  unsigned parent, state;
  SmallVector<unsigned, 4> children;
  llvm::DenseSet<unsigned> childSet;
};
class Model {
public:
  Model(Operation *module, unsigned maximumWork, unsigned maximumDepth)
      : module(module), maximumWork(maximumWork), maximumDepth(maximumDepth) {}
  LogicalResult build() {
    if (!llvm::isa_and_nonnull<ModuleOp>(module) || module->getNumRegions() != 1 ||
        !module->getRegion(0).hasOneBlock())
      return failure();
    SmallVector<Operation *, 8> composition;
    for (Operation &op : module->getRegion(0).front()) {
      if (!charge())
        return failure();
      if (join(&op) || activation(&op)) {
        if (failed(verifyFsmCompositionOperation(&op)))
          return failure();
        composition.push_back(&op);
      }
      if (!llvm::isa<FlatFSMOp>(op))
        continue;
      auto header = inspectFlatFSMHeader(&op);
      if (failed(header))
        return failure();
      if (!header->composed)
        continue;
      if (owners.size() == 4096)
        return reject(&op, "composition exceeds occurrence budget");
      auto graph = inspectFlatFSMGraphSummary(&op);
      if (failed(graph))
        return failure();
      if (!charge(graph->caseIds.size() + graph->header.guards))
        return failure();
      unsigned index = owners.size();
      if (!roots.try_emplace(header->currentLocal(), index).second ||
          !privateRoots.insert(header->currentLocal()).second ||
          !privateRoots.insert(header->currentStatus()).second)
        return reject(&op, "composition occurrences require distinct actual private storage");
      owners.push_back({std::move(*graph), {}, {}, {}, {}, false});
    }
    for (Operation *op : composition)
      if (activation(op) && failed(addActivation(op)))
        return failure();
    for (Operation *op : composition)
      if (join(op) && failed(addJoin(op)))
        return failure();
    // Parent uniqueness makes this a forest. A linear breadth-first traversal
    // proves acyclicity and depth without recursive graph expansion.
    SmallVector<std::pair<unsigned, unsigned>, 8> queue;
    for (unsigned i = 0; i < owners.size(); ++i) {
      if (!owners[i].parent && owners[i].children.empty())
        return reject(owners[i].graph.header.operation, "v3 requires an actual composition edge");
      if (!owners[i].parent)
        queue.push_back({i, 1});
    }
    for (unsigned i = 0; i < queue.size(); ++i) {
      auto [owner, depth] = queue[i];
      if (depth > maximumDepth || !charge())
        return reject(module, "composition exceeds finite depth/work budget");
      for (unsigned child : owners[owner].children)
        queue.push_back({child, depth + 1});
    }
    if (queue.size() != owners.size())
      return reject(module, "composition topology contains a cycle");
    for (auto [owner, depth] : llvm::reverse(queue)) {
      (void)depth;
      for (Operation *op : owners[owner].joins) {
        bool possible = text(op, "mode") == "all";
        for (unsigned i = 2; i < op->getNumOperands(); i += 2) {
          auto child = roots.find(op->getOperand(i));
          bool complete = owners[child->second].canComplete;
          possible = text(op, "mode") == "all" ? possible && complete : possible || complete;
        }
        if (!possible)
          return reject(op, "join has no structural completion possibility");
      }
      // Existing graph admission proves every declared local state reachable.
      // This fact is usable only after all child join prerequisites above pass.
      owners[owner].canComplete = llvm::is_contained(owners[owner].graph.terminal, true);
    }
    return success();
  }
  SmallVector<Owner, 8> owners;
  SmallVector<Activation, 8> activations;

private:
  bool charge(uint64_t amount = 1) {
    if (amount > maximumWork - std::min(maximumWork, work)) {
      (void)reject(module, "composition exceeds finite authored work budget");
      return false;
    }
    work += amount;
    return true;
  }
  FailureOr<unsigned> owner(Operation *op, unsigned offset) {
    auto found = roots.find(op->getOperand(offset));
    if (found == roots.end() ||
        owners[found->second].graph.header.currentStatus() != op->getOperand(offset + 1) ||
        owners[found->second].graph.header.operation->getAttr("domain") != op->getAttr("domain")) {
      (void)reject(op, "composition pair must name one exact same-domain v3 owner");
      return failure();
    }
    return found->second;
  }
  LogicalResult addActivation(Operation *op) {
    auto parent = owner(op, 0);
    if (failed(parent))
      return failure();
    auto &record = owners[*parent];
    auto &header = record.graph.header;
    auto state = llvm::find(record.graph.caseIds, op->getAttrOfType<StringAttr>("owner_case"));
    unsigned stateIndex = state - record.graph.caseIds.begin();
    if (state == record.graph.caseIds.end() || record.graph.terminal[stateIndex] ||
        !record.activations.try_emplace(text(op, "owner_case"), activations.size()).second)
      return reject(op, "activation requires one unique nonterminal owner state");
    Value expected[] = {header.next(),   header.taken(),         header.entered(),
                        header.exited(), header.establishment(), header.enable()};
    for (unsigned i = 0; i < 6; ++i)
      if (op->getOperand(i + 2) != expected[i])
        return reject(op, "activation must retain exact parent future/control operands");
    Activation entry{op, *parent, stateIndex, {}, {}};
    for (unsigned i = 8; i < op->getNumOperands(); i += 2) {
      if (!charge())
        return failure();
      auto child = owner(op, i);
      if (failed(child))
        return failure();
      auto &c = owners[*child];
      if (*child == *parent || c.parent || c.graph.header.restart() != op->getResult(0) ||
          c.graph.header.cancel() != op->getResult(1) ||
          c.graph.header.enable() != op->getResult(2))
        return reject(op, "child needs one parent and the exact owned activation controls");
      c.parent = *parent;
      record.children.push_back(*child);
      entry.children.push_back(*child);
      entry.childSet.insert(*child);
    }
    for (unsigned result = 0; result < 3; ++result)
      for (OpOperand &use : op->getResult(result).getUses()) {
        if (!charge())
          return failure();
        auto child =
            roots.find(use.getOwner()->getNumOperands() ? use.getOwner()->getOperand(0) : Value());
        if (child == roots.end() || !entry.childSet.contains(child->second) ||
            use.getOwner() != owners[child->second].graph.header.operation ||
            use.getOperandNumber() != result + 2)
          return reject(op, "activation results only control their actual owned children");
      }
    activations.push_back(std::move(entry));
    return success();
  }
  LogicalResult addJoin(Operation *op) {
    auto parent = owner(op, 0);
    if (failed(parent))
      return failure();
    auto &record = owners[*parent];
    auto found = record.activations.find(text(op, "owner_case"));
    if (found == record.activations.end())
      return reject(op, "join lacks exact owner activation");
    auto &entry = activations[found->second];
    SmallVector<unsigned, 4> children;
    llvm::DenseSet<unsigned> uniqueChildren;
    for (unsigned i = 2; i < op->getNumOperands(); i += 2) {
      if (!charge())
        return failure();
      auto child = owner(op, i);
      if (failed(child) || !entry.childSet.contains(*child) ||
          !uniqueChildren.insert(*child).second)
        return reject(op, "join must retain unique children of its exact owner state");
      children.push_back(*child);
    }
    if (text(op, "selection") == "all_children" && children != entry.children)
      return reject(op, "whole join must preserve the complete ordered child inventory");
    bool used = false;
    for (OpOperand &use : op->getResult(0).getUses()) {
      if (!charge())
        return failure();
      const auto &h = record.graph.header;
      if (use.getOwner() != h.operation || use.getOperandNumber() < h.guardOffset ||
          use.getOperandNumber() >= h.guardOffset + h.guards)
        return reject(op, "join must be an actual owning parent guard");
      auto transition = llvm::cast<DictionaryAttr>(h.operation->getAttrOfType<ArrayAttr>(
          "transitions")[use.getOperandNumber() - h.guardOffset]);
      if (transition.getAs<StringAttr>("source_case") != op->getAttr("owner_case"))
        return reject(op, "join guard belongs to another owner state");
      used = true;
    }
    if (!used)
      return reject(op, "join requires an actual parent transition use");
    record.joins.push_back(op);
    return success();
  }
  Operation *module;
  unsigned maximumWork, maximumDepth, work = 0;
  llvm::DenseMap<Value, unsigned> roots;
  llvm::DenseSet<Value> privateRoots;
};
} // namespace
LogicalResult verifyFsmCompositionOperation(Operation *op) {
  bool isJoin = join(op);
  unsigned offset = isJoin ? 2 : 8;
  if ((!isJoin && !activation(op)) || !llvm::isa_and_nonnull<ModuleOp>(op->getParentOp()) ||
      op->getNumRegions() || op->getNumSuccessors() || op->getNumResults() != (isJoin ? 1u : 3u) ||
      op->getNumOperands() < offset + 2 || (op->getNumOperands() - offset) % 2 ||
      op->getNumOperands() > (isJoin ? 8192u : 8198u) ||
      text(op, "composition_contract") != "fsm-composition-v1" ||
      !op->getAttrOfType<FlatSymbolRefAttr>("domain") || text(op, "owner_case").empty() ||
      text(op, "owner_case").size() > 4096 ||
      text(op, "owner_case").trim() != text(op, "owner_case"))
    return reject(op, "composition requires its closed bounded module-owned form");
  for (NamedAttribute attribute : op->getAttrs()) {
    StringRef key = attribute.getName().getValue();
    if (!llvm::is_contained(
            ArrayRef<StringRef>{"composition_contract", "domain", "owner_case", "metadata"}, key) &&
        !(isJoin && (key == "mode" || key == "selection")))
      return reject(op, "composition has unsupported authority attributes");
  }
  if (isJoin &&
      ((text(op, "mode") != "all" && text(op, "mode") != "any") ||
       (text(op, "selection") != "all_children" && text(op, "selection") != "one_child") ||
       (text(op, "selection") == "one_child" &&
        (op->getNumOperands() != 4 || text(op, "mode") != "all"))))
    return reject(op, "join requires an explicit nonempty closed selection and policy");
  if (!llvm::isa<BitsType>(op->getOperand(0).getType()) || !bits(op->getOperand(1), 2))
    return reject(op, "composition parent needs exact local and lifecycle types");
  if (!isJoin) {
    if (op->getOperand(2).getType() != op->getOperand(0).getType())
      return reject(op, "activation effective next must retain parent local type");
    for (unsigned i = 3; i < 8; ++i)
      if (!bits(op->getOperand(i), 1))
        return reject(op, "activation controls must be Bool");
  }
  for (unsigned i = offset; i < op->getNumOperands(); i += 2)
    if (!llvm::isa<BitsType>(op->getOperand(i).getType()) || !bits(op->getOperand(i + 1), 2))
      return reject(op, "composition child pairs require exact local and lifecycle types");
  for (Value value : op->getResults())
    if (!bits(value, 1))
      return reject(op, "composition results must be Bool");
  for (Value value : op->getOperands())
    if (failed(verifyDigitalValueScope(value, op)))
      return reject(op, "composition operands must retain actual lexical scope");
  return success();
}
StringRef fsmCompositionResultRole(Value result) {
  auto value = llvm::dyn_cast_or_null<OpResult>(result);
  Operation *op = value ? value.getOwner() : nullptr;
  if (!op || (!join(op) && !activation(op)) || failed(verifyFsmCompositionOperation(op)))
    return {};
  if (join(op))
    return "join";
  static constexpr StringLiteral names[] = {"restart", "cancel", "enable"};
  return names[value.getResultNumber()];
}
LogicalResult fsmCompositionResultDependencies(Value result, SmallVectorImpl<Value> &dependencies) {
  auto value = llvm::dyn_cast_or_null<OpResult>(result);
  Operation *op = result ? result.getDefiningOp() : nullptr;
  if (!value || !op || failed(verifyFsmCompositionOperation(op)))
    return failure();
  if (join(op)) {
    for (unsigned i = 3; i < op->getNumOperands(); i += 2)
      dependencies.push_back(op->getOperand(i));
  } else if (value.getResultNumber() == 0) {
    for (unsigned i : {2u, 4u, 6u})
      dependencies.push_back(op->getOperand(i));
  } else if (value.getResultNumber() == 1) {
    for (unsigned i : {2u, 4u, 5u, 6u})
      dependencies.push_back(op->getOperand(i));
  } else {
    for (unsigned i : {0u, 1u, 7u})
      dependencies.push_back(op->getOperand(i));
  }
  return success();
}
LogicalResult verifyFsmCompositionModel(Operation *module, unsigned maximumWork,
                                        unsigned maximumDepth) {
  return Model(module, maximumWork, maximumDepth).build();
}
namespace {
class Emitter {
public:
  Emitter(Operation *op, unsigned &generated, unsigned maximum, std::string fallback)
      : op(op), builder(op), generated(generated), maximum(maximum), fallback(std::move(fallback)) {
  }
  Value literal(const llvm::APInt &value) {
    OperationState state(op->getLoc(), "nodal.const_literal");
    state.addTypes(BitsType::get(builder.getContext(), value.getBitWidth()));
    state.addAttribute("value",
                       IntegerAttr::get(IntegerType::get(builder.getContext(), value.getBitWidth(),
                                                         IntegerType::Unsigned),
                                        value));
    llvm::SmallString<32> spelling;
    value.toString(spelling, 10, false);
    state.addAttribute("spelling", builder.getStringAttr(spelling));
    return create(state);
  }
  Value expression(StringRef name, ArrayRef<Value> inputs) {
    if (llvm::any_of(inputs, [](Value value) { return !value; }))
      return {};
    OperationState state(op->getLoc(), "nodal.digital_expr");
    state.addOperands(inputs);
    state.addTypes(BitsType::get(builder.getContext(), 1));
    state.addAttribute("operator_name", builder.getStringAttr(name));
    return create(state);
  }
  Value both(Value a, Value b) { return expression("bool_and", {a, b}); }
  Value either(Value a, Value b) { return expression("bool_or", {a, b}); }
  Value negate(Value a) { return expression("bool_not", {a}); }
  Value equal(Value a, Value b) { return expression("digital_eq", {a, b}); }
  Value combine(SmallVector<Value, 8> values, bool all) {
    if (values.empty())
      return {};
    while (values.size() > 1) {
      SmallVector<Value, 8> next;
      for (unsigned i = 0; i < values.size(); i += 2)
        next.push_back(i + 1 == values.size() ? values[i]
                       : all                  ? both(values[i], values[i + 1])
                                              : either(values[i], values[i + 1]));
      values = std::move(next);
    }
    return values.front();
  }

private:
  Value create(OperationState &state) {
    if (generated >= maximum) {
      (void)reject(op, "composition expansion exceeds shared operation budget");
      return {};
    }
    ++generated;
    NamedAttrList metadata(op->getAttrOfType<DictionaryAttr>("metadata"));
    auto path = metadata.get("semantic_path");
    std::string base = path && llvm::isa<StringAttr>(path)
                           ? llvm::cast<StringAttr>(path).getValue().str()
                           : fallback;
    metadata.set("semantic_path",
                 builder.getStringAttr(base + ".value" + std::to_string(serial++)));
    state.addAttribute("metadata", metadata.getDictionary(builder.getContext()));
    return builder.create(state)->getResult(0);
  }
  Operation *op;
  OpBuilder builder;
  unsigned &generated, maximum, serial = 0;
  std::string fallback;
};
} // namespace
LogicalResult expandFsmCompositionModel(Operation *module, unsigned &generated,
                                        unsigned maximumGenerated) {
  Model model(module, 65536, 128);
  if (failed(model.build()))
    return failure();
  uint64_t cost = 0;
  for (const auto &owner : model.owners)
    for (Operation *op : owner.joins)
      cost += 1 + op->getNumOperands();
  cost += 16 * uint64_t(model.activations.size());
  if (cost > maximumGenerated - std::min(generated, maximumGenerated))
    return reject(module, "composition expansion exceeds shared bounded preflight");
  unsigned ordinal = 0;
  for (const auto &owner : model.owners)
    for (Operation *op : owner.joins) {
      Emitter emit(op, generated, maximumGenerated,
                   text(owner.graph.header.operation, "machine_id").str() + ".join" +
                       std::to_string(ordinal++));
      Value completed = emit.literal(llvm::APInt(2, unsigned(FlatFSMLifecycle::Completed)));
      SmallVector<Value, 8> tests;
      for (unsigned i = 3; i < op->getNumOperands(); i += 2)
        tests.push_back(emit.equal(op->getOperand(i), completed));
      Value result = emit.combine(std::move(tests), text(op, "mode") == "all");
      if (!result)
        return failure();
      op->getResult(0).replaceAllUsesWith(result);
      op->erase();
    }
  for (const auto &entry : model.activations) {
    Operation *op = entry.operation;
    Emitter emit(op, generated, maximumGenerated,
                 text(model.owners[entry.parent].graph.header.operation, "machine_id").str() +
                     ".activation" + std::to_string(ordinal++));
    Value code = emit.literal(model.owners[entry.parent].graph.localCodes[entry.state]);
    Value running = emit.literal(llvm::APInt(2, unsigned(FlatFSMLifecycle::Running)));
    Value currentOwner = emit.equal(op->getOperand(0), code);
    Value nextOwner = emit.equal(op->getOperand(2), code);
    Value enter = emit.both(emit.either(op->getOperand(4), op->getOperand(6)), nextOwner);
    Value cancel = emit.both(emit.either(op->getOperand(5), op->getOperand(6)), emit.negate(enter));
    Value enable = emit.both(emit.both(emit.equal(op->getOperand(1), running), currentOwner),
                             op->getOperand(7));
    if (!enter || !cancel || !enable)
      return failure();
    Value replacements[] = {enter, cancel, enable};
    for (unsigned i = 0; i < 3; ++i)
      op->getResult(i).replaceAllUsesWith(replacements[i]);
    op->erase();
  }
  return success();
}
} // namespace nodal
