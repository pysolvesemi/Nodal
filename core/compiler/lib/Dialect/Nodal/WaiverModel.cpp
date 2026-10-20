#include "nodal/Dialect/Nodal/WaiverModel.h"

#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ResetModel.h"

#include "llvm/Support/ConvertUTF.h"

using namespace mlir;
namespace nodal {
namespace {
StringRef text(Operation *op, StringRef key) {
  auto value = op ? op->getAttrOfType<StringAttr>(key) : StringAttr();
  return value ? value.getValue() : StringRef();
}
StringRef path(Operation *op) {
  auto metadata = op ? op->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
  auto value = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
  return value ? value.getValue() : StringRef();
}
LogicalResult reject(Operation *op, StringRef reason) {
  return op->emitOpError("NODAL-WAIVER-057-001: ") << reason;
}
bool boundedText(StringRef value, unsigned maximum) {
  if (value.empty() || value.size() > maximum)
    return false;
  const auto *begin = reinterpret_cast<const llvm::UTF8 *>(value.data());
  SmallVector<llvm::UTF32, 32> decoded(value.size());
  auto *out = decoded.data();
  if (llvm::ConvertUTF8toUTF32(&begin, begin + value.size(), &out, decoded.data() + decoded.size(),
                               llvm::strictConversion) != llvm::conversionOK)
    return false;
  // Unicode White_Space, identical to the immutable source-contract admission.
  // Text is retained exactly; this only rejects whitespace-only authored fields.
  return llvm::any_of(ArrayRef<llvm::UTF32>(decoded.data(), out - decoded.data()), [](auto c) {
    return !((c >= 0x09 && c <= 0x0D) || c == 0x20 || c == 0x85 || c == 0xA0 || c == 0x1680 ||
             (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F ||
             c == 0x205F || c == 0x3000);
  });
}
FailureOr<ExecutableWaiver> inspectHeader(Operation *op) {
  auto fail = [&](StringRef reason) -> FailureOr<ExecutableWaiver> {
    (void)reject(op, reason);
    return failure();
  };
  if (!isa_and_nonnull<WaiverOp>(op) || op->getNumOperands() != 1 || op->getNumResults() != 1 ||
      op->getNumRegions() || op->getNumSuccessors() ||
      !isa_and_nonnull<ModuleOp>(op->getParentOp()))
    return fail("waiver requires one actual owned payload and exact module-owned result");
  if (text(op, "contract") != "stateless_waiver_v1" ||
      !llvm::all_of(op->getAttrs(), [](NamedAttribute attr) {
        return llvm::is_contained(ArrayRef<StringRef>{"source_domain", "destination_domain",
                                                      "contract", "id", "reason", "relation",
                                                      "obligations", "metadata"},
                                  attr.getName().getValue());
      }))
    return fail("waiver requires its closed executable contract, not descriptor metadata");
  if (!boundedText(text(op, "id"), 256) || !boundedText(text(op, "reason"), 4096))
    return fail("waiver requires nonblank valid UTF-8 id (256 bytes) and reason (4096 bytes)");
  if (failed(verifyDigitalValueScope(op->getOperand(0), op)))
    return fail("waiver payload must retain actual lexical ownership");
  auto bits = inferDigitalBitPatternFixedBits(op->getOperand(0).getType(), op);
  if (failed(bits) || *bits < 1 || *bits > 4096 ||
      !equivalentDigitalStorageTypes(op->getOperand(0).getType(), op->getResult(0).getType(),
                                     op->getParentOp()))
    return fail("waiver requires the exact fixed whole bit-pattern ABI of 1 through 4096 bits");
  auto source = op->getAttrOfType<FlatSymbolRefAttr>("source_domain");
  auto destination = op->getAttrOfType<FlatSymbolRefAttr>("destination_domain");
  if (!source || !destination)
    return fail("waiver requires exact typed source and destination domain references");
  auto relation = op->getAttrOfType<DictionaryAttr>("relation");
  if (failed(verifyBoundaryRelationIntentFields(relation, op)))
    return failure();
  auto obligations = op->getAttrOfType<DictionaryAttr>("obligations");
  auto field = [&](StringRef key, StringRef expected) {
    auto value = obligations ? obligations.getAs<StringAttr>(key) : StringAttr();
    return value && value.getValue() == expected;
  };
  if (!obligations || obligations.size() != 3 || !field("physical_safety", "user_responsibility") ||
      !field("delivery", "not_guaranteed") || !field("reset", "not_exempt"))
    return fail("waiver must retain exact physical-safety, delivery and reset obligations");
  auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
  StringRef identity = path(op), owner = path(op->getParentOp());
  if (owner.empty())
    owner = text(op->getParentOp(), "sym_name");
  if (!metadata || !boundedText(identity, 4096) || identity != identity.trim() ||
      !identity.starts_with((owner + ".").str()) || identity.size() <= owner.size() + 1)
    return fail("waiver requires its canonical owned semantic_path identity");
  for (StringRef key : {"contract", "contract_version", "source_domain", "destination_domain", "id",
                        "reason", "relation", "obligations", "verified", "safe", "kind"})
    if (metadata.get(key))
      return fail("executable waiver facts belong in typed fields, not descriptive metadata");
  ExecutableWaiver result;
  result.operation = op;
  result.sourceReference = source;
  result.destinationReference = destination;
  result.identity = identity;
  result.id = text(op, "id");
  result.payloadBits = *bits;
  return result;
}
} // namespace
LogicalResult verifyWaiverOperation(Operation *op) {
  return succeeded(inspectHeader(op)) ? success() : failure();
}
FailureOr<ExecutableWaiver> inspectExecutableWaiver(Operation *op, const DomainModel &domains) {
  auto header = inspectHeader(op);
  if (failed(header))
    return failure();
  auto source = domains.resolveDomain(header->sourceReference);
  auto destination = domains.resolveDomain(header->destinationReference);
  Operation *sourceFormal = domains.unresolvedRequirement(header->sourceReference);
  Operation *destinationFormal = domains.unresolvedRequirement(header->destinationReference);
  if ((failed(source) && !sourceFormal) || (failed(destination) && !destinationFormal) ||
      (!sourceFormal && failed(domains.resolveBinding(header->sourceReference))) ||
      (!destinationFormal && failed(domains.resolveBinding(header->destinationReference)))) {
    (void)reject(
        op, "waiver endpoints require independently verified owned bindings and reset release");
    return failure();
  }
  header->sourceDomain = sourceFormal ? sourceFormal : *source;
  header->destinationDomain = destinationFormal ? destinationFormal : *destination;
  header->unresolvedRequirements = sourceFormal || destinationFormal;
  if (!header->unresolvedRequirements && failed(domains.verifyBoundaryRelationIntent(
                                             header->sourceReference, header->destinationReference,
                                             op->getAttrOfType<DictionaryAttr>("relation"), op)))
    return failure();
  return *header;
}
FailureOr<ExecutableWaiver> inspectExecutableWaiver(Operation *op, const HierarchyModel &hierarchy,
                                                    unsigned occurrence) {
  auto header = inspectHeader(op);
  if (failed(header))
    return failure();
  auto source = hierarchy.resolveBinding(occurrence, header->sourceReference);
  auto destination = hierarchy.resolveBinding(occurrence, header->destinationReference);
  if (failed(source) || failed(destination)) {
    (void)reject(op, "waiver endpoints must resolve to exact concrete occurrence bindings");
    return failure();
  }
  if (failed(verifyContextualResetForBinding(*source, hierarchy)) ||
      failed(verifyContextualResetForBinding(*destination, hierarchy)) ||
      failed(verifyBoundaryRelationIntent(header->sourceReference, header->destinationReference,
                                          op->getAttrOfType<DictionaryAttr>("relation"), op,
                                          hierarchy, occurrence)))
    return failure();
  header->occurrence = occurrence;
  header->sourceOccurrence = source->domain.occurrence;
  header->destinationOccurrence = destination->domain.occurrence;
  header->sourceDomain = source->domain.declaration;
  header->destinationDomain = destination->domain.declaration;
  return *header;
}
LogicalResult verifyWaiverSourceDependencies(Operation *module, const DigitalDependencyGraph &graph,
                                             SequentialModelLimits limits) {
  SmallVector<SequentialSample, 8> samples;
  SmallVector<unsigned, 8> roots;
  const DomainModel domains(module);
  for (const auto &waiver : graph.waivers) {
    auto reference = waiver.operation->getAttrOfType<FlatSymbolRefAttr>("source_domain");
    auto actual = domains.resolveDomain(reference);
    bool formal = domains.unresolvedRequirement(reference) != nullptr;
    if (!graph.hierarchy && failed(actual) && !formal)
      return reject(waiver.operation, "waiver source lost its actual owned domain");
    if (!graph.hierarchy && !formal)
      reference = FlatSymbolRefAttr::get(module->getContext(), text(*actual, "sym_name"));
    samples.push_back({waiver.operation->getOperand(0), reference, waiver.operation, true,
                       waiver.operation, false, false, false, waiver.occurrence});
    roots.push_back(waiver.root);
  }
  return verifySequentialSampleDependencies(module, samples, graph, roots, limits);
}
} // namespace nodal
