#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/BuiltinTypes.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/ADT/SmallString.h"
#include "llvm/Support/raw_ostream.h"

#include <cmath>
#include <optional>
#include <string>

namespace {

constexpr llvm::StringLiteral realType = "!nodal.quantity<\"real\", \"1\">";
constexpr llvm::StringLiteral integerType = "!nodal.quantity<\"integer\", \"1\">";

std::string literal(llvm::StringRef id, llvm::StringRef value, bool integer = false,
                    llvm::StringRef type = realType) {
  return "%" + id.str() + " = \"nodal." + (integer ? "analog_integer_literal" : "real_literal") +
         "\"() <{metadata = {}, value = " + value.str() + (integer ? " : i64" : " : f64") +
         "}> : () -> " + (integer ? integerType.str() : type.str()) + "\n";
}

std::string reference(llvm::StringRef id, llvm::StringRef parameter,
                      llvm::StringRef type = realType) {
  return "%" + id.str() + " = \"nodal.parameter_ref\"() <{metadata = {}, parameter = @" +
         parameter.str() + "}> : () -> " + type.str() + "\n";
}

std::string binary(llvm::StringRef id, llvm::StringRef operation, llvm::StringRef lhs,
                   llvm::StringRef rhs, llvm::StringRef lhsType = realType,
                   llvm::StringRef rhsType = realType, llvm::StringRef resultType = realType) {
  return "%" + id.str() + " = \"nodal.analog_" + operation.str() + "\"(%" + lhs.str() + ", %" +
         rhs.str() + ") <{metadata = {}}> : (" + lhsType.str() + ", " + rhsType.str() + ") -> " +
         resultType.str() + "\n";
}

std::string model(llvm::StringRef body) {
  return "module { \"nodal.module\"() <{metadata = {}, sym_name = \"Divisor\"}> ({\n"
         "\"nodal.parameter\"() <{default_value = 4.0 : f64, metadata = {}, parameter_kind = "
         "\"real\", sym_name = \"N\", type = f64, variability = \"symbolic\"}> : () -> ()\n"
         "\"nodal.parameter\"() <{default_value = 0.0 : f64, metadata = {}, parameter_kind = "
         "\"real\", sym_name = \"D\", type = f64, variability = \"symbolic\"}> : () -> ()\n"
         "\"nodal.parameter\"() <{default_value = -0.0 : f64, metadata = {}, parameter_kind = "
         "\"real\", sym_name = \"Z\", type = f64, variability = \"fixed\"}> : () -> ()\n"
         "\"nodal.analog\"() <{metadata = {}}> ({\n" +
         body.str() + "}) : () -> ()\n}) : () -> () }\n";
}

enum class Expected { Dynamic, RealConstant, IntegerConstant, ZeroDivisor, InvalidArithmetic };

bool check(mlir::MLIRContext &context, llvm::StringRef name, llvm::StringRef body,
           Expected expected, std::optional<double> real = std::nullopt,
           llvm::StringRef integer = {}) {
  std::string diagnostic;
  mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &message) {
    llvm::raw_string_ostream stream(diagnostic);
    message.print(stream);
    return mlir::success();
  });
  auto fail = [&](llvm::StringRef message) {
    llvm::errs() << "AnalogDivisorTest: " << name << ": " << message << '\n' << diagnostic << '\n';
    return false;
  };

  // Parse without verification so the evaluator and each verifier are tested independently.
  mlir::ParserConfig config(&context, false);
  auto parsed = mlir::parseSourceString<mlir::ModuleOp>(model(body), config);
  if (!parsed)
    return fail("fixture did not parse");
  mlir::Operation *division = nullptr;
  parsed->walk([&](mlir::Operation *operation) {
    if (operation->getName().getStringRef() == "nodal.analog_div")
      division = operation;
  });
  if (!division)
    return fail("missing division");

  auto evaluated = nodal::getAnalogConstantRealValue(division->getResult(0));
  const bool invalid = expected == Expected::ZeroDivisor || expected == Expected::InvalidArithmetic;
  if (mlir::failed(evaluated) != invalid)
    return fail("constant evaluator returned the wrong validity");
  if (!invalid && *evaluated != real)
    return fail("constant evaluator used a symbolic default or returned the wrong constant");

  auto expectVerification = [&](mlir::LogicalResult result) {
    if (expected != Expected::ZeroDivisor)
      return mlir::succeeded(result);
    return mlir::failed(result) && llvm::StringRef(diagnostic).contains("NODAL-ANALOG-DIVIDE-001");
  };
  diagnostic.clear();
  if (!expectVerification(nodal::verifyAnalogNumericOperation(division)))
    return fail("operation verifier returned the wrong result or diagnostic");
  diagnostic.clear();
  if (!expectVerification(nodal::verifyAnalogNumericModel(*parsed)))
    return fail("model verifier returned the wrong result or diagnostic");
  diagnostic.clear();
  if (!expectVerification(mlir::verify(*parsed)))
    return fail("native verifier returned the wrong result or diagnostic");

  diagnostic.clear();
  if (mlir::failed(nodal::foldAnalogNumericConstants(*parsed)) != invalid)
    return fail("constant folder returned the wrong validity");
  if (invalid) {
    llvm::StringRef code =
        expected == Expected::ZeroDivisor ? "NODAL-ANALOG-DIVIDE-001" : "NODAL-ANALOG-FOLD-001";
    if (!llvm::StringRef(diagnostic).contains(code))
      return fail("constant folder returned the wrong diagnostic");
  } else if (expected == Expected::Dynamic) {
    if (division->hasAttr("nodal.folded") || division->hasAttr("nodal.folded_value"))
      return fail("dynamic division was folded");
  } else if (expected == Expected::RealConstant) {
    auto folded = division->getAttrOfType<mlir::FloatAttr>("nodal.folded_value");
    if (!folded || !real || folded.getValueAsDouble() != *real)
      return fail("real division did not retain its folded value");
  } else {
    auto folded = division->getAttrOfType<mlir::IntegerAttr>("nodal.folded_value");
    llvm::SmallString<64> spelling;
    if (folded)
      folded.getValue().toString(spelling, 10, true);
    if (!folded || spelling != integer)
      return fail("integer division lost its exact folded value");
  }
  llvm::outs() << "ANALOG_DIVISOR_TEST_PASS " << name << '\n';
  return true;
}

bool checkMutation(mlir::MLIRContext &context, bool parameter) {
  const std::string body = literal("n", "6.0") +
                           (parameter ? reference("d", "D") : literal("d", "2.0")) +
                           binary("result", "div", "n", "d");
  auto parsed = mlir::parseSourceString<mlir::ModuleOp>(model(body), &context);
  if (!parsed)
    return false;
  mlir::Operation *division = nullptr;
  mlir::Operation *declaration = nullptr;
  parsed->walk([&](mlir::Operation *operation) {
    if (operation->getName().getStringRef() == "nodal.analog_div")
      division = operation;
    auto symbol = operation->getAttrOfType<mlir::StringAttr>("sym_name");
    if (symbol && symbol.getValue() == "D")
      declaration = operation;
  });
  if (!division || !declaration)
    return false;
  auto expected = [&](std::optional<double> value) {
    auto actual = nodal::getAnalogConstantRealValue(division->getResult(0));
    return mlir::succeeded(actual) && *actual == value;
  };
  if (!expected(parameter ? std::nullopt : std::optional<double>{3.0}) ||
      mlir::failed(nodal::foldAnalogNumericConstants(*parsed)))
    return false;

  mlir::Operation *divisor = division->getOperand(1).getDefiningOp();
  if (parameter)
    declaration->setAttr("variability", mlir::StringAttr::get(&context, "fixed"));
  else
    divisor->setAttr("value", mlir::FloatAttr::get(mlir::Float64Type::get(&context), 0.0));
  if (mlir::succeeded(nodal::getAnalogConstantRealValue(division->getResult(0))))
    return false;
  std::string diagnostic;
  mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &message) {
    llvm::raw_string_ostream stream(diagnostic);
    message.print(stream);
    return mlir::success();
  });
  for (unsigned repeat = 0; repeat != 2; ++repeat) {
    diagnostic.clear();
    if (mlir::succeeded(nodal::foldAnalogNumericConstants(*parsed)) ||
        !llvm::StringRef(diagnostic).contains("NODAL-ANALOG-DIVIDE-001"))
      return false;
  }

  if (parameter)
    declaration->setAttr("variability", mlir::StringAttr::get(&context, "symbolic"));
  else
    divisor->setAttr("value", mlir::FloatAttr::get(mlir::Float64Type::get(&context), 3.0));
  if (!expected(parameter ? std::nullopt : std::optional<double>{2.0}) ||
      mlir::failed(nodal::foldAnalogNumericConstants(*parsed)))
    return false;
  auto folded = division->getAttrOfType<mlir::FloatAttr>("nodal.folded_value");
  const bool correct = parameter ? !folded : folded && folded.getValueAsDouble() == 2.0;
  if (correct)
    llvm::outs() << "ANALOG_DIVISOR_TEST_PASS "
                 << (parameter ? "parameter-mutation-cache-lifetime"
                               : "literal-mutation-cache-lifetime")
                 << '\n';
  return correct;
}

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();
  unsigned failures = 0;
  unsigned cases = 0;
  auto expect = [&](llvm::StringRef name, llvm::StringRef body, Expected expected,
                    std::optional<double> real = std::nullopt, llvm::StringRef integer = {}) {
    ++cases;
    failures += !check(context, name, body, expected, real, integer);
  };
  const std::string dynamic = reference("n", "N");
  const std::string divide = binary("result", "div", "n", "d");
  for (llvm::StringRef zero : {"0.0", "-0.0"}) {
    expect("dynamic-numerator-zero-" + zero.str(), dynamic + literal("d", zero) + divide,
           Expected::ZeroDivisor);
    expect("constant-numerator-zero-" + zero.str(),
           literal("n", "4.0") + literal("d", zero) + divide, Expected::ZeroDivisor);
  }
  expect("negated-positive-zero",
         dynamic + literal("zero", "0.0") +
             "%d = \"nodal.analog_neg\"(%zero) <{metadata = {}}> : (" + realType.str() + ") -> " +
             realType.str() + "\n" + divide,
         Expected::ZeroDivisor);
  expect("constant-expression-zero",
         dynamic + literal("two", "2.0") + binary("d", "sub", "two", "two") + divide,
         Expected::ZeroDivisor);
  expect("fixed-parameter-zero", dynamic + reference("d", "Z") + divide, Expected::ZeroDivisor);
  expect("mixed-integer-zero",
         dynamic + literal("d", "0", true) +
             binary("result", "div", "n", "d", realType, integerType),
         Expected::ZeroDivisor);
  expect("integer-zero",
         literal("n", "4", true) + literal("d", "0", true) +
             binary("result", "div", "n", "d", integerType, integerType, integerType),
         Expected::ZeroDivisor);
  expect("legacy-real-zero",
         reference("n", "N", "f64") + literal("d", "-0.0", false, "f64") +
             binary("result", "div", "n", "d", "f64", "f64", "f64"),
         Expected::ZeroDivisor);
  expect("dynamic-numerator-nonzero", dynamic + literal("d", "2.0") + divide, Expected::Dynamic);
  expect("dynamic-numerator-negative", dynamic + literal("d", "-2.0") + divide, Expected::Dynamic);
  expect("symbolic-zero-default", dynamic + reference("d", "D") + divide, Expected::Dynamic);
  expect("constant-numerator-symbolic-zero-default",
         literal("n", "4.0") + reference("d", "D") + divide, Expected::Dynamic);
  expect("pure-real-constant", literal("n", "6.0") + literal("d", "2.0") + divide,
         Expected::RealConstant, 3.0);
  expect("exact-integer-constant",
         literal("n", "6", true) + literal("d", "2", true) +
             binary("result", "div", "n", "d", integerType, integerType, integerType),
         Expected::IntegerConstant, std::nullopt, "3");
  expect("inexact-integer-promotes-real",
         literal("n", "5", true) + literal("d", "2", true) +
             binary("result", "div", "n", "d", integerType, integerType),
         Expected::RealConstant, 2.5);
  expect("exact-integer-division-widens",
         literal("n", "-9223372036854775808", true) + literal("d", "-1", true) +
             binary("result", "div", "n", "d", integerType, integerType, integerType),
         Expected::IntegerConstant, std::nullopt, "9223372036854775808");
  expect("real-overflow-still-rejected", literal("n", "1.0e308") + literal("d", "0.5") + divide,
         Expected::InvalidArithmetic);
  std::string diamond = literal("x0", "1.0") + literal("d", "2.0");
  for (unsigned depth = 1; depth <= 64; ++depth) {
    std::string previous = "x" + std::to_string(depth - 1);
    diamond += binary("x" + std::to_string(depth), "add", previous, previous);
  }
  expect("shared-constant-diamond", diamond + binary("result", "div", "x64", "d"),
         Expected::RealConstant, std::ldexp(1.0, 63));
  for (bool parameter : {false, true}) {
    ++cases;
    if (!checkMutation(context, parameter)) {
      llvm::errs() << "AnalogDivisorTest: cache survived an authored IR mutation\n";
      ++failures;
    }
  }
  llvm::outs() << "AnalogDivisorTest: " << cases << " cases, " << failures << " failures\n";
  return failures ? 1 : 0;
}
