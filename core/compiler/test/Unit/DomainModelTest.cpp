#include "nodal/Dialect/Nodal/DomainModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/raw_ostream.h"

#include <functional>
#include <string>

using namespace mlir;
namespace {
std::string domain(StringRef name = "core", StringRef policy = "none") {
  return "\"nodal.domain\"() <{sym_name = \"" + name.str() +
         "\", edge = \"rising\", reset_policy = \"" + policy.str() +
         "\", metadata = {}}> : () -> ()\n";
}
std::string requirement(StringRef name = "required") {
  return "\"nodal.domain_requirement\"() <{sym_name = \"" + name.str() +
         "\", metadata = {}}> : () -> ()\n";
}
std::string bind(StringRef actual = "core", StringRef name = "required") {
  return "\"nodal.domain_bind\"() <{requirement = @" + name.str() + ", actual = @" + actual.str() +
         ", metadata = {}}> : () -> ()\n";
}
const std::string physical =
    "\"nodal.port\"() <{sym_name = \"clock\", type = !nodal.capability<\"clock\">, "
    "direction = \"input\", domain = @core, metadata = {}}> : () -> ()\n"
    "%clock = \"nodal.port_value\"() <{port = @clock, metadata = {}}> : () -> "
    "!nodal.capability<\"clock\">\n"
    "\"nodal.local_domain_binding\"(%clock) <{domain = @core, reset_polarity = "
    "\"active_high\", metadata = {}}> : (!nodal.capability<\"clock\">) -> ()\n";
std::string wrap(StringRef body) {
  return "module { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({\n" + body.str() +
         "}) : () -> () }";
}
std::string replace(std::string text, StringRef from, StringRef to) {
  auto at = text.find(from.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing DomainModel test replacement");
  text.replace(at, from.size(), to.str());
  return text;
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
    DialectRegistry registry;
    registry.insert<nodal::NodalDialect>();
    context.appendDialectRegistry(registry);
  }
  Attribute ref(StringRef name) { return FlatSymbolRefAttr::get(&context, name); }
  void report(StringRef name, bool ok) {
    ++cases;
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostic;
    }
  }
  void check(StringRef name, StringRef body,
             std::function<bool(Operation *, const nodal::DomainModel &)> query,
             StringRef expectedDiagnostic = {}) {
    diagnostic.clear();
    auto owner = parseSourceString<mlir::ModuleOp>(wrap(body), ParserConfig(&context, false));
    if (!owner) {
      report(name, false);
      return;
    }
    Operation *module = &owner->getBody()->front();
    const std::string before = print(module);
    const nodal::DomainModel model(module);
    bool ok = query(module, model);
    ok &= print(module) == before;
    ok &= expectedDiagnostic.empty() ? diagnostic.empty()
                                     : StringRef(diagnostic).contains(expectedDiagnostic);
    report(name, ok);
  }
};
} // namespace

int main() {
  Harness test;
  auto core = test.ref("core"), req = test.ref("required");
  auto resolved = [&](Operation *, const nodal::DomainModel &model) {
    auto actual = model.resolveDomain(core), required = model.resolveDomain(req);
    auto binding = model.resolveBinding(req);
    return succeeded(actual) && succeeded(required) && *actual == *required && succeeded(binding) &&
           *binding == model.findLocalBinding(core) && model.matchesActualDomain(req, core) &&
           model.matchesActualDomain(core, core) && !model.matchesActualDomain(req, req) &&
           !model.matchesActualDomain(core, req) &&
           !model.matchesActualDomain(req, test.ref("foreign"));
  };
  const auto setup = domain() + physical;
  test.check("actual-and-unique-required-share-one-physical-binding",
             setup + requirement() + bind(), resolved);
  test.check("operation-use-resolves-enclosing-module", setup + requirement() + bind(),
             [&](Operation *module, const nodal::DomainModel &) {
               Operation *use = &module->getRegion(0).front().back();
               const nodal::DomainModel model(use);
               return resolved(use, model);
             });
  test.check("descriptor-domain-does-not-invent-physical-capability", domain(),
             [&](Operation *, const nodal::DomainModel &model) {
               return succeeded(model.resolveDomain(core)) && failed(model.resolveBinding(core)) &&
                      !model.findLocalBinding(core);
             });
  auto unresolved = [&](Operation *, const nodal::DomainModel &model) {
    return failed(model.resolveDomain(req)) && failed(model.resolveBinding(req)) &&
           !model.matchesActualDomain(req, core);
  };
  test.check("missing-requirement-binding", setup + requirement(), unresolved);
  test.check("duplicate-identical-requirement-binding", setup + requirement() + bind() + bind(),
             unresolved);
  test.check("duplicate-conflicting-requirement-binding",
             setup + domain("foreign") + requirement() + bind() + bind("foreign"), unresolved);
  test.check("requirement-target-must-be-local-domain", setup + requirement() + bind("clock"),
             unresolved);
  test.check("requirement-cannot-target-missing-domain", setup + requirement() + bind("missing"),
             unresolved);
  test.check("requirement-chains-remain-unsupported",
             setup + requirement() + requirement("other") + bind("other") + bind("core", "other"),
             unresolved);
  test.check("requirement-cycle-remains-unresolved", setup + requirement() + bind("required"),
             unresolved);
  test.check(
      "requirement-bind-cannot-carry-operands",
      setup + requirement() +
          replace(replace(bind(), "\"nodal.domain_bind\"()", "\"nodal.domain_bind\"(%clock)"),
                  ": () -> ()", ": (!nodal.capability<\"clock\">) -> ()"),
      unresolved);
  test.check("requirement-bind-cannot-carry-region",
             setup + requirement() + replace(bind(), ": () -> ()", "({}) : () -> ()"), unresolved);
  test.check("nested-requirement-bind-is-not-a-local-bind",
             setup + requirement() + "\"nodal.generate\"() ({" + bind() + "}) : () -> ()\n",
             unresolved);
  test.check("requirement-target-cannot-resolve-in-outer-module",
             setup + "\"nodal.module\"() <{sym_name = \"Nested\", metadata = {}}> ({" +
                 requirement() + bind() + "}) : () -> ()\n",
             [&](Operation *module, const nodal::DomainModel &) {
               const nodal::DomainModel nested(&module->getRegion(0).front().back());
               return failed(nested.resolveDomain(req)) && failed(nested.resolveBinding(req));
             });
  test.check("unknown-and-non-symbol-references-fail-closed", setup,
             [&](Operation *, const nodal::DomainModel &model) {
               for (Attribute ref :
                    {Attribute(), test.ref("missing"),
                     Attribute(StringAttr::get(&test.context, "core")), test.ref("clock")})
                 if (succeeded(model.resolveDomain(ref)) || succeeded(model.resolveBinding(ref)) ||
                     model.matchesActualDomain(ref, core))
                   return false;
               return true;
             });
  auto invalidPhysical = [&](Operation *, const nodal::DomainModel &model) {
    return succeeded(model.resolveDomain(core)) && failed(model.resolveBinding(core));
  };
  std::string secondBinding = physical.substr(physical.find("\"nodal.local_domain_binding\""));
  test.check("duplicate-physical-bindings-are-not-a-proof", setup + secondBinding, invalidPhysical);
  test.check("first-invalid-physical-binding-keeps-diagnostic-before-duplicate",
             replace(setup, "direction = \"input\"", "direction = \"output\"") + secondBinding,
             invalidPhysical,
             "NODAL-SEQUENTIAL-056-001: clock/reset producer must match an actual local input port "
             "and domain");
  test.check("foreign-physical-domain-remains-rejected",
             domain("foreign") +
                 replace(setup, "domain = @core, metadata", "domain = @foreign, metadata"),
             invalidPhysical,
             "NODAL-SEQUENTIAL-056-001: clock/reset producer must match an actual local input port "
             "and domain");
  test.check("typed-but-unknown-clock-producer-remains-rejected",
             replace(setup, "\"nodal.port_value\"() <{port = @clock, metadata = {}}>",
                     "\"builtin.unrealized_conversion_cast\"()"),
             invalidPhysical,
             "NODAL-SEQUENTIAL-056-001: local binding requires actual owned typed input boundary "
             "signals");
  test.check("explicit-edge-remains-required", replace(setup, "\"rising\"", "\"both\""),
             invalidPhysical,
             "NODAL-SEQUENTIAL-056-001: executable local domain requires explicit rising/falling "
             "edge");
  test.check(
      "synchronized-reset-policy-requires-actual-reset",
      replace(setup, "reset_policy = \"none\"", "reset_policy = \"async_assert_sync_release\""),
      invalidPhysical,
      "NODAL-SEQUENTIAL-056-001: physical reset presence and explicit polarity must match domain "
      "policy");
  test.check("physical-reset-presence-remains-required", domain("core", "sync") + physical,
             invalidPhysical,
             "NODAL-SEQUENTIAL-056-001: physical reset presence and explicit polarity must match "
             "domain policy");
  test.check("physical-reset-polarity-remains-explicit",
             replace(setup, "\"active_high\"", "\"unknown\""), invalidPhysical,
             "NODAL-SEQUENTIAL-056-001: physical reset presence and explicit polarity must match "
             "domain policy");
  test.check("fresh-view-observes-mutated-binding", setup + requirement() + bind(),
             [&](Operation *module, const nodal::DomainModel &) {
               Operation *binding = &module->getRegion(0).front().back();
               auto before = binding->getAttr("actual");
               binding->setAttr("actual", test.ref("missing"));
               bool ok = failed(nodal::DomainModel(module).resolveDomain(req));
               binding->setAttr("actual", before);
               return ok && succeeded(nodal::DomainModel(module).resolveDomain(req));
             });
  const nodal::DomainModel detached(nullptr);
  test.report("null-owner-fails-closed",
              failed(detached.resolveDomain(core)) && failed(detached.resolveBinding(core)) &&
                  !detached.matchesActualDomain(core, core) && !detached.findLocalBinding(core));
  llvm::outs() << "domain model: " << test.cases << " cases, " << test.failures << " failures\n";
  return test.failures ? 1 : 0;
}
