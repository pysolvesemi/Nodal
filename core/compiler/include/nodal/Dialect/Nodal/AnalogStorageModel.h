#ifndef NODAL_DIALECT_NODAL_ANALOGSTORAGEMODEL_H
#define NODAL_DIALECT_NODAL_ANALOGSTORAGEMODEL_H

#include "mlir/IR/Operation.h"
#include "mlir/IR/Value.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"

#include "llvm/ADT/SmallVector.h"

#include <optional>
#include <string>

namespace nodal {
/// Derived capability and mapping facts from an actual SSA storage root. This
/// is not a declaration registry and does not establish definite initialization.
struct AnalogStorageReference {
  mlir::Operation *declaration = nullptr;
  mlir::Value root;
  VariableType elementType;
  mlir::Operation *procedure = nullptr;
  mlir::Operation *generatedOwner = nullptr;
  llvm::SmallVector<mlir::Operation *, 4> projections;
  std::string canonicalKey;
  bool wholeRoot = false;
  std::optional<int64_t> exactFixedCardinality;
  std::optional<int64_t> literalRootFlatOffset;
  llvm::SmallVector<std::string, 4> ancestorKeys;
  std::optional<int64_t> literalFirstOffset;
  std::optional<int64_t> literalLastOffset;
  bool contiguousLiteralRegion = false;
};

struct AnalogArrayValue {
  mlir::Operation *producer = nullptr;
  llvm::SmallVector<mlir::Operation *, 4> projections;
};
mlir::FailureOr<AnalogArrayValue> resolveAnalogArrayValue(mlir::Value value, mlir::Operation *use);

mlir::FailureOr<AnalogStorageReference> resolveAnalogStorageReference(mlir::Value reference,
                                                                      mlir::Operation *use);
mlir::FailureOr<mlir::Type> getAnalogStorageSnapshotType(mlir::Value reference,
                                                         mlir::Operation *use);
mlir::LogicalResult verifyGeneratedAnalogOwnership(mlir::Operation *operation);
mlir::LogicalResult verifyAnalogStorageProjection(mlir::Operation *projection);
bool analogStorageReferenceCovers(const AnalogStorageReference &initialized,
                                  const AnalogStorageReference &query);
} // namespace nodal
#endif
