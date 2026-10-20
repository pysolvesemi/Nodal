#ifndef NODAL_DIALECT_NODAL_RESETMODEL_H
#define NODAL_DIALECT_NODAL_RESETMODEL_H
#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Dialect/Nodal/PhysicalControlModel.h"

#include "llvm/ADT/SmallVector.h"
namespace nodal {
class DomainModel;
struct ResetRoot {
  PhysicalControl physical;
  mlir::StringAttr polarity;
  mlir::Operation *release = nullptr;
  mlir::Value clock;
  mlir::StringAttr edge;
};
struct ResetInfo {
  mlir::StringAttr polarity;
  llvm::SmallVector<ResetRoot> roots;
};
/// Inspect actual typed producers, not descriptive safety flags. The bounded
/// traversal charges every retained ancestry fact as well as every producer.
mlir::FailureOr<ResetInfo> inspectReset(mlir::Value value, mlir::Operation *use,
                                        const DomainModel &domains);
mlir::LogicalResult verifyResetProducer(mlir::Operation *op);
mlir::LogicalResult verifyResetForBinding(mlir::Operation *binding, mlir::Operation *domain,
                                          const DomainModel &domains);
} // namespace nodal
#endif
