#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

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

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();

  auto valid = mlir::parseSourceString<mlir::ModuleOp>(kValid, &context);
  if (!valid || mlir::failed(nodal::verifyParameterModel(*valid)) ||
      mlir::failed(mlir::verify(*valid)))
    return fail("valid parameter model did not verify");

  bool sawLossless = false;
  bool sawTargetUnit = false;
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
  });
  if (!sawLossless)
    return fail("constant expression did not preserve native spelling");
  if (!sawTargetUnit)
    return fail("bare parameter magnitude did not inherit target unit");

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

  auto fixedExplicitOverride =
      mlir::parseSourceString<mlir::ModuleOp>(kFixedExplicitOverride, &context);
  if (!fixedExplicitOverride ||
      mlir::succeeded(nodal::verifyParameterModel(*fixedExplicitOverride)))
    return fail("fixed parameter explicit override was accepted");

  return 0;
}
