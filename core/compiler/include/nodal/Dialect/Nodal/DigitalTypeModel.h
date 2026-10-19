#ifndef NODAL_DIALECT_NODAL_DIGITALTYPEMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALTYPEMODEL_H

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Diagnostics.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/STLFunctionalExtras.h"

namespace nodal {

/// Finite structural metadata limits, independent of represented bit/lane count.
inline constexpr unsigned digitalTypeMaximumDepth = 128;
inline constexpr unsigned digitalTypeMaximumNodes = 65536;
inline constexpr unsigned digitalTypeMaximumIdentifierBytes = 1024;
inline constexpr unsigned digitalTypeMaximumWidthTokenBytes = 4096;

/// A symbol or source-path identity, never inline arithmetic or a width default.
bool isCanonicalDigitalWidthToken(llvm::StringRef token);

/// Type-local validation shared by checked construction and native use checking.
mlir::LogicalResult
verifyDigitalStructFields(llvm::function_ref<mlir::InFlightDiagnostic()> emitError,
                          llvm::StringRef name, mlir::ArrayAttr fields);

/// Directionless storage values only. Protocols, Interface, nets, drivers, analog
/// quantities and other capabilities are not Struct/Mem element value types.
/// The query is bounded and does not resolve module-owned symbolic identities.
bool isDigitalStorageType(mlir::Type type);

/// A direct clock/reset capability or its structural shaped boundary view.
/// This is not a storage/scalar query; ordinary Struct/Mem/protocol payloads do
/// not admit these capabilities. Shape proofs remain owned by ShapeModel.
bool isDigitalControlBoundaryType(mlir::Type type);
mlir::FailureOr<int64_t> inferDigitalControlMaximumElements(mlir::Type type,
                                                            mlir::Operation *module);

/// Exact scalar width bounds. Symbolic widths use the existing structural
/// parameter/expression proof without substituting an overridable default.
mlir::FailureOr<ParameterIntegerBounds> inferDigitalWidthBounds(mlir::Type type,
                                                                mlir::Operation *module);

/// Prove one directionless storage type's worst-case recursive bit footprint,
/// reusing the module type verifier's checked shape/width arithmetic. The use is
/// either the owning module or an operation directly in it; diagnostics identify
/// this actual use. No physical lanes or bits are expanded.
mlir::FailureOr<int64_t> inferDigitalStorageMaximumBits(mlir::Type type, mlir::Operation *use);

/// Read-only verification of every type use in one owning nodal.module (including
/// nested attributes, operands, results and block arguments). Nested module
/// definitions are checked by their own verifier. Memoization is module-local;
/// recursive shape/cardinality and maximum bit footprints use checked i64
/// metadata arithmetic without expanding fields by their physical lane count.
mlir::LogicalResult verifyDigitalTypeModel(mlir::Operation *module);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_DIGITALTYPEMODEL_H
