#include "nodal/Dialect/Nodal/DigitalMaterializationModel.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/PassManager.h"
#include "mlir/Pass/PassRegistry.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/Support/raw_ostream.h"

#include <string>

using namespace mlir;
namespace {
std::string expression(StringRef name, StringRef opcode, StringRef operands, StringRef inputTypes,
                       StringRef resultType, StringRef extra = {}) {
  return "%" + name.str() + " = \"nodal.digital_expr\"(" + operands.str() +
         ") <{operator_name = \"" + opcode.str() + "\", metadata = {semantic_path = \"Fixture." +
         name.str() + "\"" + extra.str() + "}}> : (" + inputTypes.str() + ") -> " +
         resultType.str() + "\n";
}
std::string model(StringRef body, StringRef policy = "inline-safe", bool request = true) {
  std::string result = "module";
  if (request)
    result += " attributes {nodal.digital.quality = {version = 1 : i64, temporaries = \"" +
              policy.str() + "\", naming = \"semantic\"}}";
  result += " { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {semantic_path = "
            "\"Fixture\"}}> ({\n";
  for (StringRef name : {"a", "b"}) {
    result += "%" + name.str() +
              " = \"nodal.const_literal\"() <{value = 3 : i64, spelling = \"3\", "
              "metadata = {semantic_path = \"Fixture." +
              name.str() + "\"}}> : () -> !nodal.uint<4>\n";
  }
  return result + body.str() + "}) : () -> () }\n";
}
Operation *owner(mlir::ModuleOp root) {
  Operation *result = nullptr;
  root.walk([&](nodal::ModuleOp module) { result = module; });
  return result;
}
DictionaryAttr entry(DictionaryAttr report, StringRef name) {
  for (Attribute value : report.getAs<ArrayAttr>("decisions")) {
    auto item = llvm::cast<DictionaryAttr>(value);
    if (item.getAs<StringAttr>("path").getValue() == ("Fixture." + name).str())
      return item;
  }
  return {};
}
StringRef text(DictionaryAttr item, StringRef key) {
  return item.getAs<StringAttr>(key).getValue();
}
bool reason(DictionaryAttr item, StringRef value) {
  for (Attribute item : item.getAs<ArrayAttr>("reasons"))
    if (llvm::cast<StringAttr>(item).getValue() == value)
      return true;
  return false;
}
} // namespace

int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  ScopedDiagnosticHandler capture(&context, [](Diagnostic &) { return success(); });
  Builder builder(&context);
  unsigned cases = 0, failures = 0;
  auto check = [&](StringRef name, bool passed) {
    ++cases;
    failures += !passed;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_MATERIALIZATION_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  const std::string product = expression("product", "digital_mul", "%a, %b",
                                         "!nodal.uint<4>, !nodal.uint<4>", "!nodal.uint<8>");
  const std::string consumed =
      expression("used", "digital_not", "%product", "!nodal.uint<8>", "!nodal.uint<8>");
  auto parse = [&](const std::string &source) {
    return parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
  };
  auto one = parse(model(product + consumed));
  if (!one)
    return 2;
  auto first = nodal::analyzeDigitalMaterialization(owner(*one));
  check("single-use-is-only-candidate",
        succeeded(first) && text(entry(*first, "product"), "decision") == "inline-candidate" &&
            entry(*first, "product").getAs<IntegerAttr>("use_count").getInt() == 1);
  check("zero-use-is-not-deleted",
        succeeded(first) && text(entry(*first, "used"), "decision") == "unused-candidate");
  check("target-requirements-stay-pending", succeeded(first) &&
                                                text(*first, "target_requirements") == "pending" &&
                                                text(*first, "stage") == "target-neutral");
  check("read-only-analysis-does-not-attach-report",
        !owner(*one)->hasAttr("nodal.digital.materialization"));
  owner(*one)->setAttr("nodal.digital.materialization", *first);
  check("attached-report-reverifies",
        succeeded(nodal::verifyDigitalMaterializationReport(owner(*one))));
  auto repeat = nodal::analyzeDigitalMaterialization(owner(*one));
  check("repeat-analysis-byte-identity", succeeded(repeat) && *repeat == *first);

  auto sameCount = parse(model(product + consumed));
  owner(*sameCount)->setAttr("nodal.digital.materialization", *first);
  auto user = &owner(*sameCount)->getRegion(0).front().back();
  NamedAttrList renamed(user->getAttrOfType<DictionaryAttr>("metadata"));
  renamed.set("semantic_path", builder.getStringAttr("Fixture.changed_consumer"));
  user->setAttr("metadata", renamed.getDictionary(&context));
  check("same-count-changed-consumer-rejects-stale-report",
        failed(nodal::verifyDigitalMaterializationReport(owner(*sameCount))));
  auto duplicateInput = parse(model(product));
  auto &inputBlock = owner(*duplicateInput)->getRegion(0).front();
  auto firstInput = inputBlock.begin();
  auto secondInput = std::next(firstInput);
  secondInput->setAttr("metadata", firstInput->getAttr("metadata"));
  check("distinct-input-values-cannot-forge-one-semantic-identity",
        failed(nodal::analyzeDigitalMaterialization(owner(*duplicateInput))));

  auto orphanInput = parse(model(product + consumed));
  owner(*orphanInput)->setAttr("nodal.digital.materialization", *first);
  auto &orphanBlock = owner(*orphanInput)->getRegion(0).front();
  Operation *oldInput = &orphanBlock.front();
  Operation *replacementInput = oldInput->clone();
  orphanBlock.getOperations().insert(std::next(orphanBlock.begin()), replacementInput);
  oldInput->getResult(0).replaceAllUsesWith(replacementInput->getResult(0));
  check("orphaned-input-identity-cannot-hide-ssa-substitution",
        failed(nodal::verifyDigitalMaterializationReport(owner(*orphanInput))));

  auto indexed = parse(model(
      "%index = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", "
      "metadata = {semantic_path = \"Fixture.index\"}}> : () -> i64\n" +
      expression("shifted", "digital_shl", "%a, %index", "!nodal.uint<4>, i64", "!nodal.uint<5>")));
  auto indexedReport = nodal::analyzeDigitalMaterialization(owner(*indexed));
  if (failed(indexedReport))
    return 4;
  owner(*indexed)->setAttr("nodal.digital.materialization", *indexedReport);
  auto &indexedBlock = owner(*indexed)->getRegion(0).front();
  auto oldIndex = std::next(indexedBlock.begin(), 2);
  Operation *replacementIndex = oldIndex->clone();
  indexedBlock.getOperations().insert(std::next(oldIndex), replacementIndex);
  oldIndex->getResult(0).replaceAllUsesWith(replacementIndex->getResult(0));
  check("orphaned-static-index-identity-cannot-hide-ssa-substitution",
        failed(nodal::verifyDigitalMaterializationReport(owner(*indexed))));

  auto castConsumers = parse(
      model(product + expression("cast", "digital_to_real", "%product", "!nodal.uint<8>", "f64")));
  auto castReport = nodal::analyzeDigitalMaterialization(owner(*castConsumers));
  if (failed(castReport))
    return 5;
  owner(*castConsumers)->setAttr("nodal.digital.materialization", *castReport);
  auto &castBlock = owner(*castConsumers)->getRegion(0).front();
  Operation *oldCast = &castBlock.back();
  Operation *replacementCast = oldCast->clone();
  castBlock.push_back(replacementCast);
  oldCast->setOperand(0, castBlock.front().getResult(0));
  check("rerouted-noncandidate-consumer-cannot-hide-ssa-substitution",
        failed(nodal::verifyDigitalMaterializationReport(owner(*castConsumers))));

  const std::string repeated = expression("twice", "digital_add", "%product, %product",
                                          "!nodal.uint<8>, !nodal.uint<8>", "!nodal.uint<9>");
  auto two = parse(model(product + repeated));
  auto shared = nodal::analyzeDigitalMaterialization(owner(*two));
  check("two-operands-of-one-user-count-twice",
        succeeded(shared) &&
            entry(*shared, "product").getAs<IntegerAttr>("use_count").getInt() == 2 &&
            reason(entry(*shared, "product"), "shared-value"));
  owner(*two)->setAttr("nodal.digital.materialization", *first);
  check("changed-use-graph-rejects-stale-report",
        failed(nodal::verifyDigitalMaterializationReport(owner(*two))));
  owner(*two)->removeAttr("nodal.digital.materialization");

  const std::string equivalent = expression("separate", "digital_mul", "%a, %b",
                                            "!nodal.uint<4>, !nodal.uint<4>", "!nodal.uint<8>");
  auto separate = parse(model(product + equivalent));
  auto separateReport = nodal::analyzeDigitalMaterialization(owner(*separate));
  check("equal-authored-values-remain-distinct",
        succeeded(separateReport) && separateReport->getAs<ArrayAttr>("decisions").size() == 2 &&
            entry(*separateReport, "product") && entry(*separateReport, "separate"));
  auto reordered = parse(model(equivalent + product));
  auto reorderedReport = nodal::analyzeDigitalMaterialization(owner(*reordered));
  check("independent-walk-order-does-not-change-report", succeeded(reorderedReport) &&
                                                             succeeded(separateReport) &&
                                                             *reorderedReport == *separateReport);

  const std::string named = expression(
      "named", "digital_alias", "%product", "!nodal.uint<8>", "!nodal.uint<8>",
      ", digital_intent = {kind = \"named\", value = \"named\"}, "
      "digital_source = {name = \"named\", provenance = \"explicit\", aliases = [\"authored\"]}");
  auto minimal = parse(model(product + named));
  auto minimalReport = nodal::analyzeDigitalMaterialization(owner(*minimal));
  check("named-is-not-keep", succeeded(minimalReport) && text(entry(*minimalReport, "named"),
                                                              "decision") == "unused-candidate");
  auto readable = parse(model(product + named, "readable"));
  auto readableReport = nodal::analyzeDigitalMaterialization(owner(*readable));
  check("readable-retains-explicit-name",
        succeeded(readableReport) && reason(entry(*readableReport, "named"), "source-name"));
  auto debug = parse(model(product + named, "debug"));
  auto debugReport = nodal::analyzeDigitalMaterialization(owner(*debug));
  check("debug-retains-each-computed-anchor",
        succeeded(debugReport) && reason(entry(*debugReport, "product"), "debug-anchor") &&
            reason(entry(*debugReport, "named"), "debug-anchor"));
  check("typed-alias-is-a-valid-digital-value",
        succeeded(nodal::verifyDigitalValueModel(owner(*minimal))));
  const std::string kept =
      expression("kept", "digital_alias", "%product", "!nodal.uint<8>", "!nodal.uint<8>",
                 ", digital_intent = {kind = \"keep\", value = \"waveform\"}");
  auto keep = parse(model(product + kept));
  auto keptReport = nodal::analyzeDigitalMaterialization(owner(*keep));
  check("unused-keep-is-a-requirement",
        succeeded(keptReport) && reason(entry(*keptReport, "kept"), "explicit-keep") &&
            text(entry(*keptReport, "kept"), "decision") == "materialize-required");

  check("typed-keep-is-a-valid-digital-value",
        succeeded(nodal::verifyDigitalValueModel(owner(*keep))));
  auto wrongType = parse(model(
      product + expression("bad", "digital_alias", "%product", "!nodal.uint<8>", "!nodal.sint<8>",
                           ", digital_intent = {kind = \"keep\", value = \"x\"}")));
  check("alias-cannot-change-sign", failed(nodal::verifyDigitalValueModel(owner(*wrongType))));
  auto wrongArity = parse(
      model(expression("bad", "digital_alias", "%a, %b", "!nodal.uint<4>, !nodal.uint<4>",
                       "!nodal.uint<4>", ", digital_intent = {kind = \"keep\", value = \"x\"}")));
  check("alias-arity-is-independently-verified",
        failed(nodal::verifyDigitalValueModel(owner(*wrongArity))));
  auto constantAlias =
      parse(model(expression("alias", "digital_alias", "%a", "!nodal.uint<4>", "!nodal.uint<4>",
                             ", digital_intent = {kind = \"named\", value = \"small\"}") +
                  expression("checked", "digital_resize_checked", "%alias", "!nodal.uint<4>",
                             "!nodal.uint<2>")));
  check("alias-forwards-checked-conversion-proof",
        succeeded(nodal::verifyDigitalValueModel(owner(*constantAlias))));

  auto bad = [&](StringRef name, const std::string &body) {
    auto parsed = parse(model(product + body));
    check(name, parsed && failed(nodal::analyzeDigitalMaterialization(owner(*parsed))));
  };
  bad("alias-without-intent-rejects",
      expression("bad", "digital_alias", "%product", "!nodal.uint<8>", "!nodal.uint<8>"));
  bad("intent-on-arithmetic-rejects",
      expression("bad", "digital_not", "%product", "!nodal.uint<8>", "!nodal.uint<8>",
                 ", digital_intent = {kind = \"keep\", value = \"x\"}"));
  bad("unknown-intent-kind-rejects",
      expression("bad", "digital_alias", "%product", "!nodal.uint<8>", "!nodal.uint<8>",
                 ", digital_intent = {kind = \"force\", value = \"x\"}"));
  bad("blank-keep-reason-rejects",
      expression("bad", "digital_alias", "%product", "!nodal.uint<8>", "!nodal.uint<8>",
                 ", digital_intent = {kind = \"keep\", value = \" \"}"));
  bad("invalid-name-rejects",
      expression("bad", "digital_alias", "%product", "!nodal.uint<8>", "!nodal.uint<8>",
                 ", digital_intent = {kind = \"named\", value = \"x-y\"}"));
  bad("unknown-source-provenance-rejects",
      expression("bad", "digital_not", "%product", "!nodal.uint<8>", "!nodal.uint<8>",
                 ", digital_source = {name = \"named\", provenance = \"forged\", aliases = []}"));
  bad("unsorted-source-aliases-rejects",
      expression("bad", "digital_not", "%product", "!nodal.uint<8>", "!nodal.uint<8>",
                 ", digital_source = {name = \"named\", provenance = \"explicit\", aliases = "
                 "[\"z\", \"a\"]}"));
  auto invalidQuality = parse(model(product, "tool-friendly"));
  check("unfrozen-policy-rejects",
        failed(nodal::verifyDigitalMaterializationReport(owner(*invalidQuality))));
  auto historical = parse(model("", "inline-safe", false));
  check("old-native-fixtures-do-not-require-report-identities",
        succeeded(nodal::verifyDigitalMaterializationReport(owner(*historical))));
  owner(*historical)->setAttr("nodal.digital.materialization", *first);
  check("report-without-opt-in-quality-rejects",
        failed(nodal::verifyDigitalMaterializationReport(owner(*historical))));

  NamedAttrList forged(*first);
  forged.set("sha256", builder.getStringAttr(std::string(64, '0')));
  owner(*one)->setAttr("nodal.digital.materialization", forged.getDictionary(&context));
  check("forged-decision-hash-rejects",
        failed(nodal::verifyDigitalMaterializationReport(owner(*one))));
  std::string sharedDag = product;
  std::string previous = "product";
  for (unsigned index = 0; index != 64; ++index) {
    const std::string current = "shared_" + std::to_string(index);
    const std::string inputType = "!nodal.uint<" + std::to_string(8 + index) + ">";
    sharedDag +=
        expression(current, "digital_add", "%" + previous + ", %" + previous,
                   inputType + ", " + inputType, "!nodal.uint<" + std::to_string(9 + index) + ">");
    previous = current;
  }
  auto dag = parse(model(sharedDag));
  auto dagReport = nodal::analyzeDigitalMaterialization(owner(*dag));
  check("shared-dag-plans-unique-ssa-without-use-tree-expansion",
        succeeded(dagReport) && dagReport->getAs<ArrayAttr>("decisions").size() == 65 &&
            entry(*dagReport, "product").getAs<IntegerAttr>("use_count").getInt() == 2);

  auto mixed = parse(model(
      product + "%analog_a = \"nodal.const_literal\"() <{value = 1.0 : f64, spelling = \"1\", "
                "metadata = {semantic_path = \"Fixture.analog\"}}> : () -> f64\n"
                "%analog_b = \"nodal.const_literal\"() <{value = 2.0 : f64, spelling = \"2\", "
                "metadata = {semantic_path = \"Fixture.analog\"}}> : () -> f64\n"));
  auto mixedReport = nodal::analyzeDigitalMaterialization(owner(*mixed));
  check("unrelated-analog-provenance-is-outside-digital-identity-policy",
        succeeded(mixedReport) && mixedReport->getAs<ArrayAttr>("decisions").size() == 1);

  nodal::registerNodalPasses();
  auto transaction = parse(model(product + consumed));
  Operation *firstModule = owner(*transaction);
  Operation *secondModule = firstModule->clone();
  secondModule->setAttr("sym_name", builder.getStringAttr("Second"));
  secondModule->setAttr("nodal.digital.materialization", builder.getStringAttr("forged"));
  transaction->getBody()->push_back(secondModule);
  PassManager manager(&context);
  manager.enableVerifier(false);
  if (failed(parsePassPipeline("nodal-plan-digital-materialization", manager)))
    return 3;
  check("failed-second-module-does-not-attach-first-report",
        failed(manager.run(*transaction)) &&
            !firstModule->hasAttr("nodal.digital.materialization") &&
            secondModule->getAttrOfType<StringAttr>("nodal.digital.materialization").getValue() ==
                "forged");
  auto planned = parse(model(product + consumed));
  PassManager good(&context);
  if (failed(parsePassPipeline("nodal-plan-digital-materialization", good)))
    return 3;
  check("real-planning-pass-attaches-verified-inventory",
        succeeded(good.run(*planned)) &&
            succeeded(nodal::verifyDigitalMaterializationReport(owner(*planned))));
  std::string printed;
  llvm::raw_string_ostream stream(printed);
  planned->print(stream);
  auto reparsed = parse(printed);
  check("canonical-report-survives-print-parse",
        reparsed && succeeded(nodal::verifyDigitalMaterializationReport(owner(*reparsed))) &&
            owner(*reparsed)->getAttr("nodal.digital.materialization") ==
                owner(*planned)->getAttr("nodal.digital.materialization"));
  llvm::outs() << "DIGITAL_MATERIALIZATION_SUMMARY " << cases - failures << '/' << cases << '\n';
  return failures ? 1 : 0;
}
