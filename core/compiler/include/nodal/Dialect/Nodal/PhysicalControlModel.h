#ifndef NODAL_DIALECT_NODAL_PHYSICALCONTROLMODEL_H
#define NODAL_DIALECT_NODAL_PHYSICALCONTROLMODEL_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include <optional>
#include <string>

namespace nodal {
/// One physical scalar control input. The declaration plus canonical element
/// key is identity: alternate port_value/view producers neither split one root
/// nor conflate different elements of one shaped boundary.
struct PhysicalControl {
  mlir::Operation *port = nullptr;
  mlir::Value root;
  std::string scalarKey;
  std::optional<int64_t> literalOffset;
  bool operator==(const PhysicalControl &other) const {
    return port == other.port && scalarKey == other.scalarKey;
  }
};

/// Independently resolve an exact owned Clock/Reset input, reusing ShapeModel
/// for static projection verification and canonical coordinates. The executable
/// profile requires fixed finite shapes and a proved literal element offset;
/// dynamic selectors and symbolic/default-based guesses cannot establish proof.
mlir::FailureOr<PhysicalControl> resolvePhysicalControl(mlir::Value value, mlir::Operation *use,
                                                        llvm::StringRef kind);
bool samePhysicalControl(mlir::Value left, mlir::Value right, mlir::Operation *use,
                         llvm::StringRef kind);
} // namespace nodal
#endif
