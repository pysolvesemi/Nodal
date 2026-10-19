#ifndef NODAL_DIALECT_NODAL_ENUMVALUEMODEL_H
#define NODAL_DIALECT_NODAL_ENUMVALUEMODEL_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

namespace nodal {

class ParameterIntegerEnvironment;

/// Validate the versioned semantic ABI, if present. Historical F19 definitions
/// with empty metadata retain their declaration-only contract. New typed enum
/// uses require a complete ABI and a directly root-owned symbol definition.
mlir::LogicalResult verifyEnumDefinitionAbi(mlir::Operation *definition);

/// Closed, genuine typed SSA enum operations; metadata never substitutes for
/// input values. Safe decode and validity consume the original bits separately.
mlir::LogicalResult
verifyEnumValueOperation(mlir::Operation *operation,
                         const ParameterIntegerEnvironment *environment = nullptr);

/// Check recursive enum ABI uses on this actual operation only. Collection
/// block arguments and bodies are checked by their contextual proof owner.
mlir::LogicalResult
verifyEnumTypeOperation(mlir::Operation *operation,
                        const ParameterIntegerEnvironment *environment = nullptr);

/// Check module-owned enum uses. Collection bodies have contextual checks;
/// historical FSM state_type annotations retain declaration-only semantics.
/// includeCollections=false is the ordinary-only module orchestration pass; it
/// does not establish any collection fact. The default delegates collections to
/// their contextual proof coordinator and preserves the standalone contract.
mlir::LogicalResult verifyEnumValueModel(mlir::Operation *module, bool includeCollections = true);

/// Generic constant producers may carry enum values only when their exact
/// declared enum type resolves and the integer is a member of its code set.
/// Non-enum results pass; typed enum expression constructors retain their own
/// complete case-identity/ABI contract.
mlir::LogicalResult
verifyEnumLiteralOperation(mlir::Operation *operation,
                           const ParameterIntegerEnvironment *environment = nullptr);

/// An enum parameter's exact default must be one of its definition's codes,
/// including unsigned magnitudes beyond signed i64. Non-enum parameters pass.
mlir::LogicalResult verifyEnumParameterDefault(mlir::Operation *parameter);

/// Shared exact membership check for effective defaults and root/instance/SSA
/// bindings. The supplied attribute is an unsigned mathematical magnitude,
/// retaining its full APInt precision. Non-enum parameters pass unchanged.
mlir::LogicalResult verifyEnumParameterValue(mlir::Operation *parameter, mlir::Attribute value);

} // namespace nodal

#endif
