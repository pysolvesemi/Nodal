#ifndef NODAL_DIALECT_NODAL_ENUMVALUEMODEL_H
#define NODAL_DIALECT_NODAL_ENUMVALUEMODEL_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

namespace nodal {

/// Validate the versioned semantic ABI, if present. Historical F19 definitions
/// with empty metadata retain their declaration-only contract. New typed enum
/// uses require a complete ABI and a directly root-owned symbol definition.
mlir::LogicalResult verifyEnumDefinitionAbi(mlir::Operation *definition);

/// Closed, genuine typed SSA enum operations; metadata never substitutes for
/// input values. Safe decode and validity consume the original bits separately.
mlir::LogicalResult verifyEnumValueOperation(mlir::Operation *operation);

/// Check every recursive enum type use in an owning nodal.module, including
/// attributes and block arguments. Historical FSM state_type annotations retain
/// their F19 declaration-only semantics. No process-global mutable registry.
mlir::LogicalResult verifyEnumValueModel(mlir::Operation *module);

/// Generic constant producers may carry enum values only when their exact
/// declared enum type resolves and the integer is a member of its code set.
/// Non-enum results pass; typed enum expression constructors retain their own
/// complete case-identity/ABI contract.
mlir::LogicalResult verifyEnumLiteralOperation(mlir::Operation *operation);

/// An enum parameter's exact default must be one of its definition's codes,
/// including unsigned magnitudes beyond signed i64. Non-enum parameters pass.
mlir::LogicalResult verifyEnumParameterDefault(mlir::Operation *parameter);

/// Shared exact membership check for effective defaults and root/instance/SSA
/// bindings. The supplied attribute is an unsigned mathematical magnitude,
/// retaining its full APInt precision. Non-enum parameters pass unchanged.
mlir::LogicalResult verifyEnumParameterValue(mlir::Operation *parameter, mlir::Attribute value);

} // namespace nodal

#endif
