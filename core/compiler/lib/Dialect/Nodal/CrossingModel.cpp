#include "nodal/Dialect/Nodal/CrossingModel.h"

#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ResetModel.h"

#include "llvm/ADT/StringSet.h"

#include <map>

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
LogicalResult reject(Operation *op, StringRef reason) {
  if (!op)
    return failure();
  return op->emitOpError("NODAL-CDC-057-001: ") << reason;
}
bool boolean(Type type) {
  auto bits = llvm::dyn_cast<BitsType>(type);
  return bits && bits.getWidth() == 1;
}
FailureOr<ExecutableCrossing> inspectHeader(Operation *op) {
  auto fail = [&](StringRef reason) -> FailureOr<ExecutableCrossing> {
    (void)reject(op, reason);
    return failure();
  };
  const bool fifo = llvm::isa_and_nonnull<AsyncFifoOp>(op);
  if (!llvm::isa_and_nonnull<CrossingOp, AsyncFifoOp>(op) ||
      op->getNumOperands() != (fifo ? 3u : 1u) || op->getNumResults() != (fifo ? 3u : 1u) ||
      op->getNumRegions() || op->getNumSuccessors() ||
      !llvm::isa_and_nonnull<ModuleOp>(op->getParentOp()))
    return fail("executable crossing requires its exact actual module-owned operands and results");
  StringRef contract = text(op, "contract");
  CrossingKind kind;
  if (fifo && contract == "fifo_v1" && text(op, "kind") == "fifo")
    kind = CrossingKind::Fifo;
  else if (fifo)
    return fail("unsupported or malformed executable FIFO contract");
  else if (contract == "level_v1" && text(op, "kind") == "sync")
    kind = CrossingKind::Level;
  else if (contract == "gray_v1" && text(op, "kind") == "gray")
    kind = CrossingKind::Gray;
  else if (contract == "pulse_v1" && text(op, "kind") == "pulse")
    kind = CrossingKind::Pulse;
  else if (contract == "handshake_snapshot_v1" && text(op, "kind") == "handshake")
    kind = CrossingKind::Handshake;
  else
    return fail("unsupported or malformed executable crossing contract");
  if (!llvm::all_of(op->getAttrs(), [](NamedAttribute a) {
        return llvm::is_contained(ArrayRef<StringRef>{"source_domain", "destination_domain", "kind",
                                                      "contract", "stages", "depth", "obligations",
                                                      "metadata"},
                                  a.getName().getValue());
      }))
    return fail("unsupported or malformed executable crossing fields");
  if ((fifo && op->hasAttr("stages")) || (!fifo && op->hasAttr("depth")))
    return fail("crossing fields must match the exact closed operation contract");
  for (Value operand : op->getOperands())
    if (failed(verifyDigitalValueScope(operand, op)))
      return fail("crossing requires its actual owned sampled operands");
  if (fifo && (!boolean(op->getOperand(1).getType()) || !boolean(op->getOperand(2).getType()) ||
               !boolean(op->getResult(1).getType()) || !boolean(op->getResult(2).getType())))
    return fail("FIFO valid and ready operands/results require exact owned Bool types");
  uint64_t payloadBits = 1;
  if (kind == CrossingKind::Gray || kind == CrossingKind::Handshake || fifo) {
    auto footprint = inferDigitalBitPatternMaximumBits(op->getOperand(0).getType(), op);
    if (failed(footprint) ||
        !equivalentDigitalStorageTypes(op->getOperand(0).getType(), op->getResult(0).getType(),
                                       op->getParentOp()))
      return fail(
          "Gray/handshake/FIFO crossing requires an exact complete finite bit-pattern payload ABI");
    payloadBits = *footprint;
  } else if (!boolean(op->getOperand(0).getType()) ||
             op->getOperand(0).getType() != op->getResult(0).getType())
    return fail("level/pulse synchronizer requires exact owned Bool input/result");
  auto stages = op->getAttrOfType<IntegerAttr>("stages");
  if (!fifo &&
      (!stages || !stages.getType().isSignlessInteger(64) || stages.getInt() < 2 ||
       stages.getInt() > 32 ||
       ((kind == CrossingKind::Pulse || kind == CrossingKind::Handshake) && stages.getInt() != 2)))
    return fail(
        "crossing stages must be i64 in 2 through 32; Pulse/handshake have exactly two stages");
  unsigned depth = 0, pointerBits = 0;
  if (fifo) {
    auto count = op->getAttrOfType<IntegerAttr>("depth");
    if (!count || !count.getType().isSignlessInteger(64) || count.getInt() < 2 ||
        count.getInt() > 256 || (count.getInt() & (count.getInt() - 1)))
      return fail("FIFO depth must be an exact i64 power of two from 2 through 256");
    depth = count.getInt();
    for (unsigned remaining = depth; remaining; remaining >>= 1)
      ++pointerBits;
  }
  auto obligations = op->getAttrOfType<DictionaryAttr>("obligations");
  auto field = [&](StringRef key, StringRef expected) {
    auto value = obligations ? obligations.getAs<StringAttr>(key) : StringAttr();
    return value && value.getValue() == expected;
  };
  if ((kind == CrossingKind::Level && op->hasAttr("obligations")) ||
      (kind == CrossingKind::Gray && (!obligations || obligations.size() != 3 ||
                                      !field("transition", "one_bit_per_source_sample") ||
                                      !field("skew", "less_than_source_period") ||
                                      !field("stability", "stable_between_source_samples"))) ||
      (kind == CrossingKind::Pulse &&
       (!obligations || obligations.size() != 3 || !field("spacing", "returned_acknowledgement") ||
        !field("width", "single_source_cycle") || !field("reset_epoch", "common_assertion"))) ||
      (kind == CrossingKind::Handshake &&
       (!obligations || obligations.size() != 3 ||
        !field("delivery", "latest_snapshot_coalescing") ||
        !field("bundled_data", "settled_before_request_capture") ||
        !field("reset_epoch", "common_assertion"))) ||
      (fifo &&
       (!obligations || obligations.size() != 5 || !field("transfer", "valid_and_ready") ||
        !field("stall", "hold_payload_and_valid") || !field("reset_epoch", "common_assertion") ||
        !field("pointer_skew", "less_than_launch_period") ||
        !field("bundled_data", "settled_before_read_visibility"))))
    return fail("executable crossing requires its exact typed temporal/environment obligations");
  const uint64_t budget = SequentialModelLimits().maximumNormalizedOperations;
  uint64_t stateBits = kind == CrossingKind::Pulse ? 7 : (fifo ? 0 : stages.getInt());
  if (kind == CrossingKind::Gray) {
    const uint64_t copies = stages.getInt() + 1;
    if (payloadBits > (budget - 1) / copies)
      return fail("crossing state expansion exceeds finite sequential budget");
    stateBits = payloadBits * copies + 1;
  } else if (kind == CrossingKind::Handshake) {
    if (payloadBits > (budget - 6) / 2)
      return fail("crossing state expansion exceeds finite sequential budget");
    stateBits = payloadBits * 2 + 6;
  } else if (fifo) {
    const uint64_t controls = 8 * pointerBits + 2;
    const uint64_t copies = depth + 2;
    if (payloadBits > (budget - controls) / copies)
      return fail("crossing state expansion exceeds finite sequential budget");
    stateBits = copies * payloadBits + controls;
  }
  auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
  StringRef identity = path(op);
  StringRef ownerPath = path(op->getParentOp());
  if (ownerPath.empty())
    ownerPath = text(op->getParentOp(), "sym_name");
  if (!metadata || identity.empty() || identity.size() > 4096 || identity != identity.trim() ||
      !identity.starts_with((ownerPath + ".").str()) || identity.size() <= ownerPath.size() + 1)
    return fail("crossing requires its canonical owned semantic_path identity");
  // Existing source-map fields remain intact and non-authoritative. Reserved
  // executable fields cannot be smuggled through the descriptive dictionary.
  for (StringRef key : {"contract", "contract_version", "stages", "depth", "kind", "source_domain",
                        "destination_domain", "obligations", "verified", "safe", "category"})
    if (metadata.get(key))
      return fail("executable crossing facts belong in typed fields, not metadata");
  auto sourceRef = op->getAttrOfType<FlatSymbolRefAttr>("source_domain");
  auto destinationRef = op->getAttrOfType<FlatSymbolRefAttr>("destination_domain");
  if (!sourceRef || !destinationRef || sourceRef == destinationRef)
    return fail("crossing requires distinct typed endpoint references");
  ExecutableCrossing result;
  result.operation = op;
  result.stages = fifo ? 2 : static_cast<unsigned>(stages.getInt());
  result.identity = identity;
  result.kind = kind;
  result.payloadMaximumBits = payloadBits;
  result.stateBits = stateBits;
  result.depth = depth;
  result.pointerBits = pointerBits;
  result.operands.push_back({0, sourceRef, "source_payload"});
  result.results.push_back({0, destinationRef, "destination_payload"});
  if (fifo) {
    result.operands.push_back({1, sourceRef, "source_valid"});
    result.operands.push_back({2, destinationRef, "destination_ready"});
    result.results.push_back({1, destinationRef, "destination_valid"});
    result.results.push_back({2, sourceRef, "source_ready"});
  }
  return result;
}
// DomainModel has independently verified each effective binding/release. Keep
// the common epoch proof shared by Pulse, snapshot handshake and FIFO, while ResetModel
// remains the sole owner of bounded reset producer/lineage inspection.
LogicalResult verifyCommonResetEpoch(Operation *op, Operation *sourceBinding,
                                     Operation *destinationBinding, const DomainModel &domains) {
  if (sourceBinding->getNumOperands() != 2 || destinationBinding->getNumOperands() != 2)
    return reject(op, "crossing requires reset-bearing endpoints and a coordinated reset epoch");
  auto sourceReset = inspectReset(sourceBinding->getOperand(1), sourceBinding, domains);
  auto destinationReset =
      inspectReset(destinationBinding->getOperand(1), destinationBinding, domains);
  if (failed(sourceReset) || failed(destinationReset) || sourceReset->roots.empty() ||
      destinationReset->roots.empty())
    return reject(op,
                  "crossing requires actual typed common reset roots with per-domain safe release");
  // Compare canonical physical identities only, not release paths or textual
  // reset names: safe release is independently proved for each local clock.
  using RootKey = std::pair<Operation *, std::string>;
  std::map<RootKey, StringAttr> sourceRoots, destinationRoots;
  for (const auto &root : sourceReset->roots)
    sourceRoots.emplace(RootKey{root.physical.port, root.physical.scalarKey}, root.polarity);
  for (const auto &root : destinationReset->roots)
    destinationRoots.emplace(RootKey{root.physical.port, root.physical.scalarKey}, root.polarity);
  if (sourceRoots != destinationRoots)
    return reject(op, "crossing requires equal nonempty physical reset root/element/polarity sets");
  return success();
}
} // namespace
FailureOr<ExecutableCrossing> inspectExecutableCrossing(Operation *op, const DomainModel &domains) {
  auto header = inspectHeader(op);
  if (failed(header))
    return failure();
  auto fail = [&](StringRef reason) -> FailureOr<ExecutableCrossing> {
    (void)reject(op, reason);
    return failure();
  };
  auto sourceRef = op->getAttrOfType<FlatSymbolRefAttr>("source_domain");
  auto destinationRef = op->getAttrOfType<FlatSymbolRefAttr>("destination_domain");
  auto source = domains.resolveDomain(sourceRef);
  auto destination = domains.resolveDomain(destinationRef);
  if (failed(source) || failed(destination) || *source == *destination)
    return fail("crossing endpoints must resolve to distinct actual owned domains");
  auto sourceBinding = domains.resolveBinding(sourceRef);
  auto destinationBinding = domains.resolveBinding(destinationRef);
  if (failed(sourceBinding) || failed(destinationBinding))
    return fail("crossing endpoints require independently verified physical domain bindings");
  if ((header->kind == CrossingKind::Pulse || header->kind == CrossingKind::Handshake ||
       header->kind == CrossingKind::Fifo) &&
      failed(verifyCommonResetEpoch(op, *sourceBinding, *destinationBinding, domains)))
    return failure();
  header->sourceDomain = *source;
  header->destinationDomain = *destination;
  header->sourceBinding = *sourceBinding;
  header->destinationBinding = *destinationBinding;
  return *header;
}
FailureOr<ExecutableCrossing> inspectExecutableCrossing(Operation *op) {
  return inspectExecutableCrossing(op, DomainModel(op));
}
LogicalResult verifyCrossingOperation(Operation *op) {
  if (!llvm::isa_and_nonnull<CrossingOp, AsyncFifoOp>(op))
    return failure();
  if (llvm::isa<AsyncFifoOp>(op) || op->hasAttr("contract") || op->hasAttr("stages") ||
      op->hasAttr("obligations"))
    return succeeded(inspectHeader(op)) ? success() : failure();
  auto source = op->getAttrOfType<FlatSymbolRefAttr>("source_domain");
  auto destination = op->getAttrOfType<FlatSymbolRefAttr>("destination_domain");
  if (!source || !destination || source == destination)
    return reject(op, "requires distinct crossing domains");
  if (!llvm::is_contained(ArrayRef<StringRef>{"sync", "gray", "pulse", "handshake", "fifo", "reset",
                                              "bridge", "waived"},
                          text(op, "kind")))
    return reject(op, "unsupported crossing kind");
  return success();
}
LogicalResult verifyCrossingSourceDependencies(Operation *module,
                                               const DigitalDependencyGraph &graph,
                                               SequentialModelLimits limits) {
  SmallVector<SequentialSample, 8> samples;
  SmallVector<unsigned, 8> roots;
  const DomainModel domains(module);
  for (const auto &crossing : graph.crossings) {
    const bool fifo = llvm::isa<AsyncFifoOp>(crossing.operation);
    for (const auto &sample : crossing.samples) {
      auto owner = domains.resolveDomain(sample.domain);
      if (failed(owner))
        return reject(crossing.operation, "sample domain lost its owned resolution");
      auto actual = FlatSymbolRefAttr::get(module->getContext(), text(*owner, "sym_name"));
      samples.push_back({crossing.operation->getOperand(sample.operandIndex), actual,
                         crossing.operation, true, crossing.operation,
                         text(crossing.operation, "kind") != "pulse", fifo,
                         fifo && sample.operandIndex < 2});
      roots.push_back(sample.root);
    }
  }
  return verifySequentialSampleDependencies(module, samples, graph, roots, limits);
}
LogicalResult verifyCrossingModel(Operation *module, SequentialModelLimits limits) {
  if (!llvm::isa_and_nonnull<ModuleOp>(module) || module->getNumRegions() != 1 ||
      !module->getRegion(0).hasOneBlock())
    return failure();
  bool executable = false;
  unsigned work = 0;
  uint64_t stateCount = 0;
  auto status = module->walk<WalkOrder::PreOrder>([&](Operation *op) {
    if (op != module && llvm::isa<ModuleOp>(op))
      return WalkResult::skip();
    if (++work > limits.maximumOperations) {
      (void)reject(module, "crossing inventory exceeds finite operation budget");
      return WalkResult::interrupt();
    }
    if (llvm::isa<AsyncFifoOp>(op) ||
        (llvm::isa<CrossingOp>(op) &&
         (op->hasAttr("contract") || op->hasAttr("stages") || op->hasAttr("obligations")))) {
      executable = true;
      auto header = inspectHeader(op);
      if (failed(header))
        return WalkResult::interrupt();
      stateCount += header->stateBits;
      if (stateCount > limits.maximumNormalizedOperations) {
        (void)reject(op, "crossing state expansion exceeds finite sequential budget");
        return WalkResult::interrupt();
      }
      if (op->getParentOp() != module) {
        (void)reject(op,
                     "executable crossing instance must be owned directly by its actual module");
        return WalkResult::interrupt();
      }
    }
    return WalkResult::advance();
  });
  if (status.wasInterrupted())
    return failure();
  if (!executable)
    return success();
  DigitalAssignmentProofLimits graphLimits;
  graphLimits.maximumNodes = limits.maximumOperations;
  return succeeded(analyzeDigitalAssignmentDependencies(module, {}, graphLimits)) ? success()
                                                                                  : failure();
}
} // namespace nodal
