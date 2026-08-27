#ifndef NODAL_DIALECT_NODAL_NATUREDISCIPLINE_H
#define NODAL_DIALECT_NODAL_NATUREDISCIPLINE_H

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

namespace nodal {

/// Resolve a nature declaration through zero or more hash-pinned import aliases.
mlir::FailureOr<mlir::Operation *> resolveNatureDeclaration(mlir::Operation *scope,
                                                            mlir::FlatSymbolRefAttr reference);

/// Resolve a discipline declaration through zero or more hash-pinned aliases.
mlir::FailureOr<mlir::Operation *> resolveDisciplineDeclaration(mlir::Operation *scope,
                                                                mlir::FlatSymbolRefAttr reference);

/// Compare canonical domain, potential nature, and optional flow nature.
/// Distinct symbols are compatible only when all three canonical identities
/// match after import resolution.
mlir::FailureOr<bool> areDisciplinesCompatible(mlir::Operation *scope, mlir::FlatSymbolRefAttr lhs,
                                               mlir::FlatSymbolRefAttr rhs);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_NATUREDISCIPLINE_H
