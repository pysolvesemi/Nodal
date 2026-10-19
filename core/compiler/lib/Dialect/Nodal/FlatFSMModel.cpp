#include "nodal/Dialect/Nodal/FlatFSMModel.h"

#include "mlir/IR/IRMapping.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Dialect/Nodal/StaticPredicateModel.h"

#include "llvm/ADT/SetVector.h"
#include "llvm/ADT/SmallString.h"
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
StringRef field(DictionaryAttr a, StringRef key) {
  auto s = a ? a.getAs<StringAttr>(key) : StringAttr();
  return s ? s.getValue() : StringRef();
}
StringRef path(Operation *op) {
  return field(op->getAttrOfType<DictionaryAttr>("metadata"), "semantic_path");
}
bool canonical(StringRef s) { return !s.empty() && s.size() <= 4096 && s == s.trim(); }
LogicalResult reject(Operation *op, StringRef why) {
  return op->emitOpError("NODAL-FSM-056-001: ") << why;
}
bool closed(DictionaryAttr attrs, ArrayRef<StringRef> keys) {
  return attrs && llvm::all_of(attrs, [&](NamedAttribute a) {
           return llvm::is_contained(keys, a.getName().getValue());
         });
}
Operation *binding(Operation *op) {
  Operation *module = op->getParentOfType<ModuleOp>();
  if (!module)
    return nullptr;
  Operation *result = nullptr;
  for (Operation &candidate : module->getRegion(0).front())
    if (llvm::isa<LocalDomainBindingOp>(candidate) &&
        candidate.getAttr("domain") == op->getAttr("domain")) {
      if (result)
        return nullptr;
      result = &candidate;
    }
  return result;
}
int64_t ordinal(DictionaryAttr a) {
  auto n = a ? a.getAs<IntegerAttr>("ordinal") : IntegerAttr();
  return n && n.getType().isSignlessInteger(64) ? n.getInt() : -1;
}
struct Transition {
  unsigned source, destination;
  int64_t ordinal;
};
struct Graph {
  CanonicalEnumDefinition enumeration;
  SmallVector<llvm::APInt, 4> codes;
  SmallVector<Transition, 8> transitions;
  SmallVector<SmallVector<unsigned, 4>, 4> outgoing, incoming;
  unsigned initial = 0, width = 0;
};
FailureOr<Graph> graph(const FlatFSMHeader &h) {
  Operation *op = h.operation;
  auto enumeration =
      inspectCanonicalEnumDefinition(op, op->getAttrOfType<FlatSymbolRefAttr>("enum_ref"));
  if (failed(enumeration))
    return failure();
  auto ids = op->getAttrOfType<ArrayAttr>("case_ids"),
       codes = op->getAttrOfType<ArrayAttr>("local_codes");
  auto states = op->getAttrOfType<ArrayAttr>("states"),
       transitions = op->getAttrOfType<ArrayAttr>("transitions");
  auto actions = op->getAttrOfType<ArrayAttr>("actions");
  auto width = op->getAttrOfType<IntegerAttr>("local_width");
  if (!ids || ids.empty() || ids.size() > 4096 || !codes || !states || !width ||
      !width.getType().isSignlessInteger(64) || width.getInt() < 1 || width.getInt() > 4096 ||
      ids.size() != enumeration->caseIds.size() || codes.size() != ids.size() ||
      states.size() != ids.size())
    return failure();
  Graph g{*enumeration, {}, {}, {}, {}, 0, unsigned(width.getInt())};
  llvm::StringMap<unsigned> index;
  llvm::DenseSet<Attribute> uniqueCodes;
  uint64_t codeBits = 0;
  for (unsigned i = 0; i < ids.size(); ++i) {
    if (ids[i] != enumeration->caseIds[i])
      return failure();
    index[enumeration->caseIds[i].getValue()] = i;
    auto code = llvm::dyn_cast<IntegerAttr>(codes[i]);
    auto type = code ? llvm::dyn_cast<IntegerType>(code.getType()) : IntegerType();
    if (!type || type.getWidth() > 4096 || (!type.isUnsigned() && code.getValue().isNegative()) ||
        code.getValue().getActiveBits() > g.width || (codeBits += type.getWidth()) > 16777216)
      return failure();
    llvm::APInt value = code.getValue().zextOrTrunc(g.width);
    auto canonicalCode =
        IntegerAttr::get(IntegerType::get(op->getContext(), g.width, IntegerType::Unsigned), value);
    if (!uniqueCodes.insert(canonicalCode).second)
      return failure();
    g.codes.push_back(std::move(value));
  }
  auto initial = index.find(text(op, "initial_case"));
  if (initial == index.end())
    return failure();
  g.initial = initial->second;
  StringRef requested = text(op, "requested_encoding"), resolved = text(op, "resolved_encoding");
  if ((requested == "auto" ? resolved != "auto-compact-v1" : requested != resolved) ||
      (requested != "compact" && requested != "one_hot" && requested != "gray" &&
       requested != "auto" && requested != "custom"))
    return failure();
  unsigned compact = std::max(1u, llvm::APInt(64, ids.size() - 1).getActiveBits());
  if ((requested == "one_hot" && g.width != ids.size()) ||
      ((requested == "compact" || requested == "gray" || requested == "auto") &&
       g.width != compact))
    return failure();
  for (unsigned i = 0; i < ids.size() && requested != "custom"; ++i) {
    llvm::APInt expected(g.width, requested == "gray" ? i ^ (i >> 1) : i);
    if (requested == "one_hot")
      expected = llvm::APInt::getOneBitSet(g.width, i);
    if (g.codes[i] != expected)
      return failure();
  }
  SmallVector<bool, 4> terminal(ids.size(), false);
  llvm::DenseSet<unsigned> stateIds;
  int64_t previous = -1;
  for (Attribute a : states) {
    auto state = llvm::dyn_cast<DictionaryAttr>(a);
    auto found = index.find(field(state, "case_id"));
    auto end = state ? state.getAs<BoolAttr>("terminal") : BoolAttr();
    if (!closed(state, {"case_id", "terminal", "ordinal"}) || !end || found == index.end() ||
        !stateIds.insert(found->second).second || ordinal(state) <= previous)
      return failure();
    previous = ordinal(state);
    terminal[found->second] = end.getValue();
  }
  g.outgoing.resize(ids.size());
  g.incoming.resize(ids.size());
  llvm::StringMap<StringRef> modes;
  llvm::DenseSet<int64_t> transitionOrdinals;
  previous = -1;
  unsigned remaining = 65536;
  for (unsigned i = 0; i < transitions.size(); ++i) {
    auto transition = llvm::dyn_cast<DictionaryAttr>(transitions[i]);
    auto from = index.find(field(transition, "source_case")),
         to = index.find(field(transition, "destination_case"));
    StringRef mode = field(transition, "mode");
    if (!closed(transition, {"source_case", "destination_case", "mode", "ordinal"}) ||
        from == index.end() || to == index.end() || ordinal(transition) <= previous ||
        (mode != "exclusive" && mode != "priority"))
      return failure();
    previous = ordinal(transition);
    transitionOrdinals.insert(previous);
    auto inserted = modes.try_emplace(from->getKey(), mode);
    if (!inserted.second && inserted.first->second != mode)
      return failure();
    if (terminal[from->second])
      return failure();
    if (requested == "gray" && from->second != to->second &&
        (g.codes[from->second] ^ g.codes[to->second]).popcount() != 1)
      return failure();
    if (mode == "exclusive")
      for (unsigned earlier : g.outgoing[from->second]) {
        if (!remaining)
          return failure();
        --remaining;
        auto disjoint = proveBehavioralGuardsDisjoint(op->getOperand(1 + earlier),
                                                      op->getOperand(1 + i), op, remaining);
        if (failed(disjoint))
          return failure();
        if (!*disjoint) {
          (void)reject(op, "Exclusive guards are not proved disjoint; use explicit Priority");
          return failure();
        }
      }
    g.transitions.push_back({from->second, to->second, previous});
    g.outgoing[from->second].push_back(i);
    g.incoming[to->second].push_back(i);
  }
  for (unsigned i = 0; i < ids.size(); ++i)
    if (!terminal[i] && g.outgoing[i].empty())
      return failure();
  SmallVector<bool, 4> reached(ids.size(), false);
  SmallVector<unsigned, 8> pending{g.initial};
  reached[g.initial] = true;
  for (unsigned i = 0; i < pending.size(); ++i)
    for (unsigned edge : g.outgoing[pending[i]]) {
      unsigned destination = g.transitions[edge].destination;
      if (!reached[destination]) {
        reached[destination] = true;
        pending.push_back(destination);
      }
    }
  if (pending.size() != ids.size())
    return failure();
  previous = -1;
  for (Attribute a : actions) {
    auto action = llvm::dyn_cast<DictionaryAttr>(a);
    StringRef phase = field(action, "phase");
    if (!closed(action, {"region_id", "phase", "owner_case", "transition_ordinal", "ordinal"}) ||
        ordinal(action) <= previous)
      return failure();
    previous = ordinal(action);
    if (phase == "transition") {
      auto edge = action.getAs<IntegerAttr>("transition_ordinal");
      if (action.get("owner_case") || !edge || !edge.getType().isSignlessInteger(64) ||
          !transitionOrdinals.contains(edge.getInt()))
        return failure();
    } else if (phase == "active" || phase == "entry" || phase == "exit") {
      if (action.get("transition_ordinal") ||
          index.find(field(action, "owner_case")) == index.end())
        return failure();
    } else
      return failure();
  }
  return g;
}
} // namespace
FailureOr<FlatFSMHeader> inspectFlatFSMHeader(Operation *op) {
  if (!llvm::isa_and_nonnull<FlatFSMOp>(op) || !llvm::isa<ModuleOp>(op->getParentOp()) ||
      op->getNumOperands() < 1 || op->getNumResults() != 2 || op->getNumSuccessors() ||
      text(op, "contract_version") != "flat-fsm-v1" || !canonical(text(op, "machine_id")) ||
      path(op) != text(op, "machine_id") ||
      !closed(op->getAttrDictionary(),
              {"contract_version", "machine_id", "enum_ref", "domain", "initial_case",
               "requested_encoding", "resolved_encoding", "local_width", "case_ids", "local_codes",
               "illegal_policy", "states", "transitions", "actions", "action_capture_counts",
               "metadata"}))
    return failure();
  auto transitions = op->getAttrOfType<ArrayAttr>("transitions"),
       actions = op->getAttrOfType<ArrayAttr>("actions");
  auto counts = op->getAttrOfType<DenseI32ArrayAttr>("action_capture_counts");
  auto width = op->getAttrOfType<IntegerAttr>("local_width");
  if (!transitions || !actions || transitions.size() + actions.size() > 65536 || !counts ||
      size_t(counts.size()) != actions.size() || op->getNumRegions() != actions.size() || !width ||
      !width.getType().isSignlessInteger(64) || width.getInt() < 1 || width.getInt() > 4096 ||
      1 + transitions.size() > op->getNumOperands())
    return failure();
  Type bits = BitsType::get(op->getContext(), width.getInt()),
       boolean = BitsType::get(op->getContext(), 1);
  if (op->getOperand(0).getType() != bits || op->getResult(0).getType() != bits ||
      op->getResult(1).getType() != boolean)
    return failure();
  for (unsigned i = 0; i < transitions.size(); ++i)
    if (op->getOperand(1 + i).getType() != boolean)
      return failure();
  Operation *module = op->getParentOp();
  StringRef prefix = path(module).empty() ? text(module, "sym_name") : path(module);
  if (!text(op, "machine_id").starts_with((prefix + ".").str()))
    return failure();
  FlatFSMHeader h{op, module, unsigned(transitions.size()), {}, {}, {}};
  unsigned offset = 1 + h.guards, captures = 0;
  llvm::StringSet<> identities;
  for (unsigned i = 0; i < actions.size(); ++i) {
    auto action = llvm::dyn_cast<DictionaryAttr>(actions[i]);
    StringRef id = field(action, "region_id");
    if (!canonical(id) || !id.starts_with((text(op, "machine_id") + ".").str()) ||
        !identities.insert(id).second || counts[i] < 0 || counts[i] > 4095 ||
        (captures += counts[i]) > 8192 || unsigned(counts[i]) > op->getNumOperands() - offset ||
        !op->getRegion(i).hasOneBlock())
      return failure();
    Block *body = &op->getRegion(i).front();
    if (body->getNumArguments() != unsigned(counts[i]) + 3 ||
        body->getArgument(0).getType() != bits || body->getArgument(1).getType() != bits ||
        body->getArgument(2).getType() != boolean)
      return failure();
    for (unsigned j = 0; j < unsigned(counts[i]); ++j)
      if (body->getArgument(3 + j).getType() != op->getOperand(offset + j).getType())
        return failure();
    h.bodies.push_back(body);
    h.captureOffsets.push_back(offset);
    h.captureCounts.push_back(counts[i]);
    offset += counts[i];
  }
  if (offset != op->getNumOperands())
    return failure();
  return h;
}
FailureOr<Value> flatFSMCaptureSource(BlockArgument arg) {
  auto h = inspectFlatFSMHeader(arg.getOwner()->getParentOp());
  if (failed(h))
    return failure();
  for (unsigned i = 0; i < h->bodies.size(); ++i)
    if (h->bodies[i] == arg.getOwner()) {
      unsigned n = arg.getArgNumber();
      if (n == 0)
        return h->operation->getOperand(0);
      if (n < 3)
        return Value();
      if (n - 3 < h->captureCounts[i])
        return h->operation->getOperand(h->captureOffsets[i] + n - 3);
    }
  return failure();
}
LogicalResult flatFSMResultDependencies(Value result, SmallVectorImpl<Value> &deps) {
  Operation *op = result.getDefiningOp();
  auto h = inspectFlatFSMHeader(op);
  if (failed(h))
    return failure();
  deps.append(op->operand_begin(), op->operand_begin() + 1 + h->guards);
  Operation *bound = binding(op);
  if (!bound || failed(verifyLocalDomainBinding(bound)))
    return failure();
  if (bound->getNumOperands() == 2)
    deps.push_back(bound->getOperand(1));
  return success();
}
LogicalResult flatFSMArgumentDependencies(BlockArgument argument, SmallVectorImpl<Value> &deps) {
  auto source = flatFSMCaptureSource(argument);
  if (failed(source))
    return failure();
  if (*source) {
    deps.push_back(*source);
    return success();
  }
  return flatFSMResultDependencies(
      argument.getOwner()->getParentOp()->getResult(argument.getArgNumber() - 1), deps);
}
namespace detail {
class FlatFSMIntegerEnvironmentAdmission {
public:
  static FailureOr<ParameterIntegerEnvironment> create(const FlatFSMHeader &h, Operation *use) {
    SmallVector<ParameterIntegerBinding, 8> bindings;
    for (BlockArgument arg : use->getBlock()->getArguments().drop_front(3)) {
      if (!arg.getType().isSignlessInteger(64))
        continue;
      auto source = flatFSMCaptureSource(arg);
      if (failed(source) || failed(verifyDigitalValueScope(*source, h.operation)))
        return failure();
      auto facts = probeBehavioralIntegerCapture(*source, h.operation);
      // Only actual proved static origins enter the parameter environment.
      if (failed(facts) || !facts->expression)
        continue;
      bindings.push_back({arg, *facts->expression, *source});
    }
    return ParameterIntegerEnvironment::create(h.module, use->getBlock(), use, nullptr, bindings,
                                               {});
  }
};
} // namespace detail
FailureOr<SmallVector<ParameterIntegerEnvironment, 4>> flatFSMIntegerContextsAtUse(Operation *use) {
  auto h = use ? inspectFlatFSMHeader(use->getParentOp()) : FailureOr<FlatFSMHeader>(failure());
  if (failed(h))
    return failure();
  auto context = detail::FlatFSMIntegerEnvironmentAdmission::create(*h, use);
  if (failed(context))
    return failure();
  return SmallVector<ParameterIntegerEnvironment, 4>{*context};
}
LogicalResult verifyFlatFSMOperation(Operation *op) {
  auto h = inspectFlatFSMHeader(op);
  if (failed(h))
    return reject(
        op,
        "flat FSM requires exact bounded typed operands, result roles and isolated action headers");
  auto g = graph(*h);
  if (failed(g))
    return reject(op,
                  "flat FSM graph, encoding, coverage, ordering or action ownership is invalid");
  if (text(op, "illegal_policy") != "error" && text(op, "illegal_policy") != "recover_to_initial")
    return reject(op, "flat FSM requires explicit Error or RecoverToInitial");
  Operation *state = op->getOperand(0).getDefiningOp(), *bound = binding(op);
  if (!llvm::isa_and_nonnull<DigitalStorageOp>(state) || text(state, "kind") != "register" ||
      state->getParentOp() != h->module || state->getAttr("domain") != op->getAttr("domain") ||
      !bound || failed(verifyLocalDomainBinding(bound)) ||
      failed(verifyDigitalStorageOperation(state)))
    return reject(
        op, "flat FSM requires one actual private register and exact physical domain binding");
  if ((bound->getNumOperands() == 2) != (state->getNumOperands() == 1))
    return reject(
        op,
        "FSM state resets to initial with reset and is deliberately uninitialized without reset");
  if (state->getNumOperands()) {
    Operation *initial = state->getOperand(0).getDefiningOp();
    auto code = initial ? initial->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
    if (!llvm::isa_and_nonnull<ConstLiteralOp, ConstantOp>(initial) || !code ||
        failed(verifyDigitalLiteralOperation(initial)) || code.getValue().getBitWidth() > 4096 ||
        code.getValue().zextOrTrunc(g->width) != g->codes[g->initial])
      return reject(op, "FSM initializer must be the actual exact local initial code");
  }
  for (Value value : op->getOperands())
    if (failed(verifyDigitalValueScope(value, op)))
      return reject(op, "FSM values must retain actual lexical ownership");
  for (Block *body : h->bodies)
    if (failed(verifySequentialBody(body)))
      return failure();
  return success();
}
namespace {
class Expansion {
public:
  Expansion(FlatFSMHeader h, Graph g, unsigned &generated, unsigned maximum)
      : h(std::move(h)), g(std::move(g)), generated(generated), maximum(maximum),
        builder(this->h.operation->getContext()) {}
  LogicalResult run() {
    Operation *op = h.operation;
    // Exact emitted operation count, independent of physical lane enumeration.
    // Reject before allocating even the detached expansion; charge() below is
    // an independent guard against a future emitter/preflight disagreement.
    uint64_t cost = 2 + 2 * g.codes.size() + (g.codes.size() - 1) + 1;
    cost += binding(op)->getNumOperands() == 2;
    for (const auto &edges : g.outgoing)
      if (!edges.empty())
        cost += 5 * edges.size() - 2;
    if (!g.transitions.empty())
      cost += 2 * (g.transitions.size() - 1);
    cost += 3 + (text(op, "illegal_policy") == "recover_to_initial") +
            2 * (text(op, "illegal_policy") == "error") + 2;
    auto actionRecords = op->getAttrOfType<ArrayAttr>("actions");
    cost += 2 * actionRecords.size();
    for (Attribute raw : actionRecords) {
      auto action = llvm::cast<DictionaryAttr>(raw);
      StringRef phase = field(action, "phase");
      if (phase != "entry" && phase != "exit")
        continue;
      auto found =
          llvm::find(g.enumeration.caseIds, builder.getStringAttr(field(action, "owner_case")));
      unsigned state = found - g.enumeration.caseIds.begin();
      size_t count = phase == "entry" ? g.incoming[state].size() : g.outgoing[state].size();
      if (count)
        cost += count - 1;
    }
    for (Block *body : h.bodies)
      for (Operation &child : *body)
        child.walk([&](Operation *) { ++cost; });
    llvm::SetVector<Value> externalCaptures;
    externalCaptures.insert(op->getOperand(0));
    for (Value value : op->getOperands().drop_front(1 + h.guards))
      externalCaptures.insert(value);
    if (externalCaptures.size() + 3 + actionRecords.size() > 8192 ||
        cost > maximum - std::min(maximum, generated))
      return reject(op, "FSM expansion exceeds the exact operation/capture preflight budget");
    builder.setInsertionPoint(op);
    zero = literal(llvm::APInt(1, 0));
    one = literal(llvm::APInt(1, 1));
    if (!zero || !one)
      return failure();
    Value current = op->getOperand(0);
    SmallVector<Value, 8> constants, decoded;
    for (const auto &code : g.codes) {
      Value c = literal(code);
      if (!c)
        return failure();
      constants.push_back(c);
      Value d = expression("digital_eq", {current, c}, zero.getType());
      if (!d)
        return failure();
      decoded.push_back(d);
    }
    Value valid = any(decoded), reset = zero;
    Operation *bound = binding(op);
    if (bound->getNumOperands() == 2) {
      OperationState query(op->getLoc(), "nodal.reset_asserted");
      query.addOperands(bound->getOperand(1));
      query.addTypes(zero.getType());
      query.addAttribute("domain", op->getAttr("domain"));
      query.addAttribute("reset_polarity", bound->getAttr("reset_polarity"));
      query.addAttribute("metadata", metadata(".reset"));
      Operation *made = create(query);
      if (!made)
        return failure();
      reset = made->getResult(0);
    }
    Value notReset = expression("bool_not", {reset}, zero.getType());
    SmallVector<Value, 8> selected(g.transitions.size());
    for (unsigned state = 0; state < g.outgoing.size(); ++state) {
      const auto &edges = g.outgoing[state];
      if (edges.empty())
        continue;
      struct Node {
        unsigned first, last, left = 0, right = 0;
        Value any;
      };
      SmallVector<Node, 8> tree;
      std::function<unsigned(unsigned, unsigned)> build = [&](unsigned a, unsigned b) {
        unsigned id = tree.size();
        tree.push_back({a, b, 0, 0, {}});
        if (b - a == 1)
          tree[id].any = op->getOperand(1 + edges[a]);
        else {
          unsigned m = a + (b - a) / 2, l = build(a, m), r = build(m, b);
          tree[id].left = l;
          tree[id].right = r;
          tree[id].any = expression("bool_or", {tree[l].any, tree[r].any}, zero.getType());
        }
        return id;
      };
      unsigned root = build(0, edges.size());
      std::function<void(unsigned, Value)> choose = [&](unsigned id, Value prior) {
        Node node = tree[id];
        if (node.last - node.first == 1) {
          Value guard =
              expression("bool_and", {node.any, expression("bool_not", {prior}, zero.getType())},
                         zero.getType());
          selected[edges[node.first]] =
              expression("bool_and", {decoded[state], guard}, zero.getType());
        } else {
          choose(node.left, prior);
          choose(node.right, expression("bool_or", {prior, tree[node.left].any}, zero.getType()));
        }
      };
      choose(root, zero);
    }
    if (broken)
      return failure();
    struct Choice {
      Value condition, value;
    };
    std::function<Choice(unsigned, unsigned)> select = [&](unsigned first,
                                                           unsigned last) -> Choice {
      if (first == last)
        return {zero, current};
      if (last - first == 1)
        return {selected[first], constants[g.transitions[first].destination]};
      unsigned middle = first + (last - first) / 2;
      auto a = select(first, middle), b = select(middle, last);
      return {expression("bool_or", {a.condition, b.condition}, zero.getType()),
              expression("digital_select", {a.condition, a.value, b.value}, current.getType())};
    };
    auto choice = select(0, g.transitions.size());
    Value next =
        expression("digital_select", {choice.condition, choice.value, current}, current.getType());
    if (text(op, "illegal_policy") == "recover_to_initial")
      next = expression("digital_select", {valid, next, constants[g.initial]}, current.getType());
    Value effective = expression("digital_select", {reset, constants[g.initial], next},
                                 current.getType(), ".next");
    Value taken = expression("bool_and", {notReset, choice.condition}, zero.getType(), ".taken");
    if (broken)
      return failure();
    if (text(op, "illegal_policy") == "error") {
      Value property = expression("bool_or", {reset, valid}, zero.getType());
      Operation *domain =
          SymbolTable::lookupSymbolIn(h.module, op->getAttrOfType<FlatSymbolRefAttr>("domain"));
      OperationState assertion(op->getLoc(), "nodal.sequential_assert");
      assertion.addOperands({property, bound->getOperand(0)});
      assertion.addAttribute("domain", op->getAttr("domain"));
      assertion.addAttribute("edge", domain->getAttr("edge"));
      assertion.addAttribute("root_id", builder.getStringAttr(text(op, "machine_id")));
      assertion.addAttribute("metadata", metadata(".illegal"));
      if (!create(assertion))
        return failure();
    }
    auto actions = op->getAttrOfType<ArrayAttr>("actions");
    llvm::StringMap<unsigned> states;
    for (unsigned i = 0; i < g.enumeration.caseIds.size(); ++i)
      states[g.enumeration.caseIds[i].getValue()] = i;
    llvm::DenseMap<int64_t, unsigned> transitionIds;
    for (unsigned i = 0; i < g.transitions.size(); ++i)
      transitionIds[g.transitions[i].ordinal] = i;
    SmallVector<Value, 8> conditions;
    SmallVector<unsigned, 8> order;
    auto groupAny = [&](ArrayRef<unsigned> indices) {
      SmallVector<Value, 8> values;
      for (unsigned i : indices)
        values.push_back(selected[i]);
      return any(values);
    };
    for (unsigned i = 0; i < actions.size(); ++i) {
      auto action = llvm::cast<DictionaryAttr>(actions[i]);
      StringRef phase = field(action, "phase");
      Value condition;
      if (phase == "transition")
        condition = selected[transitionIds.lookup(
            action.getAs<IntegerAttr>("transition_ordinal").getInt())];
      else {
        unsigned state = states.lookup(field(action, "owner_case"));
        condition = phase == "active"
                        ? decoded[state]
                        : groupAny(phase == "exit" ? ArrayRef<unsigned>(g.outgoing[state])
                                                   : ArrayRef<unsigned>(g.incoming[state]));
      }
      conditions.push_back(expression("bool_and", {notReset, condition}, zero.getType()));
      order.push_back(i);
    }
    auto rank = [&](unsigned i) {
      StringRef phase = field(llvm::cast<DictionaryAttr>(actions[i]), "phase");
      return phase == "active" ? 0 : phase == "exit" ? 1 : phase == "transition" ? 2 : 3;
    };
    llvm::stable_sort(order, [&](unsigned a, unsigned b) { return rank(a) < rank(b); });
    llvm::SetVector<Value> values;
    for (Value v : {current, next, effective, taken})
      values.insert(v);
    for (Value v : conditions)
      values.insert(v);
    for (unsigned i = 1 + h.guards; i < op->getNumOperands(); ++i)
      values.insert(op->getOperand(i));
    if (values.size() > 8192 || broken)
      return reject(op, "FSM root captures exceed the shared behavioral budget");
    SmallVector<Value, 8> captures(values.begin(), values.end());
    Operation *root = branch(one, captures, text(op, "machine_id"));
    if (!root)
      return failure();
    Block *body = &root->getRegion(0).front();
    IRMapping mapping;
    for (unsigned i = 0; i < captures.size(); ++i)
      mapping.map(captures[i], body->getArgument(i));
    builder.setInsertionPointToEnd(body);
    OperationState assignment(op->getLoc(), "nodal.digital_assign");
    assignment.addOperands({mapping.lookup(current), mapping.lookup(next)});
    assignment.addAttribute("kind", builder.getStringAttr("register_next"));
    assignment.addAttribute("metadata", metadata(".state_next"));
    if (!create(assignment))
      return failure();
    for (unsigned i : order) {
      SmallVector<Value, 8> inputs{mapping.lookup(current), mapping.lookup(effective),
                                   mapping.lookup(taken)};
      for (unsigned j = 0; j < h.captureCounts[i]; ++j)
        inputs.push_back(mapping.lookup(op->getOperand(h.captureOffsets[i] + j)));
      auto action = llvm::cast<DictionaryAttr>(actions[i]);
      Operation *guard = branch(mapping.lookup(conditions[i]), inputs, field(action, "region_id"));
      if (!guard)
        return failure();
      IRMapping local;
      Block *actionBody = &guard->getRegion(0).front();
      for (unsigned j = 0; j < h.bodies[i]->getNumArguments(); ++j)
        local.map(h.bodies[i]->getArgument(j), actionBody->getArgument(j));
      builder.setInsertionPointToEnd(actionBody);
      for (Operation &child : *h.bodies[i]) {
        unsigned count = 0;
        child.walk([&](Operation *) { ++count; });
        if (!charge(count))
          return failure();
        builder.clone(child, local);
      }
      builder.setInsertionPointToEnd(body);
    }
    op->getResult(0).replaceAllUsesWith(effective);
    op->getResult(1).replaceAllUsesWith(taken);
    op->erase();
    return success();
  }

private:
  FlatFSMHeader h;
  Graph g;
  unsigned &generated, maximum;
  OpBuilder builder;
  unsigned serial = 0;
  bool broken = false;
  Value zero, one;
  bool charge(unsigned count = 1) {
    if (count > maximum - std::min(maximum, generated)) {
      broken = true;
      return false;
    }
    generated += count;
    return true;
  }
  DictionaryAttr metadata(StringRef suffix) {
    NamedAttrList attrs(h.operation->getAttrOfType<DictionaryAttr>("metadata"));
    attrs.set("semantic_path",
              builder.getStringAttr((text(h.operation, "machine_id") + suffix).str()));
    return attrs.getDictionary(builder.getContext());
  }
  Operation *create(OperationState &op) {
    if (!charge())
      return nullptr;
    return builder.create(op);
  }
  Value literal(const llvm::APInt &value) {
    OperationState op(h.operation->getLoc(), "nodal.const_literal");
    op.addTypes(BitsType::get(builder.getContext(), value.getBitWidth()));
    op.addAttribute("value",
                    IntegerAttr::get(IntegerType::get(builder.getContext(), value.getBitWidth(),
                                                      IntegerType::Unsigned),
                                     value));
    llvm::SmallString<32> spelling;
    value.toString(spelling, 10, false);
    op.addAttribute("spelling", builder.getStringAttr(spelling));
    op.addAttribute("metadata", metadata(".literal" + std::to_string(serial++)));
    auto *made = create(op);
    return made ? made->getResult(0) : Value();
  }
  Value expression(StringRef opcode, ArrayRef<Value> inputs, Type type, StringRef suffix = {}) {
    if (broken || llvm::any_of(inputs, [](Value v) { return !v; })) {
      broken = true;
      return {};
    }
    OperationState op(h.operation->getLoc(), "nodal.digital_expr");
    op.addOperands(inputs);
    op.addTypes(type);
    op.addAttribute("operator_name", builder.getStringAttr(opcode));
    op.addAttribute(
        "metadata",
        metadata(suffix.empty() ? StringRef(".value" + std::to_string(serial++)) : suffix));
    auto *made = create(op);
    return made ? made->getResult(0) : Value();
  }
  Value any(ArrayRef<Value> values) {
    if (values.empty())
      return zero;
    if (values.size() == 1)
      return values.front();
    unsigned middle = values.size() / 2;
    return expression("bool_or", {any(values.take_front(middle)), any(values.drop_front(middle))},
                      zero.getType());
  }
  Operation *branch(Value condition, ArrayRef<Value> captures, StringRef id) {
    if (!condition || captures.size() > 8192)
      return nullptr;
    OperationState op(h.operation->getLoc(), "nodal.behavioral_if");
    op.addOperands(condition);
    op.addOperands(captures);
    op.addAttribute("contract_version", builder.getStringAttr("behavioral-if-v1"));
    op.addAttribute("region_id", builder.getStringAttr(id));
    op.addAttribute("operandSegmentSizes",
                    builder.getDenseI32ArrayAttr({1, int32_t(captures.size()), 0}));
    NamedAttrList attrs(h.operation->getAttrOfType<DictionaryAttr>("metadata"));
    attrs.set("semantic_path", builder.getStringAttr(id));
    op.addAttribute("metadata", attrs.getDictionary(builder.getContext()));
    auto *body = new Block;
    op.addRegion()->push_back(body);
    for (Value v : captures)
      body->addArgument(v.getType(), h.operation->getLoc());
    op.addRegion()->push_back(new Block);
    return create(op);
  }
};
} // namespace
LogicalResult expandFlatFSMModel(Operation *module, unsigned &generated, unsigned maximum) {
  SmallVector<Operation *, 8> machines;
  for (Operation &op : module->getRegion(0).front())
    if (llvm::isa<FlatFSMOp>(op))
      machines.push_back(&op);
  for (Operation *op : machines) {
    auto h = inspectFlatFSMHeader(op);
    if (failed(h) || failed(verifyFlatFSMOperation(op)))
      return failure();
    auto g = graph(*h);
    if (failed(g) || failed(Expansion(*h, *g, generated, maximum).run()))
      return failure();
  }
  return success();
}
} // namespace nodal
