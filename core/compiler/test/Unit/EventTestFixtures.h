#ifndef NODAL_TEST_EVENT_FIXTURES_H
#define NODAL_TEST_EVENT_FIXTURES_H
#include "llvm/Support/ErrorHandling.h"

#include <string>
namespace event_test {
inline const std::string bit = "!nodal.bits<1>";
inline std::string wrap(const std::string &body) {
  return "module attributes {nodal.root.module = @Fixture} { \"nodal.module\"() <{sym_name = "
         "\"Fixture\", metadata = {}}> ({\n" +
         body + "}) : () -> () }";
}
inline std::string replace(std::string s, const std::string &a, const std::string &b) {
  auto p = s.find(a);
  if (p == std::string::npos)
    llvm::report_fatal_error("event test replacement missing");
  s.replace(p, a.size(), b);
  return s;
}
inline std::string port(std::string name, std::string direction = "input", std::string type = bit,
                        std::string domain = "none") {
  return "\"nodal.port\"() <{sym_name = \"" + name + "\", type = " + type + ", direction = \"" +
         direction + "\", domain = @" + domain + ", metadata = {}}> : () -> ()\n%" + name +
         " = \"nodal.port_value\"() <{port = @" + name + ", metadata = {}}> : () -> " + type + "\n";
}
inline std::string literal(std::string name = "one", int value = 1, std::string type = bit) {
  return "%" + name + " = \"nodal.constant\"() <{value = " + std::to_string(value) +
         " : i64, metadata = {}}> : () -> " + type + "\n";
}
inline std::string process(std::string name = "event", std::string edge = "rising",
                           std::string targets = "%output", std::string captures = "",
                           int targetCount = 1, int captureCount = 0, std::string targetTypes = bit,
                           std::string captureTypes = "") {
  return "\"nodal.event_process\"(%trigger, " + targets +
         (captures.empty() ? "" : ", " + captures) + ") <{sym_name = \"Fixture." + name +
         "\", edge = \"" + edge +
         "\", contract = \"root_event_v1\", obligations = {trigger = "
         "\"known_levels_definite_edges\", "
         "external_sampling = \"user_setup_hold\", startup = \"unspecified\", updates = "
         "\"simultaneous_pre_event\"}, "
         "operandSegmentSizes = array<i32: 1, " +
         std::to_string(targetCount) + ", " + std::to_string(captureCount) +
         ">, metadata = {semantic_path = \"Fixture." + name + "\"}}> : (" + bit + ", " +
         targetTypes + (captureTypes.empty() ? "" : ", " + captureTypes) +
         ") -> () loc(\"EventFixture.scala\":12:3)\n";
}
inline std::string update(std::string value = "one", std::string target = "output",
                          std::string name = "event", std::string type = bit) {
  return "\"nodal.digital_assign\"(%" + target + ", %" + value +
         ") <{kind = \"event_update\", event_process = @\"Fixture." + name +
         "\", metadata = {semantic_path = \"Fixture." + target + ".update\"}}> : (" + type + ", " +
         type + ") -> ()\n";
}
inline std::string setup(std::string type = bit) {
  return port("trigger") + port("output", "output", type);
}
inline std::string fixture() { return setup() + literal() + process() + update(); }
} // namespace event_test
#endif
