#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/TimeWaveform.h"

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

int checkParameterRenderBudget(mlir::MLIRContext &context) {
  auto fixture = [&](unsigned levels, llvm::StringRef opcode) {
    std::string source = R"mlir(module {
      "nodal.module"() <{metadata = {}, sym_name = "Budget"}> ({
        %v0 = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    )mlir";
    for (unsigned index = 1; index <= levels; ++index) {
      const auto current = std::to_string(index), previous = std::to_string(index - 1);
      source += "%v" + current + " = \"nodal.const_expr\"(%v" + previous;
      if (opcode != "neg")
        source += ", %v" + previous;
      source += ") <{metadata = {}, operator_name = \"" + opcode.str() + "\"}> : (i64";
      if (opcode != "neg")
        source += ", i64";
      source += ") -> i64\n";
    }
    source += "}) : () -> () }";
    return mlir::parseSourceString<mlir::ModuleOp>(source, &context);
  };
  auto output = [&](mlir::ModuleOp module) {
    mlir::Value value;
    module.walk([&](nodal::ConstExprOp op) { value = op->getResult(0); });
    return nodal::renderParameterConstantExpression(value);
  };
  auto small = fixture(8, "max");
  auto large = fixture(40, "max");
  auto deep = fixture(513, "neg");
  auto repeated = fixture(40, "add");
  if (!small || !large || !deep || !repeated)
    return fail("parameter rendering budget fixtures did not parse");
  size_t expectedSize = 1;
  for (unsigned index = 0; index < 8; ++index)
    expectedSize = 4 * expectedSize + 11;
  auto rendered = output(*small);
  if (mlir::failed(rendered) || rendered->size() != expectedSize)
    return fail("bounded shared maximum DAG did not preserve its ordinary expanded spelling");
  if (mlir::succeeded(output(*large)) || mlir::succeeded(output(*deep)) ||
      mlir::succeeded(output(*repeated)))
    return fail("oversized or deep parameter rendering escaped bounded DAG preflight");
  mlir::Operation *last = nullptr;
  small->walk([&](nodal::ConstExprOp op) { last = op.getOperation(); });
  last->setOperand(0, last->getResult(0));
  if (mlir::succeeded(nodal::renderParameterConstantExpression(last->getResult(0))))
    return fail("cyclic parameter rendering escaped bounded DAG preflight");
  return 0;
}

int checkStaticIntegerMaximum(mlir::MLIRContext &context) {
  constexpr llvm::StringLiteral source = R"mlir(
module {
  "nodal.module"() <{metadata = {}, sym_name = "Child"}> ({
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "W", type = i64, variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "X", type = i64, variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "MAXIMUM", type = i64, variability = "fixed"}> : () -> ()
    %one = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %nine = "nodal.const_literal"() <{metadata = {}, spelling = "9", value = 9 : i64}> : () -> i64
    "nodal.parameter_constraint"(%one, %nine) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}, parameter = @W}> : (i64, i64) -> ()
    "nodal.parameter_constraint"(%one, %nine) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}, parameter = @X}> : (i64, i64) -> ()
    %w = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @W}> : () -> i64
    %x = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @X}> : () -> i64
    %maximum = "nodal.const_expr"(%w, %x) <{metadata = {}, operator_name = "max"}> : (i64, i64) -> i64
    "nodal.parameter_value"(%maximum) <{metadata = {}, parameter = @MAXIMUM}> : (i64) -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Parent"}> ({
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "A", type = i64, variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 4 : i64, metadata = {}, parameter_kind = "integer", sym_name = "B", type = i64, variability = "symbolic"}> : () -> ()
    %one = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %three = "nodal.const_literal"() <{metadata = {}, spelling = "3", value = 3 : i64}> : () -> i64
    %six = "nodal.const_literal"() <{metadata = {}, spelling = "6", value = 6 : i64}> : () -> i64
    %nine = "nodal.const_literal"() <{metadata = {}, spelling = "9", value = 9 : i64}> : () -> i64
    "nodal.parameter_constraint"(%one, %nine) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}, parameter = @A}> : (i64, i64) -> ()
    "nodal.parameter_constraint"(%three, %six) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}, parameter = @B}> : (i64, i64) -> ()
    %a = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @A}> : () -> i64
    %b = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @B}> : () -> i64
    %maximum = "nodal.const_expr"(%a, %b) <{metadata = {}, operator_name = "max"}> : (i64, i64) -> i64
    %same = "nodal.const_expr"(%a, %a) <{metadata = {}, operator_name = "max"}> : (i64, i64) -> i64
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Child, parameter_bindings = {}, sym_name = "u"}> : () -> ()
    "nodal.parameter_override"(%a) <{instance = @u, parameter = @W, metadata = {}}> : (i64) -> ()
    "nodal.parameter_override"(%b) <{instance = @u, parameter = @X, metadata = {}}> : (i64) -> ()
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Child, parameter_bindings = {}, sym_name = "d"}> : () -> ()
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Child, parameter_bindings = {W = 8 : i64, X = 6 : i64}, sym_name = "v"}> : () -> ()
  }) : () -> ()
}
)mlir";
  auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
  if (!module || mlir::failed(mlir::verify(*module)) ||
      mlir::failed(nodal::verifyParameterModel(*module)))
    return fail("static Integer maximum model did not verify");
  auto *parent = namedOperation(module->getOperation(), "Parent");
  auto *child = namedOperation(module->getOperation(), "Child");
  auto *formal = namedOperation(child, "MAXIMUM");
  llvm::SmallVector<mlir::Value, 2> expressions;
  parent->walk([&](nodal::ConstExprOp op) { expressions.push_back(op->getResult(0)); });
  auto bounds = nodal::inferParameterIntegerBounds(expressions[0]);
  auto owned = nodal::resolveOwnedIntegerExpression(parent, expressions[0]);
  auto other = nodal::resolveOwnedIntegerExpression(parent, expressions[1]);
  auto bound = nodal::resolveInstanceIntegerParameter(namedOperation(parent, "u"), formal);
  auto a = nodal::resolveOwnedIntegerParameter(parent, namedOperation(parent, "A"));
  auto b = nodal::resolveOwnedIntegerParameter(parent, namedOperation(parent, "B"));
  if (mlir::failed(bounds) || bounds->lower != 3 || bounds->upper != 9 || mlir::failed(owned) ||
      mlir::failed(other) || mlir::failed(bound) || mlir::failed(a) || mlir::failed(b) ||
      owned->root->kind != nodal::InstantiatedIntegerNode::Kind::Apply ||
      owned->root->opcode != "max" ||
      !nodal::equivalentInstantiatedIntegerExpressions(*owned, *bound) ||
      nodal::equivalentInstantiatedIntegerExpressions(*owned, *other) ||
      nodal::equivalentInstantiatedIntegerExpressions(*owned,
                                                      nodal::makeLiteralIntegerExpression(4)))
    return fail("static maximum lost all-legal bounds or symbolic declaration identity");
  auto composed = nodal::composeIntegerExpression("max", {*a, *b});
  auto idempotent = nodal::composeIntegerExpression("max", {*a, *a});
  if (mlir::failed(composed) ||
      !nodal::equivalentInstantiatedIntegerExpressions(*owned, *composed) ||
      mlir::failed(idempotent) ||
      !nodal::equivalentInstantiatedIntegerExpressions(*a, *idempotent) ||
      !nodal::equivalentInstantiatedIntegerExpressions(*a, *other) ||
      nodal::equivalentInstantiatedIntegerExpressions(*a, *composed))
    return fail("static maximum composition disagrees with exact owned operand identities");
  auto childMaximum = nodal::resolveOwnedIntegerParameter(child, formal);
  auto childWidth = nodal::resolveOwnedIntegerParameter(child, namedOperation(child, "W"));
  if (mlir::failed(childMaximum) || mlir::failed(childWidth) ||
      nodal::equivalentInstantiatedIntegerExpressions(*childMaximum, *childWidth))
    return fail("static maximum collapsed independent parameters with equal defaults and bounds");
  for (auto entry : {std::make_pair("d", int64_t{4}), std::make_pair("v", int64_t{8})}) {
    auto resolved =
        nodal::resolveInstanceIntegerParameter(namedOperation(parent, entry.first), formal);
    if (mlir::failed(resolved) || !nodal::equivalentInstantiatedIntegerExpressions(
                                      *resolved, nodal::makeLiteralIntegerExpression(entry.second)))
      return fail("static maximum did not substitute a finalized default or override exactly");
  }
  auto invariant = nodal::getInvariantParameterDefault(formal);
  if (mlir::failed(invariant) || invariant->has_value())
    return fail("fixed maximum with symbolic dependencies incorrectly became an invariant default");
  auto rendered = nodal::renderParameterConstantExpression(expressions[0]);
  if (mlir::failed(rendered) || *rendered != "(A > B ? A : B)")
    return fail("static maximum rendering did not retain its symbolic operands");

  const auto minimum = nodal::makeLiteralIntegerExpression(std::numeric_limits<int64_t>::min());
  const auto maximum = nodal::makeLiteralIntegerExpression(std::numeric_limits<int64_t>::max());
  auto endpoints = nodal::composeIntegerExpression("max", {minimum, maximum});
  if (mlir::failed(endpoints) ||
      !nodal::equivalentInstantiatedIntegerExpressions(*endpoints, maximum) ||
      mlir::succeeded(nodal::composeIntegerExpression(
          "add", {*endpoints, nodal::makeLiteralIntegerExpression(1)})) ||
      mlir::succeeded(nodal::composeIntegerExpression("max", {})) ||
      mlir::succeeded(nodal::composeIntegerExpression("max", {minimum})))
    return fail("static maximum literal boundary, overflow, or arity checks failed");

  auto *operation = expressions[0].getDefiningOp();
  const auto originalOperands = llvm::SmallVector<mlir::Value, 2>(operation->getOperands());
  operation->setOperands({originalOperands[0]});
  if (mlir::succeeded(mlir::verify(operation)) ||
      mlir::succeeded(nodal::resolveOwnedIntegerExpression(parent, expressions[0])) ||
      mlir::succeeded(nodal::renderParameterConstantExpression(expressions[0])))
    return fail("malformed maximum arity escaped independent native checks");
  operation->setOperands(originalOperands);
  auto childValue = formal;
  mlir::Value foreign;
  childValue->getParentOp()->walk([&](nodal::ConstExprOp op) { foreign = op->getResult(0); });
  operation->setOperand(1, foreign);
  if (mlir::succeeded(nodal::resolveOwnedIntegerExpression(parent, expressions[0])))
    return fail("maximum accepted a foreign-module operand");
  operation->setOperands(originalOperands);
  expressions[0].setType(mlir::Float64Type::get(&context));
  if (mlir::succeeded(mlir::verify(operation)) ||
      mlir::succeeded(nodal::inferParameterIntegerBounds(expressions[0])))
    return fail("maximum accepted a non-Integer result");
  expressions[0].setType(mlir::IntegerType::get(&context, 64));
  operation->setAttr("unit", mlir::FlatSymbolRefAttr::get(&context, "unavailable"));
  if (mlir::succeeded(mlir::verify(operation)) ||
      mlir::succeeded(nodal::resolveOwnedIntegerExpression(parent, expressions[0])))
    return fail("maximum accepted dimensional metadata");
  return 0;
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

int checkGeneratedParameterQueries(mlir::MLIRContext &context) {
  std::string source = kInstanceShapeParameters.str();
  const std::string instance =
      R"mlir(    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Child, parameter_bindings = {}, sym_name = "u"}> : () -> ())mlir";
  const std::string override =
      R"mlir(    "nodal.parameter_override"(%n) <{instance = @u, parameter = @A, metadata = {}}> : (i64) -> ())mlir";
  auto inScope = [](std::string text, llvm::StringRef scope) {
    auto position = text.find("metadata = {}");
    text.replace(position, 13,
                 "generated_owner = \"" + scope.str() + "\", generated_induction = \"" +
                     scope.str() + ".i\", metadata = {}");
    return text;
  };
  auto position = source.find(instance);
  source.erase(position, instance.size());
  position = source.find(override);
  source.replace(
      position, override.size(),
      R"mlir(    "nodal.generate"() <{induction = "lane", lower = 0 : i64, upper = 3 : i64, step = 1 : i64, region_id = "Parent.first", induction_path = "Parent.first.i", metadata = {maximum_trip_count = 3 : i64}}> ({
)mlir" + inScope(instance, "Parent.first") +
          "\n" + inScope(override, "Parent.first") + R"mlir(
    }) : () -> ()
    "nodal.generate"() <{induction = "other", lower = 2 : i64, upper = 7 : i64, step = 2 : i64, region_id = "Parent.second", induction_path = "Parent.second.i", metadata = {maximum_trip_count = 3 : i64}}> ({
)mlir" + inScope(instance, "Parent.second") +
          R"mlir(
    }) : () -> ())mlir");
  auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
  if (!module || mlir::failed(nodal::verifyParameterModel(*module)))
    return fail("generated invariant instance parameter fixture did not verify");
  auto *parent = namedOperation(module->getOperation(), "Parent");
  auto *child = namedOperation(module->getOperation(), "Child");
  auto *formal = namedOperation(child, "B");
  llvm::SmallVector<mlir::Operation *> scopes;
  parent->walk([&](nodal::GenerateOp op) { scopes.push_back(op); });
  if (scopes.size() != 2)
    return fail("generated parameter fixture lost scopes");
  auto *first = &scopes[0]->getRegion(0).front().front();
  auto *second = &scopes[1]->getRegion(0).front().front();
  auto *actualOverride = &scopes[0]->getRegion(0).front().back();
  auto reference = mlir::FlatSymbolRefAttr::get(&context, "u");
  if (nodal::resolveStructuralInstance(actualOverride, reference) != first ||
      nodal::resolveStructuralInstance(second, reference) != second ||
      nodal::resolveStructuralInstance(scopes[0], reference) ||
      nodal::resolveInstanceModuleDefinition(first) != child)
    return fail("instance lookup confused lexical scope or definition namespace");
  auto firstValue = nodal::resolveInstanceIntegerParameter(first, formal);
  auto secondValue = nodal::resolveInstanceIntegerParameter(second, formal);
  if (mlir::failed(firstValue) || mlir::failed(secondValue) || firstValue->bounds.lower != 3 ||
      firstValue->bounds.upper != 7 ||
      !nodal::equivalentInstantiatedIntegerExpressions(*secondValue,
                                                       nodal::makeLiteralIntegerExpression(5)))
    return fail("same-name generated instances mixed override environments");
  auto firstDomain = nodal::resolveGeneratedDomain(scopes[0]);
  auto secondDomain = nodal::resolveGeneratedDomain(scopes[1]);
  if (mlir::failed(firstDomain) || mlir::failed(secondDomain) || firstDomain->module != parent ||
      firstDomain->maximumTripCount != 3 || secondDomain->maximumTripCount != 3 ||
      !secondDomain->attainedInductionBounds || secondDomain->attainedInductionBounds->lower != 2 ||
      secondDomain->attainedInductionBounds->upper != 6)
    return fail("shared generated domain lost positive source index/stride facts");
  auto type = mlir::IntegerType::get(&context, 64);
  auto induction = scopes[0]->getRegion(0).front().addArgument(type, scopes[0]->getLoc());
  auto withInduction = nodal::resolveGeneratedDomain(scopes[0]);
  if (mlir::failed(withInduction) || withInduction->induction != induction)
    return fail("generated domain failed to retain actual induction SSA");
  auto savedUpper = scopes[0]->getAttr("upper");
  auto countMetadata = [&](int64_t count) {
    return mlir::DictionaryAttr::get(
        &context, {mlir::NamedAttribute(mlir::StringAttr::get(&context, "maximum_trip_count"),
                                        mlir::IntegerAttr::get(type, count))});
  };
  scopes[0]->setAttr("upper", mlir::IntegerAttr::get(type, 0));
  scopes[0]->setAttr("metadata", countMetadata(0));
  auto empty = nodal::resolveGeneratedDomain(scopes[0]);
  if (mlir::failed(empty) || empty->maximumTripCount || empty->attainedInductionBounds)
    return fail("empty generated domain invented attained coordinates");
  scopes[0]->setAttr("upper", savedUpper);
  scopes[0]->setAttr("lower", mlir::IntegerAttr::get(type, -2));
  scopes[0]->setAttr("metadata", countMetadata(5));
  auto negativeOrigin = nodal::resolveGeneratedDomain(scopes[0]);
  if (mlir::failed(negativeOrigin) || negativeOrigin->maximumTripCount != 5 ||
      negativeOrigin->lower.root->literal != -2)
    return fail("generated signed-i64 origin was interpreted as unsigned");
  scopes[0]->setAttr("lower", mlir::IntegerAttr::get(type, 0));
  scopes[0]->setAttr("metadata", countMetadata(3));
  scopes[0]->setAttr("step", mlir::IntegerAttr::get(type, 0));
  if (mlir::succeeded(nodal::resolveGeneratedDomain(scopes[0])))
    return fail("zero-step generated domain was accepted");
  scopes[0]->setAttr("step", mlir::IntegerAttr::get(type, 1));
  actualOverride->setAttr("instance", mlir::FlatSymbolRefAttr::get(&context, "d"));
  if (mlir::succeeded(nodal::verifyParameterModel(*module)))
    return fail("generated override escaped to an outer instance");
  actualOverride->setAttr("instance", reference);
  const auto savedActual = actualOverride->getOperand(0);
  actualOverride->setOperand(0, induction);
  if (mlir::succeeded(nodal::verifyParameterModel(*module)) ||
      mlir::succeeded(nodal::resolveInstanceIntegerParameter(first, formal)))
    return fail("per-occurrence induction silently entered invariant instance environment");
  actualOverride->setOperand(0, savedActual);
  // Switch the same range to its retained SSA form. Its upper atom must remain
  // the actual parent parameter, and count metadata must be independently checked.
  auto *n = namedOperation(parent, "N");
  n->setAttr("classification", mlir::StringAttr::get(&context, "structural"));
  mlir::Value lowerValue, stepValue;
  for (mlir::Operation &op : parent->getRegion(0).front()) {
    if (op.getName().getStringRef() != "nodal.const_literal")
      continue;
    auto value = op.getAttrOfType<mlir::IntegerAttr>("value");
    if (value && value.getInt() == 2)
      lowerValue = op.getResult(0);
    if (value && value.getInt() == 1)
      stepValue = op.getResult(0);
  }
  scopes[0]->setOperands({lowerValue, savedActual, stepValue});
  for (auto name : {"lower", "upper", "step"})
    scopes[0]->removeAttr(name);
  scopes[0]->setAttr("region_id", mlir::StringAttr::get(&context, "Parent.range"));
  scopes[0]->setAttr("induction_path", mlir::StringAttr::get(&context, "Parent.range.i"));

  scopes[0]->setAttr("metadata", countMetadata(4));
  auto symbolicDomain = nodal::resolveGeneratedDomain(scopes[0]);
  auto nExpression = nodal::resolveOwnedIntegerParameter(parent, n);
  if (mlir::failed(symbolicDomain) || mlir::failed(nExpression) ||
      symbolicDomain->maximumTripCount != 4 ||
      !nodal::equivalentInstantiatedIntegerExpressions(symbolicDomain->upperExclusive,
                                                       *nExpression))
    return fail("generated SSA domain specialized its parameter or lost exact upper identity");
  scopes[0]->setAttr("metadata", countMetadata(3));
  if (mlir::succeeded(nodal::resolveGeneratedDomain(scopes[0])))
    return fail("forged generated count metadata passed the shared domain query");
  scopes[0]->setAttr("metadata", countMetadata(4));
  first->setAttr("sym_name", mlir::StringAttr::get(&context, "Child"));
  if (nodal::resolveInstanceModuleDefinition(first) != child)
    return fail("instance name shadowed its top-level module definition");
  return 0;
}

int checkGeneratedWaveformParameterLookup(mlir::MLIRContext &context) {
  std::string source = kValid.str();
  const auto end = source.rfind("  }) : () -> ()");
  source.insert(end, R"mlir(
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "GAIN", type = f64, variability = "symbolic"}> : () -> ()
    "nodal.generate"() <{induction = "i", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, region_id = "Fixture.range", induction_path = "Fixture.range.i", metadata = {maximum_trip_count = 2 : i64}}> ({
      "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Empty, parameter_bindings = {}, sym_name = "GAIN", generated_owner = "Fixture.range", generated_induction = "Fixture.range.i"}> : () -> ()
      "nodal.analog"() <{metadata = {}, generated_owner = "Fixture.range", generated_induction = "Fixture.range.i"}> ({
        %reference = "nodal.parameter_ref"() <{parameter = @GAIN, metadata = {}}> : () -> f64
        "nodal.analog_procedure"() <{owner = "Fixture.range.proc", generated_owner = "Fixture.range", generated_induction = "Fixture.range.i", metadata = {}}> ({^bb0:}) : () -> ()
      }) : () -> ()
    }) : () -> ()
)mlir");
  source.insert(
      source.find("  \"nodal.module\""),
      "  \"nodal.module\"() <{metadata = {}, sym_name = \"Empty\"}> ({^bb0:}) : () -> ()\n");
  auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
  if (!module)
    return fail("generated waveform parameter fixture did not parse");
  mlir::Operation *reference = nullptr, *instance = nullptr;
  module->walk([&](nodal::ParameterRefOp op) { reference = op; });
  module->walk([&](nodal::InstanceOp op) { instance = op; });
  auto dimension = nodal::getAnalogRealDimension(reference->getResult(0));
  if (!nodal::isAnalogStaticExpression(reference->getResult(0)) || mlir::failed(dimension) ||
      *dimension != "1")
    return fail("generated symbol table hid the actual module parameter from waveform analysis");
  reference->setAttr("parameter", mlir::FlatSymbolRefAttr::get(&context, "missing"));
  instance->setAttr("sym_name", mlir::StringAttr::get(&context, "missing"));
  if (nodal::isAnalogStaticExpression(reference->getResult(0)) ||
      mlir::succeeded(nodal::getAnalogRealDimension(reference->getResult(0))))
    return fail("same-name instance masqueraded as an analog parameter");
  return 0;
}

int checkSymbolicDigitalParameters(mlir::MLIRContext &context) {
  auto fixture = [](llvm::StringRef kind, llvm::StringRef value, llvm::StringRef carrier = "i64",
                    int64_t lower = 4, int64_t upper = 8, llvm::StringRef rootBindings = "",
                    llvm::StringRef instanceBindings = "", llvm::StringRef extra = "") {
    std::string source = "module";
    if (!rootBindings.empty())
      source += " attributes {nodal.root.module = @Child, nodal.root.parameter_bindings = {" +
                rootBindings.str() + "}}";
    source +=
        R"mlir( {
      "nodal.module"() <{metadata = {}, sym_name = "Child"}> ({
        "nodal.parameter"() <{classification = "structural", default_value = )mlir" +
        std::to_string(upper) +
        R"mlir( : i64, metadata = {}, parameter_kind = "integer", sym_name = "W", type = i64, variability = "symbolic"}> : () -> ()
        %low = "nodal.const_literal"() <{metadata = {}, spelling = ")mlir" +
        std::to_string(lower) + "\", value = " + std::to_string(lower) +
        R"mlir( : i64}> : () -> i64
        %high = "nodal.const_literal"() <{metadata = {}, spelling = ")mlir" +
        std::to_string(upper) + "\", value = " + std::to_string(upper) +
        R"mlir( : i64}> : () -> i64
        "nodal.parameter_constraint"(%low, %high) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, parameter = @W, metadata = {}}> : (i64, i64) -> ()
        "nodal.parameter_envelope"() <{effects = ["width"], parameter = @W, policy = "static_generate", metadata = {}}> : () -> ()
      )mlir";
    const std::string type = "!nodal.digital<\"" + kind.str() + "\", \"W\">";
    source +=
        "\"nodal.parameter\"() <{classification = \"ordinary\", default_value = " + value.str() +
        " : " + carrier.str() +
        ", metadata = {}, parameter_kind = \"integer\", sym_name = \"VALUE\", type = " + type +
        ", variability = \"symbolic\"}> : () -> ()\n";
    source += "%value = \"nodal.const_literal\"() <{metadata = {}, spelling = \"" + value.str() +
              "\", value = " + value.str() + " : " + carrier.str() + "}> : () -> " + type + "\n";
    source += "\"nodal.parameter_value\"(%value) <{metadata = {}, parameter = @VALUE}> : (" + type +
              ") -> ()\n" + extra.str() + "}) : () -> ()\n";
    if (!instanceBindings.empty())
      source += "\"nodal.module\"() <{metadata = {}, sym_name = \"Top\"}> ({"
                "\"nodal.instance\"() <{domain_bindings = {}, metadata = {}, module = @Child, "
                "parameter_bindings = {" +
                instanceBindings.str() + "}, sym_name = \"child\"}> : () -> ()}) : () -> ()\n";
    return source + "}";
  };
  unsigned failures = 0;
  auto check = [&](llvm::StringRef name, const std::string &source, bool expected) {
    auto module =
        mlir::parseSourceString<mlir::ModuleOp>(source, mlir::ParserConfig(&context, false));
    bool good = false;
    if (module) {
      const bool actual = mlir::succeeded(nodal::verifyParameterModel(*module)) &&
                          mlir::succeeded(mlir::verify(*module));
      good = actual == expected;
    }
    failures += !good;
    (good ? llvm::outs() : llvm::errs())
        << "SYMBOLIC_DIGITAL_PARAMETER_" << (good ? "PASS " : "FAIL ") << name << '\n';
  };
  check("unsigned_all_widths", fixture("uint", "15"), true);
  check("signed_minimum", fixture("sint", "-8"), true);
  check("signed_maximum", fixture("sint", "7"), true);
  check("unsigned_default_only_fit_reject", fixture("uint", "16"), false);
  check("signed_underflow_reject", fixture("sint", "-9"), false);
  check("signed_overflow_reject", fixture("sint", "8"), false);
  check("negative_unsigned_reject", fixture("uint", "-1"), false);
  check("minimum_width_root", fixture("uint", "15", "i64", 4, 8, "W = 4 : i64"), true);
  check("maximum_width_root", fixture("uint", "15", "i64", 4, 8, "W = 8 : i64"), true);
  check("illegal_width_root", fixture("uint", "15", "i64", 4, 8, "W = 3 : i64"), false);
  check("legal_unsigned_root", fixture("uint", "1", "i64", 4, 8, "VALUE = 15 : i64"), true);
  check("illegal_unsigned_root", fixture("uint", "1", "i64", 4, 8, "VALUE = 16 : i64"), false);
  check("legal_signed_root", fixture("sint", "0", "i64", 4, 8, "VALUE = -8 : i64"), true);
  check("illegal_signed_root", fixture("sint", "0", "i64", 4, 8, "VALUE = -9 : i64"), false);
  check("legal_unsigned_instance", fixture("uint", "1", "i64", 4, 8, "", "VALUE = 15 : i64"), true);
  check("illegal_unsigned_instance", fixture("uint", "1", "i64", 4, 8, "", "VALUE = 16 : i64"),
        false);
  check("illegal_signed_instance", fixture("sint", "0", "i64", 4, 8, "", "VALUE = 8 : i64"), false);
  const llvm::StringRef wide = "340282366920938463463374607431768211455";
  check("unsigned_highbit_carrier", fixture("uint", wide, "ui128", 128, 129), true);
  check("unsigned_highbit_signed_destination", fixture("sint", wide, "ui128", 129, 130), true);
  check("unsigned_highbit_signed_overflow", fixture("sint", wide, "ui128", 128, 129), false);
  auto missingRange = fixture("uint", "1");
  const auto begin = missingRange.find("        \"nodal.parameter_constraint\"");
  const auto end = missingRange.find('\n', begin);
  missingRange.erase(begin, end - begin + 1);
  check("unbounded_width_reject", missingRange, false);
  auto wrongOwner = fixture("uint", "1");
  for (auto position = wrongOwner.find("\"W\">"); position != std::string::npos;
       position = wrongOwner.find("\"W\">", position)) {
    wrongOwner.replace(position, 4, "\"Other.W\">");
    position += 10;
  }
  check("unowned_width_reject", wrongOwner, false);
  const std::string symbolic = "!nodal.digital<\"uint\", \"W\">";
  check("generic_symbolic_arithmetic_reject",
        fixture("uint", "1", "i64", 4, 8, "", "",
                "%sum = \"nodal.const_expr\"(%value, %value) <{metadata = {}, operator_name = "
                "\"add\"}> : (" +
                    symbolic + ", " + symbolic + ") -> " + symbolic + "\n"),
        false);
  return failures ? fail("symbolic digital parameter cases failed") : 0;
}

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();
  if (checkParameterRenderBudget(context) || checkStaticIntegerMaximum(context) ||
      checkSymbolicDigitalParameters(context) || checkInstanceParameterQueries(context) ||
      checkGeneratedParameterQueries(context) || checkGeneratedWaveformParameterLookup(context))
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
