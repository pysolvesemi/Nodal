#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

#include <limits>

namespace {

int fail(llvm::StringRef message) {
  llvm::errs() << "ParameterModelTest: " << message << '\n';
  return 1;
}

constexpr llvm::StringLiteral kValid = R"mlir(
module {
  "nodal.unit"() <{dimension = "resistance", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Ohm", symbol = "Ohm"}> : () -> ()
  "nodal.unit"() <{dimension = "resistance", metadata = {}, native_suffix = "k", scale = 1.0e3 : f64, sym_name = "kOhm", symbol = "kOhm"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "RBASE", type = f64, unit = @kOhm, variability = "symbolic"}> : () -> ()
    %rbase_value = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1.0 : f64}> : () -> f64
    "nodal.parameter_value"(%rbase_value) <{metadata = {}, parameter = @RBASE}> : (f64) -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 2.0e3 : f64, metadata = {}, parameter_kind = "real", sym_name = "RTOTAL", type = f64, unit = @Ohm, variability = "symbolic"}> : () -> ()
    %rbase_ref = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @RBASE}> : () -> f64
    %one_k = "nodal.const_literal"() <{metadata = {}, spelling = "1k", unit = @kOhm, value = 1.0 : f64}> : () -> f64
    %rtotal_value = "nodal.const_expr"(%rbase_ref, %one_k) <{metadata = {}, operator_name = "add"}> : (f64, f64) -> f64
    "nodal.parameter_value"(%rtotal_value) <{metadata = {}, parameter = @RTOTAL}> : (f64) -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 340282366920938463463374607431768211455 : i129, metadata = {}, parameter_kind = "integer", sym_name = "WIDE", type = !nodal.uint<128>, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0e3 : f64, metadata = {}, parameter_kind = "real", sym_name = "R", type = f64, unit = @Ohm, variability = "symbolic"}> : () -> ()
    %r = "nodal.const_literal"() <{metadata = {}, spelling = "1k", unit = @kOhm, value = 1.0 : f64}> : () -> f64
    "nodal.parameter_value"(%r) <{metadata = {}, parameter = @R}> : (f64) -> ()
    %low = "nodal.const_literal"() <{metadata = {}, spelling = "1", unit = @Ohm, value = 1.0 : f64}> : () -> f64
    %high = "nodal.const_literal"() <{metadata = {}, spelling = "10k", unit = @kOhm, value = 1.0e1 : f64}> : () -> f64
    "nodal.parameter_constraint"(%low, %high) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @R, upper_inclusive = true}> : (f64, f64) -> ()
    "nodal.parameter"() <{classification = "structural", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "COUNT", type = i64, variability = "symbolic"}> : () -> ()
    %four = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_value"(%four) <{metadata = {}, parameter = @COUNT}> : (i64) -> ()
    %one = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %eight = "nodal.const_literal"() <{metadata = {}, spelling = "8", value = 8 : i64}> : () -> i64
    "nodal.parameter_constraint"(%one, %eight) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @COUNT, upper_inclusive = true}> : (i64, i64) -> ()
    "nodal.parameter_envelope"() <{effects = ["topology"], metadata = {}, parameter = @COUNT, policy = "static_generate"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "ENABLED", type = i1, variability = "symbolic"}> : () -> ()
    %one_real = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1.0 : f64}> : () -> f64
    %zero_real = "nodal.const_literal"() <{metadata = {}, spelling = "0", value = 0.0 : f64}> : () -> f64
    %ordered = "nodal.const_expr"(%one_real, %zero_real) <{metadata = {}, operator_name = "gt"}> : (f64, f64) -> i1
    %truth = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = true}> : () -> i1
    %truth_bits = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = true}> : () -> !nodal.bits<1>
    %enabled_value = "nodal.const_expr"(%ordered, %truth) <{metadata = {}, operator_name = "and"}> : (i1, i1) -> i1
    "nodal.parameter_value"(%enabled_value) <{metadata = {}, parameter = @ENABLED}> : (i1) -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "NEAR_ORDERED", type = i1, variability = "symbolic"}> : () -> ()
    %near_left = "nodal.const_literal"() <{metadata = {}, spelling = "1.0000000000005", value = 1.0000000000005 : f64}> : () -> f64
    %near_right = "nodal.const_literal"() <{metadata = {}, spelling = "1.0", value = 1.0 : f64}> : () -> f64
    %near_ordered = "nodal.const_expr"(%near_left, %near_right) <{metadata = {}, operator_name = "gt"}> : (f64, f64) -> i1
    %false = "nodal.const_literal"() <{metadata = {}, spelling = "0", value = false}> : () -> i1
    "nodal.parameter_value"(%near_ordered) <{metadata = {}, parameter = @NEAR_ORDERED}> : (i1) -> ()
    "nodal.parameter_constraint"(%false) <{constraint_kind = "exclude", lower_inclusive = false, metadata = {}, parameter = @NEAR_ORDERED, upper_inclusive = false}> : (i1) -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kBadConstraint = R"mlir(
module {
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 12 : i64, metadata = {}, parameter_kind = "integer", sym_name = "COUNT", type = i64, variability = "symbolic"}> : () -> ()
    %value = "nodal.const_literal"() <{metadata = {}, spelling = "12", value = 12 : i64}> : () -> i64
    "nodal.parameter_value"(%value) <{metadata = {}, parameter = @COUNT}> : (i64) -> ()
    %low = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %high = "nodal.const_literal"() <{metadata = {}, spelling = "8", value = 8 : i64}> : () -> i64
    "nodal.parameter_constraint"(%low, %high) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @COUNT, upper_inclusive = true}> : (i64, i64) -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kBadEnvelope = R"mlir(
module {
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "structural", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "COUNT", type = i64, variability = "symbolic"}> : () -> ()
    %value = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_value"(%value) <{metadata = {}, parameter = @COUNT}> : (i64) -> ()
    %low = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %high = "nodal.const_literal"() <{metadata = {}, spelling = "8", value = 8 : i64}> : () -> i64
    "nodal.parameter_constraint"(%low, %high) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @COUNT, upper_inclusive = true}> : (i64, i64) -> ()
    "nodal.parameter_envelope"() <{effects = ["topology"], metadata = {}, parameter = @COUNT, policy = "fixed_topology"}> : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kDynamic = R"mlir(
module {
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1 : i64, metadata = {}, parameter_kind = "integer", sym_name = "COUNT", type = i64, variability = "symbolic"}> : () -> ()
    %one = "nodal.constant"() <{metadata = {}, value = 1 : i64}> : () -> i64
    %runtime = "nodal.dynamic_value"(%one) <{metadata = {}, origin = "Fixture.runtime"}> : (i64) -> i64
    "nodal.parameter_value"(%runtime) <{metadata = {}, parameter = @COUNT}> : (i64) -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kCycle = R"mlir(
module {
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1 : i64, metadata = {}, parameter_kind = "integer", sym_name = "A", type = i64, variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 1 : i64, metadata = {}, parameter_kind = "integer", sym_name = "B", type = i64, variability = "symbolic"}> : () -> ()
    %b = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @B}> : () -> i64
    "nodal.parameter_value"(%b) <{metadata = {}, parameter = @A}> : (i64) -> ()
    %a = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @A}> : () -> i64
    "nodal.parameter_value"(%a) <{metadata = {}, parameter = @B}> : (i64) -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kFixedBinding = R"mlir(
module {
  "nodal.module"() <{metadata = {}, sym_name = "Child"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "CONST", type = i64, variability = "fixed"}> : () -> ()
    %four = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_value"(%four) <{metadata = {}, parameter = @CONST}> : (i64) -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
  ^bb0:
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Child, parameter_bindings = {CONST = 5 : i64}, sym_name = "child"}> : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kFixedRootBinding = R"mlir(
module attributes {nodal.root.module = @Fixture, nodal.root.parameter_bindings = {CONST = 4 : i64}} {
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "CONST", type = i64, variability = "fixed"}> : () -> ()
    %four = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_value"(%four) <{metadata = {}, parameter = @CONST}> : (i64) -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kOutOfRangeRootBinding = R"mlir(
module attributes {nodal.root.module = @Fixture, nodal.root.parameter_bindings = {COUNT = 12 : i64}} {
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "COUNT", type = i64, variability = "symbolic"}> : () -> ()
    %four = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_value"(%four) <{metadata = {}, parameter = @COUNT}> : (i64) -> ()
    %one = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %eight = "nodal.const_literal"() <{metadata = {}, spelling = "8", value = 8 : i64}> : () -> i64
    "nodal.parameter_constraint"(%one, %eight) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @COUNT, upper_inclusive = true}> : (i64, i64) -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kExcludedRootBinding = R"mlir(
module attributes {nodal.root.module = @Fixture, nodal.root.parameter_bindings = {COUNT = 5 : i64}} {
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "COUNT", type = i64, variability = "symbolic"}> : () -> ()
    %four = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_value"(%four) <{metadata = {}, parameter = @COUNT}> : (i64) -> ()
    %five = "nodal.const_literal"() <{metadata = {}, spelling = "5", value = 5 : i64}> : () -> i64
    "nodal.parameter_constraint"(%five) <{constraint_kind = "exclude", lower_inclusive = false, metadata = {}, parameter = @COUNT, upper_inclusive = false}> : (i64) -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kFixedExplicitOverride = R"mlir(
module {
  "nodal.module"() <{metadata = {}, sym_name = "Child"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "CONST", type = i64, variability = "fixed"}> : () -> ()
    %four = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    "nodal.parameter_value"(%four) <{metadata = {}, parameter = @CONST}> : (i64) -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
  ^bb0:
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Child, parameter_bindings = {}, sym_name = "child"}> : () -> ()
    %five = "nodal.const_literal"() <{metadata = {}, spelling = "5", value = 5 : i64}> : () -> i64
    "nodal.parameter_override"(%five) <{instance = @child, metadata = {}, parameter = @CONST}> : (i64) -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kInstanceShapeParameters = R"mlir(
module {
  "nodal.module"() <{metadata = {}, sym_name = "Child"}> ({
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "A", type = i64, variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 5 : i64, metadata = {}, parameter_kind = "integer", sym_name = "B", type = i64, variability = "symbolic"}> : () -> ()
    %one = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %two = "nodal.const_literal"() <{metadata = {}, spelling = "2", value = 2 : i64}> : () -> i64
    %twelve = "nodal.const_literal"() <{metadata = {}, spelling = "12", value = 12 : i64}> : () -> i64
    %a = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @A}> : () -> i64
    %derived = "nodal.const_expr"(%a, %one) <{metadata = {}, operator_name = "add"}> : (i64, i64) -> i64
    "nodal.parameter_value"(%derived) <{metadata = {}, parameter = @B}> : (i64) -> ()
    "nodal.parameter_constraint"(%one, %twelve) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}, parameter = @A}> : (i64, i64) -> ()
    "nodal.parameter_constraint"(%two, %derived) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}, parameter = @B}> : (i64, i64) -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Parent"}> ({
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "N", type = i64, variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "M", type = i64, variability = "symbolic"}> : () -> ()
    %low = "nodal.const_literal"() <{metadata = {}, spelling = "2", value = 2 : i64}> : () -> i64
    %high = "nodal.const_literal"() <{metadata = {}, spelling = "6", value = 6 : i64}> : () -> i64
    %one = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    "nodal.parameter_constraint"(%low, %high) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}, parameter = @N}> : (i64, i64) -> ()
    "nodal.parameter_constraint"(%low, %high) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}, parameter = @M}> : (i64, i64) -> ()
    %n = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @N}> : () -> i64
    %m = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @M}> : () -> i64
    %nplus = "nodal.const_expr"(%n, %one) <{metadata = {}, operator_name = "add"}> : (i64, i64) -> i64
    %mplus = "nodal.const_expr"(%m, %one) <{metadata = {}, operator_name = "add"}> : (i64, i64) -> i64
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Child, parameter_bindings = {}, sym_name = "u"}> : () -> ()
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Child, parameter_bindings = {A = 7 : i64, B = 8 : i64}, sym_name = "v"}> : () -> ()
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Child, parameter_bindings = {}, sym_name = "d"}> : () -> ()
    "nodal.parameter_override"(%n) <{instance = @u, parameter = @A, metadata = {}}> : (i64) -> ()
  }) : () -> ()
}
)mlir";

mlir::Operation *namedOperation(mlir::Operation *scope, llvm::StringRef name) {
  mlir::Operation *found = nullptr;
  scope->walk([&](mlir::Operation *operation) {
    auto symbol = operation->getAttrOfType<mlir::StringAttr>("sym_name");
    if (symbol && symbol.getValue() == name)
      found = operation;
  });
  return found;
}

int checkInstanceParameterQueries(mlir::MLIRContext &context) {
  auto module = mlir::parseSourceString<mlir::ModuleOp>(kInstanceShapeParameters, &context);
  if (!module || mlir::failed(mlir::verify(*module)) ||
      mlir::failed(nodal::verifyParameterModel(*module)))
    return fail("finalized dependent-default instance fixture did not verify");
  auto *parent = namedOperation(module->getOperation(), "Parent");
  auto *child = namedOperation(module->getOperation(), "Child");
  auto *a = namedOperation(child, "A");
  auto *b = namedOperation(child, "B");
  auto *u = namedOperation(parent, "u");
  auto *v = namedOperation(parent, "v");
  auto *d = namedOperation(parent, "d");
  llvm::SmallVector<mlir::Value, 2> expressions;
  parent->walk(
      [&](nodal::ConstExprOp operation) { expressions.push_back(operation->getResult(0)); });
  auto actual = nodal::resolveInstanceIntegerParameter(u, b);
  auto expected = nodal::resolveOwnedIntegerExpression(parent, expressions[0]);
  auto independent = nodal::resolveOwnedIntegerExpression(parent, expressions[1]);
  if (mlir::failed(actual) || mlir::failed(expected) || mlir::failed(independent) ||
      actual->bounds.lower != 3 || actual->bounds.upper != 7 ||
      !nodal::equivalentInstantiatedIntegerExpressions(*actual, *expected) ||
      nodal::equivalentInstantiatedIntegerExpressions(*actual, *independent) ||
      nodal::equivalentInstantiatedIntegerExpressions(*actual,
                                                      nodal::makeLiteralIntegerExpression(5)))
    return fail("dependent instance default lost its actual parent identity or envelope");
  for (auto entry : {std::make_pair(v, int64_t{8}), std::make_pair(d, int64_t{5})}) {
    auto value = nodal::resolveInstanceIntegerParameter(entry.first, b);
    if (mlir::failed(value) || !nodal::equivalentInstantiatedIntegerExpressions(
                                   *value, nodal::makeLiteralIntegerExpression(entry.second)))
      return fail("exact instance default/literal override was not folded");
  }
  if (mlir::succeeded(nodal::resolveOwnedIntegerParameter(parent, a)) ||
      mlir::succeeded(nodal::resolveInstanceIntegerParameter(u, namedOperation(parent, "N"))))
    return fail("foreign formal/owned query was accepted");

  mlir::Operation *defaultDefinition = nullptr;
  child->walk([&](nodal::ParameterValueOp operation) { defaultDefinition = operation; });
  const auto originalDefault = defaultDefinition->getOperand(0);
  defaultDefinition->setOperand(0, expressions[0]);
  if (mlir::succeeded(nodal::resolveInstanceIntegerParameter(u, b)))
    return fail("foreign module SSA escaped through a dependent default query");
  defaultDefinition->setOperand(0, originalDefault);

  // Check arithmetic over the complete actual envelope, independently of the
  // source's default. Restore the shared literal after each causal mutation.
  auto *sum = expressions[0].getDefiningOp();
  auto *one = sum->getOperand(1).getDefiningOp();
  const auto originalValue = one->getAttr("value");
  const auto originalOperator = sum->getAttr("operator_name");
  one->setAttr("value", mlir::IntegerAttr::get(expressions[0].getType(),
                                               std::numeric_limits<int64_t>::max()));
  if (mlir::succeeded(nodal::resolveOwnedIntegerExpression(parent, expressions[0])))
    return fail("symbolic addition overflow was accepted");
  sum->setAttr("operator_name", mlir::StringAttr::get(&context, "mul"));
  if (mlir::succeeded(nodal::resolveOwnedIntegerExpression(parent, expressions[0])))
    return fail("symbolic multiplication overflow was accepted");
  one->setAttr("value", mlir::IntegerAttr::get(expressions[0].getType(), 0));
  sum->setAttr("operator_name", mlir::StringAttr::get(&context, "div"));
  if (mlir::succeeded(nodal::resolveOwnedIntegerExpression(parent, expressions[0])))
    return fail("zero divisor was accepted");
  one->setAttr("value", originalValue);
  sum->setAttr("operator_name", originalOperator);

  auto rejects = [&](llvm::StringRef before, llvm::StringRef after, llvm::StringRef instanceName,
                     llvm::StringRef formalName) {
    std::string text = kInstanceShapeParameters.str();
    auto position = text.find(before.str());
    if (position == std::string::npos)
      return false;
    text.replace(position, before.size(), after.str());
    auto candidate = mlir::parseSourceString<mlir::ModuleOp>(text, &context);
    if (!candidate)
      return false;
    return mlir::failed(nodal::resolveInstanceIntegerParameter(
        namedOperation(candidate->getOperation(), instanceName),
        namedOperation(namedOperation(candidate->getOperation(), "Child"), formalName)));
  };
  if (!rejects("spelling = \"6\", value = 6", "spelling = \"14\", value = 14", "u", "B"))
    return fail("whole actual envelope outside child range was accepted at its default");
  if (!rejects("parameter_bindings = {A = 7 : i64, B = 8 : i64}",
               "parameter_bindings = {A = 7 : i64, B = 9 : i64}", "v", "B"))
    return fail("instantiated dependent upper constraint was ignored");
  if (!rejects("parameter = @A}> : () -> i64", "parameter = @B}> : () -> i64", "d", "B"))
    return fail("dependent default cycle was accepted");
  if (!rejects("sym_name = \"A\", type = i64, variability = \"symbolic\"",
               "sym_name = \"A\", type = i64, variability = \"fixed\"", "u", "A"))
    return fail("fixed formal override was accepted by instance query");
  if (!rejects("parameter_bindings = {}, sym_name = \"u\"",
               "parameter_bindings = {A = 4 : i64}, sym_name = \"u\"", "u", "A"))
    return fail("literal binding improperly specialized a symbolic SSA override");
  if (!rejects("\"nodal.parameter_constraint\"(%two, %derived)",
               "\"nodal.parameter_constraint\"(%a, %derived)", "u", "B"))
    return fail("unproved correlated lower bound was accepted");
  return 0;
}

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();
  if (checkInstanceParameterQueries(context))
    return 1;

  auto valid = mlir::parseSourceString<mlir::ModuleOp>(kValid, &context);
  if (!valid || mlir::failed(nodal::verifyParameterModel(*valid)) ||
      mlir::failed(mlir::verify(*valid)))
    return fail("valid parameter model did not verify");

  bool sawLossless = false;
  bool sawTargetUnit = false;
  bool sawBooleanDag = false;
  valid->walk([&](nodal::ParameterValueOp value) {
    auto parameter = value->getAttrOfType<mlir::FlatSymbolRefAttr>("parameter");
    if (!parameter)
      return;
    if (parameter.getValue() == "R") {
      auto rendered = nodal::renderParameterConstantExpression(value->getOperand(0));
      sawLossless = mlir::succeeded(rendered) && *rendered == "1k";
    }
    if (parameter.getValue() == "RBASE") {
      mlir::Operation *declaration = nullptr;
      value->getParentOp()->walk([&](nodal::ParameterOp candidate) {
        if (candidate.getSymName() == "RBASE")
          declaration = candidate.getOperation();
      });
      auto rendered = nodal::renderParameterConstantExpression(value->getOperand(0), declaration);
      sawTargetUnit = mlir::succeeded(rendered) && *rendered == "1k";
    }
    if (parameter.getValue() == "ENABLED") {
      auto rendered = nodal::renderParameterConstantExpression(value->getOperand(0));
      sawBooleanDag = mlir::succeeded(rendered) && *rendered == "((1 > 0) && 1)";
    }
  });
  if (!sawLossless)
    return fail("constant expression did not preserve native spelling");
  if (!sawTargetUnit)
    return fail("bare parameter magnitude did not inherit target unit");
  if (!sawBooleanDag)
    return fail("Boolean comparison and logical expression did not retain structure");

  auto badConstraint = mlir::parseSourceString<mlir::ModuleOp>(kBadConstraint, &context);
  if (!badConstraint || mlir::succeeded(nodal::verifyParameterModel(*badConstraint)))
    return fail("range or exclusion constraint was not enforced");

  auto badEnvelope = mlir::parseSourceString<mlir::ModuleOp>(kBadEnvelope, &context);
  if (!badEnvelope || mlir::succeeded(nodal::verifyParameterModel(*badEnvelope)))
    return fail("structural envelope was not enforced");

  auto dynamic = mlir::parseSourceString<mlir::ModuleOp>(kDynamic, &context);
  if (!dynamic || mlir::succeeded(nodal::verifyParameterModel(*dynamic)))
    return fail("dynamic value entered constant evaluation");

  auto cycle = mlir::parseSourceString<mlir::ModuleOp>(kCycle, &context);
  if (!cycle || mlir::succeeded(nodal::verifyParameterModel(*cycle)))
    return fail("cyclic constant expression was accepted");

  auto fixedBinding = mlir::parseSourceString<mlir::ModuleOp>(kFixedBinding, &context);
  if (!fixedBinding || mlir::succeeded(nodal::verifyParameterModel(*fixedBinding)))
    return fail("fixed parameter dictionary binding was accepted");

  auto fixedRootBinding = mlir::parseSourceString<mlir::ModuleOp>(kFixedRootBinding, &context);
  if (!fixedRootBinding || mlir::succeeded(nodal::verifyParameterModel(*fixedRootBinding)))
    return fail("fixed root parameter binding was accepted");

  auto outOfRangeRootBinding =
      mlir::parseSourceString<mlir::ModuleOp>(kOutOfRangeRootBinding, &context);
  if (!outOfRangeRootBinding ||
      mlir::succeeded(nodal::verifyParameterModel(*outOfRangeRootBinding)))
    return fail("out-of-range root parameter binding was accepted");

  auto excludedRootBinding =
      mlir::parseSourceString<mlir::ModuleOp>(kExcludedRootBinding, &context);
  if (!excludedRootBinding || mlir::succeeded(nodal::verifyParameterModel(*excludedRootBinding)))
    return fail("excluded root parameter binding was accepted");

  auto fixedExplicitOverride =
      mlir::parseSourceString<mlir::ModuleOp>(kFixedExplicitOverride, &context);
  if (!fixedExplicitOverride ||
      mlir::succeeded(nodal::verifyParameterModel(*fixedExplicitOverride)))
    return fail("fixed parameter explicit override was accepted");

  return 0;
}
