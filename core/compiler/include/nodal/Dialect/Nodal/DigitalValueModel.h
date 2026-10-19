#ifndef NODAL_DIALECT_NODAL_DIGITALVALUEMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALVALUEMODEL_H

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

namespace nodal {

class ParameterIntegerEnvironment;

/// Bits, UInt and SInt, including their exact symbolic-width representations.
/// Enum values, builtin integers and analog quantities have separate owners.
bool isDigitalScalarType(mlir::Type type);

/// The common pure digital value profile: scalar Bits/UInt/SInt and owned
/// enum/Struct/Vec values with finite exact type proofs. Root Integer/Real and
/// capabilities are excluded; ordinary stored aggregate leaves remain intact.
bool isDigitalCombinationalValueType(mlir::Type type, mlir::Operation *module,
                                     const ParameterIntegerEnvironment *environment = nullptr);

/// Shared mathematical integer attribute fit for literals and parameter values.
/// The owning module resolves symbolic widths over all legal settings; unsigned
/// APInt carriers remain nonnegative even when their high bit is set. This is a
/// read-only fit query, not constant evaluation or parameter specialization.
bool digitalIntegerAttributeFits(mlir::IntegerAttr value, mlir::Type type, mlir::Operation *module,
                                 const ParameterIntegerEnvironment *environment = nullptr);

/// Shared exact literal validation for nodal.constant and nodal.const_literal.
/// Symbolic widths use the owning module's all-legal bounds, never its default.
/// ConstLiteral additionally requires spelling to reproduce its integer value.
mlir::LogicalResult
verifyDigitalLiteralOperation(mlir::Operation *operation,
                              const ParameterIntegerEnvironment *environment = nullptr);

/// Closed digital combinational expressions and unary scalar conversions,
/// independently checking arity, owner, exact type/width identities, static
/// literal indices and finite checked-value proofs. Select preserves complete
/// storage types. Bit-pattern allocation and mathematical proofs are bounded
/// independently of the declared scalar width; no parameter default is used.
mlir::LogicalResult
verifyDigitalValueOperation(mlir::Operation *operation,
                            const ParameterIntegerEnvironment *environment = nullptr);

/// Verify module-owned digital expressions and literals with query-local caches.
/// Collection bodies are independently checked under each admitted context.
/// includeCollections=false is the ordinary-only module orchestration pass; it
/// does not establish any collection fact. The default delegates collections to
/// their contextual proof coordinator and preserves the standalone contract.
mlir::LogicalResult verifyDigitalValueModel(mlir::Operation *module,
                                            bool includeCollections = true);

} // namespace nodal

#endif
