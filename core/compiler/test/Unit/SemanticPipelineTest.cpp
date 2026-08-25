#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

#include <string>

namespace {

int fail(llvm::StringRef message) {
  llvm::errs() << "NODAL-SEMANTIC-PIPELINE-TEST: " << message << '\n';
  return 1;
}

std::string print(mlir::ModuleOp module) {
  std::string text;
  llvm::raw_string_ostream stream(text);
  module.print(stream);
  stream.flush();
  return text;
}

constexpr llvm::StringLiteral validSource = R"mlir(
module attributes {
  nodal.target.profile = "target_neutral",
  nodal.verify.analog_topology = true,
  nodal.verify.assignment_coverage = true,
  nodal.verify.cdc_rdc_safe = true,
  nodal.verify.clock_reset_domains = true,
  nodal.verify.combinational_acyclic = true,
  nodal.verify.construction_closed = true,
  nodal.verify.driver_coverage = true,
  nodal.verify.enum_fsm = true,
  nodal.verify.hierarchy_closed = true,
  nodal.verify.latch_free = true,
  nodal.verify.layout_storage = true,
  nodal.verify.memory_effects = true,
  nodal.verify.mixed_signal_bridges = true,
  nodal.verify.parameters_complete = true,
  nodal.verify.protocol_pipeline = true,
  nodal.verify.target_capability = true,
  nodal.verify.width_sign_shape = true
} {
  "nodal.module"() <{
    metadata = {},
    sym_name = "Top"
  }> ({
    "nodal.domain"() <{
      edge = "rising",
      metadata = {},
      reset_policy = "sync",
      sym_name = "core"
    }> : () -> ()
    "nodal.port"() <{
      direction = "input",
      domain = @core,
      metadata = {},
      sym_name = "enable",
      type = !nodal.bits<1>
    }> : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral invalidSource = R"mlir(
module attributes {
  nodal.target.profile = "target_neutral",
  nodal.verify.assignment_coverage = true,
  nodal.verify.construction_closed = true,
  nodal.verify.driver_coverage = false,
  nodal.verify.hierarchy_closed = true,
  nodal.verify.latch_free = true,
  nodal.verify.combinational_acyclic = true,
  nodal.verify.width_sign_shape = true,
  nodal.verify.layout_storage = true,
  nodal.verify.parameters_complete = true,
  nodal.verify.enum_fsm = true,
  nodal.verify.clock_reset_domains = true,
  nodal.verify.cdc_rdc_safe = true,
  nodal.verify.protocol_pipeline = true,
  nodal.verify.memory_effects = true,
  nodal.verify.analog_topology = true,
  nodal.verify.mixed_signal_bridges = true,
  nodal.verify.target_capability = true
} {
  "nodal.module"() <{
    metadata = {},
    sym_name = "Rejected"
  }> ({
    "nodal.domain"() <{
      edge = "rising",
      metadata = {},
      reset_policy = "sync",
      sym_name = "core"
    }> : () -> ()
  }) : () -> ()
}
)mlir";

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  nodal::registerNodalPasses();

  auto valid = mlir::parseSourceString<mlir::ModuleOp>(validSource, &context);
  auto invalid = mlir::parseSourceString<mlir::ModuleOp>(invalidSource, &context);
  if (!valid || !invalid)
    return fail("transaction fixtures did not parse");

  nodal::PipelineSession session(&context);
  if (mlir::failed(session.accept(*valid, nodal::GateProfile::Default)))
    return fail("valid candidate was rejected");
  if (!session.hasAccepted())
    return fail("accepted state was not retained");

  std::string accepted = print(session.getAccepted());
  if (accepted.find("nodal.pipeline.normalized = \"v1\"") == std::string::npos ||
      accepted.find("nodal.pipeline.profile = \"default\"") == std::string::npos)
    return fail("accepted state lacks normalization evidence");

  if (mlir::succeeded(session.accept(*invalid, nodal::GateProfile::Default)))
    return fail("invalid candidate was accepted");
  if (print(session.getAccepted()) != accepted)
    return fail("failed candidate replaced the last accepted state");

  std::string invalidBefore = print(*invalid);
  if (mlir::succeeded(nodal::runNodalPipelineTransaction(*invalid, nodal::GateProfile::Default)))
    return fail("invalid in-place transaction was accepted");
  if (print(*invalid) != invalidBefore)
    return fail("failed in-place transaction mutated its input module");

  auto release = mlir::parseSourceString<mlir::ModuleOp>(validSource, &context);
  if (!release ||
      mlir::failed(nodal::runNodalPipelineTransaction(*release, nodal::GateProfile::Release)))
    return fail("release transaction failed");
  auto profile = release->getOperation()->getAttrOfType<mlir::StringAttr>("nodal.pipeline.profile");
  if (!profile || profile.getValue() != "release")
    return fail("release transaction recorded the wrong profile");

  auto clone = session.cloneAccepted();
  if (!clone || print(*clone) != accepted)
    return fail("accepted-state clone is not stable");

  return 0;
}
