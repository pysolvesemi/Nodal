#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/HierarchyBoundaryModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/TargetCapabilities.h"

#include "llvm/Support/raw_ostream.h"

#include <string>

using namespace mlir;
namespace {
const std::string tag = "interface_abi_version = 1 : i64";
const std::string bits = "!nodal.bits<8>", boolean = "!nodal.bits<1>";
std::string model(StringRef body, StringRef header = "") {
  return "module { " + header.str() +
         "\n \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({\n" + body.str() +
         "}) : () -> () }\n";
}
std::string domain(StringRef name = "clock") {
  return "\"nodal.domain\"() <{sym_name = \"" + name.str() +
         "\", edge = \"rising\", reset_policy = \"sync\", metadata = {}}> : () -> ()\n";
}
std::string entry(StringRef path, StringRef access, StringRef role) {
  return "{path = \"" + path.str() + "\", access = \"" + access.str() + "\", leaf_role = \"" +
         role.str() + "\", nested_roles = [], nested_interfaces = []}";
}
std::string definition(StringRef type = bits, StringRef member = "data",
                       StringRef protocol = "plain") {
  std::string body;
  for (auto pair :
       {std::pair{"source", "out"}, std::pair{"sink", "in"}, std::pair{"monitor", "observe"}})
    body += "\"nodal.interface_role\"() <{sym_name = \"" + std::string(pair.first) +
            "\", kind = \"explicit\", metadata = {" + tag + ", access = [" +
            entry(member, pair.second, pair.first) + "]}}> : () -> ()\n";
  body += "\"nodal.interface_member\"() <{sym_name = \"" + member.str() +
          "\", type = " + type.str() + ", protocol = \"" + protocol.str() +
          "\", roles = [\"source\", \"sink\", \"monitor\"], metadata = {" + tag +
          "}}> : () -> ()\n";
  return "\"nodal.interface\"() <{sym_name = \"Bus\", metadata = {" + tag +
         ", semantic_identity = \"pkg.Bus\"}}> ({\n" + body + "}) : () -> ()\n";
}
std::string instance(StringRef name, StringRef role, StringRef access, StringRef type = bits,
                     StringRef dimensions = "", StringRef clock = "clock",
                     StringRef member = "data") {
  return "\"nodal.interface_instance\"() <{sym_name = \"" + name.str() +
         "\", definition = @Bus, role = \"" + role.str() + "\", metadata = {" + tag +
         ", domain = @" + clock.str() + ", dimensions = [" + dimensions.str() +
         "], semantic_path = \"Fixture." + name.str() + "\"}}> : () -> ()\n" +
         "\"nodal.interface_abi\"() <{logical_path = \"Fixture." + name.str() +
         "\", layout_policy = \"structural\", members = [{path = \"" + member.str() +
         "\", type = " + type.str() + ", role = \"" + role.str() + "\", access = \"" +
         access.str() + "\", nested_roles = [], nested_interfaces = []}], metadata = {" + tag +
         ", instance = @" + name.str() + "}}> : () -> ()\n";
}
std::string member(StringRef name, StringRef endpoint, StringRef type = bits, StringRef role = "",
                   StringRef path = "data") {
  return "%" + name.str() + " = \"nodal.member_access\"() <{instance = @" + endpoint.str() +
         ", path = \"" + path.str() + "\", metadata = {" + tag +
         (role.empty() ? "" : ", role = \"" + role.str() + "\"") + "}}> : () -> " + type.str() +
         "\n";
}
std::string literal(StringRef name, StringRef type = bits, StringRef value = "1") {
  return "%" + name.str() + " = \"nodal.const_literal\"() <{kind = \"integer\", spelling = \"" +
         value.str() + "\", value = " + value.str() + " : i64, metadata = {}}> : () -> " +
         type.str() + "\n";
}
std::string port(StringRef name, StringRef type, StringRef direction = "input",
                 StringRef clock = "clock") {
  return "\"nodal.port\"() <{sym_name = \"" + name.str() + "\", type = " + type.str() +
         ", direction = \"" + direction.str() + "\", domain = @" + clock.str() +
         ", metadata = {}}> : () -> ()\n%" + name.str() + " = \"nodal.port_value\"() <{port = @" +
         name.str() + ", metadata = {}}> : () -> " + type.str() + "\n";
}
std::string assign(StringRef type, StringRef kind = "continuous", StringRef sourceType = "") {
  return "\"nodal.digital_assign\"(%output, %input) <{kind = \"" + kind.str() +
         "\", metadata = {}}> : (" + type.str() + ", " +
         (sourceType.empty() ? type.str() : sourceType.str()) + ") -> ()\n";
}
std::string storage(StringRef type) {
  return "%wire = \"nodal.digital_storage\"() <{name = \"wire\", kind = \"wire\", metadata = {}}> "
         ": () -> " +
         type.str() + "\n";
}
std::string connect(StringRef first = "a", StringRef second = "b", StringRef type = bits) {
  return "\"nodal.interface_connect\"(%" + first.str() + ", %" + second.str() + ") <{metadata = {" +
         tag + "}}> : (" + type.str() + ", " + type.str() + ") -> ()\n";
}
std::string drive(StringRef target = "a", StringRef value = "value", StringRef type = bits) {
  return "\"nodal.interface_drive\"(%" + target.str() + ", %" + value.str() + ") <{metadata = {" +
         tag + "}}> : (" + type.str() + ", " + type.str() + ") -> ()\n";
}
std::string observe(StringRef target = "a", StringRef type = bits) {
  return "\"nodal.interface_observe\"(%" + target.str() + ") <{metadata = {" + tag + "}}> : (" +
         type.str() + ") -> ()\n";
}
std::string resolved(StringRef mode = "push_pull", StringRef type = bits) {
  return "!nodal.resolved<\"" + mode.str() + "\", " + type.str() + ">";
}
std::string net(StringRef name = "net", StringRef mode = "push_pull",
                StringRef placement = "top_level_pin",
                StringRef profile = "portable_boundary_only") {
  return "%" + name.str() + " = \"nodal.resolved_net\"() <{name = \"" + name.str() +
         "\", metadata = {" + tag + ", placement = \"" + placement.str() + "\", profile = \"" +
         profile.str() + "\"}}> : () -> " + resolved(mode) + "\n";
}
std::string driver(StringRef name = "driver", StringRef target = "net",
                   StringRef mode = "push_pull") {
  return "%" + name.str() + " = \"nodal.net_driver\"(%" + target.str() + ") <{driver_id = \"" +
         name.str() + "\", metadata = {" + tag + "}}> : (" + resolved(mode) +
         ") -> !nodal.driver<" + bits + ">\n";
}
std::string netDrive(StringRef identity = "driver", StringRef target = "net",
                     StringRef mode = "push_pull") {
  return "\"nodal.net_drive\"(%" + target.str() + ", %" + identity.str() +
         ", %value, %enable) <{mode = \"" + mode.str() + "\", metadata = {" + tag + "}}> : (" +
         resolved(mode) + ", !nodal.driver<" + bits + ">, " + bits + ", " + boolean + ") -> ()\n";
}
std::string level(StringRef active = "low", StringRef mode = "open_drain") {
  return "\"nodal.net_drive_level\"(%net, %driver, %enable) <{level = \"" + active.str() +
         "\", metadata = {" + tag + "}}> : (" + resolved(mode) + ", !nodal.driver<" + bits + ">, " +
         boolean + ") -> ()\n";
}
std::string release(StringRef identity = "release", StringRef target = "net",
                    StringRef mode = "push_pull") {
  return "\"nodal.net_release\"(%" + target.str() + ", %" + identity.str() + ") <{metadata = {" +
         tag + "}}> : (" + resolved(mode) + ", !nodal.driver<" + bits + ">) -> ()\n";
}
std::string split(StringRef target = "net", StringRef mode = "push_pull") {
  return "%write, %enabled = \"nodal.net_split\"(%" + target.str() + ") <{metadata = {" + tag +
         "}}> : (" + resolved(mode) + ") -> (" + bits + ", " + boolean + ")\n";
}
std::string through(StringRef first = "net", StringRef second = "other",
                    StringRef mode = "push_pull") {
  return "\"nodal.net_pass_through\"(%" + first.str() + ", %" + second.str() + ") <{metadata = {" +
         tag + "}}> : (" + resolved(mode) + ", " + resolved(mode) + ") -> ()\n";
}
std::string replace(std::string text, StringRef from, StringRef to) {
  auto index = text.find(from.str());
  if (index == std::string::npos)
    return text;
  text.replace(index, from.size(), to.str());
  return text;
}
std::string resolvedDefinition() {
  std::string result = definition(resolved(), "data", "resolved");
  result = replace(result, "access = \"out\"", "access = \"drive\"");
  return replace(result, "access = \"in\"", "access = \"connect\"");
}
std::string analogDefinition(bool conservative) {
  std::string result = definition(conservative ? "!nodal.terminal<\"electrical\">" : "f64", "data",
                                  conservative ? "conservative" : "signal_flow");
  if (conservative) {
    result = replace(result, "access = \"out\"", "access = \"contribute\"");
    result = replace(result, "access = \"in\"", "access = \"connect\"");
    result = replace(result, "access = \"observe\"", "access = \"sense\"");
  }
  std::string analog = conservative ? "{discipline = \"electrical\", potential_nature = "
                                      "\"voltage\", flow_nature = \"current\"}"
                                    : "{dimension = \"voltage\"}";
  std::string refs =
      conservative
          ? ", analog_refs = {discipline = @electrical, potential = @Voltage, flow = @Current}"
          : "";
  return replace(result, "roles = [\"source\", \"sink\", \"monitor\"], metadata = {" + tag,
                 "roles = [\"source\", \"sink\", \"monitor\"], metadata = {" + tag +
                     ", analog = " + analog + refs);
}
std::string analogInstance(bool conservative) {
  std::string result = instance("left", "sink", conservative ? "connect" : "in",
                                conservative ? "!nodal.terminal<\"electrical\">" : "f64");
  std::string analog = conservative ? "{discipline = \"electrical\", potential_nature = "
                                      "\"voltage\", flow_nature = \"current\"}"
                                    : "{dimension = \"voltage\"}";
  return replace(result, "nested_interfaces = []}",
                 "nested_interfaces = [], analog = " + analog + "}");
}
std::string analogOwners() {
  return "\"nodal.nature\"() <{sym_name = \"Voltage\", units = \"V\", access = \"V\", "
         "abstol = 1.0e-6 : f64, dimension = \"voltage\", metadata = {}}> : () -> ()\n"
         "\"nodal.nature\"() <{sym_name = \"Current\", units = \"A\", access = \"I\", "
         "abstol = 1.0e-12 : f64, dimension = \"current\", metadata = {}}> : () -> ()\n"
         "\"nodal.discipline\"() <{sym_name = \"electrical\", domain = \"continuous\", "
         "potential = @Voltage, flow = @Current, metadata = {}}> : () -> ()\n";
}
std::string read(StringRef target = "net", StringRef mode = "push_pull", StringRef type = bits) {
  return "%read = \"nodal.net_read\"(%" + target.str() + ") <{metadata = {" + tag + "}}> : (" +
         resolved(mode) + ") -> " + type.str() + "\n";
}
std::string streamDefinition() {
  std::string body;
  for (StringRef role : {StringRef("source"), StringRef("sink")}) {
    bool source = role == "source";
    body += "\"nodal.interface_role\"() <{sym_name = \"" + role.str() +
            "\", kind = \"explicit\", metadata = {" + tag + ", access = [" +
            entry("channel.payload", source ? "out" : "in", role) + ", " +
            entry("channel.valid", source ? "out" : "in", role) + ", " +
            entry("channel.ready", source ? "in" : "out", role) + "]}}> : () -> ()\n";
  }
  for (StringRef suffix : {StringRef("payload"), StringRef("valid"), StringRef("ready")})
    body += "\"nodal.interface_member\"() <{sym_name = \"channel." + suffix.str() +
            "\", type = " + (suffix == "payload" ? bits : boolean) +
            ", protocol = \"stream\", roles = [\"source\", \"sink\"], metadata = {" + tag +
            "}}> : () -> ()\n";
  return "\"nodal.interface\"() <{sym_name = \"Stream\", metadata = {" + tag +
         ", semantic_identity = \"pkg.Stream\"}}> ({" + body + "}) : () -> ()\n";
}
std::string nestedFixture() {
  std::string child = replace(definition(), "sym_name = \"Bus\"", "sym_name = \"Child\"");
  child = replace(child, "semantic_identity = \"pkg.Bus\"", "semantic_identity = \"pkg.Child\"");
  std::string parent = definition(bits, "nested.data");
  for (StringRef role : {StringRef("source"), StringRef("sink"), StringRef("monitor")}) {
    std::string from =
        "leaf_role = \"" + role.str() + "\", nested_roles = [], nested_interfaces = []";
    std::string to = "leaf_role = \"" + role.str() +
                     "\", nested_roles = [{path = \"nested\", role = \"" + role.str() +
                     "\"}], nested_interfaces = [{path = \"nested\", definition = \"pkg.Child\"}]";
    parent = replace(parent, from, to);
  }
  std::string endpoint = instance("left", "source", "out", bits, "", "clock", "nested.data");
  endpoint = replace(endpoint, "nested_roles = [], nested_interfaces = []",
                     "nested_roles = [{path = \"nested\", role = \"source\"}], "
                     "nested_interfaces = [{path = \"nested\", definition = \"pkg.Child\"}]");
  return domain() + child + parent + endpoint + member("a", "left", bits, "", "nested.data");
}
std::string parameter(bool bounded = true) {
  std::string result =
      "\"nodal.parameter\"() <{sym_name = \"N\", type = i64, default_value = 2 : i64, "
      "parameter_kind = \"integer\", classification = \"structural\", "
      "variability = \"symbolic\", metadata = {}}> : () -> ()\n";
  if (bounded)
    result += literal("low", "i64", "1") + literal("high", "i64", "4") +
              "\"nodal.parameter_constraint\"(%low, %high) <{parameter = @N, "
              "constraint_kind = \"range\", lower_inclusive = true, upper_inclusive = true, "
              "metadata = {}}> : (i64, i64) -> ()\n";
  return result + "\"nodal.parameter_envelope\"() <{parameter = @N, policy = \"static_generate\", "
                  "effects = [\"shape\", \"width\"], metadata = {}}> : () -> ()\n";
}
std::string generatedInterface(StringRef body, StringRef upper = "3", StringRef name = "family",
                               StringRef lower = "0", StringRef step = "1") {
  int64_t lo = std::stoll(lower.str()), hi = std::stoll(upper.str()),
          stride = std::stoll(step.str());
  int64_t count = hi <= lo ? 0 : (hi - lo + stride - 1) / stride;
  return literal((name + "_lo").str(), "i64", lower) + literal((name + "_hi").str(), "i64", upper) +
         literal((name + "_step").str(), "i64", step) + "%" + name.str() +
         " = \"nodal.generate\"(%" + name.str() + "_lo, %" + name.str() + "_hi, %" + name.str() +
         "_step) <{induction = \"i\", region_id = \"Fixture." + name.str() +
         "\", induction_path = \"Fixture." + name.str() +
         ".i\", metadata = {maximum_trip_count = " + std::to_string(count) + " : i64}}> ({^bb0(%" +
         name.str() + "_i: i64):\n" + body.str() +
         "}) : (i64, i64, i64) -> !nodal.generated_family\n";
}
std::string generatedInterfaceExport(StringRef endpoint = "inside", StringRef key = "endpoint",
                                     StringRef kind = "interface") {
  return "\"nodal.generated_export\"() <{member = \"" + key.str() +
         "\", access = \"read\", "
         "kind = \"" +
         kind.str() + "\", target = @" + endpoint.str() + ", metadata = {}}> : () -> ()\n";
}
std::string selectedInterface(StringRef name = "selected", StringRef ordinal = "ordinal",
                              StringRef family = "family", StringRef key = "endpoint",
                              StringRef endpoint = "inside", StringRef extra = "") {
  return "\"nodal.interface_boundary\"(%" + family.str() + ", %" + ordinal.str() +
         ") <{sym_name = \"" + name.str() + "\", endpoint = \"" + endpoint.str() +
         "\", member = \"" + key.str() + "\", " + extra.str() + "metadata = {" + tag +
         ", semantic_path = \"Fixture." + name.str() +
         "\", dimensions = []}}> : (!nodal.generated_family, i64) -> ()\n";
}
std::string print(Operation *operation) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  operation->print(stream);
  return result;
}
std::string hierarchyDomain(StringRef name) {
  std::string n = name.str(), clock = "!nodal.capability<\"clock\">";
  return replace(domain(name), "reset_policy = \"sync\"", "reset_policy = \"none\"") +
         port("clock_" + n, clock, "input", name) + "\"nodal.local_domain_binding\"(%clock_" + n +
         ") <{domain = @" + n + ", reset_polarity = \"active_high\", metadata = {}}> : (" + clock +
         ") -> ()\n";
}
std::string hierarchyRequirement(StringRef name) {
  return "\"nodal.domain_requirement\"() <{sym_name = \"" + name.str() +
         "\", metadata = {}}> : () -> ()\n%" + name.str() +
         "_clock = \"nodal.requirement_clock\"() <{requirement = @" + name.str() +
         ", metadata = {}}> : () -> !nodal.capability<\"clock\">\n";
}
std::string hierarchyBinding(StringRef required, StringRef actual) {
  return "\"nodal.instance_domain_binding\"(%clock_" + actual.str() +
         ") <{instance = @child, requirement = @" + required.str() + ", actual = @" + actual.str() +
         ", origin = \"named\", metadata = {}}> : (!nodal.capability<\"clock\">) -> ()\n";
}
std::string hierarchyBoundary() {
  return "\"nodal.interface_boundary\"() <{sym_name = \"projected\", instance = @child, "
         "endpoint = \"exported\", metadata = {" +
         tag + ", semantic_path = \"Top.projected\"}}> : () -> ()\n";
}
std::string hierarchyFixture(bool forged = false, bool input = false) {
  const std::string selected = input ? "sink" : "source", permission = input ? "in" : "out";
  std::string top =
      hierarchyDomain("a") + hierarchyDomain("b") + port("input", boolean, "input", "a") +
      definition(boolean) + instance("top", selected, permission, boolean, "", "b") +
      "\"nodal.instance\"() <{sym_name = \"child\", module = @Child, "
      "parameter_bindings = {}, domain_bindings = {src = @a, dst = @b}, "
      "metadata = {}}> : () -> ()\n" +
      hierarchyBinding("src", "a") + hierarchyBinding("dst", "b") +
      "%child_in = \"nodal.instance_port\"() <{instance = @child, port = \"input\", "
      "direction = \"input\", metadata = {}}> : () -> " +
      boolean + "\n" +
      "\"nodal.digital_assign\"(%child_in, %input) <{kind = \"continuous\", metadata = {}}> : (" +
      boolean + ", " + boolean + ") -> ()\n" + hierarchyBoundary() +
      member("projected", "projected", boolean) + member("top", "top", boolean) +
      connect("top", "projected", boolean);
  if (!input)
    top += "%q = \"nodal.digital_storage\"() <{name = \"q\", kind = \"register\", domain = @b, "
           "metadata = {}}> : () -> " +
           boolean +
           "\n\"nodal.digital_assign\"(%q, %projected) <{kind = \"register_next\", metadata = {}}> "
           ": (" +
           boolean + ", " + boolean + ") -> ()\n";
  std::string child = hierarchyRequirement("src") + hierarchyRequirement("dst") +
                      port("input", boolean, "input", "src") + definition(boolean) +
                      instance("exported", selected, permission, boolean, "", "dst") +
                      member("exported", "exported", boolean);
  if (input)
    child += observe("exported", boolean);
  else {
    child +=
        "%synced = \"nodal.crossing\"(%input) <{source_domain = @src, destination_domain = @dst, "
        "kind = \"sync\", contract = \"level_v1\", stages = 2 : i64, metadata = "
        "{semantic_path = \"Child.sync\"}}> : (" +
        boolean + ") -> " + boolean + "\n";
    child += drive("exported", forged ? "input" : "synced", boolean);
  }
  auto module = [](StringRef name, const std::string &body) {
    return "\"nodal.module\"() <{sym_name = \"" + name.str() + "\", metadata = {}}> ({\n" + body +
           "}) : () -> ()\n";
  };
  return "module attributes {nodal.root.module = @Top} {\n" + module("Top", top) +
         module("Child", child) + "}\n";
}
std::string hierarchyResolvedFixture(StringRef parentEffects = "", StringRef childEffects = "",
                                     StringRef role = "sink", bool parentAccess = true,
                                     bool childAccess = true, bool sibling = false) {
  std::string permission = role == "source" ? "drive" : role == "monitor" ? "observe" : "connect";
  std::string top =
      hierarchyDomain("clock") + hierarchyDomain("other") + resolvedDefinition() +
      "\"nodal.instance\"() <{sym_name = \"child\", module = @Child, parameter_bindings = {}, "
      "domain_bindings = {required = @clock}, metadata = {}}> : () -> ()\n" +
      hierarchyBinding("required", "clock") + hierarchyBoundary();
  if (parentAccess)
    top += member("projected", "projected", resolved());
  top += parentEffects.str();
  if (sibling) {
    top +=
        "\"nodal.instance\"() <{sym_name = \"sibling\", module = @Child, parameter_bindings = {}, "
        "domain_bindings = {required = @clock}, metadata = {}}> : () -> ()\n";
    top +=
        replace(hierarchyBinding("required", "clock"), "instance = @child", "instance = @sibling");
    std::string boundary =
        replace(hierarchyBoundary(), "sym_name = \"projected\"", "sym_name = \"sibling_view\"");
    boundary = replace(boundary, "instance = @child", "instance = @sibling");
    top += replace(boundary, "Top.projected", "Top.sibling_view");
    top += member("sibling_view", "sibling_view", resolved());
  }
  std::string child = hierarchyRequirement("required") + resolvedDefinition() +
                      instance("exported", role, permission, resolved(), "", "required");
  if (childAccess)
    child += member("exported", "exported", resolved());
  child += childEffects.str();
  return "module attributes {nodal.root.module = @Top} {\n"
         "\"nodal.module\"() <{sym_name = \"Top\", metadata = {}}> ({\n" +
         top + "}) : () -> ()\n\"nodal.module\"() <{sym_name = \"Child\", metadata = {}}> ({\n" +
         child + "}) : () -> ()\n}\n";
}
std::string standaloneBoundary(StringRef value = "projected", StringRef instance = "child",
                               StringRef endpoint = "exported", StringRef type = bits) {
  return "%" + value.str() + " = \"nodal.net_boundary\"() <{instance = @" + instance.str() +
         ", endpoint = \"" + endpoint.str() + "\", metadata = {" + tag +
         ", semantic_path = \"Top." + value.str() +
         "\", placement = \"top_level_pin\", profile = \"portable_boundary_only\"}}> : () -> " +
         resolved("push_pull", type) + "\n";
}
std::string asStandaloneBoundary(std::string source) {
  source = replace(source, resolvedDefinition(), "");
  source = replace(source, resolvedDefinition(), "");
  source = replace(source, hierarchyBoundary(), standaloneBoundary());
  source = replace(source, member("projected", "projected", resolved()), "");
  source = replace(source, instance("exported", "sink", "connect", resolved(), "", "required"),
                   net("exported"));
  return replace(source, member("exported", "exported", resolved()), "");
}
std::string standaloneBoundaryFixture(StringRef parentEffects = "", StringRef childEffects = "") {
  return asStandaloneBoundary(hierarchyResolvedFixture(parentEffects, childEffects));
}
std::string contextualSplit(StringRef target = "net", StringRef mode = "push_pull") {
  return replace(split(target, mode), "metadata =", "hierarchy_driver = unit, metadata =");
}
std::string bareModule(StringRef name, StringRef body) {
  return "\"nodal.module\"() <{sym_name = \"" + name.str() + "\", metadata = {}}> ({\n" +
         body.str() + "}) : () -> ()\n";
}
std::string bareInstance(StringRef name = "child", StringRef target = "Child") {
  return "\"nodal.instance\"() <{sym_name = \"" + name.str() + "\", module = @" + target.str() +
         ", parameter_bindings = {}, domain_bindings = {}, metadata = {}}> : () -> ()\n";
}
std::string bareSplitFixture(StringRef parentEffects = "", StringRef childEffects = "",
                             bool sibling = false) {
  std::string top = bareInstance() + standaloneBoundary();
  if (sibling)
    top += bareInstance("sibling") + standaloneBoundary("sibling_view", "sibling");
  return "module attributes {nodal.root.module = @Top} {\n" +
         bareModule("Top", top + parentEffects.str()) +
         bareModule("Child", net("exported") + childEffects.str()) + "}\n";
}
std::string externalModule(StringRef name = "Child", StringRef physical = "ExternalPad",
                           StringRef body = "") {
  auto result =
      bareModule(name, body.empty() ? net("exported", "push_pull", "black_box_pin") : body.str());
  return replace(result, "metadata = {}",
                 "metadata = {}, external_name = \"" + physical.str() + "\"");
}
std::string externalFixture(StringRef parentEffects = "", StringRef declarations = "") {
  auto boundary = replace(standaloneBoundary(), "placement = \"top_level_pin\"",
                          "placement = \"black_box_pin\"");
  return "module attributes {nodal.root.module = @Top} {\n" +
         bareModule("Top", bareInstance() + boundary + parentEffects.str()) +
         (declarations.empty() ? externalModule() : declarations.str()) + "}\n";
}
std::string symbolicStandaloneBoundaryFixture(bool wrong = false) {
  std::string parentParameter = parameter();
  parentParameter = replace(parentParameter, "sym_name = \"N\"", "sym_name = \"P\"");
  while (parentParameter.find("parameter = @N") != std::string::npos)
    parentParameter = replace(parentParameter, "parameter = @N", "parameter = @P");
  std::string source = standaloneBoundaryFixture();
  source = replace(
      source, hierarchyDomain("clock"),
      hierarchyDomain("clock") + parentParameter +
          "%p = \"nodal.const_parameter_ref\"() <{parameter = @P, metadata = {}}> : () -> i64\n");
  source = replace(source, hierarchyBinding("required", "clock"),
                   hierarchyBinding("required", "clock") +
                       "\"nodal.parameter_override\"(%p) <{instance = @child, parameter = @N, "
                       "metadata = {}}> : (i64) -> ()\n");
  source = replace(source, hierarchyRequirement("required"),
                   hierarchyRequirement("required") + parameter());
  source =
      replace(source, standaloneBoundary(),
              standaloneBoundary("projected", "child", "exported",
                                 wrong ? "!nodal.uint<2>" : "!nodal.digital<\"uint\", \"P\">"));
  source = replace(source, net("exported"),
                   replace(net("exported"), bits, "!nodal.digital<\"uint\", \"N\">"));
  return source;
}
std::string hierarchyArrayFixture(StringRef actual = "P", bool symbolic = true) {
  const std::string formal = symbolic ? "N" : "2";
  const std::string selected = symbolic ? actual.str() : "2";
  const std::string parentType = "!nodal.shaped<\"" + selected + "\", " + boolean + ">";
  const std::string childType = "!nodal.shaped<\"" + formal + "\", " + boolean + ">";
  std::string parameters;
  if (symbolic) {
    auto renamedParameter = [](StringRef name) {
      std::string result = parameter();
      for (auto [from, to] : {std::pair{std::string("N"), name.str()},
                              std::pair{std::string("%low"), "%" + name.str() + "_low"},
                              std::pair{std::string("%high"), "%" + name.str() + "_high"}}) {
        size_t position = 0;
        while ((position = result.find(from, position)) != std::string::npos) {
          result.replace(position, from.size(), to);
          position += to.size();
        }
      }
      return result;
    };
    parameters =
        renamedParameter("P") + renamedParameter("Q") +
        "%p = \"nodal.const_parameter_ref\"() <{parameter = @P, metadata = {}}> : () -> i64\n";
  }
  std::string top =
      hierarchyDomain("clock") + parameters + definition(boolean) +
      instance("top", "sink", "in", boolean, "\"" + selected + "\"") +
      "\"nodal.instance\"() <{sym_name = \"child\", module = @Child, parameter_bindings = {}, "
      "domain_bindings = {required = @clock}, metadata = {}}> : () -> ()\n" +
      (symbolic ? "\"nodal.parameter_override\"(%p) <{instance = @child, parameter = @N, metadata "
                  "= {}}> : (i64) -> ()\n"
                : "") +
      hierarchyBinding("required", "clock") +
      replace(hierarchyBoundary(), "semantic_path = \"Top.projected\"",
              "semantic_path = \"Top.projected\", dimensions = [\"" + selected + "\"]") +
      member("projected", "projected", parentType) + member("top", "top", parentType) +
      connect("top", "projected", parentType);
  std::string child =
      hierarchyRequirement("required") + (symbolic ? parameter() : "") + definition(boolean) +
      instance("exported", "sink", "in", boolean, "\"" + formal + "\"", "required") +
      member("exported", "exported", childType) + observe("exported", childType);
  return "module attributes {nodal.root.module = @Top} {\n\"nodal.module\"() <{sym_name = \"Top\", "
         "metadata = {}}> ({\n" +
         top + "}) : () -> ()\n\"nodal.module\"() <{sym_name = \"Child\", metadata = {}}> ({\n" +
         child + "}) : () -> ()\n}\n";
}
std::string hierarchyPayloadFixture(StringRef actual = "P", bool access = true,
                                    StringRef siblingBinding = "") {
  std::string source = hierarchyArrayFixture();
  const std::string childMarker = "\"nodal.module\"() <{sym_name = \"Child\"";
  size_t childStart = source.find(childMarker);
  std::string top = source.substr(0, childStart), child = source.substr(childStart);
  auto replaceAll = [](std::string text, StringRef from, StringRef to) {
    size_t position = 0;
    while ((position = text.find(from.str(), position)) != std::string::npos) {
      text.replace(position, from.size(), to.str());
      position += to.size();
    }
    return text;
  };
  const std::string parentPayload = "!nodal.digital<\"uint\", \"P\">";
  const std::string childPayload = "!nodal.digital<\"uint\", \"N\">";
  if (!access) {
    const std::string parentType = "!nodal.shaped<\"P\", " + boolean + ">";
    const std::string childType = "!nodal.shaped<\"N\", " + boolean + ">";
    top = replace(top, member("projected", "projected", parentType), "");
    top = replace(top, member("top", "top", parentType), "");
    top = replace(top, connect("top", "projected", parentType), "");
    child = replace(child, member("exported", "exported", childType), "");
    child = replace(child, observe("exported", childType), "");
  }
  top = replaceAll(top, boolean, parentPayload);
  child = replaceAll(child, boolean, childPayload);
  top = replace(top, "semantic_path = \"Top.projected\", dimensions = [\"P\"]",
                "semantic_path = \"Top.projected\", dimensions = [\"P\"], actual_types = "
                "[{path = \"data\", type = !nodal.digital<\"uint\", \"" +
                    actual.str() + "\">}]");
  if (!siblingBinding.empty()) {
    std::string extra =
        "%sibling_actual = \"nodal.const_parameter_ref\"() <{parameter = @" + siblingBinding.str() +
        ", metadata = {}}> : () -> i64\n" +
        "\"nodal.instance\"() <{sym_name = \"other\", module = @Child, parameter_bindings = {}, "
        "domain_bindings = {required = @clock}, metadata = {}}> : () -> ()\n" +
        "\"nodal.parameter_override\"(%sibling_actual) <{instance = @other, parameter = @N, "
        "metadata = {}}> : (i64) -> ()\n" +
        replace(hierarchyBinding("required", "clock"), "instance = @child", "instance = @other") +
        "\"nodal.interface_boundary\"() <{sym_name = \"other_projected\", instance = @other, "
        "endpoint = \"exported\", metadata = {" +
        tag + ", semantic_path = \"Top.other_projected\", dimensions = [\"" + siblingBinding.str() +
        "\"], actual_types = [{path = \"data\", type = !nodal.digital<\"uint\", \"" +
        siblingBinding.str() + "\">}]}}> : () -> ()\n";
    const size_t close = top.rfind("}) : () -> ()");
    top.insert(close, extra);
  }
  return top + child;
}
std::string repeatedPayloadFixture(StringRef siblingBinding = "P", bool forgedLast = false) {
  std::string source = hierarchyPayloadFixture("P", false, siblingBinding);
  auto replaceAll = [&](StringRef from, StringRef to) {
    size_t position = 0;
    while ((position = source.find(from.str(), position)) != std::string::npos) {
      source.replace(position, from.size(), to.str());
      position += to.size();
    }
  };
  auto repeat = [](StringRef fragment, StringRef separator) {
    std::string result;
    for (unsigned index = 0; index < 16; ++index) {
      if (index)
        result += separator.str();
      result += replace(fragment.str(), "\"data\"", "\"data" + std::to_string(index) + "\"");
    }
    return result;
  };
  auto payload = [](StringRef width) {
    std::string fields;
    for (unsigned index = 0; index < 16; ++index) {
      if (index)
        fields += ", ";
      fields += "{name = \"field" + std::to_string(index) +
                "\", type = !nodal.shaped<\"2\", !nodal.digital<\"uint\", \"" + width.str() +
                "\">>}";
    }
    return "!nodal.struct<\"Repeated\", [" + fields + "]>";
  };
  for (auto role :
       {std::pair{"source", "out"}, std::pair{"sink", "in"}, std::pair{"monitor", "observe"}}) {
    auto original = entry("data", role.second, role.first);
    replaceAll("access = [" + original + "]", "access = [" + repeat(original, ", ") + "]");
  }
  for (StringRef width : {StringRef("P"), StringRef("Q"), StringRef("N")}) {
    std::string type = "!nodal.digital<\"uint\", \"" + width.str() + "\">";
    std::string declaration =
        "\"nodal.interface_member\"() <{sym_name = \"data\", type = " + type +
        ", protocol = \"plain\", roles = [\"source\", \"sink\", \"monitor\"], metadata = {" + tag +
        "}}> : () -> ()";
    replaceAll(declaration, repeat(declaration, "\n"));
    std::string abi = "{path = \"data\", type = " + type +
                      ", role = \"sink\", access = \"in\", nested_roles = [], nested_interfaces "
                      "= []}";
    replaceAll("members = [" + abi + "]", "members = [" + repeat(abi, ", ") + "]");
    std::string actual = "{path = \"data\", type = " + type + "}";
    replaceAll("actual_types = [" + actual + "]", "actual_types = [" + repeat(actual, ", ") + "]");
    replaceAll(type, payload(width));
  }
  if (forgedLast) {
    std::string actual = "{path = \"data\", type = " + payload("P") + "}";
    std::string inventory = repeat(actual, ", ");
    std::string changed = replace(inventory, "{path = \"data15\", type = " + payload("P") + "}",
                                  "{path = \"data15\", type = " + payload("Q") + "}");
    source =
        replace(source, "actual_types = [" + inventory + "]", "actual_types = [" + changed + "]");
  }
  return source;
}
std::string hiddenDefinition(StringRef hiddenType = boolean, bool changedRole = false) {
  std::string result = definition(boolean);
  for (auto pair :
       {std::pair{"source", "out"}, std::pair{"sink", "in"}, std::pair{"monitor", "observe"}}) {
    auto original = entry("data", pair.second, pair.first);
    auto hidden =
        entry("hidden", changedRole && StringRef(pair.first) == "source" ? "in" : pair.second,
              pair.first);
    result = replace(result, "access = [" + original + "]",
                     "access = [" + original + ", " + hidden + "]");
  }
  return replace(
      result, "}) : () -> ()",
      "\"nodal.interface_member\"() <{sym_name = \"hidden\", type = " + hiddenType.str() +
          ", protocol = \"plain\", roles = [\"source\", \"sink\", \"monitor\"], metadata = {" +
          tag + "}}> : () -> ()\n}) : () -> ()");
}
std::string hiddenInstance(StringRef name, StringRef clock, StringRef hiddenType = boolean,
                           bool changedRole = false) {
  auto result = instance(name, "source", "out", boolean, "", clock);
  return replace(result, "nested_interfaces = []}], metadata",
                 "nested_interfaces = []}, {path = \"hidden\", type = " + hiddenType.str() +
                     ", role = \"source\", access = \"" + (changedRole ? "in" : "out") +
                     "\", nested_roles = [], nested_interfaces = []}], metadata");
}
std::string hiddenFixture(StringRef childType = boolean, bool changedRole = false) {
  auto source = replace(hierarchyFixture(), definition(boolean), hiddenDefinition());
  source = replace(source, definition(boolean), hiddenDefinition(childType, changedRole));
  source = replace(source, instance("top", "source", "out", boolean, "", "b"),
                   hiddenInstance("top", "b"));
  return replace(source, instance("exported", "source", "out", boolean, "", "dst"),
                 hiddenInstance("exported", "dst", childType, changedRole));
}
std::string siblingHiddenFixture(bool mismatch) {
  auto source = hiddenFixture();
  source = replace(source, hiddenDefinition(), "");
  source = replace(source, hiddenInstance("top", "b"), "");
  source = replace(source, member("top", "top", boolean), "");
  source = replace(source, connect("top", "projected", boolean), "");
  std::string extra =
      "\"nodal.instance\"() <{sym_name = \"other\", module = @Other, parameter_bindings = {}, "
      "domain_bindings = {src = @a, dst = @b}, metadata = {}}> : () -> ()\n" +
      replace(hierarchyBinding("src", "a"), "instance = @child", "instance = @other") +
      replace(hierarchyBinding("dst", "b"), "instance = @child", "instance = @other") +
      "%other_in = \"nodal.instance_port\"() <{instance = @other, port = \"input\", "
      "direction = \"input\", metadata = {}}> : () -> " +
      boolean + "\n" +
      "\"nodal.digital_assign\"(%other_in, %input) <{kind = \"continuous\", metadata = {}}> : (" +
      boolean + ", " + boolean + ") -> ()\n" +
      replace(replace(hierarchyBoundary(), "sym_name = \"projected\"",
                      "sym_name = \"other_projection\""),
              "instance = @child", "instance = @other");
  source = replace(source, hierarchyBoundary(), hierarchyBoundary() + extra);
  const std::string childStart = "\"nodal.module\"() <{sym_name = \"Child\"";
  auto offset = source.find(childStart);
  auto copied = source.substr(offset, source.size() - offset - 2);
  copied = replace(copied, "sym_name = \"Child\"", "sym_name = \"Other\"");
  copied = replace(copied, "semantic_path = \"Child.sync\"", "semantic_path = \"Other.sync\"");
  if (mismatch) {
    copied = replace(copied, hiddenDefinition(), hiddenDefinition(bits));
    copied =
        replace(copied, hiddenInstance("exported", "dst"), hiddenInstance("exported", "dst", bits));
  }
  return source.substr(0, source.size() - 2) + copied + "}\n";
}
std::string importedRoleDefinition(bool mismatch) {
  auto source = hiddenDefinition();
  const std::string oldRoles = "roles = [\"source\", \"sink\", \"monitor\"]";
  const std::string newRoles = "roles = [\"source\", \"sink\", \"monitor\", \"audit\"]";
  source = replace(replace(source, oldRoles, newRoles), oldRoles, newRoles);
  return replace(source, "}) : () -> ()",
                 "\"nodal.interface_role\"() <{sym_name = \"audit\", kind = \"explicit\", "
                 "metadata = {" +
                     tag + ", access = [" + entry("data", "out", "audit") + ", " +
                     entry("hidden", mismatch ? "in" : "out", "audit") +
                     "]}}> : () -> ()\n}) : () -> ()");
}
std::string siblingRoleFixture(bool mismatch) {
  auto source = siblingHiddenFixture(false);
  source = replace(source, hiddenDefinition(), importedRoleDefinition(false));
  source = replace(source, hiddenDefinition(), importedRoleDefinition(mismatch));
  // The first canonical definition does not contain audit. Both siblings do,
  // so comparing only against that first definition misses their disagreement.
  return replace(source, hierarchyDomain("a"), hierarchyDomain("a") + hiddenDefinition());
}
} // namespace

int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  unsigned cases = 0, failures = 0;
  std::string diagnosticText;
  ScopedDiagnosticHandler diagnostics(&context, [&](Diagnostic &diagnostic) {
    llvm::raw_string_ostream stream(diagnosticText);
    diagnostic.print(stream);
    stream << '\n';
    return success();
  });
  auto check = [&](StringRef name, const std::string &body, bool expected, StringRef header = "",
                   StringRef expectedDiagnostic = "") {
    ++cases;
    diagnosticText.clear();
    auto parsed =
        parseSourceString<mlir::ModuleOp>(model(body, header), ParserConfig(&context, false));
    bool valid = false, unchanged = true, roundtrip = true;
    if (parsed) {
      std::string before = print(parsed->getOperation());
      valid = succeeded(verify(parsed->getOperation()));
      unchanged = before == print(parsed->getOperation());
      if (valid) {
        auto again = parseSourceString<mlir::ModuleOp>(before, ParserConfig(&context, false));
        roundtrip = again && succeeded(verify(again->getOperation())) &&
                    before == print(again->getOperation());
      }
    }
    bool passed =
        valid == expected && unchanged && roundtrip &&
        (expectedDiagnostic.empty() || StringRef(diagnosticText).contains(expectedDiagnostic));
    failures += !passed;
    if (!passed)
      llvm::errs() << diagnosticText;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  const std::string endpoints =
      domain() + definition() + instance("left", "source", "out") + instance("right", "sink", "in");
  const std::string pair = endpoints + member("a", "left") + member("b", "right");
  check("exact-role-connect", pair + connect(), true);
  check("drive-and-observe", pair + literal("value") + drive() + observe(), true);
  check("direct-member-drive-same-domain", pair + drive("a", "b"), true);
  const std::string widthA = "!nodal.digital<\"uint\", \"Fixture.widthA\">";
  const std::string widthB = "!nodal.digital<\"uint\", \"Fixture.widthB\">";
  const std::string widthRoots =
      parameter() +
      "%n = \"nodal.const_parameter_ref\"() <{parameter = @N, metadata = {}}> : () -> i64\n" +
      literal("one", "i64", "1") +
      "%wa = \"nodal.const_expr\"(%n, %one) <{operator_name = \"add\", metadata = "
      "{semantic_path = \"Fixture.widthA\"}}> : (i64, i64) -> i64\n"
      "%wb = \"nodal.const_expr\"(%n, %one) <{operator_name = \"add\", metadata = "
      "{semantic_path = \"Fixture.widthB\"}}> : (i64, i64) -> i64\n";
  const std::string equivalentPayload = domain() + widthRoots + definition(widthA) +
                                        instance("left", "source", "out", widthA) +
                                        member("a", "left", widthA) + port("value", widthB);
  const std::string equivalentDrive = "\"nodal.interface_drive\"(%a, %value) <{metadata = {" + tag +
                                      "}}> : (" + widthA + ", " + widthB + ") -> ()\n";
  check("drive-canonical-symbolic-payload", equivalentPayload + equivalentDrive, true);
  const std::string typedNet = resolved("push_pull", widthA);
  const std::string typedDriver = "!nodal.driver<" + widthA + ">";
  const std::string netPayload =
      domain() + widthRoots + port("value", widthB) + literal("enable", boolean) +
      "%net = \"nodal.resolved_net\"() <{name = \"net\", metadata = {" + tag +
      ", placement = \"top_level_pin\", profile = \"portable_boundary_only\"}}> : () -> " +
      typedNet + "\n" +
      "%driver = \"nodal.net_driver\"(%net) <{driver_id = \"driver\", metadata = {" + tag +
      "}}> : (" + typedNet + ") -> " + typedDriver + "\n" +
      "\"nodal.net_drive\"(%net, %driver, %value, %enable) <{mode = \"push_pull\", metadata = {" +
      tag + "}}> : (" + typedNet + ", " + typedDriver + ", " + widthB + ", " + boolean +
      ") -> ()\n";
  check("net-drive-canonical-symbolic-payload", netPayload, true);

  check(
      "drive-distinct-symbolic-payload",
      replace(equivalentPayload,
              "%n, %one) <{operator_name = \"add\", metadata = {semantic_path = \"Fixture.widthB",
              "%one, %n) <{operator_name = \"add\", metadata = {semantic_path = \"Fixture.widthB") +
          equivalentDrive,
      false);

  check("direct-member-drive-domain-mismatch",
        domain("otherClock") + replace(pair, "domain = @clock", "domain = @otherClock") +
            drive("a", "b"),
        false);
  check("monitor-view", endpoints + member("a", "left", bits, "monitor") + observe(), true);
  check("inverted-view", endpoints + member("a", "left", bits, "sink") + observe(), true);
  check("missing-definition", replace(pair, "definition = @Bus", "definition = @Missing"), false);
  check("unknown-role", replace(pair, "role = \"source\"", "role = \"unknown\""), false);
  check("unknown-member",
        replace(pair, "path = \"data\", metadata", "path = \"unknown\", metadata"), false);
  check("missing-actual-role-map",
        replace(pair, "access = [{path = \"data\"", "access = [{path = \"unknown\""), false);
  check("forged-access-result", endpoints + member("a", "left", "!nodal.uint<8>"), false);
  check("forged-abi-type",
        replace(pair, "members = [{path = \"data\", type = !nodal.bits<8>",
                "members = [{path = \"data\", type = !nodal.uint<8>"),
        false);
  check("missing-owned-domain", replace(pair, "domain = @clock", "domain = @missing"), false);
  check("connect-domain-mismatch",
        domain("otherClock") + replace(pair, "domain = @clock", "domain = @otherClock") + connect(),
        false);
  check("noncomplementary-connect",
        endpoints + member("a", "left") + member("b", "right", bits, "source") + connect(), false);
  check("duplicate-ordinary-driver", pair + connect() + connect(), false);
  check("monitor-cannot-drive",
        endpoints + member("a", "left", bits, "monitor") + literal("value") + drive(), false);
  check("input-cannot-drive", pair + literal("value") + drive("b"), false);
  check("typed-connect-rejects-plain-values", literal("a") + literal("b") + connect(), false);
  check("missing-typed-effect-marker", pair + replace(connect(), tag, ""), false);
  check("missing-typed-member-marker", endpoints + replace(member("a", "left"), tag, ""), false);
  check("missing-typed-instance-marker",
        domain() + definition() + replace(instance("left", "source", "out"), tag, "") +
            member("a", "left"),
        false);
  check("unsupported-version",
        replace(pair, "interface_abi_version = 1", "interface_abi_version = 2"), false);
  check("malformed-access-zero-results",
        endpoints + replace(replace(member("a", "left"), "%a = ", ""), ": () -> !nodal.bits<8>",
                            ": () -> ()"),
        false);
  const std::string shape = "!nodal.shaped<\"2,3\", " + bits + ">";
  check("structural-interface-array",
        domain() + definition() + instance("left", "source", "out", bits, "\"2\", \"3\"") +
            member("a", "left", shape),
        true);
  check("array-shape-order",
        domain() + definition() + instance("left", "source", "out", bits, "\"2\", \"3\"") +
            member("a", "left", "!nodal.shaped<\"3,2\", " + bits + ">"),
        false);
  check("array-invalid-dimension",
        domain() + definition() + instance("left", "source", "out", bits, "\"0\""), false);
  check("array-symbolic-missing-owner",
        domain() + definition() + instance("left", "source", "out", bits, "\"N\""), false);
  check("array-footprint-overflow",
        domain() + definition() +
            instance("left", "source", "out", bits, "\"9223372036854775807\""),
        false);
  check("complete-stream-role-map", streamDefinition(), true);
  check("stream-ready-complement",
        replace(streamDefinition(), "path = \"channel.ready\", access = \"in\"",
                "path = \"channel.ready\", access = \"out\""),
        false);
  check("stream-control-bool",
        replace(streamDefinition(), "sym_name = \"channel.valid\", type = !nodal.bits<1>",
                "sym_name = \"channel.valid\", type = !nodal.uint<1>"),
        false);
  check("actual-nested-role-definition", nestedFixture() + observe(), true);
  check("missing-nested-definition",
        replace(nestedFixture(), "definition = \"pkg.Child\"", "definition = \"pkg.Missing\""),
        false);
  check("missing-nested-role",
        replace(nestedFixture(), "path = \"nested\", role = \"source\"",
                "path = \"nested\", role = \"unknown\""),
        false);
  check("nested-permission-forgery",
        replace(nestedFixture(), "path = \"nested.data\", access = \"out\"",
                "path = \"nested.data\", access = \"in\""),
        false);
  const std::string symbolic = "!nodal.digital<\"uint\", \"N\">";
  check("owned-symbolic-interface-array",
        domain() + parameter() + definition(symbolic) +
            instance("left", "source", "out", symbolic, "\"N\", \"2\"") +
            member("a", "left", "!nodal.shaped<\"N,2\", " + symbolic + ">"),
        true);
  check("malformed-symbolic-bound-missing-value",
        domain() + replace(parameter(), "value = 1 : i64, ", "") + definition(symbolic) +
            instance("left", "source", "out", symbolic),
        false);
  check("malformed-ordinary-literal-missing-value",
        replace(literal("malformed", "i64"), "value = 1 : i64, ", ""), false);
  check("symbolic-width-no-default-substitution",
        domain() + parameter(false) + definition(symbolic) +
            instance("left", "source", "out", symbolic),
        false);
  check("billion-lane-structural-array",
        domain() + definition() + instance("left", "source", "out", bits, "\"1000000000\"") +
            member("a", "left", "!nodal.shaped<\"1000000000\", " + bits + ">"),
        true);
  const std::string resolvedEndpoints =
      domain() + domain("otherClock") + resolvedDefinition() +
      instance("left", "sink", "connect", resolved()) +
      instance("right", "sink", "connect", resolved(), "", "otherClock") +
      member("a", "left", resolved()) + member("b", "right", resolved());
  check("resolved-transitive-domain-mismatch",
        resolvedEndpoints + net() + through("a", "net") + through("net", "b"), false);
  check("resolved-transitive-same-domain",
        replace(resolvedEndpoints, "domain = @otherClock", "domain = @clock") + net() +
            through("a", "net") + through("net", "b"),
        true);
  check("resolved-array-structural-view",
        domain() + resolvedDefinition() + instance("left", "sink", "connect", resolved(), "\"2\"") +
            member("a", "left", "!nodal.shaped<\"2\", " + resolved() + ">") +
            observe("a", "!nodal.shaped<\"2\", " + resolved() + ">"),
        true);
  const std::string conservative = domain() + analogDefinition(true) + analogInstance(true);
  check("conservative-declaration-existing-analog-owner", conservative, true, analogOwners());
  check("conservative-missing-actual-owner", conservative, false);
  check("conservative-forged-nature-reference",
        replace(conservative, "potential = @Voltage", "potential = @Current"), false,
        analogOwners());
  const std::string signal =
      domain() + analogDefinition(false) + analogInstance(false) + member("a", "left", "f64");
  check("signal-flow-exact-dimension-abi", signal, true);
  check("signal-flow-not-digital-observation", signal + observe("a", "f64"), false);
  check("analog-abi-mismatch",
        replace(signal, "analog = {dimension = \"voltage\"}", "analog = {dimension = \"current\"}"),
        false);
  check("analog-invalid-dimension",
        replace(signal, "dimension = \"voltage\"", "dimension = \"?invalid!\""), false);
  std::string empty =
      "\"nodal.interface\"() <{sym_name = \"Empty\", metadata = {" + tag +
      ", semantic_identity = \"pkg.Empty\"}}> ({\"nodal.interface_role\"() <{sym_name = \"empty\", "
      "kind = \"explicit\", metadata = {" +
      tag + ", access = []}}> : () -> ()}) : () -> ()\n";
  check("empty-interface-actual-empty-role", empty, true);
  check("typed-read-exact-value", net() + read(), true);
  check("typed-readonly-read", net("net", "read_only") + read("net", "read_only"), true);
  check("typed-read-exact-type-required", net() + read("net", "push_pull", "!nodal.uint<8>"),
        false);
  check("typed-read-marker-required", net() + replace(read(), tag, ""), false);
  const std::string values = literal("value") + literal("enable", boolean);
  const std::string driven = net() + values + driver() + netDrive();
  check("conditional-driver-split", driven + split(), true);
  check("release-does-not-compete", driven + driver("release") + release() + split(), true);
  check("split-after-pass-through", driven + net("other") + through() + split("other"), true);
  check("multiple-alias-edges",
        driven + net("other") + through() + through("other", "net") + split(), true);
  check("no-value-driver", net() + split(), false);
  check("release-only-no-write-projection", net() + driver("release") + release() + split(), false);
  check("multiple-value-drivers", driven + driver("second") + netDrive("second") + split(), false);
  check("multiple-value-drivers-through-alias",
        driven + net("other") + driver("second", "other") + netDrive("second", "other") +
            through() + split(),
        false);
  check("driver-used-twice", driven + netDrive(), false);
  check("driver-unconsumed", net() + driver(), false);
  check("driver-wrong-net", net() + net("other") + values + driver() + netDrive("driver", "other"),
        false);
  check("duplicate-driver-id",
        driven + replace(driver("second"), "driver_id = \"second\"", "driver_id = \"driver\"") +
            netDrive("second"),
        false);
  check("missing-driver-marker", net() + values + replace(driver(), tag, "") + netDrive(), false);
  check("missing-net-marker", replace(net(), tag + ", ", "") + values + driver() + netDrive(),
        false);
  check("missing-drive-marker", net() + values + driver() + replace(netDrive(), tag, ""), false);
  check("missing-split-marker", driven + replace(split(), tag, ""), false);
  check("readonly-driver", net("net", "read_only") + driver("driver", "net", "read_only"), false);
  check("portable-internal-rejected", net("net", "push_pull", "internal_resolved_net"), false);
  check("simulation-internal-retained",
        net("net", "push_pull", "internal_resolved_net", "full_resolved_simulation"), true);
  check("technology-internal-retained",
        net("net", "push_pull", "internal_resolved_net", "technology_mapped"), true);
  check("low-driver-split",
        net("net", "open_drain") + values + driver("driver", "net", "open_drain") + level() +
            split("net", "open_drain"),
        true);
  check("high-driver-split",
        net("net", "open_source") + values + driver("driver", "net", "open_source") +
            level("high", "open_source") + split("net", "open_source"),
        true);
  check("wrong-level-mode",
        net("net", "open_drain") + values + driver("driver", "net", "open_drain") + level("high"),
        false);
  check("open-drain-arbitrary-value",
        net("net", "open_drain") + values + driver("driver", "net", "open_drain") +
            netDrive("driver", "net", "open_drain"),
        false);
  check("pass-through-profile-mismatch",
        net() + net("other", "push_pull", "top_level_pin", "technology_mapped") + through(), false);
  check("split-exact-value-type",
        driven + replace(split(), "-> (!nodal.bits<8>", "-> (!nodal.uint<8>"), false);
  check("split-exact-bool-type", driven + replace(split(), ", !nodal.bits<1>)", ", i1)"), false);
  check("driver-exact-bool-enable",
        net() + literal("value") + literal("enable", "i1") + driver() +
            replace(netDrive(), ", !nodal.bits<1>)", ", i1)"),
        false);
  const std::string clock = "!nodal.capability<\"clock\">", reset = "!nodal.capability<\"reset\">";
  const std::string clockVec = "!nodal.shaped<\"2\", " + clock + ">";
  auto capabilities = [&](StringRef type, StringRef dimensions = "", StringRef view = "") {
    std::string result = domain() + definition(type) +
                         instance("left", "source", "out", type, dimensions) +
                         instance("right", "sink", "in", type, dimensions);
    StringRef resultType = view.empty() ? type : view;
    return result + member("a", "left", resultType) + member("b", "right", resultType);
  };
  check("clock-interface-connect-observe",
        capabilities(clock) + connect("a", "b", clock) + observe("b", clock), true);
  check("reset-interface-connect-observe",
        capabilities(reset) + connect("a", "b", reset) + observe("b", reset), true);
  check("clock-shaped-plain-members", capabilities(clockVec) + connect("a", "b", clockVec), true);
  check("clock-interface-array",
        capabilities(clock, "\"2\"", clockVec) + connect("a", "b", clockVec), true);
  check("clock-reset-member-substitution",
        domain() + definition(clock) + instance("left", "source", "out", clock) +
            member("a", "left", reset),
        false);
  check("clock-bool-member-substitution",
        domain() + definition(clock) + instance("left", "source", "out", clock) +
            member("a", "left", boolean),
        false);
  check("clock-input-member-drive", capabilities(clock) + drive("a", "b", clock), true);
  check("clock-input-port-member-drive",
        capabilities(clock) + port("value", clock) + drive("a", "value", clock), true);
  check("clock-input-port-member-domain",
        capabilities(clock) + domain("other") + port("value", clock, "input", "other") +
            drive("a", "value", clock),
        false);
  check("clock-output-port-not-input-provenance",
        capabilities(clock) + port("value", clock, "output") + drive("a", "value", clock), false);
  check("clock-computed-member-source",
        capabilities(clock) + literal("value", clock) + drive("a", "value", clock), false);
  auto ports = [&](StringRef type) {
    return domain() + port("input", type) + port("output", type, "output");
  };
  check("clock-continuous-boundary", ports(clock) + assign(clock), true);
  check("reset-continuous-boundary", ports(reset) + assign(reset), true);
  check("clock-shaped-continuous-boundary", ports(clockVec) + assign(clockVec), true);
  check("clock-reset-assignment-substitution",
        domain() + port("input", reset) + port("output", clock, "output") +
            assign(clock, "continuous", reset),
        false);
  check("clock-bool-assignment-substitution",
        domain() + port("input", boolean) + port("output", clock, "output") +
            assign(clock, "continuous", boolean),
        false);
  const std::string indexed =
      literal("zero", "i64", "0") +
      "%input = \"nodal.shape_index\"(%vector, %zero) <{metadata = {}}> : (" + clockVec +
      ", i64) -> " + clock + "\n";
  check("clock-structural-input-index",
        domain() + port("vector", clockVec) + port("output", clock, "output") + indexed +
            assign(clock),
        true);
  check("clock-projected-port-member-drive",
        capabilities(clock) + port("vector", clockVec) + indexed + drive("a", "input", clock),
        true);
  check("clock-scalar-conversion-rejected",
        domain() + port("input", clock) +
            "%value = \"nodal.digital_expr\"(%input) <{operator_name = "
            "\"digital_reinterpret_unsigned\", metadata = {}}> : (" +
            clock + ") -> !nodal.uint<1>\n",
        false);
  check("clock-computed-producer-rejected",
        domain() + port("output", clock, "output") + literal("input", clock) + assign(clock),
        false);
  check("clock-register-next-rejected", ports(clock) + assign(clock, "register_next"), false);
  check("clock-port-domain-mismatch",
        domain() + domain("other") + port("input", clock, "input", "other") +
            port("output", clock, "output") + assign(clock),
        false);
  check("clock-port-missing-actual-domain",
        replace(ports(clock), "domain = @clock", "domain = @missing") + assign(clock), false);
  check("clock-port-direction-mismatch",
        domain() + port("input", clock, "output") + port("output", clock, "output") + assign(clock),
        false);
  check("clock-wire-storage-rejected", storage(clock), false);
  check("clock-shaped-storage-rejected", storage(clockVec), false);
  check("clock-literal-rejected", literal("value", clock), false);
  check("clock-protocol-payload-rejected",
        replace(streamDefinition(), "type = !nodal.bits<8>", "type = " + clock), false);
  check("clock-shaped-protocol-payload-rejected",
        replace(streamDefinition(), "type = !nodal.bits<8>", "type = " + clockVec), false);
  check("clock-unsupported-kind", port("input", "!nodal.capability<\"enable\">"), false);
  {
    ++cases;
    diagnosticText.clear();
    auto parsed =
        parseSourceString<mlir::ModuleOp>(model(ports(clock)), ParserConfig(&context, false));
    bool digital = false;
    if (parsed)
      parsed->walk([&](Operation *op) { digital |= nodal::hasDigitalTargetCapability(op); });
    bool passed = parsed && succeeded(verify(parsed->getOperation())) && digital &&
                  failed(nodal::verifyTargetLanguageCapabilities(*parsed, "verilog-a"));
    failures += !passed;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ")
        << "clock-digital-target-capability\n";
  }
  auto ordinaryAssignment = [&](StringRef target, StringRef source, StringRef type = bits) {
    return "\"nodal.digital_assign\"(%" + target.str() + ", %" + source.str() +
           ") <{kind = \"continuous\", metadata = {}}> : (" + type.str() + ", " + type.str() +
           ") -> ()\n";
  };
  auto dependency = [&](StringRef name, const std::string &body, bool expected) {
    check(name, body, expected);
    if (!expected && diagnosticText.find("NODAL-DIGITAL-055-010") == std::string::npos) {
      ++failures;
      llvm::errs() << "DIGITAL_INTERFACE_MODEL_FAIL " << name << "-causal-diagnostic\n";
    }
  };
  dependency("assignment-external-member-read",
             pair + port("output", bits, "output") + ordinaryAssignment("output", "b"), true);
  dependency("assignment-connected-member-read",
             pair + literal("value") + drive() + connect() + port("output", bits, "output") +
                 ordinaryAssignment("output", "b"),
             true);
  dependency("assignment-member-wire-cycle",
             pair + storage(bits) + drive("a", "wire") + ordinaryAssignment("wire", "a"), false);
  dependency("assignment-member-connect-wire-cycle",
             pair + storage(bits) + drive("a", "wire") + connect() +
                 ordinaryAssignment("wire", "b"),
             false);
  dependency("assignment-member-alias-wire-cycle",
             pair + member("alias", "left", bits, "monitor") + storage(bits) + drive("a", "wire") +
                 ordinaryAssignment("wire", "alias"),
             false);
  const std::string resolvedValues = literal("value") + literal("enable", boolean);
  dependency("assignment-resolved-net-boundary-read",
             net() + read() + port("output", bits, "output") + ordinaryAssignment("output", "read"),
             true);
  dependency("assignment-resolved-net-multiple-drivers",
             net() + driver("first") + driver("second") + resolvedValues + netDrive("first") +
                 netDrive("second") + read() + port("output", bits, "output") +
                 ordinaryAssignment("output", "read"),
             true);
  dependency("assignment-resolved-pass-through-read",
             net() + net("other") + through() + driver() + resolvedValues + netDrive() +
                 read("other") + port("output", bits, "output") +
                 ordinaryAssignment("output", "read"),
             true);
  dependency("assignment-resolved-net-value-cycle",
             net() + driver() + storage(bits) + literal("enable", boolean) +
                 replace(netDrive(), "%value", "%wire") + read() +
                 ordinaryAssignment("wire", "read"),
             false);
  dependency("assignment-resolved-pass-through-value-cycle",
             net() + net("other") + through() + driver() + storage(bits) +
                 literal("enable", boolean) + replace(netDrive(), "%value", "%wire") +
                 read("other") + ordinaryAssignment("wire", "read"),
             false);
  dependency("assignment-resolved-split-driver-read",
             net() + driver() + resolvedValues + netDrive() + split() +
                 port("output", bits, "output") + ordinaryAssignment("output", "write"),
             true);
  dependency("assignment-resolved-split-value-cycle",
             net() + driver() + storage(bits) + literal("enable", boolean) +
                 replace(netDrive(), "%value", "%wire") + split() +
                 ordinaryAssignment("wire", "write"),
             false);
  dependency("assignment-resolved-split-enable-cycle",
             net() + driver() + storage(boolean) + literal("value") +
                 replace(netDrive(), "%enable", "%wire") + split() +
                 ordinaryAssignment("wire", "enabled", boolean),
             false);
  dependency("assignment-legacy-capability-not-runtime-read",
             replace(net(), tag + ", ", "") + replace(read(), tag, "") +
                 port("output", bits, "output") + ordinaryAssignment("output", "read"),
             false);
  auto standalone = [&](StringRef name, const std::string &body, bool expected) {
    dependency(name, body, expected);
    if (body.find("nodal.digital_assign") != std::string::npos ||
        (!expected && diagnosticText.find("combinational value cycle") == std::string::npos)) {
      ++failures;
      llvm::errs() << "DIGITAL_INTERFACE_MODEL_FAIL " << name << "-standalone-causal-control\n";
    }
  };
  standalone("standalone-interface-self-drive-feedback", pair + drive("a", "a"), false);
  standalone("standalone-interface-connect-feedback", pair + drive("a", "b") + connect(), false);
  const std::string outputPair = domain() + definition() + instance("left", "source", "out") +
                                 instance("right", "source", "out") + member("a", "left") +
                                 member("b", "right");
  standalone("standalone-interface-two-drive-feedback",
             outputPair + drive("a", "b") + drive("b", "a"), false);
  standalone("standalone-interface-alias-feedback",
             pair + member("alias", "left", bits, "monitor") + drive("a", "alias"), false);
  standalone("standalone-interface-acyclic-connect", pair + literal("value") + drive() + connect(),
             true);
  standalone("standalone-interface-alias-not-a-value-cycle",
             pair + member("alias", "left", bits, "monitor") + observe("alias"), true);
  standalone("standalone-interface-undriven-boundary-policy-preserved", pair, true);
  standalone("standalone-net-read-driver-feedback",
             net() + driver() + literal("enable", boolean) + read() +
                 replace(netDrive(), "%value", "%read"),
             false);
  standalone("standalone-net-pass-through-feedback",
             net() + net("other") + through() + driver() + literal("enable", boolean) +
                 read("other") + replace(netDrive(), "%value", "%read"),
             false);
  standalone("standalone-two-net-read-driver-feedback",
             net() + net("other") + driver("first") + driver("second", "other") +
                 literal("enable", boolean) + read() +
                 replace(read("other"), "%read =", "%otherRead =") +
                 replace(netDrive("first"), "%value", "%otherRead") +
                 replace(netDrive("second", "other"), "%value", "%read"),
             false);
  standalone("standalone-net-conditional-enable-feedback",
             net() + driver() + literal("value") + read() +
                 "%enable = \"nodal.digital_expr\"(%read, %value) <{operator_name = "
                 "\"digital_eq\", metadata = {}}> : (" +
                 bits + ", " + bits + ") -> " + boolean + "\n" + netDrive(),
             false);
  standalone("standalone-net-multiple-drivers-preserved",
             net() + driver("first") + driver("second") + resolvedValues + netDrive("first") +
                 netDrive("second") + read(),
             true);
  standalone("standalone-net-feedback-among-multiple-drivers",
             net() + driver("first") + driver("second") + resolvedValues + read() +
                 netDrive("first") + replace(netDrive("second"), "%value", "%read"),
             false);
  standalone("standalone-net-pass-through-acyclic-driver",
             net() + net("other") + through() + driver() + resolvedValues + netDrive() +
                 read("other"),
             true);
  standalone("standalone-net-pass-through-union-is-not-a-value-cycle",
             net() + net("other") + net("third") + through() + through("other", "third") +
                 through("third", "net") + read("third"),
             true);
  standalone("standalone-net-external-boundary-read", net() + read(), true);
  standalone("standalone-net-split-value-feedback",
             net() + driver() + literal("enable", boolean) + split() +
                 replace(netDrive(), "%value", "%write"),
             false);
  standalone("standalone-net-split-enable-feedback",
             net() + driver() + literal("value") + split() +
                 replace(netDrive(), "%enable", "%enabled"),
             false);
  standalone("standalone-net-split-acyclic",
             net() + driver() + resolvedValues + netDrive() + split(), true);
  auto hierarchy = [&](StringRef name, const std::string &source, bool expected,
                       StringRef expectedDiagnostic = "") {
    ++cases;
    diagnosticText.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    bool valid = parsed && succeeded(verify(parsed->getOperation()));
    bool passed =
        valid == expected && (expectedDiagnostic.empty() ||
                              diagnosticText.find(expectedDiagnostic.str()) != std::string::npos);
    failures += !passed;
    if (!passed)
      llvm::errs() << diagnosticText;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  auto resolvedRead = [&](StringRef endpoint, StringRef result = "read") {
    return "%" + result.str() + " = \"nodal.net_read\"(%" + endpoint.str() + ") <{metadata = {" +
           tag + "}}> : (" + resolved() + ") -> " + bits + "\n";
  };
  const std::string conditional = literal("value") + literal("enable", boolean);
  const std::string childDriver =
      driver("driver", "exported") + conditional + netDrive("driver", "exported");
  const std::string parentDriver =
      driver("driver", "projected") + conditional + netDrive("driver", "projected");
  const std::string repeatedStandalone =
      "\"nodal.instance\"() <{sym_name = \"sibling\", module = @Child, parameter_bindings = {}, "
      "domain_bindings = {required = @other}, metadata = {}}> : () -> ()\n" +
      replace(hierarchyBinding("required", "other"), "instance = @child", "instance = @sibling") +
      standaloneBoundary("sibling_view", "sibling");
  hierarchy("standalone-boundary-repeated-child-retains-occurrence-identity",
            standaloneBoundaryFixture(repeatedStandalone, childDriver), true);
  hierarchy("standalone-boundary-wrong-element-rejected",
            replace(standaloneBoundaryFixture(), standaloneBoundary(),
                    standaloneBoundary("projected", "child", "exported", boolean)),
            false, "must preserve exact mode");
  hierarchy("standalone-boundary-parent-marker-required",
            replace(standaloneBoundaryFixture(), standaloneBoundary(),
                    replace(standaloneBoundary(), tag + ", ", "")),
            false, "interface_abi_version");
  hierarchy("standalone-boundary-imported-policy-is-independently-verified",
            replace(standaloneBoundaryFixture(), net("exported"),
                    net("exported", "push_pull", "internal_resolved_net")),
            false, "portable boundary resolution cannot represent an internal resolved net");
  hierarchy("standalone-boundary-read-retains-child-component",
            standaloneBoundaryFixture(resolvedRead("projected"), resolvedRead("exported")), true);
  hierarchy("standalone-boundary-parent-and-child-conditional-drivers",
            standaloneBoundaryFixture(parentDriver + resolvedRead("projected"), childDriver), true);
  hierarchy("standalone-boundary-pass-through-is-not-a-causal-cycle",
            standaloneBoundaryFixture(net("local") + through("projected", "local") +
                                      through("local", "projected")),
            true);
  hierarchy("standalone-boundary-exact-symbolic-element-binding",
            symbolicStandaloneBoundaryFixture(), true);
  hierarchy("standalone-boundary-default-is-not-symbolic-element-proof",
            symbolicStandaloneBoundaryFixture(true), false, "must preserve exact mode");
  hierarchy("standalone-boundary-duplicate-projection-rejected",
            standaloneBoundaryFixture(standaloneBoundary("again")), false,
            "duplicate child resolved projection");
  hierarchy(
      "standalone-boundary-unknown-child-net-rejected",
      replace(standaloneBoundaryFixture(), "endpoint = \"exported\"", "endpoint = \"missing\""),
      false, "exact immediate child's direct net");
  hierarchy("standalone-boundary-foreign-instance-rejected",
            replace(standaloneBoundaryFixture(), "instance = @child, endpoint",
                    "instance = @missing, endpoint"),
            false);
  hierarchy("standalone-boundary-duplicate-child-name-rejected",
            standaloneBoundaryFixture(
                "", replace(net("alias"), "name = \"alias\"", "name = \"exported\"")),
            false, "unambiguous identities");
  hierarchy("standalone-boundary-legacy-child-net-rejected",
            replace(standaloneBoundaryFixture(), net("exported"),
                    replace(net("exported"), tag + ", ", "")),
            false, "exact immediate child's direct net");
  hierarchy("standalone-boundary-profile-cannot-be-upgraded",
            replace(standaloneBoundaryFixture(), "profile = \"portable_boundary_only\"",
                    "profile = \"technology_mapped\""),
            false, "must preserve exact mode");
  hierarchy("standalone-boundary-placement-cannot-be-forged",
            replace(standaloneBoundaryFixture(), "placement = \"top_level_pin\"",
                    "placement = \"internal_resolved_net\""),
            false, "must preserve exact mode");
  hierarchy("standalone-boundary-mode-cannot-be-forged",
            replace(standaloneBoundaryFixture(), resolved(), resolved("open_drain")), false,
            "must preserve exact mode");
  hierarchy("standalone-boundary-domain-label-is-not-a-proof",
            replace(standaloneBoundaryFixture(), "semantic_path = \"Top.projected\"",
                    "semantic_path = \"Top.projected\", domain = @clock"),
            false, "without inventing a domain constraint");
  hierarchy("standalone-boundary-readonly-driver-remains-forbidden",
            replace(replace(standaloneBoundaryFixture(driver("driver", "projected", "read_only")),
                            standaloneBoundary(),
                            replace(standaloneBoundary(), resolved(), resolved("read_only"))),
                    net("exported"), net("exported", "read_only")),
            false, "does not permit creating or using a driver");
  hierarchy("standalone-boundary-local-split-retains-unique-global-driver",
            standaloneBoundaryFixture("", childDriver + split("exported")), true);
  hierarchy("standalone-boundary-no-local-driver-split-remains-strict",
            standaloneBoundaryFixture("", split("exported")), false,
            "exactly one authored conditional driver");
  hierarchy("contextual-split-parent-observes-child-driver-without-clock-trigger",
            bareSplitFixture(contextualSplit("projected"), childDriver), true);
  hierarchy("contextual-split-child-observes-parent-driver-without-clock-trigger",
            bareSplitFixture(parentDriver, contextualSplit("exported")), true);
  hierarchy("contextual-split-missing-root-is-not-an-empty-constant",
            replace(bareSplitFixture(contextualSplit("projected"), childDriver),
                    " attributes {nodal.root.module = @Top}", ""),
            false, "requires one exact outer nodal.root.module selector");
  hierarchy("contextual-split-driverless-joined-component-rejected",
            bareSplitFixture(contextualSplit("projected")), false,
            "resolved hierarchy split requires exactly one actual conditional driver");
  hierarchy("contextual-split-local-driver-forged-intent-rejected",
            bareSplitFixture(parentDriver + contextualSplit("projected")), false,
            "hierarchy-driver split must have no local authored value driver");
  hierarchy("contextual-split-unjoined-root-rejected",
            "module attributes {nodal.root.module = @Top} {" +
                bareModule("Top", net() + contextualSplit()) + "}",
            false, "hierarchy-driver split requires an actual closed-root boundary connection");
  const std::string unreachable = bareSplitFixture("", childDriver);
  hierarchy("contextual-split-unused-definition-cannot-hide-obligation",
            unreachable.substr(0, unreachable.rfind('}')) +
                bareModule("Dead", net() + contextualSplit()) + "}",
            false, "hierarchy-driver split belongs to an unreachable definition");
  hierarchy("contextual-split-repeated-definition-keeps-independent-drivers",
            bareSplitFixture(
                contextualSplit("projected") +
                    replace(replace(contextualSplit("sibling_view"), "%write", "%second_write"),
                            "%enabled", "%second_enable"),
                childDriver, true),
            true);
  hierarchy("contextual-split-sibling-join-counts-both-occurrence-drivers",
            bareSplitFixture(through("projected", "sibling_view") + contextualSplit("projected"),
                             childDriver, true),
            false, "resolved hierarchy split requires exactly one actual conditional driver");
  const std::string middle = net("exported") + bareInstance() +
                             replace(standaloneBoundary(), "Top.projected", "Middle.projected") +
                             through("exported", "projected");
  hierarchy("contextual-split-two-boundaries-retain-one-actual-driver",
            "module attributes {nodal.root.module = @Top} {" +
                bareModule("Top", bareInstance("child", "Middle") + standaloneBoundary() +
                                      contextualSplit("projected")) +
                bareModule("Middle", middle) + bareModule("Child", net("exported") + childDriver) +
                "}",
            true);
  const std::string splitFeedback =
      "%child_in = \"nodal.instance_port\"() <{instance = @child, port = \"input\", "
      "direction = \"input\", metadata = {}}> : () -> " +
      bits + "\n" + contextualSplit("projected") +
      "\"nodal.digital_assign\"(%child_in, %write) <{kind = \"continuous\", metadata = {}}> : (" +
      bits + ", " + bits + ") -> ()\n";
  hierarchy("contextual-split-data-feedback-retains-cross-occurrence-cycle",
            bareSplitFixture(splitFeedback,
                             port("input", bits, "input", "none") + driver("driver", "exported") +
                                 literal("enable", boolean) +
                                 replace(netDrive("driver", "exported"), "%value", "%input")),
            false, "hierarchy connection contains a causal combinational dependency cycle");
  hierarchy("external-inout-exact-declaration-and-pass-through",
            externalFixture(net("top") + through("projected", "top") + resolvedRead("top")), true);
  hierarchy("external-inout-one-bit-pin-keeps-exact-bit-pattern-identity",
            replace(replace(externalFixture(), resolved(), resolved("push_pull", boolean)),
                    resolved(), resolved("push_pull", boolean)),
            true);
  hierarchy("external-inout-bool-is-not-a-bit-pattern-pin",
            replace(replace(externalFixture(), resolved(), resolved("push_pull", "i1")), resolved(),
                    resolved("push_pull", "i1")),
            false, "fixed scalar black-box resolved pin declarations");

  hierarchy("external-inout-read-retains-opaque-contribution",
            externalFixture(resolvedRead("projected")), true);
  hierarchy("external-inout-conditional-local-drive-is-not-an-external-body",
            externalFixture(parentDriver), true);
  hierarchy("external-inout-local-split-cannot-ignore-opaque-contribution",
            externalFixture(parentDriver + split("projected")), false,
            "opaque external contribution");
  hierarchy("external-inout-contextual-split-cannot-infer-empty-driver",
            externalFixture(contextualSplit("projected")), false, "opaque external contribution");
  hierarchy("external-signature-executable-body-rejected",
            externalFixture("", externalModule("Child", "ExternalPad",
                                               net("exported", "push_pull", "black_box_pin") +
                                                   literal("value"))),
            false, "never executable statements");
  hierarchy("external-signature-placement-is-not-an-internal-net",
            externalFixture("", externalModule("Child", "ExternalPad", net("exported"))), false,
            "black-box resolved pin declarations");
  hierarchy("external-signature-technology-profile-is-not-assumed",
            externalFixture("", externalModule("Child", "ExternalPad",
                                               net("exported", "push_pull", "black_box_pin",
                                                   "technology_mapped"))),
            false, "black-box resolved pin declarations");
  hierarchy("external-signature-empty-declaration-rejected",
            externalFixture("", replace(externalModule(),
                                        net("exported", "push_pull", "black_box_pin"), "^bb0:\n")),
            false, "at least one independently verified resolved pin");
  hierarchy("external-signature-physical-name-is-exact",
            externalFixture("", externalModule("Child", "bad name")), false,
            "canonical exact external symbol");
  hierarchy("external-signature-duplicate-physical-name-rejected",
            externalFixture("", externalModule() + externalModule("Other")), false,
            "external physical symbol has more than one declaration");
  hierarchy("external-signature-cannot-shadow-local-module-name",
            externalFixture("", externalModule() + bareModule("ExternalPad", "")), false,
            "external physical symbol collides with a local module definition");
  hierarchy(
      "external-signature-duplicate-pin-rejected",
      externalFixture("", externalModule("Child", "ExternalPad",
                                         net("exported", "push_pull", "black_box_pin") +
                                             replace(net("alias", "push_pull", "black_box_pin"),
                                                     "name = \"alias\"", "name = \"exported\""))),
      false, "black-box resolved pin declarations");
  hierarchy("external-signature-is-top-level-definition-only",
            "module {" + bareModule("Top", externalModule()) + "}", false,
            "canonical exact external symbol");
  hierarchy(
      "external-signature-does-not-admit-control-domain-body",
      externalFixture("", externalModule("Child", "ExternalPad",
                                         net("exported", "push_pull", "black_box_pin") + domain())),
      false, "never executable statements");
  hierarchy("external-boundary-cannot-forge-element",
            replace(externalFixture(), resolved(), resolved("push_pull", boolean)), false,
            "must preserve exact mode");
  auto physicalFacts = [&](StringRef name, const std::string &source, bool expected, auto inspect) {
    ++cases;
    diagnosticText.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    bool valid = bool(parsed);
    SmallVector<nodal::DigitalResolvedHierarchyComponent> facts(1);
    SmallVector<nodal::DigitalResolvedSplitBinding> bindings(1);
    const std::string before = parsed ? print(parsed->getOperation()) : "";
    if (valid) {
      auto model = nodal::HierarchyModel::create(parsed->getOperation());
      valid = succeeded(model) &&
              succeeded(nodal::verifyHierarchyDependencies(*model, {}, &bindings, &facts));
    }
    bool passed =
        valid == expected && parsed && before == print(parsed->getOperation()) &&
        (valid ? inspect(llvm::ArrayRef(facts).drop_front(), llvm::ArrayRef(bindings).drop_front())
               : facts.size() == 1 && bindings.size() == 1);
    failures += !passed;
    if (!passed)
      llvm::errs() << diagnosticText;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  physicalFacts(
      "resolved-component-query-keeps-all-actual-external-identities",
      externalFixture(net("top") + through("projected", "top") + parentDriver), true,
      [](auto facts, auto bindings) {
        if (facts.size() != 1 || !bindings.empty() || facts[0].endpoints.size() != 3 ||
            facts[0].drivers.size() != 1 || facts[0].externalContributions.size() != 1)
          return false;
        const auto &component = facts[0];
        const auto &projected = component.endpoints[0];
        const auto &top = component.endpoints[1];
        const auto &external = component.endpoints[2];
        return component.representative && component.occurrence == 0 && projected.occurrence == 0 &&
               top.occurrence == 0 && external.occurrence == 1 &&
               projected.endpoint.declaration == external.endpoint.operation &&
               projected.endpoint.placement == "black_box_pin" &&
               top.endpoint.placement == "top_level_pin" &&
               external.endpoint.type == projected.endpoint.type &&
               external.endpoint.profile == "portable_boundary_only" && !external.endpoint.domain &&
               component.externalContributions[0].occurrence == 1 &&
               component.externalContributions[0].operation == external.endpoint.operation &&
               component.drivers[0].occurrence == 0;
      });
  physicalFacts("black-box-placement-tag-alone-is-not-an-opaque-external-signature",
                "module attributes {nodal.root.module = @Top} {" +
                    bareModule("Top", net("net", "push_pull", "black_box_pin") + driver() +
                                          conditional + netDrive() + split()) +
                    "}",
                true, [](auto facts, auto bindings) {
                  return facts.size() == 1 && bindings.size() == 1 &&
                         facts[0].externalContributions.empty() && facts[0].drivers.size() == 1 &&
                         facts[0].endpoints.size() == 1;
                });
  physicalFacts("resolved-component-query-failed-split-is-transactional",
                externalFixture(parentDriver + split("projected")), false,
                [](auto, auto) { return false; });
  physicalFacts("resolved-component-query-retains-unaccessed-interface-identities",
                hierarchyResolvedFixture("", "", "sink", false, false), true,
                [](auto facts, auto bindings) {
                  return facts.size() == 1 && bindings.empty() && !facts[0].representative &&
                         facts[0].endpoints.size() == 2 && facts[0].drivers.empty() &&
                         facts[0].externalContributions.empty() &&
                         facts[0].endpoints[0].endpoint.path == "data" &&
                         facts[0].endpoints[1].endpoint.path == "data" &&
                         !facts[0].endpoints[0].endpoint.placement;
                });
  auto bindingFacts = [&](StringRef name, const std::string &source, unsigned expected) {
    ++cases;
    diagnosticText.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    bool valid = parsed && succeeded(verify(parsed->getOperation()));
    SmallVector<nodal::DigitalResolvedSplitBinding> facts;
    std::string before = valid ? print(parsed->getOperation()) : "";
    if (valid) {
      auto model = nodal::HierarchyModel::create(parsed->getOperation());
      valid = succeeded(model) && succeeded(nodal::verifyHierarchyDependencies(*model, {}, &facts));
    }
    bool passed = valid && facts.size() == expected && before == print(parsed->getOperation());
    for (const auto &fact : facts)
      passed &= fact.split && fact.driver && fact.component && fact.enable &&
                bool(fact.data) != bool(fact.level) &&
                fact.driverOccurrence != fact.splitOccurrence;
    failures += !passed;
    if (!passed)
      llvm::errs() << diagnosticText;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  bindingFacts("contextual-split-facts-retain-actual-child-driver",
               bareSplitFixture(contextualSplit("projected"), childDriver), 1);
  bindingFacts("contextual-split-facts-retain-actual-parent-driver",
               bareSplitFixture(parentDriver, contextualSplit("exported")), 1);
  hierarchy("standalone-boundary-read-feedback-remains-a-real-cycle",
            standaloneBoundaryFixture(driver("driver", "projected") + literal("enable", boolean) +
                                      resolvedRead("projected", "value") +
                                      netDrive("driver", "projected")),
            false,
            "NODAL-DIGITAL-055-010: digital dependency graph contains a combinational value cycle");
  hierarchy("resolved-boundary-pass-through-without-drivers",
            hierarchyResolvedFixture(resolvedRead("projected"), resolvedRead("exported")), true);
  hierarchy("resolved-boundary-parent-and-child-drivers",
            hierarchyResolvedFixture(parentDriver + resolvedRead("projected"), childDriver), true);
  hierarchy("resolved-boundary-exact-repeated-child-components",
            hierarchyResolvedFixture(parentDriver, childDriver, "sink", true, true, true), true);
  hierarchy("resolved-boundary-unaccessed-child-is-not-a-fresh-net",
            hierarchyResolvedFixture(parentDriver, "", "sink", true, false), true);
  hierarchy("resolved-boundary-unaccessed-projection",
            hierarchyResolvedFixture("", childDriver, "sink", false), true);
  hierarchy("resolved-boundary-true-read-to-driver-feedback",
            hierarchyResolvedFixture(driver("driver", "projected") + literal("enable", boolean) +
                                     resolvedRead("projected", "value") +
                                     netDrive("driver", "projected")),
            false);
  hierarchy(
      "resolved-boundary-cross-scope-read-feedback",
      hierarchyResolvedFixture(
          resolvedRead("projected", "input") +
              "%child_input = \"nodal.instance_port\"() <{instance = @child, port = \"input\", "
              "direction = \"input\", metadata = {}}> : () -> " +
              bits + "\n" +
              "\"nodal.digital_assign\"(%child_input, %input) <{kind = \"continuous\", metadata = "
              "{}}> : (" +
              bits + ", " + bits + ") -> ()\n",
          replace(replace(port("value", bits, "input", "required"), "sym_name = \"value\"",
                          "sym_name = \"input\""),
                  "port = @value", "port = @input") +
              driver("driver", "exported") + literal("enable", boolean) +
              netDrive("driver", "exported")),
      false, "causal combinational dependency cycle");
  hierarchy("resolved-boundary-split-rechecks-all-actual-drivers",
            hierarchyResolvedFixture(parentDriver, childDriver + split("exported")), false,
            "resolved hierarchy split requires exactly one actual conditional driver");
  hierarchy("resolved-boundary-unaccessed-parent-retains-unique-child-split",
            hierarchyResolvedFixture("", childDriver + split("exported"), "sink", false), true);
  hierarchy("resolved-boundary-local-split-remains-strict",
            hierarchyResolvedFixture("", split("exported")), false);
  hierarchy("resolved-boundary-monitor-cannot-gain-drive",
            hierarchyResolvedFixture(parentDriver, "", "monitor"), false);
  hierarchy("resolved-boundary-monitor-cannot-gain-connect",
            hierarchyResolvedFixture(net() + through("projected", "net"), "", "monitor"), false);
  hierarchy("resolved-boundary-constrained-mode-is-exact",
            replace(hierarchyResolvedFixture(), "type = " + resolved(),
                    "type = " + resolved("open_drain")),
            false);
  hierarchy("resolved-boundary-forged-actual-element-type",
            replace(hierarchyResolvedFixture(), "semantic_path = \"Top.projected\"",
                    "semantic_path = \"Top.projected\", actual_types = [{path = \"data\", type = " +
                        resolved("push_pull", boolean) + "}]"),
            false);
  std::string siblingDomains = hierarchyResolvedFixture("", childDriver, "sink", true, true, true);
  siblingDomains = replace(siblingDomains,
                           "sym_name = \"sibling\", module = @Child, parameter_bindings = {}, "
                           "domain_bindings = {required = @clock}",
                           "sym_name = \"sibling\", module = @Child, parameter_bindings = {}, "
                           "domain_bindings = {required = @other}");
  siblingDomains = replace(
      siblingDomains,
      replace(hierarchyBinding("required", "clock"), "instance = @child", "instance = @sibling"),
      replace(hierarchyBinding("required", "other"), "instance = @child", "instance = @sibling"));
  hierarchy("resolved-repeated-definition-retains-distinct-actual-domains", siblingDomains, true);
  const std::string sampled =
      resolvedRead("projected", "sampled") +
      "%q = \"nodal.digital_storage\"() <{name = \"q\", kind = \"register\", domain = @clock, "
      "metadata = {}}> : () -> " +
      bits +
      "\n\"nodal.digital_assign\"(%q, %sampled) <{kind = \"register_next\", metadata = {}}> : (" +
      bits + ", " + bits + ") -> ()\n";
  std::string foreign = hierarchyResolvedFixture(
      port("input", bits, "input", "other") +
          "%child_input = \"nodal.instance_port\"() <{instance = @child, port = \"value\", "
          "direction = \"input\", metadata = {}}> : () -> " +
          bits +
          "\n\"nodal.digital_assign\"(%child_input, %input) <{kind = \"continuous\", metadata = "
          "{}}> : (" +
          bits + ", " + bits + ") -> ()\n" + sampled,
      hierarchyRequirement("alien") + port("value", bits, "input", "alien") +
          driver("driver", "exported") + literal("enable", boolean) +
          netDrive("driver", "exported"));
  foreign = replace(foreign, "domain_bindings = {required = @clock}",
                    "domain_bindings = {required = @clock, alien = @other}");
  foreign = replace(foreign, hierarchyBinding("required", "clock"),
                    hierarchyBinding("required", "clock") + hierarchyBinding("alien", "other"));
  hierarchy("resolved-child-domain-label-cannot-hide-actual-foreign-driver", foreign, false,
            "foreign or unresolved input domain");
  std::string actualSame = replace(foreign, "alien = @other", "alien = @clock");
  actualSame =
      replace(actualSame, hierarchyBinding("alien", "other"), hierarchyBinding("alien", "clock"));
  actualSame =
      replace(actualSame,
              "sym_name = \"input\", type = " + bits + ", direction = \"input\", domain = @other",
              "sym_name = \"input\", type = " + bits + ", direction = \"input\", domain = @clock");
  hierarchy("resolved-child-actual-same-domain-driver-is-observed", actualSame, true);
  hierarchy("standalone-child-foreign-driver-remains-causal", asStandaloneBoundary(foreign), false,
            "foreign or unresolved input domain");
  hierarchy("standalone-child-same-domain-driver-is-observed", asStandaloneBoundary(actualSame),
            true);

  std::string attributedRead =
      resolvedRead("projected", "sampled") +
      "%synced = \"nodal.crossing\"(%sampled) <{source_domain = @clock, destination_domain = "
      "@other, "
      "kind = \"sync\", contract = \"level_v1\", stages = 2 : i64, metadata = "
      "{semantic_path = \"Top.sync\"}}> : (" +
      bits + ") -> " + bits + "\n";
  std::string unAttributed = hierarchyResolvedFixture(attributedRead, "", "sink", true, false);
  while (unAttributed.find(bits) != std::string::npos)
    unAttributed = replace(unAttributed, bits, boolean);
  hierarchy("resolved-unaccessed-child-is-not-attributed-by-domain-label", unAttributed, false,
            "actual attributed dependency evidence");
  std::string opaqueSampling =
      externalFixture(hierarchyDomain("clock") + hierarchyDomain("other") + attributedRead);
  while (opaqueSampling.find(bits) != std::string::npos)
    opaqueSampling = replace(opaqueSampling, bits, boolean);
  hierarchy("external-inout-is-an-unannotated-source-not-a-constant", opaqueSampling, false,
            "crossing input contains an unannotated source boundary");
  std::string opaqueDrivenSampling = externalFixture(
      hierarchyDomain("clock") + hierarchyDomain("other") + port("value", bits, "input", "clock") +
      driver("driver", "projected") + literal("enable", boolean) + netDrive("driver", "projected") +
      attributedRead);
  while (opaqueDrivenSampling.find(bits) != std::string::npos)
    opaqueDrivenSampling = replace(opaqueDrivenSampling, bits, boolean);
  hierarchy("external-contribution-remains-unannotated-alongside-attributed-visible-driver",
            opaqueDrivenSampling, false, "crossing input contains an unannotated source boundary");
  hierarchy("ordinary-tagged-pin-retains-attributed-visible-driver-without-external-contribution",
            replace(opaqueDrivenSampling, ", external_name = \"ExternalPad\"", ""), true);
  hierarchy("child-output-actual-crossing", hierarchyFixture(), true);
  hierarchy("child-input-forwarding", hierarchyFixture(false, true), true);
  hierarchy("child-output-domain-label-does-not-hide-source", hierarchyFixture(true), false);
  hierarchy("undriven-child-output-rejected",
            replace(hierarchyFixture(), drive("exported", "synced", boolean), ""), false);
  hierarchy("undriven-child-input-rejected",
            replace(hierarchyFixture(false, true), connect("top", "projected", boolean), ""),
            false);
  hierarchy("complete-hidden-member-ABI-match", hiddenFixture(), true);
  hierarchy("sibling-only-complete-ABI-match", siblingHiddenFixture(false), true);
  hierarchy("sibling-only-hidden-ABI-mismatch", siblingHiddenFixture(true), false);
  hierarchy("sibling-imported-extra-role-match", siblingRoleFixture(false), true);
  hierarchy("sibling-imported-extra-role-mismatch", siblingRoleFixture(true), false);
  if (diagnosticText.find("identically named role map") == std::string::npos) {
    ++failures;
    llvm::errs() << "DIGITAL_INTERFACE_MODEL_FAIL sibling-extra-role-causal-control\n";
  }
  hierarchy("unaccessed-child-member-type-mismatch", hiddenFixture(bits), false);
  hierarchy("unaccessed-child-role-map-mismatch", hiddenFixture(boolean, true), false);
  hierarchy("missing-child-endpoint",
            replace(hierarchyFixture(), "endpoint = \"exported\"", "endpoint = \"absent\""), false);
  hierarchy(
      "wrong-child-instance",
      replace(hierarchyFixture(), "instance = @child, endpoint", "instance = @absent, endpoint"),
      false);
  hierarchy("duplicate-projection-physical-identity",
            replace(hierarchyFixture(), hierarchyBoundary(),
                    hierarchyBoundary() + replace(hierarchyBoundary(), "sym_name = \"projected\"",
                                                  "sym_name = \"duplicate\"")),
            false);
  hierarchy("output-write-rejected",
            replace(hierarchyFixture(), connect("top", "projected", boolean),
                    drive("projected", "input", boolean)),
            false);
  hierarchy("declared-role-view-rejected",
            replace(hierarchyFixture(), member("projected", "projected", boolean),
                    member("projected", "projected", boolean, "sink")),
            false);
  hierarchy("concrete-whole-array-forwarding", hierarchyArrayFixture("P", false), true);
  hierarchy("symbolic-whole-array-exact-substitution", hierarchyArrayFixture(), true);
  hierarchy("symbolic-array-equal-bounds-other-parameter", hierarchyArrayFixture("Q"), false);
  hierarchy("symbolic-array-default-is-not-binding", hierarchyArrayFixture("2"), false);
  hierarchy("symbolic-array-missing-substitution-metadata",
            replace(hierarchyArrayFixture(),
                    "semantic_path = \"Top.projected\", dimensions = [\"P\"]",
                    "semantic_path = \"Top.projected\""),
            false);
  hierarchy("symbolic-array-forged-rank",
            replace(hierarchyArrayFixture(),
                    "semantic_path = \"Top.projected\", dimensions = [\"P\"]",
                    "semantic_path = \"Top.projected\", dimensions = [\"P\", \"2\"]"),
            false);
  hierarchy("scalar-boundary-cannot-acquire-array-rank",
            replace(hierarchyFixture(), "semantic_path = \"Top.projected\"",
                    "semantic_path = \"Top.projected\", dimensions = [\"2\"]"),
            false);
  hierarchy("symbolic-payload-exact-parent-instantiation", hierarchyPayloadFixture(), true);
  hierarchy("symbolic-payload-complete-no-access", hierarchyPayloadFixture("P", false), true);
  hierarchy("symbolic-payload-equal-bounds-other-identity", hierarchyPayloadFixture("Q", false),
            false);
  hierarchy("symbolic-payload-default-not-actual", hierarchyPayloadFixture("2", false), false);
  hierarchy("symbolic-payload-missing-actual-inventory",
            replace(hierarchyPayloadFixture("P", false),
                    ", actual_types = [{path = \"data\", type = !nodal.digital<\"uint\", \"P\">}]",
                    ""),
            false);
  hierarchy("symbolic-payload-forged-member-name",
            replace(hierarchyPayloadFixture("P", false), "actual_types = [{path = \"data\"",
                    "actual_types = [{path = \"other\""),
            false);
  hierarchy("symbolic-payload-duplicate-actual-member",
            replace(hierarchyPayloadFixture("P", false),
                    "actual_types = [{path = \"data\", type = !nodal.digital<\"uint\", \"P\">}]",
                    "actual_types = [{path = \"data\", type = !nodal.digital<\"uint\", \"P\">}, "
                    "{path = \"data\", type = !nodal.digital<\"uint\", \"P\">}]"),
            false);
  hierarchy("symbolic-payload-same-definition-sibling-context-match",
            hierarchyPayloadFixture("P", false, "P"), true);
  hierarchy("symbolic-payload-same-definition-sibling-context-mismatch",
            hierarchyPayloadFixture("P", false, "Q"), false);
  hierarchy("repeated-payload-types-preserve-exact-contexts", repeatedPayloadFixture(), true);
  hierarchy("repeated-payload-types-distinct-sibling-binding", repeatedPayloadFixture("Q"), false);
  hierarchy("repeated-payload-types-forged-last-actual", repeatedPayloadFixture("P", true), false);
  auto endpointQuery = [&](StringRef name, const std::string &source, bool expected, auto inspect) {
    ++cases;
    diagnosticText.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    bool passed = false;
    if (parsed && !parsed->getBody()->empty()) {
      Operation *owner = nullptr;
      for (Operation &operation : *parsed->getBody())
        if (operation.getName().getStringRef() == "nodal.module") {
          owner = &operation;
          break;
        }
      auto before = print(parsed->getOperation());
      SmallVector<nodal::DigitalInterfaceEndpoint> endpoints(1);
      endpoints.front().operation = owner;
      bool valid = owner && succeeded(nodal::collectDigitalInterfaceEndpoints(owner, endpoints));
      passed = valid == expected && before == print(parsed->getOperation()) &&
               endpoints.front().operation == owner &&
               (valid ? inspect(ArrayRef<nodal::DigitalInterfaceEndpoint>(endpoints).drop_front())
                      : endpoints.size() == 1);
    }
    failures += !passed;
    if (!passed)
      llvm::errs() << diagnosticText;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  auto symbolName = [](Operation *operation) {
    return operation->getAttrOfType<StringAttr>("sym_name").getValue();
  };
  const std::string generatedEndpoint = hiddenInstance("inside", "clock");
  const std::string generatedTemplate =
      generatedInterface(generatedEndpoint + generatedInterfaceExport());
  const std::string generatedSelected = domain() + hiddenDefinition() + generatedTemplate +
                                        literal("ordinal", "i64", "1") + selectedInterface();
  auto generatedQuery = [&](StringRef name, const std::string &body, bool expected) {
    endpointQuery(name, model(body), expected, [](const auto &) { return true; });
  };
  endpointQuery(
      "generated-interface-retains-one-template-and-complete-selected-role",
      model(generatedSelected), true, [&](const auto &endpoints) {
        return endpoints.size() == 2 && endpoints[0].declaration == endpoints[0].operation &&
               !endpoints[0].selection && endpoints[1].selection == endpoints[1].operation &&
               endpoints[1].declaration == endpoints[0].declaration && !endpoints[1].instance &&
               endpoints[1].declarationOwner == endpoints[1].domainOwner &&
               endpoints[0].leaves.size() == 2 && endpoints[1].leaves.size() == 2 &&
               endpoints[1].leaves[1].path == "hidden" && endpoints[1].leaves[1].access == "out" &&
               endpoints[1].domain.getValue() == "clock";
      });
  generatedQuery("generated-interface-empty-template-inventory",
                 domain() + hiddenDefinition() + generatedInterface(generatedEndpoint, "0"), true);
  generatedQuery("generated-interface-million-lanes-one-template",
                 domain() + hiddenDefinition() + generatedInterface(generatedEndpoint, "1000000"),
                 true);
  generatedQuery("generated-interface-unaccessed-leaf-ABI-forgery",
                 replace(generatedSelected, "path = \"hidden\", type = !nodal.bits<1>, role",
                         "path = \"hidden\", type = !nodal.bits<8>, role"),
                 false);
  generatedQuery("generated-interface-exact-export-target",
                 replace(generatedSelected, "target = @inside", "target = @absent"), false);
  generatedQuery("generated-interface-export-precedes-target-rejected",
                 replace(generatedSelected, generatedEndpoint + generatedInterfaceExport(),
                         generatedInterfaceExport() + generatedEndpoint),
                 false);
  generatedQuery("generated-interface-exact-export-member",
                 replace(generatedSelected, "member = \"endpoint\", metadata = {" + tag,
                         "member = \"absent\", metadata = {" + tag),
                 false);
  generatedQuery("generated-interface-exact-selected-endpoint",
                 replace(generatedSelected, "endpoint = \"inside\"", "endpoint = \"absent\""),
                 false);
  generatedQuery("generated-interface-cannot-claim-child-instance",
                 replace(generatedSelected, "endpoint = \"inside\"",
                         "instance = @inside, endpoint = \"inside\""),
                 false);
  generatedQuery(
      "generated-interface-exact-domain",
      replace(generatedSelected, "domain = @clock, dimensions", "domain = @absent, dimensions"),
      false);
  generatedQuery(
      "generated-interface-last-ordinal",
      replace(generatedSelected, literal("ordinal", "i64", "1"), literal("ordinal", "i64", "2")),
      true);
  generatedQuery(
      "generated-interface-past-last-ordinal",
      replace(generatedSelected, literal("ordinal", "i64", "1"), literal("ordinal", "i64", "3")),
      false);
  generatedQuery(
      "generated-interface-negative-ordinal",
      replace(generatedSelected, literal("ordinal", "i64", "1"), literal("ordinal", "i64", "-1")),
      false);
  generatedQuery("generated-interface-empty-selection-rejected",
                 replace(generatedSelected, generatedTemplate,
                         generatedInterface(generatedEndpoint + generatedInterfaceExport(), "0")),
                 false);
  generatedQuery("generated-interface-selected-member-type-forgery",
                 generatedSelected + member("selectedData", "selected", bits), false);
  generatedQuery("generated-interface-selected-role-cannot-invert",
                 generatedSelected + member("selectedData", "selected", boolean, "sink"), false);
  generatedQuery("generated-interface-selected-role-cannot-monitor-alias",
                 generatedSelected + member("selectedData", "selected", boolean, "monitor"), false);
  generatedQuery("generated-interface-selected-member-exact-type",
                 generatedSelected + member("selectedData", "selected", boolean), true);
  generatedQuery("generated-interface-selected-write-exact-physical-owner",
                 generatedSelected + member("selectedData", "selected", boolean) +
                     literal("value", boolean) + drive("selectedData", "value", boolean),
                 true);
  generatedQuery("generated-interface-distinct-lanes-retain-distinct-selection",
                 generatedSelected + literal("otherOrdinal", "i64", "2") +
                     selectedInterface("other", "otherOrdinal"),
                 true);
  generatedQuery("generated-interface-duplicate-lane-reuses-identity",
                 generatedSelected + literal("otherOrdinal", "i64", "1") +
                     selectedInterface("other", "otherOrdinal"),
                 false);
  generatedQuery("generated-interface-direct-reference-cannot-escape-template",
                 generatedSelected + member("escaped", "inside", boolean), false);
  generatedQuery(
      "generated-interface-forged-direct-member-carrier",
      replace(generatedSelected, selectedInterface(),
              "\"nodal.interface_boundary\"() <{sym_name = \"selected\", instance = @inside, "
              "endpoint = \"inside\", member = \"endpoint\", metadata = {" +
                  tag + ", semantic_path = \"Fixture.selected\"}}> : () -> ()\n"),
      false);
  std::string generatedArray =
      domain() + definition() +
      generatedInterface(instance("inside", "source", "out", bits, "\"2\"") +
                         generatedInterfaceExport()) +
      literal("ordinal", "i64", "0") +
      replace(selectedInterface(), "dimensions = []", "dimensions = [\"2\"]");
  generatedQuery("generated-interface-selected-array-exact-rank", generatedArray, true);
  generatedQuery("generated-interface-selected-array-forged-extent",
                 replace(generatedArray,
                         "semantic_path = \"Fixture.selected\", dimensions = [\"2\"]",
                         "semantic_path = \"Fixture.selected\", dimensions = [\"3\"]"),
                 false);
  generatedQuery("generated-interface-selected-array-missing-rank",
                 replace(generatedArray,
                         "semantic_path = \"Fixture.selected\", dimensions = [\"2\"]",
                         "semantic_path = \"Fixture.selected\", dimensions = []"),
                 false);
  const std::string innerTemplate =
      generatedInterface(generatedEndpoint + generatedInterfaceExport(), "2", "inner");
  const std::string nestedExport = "\"nodal.generated_export\"(%inner) <{member = \"nested\", "
                                   "access = \"read\", kind = \"family\", "
                                   "metadata = {}}> : (!nodal.generated_family) -> ()\n";
  const std::string nestedSelection = "%view = \"nodal.generated_member\"(%family, %ordinal) "
                                      "<{member = \"nested\", kind = \"family\", "
                                      "access = \"read\", metadata = {}}> : "
                                      "(!nodal.generated_family, i64) -> !nodal.generated_family\n";
  const std::string nestedSelected = domain() + hiddenDefinition() +
                                     generatedInterface(innerTemplate + nestedExport) +
                                     literal("ordinal", "i64", "1") + nestedSelection +
                                     selectedInterface("selected", "ordinal", "view");
  generatedQuery("generated-interface-nested-selected-prefix", nestedSelected, true);
  generatedQuery("generated-interface-wrong-prefix-root",
                 replace(nestedSelected, "(%view, %ordinal)", "(%family, %ordinal)"), false);
  generatedQuery(
      "generated-interface-nested-upper-ordinal",
      replace(nestedSelected, literal("ordinal", "i64", "1"), literal("ordinal", "i64", "2")),
      false);
  const std::string selectedGuard =
      literal("guardLo", "i64", "0") + literal("guardHi", "i64", "1") +
      "%condition = \"nodal.static_compare\"(%guardLo, %guardHi) <{comparison = \"lt\", "
      "metadata = {semantic_path = \"Fixture.guard_pred\"}}> : (i64, i64) -> "
      "!nodal.static_predicate\n"
      "\"nodal.structural_if\"(%condition, %family, %ordinal) <{contract_version = "
      "\"structural-if-v1\", "
      "region_id = \"Fixture.guard\", operandSegmentSizes = array<i32: 1, 2, 0>, "
      "metadata = {semantic_path = \"Fixture.guard\"}}> ({^bb0(%captured: !nodal.generated_family, "
      "%capturedOrdinal: i64):\n" +
      selectedInterface("selected", "capturedOrdinal", "captured") +
      member("selectedData", "selected", boolean) + observe("selectedData", boolean) +
      "\"nodal.structural_yield\"() <{metadata = {semantic_path = \"Fixture.guard.then.yield\"}}> "
      ": () -> ()\n}, {\n"
      "\"nodal.structural_yield\"() <{metadata = {semantic_path = \"Fixture.guard.else.yield\"}}> "
      ": () -> ()\n}) : (!nodal.static_predicate, !nodal.generated_family, i64) -> ()\n";
  const std::string guardedSelected =
      replace(generatedSelected, selectedInterface(), selectedGuard);
  generatedQuery("generated-interface-exact-guarded-family-and-ordinal-captures", guardedSelected,
                 true);
  generatedQuery(
      "generated-interface-guarded-family-capture-cannot-be-skipped",
      replace(guardedSelected, "(%captured, %capturedOrdinal)", "(%family, %capturedOrdinal)"),
      false);
  generatedQuery("generated-interface-guarded-ordinal-capture-cannot-be-skipped",
                 replace(guardedSelected, "(%captured, %capturedOrdinal)", "(%captured, %ordinal)"),
                 false);
  generatedQuery("generated-interface-guarded-direct-reference-cannot-bypass-capture",
                 replace(guardedSelected, member("selectedData", "selected", boolean),
                         member("selectedData", "inside", boolean)),
                 false);
  std::string bothArms =
      replace(guardedSelected, "structural_if\"(%condition, %family, %ordinal)",
              "structural_if\"(%condition, %family, %ordinal, %family, %ordinal)");
  bothArms = replace(bothArms, "array<i32: 1, 2, 0>", "array<i32: 1, 2, 2>");
  bothArms = replace(bothArms, "(!nodal.static_predicate, !nodal.generated_family, i64) -> ()",
                     "(!nodal.static_predicate, !nodal.generated_family, i64, "
                     "!nodal.generated_family, i64) -> ()");
  bothArms =
      replace(bothArms, "}, {\n",
              "}, {^bb0(%otherCaptured: !nodal.generated_family, %otherCapturedOrdinal: i64):\n" +
                  selectedInterface("otherSelected", "otherCapturedOrdinal", "otherCaptured"));
  generatedQuery("generated-interface-same-selection-in-distinct-arms-retains-one-declaration",
                 bothArms, true);
  generatedQuery("generated-interface-selected-symbol-cannot-escape-arm",
                 guardedSelected + member("escaped", "selected", boolean), false);
  const std::string generatedChild =
      "\"nodal.instance\"() <{sym_name = \"child\", module = @Child, parameter_bindings = {}, "
      "domain_bindings = {required = @clock}, generated_owner = \"Fixture.family\", "
      "generated_induction = \"Fixture.family.i\", metadata = {}}> : () -> ()\n";
  const std::string selectedChildModule =
      bareModule("Child", hierarchyRequirement("required") + hiddenDefinition() +
                              hiddenInstance("exported", "required"));
  const std::string selectedChildBody =
      hierarchyDomain("clock") +
      generatedInterface(generatedChild + hierarchyBinding("required", "clock") +
                         generatedInterfaceExport("child", "child", "instance")) +
      literal("ordinal", "i64", "1") +
      selectedInterface("selected", "ordinal", "family", "child", "exported",
                        "instance = @child, ");
  auto selectedChildSource = [&](StringRef body) {
    return "module {\n" + bareModule("Fixture", body) + selectedChildModule + "}\n";
  };
  auto childQuery = [&](StringRef name, const std::string &body, bool expected) {
    endpointQuery(name, selectedChildSource(body), expected, [](const auto &) { return true; });
  };
  endpointQuery("generated-child-interface-exact-immediate-endpoint-and-binding",
                selectedChildSource(selectedChildBody), true, [&](const auto &endpoints) {
                  return endpoints.size() == 1 &&
                         endpoints[0].selection == endpoints[0].operation &&
                         endpoints[0].instance && symbolName(endpoints[0].instance) == "child" &&
                         symbolName(endpoints[0].declaration) == "exported" &&
                         endpoints[0].declarationOwner != endpoints[0].domainOwner &&
                         endpoints[0].domain.getValue() == "clock" &&
                         endpoints[0].leaves.size() == 2;
                });
  childQuery("generated-child-interface-forged-instance-identity",
             replace(selectedChildBody, "member = \"child\", instance = @child",
                     "member = \"child\", instance = @absent"),
             false);
  childQuery("generated-child-interface-forged-endpoint",
             replace(selectedChildBody, "endpoint = \"exported\"", "endpoint = \"absent\""), false);
  childQuery("generated-child-interface-missing-domain-binding",
             replace(selectedChildBody, hierarchyBinding("required", "clock"), ""), false);
  childQuery("generated-child-interface-wrong-domain-actual",
             replace(selectedChildBody, "actual = @clock", "actual = @absent"), false);
  childQuery("generated-child-interface-forged-physical-clock",
             hierarchyDomain("other") + replace(selectedChildBody,
                                                "nodal.instance_domain_binding\"(%clock_clock)",
                                                "nodal.instance_domain_binding\"(%clock_other)"),
             false);
  childQuery("generated-child-interface-exact-output-role",
             selectedChildBody + member("selectedData", "selected", boolean) +
                 literal("value", boolean) + drive("selectedData", "value", boolean),
             false);
  const std::string selectedFormal = "!nodal.digital<\"uint\", \"N\">";
  const std::string selectedDependentChild = bareModule(
      "Child", hierarchyRequirement("required") +
                   replace(parameter(), "default_value = 2 : i64", "default_value = 1 : i64") +
                   definition(selectedFormal) +
                   instance("exported", "source", "out", selectedFormal, "", "required"));
  const std::string selectedOverride =
      "\"nodal.parameter_override\"(%family_i) <{instance = @child, parameter = @N, "
      "generated_owner = \"Fixture.family\", generated_induction = \"Fixture.family.i\", "
      "metadata = {}}> : (i64) -> ()\n";
  const std::string selectedActual =
      replace(selectedInterface("selected", "ordinal", "family", "child", "exported",
                                "instance = @child, "),
              "dimensions = []",
              "dimensions = [], actual_types = [{path = \"data\", type = !nodal.uint<2>}]");
  const std::string dependentSelectedBody =
      hierarchyDomain("clock") +
      generatedInterface(generatedChild + selectedOverride + hierarchyBinding("required", "clock") +
                             generatedInterfaceExport("child", "child", "instance"),
                         "4", "family", "1") +
      literal("ordinal", "i64", "1") + selectedActual;
  auto dependentSelectedQuery = [&](StringRef name, const std::string &body, bool expected) {
    endpointQuery(name, "module {\n" + bareModule("Fixture", body) + selectedDependentChild + "}\n",
                  expected, [](const auto &) { return true; });
  };
  dependentSelectedQuery("generated-child-interface-exact-induction-selected-width",
                         dependentSelectedBody, true);
  dependentSelectedQuery(
      "generated-child-interface-selected-width-not-default",
      replace(dependentSelectedBody, "type = !nodal.uint<2>", "type = !nodal.uint<1>"), false);
  dependentSelectedQuery(
      "generated-child-interface-selected-width-not-envelope",
      replace(dependentSelectedBody, "type = !nodal.uint<2>", "type = !nodal.uint<3>"), false);
  dependentSelectedQuery("generated-child-interface-selected-width-retains-ordinal",
                         replace(dependentSelectedBody, literal("ordinal", "i64", "1"),
                                 literal("ordinal", "i64", "0")),
                         false);
  dependentSelectedQuery(
      "generated-child-interface-selected-symbolic-width-needs-complete-actual-types",
      replace(dependentSelectedBody, ", actual_types = [{path = \"data\", type = !nodal.uint<2>}]",
              ""),
      false);
  dependentSelectedQuery("generated-child-interface-complete-semantic-type-under-selection",
                         definition("!nodal.uint<2>") + dependentSelectedBody, true);
  dependentSelectedQuery("generated-child-interface-forged-complete-semantic-selected-type",
                         definition("!nodal.uint<1>") + dependentSelectedBody, false);
  const std::string secondSelectedLane =
      literal("secondOrdinal", "i64", "2") +
      replace(replace(replace(selectedActual, "sym_name = \"selected\"", "sym_name = \"second\""),
                      "Fixture.selected", "Fixture.second"),
              "(%family, %ordinal)", "(%family, %secondOrdinal)");
  const std::string twoDependentSelections =
      dependentSelectedBody +
      replace(secondSelectedLane, "type = !nodal.uint<2>", "type = !nodal.uint<3>");
  dependentSelectedQuery("generated-child-interface-distinct-lanes-share-original-definition",
                         twoDependentSelections, true);
  dependentSelectedQuery("generated-child-interface-second-lane-keeps-exact-width",
                         dependentSelectedBody + secondSelectedLane, false);
  dependentSelectedQuery("generated-child-interface-second-lane-keeps-exact-prefix",
                         replace(twoDependentSelections, literal("secondOrdinal", "i64", "2"),
                                 literal("secondOrdinal", "i64", "0")),
                         false);
  dependentSelectedQuery("generated-child-interface-distinct-local-definition-still-compares-ABI",
                         definition("!nodal.uint<2>") + twoDependentSelections, false);
  hierarchy(
      "generated-child-interface-two-widths-full-gate",
      "module attributes {nodal.root.module = @Fixture, nodal.root.parameter_bindings = {}} {\n" +
          bareModule("Fixture", twoDependentSelections) + selectedDependentChild + "}\n",
      true);
  // Independent ingress must check every attained original induction value, not
  // only the selected lane's substituted width.
  const std::string excessiveGeneratedDomain = replace(
      dependentSelectedBody, literal("family_hi", "i64", "4"), literal("family_hi", "i64", "6"));
  dependentSelectedQuery(
      "generated-child-interface-selected-valid-lane-cannot-hide-invalid-other-lane",
      replace(excessiveGeneratedDomain, "maximum_trip_count = 3 : i64",
              "maximum_trip_count = 5 : i64"),
      false);
  const std::string nonunitSelectedBody =
      hierarchyDomain("clock") +
      generatedInterface(generatedChild + selectedOverride + hierarchyBinding("required", "clock") +
                             generatedInterfaceExport("child", "child", "instance"),
                         "5", "family", "2", "2") +
      literal("ordinal", "i64", "1") +
      replace(selectedActual, "type = !nodal.uint<2>", "type = !nodal.uint<4>");
  dependentSelectedQuery("generated-child-interface-nonzero-nonunit-original-coordinate",
                         nonunitSelectedBody, true);
  dependentSelectedQuery(
      "generated-child-interface-nonunit-ordinal-is-not-original-coordinate",
      replace(nonunitSelectedBody, "type = !nodal.uint<4>", "type = !nodal.uint<1>"), false);

  auto rooted = [&](const std::string &source) {
    return replace(source, "module {", "module attributes {nodal.root.module = @Fixture} {");
  };
  auto fullGenerated = [&](StringRef name, const std::string &body, bool expected,
                           StringRef expectedDiagnostic = "") {
    hierarchy(name, rooted(model(body)), expected, expectedDiagnostic);
  };
  fullGenerated("generated-interface-full-gate-unaccessed-template",
                domain() + hiddenDefinition() + generatedInterface(generatedEndpoint), true);
  fullGenerated("generated-interface-full-gate-empty-template",
                domain() + hiddenDefinition() + generatedInterface(generatedEndpoint, "0"), true);
  fullGenerated(
      "generated-interface-full-gate-local-drive-and-observe",
      domain() + definition(boolean) +
          generatedInterface(instance("inside", "source", "out", boolean) +
                             member("local", "inside", boolean) + literal("value", boolean) +
                             drive("local", "value", boolean) + observe("local", boolean)),
      true);
  fullGenerated("generated-interface-full-gate-local-connect",
                domain() + definition(boolean) +
                    generatedInterface(
                        instance("inside", "source", "out", boolean) +
                        instance("sink", "sink", "in", boolean) +
                        member("local", "inside", boolean) + member("target", "sink", boolean) +
                        literal("value", boolean) + drive("local", "value", boolean) +
                        connect("local", "target", boolean)),
                true);
  fullGenerated("generated-interface-full-gate-selected-unaccessed", generatedSelected, true);
  fullGenerated("generated-interface-full-gate-selected-read",
                generatedSelected + member("selectedData", "selected", boolean) +
                    observe("selectedData", boolean),
                true);
  fullGenerated("generated-interface-full-gate-guarded-captures", guardedSelected, true);
  fullGenerated("generated-interface-full-gate-opposite-arms", bothArms, true);
  fullGenerated(
      "generated-interface-full-gate-guarded-capture-bypass",
      replace(guardedSelected, "(%captured, %capturedOrdinal)", "(%family, %capturedOrdinal)"),
      false, "capture");
  fullGenerated("generated-interface-full-gate-selected-write-exact-physical-owner",
                generatedSelected + member("selectedData", "selected", boolean) +
                    literal("value", boolean) + drive("selectedData", "value", boolean),
                true);
  fullGenerated("generated-interface-full-gate-selected-role-cannot-invert",
                generatedSelected + member("selectedData", "selected", boolean, "sink"), false,
                "declared role");
  fullGenerated("generated-interface-full-gate-complete-hidden-ABI",
                replace(generatedSelected, "path = \"hidden\", type = !nodal.bits<1>, role",
                        "path = \"hidden\", type = !nodal.bits<8>, role"),
                false, "type");
  hierarchy("generated-child-interface-full-gate-unaccessed",
            rooted(selectedChildSource(selectedChildBody)), true);
  hierarchy("generated-child-interface-full-gate-induction-width",
            rooted("module {\n" + bareModule("Fixture", dependentSelectedBody) +
                   selectedDependentChild + "}\n"),
            true);
  hierarchy("generated-child-interface-full-gate-nonunit-induction-width",
            rooted("module {\n" + bareModule("Fixture", nonunitSelectedBody) +
                   selectedDependentChild + "}\n"),
            true);
  dependentSelectedQuery("generated-child-interface-forged-override-owner",
                         replace(dependentSelectedBody, selectedOverride,
                                 replace(selectedOverride, "generated_owner = \"Fixture.family\"",
                                         "generated_owner = \"Fixture.forged\"")),
                         false);
  dependentSelectedQuery(
      "generated-child-interface-forged-override-induction",
      replace(dependentSelectedBody, selectedOverride,
              replace(selectedOverride, "generated_induction = \"Fixture.family.i\"",
                      "generated_induction = \"Fixture.family.j\"")),
      false);
  fullGenerated("generated-interface-full-gate-unaccessed-duplicate-name",
                generatedSelected + literal("otherOrdinal", "i64", "2") +
                    selectedInterface("selected", "otherOrdinal"),
                false, "unique exact lexical name");
  fullGenerated("generated-interface-full-gate-distinct-arms-may-reuse-local-name",
                replace(bothArms, "sym_name = \"otherSelected\"", "sym_name = \"selected\""), true);
  const std::string selectedWriter =
      generatedSelected + member("selectedData", "selected", boolean) +
      literal("chosen", boolean, "1") + drive("selectedData", "chosen", boolean);
  const std::string twoSelectedWriters =
      selectedWriter + literal("otherOrdinal", "i64", "2") +
      selectedInterface("other", "otherOrdinal") + member("otherData", "other", boolean) +
      literal("otherValue", boolean, "0") + drive("otherData", "otherValue", boolean);
  fullGenerated("generated-interface-full-gate-distinct-selected-writers", twoSelectedWriters,
                true);
  fullGenerated("generated-interface-full-gate-repeated-member-writer-collision",
                selectedWriter + member("aliasData", "selected", boolean) +
                    drive("aliasData", "chosen", boolean),
                false, "multiple authored drivers");
  const std::string templateDriver = generatedInterface(
      generatedEndpoint + member("templateData", "inside", boolean) +
      literal("templateValue", boolean, "1") + drive("templateData", "templateValue", boolean) +
      generatedInterfaceExport());
  fullGenerated("generated-interface-full-gate-template-selected-writer-collision",
                replace(selectedWriter, generatedTemplate, templateDriver), false,
                "overlapping or unproved selected drivers");
  fullGenerated("generated-interface-full-gate-selected-read-retains-template-driver",
                replace(generatedSelected, generatedTemplate, templateDriver) +
                    member("selectedData", "selected", boolean) + observe("selectedData", boolean),
                true);
  auto dependencyQuery = [&](StringRef name, const std::string &body, bool expected, auto inspect) {
    ++cases;
    diagnosticText.clear();
    auto parsed =
        parseSourceString<mlir::ModuleOp>(rooted(model(body)), ParserConfig(&context, false));
    bool passed = false;
    if (parsed) {
      Operation *owner = &parsed->getBody()->front();
      auto before = print(parsed->getOperation());
      SmallVector<nodal::DigitalInterfaceValueDependencies> dependencies;
      bool valid = succeeded(nodal::collectDigitalInterfaceDependencies(owner, dependencies));
      passed = valid == expected && before == print(parsed->getOperation()) &&
               (valid ? inspect(dependencies) : dependencies.empty());
    }
    failures += !passed;
    if (!passed)
      llvm::errs() << diagnosticText;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  dependencyQuery(
      "generated-interface-dependency-keeps-distinct-selected-lanes", twoSelectedWriters, true,
      [&](const auto &dependencies) {
        if (dependencies.size() != 2)
          return false;
        for (const auto &entry : dependencies) {
          auto endpoint =
              entry.value.getDefiningOp()->template getAttrOfType<FlatSymbolRefAttr>("instance");
          if (!endpoint || entry.sources.size() != 1)
            return false;
          auto value =
              entry.sources[0].first.getDefiningOp()->template getAttrOfType<IntegerAttr>("value");
          if (!value || value.getInt() != (endpoint.getValue() == "selected" ? 1 : 0))
            return false;
        }
        return true;
      });
  dependencyQuery(
      "generated-interface-dependency-selected-read-follows-template-rhs",
      replace(generatedSelected, generatedTemplate, templateDriver) +
          member("selectedData", "selected", boolean),
      true, [&](const auto &dependencies) {
        if (dependencies.size() != 2)
          return false;
        for (const auto &entry : dependencies)
          if (entry.sources.size() != 1 ||
              !entry.sources[0].first.getDefiningOp()->template getAttrOfType<IntegerAttr>("value"))
            return false;
        return dependencies[0].sources[0].first == dependencies[1].sources[0].first;
      });
  dependencyQuery(
      "generated-interface-selected-writer-does-not-cover-template-domain",
      replace(selectedWriter, generatedTemplate,
              generatedInterface(generatedEndpoint + member("templateData", "inside", boolean) +
                                 generatedInterfaceExport())),
      false, [](const auto &) { return true; });
  const std::string unwrittenSelectedLane = selectedWriter + literal("otherOrdinal", "i64", "2") +
                                            selectedInterface("other", "otherOrdinal") +
                                            member("unwrittenData", "other", boolean) +
                                            observe("unwrittenData", boolean);
  fullGenerated("generated-interface-unwritten-lane-is-not-a-domain-only-source",
                unwrittenSelectedLane, false, "complete read coverage");
  dependencyQuery("generated-interface-unwritten-lane-query-refuses-partial-dependencies",
                  unwrittenSelectedLane, false, [](const auto &) { return true; });
  const std::string externalSelectedLane =
      domain() + definition(boolean) +
      generatedInterface(instance("inside", "sink", "in", boolean) + generatedInterfaceExport()) +
      literal("ordinal", "i64", "1") + selectedInterface() +
      member("externalData", "selected", boolean) + observe("externalData", boolean);
  fullGenerated("generated-interface-external-input-lane-retains-source-policy",
                externalSelectedLane, true);
  dependencyQuery("generated-interface-external-input-has-no-local-driver", externalSelectedLane,
                  true, [](const auto &dependencies) {
                    return dependencies.size() == 1 && dependencies[0].sources.empty() &&
                           dependencies[0].domain;
                  });
  const std::string callbackType = "!nodal.shaped<\"2\", !nodal.bits<1>>";
  auto pureCallback = [&](StringRef extra) {
    return domain() + definition(boolean) + instance("outside", "source", "out", boolean) +
           port("callbackInput", callbackType) +
           "%mapped = \"nodal.collection\"(%callbackInput) <{kind = \"map\", region_id = "
           "\"Fixture.map\", operandSegmentSizes = array<i32: 1, 0, 0>, metadata = {semantic_path "
           "= "
           "\"Fixture.map\"}}> ({^bb0(%element: !nodal.bits<1>):\n" +
           extra.str() +
           "\"nodal.collection_yield\"(%element) <{metadata = {}}> : (!nodal.bits<1>) -> ()\n}) : "
           "(" +
           callbackType + ") -> " + callbackType + "\n";
  };
  fullGenerated("interface-pure-callback-carrier-remains-valid", pureCallback(""), true);
  fullGenerated("interface-member-cannot-enter-pure-callback",
                pureCallback(member("illegal", "outside", boolean)), false,
                "exact lexical structural ownership");
  auto procedural = [&](StringRef extra) {
    return domain() + definition(boolean) + instance("outside", "source", "out", boolean) +
           literal("loopLo", "i64", "0") + literal("loopHi", "i64", "2") +
           literal("loopStep", "i64", "1") + port("loopInput", callbackType) +
           port("loopOutput", callbackType, "output") +
           "\"nodal.hardware_loop\"(%loopLo, %loopHi, %loopStep, %loopOutput, %loopInput) "
           "<{induction = \"i\", contract_version = \"bounded-digital-v1\", effect_policy = "
           "\"ordered\", "
           "region_id = \"Fixture.loop\", induction_path = \"Fixture.loop.i\", metadata = "
           "{semantic_path = \"Fixture.loop\"}}> ({^bb0(%iv: i64, %target: " +
           callbackType + ", %input: " + callbackType + "):\n" + extra.str() +
           "%dst = \"nodal.shape_index\"(%target, %iv) <{metadata = {}}> : (" + callbackType +
           ", i64) -> !nodal.bits<1>\n%src = \"nodal.shape_index\"(%input, %iv) <{metadata = {}}> "
           ": (" +
           callbackType + ", i64) -> !nodal.bits<1>\n" +
           "\"nodal.digital_assign\"(%dst, %src) <{kind = \"continuous\", metadata = {}}> : "
           "(!nodal.bits<1>, !nodal.bits<1>) -> ()\n\"nodal.hardware_loop_end\"() : () -> ()\n}) : "
           "(i64, i64, i64, " +
           callbackType + ", " + callbackType + ") -> ()\n";
  };
  fullGenerated("interface-procedural-carrier-remains-valid", procedural(""), true);
  fullGenerated("interface-member-cannot-enter-procedural-loop",
                procedural(member("illegal", "outside", boolean)), false,
                "exact lexical structural ownership");
  for (StringRef signedness : {StringRef("si64"), StringRef("ui64")}) {
    auto typedOrdinal = replace(generatedSelected, literal("ordinal", "i64", "1"),
                                literal("ordinal", signedness, "1"));
    typedOrdinal = replace(typedOrdinal, "(!nodal.generated_family, i64) -> ()",
                           "(!nodal.generated_family, " + signedness.str() + ") -> ()");
    generatedQuery("generated-interface-selected-ordinal-must-be-signless-" + signedness.str(),
                   typedOrdinal, false);
  }
  endpointQuery("projected-array-query-retains-child-formal-dimensions", hierarchyArrayFixture(),
                true, [&](const auto &endpoints) {
                  return endpoints.size() == 2 && endpoints[1].instance &&
                         endpoints[1].declarationOwner != endpoints[1].domainOwner &&
                         endpoints[1].dimensions.size() == 1 &&
                         llvm::cast<StringAttr>(endpoints[1].dimensions[0]).getValue() == "N";
                });
  endpointQuery("projected-payload-query-retains-formal-type-owner",
                hierarchyPayloadFixture("P", false), true, [&](const auto &endpoints) {
                  return endpoints.size() == 2 && endpoints[1].instance &&
                         endpoints[1].declarationOwner != endpoints[1].domainOwner &&
                         endpoints[1].leaves.size() == 1 &&
                         endpoints[1].leaves[0].type ==
                             endpoints[1]
                                 .leaves[0]
                                 .declaration->template getAttrOfType<TypeAttr>("type")
                                 .getValue() &&
                         endpoints[0].leaves[0].type != endpoints[1].leaves[0].type;
                });
  endpointQuery("logical-endpoints-retain-unaccessed-leaves-and-exact-boundary", hiddenFixture(),
                true, [&](auto endpoints) {
                  return endpoints.size() == 2 && symbolName(endpoints[0].operation) == "top" &&
                         !endpoints[0].instance &&
                         endpoints[0].declaration == endpoints[0].operation &&
                         endpoints[0].leaves.size() == 2 && endpoints[0].leaves[0].path == "data" &&
                         endpoints[0].leaves[1].path == "hidden" &&
                         symbolName(endpoints[1].operation) == "projected" &&
                         symbolName(endpoints[1].declaration) == "exported" &&
                         symbolName(endpoints[1].declarationOwner) == "Child" &&
                         symbolName(endpoints[1].domainOwner) == "Top" &&
                         symbolName(endpoints[1].instance) == "child" &&
                         symbolName(endpoints[1].selectedRole) == "source" &&
                         endpoints[1].semanticIdentity == "pkg.Bus" &&
                         endpoints[1].domain.getValue() == "b" && endpoints[1].leaves.size() == 2 &&
                         endpoints[1].leaves[1].access == "out" &&
                         !endpoints[1].leaves[1].resolvedComponent;
                });
  endpointQuery("logical-endpoints-retain-symbolic-width-and-ordered-shape",
                model(domain() + parameter() + definition(symbolic) +
                      instance("left", "source", "out", symbolic, "\"N\", \"2\"")),
                true, [&](auto endpoints) {
                  return endpoints.size() == 1 && endpoints[0].dimensions.size() == 2 &&
                         llvm::cast<StringAttr>(endpoints[0].dimensions[0]).getValue() == "N" &&
                         llvm::cast<StringAttr>(endpoints[0].dimensions[1]).getValue() == "2" &&
                         endpoints[0].leaves.size() == 1 &&
                         endpoints[0].leaves[0].type ==
                             endpoints[0]
                                 .leaves[0]
                                 .declaration->template getAttrOfType<TypeAttr>("type")
                                 .getValue();
                });
  endpointQuery(
      "logical-endpoints-do-not-expand-billion-lane-shape",
      model(domain() + definition() + instance("left", "source", "out", bits, "\"1000000000\"")),
      true, [](auto endpoints) {
        return endpoints.size() == 1 && endpoints[0].leaves.size() == 1 &&
               endpoints[0].dimensions.size() == 1 &&
               llvm::cast<StringAttr>(endpoints[0].dimensions[0]).getValue() == "1000000000";
      });
  endpointQuery("logical-endpoints-retain-nested-role-contract", model(nestedFixture()), true,
                [](auto endpoints) {
                  return endpoints.size() == 1 && endpoints[0].leaves.size() == 1 &&
                         endpoints[0].leaves[0].path == "nested.data" &&
                         endpoints[0].leaves[0].role == "source" &&
                         endpoints[0].leaves[0].nestedRoles.size() == 1 &&
                         endpoints[0].leaves[0].nestedInterfaces.size() == 1;
                });
  endpointQuery(
      "logical-endpoints-do-not-invent-unrepresented-resolved-net",
      model(domain() + resolvedDefinition() + instance("left", "sink", "connect", resolved())),
      true, [](auto endpoints) {
        return endpoints.size() == 1 && endpoints[0].leaves.size() == 1 &&
               endpoints[0].leaves[0].protocol == "resolved" &&
               !endpoints[0].leaves[0].resolvedComponent;
      });
  endpointQuery(
      "logical-endpoints-reuse-authored-order-resolved-component",
      model(domain() + resolvedDefinition() + instance("left", "sink", "connect", resolved()) +
            instance("right", "sink", "connect", resolved()) + net("earlier") +
            member("first", "left", resolved()) + member("alias", "left", resolved(), "monitor") +
            member("second", "right", resolved()) + connect("first", "second", resolved()) +
            through("first", "earlier")),
      true, [](auto endpoints) {
        if (endpoints.size() != 2 || endpoints[0].leaves.size() != 1 ||
            endpoints[1].leaves.size() != 1)
          return false;
        Value component = endpoints[0].leaves[0].resolvedComponent;
        return component && component == endpoints[1].leaves[0].resolvedComponent &&
               component.getDefiningOp()->getAttrOfType<StringAttr>("name").getValue() == "earlier";
      });
  endpointQuery("logical-endpoints-retain-conservative-capability",
                model(domain() + analogDefinition(true) + analogInstance(true), analogOwners()),
                true, [](auto endpoints) {
                  return endpoints.size() == 1 && endpoints[0].leaves.size() == 1 &&
                         endpoints[0].leaves[0].protocol == "conservative" &&
                         endpoints[0].leaves[0].access == "connect" &&
                         endpoints[0].leaves[0].analogMetadata.size() == 3 &&
                         endpoints[0].leaves[0].analogReferences.size() == 3 &&
                         !endpoints[0].leaves[0].resolvedComponent;
                });
  endpointQuery("logical-endpoints-retain-signal-flow-capability",
                model(domain() + analogDefinition(false) + analogInstance(false)), true,
                [](auto endpoints) {
                  return endpoints.size() == 1 && endpoints[0].leaves.size() == 1 &&
                         endpoints[0].leaves[0].protocol == "signal_flow" &&
                         endpoints[0].leaves[0].type.isF64() &&
                         endpoints[0].leaves[0].analogMetadata.size() == 1 &&
                         !endpoints[0].leaves[0].resolvedComponent;
                });
  endpointQuery("logical-endpoints-reject-forged-ABI-transactionally", hiddenFixture(bits), false,
                [](auto) { return true; });
  endpointQuery("logical-endpoints-reject-forged-shared-role-transactionally",
                siblingRoleFixture(true), false, [](auto) { return true; });
  // A whole resolved array is one represented pointwise family, not a scalar
  // alias joining its lanes. Geometry stays separate from the element payload.
  auto familyType = [&](StringRef dimensions) {
    return "!nodal.shaped<\"" + dimensions.str() + "\", " + resolved() + ">";
  };
  auto familyBody = [&](StringRef dimensions = "2,3", StringRef attributes = "\"2\", \"3\"") {
    auto type = familyType(dimensions);
    return domain() + resolvedDefinition() +
           instance("left", "sink", "connect", resolved(), attributes) +
           instance("right", "sink", "connect", resolved(), attributes) +
           member("a", "left", type) + member("alias", "left", type, "monitor") +
           member("b", "right", type) + connect("a", "b", type) + observe("alias", type);
  };
  auto familyQuery = [&](StringRef name, const std::string &source, bool expected, auto inspect) {
    ++cases;
    diagnosticText.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    bool passed = false;
    if (parsed) {
      Operation *owner = &parsed->getBody()->front();
      auto before = print(parsed->getOperation());
      SmallVector<nodal::DigitalInterfaceValueDependencies> dependencies;
      nodal::DigitalResolvedTopology topology;
      bool valid = succeeded(verify(parsed->getOperation())) &&
                   succeeded(nodal::collectDigitalInterfaceDependencies(owner, dependencies,
                                                                        nullptr, &topology));
      passed = valid == expected && before == print(parsed->getOperation()) &&
               (valid ? inspect(owner, topology, dependencies)
                      : topology.components.empty() && topology.endpoints.empty());
    }
    failures += !passed;
    if (!passed)
      llvm::errs() << diagnosticText;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  familyQuery(
      "resolved-family-unequal-axis-pointwise-alias", model(familyBody()), true,
      [&](Operation *owner, const auto &topology, const auto &dependencies) {
        if (topology.components.size() != 1 || topology.endpoints.size() != 2 ||
            dependencies.size() != 3)
          return false;
        const auto &component = topology.components.front();
        if (component.shape.owner != topology.endpoints[0].operation ||
            component.shape.owner->getParentOp() != owner ||
            component.shape.dimensions.size() != 2 || component.values.size() != 3 ||
            !component.drivers.empty() || !component.splits.empty())
          return false;
        for (const auto &endpoint : topology.endpoints)
          if (endpoint.shape.owner != endpoint.operation ||
              endpoint.type !=
                  llvm::cast<nodal::ShapedType>(component.value.getType()).getElementType() ||
              endpoint.component != component.value ||
              llvm::cast<StringAttr>(endpoint.shape.dimensions[0]).getValue() != "2" ||
              llvm::cast<StringAttr>(endpoint.shape.dimensions[1]).getValue() != "3" ||
              endpoint.shape.axes.size() != 2 || endpoint.shape.axes[0].literal != 2 ||
              endpoint.shape.axes[1].literal != 3 || endpoint.shape.axes[0].parameter ||
              endpoint.shape.axes[0].expression || endpoint.shape.axes[1].parameter ||
              endpoint.shape.axes[1].expression)
            return false;
        return true;
      });
  familyQuery("resolved-family-million-lanes-still-one-component",
              model(familyBody("1000000", "\"1000000\"")), true,
              [](Operation *, const auto &topology, const auto &dependencies) {
                return topology.components.size() == 1 && topology.endpoints.size() == 2 &&
                       dependencies.size() == 3 && topology.components[0].values.size() == 3 &&
                       topology.components[0].shape.dimensions.size() == 1;
              });
  familyQuery("resolved-family-unaccessed-retains-complete-geometry",
              model(domain() + resolvedDefinition() +
                    instance("left", "sink", "connect", resolved(), "\"2\", \"3\"")),
              true, [](Operation *, const auto &topology, const auto &dependencies) {
                return topology.components.empty() && topology.endpoints.size() == 1 &&
                       dependencies.empty() && !topology.endpoints[0].component &&
                       topology.endpoints[0].shape.owner == topology.endpoints[0].operation &&
                       topology.endpoints[0].shape.dimensions.size() == 2;
              });
  auto swappedFamily =
      replace(familyBody(), instance("right", "sink", "connect", resolved(), "\"2\", \"3\""),
              instance("right", "sink", "connect", resolved(), "\"3\", \"2\""));
  swappedFamily = replace(swappedFamily, member("b", "right", familyType("2,3")),
                          member("b", "right", familyType("3,2")));
  swappedFamily = replace(swappedFamily, connect("a", "b", familyType("2,3")),
                          "\"nodal.interface_connect\"(%a, %b) <{metadata = {" + tag + "}}> : (" +
                              familyType("2,3") + ", " + familyType("3,2") + ") -> ()\n");
  check("resolved-family-same-cardinality-axis-swap-rejected", swappedFamily, false, "",
        "NODAL-INTERFACE-054-001: Interface connection requires distinct exact members, dimensions "
        "and domain identity");
  const std::string familyEndpoint =
      domain() + resolvedDefinition() +
      instance("left", "sink", "connect", resolved(), "\"2\", \"3\"");
  check("resolved-family-cannot-drop-outer-rank", familyEndpoint + member("a", "left", resolved()),
        false);
  check("resolved-family-scalar-drive-does-not-broadcast",
        domain() + resolvedDefinition() +
            instance("left", "source", "drive", resolved(), "\"2\", \"3\"") +
            member("a", "left", familyType("2,3")) + literal("payload") +
            "\"nodal.interface_drive\"(%a, %payload) <{metadata = {" + tag + "}}> : (" +
            familyType("2,3") + ", " + bits + ") -> ()\n",
        false, "", "NODAL-INTERFACE-054-001: Interface drive requires scalar push-pull");
  check("resolved-family-scalar-read-does-not-strip-rank",
        familyBody() + "%read = \"nodal.net_read\"(%a) <{metadata = {" + tag + "}}> : (" +
            familyType("2,3") + ") -> " + bits + "\n",
        false, "",
        "NODAL-INTERFACE-054-001: net read/split must retain exact value and canonical Bool result "
        "types");
  check("resolved-family-scalar-split-does-not-broadcast-enable",
        familyBody() + "%written, %enabled = \"nodal.net_split\"(%a) <{metadata = {" + tag +
            "}}> : (" + familyType("2,3") + ") -> (" + bits + ", " + boolean + ")\n",
        false, "",
        "NODAL-INTERFACE-054-001: net read/split must retain exact value and canonical Bool result "
        "types");
  check("resolved-family-domain-mismatch-rejected",
        replace(familyBody(), instance("right", "sink", "connect", resolved(), "\"2\", \"3\""),
                domain("other") +
                    instance("right", "sink", "connect", resolved(), "\"2\", \"3\"", "other")),
        false, "",
        "NODAL-INTERFACE-054-001: Interface connection requires distinct exact members, dimensions "
        "and domain identity");
  check("resolved-family-payload-mismatch-rejected",
        familyEndpoint +
            member("a", "left", "!nodal.shaped<\"2,3\", " + resolved("push_pull", boolean) + ">"),
        false, "",
        "NODAL-INTERFACE-054-001: member SSA result must retain the exact declared type and "
        "ordered dimensions");
  check("resolved-family-mode-mismatch-rejected",
        familyEndpoint +
            member("a", "left", "!nodal.shaped<\"2,3\", " + resolved("open_drain") + ">"),
        false, "",
        "NODAL-INTERFACE-054-001: member SSA result must retain the exact declared type and "
        "ordered dimensions");
  familyQuery("resolved-family-symbolic-axis-keeps-original-parameter",
              model(parameter() + familyBody("N,3", "\"N\", \"3\"")), true,
              [&](Operation *owner, const auto &topology, const auto &) {
                for (const auto &endpoint : topology.endpoints) {
                  const auto &shape = endpoint.shape;
                  if (shape.owner != endpoint.operation || shape.axes.size() != 2 ||
                      !shape.axes[0].parameter || shape.axes[0].parameter->getParentOp() != owner ||
                      symbolName(shape.axes[0].parameter) != "N" || shape.axes[0].expression ||
                      shape.axes[0].literal || shape.axes[0].bounds.lower != 1 ||
                      shape.axes[0].bounds.upper != 4 || shape.axes[1].literal != 3)
                    return false;
                }
                return topology.components.size() == 1 && topology.endpoints.size() == 2;
              });
  familyQuery("resolved-family-expression-axis-keeps-original-SSA",
              model(widthRoots + familyBody("Fixture.widthA,3", "\"Fixture.widthA\", \"3\"")), true,
              [](Operation *owner, const auto &topology, const auto &) {
                for (const auto &endpoint : topology.endpoints) {
                  const auto &shape = endpoint.shape;
                  if (shape.axes.size() != 2 || shape.axes[0].parameter || shape.axes[0].literal ||
                      !shape.axes[0].expression ||
                      shape.axes[0].expression.getDefiningOp()->getParentOp() != owner ||
                      shape.axes[0]
                              .expression.getDefiningOp()
                              ->template getAttrOfType<DictionaryAttr>("metadata")
                              .template getAs<StringAttr>("semantic_path")
                              .getValue() != "Fixture.widthA" ||
                      shape.axes[0].bounds.lower != 2 || shape.axes[0].bounds.upper != 5 ||
                      shape.axes[1].literal != 3)
                    return false;
                }
                return topology.endpoints.size() == 2;
              });
  familyQuery("resolved-family-rank-zero-retains-scalar-identity",
              model(domain() + resolvedDefinition() +
                    instance("left", "sink", "connect", resolved()) +
                    member("a", "left", resolved())),
              true, [](Operation *, const auto &topology, const auto &) {
                return topology.components.size() == 1 && topology.endpoints.size() == 1 &&
                       topology.components[0].shape.dimensions.empty() &&
                       topology.components[0].shape.axes.empty() &&
                       topology.endpoints[0].shape.axes.empty() &&
                       topology.endpoints[0].shape.owner == topology.endpoints[0].operation &&
                       topology.endpoints[0].profile.getValue() == "portable_boundary_only";
              });
  auto nestedFamily = nestedFixture();
  auto replaceEvery = [](std::string source, StringRef from, StringRef to) {
    size_t position = 0;
    while ((position = source.find(from.str(), position)) != std::string::npos) {
      source.replace(position, from.size(), to.str());
      position += to.size();
    }
    return source;
  };
  nestedFamily = replaceEvery(nestedFamily, bits, resolved());
  nestedFamily = replaceEvery(nestedFamily, "protocol = \"plain\"", "protocol = \"resolved\"");
  nestedFamily = replaceEvery(nestedFamily, "access = \"out\"", "access = \"drive\"");
  nestedFamily = replaceEvery(nestedFamily, "access = \"in\"", "access = \"connect\"");
  nestedFamily = replace(nestedFamily, "dimensions = []", "dimensions = [\"2\", \"3\"]");
  nestedFamily = replace(nestedFamily, member("a", "left", resolved(), "", "nested.data"),
                         member("a", "left", familyType("2,3"), "", "nested.data"));
  familyQuery("resolved-family-nested-member-keeps-unequal-outer-axes", model(nestedFamily), true,
              [](Operation *, const auto &topology, const auto &) {
                return topology.components.size() == 1 && topology.endpoints.size() == 1 &&
                       topology.endpoints[0].path.getValue() == "nested.data" &&
                       topology.endpoints[0].shape.axes.size() == 2 &&
                       topology.endpoints[0].shape.axes[0].literal == 2 &&
                       topology.endpoints[0].shape.axes[1].literal == 3 &&
                       llvm::isa<nodal::ResolvedType>(topology.endpoints[0].type);
              });
  auto arrayResolvedHierarchy = [&](StringRef actual = "P", bool symbolic = true) {
    auto source = hierarchyArrayFixture(actual, symbolic);
    auto all = [&](StringRef from, StringRef to) {
      size_t position = 0;
      while ((position = source.find(from.str(), position)) != std::string::npos) {
        source.replace(position, from.size(), to.str());
        position += to.size();
      }
    };
    all(boolean, resolved());
    all("protocol = \"plain\"", "protocol = \"resolved\"");
    all("access = \"out\"", "access = \"drive\"");
    all("access = \"in\"", "access = \"connect\"");
    return source;
  };
  auto reusedArrayFamily = [&](bool forged = false) {
    const std::string actual = forged ? "P" : "Q";
    const std::string actualType = familyType(actual);
    std::string second =
        "%q = \"nodal.const_parameter_ref\"() <{parameter = @Q, metadata = {}}> : () -> i64\n" +
        instance("secondTop", "sink", "connect", resolved(), "\"" + actual + "\"") +
        "\"nodal.instance\"() <{sym_name = \"secondChild\", module = @Child, parameter_bindings = "
        "{}, "
        "domain_bindings = {required = @clock}, metadata = {}}> : () -> ()\n" +
        "\"nodal.parameter_override\"(%q) <{instance = @secondChild, parameter = @N, metadata = "
        "{}}> : (i64) -> ()\n" +
        replace(hierarchyBinding("required", "clock"), "instance = @child",
                "instance = @secondChild") +
        replace(
            replace(replace(hierarchyBoundary(), "instance = @child", "instance = @secondChild"),
                    "sym_name = \"projected\"", "sym_name = \"secondProjected\""),
            "semantic_path = \"Top.projected\"",
            "semantic_path = \"Top.secondProjected\", dimensions = [\"" + actual + "\"]") +
        member("secondProjected", "secondProjected", actualType) +
        member("secondTop", "secondTop", actualType) +
        connect("secondTop", "secondProjected", actualType);
    const std::string boundary = "}) : () -> ()\n\"nodal.module\"() <{sym_name = \"Child\"";
    return replace(arrayResolvedHierarchy(), boundary, second + boundary);
  };
  hierarchy("resolved-family-reused-child-different-shape-accepts", reusedArrayFamily(), true);
  hierarchy("resolved-family-reused-child-equal-bounds-forged-shape", reusedArrayFamily(true),
            false, "shape differs from exact finalized");
  hierarchy(
      "resolved-family-parent-cannot-borrow-child-shape-owner", arrayResolvedHierarchy("N"), false,
      "NODAL-INTERFACE-054-001: Interface array dimensions require owned finite structural proofs");
  ++cases;
  diagnosticText.clear();
  auto reusedFamilies =
      parseSourceString<mlir::ModuleOp>(reusedArrayFamily(), ParserConfig(&context, false));
  bool reusedFamiliesValid = reusedFamilies && succeeded(verify(reusedFamilies->getOperation()));
  SmallVector<nodal::DigitalResolvedHierarchyComponent> independentFamilies;
  if (reusedFamiliesValid) {
    auto hierarchyModel = nodal::HierarchyModel::create(reusedFamilies->getOperation());
    reusedFamiliesValid = succeeded(hierarchyModel) &&
                          succeeded(nodal::verifyHierarchyDependencies(*hierarchyModel, {}, nullptr,
                                                                       &independentFamilies)) &&
                          independentFamilies.size() == 2;
    if (reusedFamiliesValid) {
      Operation *original = nullptr;
      for (auto [index, family] : llvm::enumerate(independentFamilies)) {
        reusedFamiliesValid &=
            family.occurrence == 0 && family.endpoints.size() == 3 &&
            family.shape.axes.size() == 1 && family.shape.axes[0].parameter &&
            symbolName(family.shape.axes[0].parameter) == (index == 0 ? "P" : "Q");
        unsigned children = 0;
        for (const auto &endpoint : family.endpoints)
          if (endpoint.occurrence != 0) {
            ++children;
            reusedFamiliesValid &= endpoint.occurrence == index + 1 &&
                                   endpoint.endpoint.shape.axes.size() == 1 &&
                                   endpoint.endpoint.shape.axes[0].parameter &&
                                   symbolName(endpoint.endpoint.shape.axes[0].parameter) == "N";
            if (!original)
              original = endpoint.endpoint.declaration;
            else
              reusedFamiliesValid &= original == endpoint.endpoint.declaration;
          }
        reusedFamiliesValid &= children == 1;
      }
    }
  }
  failures += !reusedFamiliesValid;
  if (!reusedFamiliesValid)
    llvm::errs() << diagnosticText;
  (reusedFamiliesValid ? llvm::outs() : llvm::errs())
      << "DIGITAL_INTERFACE_MODEL_" << (reusedFamiliesValid ? "PASS " : "FAIL ")
      << "resolved-family-reused-declaration-distinct-pointwise-occurrences\n";
  hierarchy("resolved-family-child-exact-symbolic-shape", arrayResolvedHierarchy(), true);
  hierarchy("resolved-family-child-equal-bounds-other-owner", arrayResolvedHierarchy("Q"), false,
            "shape differs from exact finalized");
  hierarchy("resolved-family-child-default-is-not-binding", arrayResolvedHierarchy("2"), false,
            "shape differs from exact finalized");
  hierarchy("resolved-family-child-concrete-shape", arrayResolvedHierarchy("P", false), true);
  ++cases;
  diagnosticText.clear();
  auto familyHierarchy =
      parseSourceString<mlir::ModuleOp>(arrayResolvedHierarchy(), ParserConfig(&context, false));
  bool familyHierarchyValid = familyHierarchy && succeeded(verify(familyHierarchy->getOperation()));
  SmallVector<nodal::DigitalResolvedHierarchyComponent> familyComponents;
  if (familyHierarchyValid) {
    auto hierarchyModel = nodal::HierarchyModel::create(familyHierarchy->getOperation());
    familyHierarchyValid =
        succeeded(hierarchyModel) && succeeded(nodal::verifyHierarchyDependencies(
                                         *hierarchyModel, {}, nullptr, &familyComponents));
    familyHierarchyValid =
        familyHierarchyValid && familyComponents.size() == 1 &&
        familyComponents[0].endpoints.size() == 3 && familyComponents[0].shape.owner &&
        familyComponents[0].shape.dimensions.size() == 1 &&
        llvm::cast<StringAttr>(familyComponents[0].shape.dimensions[0]).getValue() == "P" &&
        familyComponents[0].drivers.empty() && familyComponents[0].externalContributions.empty();
    if (familyHierarchyValid)
      for (const auto &endpoint : familyComponents[0].endpoints) {
        const auto &shape = endpoint.endpoint.shape;
        StringRef expected = endpoint.occurrence == 0 ? "P" : "N";
        familyHierarchyValid &=
            shape.owner == endpoint.endpoint.operation && shape.axes.size() == 1 &&
            shape.axes[0].parameter && symbolName(shape.axes[0].parameter) == expected &&
            shape.axes[0].parameter->getParentOp() == shape.owner->getParentOp() &&
            !shape.axes[0].expression && !shape.axes[0].literal;
      }
  }
  failures += !familyHierarchyValid;
  if (!familyHierarchyValid)
    llvm::errs() << diagnosticText;
  (familyHierarchyValid ? llvm::outs() : llvm::errs())
      << "DIGITAL_INTERFACE_MODEL_" << (familyHierarchyValid ? "PASS " : "FAIL ")
      << "resolved-family-occurrence-query-retains-pointwise-owner\n";

  // Conservative members use the same complete role model, never digital union.
  const std::string terminalType = "!nodal.terminal<\"electrical\">";
  auto analogAccessQuery = [&](StringRef name, StringRef effect, bool expected,
                               StringRef accessRole = "monitor") {
    ++cases;
    diagnosticText.clear();
    std::string body = conservative + member("a", "left", terminalType) +
                       member("b", "left", terminalType, accessRole) + effect.str();
    auto parsed = parseSourceString<mlir::ModuleOp>(model(body, analogOwners()),
                                                    ParserConfig(&context, false));
    bool passed = false;
    if (parsed) {
      Operation *owner = &*std::prev(parsed->getBody()->end());
      auto before = print(parsed->getOperation());
      SmallVector<nodal::DigitalInterfaceEndpoint> endpoints;
      SmallVector<nodal::DigitalInterfaceAccess> accesses;
      bool valid = succeeded(nodal::collectDigitalInterfaceEndpoints(owner, endpoints, &accesses));
      passed = valid == expected && before == print(parsed->getOperation());
      if (valid)
        passed &= accesses.size() == 2 && endpoints.size() == 1 &&
                  accesses[0].canonicalValue == accesses[1].canonicalValue &&
                  accesses[0].leaf.access == "connect" &&
                  accesses[1].leaf.access == (accessRole == "monitor" ? "sense" : "connect") &&
                  accesses[0].leaf.type == accesses[0].value.getType();
      else
        passed &= endpoints.empty() && accesses.empty() &&
                  diagnosticText.find("selected-role permission") != std::string::npos;
    }
    failures += !passed;
    if (!passed)
      llvm::errs() << diagnosticText;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  analogAccessQuery("conservative-repeated-member-preserves-narrow-monitor-role", "", true);
  const std::string analogConnect =
      "\"nodal.connect\"(%a, %b) <{connection_id = \"same\", source_path = \"Fixture.same\", "
      "metadata = {}}> : (" +
      terminalType + ", " + terminalType + ") -> ()\n";
  analogAccessQuery("conservative-connect-role-is-not-digital-payload", analogConnect, true,
                    "sink");
  analogAccessQuery("conservative-monitor-cannot-connect", analogConnect, false);
  analogAccessQuery(
      "conservative-monitor-cannot-alias",
      replace(replace(analogConnect, "nodal.connect", "nodal.alias"), "connection_id", "alias_id"),
      false);
  analogAccessQuery("conservative-monitor-cannot-reference",
                    "\"nodal.reference\"(%b) <{scope = \"module\", source_path = \"Fixture.ref\", "
                    "metadata = {}}> : (" +
                        terminalType + ") -> ()\n",
                    false);
  analogAccessQuery(
      "conservative-branch-contribution-cannot-escalate-monitor",
      "%branch = \"nodal.branch\"(%a, %b) <{metadata = {}}> : (" + terminalType + ", " +
          terminalType +
          ") -> !nodal.branch<\"electrical\">\n"
          "\"nodal.analog\"() <{metadata = {}}> ({\n"
          "%value = \"nodal.real_literal\"() <{value = 1.0 : f64, "
          "metadata = {}}> : () -> f64\n"
          "\"nodal.contribute\"(%branch, %value) <{kind = \"flow\", metadata = {}}> : "
          "(!nodal.branch<\"electrical\">, f64) -> ()\n}) : () -> ()\n",
      false);

  const std::string lexicalTemplate =
      generatedInterface(hiddenDefinition() + generatedEndpoint + generatedInterfaceExport());
  const std::string lexicalSelected =
      domain() + lexicalTemplate + literal("ordinal", "i64", "1") + selectedInterface();
  generatedQuery("lexical-definition-generated-complete-inventory", lexicalSelected, true);
  fullGenerated("lexical-definition-generated-full-gate", lexicalSelected, true);
  generatedQuery("lexical-definition-module-forward-reference-preserved",
                 domain() + generatedTemplate + hiddenDefinition(), true);
  generatedQuery("lexical-definition-regional-forward-reference-rejected",
                 domain() + generatedInterface(generatedEndpoint + hiddenDefinition()), false);
  generatedQuery("lexical-definition-nearest-scope-shadows-module",
                 domain() + hiddenDefinition(bits) + lexicalTemplate, true);
  generatedQuery(
      "lexical-definition-sibling-name-isolation",
      domain() + generatedInterface(hiddenDefinition() + generatedEndpoint, "3", "a") +
          generatedInterface(hiddenDefinition(bits) + hiddenInstance("inside", "clock", bits), "3",
                             "b"),
      true);
  generatedQuery("lexical-definition-sibling-cannot-borrow",
                 domain() + generatedInterface(hiddenDefinition(), "3", "a") +
                     generatedInterface(generatedEndpoint, "3", "b"),
                 false);
  generatedQuery("lexical-definition-regional-cannot-escape",
                 domain() + generatedInterface(hiddenDefinition()) + generatedEndpoint, false);
  generatedQuery("lexical-definition-duplicate-local-symbol",
                 domain() + generatedInterface(hiddenDefinition() +
                                               replace(hiddenDefinition(), "pkg.Bus", "pkg.Other") +
                                               generatedEndpoint),
                 false);
  generatedQuery("lexical-definition-duplicate-local-semantic-identity",
                 domain() + generatedInterface(hiddenDefinition() +
                                               replace(hiddenDefinition(), "sym_name = \"Bus\"",
                                                       "sym_name = \"Other\"") +
                                               generatedEndpoint),
                 false);
  generatedQuery(
      "lexical-definition-shadow-does-not-fall-back-on-ABI-mismatch",
      domain() + hiddenDefinition(bits) +
          generatedInterface(hiddenDefinition() + hiddenInstance("inside", "clock", bits)),
      false);
  generatedQuery("lexical-definition-named-non-definition-cannot-fall-back",
                 domain() + hiddenDefinition() +
                     generatedInterface(
                         replace(generatedEndpoint, "sym_name = \"inside\"", "sym_name = \"Bus\"") +
                         generatedEndpoint),
                 false);
  fullGenerated("lexical-definition-nested-complete-role-map",
                domain() + generatedInterface(replace(nestedFixture(), domain(), "")), true);
  generatedQuery("lexical-definition-nested-foreign-identity-refused",
                 domain() + generatedInterface(replace(replace(nestedFixture(), domain(), ""),
                                                       "definition = \"pkg.Child\"",
                                                       "definition = \"pkg.Foreign\"")),
                 false);
  const std::string guardHeader =
      literal("guard0", "i64", "0") + literal("guard1", "i64", "1") +
      "%condition = \"nodal.static_compare\"(%guard0, %guard1) <{comparison = \"lt\", "
      "metadata = {semantic_path = \"Fixture.condition\"}}> : (i64, i64) -> "
      "!nodal.static_predicate\n";
  auto definitionArm = [&](const std::string &left, const std::string &right) {
    auto scoped = [](std::string body, StringRef arm) {
      const std::string before = "Fixture.family";
      const std::string after = ("Fixture.definitionGuard." + arm + ".family").str();
      size_t at = 0;
      while ((at = body.find(before, at)) != std::string::npos) {
        body.replace(at, before.size(), after);
        at += after.size();
      }
      return body;
    };
    return guardHeader +
           "\"nodal.structural_if\"(%condition) <{contract_version = \"structural-if-v1\", "
           "region_id = \"Fixture.definitionGuard\", operandSegmentSizes = array<i32: 1, 0, 0>, "
           "metadata = {semantic_path = \"Fixture.definitionGuard\"}}> ({\n" +
           scoped(left, "then") +
           "\"nodal.structural_yield\"() <{metadata = {semantic_path = "
           "\"Fixture.definitionGuard.then.yield\"}}> : () -> ()\n}, {\n" +
           scoped(right, "else") +
           "\"nodal.structural_yield\"() <{metadata = {semantic_path = "
           "\"Fixture.definitionGuard.else.yield\"}}> : () -> ()\n}) : (!nodal.static_predicate) "
           "-> ()\n";
  };
  fullGenerated("lexical-definition-arm-owner-nested-generate-endpoint",
                domain() +
                    definitionArm(hiddenDefinition() + generatedInterface(generatedEndpoint), ""),
                true);
  fullGenerated("lexical-definition-opposite-arms-reuse-name-independently",
                domain() + definitionArm(hiddenDefinition() + generatedInterface(generatedEndpoint),
                                         hiddenDefinition(bits) + generatedInterface(hiddenInstance(
                                                                      "inside", "clock", bits))),
                true);
  generatedQuery(
      "lexical-definition-opposite-arm-cannot-borrow",
      domain() + definitionArm(hiddenDefinition(), generatedInterface(generatedEndpoint)), false);
  fullGenerated("lexical-definition-does-not-admit-direct-arm-endpoint",
                domain() + definitionArm(hiddenDefinition() + generatedEndpoint, ""), false);

  const std::string lexicalWidth = "!nodal.digital<\"uint\", \"Fixture.family.width\">";
  const std::string widthProducer =
      "%width = \"nodal.const_expr\"(%family_i, %family_step) <{operator_name = \"add\", "
      "metadata = {semantic_path = \"Fixture.family.width\"}}> : (i64, i64) -> i64\n";
  const std::string varyingDeclaration =
      definition(lexicalWidth) + instance("inside", "source", "out", lexicalWidth);
  const std::string varyingTemplate =
      generatedInterface(widthProducer + varyingDeclaration + generatedInterfaceExport());
  const std::string varyingSelected = domain() + varyingTemplate + literal("ordinal", "i64", "1") +
                                      replace(selectedInterface(), "dimensions = []",
                                              "dimensions = [], actual_types = "
                                              "[{path = \"data\", type = !nodal.uint<2>}]");
  generatedQuery("lexical-definition-induction-width-original-anchor", domain() + varyingTemplate,
                 true);
  fullGenerated("lexical-definition-induction-width-full-gate", domain() + varyingTemplate, true);
  fullGenerated("lexical-definition-induction-width-selected", varyingSelected, true);
  generatedQuery("lexical-definition-induction-width-wrong-actual",
                 replace(varyingSelected, "type = !nodal.uint<2>", "type = !nodal.uint<1>"), false);
  generatedQuery(
      "lexical-definition-induction-width-wrong-prefix",
      replace(varyingSelected, literal("ordinal", "i64", "1"), literal("ordinal", "i64", "0")),
      false);
  generatedQuery("lexical-definition-type-token-must-precede-definition",
                 domain() + generatedInterface(varyingDeclaration + widthProducer), false);
  generatedQuery(
      "lexical-definition-module-cannot-inherit-generated-token",
      domain() + definition(lexicalWidth) +
          generatedInterface(widthProducer + instance("inside", "source", "out", lexicalWidth)),
      false);
  generatedQuery("lexical-definition-independent-hidden-leaf-still-checked",
                 domain() +
                     generatedInterface(hiddenDefinition("!nodal.digital<\"uint\", \"missing\">") +
                                        generatedEndpoint),
                 false);
  const std::string extentType = "!nodal.shaped<\"Fixture.family.width\", !nodal.uint<2>>";
  fullGenerated("lexical-definition-induction-payload-shape-full-gate",
                domain() + generatedInterface(widthProducer + definition(extentType) +
                                              instance("inside", "source", "out", extentType)),
                true);
  generatedQuery("lexical-definition-million-lanes-one-authored-definition",
                 domain() + generatedInterface(hiddenDefinition() + generatedEndpoint, "1000000"),
                 true);

  const std::string aliasWidth = "!nodal.digital<\"uint\", \"Fixture.family.alias\">";
  const std::string aliasProducer = replace(replace(widthProducer, "%width =", "%aliasWidth ="),
                                            "Fixture.family.width", "Fixture.family.alias");
  fullGenerated("lexical-definition-equal-DAG-different-token-access",
                domain() + generatedInterface(widthProducer + varyingDeclaration + aliasProducer +
                                              member("a", "inside", aliasWidth) +
                                              observe("a", aliasWidth)),
                true);
  const std::string namedWidth = "!nodal.digital<\"uint\", \"N\">";
  generatedQuery("lexical-definition-module-payload-does-not-inherit-local-token",
                 domain() + parameter() + definition(namedWidth) +
                     generatedInterface(replace(widthProducer, "Fixture.family.width", "N") +
                                        instance("inside", "source", "out", namedWidth) +
                                        member("a", "inside", namedWidth)),
                 false);
  fullGenerated("lexical-definition-body-connect-dependent-payload",
                domain() + generatedInterface(widthProducer + definition(lexicalWidth) +
                                              instance("left", "source", "out", lexicalWidth) +
                                              instance("right", "sink", "in", lexicalWidth) +
                                              member("a", "left", lexicalWidth) +
                                              member("b", "right", lexicalWidth) +
                                              connect("a", "b", lexicalWidth) +
                                              observe("b", lexicalWidth)),
                true);
  const std::string twoScoped =
      domain() +
      generatedInterface(definition(boolean) + instance("inside", "source", "out", boolean) +
                             generatedInterfaceExport(),
                         "3", "first") +
      generatedInterface(definition(boolean) + instance("inside", "sink", "in", boolean) +
                             generatedInterfaceExport(),
                         "3", "second") +
      literal("ordinal", "i64", "1") + selectedInterface("left", "ordinal", "first") +
      selectedInterface("right", "ordinal", "second") + member("a", "left", boolean) +
      member("b", "right", boolean) + connect("a", "b", boolean);
  generatedQuery("lexical-definition-label-alone-does-not-equate-two-declarations", twoScoped,
                 false);

  std::string manyLexicalEndpoints = definition(boolean);
  for (unsigned index = 0; index < 512; ++index)
    manyLexicalEndpoints += instance("endpoint" + std::to_string(index), "source", "out", boolean);
  endpointQuery("lexical-definition-indexed-512-authored-endpoints-million-lanes",
                model(domain() + generatedInterface(manyLexicalEndpoints, "1000000")), true,
                [](auto endpoints) { return endpoints.size() == 512; });
  generatedQuery("lexical-definition-procedural-owner-refused",
                 domain() + "\"nodal.analog\"() <{metadata = {}}> ({\n" + hiddenDefinition() +
                     "}) : () -> ()\n",
                 false);

  auto driverBatch = [&](StringRef name, const std::string &body, bool expected, unsigned mode = 0,
                         unsigned copies = 1) {
    ++cases;
    diagnosticText.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(model(body), ParserConfig(&context, false));
    bool passed = false;
    if (parsed) {
      Operation *owner = &parsed->getBody()->front();
      SmallVector<Operation *> members, families;
      owner->walk([&](Operation *op) {
        if (isa<nodal::MemberAccessOp>(op))
          members.push_back(op);
        if (isa<nodal::GenerateOp>(op))
          families.push_back(op);
      });
      if (!members.empty() && !families.empty()) {
        Operation *root = mode == 1 ? members.back() : members.front();
        Operation *family = families.front();
        Operation *use = &family->getRegion(0).front().back();
        if (mode == 2)
          use = &family->getRegion(0).front().front();
        if (mode == 3)
          use = owner;
        if (mode == 4 && families.size() > 1)
          use = &families.back()->getRegion(0).front().back();
        SmallVector<std::pair<Value, Operation *>> seeds(copies, {root->getResult(0), use});
        if (mode == 5)
          seeds.front().first = Value();
        if (mode == 6)
          seeds.front().second = nullptr;
        auto before = print(parsed->getOperation());
        auto facts = nodal::inspectGeneratedInterfaceTemplateDrivers(owner, seeds);
        passed = succeeded(facts) == expected && before == print(parsed->getOperation());
        if (succeeded(facts)) {
          passed &= facts->size() == copies;
          for (const auto &fact : *facts)
            passed &= fact.root == root->getResult(0) && fact.source && fact.effect &&
                      fact.family == family;
        }
      }
    }
    failures += !passed;
    if (!passed)
      llvm::errs() << diagnosticText;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  const std::string authoredBody = instance("inside", "source", "out") + member("a", "inside") +
                                   literal("value") + drive() + observe();
  const std::string authored = domain() + definition() + generatedInterface(authoredBody);
  driverBatch("template-driver-whole-original", authored, true);
  driverBatch("template-driver-exact-empty-retains-authored-writer",
              domain() + definition() + generatedInterface(authoredBody, "0"), true);
  driverBatch("template-driver-million-lanes-one-authored-fact",
              domain() + definition() + generatedInterface(authoredBody, "1000000"), true);
  driverBatch("template-driver-ordered-duplicates", authored, true, 0, 3);
  driverBatch("template-driver-duplicate-seed-budget", authored, false, 0,
              nodal::digitalTypeMaximumNodes + 1);
  driverBatch("template-driver-not-final-anchor", authored, false, 2);
  driverBatch("template-driver-module-anchor", authored, false, 3);
  driverBatch("template-driver-null-root", authored, false, 5);
  driverBatch("template-driver-null-use", authored, false, 6);
  driverBatch("template-driver-foreign-family-anchor",
              authored + generatedInterface(literal("foreignAnchor"), "2", "other"), false, 4);
  driverBatch("template-driver-missing-writer",
              domain() + definition() +
                  generatedInterface(instance("inside", "source", "out") + member("a", "inside") +
                                     observe()),
              false);
  driverBatch("template-driver-ambiguous-writer",
              domain() + definition() + generatedInterface(authoredBody + drive()), false);
  driverBatch("template-driver-monitor-does-not-grant-drive",
              domain() + definition() +
                  generatedInterface(instance("inside", "monitor", "observe") +
                                     member("a", "inside") + observe()),
              false);
  driverBatch("template-driver-selected-root-refused",
              domain() + definition() +
                  generatedInterface(authoredBody + generatedInterfaceExport()) +
                  literal("ordinal", "i64", "1") + selectedInterface() +
                  member("selectedValue", "selected"),
              false, 1);
  driverBatch(
      "template-driver-empty-malformed-body-refused",
      domain() + definition() +
          generatedInterface(
              authoredBody +
                  "%bad = \"builtin.unrealized_conversion_cast\"() : () -> !nodal.bits<8>\n",
              "0"),
      false);
  driverBatch("template-driver-hidden-ABI-forgery-refused",
              domain() + hiddenDefinition() +
                  generatedInterface(replace(hiddenInstance("inside", "clock"),
                                             "path = \"hidden\", type = !nodal.bits<1>",
                                             "path = \"hidden\", type = !nodal.bits<8>") +
                                     member("a", "inside", boolean) + literal("value", boolean) +
                                     drive("a", "value", boolean) + observe("a", boolean)),
              false);

  auto boundaryBatch = [&](StringRef name, const std::string &body, bool expected,
                           unsigned mode = 0, unsigned copies = 1, int64_t widthMaximum = 8,
                           unsigned outerRank = 0, unsigned payloadRank = 0) {
    ++cases;
    diagnosticText.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(model(body), ParserConfig(&context, false));
    bool passed = false;
    if (parsed) {
      Operation *owner = &parsed->getBody()->front();
      SmallVector<Operation *> endpoints, members, projections;
      owner->walk([&](Operation *op) {
        if (isa<nodal::InterfaceInstanceOp>(op))
          endpoints.push_back(op);
        if (isa<nodal::InterfaceMemberOp>(op))
          members.push_back(op);
        if (isa<nodal::InterfaceBoundaryOp>(op))
          projections.push_back(op);
      });
      if (!endpoints.empty() && !members.empty()) {
        Operation *endpoint = endpoints.front(), *memberOp = members.front();
        if (mode == 1)
          memberOp = members.back();
        if (mode == 2 && !projections.empty())
          endpoint = projections.front();
        if (mode == 3)
          endpoint = owner;
        if (mode == 4)
          memberOp = nullptr;
        SmallVector<std::pair<Operation *, Operation *>> seeds(copies, {endpoint, memberOp});
        auto before = print(parsed->getOperation());
        auto facts = nodal::inspectDigitalInterfaceBoundaryTypes(owner, seeds);
        passed = succeeded(facts) == expected && before == print(parsed->getOperation());
        if (succeeded(facts)) {
          passed &= facts->size() == copies;
          for (const auto &fact : *facts) {
            passed &= fact.endpoint == endpoint && fact.member == memberOp &&
                      fact.definition == memberOp->getParentOp() &&
                      fact.outerDimensions.size() == outerRank && !fact.payload.instance &&
                      !fact.payload.leaves.empty();
            if (!fact.payload.leaves.empty()) {
              const auto &leaf = fact.payload.leaves.front();
              passed &= leaf.width.bounds.upper == widthMaximum;
              unsigned rank = 0;
              for (const auto &group : leaf.dimensions)
                rank += group.extents.size();
              passed &= rank == payloadRank;
            }
          }
        }
      }
    }
    failures += !passed;
    if (!passed)
      llvm::errs() << diagnosticText;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  boundaryBatch("interface-boundary-module-predecessor",
                domain() + definition() + instance("inside", "source", "out"), true);
  boundaryBatch("interface-boundary-original-generated-payload", authored, true);
  boundaryBatch("interface-boundary-empty-fixed-payload-still-numeric",
                domain() + definition() + generatedInterface(authoredBody, "0"), true);
  boundaryBatch("interface-boundary-ordered-duplicates", authored, true, 0, 3);
  boundaryBatch("interface-boundary-duplicate-copy-budget", authored, false, 0,
                nodal::digitalTypeMaximumNodes);
  boundaryBatch("interface-boundary-invalid-endpoint", authored, false, 3);
  boundaryBatch("interface-boundary-null-member", authored, false, 4);
  boundaryBatch("interface-boundary-selection-not-original", generatedSelected, false, 2);
  boundaryBatch(
      "interface-boundary-foreign-scoped-member",
      domain() +
          generatedInterface(definition() + instance("inside", "source", "out"), "3", "first") +
          generatedInterface(definition() + instance("inside", "source", "out"), "3", "second"),
      false, 1);
  boundaryBatch("interface-boundary-lexical-width-all-lanes", domain() + varyingTemplate, true, 0,
                1, 3);
  boundaryBatch("interface-boundary-empty-dependent-width-not-numeric",
                domain() + generatedInterface(widthProducer + varyingDeclaration, "0"), false);
  boundaryBatch("interface-boundary-width-token-must-precede-definition",
                domain() + generatedInterface(varyingDeclaration + widthProducer), false);
  const std::string payloadShape = "!nodal.shaped<\"2,3\", !nodal.bits<8>>";
  boundaryBatch(
      "interface-boundary-payload-and-endpoint-axes-separate",
      domain() + definition(payloadShape) +
          generatedInterface(instance("inside", "source", "out", payloadShape, "\"4\", \"5\"")),
      true, 0, 1, 8, 2, 2);
  boundaryBatch("interface-boundary-unaccessed-invalid-member-refused",
                domain() +
                    generatedInterface(hiddenDefinition("!nodal.digital<\"uint\", \"missing\">") +
                                       generatedEndpoint),
                false);
  driverBatch("template-driver-exact-guarded-family",
              domain() + definitionArm(definition() + generatedInterface(authoredBody), ""), true);
  driverBatch("template-driver-other-arm-final-anchor",
              domain() + definitionArm(definition() + generatedInterface(authoredBody),
                                       definition() + generatedInterface(authoredBody)),
              false, 4);
  driverBatch("template-driver-alias-root-same-whole-writer",
              domain() + definition() +
                  generatedInterface(instance("inside", "source", "out") + member("a", "inside") +
                                     member("alias", "inside") + literal("value") + drive("alias") +
                                     observe()),
              true);
  boundaryBatch(
      "interface-boundary-arm-definition-real-payload-anchor",
      domain() +
          definitionArm(definition() + generatedInterface(instance("inside", "source", "out")), ""),
      true);
  boundaryBatch(
      "interface-boundary-opposite-arm-member-refused",
      domain() +
          definitionArm(definition() + generatedInterface(instance("inside", "source", "out")),
                        definition() + generatedInterface(instance("inside", "source", "out"))),
      false, 1);
  const std::string nominalPayload =
      "!nodal.struct<\"Packet\", [{name = \"tag\", type = !nodal.bits<8>}, {name = \"data\", type "
      "= !nodal.shaped<\"2\", !nodal.uint<3>>}]>";
  boundaryBatch("interface-boundary-nominal-nested-complete-leaves",
                domain() + definition(nominalPayload) +
                    generatedInterface(instance("inside", "source", "out", nominalPayload)),
                true);
  {
    ++cases;
    auto parsed = parseSourceString<mlir::ModuleOp>(
        model(domain() + hiddenDefinition(bits) +
              generatedInterface(hiddenInstance("inside", "clock", bits))),
        ParserConfig(&context, false));
    bool passed = false;
    if (parsed) {
      Operation *owner = &parsed->getBody()->front();
      SmallVector<nodal::DigitalInterfaceEndpoint> endpoints;
      if (succeeded(nodal::collectDigitalInterfaceEndpoints(owner, endpoints)) &&
          endpoints.size() == 1 && endpoints[0].leaves.size() == 2) {
        auto &endpoint = endpoints[0];
        auto facts = nodal::inspectDigitalInterfaceBoundaryTypes(
            owner, {{endpoint.operation, endpoint.leaves[1].declaration},
                    {endpoint.operation, endpoint.leaves[0].declaration},
                    {endpoint.operation, endpoint.leaves[1].declaration}});
        passed = succeeded(facts) && facts->size() == 3 &&
                 (*facts)[0].payload.leaves[0].width.bounds.upper == 8 &&
                 (*facts)[1].payload.leaves[0].width.bounds.upper == 1 &&
                 (*facts)[2].member == (*facts)[0].member;
      }
    }
    failures += !passed;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ")
        << "interface-boundary-seed-order-and-original-member-identity\n";
  }
  {
    ++cases;
    const std::string layered = "!nodal.shaped<\"Fixture.family.width\", " + lexicalWidth + ">";
    auto parsed = parseSourceString<mlir::ModuleOp>(
        model(domain() + generatedInterface(widthProducer + definition(layered) +
                                                instance("inside", "source", "out", layered,
                                                         "\"Fixture.family.width\""),
                                            "9", "family", "2", "3")),
        ParserConfig(&context, false));
    bool passed = false;
    if (parsed) {
      Operation *owner = &parsed->getBody()->front();
      SmallVector<nodal::DigitalInterfaceEndpoint> endpoints;
      if (succeeded(nodal::collectDigitalInterfaceEndpoints(owner, endpoints)) &&
          endpoints.size() == 1 && endpoints[0].leaves.size() == 1) {
        auto &endpoint = endpoints[0];
        auto domain = nodal::resolveGeneratedDomain(endpoint.operation->getParentOp());
        auto facts = nodal::inspectDigitalInterfaceBoundaryTypes(
            owner, {{endpoint.operation, endpoint.leaves[0].declaration}});
        if (succeeded(domain) && domain->attainedInductionBounds && succeeded(facts) &&
            facts->size() == 1) {
          auto atom = nodal::makeOpaqueIntegerExpression(domain->induction,
                                                         *domain->attainedInductionBounds);
          auto expected = nodal::composeIntegerExpression(
              "add", {atom, nodal::makeLiteralIntegerExpression(3)});
          const auto &fact = facts->front();
          passed =
              succeeded(expected) && fact.outerDimensions.size() == 1 &&
              fact.payload.leaves.size() == 1 && fact.payload.leaves[0].dimensions.size() == 1 &&
              fact.payload.leaves[0].dimensions[0].extents.size() == 1 &&
              fact.payload.leaves[0].width.bounds.lower == 5 &&
              fact.payload.leaves[0].width.bounds.upper == 11 &&
              nodal::equivalentInstantiatedIntegerExpressions(fact.payload.leaves[0].width,
                                                              *expected) &&
              nodal::equivalentInstantiatedIntegerExpressions(
                  fact.payload.leaves[0].dimensions[0].extents[0], *expected) &&
              nodal::equivalentInstantiatedIntegerExpressions(fact.outerDimensions[0], *expected);
        }
      }
    }
    failures += !passed;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ")
        << "interface-boundary-nonunit-original-induction-DAG-in-all-three-axes\n";
  }
  // Whole-family runtime carriers reuse the existing resolved component graph.
  // Data and enables retain every axis, with no scalar broadcast or lane nodes.
  auto shaped = [](StringRef dimensions, StringRef element) {
    return "!nodal.shaped<\"" + dimensions.str() + "\", " + element.str() + ">";
  };
  auto liftFamily = [&](std::string source, StringRef dimensions = "2,3",
                        StringRef mode = "push_pull") {
    source = replaceEvery(source, resolved(mode), "__NET__");
    source = replaceEvery(source, "!nodal.driver<" + bits + ">", "__DRIVER__");
    source = replaceEvery(source, bits, "__DATA__");
    source = replaceEvery(source, boolean, "__ENABLE__");
    source = replaceEvery(source, "__NET__", shaped(dimensions, resolved(mode)));
    source = replaceEvery(source, "__DRIVER__", shaped(dimensions, "!nodal.driver<" + bits + ">"));
    source = replaceEvery(source, "__DATA__", shaped(dimensions, bits));
    return replaceEvery(source, "__ENABLE__", shaped(dimensions, boolean));
  };
  auto familyValues = [&](StringRef dimensions = "2,3", StringRef clock = "clock") {
    return port("value", shaped(dimensions, bits), "input", clock) +
           port("enable", shaped(dimensions, boolean), "input", clock);
  };
  const std::string familyDriver = liftFamily(driver("driver", "a"));
  const std::string familyDrive = liftFamily(netDrive("driver", "a"));
  const std::string familySplit = liftFamily(split("b"));
  const std::string familyRead = liftFamily(resolvedRead("b"));
  const std::string familyRuntime = familyBody() + familyValues() + familyDriver + familyDrive;
  check("resolved-family-pointwise-read", familyBody() + familyRead, true);
  check("resolved-family-pointwise-drive-and-split", familyRuntime + familySplit + familyRead,
        true);
  check("resolved-family-pointwise-explicit-release",
        familyBody() + familyDriver + liftFamily(release("driver", "a")), true);
  check(
      "resolved-family-pointwise-pass-through",
      replace(familyRuntime, connect("a", "b", familyType("2,3")), liftFamily(through("a", "b"))) +
          familySplit,
      true);
  auto modeFamily = [&](StringRef mode, StringRef active) {
    auto body = replaceEvery(familyBody(), "push_pull", mode);
    return body + familyValues() + liftFamily(driver("driver", "a", mode), "2,3", mode) +
           liftFamily(replace(level(active, mode), "%net", "%a"), "2,3", mode) +
           liftFamily(split("b", mode), "2,3", mode);
  };
  check("resolved-family-pointwise-open-drain-low", modeFamily("open_drain", "low"), true);
  check("resolved-family-pointwise-open-source-high", modeFamily("open_source", "high"), true);
  check("resolved-family-pointwise-level-mode-mismatch", modeFamily("open_drain", "high"), false,
        "", "constrained level drive requires its declared low/high resolution mode");
  check("resolved-family-enable-cannot-broadcast",
        replace(replace(familyRuntime, port("enable", shaped("2,3", boolean)),
                        literal("enable", boolean)),
                ", " + shaped("2,3", boolean) + ") -> ()", ", " + boolean + ") -> ()"),
        false, "", "conditional driver enable requires an actual owned canonical Bool");
  check("resolved-family-enable-axes-cannot-permute",
        replaceEvery(familyRuntime, shaped("2,3", boolean), shaped("3,2", boolean)), false, "",
        "conditional driver enable requires an actual owned canonical Bool");
  check("resolved-family-data-cannot-broadcast",
        replaceEvery(familyRuntime, shaped("2,3", bits), bits), false, "",
        "conditional value drive requires permitted mode and exact owned value type");
  check("resolved-family-data-axes-cannot-permute",
        replaceEvery(familyRuntime, shaped("2,3", bits), shaped("3,2", bits)), false, "",
        "conditional value drive requires permitted mode and exact owned value type");
  check("resolved-family-driver-cannot-drop-shape",
        familyBody() + replaceEvery(familyDriver, shaped("2,3", "!nodal.driver<" + bits + ">"),
                                    "!nodal.driver<" + bits + ">"),
        false, "", "driver requires exact element type and unique owned authored identity");
  check("resolved-family-driver-cannot-permute-shape",
        familyBody() + replaceEvery(familyDriver, shaped("2,3", "!nodal.driver<" + bits + ">"),
                                    shaped("3,2", "!nodal.driver<" + bits + ">")),
        false, "", "driver requires exact element type and unique owned authored identity");
  check("resolved-family-split-enable-cannot-drop-shape",
        familyRuntime + replace(familySplit, shaped("2,3", boolean), boolean), false, "",
        "net read/split must retain exact value and canonical Bool result types");
  check("resolved-family-read-cannot-permute-shape",
        familyBody() + replace(familyRead, shaped("2,3", bits), shaped("3,2", bits)), false, "",
        "net read/split must retain exact value and canonical Bool result types");
  check("resolved-family-split-missing-driver", familyBody() + familySplit, false, "",
        "split requires exactly one authored conditional driver");
  check("resolved-family-released-driver-not-conditional",
        familyBody() + familyDriver + liftFamily(release("driver", "a")) + familySplit, false, "",
        "split requires exactly one authored conditional driver");
  check("resolved-family-two-drivers-not-unique",
        familyRuntime + liftFamily(driver("other", "b") + netDrive("other", "b")) + familySplit,
        false, "", "split requires exactly one authored conditional driver");
  check("resolved-family-driver-cannot-borrow-alias-identity",
        familyBody() + familyValues() + familyDriver + liftFamily(netDrive("driver", "b")), false,
        "", "drive/release must use one actual unique driver of this exact owned net");
  check("resolved-family-driver-cannot-be-used-twice", familyRuntime + familyDrive, false, "",
        "drive/release must use one actual unique driver of this exact owned net");
  check("resolved-family-monitor-cannot-create-driver",
        familyBody() + liftFamily(driver("driver", "alias")), false, "",
        "resolved endpoint does not permit creating or using a driver");
  const auto driveOnlyFamily = domain() + resolvedDefinition() +
                               instance("left", "source", "drive", resolved(), "\"2\", \"3\"") +
                               member("a", "left", familyType("2,3"));
  check("resolved-family-drive-only-cannot-read", driveOnlyFamily + liftFamily(resolvedRead("a")),
        false, "", "drive-only Interface capability cannot be read or split");
  check("resolved-family-unversioned-read-refused",
        familyBody() + replace(familyRead, "metadata = {" + tag + "}", "metadata = {}"), false);
  check("resolved-family-unversioned-driver-refused",
        familyBody() + replace(familyDriver, "metadata = {" + tag + "}", "metadata = {}"), false);
  check("resolved-family-nested-wrapper-refused",
        replaceEvery(familyBody() + familyRead, familyType("2,3"),
                     shaped("2", shaped("3", resolved()))),
        false, "", "member SSA result must retain the exact declared type and ordered dimensions");
  familyQuery(
      "resolved-family-read-split-use-original-whole-values",
      model(familyRuntime + familySplit + familyRead), true,
      [&](Operation *, const auto &topology, const auto &dependencies) {
        if (topology.components.size() != 1 || topology.components[0].drivers.size() != 1 ||
            topology.components[0].splits.size() != 1 ||
            topology.components[0].shape.axes.size() != 2)
          return false;
        Operation *effect = topology.components[0].drivers[0];
        Operation *splitOp = topology.components[0].splits[0];
        unsigned matched = 0;
        for (const auto &entry : dependencies)
          for (const auto &edge : entry.sources)
            if ((entry.value == splitOp->getResult(0) && edge.first == effect->getOperand(2)) ||
                (entry.value == splitOp->getResult(1) && edge.first == effect->getOperand(3)))
              ++matched;
        return matched == 2;
      });
  auto carrierHierarchy = [&](bool parentWrites, bool childWrites, bool parentSplit,
                              bool childSplit) {
    std::string source = arrayResolvedHierarchy();
    auto parentOps =
        familyValues("P") +
        (parentWrites ? liftFamily(driver("driver", "top") + netDrive("driver", "top"), "P") : "") +
        (parentSplit
             ? liftFamily(replace(split("top"), "metadata = {", "hierarchy_driver, metadata = {"),
                          "P")
             : "");
    for (auto entry : {std::pair{StringRef("value"), StringRef(bits)},
                       std::pair{StringRef("enable"), StringRef(boolean)}}) {
      auto type = shaped("P", entry.second);
      parentOps += "%child_" + entry.first.str() +
                   " = \"nodal.instance_port\"() <{instance = @child, port = \"" +
                   entry.first.str() + "\", direction = \"input\", metadata = {}}> : () -> " +
                   type + "\n\"nodal.digital_assign\"(%child_" + entry.first.str() + ", %" +
                   entry.first.str() + ") <{kind = \"continuous\", metadata = {}}> : (" + type +
                   ", " + type + ") -> ()\n";
    }
    auto childOps =
        familyValues("N", "required") +
        (childWrites
             ? liftFamily(driver("driver", "exported") + netDrive("driver", "exported"), "N")
             : "") +
        (childSplit ? liftFamily(replace(split("exported"), "metadata = {",
                                         "hierarchy_driver, metadata = {"),
                                 "N")
                    : "");
    source = replace(source, "}) : () -> ()\n\"nodal.module\"() <{sym_name = \"Child\"",
                     parentOps + "}) : () -> ()\n\"nodal.module\"() <{sym_name = \"Child\"");
    return replace(source, observe("exported", familyType("N")),
                   observe("exported", familyType("N")) + childOps);
  };
  hierarchy("resolved-family-child-driver-parent-split", carrierHierarchy(false, true, true, false),
            true);
  hierarchy("resolved-family-parent-driver-child-split", carrierHierarchy(true, false, false, true),
            true);
  hierarchy("resolved-family-boundary-two-drivers-reject-split",
            carrierHierarchy(true, true, false, true), false,
            "hierarchy-driver split must have no local authored value driver");
  hierarchy("resolved-family-boundary-missing-driver-reject-split",
            carrierHierarchy(false, false, true, false), false,
            "resolved hierarchy split requires exactly one actual conditional driver");

  auto operationRefusal = [&](StringRef name, const std::string &body, StringRef operationName,
                              StringRef expectedDiagnostic) {
    ++cases;
    diagnosticText.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(model(body), ParserConfig(&context, false));
    Operation *effect = nullptr;
    if (parsed)
      parsed->walk([&](Operation *op) {
        if (op->getName().getStringRef() == operationName)
          effect = op;
      });
    bool passed =
        effect && failed(verify(effect)) && StringRef(diagnosticText).contains(expectedDiagnostic);
    failures += !passed;
    if (!passed)
      llvm::errs() << diagnosticText;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_MODEL_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  operationRefusal("resolved-family-ODS-nested-resolved-wrapper-refused",
                   replaceEvery(familyBody() + familyRead, familyType("2,3"),
                                shaped("2", shaped("3", resolved()))),
                   "nodal.net_read", "scalar resolved capability or one shaped resolved family");
  operationRefusal("resolved-family-ODS-nested-driver-wrapper-refused",
                   familyBody() +
                       replaceEvery(familyDriver, shaped("2,3", "!nodal.driver<" + bits + ">"),
                                    shaped("2", shaped("3", "!nodal.driver<" + bits + ">"))),
                   "nodal.net_driver", "scalar driver capability or one shaped driver family");
  operationRefusal("resolved-family-legacy-read-independent-guard",
                   familyBody() + replace(familyRead, "metadata = {" + tag + "}", "metadata = {}"),
                   "nodal.net_read", "resolved families require the typed Interface contract");
  operationRefusal("resolved-family-legacy-driver-independent-guard",
                   familyBody() +
                       replace(familyDriver, "metadata = {" + tag + "}", "metadata = {}"),
                   "nodal.net_driver", "resolved families require the typed Interface contract");
  operationRefusal(
      "resolved-family-legacy-drive-independent-guard",
      familyBody() + familyValues() + familyDriver + literal("legacyEnable", "i1") +
          replace(replace(replace(familyDrive, "metadata = {" + tag + "}", "metadata = {}"),
                          "%enable", "%legacyEnable"),
                  shaped("2,3", boolean), "i1"),
      "nodal.net_drive", "resolved families require the typed Interface contract");
  check("resolved-family-driver-illegal-lexical-scope",
        familyBody() + familyValues() + generatedInterface(familyDriver + familyDrive), false, "",
        "fully typed Interface operation requires exact lexical structural ownership");
  hierarchy("resolved-family-global-two-drivers-reject-split",
            replace(carrierHierarchy(true, true, true, false), "hierarchy_driver, ", ""), false,
            "resolved hierarchy split requires exactly one actual conditional driver");
  check("resolved-family-read-driver-feedback-remains-cycle",
        familyBody() + familyValues() + familyDriver + familyRead +
            replace(familyDrive, "%value", "%read"),
        false, "", "digital dependency graph contains a combinational value cycle");
  physicalFacts("resolved-family-cross-occurrence-split-preserves-original-shape-and-values",
                carrierHierarchy(false, true, true, false), true, [&](auto facts, auto bindings) {
                  if (facts.size() != 1 || bindings.size() != 1 || facts[0].drivers.size() != 1 ||
                      facts[0].shape.axes.size() != 1)
                    return false;
                  const auto &binding = bindings[0];
                  auto originalData = llvm::dyn_cast<nodal::ShapedType>(binding.data.getType());
                  auto originalEnable = llvm::dyn_cast<nodal::ShapedType>(binding.enable.getType());
                  auto parentData =
                      llvm::dyn_cast<nodal::ShapedType>(binding.split->getResult(0).getType());
                  return binding.driverOccurrence == 1 && binding.splitOccurrence == 0 &&
                         binding.data == binding.driver->getOperand(2) &&
                         binding.enable == binding.driver->getOperand(3) && originalData &&
                         originalEnable && parentData && originalData.getDimensions() == "N" &&
                         originalEnable.getDimensions() == "N" &&
                         parentData.getDimensions() == "P" &&
                         facts[0].drivers[0].operation == binding.driver &&
                         facts[0].drivers[0].occurrence == binding.driverOccurrence;
                });
  auto symbolicFamily =
      parameter() + familyBody("N", "\"N\"") + familyValues("N") +
      liftFamily(driver("driver", "a") + netDrive("driver", "a") + split("b"), "N");
  check("resolved-family-symbolic-pointwise-enable", symbolicFamily, true);
  auto unrelated = replaceEvery(parameter(), "N", "Q");
  unrelated = replaceEvery(unrelated, "%low", "%q_low");
  unrelated = replaceEvery(unrelated, "%high", "%q_high");
  check("resolved-family-symbolic-enable-equal-bounds-not-identity",
        unrelated + replaceEvery(symbolicFamily, shaped("N", boolean), shaped("Q", boolean)), false,
        "", "conditional driver enable requires an actual owned canonical Bool");

  llvm::outs() << "DIGITAL_INTERFACE_MODEL_RESULT " << cases - failures << '/' << cases << '\n';
  return failures ? 1 : 0;
}
