#include "nodal/Dialect/Nodal/WaiverModel.h"

#include "WaiverTestFixtures.h"
#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/raw_ostream.h"
using namespace mlir;
using namespace waiver_test;
int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream stream(diagnostics);
    d.print(stream);
    stream << '\n';
    return success();
  });
  unsigned count = 0, failures = 0;
  auto report = [&](StringRef name, bool ok) {
    ++count;
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << "\n" << diagnostics;
    }
  };
  auto check = [&](StringRef name, std::string body, bool expected, StringRef message = "") {
    diagnostics.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(wrap(body), ParserConfig(&context, false));
    bool ok = parsed && succeeded(verify(*parsed)) == expected;
    report(name, ok && (message.empty() || StringRef(diagnostics).contains(message)));
    return parsed;
  };
  const std::string base = setup() + waiver();
  check("resetless-scalar-identity", base + sample(), true);
  for (std::string relation :
       {"kind = \"same\"", "kind = \"asynchronous\"", "kind = \"mutually_exclusive\"",
        "kind = \"unknown\"", "kind = \"synchronous\", phase_known = false",
        "kind = \"ratio\", multiply = 3 : i32, divide = 2 : i32, phase = \"0.0\""})
    check("all-six-typed-intents", setup() + waiver("waived", "input", "a", "b", bit, relation),
          true);
  for (std::string type : {"!nodal.uint<8>", "!nodal.shaped<\"3\", !nodal.uint<8>>",
                           "!nodal.struct<\"Payload\", [{name = \"valid\", type = !nodal.bits<1>}, "
                           "{name = \"data\", type = !nodal.uint<7>}]>",
                           "!nodal.bits<4096>"})
    check("fixed-whole-abi",
          setup(type) + waiver("waived", "input", "a", "b", type) + output("waived", type), true);
  check("one-result-reused", base + expression("joined", "waived", "waived") + sample("joined"),
        true);
  check("nested-waivers", base + waiver("next", "waived", "b", "c") + sample("next", "c"), true);
  check("exact-same-domain-exception",
        setup() + waiver("waived", "input", "a", "a") + sample("waived", "a"), true);
  check("does-not-authorize-other-values", base + sample("input"), false, "NODAL-CDC-001");
  check("wrong-source", replace(base, "source_domain = @a", "source_domain = @c"), false,
        "NODAL-CDC-001");
  check("wrong-destination-not-global", base + sample("waived", "c"), false, "NODAL-CDC-001");
  check("missing-binding",
        replace(base, "domain = @b, reset_polarity", "domain = @c, reset_polarity"), false);
  check("unresolved-reference", replace(base, "source_domain = @a", "source_domain = @missing"),
        false);
  check("mixed-source",
        setup() + port("other", "b") + expression("mixed", "input", "other") +
            waiver("waived", "mixed"),
        false, "NODAL-CDC-001");
  check(
      "unannotated-source",
      replace(base, "domain = @a, type = !nodal.bits<1>", "domain = @none, type = !nodal.bits<1>"),
      false, "unannotated");
  check("constant-only",
        setup() + "%zero = \"nodal.constant\"() <{value = 0 : i1, metadata = {}}> : () -> " + bit +
            "\n" + waiver("waived", "zero"),
        false, "attributed");
  check("duplicate-id", base + replace(waiver("other"), "id = \"other\"", "id = \"waived\""), false,
        "unique IDs");
  check("duplicate-path", base + replace(waiver("other"), "Fixture.other", "Fixture.waived"), false,
        "unique IDs");
  check("empty-id", replace(base, "id = \"waived\"", "id = \"\""), false, "nonblank");
  check("blank-reason", replace(base, "explicit external boundary responsibility", " \\09\\0A"),
        false, "nonblank");
  check("unicode-blank-id", replace(base, "id = \"waived\"", "id = \"\\E3\\80\\80\""), false,
        "nonblank");
  check("invalid-utf8-id", replace(base, "id = \"waived\"", "id = \"\\FF\""), false, "UTF-8");
  check("id-byte-boundary",
        replace(base, "id = \"waived\"", "id = \"" + std::string(256, 'x') + "\""), true);
  check("id-overflow", replace(base, "id = \"waived\"", "id = \"" + std::string(257, 'x') + "\""),
        false, "256 bytes");
  check("reason-byte-boundary",
        replace(base, "explicit external boundary responsibility", std::string(4096, 'x')), true);
  check("reason-overflow",
        replace(base, "explicit external boundary responsibility", std::string(4097, 'x')), false,
        "4096 bytes");
  check("missing-id", replace(base, "id = \"waived\", ", ""), false);
  check("missing-reason",
        replace(base, "reason = \"explicit external boundary responsibility\", ", ""), false);
  check("descriptor-only", replace(base, "contract = \"stateless_waiver_v1\", ", ""), false);
  check("wrong-contract", replace(base, "stateless_waiver_v1", "level_v1"), false,
        "closed executable");
  check("metadata-forgery",
        replace(base, "semantic_path = \"Fixture.waived\"",
                "semantic_path = \"Fixture.waived\", verified = true"),
        false, "descriptive metadata");
  check("extra-field",
        replace(base, "Fixture.waived\"}}> : (", "Fixture.waived\"}}> {safe = true} : ("), false,
        "closed executable");
  check("bad-obligation", replace(base, "not_exempt", "safe"), false, "obligations");
  check("malformed-relation", replace(base, "kind = \"unknown\"", "kind = \"ratio\""), false);
  check("relation-extra",
        replace(base, "kind = \"unknown\"", "kind = \"unknown\", phase_known = true"), false);
  check("type-substitution", replace(base, ") -> !nodal.bits<1> loc", ") -> !nodal.uint<1> loc"),
        false, "exact fixed whole");
  check("clock-capability-cast", setup() + waiver("waived", "aClock", "a", "b", clockType), false,
        "Clock/Reset");
  for (std::string type :
       {"!nodal.capability<\"reset\">", "i64", "f64", "!nodal.shaped<\"2\", f64>"})
    check("non-bit-pattern-payload", setup(type) + waiver("waived", "input", "a", "b", type), false,
          type.find("capability") != std::string::npos ? "Clock/Reset" : "bit-pattern ABI");
  std::string unicodeId;
  for (unsigned i = 0; i < 128; ++i)
    unicodeId += "\\C3\\A9";
  check("unicode-byte-boundary", replace(base, "id = \"waived\"", "id = \"" + unicodeId + "\""),
        true);
  check("unicode-byte-overflow",
        replace(base, "id = \"waived\"", "id = \"" + unicodeId + "\\C3\\A9\""), false, "256 bytes");
  check("oversized-payload",
        setup("!nodal.bits<4097>") + waiver("waived", "input", "a", "b", "!nodal.bits<4097>"),
        false, "4096 bits");
  const std::string wire = "%wire = \"nodal.digital_storage\"() <{name = \"wire\", kind = "
                           "\"wire\", metadata = {}}> : () -> " +
                           bit + "\n";
  check("causal-cycle-retained",
        setup() + wire + expression("feedback", "wire", "input") + waiver("waived", "feedback") +
            assign("wire", "waived"),
        false, "cycle");
  const std::string chains =
      setup() + crossing("left", "input", "a", "b") + crossing("right", "input", "a", "b");
  check("independent-chain-reconvergence",
        chains + waiver("first", "left", "b", "c") + waiver("second", "right", "b", "c") +
            expression("joined", "first", "second") + sample("joined", "c"),
        false, "NODAL-CDC-057-002");
  check("pre-waiver-reconvergence",
        chains + expression("joined", "left", "right") + waiver("waived", "joined", "b", "c"),
        false, "NODAL-CDC-057-002");
  check("same-chain-two-aliases",
        setup() + crossing("left", "input", "a", "b") + waiver("first", "left", "b", "c") +
            waiver("second", "left", "b", "c") + expression("joined", "first", "second") +
            sample("joined", "c"),
        true);
  const std::string nestedChain = setup() + crossing("first", "input", "a", "b") +
                                  waiver("alias", "first", "b", "c") +
                                  crossing("second", "alias", "c", "b");
  check("transitive-original-source-reconvergence",
        nestedChain + crossing("other", "input", "a", "b") +
            expression("joined", "second", "other") + sample("joined"),
        false, "NODAL-CDC-057-002");
  check("transitive-reused-chain",
        nestedChain + expression("joined", "second", "second") + sample("joined"), true);
  check("transitive-unrelated-original-source",
        nestedChain + port("unrelated", "a") + crossing("other", "unrelated", "a", "b") +
            expression("joined", "second", "other") + sample("joined"),
        true);
  const std::string pulse = pulseSetup() + crossing("pulse", "input", "a", "b", "pulse") +
                            waiver("waived", "pulse", "b", "c");
  check("pulse-stays-pulse-data", pulse + sample("waived", "c"), true);
  check("waiver-does-not-exempt-reset-release",
        replace(pulseSetup() + waiver(), "release_domain = @a", "release_domain = @b"), false);
  for (std::string kind : {"sync", "gray", "handshake"})
    check("reject-pulse-relabel-" + kind, pulse + crossing("launder", "waived", "c", "a", kind),
          false, "NODAL-CDC-003");
  check("nested-pulse-relabel",
        pulse + waiver("nested", "waived", "c", "a") + crossing("launder", "nested", "a", "b"),
        false, "NODAL-CDC-003");
  const std::string fifo =
      "%fifo:3 = \"nodal.async_fifo\"(%waived, %waived, %input) <{source_domain = @c, "
      "destination_domain = @a, kind = \"fifo\", contract = \"fifo_v1\", depth = 4 : i64, "
      "obligations = {transfer = \"valid_and_ready\", stall = \"hold_payload_and_valid\", "
      "reset_epoch = \"common_assertion\", pointer_skew = \"less_than_launch_period\", "
      "bundled_data = \"settled_before_read_visibility\"}, metadata = {semantic_path = "
      "\"Fixture.fifo\"}}> : (" +
      bit + ", " + bit + ", " + bit + ") -> (" + bit + ", " + bit + ", " + bit + ")\n";
  check("reject-pulse-relabel-fifo", pulse + fifo, false, "NODAL-CDC-003");
  auto parsed = check("graph-witness", base + output(), true);
  if (parsed) {
    auto owner = *parsed->getOps<nodal::ModuleOp>().begin();
    auto operation = *owner.getOps<nodal::WaiverOp>().begin();
    auto graph = nodal::analyzeDigitalAssignmentDependencies(
        owner, {{operation.getResult(), &owner.getBody().front().back()}});
    report("graph-is-stateless-owned-dependency",
           succeeded(graph) && graph->crossings.empty() && graph->waivers.size() == 1 &&
               graph->nodes[graph->roots[0]].waiver == operation &&
               graph->nodes[graph->roots[0]].dependencies.size() == 1);
    auto before = operation->getAttrDictionary();
    report("header-read-only",
           succeeded(nodal::inspectExecutableWaiver(operation, nodal::DomainModel(owner))) &&
               operation->getAttrDictionary() == before);
  }
  llvm::outs() << "WAIVER_MODEL_CASES " << count << " FAILURES " << failures << '\n';
  return failures ? 1 : 0;
}
