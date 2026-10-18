#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Backend/AnalogEventBackend.h"
#include "nodal/Backend/AnalogUserFunctionBackend.h"
#include "nodal/Dialect/Nodal/AnalogUserFunctions.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/ADT/StringMap.h"
#include "llvm/Support/raw_ostream.h"

#include <limits>
#include <string>

namespace {
constexpr llvm::StringLiteral realType = "!nodal.quantity<\"real\", \"1\">";
constexpr llvm::StringLiteral integerType = "!nodal.quantity<\"integer\", \"1\">";
constexpr llvm::StringLiteral marker = "nodal.canonical.dead_local";

std::string value(llvm::StringRef id, llvm::StringRef kind, llvm::StringRef operands = {},
                  llvm::StringRef operandTypes = {}, llvm::StringRef extra = {},
                  llvm::StringRef resultType = realType) {
  return "%" + id.str() + " = \"nodal.analog_function_value\"(" + operands.str() + ") <{kind = \"" +
         kind.str() + "\", metadata = {semantic_path = \"Dead." + id.str() + "\"}" + extra.str() +
         "}> : (" + operandTypes.str() + ") -> " + resultType.str() + " loc(\"Dead.scala\":12:3)\n";
}
std::string input() {
  return value("x", "input", "", "", ", name = \"signal\", argument_index = 0 : i64");
}
std::string literal() { return value("one", "literal", "", "", ", value = 1.0 : f64"); }
std::string local(llvm::StringRef id, llvm::StringRef initializer = "%one",
                  llvm::StringRef type = realType) {
  return value(id, "local", initializer, type, ", name = \"" + id.str() + "\"", type);
}
std::string returned(llvm::StringRef id = "x") {
  return "\"nodal.analog_function_return\"(%" + id.str() + ") <{metadata = {}}> : (" +
         realType.str() + ") -> ()\n";
}
std::string function(llvm::StringRef name, llvm::StringRef body) {
  return "\"nodal.analog_user_function\"() <{sym_name = \"" + name.str() +
         "\", return_type = " + realType.str() +
         ", contract_version = \"1\", metadata = {semantic_path = \"Dead." + name.str() +
         "\"}}> ({\n" + body.str() + "}) : () -> ()\n";
}
std::string model(llvm::StringRef body, bool extraState = false) {
  std::string state = extraState ? R"mlir(
"nodal.analog"() <{metadata = {}}> ({
  "nodal.analog_procedure"() <{owner = "Dead", metadata = {}}> ({
    %state = "nodal.analog_variable"() <{identity = "Dead.held", owner = "Dead", declaration_order = 0 : i64, initialized = true, initializer_value = "1.0", initializer_kind = "real", initializer_dimension = "1", initializer_reads = [], metadata = {semantic_path = "Dead.held"}}> : () -> !nodal.variable<"real", "1">
  }) : () -> ()
}) : () -> ()
)mlir"
                                 : "";
  return "module { \"nodal.module\"() <{sym_name = \"Dead\", metadata = {}}> ({\n" +
         function("mainSignal", body) + function("helperSignal", input() + returned()) + state +
         "}) : () -> () }\n";
}
std::string body() {
  return input() + literal() + local("spare") +
         value("integer", "literal", "", "", ", value = -2147483648 : i32", integerType) +
         local("spareCount", "%integer", integerType) + local("kept") + local("dynamic", "%x") +
         "%call = \"nodal.analog_user_call\"(%x) <{callee = @helperSignal, contract_version = "
         "\"1\", metadata = {}}> : (" +
         realType.str() + ") -> " + realType.str() + "\n" + local("called", "%call") +
         value("sum", "add", "%one, %one", realType.str() + ", " + realType.str()) +
         local("expression", "%sum") + value("guard", "literal", "", "", ", value = true", "i1") +
         value("selected", "select", "%guard, %one, %x",
               "i1, " + realType.str() + ", " + realType.str()) +
         local("conditional", "%selected") + returned("kept");
}
std::string print(mlir::Operation *op) {
  std::string result;
  llvm::raw_string_ostream output(result);
  op->print(output);
  return result;
}
mlir::Operation *findOperation(mlir::Operation *root, llvm::StringRef operation,
                               llvm::StringRef name = {}) {
  mlir::Operation *result = nullptr;
  root->walk([&](mlir::Operation *op) {
    if (op->getName().getStringRef() != operation)
      return;
    auto actualName = op->getAttrOfType<mlir::StringAttr>("name");
    if (name.empty() || (actualName && actualName.getValue() == name))
      result = op;
  });
  return result;
}
mlir::FailureOr<std::string> render(mlir::Operation *root) {
  std::string output;
  llvm::raw_string_ostream stream(output);
  if (mlir::failed(nodal::renderAnalogUserFunctions(findOperation(root, "nodal.module"), stream)))
    return mlir::failure();
  return output;
}
bool reparse(llvm::StringRef output) {
  llvm::StringMap<unsigned> functions;
  while (!(output = output.ltrim()).empty()) {
    auto parsed = nodal::reparseAnalogUserFunction(output, functions);
    if (mlir::failed(parsed) || *parsed == 0)
      return false;
    output = output.drop_front(*parsed);
  }
  return functions.size() == 2;
}
} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();
  std::string diagnostic;
  mlir::ScopedDiagnosticHandler diagnostics(&context, [&](mlir::Diagnostic &message) {
    llvm::raw_string_ostream stream(diagnostic);
    message.print(stream);
    return mlir::success();
  });
  unsigned cases = 0, failures = 0;
  auto expect = [&](bool valid, llvm::StringRef name) {
    ++cases;
    if (!valid) {
      ++failures;
      llvm::errs() << "AnalogDeadLocalTest: " << name << '\n' << diagnostic << '\n';
    }
    diagnostic.clear();
  };
  mlir::ParserConfig config(&context, false);
  auto parsed = mlir::parseSourceString<mlir::ModuleOp>(model(body(), true), config);
  if (!parsed) {
    llvm::errs() << diagnostic;
    return 1;
  }
  expect(mlir::succeeded(mlir::verify(*parsed)), "original complete model verifies");
  const std::string original = print(*parsed);
  auto before = render(*parsed);
  expect(mlir::succeeded(before) && llvm::StringRef(*before).contains("real spare;") &&
             llvm::StringRef(*before).contains("spare = 1.0;") && reparse(*before),
         "unmarked F41 declarations and assignments remain unchanged and parse");
  auto *state = findOperation(*parsed, "nodal.analog_variable");
  const std::string stateBefore = print(state);
  auto evidence = nodal::normalizeAnalogDeadLocals(*parsed);
  expect(mlir::succeeded(evidence) && evidence->size() == 2,
         "only direct-literal unused Real/Integer locals qualify");
  if (mlir::failed(evidence) || evidence->size() != 2)
    return 1;
  for (const auto &record : *evidence) {
    expect(record.local->getOperand(0).getDefiningOp() == record.initializer &&
               record.local->getAttrOfType<mlir::DictionaryAttr>("metadata") &&
               llvm::isa<mlir::FileLineColLoc>(record.local->getLoc()) &&
               record.local->getResult(0).use_empty(),
           "evidence retains exact original local/initializer and source provenance");
  }
  expect(mlir::succeeded(mlir::verify(*parsed)), "normalized original model verifies");
  auto after = render(*parsed);
  expect(mlir::succeeded(after) && !llvm::StringRef(*after).contains("spare") &&
             llvm::StringRef(*after).contains("kept = 1.0;") &&
             llvm::StringRef(*after).contains("dynamic = signal;") &&
             llvm::StringRef(*after).contains("called = helperSignal(signal);") &&
             llvm::StringRef(*after).contains("expression = (1.0 + 1.0);") &&
             llvm::StringRef(*after).contains("conditional = (1 ? 1.0 : signal);") &&
             reparse(*after),
         "target elides proven locals only and independent parser accepts both functions");
  const std::string normalized = print(*parsed);
  auto again = nodal::normalizeAnalogDeadLocals(*parsed);
  auto replay = render(*parsed);
  expect(mlir::succeeded(again) && again->size() == evidence->size() &&
             print(*parsed) == normalized && mlir::succeeded(replay) && mlir::succeeded(after) &&
             *replay == *after,
         "normalization and output are deterministic and idempotent");
  expect(print(state) == stateBefore, "mutable analog state is unchanged");
  for (const auto &record : *evidence)
    record.local->removeAttr(marker);
  expect(print(*parsed) == original,
         "all original SSA, names, metadata and source locations survive");

  for (llvm::StringRef target :
       {"spare", "kept", "dynamic", "called", "expression", "conditional", "literal", "input",
        "call", "return", "function", "module", "state"}) {
    auto mutated = mlir::parseSourceString<mlir::ModuleOp>(model(body(), true), config);
    mlir::Operation *op = nullptr;
    if (target == "literal" || target == "input") {
      mutated->walk([&](mlir::Operation *candidate) {
        auto kind = candidate->getAttrOfType<mlir::StringAttr>("kind");
        if (!op && kind && kind.getValue() == target)
          op = candidate;
      });
    } else if (target == "call")
      op = findOperation(*mutated, "nodal.analog_user_call");
    else if (target == "return")
      op = findOperation(*mutated, "nodal.analog_function_return");
    else if (target == "function")
      op = findOperation(*mutated, "nodal.analog_user_function");
    else if (target == "module")
      op = findOperation(*mutated, "nodal.module");
    else if (target == "state")
      op = findOperation(*mutated, "nodal.analog_variable");
    else
      op = findOperation(*mutated, "nodal.analog_function_value", target);
    if (!op)
      return 1;
    // False/non-Boolean markers never mean a harmless disabled request.
    for (mlir::Attribute attr : {mlir::Attribute(mlir::BoolAttr::get(&context, false)),
                                 mlir::Attribute(mlir::StringAttr::get(&context, "true")),
                                 mlir::Attribute(mlir::BoolAttr::get(&context, true))}) {
      if (target == "spare" && attr == mlir::BoolAttr::get(&context, true))
        continue;
      op->setAttr(marker, attr);
      const std::string snapshot = print(*mutated);
      expect(mlir::failed(nodal::verifyAnalogDeadLocalMarkers(*mutated)) &&
                 mlir::failed(mlir::verify(*mutated)) && mlir::failed(render(*mutated)) &&
                 mlir::failed(nodal::normalizeAnalogDeadLocals(*mutated)) &&
                 print(*mutated) == snapshot,
             "forged marker rejected without mutation: " + target.str());
    }
  }

  for (bool foreignOperation : {false, true}) {
    auto mutated = mlir::parseSourceString<mlir::ModuleOp>(model(body()), config);
    mlir::Operation *target = mutated->getOperation();
    if (foreignOperation) {
      mlir::OperationState state(mutated->getLoc(), "builtin.unrealized_conversion_cast");
      state.addTypes(mlir::IntegerType::get(&context, 1));
      target = mlir::Operation::create(state);
      mutated->getBody()->push_back(target);
    }
    expect(mlir::succeeded(mlir::verify(*mutated)), "unmarked foreign/root operation is valid");
    target->setAttr(marker, mlir::BoolAttr::get(&context, true));
    const std::string snapshot = print(*mutated);
    expect(mlir::failed(nodal::verifyAnalogDeadLocalMarker(target)) &&
               mlir::failed(nodal::verifyAnalogDeadLocalMarkers(*mutated)) &&
               mlir::failed(mlir::verify(*mutated)) &&
               mlir::failed(nodal::normalizeAnalogDeadLocals(*mutated)) &&
               print(*mutated) == snapshot,
           foreignOperation ? "foreign operation outside nodal module rejects marker"
                            : "outer builtin module rejects marker");
  }

  const std::string pair = realType.str() + ", " + realType.str();
  for (const std::string &invalid :
       {input() + literal() + local("spare") +
            value("zero", "literal", "", "", ", value = 0.0 : f64") +
            value("bad", "div", "%x, %zero", pair) + returned(),
        input() + literal() + local("spare") +
            value("huge", "literal", "", "", ", value = 1.0e308 : f64") +
            value("bad", "mul", "%huge, %huge", pair) + returned(),
        input() + literal() + local("spare") +
            value("bad", "math", "%one", realType, ", function_id = \"acos\"") +
            value("two", "literal", "", "", ", value = 2.0 : f64") +
            value("domain", "math", "%two", realType, ", function_id = \"acos\"") + returned()}) {
    auto bad = mlir::parseSourceString<mlir::ModuleOp>(model(invalid), config);
    const std::string snapshot = print(*bad);
    expect(mlir::failed(nodal::normalizeAnalogDeadLocals(*bad)) && print(*bad) == snapshot,
           "invalid unused arithmetic/domain rejects before any marker mutation");
  }
  auto nonfinite = mlir::parseSourceString<mlir::ModuleOp>(model(body()), config);
  auto *spare = findOperation(*nonfinite, "nodal.analog_function_value", "spare");
  spare->getOperand(0).getDefiningOp()->setAttr(
      "value", mlir::FloatAttr::get(mlir::Float64Type::get(&context),
                                    std::numeric_limits<double>::infinity()));
  spare->setAttr(marker, mlir::BoolAttr::get(&context, true));
  expect(mlir::failed(nodal::verifyAnalogDeadLocalMarkers(*nonfinite)) &&
             mlir::failed(nodal::normalizeAnalogDeadLocals(*nonfinite)),
         "nonfinite literal cannot become dead-local proof");

  auto mismatch = mlir::parseSourceString<mlir::ModuleOp>(model(body()), config);
  spare = findOperation(*mismatch, "nodal.analog_function_value", "spare");
  spare->getResult(0).setType(findOperation(*mismatch, "nodal.analog_function_value", "spareCount")
                                  ->getResult(0)
                                  .getType());
  spare->setAttr(marker, mlir::BoolAttr::get(&context, true));
  expect(mlir::failed(nodal::verifyAnalogDeadLocalMarkers(*mismatch)) &&
             mlir::failed(mlir::verify(*mismatch)) && mlir::failed(render(*mismatch)),
         "marker cannot authorize a mismatched local/initializer type");

  auto deadBranch = mlir::parseSourceString<mlir::ModuleOp>(
      model(input() + literal() + local("spare") +
            value("zero", "literal", "", "", ", value = 0.0 : f64") +
            value("bad", "div", "%one, %zero", pair) +
            value("guard", "literal", "", "", ", value = true", "i1") +
            value("selected", "select", "%guard, %one, %bad", "i1, " + pair) +
            returned("selected")),
      config);
  const std::string deadBranchBefore = print(*deadBranch);
  expect(mlir::failed(nodal::normalizeAnalogDeadLocals(*deadBranch)) &&
             print(*deadBranch) == deadBranchBefore,
         "constant dead branch cannot conceal invalid arithmetic");

  auto subtree = mlir::parseSourceString<mlir::ModuleOp>(
      model(input() + literal() + local("spare") +
            value("zero", "literal", "", "", ", value = 0.0 : f64") +
            value("bad", "div", "%one, %zero", pair) + returned()),
      config);
  const std::string subtreeBefore = print(*subtree);
  expect(mlir::failed(nodal::normalizeAnalogDeadLocals(
             findOperation(*subtree, "nodal.analog_function_value", "spare"))) &&
             print(*subtree) == subtreeBefore,
         "subtree normalization still validates the complete owning function");

  auto reserved = mlir::parseSourceString<mlir::ModuleOp>(model(body()), config);
  spare = findOperation(*reserved, "nodal.analog_function_value", "spare");
  spare->setAttr("name", mlir::StringAttr::get(&context, "input"));
  expect(mlir::succeeded(nodal::normalizeAnalogDeadLocals(*reserved)) &&
             mlir::failed(render(*reserved)),
         "elision never bypasses original reserved target-name checks");
  llvm::outs() << "AnalogDeadLocalTest: " << cases << " cases, " << failures << " failures\n";
  return failures ? 1 : 0;
}
