#include "nodal/Transforms/AnalogCanonicalization.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/PassManager.h"
#include "mlir/Pass/PassRegistry.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/ADT/SmallVector.h"
#include "llvm/Support/raw_ostream.h"

#include <chrono>
#include <string>

namespace {
constexpr llvm::StringLiteral quantity = "!nodal.quantity<\"real\", \"1\">";
constexpr llvm::StringLiteral temperature = "!nodal.quantity<\"real\", \"temperature\">";

std::string print(mlir::ModuleOp module) {
  std::string text;
  llvm::raw_string_ostream output(text);
  module.print(output);
  return text;
}
std::string model(llvm::StringRef body, llvm::StringRef attributes = {}) {
  return "module " + (attributes.empty() ? "" : "attributes {" + attributes.str() + "}") +
         " { \"nodal.module\"() <{sym_name = \"T\", metadata = {semantic_path = \"T\"}}> ({\n"
         "\"nodal.parameter\"() <{sym_name = \"P\", type = f64, default_value = 2.0 : f64, "
         "variability = \"symbolic\", metadata = {}}> : () -> ()\n"
         "\"nodal.parameter\"() <{sym_name = \"Q\", type = f64, default_value = 3.0 : f64, "
         "variability = \"symbolic\", metadata = {}}> : () -> ()\n" +
         body.str() + "}) : () -> () }\n";
}
std::string analog(llvm::StringRef body) {
  return "\"nodal.analog\"() <{metadata = {}}> ({\n" + body.str() + "}) : () -> ()\n";
}
std::string literal(llvm::StringRef id, llvm::StringRef number = "1.0",
                    llvm::StringRef type = "f64") {
  return "%" + id.str() + " = \"nodal.real_literal\"() <{value = " + number.str() +
         " : f64, metadata = {semantic_path = \"T." + id.str() + "\"}}> : () -> " + type.str() +
         " loc(\"canonical.scala\":7:3)\n";
}
std::string reference(llvm::StringRef id, llvm::StringRef parameter = "P",
                      llvm::StringRef type = "f64") {
  return "%" + id.str() + " = \"nodal.parameter_ref\"() <{parameter = @" + parameter.str() +
         ", metadata = {semantic_path = \"T." + id.str() + "\"}}> : () -> " + type.str() + "\n";
}
std::string binary(llvm::StringRef id, llvm::StringRef op, llvm::StringRef left,
                   llvm::StringRef right, llvm::StringRef type = "f64",
                   llvm::StringRef metadata = {}) {
  return "%" + id.str() + " = \"nodal.analog_" + op.str() + "\"(%" + left.str() + ", %" +
         right.str() + ") <{metadata = {semantic_path = \"T." + id.str() + "\"" +
         (metadata.empty() ? "" : ", " + metadata.str()) + "}}> : (" + type.str() + ", " +
         type.str() + ") -> " + type.str() + "\n";
}
std::string neg(llvm::StringRef id, llvm::StringRef operand, llvm::StringRef input = "f64",
                llvm::StringRef result = "f64", llvm::StringRef metadata = {}) {
  return "%" + id.str() + " = \"nodal.analog_neg\"(%" + operand.str() +
         ") <{metadata = {semantic_path = \"T." + id.str() + "\"" +
         (metadata.empty() ? "" : ", " + metadata.str()) + "}}> : (" + input.str() + ") -> " +
         result.str() + "\n";
}
std::string consume(llvm::StringRef id, llvm::StringRef type = "f64") {
  // The F38 mathematical-call owner is deliberately outside F45's whitelist.
  return "%sink_" + id.str() + " = \"nodal.analog_function\"(%" + id.str() +
         ") <{function_id = \"sin\", registry_version = \"1\", metadata = {}}> : (" + type.str() +
         ") -> " + type.str() + "\n";
}
unsigned count(mlir::ModuleOp module, llvm::StringRef name) {
  unsigned result = 0;
  module.walk([&](mlir::Operation *op) { result += op->getName().getStringRef() == name; });
  return result;
}
mlir::ArrayAttr records(mlir::ModuleOp module) {
  auto journal = module->getAttrOfType<mlir::DictionaryAttr>("nodal.canonical.analog_journal");
  return journal ? journal.getAs<mlir::ArrayAttr>("records") : mlir::ArrayAttr();
}
unsigned reasons(mlir::ModuleOp module, llvm::StringRef reason) {
  unsigned result = 0;
  if (auto entries = records(module))
    for (mlir::Attribute entry : entries)
      result +=
          llvm::cast<mlir::DictionaryAttr>(entry).getAs<mlir::StringAttr>("reason").getValue() ==
          reason;
  return result;
}

std::string equation(llvm::StringRef identity = "T.eq", llvm::StringRef kind = "equation",
                     llvm::StringRef analyses = "[\"operating-point\", \"transient\"]") {
  return "{kind = \"" + kind.str() + "\", identity = \"" + identity.str() +
         "\", authored_left = \"V(p,n)\", authored_right = \"unparsed_source_expression\", "
         "dimension = \"voltage\", analyses = " +
         analyses.str() +
         ", continuity = \"unspecified\", guard = \"opaque_guard\", owner = \"T\", "
         "source_file = \"model.scala\", source_line = 10 : i64, source_column = 4 : i64, "
         "residual_convention = \"lhs-minus-rhs-equals-zero\", causally_oriented = false, divided "
         "= false}";
}
std::string contribution(llvm::StringRef identity = "T.eq") {
  return "{kind = \"contribution\", identity = \"" + identity.str() +
         "\", target_identity = \"T.branch\", target_kind = \"flow\", "
         "target_dimension = \"current\", target_orientation = \"p-to-n\", "
         "value = \"opaque_source_value\", analyses = [\"transient\"], continuity = "
         "\"unspecified\", "
         "guard = \"\", owner = \"T\", source_file = \"model.scala\", "
         "source_line = 12 : i64, source_column = 2 : i64}";
}
std::string replace(std::string text, llvm::StringRef before, llvm::StringRef after) {
  size_t position = text.find(before.str());
  if (position != std::string::npos)
    text.replace(position, before.size(), after.str());
  return text;
}

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  std::string diagnostic;
  mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &message) {
    llvm::raw_string_ostream output(diagnostic);
    message.print(output);
    return mlir::success();
  });
  unsigned cases = 0;
  auto failure = [&](llvm::StringRef message) {
    llvm::errs() << "AnalogCanonicalizationTest: " << message << '\n' << diagnostic << '\n';
    return 1;
  };
  auto parse = [&](llvm::StringRef source, bool verify = true) {
    diagnostic.clear();
    return mlir::parseSourceString<mlir::ModuleOp>(source, mlir::ParserConfig(&context, verify));
  };
  auto run = [&](llvm::StringRef source) {
    auto result = parse(source);
    if (!result || mlir::failed(nodal::canonicalizeAnalog(*result)))
      return mlir::OwningOpRef<mlir::ModuleOp>();
    ++cases;
    return result;
  };

  const std::string source = model(
      analog(reference("p") + reference("p2") + reference("q", "Q") + binary("a", "add", "p", "q") +
             binary("b", "add", "p2", "q") + neg("n", "a") + neg("nn", "n") + literal("unused") +
             consume("a") + consume("b") + consume("nn")),
      "nodal.bridge.analog_semantics = [" + equation() + ", " +
          equation("T.initial", "initial_equation", "[\"initialization\"]") + "]");
  auto original = parse(source);
  if (!original)
    return failure("positive fixture did not parse");
  mlir::Attribute inventory = (*original)->getAttr("nodal.bridge.analog_semantics");
  auto canonical = run(source);
  if (!canonical || count(*canonical, "nodal.parameter_ref") != 2 ||
      count(*canonical, "nodal.analog_add") != 1 || count(*canonical, "nodal.analog_neg") != 0 ||
      count(*canonical, "nodal.real_literal") != 0 ||
      count(*canonical, "nodal.analog_function") != 3 || reasons(*canonical, "exact-cse") != 2 ||
      reasons(*canonical, "double-negation") != 1 ||
      reasons(*canonical, "dead-pure-expression") != 2 ||
      (*canonical)->getAttr("nodal.bridge.analog_semantics") != inventory)
    return failure("finite rules, consumers, provenance or F32 inventory changed incorrectly");
  const std::string normalized = print(*canonical);
  if (mlir::failed(nodal::canonicalizeAnalog(*canonical)) || print(*canonical) != normalized)
    return failure("repeated canonicalization is not a byte-stable fixed point");
  auto deterministic = run(source);
  if (!deterministic || print(*deterministic) != normalized)
    return failure("canonical output is nondeterministic");
  for (mlir::Attribute entry : records(*canonical)) {
    auto old = llvm::cast<mlir::DictionaryAttr>(entry).getAs<mlir::DictionaryAttr>("original");
    if (!old.getAs<mlir::DictionaryAttr>("attributes").getAs<mlir::DictionaryAttr>("metadata") ||
        !old.getAs<mlir::ArrayAttr>("types") || !old.getAs<mlir::ArrayAttr>("operands") ||
        !old.getAs<mlir::LocationAttr>("location"))
      return failure("journal lost original type, metadata, location or operand identities");
  }

  auto nonmerge = run(model(analog(
      reference("p") + reference("q", "Q") + reference("typed", "P", quantity) +
      binary("a", "add", "p", "q") + binary("reversed", "add", "q", "p") +
      binary("different", "add", "p", "p") +
      binary("owner", "add", "p", "q", "f64", "owner = \"different\"") +
      binary("continuity", "add", "p", "q", "f64", "continuity = \"c1\"") +
      binary("unknown", "add", "p", "q", "f64", "future_contract = true") +
      binary("dimension", "add", "p", "q", "f64", "dimension = \"custom\"") +
      neg("n", "p", "f64", quantity) + neg("nn", "n", quantity, "f64") + consume("a") +
      consume("reversed") + consume("different") + consume("owner") + consume("continuity") +
      consume("unknown") + consume("dimension") + consume("typed", quantity) + consume("nn"))));
  if (!nonmerge || count(*nonmerge, "nodal.analog_add") != 7 ||
      count(*nonmerge, "nodal.parameter_ref") != 3 || count(*nonmerge, "nodal.analog_neg") != 2)
    return failure("different operands, semantic attrs, or exact types were merged");

  auto scopes =
      run(model(analog(reference("a") + consume("a")) + analog(reference("b") + consume("b"))));
  if (!scopes || count(*scopes, "nodal.parameter_ref") != 2 || records(*scopes))
    return failure("CSE crossed actual analog block boundaries");

  auto boundaries = run(model(
      analog(reference("p") + literal("positiveZero", "0.0") + literal("negativeZero", "-0.0") +
             binary("ratio", "div", "p", "p") + binary("cancellation", "sub", "p", "p") +
             binary("positiveAdd", "add", "p", "positiveZero") +
             binary("negativeAdd", "add", "p", "negativeZero") + consume("ratio") +
             consume("cancellation") + consume("positiveAdd") + consume("negativeAdd"))));
  if (!boundaries || count(*boundaries, "nodal.analog_div") != 1 ||
      count(*boundaries, "nodal.analog_sub") != 1 || count(*boundaries, "nodal.analog_add") != 2 ||
      count(*boundaries, "nodal.real_literal") != 2)
    return failure("cancellation, x/x or signed-zero algebra crossed the finite rule boundary");

  const std::string integerType = "!nodal.quantity<\"integer\", \"1\">";
  auto integerNegation = run(model(
      "\"nodal.parameter\"() <{sym_name = \"I\", type = i32, default_value = 1 : i32, "
      "parameter_kind = \"integer\", variability = \"symbolic\", metadata = {}}> : () -> ()\n" +
      analog(reference("i", "I", integerType) + neg("in", "i", integerType, integerType) +
             neg("inn", "in", integerType, integerType) +
             "%compare = \"nodal.analog_compare\"(%inn, %i) <{predicate = \"eq\", metadata = {}}> "
             ": (" +
             integerType + ", " + integerType + ") -> i1\n")));
  if (!integerNegation || count(*integerNegation, "nodal.analog_neg") != 2 ||
      reasons(*integerNegation, "double-negation"))
    return failure("dynamic Integer double negation lost its intermediate overflow boundary");

  const std::string query =
      "%temperature = \"nodal.analog_context\"() <{query_kind = \"temperature\", "
      "registry_version = \"1\", metadata = {}}> : () -> " +
      temperature.str() + "\n";
  auto effects = run(model(analog(
      query + binary("querySum", "add", "temperature", "temperature", temperature) +
      binary("querySum2", "add", "temperature", "temperature", temperature) +
      neg("queryNeg", "temperature", temperature, temperature) +
      neg("queryNeg2", "queryNeg", temperature, temperature) + literal("one") +
      "%state = \"nodal.analog_ddt\"(%one) <{metadata = {}}> : (f64) -> f64\n"
      "%state2 = \"nodal.analog_ddt\"(%one) <{metadata = {}}> : (f64) -> f64\n" +
      binary("stateSum", "add", "state", "state2") + binary("stateSum2", "add", "state", "state2") +
      "%integral = \"nodal.analog_idt\"(%one) <{analyses = [\"transient\"], context = "
      "\"equation\", "
      "initialization = \"solver-selected\", input_dimension = \"1\", metadata = {}, "
      "operator_contract = \"increment35\", operator_id = \"T.integral\", owner = \"T\", "
      "result_dimension = \"time\", state_id = \"T.integral.state\"}> : (f64) -> f64\n" +
      neg("integralNeg", "integral") + neg("integralNeg2", "integralNeg"))));
  if (!effects || count(*effects, "nodal.analog_add") != 4 ||
      count(*effects, "nodal.analog_neg") != 4 || count(*effects, "nodal.analog_ddt") != 2 ||
      count(*effects, "nodal.analog_context") != 1 || records(*effects))
    return failure("effectful expressions or their transitive users were changed");

  const std::string function =
      "\"nodal.analog_user_function\"() <{sym_name = \"identitySignal\", return_type = " +
      quantity.str() +
      ", contract_version = \"1\", metadata = {}}> ({\n"
      "%input = \"nodal.analog_function_value\"() <{kind = \"input\", name = \"signal\", "
      "argument_index = 0 : i64, metadata = {}}> : () -> " +
      quantity.str() +
      "\n"
      "%one = \"nodal.analog_function_value\"() <{kind = \"literal\", value = 1.0 : f64, "
      "metadata = {}}> : () -> " +
      quantity.str() +
      "\n"
      "%unused = \"nodal.analog_function_value\"(%one) <{kind = \"local\", name = \"spare\", "
      "metadata = {}}> : (" +
      quantity.str() + ") -> " + quantity.str() +
      "\n"
      "\"nodal.analog_function_return\"(%input) <{metadata = {}}> : (" +
      quantity.str() + ") -> ()\n}) : () -> ()\n";
  auto calls = run(model(
      function +
      analog(
          literal("one", "1.0", quantity) +
          "%call = \"nodal.analog_user_call\"(%one) <{callee = @identitySignal, contract_version = "
          "\"1\", metadata = {}}> : (" +
          quantity.str() + ") -> " + quantity.str() +
          "\n"
          "%call2 = \"nodal.analog_user_call\"(%one) <{callee = @identitySignal, contract_version "
          "= "
          "\"1\", metadata = {}}> : (" +
          quantity.str() + ") -> " + quantity.str() + "\n" +
          neg("callNeg", "call", quantity, quantity) +
          neg("callNeg2", "callNeg", quantity, quantity))));
  if (!calls || count(*calls, "nodal.analog_user_call") != 2 ||
      count(*calls, "nodal.analog_neg") != 2 || count(*calls, "nodal.analog_function_value") != 3 ||
      reasons(*calls, "dead-literal-local") != 1)
    return failure("user calls changed or dead-local source evidence was lost");
  const std::string callsOnce = print(*calls);
  if (mlir::failed(nodal::canonicalizeAnalog(*calls)) || print(*calls) != callsOnce)
    return failure("dead-local journal duplicated on repeated normalization");

  const std::string topology =
      "%p = \"nodal.terminal\"() <{name = \"p\", metadata = {}}> : () -> "
      "!nodal.terminal<\"electrical\">\n"
      "%n = \"nodal.node\"() <{name = \"n\", metadata = {}}> : () -> "
      "!nodal.terminal<\"electrical\">\n"
      "%branch = \"nodal.branch\"(%p, %n) <{metadata = {}}> : (!nodal.terminal<\"electrical\">, "
      "!nodal.terminal<\"electrical\">) -> !nodal.branch<\"electrical\">\n";
  auto contributions = run(
      model(topology +
            analog(reference("pvalue") + reference("pvalue2") +
                   "%access = \"nodal.access\"(%branch) <{kind = \"potential\", metadata = {}}> : "
                   "(!nodal.branch<\"electrical\">) -> f64\n"
                   "%access2 = \"nodal.access\"(%branch) <{kind = \"potential\", metadata = {}}> : "
                   "(!nodal.branch<\"electrical\">) -> f64\n" +
                   binary("probeSum", "add", "access", "access2") +
                   binary("probeSum2", "add", "access", "access2") +
                   "\"nodal.contribute\"(%branch, %pvalue) <{kind = \"flow\", metadata = "
                   "{semantic_path = \"T.first\"}}> : "
                   "(!nodal.branch<\"electrical\">, f64) -> ()\n"
                   "\"nodal.contribute\"(%branch, %pvalue2) <{kind = \"flow\", metadata = "
                   "{semantic_path = \"T.second\"}}> : "
                   "(!nodal.branch<\"electrical\">, f64) -> ()\n")));
  if (!contributions || count(*contributions, "nodal.contribute") != 2 ||
      count(*contributions, "nodal.branch") != 1 || count(*contributions, "nodal.access") != 2 ||
      count(*contributions, "nodal.analog_add") != 2)
    return failure("access, topology or additive contribution multiplicity changed");
  llvm::SmallVector<std::string> contributionOrder;
  contributions->walk([&](mlir::Operation *op) {
    if (op->getName().getStringRef() == "nodal.contribute")
      contributionOrder.push_back(op->getAttrOfType<mlir::DictionaryAttr>("metadata")
                                      .getAs<mlir::StringAttr>("semantic_path")
                                      .getValue()
                                      .str());
  });
  if (contributionOrder != llvm::SmallVector<std::string>{"T.first", "T.second"})
    return failure("contributions reordered");

  auto rejectUnchanged = [&](const std::string &invalid, llvm::StringRef code) {
    auto candidate = parse(invalid, false);
    if (!candidate)
      return false;
    const std::string before = print(*candidate);
    ++cases;
    return mlir::failed(nodal::canonicalizeAnalog(*candidate)) && print(*candidate) == before &&
           llvm::StringRef(diagnostic).contains(code);
  };
  for (const std::string &bad :
       {replace(source, "causally_oriented = false", "causally_oriented = true"),
        replace(source, "divided = false", "divided = true"),
        replace(source, "lhs-minus-rhs-equals-zero", "solved-by-division"),
        replace(source, "identity = \"T.initial\"", "identity = \"T.eq\""),
        replace(source, "[\"initialization\"]", "[\"transient\"]"),
        replace(source, "operating-point", "operating_point"),
        replace(source, "source_line = 10", "source_line = 0"),
        replace(source, "owner = \"T\"", "owner = \"MissingModule\""),
        replace(source, "identity = \"T.eq\"", "identity = \"Foreign.eq\""),
        replace(source, "authored_left = \"V(p,n)\"", "authored_left = 1 : i64")})
    if (!rejectUnchanged(bad, "NODAL-ANALOG-045-001"))
      return failure("invalid F32 inventory accepted or transaction changed input");
  if (!rejectUnchanged(model(analog(reference("p") + literal("zero", "-0.0") +
                                    binary("invalid", "div", "p", "zero"))),
                       "NODAL-ANALOG-DIVIDE-001"))
    return failure("dead invalid division was erased before validation");
  if (!rejectUnchanged(model(analog(literal("one")),
                             "nodal.canonical.analog_journal = {version = 2 : i64, records = []}"),
                       "NODAL-ANALOG-045-002"))
    return failure("forged journal accepted");

  const std::string mixedIdentities =
      model(analog(reference("p") + consume("p")),
            "nodal.bridge.analog_semantics = [" + equation() + ", " + contribution() + "]");
  if (!run(mixedIdentities))
    return failure("equation and contribution identity namespaces were incorrectly combined");
  for (const std::string &bad :
       {model(analog(reference("p") + consume("p")),
              "nodal.bridge.analog_semantics = [" + contribution() + ", " + contribution() + "]"),
        replace(mixedIdentities, "target_kind = \"flow\"", "target_kind = \"charge\""),
        replace(mixedIdentities, "target_orientation = \"p-to-n\"", "target_orientation = \"\"")})
    if (!rejectUnchanged(bad, "NODAL-ANALOG-045-001"))
      return failure("duplicate contribution or malformed contribution schema was accepted");

  // Reused module definitions preserve authored owners, including descendants
  // below an occurrence that has no separately emitted definition.
  const std::string reused =
      "module attributes {nodal.bridge.analog_semantics = [" +
      replace(equation("T.second.leaf.eq"), "owner = \"T\"", "owner = \"T.second.leaf\"") +
      "]} {\n"
      "\"nodal.module\"() <{sym_name = \"T\", metadata = {semantic_path = \"T\"}}> ({\n"
      "\"nodal.instance\"() <{sym_name = \"first_instance\", module = @Child, parameter_bindings = "
      "{}, "
      "domain_bindings = {}, metadata = {semantic_path = \"T.first_instance\", child_path = "
      "\"T.first\"}}> "
      ": () -> ()\n"
      "\"nodal.instance\"() <{sym_name = \"second_instance\", module = @Child, parameter_bindings "
      "= {}, "
      "domain_bindings = {}, metadata = {semantic_path = \"T.second_instance\", child_path = "
      "\"T.second\"}}> : () -> ()\n"
      "}) : () -> ()\n"
      "\"nodal.module\"() <{sym_name = \"Child\", metadata = {semantic_path = \"T.first\"}}> ({\n"
      "\"nodal.instance\"() <{sym_name = \"leaf_instance\", module = @Leaf, parameter_bindings = "
      "{}, "
      "domain_bindings = {}, metadata = {semantic_path = \"T.first.leaf_instance\", child_path = "
      "\"T.first.leaf\"}}> : () -> ()\n"
      "}) : () -> ()\n"
      "\"nodal.module\"() <{sym_name = \"Leaf\", metadata = {semantic_path = \"T.first.leaf\"}}> "
      "({^bb0:}) : () -> ()\n}\n";
  if (!run(reused))
    return failure("reused nested module owner did not resolve through actual instances");
  for (const std::string &bad :
       {replace(reused, "child_path = \"T.second\"", "child_path = \"MissingModule\""),
        replace(reused, "module = @Child", "module = @MissingModule"),
        replace(reused, "child_path = \"T.second\"", "child_path = \"T.fake\""),
        replace(reused, "semantic_path = \"T.second_instance\"",
                "semantic_path = \"T.fake_instance\""),
        replace(reused, "owner = \"T.second.leaf\"", "owner = \"T.second.absent\"")})
    if (!rejectUnchanged(bad, "NODAL-ANALOG-045-001"))
      return failure("invalid instance alias granted F32 inventory ownership");

  nodal::registerNodalPasses();
  mlir::PassManager manager(&context);
  if (mlir::failed(mlir::parsePassPipeline("nodal-canonicalize-analog", manager)))
    return failure("pass is not registered");
  auto passInput = parse(source);
  if (!passInput || mlir::failed(manager.run(*passInput)) || print(*passInput) != normalized)
    return failure("registered pass differs from shared API");
  ++cases;

  // A shared doubling DAG has linear representation, although recursively
  // expanding operands would have exponential cost. Keep each node finite.
  for (unsigned depth : {64U, 256U}) {
    std::string graph = literal("x0", "0.0");
    for (unsigned index = 1; index <= depth; ++index) {
      const std::string previous = "x" + std::to_string(index - 1);
      graph += binary("x" + std::to_string(index), "add", previous, previous);
    }
    const auto start = std::chrono::steady_clock::now();
    auto scaled = run(model(analog(graph)));
    const auto milliseconds = std::chrono::duration_cast<std::chrono::milliseconds>(
                                  std::chrono::steady_clock::now() - start)
                                  .count();
    if (!scaled || count(*scaled, "nodal.analog_add") != 0 || !records(*scaled) ||
        records(*scaled).size() != depth + 1 || print(*scaled).size() > (depth + 1) * 1800)
      return failure("DAG cleanup or provenance grew beyond its flat linear bound");
    llvm::outs() << "ANALOG_CANONICAL_DAG depth=" << depth << " records=" << records(*scaled).size()
                 << " bytes=" << print(*scaled).size() << " elapsed_ms=" << milliseconds << '\n';
  }
  llvm::outs() << "ANALOG_CANONICALIZATION_TEST_PASS cases=" << cases << '\n';
  return 0;
}
