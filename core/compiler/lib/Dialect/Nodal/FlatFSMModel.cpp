#include "nodal/Dialect/Nodal/FlatFSMModel.h"

#include "mlir/IR/IRMapping.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Dialect/Nodal/StaticPredicateModel.h"

#include "llvm/ADT/MapVector.h"
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
  SmallVector<bool, 4> terminal;
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
  Graph g{*enumeration, {}, {}, {}, {}, {}, 0, unsigned(width.getInt())};
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
  g.terminal.resize(ids.size(), false);
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
    g.terminal[found->second] = end.getValue();
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
    if (g.terminal[from->second])
      return failure();
    if (requested == "gray" && from->second != to->second &&
        (g.codes[from->second] ^ g.codes[to->second]).popcount() != 1)
      return failure();
    if (mode == "exclusive")
      for (unsigned earlier : g.outgoing[from->second]) {
        if (!remaining)
          return failure();
        --remaining;
        auto disjoint =
            proveBehavioralGuardsDisjoint(op->getOperand(h.guardOffset + earlier),
                                          op->getOperand(h.guardOffset + i), op, remaining);
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
    if (!g.terminal[i] && g.outgoing[i].empty())
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
struct ActionControl {
  StringRef phase;
  unsigned owner; // Transition index for transition, state index otherwise.
};
SmallVector<ActionControl, 4> actionControls(Operation *op, const Graph &g) {
  llvm::StringMap<unsigned> states;
  for (unsigned i = 0; i < g.enumeration.caseIds.size(); ++i)
    states[g.enumeration.caseIds[i].getValue()] = i;
  llvm::DenseMap<int64_t, unsigned> transitions;
  for (unsigned i = 0; i < g.transitions.size(); ++i)
    transitions[g.transitions[i].ordinal] = i;
  SmallVector<ActionControl, 4> result;
  for (Attribute a : op->getAttrOfType<ArrayAttr>("actions")) {
    auto action = llvm::cast<DictionaryAttr>(a);
    StringRef phase = field(action, "phase");
    result.push_back(
        {phase, phase == "transition"
                    ? transitions.lookup(action.getAs<IntegerAttr>("transition_ordinal").getInt())
                    : states.lookup(field(action, "owner_case"))});
  }
  return result;
}
} // namespace
static FailureOr<FlatFSMHeader> inspectFlatFSMHeaderImpl(Operation *op, bool actionHeaders) {
  if (!llvm::isa_and_nonnull<FlatFSMOp>(op))
    return failure();
  bool extended = text(op, "contract_version") == "flat-fsm-v4";
  StringRef mode = text(op, "lifecycle_mode");
  bool composed = text(op, "contract_version") == "flat-fsm-v3" || (extended && mode == "composed");
  bool controlled = composed || text(op, "contract_version") == "flat-fsm-v2" ||
                    (extended && mode == "controlled");
  auto timers = op->getAttrOfType<ArrayAttr>("timer_edges"),
       transfers = op->getAttrOfType<ArrayAttr>("transfer_edges");
  if (extended ? (!timers || !transfers || (timers.empty() && transfers.empty()) ||
                  timers.size() + transfers.size() > 65536 ||
                  (mode != "legacy" && mode != "controlled" && mode != "composed"))
               : (op->hasAttr("lifecycle_mode") || op->hasAttr("timer_edges") ||
                  op->hasAttr("transfer_edges")))
    return failure();
  bool timed = extended && !timers.empty();
  unsigned privateOffset = controlled ? 5 : 1, guardOffset = privateOffset + unsigned(timed);
  if (!llvm::isa<ModuleOp>(op->getParentOp()) || op->getNumOperands() < guardOffset ||
      op->getNumResults() != (composed     ? 5
                              : controlled ? 4
                                           : 2) ||
      op->getNumSuccessors() ||
      (!controlled && !extended && text(op, "contract_version") != "flat-fsm-v1") ||
      !canonical(text(op, "machine_id")) || path(op) != text(op, "machine_id") ||
      !closed(op->getAttrDictionary(),
              {"contract_version", "machine_id", "enum_ref", "domain", "initial_case",
               "requested_encoding", "resolved_encoding", "local_width", "case_ids", "local_codes",
               "illegal_policy", "states", "transitions", "actions", "action_capture_counts",
               "metadata", "lifecycle_mode", "timer_edges", "transfer_edges"}))
    return failure();
  auto transitions = op->getAttrOfType<ArrayAttr>("transitions"),
       actions = op->getAttrOfType<ArrayAttr>("actions");
  auto counts = op->getAttrOfType<DenseI32ArrayAttr>("action_capture_counts");
  auto width = op->getAttrOfType<IntegerAttr>("local_width");
  if (!transitions || !actions || transitions.size() + actions.size() > 65536 || !counts ||
      size_t(counts.size()) != actions.size() || op->getNumRegions() != actions.size() || !width ||
      !width.getType().isSignlessInteger(64) || width.getInt() < 1 || width.getInt() > 4096 ||
      guardOffset + transitions.size() > op->getNumOperands())
    return failure();
  Type bits = BitsType::get(op->getContext(), width.getInt()),
       boolean = BitsType::get(op->getContext(), 1);
  if (op->getOperand(0).getType() != bits || op->getResult(0).getType() != bits ||
      op->getResult(1).getType() != boolean)
    return failure();
  if (controlled && op->getOperand(1).getType() != BitsType::get(op->getContext(), 2))
    return failure();
  for (unsigned i = 1; i < op->getNumResults(); ++i)
    if (op->getResult(i).getType() != boolean)
      return failure();
  if (timed && op->getOperand(privateOffset).getType() != UIntType::get(op->getContext(), 31))
    return failure();
  if (actionHeaders)
    for (unsigned i = controlled ? 2 : 1; i < guardOffset + transitions.size(); ++i)
      if ((!timed || i != privateOffset) && op->getOperand(i).getType() != boolean)
        return failure();
  Operation *module = op->getParentOp();
  StringRef prefix = path(module).empty() ? text(module, "sym_name") : path(module);
  if (!text(op, "machine_id").starts_with((prefix + ".").str()))
    return failure();
  FlatFSMHeader h;
  h.operation = op;
  h.module = module;
  h.guards = transitions.size();
  h.guardOffset = guardOffset;
  h.controlled = controlled;
  h.composed = composed;
  h.extended = extended;
  h.timed = timed;
  if (!actionHeaders)
    return h;
  unsigned offset = h.guardOffset + h.guards, captures = 0;
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
FailureOr<FlatFSMHeader> inspectFlatFSMHeader(Operation *op) {
  return inspectFlatFSMHeaderImpl(op, true);
}
Value FlatFSMHeader::currentLocal() const { return operation->getOperand(0); }
Value FlatFSMHeader::currentStatus() const {
  return controlled ? operation->getOperand(1) : Value();
}
Value FlatFSMHeader::restart() const { return controlled ? operation->getOperand(2) : Value(); }
Value FlatFSMHeader::cancel() const { return controlled ? operation->getOperand(3) : Value(); }
Value FlatFSMHeader::enable() const { return controlled ? operation->getOperand(4) : Value(); }
Value FlatFSMHeader::elapsed() const {
  return timed ? operation->getOperand(controlled ? 5 : 1) : Value();
}
SmallVector<Value> FlatFSMHeader::privateStateOperands() const {
  SmallVector<Value> result{currentLocal()};
  if (controlled)
    result.push_back(currentStatus());
  if (timed)
    result.push_back(elapsed());
  return result;
}
SmallVector<Value> FlatFSMHeader::sampledInputs() const {
  SmallVector<Value> result;
  if (controlled)
    result.append({restart(), cancel(), enable()});
  result.append(operation->operand_begin() + guardOffset,
                operation->operand_begin() + guardOffset + guards);
  return result;
}
Value FlatFSMHeader::next() const { return operation->getResult(0); }
Value FlatFSMHeader::taken() const { return operation->getResult(1); }
Value FlatFSMHeader::entered() const { return controlled ? operation->getResult(2) : Value(); }
Value FlatFSMHeader::exited() const { return controlled ? operation->getResult(3) : Value(); }
Value FlatFSMHeader::establishment() const { return composed ? operation->getResult(4) : Value(); }
llvm::StringRef flatFSMResultRole(Value value) {
  auto result = llvm::dyn_cast<OpResult>(value);
  if (!result || failed(inspectFlatFSMHeader(result.getOwner())))
    return {};
  static constexpr const char *roles[] = {"next", "taken", "entered", "exited", "establishment"};
  return roles[result.getResultNumber()];
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
  deps.append(op->operand_begin(), op->operand_begin() + h->guardOffset + h->guards);
  auto controls = inspectSequentialDomainControls(op, op->getAttr("domain"));
  if (failed(controls))
    return failure();
  // Contextual reset intent is also retained by the occurrence dependency
  // owner; a missing local Reset projection never means resetless.
  if (!controls->contextual() && controls->reset)
    deps.push_back(controls->reset);
  return success();
}
LogicalResult flatFSMActionControlDependencies(Operation *op,
                                               SmallVectorImpl<SmallVector<Value, 4>> &dependencies,
                                               unsigned &work, unsigned maximumWork) {
  auto h = inspectFlatFSMHeader(op);
  if (failed(h))
    return failure();
  auto g = graph(*h);
  if (failed(g))
    return failure();
  auto domainControls = inspectSequentialDomainControls(op, op->getAttr("domain"));
  if (failed(domainControls))
    return failure();
  auto charge = [&]() {
    if (++work > maximumWork)
      return reject(op, "FSM action dependency roots exceed finite sampling budget");
    return success();
  };
  // A selected edge depends on its source's priority prefix. For entry actions,
  // coalesce the latest selected edge per source before expanding each prefix.
  // This avoids quadratic prefix unions for high-fan-in actions.
  for (auto control : actionControls(op, *g)) {
    SmallVector<Value, 4> roots;
    if (failed(charge()))
      return failure();
    roots.push_back(op->getOperand(0));
    for (unsigned i = 1; i < h->guardOffset; ++i) {
      if (failed(charge()))
        return failure();
      roots.push_back(op->getOperand(i));
    }
    if (!domainControls->contextual() && domainControls->reset) {
      if (failed(charge()))
        return failure();
      roots.push_back(domainControls->reset);
    }
    llvm::MapVector<unsigned, unsigned> lastBySource;
    auto selected = [&](unsigned edge) {
      unsigned source = g->transitions[edge].source;
      auto found = lastBySource.find(source);
      if (found == lastBySource.end())
        lastBySource.insert({source, edge});
      else
        found->second = std::max(found->second, edge);
    };
    if (control.phase == "transition")
      selected(control.owner);
    else if (control.phase != "active") {
      auto &edges =
          control.phase == "exit" ? g->outgoing[control.owner] : g->incoming[control.owner];
      for (unsigned edge : edges) {
        if (failed(charge()))
          return failure();
        selected(edge);
      }
    }
    for (auto [source, last] : lastBySource)
      for (unsigned edge : g->outgoing[source]) {
        if (edge > last)
          break;
        if (failed(charge()))
          return failure();
        roots.push_back(op->getOperand(h->guardOffset + edge));
      }
    dependencies.push_back(std::move(roots));
  }
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
namespace {
bool timingOperation(Operation *op) {
  return op && (op->getName().getStringRef() == "nodal.fsm_elapsed" ||
                op->getName().getStringRef() == "nodal.fsm_transfer");
}
bool elapsedOperation(Operation *op) { return op->getName().getStringRef() == "nodal.fsm_elapsed"; }
LogicalResult timingShape(Operation *op) {
  bool elapsed = elapsedOperation(op);
  auto cycles = op->getAttrOfType<IntegerAttr>("cycles");
  if (!llvm::isa<ModuleOp>(op->getParentOp()) || op->getNumResults() != 1 ||
      op->getNumOperands() != (elapsed ? 1 : 2) || op->getNumRegions() || op->getNumSuccessors() ||
      op->getResult(0).getType() != BitsType::get(op->getContext(), 1) ||
      !op->getAttrOfType<FlatSymbolRefAttr>("domain") ||
      !closed(op->getAttrDictionary(), {"domain", "cycles", "metadata"}) ||
      (op->hasAttr("metadata") && !op->getAttrOfType<DictionaryAttr>("metadata")) ||
      (elapsed ? (!cycles || !cycles.getType().isSignlessInteger(64) || cycles.getInt() < 1 ||
                  cycles.getInt() > 2147483647)
               : op->hasAttr("cycles")))
    return reject(op, "timing predicate requires its closed bounded typed shape");
  for (Value input : op->getOperands())
    if (input.getType() != (elapsed ? Type(UIntType::get(op->getContext(), 31))
                                    : Type(BitsType::get(op->getContext(), 1))) ||
        failed(verifyDigitalValueScope(input, op)))
      return reject(op, "timing predicate requires exact owned UInt31 or Bool operands");
  return success();
}
LogicalResult timingTables(const FlatFSMHeader &h) {
  if (!h.extended)
    return success();
  auto edges = h.operation->getAttrOfType<ArrayAttr>("transitions");
  llvm::DenseMap<int64_t, unsigned> indices;
  for (auto [index, raw] : llvm::enumerate(edges)) {
    auto record = llvm::dyn_cast<DictionaryAttr>(raw);
    if (!record || ordinal(record) < 0 || !indices.try_emplace(ordinal(record), index).second)
      return reject(h.operation, "timing owner requires distinct actual transition ordinals");
  }
  llvm::DenseSet<int64_t> retained;
  for (StringRef name : {"timer_edges", "transfer_edges"}) {
    bool elapsed = name == "timer_edges";
    int64_t previous = -1;
    for (Attribute raw : h.operation->getAttrOfType<ArrayAttr>(name)) {
      auto entry = llvm::dyn_cast<DictionaryAttr>(raw);
      auto found = indices.find(ordinal(entry));
      auto cycles = entry ? entry.getAs<IntegerAttr>("cycles") : IntegerAttr();
      if (!entry ||
          !closed(entry, elapsed ? ArrayRef<StringRef>{"ordinal", "cycles"}
                                 : ArrayRef<StringRef>{"ordinal"}) ||
          found == indices.end() || ordinal(entry) <= previous ||
          !retained.insert(ordinal(entry)).second ||
          (elapsed && (!cycles || !cycles.getType().isSignlessInteger(64) || cycles.getInt() < 1 ||
                       cycles.getInt() > 2147483647)))
        return reject(
            h.operation,
            "timing table requires distinct actual edge ordinals and positive bounded cycles");
      previous = ordinal(entry);
      Operation *query = h.operation->getOperand(h.guardOffset + found->second).getDefiningOp();
      if (!timingOperation(query) || elapsedOperation(query) != elapsed ||
          failed(timingShape(query)) ||
          query->getAttr("domain") != h.operation->getAttr("domain") ||
          query->getParentOp() != h.module ||
          (elapsed && (query->getOperand(0) != h.elapsed() || query->getAttr("cycles") != cycles)))
        return reject(
            h.operation,
            "timing table must retain the exact predicate, counter and domain of its actual guard");
    }
  }
  for (unsigned index = 0; index < h.guards; ++index)
    if (timingOperation(h.operation->getOperand(h.guardOffset + index).getDefiningOp()) &&
        !retained.contains(ordinal(llvm::cast<DictionaryAttr>(edges[index]))))
      return reject(h.operation, "timing guard has no exact retained edge table entry");
  return success();
}
} // namespace
LogicalResult verifyFsmTimingOperation(Operation *op) {
  if (!timingOperation(op))
    return failure();
  if (failed(timingShape(op)))
    return failure();
  Operation *owner = nullptr;
  unsigned work = 0;
  for (OpOperand &use : op->getResult(0).getUses()) {
    if (++work > 65536)
      return reject(op, "timing predicate use budget exceeded");
    // Full action and table admission belongs to the actual FSM verifier. The
    // predicate only needs the canonical operand layout and one sorted table lookup.
    auto h = inspectFlatFSMHeaderImpl(use.getOwner(), false);
    if (failed(h) || !h->extended || (owner && owner != h->operation) ||
        use.getOperandNumber() < h->guardOffset ||
        use.getOperandNumber() >= h->guardOffset + h->guards || op->getParentOp() != h->module ||
        op->getAttr("domain") != h->operation->getAttr("domain"))
      return reject(op,
                    "timing predicate may only feed guard operands of its one actual FSM owner");
    owner = h->operation;
    auto edges = owner->getAttrOfType<ArrayAttr>("transitions");
    int64_t edge =
        ordinal(llvm::dyn_cast<DictionaryAttr>(edges[use.getOperandNumber() - h->guardOffset]));
    auto table =
        owner->getAttrOfType<ArrayAttr>(elapsedOperation(op) ? "timer_edges" : "transfer_edges");
    auto found = llvm::lower_bound(table, edge, [](Attribute raw, int64_t value) {
      return ordinal(llvm::dyn_cast<DictionaryAttr>(raw)) < value;
    });
    auto record = found == table.end() ? DictionaryAttr() : llvm::dyn_cast<DictionaryAttr>(*found);
    if (edge < 0 || !record || ordinal(record) != edge ||
        (elapsedOperation(op) &&
         (record.get("cycles") != op->getAttr("cycles") || op->getOperand(0) != h->elapsed())))
      return reject(op,
                    "timing predicate is not the exact retained guard/counter policy of its owner");
  }
  return owner ? success() : reject(op, "timing predicate requires an actual transition owner");
}
LogicalResult fsmTimingDependencies(Value value, SmallVectorImpl<Value> &dependencies) {
  auto result = llvm::dyn_cast<OpResult>(value);
  if (!result || !timingOperation(result.getOwner()) ||
      failed(verifyFsmTimingOperation(result.getOwner())))
    return failure();
  dependencies.append(result.getOwner()->operand_begin(), result.getOwner()->operand_end());
  return success();
}
static LogicalResult verifyFlatFSMWithGraph(const FlatFSMHeader &header, const Graph &value) {
  const auto *h = &header;
  const auto *g = &value;
  Operation *op = header.operation;
  if (text(op, "illegal_policy") != "error" && text(op, "illegal_policy") != "recover_to_initial")
    return reject(op, "flat FSM requires explicit Error or RecoverToInitial");
  auto controls = inspectSequentialDomainControls(op, op->getAttr("domain"));
  if (failed(controls))
    return reject(op, "flat FSM requires an exact local binding or unresolved requirement");
  llvm::DenseSet<Value> privateValues;
  for (Value storage : h->privateStateOperands()) {
    Operation *state = storage.getDefiningOp();
    if (!llvm::isa_and_nonnull<DigitalStorageOp>(state) || text(state, "kind") != "register" ||
        state->getParentOp() != h->module || state->getAttr("domain") != op->getAttr("domain") ||
        failed(verifyDigitalStorageOperation(state)) || !privateValues.insert(storage).second)
      return reject(op, "flat FSM requires distinct actual private registers in its exact domain");
    bool initialized = controls->resetAvailability != SequentialResetAvailability::Absent;
    if (initialized != (state->getNumOperands() == 1) ||
        (controls->contextual() ? text(state, "initializer_policy") != "if_reset"
                                : state->hasAttr("initializer_policy")))
      return reject(op,
                    "FSM storage requires exact physical or conditional contextual initialization");
    if (state->getNumOperands()) {
      Operation *initial = state->getOperand(0).getDefiningOp();
      auto code = initial ? initial->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
      bool local = storage == h->currentLocal();
      unsigned width = local ? g->width : storage == h->elapsed() ? 31 : 2;
      llvm::APInt expected = local ? g->codes[g->initial] : llvm::APInt(width, 0);
      if (!llvm::isa_and_nonnull<ConstLiteralOp, ConstantOp>(initial) || !code ||
          failed(verifyDigitalLiteralOperation(initial)) || code.getValue().getBitWidth() > 4096 ||
          code.getValue().zextOrTrunc(width) != expected)
        return reject(op, local ? "FSM initializer must be the actual exact local initial code"
                                : "FSM lifecycle/timer initializer must be exact zero");
    }
  }
  if (failed(timingTables(header)))
    return failure();
  llvm::DenseSet<Operation *> timingQueries;
  for (Value value : header.sampledInputs())
    if (timingOperation(value.getDefiningOp()) &&
        timingQueries.insert(value.getDefiningOp()).second &&
        failed(verifyFsmTimingOperation(value.getDefiningOp())))
      return failure();
  for (Value value : op->getOperands())
    if (failed(verifyDigitalValueScope(value, op)))
      return reject(op, "FSM values must retain actual lexical ownership");
  for (Block *body : h->bodies)
    if (failed(verifySequentialBody(body)))
      return failure();
  return success();
}
FailureOr<FlatFSMGraphSummary> inspectFlatFSMGraphSummary(Operation *op) {
  auto h = inspectFlatFSMHeader(op);
  if (failed(h)) {
    (void)reject(
        op,
        "flat FSM requires exact bounded typed operands, result roles and isolated action headers");
    return failure();
  }
  auto g = graph(*h);
  if (failed(g)) {
    (void)reject(op, "flat FSM graph, encoding, coverage, ordering or action ownership is invalid");
    return failure();
  }
  if (failed(verifyFlatFSMWithGraph(*h, *g)))
    return failure();
  FlatFSMGraphSummary summary;
  summary.header = std::move(*h);
  summary.caseIds.append(g->enumeration.caseIds.begin(), g->enumeration.caseIds.end());
  summary.localCodes.append(g->codes.begin(), g->codes.end());
  summary.terminal.append(g->terminal.begin(), g->terminal.end());
  summary.initial = g->initial;
  summary.width = g->width;
  return summary;
}
LogicalResult verifyFlatFSMOperation(Operation *op) {
  return success(succeeded(inspectFlatFSMGraphSummary(op)));
}
namespace {
class Expansion {
public:
  Expansion(FlatFSMHeader h, Graph g, unsigned &generated, unsigned maximum)
      : h(std::move(h)), g(std::move(g)), generated(generated), maximum(maximum),
        builder(this->h.operation->getContext()) {}
  LogicalResult run() {
    Operation *op = h.operation;
    auto domainControls = inspectSequentialDomainControls(op, op->getAttr("domain"));
    if (failed(domainControls))
      return reject(op, "FSM expansion lost its exact domain control admission");
    // Exact emitted operation count, independent of physical lane enumeration.
    // Reject before allocating even the detached expansion; charge() below is
    // an independent guard against a future emitter/preflight disagreement.
    uint64_t cost = 2 + 2 * g.codes.size() + (g.codes.size() - 1) + 1;
    cost += domainControls->resetAvailability != SequentialResetAvailability::Absent;
    cost += domainControls->contextual() && !domainControls->clock &&
            text(op, "illegal_policy") == "error";
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
    // The v2 control network has a conservative structural upper bound rather
    // than a promised minimal count. Each input collection is independently
    // bounded by header/graph admission; checked addition cannot overflow.
    if (h.controlled) {
      uint64_t extra = 128 + 4 * uint64_t(g.codes.size()) + 4 * uint64_t(actionRecords.size()) +
                       (h.composed ? 2 : 0);
      if (cost > maximum || extra > uint64_t(maximum) - cost)
        return reject(op, "FSM expansion exceeds its bounded lifecycle preflight budget");
      cost += extra;
    }
    if (h.extended) {
      uint64_t extra = 3 * uint64_t(op->getAttrOfType<ArrayAttr>("timer_edges").size()) +
                       uint64_t(op->getAttrOfType<ArrayAttr>("transfer_edges").size()) +
                       (h.timed ? 128 + 4 * uint64_t(g.codes.size()) : 0);
      if (cost > maximum || extra > uint64_t(maximum) - cost)
        return reject(op,
                      "FSM timing expansion exceeds its conservative structural preflight budget");
      cost += extra;
    }
    llvm::SetVector<Value> externalCaptures;
    externalCaptures.insert(op->getOperand(0));
    if (h.controlled)
      externalCaptures.insert(op->getOperand(1));
    if (h.timed)
      externalCaptures.insert(h.elapsed());
    for (Value value : op->getOperands().drop_front(h.guardOffset + h.guards))
      externalCaptures.insert(value);
    if (externalCaptures.size() + (h.controlled ? 4 : 3) + unsigned(h.timed) +
                actionRecords.size() >
            8192 ||
        cost > maximum - std::min(maximum, generated))
      return reject(op, "FSM expansion exceeds the exact operation/capture preflight budget");
    builder.setInsertionPoint(op);
    zero = literal(llvm::APInt(1, 0));
    one = literal(llvm::APInt(1, 1));
    if (!zero || !one)
      return failure();
    if (h.extended) {
      llvm::SetVector<Operation *> queries;
      for (Value guard : op->getOperands().slice(h.guardOffset, h.guards))
        if (timingOperation(guard.getDefiningOp()))
          queries.insert(guard.getDefiningOp());
      for (Operation *query : queries) {
        Value predicate;
        if (elapsedOperation(query)) {
          auto cycles = query->getAttrOfType<IntegerAttr>("cycles").getInt();
          Value threshold =
              literal(llvm::APInt(31, cycles - 1), UIntType::get(builder.getContext(), 31));
          predicate = expression("digital_ge", {query->getOperand(0), threshold}, zero.getType());
        } else
          predicate = both(query->getOperand(0), query->getOperand(1));
        if (!predicate)
          return failure();
        query->getResult(0).replaceAllUsesWith(predicate);
        query->erase();
      }
    }
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
    if (domainControls->resetAvailability != SequentialResetAvailability::Absent) {
      OperationState query(op->getLoc(), "nodal.reset_asserted");
      if (!domainControls->contextual())
        query.addOperands(domainControls->reset);
      query.addTypes(zero.getType());
      query.addAttribute("domain", op->getAttr("domain"));
      if (!domainControls->contextual())
        query.addAttribute("reset_polarity", domainControls->binding->getAttr("reset_polarity"));
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
          tree[id].any = op->getOperand(h.guardOffset + edges[a]);
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
    Value actionGate = notReset, restarting = zero, cancelling = zero, recovering = zero;
    Value lifecycle, lifecycleNext, illegalProperty = valid;
    if (h.controlled) {
      lifecycle = op->getOperand(1);
      Value runningCode = literal(llvm::APInt(2, unsigned(FlatFSMLifecycle::Running))),
            completedCode = literal(llvm::APInt(2, unsigned(FlatFSMLifecycle::Completed))),
            cancelledCode = literal(llvm::APInt(2, unsigned(FlatFSMLifecycle::Cancelled)));
      Value running = eq(lifecycle, runningCode), completed = eq(lifecycle, completedCode);
      SmallVector<Value, 4> terminalStates;
      for (unsigned i = 0; i < g.terminal.size(); ++i)
        if (g.terminal[i])
          terminalStates.push_back(decoded[i]);
      Value terminal = any(terminalStates);
      Value operational = either(running, completed);
      Value legal = both(valid, either(negate(completed), terminal));
      Value cancelSelected = both(running, op->getOperand(3));
      Value restartSelected = both(op->getOperand(2), negate(cancelSelected));
      restarting = both(notReset, restartSelected);
      cancelling = both(notReset, cancelSelected);
      Value ordinary =
          both(both(negate(cancelSelected), negate(op->getOperand(2))), op->getOperand(4));
      Value advance = both(ordinary, running);
      actionGate = both(notReset, both(advance, valid));
      Value initialStatus = g.terminal[g.initial] ? completedCode : runningCode;
      SmallVector<Value, 4> nextTerminals;
      for (unsigned i = 0; i < g.terminal.size(); ++i)
        if (g.terminal[i])
          nextTerminals.push_back(eq(next, constants[i]));
      Value advancedStatus = selectValue(any(nextTerminals), completedCode, runningCode);
      lifecycleNext = selectValue(advance, advancedStatus, lifecycle);
      next = selectValue(advance, next, current);
      if (text(op, "illegal_policy") == "recover_to_initial") {
        Value recover = both(ordinary, both(operational, negate(legal)));
        if (h.composed || h.timed)
          recovering = both(notReset, recover);
        next = selectValue(recover, constants[g.initial], next);
        lifecycleNext = selectValue(recover, initialStatus, lifecycleNext);
      }
      next = selectValue(restartSelected, constants[g.initial], next);
      lifecycleNext = selectValue(restartSelected, initialStatus, lifecycleNext);
      lifecycleNext = selectValue(cancelSelected, cancelledCode, lifecycleNext);
      illegalProperty =
          either(either(restartSelected, cancelSelected), either(negate(operational), legal));
    }
    if (h.timed && !h.controlled && text(op, "illegal_policy") == "recover_to_initial")
      recovering = both(notReset, negate(valid));
    Value effective = expression("digital_select", {reset, constants[g.initial], next},
                                 current.getType(), ".next");
    Value taken = expression("bool_and", {actionGate, choice.condition}, zero.getType(), ".taken");
    Value entered = h.controlled ? either(taken, restarting) : taken;
    Value exited = h.controlled ? either(taken, cancelling) : taken;
    Value establishment = h.composed ? expression("bool_or", {restarting, recovering},
                                                  zero.getType(), ".establishment")
                                     : Value();
    Value elapsedNext;
    if (h.timed) {
      llvm::DenseSet<int64_t> timedEdges;
      for (Attribute raw : op->getAttrOfType<ArrayAttr>("timer_edges"))
        timedEdges.insert(ordinal(llvm::cast<DictionaryAttr>(raw)));
      llvm::DenseSet<unsigned> timedStates;
      for (const auto &edge : g.transitions)
        if (timedEdges.contains(edge.ordinal))
          timedStates.insert(edge.source);
      SmallVector<Value> states;
      for (unsigned state = 0; state < decoded.size(); ++state)
        if (timedStates.contains(state))
          states.push_back(decoded[state]);
      Value clear = either(reset, either(entered, either(cancelling, recovering)));
      Value advance = both(actionGate, any(states));
      Type counterType = UIntType::get(builder.getContext(), 31);
      Value initial = literal(llvm::APInt(31, 0), counterType);
      Value limit = literal(llvm::APInt(31, 2147483646), counterType);
      Value unit = literal(llvm::APInt(31, 1), counterType);
      Value saturated = expression("digital_ge", {h.elapsed(), limit}, zero.getType());
      Value sum =
          expression("digital_add", {h.elapsed(), unit}, UIntType::get(builder.getContext(), 32));
      Value incremented = expression("digital_truncate", {sum}, counterType);
      Value increment = selectValue(saturated, limit, incremented);
      elapsedNext = selectValue(clear, initial, selectValue(advance, increment, h.elapsed()));
    }
    if (broken)
      return failure();
    if (text(op, "illegal_policy") == "error") {
      Value property = expression("bool_or", {reset, illegalProperty}, zero.getType());
      if (!domainControls->clock && !charge())
        return failure();
      auto clock = getOrCreateSequentialDomainClock(op, builder, metadata(".clock"));
      if (failed(clock))
        return failure();
      OperationState assertion(op->getLoc(), "nodal.sequential_assert");
      assertion.addOperands({property, *clock});
      assertion.addAttribute("domain", op->getAttr("domain"));
      if (!domainControls->contextual())
        assertion.addAttribute("edge", domainControls->declaration->getAttr("edge"));
      assertion.addAttribute("root_id", builder.getStringAttr(text(op, "machine_id")));
      assertion.addAttribute("metadata", metadata(".illegal"));
      if (!create(assertion))
        return failure();
    }
    auto actions = op->getAttrOfType<ArrayAttr>("actions");
    auto controls = actionControls(op, g);
    SmallVector<Value, 8> conditions;
    SmallVector<unsigned, 8> order;
    auto groupAny = [&](ArrayRef<unsigned> indices) {
      SmallVector<Value, 8> values;
      for (unsigned i : indices)
        values.push_back(selected[i]);
      return any(values);
    };
    for (unsigned i = 0; i < actions.size(); ++i) {
      auto control = controls[i];
      Value condition =
          control.phase == "transition" ? selected[control.owner]
          : control.phase == "active"
              ? decoded[control.owner]
              : groupAny(control.phase == "exit" ? ArrayRef<unsigned>(g.outgoing[control.owner])
                                                 : ArrayRef<unsigned>(g.incoming[control.owner]));
      condition = both(actionGate, condition);
      if (h.controlled && control.phase == "entry" && control.owner == g.initial)
        condition = either(condition, restarting);
      if (h.controlled && control.phase == "exit")
        condition = either(condition, both(cancelling, decoded[control.owner]));
      conditions.push_back(condition);
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
    if (h.controlled) {
      values.insert(lifecycle);
      values.insert(lifecycleNext);
    }
    if (h.timed) {
      values.insert(h.elapsed());
      values.insert(elapsedNext);
    }
    for (Value v : conditions)
      values.insert(v);
    for (unsigned i = h.guardOffset + h.guards; i < op->getNumOperands(); ++i)
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
    if (h.controlled) {
      OperationState statusAssignment(op->getLoc(), "nodal.digital_assign");
      statusAssignment.addOperands({mapping.lookup(lifecycle), mapping.lookup(lifecycleNext)});
      statusAssignment.addAttribute("kind", builder.getStringAttr("register_next"));
      statusAssignment.addAttribute("metadata", metadata(".status_next"));
      if (!create(statusAssignment))
        return failure();
    }
    if (h.timed) {
      OperationState timerAssignment(op->getLoc(), "nodal.digital_assign");
      timerAssignment.addOperands({mapping.lookup(h.elapsed()), mapping.lookup(elapsedNext)});
      timerAssignment.addAttribute("kind", builder.getStringAttr("register_next"));
      timerAssignment.addAttribute("metadata", metadata(".elapsed_next"));
      if (!create(timerAssignment))
        return failure();
    }
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
    if (h.controlled) {
      op->getResult(2).replaceAllUsesWith(entered);
      op->getResult(3).replaceAllUsesWith(exited);
      if (h.composed)
        op->getResult(4).replaceAllUsesWith(establishment);
    }
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
  Value both(Value a, Value b) { return expression("bool_and", {a, b}, zero.getType()); }
  Value either(Value a, Value b) { return expression("bool_or", {a, b}, zero.getType()); }
  Value negate(Value a) { return expression("bool_not", {a}, zero.getType()); }
  Value eq(Value a, Value b) { return expression("digital_eq", {a, b}, zero.getType()); }
  Value selectValue(Value condition, Value a, Value b) {
    return expression("digital_select", {condition, a, b}, a.getType());
  }
  Value literal(const llvm::APInt &value, Type type = {}) {
    OperationState op(h.operation->getLoc(), "nodal.const_literal");
    op.addTypes(type ? type : BitsType::get(builder.getContext(), value.getBitWidth()));
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
  uint64_t timerBits = 0;
  for (Operation *op : machines) {
    auto h = inspectFlatFSMHeader(op);
    if (failed(h) || (timerBits += h->timed ? 31 : 0) > 65536)
      return reject(op, "actual private timer state exceeds its finite bit budget");
  }
  for (Operation *op : machines) {
    auto h = inspectFlatFSMHeader(op);
    if (failed(h))
      return failure();
    auto g = graph(*h);
    if (failed(g) || failed(verifyFlatFSMWithGraph(*h, *g)) ||
        failed(Expansion(*h, *g, generated, maximum).run()))
      return failure();
  }
  return success();
}
} // namespace nodal
