#ifndef NODAL_TEST_WAIVERTESTFIXTURES_H
#define NODAL_TEST_WAIVERTESTFIXTURES_H
#include "llvm/ADT/StringRef.h"
#include "llvm/Support/ErrorHandling.h"

#include <string>
namespace waiver_test {
inline const std::string bit = "!nodal.bits<1>";
inline const std::string clockType = "!nodal.capability<\"clock\">";
inline std::string replace(std::string value, llvm::StringRef before, llvm::StringRef after) {
  auto at = value.find(before.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing waiver fixture replacement");
  return value.replace(at, before.size(), after.str());
}
inline std::string port(std::string name, std::string domain, std::string type = bit,
                        std::string direction = "input") {
  return "\"nodal.port\"() <{sym_name = \"" + name + "\", direction = \"" + direction +
         "\", domain = @" + domain + ", type = " + type + ", metadata = {}}> : () -> ()\n%" + name +
         " = \"nodal.port_value\"() <{port = @" + name + ", metadata = {}}> : () -> " + type + "\n";
}
inline std::string domain(std::string name) {
  return "\"nodal.domain\"() <{sym_name = \"" + name +
         "\", edge = \"rising\", reset_policy = \"none\", metadata = {}}> : () -> ()\n" +
         port(name + "Clock", name, clockType) + "\"nodal.local_domain_binding\"(%" + name +
         "Clock) <{domain = @" + name + ", reset_polarity = \"active_high\", metadata = {}}> : (" +
         clockType + ") -> ()\n";
}
inline std::string setup(std::string type = bit) {
  return domain("a") + domain("b") + domain("c") + port("input", "a", type);
}
inline std::string waiver(std::string name = "waived", std::string input = "input",
                          std::string from = "a", std::string to = "b", std::string type = bit,
                          std::string relation = "kind = \"unknown\"",
                          std::string owner = "Fixture") {
  return "%" + name + " = \"nodal.waiver\"(%" + input + ") <{source_domain = @" + from +
         ", destination_domain = @" + to + ", contract = \"stateless_waiver_v1\", id = \"" + name +
         "\", reason = \"explicit external boundary responsibility\", relation = {" + relation +
         "}, obligations = {physical_safety = \"user_responsibility\", delivery = "
         "\"not_guaranteed\", reset = \"not_exempt\"}, metadata = {semantic_path = \"" +
         owner + "." + name + "\"}}> : (" + type + ") -> " + type +
         " loc(\"waiver-source.scala\":17:5)\n";
}
inline std::string module(std::string body, std::string name = "Fixture") {
  return "\"nodal.module\"() <{sym_name = \"" + name + "\", metadata = {}}> ({\n" + body +
         "}) : () -> ()\n";
}
inline std::string wrap(std::string body) { return "module {\n" + module(body) + "}\n"; }
inline std::string assign(std::string target, std::string input, std::string type = bit,
                          std::string kind = "continuous") {
  return "\"nodal.digital_assign\"(%" + target + ", %" + input + ") <{kind = \"" + kind +
         "\", metadata = {}}> : (" + type + ", " + type + ") -> ()\n";
}
inline std::string sample(std::string input = "waived", std::string owner = "b") {
  return "%state = \"nodal.digital_storage\"() <{name = \"state\", kind = \"register\", domain = "
         "@" +
         owner + ", metadata = {}}> : () -> " + bit + "\n" +
         assign("state", input, bit, "register_next");
}
inline std::string output(std::string input = "waived", std::string type = bit) {
  return port("result", "b", type, "output") + assign("result", input, type);
}
inline std::string expression(std::string name, std::string left, std::string right) {
  return "%" + name + " = \"nodal.digital_expr\"(%" + left + ", %" + right +
         ") <{operator_name = \"bool_and\", metadata = {}}> : (" + bit + ", " + bit + ") -> " +
         bit + "\n";
}
inline std::string crossing(std::string name, std::string input, std::string from, std::string to,
                            std::string kind = "sync") {
  std::string contract = kind == "sync" ? "level_v1" : kind + "_v1";
  std::string obligations;
  if (kind == "pulse")
    obligations = "obligations = {spacing = \"returned_acknowledgement\", width = "
                  "\"single_source_cycle\", reset_epoch = \"common_assertion\"}, ";
  if (kind == "gray")
    obligations = "obligations = {transition = \"one_bit_per_source_sample\", skew = "
                  "\"less_than_source_period\", stability = \"stable_between_source_samples\"}, ";
  if (kind == "handshake") {
    contract = "handshake_snapshot_v1";
    obligations = "obligations = {delivery = \"latest_snapshot_coalescing\", bundled_data = "
                  "\"settled_before_request_capture\", reset_epoch = \"common_assertion\"}, ";
  }
  return "%" + name + " = \"nodal.crossing\"(%" + input + ") <{source_domain = @" + from +
         ", destination_domain = @" + to + ", kind = \"" + kind + "\", contract = \"" + contract +
         "\", stages = 2 : i64, " + obligations + "metadata = {semantic_path = \"Fixture." + name +
         "\"}}> : (" + bit + ") -> " + bit + "\n";
}
inline std::string pulseSetup() {
  const std::string reset = "!nodal.capability<\"reset\">";
  std::string result;
  for (std::string name : {"a", "b", "c"})
    result += "\"nodal.domain\"() <{sym_name = \"" + name +
              "\", edge = \"rising\", reset_policy = \"async\", metadata = {}}> : () -> ()\n" +
              port(name + "Clock", name, clockType);
  result += port("root", "a", reset);
  for (std::string name : {"a", "b", "c"})
    result += "%" + name +
              "Reset = \"nodal.reset_boundary\"(%root) <{reset_polarity = "
              "\"active_high\", release = \"external\", release_domain = @" +
              name + ", metadata = {}}> : (" + reset + ") -> " + reset +
              "\n\"nodal.local_domain_binding\"(%" + name + "Clock, %" + name +
              "Reset) <{domain = @" + name +
              ", reset_polarity = \"active_high\", metadata = {}}> : (" + clockType + ", " + reset +
              ") -> ()\n";
  return result + port("input", "a");
}
} // namespace waiver_test
#endif
