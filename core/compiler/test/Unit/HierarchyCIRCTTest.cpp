#include "circt/Dialect/HW/HWOps.h"
#include "circt/Dialect/Seq/SeqOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassManager.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/Support/raw_ostream.h"
using namespace mlir;
namespace {
const std::string bit = "!nodal.bits<1>", clockType = "!nodal.capability<\"clock\">",
                  resetType = "!nodal.capability<\"reset\">";
std::string owner(StringRef name, const std::string &body) {
  return "\"nodal.module\"() <{sym_name = \"" + name.str() + "\", metadata = {}}> ({\n" + body +
         "}) : () -> ()\n";
}
std::string port(StringRef name, StringRef direction, StringRef domain,
                 const std::string &type = bit) {
  return "\"nodal.port\"() <{sym_name = \"" + name.str() + "\", type = " + type +
         ", direction = \"" + direction.str() + "\", domain = @" + domain.str() +
         ", metadata = {}}> : () -> ()\n%" + name.str() + " = \"nodal.port_value\"() <{port = @" +
         name.str() + ", metadata = {}}> : () -> " + type + "\n";
}
std::string domain(StringRef name, StringRef edge = "rising", StringRef polarity = "active_high",
                   bool reset = true) {
  auto n = name.str();
  return "\"nodal.domain\"() <{sym_name = \"" + n + "\", edge = \"" + edge.str() +
         "\", reset_policy = \"" + (reset ? "sync" : "none") + "\", metadata = {}}> : () -> ()\n" +
         port("clock_" + n, "input", name, clockType) +
         (reset ? port("reset_" + n, "input", name, resetType) : "") +
         "\"nodal.local_domain_binding\"(%clock_" + n + (reset ? ", %reset_" + n : "") +
         ") <{domain = @" + n + ", reset_polarity = \"" + polarity.str() +
         "\", metadata = {}}> : (" + clockType + (reset ? ", " + resetType : "") + ") -> ()\n";
}
std::string requirement(bool projections = false) {
  return "\"nodal.domain_requirement\"() <{sym_name = \"req\", metadata = {}}> : () -> ()\n" +
         (projections
              ? "%clock_req = \"nodal.requirement_clock\"() <{requirement = @req, metadata = "
                "{}}> : () -> " +
                    clockType +
                    "\n%reset_req = \"nodal.requirement_reset\"() <{requirement = @req, metadata "
                    "= {}}> : () -> " +
                    resetType + "\n"
              : "");
}
std::string assign(StringRef target, StringRef value, StringRef kind = "continuous") {
  return "\"nodal.digital_assign\"(%" + target.str() + ", %" + value.str() + ") <{kind = \"" +
         kind.str() + "\", metadata = {}}> : (" + bit + ", " + bit + ") -> ()\n";
}
std::string instance(StringRef name, StringRef target, StringRef actual, StringRef input,
                     bool reset = true) {
  auto n = name.str();
  return "\"nodal.instance\"() <{sym_name = \"" + n + "\", module = @" + target.str() +
         ", domain_bindings = {req = @" + actual.str() +
         "}, parameter_bindings = {}, metadata = {}}> : () -> ()\n"
         "\"nodal.instance_domain_binding\"(%clock_" +
         actual.str() + (reset ? ", %reset_" + actual.str() : "") + ") <{instance = @" + n +
         ", requirement = @req, actual = @" + actual.str() +
         ", origin = \"named\", metadata = {}}> : (" + clockType + (reset ? ", " + resetType : "") +
         ") -> () loc(\"hierarchy-target.scala\":21:7)\n%" + n +
         "_input = \"nodal.instance_port\"() <{instance = @" + n +
         ", port = \"input\", direction = \"input\", metadata = {}}> : () -> " + bit + "\n%" + n +
         "_output = \"nodal.instance_port\"() <{instance = @" + n +
         ", port = \"output\", direction = \"output\", metadata = {}}> : () -> " + bit + "\n" +
         assign(n + "_input", input);
}
std::string leaf(bool initialized = true) {
  return requirement() + port("input", "input", "req") + port("output", "output", "req") +
         "%zero = \"nodal.const_literal\"() <{value = false, spelling = \"0\", metadata = "
         "{}}> : () -> " +
         bit + "\n%state = \"nodal.digital_storage\"(" + (initialized ? "%zero" : "") +
         ") <{name = \"state\", kind = \"register\", domain = @req, metadata = {}}> : (" +
         (initialized ? bit : "") + ") -> " + bit + "\n" +
         assign("state", "input", "register_next") + assign("output", "state");
}
std::string fixture(bool material = false, bool nested = false, bool resetless = false,
                    unsigned repeated = 2) {
  std::string top = domain("a") +
                    domain("b", material ? "falling" : "rising",
                           material ? "active_low" : "active_high", !resetless) +
                    port("x", "input", "a") + port("y", "input", "b");
  for (unsigned i = 0; i < repeated; ++i) {
    auto name = "child" + std::to_string(i);
    auto actual = i % 2 ? "b" : "a";
    auto target = nested && i % 2 ? "Forward" : resetless && i % 2 ? "Resetless" : "Child";
    top += instance(name, target, actual, i % 2 ? "y" : "x", !(resetless && i % 2));
    top += port("result" + std::to_string(i), "output", actual) +
           assign("result" + std::to_string(i), name + "_output");
  }
  std::string result = "module attributes {nodal.root.module = @Top} {\n" + owner("Top", top) +
                       owner("Child", leaf());
  if (nested)
    result +=
        owner("Forward",
              requirement(true) + port("input", "input", "req") + port("output", "output", "req") +
                  instance("leaf", "Child", "req", "input") + assign("output", "leaf_output"));
  if (resetless)
    result += owner("Resetless", leaf(false));
  return result + "}\n";
}
std::string replace(std::string text, StringRef from, StringRef to) {
  auto at = text.find(from.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing hierarchy target mutation");
  return text.replace(at, from.size(), to.str());
}
std::string conditionalFixture(bool queryOnly = false) {
  std::string top = domain("a", "rising", "active_low") +
                    domain("b", "falling", "active_high", false) + port("x", "input", "a") +
                    port("y", "input", "b") + instance("left", "Child", "a", "x") +
                    instance("right", "Child", "b", "y", false) +
                    port("leftResult", "output", "a") + assign("leftResult", "left_output") +
                    port("rightResult", "output", "b") + assign("rightResult", "right_output");
  std::string child =
      queryOnly ? requirement() + port("input", "input", "req") + port("output", "output", "req") +
                      "%asserted = \"nodal.reset_asserted\"() <{domain = @req, metadata = "
                      "{semantic_path = \"Child.asserted\"}}> : () -> " +
                      bit + "\n" + assign("output", "asserted")
                : replace(leaf(), "kind = \"register\", domain = @req,",
                          "kind = \"register\", domain = @req, initializer_policy = \"if_reset\",");
  return "module attributes {nodal.root.module = @Top} {\n" + owner("Top", top) +
         owner("Child", child) + "}\n";
}
std::string generatedFixture(bool symbolic = true, bool choice = true) {
  std::string parameters = symbolic ? R"mlir(
    "nodal.parameter"() <{sym_name = "N", type = i64, classification = "structural", variability = "symbolic", default_value = 3 : i64, metadata = {}}> : () -> ()
    %minimum = "nodal.const_literal"() <{value = 1 : i64, spelling = "1", metadata = {}}> : () -> i64
    %maximum = "nodal.const_literal"() <{value = 8 : i64, spelling = "8", metadata = {}}> : () -> i64
    "nodal.parameter_constraint"(%minimum, %maximum) <{parameter = @N, constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}}> : (i64, i64) -> ()
    "nodal.parameter_envelope"() <{parameter = @N, effects = ["shape", "topology"], policy = "static_generate", metadata = {}}> : () -> ()
    %upper = "nodal.const_parameter_ref"() <{parameter = @N, metadata = {}}> : () -> i64
  )mlir"
                                    : R"mlir(
    %upper = "nodal.const_literal"() <{value = 3 : i64, spelling = "3", metadata = {}}> : () -> i64
  )mlir";
  std::string body = R"mlir(
    %zero = "nodal.const_literal"() <{value = false, spelling = "0", metadata = {}}> : () -> !nodal.bits<1>
    %state = "nodal.digital_storage"(%zero) <{name = "state", kind = "register", domain = @a, metadata = {semantic_path = "Top.family.state"}}> : (!nodal.bits<1>) -> !nodal.bits<1>
  )mlir";
  if (choice)
    body += R"mlir(
    %predicate = "nodal.static_compare"(%i, %lower) <{comparison = "eq", metadata = {semantic_path = "Top.family.predicate"}}> : (i64, i64) -> !nodal.static_predicate
    "nodal.structural_if"(%predicate, %state, %input) <{contract_version = "structural-if-v1", region_id = "Top.family.choice", operandSegmentSizes = array<i32: 1, 2, 0>, metadata = {semantic_path = "Top.family.choice"}}> ({
    ^bb0(%s: !nodal.bits<1>, %v: !nodal.bits<1>):
      "nodal.digital_assign"(%s, %v) <{kind = "register_next", metadata = {}}> : (!nodal.bits<1>, !nodal.bits<1>) -> ()
      "nodal.structural_yield"() <{metadata = {semantic_path = "Top.family.choice.then.yield"}}> : () -> ()
    }, {
      "nodal.structural_yield"() <{metadata = {semantic_path = "Top.family.choice.else.yield"}}> : () -> ()
    }) : (!nodal.static_predicate, !nodal.bits<1>, !nodal.bits<1>) -> ()
    )mlir";
  else
    body += assign("state", "input", "register_next");
  body += R"mlir(
    "nodal.generated_export"(%state) <{member = "state", access = "register", metadata = {}}> : (!nodal.bits<1>) -> ()
  )mlir";
  std::string family = R"mlir(
    %lower = "nodal.const_literal"() <{value = 0 : i64, spelling = "0", metadata = {}}> : () -> i64
    %step = "nodal.const_literal"() <{value = 1 : i64, spelling = "1", metadata = {}}> : () -> i64
    %family = "nodal.generate"(%lower, %upper, %step) <{induction = "i", induction_path = "Top.family.i", region_id = "Top.family", metadata = {maximum_trip_count = )mlir" +
                       std::string(symbolic ? "8" : "3") + R"mlir( : i64}}> ({
    ^bb0(%i: i64):
  )mlir" + body + R"mlir(
    }) : (i64, i64, i64) -> !nodal.generated_family
    %selected = "nodal.generated_member"(%family, %lower) <{member = "state", access = "read", metadata = {}}> : (!nodal.generated_family, i64) -> !nodal.bits<1>
  )mlir";
  return "module attributes {nodal.root.module = @Top} {\n" +
         owner("Top", parameters + domain("a") + port("input", "input", "a") +
                          port("output", "output", "a") + family + assign("output", "selected")) +
         "}\n";
}

std::string nestedGeneratedFixture(unsigned depth = 2, bool symbolic = true) {
  std::string input = generatedFixture(symbolic, false);
  const std::string originalPath = "Top.family";
  std::string path = "Top";
  for (unsigned i = 0; i < depth; ++i)
    path += ".family";
  for (size_t at = 0; (at = input.find(originalPath, at)) != std::string::npos; at += path.size())
    input.replace(at, originalPath.size(), path);
  auto begin = input.find("%family =");
  auto end = input.find("%selected =", begin);
  std::string body = input.substr(begin, end - begin);
  for (unsigned i = depth - 1; i-- > 0;) {
    std::string owner = "Top";
    for (unsigned j = 0; j <= i; ++j)
      owner += ".family";
    std::string handle = i + 1 == depth - 1 ? "family" : "family" + std::to_string(i + 1);
    body = "%family" + std::to_string(i) +
           " = \"nodal.generate\"(%lower, %outerUpper, %step) <{induction = \"i\", "
           "induction_path = \"" +
           owner + ".i\", region_id = \"" + owner +
           "\", metadata = {maximum_trip_count = 2 : i64}}> ({^bb0(%outer" + std::to_string(i) +
           ": i64):\n" + body + "\"nodal.generated_export\"(%" + handle +
           ") <{member = \"inner\", access = \"read\", kind = \"family\", metadata = {}}> : "
           "(!nodal.generated_family) -> ()\n}) : (i64, i64, i64) -> !nodal.generated_family\n";
  }
  std::string selection;
  std::string handle = "family0";
  for (unsigned i = 0; i + 1 < depth; ++i) {
    std::string view = "view" + std::to_string(i);
    selection += "%" + view + " = \"nodal.generated_member\"(%" + handle +
                 ", %step) <{member = \"inner\", access = \"read\", kind = \"family\", "
                 "metadata = {}}> : (!nodal.generated_family, i64) -> !nodal.generated_family\n";
    handle = view;
  }
  std::string suffix = input.substr(end);
  suffix = replace(suffix, "generated_member\"(%family,", "generated_member\"(%" + handle + ",");
  return input.substr(0, begin) +
         "%outerUpper = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\", "
         "metadata = {}}> : () -> i64\n" +
         body + selection + suffix;
}

std::string nestedMatrixFixture() {
  const std::string matrix = "!nodal.shaped<\"2,N\", !nodal.bits<1>>";
  const std::string row = "!nodal.shaped<\"N\", !nodal.bits<1>>";
  auto slice = [&](StringRef name, StringRef input, StringRef index, const std::string &inputType,
                   const std::string &resultType) {
    return "%" + name.str() + " = \"nodal.shape_index\"(%" + input.str() + ", %" + index.str() +
           ") <{metadata = {semantic_path = \"Top." + name.str() + "\"}}> : (" + inputType +
           ", i64) -> " + resultType + "\n";
  };
  std::string input =
      replace(nestedGeneratedFixture(), port("input", "input", "a"),
              port("matrixIn", "input", "a", matrix) + port("matrixOut", "output", "a", matrix));
  input = replace(input, "%family =",
                  slice("rowIn", "matrixIn", "outer0", matrix, row) +
                      slice("rowOut", "matrixOut", "outer0", matrix, row) + "%family =");
  input = replace(input, "%zero =", slice("input", "rowIn", "i", row, bit) + "%zero =");
  input = replace(input, "%family = \"nodal.generate\"(%lower, %upper, %step)",
                  "%innerLower = \"nodal.const_literal\"() <{value = 0 : i64, spelling = \"0\", "
                  "metadata = {}}> : () -> i64\n"
                  "%innerStep = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", "
                  "metadata = {}}> : () -> i64\n"
                  "%family = \"nodal.generate\"(%innerLower, %upper, %innerStep)");
  return replace(input, "\"nodal.generated_export\"(%state)",
                 slice("laneOut", "rowOut", "i", row, bit) + assign("laneOut", "state") +
                     "\"nodal.generated_export\"(%state)");
}

std::string derivedWidthFixture() {
  auto ty = [](unsigned bits) {
    return "!nodal.digital<\"sint\", \"Top.width" + std::to_string(bits) + "\">";
  };
  std::string body = domain("a") + "%widthZero = \"nodal.const_literal\"() <{value = 0 : i64, "
                                   "spelling = \"0\", metadata = {}}> : () -> i64\n";
  for (unsigned bits : {4, 5, 8}) {
    std::string name = std::to_string(bits);
    body += "%literal" + name + " = \"nodal.const_literal\"() <{value = " + name +
            " : i64, spelling = \"" + name + "\", metadata = {}}> : () -> i64\n" + "%width" + name +
            " = \"nodal.const_expr\"(%literal" + name +
            ", %widthZero) "
            "<{operator_name = \"add\", metadata = {semantic_path = \"Top.width" +
            name + "\"}}> : (i64, i64) -> i64\n";
  }
  body += port("x", "input", "a", ty(4)) + port("y", "input", "a", ty(4)) +
          port("sum", "output", "a", ty(5)) + port("product", "output", "a", ty(8)) +
          port("negative", "output", "a", ty(5)) + port("less", "output", "a");
  auto expression = [&](StringRef name, StringRef code, unsigned bits, bool unary = false) {
    const std::string type = bits == 1 ? bit : ty(bits);
    return "%" + name.str() + "Value = \"nodal.digital_expr\"(%x" + (unary ? "" : ", %y") +
           ") <{operator_name = \"" + code.str() + "\", metadata = {}}> : (" + ty(4) +
           (unary ? "" : ", " + ty(4)) + ") -> " + type + "\n\"nodal.digital_assign\"(%" +
           name.str() + ", %" + name.str() + "Value) <{kind = \"continuous\", metadata = {}}> : (" +
           type + ", " + type + ") -> ()\n";
  };
  body += expression("sum", "digital_add", 5) + expression("product", "digital_mul", 8) +
          expression("negative", "digital_neg", 5, true) + expression("less", "digital_lt", 1);
  return "module attributes {nodal.root.module = @Top} {\n" + owner("Top", body) + "}\n";
}

std::string print(Operation *op) {
  std::string result;
  llvm::raw_string_ostream out(result);
  op->print(out);
  return result;
}
} // namespace
int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream out(diagnostics);
    d.getLocation().print(out);
    d.print(out);
    out << '\n';
    return success();
  });
  unsigned count = 0, failures = 0;
  auto report = [&](StringRef name, bool ok) {
    ++count;
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  auto positive = [&](StringRef name, const std::string &input, unsigned modules,
                      unsigned instances, unsigned states, unsigned falling = 0) {
    diagnostics.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    PassManager manager(&context);
    manager.addPass(nodal::createSequentialToCIRCTPass());
    bool ok = parsed && succeeded(verify(*parsed)) && succeeded(manager.run(*parsed));
    unsigned m = 0, i = 0, s = 0, f = 0;
    if (ok) {
      parsed->walk([&](circt::hw::HWModuleOp) { ++m; });
      parsed->walk([&](circt::hw::InstanceOp) { ++i; });
      parsed->walk([&](circt::seq::CompRegOp) { ++s; });
      parsed->walk([&](circt::seq::ClockInverterOp) { ++f; });
      ok = m == modules && i == instances && s == states && f == falling &&
           StringRef(print(parsed->getOperation())).contains("__nodal_req_clock");
    }
    report(name, ok);
  };
  positive("shared-definition-independent-clock-inputs", fixture(), 2, 2, 1);
  positive("material-edge-polarity-variants", fixture(true), 3, 2, 2, 1);
  positive("nested-forwarded-domain", fixture(false, true), 3, 3, 1);
  positive("resetless-distinct-state-shape", fixture(false, false, true), 3, 2, 2);
  positive("repeated-definition-not-per-occurrence-cloned", fixture(false, false, false, 32), 2, 32,
           1);
  diagnostics.clear();
  {
    auto parsed = parseSourceString<mlir::ModuleOp>(fixture(), ParserConfig(&context, false));
    PassManager manager(&context);
    manager.addPass(nodal::createSequentialToCIRCTPass());
    bool wired = parsed && succeeded(manager.run(*parsed));
    circt::hw::HWModuleOp top;
    if (wired)
      for (auto module : parsed->getOps<circt::hw::HWModuleOp>())
        if (module->getAttrOfType<StringAttr>("sym_name").getValue() == "Top")
          top = module;
    wired &= bool(top);
    if (wired) {
      auto *body = top.getBodyBlock();
      SmallVector<circt::hw::InstanceOp> instances;
      for (auto instance : body->getOps<circt::hw::InstanceOp>())
        instances.push_back(instance);
      wired = body->getNumArguments() == 6 && instances.size() == 2;
      if (wired) {
        // Both references name one child definition, but no physical input or
        // output result is shared. This checks causal wiring, not only op counts.
        wired = instances[0].getModuleName() == "Child" && instances[1].getModuleName() == "Child";
        for (unsigned i = 0; i < 2; ++i) {
          wired &= instances[i]->getNumOperands() == 3 &&
                   instances[i]->getOperand(0) == body->getArgument(4 + i) &&
                   instances[i]->getOperand(1) == body->getArgument(2 * i) &&
                   instances[i]->getOperand(2) == body->getArgument(2 * i + 1) &&
                   body->back().getOperand(i) == instances[i].getResult(0);
        }
      }
    }
    report("shared-definition-preserves-exact-independent-causal-edges", wired);
  }
  {
    auto formal = [](StringRef name) {
      auto result =
          replace(requirement(true), "sym_name = \"req\"", "sym_name = \"" + name.str() + "\"");
      result = replace(result, "%clock_req", "%clock_" + name.str());
      result = replace(result, "%reset_req", "%reset_" + name.str());
      result = replace(result, "requirement = @req", "requirement = @" + name.str());
      result = replace(result, "requirement = @req", "requirement = @" + name.str());
      return result;
    };
    std::string child =
        formal("src") + formal("dst") + port("input", "input", "src") +
        port("output", "output", "dst") +
        "%gray = \"nodal.crossing\"(%input) <{source_domain = @src, destination_domain = @dst, "
        "kind = \"gray\", contract = \"gray_v1\", stages = 2 : i64, obligations = {"
        "transition = \"one_bit_per_source_sample\", skew = \"less_than_source_period\", "
        "stability = \"stable_between_source_samples\"}, metadata = {semantic_path = "
        "\"Child.gray\"}}> : (" +
        bit + ") -> " + bit + "\n" + assign("output", "gray");
    std::string occurrence = instance("child", "Child", "a", "x");
    occurrence = replace(occurrence, "domain_bindings = {req = @a}",
                         "domain_bindings = {src = @a, dst = @b}");
    occurrence = replace(occurrence, "requirement = @req", "requirement = @src");
    occurrence += "\"nodal.instance_domain_binding\"(%clock_b, %reset_b) <{instance = @child, "
                  "requirement = @dst, actual = @b, origin = \"named\", metadata = {}}> : (" +
                  clockType + ", " + resetType + ") -> ()\n";
    auto input =
        "module attributes {nodal.root.module = @Top} {\n" +
        owner("Top", domain("a") + domain("b") + port("x", "input", "a") + occurrence +
                         port("result", "output", "b") + assign("result", "child_output")) +
        owner("Child", child) + "}\n";
    diagnostics.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    PassManager manager(&context);
    manager.addPass(nodal::createSequentialToCIRCTPass());
    bool minimal = parsed && succeeded(manager.run(*parsed));
    if (minimal) {
      auto output = print(parsed->getOperation());
      minimal = StringRef(output).contains("__nodal_src_reset") &&
                StringRef(output).contains("__nodal_dst_clock") &&
                !StringRef(output).contains("__nodal_dst_reset");
    }
    report("gray-destination-payload-does-not-demand-reset", minimal);
  }
  positive("conditional-initializer-partitions-reset-bearing-and-resetless-occurrences",
           conditionalFixture(), 3, 2, 2, 1);
  {
    diagnostics.clear();
    auto parsed =
        parseSourceString<mlir::ModuleOp>(conditionalFixture(true), ParserConfig(&context, false));
    bool ok = bool(parsed);
    unsigned obligations = 0;
    if (ok) {
      for (auto module : parsed->getOps<nodal::ModuleOp>())
        if (module->getAttrOfType<StringAttr>("sym_name").getValue() == "Child") {
          auto query = *module.getBody().front().getOps<nodal::ResetAssertedOp>().begin();
          auto graph = nodal::analyzeDigitalAssignmentDependencies(
              module, {{query->getResult(0), *query->getResult(0).getUsers().begin()}});
          ok &= succeeded(graph);
          if (succeeded(graph))
            for (auto &node : graph->nodes)
              obligations += bool(node.contextualReset);
        }
      ok &= obligations == 1;
    }
    report("formal-reset-query-retains-explicit-occurrence-dependency", ok);
    PassManager manager(&context);
    manager.addPass(nodal::createSequentialToCIRCTPass());
    ok &= parsed && succeeded(manager.run(*parsed));
    if (ok) {
      std::string output = print(*parsed);
      unsigned children = 0, resetPorts = 0, resetlessFalse = 0;
      parsed->walk([&](circt::hw::HWModuleOp module) {
        auto definition = module->getAttrOfType<StringAttr>("nodal.definition");
        if (!definition || definition.getValue() != "Child")
          return;
        ++children;
        auto *body = module.getBodyBlock();
        resetPorts += body->getNumArguments() == 2;
        if (body->getNumArguments() == 1) {
          auto value = body->back().getOperand(0).getDefiningOp();
          auto constant = value ? value->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
          resetlessFalse += constant && constant.getValue().isZero();
        }
      });
      ok = children == 2 && resetPorts == 1 && resetlessFalse == 1 &&
           !StringRef(output).contains("__nodal_req_clock") &&
           StringRef(output).contains("__nodal_req_reset") &&
           StringRef(output).contains("comb.xor");
    }
    report("reset-query-alone-infers-no-clock-and-proves-resetless-false", ok);
  }
  auto negative = [&](StringRef name, const std::string &input, StringRef diagnostic) {
    diagnostics.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    auto before = parsed ? print(parsed->getOperation()) : "";
    PassManager manager(&context);
    manager.addPass(nodal::createSequentialToCIRCTPass());
    bool ok = parsed && failed(manager.run(*parsed)) && before == print(parsed->getOperation()) &&
              StringRef(diagnostics).contains(diagnostic);
    report(name, ok);
  };
  negative("actual-clock-swap-keeps-descriptor",
           replace(fixture(), "instance_domain_binding\"(%clock_a,",
                   "instance_domain_binding\"(%clock_b,"),
           "Clock operand disagrees");
  negative("actual-reset-swap-keeps-descriptor",
           replace(fixture(), "instance_domain_binding\"(%clock_a, %reset_a)",
                   "instance_domain_binding\"(%clock_a, %reset_b)"),
           "Reset operand disagrees");
  negative("initialized-resetless-occurrence",
           replace(fixture(false, false, true), owner("Resetless", leaf(false)),
                   owner("Resetless", leaf(true))),
           "initialized child state requires");
  negative("unbounded-ordinary-parameter-remains-unsupported-atomically",
           replace(fixture(), owner("Child", leaf()),
                   owner("Child",
                         leaf() + "\"nodal.parameter\"() <{sym_name = \"N\", type = i64, "
                                  "default_value = 4 : i64, variability = \"symbolic\", metadata "
                                  "= {}}> : () -> ()\n")),
           "target Integer parameter envelope must fit signed 32-bit arithmetic");
  negative("conditional-initializer-policy-is-closed",
           replace(conditionalFixture(), "initializer_policy = \"if_reset\"",
                   "initializer_policy = \"sometimes\""),
           "if_reset requires");
  negative("conditional-initializer-cannot-hide-missing-value",
           replace(replace(conditionalFixture(), "digital_storage\"(%zero)", "digital_storage\"()"),
                   "}> : (!nodal.bits<1>) -> !nodal.bits<1>", "}> : () -> !nodal.bits<1>"),
           "if_reset requires");
  negative("formal-reset-query-cannot-guess-polarity",
           replace(conditionalFixture(true), "domain = @req, metadata = {semantic_path",
                   "domain = @req, reset_polarity = \"active_high\", metadata = {semantic_path"),
           "formal reset assertion retains only");
  negative(
      "formal-query-cannot-bypass-closed-root-selection",
      replace(conditionalFixture(true), "module attributes {nodal.root.module = @Top}", "module"),
      "requires one exact outer nodal.root.module selector");
  negative(
      "formal-consumer-root-cannot-remain-unbound",
      replace(conditionalFixture(true), "nodal.root.module = @Top", "nodal.root.module = @Child"),
      "reachable requirement lacks a unique concrete clock/reset binding");
  {
    diagnostics.clear();
    auto parsed =
        parseSourceString<mlir::ModuleOp>(conditionalFixture(), ParserConfig(&context, false));
    bool valid = bool(parsed);
    unsigned conditional = 0, resets = 0;
    if (valid) {
      for (auto module : parsed->getOps<nodal::ModuleOp>())
        valid &= succeeded(nodal::normalizeSequentialModel(module));
      parsed->walk([&](nodal::SequentialUpdateOp update) {
        conditional +=
            update->getNumOperands() == 4 &&
            update->getAttrOfType<StringAttr>("initializer_policy").getValue() == "if_reset" &&
            !update->hasAttr("edge") && !update->hasAttr("reset_policy") &&
            !update->hasAttr("reset_polarity");
      });
      parsed->walk([&](nodal::RequirementResetOp) { ++resets; });
      valid &= conditional == 1 && resets == 0 && succeeded(verify(*parsed));
    }
    report("conditional-normalization-preserves-initial-without-reset-projection", valid);
  }
  diagnostics.clear();
  auto normalized = parseSourceString<mlir::ModuleOp>(fixture(), ParserConfig(&context, false));
  bool ok = bool(normalized);
  if (ok) {
    for (auto module : normalized->getOps<nodal::ModuleOp>())
      ok &= succeeded(nodal::normalizeSequentialModel(module));
    unsigned formal = 0;
    normalized->walk([&](nodal::SequentialUpdateOp update) {
      if (!update->hasAttr("edge") && !update->hasAttr("reset_policy") &&
          !update->hasAttr("reset_polarity"))
        ++formal;
    });
    ok &= succeeded(verify(*normalized)) && formal == 1;
  }
  report("normalization-retains-formal-material-absence", ok);
  if (ok) {
    auto malformed = cast<mlir::ModuleOp>(normalized->clone());
    malformed.walk([&](nodal::SequentialUpdateOp update) {
      update->setAttr("edge", StringAttr::get(&context, "rising"));
    });
    diagnostics.clear();
    report("formal-update-cannot-guess-one-material-field",
           failed(verify(malformed)) && StringRef(diagnostics).contains("no guessed material"));
    malformed.erase();
  }
  auto generatedTarget = [&](StringRef name, const std::string &input, bool preserve, bool expected,
                             StringRef diagnostic = "", int extractOffset = -1,
                             unsigned depth = 1) {
    diagnostics.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    bool valid = parsed && succeeded(verify(*parsed));
    std::string before = parsed ? print(*parsed) : "";
    PassManager manager(&context);
    auto pass = nodal::createSequentialToCIRCTPass();
    valid &= succeeded(pass->initializeOptions(preserve ? "preserve-constant-loops=true" : "",
                                               [&](const Twine &) { return failure(); }));
    manager.addPass(std::move(pass));
    bool lowered = valid && succeeded(manager.run(*parsed));
    bool correct = valid && lowered == expected;
    if (lowered) {
      unsigned modules = 0, instances = 0, loops = 0, registers = 0, leftovers = 0;
      bool extraction = extractOffset < 0;
      unsigned prefixMaps = 0;
      bool constantWidthsDominate = true;
      parsed->walk([&](Operation *op) {
        if (op->getName().getStringRef() == "comb.extract")
          if (auto low = op->getAttrOfType<IntegerAttr>("lowBit"))
            extraction |= low.getInt() == extractOffset;
        if (op->getName().getStringRef() == "sv.localparam")
          for (Operation *user : op->getUsers()) {
            Operation *ancestor = user;
            while (ancestor && ancestor->getBlock() != op->getBlock())
              ancestor = ancestor->getParentOp();
            constantWidthsDominate &= ancestor && op->isBeforeInBlock(ancestor);
          }
        if (auto prefixes = op->getAttrOfType<ArrayAttr>("nodal.generated_selection")) {
          if (prefixes.size() == depth) {
            bool exact = true;
            for (auto [index, raw] : llvm::enumerate(prefixes)) {
              auto frame = cast<DictionaryAttr>(raw);
              auto coordinate = dyn_cast<IntegerAttr>(frame.get("coordinate"));
              exact &= frame.getAs<StringAttr>("family") && frame.get("stride_bits") &&
                       coordinate && coordinate.getInt() == (index + 1 == depth ? 0 : 1);
            }
            prefixMaps += exact;
          }
        }
        modules += isa<circt::hw::HWModuleOp>(op);
        instances += isa<circt::hw::InstanceOp>(op);
        registers += isa<circt::seq::CompRegOp>(op);
        loops += op->getName().getStringRef() == "sv.generate.for";
        leftovers += op->getDialect()->getNamespace() == "nodal";
      });
      correct &= modules == depth + 1 && instances == depth && registers == 1 && loops == depth &&
                 !leftovers && extraction && prefixMaps == 1 && constantWidthsDominate &&
                 succeeded(verify(*parsed));
      auto text = print(*parsed);
      correct &= !StringRef(text).contains("i0") &&
                 StringRef(text).contains("nodal.generated_template") &&
                 StringRef(text).contains("nodal.preserve_constant_loops");
      auto roundtrip = parseSourceString<mlir::ModuleOp>(text, &context);
      correct &= bool(roundtrip) && succeeded(verify(*roundtrip));
    } else if (parsed)
      correct &= before == print(*parsed) && StringRef(diagnostics).contains(diagnostic);
    report(name, correct);
  };
  generatedTarget("symbolic-family-retains-one-parametric-state-template", generatedFixture(),
                  false, true);
  generatedTarget("symbolic-family-direct-update-shares-clocked-emitter",
                  generatedFixture(true, false), false, true);
  std::string extracted = R"mlir(
    %wide = "nodal.const_literal"() <{value = 164 : i64, spelling = "164", metadata = {}}> : () -> !nodal.uint<8>
    %begin = "nodal.const_literal"() <{value = 2 : i64, spelling = "2", metadata = {}}> : () -> !nodal.uint<3>
    %end = "nodal.const_literal"() <{value = 3 : i64, spelling = "3", metadata = {}}> : () -> !nodal.uint<3>
    %extracted = "nodal.digital_expr"(%wide, %begin, %end) <{operator_name = "digital_extract", metadata = {}}> : (!nodal.uint<8>, !nodal.uint<3>, !nodal.uint<3>) -> !nodal.bits<1>
  )mlir" + assign("state", "extracted", "register_next");
  generatedTarget(
      "shared-generated-extraction-retains-nonzero-offset",
      replace(generatedFixture(true, false), assign("state", "input", "register_next"), extracted),
      false, true, "", 2);
  generatedTarget("concrete-family-explicit-preservation", generatedFixture(false), true, true);
  generatedTarget("concrete-family-no-silent-unroll-or-default-preservation",
                  generatedFixture(false), false, false, "preserve-constant-loops=true");
  generatedTarget("generated-static-predicate-profile-is-explicit",
                  replace(generatedFixture(), "comparison = \"eq\"", "comparison = \"lt\""), false,
                  false, "generated static alternatives require exact supported Integer equality");
  generatedTarget(
      "generated-nonunit-stride-does-not-change-lane-layout",
      replace(replace(generatedFixture(false, false),
                      "%step = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\"",
                      "%step = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\""),
              "maximum_trip_count = 3", "maximum_trip_count = 2"),
      true, false, "zero-based unit-stride family");
  generatedTarget("nested-family-two-exact-prefixes-and-reusable-bodies", nestedGeneratedFixture(),
                  true, true, "", -1, 2);
  generatedTarget("nested-matrix-routes-exact-parent-row-through-shared-helpers",
                  nestedMatrixFixture(), true, true, "", -1, 2);
  generatedTarget("nested-family-three-prefixes-retain-inverse-dimensions",
                  nestedGeneratedFixture(3), true, true, "", -1, 3);
  generatedTarget("nested-concrete-preserves-each-actual-loop", nestedGeneratedFixture(2, false),
                  true, true, "", -1, 2);
  generatedTarget("nested-template-count-independent-of-cardinality",
                  replace(replace(nestedGeneratedFixture(), "value = 8 : i64, spelling = \"8\"",
                                  "value = 256 : i64, spelling = \"256\""),
                          "maximum_trip_count = 8", "maximum_trip_count = 256"),
                  true, true, "", -1, 2);
  generatedTarget("nested-physical-output-budget-is-not-template-budget",
                  replace(replace(nestedGeneratedFixture(), "value = 8 : i64, spelling = \"8\"",
                                  "value = 4096 : i64, spelling = \"4096\""),
                          "maximum_trip_count = 8", "maximum_trip_count = 4096"),
                  true, false, "nested generated export exceeds finite physical bit budget");
  negative("nested-selection-outer-ordinal-proof-is-not-last-index-only",
           replace(nestedGeneratedFixture(), "generated_member\"(%family0, %step)",
                   "generated_member\"(%family0, %outerUpper)"),
           "generated ordinal lacks an all-legal existence proof");
  negative("nested-selection-inner-ordinal-proof-remains-independent",
           replace(nestedGeneratedFixture(), "generated_member\"(%view0, %lower)",
                   "generated_member\"(%view0, %upper)"),
           "generated ordinal lacks an all-legal existence proof");
  negative("nested-selection-member-must-belong-to-exact-export-inventory",
           replace(nestedGeneratedFixture(),
                   "member = \"inner\", access = \"read\", kind = \"family\", metadata = {}}> : "
                   "(!nodal.generated_family, i64)",
                   "member = \"foreign\", access = \"read\", kind = \"family\", metadata = {}}> : "
                   "(!nodal.generated_family, i64)"),
           "generated member key is absent");
  {
    diagnostics.clear();
    auto parsed =
        parseSourceString<mlir::ModuleOp>(derivedWidthFixture(), ParserConfig(&context, false));
    PassManager manager(&context);
    manager.addPass(nodal::createSequentialToCIRCTPass());
    bool ok = parsed && succeeded(verify(*parsed)) && succeeded(manager.run(*parsed));
    if (ok) {
      auto output = print(*parsed);
      ok = !StringRef(output).contains("i0") && StringRef(output).contains("comb.add") &&
           StringRef(output).contains("comb.mul") && StringRef(output).contains("slt") &&
           StringRef(output).contains("i5") && StringRef(output).contains("i8");
      auto roundtrip = parseSourceString<mlir::ModuleOp>(output, &context);
      ok &= bool(roundtrip) && succeeded(verify(*roundtrip));
    }
    report("fixed-derived-width-arithmetic-retains-mapped-width-and-sign", ok);
  }
  llvm::outs() << count << " hierarchy target cases, " << failures << " failures\n";
  return failures ? 1 : 0;
}
