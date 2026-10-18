#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

#include <cstdint>
#include <limits>
#include <string>

namespace {

std::string literal(llvm::StringRef id, llvm::StringRef number, llvm::StringRef type = "si64",
                    bool probe = false, llvm::StringRef attributeType = "") {
  const std::string carrier = attributeType.empty() ? type.str() : attributeType.str();
  return "%" + id.str() +
         " = \"nodal.const_literal\"() <{metadata = {probe = " + (probe ? "true" : "false") +
         "}, spelling = \"" + number.str() + "\", value = " + number.str() + " : " + carrier +
         "}> : () -> " + type.str() + "\n";
}

std::string reference(llvm::StringRef id, llvm::StringRef name, llvm::StringRef type = "si64",
                      bool probe = false) {
  return "%" + id.str() +
         " = \"nodal.const_parameter_ref\"() <{metadata = {probe = " + (probe ? "true" : "false") +
         "}, parameter = @" + name.str() + "}> : () -> " + type.str() + "\n";
}

std::string parameter(llvm::StringRef name, llvm::StringRef initial, llvm::StringRef type = "si64",
                      bool fixed = false) {
  return "\"nodal.parameter\"() <{classification = \"ordinary\", default_value = " + initial.str() +
         " : " + type.str() + ", metadata = {}, parameter_kind = \"integer\", sym_name = \"" +
         name.str() + "\", type = " + type.str() + ", variability = \"" +
         (fixed ? "fixed" : "symbolic") + "\"}> : () -> ()\n";
}

std::string range(llvm::StringRef name, llvm::StringRef low, llvm::StringRef high,
                  llvm::StringRef type = "si64", bool lowerInclusive = true,
                  bool upperInclusive = true) {
  return "\"nodal.parameter_constraint\"(%" + low.str() + ", %" + high.str() +
         ") <{constraint_kind = \"range\", lower_inclusive = " +
         (lowerInclusive ? "true" : "false") + ", metadata = {}, parameter = @" + name.str() +
         ", upper_inclusive = " + (upperInclusive ? "true" : "false") + "}> : (" + type.str() +
         ", " + type.str() + ") -> ()\n";
}

std::string expression(llvm::StringRef id, llvm::StringRef op, llvm::StringRef left,
                       llvm::StringRef right = "", llvm::StringRef type = "si64",
                       bool probe = true) {
  std::string arguments = "%" + left.str();
  std::string types = type.str();
  if (!right.empty()) {
    arguments += ", %" + right.str();
    types += ", " + type.str();
  }
  return "%" + id.str() + " = \"nodal.const_expr\"(" + arguments +
         ") <{metadata = {probe = " + (probe ? "true" : "false") + "}, operator_name = \"" +
         op.str() + "\"}> : (" + types + ") -> " + type.str() + "\n";
}

std::string model(llvm::StringRef body) {
  return "module { \"nodal.module\"() <{metadata = {}, sym_name = \"Fixture\"}> ({\n" + body.str() +
         "}) : () -> () }\n";
}

std::string bounded(llvm::StringRef low, llvm::StringRef high, llvm::StringRef initial = "1",
                    llvm::StringRef type = "si64") {
  return parameter("N", initial, type) + literal("lo", low, type) + literal("hi", high, type) +
         range("N", "lo", "hi", type) + reference("n", "N", type);
}

std::string printed(mlir::ModuleOp module) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  module.print(stream);
  stream.flush();
  return result;
}

bool check(mlir::MLIRContext &context, llvm::StringRef name, llvm::StringRef body, bool available,
           int64_t lower = 0, int64_t upper = 0) {
  auto parsed = mlir::parseSourceString<mlir::ModuleOp>(model(body), &context);
  if (!parsed) {
    llvm::errs() << "INTEGER_BOUNDS_TEST_PARSE_FAILED " << name << '\n';
    return false;
  }
  const std::string before = printed(*parsed);
  mlir::Value input;
  unsigned probes = 0;
  parsed->walk([&](mlir::Operation *operation) {
    auto metadata = operation->getAttrOfType<mlir::DictionaryAttr>("metadata");
    auto probe = metadata ? metadata.getAs<mlir::BoolAttr>("probe") : mlir::BoolAttr();
    if (probe && probe.getValue() && operation->getNumResults() == 1) {
      input = operation->getResult(0);
      ++probes;
    }
  });
  if (probes != 1) {
    llvm::errs() << "INTEGER_BOUNDS_TEST_PROBE_FAILED " << name << '\n';
    return false;
  }
  auto actual = nodal::inferParameterIntegerBounds(input);
  bool matched = available
                     ? mlir::succeeded(actual) && actual->lower == lower && actual->upper == upper
                     : mlir::failed(actual);
  if (!matched || printed(*parsed) != before) {
    llvm::errs() << "INTEGER_BOUNDS_TEST_FAILED " << name << '\n';
    return false;
  }
  llvm::outs() << "INTEGER_BOUNDS_TEST_PASS " << name << '\n';
  return true;
}

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();
  unsigned failures = 0;
  unsigned cases = 0;
  auto expect = [&](llvm::StringRef name, llvm::StringRef body, int64_t lower, int64_t upper) {
    ++cases;
    failures += !check(context, name, body, true, lower, upper);
  };
  auto unavailable = [&](llvm::StringRef name, llvm::StringRef body) {
    ++cases;
    failures += !check(context, name, body, false);
  };

  expect("parameter-range-not-default", bounded("-5", "8") + reference("result", "N", "si64", true),
         -5, 8);
  expect("range-intersection-and-open-endpoints",
         bounded("-5", "8") + literal("lo2", "-3") + literal("hi2", "6") +
             range("N", "lo2", "hi2", "si64", false, false) +
             reference("result", "N", "si64", true),
         -2, 5);
  expect("singleton", bounded("7", "7", "7") + reference("result", "N", "si64", true), 7, 7);
  unavailable("unbounded-default-is-not-a-bound",
              parameter("N", "4") + reference("result", "N", "si64", true));
  expect("fixed-literal",
         parameter("N", "4", "si64", true) + reference("result", "N", "si64", true), 4, 4);
  expect(
      "fixed-expression-keeps-dependency",
      bounded("1", "8") + literal("two", "2") +
          expression("sum", "add", "n", "two", "si64", false) +
          parameter("TOTAL", "3", "si64", true) +
          "\"nodal.parameter_value\"(%sum) <{metadata = {}, parameter = @TOTAL}> : (si64) -> ()\n" +
          reference("result", "TOTAL", "si64", true),
      3, 10);
  expect("varying-range-uses-outer-endpoints",
         bounded("2", "9", "2") + parameter("M", "2") + literal("one", "1") +
             range("M", "one", "n") + reference("result", "M", "si64", true),
         1, 9);
  unavailable("constraint-cycle-never-uses-default",
              parameter("N", "2") + parameter("M", "2") + literal("one", "1") +
                  reference("n", "N") + reference("m", "M") + range("N", "one", "m") +
                  range("M", "one", "n") + reference("result", "N", "si64", true));
  unavailable("empty-integer-interval", bounded("2", "3", "2") +
                                            range("N", "lo", "hi", "si64", false, false) +
                                            reference("result", "N", "si64", true));

  expect("sum", bounded("-5", "8") + literal("two", "2") + expression("result", "add", "n", "two"),
         -3, 10);
  expect("difference",
         bounded("-5", "8") + literal("two", "2") + expression("result", "sub", "n", "two"), -7, 6);
  expect("shared-subtraction", bounded("-5", "8") + expression("result", "sub", "n", "n"), 0, 0);
  expect("negative-factor",
         bounded("-5", "8") + literal("factor", "-3") + expression("result", "mul", "n", "factor"),
         -24, 15);
  expect("negation", bounded("-5", "8") + expression("result", "neg", "n"), -8, 5);
  expect("division-truncates-toward-zero",
         bounded("-5", "8") + literal("two", "2") + expression("result", "div", "n", "two"), -2, 4);
  expect("negative-divisor",
         bounded("-5", "8") + literal("two", "-2") + expression("result", "div", "n", "two"), -4,
         2);
  expect("signed-remainder",
         bounded("-5", "8") + literal("three", "3") + expression("result", "mod", "n", "three"), -2,
         2);
  expect("negative-remainder-divisor",
         bounded("-5", "8") + literal("three", "-3") + expression("result", "mod", "n", "three"),
         -2, 2);
  unavailable("varying-divisor-can-be-zero", bounded("0", "3") + literal("eight", "8") +
                                                 expression("result", "div", "eight", "n"));
  unavailable("varying-modulus-can-be-zero", bounded("-3", "3") + literal("eight", "8") +
                                                 expression("result", "mod", "eight", "n"));
  unavailable("signed-host-overflow", bounded("1", "9223372036854775807") + literal("one", "1") +
                                          expression("result", "add", "n", "one"));
  unavailable("storage-type-overflow", bounded("1", "127", "1", "si8") +
                                           literal("one", "1", "si8") +
                                           expression("result", "add", "n", "one", "si8"));
  unavailable("negation-minimum-overflow",
              bounded("-9223372036854775808", "0", "0") + expression("result", "neg", "n"));
  unavailable("division-minimum-overflow", bounded("-9223372036854775808", "0", "0") +
                                               literal("minus_one", "-1") +
                                               expression("result", "div", "n", "minus_one"));
  unavailable("remainder-minimum-host-contract", bounded("-9223372036854775808", "0", "0") +
                                                     literal("minus_one", "-1") +
                                                     expression("result", "mod", "n", "minus_one"));
  unavailable("open-maximum-overflow", parameter("N", "9223372036854775807") +
                                           literal("low", "9223372036854775807") +
                                           literal("high", "9223372036854775807") +
                                           range("N", "low", "high", "si64", false, true) +
                                           reference("result", "N", "si64", true));
  unavailable("open-minimum-overflow", parameter("N", "-9223372036854775808") +
                                           literal("low", "-9223372036854775808") +
                                           literal("high", "-9223372036854775808") +
                                           range("N", "low", "high", "si64", true, false) +
                                           reference("result", "N", "si64", true));
  expect("signed-endpoints-exact",
         bounded("-9223372036854775808", "9223372036854775807", "0") +
             reference("result", "N", "si64", true),
         std::numeric_limits<int64_t>::min(), std::numeric_limits<int64_t>::max());
  expect("unsigned-storage",
         parameter("N", "1", "i8") + literal("lo", "0", "i8") +
             literal("hi", "255", "i8", false, "i9") + range("N", "lo", "hi", "i8") +
             reference("result", "N", "i8", true),
         0, 255);
  unavailable("wide-unsigned-value-not-narrowed",
              "%result = \"nodal.constant\"() <{metadata = {probe = true}, value = "
              "18446744073709551615 : i65}> : () -> i64\n");
  unavailable("real-is-not-integer", literal("result", "1.0", "f64", true));
  unavailable("boolean-is-not-integer", literal("result", "1", "i1", true));
  unavailable("dynamic-origin-is-not-constant",
              literal("zero", "0") + "%result = \"nodal.dynamic_value\"(%zero) <{metadata = {probe "
                                     "= true}, origin = \"runtime\"}> : (si64) -> si64\n");

  std::string shared = literal("seed", "0");
  std::string previous = "seed";
  for (unsigned i = 0; i < 80; ++i) {
    std::string current = "v" + std::to_string(i);
    shared += expression(current, "add", previous, previous, "si64", i == 79);
    previous = current;
  }
  expect("eighty-level-shared-dag-without-expansion", shared, 0, 0);
  std::string deep = literal("seed", "0") + literal("zero", "0");
  previous = "seed";
  for (unsigned i = 0; i < 520; ++i) {
    std::string current = "v" + std::to_string(i);
    deep += expression(current, "add", previous, "zero", "si64", i == 519);
    previous = current;
  }
  unavailable("over-depth-fails-without-stack-exhaustion", deep);
  ++cases;
  failures += mlir::succeeded(nodal::inferParameterIntegerBounds(mlir::Value()));
  llvm::outs() << "INTEGER_BOUNDS_UNIT_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures == 0 ? 0 : 1;
}
