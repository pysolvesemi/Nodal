#include "nodal/Dialect/Nodal/SequentialModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassManager.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/raw_ostream.h"

#include <chrono>
#include <map>
#include <string>
#include <vector>
namespace nodal {
std::unique_ptr<mlir::Pass> createNormalizeSequentialPass();
}
using namespace mlir;
namespace {
const std::string u8 = "!nodal.uint<8>", bit = "!nodal.bits<1>";
std::string wrap(const std::string &body) {
  return "module { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({\n" + body +
         "}) : () -> () }";
}
std::string print(Operation *op) {
  std::string s;
  llvm::raw_string_ostream os(s);
  op->print(os);
  return s;
}
std::string replace(std::string s, const std::string &a, const std::string &b) {
  auto p = s.find(a);
  if (p == std::string::npos)
    llvm::report_fatal_error("test replacement missing");
  s.replace(p, a.size(), b);
  return s;
}
std::string literal(std::string name, int value, std::string type = u8) {
  return "%" + name + " = \"nodal.const_literal\"() <{value = " + std::to_string(value) +
         " : i64, spelling = \"" + std::to_string(value) + "\", metadata = {}}> : () -> " + type +
         "\n";
}
std::string port(std::string name, std::string type = u8, std::string domain = "core") {
  return "\"nodal.port\"() <{sym_name = \"" + name + "\", type = " + type +
         ", direction = \"input\", domain = @" + domain + ", metadata = {}}> : () -> ()\n%" + name +
         " = \"nodal.port_value\"() <{port = @" + name + ", metadata = {}}> : () -> " + type + "\n";
}
std::string domain(std::string policy = "sync", std::string edge = "rising",
                   std::string name = "core") {
  return "\"nodal.domain\"() <{sym_name = \"" + name + "\", edge = \"" + edge +
         "\", reset_policy = \"" + policy + "\", metadata = {}}> : () -> ()\n";
}
std::string binding(std::string policy = "sync", std::string name = "core",
                    std::string polarity = "active_high") {
  std::string s = port(name + "Clock", "!nodal.capability<\"clock\">", name);
  if (policy != "none")
    s += port(name + "Reset", "!nodal.capability<\"reset\">", name);
  return s + "\"nodal.local_domain_binding\"(%" + name + "Clock" +
         (policy == "none" ? "" : ", %" + name + "Reset") + ") <{domain = @" + name +
         ", reset_polarity = \"" + polarity +
         "\", metadata = {}}> : (!nodal.capability<\"clock\">" +
         (policy == "none" ? "" : ", !nodal.capability<\"reset\">") + ") -> ()\n";
}
std::string state(std::string name = "state", bool initialized = true, std::string domain = "core",
                  std::string type = u8) {
  return "%" + name + " = \"nodal.digital_storage\"(" + (initialized ? "%zero" : "") +
         ") <{name = \"" + name + "\", kind = \"register\", domain = @" + domain +
         ", metadata = {semantic_path = \"Fixture." + name + "\"}}> : (" +
         (initialized ? type : "") + ") -> " + type + "\n";
}
std::string assign(std::string target = "state", std::string value = "input",
                   std::string type = u8) {
  return "\"nodal.digital_assign\"(%" + target + ", %" + value +
         ") <{kind = \"register_next\", metadata = {}}> : (" + type + ", " + type + ") -> ()\n";
}
struct Capture {
  std::string source, arg, type = u8;
};
std::string branch(std::string id, std::string cond, std::vector<Capture> a, std::string left,
                   std::vector<Capture> b = {}, std::string right = "",
                   std::string condType = bit) {
  std::string operands = "%" + cond, types = condType;
  auto body = [&](const std::vector<Capture> &captures, const std::string &s) {
    std::string r = "{^bb0(";
    for (unsigned i = 0; i < captures.size(); ++i) {
      auto c = captures[i];
      operands += ", %" + c.source;
      types += ", " + c.type;
      if (i)
        r += ", ";
      r += "%" + c.arg + ": " + c.type;
    }
    return r + "):\n" + s + "}";
  };
  std::string thenBody = body(a, left), elseBody = body(b, right);
  return "\"nodal.behavioral_if\"(" + operands +
         ") <{contract_version = \"behavioral-if-v1\", "
         "region_id = \"Fixture." +
         id + "\", operandSegmentSizes = array<i32: 1, " + std::to_string(a.size()) + ", " +
         std::to_string(b.size()) + ">, metadata = {semantic_path = \"Fixture." + id + "\"}}> (" +
         thenBody + ", " + elseBody + ") : (" + types + ") -> ()\n";
}
std::string setup(std::string policy = "sync", bool initialized = true, std::string edge = "rising",
                  std::string polarity = "active_high") {
  return domain(policy, edge) + binding(policy, "core", polarity) + literal("zero", 0) +
         literal("one", 1) + literal("two", 2) + port("enable", bit) + port("other", bit) +
         port("input") + state("state", initialized);
}
std::string enabled(std::string id = "choice") {
  return branch(id, "enable", {{"state", "s"}, {"input", "v"}}, assign("s", "v"));
}
std::string parameter(bool structural = false) {
  return literal("integerZero", 0, "i64") +
         "\"nodal.parameter\"() <{sym_name = \"MODE\", type = i64, default_value = 0 : i64, "
         "parameter_kind = \"integer\", classification = \"" +
         std::string(structural ? "structural" : "ordinary") +
         "\", variability = \"symbolic\", metadata = {}}> : () -> ()\n"
         "%mode = \"nodal.const_parameter_ref\"() <{parameter = @MODE, metadata = {}}> : () -> "
         "i64\n"
         "%static = \"nodal.static_compare\"(%mode, %integerZero) <{comparison = \"eq\", metadata "
         "= "
         "{semantic_path = \"Fixture.static\"}}> : (i64, i64) -> !nodal.static_predicate\n"
         "%asBool = \"nodal.behavioral_predicate\"(%static) <{metadata = {semantic_path = "
         "\"Fixture.asBool\"}}> "
         ": (!nodal.static_predicate) -> !nodal.bits<1>\n";
}
std::string interfaceInput(std::string type, std::string sourceDomain, bool resolved = false) {
  const std::string tag = "interface_abi_version = 1 : i64";
  const std::string access = resolved ? "connect" : "in";
  const std::string memberType = resolved ? "!nodal.resolved<\"push_pull\", " + type + ">" : type;
  const std::string leaf = "{path = \"data\", access = \"" + access +
                           "\", leaf_role = \"sink\", nested_roles = [], nested_interfaces = []}";
  std::string result =
      "\"nodal.interface\"() <{sym_name = \"Bus\", metadata = {" + tag +
      ", semantic_identity = \"pkg.Bus\"}}> ({\n"
      "\"nodal.interface_role\"() <{sym_name = \"sink\", kind = \"explicit\", metadata = {" +
      tag + ", access = [" + leaf +
      "]}}> : () -> ()\n"
      "\"nodal.interface_member\"() <{sym_name = \"data\", type = " +
      memberType + ", protocol = \"" + (resolved ? "resolved" : "plain") +
      "\", roles = [\"sink\"], metadata = {" + tag +
      "}}> : () -> ()\n}) : () -> ()\n"
      "\"nodal.interface_instance\"() <{sym_name = \"bus\", definition = @Bus, role = \"sink\", "
      "metadata = {" +
      tag + ", domain = @" + sourceDomain +
      ", dimensions = [], semantic_path = \"Fixture.bus\"}}> : () -> ()\n"
      "\"nodal.interface_abi\"() <{logical_path = \"Fixture.bus\", layout_policy = \"structural\", "
      "members = [{path = \"data\", type = " +
      memberType + ", role = \"sink\", access = \"" + access +
      "\", nested_roles = [], nested_interfaces = []}], metadata = {" + tag +
      ", instance = @bus}}> : () -> ()\n";
  // Put the standalone net first so it is the component representative. Its
  // absent own domain must not erase the actual Interface component constraint.
  if (resolved)
    result += "%net = \"nodal.resolved_net\"() <{name = \"net\", metadata = {" + tag +
              ", placement = \"top_level_pin\", profile = \"portable_boundary_only\"}}> : () -> " +
              memberType + "\n";
  result += std::string(resolved ? "%member" : "%ifaceInput") +
            " = \"nodal.member_access\"() <{instance = @bus, path = \"data\", metadata = {" + tag +
            "}}> : () -> " + memberType + "\n";
  if (resolved)
    result += "\"nodal.net_pass_through\"(%net, %member) <{metadata = {" + tag + "}}> : (" +
              memberType + ", " + memberType +
              ") -> ()\n"
              "%ifaceInput = \"nodal.net_read\"(%net) <{metadata = {" +
              tag + "}}> : (" + memberType + ") -> " + type + "\n";
  return result;
}
int evaluate(Value value, const std::map<std::string, int> &values) {
  Operation *op = value.getDefiningOp();
  if (!op)
    return -999;
  if (auto a = op->getAttrOfType<IntegerAttr>("value"))
    return a.getInt();
  if (llvm::isa<nodal::DigitalStorageOp>(op))
    return values.at(op->getAttrOfType<StringAttr>("name").getValue().str());
  if (llvm::isa<nodal::PortValueOp>(op))
    return values.at(op->getAttrOfType<FlatSymbolRefAttr>("port").getValue().str());
  if (llvm::isa<nodal::SequentialSelectOp>(op))
    return evaluate(op->getOperand(evaluate(op->getOperand(0), values) ? 1 : 2), values);
  if (llvm::isa<nodal::BehavioralPredicateOp>(op))
    return evaluate(op->getOperand(0), values);
  if (llvm::isa<nodal::StaticCompareOp>(op))
    return evaluate(op->getOperand(0), values) == evaluate(op->getOperand(1), values);
  if (llvm::isa<nodal::ConstParameterRefOp>(op))
    return values.at(op->getAttrOfType<FlatSymbolRefAttr>("parameter").getValue().str());
  if (auto a = op->getAttrOfType<StringAttr>("operator_name")) {
    if (a.getValue() == "bool_and")
      return evaluate(op->getOperand(0), values) && evaluate(op->getOperand(1), values);
    if (a.getValue() == "digital_alias")
      return evaluate(op->getOperand(0), values);
  }
  return -999;
}
} // namespace
int main(int argc, char **argv) {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream os(diagnostics);
    d.print(os);
    os << '\n';
    return success();
  });
  unsigned cases = 0, failures = 0;
  auto report = [&](StringRef name, bool ok) {
    ++cases;
    failures += !ok;
    (ok ? llvm::outs() : llvm::errs())
        << "SEQUENTIAL_MODEL_" << (ok ? "PASS " : "FAIL ") << name << '\n';
    if (!ok)
      llvm::errs() << diagnostics;
    diagnostics.clear();
  };
  auto parse = [&](const std::string &body) {
    return parseSourceString<mlir::ModuleOp>(wrap(body), ParserConfig(&context, false));
  };
  auto check = [&](StringRef name, const std::string &body, bool expected) {
    auto m = parse(body);
    bool ok = m && succeeded(verify(*m));
    bool causal = true;
    if (!expected && (name.starts_with("cross-domain") || name == "foreign-state-via-wire" ||
                      name == "foreign-guard-via-wire"))
      causal = diagnostics.find("NODAL-CDC-001") != std::string::npos;
    if (!expected && (name == "unrelated-roots" || name == "unconditional-plus-chain" ||
                      name == "unconditional-independent-writes"))
      causal = diagnostics.find("NODAL-STATE-001") != std::string::npos;
    report(name, ok == expected && causal);
    return m;
  };
  auto normalize = [&](StringRef name, const std::string &body, const std::string &outside = "") {
    std::string input = wrap(body);
    input.insert(input.size() - 1, outside);
    auto m = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    bool valid = m && succeeded(verify(*m));
    if (valid) {
      auto owner = *m->getOps<nodal::ModuleOp>().begin();
      valid = succeeded(nodal::normalizeSequentialModel(owner)) && succeeded(verify(*m));
      if (valid) {
        std::string first = print(*m);
        valid = succeeded(nodal::normalizeSequentialModel(owner)) && first == print(*m);
        auto roundtrip = parseSourceString<mlir::ModuleOp>(first, ParserConfig(&context, false));
        valid &= roundtrip && succeeded(verify(*roundtrip)) && first == print(*roundtrip);
      }
    }
    report(name, valid);
    return m;
  };
  if (argc > 1 && std::string(argv[1]) == "--emit-fixture") {
    llvm::outs() << wrap(setup() + enabled());
    return 0;
  }
  normalize("sync-enable-hold", setup() + enabled());
  normalize("falling-async-active-low", setup("async", true, "falling", "active_low") + enabled());
  normalize("resetless-uninitialized", setup("none", false) + enabled());
  auto uninitialized =
      normalize("reset-domain-uninitialized-opts-out", setup("sync", false) + enabled());
  if (uninitialized)
    uninitialized->walk([&](nodal::SequentialUpdateOp op) {
      report("uninitialized-no-fabricated-reset",
             op->getNumOperands() == 3 &&
                 op->getAttrOfType<StringAttr>("reset_policy").getValue() == "none");
    });
  normalize("declared-register-no-update-holds", setup());
  normalize("unconditional-update", setup() + assign());
  auto last =
      normalize("same-arm-last-write",
                setup() + branch("last", "enable", {{"state", "s"}, {"one", "a"}, {"two", "b"}},
                                 assign("s", "a") + assign("s", "b")));
  if (last)
    last->walk([&](nodal::SequentialUpdateOp op) {
      bool ok = true;
      for (int enable : {0, 1})
        ok &= evaluate(op->getOperand(1), {{"enable", enable}, {"state", 17}}) == (enable ? 2 : 17);
      report("same-arm-executed-truth-table", ok);
    });
  std::string inner = branch("choice.inner", "e", {{"s", "ss"}, {"t", "b"}}, assign("ss", "b"));
  auto nested =
      normalize("nested-write-preserves-earlier-write",
                setup() + branch("choice", "enable",
                                 {{"state", "s"}, {"one", "a"}, {"two", "t"}, {"other", "e", bit}},
                                 assign("s", "a") + inner));
  if (nested)
    nested->walk([&](nodal::SequentialUpdateOp op) {
      bool ok = true;
      for (int a : {0, 1})
        for (int b : {0, 1})
          ok &= evaluate(op->getOperand(1), {{"enable", a}, {"other", b}, {"state", 17}}) ==
                (a ? (b ? 2 : 1) : 17);
      report("nested-priority-executed-truth-table", ok);
    });
  std::string second = branch("choice.else", "e", {{"s", "ss"}, {"t", "b"}}, assign("ss", "b"));
  auto priority = normalize(
      "elsewhen-first-selected",
      setup() + branch("choice", "enable", {{"state", "s"}, {"one", "a"}}, assign("s", "a"),
                       {{"state", "s"}, {"two", "t"}, {"other", "e", bit}}, second));
  if (priority)
    priority->walk([&](nodal::SequentialUpdateOp op) {
      bool ok = true;
      for (int a : {0, 1})
        for (int b : {0, 1})
          ok &= evaluate(op->getOperand(1), {{"enable", a}, {"other", b}, {"state", 17}}) ==
                (a   ? 1
                 : b ? 2
                     : 17);
      report("elsewhen-executed-truth-table", ok);
    });
  normalize(
      "ordinary-parameter-behavioral-guard",
      setup() + parameter() +
          branch("staticChoice", "asBool", {{"state", "s"}, {"input", "v"}}, assign("s", "v")));
  normalize("ordinary-static-direct-guard",
            setup() + parameter() +
                branch("staticChoice", "static", {{"state", "s"}, {"input", "v"}}, assign("s", "v"),
                       {}, "", "!nodal.static_predicate"));
  auto mixed = normalize(
      "mixed-parameter-runtime",
      setup() + parameter() +
          "%mixed = \"nodal.digital_expr\"(%asBool, %enable) <{operator_name = \"bool_and\", "
          "metadata = {}}> : (!nodal.bits<1>, !nodal.bits<1>) -> !nodal.bits<1>\n" +
          branch("mixedChoice", "mixed", {{"state", "s"}, {"input", "v"}}, assign("s", "v")));
  if (mixed)
    mixed->walk([&](nodal::SequentialUpdateOp op) {
      bool ok = true;
      for (int mode : {0, 1})
        for (int enable : {0, 1})
          ok &= evaluate(op->getOperand(1),
                         {{"MODE", mode}, {"enable", enable}, {"state", 17}, {"input", 99}}) ==
                (mode == 0 && enable ? 99 : 17);
      report("mixed-executed-truth-table", ok);
    });
  std::string joined =
      "%joined = \"nodal.structural_if\"(%pred, %a, %b) <{contract_version = \"structural-if-v1\", "
      "region_id = \"Fixture.valueJoin\", operandSegmentSizes = array<i32: 1, 1, 1>, metadata = "
      "{semantic_path = \"Fixture.valueJoin\"}}> "
      "({^bb0(%x: !nodal.uint<8>): \"nodal.structural_yield\"(%x) <{metadata = {semantic_path = "
      "\"Fixture.valueJoin.then.yield\"}}> : (!nodal.uint<8>) -> ()}, "
      "{^bb0(%y: !nodal.uint<8>): \"nodal.structural_yield\"(%y) <{metadata = {semantic_path = "
      "\"Fixture.valueJoin.else.yield\"}}> : (!nodal.uint<8>) -> ()}) : (!nodal.static_predicate, "
      "!nodal.uint<8>, !nodal.uint<8>) -> !nodal.uint<8>\n";
  normalize("read-only-structural-RHS-in-behavior",
            setup() + parameter(true) +
                branch("withValue", "enable",
                       {{"state", "s"},
                        {"input", "a"},
                        {"one", "b"},
                        {"static", "pred", "!nodal.static_predicate"}},
                       joined + assign("s", "joined")));
  check("ordinary-parameter-cannot-select-structure",
        setup() + parameter(false) +
            branch("withValue", "enable",
                   {{"state", "s"},
                    {"input", "a"},
                    {"one", "b"},
                    {"static", "pred", "!nodal.static_predicate"}},
                   joined + assign("s", "joined")),
        false);
  std::string shaped = "!nodal.shaped<\"2,3\", !nodal.uint<8>>";
  normalize("whole-recursive-shaped-register",
            setup() + port("shapedInput", shaped) + state("shapedState", false, "core", shaped) +
                branch("shaped", "enable",
                       {{"shapedState", "s", shaped}, {"shapedInput", "v", shaped}},
                       assign("s", "v", shaped)));
  std::string enumDefinition =
      "\"nodal.enum\"() <{sym_name = \"Mode\", underlying_type = !nodal.uint<2>, encoding = "
      "\"custom\", metadata = {abi_version = 1 : i64, semantic_type = \"Mode\", abi_hash = "
      "\"df5b4a51f3fade96f1ab8a106bbcb0114b5d4601e4e2c6aebcf38763dd61e66a\"}}> ({"
      "\"nodal.enum_case\"() <{sym_name = \"Idle\", value = 0 : i64, metadata = {case_id = "
      "\"Mode.Idle\"}}> : () -> () "
      "\"nodal.enum_case\"() <{sym_name = \"Run\", value = 1 : i64, metadata = {case_id = "
      "\"Mode.Run\"}}> : () -> () }) : () -> ()";
  std::string enumType = "!nodal.enum<\"Mode\", 2>";
  normalize("canonical-enum-register-ABI-retained",
            setup() + port("enumInput", enumType) + state("enumState", false, "core", enumType) +
                branch("enumChoice", "enable",
                       {{"enumState", "s", enumType}, {"enumInput", "v", enumType}},
                       assign("s", "v", enumType)),
            enumDefinition);
  for (std::string polarity : {"active_high", "active_low"}) {
    auto resetCase = normalize("reset-dominates-enable-" + polarity,
                               setup("sync", true, "rising", polarity) + enabled());
    if (resetCase)
      resetCase->walk([&](nodal::SequentialUpdateOp op) {
        bool ok = true;
        for (int reset : {0, 1})
          for (int enable : {0, 1}) {
            bool asserted = polarity == "active_high" ? reset : !reset;
            int actual = asserted ? evaluate(op->getOperand(4), {})
                                  : evaluate(op->getOperand(1),
                                             {{"enable", enable}, {"state", 17}, {"input", 99}});
            ok &= actual == (asserted ? 0 : enable ? 99 : 17);
          }
        report("reset-enable-executed-truth-table-" + polarity, ok && op->getNumOperands() == 5);
      });
  }
  check("unrelated-roots", setup() + enabled() + enabled("second"), false);
  check("unconditional-plus-chain", setup() + assign() + enabled(), false);
  check("unconditional-independent-writes", setup() + assign() + assign("state", "one"), false);
  check("resetless-init-rejected", setup("none", true) + assign(), false);
  check("async-release-not-approximated", setup("async_assert_sync_release") + assign(), false);
  check("invalid-edge", setup("sync", true, "both") + assign(), false);
  check("invalid-polarity", setup("sync", true, "rising", "unknown") + assign(), false);
  check("bool-cannot-clock",
        replace(setup() + assign(), "\"nodal.local_domain_binding\"(%coreClock, %coreReset)",
                "\"nodal.local_domain_binding\"(%enable, %coreReset)"),
        false);
  check("missing-reset", replace(setup() + assign(), "%coreClock, %coreReset)", "%coreClock)"),
        false);
  check("duplicate-binding",
        setup() +
            "\"nodal.local_domain_binding\"(%coreClock, %coreReset) <{domain = @core, "
            "reset_polarity = \"active_high\", metadata = {}}> : (!nodal.capability<\"clock\">, "
            "!nodal.capability<\"reset\">) -> ()\n" +
            assign(),
        false);
  check("foreign-physical-domain",
        replace(setup() + assign(), "domain = @core, metadata = {}}>",
                "domain = @other, metadata = {}>"),
        false);
  check("structural-declaration-in-arm", setup() + branch("bad", "enable", {}, state("bad", false)),
        false);
  check("continuous-write-in-arm", setup() + replace(enabled(), "register_next", "continuous"),
        false);
  check("non-Bool-guard", setup() + branch("bad", "input", {}, "", {}, "", u8), false);
  check("no-physical-binding-for-behavior",
        domain() + literal("zero", 0) + port("enable", bit) + port("input") + state() + enabled(),
        false);
  check("descriptor-only-register-intent-preserved",
        domain() + literal("zero", 0) + port("input") + state() + assign() +
            assign("state", "zero"),
        true);
  std::string foreign = domain("none", "rising", "foreign") + binding("none", "foreign") +
                        state("foreignState", false, "foreign");
  check("cross-domain-RHS", setup() + foreign + assign("state", "foreignState"), false);
  check("cross-domain-RHS-via-alias",
        setup() + foreign +
            "%alias = \"nodal.digital_expr\"(%foreignState) <{operator_name = \"digital_alias\", "
            "metadata = {}}> : (!nodal.uint<8>) -> !nodal.uint<8>\n" +
            assign("state", "alias"),
        false);
  check("cross-domain-captured-RHS",
        setup() + foreign +
            branch("bad", "enable", {{"state", "s"}, {"foreignState", "v"}}, assign("s", "v")),
        false);
  std::string foreignBool = domain("none", "rising", "foreign") + binding("none", "foreign") +
                            state("foreignBit", false, "foreign", bit);
  check("cross-domain-guard",
        setup() + foreignBool +
            branch("bad", "foreignBit", {{"state", "s"}, {"input", "v"}}, assign("s", "v")),
        false);
  auto wire = [](std::string name, std::string type) {
    return "%" + name + " = \"nodal.digital_storage\"() <{name = \"" + name +
           "\", kind = \"wire\", metadata = {}}> : () -> " + type + "\n";
  };
  auto continuous = [&](std::string target, std::string value, std::string type) {
    return replace(assign(target, value, type), "register_next", "continuous");
  };
  check("foreign-state-via-wire",
        setup() + foreign + wire("bridge", u8) + continuous("bridge", "foreignState", u8) +
            assign("state", "bridge"),
        false);
  normalize("same-domain-state-via-wire",
            setup() + state("otherState", false) + wire("bridge", u8) +
                continuous("bridge", "otherState", u8) + assign("state", "bridge"));
  check("foreign-guard-via-wire",
        setup() + foreignBool + wire("bridge", bit) + continuous("bridge", "foreignBit", bit) +
            branch("guard", "bridge", {{"state", "s"}, {"input", "v"}}, assign("s", "v")),
        false);
  const std::string requirement =
      "\"nodal.domain_requirement\"() <{sym_name = \"required\", metadata = {}}> : () -> ()\n";
  auto requirementBind = [](std::string actual) {
    return "\"nodal.domain_bind\"() <{requirement = @required, actual = @" + actual +
           ", metadata = {}}> : () -> ()\n";
  };
  check("cross-domain-unresolved-requirement-RHS",
        setup() + requirement + port("requiredInput", u8, "required") +
            assign("state", "requiredInput"),
        false);
  check("cross-domain-unresolved-requirement-guard",
        setup() + requirement + port("requiredGuard", bit, "required") +
            branch("requiredGuard", "requiredGuard", {{"state", "s"}, {"input", "v"}},
                   assign("s", "v")),
        false);
  normalize("same-domain-requirement-binding", setup() + requirement + requirementBind("core") +
                                                   port("requiredInput", u8, "required") +
                                                   assign("state", "requiredInput"));
  check("cross-domain-foreign-requirement-binding",
        setup() + domain("none", "rising", "foreign") + requirement + requirementBind("foreign") +
            port("requiredInput", u8, "required") + assign("state", "requiredInput"),
        false);
  check("cross-domain-duplicate-requirement-binding",
        setup() + requirement + requirementBind("core") + requirementBind("core") +
            port("requiredInput", u8, "required") + assign("state", "requiredInput"),
        false);
  check("cross-domain-invalid-requirement-target",
        setup() + requirement + requirementBind("input") + port("requiredInput", u8, "required") +
            assign("state", "requiredInput"),
        false);
  normalize("unannotated-input-boundary",
            setup() + port("boundaryInput", u8, "none") + assign("state", "boundaryInput"));
  check("cross-domain-unknown-input-symbol",
        setup() + port("unknownInput", u8, "unknown") + assign("state", "unknownInput"), false);
  check("cross-domain-declared-none-is-constrained",
        setup() + replace(requirement, "sym_name = \"required\"", "sym_name = \"none\"") +
            port("noneInput", u8, "none") + assign("state", "noneInput"),
        false);
  check("cross-domain-interface-RHS",
        setup() + domain("none", "rising", "foreign") + interfaceInput(u8, "foreign") +
            assign("state", "ifaceInput"),
        false);
  check("cross-domain-interface-guard",
        setup() + domain("none", "rising", "foreign") + interfaceInput(bit, "foreign") +
            branch("interfaceGuard", "ifaceInput", {{"state", "s"}, {"input", "v"}},
                   assign("s", "v")),
        false);
  normalize("same-domain-interface-RHS",
            setup() + interfaceInput(u8, "core") + assign("state", "ifaceInput"));
  normalize("same-domain-interface-guard",
            setup() + interfaceInput(bit, "core") +
                branch("interfaceGuard", "ifaceInput", {{"state", "s"}, {"input", "v"}},
                       assign("s", "v")));
  check("cross-domain-interface-via-wire",
        setup() + domain("none", "rising", "foreign") + interfaceInput(u8, "foreign") +
            wire("interfaceWire", u8) + continuous("interfaceWire", "ifaceInput", u8) +
            assign("state", "interfaceWire"),
        false);
  check("cross-domain-resolved-component",
        setup() + domain("none", "rising", "foreign") + interfaceInput(u8, "foreign", true) +
            assign("state", "ifaceInput"),
        false);
  normalize("same-domain-resolved-component",
            setup() + interfaceInput(u8, "core", true) + assign("state", "ifaceInput"));
  check("zero-operand-assign",
        setup() +
            "\"nodal.digital_assign\"() <{kind = \"register_next\", metadata = {}}> : () -> ()\n",
        false);
  check("zero-operand-normalized-update",
        setup() + "\"nodal.sequential_update\"() <{domain = @core, edge = \"rising\", reset_policy "
                  "= \"none\", reset_polarity = \"active_high\", root_id = \"Fixture.bad\", "
                  "metadata = {}}> : () -> ()\n",
        false);
  {
    std::string first = wrap(setup() + enabled());
    std::string second = replace(wrap(setup("none", true) + assign()), "sym_name = \"Fixture\"",
                                 "sym_name = \"Second\"");
    first.insert(first.size() - 1, second.substr(9, second.size() - 10));
    auto m = parseSourceString<mlir::ModuleOp>(first, ParserConfig(&context, false));
    bool ok = bool(m);
    if (m) {
      std::string before = print(*m);
      PassManager pm(&context);
      pm.enableVerifier(false);
      pm.addPass(nodal::createNormalizeSequentialPass());
      ok = failed(pm.run(*m)) && before == print(*m);
    }
    report("two-module-pass-failure-transactional", ok);
  }
  auto bounded = parse(setup() + enabled());
  if (bounded) {
    auto m = *bounded->getOps<nodal::ModuleOp>().begin();
    std::string before = print(*bounded);
    nodal::SequentialModelLimits limits;
    limits.maximumNormalizedOperations = 1;
    report("normalization-budget-transactional",
           failed(nodal::normalizeSequentialModel(m, limits)) && before == print(*bounded));
    limits = {};
    limits.maximumDepth = 0;
    report("depth-budget", failed(nodal::verifySequentialModel(m, limits)));
    limits = {};
    limits.maximumCaptures = 1;
    report("capture-budget", failed(nodal::verifySequentialModel(m, limits)));
  }
  std::string many = setup();
  for (unsigned i = 0; i < 128; ++i)
    many += state("reg" + std::to_string(i), false) + assign("reg" + std::to_string(i), "input");
  auto start = std::chrono::steady_clock::now();
  auto scale = normalize("128-register-deterministic", many);
  auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() -
                                                                  start)
                .count();
  llvm::outs() << "SEQUENTIAL_MODEL_SCALE registers=129 elapsed_ms=" << ms << "\n";
  llvm::outs() << "SEQUENTIAL_MODEL_SUMMARY cases=" << cases << " failures=" << failures << "\n";
  return failures ? 1 : 0;
}
