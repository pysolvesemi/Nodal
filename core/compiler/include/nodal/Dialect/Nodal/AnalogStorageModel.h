#ifndef NODAL_DIALECT_NODAL_ANALOGSTORAGEMODEL_H
#define NODAL_DIALECT_NODAL_ANALOGSTORAGEMODEL_H

#include "mlir/IR/Operation.h"
#include "mlir/IR/Value.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/SmallVector.h"

#include <optional>
#include <string>

namespace nodal {
/// Derived capability and mapping facts from an actual SSA storage root. This
/// is not a declaration registry and does not establish definite initialization.
struct AnalogStorageReference : StaticShapeProjection {
  mlir::Operation *declaration = nullptr;
  VariableType elementType;
  mlir::Operation *procedure = nullptr;
  mlir::Operation *generatedOwner = nullptr;
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
bool analogPrecedesLexically(mlir::Operation *definition, mlir::Operation *use);
mlir::LogicalResult verifyGeneratedAnalogOwnership(mlir::Operation *operation);
mlir::LogicalResult verifyAnalogStorageProjection(mlir::Operation *projection);
bool analogStorageReferenceCovers(const AnalogStorageReference &initialized,
                                  const AnalogStorageReference &query);
} // namespace nodal
#endif
