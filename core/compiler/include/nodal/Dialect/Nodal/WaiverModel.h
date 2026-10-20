#ifndef NODAL_DIALECT_NODAL_WAIVERMODEL_H
#define NODAL_DIALECT_NODAL_WAIVERMODEL_H

#include "nodal/Dialect/Nodal/SequentialModel.h"

namespace nodal {
class DomainModel;
class HierarchyModel;
struct DigitalDependencyGraph;
/// An owned, stateless boundary exception. This never denotes a synchronizer or
/// a state cut; its payload remains a real combinational dependency.
struct ExecutableWaiver {
  mlir::Operation *operation = nullptr;
  mlir::Operation *sourceDomain = nullptr;
  mlir::Operation *destinationDomain = nullptr;
  mlir::FlatSymbolRefAttr sourceReference;
  mlir::FlatSymbolRefAttr destinationReference;
  llvm::StringRef identity;
  llvm::StringRef id;
  uint64_t payloadBits = 0;
  unsigned occurrence = 0;
  unsigned sourceOccurrence = 0;
  unsigned destinationOccurrence = 0;
  bool unresolvedRequirements = false;
};
mlir::LogicalResult verifyWaiverOperation(mlir::Operation *operation);
mlir::FailureOr<ExecutableWaiver> inspectExecutableWaiver(mlir::Operation *operation,
                                                          const DomainModel &domains);
mlir::FailureOr<ExecutableWaiver> inspectExecutableWaiver(mlir::Operation *operation,
                                                          const HierarchyModel &hierarchy,
                                                          unsigned occurrence);
/// Uses the existing completed dependency graph, never recursively constructing
/// another graph or substituting an authored descriptor for actual provenance.
mlir::LogicalResult verifyWaiverSourceDependencies(mlir::Operation *module,
                                                   const DigitalDependencyGraph &graph,
                                                   SequentialModelLimits limits = {});
} // namespace nodal
#endif
