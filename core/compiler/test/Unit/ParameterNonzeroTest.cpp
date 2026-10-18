#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

#include <optional>
#include <string>

namespace {
using Fact = nodal::ParameterZeroAdmissibility;

std::string parameter(llvm::StringRef name, llvm::StringRef initial = "1.0",
                      llvm::StringRef type = "f64", bool fixed = false, llvm::StringRef unit = "") {
  return "\"nodal.parameter\"() <{classification = \"ordinary\", default_value = " + initial.str() +
         " : " + type.str() + ", metadata = {}, parameter_kind = \"" +
         (type == "f64" ? "real" : "integer") + "\", sym_name = \"" + name.str() +
         "\", type = " + type.str() + ", variability = \"" + (fixed ? "fixed" : "symbolic") + "\"" +
         (unit.empty() ? "" : ", unit = @" + unit.str()) + "}> : () -> ()\n";
}

std::string literal(llvm::StringRef id, llvm::StringRef number, llvm::StringRef type = "f64",
                    llvm::StringRef unit = "", llvm::StringRef suffix = "") {
  return "%" + id.str() + " = \"nodal.const_literal\"() <{metadata = {}, spelling = \"" +
         number.str() + suffix.str() + "\", value = " + number.str() + " : " + type.str() +
         (unit.empty() ? "" : ", unit = @" + unit.str()) + "}> : () -> " + type.str() + "\n";
}

std::string reference(llvm::StringRef id, llvm::StringRef name, llvm::StringRef type = "f64") {
  return "%" + id.str() + " = \"nodal.const_parameter_ref\"() <{metadata = {}, parameter = @" +
         name.str() + "}> : () -> " + type.str() + "\n";
}

std::string range(llvm::StringRef name, llvm::StringRef lower, llvm::StringRef upper,
                  bool lowerInclusive = true, bool upperInclusive = true,
                  llvm::StringRef type = "f64") {
  return "\"nodal.parameter_constraint\"(%" + lower.str() + ", %" + upper.str() +
         ") <{constraint_kind = \"range\", lower_inclusive = " +
         (lowerInclusive ? "true" : "false") +
         ", upper_inclusive = " + (upperInclusive ? "true" : "false") +
         ", metadata = {}, parameter = @" + name.str() + "}> : (" + type.str() + ", " + type.str() +
         ") -> ()\n";
}

std::string exclude(llvm::StringRef name, llvm::StringRef value, llvm::StringRef type = "f64") {
  return "\"nodal.parameter_constraint\"(%" + value.str() +
         ") <{constraint_kind = \"exclude\", lower_inclusive = false, upper_inclusive = false, "
         "metadata = {}, parameter = @" +
         name.str() + "}> : (" + type.str() + ") -> ()\n";
}

std::string expression(llvm::StringRef id, llvm::StringRef op, llvm::StringRef lhs,
                       llvm::StringRef rhs, llvm::StringRef type = "f64") {
  return "%" + id.str() + " = \"nodal.const_expr\"(%" + lhs.str() + ", %" + rhs.str() +
         ") <{metadata = {}, operator_name = \"" + op.str() + "\"}> : (" + type.str() + ", " +
         type.str() + ") -> " + type.str() + "\n";
}

std::string defaultExpression(llvm::StringRef parameter, llvm::StringRef value,
                              llvm::StringRef type = "f64") {
  return "\"nodal.parameter_value\"(%" + value.str() + ") <{metadata = {}, parameter = @" +
         parameter.str() + "}> : (" + type.str() + ") -> ()\n";
}

std::string printed(mlir::ModuleOp module) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  module.print(stream);
  return result;
}

bool check(mlir::MLIRContext &context, llvm::StringRef name, llvm::StringRef body,
           std::optional<Fact> expected, bool declaration = false, llvm::StringRef preamble = "",
           llvm::StringRef mutation = "") {
  const auto source = "module {\n" + preamble.str() +
                      "\"nodal.module\"() <{metadata = {}, sym_name = \"Fixture\"}> ({\n" +
                      body.str() + "}) : () -> ()\n}\n";
  auto parsed = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
  if (!parsed) {
    llvm::errs() << "PARAMETER_NONZERO_PARSE_FAILED " << name << '\n';
    return false;
  }
  mlir::Operation *parameter = nullptr, *last = nullptr;
  parsed->walk([&](mlir::Operation *operation) {
    if (auto symbol = operation->getAttrOfType<mlir::StringAttr>("sym_name");
        symbol && symbol.getValue() == "P")
      parameter = operation;
    if (operation->getNumResults() == 1)
      last = operation;
  });
  if (mutation == "kind")
    parameter->setAttr("variability", mlir::StringAttr::get(&context, "invalid"));
  if (mutation == "unit")
    parameter->setAttr("unit", mlir::FlatSymbolRefAttr::get(&context, "missing"));
  if (mutation == "literal")
    last->setAttr("value", mlir::FloatAttr::get(mlir::Float64Type::get(&context), 2.0));
  if (mutation == "constraint")
    parsed->walk([&](mlir::Operation *operation) {
      if (operation->getName().getStringRef() == "nodal.parameter_constraint")
        operation->removeAttr("lower_inclusive");
    });
  if (mutation == "unsupported-constraint")
    parsed->walk([&](mlir::Operation *operation) {
      if (operation->getName().getStringRef() == "nodal.parameter_constraint")
        operation->setAttr("constraint_kind", mlir::StringAttr::get(&context, "unsupported"));
    });
  const auto before = printed(*parsed);
  unsigned diagnostics = 0;
  mlir::ScopedDiagnosticHandler capture(&context, [&](mlir::Diagnostic &) {
    ++diagnostics;
    return mlir::success();
  });
  auto actual = declaration ? nodal::analyzeParameterZeroAdmissibility(parameter)
                            : nodal::analyzeParameterZeroAdmissibility(last->getResult(0));
  const bool matches =
      expected ? mlir::succeeded(actual) && *actual == *expected : mlir::failed(actual);
  if (!matches || diagnostics || printed(*parsed) != before) {
    llvm::errs() << "PARAMETER_NONZERO_FAILED " << name << " diagnostics=" << diagnostics << '\n';
    return false;
  }
  llvm::outs() << "PARAMETER_NONZERO_PASS " << name << '\n';
  return true;
}
} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();
  unsigned failures = 0, cases = 0;
  auto expect = [&](llvm::StringRef name, llvm::StringRef body, std::optional<Fact> fact,
                    bool declaration = false, llvm::StringRef preamble = "",
                    llvm::StringRef mutation = "") {
    ++cases;
    failures += !check(context, name, body, fact, declaration, preamble, mutation);
  };
  expect("positive-real", literal("x", "1.0"), Fact::ZeroExcluded);
  expect("negative-real", literal("x", "-2.0"), Fact::ZeroExcluded);
  expect("zero-real", literal("x", "0.0"), Fact::ZeroPermitted);
  expect("negative-zero-real", literal("x", "-0.0"), Fact::ZeroPermitted);
  expect("small-real-is-not-zero", literal("x", "1.0e-15"), Fact::ZeroExcluded);
  expect("integer-zero", literal("x", "0", "si64"), Fact::ZeroPermitted);
  expect("integer-nonzero", literal("x", "-2", "si64"), Fact::ZeroExcluded);
  expect("constant-expression",
         literal("a", "2.0") + literal("b", "3.0") + expression("x", "mul", "a", "b"),
         Fact::ZeroExcluded);
  expect("invalid-constant-division",
         literal("a", "2.0") + literal("b", "0.0") + expression("x", "div", "a", "b"),
         std::nullopt);
  expect("unconstrained-default-is-not-proof", parameter("P") + reference("x", "P"), Fact::Unknown);
  expect("unconstrained-zero-default", parameter("P", "0.0"), Fact::Unknown, true);
  const auto aroundZero = literal("lo", "-2.0") + literal("hi", "2.0") + range("P", "lo", "hi");
  const auto positive = literal("lo", "1.0") + literal("hi", "2.0") + range("P", "lo", "hi");
  expect("symbolic-zero-permitted", parameter("P", "0.0") + aroundZero, Fact::ZeroPermitted, true);
  expect("symbolic-range-excludes-zero", parameter("P") + positive + reference("x", "P"),
         Fact::ZeroExcluded);
  expect("invalid-zero-default-outside-range", parameter("P", "0.0") + positive, std::nullopt,
         true);
  expect("exclusive-zero-lower",
         parameter("P") + literal("lo", "0.0") + literal("hi", "2.0") +
             range("P", "lo", "hi", false),
         Fact::ZeroExcluded, true);
  expect("exclusive-zero-upper",
         parameter("P", "-1.0") + literal("lo", "-2.0") + literal("hi", "0.0") +
             range("P", "lo", "hi", true, false),
         Fact::ZeroExcluded, true);
  expect("explicit-zero-exclusion",
         parameter("P") + aroundZero + literal("zero", "0.0") + exclude("P", "zero"),
         Fact::ZeroExcluded, true);
  expect("nonzero-exclusion-permits-zero",
         parameter("P", "0.0") + literal("two", "2.0") + exclude("P", "two"), Fact::ZeroPermitted,
         true);
  expect("contradiction-never-proves-safety",
         parameter("P") + positive + literal("one", "1.0") + exclude("P", "one"), std::nullopt,
         true);
  expect("fixed-literal", parameter("P", "2.0", "f64", true), Fact::ZeroExcluded, true);
  expect("fixed-zero", parameter("P", "0.0", "f64", true), Fact::ZeroPermitted, true);
  expect("fixed-reference-retains-symbolic-dependency",
         parameter("P") + aroundZero + reference("p", "P") + parameter("F", "1.0", "f64", true) +
             defaultExpression("F", "p") + reference("x", "F"),
         Fact::ZeroPermitted);
  expect("fixed-unbounded-dependency-is-unknown",
         parameter("P") + reference("p", "P") + parameter("F", "1.0", "f64", true) +
             defaultExpression("F", "p") + reference("x", "F"),
         Fact::Unknown);
  expect("fixed-expression-does-not-use-incidental-default",
         parameter("P") + aroundZero + reference("p", "P") + literal("one", "1.0") +
             expression("sum", "add", "p", "one") + parameter("F", "2.0", "f64", true) +
             defaultExpression("F", "sum") + reference("x", "F"),
         Fact::Unknown);
  expect("integer-expression-reuses-bounds",
         parameter("P", "1", "si64") + literal("lo", "0", "si64") + literal("hi", "2", "si64") +
             range("P", "lo", "hi", true, true, "si64") + reference("p", "P", "si64") +
             literal("one", "1", "si64") + expression("x", "add", "p", "one", "si64"),
         Fact::ZeroExcluded);
  expect("varying-real-constraint-is-unknown",
         parameter("Q", "-2.0") + parameter("P") + reference("lo", "Q") + literal("hi", "2.0") +
             range("P", "lo", "hi"),
         Fact::Unknown, true);
  expect("fixed-reference-with-unresolved-constraint",
         parameter("P") + aroundZero + reference("p", "P") + parameter("Q", "0.5") +
             reference("lo2", "Q") + parameter("F", "1.0", "f64", true) +
             defaultExpression("F", "p") + range("F", "lo2", "hi") + reference("x", "F"),
         Fact::Unknown);
  expect("integer-expression-storage-overflow",
         literal("a", "127", "si8") + literal("b", "1", "si8") +
             expression("x", "add", "a", "b", "si8"),
         std::nullopt);
  expect("cyclic-fixed-defaults",
         parameter("P", "1.0", "f64", true) + parameter("Q", "1.0", "f64", true) +
             reference("p", "P") + reference("q", "Q") + defaultExpression("P", "q") +
             defaultExpression("Q", "p"),
         std::nullopt, true);
  expect("duplicate-default-expression",
         parameter("P") + literal("one", "1.0") + defaultExpression("P", "one") +
             defaultExpression("P", "one"),
         std::nullopt, true);
  expect("malformed-variability", parameter("P"), std::nullopt, true, "", "kind");
  expect("malformed-unit-reference", parameter("P"), std::nullopt, true, "", "unit");
  expect("malformed-literal", literal("x", "1.0"), std::nullopt, false, "", "literal");
  expect("missing-range-boundary", parameter("P") + positive, std::nullopt, true, "", "constraint");
  expect("unsupported-constraint", parameter("P") + positive, std::nullopt, true, "",
         "unsupported-constraint");
  const std::string units =
      "\"nodal.unit\"() <{dimension = \"resistance\", metadata = {}, native_suffix = \"\", scale = "
      "1.0 : f64, sym_name = \"Ohm\", symbol = \"Ohm\"}> : () -> ()\n"
      "\"nodal.unit\"() <{dimension = \"resistance\", metadata = {}, native_suffix = \"k\", scale "
      "= "
      "1.0e3 : f64, sym_name = \"kOhm\", symbol = \"kOhm\"}> : () -> ()\n";
  expect("unit-normalized-range",
         parameter("P", "1.0", "f64", false, "kOhm") + literal("lo", "500.0", "f64", "Ohm") +
             literal("hi", "2.0", "f64", "kOhm", "k") + range("P", "lo", "hi"),
         Fact::ZeroExcluded, true, units);
  auto analog = [](llvm::StringRef body) {
    return "\"nodal.analog\"() <{metadata = {}}> ({\n" + body.str() + "}) : () -> ()\n";
  };
  expect("typed-analog-parameter-reference",
         parameter("P") + positive +
             analog("%x = \"nodal.parameter_ref\"() <{metadata = {}, parameter = @P}> : () -> "
                    "!nodal.quantity<\"real\", \"1\">\n"),
         Fact::ZeroExcluded);
  expect("legacy-analog-parameter-reference",
         parameter("P") + positive +
             analog("%x = \"nodal.parameter_ref\"() <{metadata = {}, "
                    "parameter = @P}> : () -> f64\n"),
         Fact::ZeroExcluded);
  expect("analog-real-literal",
         analog("%x = \"nodal.real_literal\"() <{metadata = {}, value = 2.0 : f64}> : () -> "
                "!nodal.quantity<\"real\", \"1\">\n"),
         Fact::ZeroExcluded);
  expect("analog-integer-zero",
         analog("%x = \"nodal.analog_integer_literal\"() <{metadata = {}, value = 0 : i64}> : () "
                "-> !nodal.quantity<\"integer\", \"1\">\n"),
         Fact::ZeroPermitted);
  expect("runtime-expression-is-unknown",
         literal("zero", "0.0") + "%x = \"nodal.dynamic_value\"(%zero) <{metadata = {}, origin = "
                                  "\"runtime\"}> : (f64) -> f64\n",
         Fact::Unknown);
  std::string shared = literal("seed", "0.0"), previous = "seed";
  for (unsigned index = 0; index < 80; ++index) {
    const std::string next = "v" + std::to_string(index);
    shared += expression(next, "add", previous, previous);
    previous = next;
  }
  expect("shared-dag-is-memoized", shared, Fact::ZeroPermitted);
  ++cases;
  failures += mlir::succeeded(nodal::analyzeParameterZeroAdmissibility(mlir::Value()));
  ++cases;
  failures += mlir::succeeded(
      nodal::analyzeParameterZeroAdmissibility(static_cast<mlir::Operation *>(nullptr)));
  llvm::outs() << "PARAMETER_NONZERO_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}
