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
