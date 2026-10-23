#ifndef NODAL_DIALECT_NODAL_STATICPREDICATEMODEL_H
#define NODAL_DIALECT_NODAL_STATICPREDICATEMODEL_H
#include "nodal/Dialect/Nodal/ParameterModel.h"
namespace nodal {
enum class StaticPredicateActivity { AlwaysTrue, AlwaysFalse, Either, Unreachable };
struct StaticPredicateFacts {
  mlir::Value predicate;
  StaticPredicateActivity activity;
};
mlir::FailureOr<StaticPredicateFacts>
analyzeStaticPredicate(mlir::Value predicate, mlir::Operation *use,
                       const ParameterIntegerEnvironment *environment = nullptr);
/// Query-local specialization of independently checked generated coordinates.
/// Validates the original lexical/all-lane predicate before narrowing exact
/// ancestor induction identities. This grants no selection/existence capability.
/// All visits consume the caller's shared work budget; Either stays conservative.
mlir::FailureOr<StaticPredicateFacts> analyzeStaticPredicate(
    mlir::Value predicate, mlir::Operation *use,
    llvm::ArrayRef<std::pair<mlir::Value, InstantiatedIntegerExpression>> substitutions,
    unsigned &remainingWork);
/// Specialize original child structural predicate Integer operands through
/// the exact generated instance binding and independently checked parent prefix.
/// This retains original all-lane validation and exact regional captures.
/// Descendant binding chains remain separate.
mlir::FailureOr<StaticPredicateFacts> analyzeSelectedInstanceStaticPredicate(
    mlir::Value predicate, mlir::Operation *use, mlir::Operation *instance,
    llvm::ArrayRef<std::pair<mlir::Value, InstantiatedIntegerExpression>> coordinates,
    unsigned &remainingWork);
/// Compose independently checked child-local induction coordinates with the
/// exact actual parent-instance bindings. Neither prefix grants existence or
/// lexical access, and unresolved symbolic coordinates remain conservative.
mlir::FailureOr<StaticPredicateFacts> analyzeSelectedInstanceStaticPredicate(
    mlir::Value predicate, mlir::Operation *use, mlir::Operation *instance,
    llvm::ArrayRef<std::pair<mlir::Value, InstantiatedIntegerExpression>> parentCoordinates,
    llvm::ArrayRef<std::pair<mlir::Value, InstantiatedIntegerExpression>> childCoordinates,
    unsigned &remainingWork);
/// Selected predicate in an exact complete original root-to-leaf actual path.
/// Every edge retains its own parent-coordinate prefix; the final local prefix
/// is independently checked at use. No suffix may silently rebase its root.
mlir::FailureOr<StaticPredicateFacts> analyzeSelectedInstancePathStaticPredicate(
    mlir::Value predicate, mlir::Operation *use, mlir::Operation *rootModule,
    llvm::ArrayRef<SelectedIntegerInstanceStep> path,
    llvm::ArrayRef<std::pair<mlir::Value, InstantiatedIntegerExpression>> childCoordinates,
    unsigned &remainingWork);
mlir::FailureOr<StaticPredicateFacts> analyzeBehavioralStaticPredicate(mlir::Value predicate,
                                                                       mlir::Operation *use);
/// Conservative exclusivity of actual owned Bits<1> guards. Supports Boolean
/// composition, identical runtime SSA atoms and checked static comparisons of
/// equivalent Integer DAGs. A successful false means possible/unknown overlap;
/// failure means invalid input or an exhausted finite proof/depth budget. Both
/// operands are validated even when one is false. Work is consumed from the
/// caller's shared budget; no parameter default or general solver is used.
mlir::FailureOr<bool> proveBehavioralGuardsDisjoint(mlir::Value left, mlir::Value right,
                                                    mlir::Operation *use, unsigned &remainingWork);
/// Prove an exact Integer ordering from global facts or actual enclosing static
/// arm predicates. Bounded and conservative; does not refine retained type bounds.
mlir::FailureOr<bool> proveStructuralIntegerLessAtUse(const InstantiatedIntegerExpression &left,
                                                      const InstantiatedIntegerExpression &right,
                                                      mlir::Operation *use, bool inclusive = false);
mlir::LogicalResult verifyStaticPredicateOperation(mlir::Operation *operation);
} // namespace nodal
#endif
