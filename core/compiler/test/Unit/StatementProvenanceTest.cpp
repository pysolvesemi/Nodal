#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/Builders.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Diagnostics/DiagnosticSupport.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/ADT/APInt.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/Support/Base64.h"
#include "llvm/Support/raw_ostream.h"

#include <string>
#include <utility>

namespace {
constexpr llvm::StringLiteral source = R"mlir(
module attributes {nodal.target.profile = "analog"} {
  "nodal.module"() <{sym_name = "Child", metadata = {}}> ({
  ^bb0:
  }) : () -> ()
  "nodal.module"() <{sym_name = "Top", metadata = {root = true, semantic_path = "Top"}}> ({
    "nodal.parameter"() <{classification = "ordinary", default_value = 2.0 : f64, metadata = {semantic_path = "Top.gain"}, parameter_kind = "real", sym_name = "gain", type = f64, variability = "symbolic"}> : () -> () loc("input.mlir":999:77)
    "nodal.instance"() <{sym_name = "child", module = @Child, parameter_bindings = {}, domain_bindings = {}, metadata = {semantic_path = "Origin.instance"}}> : () -> ()
    %p = "nodal.terminal"() <{name = "p", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{name = "n", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
    %b = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {semantic_path = "Top.analog"}}> ({
      %one = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {semantic_path = "Origin.expression"}}> : () -> f64
      "nodal.contribute"(%b, %one) <{kind = "flow", metadata = {semantic_path = "Origin.current"}}> : (!nodal.branch<"electrical">, f64) -> () loc("input.mlir":333:44)
    }) : () -> ()
  }) : () -> ()
}
)mlir";

std::string print(mlir::ModuleOp module) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  module.print(stream);
  return result;
}

mlir::Operation *find(mlir::ModuleOp module, llvm::StringRef name) {
  mlir::Operation *result = nullptr;
  module.walk([&](mlir::Operation *operation) {
    if (operation->getName().getStringRef() == name)
      result = operation;
  });
  return result;
}

mlir::DictionaryAttr entry(mlir::Builder &builder, llvm::StringRef semantic,
                           llvm::StringRef path = "models/Ω path/器件.scala") {
  return builder.getDictionaryAttr(
      {builder.getNamedAttr("semantic_path", builder.getStringAttr(semantic)),
       builder.getNamedAttr("source_path", builder.getStringAttr(path)),
       builder.getNamedAttr("source_line", builder.getI64IntegerAttr(12)),
       builder.getNamedAttr("source_column", builder.getI64IntegerAttr(3)),
       builder.getNamedAttr("source_end_line", builder.getI64IntegerAttr(14)),
       builder.getNamedAttr("source_end_column", builder.getI64IntegerAttr(8))});
}

std::string marker(llvm::StringRef semantic, llvm::StringRef path = "models/Ω path/器件.scala") {
  return "// nodal-source-v1: " + llvm::encodeBase64(semantic) + "|" + llvm::encodeBase64(path) +
         "|12|3|14|8\n";
}

std::string withoutMarkers(llvm::StringRef output) {
  std::string result;
  llvm::SmallVector<llvm::StringRef> lines;
  output.split(lines, '\n');
  for (auto line : llvm::ArrayRef(lines).drop_back()) {
    if (!line.trim().starts_with("// nodal-source-v1:"))
      result += line.str() + "\n";
  }
  return result;
}

struct RejectMappedTarget final : nodal::TargetVerificationHooks {
  mutable bool observedProvenance = false;
  mlir::LogicalResult
  verifyTarget(llvm::StringRef candidate,
               const nodal::BackendConfiguration &configuration) const override {
    observedProvenance =
        configuration.sourceProvenance && candidate.contains("// nodal-source-v1:");
    return mlir::failure();
  }
  mlir::LogicalResult reparseTarget(llvm::StringRef,
                                    const nodal::BackendConfiguration &) const override {
    return mlir::success();
  }
};

struct Harness {
  mlir::MLIRContext context;
  mlir::Builder builder{&context};
  std::string diagnostics;
  mlir::ScopedDiagnosticHandler handler{&context, [&](mlir::Diagnostic &diagnostic) {
                                          llvm::raw_string_ostream stream(diagnostics);
                                          diagnostic.print(stream);
                                          stream << "\n";
                                          return mlir::success();
                                        }};

  Harness() {
    context.getOrLoadDialect<circt::hw::HWDialect>();
    context.getOrLoadDialect<nodal::NodalDialect>();
  }

  mlir::OwningOpRef<mlir::ModuleOp> module() {
    auto result = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (result)
      (*result)->setAttr("nodal.bridge.source_map",
                         builder.getArrayAttr(
                             {entry(builder, "Top.gain"), entry(builder, "Origin.instance"),
                              entry(builder, "Origin.current"), entry(builder, "Origin.expression"),
                              entry(builder, "Top"), entry(builder, "Top.analog")}));
    return result;
  }

  bool emit(mlir::ModuleOp module, std::string &output,
            nodal::BackendKind kind = nodal::BackendKind::VerilogA) {
    diagnostics.clear();
    llvm::raw_string_ostream stream(output);
    return mlir::succeeded(nodal::emitBackend(module, kind, stream));
  }

  bool rejects(mlir::Attribute map, llvm::StringRef message) {
    auto model = module();
    (*model)->setAttr("nodal.backend.source_provenance", builder.getBoolAttr(true));
    (*model)->setAttr("nodal.bridge.source_map", map);
    std::string before = print(*model);
    std::string output = "existing-output\n";
    return !emit(*model, output) && output == "existing-output\n" && before == print(*model) &&
           diagnostics.find("NODAL-SOURCE-MAP-001") != std::string::npos &&
           diagnostics.find(message.str()) != std::string::npos;
  }

  int fail(llvm::StringRef message) {
    llvm::errs() << "statement provenance: " << message << "\n" << diagnostics;
    return 1;
  }
};
} // namespace

int main() {
  Harness h;
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    auto model = h.module();
    if (!model || mlir::failed(mlir::verify(*model)))
      return h.fail("fixture did not parse and verify");
    std::string baseline;
    if (!h.emit(*model, baseline, kind) || baseline.find("nodal-source-v1") != std::string::npos)
      return h.fail("default emission changed");
    (*model)->setAttr("nodal.backend.source_provenance", h.builder.getBoolAttr(false));
    std::string disabled;
    if (!h.emit(*model, disabled, kind) || baseline != disabled)
      return h.fail("explicit false must retain default bytes");
    (*model)->setAttr("nodal.backend.source_provenance", h.builder.getBoolAttr(true));
    std::string before = print(*model);
    std::string enabled;
    if (!h.emit(*model, enabled, kind) || before != print(*model))
      return h.fail("opted-in emission failed or mutated input");
    if (withoutMarkers(enabled) != baseline ||
        llvm::StringRef(enabled).count("// nodal-source-v1:") != 3 ||
        enabled.find(marker("Top.gain") + "  parameter real gain = 2;") == std::string::npos ||
        enabled.find(marker("Origin.instance") + "  Child child();") == std::string::npos ||
        enabled.find(marker("Origin.current") + "    I(p, n) <+ 1;") == std::string::npos ||
        enabled.find("input.mlir") != std::string::npos ||
        enabled.find("// nodal-source-v1: VG9wLmdhaW4=|") == std::string::npos)
      return h.fail("markers must precede exactly the actual mapped statements");
    std::string repeated;
    if (!h.emit(*model, repeated, kind) || enabled != repeated)
      return h.fail("provenance emission is not deterministic");
    (*model)->removeAttr("nodal.bridge.source_map");
    std::string missing;
    if (!h.emit(*model, missing, kind) || missing != baseline)
      return h.fail("missing map must not invent transport-location provenance");
    (*model)->setAttr("nodal.bridge.source_map", h.builder.getArrayAttr({entry(h.builder, "Top")}));
    std::string ancestorOnly;
    if (!h.emit(*model, ancestorOnly, kind) || ancestorOnly != baseline)
      return h.fail("ancestor semantic identity must not map a statement");
    (*model)->setAttr("nodal.bridge.source_map",
                      h.builder.getArrayAttr({h.builder.getDictionaryAttr({h.builder.getNamedAttr(
                          "semantic_path", h.builder.getStringAttr("Top.gain"))})}));
    std::string missingSpan;
    if (!h.emit(*model, missingSpan, kind) || missingSpan != baseline)
      return h.fail("a missing authored span must not invent a marker");
  }

  std::string generated = source.str();
  const std::string analogStart = "    \"nodal.analog\"() <{metadata";
  const std::string generatedStart =
      "    \"nodal.generate\"() <{induction = \"i\", lower = 0 : i64, upper = 2 : i64, "
      "step = 1 : i64, region_id = \"Top.range\", induction_path = \"Top.range.index\", "
      "metadata = {maximum_trip_count = 2 : i64}}> ({\n";
  generated.insert(generated.find(analogStart), generatedStart);
  const std::string ownership =
      "generated_owner = \"Top.range\", generated_induction = \"Top.range.index\", ";
  generated.replace(generated.find(analogStart), analogStart.size(),
                    "    \"nodal.analog\"() <{" + ownership + "metadata");
  const std::string contributionStart = "<{kind = \"flow\", ";
  generated.insert(generated.find(contributionStart) + contributionStart.size(), ownership);
  generated.insert(generated.rfind("  }) : () -> ()"), "    }) : () -> ()\n");
  auto generatedModel = mlir::parseSourceString<mlir::ModuleOp>(generated, &h.context);
  if (!generatedModel)
    return h.fail("generated contribution fixture did not parse");
  (*generatedModel)->setAttr("nodal.backend.source_provenance", h.builder.getBoolAttr(true));
  (*generatedModel)
      ->setAttr("nodal.bridge.source_map",
                h.builder.getArrayAttr({entry(h.builder, "Origin.current")}));
  std::string generatedOutput;
  if (!h.emit(*generatedModel, generatedOutput) ||
      llvm::StringRef(generatedOutput).count("// nodal-source-v1:") != 1 ||
      generatedOutput.find(marker("Origin.current")) == std::string::npos)
    return h.fail("finite generated contribution must retain exact statement provenance");

  auto model = h.module();
  auto index = nodal::AuthoredSourceMap::create(*model);
  auto foreign = h.module();
  if (mlir::failed(index) || !index->resolve(find(*model, "nodal.parameter")) ||
      index->resolve(find(*foreign, "nodal.parameter")))
    return h.fail("source index must resolve only operations in its actual owning root");
  auto *parameter = find(*model, "nodal.parameter");
  parameter->setAttr("metadata", h.builder.getDictionaryAttr({}));
  if (index->resolve(parameter))
    return h.fail("source index must not inherit parent identity");
  const auto legacy = nodal::collectDiagnosticContext(parameter);
  if (legacy.semanticPath != "Top" || legacy.sourceRange != "input.mlir:999:77-999:77")
    return h.fail("existing diagnostic context behavior changed");

  auto valid = entry(h.builder, "Top.gain");
  if (!h.rejects(h.builder.getStringAttr("bad"), "array") ||
      !h.rejects(h.builder.getArrayAttr({h.builder.getStringAttr("bad")}), "dictionary") ||
      !h.rejects(h.builder.getArrayAttr({h.builder.getDictionaryAttr({})}), "semantic path") ||
      !h.rejects(h.builder.getArrayAttr({valid, valid}), "duplicate") ||
      !h.rejects(h.builder.getArrayAttr({valid, entry(h.builder, "Top.gain", "forged.scala")}),
                 "duplicate"))
    return h.fail("malformed map and duplicate identities must fail transactionally");
  for (llvm::StringRef field :
       {"source_line", "source_column", "source_end_line", "source_end_column"}) {
    for (int64_t invalid : {int64_t(0), int64_t(-1)}) {
      mlir::NamedAttrList attributes(valid);
      attributes.set(field, h.builder.getI64IntegerAttr(invalid));
      if (!h.rejects(h.builder.getArrayAttr({attributes.getDictionary(&h.context)}), "positive"))
        return h.fail("nonpositive span coordinates must fail transactionally");
    }
  }
  for (auto [field, value] :
       {std::pair("source_end_line", int64_t(11)), std::pair("source_end_column", int64_t(2))}) {
    mlir::NamedAttrList attributes(valid);
    attributes.set("source_end_line", h.builder.getI64IntegerAttr(12));
    attributes.set(field, h.builder.getI64IntegerAttr(value));
    if (!h.rejects(h.builder.getArrayAttr({attributes.getDictionary(&h.context)}), "precedes"))
      return h.fail("reversed spans must fail transactionally");
  }
  mlir::NamedAttrList widePosition(valid);
  widePosition.set("source_line",
                   h.builder.getIntegerAttr(h.builder.getIntegerType(128),
                                            llvm::APInt(128, "9223372036854775808", 10)));
  if (!h.rejects(h.builder.getArrayAttr({widePosition.getDictionary(&h.context)}), "signed-64"))
    return h.fail("out-of-range source coordinate must fail without truncation");
  widePosition.set("source_line", h.builder.getStringAttr("12"));
  if (!h.rejects(h.builder.getArrayAttr({widePosition.getDictionary(&h.context)}), "positive"))
    return h.fail("noninteger coordinate must fail transactionally");

  mlir::NamedAttrList incomplete(valid);
  incomplete.erase("source_end_column");
  if (!h.rejects(h.builder.getArrayAttr({incomplete.getDictionary(&h.context)}), "positive"))
    return h.fail("partial span must fail transactionally");
  mlir::NamedAttrList invalidPath(valid);
  invalidPath.set("source_path", h.builder.getStringAttr(""));
  if (!h.rejects(h.builder.getArrayAttr({invalidPath.getDictionary(&h.context)}), "source path"))
    return h.fail("empty source path must fail transactionally");
  invalidPath.set("source_path", h.builder.getStringAttr(llvm::StringRef("\xff", 1)));
  if (!h.rejects(h.builder.getArrayAttr({invalidPath.getDictionary(&h.context)}), "UTF-8"))
    return h.fail("invalid UTF-8 must fail transactionally");

  for (mlir::Attribute flag : {mlir::Attribute(h.builder.getStringAttr("true")),
                               mlir::Attribute(h.builder.getI64IntegerAttr(1))}) {
    auto invalid = h.module();
    (*invalid)->setAttr("nodal.backend.source_provenance", flag);
    std::string before = print(*invalid);
    std::string output = "existing-output\n";
    if (h.emit(*invalid, output) || output != "existing-output\n" || before != print(*invalid) ||
        h.diagnostics.find("NODAL-BACKEND-CONFIG-003") == std::string::npos)
      return h.fail("non-Boolean option must reject without publishing or mutating input");
  }
  auto rejectedTarget = h.module();
  (*rejectedTarget)->setAttr("nodal.backend.source_provenance", h.builder.getBoolAttr(true));
  const std::string beforeRejectedTarget = print(*rejectedTarget);
  std::string unpublished = "existing-output\n";
  llvm::raw_string_ostream unpublishedStream(unpublished);
  RejectMappedTarget hooks;
  if (mlir::succeeded(nodal::emitBackend(*rejectedTarget, nodal::BackendKind::VerilogA,
                                         unpublishedStream, &hooks)) ||
      !hooks.observedProvenance || unpublished != "existing-output\n" ||
      print(*rejectedTarget) != beforeRejectedTarget)
    return h.fail("rejected mapped target candidate must remain private and leave input unchanged");

  auto malformedDisabled = h.module();
  (*malformedDisabled)->setAttr("nodal.bridge.source_map", h.builder.getArrayAttr({valid, valid}));
  std::string disabled;
  if (!h.emit(*malformedDisabled, disabled))
    return h.fail("default emission must retain legacy source-map acceptance");

  auto unsafePath = h.module();
  constexpr llvm::StringLiteral hostile = "path */\n// pretend-marker | model.scala";
  (*unsafePath)->setAttr("nodal.backend.source_provenance", h.builder.getBoolAttr(true));
  (*unsafePath)
      ->setAttr("nodal.bridge.source_map",
                h.builder.getArrayAttr({entry(h.builder, "Top.gain", hostile)}));
  std::string safe;
  if (!h.emit(*unsafePath, safe) || safe.find(marker("Top.gain", hostile)) == std::string::npos ||
      safe.find("pretend-marker") != std::string::npos)
    return h.fail("source paths must remain inert encoded data");
  llvm::outs() << "statement provenance cases passed\n";
  return 0;
}
