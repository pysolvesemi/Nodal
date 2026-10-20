#include "nodal/Dialect/Nodal/CrossingModel.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/FlatFSMModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/raw_ostream.h"

#include <chrono>
#include <string>
using namespace mlir;
namespace {
const std::string bit = "!nodal.bits<1>";
std::string wrap(const std::string &body) {
  return "module { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({\n" + body +
         "}) : () -> () }";
}
std::string print(Operation *op) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  op->print(stream);
  return result;
}
std::string replace(std::string value, StringRef before, StringRef after) {
  auto at = value.find(before.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing crossing fixture replacement");
  return value.replace(at, before.size(), after.str());
}
std::string port(std::string name, std::string domain, std::string type = bit) {
  return "\"nodal.port\"() <{sym_name = \"" + name + "\", direction = \"input\", domain = @" +
         domain + ", type = " + type + ", metadata = {}}> : () -> ()\n%" + name +
         " = \"nodal.port_value\"() <{port = @" + name + ", metadata = {}}> : () -> " + type + "\n";
}
std::string domain(std::string name, std::string policy = "none") {
  std::string result = "\"nodal.domain\"() <{sym_name = \"" + name +
                       "\", edge = \"rising\", reset_policy = \"" + policy +
                       "\", metadata = {}}> : () -> ()\n" +
                       port(name + "Clock", name, "!nodal.capability<\"clock\">");
  if (policy != "none")
    result += port(name + "Reset", name, "!nodal.capability<\"reset\">");
  return result + "\"nodal.local_domain_binding\"(%" + name + "Clock" +
         (policy == "none" ? "" : ", %" + name + "Reset") + ") <{domain = @" + name +
         ", reset_polarity = \"active_high\", metadata = {}}> : (!nodal.capability<\"clock\">" +
         (policy == "none" ? "" : ", !nodal.capability<\"reset\">") + ") -> ()\n";
}
std::string setup() { return domain("a") + domain("b") + port("input", "a"); }
std::string crossing(std::string name = "sync", std::string input = "input", std::string from = "a",
                     std::string to = "b", unsigned stages = 2) {
  return "%" + name + " = \"nodal.crossing\"(%" + input + ") <{source_domain = @" + from +
         ", destination_domain = @" + to +
         ", kind = \"sync\", contract = \"level_v1\", stages = " + std::to_string(stages) +
         " : i64, metadata = {semantic_path = \"Fixture." + name + "\"}}> : (" + bit + ") -> " +
         bit + "\n";
}
std::string grayCrossing(std::string type = "!nodal.uint<8>", unsigned stages = 2,
                         std::string name = "gray", std::string input = "input") {
  return "%" + name + " = \"nodal.crossing\"(%" + input +
         ") <{source_domain = @a, destination_domain = @b, kind = \"gray\", "
         "contract = \"gray_v1\", stages = " +
         std::to_string(stages) +
         " : i64, obligations = {transition = \"one_bit_per_source_sample\", "
         "skew = \"less_than_source_period\", stability = \"stable_between_source_samples\"}, "
         "metadata = {semantic_path = \"Fixture." +
         name + "\"}}> : (" + type + ") -> " + type + "\n";
}
std::string pulseCrossing(std::string name = "pulse", std::string input = "input") {
  return "%" + name + " = \"nodal.crossing\"(%" + input +
         ") <{source_domain = @a, destination_domain = @b, kind = \"pulse\", "
         "contract = \"pulse_v1\", stages = 2 : i64, obligations = "
         "{spacing = \"returned_acknowledgement\", width = \"single_source_cycle\", "
         "reset_epoch = \"common_assertion\"}, metadata = {semantic_path = \"Fixture." +
         name + "\"}}> : (" + bit + ") -> " + bit + "\n";
}
std::string handshakeCrossing(std::string type = "!nodal.uint<8>", std::string name = "handshake",
                              std::string input = "input") {
  return "%" + name + " = \"nodal.crossing\"(%" + input +
         ") <{source_domain = @a, destination_domain = @b, kind = \"handshake\", "
         "contract = \"handshake_snapshot_v1\", stages = 2 : i64, obligations = "
         "{delivery = \"latest_snapshot_coalescing\", "
         "bundled_data = \"settled_before_request_capture\", "
         "reset_epoch = \"common_assertion\"}, metadata = {semantic_path = \"Fixture." +
         name + "\"}}> : (" + type + ") -> " + type + "\n";
}
std::string noZeroEnum() {
  return "\"nodal.enum\"() <{sym_name = \"NoZero\", underlying_type = !nodal.uint<2>, "
         "encoding = \"custom\", metadata = {abi_version = 1 : i64, semantic_type = "
         "\"example.NoZero\", abi_hash = "
         "\"8677e4c94965ad67d39908aa748086bab092b050966a4e4b92b15d4149e96250\"}}> ({\n"
         "\"nodal.enum_case\"() <{sym_name = \"One\", value = 1 : i64, metadata = "
         "{case_id = \"example.NoZero.One\"}}> : () -> ()\n"
         "\"nodal.enum_case\"() <{sym_name = \"Two\", value = 2 : i64, metadata = "
         "{case_id = \"example.NoZero.Two\"}}> : () -> ()\n}) : () -> ()\n";
}
std::string pulseSetup(bool common = true, bool shaped = false) {
  const std::string reset = "!nodal.capability<\"reset\">", clock = "!nodal.capability<\"clock\">";
  std::string result;
  for (const std::string name : {"a", "b"})
    result += "\"nodal.domain\"() <{sym_name = \"" + name +
              "\", edge = \"rising\", reset_policy = \"async\", metadata = {}}> : () -> ()\n" +
              port(name + "Clock", name, clock);
  if (shaped) {
    result += port("roots", "a", "!nodal.shaped<\"2\", " + reset + ">") +
              "%i0 = \"nodal.constant\"() <{value = 0 : index, metadata = {}}> : () -> index\n"
              "%i1 = \"nodal.constant\"() <{value = 1 : index, metadata = {}}> : () -> index\n";
    for (int i = 0; i != 2; ++i)
      result += "%root" + std::to_string(i) + " = \"nodal.shape_index\"(%roots, %i" +
                std::to_string(i) + ") <{metadata = {}}> : (!nodal.shaped<\"2\", " + reset +
                ">, index) -> " + reset + "\n";
  } else
    result += port("root0", "a", reset) + port("root1", "b", reset);
  for (const std::string name : {"a", "b"}) {
    result += "%" + name + "Reset = \"nodal.reset_boundary\"(%root" +
              (common || name == "a" ? "0" : "1") +
              ") <{reset_polarity = \"active_high\", release = \"external\", release_domain = @" +
              name + ", metadata = {}}> : (" + reset + ") -> " + reset + "\n";
    result += "\"nodal.local_domain_binding\"(%" + name + "Clock, %" + name +
              "Reset) <{domain = @" + name +
              ", reset_polarity = \"active_high\", metadata = {}}> : (" + clock + ", " + reset +
              ") -> ()\n";
  }
  return result + port("input", "a");
}
std::string expression(std::string name, std::string left, std::string right) {
  return "%" + name + " = \"nodal.digital_expr\"(%" + left + ", %" + right +
         ") <{operator_name = \"bool_and\", metadata = {}}> : (" + bit + ", " + bit + ") -> " +
         bit + "\n";
}
std::string sample(std::string input = "sync", std::string owner = "b") {
  return "%state = \"nodal.digital_storage\"() <{name = \"state\", kind = \"register\", domain = "
         "@" +
         owner + ", metadata = {semantic_path = \"Fixture.state\"}}> : () -> " + bit +
         "\n\"nodal.digital_assign\"(%state, %" + input +
         ") <{kind = \"register_next\", metadata = {}}> : (" + bit + ", " + bit + ") -> ()\n";
}
std::string wire(std::string input = "input") {
  return "%wire = \"nodal.digital_storage\"() <{name = \"wire\", kind = \"wire\", metadata = "
         "{semantic_path = \"Fixture.wire\"}}> : () -> " +
         bit + "\n\"nodal.digital_assign\"(%wire, %" + input +
         ") <{kind = \"continuous\", metadata = {}}> : (" + bit + ", " + bit + ") -> ()\n";
}
std::string interfaceInput(std::string type, std::string sourceDomain, bool resolved = false) {
  const std::string tag = "interface_abi_version = 1 : i64";
  const std::string access = resolved ? "connect" : "in";
  const std::string memberType = resolved ? "!nodal.resolved<\"push_pull\", " + type + ">" : type;
  const std::string leaf = "{path = \"data\", access = \"" + access +
                           "\", leaf_role = \"sink\", nested_roles = [], nested_interfaces = []}";
  std::string result =
      "\"nodal.interface\"() <{sym_name = \"Bus\", metadata = {" + tag +
      ", semantic_identity = \"pkg.Bus\"}}> ({\n"
      "\"nodal.interface_role\"() <{sym_name = \"sink\", kind = \"explicit\", metadata = {" +
      tag + ", access = [" + leaf +
      "]}}> : () -> ()\n"
      "\"nodal.interface_member\"() <{sym_name = \"data\", type = " +
      memberType + ", protocol = \"" + (resolved ? "resolved" : "plain") +
      "\", roles = [\"sink\"], metadata = {" + tag +
      "}}> : () -> ()\n}) : () -> ()\n"
      "\"nodal.interface_instance\"() <{sym_name = \"bus\", definition = @Bus, role = \"sink\", "
      "metadata = {" +
      tag + ", domain = @" + sourceDomain +
      ", dimensions = [], semantic_path = \"Fixture.bus\"}}> : () -> ()\n"
      "\"nodal.interface_abi\"() <{logical_path = \"Fixture.bus\", layout_policy = \"structural\", "
      "members = [{path = \"data\", type = " +
      memberType + ", role = \"sink\", access = \"" + access +
      "\", nested_roles = [], nested_interfaces = []}], metadata = {" + tag +
      ", instance = @bus}}> : () -> ()\n";
  // Put the standalone net first so it is the component representative. Its
  // absent own domain must not erase the actual Interface component constraint.
  if (resolved)
    result += "%net = \"nodal.resolved_net\"() <{name = \"net\", metadata = {" + tag +
              ", placement = \"top_level_pin\", profile = \"portable_boundary_only\"}}> : () -> " +
              memberType + "\n";
  result += std::string(resolved ? "%member" : "%ifaceInput") +
            " = \"nodal.member_access\"() <{instance = @bus, path = \"data\", metadata = {" + tag +
            "}}> : () -> " + memberType + "\n";
  if (resolved)
    result += "\"nodal.net_pass_through\"(%net, %member) <{metadata = {" + tag + "}}> : (" +
              memberType + ", " + memberType +
              ") -> ()\n"
              "%ifaceInput = \"nodal.net_read\"(%net) <{metadata = {" +
              tag + "}}> : (" + memberType + ") -> " + type + "\n";
  return result;
}
std::string fsmAction(std::string phase, std::string data, bool memory = false,
                      bool priorityPrefix = false) {
  std::string enumeration =
      "\"nodal.enum\"() <{sym_name = \"Mode\", underlying_type = !nodal.uint<3>, "
      "encoding = \"custom\", metadata = {abi_version = 1 : i64, semantic_type = "
      "\"example.Mode\", abi_hash = "
      "\"cb807326542df7db5314f8d795ea4384c2b40209497084c69a41f9ccd9faf46b\"}}> ({\n";
  const char *names[] = {"Idle", "Run", "Stop"};
  int values[] = {0, 3, 7};
  for (unsigned i = 0; i < 3; ++i)
    enumeration += "\"nodal.enum_case\"() <{sym_name = \"" + std::string(names[i]) +
                   "\", value = " + std::to_string(values[i]) +
                   " : i64, metadata = {case_id = \"example.Mode." + names[i] +
                   "\"}}> : () -> ()\n";
  enumeration += "}) : () -> ()\n";
  std::string body =
      domain("a") + domain("b", "sync") + port("input", "a") + crossing() + crossing("other") +
      "%zero = \"nodal.const_literal\"() <{value = 0 : ui2, spelling = \"0\", metadata = {}}> : () "
      "-> !nodal.bits<2>\n"
      "%one = \"nodal.const_literal\"() <{value = 1 : ui1, spelling = \"1\", metadata = {}}> : () "
      "-> !nodal.bits<1>\n"
      "%fsmState = \"nodal.digital_storage\"(%zero) <{name = \"fsmState\", kind = \"register\", "
      "domain = @b, metadata = {semantic_path = \"Fixture.fsmState\"}}> : (!nodal.bits<2>) -> "
      "!nodal.bits<2>\n"
      "%target = \"nodal.digital_storage\"() <{name = \"target\", kind = \"register\", domain = "
      "@b, metadata = {semantic_path = \"Fixture.target\"}}> : () -> !nodal.bits<1>\n";
  if (memory)
    body += port("addr", "b", "!nodal.uint<2>") +
            "\"nodal.memory\"() <{sym_name = \"destRam\", element_type = !nodal.bits<1>, depth = "
            "\"4\", read_latency = 1 : i64, read_under_write = \"old_data\", ordering = "
            "\"ordered\", domain = @b, metadata = {}}> : () -> ()\n";
  std::string actionOwner =
      phase == "transition"
          ? "transition_ordinal = " + std::string(priorityPrefix ? "1" : "0") + " : i64"
          : "owner_case = \"example.Mode." + std::string(phase == "exit" ? "Idle" : "Run") + "\"";
  std::string captures = memory ? "%addr, %" + data + ", %one" : "%target, %" + data;
  std::string types =
      memory ? "!nodal.uint<2>, !nodal.bits<1>, !nodal.bits<1>" : "!nodal.bits<1>, !nodal.bits<1>";
  std::string arguments =
      memory ? "%address: !nodal.uint<2>, %value: !nodal.bits<1>, %mask: !nodal.bits<1>"
             : "%out: !nodal.bits<1>, %value: !nodal.bits<1>";
  std::string effect =
      memory ? "\"nodal.memory_write\"(%address, %value, %mask) <{memory = @destRam, domain = @b, "
               "metadata = {semantic_path = \"Fixture.machine.write\"}}> : (!nodal.uint<2>, "
               "!nodal.bits<1>, !nodal.bits<1>) -> ()"
             : "\"nodal.digital_assign\"(%out, %value) <{kind = \"register_next\", metadata = {}}> "
               ": (!nodal.bits<1>, !nodal.bits<1>) -> ()";
  body +=
      "%next, %taken = \"nodal.flat_fsm\"(%fsmState, %sync, %one" +
      std::string(priorityPrefix ? ", %one" : "") + ", " + captures +
      ") <{contract_version = \"flat-fsm-v1\", machine_id = \"Fixture.machine\", enum_ref = "
      "@Mode, domain = @b, initial_case = \"example.Mode.Idle\", requested_encoding = "
      "\"compact\", resolved_encoding = \"compact\", local_width = 2 : i64, case_ids = "
      "[\"example.Mode.Idle\", \"example.Mode.Run\", \"example.Mode.Stop\"], local_codes = [0 : "
      "ui2, 1 : ui2, 2 : ui2], illegal_policy = \"error\", states = [{case_id = "
      "\"example.Mode.Idle\", terminal = false, ordinal = 0 : i64}, {case_id = "
      "\"example.Mode.Run\", terminal = false, ordinal = 1 : i64}, {case_id = "
      "\"example.Mode.Stop\", terminal = true, ordinal = 2 : i64}], transitions = [{source_case "
      "= \"example.Mode.Idle\", destination_case = \"example.Mode.Run\", mode = \"priority\", "
      "ordinal = 0 : i64}, " +
      std::string(priorityPrefix
                      ? "{source_case = \"example.Mode.Idle\", destination_case = "
                        "\"example.Mode.Stop\", mode = \"priority\", ordinal = 1 : i64}, "
                      : "") +
      "{source_case = \"example.Mode.Run\", destination_case = \"example.Mode.Stop\", mode = "
      "\"priority\", ordinal = 2 : i64}], actions = [{region_id = \"Fixture.machine.action\", "
      "phase = \"" +
      phase + "\", " + actionOwner + ", ordinal = 0 : i64}], action_capture_counts = array<i32: " +
      std::string(memory ? "3" : "2") +
      ">, metadata = {semantic_path = \"Fixture.machine\"}}> ({^bb0(%current: !nodal.bits<2>, "
      "%future: !nodal.bits<2>, %fired: !nodal.bits<1>, " +
      arguments + "): " + effect + "}) : (!nodal.bits<2>, !nodal.bits<1>, !nodal.bits<1>" +
      std::string(priorityPrefix ? ", !nodal.bits<1>" : "") + ", " + types +
      ") -> (!nodal.bits<2>, !nodal.bits<1>)\n";
  return "module { " + enumeration + wrap(body).substr(9);
}
} // namespace
int main(int argc, char **argv) {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream stream(diagnostics);
    d.print(stream);
    stream << '\n';
    return success();
  });
  if (argc == 3 && StringRef(argv[1]) == "--verify-file") {
    auto module = parseSourceFile<mlir::ModuleOp>(argv[2], ParserConfig(&context, false));
    bool valid = module && succeeded(verify(*module));
    if (!valid)
      llvm::errs() << diagnostics;
    return valid ? 0 : 1;
  }
  if (argc == 2 && StringRef(argv[1]) == "--emit-fsm-fixture") {
    llvm::outs() << fsmAction("transition", "other");
    return 0;
  }
  unsigned cases = 0, failures = 0;
  auto report = [&](StringRef name, bool ok) {
    ++cases;
    failures += !ok;
    (ok ? llvm::outs() : llvm::errs())
        << "CROSSING_MODEL_" << (ok ? "PASS " : "FAIL ") << name << '\n';
    if (!ok)
      llvm::errs() << diagnostics;
    diagnostics.clear();
  };
  auto parse = [&](const std::string &body) {
    return parseSourceString<mlir::ModuleOp>(StringRef(body).starts_with("module { ") ? body
                                                                                      : wrap(body),
                                             ParserConfig(&context, false));
  };
  auto check = [&](StringRef name, const std::string &body, bool expected, StringRef reason = "") {
    auto root = parse(body);
    std::string before = root ? print(*root) : "";
    bool valid = root && succeeded(verify(*root));
    report(name, root && valid == expected && before == print(*root) &&
                     (reason.empty() || diagnostics.find(reason.str()) != std::string::npos));
    return root;
  };
  std::string base = setup() + crossing();
  if (argc > 1 && StringRef(argv[1]) == "--emit-fixture") {
    llvm::outs() << wrap(base + sample());
    return 0;
  }
  check("two-stage-bool-source-destination", base + sample(), true);
  auto replaceAll = [](std::string value, const std::string &from, const std::string &to) {
    size_t at = 0;
    while ((at = value.find(from, at)) != std::string::npos) {
      value.replace(at, from.size(), to);
      at += to.size();
    }
    return value;
  };
  check("multibit-level-rejected", replaceAll(base, bit, "!nodal.bits<2>"), false);
  check("uint-one-is-not-bool", replaceAll(base, bit, "!nodal.uint<1>"), false);

  check("stage-upper-bound", setup() + crossing("sync", "input", "a", "b", 32) + sample(), true);
  check("sync-reset-destination",
        domain("a") + domain("b", "sync") + port("input", "a") + crossing(), true);
  check("stage-lower-bound", replace(base, "stages = 2", "stages = 1"), false);
  check("stage-above-bound", replace(base, "stages = 2", "stages = 33"), false);
  check("negative-stage", replace(base, "stages = 2", "stages = -1"), false);
  check("stage-wrong-width", replace(base, "stages = 2 : i64", "stages = 2 : i32"), false);
  auto badStage = parse(replace(base, "stages = 2 : i64", "stages = \"2\""));
  report("stage-wrong-type-rejected-at-parser",
         !badStage && diagnostics.find("Invalid attribute `stages`") != std::string::npos);
  check("missing-stage", replace(base, "stages = 2 : i64, ", ""), false);
  check("unknown-contract", replace(base, "level_v1", "level_v2"), false);
  check("missing-contract-with-stage", replace(base, "contract = \"level_v1\", ", ""), false);
  check("pulse-is-not-level", replace(base, "kind = \"sync\"", "kind = \"pulse\""), false);
  check("gray-is-not-level", replace(base, "kind = \"sync\"", "kind = \"gray\""), false);
  check("lost-identity", replace(base, "semantic_path = \"Fixture.sync\"", "origin = \"unknown\""),
        false);
  check("foreign-identity", replace(base, "Fixture.sync", "Other.sync"), false);
  check("unknown-executable-field", setup() + replace(crossing(), "}> : (", "}> {safe = true} : ("),
        false);
  check("metadata-cannot-authorize",
        replace(base, "semantic_path = \"Fixture.sync\"",
                "semantic_path = \"Fixture.sync\", safe = true"),
        false);
  check(
      "canonical-source-map-retained",
      replace(
          base, "semantic_path = \"Fixture.sync\"",
          "semantic_path = \"Fixture.sync\", bridge_schema = \"nodal\", bridge_version = 1 : i64, "
          "source_end_line = 8 : i64, source_end_column = 12 : i64, documentation = \"unchanged\""),
      true);
  check("same-domain", replace(base, "destination_domain = @b", "destination_domain = @a"), false);
  std::string sourceBinding =
      "\"nodal.local_domain_binding\"(%aClock) <{domain = @a, reset_polarity = \"active_high\", "
      "metadata = {}}> : (!nodal.capability<\"clock\">) -> ()\n";
  check("missing-physical-source-binding", replace(base, sourceBinding, ""), false);
  check("duplicate-physical-source-binding",
        replace(base, sourceBinding, sourceBinding + sourceBinding), false);
  check("unresolved-source", replace(base, "source_domain = @a", "source_domain = @missing"),
        false);
  check("spoofed-source",
        replace(base, "source_domain = @a, destination_domain = @b",
                "source_domain = @b, destination_domain = @a"),
        false);
  check("raw-foreign-still-rejected", setup() + sample("input"), false);
  check("crossing-result-is-destination-owned", base + sample("sync", "a"), false);
  check("unannotated-input-rejected",
        replace(base, "domain = @a, type = " + bit, "domain = @none, type = " + bit), false);
  check("mixed-source-rejected",
        setup() + port("foreign", "b") + expression("mixed", "input", "foreign") +
            crossing("sync", "mixed"),
        false);
  check("actual-interface-source",
        setup() + interfaceInput(bit, "a") + crossing("sync", "ifaceInput") + sample(), true);
  check("interface-source-spoof-rejected",
        setup() + interfaceInput(bit, "b") + crossing("sync", "ifaceInput"), false);
  std::string memory = port("address", "a", "!nodal.uint<2>") +
                       "\"nodal.memory\"() <{sym_name = \"ram\", element_type = " + bit +
                       ", depth = \"4\", "
                       "read_latency = 1 : i64, read_under_write = \"old_data\", ordering = "
                       "\"ordered\", domain = @a, metadata = {}}> : () -> ()\n"
                       "%read = \"nodal.memory_read\"(%address) <{memory = @ram, domain = @a, "
                       "metadata = {semantic_path = \"Fixture.read\"}}> : (!nodal.uint<2>) -> " +
                       bit + "\n";
  check("actual-memory-source", setup() + memory + crossing("sync", "read") + sample(), true);
  check("memory-address-crossing-still-rejected",
        setup() +
            replace(memory, "domain = @a, type = !nodal.uint<2>",
                    "domain = @b, type = !nodal.uint<2>") +
            crossing("sync", "read"),
        false);
  std::string capture =
      replace(sample(),
              "\"nodal.digital_assign\"(%state, %sync) <{kind = \"register_next\", metadata = {}}> "
              ": (" +
                  bit + ", " + bit + ") -> ()\n",
              "") +
      port("enabled", "b") +
      "\"nodal.behavioral_if\"(%enabled, %state, %sync) <{contract_version = \"behavioral-if-v1\", "
      "region_id = \"Fixture.choice\", operandSegmentSizes = array<i32: 1, 2, 0>, metadata = "
      "{semantic_path = \"Fixture.choice\"}}> "
      "({^bb0(%s: " +
      bit + ", %v: " + bit +
      "):\n"
      "\"nodal.digital_assign\"(%s, %v) <{kind = \"register_next\", metadata = {}}> : (" +
      bit + ", " + bit + ") -> ()\n}, {^bb1():}) : (" + bit + ", " + bit + ", " + bit + ") -> ()\n";
  check("crossing-capture-retains-destination", base + capture, true);
  std::string independentGuard =
      base + crossing("other") +
      replace(capture, "%enabled, %state, %sync", "%other, %state, %sync");
  check("raw-guard-rhs-reconvergence", independentGuard, false, "NODAL-CDC-057-002");
  std::string reusedGuard = base + crossing("other") +
                            replace(capture, "%enabled, %state, %sync", "%sync, %state, %sync");
  check("raw-guard-rhs-one-instance", reusedGuard, true);
  auto alternatives = [&](std::string right) {
    return replace(sample(),
                   "\"nodal.digital_assign\"(%state, %sync) <{kind = \"register_next\", metadata = "
                   "{}}> : (" +
                       bit + ", " + bit + ") -> ()\n",
                   "") +
           port("select", "b") + "\"nodal.behavioral_if\"(%select, %state, %sync, %state, %" +
           right +
           ") <{contract_version = \"behavioral-if-v1\", region_id = \"Fixture.alternatives\", "
           "operandSegmentSizes = array<i32: 1, 2, 2>, metadata = {semantic_path = "
           "\"Fixture.alternatives\"}}> "
           "({^bb0(%s0: " +
           bit + ", %v0: " + bit +
           "): "
           "\"nodal.digital_assign\"(%s0, %v0) <{kind = \"register_next\", metadata = {}}> : (" +
           bit + ", " + bit +
           ") -> ()}, "
           "{^bb1(%s1: " +
           bit + ", %v1: " + bit +
           "): "
           "\"nodal.digital_assign\"(%s1, %v1) <{kind = \"register_next\", metadata = {}}> : (" +
           bit + ", " + bit + ") -> ()}) : (" + bit + ", " + bit + ", " + bit + ", " + bit + ", " +
           bit + ") -> ()\n";
  };
  check("same-target-alternatives-conservative-rejection",
        base + crossing("other") + alternatives("other"), false, "NODAL-CDC-057-002");
  check("same-target-alternatives-one-instance", base + crossing("other") + alternatives("sync"),
        true);
  std::string destinationMemory =
      port("writeAddress", "b", "!nodal.uint<2>") +
      "\"nodal.memory\"() <{sym_name = \"destRam\", element_type = " + bit +
      ", depth = \"4\", "
      "read_latency = 1 : i64, read_under_write = \"old_data\", ordering = \"ordered\", domain = "
      "@b, metadata = {}}> : () -> ()\n";
  auto memoryWrite = [&](std::string name, std::string data, std::string mask) {
    return "\"nodal.memory_write\"(%writeAddress, %" + data + ", %" + mask +
           ") <{memory = @destRam, domain = @b, metadata = {semantic_path = \"Fixture." + name +
           "\"}}> : (!nodal.uint<2>, " + bit + ", " + bit + ") -> ()\n";
  };
  check("memory-effect-data-mask-reconvergence",
        base + crossing("other") + destinationMemory + memoryWrite("write", "sync", "other"), false,
        "NODAL-CDC-057-002");
  check("memory-effect-data-mask-one-instance",
        base + crossing("other") + destinationMemory + memoryWrite("write", "sync", "sync"), true);
  check("memory-effects-remain-distinct",
        base + crossing("other") + destinationMemory + memoryWrite("firstWrite", "sync", "sync") +
            memoryWrite("secondWrite", "other", "other"),
        true);

  for (StringRef phase : {"transition", "entry", "exit"})
    for (bool memoryEffect : {false, true}) {
      std::string label = ("raw-fsm-" + phase + (memoryEffect ? "-memory" : "-register")).str();
      check(label + "-reconvergence", fsmAction(phase.str(), "other", memoryEffect), false,
            "NODAL-CDC-057-002");
      check(label + "-one-instance", fsmAction(phase.str(), "sync", memoryEffect), true);
    }
  check("raw-fsm-active-unrelated-guard-register", fsmAction("active", "other"), true);
  check("raw-fsm-active-unrelated-guard-memory", fsmAction("active", "other", true), true);
  for (bool reuse : {false, true}) {
    std::string active = fsmAction("active", reuse ? "sync" : "other", true);
    active = replace(active, "(%address, %value, %mask)", "(%address, %value, %fired)");
    check(reuse ? "raw-fsm-active-taken-one-instance" : "raw-fsm-active-taken-reconvergence",
          active, reuse, reuse ? "" : "NODAL-CDC-057-002");
  }
  check("raw-fsm-priority-prefix-controls-action", fsmAction("transition", "other", false, true),
        false, "NODAL-CDC-057-002");
  for (bool memoryEffect : {false, true}) {
    auto expanded = parse(fsmAction("transition", "other", memoryEffect));
    bool rejected = false;
    if (expanded) {
      auto module = *expanded->getOps<nodal::ModuleOp>().begin();
      unsigned generated = 0;
      rejected = succeeded(nodal::expandFlatFSMModel(module, generated, 65536)) &&
                 failed(verify(*expanded)) &&
                 diagnostics.find("NODAL-CDC-057-002") != std::string::npos;
    }
    report(memoryEffect ? "expanded-fsm-memory-reconvergence"
                        : "expanded-fsm-register-reconvergence",
           rejected);
  }
  auto safeFSM = parse(fsmAction("transition", "sync"));
  bool normalizedFSM = safeFSM && succeeded(verify(*safeFSM));
  if (normalizedFSM)
    normalizedFSM =
        succeeded(nodal::normalizeSequentialModel(*safeFSM->getOps<nodal::ModuleOp>().begin())) &&
        succeeded(verify(*safeFSM));
  report("normalized-fsm-one-instance", normalizedFSM);
  auto budgetFSM = parse(fsmAction("transition", "sync"));
  bool boundedFSM = false;
  if (budgetFSM) {
    auto module = *budgetFSM->getOps<nodal::ModuleOp>().begin();
    auto machine = *module.getOps<nodal::FlatFSMOp>().begin();
    SmallVector<SmallVector<Value, 4>, 4> controls;
    unsigned work = 0;
    boundedFSM = failed(nodal::flatFSMActionControlDependencies(machine, controls, work, 1)) &&
                 controls.empty() &&
                 diagnostics.find("finite sampling budget") != std::string::npos;
  }
  report("fsm-control-root-budget-before-growth", boundedFSM);

  auto rawUnsafe = parse(independentGuard);
  if (rawUnsafe) {
    auto module = *rawUnsafe->getOps<nodal::ModuleOp>().begin();
    std::string before = print(module);
    report("raw-unsafe-normalization-atomic",
           failed(nodal::normalizeSequentialModel(module)) && before == print(module));
  }
  auto normalizedUnsafe = parse(reusedGuard);
  if (normalizedUnsafe) {
    auto module = *normalizedUnsafe->getOps<nodal::ModuleOp>().begin();
    bool good = succeeded(nodal::normalizeSequentialModel(module));
    Operation *other = nullptr;
    for (auto crossing : module.getOps<nodal::CrossingOp>())
      if (crossing->getAttrOfType<DictionaryAttr>("metadata")
              .getAs<StringAttr>("semantic_path")
              .getValue() == "Fixture.other")
        other = crossing;
    auto selections = module.getOps<nodal::SequentialSelectOp>();
    if (good && other && !selections.empty()) {
      (*selections.begin())->setOperand(0, other->getResult(0));
      good = failed(verify(*normalizedUnsafe)) &&
             diagnostics.find("NODAL-CDC-057-002") != std::string::npos;
    } else {
      good = false;
    }
    report("normalized-guard-rhs-reconvergence", good);
  }

  check("actual-wire-source", setup() + wire() + crossing("sync", "wire") + sample(), true);
  check("wire-does-not-hide-foreign-source",
        setup() + port("foreign", "b") + wire("foreign") + crossing("sync", "wire"), false);
  check("constant-not-source-proof",
        setup() +
            "%zero = \"nodal.const_literal\"() <{value = 0 : i64, spelling = \"0\", metadata = "
            "{}}> : () -> " +
            bit + "\n" + crossing("sync", "zero"),
        false);
  std::string req =
      "\"nodal.domain_requirement\"() <{sym_name = \"required\", metadata = {}}> : () -> ()\n"
      "\"nodal.domain_bind\"() <{requirement = @required, actual = @a, metadata = {}}> : () -> "
      "()\n";
  check("unique-bound-source-requirement", setup() + req + crossing("sync", "input", "required"),
        true);
  check("aliases-not-distinct-endpoints",
        setup() + req + crossing("sync", "input", "required", "a"), false);
  std::string descriptor =
      replace(replace(crossing(), "contract = \"level_v1\", ", ""), "stages = 2 : i64, ", "");
  check("f19-descriptor-preserved", setup() + descriptor, true);
  check("descriptor-cannot-sample", setup() + descriptor + sample(), false);
  check("descriptor-pulse-cannot-be-reclassified",
        setup() + replace(descriptor, "kind = \"sync\"", "kind = \"pulse\"") +
            crossing("again", "sync", "b", "a"),
        false);
  check("separate-crossings-remain-legal", base + crossing("other"), true);
  check("independent-destination-registers",
        base + crossing("other") + sample() + replaceAll(sample("other"), "state", "state2"), true);

  check("duplicate-identity", base + replace(crossing("other"), "Fixture.other", "Fixture.sync"),
        false);
  check("one-crossing-reused", base + expression("joined", "sync", "sync") + sample("joined"),
        true);
  std::string aliasPort =
      "%inputAlias = \"nodal.port_value\"() <{port = @input, metadata = {}}> : () -> " + bit + "\n";
  check("same-physical-port-reconvergence",
        base + aliasPort + crossing("other", "inputAlias") + expression("joined", "sync", "other") +
            sample("joined"),
        false);
  check("independent-common-source-reconvergence",
        base + crossing("other") + expression("joined", "sync", "other") + sample("joined"), false);
  check("independent-disjoint-sources",
        base + port("input2", "a") + crossing("other", "input2") +
            expression("joined", "sync", "other") + sample("joined"),
        true);
  check("wire-causal-reconvergence",
        setup() + wire() + crossing() + crossing("other", "wire") +
            expression("joined", "sync", "other") + sample("joined"),
        false);
  check("source-proof-crossing-chain", base + crossing("again", "sync", "b", "a"), true);
  auto graphRoot = check("graph-inspection-fixture", base + crossing("other") + sample(), true);
  if (graphRoot) {
    auto module = *graphRoot->getOps<nodal::ModuleOp>().begin();
    auto first = *module.getOps<nodal::CrossingOp>().begin();
    Operation *use = &module->getRegion(0).front().back();
    auto graph = nodal::analyzeDigitalAssignmentDependencies(module, {{first->getResult(0), use}});
    bool valid = succeeded(graph) && graph->crossings.size() == 2;
    if (valid) {
      const auto &node = graph->nodes[graph->roots[0]];
      valid = node.crossing == first && node.dependencies.empty() && node.sourceDomain &&
              node.sourceDomain.getValue() == "b" && node.crossingSource < graph->nodes.size() &&
              graph->nodes[node.crossingSource].value == first->getOperand(0) &&
              graph->crossings[0].operation != graph->crossings[1].operation;
    }
    report("graph-retains-boundary-input-and-destination", valid);
    auto limits = nodal::DigitalAssignmentProofLimits();
    limits.maximumNodes = 1;
    report("graph-budget-rejects-before-expansion",
           failed(nodal::analyzeDigitalAssignmentDependencies(module, {{first->getResult(0), use}},
                                                              limits)));
  }
  auto mutation = parse(base);
  if (mutation) {
    auto module = *mutation->getOps<nodal::ModuleOp>().begin();
    auto op = *module.getOps<nodal::CrossingOp>().begin();
    nodal::SequentialModelLimits stateLimits;
    stateLimits.maximumNormalizedOperations = 1;
    report("checked-total-state-budget", failed(nodal::verifyCrossingModel(module, stateLimits)));
    Type original = op->getResult(0).getType();
    op->getResult(0).setType(nodal::UIntType::get(&context, 1));
    report("native-result-type-forgery", failed(nodal::verifyCrossingModel(module)));
    op->getResult(0).setType(original);
    Value input = op->getOperand(0);
    auto foreign = parse(setup());
    if (foreign) {
      auto foreignModule = *foreign->getOps<nodal::ModuleOp>().begin();
      for (auto portValue : foreignModule.getOps<nodal::PortValueOp>())
        if (portValue->getAttrOfType<FlatSymbolRefAttr>("port").getValue() == "input")
          op->setOperand(0, portValue->getResult(0));
      report("native-foreign-module-input", failed(nodal::verifyCrossingModel(module)));
      op->setOperand(0, input);
    }
    op->setOperands({});
    report("native-arity-forgery", failed(nodal::verifyCrossingModel(module)));
    op->setOperands({input});
    op->setAttr("stages", IntegerAttr::get(IntegerType::get(&context, 8), 2));
    report("native-stage-carrier-forgery", failed(nodal::verifyCrossingModel(module)));
  }
  // Gray/Pulse extend the same owned input-root proof and retained lineage.
  const std::string u8 = "!nodal.uint<8>";
  auto grayBody = [&](std::string type = "!nodal.uint<8>", unsigned stages = 2) {
    return domain("a") + domain("b") + port("input", "a", type) + grayCrossing(type, stages);
  };
  for (const std::string &type : std::initializer_list<std::string>{
           bit, u8, "!nodal.bits<4096>", "!nodal.sint<9>", "!nodal.shaped<\"2,3\", !nodal.uint<4>>",
           "!nodal.struct<\"Pair\", [{name = \"low\", type = !nodal.bits<3>}, "
           "{name = \"high\", type = !nodal.shaped<\"2\", !nodal.sint<5>>}]>"})
    check("gray-complete-abi-" + type, grayBody(type), true);
  check("gray-stage32", grayBody(u8, 32), true);
  check("gray-stage1-rejected", grayBody(u8, 1), false);
  check("gray-stage33-rejected", grayBody(u8, 33), false);
  check("gray-host-integer-rejected", grayBody("i64"), false, "bit-pattern payload ABI");
  check("gray-host-real-rejected", grayBody("f64"), false, "bit-pattern payload ABI");
  check("gray-nested-real-rejected", grayBody("!nodal.shaped<\"2\", f64>"), false,
        "bit-pattern payload ABI");
  check("gray-resetless-state-budget", grayBody("!nodal.bits<8000>", 32), false, "state expansion");
  check("gray-no-width-coercion",
        domain("a") + domain("b") + port("input", "a", u8) +
            replace(grayCrossing(), ") -> !nodal.uint<8>\n", ") -> !nodal.sint<8>\n"),
        false);
  check("gray-source-domain-spoof",
        replace(grayBody(), "source_domain = @a, destination_domain = @b",
                "source_domain = @b, destination_domain = @a"),
        false);
  check("gray-destination-reset-does-not-change-payload-abi",
        domain("a") + domain("b", "sync") + port("input", "a", u8) + grayCrossing(), true);
  for (const std::string field :
       {"one_bit_per_source_sample", "less_than_source_period", "stable_between_source_samples"})
    check("gray-obligation-forgery-" + field, replace(grayBody(), field, "unproved"), false,
          "typed temporal/environment obligations");
  check("gray-unknown-obligation",
        replace(grayBody(), "transition = \"one_bit_per_source_sample\"",
                "unrecognized = true, transition = \"one_bit_per_source_sample\""),
        false);
  const std::string enumeration = "!nodal.enum<\"NoZero\", 2>";
  check("gray-resetless-nominal-enum-no-zero",
        replace(wrap(grayBody(enumeration)), "module { ", "module { " + noZeroEnum()), true);
  check("gray-reset-bearing-enum-no-zero-still-uninitialized",
        replace(wrap(domain("a") + domain("b", "sync") + port("input", "a", enumeration) +
                     grayCrossing(enumeration)),
                "module { ", "module { " + noZeroEnum()),
        true);
  check("gray-shaped-enum-complete-abi",
        replace(wrap(grayBody("!nodal.shaped<\"3\", " + enumeration + ">")), "module { ",
                "module { " + noZeroEnum()),
        true);
  auto grayRoot = check("gray-checked-physical-bit-budget", grayBody(), true);
  if (grayRoot) {
    auto module = *grayRoot->getOps<nodal::ModuleOp>().begin();
    auto op = *module.getOps<nodal::CrossingOp>().begin();
    auto header = nodal::inspectExecutableCrossing(op);
    report("gray-shared-header-counts-monitor-bits",
           succeeded(header) && header->kind == nodal::CrossingKind::Gray &&
               header->payloadMaximumBits == 8 && header->stateBits == 25);
    nodal::SequentialModelLimits limits;
    limits.maximumNormalizedOperations = 24;
    report("gray-custom-total-bit-budget", failed(nodal::verifyCrossingModel(module, limits)));
  }
  const std::string pulse = pulseSetup() + pulseCrossing();
  std::string lateBindings = pulseSetup();
  size_t firstReset = lateBindings.find("%aReset =");
  check("pulse-symbol-binding-may-follow-crossing",
        lateBindings.substr(0, firstReset) + port("input", "a") + pulseCrossing() +
            replace(lateBindings.substr(firstReset), port("input", "a"), ""),
        true);
  check("pulse-common-root-external-release", pulse, true);
  std::string synchronized = pulseSetup();
  for (const std::string name : {"a", "b"}) {
    synchronized = replace(synchronized, "release = \"external\", release_domain = @" + name,
                           "release = \"raw\"");
    const std::string producer =
        "%" + name + "Safe = \"nodal.reset_sync\"(%" + name + "Reset, %" + name +
        "Clock) <{destination_domain = @" + name +
        ", stages = 2 : i64, reset_polarity = \"active_high\", metadata = {}}> : "
        "(!nodal.capability<\"reset\">, !nodal.capability<\"clock\">) -> "
        "!nodal.capability<\"reset\">\n";
    synchronized = replace(
        synchronized, "\"nodal.local_domain_binding\"(%" + name + "Clock, %" + name + "Reset)",
        producer + "\"nodal.local_domain_binding\"(%" + name + "Clock, %" + name + "Safe)");
  }
  check("pulse-common-root-independent-safe-release", synchronized + pulseCrossing(), true);
  check("pulse-wrong-release-clock-rejected",
        replace(pulse, "release_domain = @b", "release_domain = @a"), false,
        "exact destination clock and edge");
  check("pulse-common-shaped-element", pulseSetup(true, true) + pulseCrossing(), true);
  check("pulse-distinct-physical-roots", pulseSetup(false) + pulseCrossing(), false,
        "equal nonempty physical reset root/element/polarity sets");
  check("pulse-distinct-shaped-elements", pulseSetup(false, true) + pulseCrossing(), false,
        "equal nonempty physical reset root/element/polarity sets");
  check("pulse-resetless-rejected", setup() + pulseCrossing(), false, "reset-bearing");
  check("pulse-nonfixed-stages", replace(pulse, "stages = 2 : i64", "stages = 3 : i64"), false);
  check("pulse-nonbool-rejected", replaceAll(pulse, bit, "!nodal.uint<1>"), false);
  check("pulse-no-metadata-epoch-proof",
        replace(pulse, "reset_epoch = \"common_assertion\"", "reset_epoch = \"independent\""),
        false);
  check("pulse-no-assumed-clock-ratio-spacing",
        replace(pulse, "returned_acknowledgement", "two_source_cycles"), false);
  check("pulse-source-spoof",
        replace(pulse, "source_domain = @a, destination_domain = @b",
                "source_domain = @b, destination_domain = @a"),
        false);
  check("pulse-independent-instances", pulse + pulseCrossing("other"), true);
  check("pulse-reconvergence-not-waived",
        pulse + pulseCrossing("other") + expression("joined", "pulse", "other") + sample("joined"),
        false, "NODAL-CDC-057-002");
  check("pulse-ordinary-destination-sampling-legal", pulse + sample("pulse"), true);
  check("pulse-result-cannot-be-level", pulse + crossing("again", "pulse", "b", "a"), false,
        "NODAL-CDC-003");
  check("pulse-result-cannot-be-gray",
        pulse + replace(grayCrossing(bit, 2, "again", "pulse"),
                        "source_domain = @a, destination_domain = @b",
                        "source_domain = @b, destination_domain = @a"),
        false, "NODAL-CDC-003");
  check("pulse-composition-cannot-be-level",
        pulse + expression("joined", "pulse", "pulse") + crossing("again", "joined", "b", "a"),
        false, "NODAL-CDC-003");
  check("pulse-wire-alias-cannot-be-level",
        pulse + wire("pulse") + crossing("again", "wire", "b", "a"), false, "NODAL-CDC-003");
  std::string returnedPulse =
      replace(pulseCrossing("returned", "pulse"), "source_domain = @a, destination_domain = @b",
              "source_domain = @b, destination_domain = @a");
  check("pulse-explicit-category-retransfer", pulse + returnedPulse, true);
  check("pulse-source-proof-cache-retains-category",
        pulse + returnedPulse + crossing("again", "pulse", "b", "a"), false, "NODAL-CDC-003");
  auto pulseRoot = check("pulse-header-fixture", pulse, true);
  if (pulseRoot) {
    auto module = *pulseRoot->getOps<nodal::ModuleOp>().begin();
    auto header = nodal::inspectExecutableCrossing(*module.getOps<nodal::CrossingOp>().begin());
    report("pulse-shared-header-counts-return-sync-and-monitor",
           succeeded(header) && header->kind == nodal::CrossingKind::Pulse && header->stages == 2 &&
               header->payloadMaximumBits == 1 && header->stateBits == 7);
  }
  // Snapshot handshake reuses the finite Gray payload ABI, actual source graph,
  // and Pulse common-reset proof; neither payload register has an initializer.
  auto handshakeBody = [&](std::string type = "!nodal.uint<8>") {
    return replaceAll(pulseSetup(), bit, type) + handshakeCrossing(type);
  };
  const std::string handshake = handshakeBody();
  for (const std::string &type : std::initializer_list<std::string>{
           bit, u8, "!nodal.bits<4096>", "!nodal.sint<9>", "!nodal.shaped<\"2,3\", !nodal.uint<4>>",
           "!nodal.struct<\"Pair\", [{name = \"low\", type = !nodal.bits<3>}, "
           "{name = \"high\", type = !nodal.shaped<\"2\", !nodal.sint<5>>}]>"})
    check("handshake-complete-abi-" + type, handshakeBody(type), true);
  check("handshake-nominal-enum-without-zero",
        replace(wrap(handshakeBody(enumeration)), "module { ", "module { " + noZeroEnum()), true);
  check("handshake-shaped-enum-without-zero",
        replace(wrap(handshakeBody("!nodal.shaped<\"3\", " + enumeration + ">")), "module { ",
                "module { " + noZeroEnum()),
        true);
  for (const std::string &type : std::initializer_list<std::string>{
           "i64", "f64", "!nodal.shaped<\"2\", f64>",
           "!nodal.struct<\"RealPayload\", [{name = \"bad\", type = f64}]>"})
    check("handshake-non-bit-pattern-" + type, handshakeBody(type), false,
          "bit-pattern payload ABI");
  check("handshake-exact-signedness",
        replace(handshake, "}> : (!nodal.uint<8>) -> !nodal.uint<8>\n",
                "}> : (!nodal.uint<8>) -> !nodal.sint<8>\n"),
        false);
  check("handshake-exact-result-width",
        replace(handshake, "}> : (!nodal.uint<8>) -> !nodal.uint<8>\n",
                "}> : (!nodal.uint<8>) -> !nodal.uint<9>\n"),
        false);
  for (int stages : {0, 1, 3, 32})
    check("handshake-exact-two-stages-" + std::to_string(stages),
          replace(handshake, "stages = 2 : i64", "stages = " + std::to_string(stages) + " : i64"),
          false);
  check("handshake-stage-exact-i64", replace(handshake, "stages = 2 : i64", "stages = 2 : i32"),
        false);
  check("handshake-missing-stages", replace(handshake, "stages = 2 : i64, ", ""), false);
  check("handshake-wrong-contract", replace(handshake, "handshake_snapshot_v1", "handshake_v1"),
        false);
  check("handshake-kind-contract-mismatch",
        replace(handshake, "kind = \"handshake\"", "kind = \"pulse\""), false);
  for (const std::string field :
       {"latest_snapshot_coalescing", "settled_before_request_capture", "common_assertion"})
    check("handshake-obligation-forgery-" + field, replace(handshake, field, "unproved"), false,
          "typed temporal/environment obligations");
  check("handshake-missing-obligation",
        replace(handshake, "bundled_data = \"settled_before_request_capture\", ", ""), false);
  check("handshake-untyped-obligation",
        replace(handshake, "delivery = \"latest_snapshot_coalescing\"", "delivery = true"), false);
  check("handshake-extra-obligation",
        replace(handshake, "delivery = \"latest_snapshot_coalescing\"",
                "extra = true, delivery = \"latest_snapshot_coalescing\""),
        false);
  check("handshake-metadata-is-not-proof",
        replace(handshake, "semantic_path = \"Fixture.handshake\"",
                "semantic_path = \"Fixture.handshake\", safe = true"),
        false);
  check("handshake-resetless-rejected", setup() + handshakeCrossing(bit), false, "reset-bearing");
  check("handshake-sync-reset-common-root",
        replaceAll(handshake, "reset_policy = \"async\"", "reset_policy = \"sync\""), true);
  check("handshake-common-root-independent-safe-release",
        replaceAll(synchronized, bit, u8) + handshakeCrossing(), true);
  check("handshake-common-shaped-element",
        replaceAll(pulseSetup(true, true), bit, u8) + handshakeCrossing(), true);
  check("handshake-distinct-roots", replaceAll(pulseSetup(false), bit, u8) + handshakeCrossing(),
        false, "equal nonempty physical reset root/element/polarity sets");
  check("handshake-distinct-shaped-elements",
        replaceAll(pulseSetup(false, true), bit, u8) + handshakeCrossing(), false,
        "equal nonempty physical reset root/element/polarity sets");
  check("handshake-wrong-release-clock",
        replace(handshake, "release_domain = @b", "release_domain = @a"), false,
        "exact destination clock and edge");
  check("handshake-raw-release-rejected",
        replace(handshake, "release = \"external\", release_domain = @b", "release = \"raw\""),
        false, "reset release lacks proof");
  check("handshake-contradictory-root-polarity",
        replace(handshake,
                "%bReset = \"nodal.reset_boundary\"(%root0) <{reset_polarity = \"active_high\"",
                "%bReset = \"nodal.reset_boundary\"(%root0) <{reset_polarity = \"active_low\""),
        false, "contradictory polarities");
  check("handshake-source-spoof",
        replace(handshake, "source_domain = @a, destination_domain = @b",
                "source_domain = @b, destination_domain = @a"),
        false);
  check("handshake-unattributed-source",
        replace(handshake, port("input", "a", u8), port("input", "none", u8)), false);
  check("handshake-source-wire-alias",
        pulseSetup() + wire() + handshakeCrossing(bit, "handshake", "wire"), true);
  check("handshake-result-destination-owned", handshake + replaceAll(sample("handshake"), bit, u8),
        true);
  check("handshake-result-not-source-owned",
        handshake + replaceAll(sample("handshake", "a"), bit, u8), false);
  check("handshake-pulse-source-not-relabeled",
        pulse + replace(handshakeCrossing(bit, "again", "pulse"),
                        "source_domain = @a, destination_domain = @b",
                        "source_domain = @b, destination_domain = @a"),
        false, "NODAL-CDC-003");
  check("handshake-independent-instances", handshake + handshakeCrossing(u8, "other"), true);
  check("handshake-duplicate-instance-identity",
        handshake + replace(handshakeCrossing(u8, "other"), "Fixture.other", "Fixture.handshake"),
        false);
  check("handshake-reconvergence-not-waived",
        handshakeBody(bit) + handshakeCrossing(bit, "other") +
            expression("joined", "handshake", "other") + sample("joined"),
        false, "NODAL-CDC-057-002");
  check("handshake-same-instance-reused",
        handshakeBody(bit) + expression("joined", "handshake", "handshake") + sample("joined"),
        true);
  const std::string boundedWidth =
      "\"nodal.parameter\"() <{sym_name = \"W\", type = i64, parameter_kind = \"integer\", "
      "classification = \"structural\", default_value = 4 : i64, variability = \"symbolic\", "
      "metadata = {}}> : () -> ()\n"
      "%lower = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", metadata = {}}> : "
      "() -> i64\n"
      "%upper = \"nodal.const_literal\"() <{value = 32 : i64, spelling = \"32\", metadata = {}}> : "
      "() -> i64\n"
      "\"nodal.parameter_constraint\"(%lower, %upper) <{parameter = @W, constraint_kind = "
      "\"range\", "
      "lower_inclusive = true, upper_inclusive = true, metadata = {}}> : (i64, i64) -> ()\n"
      "\"nodal.parameter_envelope\"() <{parameter = @W, effects = [\"shape\", \"width\"], "
      "policy = \"static_generate\", metadata = {}}> : () -> ()\n";
  auto boundedHandshake =
      check("handshake-bounded-symbolic-payload-native",
            boundedWidth + handshakeBody("!nodal.digital<\"uint\", \"W\">"), true);
  if (boundedHandshake) {
    auto module = *boundedHandshake->getOps<nodal::ModuleOp>().begin();
    auto header = nodal::inspectExecutableCrossing(*module.getOps<nodal::CrossingOp>().begin());
    report("handshake-symbolic-budget-uses-upper-not-default",
           succeeded(header) && header->payloadMaximumBits == 32 && header->stateBits == 70);
  }
  check("handshake-unresolved-symbolic-width-rejected",
        handshakeBody("!nodal.digital<\"uint\", \"W\">"), false);
  check("handshake-maximum-state-budget", handshakeBody("!nodal.bits<131069>"), true);
  check("handshake-state-budget-overflow", handshakeBody("!nodal.bits<131070>"), false,
        "state expansion");
  check("handshake-state-budget-arithmetic-overflow",
        handshakeBody("!nodal.bits<9223372036854775807>"), false, "state expansion");
  auto handshakeRoot = check("handshake-header-fixture", handshake, true);
  if (handshakeRoot) {
    auto module = *handshakeRoot->getOps<nodal::ModuleOp>().begin();
    auto op = *module.getOps<nodal::CrossingOp>().begin();
    auto header = nodal::inspectExecutableCrossing(op);
    report("handshake-counts-two-payloads-six-controls",
           succeeded(header) && header->kind == nodal::CrossingKind::Handshake &&
               header->stages == 2 && header->payloadMaximumBits == 8 && header->stateBits == 22);
    nodal::SequentialModelLimits limits;
    limits.maximumNormalizedOperations = 21;
    report("handshake-custom-total-bit-budget", failed(nodal::verifyCrossingModel(module, limits)));
    limits.maximumNormalizedOperations = 22;
    report("handshake-custom-exact-bit-budget",
           succeeded(nodal::verifyCrossingModel(module, limits)));
  }
  std::string many = setup();
  for (unsigned i = 0; i < 128; ++i)
    many += crossing("chain" + std::to_string(i), i ? "chain" + std::to_string(i - 1) : "input",
                     i % 2 ? "b" : "a", i % 2 ? "a" : "b");
  auto start = std::chrono::steady_clock::now();
  check("128-boundaries-nonrecursive-source-proof", many, true);
  llvm::outs() << "CROSSING_CHAIN_128_US "
               << std::chrono::duration_cast<std::chrono::microseconds>(
                      std::chrono::steady_clock::now() - start)
                      .count()
               << '\n';
  llvm::outs() << "CROSSING_MODEL_CASES " << cases << " FAILURES " << failures << '\n';
  return failures ? 1 : 0;
}
