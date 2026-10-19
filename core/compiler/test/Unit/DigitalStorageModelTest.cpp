#include "nodal/Dialect/Nodal/DigitalStorageModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/raw_ostream.h"

#include <string>

using namespace mlir;
namespace {
std::string domain(StringRef name = "clock") {
  return "\"nodal.domain\"() <{sym_name = \"" + name.str() +
         "\", edge = \"rising\", reset_policy = \"sync\", metadata = {}}> : () -> ()\n";
}
std::string model(StringRef body) {
  return "module { \"nodal.module\"() <{metadata = {}, sym_name = \"Fixture\"}> ({\n" + body.str() +
         "}) : () -> () "
         "\"nodal.enum\"() <{sym_name = \"Mode\", underlying_type = !nodal.uint<2>, "
         "encoding = \"custom\", metadata = {abi_version = 1 : i64, semantic_type = \"Mode\", "
         "abi_hash = \"df5b4a51f3fade96f1ab8a106bbcb0114b5d4601e4e2c6aebcf38763dd61e66a\"}}> ({"
         "\"nodal.enum_case\"() <{sym_name = \"Idle\", value = 0 : i64, "
         "metadata = {case_id = \"Mode.Idle\"}}> : () -> () "
         "\"nodal.enum_case\"() <{sym_name = \"Run\", value = 1 : i64, "
         "metadata = {case_id = \"Mode.Run\"}}> : () -> () "
         "}) : () -> () }\n";
}
std::string storage(StringRef type, StringRef name = "wire", StringRef kind = "wire",
                    StringRef initial = "", StringRef initialType = "", StringRef domainName = "") {
  return "%" + name.str() + " = \"nodal.digital_storage\"(" + initial.str() + ") <{name = \"" +
         name.str() + "\", kind = \"" + kind.str() + "\", metadata = {}" +
         (domainName.empty() ? "" : ", domain = @" + domainName.str()) + "}> : (" +
         initialType.str() + ") -> " + type.str() + "\n";
}
std::string memory(StringRef element, StringRef depth = "16", StringRef latency = "1",
                   StringRef collision = "old_data", StringRef ordering = "ordered",
                   StringRef domainName = "clock") {
  return "\"nodal.memory\"() <{sym_name = \"memory\", element_type = " + element.str() +
         ", depth = \"" + depth.str() + "\", read_latency = " + latency.str() +
         " : i64, read_under_write = \"" + collision.str() + "\", ordering = \"" + ordering.str() +
         "\", domain = @" + domainName.str() + ", metadata = {}}> : () -> ()\n";
}
std::string literal(StringRef type, StringRef value = "0", StringRef name = "initial") {
  return "%" + name.str() + " = \"nodal.const_literal\"() <{metadata = {}, spelling = \"" +
         value.str() + "\", value = " + value.str() + " : i64}> : () -> " + type.str() + "\n";
}
std::string port(StringRef type, StringRef name = "source", StringRef direction = "input") {
  return "\"nodal.port\"() <{sym_name = \"" + name.str() + "\", type = " + type.str() +
         ", direction = \"" + direction.str() +
         "\", domain = @none, metadata = {}}> : () -> ()\n%" + name.str() +
         " = \"nodal.port_value\"() <{port = @" + name.str() + ", metadata = {}}> : () -> " +
         type.str() + "\n";
}
std::string assign(StringRef targetType, StringRef sourceType = "!nodal.sint<8>",
                   StringRef target = "wire", StringRef source = "source",
                   StringRef kind = "continuous") {
  return "\"nodal.digital_assign\"(%" + target.str() + ", %" + source.str() + ") <{kind = \"" +
         kind.str() + "\", metadata = {}}> : (" + targetType.str() + ", " + sourceType.str() +
         ") -> ()\n";
}
std::string parameter(bool bounded = true, StringRef effects = "\"shape\", \"width\"") {
  std::string text =
      "\"nodal.parameter\"() <{sym_name = \"N\", type = i64, default_value = 2 : i64, "
      "parameter_kind = \"integer\", classification = \"structural\", "
      "variability = \"symbolic\", metadata = {}}> : () -> ()\n";
  if (bounded)
    text += literal("i64", "1", "low") + literal("i64", "4", "high") +
            "\"nodal.parameter_constraint\"(%low, %high) <{parameter = @N, "
            "constraint_kind = \"range\", lower_inclusive = true, upper_inclusive = true, "
            "metadata = {}}> : (i64, i64) -> ()\n";
  text += "\"nodal.parameter_envelope\"() <{parameter = @N, policy = \"static_generate\", "
          "effects = [" +
          effects.str() + "], metadata = {}}> : () -> ()\n";
  text += "%N = \"nodal.const_parameter_ref\"() <{parameter = @N, metadata = {}}> : () -> i64\n";
  return text;
}
std::string print(Operation *operation) {
  std::string text;
  llvm::raw_string_ostream stream(text);
  operation->print(stream);
  return text;
}
std::string replace(std::string text, StringRef from, StringRef to) {
  auto start = text.find(from.str());
  if (start == std::string::npos)
    llvm::report_fatal_error("test replacement was absent");
  text.replace(start, from.size(), to.str());
  return text;
}
} // namespace

int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
  unsigned cases = 0, failures = 0;
  auto report = [&](StringRef name, bool passed) {
    ++cases;
    failures += !passed;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_STORAGE_MODEL_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  auto check = [&](StringRef name, const std::string &body, bool expected, bool wrap = true) {
    auto parsed =
        parseSourceString<mlir::ModuleOp>(wrap ? model(body) : body, ParserConfig(&context, false));
    if (!parsed) {
      report(name, !expected);
      return;
    }
    std::string before = print(parsed->getOperation());
    bool valid = succeeded(verify(parsed->getOperation()));
    bool passed = valid == expected && before == print(parsed->getOperation());
    if (passed && expected) {
      auto roundtrip = parseSourceString<mlir::ModuleOp>(before, ParserConfig(&context, false));
      passed = roundtrip && succeeded(verify(roundtrip->getOperation())) &&
               before == print(roundtrip->getOperation());
    }
    report(name, passed);
  };
  const std::string sint = "!nodal.sint<8>", uint = "!nodal.uint<8>";
  const std::string shape = "!nodal.shaped<\"2,3\", !nodal.sint<8>>";
  const std::string record = "!nodal.struct<\"Packet\", [{name = \"samples\", type = " + shape +
                             "}, {name = \"mode\", type = !nodal.enum<\"Mode\", 2>}]>";
  for (StringRef type : {StringRef("i1"), StringRef("i64"), StringRef("f64"),
                         StringRef("!nodal.bits<9>"), StringRef(uint), StringRef(sint)})
    check("wire-" + type.str(), storage(type), true);
  check("wire-recursive-struct-vec-enum", storage(record), true);
  check("register-exact-initializer",
        domain() + literal(sint, "-128") +
            storage(sint, "state", "register", "%initial", sint, "clock"),
        true);
  check("register-uninitialized", domain() + storage(record, "state", "register", "", "", "clock"),
        true);
  check("register-required-local-domain",
        "\"nodal.domain_requirement\"() <{sym_name = \"required\", metadata = {}}> : () -> ()\n" +
            storage(uint, "state", "register", "", "", "required"),
        true);
  check("register-missing-domain", storage(uint, "state", "register"), false);
  check("register-nonexistent-domain", storage(uint, "state", "register", "", "", "missing"),
        false);
  check("register-symbol-is-not-domain",
        storage(uint, "wrong") + storage(uint, "state", "register", "", "", "wrong"), false);
  check("register-initializer-signedness",
        domain() + literal(uint) + storage(sint, "state", "register", "%initial", uint, "clock"),
        false);
  check("register-initializer-shape-order",
        domain() + storage(shape, "initial") +
            storage("!nodal.shaped<\"3,2\", !nodal.sint<8>>", "state", "register", "%initial",
                    shape, "clock"),
        false);
  check("wire-no-state-domain", domain() + storage(sint, "wire", "wire", "", "", "clock"), false);
  check("wire-no-initializer", literal(sint) + storage(sint, "wire", "wire", "%initial", sint),
        false);
  check("storage-explicit-kind", storage(sint, "bad", "variable"), false);
  check("storage-unique-names",
        storage(sint, "first") +
            replace(storage(sint, "second"), "name = \"second\"", "name = \"first\""),
        false);
  check("storage-rejects-analog", storage("!nodal.terminal<\"electrical\">"), false);
  check("storage-rejects-protocol", storage("!nodal.valid<!nodal.uint<8>>"), false);
  check("storage-rejects-zero-width", storage("!nodal.uint<0>"), false);
  check("storage-rejects-foreign-owner", "module { " + storage(sint) + " }", false, false);
  check("memory-signed-elements", domain() + memory(sint), true);
  check("memory-maximum-finite-footprint",
        domain() + memory("!nodal.uint<1>", "9223372036854775807"), true);
  check("memory-recursive-elements", domain() + memory(record), true);
  for (StringRef policy : {"old_data", "new_data", "no_change", "undefined"})
    check("memory-policy-" + policy.str(),
          domain() + memory(uint, "1", "0", policy, "independent_ports"), true);
  check("memory-symbolic-width-depth",
        domain() + parameter() +
            memory("!nodal.shaped<\"N,2\", !nodal.digital<\"sint\", \"N\">>", "N"),
        true);
  check("memory-compound-depth",
        domain() + parameter() + literal("i64", "2", "two") +
            "%depth = \"nodal.const_expr\"(%N, %two) <{operator_name = \"mul\", "
            "metadata = {semantic_path = \"Fixture.depth\"}}> : (i64, i64) -> i64\n" +
            memory(sint, "Fixture.depth"),
        true);
  check("memory-depth-no-default-substitution", domain() + parameter(false) + memory(sint, "N"),
        false);
  check("memory-depth-requires-shape-effect",
        domain() + parameter(true, "\"width\"") + memory(sint, "N"), false);
  for (StringRef depth : {"0", "-1", "01", "+2", "2,3", "9223372036854775808", "missing", "N+1"})
    check("memory-invalid-depth-" + depth.str(), domain() + memory(sint, depth), false);
  check("memory-total-footprint-overflow", domain() + memory(sint, "9223372036854775807"), false);
  check("wire-recursive-footprint-overflow",
        storage("!nodal.shaped<\"9223372036854775807\", !nodal.uint<2>>"), false);
  check("memory-invalid-latency", domain() + memory(sint, "1", "-1"), false);
  check("memory-invalid-collision", domain() + memory(sint, "1", "0", "write_first"), false);
  check("memory-invalid-ordering", domain() + memory(sint, "1", "0", "old_data", "unspecified"),
        false);
  check("memory-missing-domain", memory(sint, "1", "0", "old_data", "ordered", "missing"), false);
  check("memory-not-a-value",
        domain() + "%bad = " + replace(memory(sint), ": () -> ()", ": () -> !nodal.sint<8>"),
        false);
  check("assignment-wire", port(sint) + storage(sint) + assign(sint), true);
  check("assignment-output", port(sint) + port(sint, "out", "output") + assign(sint, sint, "out"),
        true);
  check("assignment-register-next",
        domain() + port(sint) + storage(sint, "state", "register", "", "", "clock") +
            assign(sint, sint, "state", "source", "register_next"),
        true);
  check("assignment-exact-aggregate", port(record) + storage(record) + assign(record, record),
        true);
  check("assignment-readonly-input", port(sint) + assign(sint, sint, "source"), false);
  check("assignment-readonly-expression",
        literal(sint, "1", "source") + assign(sint, sint, "source"), false);
  check("assignment-no-signedness-conversion", port(uint) + storage(sint) + assign(sint, uint),
        false);
  check("assignment-register-intent-only-for-state",
        port(sint) + storage(sint) + assign(sint, sint, "wire", "source", "register_next"), false);
  check("assignment-continuous-cannot-update-state",
        domain() + port(sint) + storage(sint, "state", "register", "", "", "clock") +
            assign(sint, sint, "state"),
        false);
  check("assignment-shape-projection",
        storage(shape) + literal("i64", "0", "zero") +
            "%row = \"nodal.shape_index\"(%wire, %zero) <{metadata = {}}> : (" + shape +
            ", i64) -> !nodal.shaped<\"3\", !nodal.sint<8>>\n" +
            port("!nodal.shaped<\"3\", !nodal.sint<8>>") +
            assign("!nodal.shaped<\"3\", !nodal.sint<8>>", "!nodal.shaped<\"3\", !nodal.sint<8>>",
                   "row"),
        true);
  for (StringRef element : {"i64", "f64"}) {
    std::string cells = "!nodal.shaped<\"2\", " + element.str() + ">";
    check("assignment-numeric-projection-" + element.str(),
          storage(cells) + literal("i64", "0", "zero") +
              "%cell = \"nodal.shape_index\"(%wire, %zero) <{metadata = {}}> : (" + cells +
              ", i64) -> " + element.str() + "\n" + port(element) +
              assign(element, element, "cell"),
          true);
  }
  const std::string symbolicN = "!nodal.digital<\"uint\", \"N\">";
  const std::string firstWidth = "!nodal.digital<\"uint\", \"Fixture.first\">";
  const std::string secondWidth = "!nodal.digital<\"uint\", \"Fixture.second\">";
  const std::string symbolicWidths =
      parameter() + literal("i64", "1", "one") +
      "%maximum = \"nodal.const_expr\"(%N, %N) <{operator_name = \"max\", metadata = "
      "{semantic_path = \"Fixture.maximum\"}}> : (i64, i64) -> i64\n"
      "%first = \"nodal.const_expr\"(%maximum, %one) <{operator_name = \"add\", metadata = "
      "{semantic_path = \"Fixture.first\"}}> : (i64, i64) -> i64\n"
      "%otherMaximum = \"nodal.const_expr\"(%N, %N) <{operator_name = \"max\", metadata = "
      "{semantic_path = \"Fixture.otherMaximum\"}}> : (i64, i64) -> i64\n"
      "%second = \"nodal.const_expr\"(%otherMaximum, %one) <{operator_name = \"add\", metadata = "
      "{semantic_path = \"Fixture.second\"}}> : (i64, i64) -> i64\n";
  const std::string arithmetic = port(symbolicN, "left") + port(symbolicN, "right") +
                                 "%source = \"nodal.digital_expr\"(%left, %right) <{operator_name "
                                 "= \"digital_add\", metadata = {}}> : (" +
                                 symbolicN + ", " + symbolicN + ") -> " + firstWidth + "\n";
  check("assignment-fresh-equivalent-symbolic-arithmetic",
        symbolicWidths + arithmetic + port(secondWidth, "destination", "output") +
            assign(secondWidth, firstWidth, "destination"),
        true);
  const std::string manualWidth = "!nodal.digital<\"uint\", \"Fixture.manual\">";
  const std::string manualWidthExpression =
      "%manual = \"nodal.const_expr\"(%N, %one) <{operator_name = \"add\", metadata = "
      "{semantic_path = \"Fixture.manual\"}}> : (i64, i64) -> i64\n";
  check("assignment-symbolic-add-to-manual-plus-one-output",
        symbolicWidths + manualWidthExpression + arithmetic +
            port(manualWidth, "destination", "output") +
            assign(manualWidth, firstWidth, "destination"),
        true);
  check("register-initializer-equivalent-symbolic-type",
        symbolicWidths + domain() + literal(firstWidth, "0") +
            storage(secondWidth, "state", "register", "%initial", firstWidth, "clock"),
        true);
  const std::string otherParameter =
      "\"nodal.parameter\"() <{sym_name = \"M\", type = i64, default_value = 2 : i64, "
      "parameter_kind = \"integer\", classification = \"structural\", variability = \"symbolic\", "
      "metadata = {}}> : () -> ()\n"
      "\"nodal.parameter_constraint\"(%low, %high) <{parameter = @M, constraint_kind = \"range\", "
      "lower_inclusive = true, upper_inclusive = true, metadata = {}}> : (i64, i64) -> ()\n"
      "\"nodal.parameter_envelope\"() <{parameter = @M, policy = \"static_generate\", effects = "
      "[\"shape\", \"width\"], metadata = {}}> : () -> ()\n";
  const std::string symbolicM = "!nodal.digital<\"uint\", \"M\">";
  check("assignment-keeps-actual-parameter-identity",
        parameter() + otherParameter + port(symbolicN) + storage(symbolicM) +
            assign(symbolicM, symbolicN),
        false);
  check("initializer-keeps-actual-parameter-identity",
        parameter() + otherParameter + domain() + literal(symbolicN, "0") +
            storage(symbolicM, "state", "register", "%initial", symbolicN, "clock"),
        false);
  llvm::outs() << "DIGITAL_STORAGE_MODEL_RESULT " << cases - failures << '/' << cases << '\n';
  return failures ? 1 : 0;
}
