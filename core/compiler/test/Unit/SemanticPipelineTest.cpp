#include "EventTestFixtures.h"
#include "WaiverTestFixtures.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/PassManager.h"
#include "mlir/Pass/PassRegistry.h"
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

  // Domain absence remains narrow: actual verified event root inputs (even
  // unused represented inputs) and tracked outputs, never arbitrary @none.
  std::string diagnostics;
  mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
    llvm::raw_string_ostream stream(diagnostics);
    diagnostic.getLocation().print(stream);
    diagnostic.print(stream);
    return mlir::success();
  });
  auto root = [](const std::string &body) {
    return event_test::replace(event_test::wrap(body), "nodal.root.module = @Fixture",
                               "nodal.root.module = @Fixture, nodal.root.parameter_bindings = {}");
  };
  const std::string base = event_test::fixture();
  const std::string capture = event_test::setup() + event_test::port("data") +
                              event_test::process("event", "rising", "%output", "%data", 1, 1,
                                                  event_test::bit, event_test::bit) +
                              event_test::update("data");
  struct EventCase {
    llvm::StringRef name;
    std::string source;
    bool expected;
    llvm::StringRef diagnostic;
    llvm::StringRef standaloneDiagnostic;
  };
  const EventCase eventCases[] = {
      {"event-root", root(base), true, ""},
      {"unused-input", root(base + event_test::port("unused")), true, ""},
      {"unused-wide-input", root(base + event_test::port("unused", "input", "!nodal.bits<8192>")),
       true, ""},
      {"exact-capture", root(capture), true, ""},
      {"ordinary-none-input", root(event_test::port("input")), false, "NODAL-VERIFY-DOMAIN-003"},
      {"no-clock-exemption",
       root(base + event_test::port("clock", "input", "!nodal.capability<\"clock\">")), false,
       "Clock/Reset port requires an actual local domain", "NODAL-VERIFY-DOMAIN-003"},
      {"no-reset-exemption",
       root(base + event_test::port("reset", "input", "!nodal.capability<\"reset\">")), false,
       "Clock/Reset port requires an actual local domain", "NODAL-VERIFY-DOMAIN-003"},
      {"ordinary-none-output", root(base + event_test::port("extra", "output")), false,
       "NODAL-VERIFY-DOMAIN-003"},
      {"missing-update", root(event_test::setup() + event_test::process()), false,
       "exactly one event update"},
      {"forged-trigger", root(event_test::replace(base, "(%trigger, %output)", "(%one, %output)")),
       false, "exact unannotated root scalar Bool input"},
      {"missing-capture",
       root(event_test::setup() + event_test::port("data") + event_test::process() +
            event_test::update("data")),
       false, "exactly match actual RHS"},
      {"unused-capture",
       root(event_test::replace(capture, event_test::update("data"),
                                event_test::literal() + event_test::update())),
       false, "exactly match actual RHS"},
      {"attributed-trigger", root(event_test::replace(base, "domain = @none", "domain = @foreign")),
       false, "exact unannotated root scalar Bool input"},
      {"child-event",
       "module attributes {nodal.root.module = @Child, nodal.root.parameter_bindings = {}} {" +
           waiver_test::module(base) + waiver_test::module("", "Child") + "}",
       false, "actual selected application root"},
      {"other-module-none",
       "module attributes {nodal.root.module = @Fixture, nodal.root.parameter_bindings = {}} {" +
           waiver_test::module(base) + waiver_test::module(event_test::port("other"), "Other") +
           "}",
       false, "NODAL-VERIFY-DOMAIN-003"},
      {"instantiated-event-root",
       "module attributes {nodal.root.module = @Fixture, nodal.root.parameter_bindings = {}} {" +
           waiver_test::module(base) +
           waiver_test::module("\"nodal.instance\"() <{sym_name = \"used\", module = @Fixture, "
                               "parameter_bindings = {}, domain_bindings = {}, metadata = {}}> : "
                               "() -> ()\n",
                               "Other") +
           "}",
       false, "cannot be instantiated"},
  };
  unsigned eventCasesPassed = 0;
  for (unsigned mode = 0; mode < 3; ++mode) {
    for (const auto &test : eventCases) {
      diagnostics.clear();
      auto candidate =
          mlir::parseSourceString<mlir::ModuleOp>(test.source, mlir::ParserConfig(&context, false));
      if (!candidate)
        return fail("event domain fixture did not parse: " + test.name.str() + ": " + diagnostics);
      std::string before = print(*candidate);
      bool admitted;
      if (mode == 2) {
        // Exercise the standalone trust boundary without an earlier op verifier
        // supplying its proof. The domain pass itself must validate real events.
        mlir::PassManager manager(&context);
        manager.enableVerifier(false);
        if (mlir::failed(mlir::parsePassPipeline("nodal-verify-domains", manager)))
          return fail("standalone domain pass was not registered");
        admitted = mlir::succeeded(manager.run(*candidate));
      } else {
        admitted = mlir::succeeded(nodal::runNodalPipelineTransaction(
            *candidate, mode == 0 ? nodal::GateProfile::Default : nodal::GateProfile::Fast));
      }
      llvm::StringRef expectedDiagnostic = mode == 2 && !test.standaloneDiagnostic.empty()
                                               ? test.standaloneDiagnostic
                                               : test.diagnostic;
      if (admitted != test.expected || (!admitted && print(*candidate) != before) ||
          (!expectedDiagnostic.empty() &&
           !llvm::StringRef(diagnostics).contains(expectedDiagnostic)))
        return fail("event domainless boundary case " + test.name.str() + " mode " +
                    std::to_string(mode) + " failed: " + diagnostics);
      if (test.name == "forged-trigger" &&
          !llvm::StringRef(diagnostics).contains("EventFixture.scala"))
        return fail("event domain rejection lost its authored source location");
      ++eventCasesPassed;
    }
  }
  llvm::outs() << "PASS event semantic/domain gate cases " << eventCasesPassed << "/48\n";
  return 0;
}
