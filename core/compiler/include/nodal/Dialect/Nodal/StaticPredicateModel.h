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
mlir::LogicalResult verifyStaticPredicateOperation(mlir::Operation *operation);
} // namespace nodal
#endif
