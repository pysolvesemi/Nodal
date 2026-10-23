#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
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
  auto check = [&](StringRef name, const std::string &body, bool expected, StringRef header = "") {
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
    bool passed = valid == expected && unchanged && roundtrip;
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

  llvm::outs() << "DIGITAL_INTERFACE_MODEL_RESULT " << cases - failures << '/' << cases << '\n';
  return failures ? 1 : 0;
}
