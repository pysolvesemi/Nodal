#ifndef NODAL_DIALECT_NODAL_SHAPEMODEL_H
#define NODAL_DIALECT_NODAL_SHAPEMODEL_H

#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"

#include <optional>
#include <string>

namespace nodal {

/// One proved canonical dimension. Exactly one representation is populated:
/// a positive literal, an owned parameter declaration, or an owned expression.
struct StaticShapeDimension {
  ParameterIntegerBounds bounds;
  mlir::Operation *parameter = nullptr;
  mlir::Value expression;
  int64_t literal = 0;
};

/// Compare an exact child formal extent under its finalized instance parameter
/// environment with an owned parent extent. Physical index identity is separate.
mlir::LogicalResult verifyInstanceShapeDimension(mlir::Operation *instance,
                                                 const StaticShapeDimension &formal,
                                                 const StaticShapeDimension &parentActual);

/// Check canonical static provenance and parameter effect dependencies only.
/// Arithmetic validity and positivity are separate ParameterModel queries.
bool hasStaticIntegerEffect(mlir::Value value, mlir::Operation *module,
                            llvm::StringRef requiredEffect,
                            const ParameterIntegerEnvironment *environment = nullptr);

/// Resolve a canonical dimension without substituting parameter defaults.
/// Requires positive finite i64 bounds and ordinary module ownership, or an
/// explicitly admitted lexical collection context. Retains the actual SSA Value.
mlir::FailureOr<StaticShapeDimension>
resolveStaticShapeDimension(mlir::Operation *module, llvm::StringRef dimension,
                            llvm::StringRef requiredEffect = "shape",
                            const ParameterIntegerEnvironment *environment = nullptr);

/// Prove a nonempty shape's worst-case cardinality fits positive signed i64.
mlir::FailureOr<int64_t>
inferStaticShapeMaximumCardinality(mlir::Operation *module, llvm::StringRef dimensions,
                                   const ParameterIntegerEnvironment *environment = nullptr);

/// Prove an owned static index using the existing literal/i64 carriers, with
/// explicit admitted collection context when the value is collection-local.
mlir::FailureOr<ParameterIntegerBounds>
inferStaticShapeIndexBounds(mlir::Operation *module, mlir::Value value,
                            const ParameterIntegerEnvironment *environment = nullptr);

struct StaticShapeIndexIdentity {
  // StaticArgument uses the actual collection root and argument ordinal in
  // literal, preserving identity without treating equal bounds as equality.
  enum Kind { Literal, Parameter, Expression, Induction, StaticArgument, IterationAffine } kind;
  int64_t literal = 0;
  mlir::Operation *root = nullptr;
  bool operator==(const StaticShapeIndexIdentity &other) const {
    return kind == other.kind && literal == other.literal && root == other.root;
  }
};
mlir::FailureOr<StaticShapeIndexIdentity>
resolveStaticShapeIndexIdentity(mlir::Operation *module, mlir::Value value,
                                const ParameterIntegerEnvironment *environment = nullptr);

/// A use-aware static or direct active-induction proof. An empty attained range
/// means the independently verified generated domain has no occurrences.
struct ShapeIndexProof {
  StaticShapeIndexIdentity identity;
  std::optional<ParameterIntegerBounds> attainedBounds;
};
mlir::FailureOr<ShapeIndexProof>
resolveShapeIndexProof(mlir::Value value, mlir::Operation *use,
                       const ParameterIntegerEnvironment *environment = nullptr);
mlir::LogicalResult
verifyShapeIndexInExtent(mlir::Value value, const StaticShapeDimension &extent,
                         mlir::Operation *use,
                         const ParameterIntegerEnvironment *environment = nullptr);

/// Resolve a zero-based family ordinal to its canonical induction coordinate.
/// Existence is proved for every legal bound setting, without lane enumeration.
mlir::FailureOr<InstantiatedIntegerExpression>
resolveGeneratedOrdinal(mlir::Operation *family, mlir::Value ordinal, mlir::Operation *use);

/// Element-neutral facts derived from an actual acyclic SSA projection chain.
/// These establish shape/ordinal mapping, never storage or conservative rights.
struct StaticShapeProjection {
  mlir::Value root;
  llvm::SmallVector<mlir::Operation *, 4> projections;
  std::string canonicalKey;
  std::string canonicalScalarKey;
  bool wholeRoot = false;
  std::optional<int64_t> exactFixedCardinality;
  std::optional<int64_t> literalRootFlatOffset;
  llvm::SmallVector<std::string, 4> ancestorKeys;
  std::optional<int64_t> literalFirstOffset;
  std::optional<int64_t> literalLastOffset;
  bool contiguousLiteralRegion = false;
  std::optional<ParameterIntegerBounds> rootFlatBounds;
  llvm::SmallVector<ParameterIntegerBounds, 4> rootCoordinateBounds;
};
mlir::FailureOr<StaticShapeProjection> resolveStaticShapeProjection(mlir::Value reference,
                                                                    mlir::Operation *use);

/// Exact, element-neutral images in one actual root. Boxes use half-open root
/// coordinates; intervals use half-open root row-major ordinals. A reshape
/// preserves the selected image and never makes a column into an interval.
struct StaticShapeRegionAxis {
  InstantiatedIntegerExpression lower, upperExclusive;
};
/// One actual injective induction family, retained with its canonical image.
/// axes[varyingAxis] is the progression boundary in root coordinates; a missing
/// axis means root row-major ordinals. The region must never be treated as a
/// filled box/interval while stride is greater than one.
struct StaticShapeIterationImage {
  /// Actual bounded-loop or Generate owner; only the former grants ordering.
  mlir::Operation *loop = nullptr;
  GeneratedDomain domain;
  InstantiatedIntegerExpression offset;
  InstantiatedIntegerExpression tripCount;
  /// Width of one occurrence in the varying coordinate (or flat ordinal).
  InstantiatedIntegerExpression occurrenceWidth;
  std::optional<unsigned> varyingAxis;
  int64_t stride = 1;
  bool empty = false;
};
struct StaticShapeRegion {
  enum class Kind { Whole, Box, Interval, Empty } kind = Kind::Whole;
  mlir::Value root;
  llvm::SmallVector<StaticShapeRegionAxis, 4> axes;
  StaticShapeRegionAxis interval;
  /// Retained actual map for scalar identity; coverage cuts alone do not
  /// identify independently authored compound selector atoms.
  std::optional<StaticShapeProjection> projection;
  std::optional<StaticShapeIterationImage> iteration;
};
enum class ShapeRegionProofStatus { Proved, Unsupported, Invalid, ResourceLimit };
struct ShapeRegionProofLimits {
  uint64_t maximumWork = 200000;
  uint64_t maximumStates = 16384;
};
/// All queries are read-only and transactional: outputs are assigned only after
/// a proof succeeds. Limits count query-local cut/comparison/map work and active
/// partition states, independently of the number of physical lanes.
ShapeRegionProofStatus resolveStaticShapeRegion(const StaticShapeProjection &projection,
                                                mlir::Operation *use, StaticShapeRegion &result,
                                                ShapeRegionProofLimits limits = {});
ShapeRegionProofStatus verifyStaticShapeRegionPartition(mlir::Value root,
                                                        llvm::ArrayRef<StaticShapeRegion> regions,
                                                        mlir::Operation *use,
                                                        ShapeRegionProofLimits limits = {});

/// Resolve an actual affine index from either an ordered bounded loop or a
/// Generate. Captures retain the original induction identity; Generate arithmetic
/// is independently admitted by ParameterModel before affine decomposition.
mlir::FailureOr<DigitalIterationIndex> resolveStaticShapeIterationIndex(mlir::Value value,
                                                                        mlir::Operation *use);

/// Resolve the aggregate image of one actual iteration-selected projection. Success
/// establishes per-iteration injectivity, including empty domains; ordinary or
/// invariant projections return Unsupported. The returned iteration facts retain
/// actual iteration/domain/offset identity. Ordering remains loop-only.
ShapeRegionProofStatus resolveStaticShapeIterationImage(const StaticShapeProjection &projection,
                                                        mlir::Operation *use,
                                                        StaticShapeRegion &result,
                                                        ShapeRegionProofLimits limits = {});

/// Prove that overlapping images identify the same actual occurrence. Requires
/// one common actual iteration owner and identical injective per-occurrence maps.
/// This does not grant ordering to a Generate or refine any predicate by lanes.
ShapeRegionProofStatus proveStaticShapeSameOccurrence(const StaticShapeProjection &left,
                                                      const StaticShapeProjection &right,
                                                      mlir::Operation *use,
                                                      ShapeRegionProofLimits limits = {});

/// Exact occurrence displacement: read(q) equals write(q + result). Only the
/// same actual loop and identical canonical physical per-occurrence map qualify.
/// A result of -1 is the immediately preceding occurrence; failure is unknown.
mlir::FailureOr<int64_t> compareStaticShapeIterationPositions(const StaticShapeProjection &read,
                                                              const StaticShapeProjection &write,
                                                              mlir::Operation *use,
                                                              ShapeRegionProofLimits limits = {});

/// Check the exact ordinary q=0 base image of the supported unit-step chain.
/// Writer uniqueness, source order and external seed dependencies remain the
/// assignment graph's responsibility.
ShapeRegionProofStatus verifyStaticShapeIterationPredecessorSeed(
    const StaticShapeProjection &read, const StaticShapeProjection &write,
    const StaticShapeProjection &seed, mlir::Operation *use, ShapeRegionProofLimits limits = {});

enum class ShapeRegionOverlap { Disjoint, Overlapping, Unknown };
struct StaticShapeOverlap {
  ShapeRegionProofStatus status = ShapeRegionProofStatus::Unsupported;
  ShapeRegionOverlap overlap = ShapeRegionOverlap::Unknown;
};
/// Exact all-legal pairwise relation. Unknown (including unsupported mapping)
/// cannot establish independent writers. Actual SSA chains are rechecked, and
/// physical selector identities are preserved; no supplied flags are trusted.
StaticShapeOverlap compareStaticShapeProjections(const StaticShapeProjection &left,
                                                 const StaticShapeProjection &right,
                                                 mlir::Operation *use,
                                                 ShapeRegionProofLimits limits = {});

/// Prove every physical point of a read is covered exactly once by the supplied
/// disjoint writes. Uses the same canonical cut/partition engine, with bounded
/// authored-region work rather than physical lane enumeration.
ShapeRegionProofStatus
verifyStaticShapeProjectionCoverage(const StaticShapeProjection &read,
                                    llvm::ArrayRef<StaticShapeProjection> writes,
                                    mlir::Operation *use, ShapeRegionProofLimits limits = {});

/// One physical scalar. Coordinates and/or a flat ordinal are exact expressions;
/// opaque selector atoms retain actual SSA identity, including active induction.
struct StaticShapePoint {
  mlir::Value root;
  llvm::SmallVector<InstantiatedIntegerExpression, 4> coordinates;
  std::optional<InstantiatedIntegerExpression> flatOrdinal;
};
enum class ShapeRegionMembership { Inside, Outside, Unknown };
struct StaticShapeScalarLocation {
  ShapeRegionProofStatus status = ShapeRegionProofStatus::Unsupported;
  ShapeRegionMembership membership = ShapeRegionMembership::Unknown;
  std::optional<InstantiatedIntegerExpression> ordinal;
};
ShapeRegionProofStatus resolveStaticShapePoint(const StaticShapeProjection &projection,
                                               mlir::Operation *use, StaticShapePoint &result,
                                               ShapeRegionProofLimits limits = {});
StaticShapeScalarLocation locateStaticShapeScalar(const StaticShapeRegion &region,
                                                  const StaticShapePoint &point,
                                                  mlir::Operation *use,
                                                  ShapeRegionProofLimits limits = {});
StaticShapeScalarLocation locateStaticShapeScalar(const StaticShapeProjection &regionProjection,
                                                  const StaticShapeProjection &scalarProjection,
                                                  mlir::Operation *use,
                                                  ShapeRegionProofLimits limits = {});
ShapeRegionProofStatus mapStaticShapeOrdinal(const StaticShapeRegion &region,
                                             const InstantiatedIntegerExpression &ordinal,
                                             mlir::Operation *use, StaticShapePoint &result,
                                             ShapeRegionProofLimits limits = {});
ShapeRegionProofStatus mapStaticShapeOrdinal(const StaticShapeProjection &projection,
                                             const InstantiatedIntegerExpression &ordinal,
                                             mlir::Operation *use, StaticShapePoint &result,
                                             ShapeRegionProofLimits limits = {});

/// Local transformation proofs shared by operation and storage-map verification.
/// These do not grant storage capability or establish initialization.
mlir::LogicalResult
verifyShapeIndexMapping(mlir::Operation *operation,
                        const ParameterIntegerEnvironment *environment = nullptr);
mlir::LogicalResult
verifyShapeViewMapping(mlir::Operation *operation,
                       const ParameterIntegerEnvironment *environment = nullptr);
mlir::LogicalResult
verifyShapeSliceMapping(mlir::Operation *operation,
                        const ParameterIntegerEnvironment *environment = nullptr);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_SHAPEMODEL_H
