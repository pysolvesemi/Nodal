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

/// Opt-in mathematical interpretation for compiler-derived static i64 metadata.
/// Storage preserves existing public literal/storage rules. MathematicalSigned64
/// reuses the generated-bound signed-i64 policy, without admitting runtime values,
/// arbitrary block arguments, foreign owners or units. Width/count callers must
/// independently enforce their positive and finite domain requirements.
enum class ParameterIntegerMode { Storage, MathematicalSigned64 };

/// Analyze the canonical integer constant-expression DAG without substituting
/// overridable defaults. Requires explicit finite ranges for symbolic parameters;
/// fixed parameters retain the variability of their default expression. Unknown,
/// cyclic, non-integer, overflowing or unsupported proofs fail closed. This
/// bounded profile represents endpoints in signed 64-bit host metadata only.
mlir::FailureOr<ParameterIntegerBounds> inferParameterIntegerBounds(mlir::Value value);
mlir::FailureOr<ParameterIntegerBounds> inferParameterIntegerBounds(mlir::Value value,
                                                                    ParameterIntegerMode mode);

/// Query the same conservative analysis for a canonical parameter declaration.
/// Symbolic ranges, exclusive endpoints and intersecting constraints are retained;
/// an overridable default alone is never a bound.
mlir::FailureOr<ParameterIntegerBounds> inferParameterIntegerBounds(mlir::Operation *parameter);
mlir::FailureOr<ParameterIntegerBounds> inferParameterIntegerBounds(mlir::Operation *parameter,
                                                                    ParameterIntegerMode mode);

/// Same canonical bound interpreter, charged to shared caller work.
mlir::FailureOr<ParameterIntegerBounds>
inferParameterIntegerBounds(mlir::Value value, unsigned &remainingWork,
                            ParameterIntegerMode mode = ParameterIntegerMode::Storage);
mlir::FailureOr<ParameterIntegerBounds>
inferParameterIntegerBounds(mlir::Operation *parameter, unsigned &remainingWork,
                            ParameterIntegerMode mode = ParameterIntegerMode::Storage);

/// Finite zero-admissibility facts for numeric denominator lint. ZeroPermitted
/// means a checked constant is zero or every invariant explicit constraint on a
/// symbolic declaration admits zero; it is not a claim about runtime reachability.
/// Unknown covers unconstrained symbols and unsupported runtime expressions.
enum class ParameterZeroAdmissibility { ZeroExcluded, ZeroPermitted, Unknown };

/// Read-only, diagnostic-free queries for a direct declaration or scalar value.
/// Reuse checked constant folding, unit normalization, explicit constraints and
/// integer bounds; never replace an overridable parameter by its default. Fixed
/// defaults retain symbolic dependencies. Invalid queried declarations, defaults,
/// constraints, units and cyclic constant dependencies fail instead of proving
/// an empty legal set. This does not replace whole-model semantic verification.
mlir::FailureOr<ParameterZeroAdmissibility>
analyzeParameterZeroAdmissibility(mlir::Operation *parameter);
mlir::FailureOr<ParameterZeroAdmissibility> analyzeParameterZeroAdmissibility(mlir::Value value);

/// Checked fixed default, normalized to the declaration's unit. An empty
/// optional means the declaration is overridable or its default still depends on
/// an overridable parameter. Reuses the same silent, read-only validity checks as
/// zero-admissibility; malformed/cyclic input fails instead of becoming dynamic.
mlir::FailureOr<std::optional<mlir::Attribute>>
getInvariantParameterDefault(mlir::Operation *parameter);

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

/// Mechanical emission of canonical checked structural integer DAGs. These do
/// not prove congruence, topology or target capability. Exact bindings are actual
/// original Values, never textual names or value substitutions. Outputs and IR
/// remain unchanged on failure; work is charged even on a refused query.
enum class IntegerExpressionEmissionStatus { Proved, Unsupported, Invalid, ResourceLimit };
IntegerExpressionEmissionStatus materializeStructuralIntegerExpression(
    const InstantiatedIntegerExpression &expression, mlir::Operation *actualUse,
    llvm::ArrayRef<mlir::Value> exactBindings, llvm::StringRef uniquePathPrefix,
    mlir::Value &result, unsigned &remainingWork);
/// Binding text belongs to the target caller's already checked Value/name map.
/// It is not interpreted or used as proof. Initial DAG profile is literal,
/// exact opaque Value and add/sub/mul/div; parent parameters remain unsupported.
IntegerExpressionEmissionStatus renderStructuralIntegerExpression(
    const InstantiatedIntegerExpression &expression, mlir::Operation *actualUse,
    llvm::ArrayRef<std::pair<mlir::Value, std::string>> exactBindings,
    std::string &result, unsigned &remainingWork, uint64_t maximumBytes = 65536);

namespace detail {
class CollectionIntegerEnvironmentAdmission;
class LoopIntegerEnvironmentAdmission;
class StructuralIfIntegerEnvironmentAdmission;
class BehavioralIntegerEnvironmentAdmission;
class FlatFSMIntegerEnvironmentAdmission;
class EnumSwitchIntegerEnvironmentAdmission;
class DigitalGenerateIntegerEnvironmentAdmission;
} // namespace detail

/// One checked substitution for an actual static collection block argument.
/// A non-null captureSource denotes an actual collection operand; the factory
/// independently checks its static value, dominance and exact substitution.
struct ParameterIntegerBinding {
  mlir::BlockArgument argument;
  InstantiatedIntegerExpression expression;
  mlir::Value captureSource;
};

/// Private collection-derived universal width domain. The real admission owner
/// proves reachability; no serialized bound or public factory can create it.
struct ParameterIntegerFormalDomain {
  mlir::BlockArgument argument;
  ParameterIntegerBounds bounds;
};

/// Immutable, query-local substitutions for one actual lexical use. Construction
/// belongs to the collection or bounded-loop raw-header/proof owner, after checking
/// the raw header, argument roles, capture provenance and substitution meaning.
/// Full body-family validation is still required before publishing reduction
/// facts; this object alone is not a verified reduction certificate. No serialized
/// bounds, labels, defaults or caller-asserted equality pairs grant static status.
///
/// This is a proof query, never a rewrite of the retained raw template DAG.
/// First-combine substitutions may normalize max(L, R) to W locally; recurrence
/// inference must still inspect the actual, distinct L/R formal operands.
/// Parameters retain their exact identities; equal substitutions establish only
/// local equality. Each query has fresh caches. The optional finalized instance
/// context is snapshotted, including failed/unsupported parameter resolutions.
/// The IR must remain alive and structurally unchanged while the context is used.
/// Arithmetic mode is a separate query argument and never grants scope access.
class ParameterIntegerEnvironment {
public:
  mlir::Operation *module() const;
  mlir::Operation *instance() const;
  mlir::Block *scope() const;
  mlir::Operation *use() const;
  const InstantiatedIntegerExpression *binding(mlir::Value value) const;
  /// An immutable admitted domain anchor, including checked enclosing captures.
  const InstantiatedIntegerExpression *domain(mlir::Value value) const;
  const InstantiatedIntegerExpression *parameter(mlir::Operation *value,
                                                 ParameterIntegerMode mode) const;
  /// Narrow the same admitted context to another operation in the exact block.
  /// Crossing an isolated nested scope requires a separately admitted capture.
  mlir::FailureOr<ParameterIntegerEnvironment> atUse(mlir::Operation *use) const;
  /// Retarget only a retained immediate enclosing context for an explicit capture.
  mlir::FailureOr<ParameterIntegerEnvironment> enclosingAtUse(mlir::Operation *use) const;

private:
  struct Storage;
  explicit ParameterIntegerEnvironment(std::shared_ptr<const Storage> storage,
                                       mlir::Operation *use);
  static mlir::FailureOr<ParameterIntegerEnvironment>
  create(mlir::Operation *module, mlir::Block *scope, mlir::Operation *use,
         mlir::Operation *instance, llvm::ArrayRef<ParameterIntegerBinding> bindings,
         llvm::ArrayRef<ParameterIntegerFormalDomain> domains,
         const ParameterIntegerEnvironment *enclosing = nullptr);
  std::shared_ptr<const Storage> storage;
  mlir::Operation *lexicalUse;
  friend class detail::CollectionIntegerEnvironmentAdmission;
  friend class detail::LoopIntegerEnvironmentAdmission;
  friend class detail::StructuralIfIntegerEnvironmentAdmission;
  friend class detail::BehavioralIntegerEnvironmentAdmission;
  friend class detail::FlatFSMIntegerEnvironmentAdmission;
  friend class detail::EnumSwitchIntegerEnvironmentAdmission;
  friend class detail::DigitalGenerateIntegerEnvironmentAdmission;
};

/// Environment-bearing queries require explicit collection or bounded-loop admission.
/// Loop contexts bind actual static captures only, never induction or width domains.
/// Existing overloads never consult a global environment or admit block arguments.
mlir::FailureOr<ParameterIntegerBounds>
inferParameterIntegerBounds(mlir::Value value, const ParameterIntegerEnvironment &environment,
                            ParameterIntegerMode mode = ParameterIntegerMode::Storage);
mlir::FailureOr<ParameterIntegerBounds>
inferParameterIntegerBounds(mlir::Operation *parameter,
                            const ParameterIntegerEnvironment &environment,
                            ParameterIntegerMode mode = ParameterIntegerMode::Storage);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveOwnedIntegerExpression(mlir::Operation *module, mlir::Value expression,
                              const ParameterIntegerEnvironment &environment,
                              ParameterIntegerMode mode = ParameterIntegerMode::Storage);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveOwnedIntegerParameter(mlir::Operation *module, mlir::Operation *parameter,
                             const ParameterIntegerEnvironment &environment,
                             ParameterIntegerMode mode = ParameterIntegerMode::Storage);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveInstanceIntegerExpression(mlir::Operation *instance, mlir::Value expression,
                                 const ParameterIntegerEnvironment &environment,
                                 ParameterIntegerMode mode = ParameterIntegerMode::Storage);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveInstanceIntegerParameter(mlir::Operation *instance, mlir::Operation *parameter,
                                const ParameterIntegerEnvironment &environment,
                                ParameterIntegerMode mode = ParameterIntegerMode::Storage);

/// Resolve a finalized immediate instance environment, substituting actual SSA
/// overrides, literal bindings and recursively dependent defaults. Every used
/// formal is checked against its effective constraints over the whole actual
/// envelope. Unknown correlation, cycles and overflow fail closed.
mlir::FailureOr<InstantiatedIntegerExpression>
resolveInstanceIntegerParameter(mlir::Operation *instance, mlir::Operation *childParameter);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveInstanceIntegerParameter(mlir::Operation *instance, mlir::Operation *childParameter,
                                ParameterIntegerMode mode);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveInstanceIntegerExpression(mlir::Operation *instance, mlir::Value childExpression);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveInstanceIntegerExpression(mlir::Operation *instance, mlir::Value childExpression,
                                 ParameterIntegerMode mode);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveOwnedIntegerParameter(mlir::Operation *module, mlir::Operation *parameter);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveOwnedIntegerParameter(mlir::Operation *module, mlir::Operation *parameter,
                             ParameterIntegerMode mode);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveOwnedIntegerExpression(mlir::Operation *module, mlir::Value expression);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveOwnedIntegerExpression(mlir::Operation *module, mlir::Value expression,
                              ParameterIntegerMode mode);
/// Same owned DAG interpreter with shared finite work, no added applicability.
mlir::FailureOr<InstantiatedIntegerExpression>
resolveOwnedIntegerParameter(mlir::Operation *module, mlir::Operation *parameter,
                             unsigned &remainingWork,
                             ParameterIntegerMode mode = ParameterIntegerMode::Storage);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveOwnedIntegerExpression(mlir::Operation *module, mlir::Value expression,
                              unsigned &remainingWork,
                              ParameterIntegerMode mode = ParameterIntegerMode::Storage);
InstantiatedIntegerExpression makeLiteralIntegerExpression(int64_t value);
/// Query-only identity atom. The caller must independently prove the value's
/// ownership and bounds; ordinary parameter interpretation never creates these.
InstantiatedIntegerExpression makeOpaqueIntegerExpression(mlir::Value identity,
                                                          ParameterIntegerBounds bounds);
/// Checked composition of already proved immutable expressions. Reuses the
/// parameter arithmetic policy and retains opaque identities through arithmetic.
/// Supports add/sub/mul/div/mod/neg and static Integer max/ceil_log2/choose_zero.
/// ceil_log2 requires a strictly positive envelope. choose_zero checks all three
/// operands eagerly and selects bounds only when zero is proved or excluded;
/// otherwise it unions the two branch bounds. Exact identities survive equal
/// bounds/defaults. max(W, W) and choose_zero(N, W, W) normalize only for exact
/// operand identity; choose_zero also folds a literal test after validation.
mlir::FailureOr<InstantiatedIntegerExpression>
composeIntegerExpression(llvm::StringRef opcode,
                         llvm::ArrayRef<InstantiatedIntegerExpression> operands);
/// Query-only substitution of already checked exact opaque identities. Replacements
/// must narrow the original atom envelope; this grants no lexical/type capability.
mlir::FailureOr<InstantiatedIntegerExpression> substituteIntegerExpression(
    const InstantiatedIntegerExpression &expression,
    llvm::ArrayRef<std::pair<mlir::Value, InstantiatedIntegerExpression>> substitutions);
/// Original generated occurrence query: proves every actual lane against the
/// exact child formal contract without admitting induction as a generic type binding.
mlir::FailureOr<InstantiatedIntegerExpression>
resolveGeneratedInstanceIntegerParameter(mlir::Operation *instance, mlir::Operation *parameter,
                                         ParameterIntegerMode mode = ParameterIntegerMode::Storage);
/// Original child expression under the same all-lane generated contract.
mlir::FailureOr<InstantiatedIntegerExpression> resolveGeneratedInstanceIntegerExpression(
    mlir::Operation *instance, mlir::Value expression,
    ParameterIntegerMode mode = ParameterIntegerMode::Storage);
/// Selected structural consumer only. Caller independently verifies the exact
/// instance/export selection and passes its checked induction-coordinate prefix.
mlir::FailureOr<InstantiatedIntegerExpression> resolveSelectedInstanceIntegerParameter(
    mlir::Operation *instance, mlir::Operation *parameter,
    llvm::ArrayRef<std::pair<mlir::Value, InstantiatedIntegerExpression>> substitutions,
    ParameterIntegerMode mode = ParameterIntegerMode::Storage);
mlir::FailureOr<InstantiatedIntegerExpression> resolveSelectedInstanceIntegerExpression(
    mlir::Operation *instance, mlir::Value expression,
    llvm::ArrayRef<std::pair<mlir::Value, InstantiatedIntegerExpression>> substitutions,
    ParameterIntegerMode mode = ParameterIntegerMode::Storage);
/// Conservative all-legal ordering, using exact identity and checked bounds.
bool proveIntegerExpressionLess(const InstantiatedIntegerExpression &left,
                                const InstantiatedIntegerExpression &right, bool inclusive = false);
bool equivalentInstantiatedIntegerExpressions(const InstantiatedIntegerExpression &left,
                                              const InstantiatedIntegerExpression &right);

/// Static-predicate use admission follows actual explicit captures and Generate
/// ownership. Missing expression means a checked empty generated occurrence;
/// it never invents an attained index or a reduction width-formal fact.
struct StructuralIntegerFacts {
  std::optional<InstantiatedIntegerExpression> expression;
};
/// Generated declaration consumers may retain checked empty attainment. Numeric
/// and selected-use APIs above never synthesize an expression for absence.
mlir::FailureOr<StructuralIntegerFacts> resolveGeneratedInstanceIntegerParameterFacts(
    mlir::Operation *instance, mlir::Operation *parameter,
    ParameterIntegerMode mode = ParameterIntegerMode::Storage);
mlir::FailureOr<StructuralIntegerFacts> resolveGeneratedInstanceIntegerExpressionFacts(
    mlir::Operation *instance, mlir::Value expression,
    ParameterIntegerMode mode = ParameterIntegerMode::Storage);
mlir::FailureOr<StructuralIntegerFacts> resolveStructuralIntegerExpression(mlir::Value value,
                                                                           mlir::Operation *use);
/// Explicit arithmetic policy only; it does not grant additional lexical rights.
mlir::FailureOr<StructuralIntegerFacts>
resolveStructuralIntegerExpression(mlir::Value value, mlir::Operation *use,
                                   ParameterIntegerMode mode);
/// Same use-scoped interpreter, charged to the caller's shared finite work.
mlir::FailureOr<StructuralIntegerFacts> resolveStructuralIntegerExpression(
    mlir::Value value, mlir::Operation *use, unsigned &remainingWork,
    ParameterIntegerMode mode = ParameterIntegerMode::MathematicalSigned64);
/// Borrowed query-local view of one original actual hierarchy edge. The
/// coordinate storage must outlive the query; no environment survives it.
struct SelectedIntegerInstanceStep {
  mlir::Operation *instance = nullptr;
  llvm::ArrayRef<std::pair<mlir::Value, InstantiatedIntegerExpression>> coordinates;
};
/// Resolve an exact structural use through the full original selected-root path.
/// Checks original per-edge contracts before actual contextual substitution.
mlir::FailureOr<StructuralIntegerFacts> resolveSelectedInstancePathStructuralIntegerExpression(
    mlir::Operation *rootModule, llvm::ArrayRef<SelectedIntegerInstanceStep> path,
    mlir::Value value, mlir::Operation *use, unsigned &remainingWork,
    ParameterIntegerMode mode = ParameterIntegerMode::MathematicalSigned64);
/// Exact structural use in the selected generated instance's child definition.
/// Follows the existing structural capture/local DAG interpreter, substituting
/// only canonical instance/formal leaves through the independently checked
/// parent-coordinate prefix. Original all-lane validity precedes specialization;
/// absent child attainment remains absent. No new lexical or selection access.
mlir::FailureOr<StructuralIntegerFacts> resolveSelectedInstanceStructuralIntegerExpression(
    mlir::Operation *instance, mlir::Value value, mlir::Operation *use,
    llvm::ArrayRef<std::pair<mlir::Value, InstantiatedIntegerExpression>> parentCoordinates,
    unsigned &remainingWork,
    ParameterIntegerMode mode = ParameterIntegerMode::MathematicalSigned64);
mlir::FailureOr<StructuralIntegerFacts> resolveBehavioralIntegerExpression(mlir::Value value,
                                                                           mlir::Operation *use);
/// Non-diagnostic type-binding capture classification. A runtime Integer or
/// generated induction is not a type binding. Actual static uses still call the
/// resolving API above and retain their normal proof and diagnostics; no such
/// value is represented as unreachable or zero.
mlir::FailureOr<StructuralIntegerFacts> probeBehavioralIntegerCapture(mlir::Value value,
                                                                      mlir::Operation *use);
mlir::LogicalResult verifyStructuralIntegerOperation(mlir::Operation *operation);
/// Producer-only admission for finite ordinary Integer constructor actuals.
/// Every actual arithmetic/capture use must end in a checked ordinary generated
/// child override. This does not confer structural effects or runtime eligibility.
mlir::LogicalResult verifyConstructorBindingIntegerOperation(mlir::Operation *operation);
/// Same original producer/use inventory through an exact actual capture. Success
/// grants capture admission only, never a type-environment binding.
mlir::LogicalResult verifyConstructorBindingIntegerCapture(mlir::Value value, mlir::Operation *use);

/// Resolve only a direct instance in the use's immediate Module/Generate scope.
/// Module definitions and parameter declarations have separate owner namespaces.
mlir::LogicalResult verifyGeneratedStructuralOwnership(mlir::Operation *operation);
mlir::Operation *resolveStructuralInstance(mlir::Operation *use, mlir::FlatSymbolRefAttr reference);
mlir::Operation *resolveInstanceModuleDefinition(mlir::Operation *instance);

/// Classify an exact parent ordinary dimensionless Real reference or bounded
/// add/sub/mul/neg DAG into a child formal in a one-level Module-owned Generate.
/// Rechecks the override's linkage to the supplied instance and formal identities.
/// Every producer is in the parent Module before the family, and each operand
/// precedes its consumer. Literal leaves are finite f64; parameter leaves retain
/// their exact declarations, never substituted defaults. Sharing/cycles, 512-level
/// depth, 65536 traversal work and the existing expanded-text budget are checked.
/// This non-evaluating admission query is not a parameter-contract or all-legal-
/// Real proof: fixed/default/constraint checks remain in verifyParameterModel,
/// and selected topology restrictions remain independently owned by consumers.
bool isGeneratedParentRealParameterOverride(mlir::Operation *operation, mlir::Operation *instance,
                                            mlir::Operation *formal);

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
/// Same canonical proof, charged to the caller's shared finite query work.
mlir::FailureOr<GeneratedDomain> resolveGeneratedDomain(mlir::Operation *operation,
                                                        unsigned &remainingWork);
/// Shared positive half-open domain arithmetic for a checked SSA iteration
/// header. Exactly the first three operands are bounds; remaining operands are
/// captures. This query independently proves finite owned bounds and direction.
mlir::FailureOr<GeneratedDomain> resolveBoundedIterationDomain(mlir::Operation *operation);

/// Activity facts from the same checked bound/direction/count contract. Legacy
/// no-induction negative-step loops are retained; no lanes are enumerated.
enum class GeneratedScopeActivity { AlwaysEmpty, AlwaysNonempty, MaybeEmpty };
mlir::FailureOr<GeneratedScopeActivity> analyzeGeneratedScopeActivity(mlir::Operation *operation);

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
