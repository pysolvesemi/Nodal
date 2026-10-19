#ifndef NODAL_DIGITAL_LOOP_TEST_FIXTURES_H
#define NODAL_DIGITAL_LOOP_TEST_FIXTURES_H
namespace {
const std::string u8 = "!nodal.uint<8>";
std::string shape(std::string size) { return "!nodal.shaped<\"" + size + "\", " + u8 + ">"; }
std::string literal(std::string name, int64_t n) {
  return "%" + name + " = \"nodal.const_literal\"() <{value = " + std::to_string(n) +
         " : i64, spelling = \"" + std::to_string(n) + "\", metadata = {}}> : () -> i64\n";
}
std::string port(std::string name, std::string type, std::string direction = "input") {
  return "\"nodal.port\"() <{sym_name = \"" + name + "\", type = " + type + ", direction = \"" +
         direction + "\", domain = @none, metadata = {}}> : () -> ()\n%" + name +
         " = \"nodal.port_value\"() <{port = @" + name + ", metadata = {}}> : () -> " + type + "\n";
}
std::string wire(std::string name, std::string type) {
  return "%" + name + " = \"nodal.digital_storage\"() <{name = \"" + name +
         "\", kind = \"wire\", metadata = {}}> : () -> " + type + "\n";
}
std::string index(std::string name, std::string root, std::string iv, std::string type) {
  return "%" + name + " = \"nodal.shape_index\"(%" + root + ", %" + iv + ") <{metadata = {}}> : (" +
         type + ", i64) -> " + u8 + "\n";
}
std::string assign(std::string target, std::string rhs, std::string type = u8) {
  return "\"nodal.digital_assign\"(%" + target + ", %" + rhs +
         ") <{kind = \"continuous\", metadata = {}}> : (" + type + ", " + type + ") -> ()\n";
}
std::string loop(std::string id, std::string captures, std::string types, std::string args,
                 std::string body) {
  return "\"nodal.hardware_loop\"(%lo, %hi, %step" + captures +
         ") <{induction = \"i\", contract_version = \"bounded-digital-v1\", effect_policy = "
         "\"ordered\", "
         "region_id = \"Fixture." +
         id + "\", induction_path = \"Fixture." + id +
         ".i\", metadata = {semantic_path = \"Fixture." + id + "\"}}> ({^bb0(%iv: i64" + args +
         "):\n" + body + "\"nodal.hardware_loop_end\"() : () -> ()\n}) : (i64, i64, i64" + types +
         ") -> ()\n";
}
std::string independent(int64_t count, int64_t step = 1, int64_t lower = 0, bool second = false) {
  std::string type = shape("8");
  auto body =
      index("dst", "target", "iv", type) + index("src", "input", "iv", type) + assign("dst", "src");
  if (second)
    body += literal("one", 1) +
            "%next = \"nodal.iteration_index\"(%iv, %one) <{operator_name = \"add\", metadata = "
            "{}}> : (i64, i64) -> i64\n" +
            index("dst2", "target", "next", type) + index("src2", "input", "next", type) +
            assign("dst2", "src2");
  return literal("lo", lower) + literal("hi", count) + literal("step", step) + port("in", type) +
         port("out", type, "output") +
         loop("loop", ", %out, %in", ", " + type + ", " + type,
              ", %target: " + type + ", %input: " + type, body);
}
std::string chain(int64_t count, bool seed = true, bool late = false) {
  std::string type = shape(std::to_string(count + 1));
  std::string start = literal("lo", 0) + literal("hi", count) + literal("step", 1) +
                      port("seed", u8) + wire("cells", type) + port("out", type, "output");
  std::string pre = index("base", "cells", "lo", type) + assign("base", "seed");
  std::string body = literal("one", 1) +
                     "%next = \"nodal.iteration_index\"(%iv, %one) <{operator_name = \"add\", "
                     "metadata = {}}> : (i64, i64) -> i64\n" +
                     index("dst", "target", "next", type) + index("src", "target", "iv", type) +
                     assign("dst", "src");
  return start + (seed && !late ? pre : "") +
         loop("chain", ", %cells", ", " + type, ", %target: " + type, body) +
         (seed && late ? pre : "") + assign("out", "cells", type);
}

std::string symbolicChain() {
  std::string source = chain(2);
  std::string
      old = literal("hi", 2),
      param =
          "\"nodal.parameter\"() <{sym_name = \"N\", type = i64, default_value = 2 : i64, "
          "parameter_kind = \"integer\", classification = \"structural\", variability = "
          "\"symbolic\", metadata = {}}> : () -> ()\n" +
          literal("minimum", 0) + literal("maximum", 1000000) +
          "\"nodal.parameter_constraint\"(%minimum, %maximum) <{parameter = @N, constraint_kind = "
          "\"range\", lower_inclusive = true, upper_inclusive = true, metadata = {}}> : (i64, i64) "
          "-> ()\n\"nodal.parameter_envelope\"() <{parameter = @N, policy = \"static_generate\", "
          "effects = [\"shape\", \"width\"], metadata = {}}> : () -> ()\n%hi = "
          "\"nodal.const_parameter_ref\"() <{parameter = @N, metadata = {}}> : () -> i64\n" +
          literal("extentOne", 1) +
          "%extent = \"nodal.const_expr\"(%hi, %extentOne) <{operator_name = \"add\", metadata = "
          "{semantic_path = \"Fixture.extent\"}}> : (i64, i64) -> i64\n";
  source.replace(source.find(old), old.size(), param);
  size_t position = 0;
  while ((position = source.find("shaped<\"3\"", position)) != std::string::npos) {
    source.replace(position, 10, "shaped<\"Fixture.extent\"");
    position += 23;
  }
  return source;
}
std::string crossLoop(bool cycle) {
  std::string type = shape("3"), setup = literal("lo", 0) + literal("hi", 2) + literal("step", 1) +
                                         port("seed", u8) + wire("a", type) +
                                         (cycle ? wire("b", type) : port("b", type)) +
                                         index("baseA", "a", "lo", type) + assign("baseA", "seed");
  if (cycle)
    setup += index("baseB", "b", "lo", type) + assign("baseB", "seed");
  auto body = [&]() {
    return literal("one", 1) +
           "%next = \"nodal.iteration_index\"(%iv, %one) <{operator_name = \"add\", metadata = "
           "{}}> : (i64, i64) -> i64\n" +
           index("dst", "target", "next", type) + index("prev", "target", "iv", type) +
           index("external", "other", "next", type) +
           "%rhs = \"nodal.digital_expr\"(%prev, %external) <{operator_name = \"digital_xor\", "
           "metadata = {}}> : (" +
           u8 + ", " + u8 + ") -> " + u8 + "\n" + assign("dst", "rhs");
  };
  setup += loop("aLoop", ", %a, %b", ", " + type + ", " + type,
                ", %target: " + type + ", %other: " + type, body());
  if (cycle)
    setup += loop("bLoop", ", %b, %a", ", " + type + ", " + type,
                  ", %target: " + type + ", %other: " + type, body());
  return setup;
}

std::string nestedWideningReduction() {
  return R"mlir(
%lo = "nodal.const_literal"() <{value = 0 : i64, spelling = "0", metadata = {}}> : () -> i64
%hi = "nodal.const_literal"() <{value = 2 : i64, spelling = "2", metadata = {}}> : () -> i64
%step = "nodal.const_literal"() <{value = 1 : i64, spelling = "1", metadata = {}}> : () -> i64
"nodal.port"() <{sym_name = "input", type = !nodal.shaped<"2", !nodal.shaped<"3", !nodal.uint<4>>>, direction = "input", domain = @none, metadata = {}}> : () -> ()
"nodal.port"() <{sym_name = "output", type = !nodal.shaped<"2", !nodal.uint<6>>, direction = "output", domain = @none, metadata = {}}> : () -> ()
%input = "nodal.port_value"() <{port = @input, metadata = {}}> : () -> !nodal.shaped<"2", !nodal.shaped<"3", !nodal.uint<4>>>
%output = "nodal.port_value"() <{port = @output, metadata = {}}> : () -> !nodal.shaped<"2", !nodal.uint<6>>
"nodal.hardware_loop"(%lo, %hi, %step, %output, %step, %input) <{induction = "i", contract_version = "bounded-digital-v1", effect_policy = "ordered", region_id = "Fixture.loop", induction_path = "Fixture.loop.arg0", metadata = {semantic_path = "Fixture.loop"}}> ({
^bb0(%iv: i64, %out: !nodal.shaped<"2", !nodal.uint<6>>, %increment: i64, %in: !nodal.shaped<"2", !nodal.shaped<"3", !nodal.uint<4>>>):
%dst = "nodal.shape_index"(%out, %iv) <{metadata = {}}> : (!nodal.shaped<"2", !nodal.uint<6>>, i64) -> !nodal.uint<6>
%four = "nodal.const_literal"() <{value = 4 : i64, spelling = "4", metadata = {}}> : () -> i64
%three = "nodal.const_literal"() <{value = 3 : i64, spelling = "3", metadata = {}}> : () -> i64
%depth = "nodal.const_expr"(%three) <{operator_name = "ceil_log2", metadata = {}}> : (i64) -> i64
%growth = "nodal.const_expr"(%depth, %increment) <{operator_name = "mul", metadata = {}}> : (i64, i64) -> i64
%width = "nodal.const_expr"(%four, %growth) <{operator_name = "add", metadata = {semantic_path = "Fixture.width"}}> : (i64, i64) -> i64
%row = "nodal.shape_index"(%in, %iv) <{metadata = {}}> : (!nodal.shaped<"2", !nodal.shaped<"3", !nodal.uint<4>>>, i64) -> !nodal.shaped<"3", !nodal.uint<4>>
%sum = "nodal.collection"(%row, %increment) <{kind = "reduce_balanced", region_id = "Fixture.reduce", operandSegmentSizes = array<i32: 1, 0, 1>, metadata = {semantic_path = "Fixture.reduce"}}> ({
^bb0(%lw: i64, %rw: i64, %left: !nodal.digital<"uint", "Fixture.reduce.arg0">, %right: !nodal.digital<"uint", "Fixture.reduce.arg1">, %one: i64):
%maximum = "nodal.const_expr"(%lw, %rw) <{operator_name = "max", metadata = {}}> : (i64, i64) -> i64
%nextwidth = "nodal.const_expr"(%maximum, %one) <{operator_name = "add", metadata = {semantic_path = "Fixture.nextwidth"}}> : (i64, i64) -> i64
%next = "nodal.digital_expr"(%left, %right) <{operator_name = "digital_add", metadata = {}}> : (!nodal.digital<"uint", "Fixture.reduce.arg0">, !nodal.digital<"uint", "Fixture.reduce.arg1">) -> !nodal.digital<"uint", "Fixture.nextwidth">
"nodal.collection_yield"(%next) <{metadata = {}}> : (!nodal.digital<"uint", "Fixture.nextwidth">) -> ()
}) : (!nodal.shaped<"3", !nodal.uint<4>>, i64) -> !nodal.digital<"uint", "Fixture.width">
"nodal.digital_assign"(%dst, %sum) <{kind = "continuous", metadata = {}}> : (!nodal.uint<6>, !nodal.digital<"uint", "Fixture.width">) -> ()
"nodal.hardware_loop_end"() : () -> ()
}) : (i64, i64, i64, !nodal.shaped<"2", !nodal.uint<6>>, i64, !nodal.shaped<"2", !nodal.shaped<"3", !nodal.uint<4>>>) -> ()
)mlir";
}

std::string replace(std::string text, std::string from, std::string to) {
  auto p = text.find(from);
  if (p == std::string::npos)
    llvm::report_fatal_error("missing mutation");
  text.replace(p, from.size(), to);
  return text;
}
} // namespace
#endif
