#ifndef NODAL_DIALECT_NODAL_PARAMETERMODEL_H
#define NODAL_DIALECT_NODAL_PARAMETERMODEL_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Operation.h"
#include "mlir/IR/Value.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/ArrayRef.h"
#include "llvm/ADT/StringRef.h"

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace nodal {

/// Verify one parameter declaration, including its explicit kind,
/// ordinary/structural classification, and optional physical unit.
mlir::LogicalResult verifyParameterDeclaration(mlir::Operation *operation);

/// Verify cross-operation constant folding, constraints, overrides,
/// structural envelopes, and dynamic-value exclusion for the whole design.
mlir::LogicalResult verifyParameterModel(mlir::ModuleOp module);

/// Check the exclusive legacy-attribute or three-SSA-value generate bound form.
mlir::LogicalResult verifyGeneratedBoundForm(mlir::Operation *operation);

/// Conservative closed bounds over every legal parameter setting. These are
/// mathematical integers, not evaluated defaults or a wrapping target value.
struct ParameterIntegerBounds {
  int64_t lower;
  int64_t upper;
};

/// Analyze the canonical integer constant-expression DAG without substituting
/// overridable defaults. Requires explicit finite ranges for symbolic parameters;
/// fixed parameters retain the variability of their default expression. Unknown,
/// cyclic, non-integer, overflowing or unsupported proofs fail closed. This
/// bounded profile represents endpoints in signed 64-bit host metadata only.
mlir::FailureOr<ParameterIntegerBounds> inferParameterIntegerBounds(mlir::Value value);

/// Query the same conservative analysis for a canonical parameter declaration.
/// Symbolic ranges, exclusive endpoints and intersecting constraints are retained;
/// an overridable default alone is never a bound.
mlir::FailureOr<ParameterIntegerBounds> inferParameterIntegerBounds(mlir::Operation *parameter);

/// Immutable, query-local proof DAG. Parent parameters retain declaration identity;
/// a singleton range or equal default never converts such an atom to a literal.
struct InstantiatedIntegerNode {
  enum class Kind { Literal, ParentParameter, Apply, OpaqueValue };
  Kind kind;
  int64_t literal = 0;
  mlir::Operation *parameter = nullptr;
  mlir::Operation *origin = nullptr;
  mlir::Value identity;
  std::string opcode;
  std::vector<std::shared_ptr<const InstantiatedIntegerNode>> operands;
  ParameterIntegerBounds bounds;
};
struct InstantiatedIntegerExpression {
  std::shared_ptr<const InstantiatedIntegerNode> root;
  ParameterIntegerBounds bounds;
};

/// Resolve a finalized immediate instance environment, substituting actual SSA
/// overrides, literal bindings and recursively dependent defaults. Every used
/// formal is checked against its effective constraints over the whole actual
/// envelope. Unknown correlation, cycles and overflow fail closed.
mlir::FailureOr<InstantiatedIntegerExpression>
resolveInstanceIntegerParameter(mlir::Operation *instance, mlir::Operation *childParameter);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveInstanceIntegerExpression(mlir::Operation *instance, mlir::Value childExpression);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveOwnedIntegerParameter(mlir::Operation *module, mlir::Operation *parameter);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveOwnedIntegerExpression(mlir::Operation *module, mlir::Value expression);
InstantiatedIntegerExpression makeLiteralIntegerExpression(int64_t value);
/// Query-only identity atom. The caller must independently prove the value's
/// ownership and bounds; ordinary parameter interpretation never creates these.
InstantiatedIntegerExpression makeOpaqueIntegerExpression(mlir::Value identity,
                                                          ParameterIntegerBounds bounds);
/// Checked composition of already proved immutable expressions. Reuses the
/// parameter arithmetic policy and retains opaque identities through arithmetic.
mlir::FailureOr<InstantiatedIntegerExpression>
composeIntegerExpression(llvm::StringRef opcode,
                         llvm::ArrayRef<InstantiatedIntegerExpression> operands);
/// Conservative all-legal ordering, using exact identity and checked bounds.
bool proveIntegerExpressionLess(const InstantiatedIntegerExpression &left,
                                const InstantiatedIntegerExpression &right, bool inclusive = false);
bool equivalentInstantiatedIntegerExpressions(const InstantiatedIntegerExpression &left,
                                              const InstantiatedIntegerExpression &right);

/// Resolve only a direct instance in the use's immediate Module/Generate scope.
/// Module definitions and parameter declarations have separate owner namespaces.
mlir::LogicalResult verifyGeneratedStructuralOwnership(mlir::Operation *operation);
mlir::Operation *resolveStructuralInstance(mlir::Operation *use, mlir::FlatSymbolRefAttr reference);
mlir::Operation *resolveInstanceModuleDefinition(mlir::Operation *instance);

/// Read-only facts from the existing checked positive generated-domain proof.
/// No attained bounds means that the entire domain is proven empty. Otherwise
/// the closed interval conservatively contains every attained source induction.
struct GeneratedDomain {
  mlir::Operation *module = nullptr;
  mlir::Value induction;
  InstantiatedIntegerExpression lower, upperExclusive, step;
  uint64_t maximumTripCount = 0;
  std::optional<ParameterIntegerBounds> attainedInductionBounds;
};
mlir::FailureOr<GeneratedDomain> resolveGeneratedDomain(mlir::Operation *operation);

/// Render a compile-time expression using retained literal spellings.
mlir::FailureOr<std::string> renderParameterConstantExpression(mlir::Value value);

/// Render an expression in one parameter's declared unit context. A
/// dimensionless authored magnitude receives the target parameter's exact
/// native scale suffix (or an explicit scale factor for compound expressions).
mlir::FailureOr<std::string> renderParameterConstantExpression(mlir::Value value,
                                                               mlir::Operation *targetParameter);

/// Return the explicit or legacy-inferred parameter kind.
llvm::StringRef getParameterKind(mlir::Operation *parameter);

/// Return the explicit or legacy-inferred parameter classification.
llvm::StringRef getParameterClassification(mlir::Operation *parameter);

/// True only for explicitly classified structural parameters.
bool isStructuralParameter(mlir::Operation *parameter);

/// Return the display symbol of a declared parameter unit, or an empty string.
std::string getParameterUnitSymbol(mlir::Operation *parameter);

/// Return the exact native Verilog scale suffix of the parameter unit.
std::string getParameterUnitNativeSuffix(mlir::Operation *parameter);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_PARAMETERMODEL_H
