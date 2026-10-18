#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Backend/AnalogVerticalSlice.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"

#include "llvm/ADT/SmallVector.h"
#include "llvm/Support/raw_ostream.h"

#include <cstdint>
#include <fstream>
#include <functional>
#include <string>
#include <utility>
#include <vector>

namespace {
constexpr llvm::StringLiteral kNested = R"mlir(module attributes {nodal.target.profile = "analog"} {
"nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", dimension = "voltage", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
"nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", dimension = "current", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
"nodal.discipline"() <{domain = "continuous", flow = @Current, potential = @Voltage, metadata = {}, sym_name = "electrical"}> : () -> ()
"nodal.module"() <{sym_name = "Child", metadata = {semantic_path = "Child"}}> ({
"nodal.component_contract"() <{kind = "partial", connectivity_ownership = "extensible", source_path = "Child", metadata = {}}> : () -> ()
}) : () -> ()
"nodal.module"() <{sym_name = "Parent", metadata = {semantic_path = "Parent"}}> ({
"nodal.component_contract"() <{kind = "partial", connectivity_ownership = "extensible", source_path = "Parent", metadata = {}}> : () -> ()
%c0 = "nodal.const_literal"() <{metadata = {}, spelling = "0", value = 0 : i64}> : () -> i64
%c1 = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
%c2 = "nodal.const_literal"() <{metadata = {}, spelling = "2", value = 2 : i64}> : () -> i64
%c3 = "nodal.const_literal"() <{metadata = {}, spelling = "3", value = 3 : i64}> : () -> i64
%c4 = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
%c5 = "nodal.const_literal"() <{metadata = {}, spelling = "5", value = 5 : i64}> : () -> i64
%c6 = "nodal.const_literal"() <{metadata = {}, spelling = "6", value = 6 : i64}> : () -> i64
%c7 = "nodal.const_literal"() <{metadata = {}, spelling = "7", value = 7 : i64}> : () -> i64
%c8 = "nodal.const_literal"() <{metadata = {}, spelling = "8", value = 8 : i64}> : () -> i64
%a = "nodal.node_array"() <{name = "a", source_path = "Parent.a", metadata = {semantic_path = "Parent.a", storage = "structural"}}> : () -> !nodal.shaped<"4", !nodal.terminal<"electrical">>
%n = "nodal.node"() <{name = "n", source_path = "Parent.n", metadata = {semantic_path = "Parent.n"}}> : () -> !nodal.terminal<"electrical">
"nodal.generate"() <{induction = "i", lower = 0 : i64, upper = 1 : i64, step = 1 : i64, region_id = "Parent.gen", induction_path = "Parent.gen.index", metadata = {maximum_trip_count = 1 : i64}}> ({
^bb0(%i: i64):
%ga = "nodal.shape_index"(%a, %i) <{generated_owner = "Parent.gen", generated_induction = "Parent.gen.index", metadata = {input_path = "Parent.a", semantic_path = "Parent.a.at(induction:Parent.gen.index).in(Parent.gen)", source_uses = ["Parent.gen.use_ga"], storage = "structural", selection_kind = "node_array"}}> : (!nodal.shaped<"4", !nodal.terminal<"electrical">>, i64) -> !nodal.terminal<"electrical">
"nodal.generate"() <{induction = "j", lower = 0 : i64, upper = 1 : i64, step = 1 : i64, region_id = "Parent.gen.inner", induction_path = "Parent.gen.inner.index", metadata = {maximum_trip_count = 1 : i64}}> ({
^bb0(%j: i64):
"nodal.connect"(%ga, %n) <{connection_id = "Parent.nestedFamily", source_path = "Parent.nestedFamily", generated_owner = "Parent.gen.inner", generated_induction = "Parent.gen.inner.index", metadata = {semantic_path = "Parent.nestedFamily"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
%first = "nodal.shape_index"(%a, %c0) <{generated_owner = "Parent.gen.inner", generated_induction = "Parent.gen.inner.index", metadata = {input_path = "Parent.a", semantic_path = "Parent.a.at(literal:0).in(Parent.gen.inner)", source_uses = ["Parent.gen.inner.use_first"], storage = "structural", selection_kind = "node_array"}}> : (!nodal.shaped<"4", !nodal.terminal<"electrical">>, i64) -> !nodal.terminal<"electrical">
%second = "nodal.shape_index"(%a, %c1) <{generated_owner = "Parent.gen.inner", generated_induction = "Parent.gen.inner.index", metadata = {input_path = "Parent.a", semantic_path = "Parent.a.at(literal:1).in(Parent.gen.inner)", source_uses = ["Parent.gen.inner.use_second"], storage = "structural", selection_kind = "node_array"}}> : (!nodal.shaped<"4", !nodal.terminal<"electrical">>, i64) -> !nodal.terminal<"electrical">
"nodal.analog"() <{generated_owner = "Parent.gen.inner", generated_induction = "Parent.gen.inner.index", metadata = {}}> ({
%access = "nodal.terminal_access"(%first, %second) <{kind = "flow", function = "I", metadata = {semantic_path = "Parent.access"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.quantity<"real", "current">
"nodal.analog_procedure"() <{owner = "Parent", generated_owner = "Parent.gen.inner", generated_induction = "Parent.gen.inner.index", metadata = {}}> ({
^bb0:
}) : () -> ()
}) : () -> ()
}) : () -> ()
}) : () -> ()
}) : () -> ()
}
)mlir";

bool replace(std::string &text, llvm::StringRef before, llvm::StringRef after) {
  const auto offset = text.find(before.str());
  if (offset == std::string::npos)
    return false;
  text.replace(offset, before.size(), after.str());
  return true;
}

std::string print(mlir::ModuleOp module) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  module.print(stream);
  stream.flush();
  return result;
}

mlir::Operation *find(mlir::ModuleOp module, llvm::StringRef name, llvm::StringRef region = {}) {
  mlir::Operation *result = nullptr;
  module.walk([&](mlir::Operation *operation) {
    auto id = operation->getAttrOfType<mlir::StringAttr>("region_id");
    if (!result && operation->getName().getStringRef() == name &&
        (region.empty() || (id && id.getValue() == region)))
      result = operation;
  });
  return result;
}

std::string generate(llvm::StringRef body, llvm::StringRef owner, llvm::StringRef induction,
                     int64_t count, bool argument = true) {
  return "\"nodal.generate\"() <{induction = \"" + induction.str() +
         "\", lower = 0 : i64, upper = " + std::to_string(count) +
         " : i64, step = 1 : i64, region_id = \"" + owner.str() + "\", induction_path = \"" +
         owner.str() + ".index\", metadata = {maximum_trip_count = " + std::to_string(count) +
         " : i64}}> ({\n" + (argument ? "^bb0(%" + induction.str() + ": i64):\n" : "") +
         body.str() + "}) : () -> ()\n";
}

std::string analog(llvm::StringRef access, llvm::StringRef owner) {
  return "\"nodal.analog\"() <{generated_owner = \"" + owner.str() +
         "\", generated_induction = \"" + owner.str() + ".index\", metadata = {}}> ({\n" +
         access.str() + "\"nodal.analog_procedure\"() <{owner = \"Parent\", generated_owner = \"" +
         owner.str() + "\", generated_induction = \"" + owner.str() +
         ".index\", metadata = {}}> ({\n^bb0:\n}) : () -> ()\n}) : () -> ()\n";
}

std::string design(llvm::StringRef body) {
  auto prefix = kNested.take_front(kNested.find("\"nodal.generate\""));
  return prefix.str() + body.str() + "}) : () -> ()\n}\n";
}

nodal::BackendConfiguration configuration(nodal::BackendKind kind) {
  return {&nodal::getBackendProfile(kind), nodal::GateProfile::Default,
          nodal::ShapedValueLayout::ScalarOrFlat, nodal::MaterializationPolicy::SafeInline,
          nodal::NamingPolicy::Semantic};
}
} // namespace

int main(int argc, char **argv) {
  mlir::MLIRContext context;
  context.loadDialect<nodal::NodalDialect, circt::hw::HWDialect>();
  unsigned checks = 0;
  auto check = [&](bool valid, const std::string &message) {
    ++checks;
    if (!valid)
      llvm::errs() << "GeneratedProbeBackendTest: " << message << "\n";
    return valid;
  };
  auto positive = [&](const std::string &source, const std::string &name,
                      llvm::StringRef expectedScope, bool namedBranch = false) {
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!check(module && mlir::succeeded(mlir::verify(*module)), name + " native source") ||
        !check(mlir::succeeded(nodal::normalizePotentialFlowAccess(*module)) &&
                   mlir::succeeded(nodal::verifyPotentialFlowAccessModel(*module)),
               name + " normalized native model"))
      return false;
    unsigned probes = 0;
    bool placement = true;
    module->walk([&](mlir::Operation *operation) {
      if (operation->getName().getStringRef() != "nodal.probe")
        return;
      ++probes;
      auto owner = operation->getAttrOfType<mlir::StringAttr>("generated_owner");
      auto induction = operation->getAttrOfType<mlir::StringAttr>("generated_induction");
      auto parent = operation->getParentOp()->getAttrOfType<mlir::StringAttr>("region_id");
      placement &= owner && owner.getValue() == expectedScope && parent &&
                   parent.getValue() == expectedScope && induction &&
                   induction.getValue() == (expectedScope + ".index").str();
    });
    if (!check(probes == (expectedScope.empty() ? 0u : 1u) && placement,
               name + " exact active generated support"))
      return false;
    const auto normalized = print(*module);
    if (!check(mlir::succeeded(nodal::normalizePotentialFlowAccess(*module)) &&
                   normalized == print(*module),
               name + " idempotent normalization"))
      return false;
    for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
      auto original = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
      std::string target, repeated, direct;
      llvm::raw_string_ostream output(target), replay(repeated), sourceOutput(direct);
      if (!check(mlir::succeeded(nodal::emitBackend(*module, kind, output)) &&
                     mlir::succeeded(nodal::emitBackend(*module, kind, replay)) &&
                     mlir::succeeded(nodal::emitBackend(*original, kind, sourceOutput)),
                 name + " target emission"))
        return false;
      output.flush();
      replay.flush();
      sourceOutput.flush();
      if (!check(target == repeated && target == direct,
                 name + " exact source/default-gate/normalized repeat parity") ||
          !check(mlir::succeeded(nodal::reparseBackendTarget(target, configuration(kind))),
                 name + " target reparse") ||
          !check(!llvm::StringRef(target).contains("<+") &&
                     llvm::StringRef(target).count("branch (") == (namedBranch ? 1u : 0u),
                 name + " intent adds no equation or duplicate branch"))
        return false;
      if (argc > 1) {
        const auto stem = std::string(argv[1]) + "/" + name;
        std::ofstream(stem + ".source.mlir") << source;
        std::ofstream(stem + ".normalized.mlir") << normalized;
        std::ofstream(stem + (kind == nodal::BackendKind::VerilogA ? ".va" : ".vams")) << target;
      }
    }
    return true;
  };
  if (!positive(kNested.str(), "retained-nested-flow", "Parent.gen.inner"))
    return 1;
  auto empty = kNested.str();
  if (!check(replace(empty, "upper = 1 : i64", "upper = 0 : i64") &&
                 replace(empty, "maximum_trip_count = 1 : i64", "maximum_trip_count = 0 : i64"),
             "empty ancestor fixture") ||
      !positive(empty, "empty-ancestor", ""))
    return 1;

  const auto access =
      R"mlir(%access = "nodal.terminal_access"(%n, %m) <{kind = "flow", function = "I", metadata = {semantic_path = "Parent.access"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.quantity<"real", "current">
)mlir";
  const auto moduleNode =
      R"mlir(%m = "nodal.node"() <{name = "m", source_path = "Parent.m", metadata = {semantic_path = "Parent.m"}}> : () -> !nodal.terminal<"electrical">
)mlir";
  const auto legacy = design(
      moduleNode + generate(analog(access, "Parent.legacy"), "Parent.legacy", "k", 1, false));
  if (!positive(legacy, "legacy-singleton", "Parent.legacy"))
    return 1;
  auto wideLegacy = legacy;
  if (!check(replace(wideLegacy, "lower = 0 : i64, upper = 1 : i64",
                     "lower = 999999999999 : i64, upper = 1000000000000 : i64"),
             "wide-coordinate singleton fixture") ||
      !positive(wideLegacy, "legacy-wide-coordinate", "Parent.legacy"))
    return 1;

  const auto selected =
      R"mlir(%selected = "nodal.shape_index"(%a, %i) <{generated_owner = "Parent.gen", generated_induction = "Parent.gen.index", metadata = {input_path = "Parent.a", semantic_path = "Parent.a.at(induction:Parent.gen.index).in(Parent.gen)", source_uses = ["Parent.gen.use_selected"], storage = "structural", selection_kind = "node_array"}}> : (!nodal.shaped<"4", !nodal.terminal<"electrical">>, i64) -> !nodal.terminal<"electrical">
)mlir";
  const auto selectedAccess =
      R"mlir(%access = "nodal.terminal_access"(%selected, %n) <{kind = "flow", function = "I", metadata = {semantic_path = "Parent.access"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.quantity<"real", "current">
)mlir";
  if (!positive(
          design(generate(selected + analog(selectedAccess, "Parent.gen"), "Parent.gen", "i", 3)),
          "direct-induction-flow", "Parent.gen"))
    return 1;

  const auto local =
      R"mlir(%local = "nodal.node"() <{name = "localNet", source_path = "Parent.gen.localNet", generated_owner = "Parent.gen", generated_induction = "Parent.gen.index", metadata = {semantic_path = "Parent.gen.localNet"}}> : () -> !nodal.terminal<"electrical">
%sense = "nodal.branch"(%local, %n) <{name = "senseBranch", declaration_kind = "named", source_path = "Parent.gen.senseBranch", metadata = {semantic_path = "Parent.gen.senseBranch", generated_owner = "Parent.gen", generated_induction = "Parent.gen.index"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
)mlir";
  const auto branchAccess =
      R"mlir(%access = "nodal.access"(%sense) <{kind = "flow", metadata = {semantic_path = "Parent.access"}}> : (!nodal.branch<"electrical">) -> !nodal.quantity<"real", "current">
)mlir";
  if (!positive(design(generate(local + generate(analog(branchAccess, "Parent.gen.inner"),
                                                 "Parent.gen.inner", "j", 1),
                                "Parent.gen", "i", 2)),
                "fresh-named-descendant", "Parent.gen.inner", true))
    return 1;

  const auto sibling = generate("", "Parent.sibling", "s", 1);
  auto forgedSource = kNested.str();
  const auto insertion = forgedSource.rfind("}) : () -> ()\n}");
  forgedSource.insert(insertion, sibling);
  using Mutation = std::function<void(mlir::ModuleOp, mlir::Operation *)>;
  for (const auto &[name, mutate] : std::vector<std::pair<std::string, Mutation>>{
           {"wrong owner",
            [&](mlir::ModuleOp, mlir::Operation *probe) {
              probe->setAttr("generated_owner", mlir::StringAttr::get(&context, "Parent.sibling"));
            }},
           {"wrong induction",
            [&](mlir::ModuleOp, mlir::Operation *probe) {
              probe->setAttr("generated_induction",
                             mlir::StringAttr::get(&context, "Parent.gen.index"));
            }},
           {"moved to module",
            [&](mlir::ModuleOp module, mlir::Operation *probe) {
              auto *scope = find(module, "nodal.generate", "Parent.gen");
              probe->moveAfter(scope);
            }},
           {"moved to sibling",
            [&](mlir::ModuleOp module, mlir::Operation *probe) {
              auto *scope = find(module, "nodal.generate", "Parent.sibling");
              probe->moveBefore(&scope->getRegion(0).front(), scope->getRegion(0).front().end());
            }},
           {"duplicate",
            [&](mlir::ModuleOp, mlir::Operation *probe) {
              probe->getBlock()->getOperations().insertAfter(probe->getIterator(), probe->clone());
            }},
           {"missing", [&](mlir::ModuleOp, mlir::Operation *probe) { probe->erase(); }},
           {"forged provenance", [&](mlir::ModuleOp, mlir::Operation *probe) {
              probe->setAttr("provenance", mlir::ArrayAttr::get(&context, {}));
            }}}) {
    auto module = mlir::parseSourceString<mlir::ModuleOp>(forgedSource, &context);
    if (!check(module && mlir::succeeded(mlir::verify(*module)) &&
                   mlir::succeeded(nodal::normalizePotentialFlowAccess(*module)),
               name + " valid normalized start"))
      return 1;
    auto *probe = find(*module, "nodal.probe");
    if (!check(probe != nullptr, name + " has actual derived probe"))
      return 1;
    mutate(*module, probe);
    if (!check(mlir::failed(nodal::verifyPotentialFlowAccessModel(*module)),
               name + " independent model rejection"))
      return 1;
    llvm::SmallVector<mlir::Operation *> definitions;
    for (mlir::Operation &operation : module->getBody()->getOperations())
      if (operation.getName().getStringRef() == "nodal.module")
        definitions.push_back(&operation);
    std::string target = "sentinel";
    llvm::raw_string_ostream stream(target);
    const auto status = nodal::renderBackendCandidate(
        definitions, configuration(nodal::BackendKind::VerilogA), stream);
    stream.flush();
    if (!check(mlir::failed(status) && target == "sentinel",
               name + " direct rendering rejects before output"))
      return 1;
  }
  llvm::outs() << checks << " generated-probe backend checks passed\n";
  return 0;
}
