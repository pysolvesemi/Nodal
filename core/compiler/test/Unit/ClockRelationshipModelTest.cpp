#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/ClockModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/raw_ostream.h"

#include <functional>
#include <string>

using namespace mlir;
namespace {
const std::string clockType = "!nodal.capability<\"clock\">";
const std::string bit = "!nodal.bits<1>";
std::string domain(StringRef name, StringRef parent = "", StringRef frequency = "") {
  return "\"nodal.domain\"() <{sym_name = \"" + name.str() +
         "\", edge = \"rising\", reset_policy = \"none\", " +
         (parent.empty() ? "" : "parent = @" + parent.str() + ", ") + "metadata = {" +
         (frequency.empty() ? "" : "frequency_hz = \"" + frequency.str() + "\"") +
         "}}> : () -> ()\n";
}
std::string port(StringRef name, StringRef type = clockType, StringRef dom = "") {
  return "\"nodal.port\"() <{sym_name = \"" + name.str() + "\", type = " + type.str() +
         ", direction = \"input\", " + (dom.empty() ? "" : "domain = @" + dom.str() + ", ") +
         "metadata = {}}> : () -> ()\n%" + name.str() + " = \"nodal.port_value\"() <{port = @" +
         name.str() + ", metadata = {}}> : () -> " + type.str() + "\n";
}
std::string binding(StringRef name, StringRef clock) {
  return "\"nodal.local_domain_binding\"(%" + clock.str() + ") <{domain = @" + name.str() +
         ", reset_polarity = \"active_high\", metadata = {}}> : (" + clockType + ") -> ()\n";
}
std::string setup(unsigned count = 3) {
  std::string result;
  for (unsigned i = 0; i != count; ++i) {
    std::string name(1, 'a' + i);
    result += domain(name) + port("clk" + name) + binding(name, "clk" + name);
  }
  return result;
}
std::string relation(StringRef source, StringRef destination, StringRef kind = "same",
                     StringRef parameters = "", StringRef identity = "") {
  std::string proof = "classification", obligations;
  if (kind == "same") {
    proof = "external_contract";
    obligations = "active_edges = \"identical_stream\"";
  } else if (kind == "ratio") {
    proof = "timing_contract";
    obligations = "timing = \"exact_relative_period_phase\"";
  } else if (kind == "synchronous") {
    proof = "timing_contract";
    obligations = "timing = \"synchronous_phase_knowledge_retained\"";
  } else if (kind == "mutually_exclusive") {
    proof = "operating_mode_contract";
    obligations = "exclusion = \"never_simultaneously_enabled\"";
  }
  return "\"nodal.clock_relationship\"(%clk" + source.str() + ", %clk" + destination.str() +
         ") <{source = @" + source.str() + ", destination = @" + destination.str() + ", kind = \"" +
         kind.str() + "\", contract = \"relationship_v1\", proof = \"" + proof + "\", " +
         parameters.str() + "obligations = {" + obligations +
         "}, metadata = {semantic_path = \"Fixture." +
         (identity.empty() ? source.str() + destination.str() + kind.str() : identity.str()) +
         "\"}}> : (" + clockType + ", " + clockType + ") -> ()\n";
}
std::string ratio(StringRef a, StringRef b, int multiply, int divide, StringRef phase = "0.0",
                  StringRef identity = "") {
  return relation(a, b, "ratio",
                  "multiply = " + std::to_string(multiply) + " : i32, divide = " +
                      std::to_string(divide) + " : i32, phase = \"" + phase.str() + "\", ",
                  identity);
}
std::string structural(std::string record) {
  auto from = record.find("external_contract");
  record.replace(from, 17, "structural");
  from = record.find("active_edges = \"identical_stream\"");
  record.erase(from, std::string("active_edges = \"identical_stream\"").size());
  return record;
}
std::string replace(std::string value, StringRef from, StringRef to) {
  auto at = value.find(from.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing relationship test replacement");
  value.replace(at, from.size(), to.str());
  return value;
}
std::string sample(StringRef from = "a", StringRef to = "b") {
  return port("data", bit, from) +
         "%state = \"nodal.digital_storage\"() <{name = \"state\", kind = \"register\", domain = "
         "@" +
         to.str() + ", metadata = {semantic_path = \"Fixture.state\"}}> : () -> " + bit + "\n" +
         "\"nodal.digital_assign\"(%state, %data) <{kind = \"register_next\", metadata = "
         "{semantic_path = \"Fixture.assign\"}}> : (" +
         bit + ", " + bit + ") -> ()\n";
}
std::string print(Operation *op) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  op->print(stream);
  return result;
}
struct Harness {
  MLIRContext context;
  std::string diagnostic;
  unsigned cases = 0, failures = 0;
  ScopedDiagnosticHandler handler;
  Harness()
      : handler(&context, [&](Diagnostic &d) {
          llvm::raw_string_ostream stream(diagnostic);
          d.print(stream);
          stream << '\n';
          return success();
        }) {
    context.getOrLoadDialect<nodal::NodalDialect>();
  }
  Attribute ref(StringRef name) { return FlatSymbolRefAttr::get(&context, name); }
  void check(StringRef name, StringRef body, bool valid = true, bool sampling = false,
             bool permission = false, StringRef expected = "") {
    diagnostic.clear();
    std::string ir = "module { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({\n" +
                     body.str() + "}) : () -> () }";
    auto owner = parseSourceString<mlir::ModuleOp>(ir, ParserConfig(&context, false));
    bool ok = !!owner;
    if (owner) {
      Operation *module = &owner->getBody()->front();
      std::string before = print(module);
      nodal::DomainModel domains(module);
      bool result = succeeded(sampling ? verify(*owner) : domains.verifyRelationships());
      ok &= result == valid && print(module) == before;
      if (valid) {
        ok &= domains.allowsDirectSampling(ref("a"), ref("b")) == permission;
        ok &= !domains.matchesActualDomain(ref("a"), ref("b"));
        ok &= domains.matchesActualDomain(ref("a"), ref("a"));
      }
    }
    ok &= expected.empty() || StringRef(diagnostic).contains(expected);
    ++cases;
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostic;
    }
  }
  void intent(StringRef name, StringRef body, StringRef fields, bool valid = true,
              StringRef source = "a", StringRef destination = "b", StringRef expected = "") {
    diagnostic.clear();
    auto owner = parseSourceString<mlir::ModuleOp>(
        "module attributes {test_intent = {" + fields.str() +
            "}} { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({\n" + body.str() +
            "}) : () -> () }",
        ParserConfig(&context, false));
    bool ok = !!owner;
    if (owner) {
      Operation *module = &owner->getBody()->front();
      auto fields = owner->getOperation()->getAttrOfType<DictionaryAttr>("test_intent");
      nodal::DomainModel domains(module);
      auto asynchronous = DictionaryAttr::get(
          &context, {NamedAttribute(StringAttr::get(&context, "kind"),
                                    StringAttr::get(&context, "asynchronous"))});
      bool timingBefore =
          succeeded(domains.verifyBoundaryRelationIntent(ref("a"), ref("b"), asynchronous, module));
      SmallVector<bool> permission;
      for (StringRef from : {"a", "b", "c"})
        for (StringRef to : {"a", "b", "c"})
          permission.push_back(domains.allowsDirectSampling(ref(from), ref(to)));
      auto before = print(module);
      diagnostic.clear();
      bool result = succeeded(
          domains.verifyBoundaryRelationIntent(ref(source), ref(destination), fields, module));
      ok &= result == valid && print(module) == before;
      ok &= expected.empty() || StringRef(diagnostic).contains(expected);
      unsigned cursor = 0;
      for (StringRef from : {"a", "b", "c"})
        for (StringRef to : {"a", "b", "c"})
          ok &= domains.allowsDirectSampling(ref(from), ref(to)) == permission[cursor++];
      ok &= succeeded(domains.verifyBoundaryRelationIntent(ref("a"), ref("b"), asynchronous,
                                                           module)) == timingBefore;
      ok &= print(module) == before;
    }
    ++cases;
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL intent-" << name << '\n' << diagnostic;
    }
  }
  void contextual(StringRef name, StringRef topBody, StringRef childBody, StringRef fields,
                  bool valid = true, StringRef destination = "b") {
    diagnostic.clear();
    std::string requirement =
        "\"nodal.domain_requirement\"() <{sym_name = \"req\", metadata = {}}> : () -> ()\n";
    std::string instance =
        "\"nodal.instance\"() <{sym_name = \"child\", module = @Child, parameter_bindings = {}, "
        "domain_bindings = {req = @a}, metadata = {}}> : () -> ()\n"
        "\"nodal.instance_domain_binding\"(%clka) <{instance = @child, requirement = @req, actual "
        "= @a, origin = \"named\", metadata = {}}> : (" +
        clockType + ") -> ()\n";
    auto owner = parseSourceString<mlir::ModuleOp>(
        "module attributes {nodal.root.module = @Top, test_intent = {" + fields.str() +
            "}} { \"nodal.module\"() <{sym_name = \"Top\", metadata = {}}> ({\n" + topBody.str() +
            instance +
            "}) : () -> ()\n\"nodal.module\"() <{sym_name = \"Child\", metadata = {}}> ({\n" +
            requirement + childBody.str() + "}) : () -> () }",
        ParserConfig(&context, false));
    bool ok = !!owner;
    if (owner) {
      std::string before = print(owner->getOperation());
      auto hierarchy = nodal::HierarchyModel::create(owner->getOperation());
      ok &= succeeded(hierarchy);
      if (succeeded(hierarchy)) {
        auto fields = owner->getOperation()->getAttrOfType<DictionaryAttr>("test_intent");
        Operation *child = (*hierarchy)->occurrences()[1].module;
        bool result = succeeded(nodal::verifyBoundaryRelationIntent(ref("req"), ref(destination),
                                                                    fields, child, **hierarchy, 1));
        ok &= result == valid;
        auto a = (*hierarchy)->resolveDomain(1, ref("req"));
        auto b = (*hierarchy)->resolveDomain(1, ref(destination));
        if (succeeded(a) && succeeded(b))
          ok &= !(*hierarchy)->allowsDirectSampling(*a, *b);
      }
      ok &= print(owner->getOperation()) == before;
    }
    ++cases;
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL contextual-intent-" << name << '\n' << diagnostic;
    }
  }
};
} // namespace
int main() {
  Harness test;
  const auto base = setup();
  const auto same = relation("a", "b");
  const auto structuralSame = replace(structural(same), "%clkb", "%clka");
  const auto aliases =
      replace(base, "local_domain_binding\"(%clkb)", "local_domain_binding\"(%clka)");
  test.check("external-same-permits-without-changing-domain-identity", base + same, true, false,
             true);
  test.check("structural-same-exact-input-identity", aliases + structuralSame, true, false, true);
  test.check("physical-alias-without-same-does-not-invent-permission", aliases);
  test.check("same-chain-transitive-symmetric", base + relation("a", "c") + relation("c", "b"),
             true, false, true);
  test.check("same-chain-declaration-order", base + relation("c", "b") + relation("a", "c"), true,
             false, true);
  test.check("same-actual-register-sampling", base + same + sample(), true, true, true);
  test.check("same-reverse-actual-register-sampling", base + same + sample("b", "a"), true, true,
             true);
  test.check("non-same-register-negative", base + sample(), false, true, false, "NODAL-CDC-001");
  for (StringRef kind : {"unknown", "asynchronous", "mutually_exclusive", "synchronous", "ratio"}) {
    auto r = kind == "ratio"
                 ? ratio("a", "b", 1, 1)
                 : relation("a", "b", kind, kind == "synchronous" ? "phase_known = true, " : "");
    test.check((kind + "-valid-not-permission").str(), base + r);
    test.check((kind + "-sampling-rejects").str(), base + r + sample(), false, true, false,
               "NODAL-CDC-001");
  }
  test.check("same-versus-asynchronous", base + same + relation("a", "b", "asynchronous"), false);
  test.check("same-versus-exclusion", base + same + relation("a", "b", "mutually_exclusive"),
             false);
  test.check("unknown-neutral", base + same + relation("a", "b", "unknown"), true, false, true);
  test.check("identity-ratio-compatible-with-same", base + same + ratio("a", "b", 2, 2, "720.0"),
             true, false, true);
  test.check("nonidentity-ratio-inside-same-rejects", base + same + ratio("a", "b", 2, 1), false);
  test.check("nonzero-phase-inside-same-rejects", base + same + ratio("a", "b", 1, 1, "1.0"),
             false);
  test.check("normalized-ratio-duplicates",
             base + ratio("a", "b", 2, 4, "-90.0", "one") + ratio("a", "b", 1, 2, "630.0", "two"));
  test.check("exact-near-phase-not-epsilon",
             base + ratio("a", "b", 1, 1, "0.0", "one") + ratio("a", "b", 1, 1, "1.0E-15", "two"),
             false);
  test.check("exact-signed-scientific-phase", base + ratio("a", "b", 1, 1, "-1.0E-4", "one") +
                                                  ratio("a", "b", 1, 1, "359.9999", "two"));
  test.check("equivalent-diamond",
             base + ratio("a", "b", 2, 1) + ratio("b", "c", 3, 1) + ratio("a", "c", 6, 1));
  test.check("contradictory-diamond",
             base + ratio("a", "b", 2, 1) + ratio("b", "c", 3, 1) + ratio("a", "c", 5, 1), false);
  test.check("reciprocal-ratio-does-not-erase-phase",
             base + ratio("a", "b", 2, 1, "180.0") + ratio("b", "a", 1, 2), false);
  test.check("reciprocal-phase-consistent",
             base + ratio("a", "b", 2, 1, "180.0") + ratio("b", "a", 1, 2, "270.0"));
  test.check("same-physical-ratio-conflict",
             aliases + replace(ratio("a", "b", 2, 1), "%clkb", "%clka"), false);
  test.check("same-physical-asynchronous-conflict",
             aliases + replace(relation("a", "b", "asynchronous"), "%clkb", "%clka"), false);
  test.check("known-synchronous-asynchronous-conflict",
             base + relation("a", "b", "synchronous", "phase_known = true, ") +
                 relation("b", "c", "synchronous", "phase_known = false, ") +
                 relation("a", "c", "asynchronous"),
             false);
  test.check("conflicting-synchronous-phase-known",
             base + relation("a", "b", "synchronous", "phase_known = true, ", "one") +
                 relation("a", "b", "synchronous", "phase_known = false, ", "two"),
             false);
  test.check("exclusive-asynchronous-compatible",
             base + relation("a", "b", "mutually_exclusive") + relation("a", "b", "asynchronous"));
  test.check("exclusive-ratio-compatible-mode-obligation",
             base + relation("a", "b", "mutually_exclusive") + ratio("a", "b", 2, 1));
  test.check("ratio-synchronous-compatible",
             base + ratio("a", "b", 2, 1) +
                 relation("a", "b", "synchronous", "phase_known = true, "));
  const std::string required =
      "\"nodal.domain_requirement\"() <{sym_name = \"req\", metadata = {}}> : () -> "
      "()\n\"nodal.domain_bind\"() <{requirement = @req, actual = @a, metadata = {}}> : () -> ()\n";
  test.check("requirement-resolves-exact-operand",
             base + required + replace(same, "source = @a", "source = @req"), true, false, true);
  test.check("requirement-cannot-forge-clock",
             base + required +
                 replace(replace(same, "source = @a", "source = @req"), "%clka", "%clkc"),
             false);
  test.check("actual-operand-forgery", base + replace(same, "%clkb", "%clkc"), false);
  test.check("foreign-domain-forgery",
             base + replace(same, "destination = @b", "destination = @missing"), false);
  test.check("external-classification-cannot-forge-structural", base + structural(same), false);
  test.check("structural-classification-cannot-forge-external",
             aliases + replace(same, "%clkb", "%clka"), false);
  test.check("missing-obligation", base + replace(same, "active_edges = \"identical_stream\"", ""),
             false);
  test.check("extra-obligation",
             base + replace(same, "active_edges =", "fabricated = true, active_edges ="), false);
  test.check("wrong-obligation", base + replace(same, "identical_stream", "same_frequency"), false);
  test.check("wrong-contract", base + replace(same, "relationship_v1", "descriptor"), false);
  test.check("missing-semantic-identity",
             base + replace(same, "semantic_path = \"Fixture.absame\"", ""), false);
  test.check("foreign-semantic-identity", base + replace(same, "Fixture.absame", "Other.absame"),
             false);
  test.check("duplicate-semantic-identity", base + same + same, false);
  test.check("unexpected-kind-field",
             base + replace(same, "obligations =", "phase_known = true, obligations ="), false);
  test.check("unknown-kind", base + replace(same, "kind = \"same\"", "kind = \"equal_frequency\""),
             false);
  test.check("extra-field", base + replace(same, "}> :", "}> {safe = true} :"), false);
  test.check("missing-actual-operand",
             base + replace(replace(same, "%clka, %clkb", "%clka"), clockType + ", " + clockType,
                            clockType),
             false);
  test.check(
      "incompatible-active-edge",
      replace(base, "sym_name = \"b\", edge = \"rising\"", "sym_name = \"b\", edge = \"falling\"") +
          same,
      false);
  for (StringRef phase : {"NaN", "Infinity", "-Infinity", "1.0E999", "1.0E-9999", "bad", "", "1/2"})
    test.check(("malformed-phase-" + phase).str(), base + ratio("a", "b", 1, 1, phase), false);
  for (StringRef phase : {"90", "090.0", "90.00", "9.0E1", "0.10000000000000001", "1e-4", "1.0e-4"})
    test.check(("noncanonical-phase-" + phase).str(), base + ratio("a", "b", 1, 1, phase), false);
  for (StringRef phase : {"4.9E-324", "9.9E-324", "1.0E23", "1.7976931348623157E308", "-0.0"})
    test.check(("canonical-phase-boundary-" + phase).str(), base + ratio("a", "b", 1, 1, phase));
  for (int invalid : {0, -1, -2147483647}) {
    test.check("invalid-multiply", base + ratio("a", "b", invalid, 1), false);
    test.check("invalid-divide", base + ratio("a", "b", 1, invalid), false);
  }
  test.check("positive-int32-boundary", base + ratio("a", "b", 2147483647, 2147483646));
  test.check("ratio-wrong-integer-width",
             base + replace(ratio("a", "b", 1, 1), "1 : i32", "1 : i64"), false);
  test.check("ratio-missing-field",
             base + replace(ratio("a", "b", 1, 1), "multiply = 1 : i32, ", ""), false);
  test.check("same-known-equal-frequency",
             replace(replace(base, "sym_name = \"a\", edge", "sym_name = \"a\", edge"),
                     "reset_policy = \"none\", metadata = {}",
                     "reset_policy = \"none\", metadata = {frequency_hz = \"100.0\"}") +
                 same,
             true, false, true);
  auto frequencies = domain("a", "", "100.0") + domain("b", "", "200.0") + port("clka") +
                     port("clkb") + binding("a", "clka") + binding("b", "clkb");
  test.check("known-frequency-ratio-consistent", frequencies + ratio("a", "b", 2, 1));
  test.check("known-frequency-same-conflict", frequencies + same, false);
  test.check("known-frequency-ratio-conflict", frequencies + ratio("a", "b", 3, 1), false);
  test.check("malformed-known-frequency",
             replace(frequencies, "100.0", "NaN") + ratio("a", "b", 2, 1), false);
  const std::string descriptor = "\"nodal.clock_relation\"() <{source = @a, destination = @b, kind "
                                 "= \"alias\", metadata = {safe = true}}> : () -> ()\n";
  test.check("descriptor-does-not-authorize", base + descriptor);
  test.check("descriptor-cannot-authorize-sampling", base + descriptor + sample(), false, true,
             false, "NODAL-CDC-001");
  test.check("parent-cycle-independent-negative",
             replace(replace(base, "sym_name = \"a\", edge", "sym_name = \"a\", parent = @b, edge"),
                     "sym_name = \"b\", edge", "sym_name = \"b\", parent = @a, edge") +
                 same,
             false);

  const std::string controls =
      "%one = \"nodal.constant\"() <{value = 1 : i1, metadata = {}}> : () -> " + bit + "\n";
  const std::string gate =
      domain("g", "a") + controls +
      "%g = \"nodal.clock_gate\"(%clka, %one, %one) <{parent_domains = [@a], domain = @g, "
      "edge = \"rising\", contract = \"gate_v1\", control_domains = [\"constant\", \"constant\"], "
      "obligations = {control_timing = \"inactive_phase_aperture\", startup = "
      "\"known_after_inactive_phase\"}, "
      "metadata = {semantic_path = \"Fixture.g\"}}> : (" +
      clockType + ", " + bit + ", " + bit + ") -> " + clockType + "\n" + binding("g", "g");
  test.check("constant-enabled-gate-cannot-be-external-same",
             base + gate + replace(relation("a", "g"), "%clkg", "%g"), false);
  test.check("constant-enabled-gate-cannot-be-structural-same",
             base + gate + replace(structural(relation("a", "g")), "%clkg", "%g"), false);
  std::string gateAliases =
      replace(replace(base,
                      "\"nodal.local_domain_binding\"(%clkb) <{domain = @b, reset_polarity = "
                      "\"active_high\", metadata = {}}> : (" +
                          clockType + ") -> ()\n",
                      ""),
              "\"nodal.local_domain_binding\"(%clkc) <{domain = @c, reset_polarity = "
              "\"active_high\", metadata = {}}> : (" +
                  clockType + ") -> ()\n",
              "") +
      gate + binding("b", "g") + binding("c", "g");
  test.check("same-owned-producer-alias-is-structural",
             gateAliases +
                 replace(replace(structural(relation("b", "c")), "%clkb", "%g"), "%clkc", "%g"));
  const std::string combine = port("left", bit, "a") + port("right", bit, "b") +
                              "%both = \"nodal.digital_expr\"(%left, %right) <{operator_name = "
                              "\"bool_and\", metadata = {semantic_path = \"Fixture.both\"}}> : (" +
                              bit + ", " + bit + ") -> " + bit + "\n";
  const std::string crossing =
      "%crossed = \"nodal.crossing\"(%both) <{source_domain = @a, destination_domain = @c, kind = "
      "\"sync\", contract = \"level_v1\", stages = 2 : i64, metadata = {semantic_path = "
      "\"Fixture.crossed\"}}> : (" +
      bit + ") -> " + bit + "\n";
  test.check("same-mixed-roots-do-not-choose-actual-cdc-source", base + same + combine + crossing,
             false, true, false, "NODAL-CDC-001");
  test.check("same-mixed-roots-ordinary-sampling",
             base + same + combine + replace(sample(), "%state, %data", "%state, %both"), true,
             true, true);
  std::string gateControl = replace(
      replace(gate, "%clka, %one, %one", "%clka, %foreignControl, %one"),
      "control_domains = [\"constant\", \"constant\"]", "control_domains = [@b, \"constant\"]");
  test.check("gate-control-through-verified-same",
             base + same + port("foreignControl", bit, "b") + gateControl, true, true, true);
  test.check("gate-control-without-same-rejects",
             base + port("foreignControl", bit, "b") + gateControl, false, true, false,
             "actual parent or verified Same");
  test.check("gate-control-ratio-does-not-authorize",
             base + ratio("a", "b", 1, 1) + port("foreignControl", bit, "b") + gateControl, false,
             true, false, "actual parent or verified Same");
  const std::string shapedType = "!nodal.shaped<\"2\", " + clockType + ">";
  std::string shaped =
      domain("a") + domain("b") + port("clocks", shapedType) +
      "%i0 = \"nodal.constant\"() <{value = 0 : index, metadata = {}}> : () -> index\n" +
      "%i1 = \"nodal.constant\"() <{value = 1 : index, metadata = {}}> : () -> index\n";
  for (unsigned i = 0; i != 2; ++i)
    shaped += "%clk" + std::string(1, 'a' + i) + " = \"nodal.shape_index\"(%clocks, %i" +
              std::to_string(i) + ") <{metadata = {}}> : (" + shapedType + ", index) -> " +
              clockType + "\n";
  shaped += binding("a", "clka") + binding("b", "clkb");
  test.check("distinct-shaped-clock-elements-external-same", shaped + same, true, true, true);
  test.check("shaped-element-cannot-forge-actual-operand",
             shaped + replace(same, "%clka, %clkb", "%clka, %clka"), false);
  test.check("shaped-same-element-structural",
             replace(shaped, "%clocks, %i1", "%clocks, %i0") + structural(same), true, true, true);
  const std::string resetType = "!nodal.capability<\"reset\">";
  std::string resetSetup = replace(
      replace(base, "sym_name = \"a\", edge = \"rising\", reset_policy = \"none\"",
              "sym_name = \"a\", edge = \"rising\", reset_policy = \"async_assert_sync_release\""),
      "sym_name = \"b\", edge = \"rising\", reset_policy = \"none\"",
      "sym_name = \"b\", edge = \"rising\", reset_policy = \"async_assert_sync_release\"");
  resetSetup = replace(resetSetup, binding("a", "clka"), "");
  resetSetup = replace(resetSetup, binding("b", "clkb"), "");
  resetSetup +=
      port("rst", resetType, "a") +
      "%raw = \"nodal.reset_boundary\"(%rst) <{reset_polarity = \"active_high\", release = "
      "\"raw\", metadata = {semantic_path = \"Fixture.raw\"}}> : (" +
      resetType + ") -> " + resetType + "\n" +
      "%releaseA = \"nodal.reset_sync\"(%raw, %clka) <{destination_domain = @a, stages = 2 : i64, "
      "reset_polarity = \"active_high\", metadata = {semantic_path = \"Fixture.releaseA\"}}> : (" +
      resetType + ", " + clockType + ") -> " + resetType + "\n";
  for (StringRef name : {"a", "b"})
    resetSetup += "\"nodal.local_domain_binding\"(%clk" + name.str() + ", %releaseA) <{domain = @" +
                  name.str() + ", reset_polarity = \"active_high\", metadata = {}}> : (" +
                  clockType + ", " + resetType + ") -> ()\n";
  test.check("same-cannot-reuse-another-physical-reset-release", resetSetup + same, false, true,
             false, "NODAL-RESET");
  auto opposite = replace(replace(frequencies, "sym_name = \"b\", edge = \"rising\"",
                                  "sym_name = \"b\", edge = \"falling\""),
                          "local_domain_binding\"(%clkb)", "local_domain_binding\"(%clka)");
  test.check("same-physical-opposite-edges-frequency-still-consistent",
             opposite + replace(structural(relation("a", "a")), "%clka, %clka", "%clka, %clka"),
             false);

  const std::string sameIntent = "kind = \"same\"";
  const std::string ratioIntent =
      "kind = \"ratio\", multiply = 2 : i32, divide = 1 : i32, phase = \"90.0\"";
  const std::string identityIntent =
      "kind = \"ratio\", multiply = 1 : i32, divide = 1 : i32, phase = \"0.0\"";
  const std::string syncIntent = "kind = \"synchronous\", phase_known = true";
  for (StringRef kind :
       {"same", "ratio", "synchronous", "mutually_exclusive", "asynchronous", "unknown"}) {
    std::string fields = kind == "ratio"         ? ratioIntent
                         : kind == "synchronous" ? syncIntent
                                                 : "kind = \"" + kind.str() + "\"";
    test.intent((kind + "-absent-proof-no-new-permission-or-timing").str(), base, fields);
  }
  test.intent("same-versus-known-frequency", frequencies, sameIntent, false, "a", "b",
              "frequencies");
  test.intent("ratio-independent-frequency-does-not-infer-phase", frequencies, ratioIntent);
  test.intent("ratio-independent-frequency-conflict", frequencies,
              replace(ratioIntent, "2 : i32", "3 : i32"), false);
  test.intent("ratio-known-phase-consistent", base + ratio("a", "b", 2, 1, "90.0"), ratioIntent);
  test.intent("ratio-known-phase-conflict", base + ratio("a", "b", 2, 1, "0.0"), ratioIntent,
              false);
  test.intent("same-known-ratio-conflict", base + ratio("a", "b", 2, 1), sameIntent, false);
  test.intent("same-known-phase-conflict", base + ratio("a", "b", 1, 1, "90.0"), sameIntent, false);
  test.intent("same-known-exclusion", base + relation("a", "b", "mutually_exclusive"), sameIntent,
              false);
  for (const auto &fields : {sameIntent, ratioIntent, syncIntent})
    test.intent("timing-intent-known-asynchronous", base + relation("a", "b", "asynchronous"),
                fields, false);
  test.intent("normalized-asynchronous-through-Same",
              base + same + relation("b", "c", "asynchronous"), ratioIntent, false, "a", "c");
  test.intent("synchronous-conflicting-phase-knowledge",
              base + relation("a", "b", "synchronous", "phase_known = false, "), syncIntent, false);
  test.intent("synchronous-false-preserves-global-ratio-compatibility",
              base + ratio("a", "b", 2, 1), replace(syncIntent, "true", "false"));
  test.intent("asynchronous-transitive-timing",
              base + relation("a", "c", "synchronous", "phase_known = true, ") +
                  relation("c", "b", "synchronous", "phase_known = false, "),
              "kind = \"asynchronous\"", false);
  test.intent("exclusive-Same", base + same, "kind = \"mutually_exclusive\"", false);
  test.intent("exclusive-physical-alias", aliases, "kind = \"mutually_exclusive\"", false);
  test.intent("unknown-does-not-erase-Same", base + same, "kind = \"unknown\"");
  test.intent("unknown-does-not-erase-asynchronous", base + relation("a", "b", "asynchronous"),
              "kind = \"unknown\"");
  test.intent("physical-alias-ratio", aliases, identityIntent);
  test.intent("physical-alias-nonzero-phase", aliases, replace(identityIntent, "0.0", "90.0"),
              false);
  test.intent("gate-Same-boundary-not-global-producer-merge", base + gate, sameIntent, true, "a",
              "g");
  auto oppositeAliases = replace(aliases, "sym_name = \"b\", edge = \"rising\"",
                                 "sym_name = \"b\", edge = \"falling\"");
  auto oppositeInputs =
      replace(base, "sym_name = \"b\", edge = \"rising\"", "sym_name = \"b\", edge = \"falling\"");
  test.intent("opposite-edges-unknown", oppositeAliases, "kind = \"unknown\"");
  test.intent("opposite-edges-Same", oppositeAliases, sameIntent, false);
  test.intent("opposite-edges-do-not-infer-phase", oppositeAliases,
              replace(identityIntent, "0.0", "90.0"));
  test.intent("opposite-edges-same-producer-frequency", oppositeAliases, ratioIntent, false);
  test.intent("opposite-edges-known-correlation", oppositeAliases, "kind = \"asynchronous\"",
              false);
  test.intent("opposite-edges-independent-producers", oppositeInputs, "kind = \"asynchronous\"");
  test.intent("foreign-source", base, sameIntent, false, "missing");
  test.intent("unbound-endpoint", replace(base, binding("b", "clkb"), ""), sameIntent, false);
  for (const auto &fields :
       {std::string("kind = \"other\""), sameIntent + ", phase_known = true",
        sameIntent + ", extra = true", replace(ratioIntent, "2 : i32", "0 : i32"),
        replace(ratioIntent, "2 : i32", "2 : i64"), replace(ratioIntent, "90.0", "90.00"),
        replace(syncIntent, "true", "\"true\""), std::string("kind = \"ratio\"")})
    test.intent("closed-fields", base, fields, false);
  for (const auto &fields : {sameIntent, ratioIntent, syncIntent})
    test.intent("normalized-asynchronous-through-timing",
                base + ratio("a", "b", 2, 1) + relation("b", "c", "asynchronous"), fields, false,
                "a", "c");
  const auto parentFrequency = domain("a", "", "100.0") + port("clka") + binding("a", "clka");
  const auto childFrequency = domain("b", "", "200.0") + port("clkb") + binding("b", "clkb");
  test.contextual("independent-occurrence-frequencies-no-phase-proof", parentFrequency,
                  childFrequency, ratioIntent);
  test.contextual("independent-occurrence-frequency-conflict", parentFrequency, childFrequency,
                  sameIntent, false);
  test.contextual("independent-occurrence-asynchronous", parentFrequency, childFrequency,
                  "kind = \"asynchronous\"");
  test.contextual("independent-occurrence-unknown", parentFrequency, childFrequency,
                  "kind = \"unknown\"");
  test.contextual("independent-occurrence-equal-frequency-no-phase-proof", parentFrequency,
                  replace(childFrequency, "200.0", "100.0"),
                  replace(identityIntent, "0.0", "90.0"));
  test.contextual("opposite-edges-frequency-not-duty", parentFrequency,
                  replace(childFrequency, "rising", "falling"), ratioIntent);
  test.contextual("opposite-edges-Same", parentFrequency,
                  replace(childFrequency, "rising", "falling"), sameIntent, false);
  const auto shadowed = domain("a") + port("clka") + binding("a", "clka");
  test.contextual("lexical-clock-domain-names-not-identity", parentFrequency, shadowed,
                  "kind = \"asynchronous\"", true, "a");
  test.contextual("missing-occurrence-endpoint", parentFrequency, childFrequency,
                  "kind = \"unknown\"", false, "missing");
  const auto inferredParent = domain("a") + domain("c", "", "100.0") + port("clka") + port("clkc") +
                              binding("a", "clka") + binding("c", "clkc") +
                              replace(ratio("c", "a", 2, 1), "Fixture.", "Top.");
  test.contextual("independent-occurrence-frequency-through-existing-relationship", inferredParent,
                  replace(childFrequency, "200.0", "400.0"), ratioIntent);
  test.contextual("independent-occurrence-inferred-frequency-conflict", inferredParent,
                  replace(childFrequency, "200.0", "400.0"), sameIntent, false);
  auto oppositeFrequency =
      replace(oppositeAliases,
              "sym_name = \"a\", edge = \"rising\", reset_policy = \"none\", metadata = {}",
              "sym_name = \"a\", edge = \"rising\", reset_policy = \"none\", metadata = "
              "{frequency_hz = \"100.0\"}");
  oppositeFrequency =
      replace(oppositeFrequency,
              "sym_name = \"c\", edge = \"rising\", reset_policy = \"none\", metadata = {}",
              "sym_name = \"c\", edge = \"rising\", reset_policy = \"none\", metadata = "
              "{frequency_hz = \"200.0\"}");
  test.intent("unannotated-opposite-edge-known-frequency", oppositeFrequency, ratioIntent, true,
              "b", "c");
  test.intent("unannotated-opposite-edge-frequency-conflict", oppositeFrequency, identityIntent,
              false, "b", "c", "frequencies");
  auto inferredOpposite = oppositeAliases + domain("x", "", "100.0") + port("clkx") +
                          binding("x", "clkx") + ratio("x", "a", 2, 1);
  inferredOpposite =
      replace(inferredOpposite,
              "sym_name = \"c\", edge = \"rising\", reset_policy = \"none\", metadata = {}",
              "sym_name = \"c\", edge = \"rising\", reset_policy = \"none\", metadata = "
              "{frequency_hz = \"400.0\"}");
  test.intent("opposite-edge-inferred-frequency", inferredOpposite, ratioIntent, true, "b", "c");
  test.intent("opposite-edge-inferred-frequency-conflict", inferredOpposite, identityIntent, false,
              "b", "c", "frequencies");
  auto conflictingOpposite =
      inferredOpposite + replace(domain("y", "", "100.0"), "rising", "falling") + port("clky") +
      binding("y", "clky") + replace(ratio("y", "b", 3, 1), "%clkb", "%clka");
  test.check("global-opposite-edge-compatible-frequency-without-duty-inference",
             replace(conflictingOpposite, "multiply = 3 : i32, divide = 1 : i32, phase = \"0.0\"",
                     "multiply = 2 : i32, divide = 1 : i32, phase = \"90.0\""));
  test.check("global-opposite-edge-conflicting-independent-frequency-not-first-match",
             conflictingOpposite, false, false, false,
             "inconsistent independently inferred frequencies");
  std::string huge;
  for (unsigned i = 0; i != 140; ++i) {
    std::string name = "n" + std::to_string(i);
    huge += domain(name) + port("clk" + name) + binding(name, "clk" + name);
  }
  for (unsigned i = 1; i != 140; ++i)
    huge += ratio("n" + std::to_string(i - 1), "n" + std::to_string(i), 2147483647, 1);
  test.check("bounded-rational-intermediate-overflow", huge, false, false, false, "4096-bit");
  llvm::outs() << "clock relationship model: " << test.cases << " cases, " << test.failures
               << " failures\n";
  return test.failures ? 1 : 0;
}
