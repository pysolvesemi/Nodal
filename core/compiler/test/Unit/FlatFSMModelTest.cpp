#include "nodal/Dialect/Nodal/FlatFSMModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Dialect/Nodal/StaticPredicateModel.h"

#include "llvm/ADT/StringExtras.h"
#include "llvm/Support/SHA256.h"
#include "llvm/Support/raw_ostream.h"

#include <chrono>
#include <functional>
#include <map>
#include <string>
#include <tuple>
#include <vector>
using namespace mlir;
namespace {
std::string hash = "cb807326542df7db5314f8d795ea4384c2b40209497084c69a41f9ccd9faf46b";
std::string abi() {
  return "abi_version = 1 : i64, semantic_type = \"example.Mode\", abi_hash = \"" + hash + "\"";
}
std::string bits(unsigned width) { return "!nodal.bits<" + std::to_string(width) + ">"; }
std::string enumeration() {
  std::string s = "\"nodal.enum\"() <{sym_name = \"Mode\", underlying_type = !nodal.uint<3>, "
                  "encoding = \"custom\", metadata = {" +
                  abi() + "}}> ({\n";
  const char *names[] = {"Idle", "Run", "Stop"};
  int values[] = {0, 3, 7};
  for (unsigned i = 0; i < 3; ++i)
    s += "\"nodal.enum_case\"() <{sym_name = \"" + std::string(names[i]) +
         "\", value = " + std::to_string(values[i]) +
         " : i64, metadata = {case_id = \"example.Mode." + names[i] + "\"}}> : () -> ()\n";
  return s + "}) : () -> ()\n";
}
std::string literal(std::string name, unsigned value, unsigned width = 8) {
  return "%" + name + " = \"nodal.const_literal\"() <{value = " + std::to_string(value) + " : ui" +
         std::to_string(width) + ", spelling = \"" + std::to_string(value) +
         "\", metadata = {semantic_path = \"Fixture." + name + "\"}}> : () -> " + bits(width) +
         "\n";
}
std::string port(std::string name, std::string type) {
  return "\"nodal.port\"() <{sym_name = \"" + name + "\", direction = \"input\", type = " + type +
         ", domain = @core, metadata = {}}> : () -> ()\n%" + name +
         " = \"nodal.port_value\"() <{port = @" + name +
         ", metadata = {semantic_path = \"Fixture." + name + "\"}}> : () -> " + type + "\n";
}
std::string state(std::string name, std::string type, std::string initial = {}) {
  return "%" + name + " = \"nodal.digital_storage\"(" + (initial.empty() ? "" : "%" + initial) +
         ") <{name = \"" + name +
         "\", kind = \"register\", domain = @core, metadata = {semantic_path = \"Fixture." + name +
         "\"}}> : (" + (initial.empty() ? "" : type) + ") -> " + type + "\n";
}
std::string setup(bool reset = true, unsigned width = 2, unsigned initial = 0) {
  std::string s = "\"nodal.domain\"() <{sym_name = \"core\", edge = \"rising\", reset_policy = \"" +
                  std::string(reset ? "sync" : "none") + "\", metadata = {}}> : () -> ()\n";
  s += port("clock", "!nodal.capability<\"clock\">");
  if (reset)
    s += port("reset", "!nodal.capability<\"reset\">");
  s += "\"nodal.local_domain_binding\"(%clock" + std::string(reset ? ", %reset" : "") +
       ") <{domain = @core, reset_polarity = \"active_high\", metadata = {}}> : "
       "(!nodal.capability<\"clock\">" +
       std::string(reset ? ", !nodal.capability<\"reset\">" : "") + ") -> ()\n";
  s += literal("initial", initial, width) + state("state", bits(width), reset ? "initial" : "") +
       state("count", bits(8)) + port("go", bits(1)) + port("again", bits(1)) +
       port("done", bits(1));
  for (unsigned i = 1; i <= 5; ++i)
    s += literal("v" + std::to_string(i), i);
  return s;
}
std::string assignment(std::string target, std::string value, std::string type = bits(8)) {
  return "\"nodal.digital_assign\"(%" + target + ", %" + value +
         ") <{kind = \"register_next\", metadata = {}}> : (" + type + ", " + type + ") -> ()\n";
}
std::string machine(bool actions = true, std::string policy = "error",
                    std::string encoding = "compact", unsigned width = 2,
                    std::string codes = "0 : ui2, 1 : ui2, 2 : ui2") {
  std::string operands = "%state, %go, %again, %done",
              types = bits(width) + ", " + bits(1) + ", " + bits(1) + ", " + bits(1);
  std::string actionAttrs, counts, regions;
  const char *phases[] = {"active", "exit", "transition", "entry", "entry"};
  for (unsigned i = 0; actions && i < 5; ++i) {
    if (i) {
      actionAttrs += ", ";
      counts += ", ";
      regions += ", ";
    }
    actionAttrs += "{region_id = \"Fixture.machine.action" + std::to_string(i) + "\", phase = \"" +
                   phases[i] + "\", ";
    actionAttrs +=
        i == 2 ? "transition_ordinal = 1 : i64"
               : "owner_case = \"example.Mode." + std::string(i == 4 ? "Stop" : "Run") + "\"";
    actionAttrs += ", ordinal = " + std::to_string(i) + " : i64}";
    counts += "2";
    operands += ", %count, %v" + std::to_string(i + 1);
    types += ", " + bits(8) + ", " + bits(8);
    regions += "{^bb0(%cur" + std::to_string(i) + ": " + bits(width) + ", %next" +
               std::to_string(i) + ": " + bits(width) + ", %taken" + std::to_string(i) + ": " +
               bits(1) + ", %target" + std::to_string(i) + ": " + bits(8) + ", %value" +
               std::to_string(i) + ": " + bits(8) + "):\n" +
               assignment("target" + std::to_string(i), "value" + std::to_string(i)) + "}";
  }
  return "%next, %taken = \"nodal.flat_fsm\"(" + operands +
         ") <{contract_version = \"flat-fsm-v1\", machine_id = \"Fixture.machine\", enum_ref = "
         "@Mode, domain = @core, initial_case = \"example.Mode.Idle\", requested_encoding = \"" +
         encoding + "\", resolved_encoding = \"" +
         (encoding == "auto" ? "auto-compact-v1" : encoding) +
         "\", local_width = " + std::to_string(width) +
         " : i64, case_ids = [\"example.Mode.Idle\", \"example.Mode.Run\", \"example.Mode.Stop\"], "
         "local_codes = [" +
         codes + "], illegal_policy = \"" + policy +
         "\", states = [{case_id = \"example.Mode.Idle\", terminal = false, ordinal = 0 : i64}, "
         "{case_id = \"example.Mode.Run\", terminal = false, ordinal = 1 : i64}, {case_id = "
         "\"example.Mode.Stop\", terminal = true, ordinal = 2 : i64}], transitions = [{source_case "
         "= \"example.Mode.Idle\", destination_case = \"example.Mode.Run\", mode = \"priority\", "
         "ordinal = 0 : i64}, {source_case = \"example.Mode.Run\", destination_case = "
         "\"example.Mode.Run\", mode = \"priority\", ordinal = 1 : i64}, {source_case = "
         "\"example.Mode.Run\", destination_case = \"example.Mode.Stop\", mode = \"priority\", "
         "ordinal = 2 : i64}], actions = [" +
         actionAttrs + "], action_capture_counts = array<i32" +
         (counts.empty() ? "" : ": " + counts) +
         ">, metadata = {semantic_path = \"Fixture.machine\"}}>" +
         (regions.empty() ? "" : " (" + regions + ")") + " : (" + types + ") -> (" + bits(width) +
         ", " + bits(1) + ")\n";
}
std::string wrap(std::string body) {
  return "module { " + enumeration() +
         "\"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({\n" + body +
         "}) : () -> () }";
}
std::string replace(std::string s, std::string a, std::string b) {
  auto at = s.find(a);
  if (at == std::string::npos)
    llvm::report_fatal_error("missing replacement");
  s.replace(at, a.size(), b);
  return s;
}
std::string controlledSetup(bool reset = true) {
  return setup(reset) + literal("false", 0, 1) + literal("dormant", 0, 2) +
         state("status", bits(2), reset ? "dormant" : "") + port("restart", bits(1)) +
         port("cancel", bits(1)) + port("enable", bits(1));
}
std::string controlledMachine(bool actions = true, std::string policy = "recover_to_initial") {
  auto result = machine(actions, policy);
  result = replace(result, "%next, %taken =", "%next, %taken, %entered, %exited =");
  result = replace(result, "flat-fsm-v1", "flat-fsm-v2");
  result = replace(result, "%state, %go", "%state, %status, %restart, %cancel, %enable, %go");
  result = replace(result, " : (" + bits(2) + ", " + bits(1),
                   " : (" + bits(2) + ", " + bits(2) + ", " + bits(1) + ", " + bits(1) + ", " +
                       bits(1) + ", " + bits(1));
  result = replace(result, "-> (" + bits(2) + ", " + bits(1) + ")",
                   "-> (" + bits(2) + ", " + bits(1) + ", " + bits(1) + ", " + bits(1) + ")");
  if (actions)
    result = replace(result,
                     "region_id = \"Fixture.machine.action3\", phase = \"entry\", owner_case = "
                     "\"example.Mode.Run\"",
                     "region_id = \"Fixture.machine.action3\", phase = \"entry\", owner_case = "
                     "\"example.Mode.Idle\"");
  for (std::string event : {"entered", "exited"})
    result += "%observed_" + event + " = \"nodal.digital_expr\"(%" + event +
              ", %false) <{operator_name = \"bool_or\", metadata = {semantic_path = \"Fixture." +
              event + "\"}}> : (" + bits(1) + ", " + bits(1) + ") -> " + bits(1) + "\n";
  return result;
}
std::string timedSetup(bool reset = true) {
  return controlledSetup(reset) +
         replace(literal("elapsedZero", 0, 31), bits(31), "!nodal.uint<31>") +
         state("elapsed", "!nodal.uint<31>", reset ? "elapsedZero" : "");
}
std::string timedMachine(int cycles = 3, bool controlled = true) {
  auto source = controlled ? controlledMachine(false) : machine(false, "recover_to_initial");
  source = replace(source, controlled ? "flat-fsm-v2" : "flat-fsm-v1", "flat-fsm-v4");
  source = replace(source, "machine_id =",
                   "lifecycle_mode = \"" + std::string(controlled ? "controlled" : "legacy") +
                       "\", timer_edges = [{ordinal = 0 : i64, cycles = " + std::to_string(cycles) +
                       " : i64}], transfer_edges = [], machine_id =");
  source = replace(source, controlled ? "%enable, %go" : "%state, %go",
                   controlled ? "%enable, %elapsed, %expired" : "%state, %elapsed, %expired");
  std::string prefix =
      " : (" + bits(2) + ", " +
      (controlled ? bits(2) + ", " + bits(1) + ", " + bits(1) + ", " + bits(1) + ", " : "");
  source = replace(source, prefix, prefix + "!nodal.uint<31>, ");
  return "%expired = \"nodal.fsm_elapsed\"(%elapsed) <{cycles = " + std::to_string(cycles) +
         " : i64, domain = @core, metadata = {semantic_path = \"Fixture.expired\"}}> : "
         "(!nodal.uint<31>) -> " +
         bits(1) + "\n" + source;
}
std::string transferMachine() {
  auto source = replace(controlledMachine(false), "flat-fsm-v2", "flat-fsm-v4");
  source = replace(source, "machine_id =",
                   "lifecycle_mode = \"controlled\", timer_edges = [], transfer_edges = [{ordinal "
                   "= 0 : i64}], machine_id =");
  source = replace(source, "%enable, %go", "%enable, %transferred");
  return "%transferred = \"nodal.fsm_transfer\"(%go, %done) <{domain = @core, metadata = "
         "{semantic_path = \"Fixture.transferred\"}}> : (" +
         bits(1) + ", " + bits(1) + ") -> " + bits(1) + "\n" + source;
}
std::string requiredFixture(bool controlled = true, bool conditional = true, bool error = false,
                            bool bothReset = false) {
  auto child = controlled ? controlledSetup() : setup();
  child = "\"nodal.domain_requirement\"() <{sym_name = \"core\", metadata = {}}> : () -> ()\n" +
          child.substr(child.find("%initial"));
  if (conditional)
    for (std::string name : controlled ? std::vector<std::string>{"state", "status"}
                                       : std::vector<std::string>{"state"})
      child = replace(child, "name = \"" + name + "\", kind = \"register\"",
                      "name = \"" + name +
                          "\", kind = \"register\", initializer_policy = \"if_reset\"");
  child += controlled ? controlledMachine(true, error ? "error" : "recover_to_initial")
                      : machine(true, error ? "error" : "recover_to_initial");
  std::string top;
  for (bool first : {true, false}) {
    bool reset = first || bothReset;
    std::string domain = first ? "core" : "other";
    top += "\"nodal.domain\"() <{sym_name = \"" + domain +
           "\", edge = \"rising\", reset_policy = \"" + (reset ? "sync" : "none") +
           "\", metadata = {}}> : () -> ()\n";
    for (std::string role :
         reset ? std::vector<std::string>{"clock", "reset"} : std::vector<std::string>{"clock"}) {
      auto name = role + "_" + domain;
      top += "\"nodal.port\"() <{sym_name = \"" + name +
             "\", direction = \"input\", type = !nodal.capability<\"" + role + "\">, domain = @" +
             domain + ", metadata = {}}> : () -> ()\n%" + name +
             " = \"nodal.port_value\"() <{port = @" + name +
             ", metadata = {}}> : () -> !nodal.capability<\"" + role + "\">\n";
    }
    top += "\"nodal.local_domain_binding\"(%clock_" + domain + (reset ? ", %reset_" + domain : "") +
           ") <{domain = @" + domain +
           ", reset_polarity = \"active_high\", metadata = {}}> : "
           "(!nodal.capability<\"clock\">" +
           (reset ? std::string(", !nodal.capability<\"reset\">") : "") + ") -> ()\n";
    auto name = "child_" + domain;
    top += "\"nodal.instance\"() <{sym_name = \"" + name +
           "\", module = @Fixture, domain_bindings = {core = @" + domain +
           "}, parameter_bindings = {}, metadata = {}}> : () -> ()\n";
    top += "\"nodal.instance_domain_binding\"(%clock_" + domain +
           (reset ? ", %reset_" + domain : "") + ") <{instance = @" + name +
           ", requirement = @core, actual = @" + domain +
           ", origin = \"named\", metadata = {}}> : (!nodal.capability<\"clock\">" +
           (reset ? std::string(", !nodal.capability<\"reset\">") : "") + ") -> ()\n";
    for (std::string input :
         controlled ? std::vector<std::string>{"go", "again", "done", "restart", "cancel", "enable"}
                    : std::vector<std::string>{"go", "again", "done"}) {
      auto value = name + "_" + input;
      top += "%" + value + " = \"nodal.instance_port\"() <{instance = @" + name + ", port = \"" +
             input + "\", direction = \"input\", metadata = {}}> : () -> !nodal.bits<1>\n";
      top += "\"nodal.digital_assign\"(%" + value +
             ", %zero) <{kind = \"continuous\", metadata = {}}> : "
             "(!nodal.bits<1>, !nodal.bits<1>) -> ()\n";
    }
  }
  return "module attributes {nodal.root.module = @Top} {\n" + enumeration() +
         "\"nodal.module\"() <{sym_name = \"Top\", metadata = {}}> ({\n"
         "%zero = \"nodal.const_literal\"() <{value = false, spelling = \"0\", metadata = "
         "{}}> : () -> !nodal.bits<1>\n" +
         top +
         "}) : () -> ()\n"
         "\"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({\n" +
         child + "}) : () -> ()\n}";
}
std::string stackPolicy(StringRef target = {}) {
  return target.empty() ? "{kind = \"assert_hold\"}"
                        : "{kind = \"trap\", target_case = \"example.Mode." + target.str() + "\"}";
}
unsigned pointerWidth(unsigned capacity) { return llvm::APInt(64, capacity).getActiveBits(); }
std::string stackSetup(bool reset = true, unsigned capacity = 2, unsigned width = 2,
                       unsigned initial = 0) {
  auto source = controlledSetup(reset);
  source = replace(source, literal("initial", 0, 2), literal("initial", initial, width));
  source = replace(source, state("state", bits(2), reset ? "initial" : ""),
                   state("state", bits(width), reset ? "initial" : ""));
  auto type = "!nodal.uint<" + std::to_string(pointerWidth(capacity)) + ">";
  source +=
      replace(literal("depthZero", 0, pointerWidth(capacity)), bits(pointerWidth(capacity)), type) +
      state("depth", type, reset ? "depthZero" : "");
  for (unsigned i = 0; i < capacity; ++i)
    source += state("frame" + std::to_string(i), bits(width));
  return source;
}
std::string stackMachine(bool actions = true, unsigned capacity = 2,
                         std::string overflow = stackPolicy(),
                         std::string underflow = stackPolicy(), std::string invalid = stackPolicy(),
                         unsigned width = 2, std::string encoding = "compact",
                         std::string codes = "0 : ui2, 1 : ui2, 2 : ui2") {
  auto source = controlledMachine(actions);
  source = replace(source, "flat-fsm-v2", "flat-fsm-v5");
  source = replace(source, "machine_id =",
                   "lifecycle_mode = \"controlled\", timer_edges = [], "
                   "transfer_edges = [], stack = {capacity = " +
                       std::to_string(capacity) +
                       " : i64, pointer_width = " + std::to_string(pointerWidth(capacity)) +
                       " : i64, overflow = " + overflow + ", underflow = " + underflow +
                       ", invalid_frame = " + invalid + "}, machine_id =");
  source = replace(source, "requested_encoding = \"compact\"",
                   "requested_encoding = \"" + encoding + "\"");
  source = replace(source, "resolved_encoding = \"compact\"",
                   "resolved_encoding = \"" + encoding + "\"");
  source =
      replace(source, "local_width = 2 : i64", "local_width = " + std::to_string(width) + " : i64");
  source =
      replace(source, "local_codes = [0 : ui2, 1 : ui2, 2 : ui2]", "local_codes = [" + codes + "]");
  source =
      replace(source, " : (" + bits(2) + ", " + bits(2), " : (" + bits(width) + ", " + bits(2));
  source = replace(source, ") -> (" + bits(2), ") -> (" + bits(width));
  std::string operands = "%enable, %depth",
              types = "!nodal.uint<" + std::to_string(pointerWidth(capacity)) + ">";
  for (unsigned i = 0; i < capacity; ++i) {
    operands += ", %frame" + std::to_string(i);
    types += ", " + bits(width);
  }
  source = replace(source, "%enable, %go", operands + ", %go");
  std::string prefix = " : (" + bits(width) + ", " + bits(2) + ", " + bits(1) + ", " + bits(1) +
                       ", " + bits(1) + ", ";
  source = replace(source, prefix, prefix + types + ", ");
  source = replace(source,
                   "source_case = \"example.Mode.Idle\", destination_case = \"example.Mode.Run\"",
                   "kind = \"call\", source_case = \"example.Mode.Idle\", destination_case = "
                   "\"example.Mode.Run\", resume_case = \"example.Mode.Stop\"");
  source =
      replace(source, "source_case = \"example.Mode.Run\", destination_case = \"example.Mode.Run\"",
              "kind = \"call\", source_case = \"example.Mode.Run\", destination_case = "
              "\"example.Mode.Run\", resume_case = \"example.Mode.Run\"");
  source = replace(source,
                   "source_case = \"example.Mode.Run\", destination_case = \"example.Mode.Stop\"",
                   "kind = \"return\", source_case = \"example.Mode.Run\"");
  if (actions)
    source = replace(source, "phase = \"entry\", owner_case = \"example.Mode.Idle\"",
                     "phase = \"entry\", owner_case = \"example.Mode.Run\"");
  for (std::string event : {"next", "taken"}) {
    auto type = event == "next" ? bits(width) : bits(1);
    source +=
        "%stack_observed_" + event + " = \"nodal.digital_expr\"(%false, %" + event + ", %" + event +
        ") <{operator_name = \"digital_select\", metadata = {semantic_path = \"Fixture.observed_" +
        event + "\"}}> : (" + bits(1) + ", " + type + ", " + type + ") -> " + type + "\n";
  }
  return source;
}
std::map<std::string, int> stackInputs() {
  return {{"state", 1},   {"status", 1}, {"depth", 0},  {"frame0", 3}, {"frame1", 3},
          {"restart", 0}, {"cancel", 0}, {"enable", 1}, {"go", 0},     {"again", 0},
          {"done", 0},    {"reset", 0},  {"count", 99}, {"clock", 0}};
}
std::string print(Operation *op) {
  std::string s;
  llvm::raw_string_ostream out(s);
  op->print(out);
  return s;
}
int evaluate(Value value, const std::map<std::string, int> &inputs) {
  Operation *op = value.getDefiningOp();
  if (!op)
    return -999;
  if (auto literal = op->getAttrOfType<IntegerAttr>("value"))
    return int(literal.getValue().getZExtValue());
  if (llvm::isa<nodal::DigitalStorageOp>(op))
    return inputs.at(op->getAttrOfType<StringAttr>("name").getValue().str());
  if (llvm::isa<nodal::PortValueOp>(op))
    return inputs.at(op->getAttrOfType<FlatSymbolRefAttr>("port").getValue().str());
  if (llvm::isa<nodal::ResetAssertedOp>(op))
    return (evaluate(op->getOperand(0), inputs) != 0) ==
           (op->getAttrOfType<StringAttr>("reset_polarity").getValue() == "active_high");
  if (llvm::isa<nodal::SequentialSelectOp>(op))
    return evaluate(op->getOperand(evaluate(op->getOperand(0), inputs) ? 1 : 2), inputs);
  auto attr = op->getAttrOfType<StringAttr>("operator_name");
  if (!attr)
    return -999;
  StringRef code = attr.getValue();
  auto at = [&](unsigned i) { return evaluate(op->getOperand(i), inputs); };
  if (code == "digital_alias" || code == "digital_extend")
    return at(0);
  if (code == "bool_not")
    return !at(0);
  if (code == "bool_and")
    return at(0) && at(1);
  if (code == "bool_or")
    return at(0) || at(1);
  if (code == "digital_eq")
    return at(0) == at(1);
  if (code == "digital_ge")
    return at(0) >= at(1);
  if (code == "digital_le")
    return at(0) <= at(1);
  if (code == "digital_add")
    return at(0) + at(1);
  if (code == "digital_truncate") {
    Type type = value.getType();
    unsigned width = llvm::isa<nodal::UIntType>(type)
                         ? llvm::cast<nodal::UIntType>(type).getWidth()
                         : llvm::cast<nodal::BitsType>(type).getWidth();
    return at(0) & int((uint64_t(1) << width) - 1);
  }
  if (code == "digital_select")
    return at(0) ? at(1) : at(2);
  if (code == "enum_is_valid")
    return at(0) == 0 || at(0) == 3 || at(0) == 7;
  if (code == "enum_is_one_of") {
    int actual = at(0);
    for (Attribute a : op->getAttrOfType<ArrayAttr>("case_ids")) {
      StringRef id = llvm::cast<StringAttr>(a).getValue();
      if (actual == (id.ends_with("Idle") ? 0 : id.ends_with("Run") ? 3 : 7))
        return 1;
    }
    return 0;
  }
  return -999;
}
bool stackStep(nodal::ModuleOp module, StringRef label, std::map<std::string, int> inputs,
               std::map<std::string, int> changes, int assertion = 1) {
  auto expected = inputs;
  expected["next"] = inputs.at("state");
  expected["taken"] = expected["entered"] = expected["exited"] = 0;
  for (const auto &[name, value] : changes)
    expected[name] = value;
  if (changes.count("state") && !changes.count("next"))
    expected["next"] = changes.at("state");
  unsigned updates = 0, events = 0, assertions = 0;
  bool good = true;
  auto equal = [&](StringRef name, int actual, int wanted) {
    if (actual != wanted) {
      llvm::errs() << "stack step " << label << " " << name << ": " << actual << " != " << wanted
                   << '\n';
      good = false;
    }
  };
  module.walk([&](nodal::SequentialUpdateOp op) {
    ++updates;
    auto name = op->getOperand(0).getDefiningOp()->getAttrOfType<StringAttr>("name").getValue();
    int actual = inputs.at("reset") && op->getNumOperands() == 5
                     ? evaluate(op->getOperand(4), inputs)
                     : evaluate(op->getOperand(1), inputs);
    equal(name, actual, expected.at(name.str()));
    if (name.starts_with("frame"))
      good &= op->getNumOperands() == 3;
  });
  module.walk([&](nodal::SequentialAssertOp op) {
    ++assertions;
    equal("assertion", evaluate(op->getOperand(0), inputs), assertion);
  });
  module.walk([&](nodal::DigitalExprOp op) {
    auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
    auto path = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
    if (!path)
      return;
    for (std::string event : {"next", "taken", "entered", "exited"})
      if (path.getValue() ==
          (event == "next" || event == "taken" ? "Fixture.observed_" : "Fixture.") + event) {
        ++events;
        equal(event, evaluate(op->getResult(0), inputs), expected.at(event));
      }
  });
  return good && updates == (inputs.count("elapsed") ? 7 : 6) && events == 4 && assertions == 1;
}
std::string enumSwitch() {
  std::string s = port("selector", "!nodal.enum<\"Mode\", 3>");
  s += "\"nodal.enum_switch\"(%selector, %count, %v1, %count, %v2, %count, %v3, %count, %v5) "
       "<{contract_version = \"enum-switch-v1\", region_id = \"Fixture.switch\", enum_ref = @Mode, "
       "case_ids = [\"example.Mode.Idle\", \"example.Mode.Run\", \"example.Mode.Stop\"], "
       "capture_counts = array<i32: 2, 2, 2, 2>, arm_ids = [\"Fixture.switch.idle\", "
       "\"Fixture.switch.run\", \"Fixture.switch.stop\", \"Fixture.switch.invalid\"], metadata = "
       "{semantic_path = \"Fixture.switch\"}}> (";
  for (unsigned i = 0; i < 4; ++i) {
    if (i)
      s += ", ";
    s += "{^bb0(%target" + std::to_string(i) + ": " + bits(8) + ", %value" + std::to_string(i) +
         ": " + bits(8) + "):\n" +
         assignment("target" + std::to_string(i), "value" + std::to_string(i)) + "}";
  }
  return s + ") : (!nodal.enum<\"Mode\", 3>, " + bits(8) + ", " + bits(8) + ", " + bits(8) + ", " +
         bits(8) + ", " + bits(8) + ", " + bits(8) + ", " + bits(8) + ", " + bits(8) + ") -> ()\n";
}

std::string scaleFixture() {
  const unsigned count = 65, width = 7, transitions = 256;
  std::string identity = "example.Large";
  llvm::SHA256 digest;
  digest.update("nodal.enum.abi.v1\n");
  auto field = [&](StringRef value) {
    digest.update(std::to_string(value.size()));
    digest.update(":");
    digest.update(value);
    digest.update("\n");
  };
  field(identity);
  field("7");
  std::map<std::string, unsigned> sorted;
  for (unsigned i = 0; i < count; ++i)
    sorted[identity + ".S" + std::to_string(i)] = i;
  for (auto &entry : sorted) {
    field(entry.first);
    field(std::to_string(entry.second));
  }
  std::string e = "\"nodal.enum\"() <{sym_name = \"Large\", underlying_type = !nodal.uint<7>, "
                  "encoding = \"custom\", metadata = {abi_version = 1 : i64, semantic_type = \"" +
                  identity + "\", abi_hash = \"" + llvm::toHex(digest.final(), true) + "\"}}> ({\n";
  std::string ids, codes, states, edges, operands = "%state", types = bits(width);
  for (unsigned i = 0; i < count; ++i) {
    std::string name = "S" + std::to_string(i), id = identity + "." + name;
    e += "\"nodal.enum_case\"() <{sym_name = \"" + name + "\", value = " + std::to_string(i) +
         " : ui7, metadata = {case_id = \"" + id + "\"}}> : () -> ()\n";
    if (i) {
      ids += ", ";
      codes += ", ";
      states += ", ";
    }
    ids += "\"" + id + "\"";
    codes += std::to_string(i) + " : ui7";
    states += "{case_id = \"" + id + "\", terminal = " + (i + 1 == count ? "true" : "false") +
              ", ordinal = " + std::to_string(i) + " : i64}";
  }
  for (unsigned i = 0; i < transitions; ++i) {
    if (i)
      edges += ", ";
    unsigned from = i / 4;
    edges += "{source_case = \"" + identity + ".S" + std::to_string(from) +
             "\", destination_case = \"" + identity + ".S" + std::to_string(from + 1) +
             "\", mode = \"priority\", ordinal = " + std::to_string(i) + " : i64}";
    operands += ", %go";
    types += ", " + bits(1);
  }
  e += "}) : () -> ()\n";
  std::string f =
      "%next, %taken = \"nodal.flat_fsm\"(" + operands +
      ") <{contract_version = \"flat-fsm-v1\", machine_id = \"Fixture.machine\", enum_ref = "
      "@Large, domain = @core, initial_case = \"example.Large.S0\", requested_encoding = "
      "\"compact\", resolved_encoding = \"compact\", local_width = 7 : i64, case_ids = [" +
      ids + "], local_codes = [" + codes + "], illegal_policy = \"error\", states = [" + states +
      "], transitions = [" + edges +
      "], actions = [], action_capture_counts = array<i32>, metadata = {semantic_path = "
      "\"Fixture.machine\"}}> : (" +
      types + ") -> (!nodal.bits<7>, !nodal.bits<1>)\n";
  return "module { " + e + "\"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({\n" +
         setup(false, width) + f + "}) : () -> () }";
}
} // namespace
int main(int argc, char **argv) {
  if (argc == 2 && StringRef(argv[1]).starts_with("--emit-")) {
    if (StringRef(argv[1]) == "--emit-required") {
      llvm::outs() << requiredFixture();
      return 0;
    }
    bool recovery = StringRef(argv[1]) == "--emit-recovery";
    llvm::outs() << wrap(setup() + machine(true, recovery ? "recover_to_initial" : "error"));
    return 0;
  }
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  unsigned total = 0, failures = 0;
  auto check = [&](StringRef name, const std::string &source, bool expected,
                   std::function<bool(nodal::ModuleOp)> inspect = {}, bool normalize = true) {
    ++total;
    std::string diagnostics;
    ScopedDiagnosticHandler capture(&context, [&](Diagnostic &d) {
      llvm::raw_string_ostream out(diagnostics);
      d.print(out);
      out << '\n';
      return success();
    });
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    bool actual = parsed && succeeded(verify(*parsed));
    nodal::ModuleOp module;
    if (parsed)
      for (auto candidate : parsed->getOps<nodal::ModuleOp>())
        module = candidate;
    if (actual && normalize) {
      actual = succeeded(nodal::normalizeSequentialModel(module));
      if (!actual && diagnostics.empty())
        diagnostics = "normalization rejected without diagnostic\n";
    }
    if (actual && inspect)
      actual = inspect(module);
    if (!expected && !actual && diagnostics.empty()) {
      actual = true;
      diagnostics = "rejection omitted its native diagnostic\n";
    }
    if (actual != expected) {
      ++failures;
      llvm::errs() << "FAIL " << name << "\n" << diagnostics;
    } else
      llvm::outs() << "PASS " << name << '\n';
  };
  // This is an executor-local test, intentionally not whole-module topology
  // admission. The composition owner separately rejects an orphan v3 machine.
  for (bool reset : {false, true})
    for (bool recover : {false, true}) {
      ++total;
      auto v3 = controlledMachine(false, recover ? "recover_to_initial" : "error");
      v3 = replace(v3, "%next, %taken, %entered, %exited =",
                   "%next, %taken, %entered, %exited, %establishment =");
      v3 = replace(v3, "flat-fsm-v2", "flat-fsm-v3");
      v3 = replace(v3, "-> (" + bits(2) + ", " + bits(1) + ", " + bits(1) + ", " + bits(1) + ")",
                   "-> (" + bits(2) + ", " + bits(1) + ", " + bits(1) + ", " + bits(1) + ", " +
                       bits(1) + ")");
      v3 += "%establishment_observed = \"nodal.digital_expr\"(%establishment, %false) "
            "<{operator_name = \"bool_or\", metadata = {semantic_path = "
            "\"Fixture.establishment_observed\"}}> : (" +
            bits(1) + ", " + bits(1) + ") -> " + bits(1) + "\n";
      auto parsed = parseSourceString<mlir::ModuleOp>(wrap(controlledSetup(reset) + v3),
                                                      ParserConfig(&context, false));
      auto module = parsed ? *parsed->getOps<nodal::ModuleOp>().begin() : nodal::ModuleOp();
      bool good = bool(module);
      nodal::FlatFSMOp fsm;
      if (module)
        for (auto op : module.getOps<nodal::FlatFSMOp>())
          fsm = op;
      auto summary = fsm ? nodal::inspectFlatFSMGraphSummary(fsm)
                         : FailureOr<nodal::FlatFSMGraphSummary>(failure());
      good &= succeeded(summary);
      if (succeeded(summary)) {
        good &= summary->header.composed && summary->header.controlled && summary->initial == 0 &&
                summary->width == 2 && summary->localCodes.size() == 3 && summary->terminal[2] &&
                nodal::flatFSMResultRole(summary->header.establishment()) == "establishment";
        unsigned generated = 0;
        auto before = print(module);
        good &= failed(nodal::expandFlatFSMModel(module, generated, 0)) && before == print(module);
        generated = 0;
        good &= succeeded(nodal::expandFlatFSMModel(module, generated, 65536)) && generated > 0;
        Value observed;
        module.walk([&](nodal::DigitalExprOp op) {
          auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
          auto path = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
          if (path && path.getValue() == "Fixture.establishment_observed")
            observed = op->getResult(0);
        });
        good &= bool(observed);
        if (observed)
          for (int state = 0; state != 4; ++state)
            for (int status = 0; status != 4; ++status)
              for (int restart = 0; restart != 2; ++restart)
                for (int cancel = 0; cancel != 2; ++cancel)
                  for (int enable = 0; enable != 2; ++enable)
                    for (int asserted = 0; asserted != (reset ? 2 : 1); ++asserted) {
                      bool cancelled = status == int(nodal::FlatFSMLifecycle::Running) && cancel;
                      bool operational = status == int(nodal::FlatFSMLifecycle::Running) ||
                                         status == int(nodal::FlatFSMLifecycle::Completed);
                      bool legal =
                          state < 3 &&
                          (status != int(nodal::FlatFSMLifecycle::Completed) || state == 2);
                      bool expected = !asserted && !cancelled &&
                                      (restart || (recover && enable && operational && !legal));
                      good &= evaluate(observed, {{"state", state},
                                                  {"status", status},
                                                  {"restart", restart},
                                                  {"cancel", cancel},
                                                  {"enable", enable},
                                                  {"reset", asserted},
                                                  {"go", 0},
                                                  {"again", 0},
                                                  {"done", 0}}) == expected;
                    }
      }
      std::string name = std::string("composed-establishment-") +
                         (reset ? "reset-" : "resetless-") + (recover ? "recover" : "error");
      if (good)
        llvm::outs() << "PASS " << name << '\n';
      else {
        ++failures;
        llvm::errs() << "FAIL " << name << '\n';
      }
    }
  auto controlled = wrap(controlledSetup() + controlledMachine());
  check("contextual-reset-same-controlled-definition", requiredFixture(), true,
        [](nodal::ModuleOp module) {
          unsigned conditional = 0, queries = 0, resetProjections = 0;
          bool valid = true;
          module.walk([&](nodal::SequentialUpdateOp op) {
            if (op->hasAttr("initializer_policy")) {
              ++conditional;
              valid &= op->getNumOperands() == 4 && !op->hasAttr("edge") &&
                       !op->hasAttr("reset_policy") && !op->hasAttr("reset_polarity");
            }
          });
          module.walk([&](nodal::ResetAssertedOp op) {
            ++queries;
            valid &= op->getNumOperands() == 0 && !op->hasAttr("reset_polarity");
          });
          module.walk([&](nodal::RequirementResetOp) { ++resetProjections; });
          return valid && conditional == 2 && queries == 1 && resetProjections == 0;
        });
  check("contextual-reset-ordinary-machine", requiredFixture(false), true);
  check("contextual-error-assertion-materializes-exact-clock", requiredFixture(true, true, true),
        true, [](nodal::ModuleOp module) {
          unsigned clocks = 0, assertions = 0;
          bool valid = true;
          module.walk([&](nodal::RequirementClockOp) { ++clocks; });
          module.walk([&](nodal::SequentialAssertOp op) {
            ++assertions;
            auto controls = nodal::inspectSequentialDomainControls(op, op->getAttr("domain"));
            valid &= succeeded(controls) && controls->contextual() &&
                     nodal::matchesSequentialDomainClock(op->getOperand(1), *controls) &&
                     !op->hasAttr("edge");
          });
          return valid && clocks == 1 && assertions == 1;
        });
  const std::string requirement =
      "\"nodal.domain_requirement\"() <{sym_name = \"core\", metadata = {}}> : () -> ()\n";
  auto projected = replace(
      requiredFixture(true, true, false, true), requirement,
      requirement +
          "%projected_clock = \"nodal.requirement_clock\"() <{requirement = @core, metadata = "
          "{}}> : () -> !nodal.capability<\"clock\">\n"
          "%projected_reset = \"nodal.requirement_reset\"() <{requirement = @core, metadata = "
          "{}}> : () -> !nodal.capability<\"reset\">\n" +
          literal("unrelated_initial", 0, 1) + state("unrelated", bits(1), "unrelated_initial") +
          "\"nodal.sequential_update\"(%unrelated, %unrelated, %projected_clock, %projected_reset, "
          "%unrelated_initial) <{domain = @core, root_id = \"Fixture.unrelated\", metadata = "
          "{}}> : (!nodal.bits<1>, !nodal.bits<1>, !nodal.capability<\"clock\">, "
          "!nodal.capability<\"reset\">, !nodal.bits<1>) -> ()\n");
  check(
      "contextual-reset-dependencies-independent-of-unrelated-projection", projected, true,
      [](nodal::ModuleOp module) {
        bool valid = true;
        unsigned machines = 0;
        module.walk([&](nodal::FlatFSMOp op) {
          ++machines;
          auto controls = nodal::inspectSequentialDomainControls(op, op->getAttr("domain"));
          if (failed(controls) || !controls->contextual() || !controls->reset ||
              controls->reset.use_empty()) {
            valid = false;
            return;
          }
          SmallVector<Value> resultDependencies;
          valid &=
              succeeded(nodal::flatFSMResultDependencies(op->getResult(0), resultDependencies)) &&
              !llvm::is_contained(resultDependencies, controls->reset);
          SmallVector<SmallVector<Value, 4>> actionDependencies;
          unsigned work = 0;
          valid &= succeeded(nodal::flatFSMActionControlDependencies(op, actionDependencies, work,
                                                                     65536)) &&
                   actionDependencies.size() == 5;
          for (const auto &roots : actionDependencies)
            valid &= !llvm::is_contained(roots, controls->reset);
        });
        return valid && machines == 1;
      },
      false);
  check("contextual-FSM-missing-conditional-policy", requiredFixture(true, false), false);
  check("contextual-FSM-unknown-conditional-policy",
        replace(requiredFixture(), "initializer_policy = \"if_reset\"",
                "initializer_policy = \"guess\""),
        false);
  check("physical-FSM-cannot-claim-contextual-policy",
        replace(controlled, "name = \"state\", kind = \"register\"",
                "name = \"state\", kind = \"register\", initializer_policy = \"if_reset\""),
        false);
  check(
      "controlled-distinct-result-semantic-identities", controlled, true,
      [](nodal::ModuleOp module) {
        unsigned count = 0;
        bool good = true;
        const char *roles[] = {"next", "taken", "entered", "exited"};
        module.walk([&](nodal::FlatFSMOp op) {
          for (Value result : op->getResults()) {
            good &= count < 4 && nodal::digitalValueSemanticPath(result) ==
                                     "Fixture.machine." + std::string(roles[count]);
            ++count;
          }
        });
        return good && count == 4;
      },
      false);
  check(
      "controlled-lifecycle-priority-actions-events", controlled, true,
      [&](nodal::ModuleOp module) {
        struct Case {
          int state, status, restart, cancel, enable, go, again, done, reset;
          int next, nextStatus, count, taken, entered, exited;
        };
        const Case cases[] = {
            {0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 99, 0, 0, 0}, // dormant does not implicitly boot
            {3, 0, 1, 0, 0, 1, 1, 1, 0, 0, 1, 4, 0, 1,
             0}, // restart ignores old local code and enable
            {0, 1, 0, 0, 1, 1, 0, 0, 0, 1, 1, 99, 1, 1, 1},
            {1, 1, 0, 0, 1, 0, 1, 1, 0, 1, 1, 3, 1, 1, 1}, // active, exit, transition precedence
            {1, 1, 0, 0, 1, 0, 0, 1, 0, 2, 2, 5, 1, 1, 1}, // terminal entry completes
            {2, 2, 0, 0, 1, 1, 1, 1, 0, 2, 2, 99, 0, 0, 0},
            {1, 1, 0, 1, 0, 0, 1, 1, 0, 1, 3, 2, 0, 0,
             1}, // cancel runs exit, independent of enable
            {1, 1, 1, 1, 1, 0, 1, 1, 0, 1, 3, 2, 0, 0, 1}, // cancel-Running wins restart
            {2, 2, 1, 1, 0, 0, 0, 0, 0, 0, 1, 4, 0, 1,
             0}, // nonrunning cancel does not block restart
            {1, 1, 0, 0, 0, 1, 1, 1, 0, 1, 1, 99, 0, 0, 0},
            {1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 99, 0, 0, 0}, // reset wins all controls/actions
            {3, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 99, 0, 0, 0}, // illegal local recovery has no actions
            {1, 2, 0, 0, 1, 1, 1, 1, 0, 0, 1, 99, 0, 0, 0}, // forged Completed/nonterminal recovers
            {3, 0, 0, 0, 1, 1, 1, 1, 0, 3, 0, 99, 0, 0, 0},
            {3, 1, 1, 1, 1, 1, 1, 1, 0, 3, 3, 99, 0, 0, 1},
            {3, 3, 1, 0, 1, 1, 1, 1, 0, 0, 1, 4, 0, 1, 0},
        };
        bool good = true;
        for (const auto &c : cases) {
          std::map<std::string, int> values{
              {"state", c.state},   {"status", c.status}, {"count", 99}, {"restart", c.restart},
              {"cancel", c.cancel}, {"enable", c.enable}, {"go", c.go},  {"again", c.again},
              {"done", c.done},     {"reset", c.reset},   {"clock", 0}};
          unsigned updates = 0, observations = 0;
          module.walk([&](nodal::SequentialUpdateOp op) {
            ++updates;
            StringRef name =
                op->getOperand(0).getDefiningOp()->getAttrOfType<StringAttr>("name").getValue();
            int actual = c.reset && op->getNumOperands() == 5 ? evaluate(op->getOperand(4), values)
                                                              : evaluate(op->getOperand(1), values);
            int expected = name == "state" ? c.next : name == "status" ? c.nextStatus : c.count;
            good &= actual == expected;
            good &= op->getAttrOfType<StringAttr>("root_id").getValue() == "Fixture.machine";
          });
          module.walk([&](Operation *op) {
            auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
            auto path = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
            if (!path || !op->getNumResults())
              return;
            int expected;
            if (path.getValue() == "Fixture.machine.next")
              expected = c.next;
            else if (path.getValue() == "Fixture.machine.taken")
              expected = c.taken;
            else if (path.getValue() == "Fixture.entered")
              expected = c.entered;
            else if (path.getValue() == "Fixture.exited")
              expected = c.exited;
            else
              return;
            ++observations;
            good &= evaluate(op->getResult(0), values) == expected;
          });
          good &= updates == 3 && observations == 4;
        }
        return good;
      });
  check("controlled-resetless-establishment-cancel-tie",
        wrap(controlledSetup(false) + controlledMachine()), true, [&](nodal::ModuleOp module) {
          bool good = true;
          for (int status = 0; status < 4; ++status)
            for (int cancel = 0; cancel < 2; ++cancel) {
              bool cancelled = status == 1 && cancel;
              std::map<std::string, int> values{{"state", 3},   {"status", status}, {"count", 99},
                                                {"restart", 1}, {"cancel", cancel}, {"enable", 0},
                                                {"go", 1},      {"again", 1},       {"done", 1},
                                                {"clock", 0}};
              module.walk([&](nodal::SequentialUpdateOp op) {
                good &= op->getNumOperands() == 3;
                StringRef name =
                    op->getOperand(0).getDefiningOp()->getAttrOfType<StringAttr>("name").getValue();
                int expected = name == "state"    ? (cancelled ? 3 : 0)
                               : name == "status" ? (cancelled ? 3 : 1)
                                                  : (cancelled ? 99 : 4);
                good &= evaluate(op->getOperand(1), values) == expected;
              });
            }
          return good;
        });
  check("controlled-error-operational-gating",
        wrap(controlledSetup() + controlledMachine(false, "error")), true,
        [&](nodal::ModuleOp module) {
          bool good = true;
          unsigned assertions = 0;
          module.walk([&](nodal::SequentialAssertOp op) {
            ++assertions;
            for (int status : {0, 1, 2, 3})
              for (int restart : {0, 1})
                for (int cancel : {0, 1}) {
                  std::map<std::string, int> values{
                      {"state", 3},       {"status", status}, {"restart", restart},
                      {"cancel", cancel}, {"enable", 1},      {"go", 1},
                      {"again", 1},       {"done", 1},        {"reset", 0}};
                  bool expected =
                      restart || (status == 1 && cancel) || (status != 1 && status != 2);
                  good &= evaluate(op->getOperand(0), values) == expected;
                }
          });
          return good && assertions == 1;
        });
  check("controlled-distinct-private-status",
        replace(controlled, "%state, %status, %restart", "%state, %state, %restart"), false);
  check("controlled-wrong-status-initializer",
        replace(controlled, literal("dormant", 0, 2), literal("dormant", 1, 2)), false);
  check("controlled-external-status-writer",
        wrap(controlledSetup() + controlledMachine() + assignment("status", "initial", bits(2))),
        false);
  check("controlled-exact-result-arity",
        replace(controlled, "%next, %taken, %entered, %exited =", "%next, %taken ="), false);
  check("controlled-resetless-no-fabricated-init",
        wrap(controlledSetup(false) + controlledMachine()), true, [&](nodal::ModuleOp module) {
          bool good = true;
          module.walk([&](nodal::DigitalStorageOp op) { good &= op->getNumOperands() == 0; });
          return good;
        });
  check(
      "controlled-transactional-bound", controlled, true,
      [&](nodal::ModuleOp module) {
        std::string before = print(module);
        nodal::SequentialModelLimits limits;
        limits.maximumNormalizedOperations = 100;
        bool good =
            failed(nodal::normalizeSequentialModel(module, limits)) && before == print(module);
        unsigned generated = 0;
        auto candidateRoot = llvm::cast<mlir::ModuleOp>(module->getParentOp()->clone());
        auto candidate = *candidateRoot.getOps<nodal::ModuleOp>().begin();
        good &= succeeded(nodal::expandFlatFSMModel(candidate, generated, 65536));
        llvm::outs() << "controlled representative expansion: " << generated << " operations\n";
        candidateRoot->erase();
        return good && generated > 50 && generated < limits.maximumNormalizedOperations;
      },
      false);
  auto base = wrap(setup() + machine());
  check("action-order-self-edge-reset-hold", base, true, [&](nodal::ModuleOp module) {
    bool good = true;
    unsigned asserts = 0;
    module.walk([&](nodal::SequentialAssertOp op) {
      ++asserts;
      good &= op->getAttrOfType<StringAttr>("root_id").getValue() == "Fixture.machine";
    });
    for (int state = 0; state < 4; ++state)
      for (int go = 0; go < 2; ++go)
        for (int again = 0; again < 2; ++again)
          for (int done = 0; done < 2; ++done)
            for (int reset = 0; reset < 2; ++reset) {
              std::map<std::string, int> values{{"state", state}, {"count", 99},  {"go", go},
                                                {"again", again}, {"done", done}, {"reset", reset},
                                                {"clock", 0}};
              int next = reset                          ? 0
                         : state == 0 && go             ? 1
                         : state == 1 && !again && done ? 2
                                                        : state;
              int count = reset              ? 99
                          : state == 1       ? (again  ? 4
                                                : done ? 5
                                                       : 1)
                          : state == 0 && go ? 4
                                             : 99;
              for (Operation &op : module->getRegion(0).front())
                if (llvm::isa<nodal::SequentialUpdateOp>(op)) {
                  good &= op.getAttrOfType<StringAttr>("root_id").getValue() == "Fixture.machine";
                  std::string target = op.getOperand(0)
                                           .getDefiningOp()
                                           ->getAttrOfType<StringAttr>("name")
                                           .getValue()
                                           .str();
                  int result = op.getNumOperands() == 5 && reset
                                   ? evaluate(op.getOperand(4), values)
                                   : evaluate(op.getOperand(1), values);
                  good &= result == (target == "state" ? next : count);
                }
              module.walk([&](nodal::SequentialAssertOp op) {
                good &= evaluate(op->getOperand(0), values) == (reset || state < 3);
              });
              unsigned observations = 0;
              module.walk([&](Operation *op) {
                auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
                auto path = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
                if (!path)
                  return;
                if (path.getValue() == "Fixture.machine.next") {
                  ++observations;
                  good &= evaluate(op->getResult(0), values) == next;
                }
                if (path.getValue() == "Fixture.machine.taken") {
                  ++observations;
                  good &= evaluate(op->getResult(0), values) ==
                          (!reset && ((state == 0 && go) || (state == 1 && (again || done))));
                }
              });
              good &= observations == 2;
            }
    std::string once = print(module);
    good &= succeeded(nodal::normalizeSequentialModel(module)) && once == print(module);
    return good && asserts == 1;
  });
  for (unsigned exposed = 0; exposed < 3; ++exposed) {
    std::string altered = base;
    for (unsigned later = exposed + 1; later < 5; ++later)
      altered = replace(
          altered, assignment("target" + std::to_string(later), "value" + std::to_string(later)),
          "");
    check("causal-action-phase-" + std::to_string(exposed), altered, true,
          [exposed](nodal::ModuleOp module) {
            bool good = true;
            std::map<std::string, int> values{{"state", 1}, {"count", 99}, {"go", 0},
                                              {"again", 1}, {"done", 1},   {"reset", 0}};
            module.walk([&](nodal::SequentialUpdateOp op) {
              if (op->getOperand(0).getDefiningOp()->getAttrOfType<StringAttr>("name").getValue() ==
                  "count")
                good &= evaluate(op->getOperand(1), values) == int(exposed + 1);
            });
            return good;
          });
  }
  auto wire = "%wire = \"nodal.digital_storage\"() <{name = \"wire\", kind = \"wire\", metadata = "
              "{semantic_path = \"Fixture.wire\"}}> : () -> !nodal.bits<8>\n";
  auto extended =
      "%extended = \"nodal.digital_expr\"(%next) <{operator_name = \"digital_extend\", metadata = "
      "{semantic_path = \"Fixture.extended\"}}> : (!nodal.bits<2>) -> !nodal.bits<8>\n";
  auto wireAssign =
      "\"nodal.digital_assign\"(%wire, %extended) <{kind = \"continuous\", metadata = "
      "{semantic_path = \"Fixture.wireAssign\"}}> : (!nodal.bits<8>, !nodal.bits<8>) -> ()\n";
  check("result-dependency-excludes-action-capture",
        wrap(setup() + wire + replace(machine(), "%count, %v1", "%count, %wire") + extended +
             wireAssign),
        true);
  std::string feedbackSetup = replace(setup(), port("go", bits(1)),
                                      "%go = \"nodal.digital_storage\"() <{name = \"go\", kind = "
                                      "\"wire\", metadata = {}}> : () -> !nodal.bits<1>\n");
  auto feedback = "\"nodal.digital_assign\"(%go, %taken) <{kind = \"continuous\", metadata = {}}> "
                  ": (!nodal.bits<1>, !nodal.bits<1>) -> ()\n";
  check("taken-feedback-into-guard", wrap(feedbackSetup + machine() + feedback), false);
  std::string fromNext =
      "%fromNext = \"nodal.digital_expr\"(%next0) <{operator_name = \"digital_extend\", metadata = "
      "{semantic_path = \"Fixture.machine.action0.fromNext\"}}> : (!nodal.bits<2>) -> "
      "!nodal.bits<8>\n";
  auto contextFixture =
      replace(base, assignment("target0", "value0"), fromNext + assignment("target0", "fromNext"));
  for (unsigned later = 1; later < 5; ++later)
    contextFixture =
        replace(contextFixture,
                assignment("target" + std::to_string(later), "value" + std::to_string(later)), "");
  check("action-next-context-is-effective-selection", contextFixture, true,
        [](nodal::ModuleOp module) {
          bool good = true;
          for (int done = 0; done < 2; ++done) {
            std::map<std::string, int> values{{"state", 1}, {"count", 99},  {"go", 0},
                                              {"again", 0}, {"done", done}, {"reset", 0}};
            module.walk([&](nodal::SequentialUpdateOp op) {
              if (op->getOperand(0).getDefiningOp()->getAttrOfType<StringAttr>("name").getValue() ==
                  "count")
                good &= evaluate(op->getOperand(1), values) == (done ? 2 : 1);
            });
          }
          return good;
        });
  check(
      "recover-invalid-suppresses-actions", wrap(setup() + machine(true, "recover_to_initial")),
      true, [](nodal::ModuleOp module) {
        bool good = true;
        module.walk([&](nodal::SequentialAssertOp) { good = false; });
        std::map<std::string, int> values{{"state", 3}, {"count", 99}, {"go", 1},
                                          {"again", 1}, {"done", 1},   {"reset", 0}};
        module.walk([&](nodal::SequentialUpdateOp op) {
          std::string name =
              op->getOperand(0).getDefiningOp()->getAttrOfType<StringAttr>("name").getValue().str();
          good &= evaluate(op->getOperand(1), values) == (name == "state" ? 0 : 99);
        });
        return good;
      });
  check("resetless-state-has-no-initializer", wrap(setup(false) + machine(false)), true,
        [](nodal::ModuleOp module) {
          bool good = true;
          module.walk([&](nodal::SequentialUpdateOp op) { good &= op->getNumOperands() == 3; });
          return good;
        });
  check(
      "one-hot-local-canonical-abi",
      wrap(setup(true, 3, 1) + machine(false, "error", "one_hot", 3, "1 : ui3, 2 : ui3, 4 : ui3")),
      true);
  check("gray-local-canonical-abi",
        wrap(setup() + machine(false, "error", "gray", 2, "0 : ui2, 1 : ui2, 3 : ui2")), true);
  check("custom-local-canonical-abi",
        wrap(setup(true, 3, 2) + machine(false, "error", "custom", 3, "2 : ui3, 5 : ui3, 6 : ui3")),
        true);
  check("auto-policy", wrap(setup() + machine(false, "error", "auto")), true);
  check("exhaustive-switch-invalid", wrap(setup() + enumSwitch()), true,
        [](nodal::ModuleOp module) {
          bool good = true;
          for (int value : {0, 3, 7, 1})
            module.walk([&](nodal::SequentialUpdateOp op) {
              if (op->getOperand(0).getDefiningOp()->getAttrOfType<StringAttr>("name").getValue() ==
                  "count")
                good &= evaluate(op->getOperand(1), {{"selector", value}, {"count", 99}}) ==
                        (value == 0   ? 1
                         : value == 3 ? 2
                         : value == 7 ? 3
                                      : 5);
            });
          return good;
        });
  check("unknown-destination",
        replace(base, "destination_case = \"example.Mode.Stop\"",
                "destination_case = \"foreign.Mode.Stop\""),
        false);
  check("unreachable-state",
        replace(base, "destination_case = \"example.Mode.Stop\"",
                "destination_case = \"example.Mode.Run\""),
        false);
  check("terminal-outgoing",
        replace(base, "case_id = \"example.Mode.Run\", terminal = false",
                "case_id = \"example.Mode.Run\", terminal = true"),
        false);
  check("nonterminal-dead-end",
        replace(base, "case_id = \"example.Mode.Stop\", terminal = true",
                "case_id = \"example.Mode.Stop\", terminal = false"),
        false);
  check("duplicate-code", replace(base, "0 : ui2, 1 : ui2, 2 : ui2", "0 : ui2, 1 : ui2, 1 : ui2"),
        false);
  check("wrong-deterministic-code",
        replace(base, "0 : ui2, 1 : ui2, 2 : ui2", "0 : ui2, 2 : ui2, 1 : ui2"), false);
  check(
      "wrong-initial-code",
      replace(base, "initial_case = \"example.Mode.Idle\"", "initial_case = \"example.Mode.Run\""),
      false);
  check("forged-guard-type", replace(base, "%go, %again, %done", "%v1, %again, %done"), false);
  check("foreign-canonical-enum", replace(base, "enum_ref = @Mode", "enum_ref = @Missing"), false);
  check("unknown-action-phase", replace(base, "phase = \"active\"", "phase = \"unknown\""), false);
  check("unknown-action-owner",
        replace(base, "owner_case = \"example.Mode.Run\"", "owner_case = \"example.Mode.Unknown\""),
        false);
  check("local-width-header-diagnostic",
        replace(base, "local_width = 2 : i64", "local_width = 3 : i64"), false);
  check("bad-context-type", replace(base, "%cur0: !nodal.bits<2>", "%cur0: !nodal.bits<3>"), false);
  check("bad-action-capture-count",
        replace(base, "array<i32: 2, 2, 2, 2, 2>", "array<i32: 3, 2, 2, 2, 2>"), false);
  check("unrelated-root-write", wrap(setup() + machine() + assignment("count", "v1")), false);
  auto foreignDomain =
      "\"nodal.domain\"() <{sym_name = \"other\", edge = \"rising\", reset_policy = \"none\", "
      "metadata = {}}> : () -> ()\n" +
      replace(port("otherClock", "!nodal.capability<\"clock\">"), "domain = @core",
              "domain = @other") +
      "\"nodal.local_domain_binding\"(%otherClock) <{domain = @other, reset_polarity = "
      "\"active_high\", metadata = {}}> : (!nodal.capability<\"clock\">) -> ()\n";
  check("controlled-foreign-clock-control-rejected",
        wrap(controlledSetup() + foreignDomain +
             replace(port("foreignRestart", bits(1)), "domain = @core", "domain = @other") +
             replace(controlledMachine(false), "%restart", "%foreignRestart")),
        false);
  auto foreignSetup =
      replace(setup(), state("count", bits(8)),
              replace(state("count", bits(8)), "domain = @core", "domain = @other"));
  check("raw-action-foreign-domain-constant-RHS", wrap(foreignSetup + foreignDomain + machine()),
        false, {}, false);
  check("external-private-write",
        wrap(setup() + machine() + assignment("state", "initial", bits(2))), false);
  auto overlap = base;
  while (overlap.find("mode = \"priority\"") != std::string::npos)
    overlap = replace(overlap, "mode = \"priority\"", "mode = \"exclusive\"");
  check("exclusive-unknown-overlap", overlap, false);
  auto negate =
      "%notAgain = \"nodal.digital_expr\"(%again) <{operator_name = \"bool_not\", metadata = "
      "{semantic_path = \"Fixture.notAgain\"}}> : (!nodal.bits<1>) -> !nodal.bits<1>\n";
  auto exclusive = machine(false);
  while (exclusive.find("mode = \"priority\"") != std::string::npos)
    exclusive = replace(exclusive, "mode = \"priority\"", "mode = \"exclusive\"");
  exclusive = replace(exclusive, "%go, %again, %done", "%go, %again, %notAgain");
  check("exclusive-complementary-actual-guards", wrap(setup() + negate + exclusive), true);
  auto sw = wrap(setup() + enumSwitch());
  check("switch-duplicate-case",
        replace(sw,
                "case_ids = [\"example.Mode.Idle\", \"example.Mode.Run\", \"example.Mode.Stop\"]",
                "case_ids = [\"example.Mode.Idle\", \"example.Mode.Run\", \"example.Mode.Run\"]"),
        false);
  check("switch-foreign-selector", replace(sw, "enum_ref = @Mode", "enum_ref = @Other"), false);
  check(
      "budget-transaction", base, true,
      [](nodal::ModuleOp module) {
        std::string before = print(module);
        nodal::SequentialModelLimits limits;
        limits.maximumNormalizedOperations = 3;
        return failed(nodal::normalizeSequentialModel(module, limits)) && before == print(module);
      },
      false);

  auto mutate = [&](StringRef name, const std::string &input,
                    std::function<void(nodal::ModuleOp)> change) {
    check(
        name, input, true,
        [&](nodal::ModuleOp module) {
          change(module);
          return failed(verify(module));
        },
        false);
  };
  mutate("forged-duplicate-authored-machine-root", base, [&](nodal::ModuleOp module) {
    nodal::FlatFSMOp original;
    module.walk([&](nodal::FlatFSMOp op) { original = op; });
    module->getRegion(0).front().push_back(original->clone());
  });
  mutate("forged-ingress-result-type", base, [&](nodal::ModuleOp module) {
    module.walk(
        [&](nodal::FlatFSMOp op) { op->getResult(1).setType(nodal::BitsType::get(&context, 2)); });
  });
  mutate("forged-ingress-guard-type", base, [&](nodal::ModuleOp module) {
    module.walk([&](nodal::FlatFSMOp op) { op->setOperand(1, op->getOperand(0)); });
  });
  mutate("forged-ingress-action-private-write", base, [&](nodal::ModuleOp module) {
    module.walk([&](nodal::FlatFSMOp op) {
      Operation &write = op->getRegion(0).front().front();
      write.setOperand(0, op->getRegion(0).front().getArgument(0));
      write.setOperand(1, op->getRegion(0).front().getArgument(1));
    });
  });
  mutate("forged-ingress-switch-incomplete", sw, [&](nodal::ModuleOp module) {
    module.walk([&](nodal::EnumSwitchOp op) {
      auto ids = op->getAttrOfType<ArrayAttr>("case_ids");
      op->setAttr("case_ids", ArrayAttr::get(&context, ids.getValue().drop_back()));
    });
  });
  check("forged-normalized-assertion-edge", base, true, [&](nodal::ModuleOp module) {
    module.walk([&](nodal::SequentialAssertOp op) {
      op->setAttr("edge", StringAttr::get(&context, "falling"));
    });
    return failed(verify(module));
  });
  check("forged-normalized-reset-polarity", base, true, [&](nodal::ModuleOp module) {
    module.walk([&](nodal::ResetAssertedOp op) {
      op->setAttr("reset_polarity", StringAttr::get(&context, "active_low"));
    });
    return failed(verify(module));
  });
  check("unknown-action-effect",
        replace(base, assignment("target0", "value0"),
                "\"nodal.local_domain_binding\"() <{domain = @core, reset_polarity = "
                "\"active_high\", metadata = {}}> : () -> ()\n"),
        false);
  auto low = replace(base, "reset_polarity = \"active_high\"", "reset_polarity = \"active_low\"");
  check("actual-active-low-reset", low, true, [](nodal::ModuleOp module) {
    bool good = true;
    for (int physical : {0, 1}) {
      std::map<std::string, int> values{{"state", 3}, {"count", 99}, {"go", 0},
                                        {"again", 0}, {"done", 0},   {"reset", physical}};
      module.walk([&](nodal::SequentialAssertOp op) {
        good &= evaluate(op->getOperand(0), values) == !physical;
      });
    }
    return good;
  });
  auto gray = wrap(setup() + machine(false, "error", "gray", 2, "0 : ui2, 1 : ui2, 3 : ui2"));
  check("gray-incompatible-declared-edge",
        replace(gray, "destination_case = \"example.Mode.Run\"",
                "destination_case = \"example.Mode.Stop\""),
        false);
  // The exact expansion charge is independently enforced both before and while
  // constructing a detached candidate. One less rejects without mutation.
  check(
      "exact-expansion-boundary", base, true,
      [](nodal::ModuleOp module) {
        auto clone = [&]() {
          Operation *root = module->getParentOp()->clone();
          Operation *candidate = nullptr;
          for (Operation &op : root->getRegion(0).front())
            if (llvm::isa<nodal::ModuleOp>(op))
              candidate = &op;
          return std::make_pair(root, candidate);
        };
        unsigned generated = 0;
        auto copy = clone();
        bool good = succeeded(nodal::expandFlatFSMModel(copy.second, generated, 262144));
        copy.first->destroy();
        if (!good || generated < 2)
          return false;
        llvm::outs() << "BUDGET exact_fsm_expansion_operations=" << generated << '\n';
        auto lower = clone();
        unsigned lowCount = 0;
        std::string before = print(lower.first);
        good = failed(nodal::expandFlatFSMModel(lower.second, lowCount, generated - 1)) &&
               lowCount == 0 && before == print(lower.first);
        lower.first->destroy();
        auto exact = clone();
        unsigned exactCount = 0;
        good &= succeeded(nodal::expandFlatFSMModel(exact.second, exactCount, generated)) &&
                exactCount == generated;
        exact.first->destroy();
        return good;
      },
      false);
  // Whole-register Integer/Real values are runtime storage. They never acquire
  // static parameter facts merely because the transport carrier is i64.
  auto storageBranch = [&](std::string type, std::string extra = {}) {
    return "\"nodal.behavioral_if\"(%go, %dynamicState, %dynamicInput) <{contract_version = "
           "\"behavioral-if-v1\", region_id = \"Fixture.dynamic\", operandSegmentSizes = "
           "array<i32: 1, 2, 0>, metadata = {semantic_path = \"Fixture.dynamic\"}}> "
           "({^bb0(%target: " +
           type + ", %value: " + type + "):\n" + extra + assignment("target", "value", type) +
           "}, {^bb0:}) : (!nodal.bits<1>, " + type + ", " + type + ") -> ()\n";
  };
  for (std::string type : {"i64", "f64"})
    check("runtime-storage-" + type,
          wrap(setup() + state("dynamicState", type) + port("dynamicInput", type) +
               storageBranch(type)),
          true);
  auto staticMisuse =
      "%forged = \"nodal.static_compare\"(%target, %value) <{comparison = \"eq\", metadata = "
      "{semantic_path = \"Fixture.dynamic.forged\"}}> : (i64, i64) -> !nodal.static_predicate\n";
  check("runtime-integer-cannot-be-static-proof",
        wrap(setup() + state("dynamicState", "i64") + port("dynamicInput", "i64") +
             storageBranch("i64", staticMisuse)),
        false);

  auto large = scaleFixture();
  check(
      "scale-65-states-256-transitions", large, true,
      [](nodal::ModuleOp module) {
        auto start = std::chrono::steady_clock::now();
        bool good = succeeded(nodal::normalizeSequentialModel(module));
        unsigned operations = 0;
        module.walk([&](Operation *) { ++operations; });
        std::string once = print(module);
        good &= succeeded(nodal::normalizeSequentialModel(module)) && once == print(module);
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                           std::chrono::steady_clock::now() - start)
                           .count();
        llvm::outs() << "SCALE states=65 transitions=256 actions=0 normalized_operations="
                     << operations << " elapsed_ms=" << elapsed << '\n';
        return good;
      },
      false);
  check(
      "represented-operation-budget-transaction", large, true,
      [](nodal::ModuleOp module) {
        std::string before = print(module);
        nodal::SequentialModelLimits limits;
        limits.maximumOperations = 16;
        return failed(nodal::normalizeSequentialModel(module, limits)) && before == print(module);
      },
      false);
  auto parameters =
      "\"nodal.parameter\"() <{sym_name = \"MODE\", type = i64, default_value = 0 : i64, "
      "parameter_kind = \"integer\", classification = \"ordinary\", variability = \"symbolic\", "
      "metadata = {}}> : () -> ()\n%mode = \"nodal.const_parameter_ref\"() <{parameter = @MODE, "
      "metadata = {}}> : () -> i64\n%integerZero = \"nodal.const_literal\"() <{value = 0 : i64, "
      "spelling = \"0\", metadata = {}}> : () -> i64\n";
  std::string comparisons;
  for (std::string comparison : {"eq", "ne"})
    comparisons += "%" + comparison +
                   " = \"nodal.static_compare\"(%mode, %integerZero) <{comparison = \"" +
                   comparison + "\", metadata = {semantic_path = \"Fixture." + comparison +
                   "\"}}> : (i64, i64) -> !nodal.static_predicate\n%" + comparison +
                   "Bool = \"nodal.behavioral_predicate\"(%" + comparison +
                   ") <{metadata = {semantic_path = \"Fixture." + comparison +
                   "Bool\"}}> : (!nodal.static_predicate) -> !nodal.bits<1>\n";
  auto staticExclusive = replace(exclusive, "%go, %again, %notAgain", "%go, %eqBool, %neBool");
  check("exclusive-independent-static-complements",
        wrap(setup() + parameters + comparisons + staticExclusive), true);
  auto nestedSwitch = enumSwitch();
  nestedSwitch = nestedSwitch.substr(port("selector", "!nodal.enum<\"Mode\", 3>").size());
  for (std::string name : {"selector", "count", "v1", "v2", "v3", "v5"}) {
    std::string token = "%" + name;
    size_t at = 0;
    while ((at = nestedSwitch.find(token, at)) != std::string::npos) {
      nestedSwitch.replace(at, token.size(), token + "Scoped");
      at += token.size() + 6;
    }
  }
  auto nested =
      "\"nodal.behavioral_if\"(%go, %selector, %count, %v1, %v2, %v3, %v5) <{contract_version = "
      "\"behavioral-if-v1\", region_id = \"Fixture.outer\", operandSegmentSizes = array<i32: 1, 6, "
      "0>, metadata = {semantic_path = \"Fixture.outer\"}}> ({^bb0(%selectorScoped: "
      "!nodal.enum<\"Mode\", 3>, %countScoped: !nodal.bits<8>, %v1Scoped: !nodal.bits<8>, "
      "%v2Scoped: !nodal.bits<8>, %v3Scoped: !nodal.bits<8>, %v5Scoped: !nodal.bits<8>):\n" +
      nestedSwitch +
      "}, {^bb0:}) : (!nodal.bits<1>, !nodal.enum<\"Mode\", 3>, !nodal.bits<8>, !nodal.bits<8>, "
      "!nodal.bits<8>, !nodal.bits<8>, !nodal.bits<8>) -> ()\n";
  check(
      "nested-exhaustive-switch-root-and-guard",
      wrap(setup() + port("selector", "!nodal.enum<\"Mode\", 3>") + nested), true,
      [](nodal::ModuleOp module) {
        bool good = true;
        for (int go : {0, 1})
          for (int selector : {0, 3, 7, 1})
            module.walk([&](nodal::SequentialUpdateOp op) {
              if (op->getOperand(0).getDefiningOp()->getAttrOfType<StringAttr>("name").getValue() ==
                  "count")
                good &= evaluate(op->getOperand(1),
                                 {{"go", go}, {"selector", selector}, {"count", 99}}) ==
                        (!go             ? 99
                         : selector == 0 ? 1
                         : selector == 3 ? 2
                         : selector == 7 ? 3
                                         : 5);
            });
        return good;
      });

  auto allReplace = [](std::string source, const std::string &from, const std::string &to) {
    size_t at = 0;
    while ((at = source.find(from, at)) != std::string::npos) {
      source.replace(at, from.size(), to);
      at += to.size();
    }
    return source;
  };
  for (std::string type : {"i64", "f64"}) {
    auto typedSetup = replace(setup(), state("count", bits(8)), state("count", type)) +
                      port("dynamicInput", type);
    auto typedMachine = machine();
    auto typedSwitch = enumSwitch();
    for (unsigned i = 1; i <= 5; ++i) {
      typedMachine = allReplace(typedMachine, "%v" + std::to_string(i), "%dynamicInput");
      typedSwitch = allReplace(typedSwitch, "%v" + std::to_string(i), "%dynamicInput");
    }
    typedMachine = allReplace(typedMachine, bits(8), type);
    typedSwitch = allReplace(typedSwitch, bits(8), type);
    check("runtime-FSM-storage-" + type, wrap(typedSetup + typedMachine), true);
    check("runtime-switch-storage-" + type, wrap(typedSetup + typedSwitch), true);
  }
  auto pairBudget = machine(false);
  std::string edgeRecords, edgeOperands = "%state", edgeTypes = bits(2);
  for (unsigned i = 0; i < 401; ++i) {
    if (i)
      edgeRecords += ", ";
    edgeRecords += "{source_case = \"example.Mode." + std::string(i ? "Run" : "Idle") +
                   "\", destination_case = \"example.Mode." + std::string(i ? "Stop" : "Run") +
                   "\", mode = \"exclusive\", ordinal = " + std::to_string(i) + " : i64}";
    edgeOperands += ", %false";
    edgeTypes += ", " + bits(1);
  }
  pairBudget = replace(pairBudget, "%state, %go, %again, %done", edgeOperands);
  pairBudget =
      replace(pairBudget, bits(2) + ", " + bits(1) + ", " + bits(1) + ", " + bits(1), edgeTypes);
  size_t first = pairBudget.find("transitions = [") + 14,
         last = pairBudget.find("], actions", first);
  pairBudget.replace(first, last - first, edgeRecords);
  check("exclusive-pair-work-budget", wrap(setup() + literal("false", 0, 1) + pairBudget), false);
  for (bool reset : {true, false}) {
    check(reset ? "timed-controlled-cycle-pause-reentry-reset"
                : "timed-resetless-establishment-and-cancel",
          wrap(timedSetup(reset) + timedMachine()), true, [&](nodal::ModuleOp module) {
            bool good = true;
            for (int state : {0, 1, 2, 3})
              for (int status : {0, 1, 2, 3})
                for (int elapsed : {0, 1, 2, 2147483646, 2147483647})
                  for (int restart : {0, 1})
                    for (int cancel : {0, 1})
                      for (int enable : {0, 1}) {
                        std::map<std::string, int> values{
                            {"state", state},     {"status", status}, {"elapsed", elapsed},
                            {"restart", restart}, {"cancel", cancel}, {"enable", enable},
                            {"reset", 0},         {"go", 0},          {"again", 0},
                            {"done", 0},          {"count", 0}};
                        bool cancelling = status == 1 && cancel,
                             restarting = restart && !cancelling;
                        bool ordinary = !cancelling && !restart && enable;
                        bool recovery = ordinary && (status == 1 || status == 2) &&
                                        (state == 3 || (status == 2 && state != 2));
                        bool taken = ordinary && status == 1 && state == 0 && elapsed >= 2;
                        int expected = cancelling || restarting || recovery || taken ? 0
                                       : ordinary && status == 1 && state == 0
                                           ? std::min(elapsed + 1, 2147483646)
                                           : elapsed;
                        module.walk([&](nodal::SequentialUpdateOp op) {
                          if (op->getOperand(0)
                                  .getDefiningOp()
                                  ->getAttrOfType<StringAttr>("name")
                                  .getValue() == "elapsed")
                            good &= evaluate(op->getOperand(1), values) == expected;
                        });
                      }
            return good;
          });
  }
  check("timed-legacy-public-after", wrap(timedSetup() + timedMachine(1, false)), true);
  check("timed-maximum-threshold-saturates", wrap(timedSetup() + timedMachine(2147483647)), true,
        [](nodal::ModuleOp module) {
          bool good = true;
          for (int elapsed : {2147483645, 2147483646, 2147483647})
            module.walk([&](nodal::SequentialUpdateOp op) {
              if (op->getOperand(0).getDefiningOp()->getAttrOfType<StringAttr>("name").getValue() ==
                  "elapsed")
                good &= evaluate(op->getOperand(1), {{"state", 0},
                                                     {"status", 1},
                                                     {"elapsed", elapsed},
                                                     {"restart", 0},
                                                     {"cancel", 0},
                                                     {"enable", 1},
                                                     {"reset", 0},
                                                     {"again", 0},
                                                     {"done", 0}}) ==
                        (elapsed >= 2147483646 ? 0 : 2147483646);
            });
          return good;
        });
  check(
      "transfer-valid-ready-observation", wrap(controlledSetup() + transferMachine()), true,
      [](nodal::ModuleOp module) {
        bool good = true;
        for (int valid : {0, 1})
          for (int ready : {0, 1})
            module.walk([&](nodal::SequentialUpdateOp op) {
              if (op->getOperand(0).getDefiningOp()->getAttrOfType<StringAttr>("name").getValue() ==
                  "state")
                good &= evaluate(op->getOperand(1), {{"state", 0},
                                                     {"status", 1},
                                                     {"restart", 0},
                                                     {"cancel", 0},
                                                     {"enable", 1},
                                                     {"reset", 0},
                                                     {"go", valid},
                                                     {"done", ready},
                                                     {"again", 0}}) == (valid && ready ? 1 : 0);
            });
        return good;
      });
  check("timed-zero-count-rejected", wrap(timedSetup() + timedMachine(0)), false);
  check("timed-negative-count-rejected", wrap(timedSetup() + timedMachine(-1)), false);
  check("timed-counter-wrong-type",
        wrap(timedSetup() + replace(timedMachine(), "%elapsed) <{cycles", "%state) <{cycles")),
        false);
  check("timed-table-disagrees",
        wrap(timedSetup() + replace(timedMachine(), "cycles = 3 : i64}],", "cycles = 4 : i64}],")),
        false);
  check("timed-query-extra-consumer",
        wrap(timedSetup() + timedMachine() +
             "%escape = \"nodal.digital_expr\"(%expired) <{operator_name = \"bool_not\", metadata "
             "= {}}> : (" +
             bits(1) + ") -> " + bits(1) + "\n"),
        false);
  check("timed-orphan-query",
        wrap(timedSetup() + timedMachine().substr(0, timedMachine().find("%next"))), false);
  check(
      "timed-private-counter-writer-rejected",
      wrap(timedSetup() + timedMachine() + assignment("elapsed", "elapsedZero", "!nodal.uint<31>")),
      false);
  check(
      "timed-below-bound-budget-transaction", wrap(timedSetup() + timedMachine()), true,
      [](nodal::ModuleOp module) {
        auto before = print(module);
        nodal::SequentialModelLimits limits;
        limits.maximumOperations = 16;
        return failed(nodal::normalizeSequentialModel(module, limits)) && print(module) == before;
      },
      false);
  // Real v5 graphs exercise the normalized register network, not a parallel stack interpreter.
  auto stack = wrap(stackSetup() + stackMachine());
  check("stack-nested-call-return-exact-frame-writes", stack, true, [](nodal::ModuleOp module) {
    auto values = stackInputs();
    values["state"] = 0;
    values["go"] = 1;
    bool good = stackStep(module, "first call", values,
                          {{"state", 1},
                           {"depth", 1},
                           {"frame0", 2},
                           {"count", 4},
                           {"taken", 1},
                           {"entered", 1},
                           {"exited", 1}});
    values["state"] = 1;
    values["depth"] = 1;
    values["frame0"] = 2;
    values["again"] = 1;
    // Both guards true proves stack operations use the ordinary authored priority owner.
    values["done"] = 1;
    good &= stackStep(
        module, "nested call at last slot", values,
        {{"depth", 2}, {"frame1", 1}, {"count", 4}, {"taken", 1}, {"entered", 1}, {"exited", 1}});
    values["depth"] = 2;
    values["frame1"] = 1;
    values["again"] = 0;
    good &= stackStep(module, "nested return", values,
                      {{"depth", 1}, {"count", 4}, {"taken", 1}, {"entered", 1}, {"exited", 1}});
    values["depth"] = 1;
    good &= stackStep(module, "outer terminal return", values,
                      {{"state", 2},
                       {"status", 2},
                       {"depth", 0},
                       {"count", 5},
                       {"taken", 1},
                       {"entered", 1},
                       {"exited", 1}});
    return good;
  });
  check("stack-assert-hold-faults-preserve-active-only", stack, true, [](nodal::ModuleOp module) {
    bool good = true;
    for (auto [depth, call, returning, frame] :
         {std::tuple{2, 1, 1, 2}, std::tuple{0, 0, 1, 3}, std::tuple{3, 1, 1, 2},
          std::tuple{1, 0, 1, 3}, std::tuple{3, 0, 0, 2}}) {
      auto values = stackInputs();
      values["depth"] = depth;
      values["again"] = call;
      values["done"] = returning;
      values["frame0"] = values["frame1"] = frame;
      good &= stackStep(module, "hold fault", values, {{"count", 1}}, 0);
    }
    auto values = stackInputs();
    good &= stackStep(module, "uninitialized frames at zero are ignored", values, {{"count", 1}});
    for (int status : {0, 2, 3}) {
      values["status"] = status;
      values["state"] = status == 2 ? 2 : 1;
      values["depth"] = 3;
      good &= stackStep(module, "fault operational gating", values, {}, status == 2 ? 0 : 1);
    }
    values["status"] = 1;
    values["state"] = 1;
    values["enable"] = 0;
    good &= stackStep(module, "disabled fault", values, {});
    return good;
  });
  check("stack-assert-hold-preserves-running-terminal-status", stack, true,
        [](nodal::ModuleOp module) {
          auto values = stackInputs();
          values["state"] = 2;
          values["depth"] = 3;
          return stackStep(module, "terminal corrupt depth hold", values, {}, 0);
        });
  check("stack-invalid-pointer-policy-precedes-overflow",
        wrap(stackSetup() +
             stackMachine(true, 2, stackPolicy("Stop"), stackPolicy("Stop"), stackPolicy("Run"))),
        true, [](nodal::ModuleOp module) {
          auto values = stackInputs();
          values["depth"] = 3;
          values["again"] = values["done"] = 1;
          return stackStep(
              module, "invalid beats capacity policy", values,
              {{"depth", 0}, {"count", 4}, {"taken", 1}, {"entered", 1}, {"exited", 1}});
        });
  check("stack-reset-restart-recovery-cancel-terminal-clear", stack, true,
        [](nodal::ModuleOp module) {
          auto values = stackInputs();
          values["depth"] = 2;
          values["reset"] = 1;
          values["again"] = 1;
          bool good =
              stackStep(module, "reset", values, {{"state", 0}, {"status", 0}, {"depth", 0}});
          values["reset"] = 0;
          values["restart"] = 1;
          good &=
              stackStep(module, "restart", values, {{"state", 0}, {"depth", 0}, {"entered", 1}});
          values["cancel"] = 1;
          good &= stackStep(module, "cancel wins restart", values,
                            {{"status", 3}, {"depth", 0}, {"count", 2}, {"exited", 1}});
          values["cancel"] = values["restart"] = 0;
          values["state"] = 3;
          good &= stackStep(module, "local recovery", values, {{"state", 0}, {"depth", 0}});
          values["state"] = 1;
          values["again"] = 0;
          values["status"] = 2;
          good &= stackStep(module, "stale completion recovery", values,
                            {{"state", 0}, {"status", 1}, {"depth", 0}});
          values["state"] = 2;
          good &= stackStep(module, "terminal continuation clear", values, {{"depth", 0}});
          return good;
        });
  for (StringRef target : {StringRef("Run"), StringRef("Stop")}) {
    auto policy = stackPolicy(target);
    check("stack-trap-real-entry-" + target.str(),
          wrap(stackSetup() + stackMachine(true, 2, policy, policy, policy)), true,
          [target](nodal::ModuleOp module) {
            bool good = true;
            for (auto [depth, call, returning, frame, current, status] :
                 {std::tuple{2, 1, 0, 2, 1, 1}, std::tuple{0, 0, 1, 3, 1, 1},
                  std::tuple{1, 0, 1, 3, 1, 1}, std::tuple{3, 0, 0, 3, 1, 1},
                  std::tuple{3, 0, 0, 3, 2, 2}}) {
              auto values = stackInputs();
              values["state"] = current;
              values["status"] = status;
              values["depth"] = depth;
              values["again"] = call;
              values["done"] = returning;
              values["frame0"] = values["frame1"] = frame;
              bool terminal = target == "Stop";
              good &= stackStep(module, "trap", values,
                                {{"state", terminal ? 2 : 1},
                                 {"status", terminal ? 2 : 1},
                                 {"depth", 0},
                                 {"count", terminal ? 5 : 4},
                                 {"taken", 1},
                                 {"entered", 1},
                                 {"exited", 1}});
            }
            return good;
          });
  }
  auto ordinaryStack = stackMachine();
  ordinaryStack = replace(ordinaryStack,
                          "kind = \"call\", source_case = \"example.Mode.Idle\", destination_case "
                          "= \"example.Mode.Run\", resume_case = \"example.Mode.Stop\"",
                          "kind = \"ordinary\", source_case = \"example.Mode.Idle\", "
                          "destination_case = \"example.Mode.Run\"");
  ordinaryStack = replace(ordinaryStack, "resume_case = \"example.Mode.Run\"",
                          "resume_case = \"example.Mode.Stop\"");
  check("stack-invalid-pointer-blocks-ordinary-edge", wrap(stackSetup() + ordinaryStack), true,
        [](nodal::ModuleOp module) {
          auto values = stackInputs();
          values["state"] = 0;
          values["depth"] = 3;
          values["go"] = 1;
          return stackStep(module, "ordinary invalid pointer", values, {}, 0);
        });
  check("stack-resetless-restart-establishes-pointer-only",
        wrap(stackSetup(false) + stackMachine()), true, [](nodal::ModuleOp module) {
          auto values = stackInputs();
          values["state"] = values["depth"] = 3;
          values["restart"] = 1;
          bool good = stackStep(module, "resetless restart", values,
                                {{"state", 0}, {"depth", 0}, {"entered", 1}});
          module.walk([&](nodal::DigitalStorageOp op) { good &= op->getNumOperands() == 0; });
          return good;
        });
  check(
      "stack-role-aware-header-and-bounded-summary", stack, true,
      [](nodal::ModuleOp module) {
        auto machine = *module.getOps<nodal::FlatFSMOp>().begin();
        auto summary = nodal::inspectFlatFSMGraphSummary(machine);
        if (failed(summary))
          return false;
        auto roles = summary->header.privateStateRoles();
        return summary->header.stacked && summary->header.guardOffset == 8 &&
               summary->header.stackCapacity == 2 && roles.size() == 5 &&
               roles[0].role == nodal::FlatFSMPrivateRole::Local &&
               roles[1].role == nodal::FlatFSMPrivateRole::Lifecycle &&
               roles[2].role == nodal::FlatFSMPrivateRole::StackPointer &&
               roles[3].role == nodal::FlatFSMPrivateRole::StackFrame &&
               roles[4].role == nodal::FlatFSMPrivateRole::StackFrame &&
               summary->completionPossible && summary->structuralWork == 82;
      },
      false);
  auto noReturn =
      replace(stackMachine(false), "kind = \"return\", source_case = \"example.Mode.Run\"",
              "kind = \"ordinary\", source_case = \"example.Mode.Run\", destination_case = "
              "\"example.Mode.Run\"");
  check("stack-summary-resume-needs-matching-return", wrap(stackSetup() + noReturn), false);
  noReturn = replace(noReturn, "invalid_frame = " + stackPolicy(),
                     "invalid_frame = " + stackPolicy("Stop"));
  noReturn = replace(noReturn, "overflow = " + stackPolicy(), "overflow = " + stackPolicy("Idle"));
  check(
      "stack-summary-trap-clears-continuation-not-return", wrap(stackSetup() + noReturn), true,
      [](nodal::ModuleOp module) {
        auto summary =
            nodal::inspectFlatFSMGraphSummary(*module.getOps<nodal::FlatFSMOp>().begin());
        return succeeded(summary) && !summary->completionPossible && summary->structuralWork > 0;
      },
      false);
  auto completeInside =
      replace(noReturn,
              "kind = \"ordinary\", source_case = \"example.Mode.Run\", destination_case = "
              "\"example.Mode.Run\", mode = \"priority\", ordinal = 2",
              "kind = \"ordinary\", source_case = \"example.Mode.Run\", destination_case = "
              "\"example.Mode.Stop\", mode = \"priority\", ordinal = 2");
  check(
      "stack-summary-callee-may-complete-without-return", wrap(stackSetup() + completeInside), true,
      [](nodal::ModuleOp module) {
        auto summary =
            nodal::inspectFlatFSMGraphSummary(*module.getOps<nodal::FlatFSMOp>().begin());
        return succeeded(summary) && summary->completionPossible;
      },
      false);
  for (unsigned capacity : {1u, 128u})
    check("stack-capacity-boundary-" + std::to_string(capacity),
          wrap(stackSetup(true, capacity) + stackMachine(false, capacity)), true, {}, false);
  for (unsigned capacity : {0u, 129u})
    check("stack-invalid-capacity-" + std::to_string(capacity),
          replace(stack, "capacity = 2 : i64", "capacity = " + std::to_string(capacity) + " : i64"),
          false);
  check("stack-pointer-width-forgery",
        replace(stack, "pointer_width = 2 : i64", "pointer_width = 3 : i64"), false);
  check("stack-return-cannot-forge-destination",
        replace(stack, "kind = \"return\"",
                "kind = \"return\", destination_case = \"example.Mode.Stop\""),
        false);
  check("stack-call-needs-resume", replace(stack, ", resume_case = \"example.Mode.Stop\"", ""),
        false);
  check("stack-unknown-policy-field",
        replace(stack, "overflow = {kind = \"assert_hold\"}",
                "overflow = {kind = \"assert_hold\", target_case = \"example.Mode.Stop\"}"),
        false);
  check("stack-foreign-trap-target",
        replace(stack, "overflow = " + stackPolicy(),
                "overflow = {kind = \"trap\", target_case = \"foreign.Mode.Stop\"}"),
        false);
  check("stack-unknown-transition-kind", replace(stack, "kind = \"call\"", "kind = \"tail_call\""),
        false);
  check("stack-private-pointer-cannot-alias-local",
        replace(stack, "%enable, %depth", "%enable, %state"), false);
  check("stack-private-frames-must-be-distinct",
        replace(stack, "%depth, %frame0, %frame1", "%depth, %frame0, %frame0"), false);
  check("stack-frame-initializer-forgery",
        replace(stack, state("frame0", bits(2)), state("frame0", bits(2), "initial")), false);
  check(
      "stack-pointer-wrong-initializer",
      replace(
          stack,
          "value = 0 : ui2, spelling = \"0\", metadata = {semantic_path = \"Fixture.depthZero\"}",
          "value = 1 : ui2, spelling = \"1\", metadata = {semantic_path = \"Fixture.depthZero\"}"),
      false);
  check("stack-external-frame-writer",
        wrap(stackSetup() + stackMachine() + assignment("frame0", "initial", bits(2))), false);
  check("stack-external-pointer-writer",
        wrap(stackSetup() + stackMachine() + assignment("depth", "depthZero", "!nodal.uint<2>")),
        false);
  check("stack-frame-conditional-initializer-forgery",
        replace(stack, "name = \"frame0\", kind = \"register\"",
                "name = \"frame0\", kind = \"register\", initializer_policy = \"if_reset\""),
        false);
  check("stack-gray-explicitly-unsupported",
        wrap(stackSetup() + stackMachine(false, 2, stackPolicy(), stackPolicy(), stackPolicy(), 2,
                                         "gray", "0 : ui2, 1 : ui2, 3 : ui2")),
        false);
  check("stack-one-hot-local-codes",
        wrap(stackSetup(true, 2, 3, 1) + stackMachine(false, 2, stackPolicy(), stackPolicy(),
                                                      stackPolicy(), 3, "one_hot",
                                                      "1 : ui3, 2 : ui3, 4 : ui3")),
        true);
  check("stack-custom-local-codes",
        wrap(stackSetup() + stackMachine(false, 2, stackPolicy(), stackPolicy(), stackPolicy(), 2,
                                         "custom", "0 : ui2, 2 : ui2, 3 : ui2")),
        true);
  check(
      "stack-normalization-limit-is-atomic", stack, true,
      [](nodal::ModuleOp module) {
        auto before = print(module);
        nodal::SequentialModelLimits limits;
        limits.maximumNormalizedOperations = 32;
        return failed(nodal::normalizeSequentialModel(module, limits)) && before == print(module);
      },
      false);
  for (unsigned width : {511u, 512u}) {
    std::string codes = "0 : ui" + std::to_string(width) + ", 1 : ui" + std::to_string(width) +
                        ", 2 : ui" + std::to_string(width);
    check(
        "stack-actual-storage-bits-" + std::to_string(width),
        wrap(stackSetup(true, 128, width) + stackMachine(false, 128, stackPolicy(), stackPolicy(),
                                                         stackPolicy(), width, "custom", codes)),
        true,
        [width](nodal::ModuleOp module) {
          auto before = print(module);
          unsigned generated = 0;
          auto result = nodal::expandFlatFSMModel(module, generated, 65536);
          return width == 511 ? succeeded(result) && generated > 0
                              : failed(result) && generated == 0 && before == print(module);
        },
        false);
  }
  auto contextualStack = requiredFixture();
  auto stackStorage = stackSetup().substr(controlledSetup().size());
  stackStorage =
      replace(stackStorage, "name = \"depth\", kind = \"register\"",
              "name = \"depth\", kind = \"register\", initializer_policy = \"if_reset\"");
  contextualStack = replace(contextualStack, controlledMachine(), stackStorage + stackMachine());
  check("stack-contextual-mixed-reset-role-policy", contextualStack, true,
        [](nodal::ModuleOp module) {
          unsigned conditional = 0, frames = 0;
          bool good = true;
          module.walk([&](nodal::SequentialUpdateOp op) {
            auto name =
                op->getOperand(0).getDefiningOp()->getAttrOfType<StringAttr>("name").getValue();
            conditional += op->hasAttr("initializer_policy");
            if (name.starts_with("frame")) {
              ++frames;
              good &= !op->hasAttr("initializer_policy") && op->getNumOperands() == 3;
            }
          });
          return good && conditional == 3 && frames == 2;
        });
  check("stack-contextual-pointer-needs-conditional-policy",
        replace(contextualStack,
                "name = \"depth\", kind = \"register\", initializer_policy = \"if_reset\"",
                "name = \"depth\", kind = \"register\""),
        false);
  auto timedStack = stackMachine();
  timedStack = replace(timedStack, "timer_edges = []",
                       "timer_edges = [{ordinal = 2 : i64, cycles = 3 : i64}]");
  timedStack = replace(timedStack, "%enable, %depth", "%enable, %elapsed, %depth");
  timedStack = replace(timedStack, "!nodal.uint<2>, !nodal.bits<2>, !nodal.bits<2>",
                       "!nodal.uint<31>, !nodal.uint<2>, !nodal.bits<2>, !nodal.bits<2>");
  timedStack = replace(timedStack, "%go, %again, %done", "%go, %again, %expired");
  auto elapsedQuery = timedMachine().substr(0, timedMachine().find("%next"));
  check("stack-timer-return-entry-restarts-elapsed",
        wrap(timedSetup() + stackSetup().substr(controlledSetup().size()) + elapsedQuery +
             timedStack),
        true, [](nodal::ModuleOp module) {
          auto values = stackInputs();
          values["elapsed"] = 2;
          values["depth"] = 1;
          values["frame0"] = 1;
          bool good = stackStep(module, "timed self return", values,
                                {{"depth", 0},
                                 {"elapsed", 0},
                                 {"count", 4},
                                 {"taken", 1},
                                 {"entered", 1},
                                 {"exited", 1}});
          values["elapsed"] = values["depth"] = 0;
          good &= stackStep(module, "first resumed cycle", values, {{"elapsed", 1}, {"count", 1}});
          return good;
        });
  auto trapExit = stackMachine(true, 2, stackPolicy("Run"), stackPolicy("Run"), stackPolicy("Run"));
  trapExit = replace(trapExit, assignment("target3", "value3"), "");
  check("stack-trap-exit-without-failed-transition-action", wrap(stackSetup() + trapExit), true,
        [](nodal::ModuleOp module) {
          auto values = stackInputs();
          values["depth"] = 2;
          values["again"] = 1;
          return stackStep(
              module, "trap exit action", values,
              {{"depth", 0}, {"count", 2}, {"taken", 1}, {"entered", 1}, {"exited", 1}});
        });
  auto proofLimit = stackMachine(false, 128);
  std::string extraOperands, extraTypes, extraEdges;
  for (unsigned i = 3; i < 67; ++i) {
    extraOperands += ", %again";
    extraTypes += ", " + bits(1);
    extraEdges += ", {kind = \"call\", source_case = \"example.Mode.Run\", destination_case = "
                  "\"example.Mode.Run\", resume_case = \"example.Mode.Run\", mode = \"priority\", "
                  "ordinal = " +
                  std::to_string(i) + " : i64}";
  }
  proofLimit =
      replace(proofLimit, "%go, %again, %done)", "%go, %again, %done" + extraOperands + ")");
  proofLimit = replace(proofLimit, "ordinal = 2 : i64}], actions",
                       "ordinal = 2 : i64}" + extraEdges + "], actions");
  proofLimit = replace(proofLimit, ") -> (" + bits(2), extraTypes + ") -> (" + bits(2));
  check("stack-predicate-and-rule-work-preflight-limit", wrap(stackSetup(true, 128) + proofLimit),
        false);
  for (bool recovery : {true, false}) {
    auto legacy =
        stackMachine(false, 2, stackPolicy("Run"), stackPolicy("Run"), stackPolicy("Run"));
    legacy = replace(legacy, "lifecycle_mode = \"controlled\"", "lifecycle_mode = \"legacy\"");
    legacy = replace(legacy, "%next, %taken, %entered, %exited =", "%next, %taken =");
    legacy = replace(legacy, "%state, %status, %restart, %cancel, %enable, ", "%state, ");
    legacy = replace(legacy,
                     " : (" + bits(2) + ", " + bits(2) + ", " + bits(1) + ", " + bits(1) + ", " +
                         bits(1) + ", ",
                     " : (" + bits(2) + ", ");
    legacy =
        replace(legacy, ") -> (" + bits(2) + ", " + bits(1) + ", " + bits(1) + ", " + bits(1) + ")",
                ") -> (" + bits(2) + ", " + bits(1) + ")");
    auto begin = legacy.find("%observed_entered"), end = legacy.find("%stack_observed_next");
    legacy.erase(begin, end - begin);
    if (!recovery)
      legacy =
          replace(legacy, "illegal_policy = \"recover_to_initial\"", "illegal_policy = \"error\"");
    // This legacy fixture has only local, pointer and frame registers. Unused
    // controlled fixtures would also acquire unrelated canonical hold updates.
    auto legacySetup = replace(stackSetup(), state("status", bits(2), "dormant"), "");
    legacySetup = replace(legacySetup, state("count", bits(8)), "");
    check(recovery ? "stack-legacy-invalid-local-recovery-is-not-trap"
                   : "stack-legacy-invalid-local-error-is-not-trap",
          wrap(legacySetup + legacy), true, [recovery](nodal::ModuleOp module) {
            auto values = stackInputs();
            values["state"] = values["depth"] = 3;
            values["go"] = values["again"] = values["done"] = 1;
            bool good = true;
            unsigned updates = 0, events = 0;
            module.walk([&](nodal::SequentialUpdateOp op) {
              ++updates;
              auto name =
                  op->getOperand(0).getDefiningOp()->getAttrOfType<StringAttr>("name").getValue();
              int expected =
                  (name == "state" || name == "depth") && recovery ? 0 : values.at(name.str());
              int actual = evaluate(op->getOperand(1), values);
              if (actual != expected)
                llvm::errs() << "legacy " << recovery << " " << name << ": " << actual
                             << " != " << expected << '\n';
              good &= actual == expected;
            });
            module.walk([&](nodal::DigitalExprOp op) {
              auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
              auto path = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
              if (path && path.getValue() == "Fixture.observed_taken") {
                ++events;
                int actual = evaluate(op->getResult(0), values);
                if (actual != 0)
                  llvm::errs() << "legacy " << recovery << " taken: " << actual << " != 0\n";
                good &= actual == 0;
              }
            });
            if (updates != 4 || events != 1)
              llvm::errs() << "legacy " << recovery << " updates=" << updates
                           << " events=" << events << '\n';
            return good && updates == 4 && events == 1;
          });
  }
  llvm::outs() << total << " flat FSM cases, " << failures << " failures\n";
  return failures ? 1 : 0;
}
