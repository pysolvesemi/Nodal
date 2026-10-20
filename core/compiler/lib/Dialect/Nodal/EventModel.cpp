#include "nodal/Dialect/Nodal/EventModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/MapVector.h"

using namespace mlir;
namespace nodal {
namespace {
StringRef text(Operation *op, StringRef name) {
  auto attr = op ? op->getAttrOfType<StringAttr>(name) : StringAttr();
  return attr ? attr.getValue() : StringRef();
}
LogicalResult reject(Operation *op, StringRef message) {
  return op->emitOpError("NODAL-EVENT-057-001: ") << message;
}
Operation *portOf(Value value, Operation *module) {
  auto *op = value.getDefiningOp();
  if (!isa_and_nonnull<PortValueOp>(op) || op->getParentOp() != module || op->getNumOperands() ||
      op->getNumResults() != 1)
    return nullptr;
  auto ref = op->getAttrOfType<FlatSymbolRefAttr>("port");
  auto *port = ref ? SymbolTable::lookupSymbolIn(module, ref) : nullptr;
  auto type = port ? port->getAttrOfType<TypeAttr>("type") : TypeAttr();
  return isa_and_nonnull<PortOp>(port) && port->getParentOp() == module && type &&
                 type.getValue() == value.getType()
             ? port
             : nullptr;
}
bool unannotated(Operation *port, Operation *module) {
  auto attr = port->getAttr("domain");
  if (!attr)
    return true;
  auto ref = dyn_cast<FlatSymbolRefAttr>(attr);
  return ref && ref.getValue() == "none" && !SymbolTable::lookupSymbolIn(module, ref);
}
} // namespace
FailureOr<ExecutableEventProcess> inspectEventProcess(Operation *op) {
  auto fail = [&](StringRef message) -> FailureOr<ExecutableEventProcess> {
    (void)reject(op, message);
    return failure();
  };
  auto *module = op->getParentOp();
  if (!isa<EventProcessOp>(op) || !isa_and_nonnull<ModuleOp>(module) || op->getNumResults() ||
      op->getNumRegions() || op->getNumSuccessors())
    return fail("event process requires an actual flat owned module declaration");
  auto outer = dyn_cast_or_null<mlir::ModuleOp>(module->getParentOp());
  auto root =
      outer ? outer->getAttrOfType<FlatSymbolRefAttr>("nodal.root.module") : FlatSymbolRefAttr();
  if (!outer || !root || SymbolTable::lookupSymbolIn(outer, root) != module)
    return fail(
        "event process requires the actual selected application root, never a child library");
  if (text(op, "contract") != "root_event_v1" ||
      (text(op, "edge") != "rising" && text(op, "edge") != "falling") ||
      !llvm::all_of(op->getAttrs(), [](NamedAttribute a) {
        return llvm::is_contained(ArrayRef<StringRef>{"sym_name", "edge", "contract", "obligations",
                                                      "operandSegmentSizes", "metadata"},
                                  a.getName().getValue());
      }))
    return fail("event process requires its closed executable edge contract");
  auto segments = op->getAttrOfType<DenseI32ArrayAttr>("operandSegmentSizes");
  if (!segments || segments.size() != 3 || segments[0] != 1 || segments[1] < 1 ||
      segments[1] > 65536 || segments[2] < 0 || segments[2] > 4096 ||
      uint64_t(1) + segments[1] + segments[2] != op->getNumOperands())
    return fail("event process requires exact trigger, nonempty storage and at most 4096 captures");
  auto obligations = op->getAttrOfType<DictionaryAttr>("obligations");
  auto field = [&](StringRef key, StringRef expected) {
    auto value = obligations ? obligations.getAs<StringAttr>(key) : StringAttr();
    return value && value.getValue() == expected;
  };
  if (!obligations || obligations.size() != 4 || !field("trigger", "known_levels_definite_edges") ||
      !field("external_sampling", "user_setup_hold") || !field("startup", "unspecified") ||
      !field("updates", "simultaneous_pre_event"))
    return fail("event process must retain exact edge, sampling, startup and update obligations");
  auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
  auto identity = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
  auto ownerMetadata = module->getAttrOfType<DictionaryAttr>("metadata");
  auto ownerPath = ownerMetadata ? ownerMetadata.getAs<StringAttr>("semantic_path") : StringAttr();
  StringRef owner = ownerPath ? ownerPath.getValue() : text(module, "sym_name");
  if (!identity || identity.getValue() != text(op, "sym_name") ||
      identity.getValue().size() > 4096 || identity.getValue() != identity.getValue().trim() ||
      !identity.getValue().starts_with((owner + ".").str()) ||
      identity.getValue().size() <= owner.size() + 1)
    return fail("event process requires its exact owned semantic identity");
  for (StringRef key : {"contract", "edge", "obligations", "root_only", "verified", "safe",
                        "domain", "event_process", "storage_targets", "external_captures"})
    if (metadata.get(key))
      return fail("event execution facts belong in typed fields, not descriptive metadata");
  ExecutableEventProcess result;
  result.operation = op;
  result.module = module;
  result.trigger = op->getOperand(0);
  result.edge = text(op, "edge");
  Operation *trigger = portOf(result.trigger, module);
  auto type = dyn_cast<BitsType>(result.trigger.getType());
  if (!trigger || !type || type.getWidth() != 1 || text(trigger, "direction") != "input" ||
      !unannotated(trigger, module))
    return fail("event trigger must be an exact unannotated root scalar Bool input boundary");
  DenseSet<Operation *> storage, captures;
  for (unsigned i = 0; i < op->getNumOperands(); ++i) {
    Value value = op->getOperand(i);
    if (failed(verifyDigitalValueScope(value, op)))
      return fail("event operands require actual lexical module ownership");
    if (!i)
      continue;
    Operation *port = portOf(value, module);
    bool target = i <= unsigned(segments[1]);
    if (!port || text(port, "direction") != (target ? "output" : "input") ||
        !unannotated(port, module))
      return fail("event storage/captures require exact unannotated root output/input boundaries");
    auto bits = inferDigitalBitPatternFixedBits(value.getType(), op);
    if (failed(bits) || *bits < 1 || *bits > 4096)
      return fail("event values require fixed whole bit-pattern ABI width 1 through 4096");
    auto &set = target ? storage : captures;
    if (!set.insert(port).second)
      return fail("event inventory contains duplicate physical port aliases");
    (target ? result.storagePorts : result.capturePorts).push_back(port);
    if (target) {
      result.stateBits += *bits;
      if (result.stateBits > 262144)
        return fail("event storage exceeds finite 262144-bit state budget");
    }
  }
  return result;
}
LogicalResult verifyEventRootOwnership(Operation *module) {
  auto outer = dyn_cast_or_null<mlir::ModuleOp>(module->getParentOp());
  if (!outer)
    return reject(module, "event root requires its actual outer module");
  unsigned work = 0;
  auto status = outer->walk([&](Operation *op) {
    if (++work > 65536) {
      (void)reject(op, "event root ownership exceeds finite operation budget");
      return WalkResult::interrupt();
    }
    if (isa<InstanceOp>(op)) {
      auto ref = op->getAttrOfType<FlatSymbolRefAttr>("module");
      if (ref && SymbolTable::lookupSymbolIn(outer, ref) == module) {
        (void)reject(op, "event-bearing application root cannot be instantiated, even unused");
        return WalkResult::interrupt();
      }
    }
    return WalkResult::advance();
  });
  return failure(status.wasInterrupted());
}
LogicalResult verifyEventUpdate(Operation *op) {
  if (!isa<DigitalAssignOp>(op) || text(op, "kind") != "event_update" ||
      !isa_and_nonnull<ModuleOp>(op->getParentOp()) || op->getNumOperands() != 2 ||
      op->getNumResults() || op->getNumRegions())
    return reject(op, "event update must be one unconditional direct-module whole-output write");
  auto ref = op->getAttrOfType<FlatSymbolRefAttr>("event_process");
  Operation *process = ref ? SymbolTable::lookupSymbolIn(op->getParentOp(), ref) : nullptr;
  if (!isa_and_nonnull<EventProcessOp>(process))
    return reject(op, "event update requires an actual local event process, not descriptor intent");
  if (!llvm::all_of(op->getAttrs(), [](NamedAttribute a) {
        return llvm::is_contained(ArrayRef<StringRef>{"kind", "event_process", "metadata"},
                                  a.getName().getValue());
      }))
    return reject(op, "event update requires its closed process identity and actual operands");
  auto *port = portOf(op->getOperand(0), op->getParentOp());
  if (!port || text(port, "direction") != "output" || !unannotated(port, op->getParentOp()) ||
      !equivalentDigitalStorageTypes(op->getOperand(0).getType(), op->getOperand(1).getType(),
                                     op->getParentOp()))
    return reject(op, "event update requires an exact whole-output target and payload type");
  // The module's canonical AssignmentQuery inventories every declared storage
  // once and checks membership by physical PortOp identity. Do not rescan a
  // process's complete target/capture lists for every update (quadratic fanout).
  return success();
}
LogicalResult verifyEventSourceDependencies(Operation *module,
                                            const DigitalDependencyGraph &graph) {
  llvm::MapVector<Operation *, SmallVector<unsigned, 2>> roots;
  for (const auto &update : graph.eventUpdates)
    roots[update.process].push_back(update.root);
  unsigned work = 0;
  for (const auto &entry : roots) {
    auto header = inspectEventProcess(entry.first);
    if (failed(header))
      return failure();
    DenseSet<Operation *> captures;
    DenseSet<unsigned> seen;
    SmallVector<unsigned, 8> pending(entry.second.begin(), entry.second.end());
    while (!pending.empty()) {
      unsigned id = pending.pop_back_val();
      if (!seen.insert(id).second)
        continue;
      if (id >= graph.nodes.size() || ++work > 262144)
        return reject(entry.first, "event capture graph exceeds its finite proof budget");
      const auto &node = graph.nodes[id];
      if (node.eventProcess) {
        if (node.eventProcess != entry.first)
          return reject(entry.first, "event body cannot capture another process's state");
        pending.append(node.dependencies.begin(), node.dependencies.end());
        continue;
      }
      if (node.crossing || node.waiver || node.sourceDomain || node.hierarchyBoundary)
        return reject(entry.first,
                      "event capture cannot hide clocked, waived or hierarchical provenance");
      if (node.value) {
        auto access = resolveStaticShapeProjection(node.value, node.use);
        auto *source =
            succeeded(access) ? access->root.getDefiningOp() : node.value.getDefiningOp();
        if (isa_and_nonnull<PortValueOp>(source)) {
          auto *port = portOf(source->getResult(0), module);
          if (!port)
            return reject(entry.first, "event capture lost its actual port owner");
          if (text(port, "direction") == "input") {
            if (!unannotated(port, module))
              return reject(entry.first,
                            "event external capture must be an undriven unannotated input");
            captures.insert(port);
          } else if (node.dependencies.empty()) {
            return reject(entry.first, "event capture is not tracked state or an actual input");
          }
        } else if (isa_and_nonnull<DigitalStorageOp>(source)) {
          if (text(source, "kind") != "wire")
            return reject(entry.first, "event body cannot capture ordinary domain-owned state");
        } else if (!isa_and_nonnull<DigitalExprOp, EnumExprOp, ConstantOp, ConstLiteralOp,
                                    ConstExprOp, ConstParameterRefOp, ShapeIndexOp, ShapeSliceOp,
                                    ShapeViewOp>(source)) {
          return reject(entry.first, "event body requires bounded pure digital expressions");
        }
      }
      pending.append(node.dependencies.begin(), node.dependencies.end());
    }
    if (captures.size() != header->capturePorts.size() ||
        !llvm::all_of(header->capturePorts,
                      [&](Operation *port) { return captures.contains(port); }))
      return reject(entry.first,
                    "declared event captures must exactly match actual RHS input leaves");
  }
  return success();
}
} // namespace nodal
