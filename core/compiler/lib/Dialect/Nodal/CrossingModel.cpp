#include "nodal/Dialect/Nodal/CrossingModel.h"

#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/StringSet.h"

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
FailureOr<LevelCrossing> inspectHeader(Operation *op) {
  auto fail = [&](StringRef reason) -> FailureOr<LevelCrossing> {
    (void)reject(op, reason);
    return failure();
  };
  if (!llvm::isa_and_nonnull<CrossingOp>(op) || op->getNumOperands() != 1 ||
      op->getNumResults() != 1 || op->getNumRegions() || op->getNumSuccessors() ||
      !llvm::isa_and_nonnull<ModuleOp>(op->getParentOp()))
    return fail("executable level crossing requires one actual module-owned input and result");
  if (text(op, "contract") != "level_v1" || text(op, "kind") != "sync" ||
      !llvm::all_of(op->getAttrs(), [](NamedAttribute a) {
        return llvm::is_contained(ArrayRef<StringRef>{"source_domain", "destination_domain", "kind",
                                                      "contract", "stages", "metadata"},
                                  a.getName().getValue());
      }))
    return fail("unsupported or malformed executable level contract");
  if (!boolean(op->getOperand(0).getType()) ||
      op->getOperand(0).getType() != op->getResult(0).getType() ||
      failed(verifyDigitalValueScope(op->getOperand(0), op)))
    return fail("level synchronizer requires exact owned Bool input/result; Pulse is not a level");
  auto stages = op->getAttrOfType<IntegerAttr>("stages");
  if (!stages || !stages.getType().isSignlessInteger(64) || stages.getInt() < 2 ||
      stages.getInt() > 32)
    return fail("level synchronizer stages must be i64 in the inclusive range 2 through 32");
  auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
  StringRef identity = path(op);
  StringRef ownerPath = path(op->getParentOp());
  if (ownerPath.empty())
    ownerPath = text(op->getParentOp(), "sym_name");
  if (!metadata || identity.empty() || identity.size() > 4096 || identity != identity.trim() ||
      !identity.starts_with((ownerPath + ".").str()) || identity.size() <= ownerPath.size() + 1)
    return fail("level crossing requires its canonical owned semantic_path identity");
  // Existing source-map fields remain intact and non-authoritative. Reserved
  // executable fields cannot be smuggled through the descriptive dictionary.
  for (StringRef key : {"contract", "contract_version", "stages", "kind", "source_domain",
                        "destination_domain", "verified", "safe", "category"})
    if (metadata.get(key))
      return fail("executable crossing facts belong in typed fields, not metadata");
  auto sourceRef = op->getAttrOfType<FlatSymbolRefAttr>("source_domain");
  auto destinationRef = op->getAttrOfType<FlatSymbolRefAttr>("destination_domain");
  if (!sourceRef || !destinationRef || sourceRef == destinationRef)
    return fail("level crossing requires distinct typed endpoint references");
  return LevelCrossing{
      op, nullptr, nullptr, nullptr, nullptr, static_cast<unsigned>(stages.getInt()), identity};
}
} // namespace
FailureOr<LevelCrossing> inspectLevelCrossing(Operation *op, const DomainModel &domains) {
  auto header = inspectHeader(op);
  if (failed(header))
    return failure();
  auto fail = [&](StringRef reason) -> FailureOr<LevelCrossing> {
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
  header->sourceDomain = *source;
  header->destinationDomain = *destination;
  header->sourceBinding = *sourceBinding;
  header->destinationBinding = *destinationBinding;
  return *header;
}
FailureOr<LevelCrossing> inspectLevelCrossing(Operation *op) {
  return inspectLevelCrossing(op, DomainModel(op));
}
LogicalResult verifyCrossingOperation(Operation *op) {
  if (!llvm::isa_and_nonnull<CrossingOp>(op))
    return failure();
  if (op->hasAttr("contract") || op->hasAttr("stages"))
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
  for (auto crossing : graph.crossings) {
    auto source = domains.resolveDomain(crossing.operation->getAttr("source_domain"));
    if (failed(source))
      return reject(crossing.operation, "source domain lost its owned resolution");
    auto actual = FlatSymbolRefAttr::get(module->getContext(), text(*source, "sym_name"));
    samples.push_back({crossing.operation->getOperand(0), actual, crossing.operation, true});
    roots.push_back(crossing.sourceRoot);
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
    if (llvm::isa<CrossingOp>(op) && (op->hasAttr("contract") || op->hasAttr("stages"))) {
      executable = true;
      auto stages = op->getAttrOfType<IntegerAttr>("stages");
      if (stages && stages.getType().isSignlessInteger(64) && stages.getInt() >= 2 &&
          stages.getInt() <= 32) {
        stateCount += static_cast<uint64_t>(stages.getInt());
        if (stateCount > limits.maximumNormalizedOperations) {
          (void)reject(op, "crossing state expansion exceeds finite sequential budget");
          return WalkResult::interrupt();
        }
      }
      if (op->getParentOp() != module) {
        (void)reject(op, "executable level instance must be owned directly by its actual module");
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
