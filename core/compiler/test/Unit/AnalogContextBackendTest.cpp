#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Backend/AnalogVerticalSlice.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/raw_ostream.h"

#include <string>

namespace {
constexpr llvm::StringLiteral source = R"mlir(
module attributes {nodal.target.profile = "analog"} {
  "nodal.module"() <{sym_name = "ContextQueries", metadata = {root = true}}> ({
    %p = "nodal.terminal"() <{name = "p", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{name = "n", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
      %ambient = "nodal.analog_context"() <{query_kind = "temperature", registry_version = "1", metadata = {semantic_path = "ContextQueries.ambient"}}> : () -> !nodal.quantity<"real", "temperature">
      %nominal = "nodal.analog_context"() <{query_kind = "nominal_temperature", registry_version = "1", metadata = {semantic_path = "ContextQueries.nominal"}}> : () -> !nodal.quantity<"real", "temperature">
      %ratio = "nodal.analog_div"(%ambient, %nominal) <{metadata = {}}> : (!nodal.quantity<"real", "temperature">, !nodal.quantity<"real", "temperature">) -> !nodal.quantity<"real", "1">
      %voltage = "nodal.access"(%branch) <{kind = "potential", metadata = {}}> : (!nodal.branch<"electrical">) -> !nodal.quantity<"real", "voltage">
      %scaled = "nodal.analog_mul"(%ratio, %voltage) <{metadata = {}}> : (!nodal.quantity<"real", "1">, !nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "voltage">
      "nodal.contribute"(%branch, %scaled) <{kind = "potential", metadata = {}}> : (!nodal.branch<"electrical">, !nodal.quantity<"real", "voltage">) -> ()
      "nodal.analog_procedure"() <{owner = "ContextQueries", metadata = {}}> ({
        "nodal.analog_if"() <{statement_id = "ContextQueries.if", owner = "ContextQueries", metadata = {}}> ({
          "nodal.analog_if_arm"() <{arm_id = "ContextQueries.if.arm", owner = "ContextQueries", is_else = false, stage = "runtime", condition_value = "bool_and(real_gt(analog_context_v1_temperature(),analog_context_v1_nominal_temperature()),real_gt(analog_abstime(),0.0 s))", condition_kind = "boolean", condition_dimension = "1", condition_reads = [], static_value_present = false, static_value = false, metadata = {}}> ({
            %held = "nodal.analog_variable"() <{identity = "ContextQueries.held", owner = "ContextQueries", declaration_order = 0 : i64, initialized = true, initializer_value = "1.0", initializer_kind = "real", initializer_dimension = "1", initializer_reads = [], metadata = {}}> : () -> !nodal.variable<"real", "1">
          }) : () -> ()
        }) : () -> ()
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";
std::string generatedSource() {
  std::string result = source.str();
  const auto analogStart = result.find("    \"nodal.analog\"");
  const auto procedureStart = result.find("      \"nodal.analog_procedure\"");
  result.resize(procedureStart);
  result += "    }) : () -> ()\n  }) : () -> ()\n}\n";
  const std::string ownership = "generated_owner = \"ContextQueries.gen\", generated_induction = "
                                "\"ContextQueries.gen.index\", ";
  for (const auto &anchor :
       {std::string("\"nodal.analog\"() <{"), std::string("\"nodal.analog_context\"() <{"),
        std::string("\"nodal.contribute\"(%branch, %scaled) <{")}) {
    size_t position = 0;
    while ((position = result.find(anchor, position)) != std::string::npos) {
      position += anchor.size();
      result.insert(position, ownership);
      position += ownership.size();
    }
  }
  result.insert(analogStart,
                "\"nodal.generate\"() <{induction = \"g\", lower = 0 : i64, upper = 2 : i64, "
                "step = 1 : i64, region_id = \"ContextQueries.gen\", "
                "induction_path = \"ContextQueries.gen.index\", metadata = {maximum_trip_count = 2 "
                ": i64}}> ({\n");
  result.insert(result.rfind("  })"), "    }) : () -> ()\n");
  return result;
}
} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect, circt::hw::HWDialect>();
  mlir::MLIRContext context(registry);
  std::string diagnostic;
  mlir::ScopedDiagnosticHandler diagnostics(&context, [&](mlir::Diagnostic &message) {
    llvm::raw_string_ostream stream(diagnostic);
    message.print(stream);
    return mlir::success();
  });
  auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
  if (!module) {
    llvm::errs() << diagnostic << '\n';
    return 1;
  }
  unsigned cases = 0;
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    std::string previous;
    for (unsigned repetition = 0; repetition < 2; ++repetition) {
      std::string output;
      llvm::raw_string_ostream stream(output);
      if (mlir::failed(nodal::emitBackend(*module, kind, stream))) {
        llvm::errs() << "context emission failed: " << diagnostic << '\n';
        return 1;
      }
      stream.flush();
      const nodal::BackendConfiguration configuration{
          &nodal::getBackendProfile(kind), nodal::GateProfile::Default,
          nodal::ShapedValueLayout::ScalarOrFlat, nodal::MaterializationPolicy::SafeInline,
          nodal::NamingPolicy::Semantic};
      if (!llvm::StringRef(output).contains("($temperature / ($simparam(\"tnom\") + 273.15))") ||
          !llvm::StringRef(output).contains("($temperature > ($simparam(\"tnom\") + 273.15))") ||
          !llvm::StringRef(output).contains("($abstime > 0.0)") ||
          llvm::StringRef(output).contains("$simparam(\"tnom\",") ||
          mlir::failed(nodal::reparseBackendTarget(output, configuration)) ||
          (repetition && previous != output)) {
        llvm::errs() << "context target lost runtime units/identity/determinism: " << output
                     << '\n';
        return 1;
      }
      previous = output;
      ++cases;
    }
  }
  auto generated = mlir::parseSourceString<mlir::ModuleOp>(generatedSource(), &context);
  if (!generated) {
    llvm::errs() << "generated context fixture failed: " << diagnostic << '\n';
    return 1;
  }
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    std::string output;
    llvm::raw_string_ostream stream(output);
    if (mlir::failed(nodal::emitBackend(*generated, kind, stream)) ||
        !llvm::StringRef(output).contains("for (") ||
        !llvm::StringRef(output).contains("($temperature / ($simparam(\"tnom\") + 273.15))")) {
      llvm::errs() << "generated context emission failed: " << diagnostic << '\n' << output;
      return 1;
    }
    ++cases;
  }
  module->walk([&](nodal::AnalogContextOp query) {
    query->setAttr("nodal.folded", mlir::BoolAttr::get(&context, true));
  });
  std::string rejected = "sentinel";
  llvm::raw_string_ostream stream(rejected);
  if (mlir::succeeded(nodal::emitBackend(*module, nodal::BackendKind::VerilogA, stream)) ||
      rejected != "sentinel") {
    llvm::errs() << "forged runtime query published target output\n";
    return 1;
  }
  llvm::outs() << "Foundation 44 context backend: " << cases + 1 << " focused cases passed\n";
  return 0;
}
