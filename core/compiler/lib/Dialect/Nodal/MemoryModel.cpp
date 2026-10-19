#include "nodal/Dialect/Nodal/MemoryModel.h"

#include "mlir/IR/IRMapping.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Dialect/Nodal/StaticPredicateModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/MapVector.h"
#include "llvm/ADT/StringSet.h"

#include <limits>
using namespace mlir;
namespace nodal {
namespace {
StringRef text(Operation *op, StringRef key) {
  auto a = op ? op->getAttrOfType<StringAttr>(key) : StringAttr();
  return a ? a.getValue() : StringRef();
}
StringRef path(Operation *op) {
  auto a = op->getAttrOfType<DictionaryAttr>("metadata");
  auto p = a ? a.getAs<StringAttr>("semantic_path") : StringAttr();
  return p ? p.getValue() : StringRef();
}
bool canonical(StringRef s) { return !s.empty() && s.size() <= 4096 && s.trim() == s; }
LogicalResult reject(Operation *op, StringRef message) {
  return op->emitOpError("NODAL-MEMORY-056-001: ") << message;
}
bool closed(Operation *op, ArrayRef<StringRef> fields) {
  return llvm::all_of(op->getAttrs(), [&](NamedAttribute a) {
    return llvm::is_contained(fields, a.getName().getValue());
  });
}
bool uintType(Type t) {
  auto p = llvm::dyn_cast<ParametricIntegerType>(t);
  return llvm::isa<UIntType>(t) || (p && p.getKind() == "uint");
}
bool maskType(Type t) {
  auto p = llvm::dyn_cast<ParametricIntegerType>(t);
  return llvm::isa<BitsType, UIntType>(t) ||
         (p && (p.getKind() == "uint" || p.getKind() == "bits"));
}
/// The common digital owner follows exact captures and closed expression DAGs.
/// A runtime value or proof-limit unknown is never replaced by a default.
IntegerAttr literal(Value value, Operation *use) {
  auto fact = inferDigitalConstantInteger(value, use);
  if (failed(fact) || !*fact || (**fact).isNegative())
    return {};
  return IntegerAttr::get(
      IntegerType::get(use->getContext(), (**fact).getBitWidth(), IntegerType::Unsigned), **fact);
}
bool nonnegative(IntegerAttr a) {
  return a && (!a.getType().isSignedInteger() || !a.getValue().isNegative());
}
LogicalResult address(Value value, Operation *port, Operation *module, ParameterIntegerBounds depth,
                      const ParameterIntegerEnvironment *environment) {
  if (!uintType(value.getType()))
    return reject(port, "address requires its actual UInt type without truncation or wrapping");
  auto width = inferDigitalWidthBounds(value.getType(), module, environment);
  if (failed(width))
    return reject(port, "address width lacks an all-legal owned proof");
  if (auto a = literal(value, port)) {
    if (!nonnegative(a) || a.getValue().getActiveBits() > 63 ||
        a.getValue().getZExtValue() >= uint64_t(depth.lower))
      return reject(port, "constant address is not in range for every legal depth");
    return success();
  }
  // Finite conservative envelope, never a minimal-address-width requirement.
  if (width->upper >= 63 || width->lower <= 0 ||
      ((uint64_t(1) << width->upper) - 1) >= uint64_t(depth.lower))
    return reject(port, "runtime address full UInt envelope does not fit every legal depth");
  return success();
}
FailureOr<MemoryMaskKind> mask(Value value, Type element, Operation *port, Operation *module,
                               const ParameterIntegerEnvironment *environment) {
  if (!maskType(value.getType()))
    return failure();
  auto width = inferDigitalWidthExpression(value.getType(), module, environment);
  if (failed(width))
    return failure();
  if (width->bounds.lower == 1 && width->bounds.upper == 1)
    return MemoryMaskKind::WholeElement;
  if (!isDigitalScalarType(element))
    return failure();
  auto word = inferDigitalWidthExpression(element, module, environment);
  auto bytes = composeIntegerExpression("mul", {*width, makeLiteralIntegerExpression(8)});
  if (failed(word) || failed(bytes) || !proveIntegerExpressionLess(*word, *bytes, true) ||
      !proveIntegerExpressionLess(*bytes, *word, true))
    return failure();
  return MemoryMaskKind::Bytes;
}
struct PairFacts {
  MemoryMaskKind maskKind;
  IntegerAttr address, mask;
};
FailureOr<bool> disjoint(Operation *a, Operation *b, const PairFacts &left, const PairFacts &right,
                         unsigned &work) {
  // All operands have already been independently verified, including inactive
  // ports. Constant-zero masks are inactive regardless of source guard.
  auto am = left.mask, bm = right.mask;
  if ((am && am.getValue().isZero()) || (bm && bm.getValue().isZero()))
    return true;
  auto aa = left.address, ba = right.address;
  if (aa && ba && aa.getValue().zextOrTrunc(64) != ba.getValue().zextOrTrunc(64))
    return true;
  if (am && bm && left.maskKind == MemoryMaskKind::Bytes &&
      right.maskKind == MemoryMaskKind::Bytes) {
    unsigned width = std::max(am.getValue().getBitWidth(), bm.getValue().getBitWidth());
    if ((am.getValue().zext(width) & bm.getValue().zext(width)).isZero())
      return true;
  }
  return proveBehavioralGuardsDisjoint(a->getOperand(3), b->getOperand(3), b, work);
}
} // namespace

FailureOr<MemoryPortFacts> inspectMemoryPort(Operation *op) {
  bool read = llvm::isa_and_nonnull<MemoryReadOp>(op);
  bool effect = llvm::isa_and_nonnull<MemoryWriteEffectOp>(op);
  if (!read && !effect && !llvm::isa_and_nonnull<MemoryWriteOp>(op))
    return failure();
  auto bad = [&](StringRef message) -> FailureOr<MemoryPortFacts> {
    (void)reject(op, message);
    return failure();
  };
  Operation *module = op->getParentOfType<nodal::ModuleOp>();
  if (!module ||
      op->getNumOperands() != (read     ? 1u
                               : effect ? 5u
                                        : 3u) ||
      op->getNumResults() != (read ? 1u : 0u) || op->getNumRegions() || op->getNumSuccessors() ||
      ((read || effect) && op->getParentOp() != module) ||
      (!read && !effect &&
       !llvm::isa<ModuleOp, BehavioralIfOp, FlatFSMOp, EnumSwitchOp>(op->getParentOp())) ||
      !canonical(path(op)) ||
      !closed(op, effect ? ArrayRef<StringRef>{"memory", "domain", "edge", "order", "root_id",
                                               "metadata"}
                         : ArrayRef<StringRef>{"memory", "domain", "metadata"}))
    return bad("port requires exact owned typed arity, scope and closed fields");
  auto ref = op->getAttrOfType<FlatSymbolRefAttr>("memory");
  Operation *memory = ref ? SymbolTable::lookupSymbolIn(module, ref) : nullptr;
  if (!llvm::isa_and_nonnull<MemoryOp>(memory) || memory->getParentOp() != module ||
      failed(verifyDigitalMemoryOperation(memory)))
    return bad("memory reference must identify the actual sole local typed declaration");
  auto domain = resolveSequentialDomain(op, memory->getAttr("domain"));
  auto binding = resolveSequentialBinding(op, memory->getAttr("domain"));
  if (failed(domain) || failed(binding) ||
      op->getAttr("domain") != FlatSymbolRefAttr::get(op->getContext(), text(*domain, "sym_name")))
    return bad("port requires the memory's uniquely bound actual physical local domain");
  auto element = memory->getAttrOfType<TypeAttr>("element_type").getValue();
  auto depth = resolveStaticShapeDimension(module, text(memory, "depth"));
  if (failed(depth))
    return failure();
  std::optional<ParameterIntegerEnvironment> context;
  if (op->getParentOp() != module) {
    auto contexts = digitalIntegerContextsAtUse(op);
    if (failed(contexts) || contexts->size() != 1)
      return bad("write capture context lacks exact owned proof bindings");
    context.emplace(contexts->front());
  }
  for (Value operand : op->getOperands())
    if (failed(verifyDigitalValueScope(operand, op)))
      return bad("port operand is foreign, escaped or lacks exact lexical capture ownership");
  if (failed(address(op->getOperand(0), op, module, depth->bounds, context ? &*context : nullptr)))
    return failure();
  if (!equivalentDigitalStorageTypes(
          element, read ? op->getResult(0).getType() : op->getOperand(1).getType(), module,
          context ? &*context : nullptr))
    return bad("read result or write data must preserve the complete exact memory element type");
  MemoryMaskKind kind = MemoryMaskKind::WholeElement;
  if (!read) {
    auto proved = mask(op->getOperand(2), element, op, module, context ? &*context : nullptr);
    if (failed(proved))
      return bad("mask must be one-bit whole-element or exact bitvector wordWidth = 8 * maskWidth");
    kind = *proved;
  }
  if (effect) {
    auto order = op->getAttrOfType<IntegerAttr>("order");
    auto guard = llvm::dyn_cast<BitsType>(op->getOperand(3).getType());
    if (!guard || guard.getWidth() != 1 || !order || !order.getType().isSignlessInteger(64) ||
        order.getInt() < 0 || !canonical(text(op, "root_id")) ||
        op->getOperand(4) != (*binding)->getOperand(0) ||
        op->getAttr("edge") != (*domain)->getAttr("edge"))
      return bad(
          "canonical effect must retain actual Bool guard, clock, edge and bounded order identity");
  }
  auto selection = text(memory, "read_under_write");
  return MemoryPortFacts{memory,
                         *binding,
                         *domain,
                         element,
                         depth->bounds,
                         memory->getAttrOfType<IntegerAttr>("read_latency").getInt(),
                         kind,
                         selection == "old_data"    ? MemoryReadSelection::BeforeWrites
                         : selection == "new_data"  ? MemoryReadSelection::AfterWrites
                         : selection == "no_change" ? MemoryReadSelection::HoldOnCollision
                                                    : MemoryReadSelection::UnspecifiedCollision};
}
LogicalResult verifyMemoryPort(Operation *op) { return success(succeeded(inspectMemoryPort(op))); }

static LogicalResult verifyMemoryModelImpl(Operation *module, MemoryModelLimits limits,
                                           bool previewRaw) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return failure();
  llvm::MapVector<Operation *, SmallVector<Operation *, 4>> reads, writes;
  llvm::DenseMap<Operation *, unsigned> ordinals;
  llvm::DenseMap<Operation *, PairFacts> pairFacts;
  llvm::DenseMap<Operation *, bool> raw;
  llvm::DenseMap<Operation *, StringRef> lastRoots;
  llvm::DenseMap<Operation *, llvm::StringSet<>> completedRoots;
  llvm::StringSet<> identities;
  unsigned count = 0;
  bool invalid = false;
  module->walk([&](Operation *op) {
    if (invalid || op->getParentOfType<nodal::ModuleOp>().getOperation() != module)
      return;
    if (++count > limits.maximumOperations) {
      (void)reject(op, "memory authored-operation budget exceeded before pair/normalization work");
      invalid = true;
      return;
    }
    if (!llvm::isa<MemoryReadOp, MemoryWriteOp, MemoryWriteEffectOp>(op))
      return;
    auto port = inspectMemoryPort(op);
    if (failed(port) || !identities.insert(path(op)).second) {
      if (succeeded(port))
        (void)reject(op, "memory port source identities must be unique");
      invalid = true;
      return;
    }
    if (llvm::isa<MemoryReadOp>(op)) {
      reads[port->memory].push_back(op);
      return;
    }
    writes[port->memory].push_back(op);
    if (llvm::isa<MemoryWriteEffectOp>(op))
      pairFacts.try_emplace(op, PairFacts{port->mask, literal(op->getOperand(0), op),
                                          literal(op->getOperand(2), op)});
    if (llvm::isa<MemoryWriteOp>(op)) {
      raw[port->memory] = true;
      return;
    }
    if (op->getAttrOfType<IntegerAttr>("order").getInt() != ordinals[port->memory]++) {
      (void)reject(op, "canonical write order must be dense in actual authored block sequence");
      invalid = true;
    }
    StringRef root = text(op, "root_id"), previous = lastRoots.lookup(port->memory);
    if (root != previous) {
      if (completedRoots[port->memory].contains(root)) {
        (void)reject(op, "one source root's effects cannot be split across unrelated later roots");
        invalid = true;
      }
      if (!previous.empty())
        completedRoots[port->memory].insert(previous);
      lastRoots[port->memory] = root;
    }
  });
  if (invalid)
    return failure();
  bool preview = false;
  uint64_t prospectivePairs = 0;
  for (auto &[memory, ports] : writes) {
    if (raw.lookup(memory) && ordinals.lookup(memory))
      return reject(memory, "raw and canonical write inventories cannot be mixed for one memory");
    if (!reads[memory].empty() &&
        memory->getAttrOfType<IntegerAttr>("read_latency").getInt() == 0 &&
        text(memory, "read_under_write") != "undefined")
      return reject(memory,
                    "asynchronous mixed read/write supports Undefined only in this profile");
    if (text(memory, "ordering") == "independent_ports") {
      uint64_t n = ports.size();
      prospectivePairs += n * (n - 1) / 2;
      if (prospectivePairs > limits.maximumPairQueries)
        return reject(memory, "independent-port pair proof budget exceeded before normalization");
      if (n > 1 && raw.lookup(memory))
        preview = true;
    }
  }
  if (preview && previewRaw) {
    // Reuse actual SequentialModel body/root/FSM phase normalization. A detached
    // preview cannot mutate the source on failure, and avoids duplicating guards.
    Operation *root = module;
    while (root->getParentOp())
      root = root->getParentOp();
    IRMapping mapping;
    Operation *copy = root->clone(mapping);
    Operation *candidate = mapping.lookupOrDefault(module);
    SequentialModelLimits sequential;
    sequential.maximumOperations = limits.maximumOperations;
    bool valid = succeeded(normalizeSequentialModel(candidate, sequential)) &&
                 succeeded(verifyMemoryModel(candidate, limits));
    copy->destroy();
    return success(valid);
  }
  unsigned pairs = 0, work = limits.maximumGuardWork;
  for (auto &[memory, ports] : writes) {
    if (text(memory, "ordering") != "independent_ports" || raw.lookup(memory))
      continue;
    // Overflow-free budget before entering the quadratic proof loop.
    uint64_t n = ports.size();
    if (n > 1 && n * (n - 1) / 2 > limits.maximumPairQueries - pairs)
      return reject(memory, "independent-port pair proof budget exceeded before expansion");
    for (unsigned i = 0; i < ports.size(); ++i)
      for (unsigned j = i + 1; j < ports.size(); ++j) {
        ++pairs;
        auto proof = disjoint(ports[i], ports[j], pairFacts.find(ports[i])->second,
                              pairFacts.find(ports[j])->second, work);
        if (failed(proof) || !*proof)
          return reject(ports[j],
                        "IndependentPorts requires proved address, mask or guard disjointness");
      }
  }
  return success();
}

LogicalResult verifyMemoryModel(Operation *module, MemoryModelLimits limits) {
  return verifyMemoryModelImpl(module, limits, true);
}
LogicalResult preflightMemoryNormalization(Operation *module, MemoryModelLimits limits) {
  return verifyMemoryModelImpl(module, limits, false);
}

FailureOr<MemoryEdgeResult>
evaluateMemoryEdge(Operation *memory, llvm::function_ref<FailureOr<Attribute>(Value)> value,
                   llvm::function_ref<Attribute(int64_t)> before,
                   llvm::function_ref<Attribute(Operation *)> previousFirstStage,
                   MemoryModelLimits limits) {
  if (!llvm::isa_and_nonnull<MemoryOp>(memory) ||
      failed(verifySequentialModel(memory->getParentOp())) ||
      failed(verifyMemoryModel(memory->getParentOp(), limits)))
    return failure();
  bool rawWrite = false;
  memory->getParentOp()->walk([&](MemoryWriteOp op) {
    auto port = inspectMemoryPort(op);
    rawWrite |= succeeded(port) && port->memory == memory;
  });
  if (rawWrite) {
    (void)reject(memory, "edge query requires normalized writes throughout owned scopes");
    return failure();
  }
  // Ephemeral sparse results only. The caller owns any cycle-to-cycle storage.
  llvm::MapVector<int64_t, Attribute> after;
  llvm::DenseSet<int64_t> collisions;
  // Preserve known bytes across partial writes to unspecified old contents.
  // This sparse one-edge fact is not persistent memory state.
  llvm::DenseMap<int64_t, std::pair<llvm::APInt, llvm::APInt>> byteUpdates;
  MemoryEdgeResult result;
  SmallVector<Operation *, 4> reads;
  auto integer = [&](Value input) -> FailureOr<llvm::APInt> {
    auto a = value(input);
    auto number = succeeded(a) ? llvm::dyn_cast_if_present<IntegerAttr>(*a) : IntegerAttr();
    if (!nonnegative(number))
      return failure();
    return number.getValue();
  };
  auto addressOf = [&](Value input) -> FailureOr<int64_t> {
    auto a = integer(input);
    if (failed(a) || a->getActiveBits() > 63)
      return failure();
    return int64_t(a->getZExtValue());
  };
  for (Operation &op : memory->getParentOp()->getRegion(0).front()) {
    if (!llvm::isa<MemoryReadOp, MemoryWriteOp, MemoryWriteEffectOp>(op))
      continue;
    auto p = inspectMemoryPort(&op);
    if (failed(p))
      return failure();
    if (p->memory != memory)
      continue;
    if (llvm::isa<MemoryReadOp>(op)) {
      reads.push_back(&op);
      continue;
    }
    if (!llvm::isa<MemoryWriteEffectOp>(op))
      return failure(); // Execution requires actual normalized guards and order.
    auto guard = integer(op.getOperand(3)), maskValue = integer(op.getOperand(2));
    auto at = addressOf(op.getOperand(0));
    auto data = value(op.getOperand(1));
    if (failed(guard) || failed(maskValue) || failed(at) || failed(data) ||
        guard->getActiveBits() > 1 || *at < 0 || *at >= p->depth.lower)
      return failure();
    if (guard->isZero() || maskValue->isZero())
      continue;
    collisions.insert(*at);
    Attribute next = *data;
    if (p->mask == MemoryMaskKind::Bytes) {
      auto old =
          llvm::dyn_cast_if_present<IntegerAttr>(after.count(*at) ? after[*at] : before(*at));
      auto incoming = llvm::dyn_cast_if_present<IntegerAttr>(*data);
      auto width = inferDigitalWidthBounds(p->element, memory->getParentOp());
      if (failed(width) || width->lower != width->upper || width->upper > 65536 || !incoming)
        return failure();
      unsigned bits = unsigned(width->upper);
      if (maskValue->getActiveBits() > bits / 8 ||
          !digitalIntegerAttributeFits(incoming, p->element, memory->getParentOp()) ||
          (old && !digitalIntegerAttributeFits(old, p->element, memory->getParentOp())))
        return failure();
      auto found = byteUpdates.find(*at);
      llvm::APInt merged = found != byteUpdates.end() ? found->second.first
                           : old                      ? old.getValue().zextOrTrunc(bits)
                                                      : llvm::APInt(bits, 0);
      llvm::APInt known =
          found != byteUpdates.end() ? found->second.second : llvm::APInt::getAllOnes(bits);
      if (found == byteUpdates.end() && !old)
        known.clearAllBits();
      llvm::APInt dataBits = incoming.getValue().zextOrTrunc(bits);
      llvm::APInt selected = maskValue->zextOrTrunc(bits / 8);
      for (unsigned byte = 0; byte < bits / 8; ++byte)
        if (selected[byte]) {
          merged.insertBits(dataBits.extractBits(8, byte * 8), byte * 8);
          known.setBits(byte * 8, byte * 8 + 8);
        }
      byteUpdates[*at] = {merged, known};
      next = {};
      if (known.isAllOnes()) {
        auto parametric = llvm::dyn_cast<ParametricIntegerType>(p->element);
        bool signedWord =
            llvm::isa<SIntType>(p->element) || (parametric && parametric.getKind() == "sint");
        next = IntegerAttr::get(
            IntegerType::get(memory->getContext(), bits,
                             signedWord ? IntegerType::Signed : IntegerType::Unsigned),
            merged);
      }
    } else {
      if (maskValue->getActiveBits() > 1)
        return failure();
      byteUpdates.erase(*at);
    }
    after[*at] = next;
  }
  for (auto [at, data] : after)
    result.writes.push_back({at, data});
  for (Operation *op : reads) {
    auto p = inspectMemoryPort(op);
    auto at = addressOf(op->getOperand(0));
    if (failed(p) || failed(at) || *at < 0 || *at >= p->depth.lower)
      return failure();
    bool collision = collisions.contains(*at);
    Attribute response = before(*at);
    switch (p->selection) {
    case MemoryReadSelection::BeforeWrites:
      break;
    case MemoryReadSelection::AfterWrites:
      if (after.count(*at))
        response = after[*at];
      break;
    case MemoryReadSelection::HoldOnCollision:
      if (collision)
        response = previousFirstStage(op);
      break;
    case MemoryReadSelection::UnspecifiedCollision:
      if (collision)
        response = {};
      break;
    }
    result.reads.push_back({op, response, p->latency});
  }
  return result;
}
FailureOr<Attribute> memoryResponseAtEdge(Operation *read, int64_t edge,
                                          llvm::function_ref<Attribute(int64_t)> firstStageAt) {
  auto port = inspectMemoryPort(read);
  if (failed(port) || !llvm::isa<MemoryReadOp>(read) || edge < 0 || port->latency == 0)
    return failure();
  int64_t distance = port->latency - 1;
  return edge < distance ? Attribute() : firstStageAt(edge - distance);
}
} // namespace nodal
