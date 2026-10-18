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

std::string model(llvm::StringRef body, llvm::StringRef declarations = {},
                  llvm::StringRef units = {}) {
  return "module { " + units.str() +
         "\"nodal.module\"() <{metadata = {}, sym_name = \"Divisor\"}> ({\n"
         "\"nodal.parameter\"() <{default_value = 4.0 : f64, metadata = {}, parameter_kind = "
         "\"real\", sym_name = \"N\", type = f64, variability = \"symbolic\"}> : () -> ()\n"
         "\"nodal.parameter\"() <{default_value = 0.0 : f64, metadata = {}, parameter_kind = "
         "\"real\", sym_name = \"D\", type = f64, variability = \"symbolic\"}> : () -> ()\n"
         "\"nodal.parameter\"() <{default_value = -0.0 : f64, metadata = {}, parameter_kind = "
         "\"real\", sym_name = \"Z\", type = f64, variability = \"fixed\"}> : () -> ()\n" +
         declarations.str() + "\"nodal.analog\"() <{metadata = {}}> ({\n" + body.str() +
         "}) : () -> ()\n}) : () -> () }\n";
}

enum class Expected { Dynamic, RealConstant, IntegerConstant, ZeroDivisor, InvalidArithmetic };

bool check(mlir::MLIRContext &context, llvm::StringRef name, llvm::StringRef body,
           Expected expected, std::optional<double> real = std::nullopt,
           llvm::StringRef integer = {}, llvm::StringRef declarations = {},
           llvm::StringRef units = {}) {
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
  auto parsed = mlir::parseSourceString<mlir::ModuleOp>(model(body, declarations, units), config);
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

std::string fixedParameter(llvm::StringRef name, llvm::StringRef value, llvm::StringRef unit = {}) {
  return "\"nodal.parameter\"() <{default_value = " + value.str() +
         " : f64, metadata = {}, parameter_kind = \"real\", sym_name = \"" + name.str() +
         "\", type = f64, variability = \"fixed\"" +
         (unit.empty() ? "" : ", unit = @" + unit.str()) + "}> : () -> ()\n";
}

std::string parameterValue(llvm::StringRef name, llvm::StringRef value) {
  return "\"nodal.parameter_value\"(%" + value.str() + ") <{metadata = {}, parameter = @" +
         name.str() + "}> : (f64) -> ()\n";
}

std::string constantLiteral(llvm::StringRef name, llvm::StringRef value) {
  return "%" + name.str() + " = \"nodal.const_literal\"() <{metadata = {}, spelling = \"" +
         value.str() + "\", value = " + value.str() + " : f64}> : () -> f64\n";
}

bool checkParameterExpression(mlir::MLIRContext &context, llvm::StringRef name,
                              llvm::StringRef declarations, std::optional<double> value,
                              bool invalid = false, llvm::StringRef units = {},
                              llvm::StringRef type = realType) {
  const std::string body =
      literal("n", "6.0") + reference("d", "F", type) +
      binary("result", "div", "n", "d", realType, type,
             type == realType ? realType : "!nodal.quantity<\"real\", \"resistance^-1\">");
  mlir::ParserConfig config(&context, false);
  auto parsed = mlir::parseSourceString<mlir::ModuleOp>(model(body, declarations, units), config);
  if (!parsed)
    return false;
  mlir::Operation *division = nullptr;
  parsed->walk([&](mlir::Operation *operation) {
    if (operation->getName().getStringRef() == "nodal.analog_div")
      division = operation;
  });
  if (!division)
    return false;
  auto direct = nodal::getAnalogConstantRealValue(division->getOperand(1));
  if (mlir::failed(direct) != invalid || (!invalid && *direct != value)) {
    llvm::errs() << "AnalogDivisorTest: " << name << ": direct parameter query is incorrect\n";
    return false;
  }
  if (invalid) {
    std::string diagnostic;
    mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &message) {
      llvm::raw_string_ostream stream(diagnostic);
      message.print(stream);
      return mlir::success();
    });
    if (mlir::succeeded(nodal::getAnalogConstantRealValue(division->getResult(0))) ||
        mlir::succeeded(nodal::foldAnalogNumericConstants(*parsed)) ||
        !llvm::StringRef(diagnostic).contains("NODAL-ANALOG-FOLD-001"))
      return false;
    llvm::outs() << "ANALOG_DIVISOR_TEST_PASS " << name << '\n';
    return true;
  }
  const Expected expected = !value          ? Expected::Dynamic
                            : *value == 0.0 ? Expected::ZeroDivisor
                                            : Expected::RealConstant;
  return check(context, name, body, expected,
               value && *value != 0.0 ? std::optional<double>{6.0 / *value} : std::nullopt, {},
               declarations, units);
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

bool checkDependencyMutation(mlir::MLIRContext &context, llvm::StringRef declarations) {
  auto parsed = mlir::parseSourceString<mlir::ModuleOp>(
      model(literal("n", "6.0") + reference("d", "F") + binary("result", "div", "n", "d"),
            declarations),
      &context);
  if (!parsed)
    return false;
  mlir::Operation *division = nullptr;
  mlir::Operation *dependency = nullptr;
  parsed->walk([&](mlir::Operation *operation) {
    if (operation->getName().getStringRef() == "nodal.analog_div")
      division = operation;
    auto symbol = operation->getAttrOfType<mlir::StringAttr>("sym_name");
    if (symbol && symbol.getValue() == "N")
      dependency = operation;
  });
  if (!division || !dependency)
    return false;
  for (bool fixed : {false, true, false}) {
    dependency->setAttr("variability",
                        mlir::StringAttr::get(&context, fixed ? "fixed" : "symbolic"));
    auto evaluated = nodal::getAnalogConstantRealValue(division->getResult(0));
    if (mlir::failed(evaluated) ||
        *evaluated != (fixed ? std::optional<double>{1.5} : std::nullopt) ||
        mlir::failed(nodal::foldAnalogNumericConstants(*parsed)))
      return false;
    auto folded = division->getAttrOfType<mlir::FloatAttr>("nodal.folded_value");
    if (fixed ? !folded || folded.getValueAsDouble() != 1.5 : static_cast<bool>(folded))
      return false;
  }
  llvm::outs() << "ANALOG_DIVISOR_TEST_PASS fixed-expression-dependency-mutation\n";
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
  auto expectParameter = [&](llvm::StringRef name, llvm::StringRef declarations,
                             std::optional<double> value, bool invalid = false,
                             llvm::StringRef units = {}, llvm::StringRef type = realType) {
    ++cases;
    if (!checkParameterExpression(context, name, declarations, value, invalid, units, type)) {
      llvm::errs() << "AnalogDivisorTest: " << name << ": canonical parameter expression failed\n";
      ++failures;
    }
  };
  const std::string dependent = fixedParameter("F", "4.0") +
                                "%dependency = \"nodal.const_parameter_ref\"() "
                                "<{metadata = {}, parameter = @N}> : () -> f64\n" +
                                parameterValue("F", "dependency");
  const std::string range = constantLiteral("lower", "0.0") + constantLiteral("upper", "8.0") +
                            "\"nodal.parameter_constraint\"(%lower, %upper) "
                            "<{constraint_kind = \"range\", lower_inclusive = true, metadata = "
                            "{}, parameter = @N, upper_inclusive = true}> : (f64, f64) -> ()\n";
  expectParameter("fixed-symbolic-expression-remains-dynamic", dependent + range, std::nullopt);
  expectParameter("fixed-symbolic-expression-without-range", dependent, std::nullopt);
  expectParameter("fixed-chain-retains-symbolic-dependency",
                  fixedParameter("G", "4.0") +
                      "%dependency = \"nodal.const_parameter_ref\"() "
                      "<{metadata = {}, parameter = @N}> : () -> f64\n" +
                      parameterValue("G", "dependency") + fixedParameter("F", "4.0") +
                      "%intermediate = \"nodal.const_parameter_ref\"() "
                      "<{metadata = {}, parameter = @G}> : () -> f64\n" +
                      parameterValue("F", "intermediate"),
                  std::nullopt);
  expectParameter("fixed-zero-default-symbolic-expression",
                  fixedParameter("F", "0.0") +
                      "%dependency = \"nodal.const_parameter_ref\"() "
                      "<{metadata = {}, parameter = @D}> : () -> f64\n" +
                      parameterValue("F", "dependency"),
                  std::nullopt);
  expectParameter("fixed-literal-default-still-folds", fixedParameter("F", "2.0"), 2.0);
  expectParameter("fixed-canonical-literal-still-folds",
                  fixedParameter("F", "2.0") + constantLiteral("literal", "2.0") +
                      parameterValue("F", "literal"),
                  2.0);
  const std::string expression =
      constantLiteral("four", "4.0") + constantLiteral("two", "2.0") +
      "%expression = \"nodal.const_expr\"(%four, %two) "
      "<{metadata = {}, operator_name = \"div\"}> : (f64, f64) -> f64\n" +
      parameterValue("F", "expression");
  expectParameter("fixed-canonical-arithmetic-still-folds", fixedParameter("F", "2.0") + expression,
                  2.0);
  expectParameter("fixed-expression-metadata-mismatch-rejected",
                  fixedParameter("F", "1.0") + expression, std::nullopt, true);
  expectParameter("fixed-canonical-invalid-arithmetic-rejected",
                  fixedParameter("F", "1.0") + constantLiteral("one", "1.0") +
                      constantLiteral("zero", "0.0") +
                      "%expression = \"nodal.const_expr\"(%one, %zero) "
                      "<{metadata = {}, operator_name = \"div\"}> : (f64, f64) -> f64\n" +
                      parameterValue("F", "expression"),
                  std::nullopt, true);
  const std::string units =
      "\"nodal.unit\"() <{dimension = \"resistance\", metadata = {}, native_suffix = \"k\", "
      "scale = 1.0e3 : f64, sym_name = \"kOhm\", symbol = \"kOhm\"}> : () -> ()\n";
  expectParameter("fixed-canonical-expression-unit-scale-once",
                  fixedParameter("F", "2.0", "kOhm") + expression, 2000.0, false, units,
                  "!nodal.quantity<\"real\", \"resistance\">");
  expectParameter("fixed-physical-expression-unit-scale-once",
                  fixedParameter("F", "2.0", "kOhm") +
                      "%physical = \"nodal.const_literal\"() <{metadata = {}, spelling = \"2k\", "
                      "unit = @kOhm, value = 2.0 : f64}> : () -> f64\n" +
                      parameterValue("F", "physical"),
                  2000.0, false, units, "!nodal.quantity<\"real\", \"resistance\">");
  ++cases;
  failures += !check(
      context, "fixed-integer-expression-still-folds",
      literal("n", "6", true) + reference("d", "I", integerType) +
          binary("result", "div", "n", "d", integerType, integerType, integerType),
      Expected::IntegerConstant, std::nullopt, "3",
      "\"nodal.parameter\"() <{default_value = 2 : si64, metadata = {}, parameter_kind = "
      "\"integer\", sym_name = \"I\", type = si64, variability = \"fixed\"}> : () -> ()\n"
      "%six = \"nodal.const_literal\"() <{metadata = {}, spelling = \"6\", value = 6 : si64}> : () "
      "-> si64\n"
      "%three = \"nodal.const_literal\"() <{metadata = {}, spelling = \"3\", value = 3 : si64}> : "
      "() -> si64\n"
      "%value = \"nodal.const_expr\"(%six, %three) <{metadata = {}, operator_name = \"div\"}> : "
      "(si64, si64) -> si64\n"
      "\"nodal.parameter_value\"(%value) <{metadata = {}, parameter = @I}> : (si64) -> ()\n");
  ++cases;
  failures +=
      !check(context, "fixed-boolean-expression-still-folds",
             literal("n", "6.0") + literal("two", "2.0") + literal("zero", "0.0") +
                 reference("condition", "B", "i1") +
                 "%d = \"nodal.analog_select\"(%condition, %two, %zero) <{metadata = {}}> : (i1, " +
                 realType.str() + ", " + realType.str() + ") -> " + realType.str() + "\n" + divide,
             Expected::RealConstant, 3.0, {},
             "\"nodal.parameter\"() <{default_value = true, metadata = {}, parameter_kind = "
             "\"boolean\", sym_name = \"B\", type = i1, variability = \"fixed\"}> : () -> ()\n"
             "%false = \"nodal.const_literal\"() <{metadata = {}, spelling = \"0\", value = "
             "false}> : () -> i1\n"
             "%true = \"nodal.const_expr\"(%false) <{metadata = {}, operator_name = \"not\"}> : "
             "(i1) -> i1\n"
             "\"nodal.parameter_value\"(%true) <{metadata = {}, parameter = @B}> : (i1) -> ()\n");
  ++cases;
  if (!checkDependencyMutation(context, dependent)) {
    llvm::errs() << "AnalogDivisorTest: fixed expression cache survived dependency mutation\n";
    ++failures;
  }
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
