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
/// Return clock/reset through bounded structural shape layers, otherwise empty.
llvm::StringRef getDigitalControlBoundaryKind(mlir::Type type);
mlir::FailureOr<int64_t>
inferDigitalControlMaximumElements(mlir::Type type, mlir::Operation *module,
                                   const ParameterIntegerEnvironment *environment = nullptr);

/// Exact scalar width bounds. Symbolic widths use the existing structural
/// parameter/expression proof without substituting an overridable default.
mlir::FailureOr<ParameterIntegerBounds>
inferDigitalWidthBounds(mlir::Type type, mlir::Operation *module,
                        const ParameterIntegerEnvironment *environment = nullptr);

/// Exact owned scalar-width identity in the canonical static Integer DAG.
/// Equal bounds/defaults never substitute for parameter or expression identity.
mlir::FailureOr<InstantiatedIntegerExpression>
inferDigitalWidthExpression(mlir::Type type, mlir::Operation *module,
                            const ParameterIntegerEnvironment *environment = nullptr);
/// Exact child scalar width under a finalized immediate instance substitution.
/// The child type retains its declaration owner; no parent token lookup or
/// default/bounds-based identity is permitted.
mlir::FailureOr<InstantiatedIntegerExpression>
inferInstanceDigitalWidthExpression(mlir::Type type, mlir::Operation *instance);

/// Exact original generated-template width, retaining actual induction identities
/// and proving the full formal contract before target transport is considered.
mlir::FailureOr<InstantiatedIntegerExpression>
inferGeneratedInstanceDigitalWidthExpression(mlir::Type type, mlir::Operation *instance);

/// Same complete storage type within one actual module: retain scalar kind,
/// nominal enum/Struct identity, ordered fields and shape rank, while comparing
/// independently authored width/extent expressions by their exact all-legal DAG.
/// Both recursive types are validated; capabilities and cross-owner bindings
/// are excluded. The bounded query memoizes type pairs without physical expansion.
bool equivalentDigitalStorageTypes(mlir::Type left, mlir::Type right, mlir::Operation *module,
                                   const ParameterIntegerEnvironment *environment = nullptr);

/// Exact complete ABI across two independently admitted lexical contexts.
/// Both types are validated before comparison; no width-family coercion.
bool equivalentDigitalStorageTypesAcross(mlir::Type left,
                                         const ParameterIntegerEnvironment *leftContext,
                                         mlir::Type right,
                                         const ParameterIntegerEnvironment *rightContext,
                                         mlir::Operation *module);
/// Complete child formal versus parent actual ABI under this exact direct
/// instance's finalized parameter environment. Reuses the ordinary recursive
/// type checker with independently validated owners, shapes and width DAGs.
/// Generated lexical instances require their separately admitted family view.
/// Exact selected structural views, independently inspected at this boundary.
bool equivalentSelectedDigitalStorageTypes(mlir::Operation *selection, mlir::Operation *instance,
                                           mlir::Type formal, mlir::Type actual);
/// Compare types at their independently admitted original lexical uses. Equal
/// token spellings do not imply equal DAGs across regional contexts.
bool equivalentDigitalStorageTypesAt(mlir::Type left, mlir::Operation *leftUse, mlir::Type right,
                                     mlir::Operation *rightUse);
/// Direct exported Interface types retain their original definition/endpoint
/// anchor. The anchor is checked against the selected original endpoint.
bool equivalentSelectedDigitalStorageTypesAt(mlir::Operation *selection, mlir::Operation *formalUse,
                                             mlir::Type formal, mlir::Type actual);
bool equivalentSelectedDigitalStorageTypesAcross(mlir::Operation *leftSelection,
                                                 mlir::Operation *leftInstance, mlir::Type left,
                                                 mlir::Operation *rightSelection,
                                                 mlir::Operation *rightInstance, mlir::Type right);
/// Original all-lane formal substitution versus an actual type use in the same
/// Generate body. Selection specialization and ordinary Module queries are separate.
bool equivalentGeneratedInstanceDigitalStorageTypes(mlir::Operation *instance, mlir::Type formal,
                                                    mlir::Type actual, mlir::Operation *use);
bool equivalentInstanceDigitalStorageTypes(mlir::Operation *instance, mlir::Type formal,
                                           mlir::Type actual);
/// Compare two complete logical types in one actual parent context. A nonnull
/// instance supplies its exact immediate child's declaration owner; null means
/// a parent-owned type. Both substitutions use finalized canonical bindings.
bool equivalentDigitalStorageTypesAcrossInstances(mlir::Operation *parent, mlir::Type left,
                                                  mlir::Operation *leftInstance, mlir::Type right,
                                                  mlir::Operation *rightInstance);

/// Construction-only width-family relation. Both complete types are validated.
/// Scalar width leaves may differ; scalar kind, enum ABI, Struct name/ordered
/// fields and every inner dimension identity remain exact. This never licenses
/// assignment or port/storage ABI substitution.
bool sameDigitalStorageWidthFamily(mlir::Type base, mlir::Type instance, mlir::Operation *module,
                                   const ParameterIntegerEnvironment *environment = nullptr);

/// Checked recursive storage validity, separate from attained numeric footprint.
/// Absence means an independently checked empty generated occurrence, never zero
/// bits. Every concrete sibling/schema/effect/domain obligation remains checked.
struct DigitalStorageTypeFacts {
  std::optional<int64_t> maximumBits;
};
mlir::FailureOr<DigitalStorageTypeFacts>
inspectDigitalStorageType(mlir::Type type, mlir::Operation *use,
                          const ParameterIntegerEnvironment *environment = nullptr);

/// Complete original child storage type under its exact generated Instance.
/// Absence preserves checked empty dependent attainment, never a numeric zero.
/// Original declaration validity and independent concrete fields remain checked.
mlir::FailureOr<DigitalStorageTypeFacts>
inspectGeneratedInstanceDigitalStorageType(mlir::Type formal, mlir::Operation *instance);

/// Prove one directionless storage type's worst-case recursive bit footprint.
/// Without a context the use is the owning module or a direct child. An admitted
/// context permits an actual same-block collection use. No lanes are expanded.
mlir::FailureOr<int64_t>
inferDigitalStorageMaximumBits(mlir::Type type, mlir::Operation *use,
                               const ParameterIntegerEnvironment *environment = nullptr);

/// Finite bit-pattern payloads preserve the complete storage ABI but exclude
/// host Integer/Real and capabilities recursively. Enum leaves are admitted by
/// their canonical ABI owner, without physical lane expansion.
mlir::FailureOr<int64_t> inferDigitalBitPatternMaximumBits(mlir::Type type, mlir::Operation *use);

/// Same complete bit-pattern ABI with every scalar width and shape extent
/// proved fixed. Bounds/defaults never substitute for exact target widths;
/// footprint arithmetic and nominal validation remain with the shared owner.
mlir::FailureOr<int64_t> inferDigitalBitPatternFixedBits(mlir::Type type, mlir::Operation *use);

/// Verify this actual operation under an optional admitted context. Collection
/// block arguments and bodies belong to the collection proof coordinator.
mlir::LogicalResult
verifyDigitalTypeOperation(mlir::Operation *operation,
                           const ParameterIntegerEnvironment *environment = nullptr);

/// Verify module-owned type uses, including nested attributes and ordinary block
/// arguments. Collection bodies are checked separately under admitted contexts;
/// nested module definitions have their own verifier. Query-local memoization
/// and checked i64 footprint arithmetic never expand physical lanes or bits.
/// includeCollections=false is the ordinary-only module orchestration pass; it
/// does not establish any collection fact. The default delegates collections to
/// their contextual proof coordinator and preserves the standalone contract.
mlir::LogicalResult verifyDigitalTypeModel(mlir::Operation *module, bool includeCollections = true);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_DIGITALTYPEMODEL_H
