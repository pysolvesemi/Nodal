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
  const std::string widerRecord = replace(record, "!nodal.sint<8>", "!nodal.sint<9>");
  check("assignment-Struct-width-family-is-not-ABI-equality",
        port(widerRecord) + storage(record) + assign(record, widerRecord), false);
  check("assignment-reconstructed-exact-Struct-remains-valid",
        port(widerRecord) + storage(widerRecord) + assign(widerRecord, widerRecord), true);
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
  // F55 causal controls use the actual module hook and require the intended
  // diagnostic, so a parser/type error cannot masquerade as a cycle rejection.
  auto graph = [&](StringRef name, const std::string &body, StringRef diagnostic = "") {
    std::string messages;
    ScopedDiagnosticHandler capture(&context, [&](Diagnostic &item) {
      llvm::raw_string_ostream stream(messages);
      item.print(stream);
      return success();
    });
    auto parsed = parseSourceString<mlir::ModuleOp>(model(body), ParserConfig(&context, false));
    bool passed = static_cast<bool>(parsed);
    if (parsed) {
      std::string before = print(parsed->getOperation());
      bool valid = succeeded(verify(parsed->getOperation()));
      passed = valid == diagnostic.empty() && before == print(parsed->getOperation()) &&
               (diagnostic.empty() || messages.find(diagnostic.str()) != std::string::npos);
      if (passed && valid) {
        auto roundtrip = parseSourceString<mlir::ModuleOp>(before, ParserConfig(&context, false));
        passed = roundtrip && succeeded(verify(roundtrip->getOperation()));
      }
    }
    report(name, passed);
    if (!passed)
      llvm::errs() << messages << '\n';
  };
  const StringRef conflict = "overlapping or unproved-disjoint drivers";
  const StringRef cycle = "combinational storage-read cycle";
  const std::string scalar = "!nodal.bits<8>";
  const std::string vec = "!nodal.shaped<\"4\", !nodal.bits<8>>";
  auto wireAssign = [&](StringRef target, StringRef source, StringRef type = "!nodal.bits<8>") {
    return assign(type, type, target, source);
  };
  auto index = [&](StringRef result, StringRef source, StringRef selector,
                   StringRef inputType = "!nodal.shaped<\"4\", !nodal.bits<8>>") {
    return "%" + result.str() + " = \"nodal.shape_index\"(%" + source.str() + ", %" +
           selector.str() + ") <{metadata = {}}> : (" + inputType.str() + ", i64) -> " + scalar +
           "\n";
  };
  auto notValue = [&](StringRef result, StringRef source) {
    return "%" + result.str() + " = \"nodal.digital_expr\"(%" + source.str() +
           ") <{operator_name = \"digital_not\", metadata = {}}> : (" + scalar + ") -> " + scalar +
           "\n";
  };
  const std::string roots = port(scalar) + storage(scalar, "a") + storage(scalar, "b");
  graph("graph-same-scalar-two-writers",
        roots + wireAssign("a", "source") + wireAssign("a", "source"), conflict);
  graph("graph-self-read", roots + wireAssign("a", "a"), cycle);
  graph("graph-two-wire-cycle", roots + wireAssign("a", "b") + wireAssign("b", "a"), cycle);
  graph("graph-expression-self-read",
        roots + notValue("inverted", "a") + wireAssign("a", "inverted"), cycle);
  // Exercise the activated Struct value owner through the same graph, keeping
  // complete record ABI and actual get/make/alias operands (no field projection
  // independence is claimed by these whole-record dependencies).
  const std::string graphRecord =
      "!nodal.struct<\"GraphRecord\", [{name = \"payload\", type = !nodal.bits<8>}]>";
  auto getPayload = [&](StringRef receiver = "recordWire") {
    return "%payload = \"nodal.digital_expr\"(%" + receiver.str() +
           ") <{operator_name = \"digital_struct_get\", metadata = {}, struct_schema = " +
           graphRecord + ", field_ordinal = 0 : i64, field_name = \"payload\"}> : (" + graphRecord +
           ") -> " + scalar + "\n";
  };
  auto makeRecord = [&](StringRef input = "source") {
    return "%made = \"nodal.digital_expr\"(%" + input.str() +
           ") <{operator_name = \"digital_struct_make\", metadata = {}, struct_schema = " +
           graphRecord + "}> : (" + scalar + ") -> " + graphRecord + "\n";
  };
  graph("graph-Struct-get-make-feedback",
        storage(graphRecord, "recordWire") + getPayload() + makeRecord("payload") +
            wireAssign("recordWire", "made", graphRecord),
        cycle);
  graph("graph-Struct-get-make-acyclic", port(scalar) + storage(graphRecord, "recordWire") +
                                             port(scalar, "out", "output") + makeRecord() +
                                             wireAssign("recordWire", "made", graphRecord) +
                                             getPayload() + wireAssign("out", "payload"));
  const std::string recordAlias =
      "%recordAlias = \"nodal.digital_expr\"(%recordWire) <{operator_name = \"digital_alias\", "
      "metadata = {digital_intent = {kind = \"named\", value = \"record_alias\"}}}> : (" +
      graphRecord + ") -> " + graphRecord + "\n";
  graph("graph-Struct-get-make-alias-feedback",
        storage(graphRecord, "recordWire") + recordAlias + getPayload("recordAlias") +
            makeRecord("payload") + wireAssign("recordWire", "made", graphRecord),
        cycle);
  graph("graph-acyclic-input-fanout-output", roots + port(scalar, "out", "output") +
                                                 wireAssign("b", "a") + wireAssign("out", "a") +
                                                 wireAssign("a", "source"));
  graph("graph-unused-wire", storage(scalar, "unused"));
  graph("graph-undriven-consumed-wire", roots + wireAssign("b", "a"),
        "undriven combinational wire");
  graph("graph-register-boundary",
        domain() + storage(scalar, "a") + storage(scalar, "state", "register", "", "", "clock") +
            wireAssign("a", "state") + assign(scalar, scalar, "state", "a", "register_next"));
  graph("graph-register-next-undriven-rhs",
        domain() + storage(scalar, "a") + storage(scalar, "state", "register", "", "", "clock") +
            assign(scalar, scalar, "state", "a", "register_next"),
        "undriven combinational wire");
  graph("graph-register-next-driven-rhs",
        domain() + port(scalar) + storage(scalar, "a") +
            storage(scalar, "state", "register", "", "", "clock") + wireAssign("a", "source") +
            assign(scalar, scalar, "state", "a", "register_next"));
  graph("graph-register-next-self-read",
        domain() + storage(scalar, "state", "register", "", "", "clock") +
            assign(scalar, scalar, "state", "state", "register_next"));
  graph("graph-register-next-not-combinational-driver",
        domain() + port(scalar) + storage(scalar, "state", "register", "", "", "clock") +
            assign(scalar, scalar, "state", "source", "register_next") +
            assign(scalar, scalar, "state", "source", "register_next"));
  const std::string lanes = port(scalar) + storage(vec, "lanes") + literal("i64", "0", "i0") +
                            literal("i64", "1", "i1") + index("lane0", "lanes", "i0") +
                            index("lane1", "lanes", "i1");
  graph("graph-disjoint-lane-writers",
        lanes + wireAssign("lane0", "source") + wireAssign("lane1", "source"));
  graph("graph-overlapping-lane-writers",
        lanes + wireAssign("lane0", "source") + wireAssign("lane0", "source"), conflict);
  graph("graph-disjoint-lane-read-chain",
        lanes + wireAssign("lane1", "lane0") + wireAssign("lane0", "source"));
  graph("graph-projected-self-read", lanes + wireAssign("lane0", "lane0"), cycle);
  graph("graph-projected-mutual-cycle",
        lanes + wireAssign("lane0", "lane1") + wireAssign("lane1", "lane0"), cycle);
  graph("graph-undriven-projected-read",
        lanes + storage(scalar, "out") + wireAssign("lane0", "source") + wireAssign("out", "lane1"),
        "undriven combinational wire region");
  const std::string vec8 = "!nodal.shaped<\"8\", !nodal.bits<8>>";
  graph("graph-unknown-symbolic-overlap-fails-closed",
        parameter() + port(scalar) + storage(vec8, "lanes") + literal("i64", "2", "two") +
            index("symbolic", "lanes", "N", vec8) + index("fixed", "lanes", "two", vec8) +
            wireAssign("symbolic", "source") + wireAssign("fixed", "source"),
        conflict);
  auto slice = [&](StringRef result, StringRef lo, StringRef hi) {
    return "%" + result.str() + " = \"nodal.shape_slice\"(%lanes, %" + lo.str() + ", %" + hi.str() +
           ", %two) <{axis = 0 : i64, dimensions = \"2\", origin = \"Fixture.lanes\", "
           "observability = \"source_mapped\", metadata = {semantic_path = \"Fixture." +
           result.str() +
           "\", input_path = \"Fixture.lanes\", storage = \"structural\", access = "
           "\"read_only\"}}> : (" +
           vec + ", i64, i64, i64) -> !nodal.shaped<\"2\", !nodal.bits<8>>\n";
  };
  const std::string half = "!nodal.shaped<\"2\", !nodal.bits<8>>";
  const std::string slices = replace(storage(vec, "lanes"), "metadata = {}",
                                     "metadata = {semantic_path = \"Fixture.lanes\"}") +
                             port(half) + literal("i64", "0", "zero") + literal("i64", "1", "one") +
                             literal("i64", "2", "two") + literal("i64", "3", "three") +
                             literal("i64", "4", "four");
  graph("graph-adjacent-slices",
        slices + slice("first", "zero", "two") + slice("last", "two", "four") +
            wireAssign("first", "source", half) + wireAssign("last", "source", half));
  graph("graph-overlapping-slices",
        slices + slice("first", "zero", "two") + slice("last", "one", "three") +
            wireAssign("first", "source", half) + wireAssign("last", "source", half),
        conflict);
  graph("graph-complete-aggregate-coverage",
        slices + slice("first", "zero", "two") + slice("last", "two", "four") +
            port(vec, "out", "output") + wireAssign("first", "source", half) +
            wireAssign("last", "source", half) + wireAssign("out", "lanes", vec));
  graph("graph-incomplete-aggregate-coverage",
        slices + slice("first", "zero", "two") + port(vec, "out", "output") +
            wireAssign("first", "source", half) + wireAssign("out", "lanes", vec),
        "lacks proved complete physical driver coverage");
  graph("graph-undriven-consumed-output",
        port(scalar, "out", "output") + storage(scalar, "wire") + wireAssign("wire", "out"),
        "undriven combinational wire");
  graph("graph-unused-output", port(scalar, "out", "output"));
  const std::string aliases =
      port(scalar) + port(scalar, "out", "output") +
      "%alias = \"nodal.port_value\"() <{port = @out, metadata = {}}> : () -> " + scalar + "\n";
  graph("graph-output-alias-two-writers",
        aliases + wireAssign("out", "source") + wireAssign("alias", "source"), conflict);
  graph("graph-output-alias-cycle", aliases + wireAssign("out", "alias"), cycle);
  auto collection = [&](StringRef input, StringRef captures, StringRef captureTypes,
                        StringRef arguments, StringRef yielded) {
    return "%mapped = \"nodal.collection\"(%" + input.str() + captures.str() +
           ") <{kind = \"map\", region_id = \"Fixture.map\", operandSegmentSizes = array<i32: 1, "
           "0, " +
           (captures.empty() ? "0" : "1") +
           ">, metadata = {semantic_path = \"Fixture.map\"}}> "
           "({^bb0(%element: " +
           scalar + arguments.str() +
           "):\n"
           "\"nodal.collection_yield\"(%" +
           yielded.str() + ") <{metadata = {}}> : (" + scalar + ") -> ()\n}) : (" + vec +
           captureTypes.str() + ") -> " + vec + "\n";
  };
  graph("graph-collection-input-to-output", port(vec) + storage(vec, "out") +
                                                collection("source", "", "", "", "element") +
                                                wireAssign("out", "mapped", vec));
  graph("graph-collection-primary-argument-cycle",
        storage(vec, "out") + collection("out", "", "", "", "element") +
            wireAssign("out", "mapped", vec),
        cycle);
  graph("graph-collection-captured-projection-cycle",
        lanes +
            collection("lanes", ", %lane0", ", " + scalar, ", %captured: " + scalar, "captured") +
            wireAssign("lanes", "mapped", vec),
        cycle);
  graph("graph-collection-unused-capture-does-not-read-storage",
        port(vec) + storage(vec, "out") +
            collection("source", ", %out", ", " + vec, ", %captured: " + vec, "element") +
            wireAssign("out", "mapped", vec));
  auto fold = [&](StringRef seed, StringRef body, StringRef result = "acc") {
    return "%folded = \"nodal.collection\"(%source, %" + seed.str() +
           ") <{kind = \"fold_left\", region_id = \"Fixture.fold\", operandSegmentSizes = "
           "array<i32: 1, 1, 0>, "
           "metadata = {semantic_path = \"Fixture.fold\"}}> ({^bb0(%acc: " +
           scalar + ", %element: " + scalar + "):\n" + body.str() + "\"nodal.collection_yield\"(%" +
           result.str() + ") <{metadata = {}}> : (" + scalar + ") -> ()\n}) : (" + vec + ", " +
           scalar + ") -> " + scalar + "\n";
  };
  graph("graph-collection-fold-accumulator-inputs", port(vec) + literal(scalar, "0", "seed") +
                                                        storage(scalar, "out") + fold("seed", "") +
                                                        wireAssign("out", "folded"));
  graph("graph-collection-fold-seed-cycle",
        port(vec) + storage(scalar, "out") + fold("out", "") + wireAssign("out", "folded"), cycle);
  const std::string deepStart = port(scalar) + storage(scalar, "out");
  std::string deep = deepStart;
  for (unsigned i = 0; i < 2048; ++i)
    deep += notValue("n" + std::to_string(i), i ? "n" + std::to_string(i - 1) : "source");
  // The graph query itself is iterative; scalar semantic proof retains its own
  // separately bounded depth and is not widened by this focused graph witness.
  auto deepParsed = parseSourceString<mlir::ModuleOp>(model(deep + wireAssign("out", "n2047")),
                                                      ParserConfig(&context, false));
  bool deepGood = false, budgetGood = false;
  if (deepParsed) {
    for (Operation &operation : deepParsed->getBody()->getOperations())
      if (llvm::isa<nodal::ModuleOp>(operation)) {
        deepGood = succeeded(nodal::verifyDigitalAssignmentModel(&operation));
        nodal::DigitalAssignmentProofLimits limits;
        limits.maximumNodes = 16;
        budgetGood = failed(nodal::verifyDigitalAssignmentModel(&operation, limits));
      }
  }
  report("graph-deep-shared-query-no-recursion", deepGood);
  report("graph-node-budget-fails-closed", budgetGood);
  // Write authority follows actual SSA storage roots; neither metadata nor a shape label is proof.
  auto writableTarget = [&](StringRef name, const std::string &body, StringRef diagnostic = "") {
    std::string messages;
    ScopedDiagnosticHandler capture(&context, [&](Diagnostic &value) {
      llvm::raw_string_ostream stream(messages);
      value.print(stream);
      return success();
    });
    auto parsed = parseSourceString<mlir::ModuleOp>(model(body), ParserConfig(&context, false));
    bool passed = false;
    if (parsed) {
      std::string before = print(parsed->getOperation());
      bool valid = succeeded(verify(parsed->getOperation()));
      passed =
          before == print(parsed->getOperation()) &&
          (diagnostic.empty() ? valid
                              : !valid && messages.find(diagnostic.str()) != std::string::npos);
      if (passed && diagnostic.empty()) {
        auto roundtrip = parseSourceString<mlir::ModuleOp>(before, ParserConfig(&context, false));
        passed = roundtrip && succeeded(verify(roundtrip->getOperation())) &&
                 before == print(roundtrip->getOperation());
      }
    }
    if (!passed)
      llvm::errs() << messages;
    report(name, passed);
  };
  const std::string targetShape = "!nodal.shaped<\"2\", !nodal.uint<8>>";
  auto targetLane = [&](StringRef root = "cells", StringRef position = "0") {
    return literal("i64", position, "position") + "%lane = \"nodal.shape_index\"(%" + root.str() +
           ", %position) <{metadata = {writable = true, target_kind = \"wire\"}}> : (" +
           targetShape + ", i64) -> !nodal.uint<8>\n";
  };
  const std::string laneWrite = port(uint) + assign(uint, uint, "lane");
  writableTarget("writable-selection-wire-positive",
                 storage(targetShape, "cells") + targetLane() + laneWrite);
  writableTarget(
      "writable-selection-output-positive",
      port(targetShape, "cells", "output") + targetLane() + laneWrite +
          literal("i64", "1", "otherPosition") +
          "%otherLane = \"nodal.shape_index\"(%cells, %otherPosition) <{metadata = {}}> : (" +
          targetShape + ", i64) -> !nodal.uint<8>\n" + assign(uint, uint, "otherLane"));
  writableTarget("writable-selection-output-missing-lane",
                 port(targetShape, "cells", "output") + targetLane() + laneWrite,
                 "NODAL-DIGITAL-055-010: ordinary assignment read lacks proved complete physical "
                 "driver coverage");
  writableTarget(
      "writable-selection-input-forged-labels",
      port(targetShape, "cells") + targetLane() + laneWrite,
      "NODAL-DIGITAL-054-010: digital assignment port target must be an exact writable output");
  writableTarget(
      "writable-selection-inout-forged-labels",
      port(targetShape, "cells", "inout") + targetLane() + laneWrite,
      "NODAL-DIGITAL-054-010: digital assignment port target must be an exact writable output");
  const std::string state = domain() + storage(targetShape, "cells", "register", "", "", "clock");
  writableTarget("writable-selection-register-next-rejected",
                 state + targetLane() + port(uint) +
                     assign(uint, uint, "lane", "source", "register_next"),
                 "NODAL-DIGITAL-054-010: projected register updates are unsupported");
  writableTarget("writable-selection-register-continuous-rejected",
                 state + targetLane() + laneWrite,
                 "NODAL-DIGITAL-054-010: projected register updates are unsupported");
  writableTarget("writable-selection-register-whole-view-rejected",
                 state +
                     "%view = \"nodal.shape_view\"(%cells) <{dimensions = \"2\", materialization = "
                     "\"view\", observability = \"source_mapped\", origin = \"cells\", metadata = "
                     "{writable = true, storage = \"structural\"}}> : (" +
                     targetShape + ") -> " + targetShape + "\n" + port(targetShape) +
                     assign(targetShape, targetShape, "view", "source", "register_next"),
                 "NODAL-DIGITAL-054-010: projected register updates are unsupported");
  writableTarget("writable-selection-root-register-predecessor",
                 state + port(targetShape) +
                     assign(targetShape, targetShape, "cells", "source", "register_next"));
  writableTarget("writable-selection-out-of-bounds-forged-labels",
                 storage(targetShape, "cells") + targetLane("cells", "2") + laneWrite,
                 "NODAL-SHAPE-043-002");
  writableTarget("writable-selection-exact-rhs-abi",
                 storage(targetShape, "cells") + targetLane() + port(sint) +
                     assign(uint, sint, "lane"),
                 "NODAL-DIGITAL-054-010: digital assignment requires exactly matching recursive "
                 "storage types");
  writableTarget(
      "writable-selection-computed-alias-root",
      port(targetShape, "input") +
          "%cells = \"nodal.digital_expr\"(%input) <{operator_name = \"digital_alias\", metadata = "
          "{writable = true}}> : (" +
          targetShape + ") -> " + targetShape + "\n" + targetLane() + laneWrite,
      "NODAL-DIGITAL-054-010: digital assignment target must be a wire, output or register");
  {
    const std::string escaped = storage(targetShape, "cells") + targetLane() +
                                "\"nodal.module\"() <{sym_name = \"Child\", metadata = {}}> ({" +
                                laneWrite + "}) : () -> ()\n";
    std::string messages;
    ScopedDiagnosticHandler capture(&context, [&](Diagnostic &value) {
      llvm::raw_string_ostream stream(messages);
      value.print(stream);
      return success();
    });
    auto parsed = parseSourceString<mlir::ModuleOp>(model(escaped), ParserConfig(&context, false));
    bool rejected = false;
    if (parsed)
      parsed->walk([&](nodal::DigitalAssignOp assignment) {
        rejected = failed(nodal::verifyDigitalAssignmentOperation(assignment));
      });
    report("writable-selection-actual-foreign-owner",
           rejected && messages.find("NODAL-DIGITAL-054-010: digital assignment operands must "
                                     "belong to the same module") != std::string::npos);
  }
  llvm::outs() << "DIGITAL_STORAGE_MODEL_RESULT " << cases - failures << '/' << cases << '\n';
  return failures ? 1 : 0;
}
