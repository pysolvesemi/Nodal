#include "nodal/Dialect/Nodal/MemoryModel.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"

#include "llvm/Support/raw_ostream.h"

#include <chrono>
#include <functional>
#include <map>
#include <string>
using namespace mlir;
namespace {
const std::string u2 = "!nodal.uint<2>", u32 = "!nodal.uint<32>", bit = "!nodal.bits<1>",
                  m4 = "!nodal.uint<4>";
std::string enumeration();
std::string wrap(std::string body) {
  std::string outside;
  auto pos = body.find("\"nodal.enum\"");
  if (pos != std::string::npos) {
    // Test enum definitions occupy the actual root symbol table, like bridge IR.
    auto end = body.find("}) : () -> ()\n", pos);
    if (end != std::string::npos) {
      end += std::string("}) : () -> ()\n").size();
      outside = body.substr(pos, end - pos);
      body.erase(pos, end - pos);
    }
  }
  return "module { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({\n" + body +
         "}) : () -> () " + outside + "}";
}
std::string print(Operation *op) {
  std::string s;
  llvm::raw_string_ostream os(s);
  op->print(os);
  return s;
}
std::string replace(std::string s, StringRef a, StringRef b) {
  size_t at = s.find(a.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing test replacement");
  s.replace(at, a.size(), b.str());
  return s;
}
std::string domain(std::string name = "core") {
  return "\"nodal.domain\"() <{sym_name = \"" + name +
         "\", edge = \"rising\", reset_policy = \"none\", metadata = {}}> : () -> ()\n";
}
std::string port(std::string name, std::string type, std::string d = "core") {
  return "\"nodal.port\"() <{sym_name = \"" + name + "\", type = " + type +
         ", direction = \"input\", domain = @" + d + ", metadata = {}}> : () -> ()\n%" + name +
         " = \"nodal.port_value\"() <{port = @" + name + ", metadata = {}}> : () -> " + type + "\n";
}
std::string setup() {
  return domain() + port("clock", "!nodal.capability<\"clock\">") +
         "\"nodal.local_domain_binding\"(%clock) <{domain = @core, reset_polarity = "
         "\"active_high\", metadata = {}}> : (!nodal.capability<\"clock\">) -> ()\n";
}
std::string literal(std::string name, int64_t n, std::string type = u2) {
  return "%" + name + " = \"nodal.const_literal\"() <{value = " + std::to_string(n) +
         " : i64, spelling = \"" + std::to_string(n) + "\", metadata = {}}> : () -> " + type + "\n";
}
std::string memory(std::string type = u32, int64_t latency = 1, std::string policy = "old_data",
                   std::string ordering = "ordered", std::string depth = "4") {
  return "\"nodal.memory\"() <{sym_name = \"ram\", element_type = " + type + ", depth = \"" +
         depth + "\", read_latency = " + std::to_string(latency) + " : i64, read_under_write = \"" +
         policy + "\", ordering = \"" + ordering +
         "\", domain = @core, metadata = {}}> : () -> ()\n";
}
std::string read(std::string name = "read", std::string a = "address", std::string type = u32,
                 std::string at = u2) {
  return "%" + name + " = \"nodal.memory_read\"(%" + a +
         ") <{memory = @ram, domain = @core, metadata = {semantic_path = \"Fixture." + name +
         "\"}}> : (" + at + ") -> " + type + "\n";
}
std::string write(std::string id = "write", std::string a = "address", std::string d = "data",
                  std::string m = "mask", std::string type = u32, std::string mt = m4,
                  std::string at = u2) {
  return "\"nodal.memory_write\"(%" + a + ", %" + d + ", %" + m +
         ") <{memory = @ram, domain = @core, metadata = {semantic_path = \"Fixture." + id +
         "\"}}> : (" + at + ", " + type + ", " + mt + ") -> ()\n";
}
std::string input(std::string type = u32, std::string mt = m4) {
  return port("address", u2) + port("data", type) + port("mask", mt);
}
std::string branch(std::string type = u32, std::string mt = m4, bool both = true) {
  std::string args = "%a: " + u2 + ", %d: " + type + ", %m: " + mt;
  return "\"nodal.behavioral_if\"(%enable, %address, %data, %mask, %address, %data, %mask) "
         "<{contract_version = \"behavioral-if-v1\", region_id = \"Fixture.choice\", "
         "operandSegmentSizes = array<i32: 1, 3, 3>, metadata = {semantic_path = "
         "\"Fixture.choice\"}}> ({^bb0(" +
         args + "):\n" + write("choice.left", "a", "d", "m", type, mt) + "}, {^bb0(" + args +
         "):\n" + (both ? write("choice.right", "a", "d", "m", type, mt) : "") + "}) : (" + bit +
         ", " + u2 + ", " + type + ", " + mt + ", " + u2 + ", " + type + ", " + mt + ") -> ()\n";
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
std::string enumeration() {
  return "\"nodal.enum\"() <{sym_name = \"Mode\", underlying_type = !nodal.uint<2>, encoding = "
         "\"custom\", metadata = {abi_version = 1 : i64, semantic_type = \"Mode\", abi_hash = "
         "\"df5b4a51f3fade96f1ab8a106bbcb0114b5d4601e4e2c6aebcf38763dd61e66a\"}}> "
         "({\"nodal.enum_case\"() <{sym_name = \"Idle\", value = 0 : i64, metadata = {case_id = "
         "\"Mode.Idle\"}}> : () -> () \"nodal.enum_case\"() <{sym_name = \"Run\", value = 1 : i64, "
         "metadata = {case_id = \"Mode.Run\"}}> : () -> () }) : () -> ()\n";
}
std::string parameter(int low, int high) {
  return "\"nodal.parameter\"() <{sym_name = \"N\", type = i64, default_value = " +
         std::to_string(low) +
         " : i64, parameter_kind = \"integer\", classification = \"structural\", variability = "
         "\"symbolic\", metadata = {}}> : () -> ()\n" +
         literal("low", low, "i64") + literal("high", high, "i64") +
         "\"nodal.parameter_constraint\"(%low, %high) <{parameter = @N, constraint_kind = "
         "\"range\", lower_inclusive = true, upper_inclusive = true, metadata = {}}> : (i64, i64) "
         "-> ()\n"
         "\"nodal.parameter_envelope\"() <{parameter = @N, policy = \"static_generate\", effects = "
         "[\"shape\", \"width\"], metadata = {}}> : () -> ()\n";
}
} // namespace
int main(int argc, char **argv) {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream os(diagnostics);
    d.print(os);
    os << '\n';
    return success();
  });
  unsigned cases = 0, failures = 0;
  auto report = [&](StringRef name, bool good) {
    ++cases;
    failures += !good;
    (good ? llvm::outs() : llvm::errs())
        << "MEMORY_MODEL_" << (good ? "PASS " : "FAIL ") << name << '\n';
    if (!good)
      llvm::errs() << diagnostics;
    diagnostics.clear();
  };
  auto parse = [&](const std::string &body) {
    return parseSourceString<mlir::ModuleOp>(wrap(body), ParserConfig(&context, false));
  };
  auto check = [&](StringRef name, const std::string &body, bool expected) {
    auto m = parse(body);
    std::string before = m ? print(*m) : "";
    bool valid = m && succeeded(verify(*m));
    report(name, m && valid == expected && before == print(*m));
    return m;
  };
  auto normalize = [&](StringRef name, const std::string &body) {
    auto m = parse(body);
    bool valid = m && succeeded(verify(*m));
    if (valid) {
      auto owner = *m->getOps<nodal::ModuleOp>().begin();
      valid = succeeded(nodal::normalizeSequentialModel(owner)) && succeeded(verify(*m));
      if (valid) {
        std::string first = print(*m);
        valid = succeeded(nodal::normalizeSequentialModel(owner)) && first == print(*m);
      }
    }
    report(name, valid);
    return m;
  };
  std::string basic = setup() + memory() + input() + read() + write();
  if (argc > 1 && StringRef(argv[1]) == "--emit-fixture") {
    llvm::outs() << wrap(basic);
    return 0;
  }
  normalize("typed-byte-32-4-roundtrip-idempotence", basic);
  for (const std::string &type :
       {bit, u32, std::string("!nodal.sint<16>"), std::string("i64"), std::string("f64"),
        std::string("!nodal.enum<\"Mode\", 2>"),
        std::string("!nodal.shaped<\"2,3\", !nodal.uint<8>>"),
        std::string("!nodal.struct<\"Packet\", [{name = \"samples\", type = !nodal.shaped<\"2\", "
                    "!nodal.struct<\"Leaf\", [{name = \"real\", type = f64}, {name = \"count\", "
                    "type = i64}]>>}, {name = \"mode\", type = !nodal.enum<\"Mode\", 2>}]>")}) {
    std::string outside = type.find("Mode") != std::string::npos ? enumeration() : "";
    normalize("whole-element-" + type, setup() + outside + memory(type) + input(type, bit) +
                                           read("read", "address", type) +
                                           write("write", "address", "data", "mask", type, bit));
    normalize("captured-whole-element-" + type,
              setup() + outside + memory(type) + input(type, bit) + port("enable", bit) +
                  read("read", "address", type) + branch(type, bit));
  }
  for (const std::string &type :
       {std::string("i64"), std::string("f64"), std::string("!nodal.enum<\"Mode\", 2>"),
        std::string("!nodal.shaped<\"2\", !nodal.uint<8>>")})
    check("reject-partial-" + type,
          setup() + (type.find("Mode") != std::string::npos ? enumeration() : "") + memory(type) +
              input(type) + write("write", "address", "data", "mask", type),
          false);
  check("mask-word-width-mismatch",
        replace(basic, "type = !nodal.uint<4>", "type = !nodal.uint<3>"), false);
  std::string wide = setup() + memory() + literal("wide", 3, "!nodal.uint<128>") +
                     read("read", "wide", u32, "!nodal.uint<128>");
  check("any-width-constant-in-range", wide, true);
  check("constant-out-of-range",
        replace(replace(wide, "value = 3", "value = 4"), "spelling = \"3\"", "spelling = \"4\""),
        false);
  check("runtime-address-full-envelope",
        setup() + memory() + port("wide", "!nodal.uint<3>") +
            read("read", "wide", u32, "!nodal.uint<3>"),
        false);
  check("safe-symbolic-depth",
        setup() + parameter(4, 9) + memory(u32, 1, "old_data", "ordered", "N") + input() + read() +
            write(),
        true);
  check("unsafe-symbolic-depth-no-default",
        setup() + parameter(2, 9) + memory(u32, 1, "old_data", "ordered", "N") + input() + read() +
            write(),
        false);
  std::string derived =
      setup() + memory() + literal("left", 1, "!nodal.uint<64>") +
      literal("right", 2, "!nodal.uint<64>") +
      "%derived = \"nodal.digital_expr\"(%left, %right) <{operator_name = \"digital_add\", "
      "metadata = {}}> : (!nodal.uint<64>, !nodal.uint<64>) -> !nodal.uint<65>\n" +
      read("read", "derived", u32, "!nodal.uint<65>");
  check("derived-constant-address-without-truncation", derived, true);
  check("derived-runtime-unknown-address-rejected",
        replace(derived, literal("left", 1, "!nodal.uint<64>"), port("left", "!nodal.uint<64>")),
        false);
  std::string widths =
      parameter(2, 4) +
      "%N = \"nodal.const_parameter_ref\"() <{parameter = @N, metadata = {}}> : () -> i64\n" +
      literal("eight", 8, "i64") +
      "%word = \"nodal.const_expr\"(%N, %eight) <{operator_name = \"mul\", metadata = "
      "{semantic_path = \"Fixture.word\"}}> : (i64, i64) -> i64\n";
  std::string wordType = "!nodal.digital<\"uint\", \"Fixture.word\">",
              maskType = "!nodal.digital<\"uint\", \"N\">";
  normalize("exact-symbolic-byte-mask",
            setup() + widths + memory(wordType) + input(wordType, maskType) +
                read("read", "address", wordType) +
                write("write", "address", "data", "mask", wordType, maskType));
  normalize("singleton-symbolic-whole-mask",
            setup() + parameter(1, 1) + memory("f64") + input("f64", maskType) +
                read("read", "address", "f64") +
                write("write", "address", "data", "mask", "f64", maskType));
  std::string requirements =
      setup() +
      "\"nodal.domain_requirement\"() <{sym_name = \"required\", metadata = {}}> : () -> ()\n"
      "\"nodal.domain_bind\"() <{requirement = @required, actual = @core, metadata = {}}> : () -> "
      "()\n";
  check("memory-declaration-unique-requirement-binding",
        requirements + replace(memory(), "domain = @core", "domain = @required") + input() +
            read() + write(),
        true);
  check(
      "memory-requirement-unresolved",
      setup() +
          "\"nodal.domain_requirement\"() <{sym_name = \"required\", metadata = {}}> : () -> ()\n" +
          replace(memory(), "domain = @core", "domain = @required") + input() + read(),
      false);
  check("enum-ABI-cannot-be-forged",
        setup() +
            replace(enumeration(),
                    "df5b4a51f3fade96f1ab8a106bbcb0114b5d4601e4e2c6aebcf38763dd61e66a",
                    "df5b4a51f3fade96f1ab8a106bbcb0114b5d4601e4e2c6aebcf38763dd61e660") +
            memory("!nodal.enum<\"Mode\", 2>") + port("address", u2) +
            read("read", "address", "!nodal.enum<\"Mode\", 2>"),
        false);
  check("forged-memory-symbol", replace(basic, "memory = @ram", "memory = @missing"), false);
  check("forged-read-type",
        replace(basic, ") -> !nodal.uint<32>\n\"nodal.memory_write",
                ") -> !nodal.bits<32>\n\"nodal.memory_write"),
        false);
  check("forged-domain",
        replace(basic, "memory = @ram, domain = @core", "memory = @ram, domain = @missing"), false);
  auto asserted = parse(basic);
  if (asserted) {
    asserted->walk([&](nodal::MemoryReadOp op) {
      op->setAttr("address_upper", IntegerAttr::get(IntegerType::get(&context, 64), 3));
    });
    report("asserted-range-not-authority", failed(verify(*asserted)));
  } else
    report("asserted-range-not-authority", false);
  check("foreign-address-domain",
        setup() + domain("other") + memory() + port("address", u2, "other") + read(), false);
  check("foreign-data-domain",
        setup() + domain("other") + memory() + port("address", u2) + port("data", u32, "other") +
            port("mask", m4) + write(),
        false);
  check("foreign-mask-domain",
        setup() + domain("other") + memory() + port("address", u2) + port("data", u32) +
            port("mask", m4, "other") + write(),
        false);
  check("foreign-guard-domain",
        setup() + domain("other") + memory() + input() + port("enable", bit, "other") + branch(),
        false);
  for (bool resolved : {false, true}) {
    std::string prefix = setup() + domain("other") + memory();
    check(resolved ? "resolved-interface-address-local" : "interface-address-local",
          prefix + interfaceInput(u2, "core", resolved) + read("read", "ifaceInput"), true);
    check(resolved ? "resolved-interface-address-foreign" : "interface-address-foreign",
          prefix + interfaceInput(u2, "other", resolved) + read("read", "ifaceInput"), false);
  }
  check("memory-read-foreign-domain-cannot-feed-register",
        setup() + domain("other") + port("otherClock", "!nodal.capability<\"clock\">", "other") +
            "\"nodal.local_domain_binding\"(%otherClock) <{domain = @other, reset_polarity = "
            "\"active_high\", metadata = {}}> : (!nodal.capability<\"clock\">) -> ()\n" +
            memory() + port("address", u2) + read() +
            "%state = \"nodal.digital_storage\"() <{name = \"state\", kind = \"register\", domain "
            "= @other, metadata = {}}> : () -> " +
            u32 +
            "\n"
            "\"nodal.digital_assign\"(%state, %read) <{kind = \"register_next\", metadata = {}}> : "
            "(" +
            u32 + ", " + u32 + ") -> ()\n",
        false);
  normalize("runtime-Integer-read-capture-is-not-static",
            setup() + memory("i64") + port("address", u2) + port("mask", bit) +
                port("enable", bit) + read("data", "address", "i64") + branch("i64", bit));
  check("runtime-Integer-cannot-forge-static-guard",
        setup() + memory("i64") + input("i64", bit) + literal("zeroInteger", 0, "i64") +
            "%pred = \"nodal.static_compare\"(%data, %zeroInteger) <{comparison = \"eq\", metadata "
            "= {}}> : (i64, i64) -> !nodal.static_predicate\n"
            "%enable = \"nodal.behavioral_predicate\"(%pred) <{metadata = {}}> : "
            "(!nodal.static_predicate) -> !nodal.bits<1>\n" +
            branch("i64", bit),
        false);
  for (StringRef policy : {"old_data", "new_data", "no_change", "undefined"}) {
    check("async-read-only-" + policy.str(),
          setup() + memory(u32, 0, policy.str()) + input() + read(), true);
    check("async-mixed-" + policy.str(),
          setup() + memory(u32, 0, policy.str()) + input() + read() + write(),
          policy == "undefined");
  }
  std::string independent = setup() + memory(u32, 1, "new_data", "independent_ports") + input() +
                            port("enable", bit) + read();
  normalize("independent-opposite-actual-guards", independent + branch());
  check("independent-unknown-overlap", independent + write("first") + write("second"), false);
  normalize("independent-distinct-addresses", independent + literal("zero", 0) + literal("one", 1) +
                                                  write("first", "zero") + write("second", "one"));
  normalize("independent-disjoint-byte-masks",
            independent + literal("lo", 1, m4) + literal("hi", 2, m4) +
                write("first", "address", "data", "lo") + write("second", "address", "data", "hi"));
  normalize("independent-zero-mask", independent + literal("zeroMask", 0, m4) +
                                         write("first", "address", "data", "zeroMask") +
                                         write("second"));
  // Read-address feedback through the actual ordinary wire dependency graph.
  auto feedback = [&](int latency) {
    return setup() + memory(u2, latency, "undefined") +
           "%wire = \"nodal.digital_storage\"() <{name = \"wire\", kind = \"wire\", metadata = "
           "{}}> : () -> " +
           u2 + "\n" + read("read", "wire", u2) +
           "\"nodal.digital_assign\"(%wire, %read) <{kind = \"continuous\", metadata = {}}> : (" +
           u2 + ", " + u2 + ") -> ()\n";
  };
  check("sync-address-feedback-is-state-cut", feedback(1), true);
  check("async-address-feedback-cycle-rejected", feedback(0), false);
  auto canonical = normalize("order-fixture", setup() + memory() + input() + read() +
                                                  write("first") + write("second"));
  if (canonical) {
    nodal::MemoryWriteEffectOp last;
    canonical->walk([&](nodal::MemoryWriteEffectOp op) { last = op; });
    if (last) {
      last->setAttr("order", IntegerAttr::get(IntegerType::get(&context, 64), 0));
      std::string before = print(*canonical);
      auto owner = *canonical->getOps<nodal::ModuleOp>().begin();
      report("forged-order-rejected-transactionally",
             failed(verify(*canonical)) && failed(nodal::normalizeSequentialModel(owner)) &&
                 before == print(*canonical));
    }
  }
  for (StringRef mutation : {"guard", "clock", "root", "mixed"}) {
    auto forged = normalize("effect-mutation-source-" + mutation.str(), basic);
    if (!forged)
      continue;
    nodal::MemoryWriteEffectOp effect;
    Value data;
    forged->walk([&](nodal::MemoryWriteEffectOp op) {
      effect = op;
      data = op->getOperand(1);
    });
    if (!effect)
      continue;
    if (mutation == "guard")
      effect->setOperand(3, data);
    if (mutation == "clock")
      effect->setOperand(4, data);
    if (mutation == "root")
      effect->setAttr("root_id", StringAttr::get(&context, ""));
    if (mutation == "mixed") {
      OpBuilder builder(effect);
      OperationState state(effect.getLoc(), "nodal.memory_write");
      state.addOperands(effect->getOperands().take_front(3));
      state.addAttribute("memory", effect->getAttr("memory"));
      state.addAttribute("domain", effect->getAttr("domain"));
      state.addAttribute(
          "metadata", DictionaryAttr::get(
                          &context, {NamedAttribute(StringAttr::get(&context, "semantic_path"),
                                                    StringAttr::get(&context, "Fixture.extra"))}));
      builder.create(state);
    }
    auto owner = *forged->getOps<nodal::ModuleOp>().begin();
    std::string before = print(*forged);
    report("forged-effect-" + mutation.str(), failed(verify(*forged)) &&
                                                  failed(nodal::normalizeSequentialModel(owner)) &&
                                                  before == print(*forged));
  }
  auto lateClock = normalize("late-bound-clock-retains-write-order",
                             domain() + memory() + input() + read() + write("early") +
                                 setup().substr(domain().size()) + write("late"));
  if (lateClock) {
    nodal::MemoryWriteEffectOp effect;
    lateClock->walk([&](nodal::MemoryWriteEffectOp op) { effect = op; });
    auto owner = *lateClock->getOps<nodal::ModuleOp>().begin();
    OpBuilder builder(&context);
    builder.setInsertionPointToEnd(&owner->getRegion(0).front());
    OperationState future(owner.getLoc(), "nodal.const_literal");
    future.addTypes(nodal::BitsType::get(&context, 1));
    future.addAttribute("value", builder.getI64IntegerAttr(1));
    future.addAttribute("spelling", builder.getStringAttr("1"));
    future.addAttribute("metadata", builder.getDictionaryAttr({}));
    effect->setOperand(3, builder.create(future)->getResult(0));
    std::string before = print(*lateClock);
    report("forged-future-guard-capture-rejected",
           failed(verify(*lateClock)) && failed(nodal::normalizeSequentialModel(owner)) &&
               before == print(*lateClock));
  }
  auto futureClock = normalize("future-clock-mutation-source", basic);
  if (futureClock) {
    nodal::MemoryWriteEffectOp effect;
    nodal::LocalDomainBindingOp binding;
    futureClock->walk([&](nodal::MemoryWriteEffectOp op) { effect = op; });
    futureClock->walk([&](nodal::LocalDomainBindingOp op) { binding = op; });
    auto owner = *futureClock->getOps<nodal::ModuleOp>().begin();
    Block &body = owner->getRegion(0).front();
    effect->getOperand(4).getDefiningOp()->moveBefore(&body, body.end());
    binding->moveBefore(&body, body.end());
    std::string before = print(*futureClock);
    report("forged-future-bound-clock-rejected",
           failed(verify(*futureClock)) && failed(nodal::normalizeSequentialModel(owner)) &&
               before == print(*futureClock));
  }
  for (bool overlap : {false, true}) {
    auto mixed =
        normalize(overlap ? "cross-memory-overlap-source" : "cross-memory-disjoint-source", basic);
    if (!mixed)
      continue;
    auto owner = *mixed->getOps<nodal::ModuleOp>().begin();
    nodal::MemoryOp original;
    nodal::MemoryWriteEffectOp effect;
    mixed->walk([&](nodal::MemoryOp op) { original = op; });
    mixed->walk([&](nodal::MemoryWriteEffectOp op) { effect = op; });
    OpBuilder builder(&context);
    builder.setInsertionPointToEnd(&owner->getRegion(0).front());
    Operation *secondMemory = builder.clone(*original);
    secondMemory->setAttr("sym_name", builder.getStringAttr("ramB"));
    secondMemory->setAttr("ordering", builder.getStringAttr("independent_ports"));
    for (unsigned i = 0; i < 2; ++i) {
      OperationState constant(owner.getLoc(), "nodal.const_literal");
      constant.addTypes(nodal::UIntType::get(&context, 2));
      constant.addAttribute("value", builder.getI64IntegerAttr(overlap ? 0 : i));
      constant.addAttribute("spelling", builder.getStringAttr(std::to_string(overlap ? 0 : i)));
      constant.addAttribute("metadata", builder.getDictionaryAttr({}));
      Value address = builder.create(constant)->getResult(0);
      OperationState write(owner.getLoc(), "nodal.memory_write");
      write.addOperands({address, effect->getOperand(1), effect->getOperand(2)});
      write.addAttribute("memory", FlatSymbolRefAttr::get(&context, "ramB"));
      write.addAttribute("domain", effect->getAttr("domain"));
      write.addAttribute(
          "metadata",
          builder.getDictionaryAttr({builder.getNamedAttr(
              "semantic_path", builder.getStringAttr("Fixture.ramB.write" + std::to_string(i)))}));
      builder.create(write);
    }
    std::string before = print(*mixed);
    bool admitted = succeeded(verify(*mixed));
    bool normalized = succeeded(nodal::normalizeSequentialModel(owner));
    report(overlap ? "cross-memory-raw-canonical-overlap-rejected"
                   : "cross-memory-raw-canonical-disjoint-bounded",
           admitted == !overlap && normalized == !overlap &&
               (overlap ? print(*mixed) == before : succeeded(verify(*mixed))));
  }
  std::string overBudget = setup() + memory(u32, 1, "old_data", "independent_ports") + input();
  for (unsigned i = 0; i < 184; ++i)
    overBudget += write("over" + std::to_string(i));
  auto directBudget = parse(overBudget);
  if (directBudget) {
    auto owner = *directBudget->getOps<nodal::ModuleOp>().begin();
    std::string before = print(*directBudget);
    report("direct-normalization-pair-budget-before-expansion",
           failed(nodal::normalizeSequentialModel(owner)) &&
               diagnostics.find("pair proof budget exceeded before normalization") !=
                   std::string::npos &&
               before == print(*directBudget));
  }
  // Causal semantics use the production canonical memory query, not a fixture
  // memory simulator. Only pure SSA fixture inputs are supplied by this adapter.
  auto number = [&](uint64_t n) -> Attribute {
    return IntegerAttr::get(IntegerType::get(&context, 64, IntegerType::Unsigned), n);
  };
  std::string nestedRaw =
      setup() + memory() + input() + literal("enable", 1, bit) + branch(u32, m4, false);
  auto nested = check("single-nested-raw-write-valid", nestedRaw, true);
  if (nested) {
    Operation *memoryOp = nullptr;
    nested->walk([&](nodal::MemoryOp op) { memoryOp = op; });
    auto values = [&](Value v) -> FailureOr<Attribute> {
      Operation *op = v.getDefiningOp();
      if (!op)
        return failure();
      if (auto attr = op->getAttr("value"))
        return attr;
      if (auto ref = op->getAttrOfType<FlatSymbolRefAttr>("port")) {
        if (ref.getValue() == "address")
          return number(0);
        if (ref.getValue() == "data")
          return number(0xabcdef01);
        if (ref.getValue() == "mask")
          return number(15);
      }
      return failure();
    };
    auto query = [&]() {
      return nodal::evaluateMemoryEdge(
          memoryOp, values, [&](int64_t) { return number(0x11223344); },
          [&](Operation *) { return Attribute(); });
    };
    report("edge-query-rejects-nested-raw-write", failed(query()));
    auto owner = *nested->getOps<nodal::ModuleOp>().begin();
    bool normalized = succeeded(nodal::normalizeSequentialModel(owner));
    nested->walk([&](nodal::MemoryOp op) { memoryOp = op; });
    auto edge = query();
    report("edge-query-normalized-nested-write",
           normalized && succeeded(edge) && edge->writes.size() == 1 &&
               llvm::cast<IntegerAttr>(edge->writes[0].value).getValue().getZExtValue() ==
                   0xabcdef01);
  }
  auto causal = [&](std::string policy, bool reverse, unsigned firstMask, unsigned secondMask,
                    int64_t latency, bool unspecifiedBefore = false,
                    std::string ordering = "ordered") {
    std::string body = setup() + memory(u32, latency, policy, ordering) + input() +
                       port("second", u32) + literal("mA", firstMask, m4) +
                       literal("mB", secondMask, m4) + read();
    std::string a = write("first", "address", "data", "mA"),
                b = write("second", "address", "second", "mB");
    auto m = normalize("causal-normalize-" + policy + (reverse ? "-reverse" : ""),
                       body + (reverse ? b + a : a + b));
    if (!m)
      return;
    Operation *memoryOp = nullptr;
    m->walk([&](nodal::MemoryOp op) { memoryOp = op; });
    std::function<FailureOr<Attribute>(Value)> values = [&](Value v) -> FailureOr<Attribute> {
      Operation *op = v.getDefiningOp();
      if (!op)
        return failure();
      if (auto attr = op->getAttr("value"))
        return attr;
      if (auto ref = op->getAttrOfType<FlatSymbolRefAttr>("port")) {
        if (ref.getValue() == "address")
          return number(0);
        if (ref.getValue() == "data")
          return number(0xaabbccdd);
        if (ref.getValue() == "second")
          return number(0x55667788);
      }
      return failure();
    };
    auto edge = nodal::evaluateMemoryEdge(
        memoryOp, values,
        [&](int64_t) { return unspecifiedBefore ? Attribute() : number(0x11223344); },
        [&](Operation *) { return number(0x99887766); });
    uint64_t expected = 0x11223344;
    auto apply = [&](uint64_t data, unsigned mask) {
      for (unsigned byte = 0; byte < 4; ++byte)
        if (mask & (1u << byte))
          expected =
              (expected & ~(uint64_t(255) << (byte * 8))) | (data & (uint64_t(255) << (byte * 8)));
    };
    if (reverse) {
      apply(0x55667788, secondMask);
      apply(0xaabbccdd, firstMask);
    } else {
      apply(0xaabbccdd, firstMask);
      apply(0x55667788, secondMask);
    }
    bool collision = firstMask || secondMask;
    bool good =
        succeeded(edge) && edge->reads.size() == 1 && edge->writes.size() == (collision ? 1u : 0u);
    if (good && collision)
      good &= llvm::cast<IntegerAttr>(edge->writes[0].value).getValue().getZExtValue() == expected;
    if (good) {
      Attribute actual = edge->reads[0].firstStage;
      if (policy == "undefined" && collision)
        good &= !actual;
      else {
        uint64_t want = policy == "new_data"                 ? expected
                        : policy == "no_change" && collision ? 0x99887766
                                                             : 0x11223344;
        good &= actual && llvm::cast<IntegerAttr>(actual).getValue().getZExtValue() == want;
      }
      auto response0 =
          nodal::memoryResponseAtEdge(edge->reads[0].port, 0, [&](int64_t) { return actual; });
      auto response1 = nodal::memoryResponseAtEdge(
          edge->reads[0].port, 1, [&](int64_t e) { return e == 0 ? actual : number(0x12345678); });
      good &= succeeded(response0) && succeeded(response1) &&
              (latency == 1 ? *response0 == actual : !*response0) &&
              (latency == 1 ? *response1 == number(0x12345678) : *response1 == actual);
    }
    report("causal-values-and-exact-latency-" + policy + (reverse ? "-reverse" : ""), good);
  };
  for (std::string policy : {"old_data", "new_data", "no_change", "undefined"}) {
    causal(policy, false, 3, 3, 1);
    causal(policy, true, 3, 3, 2);
  }
  causal("new_data", false, 1, 2, 1);
  causal("new_data", false, 3, 12, 1, true, "ordered");
  causal("new_data", false, 3, 12, 1, true, "independent_ports");
  causal("no_change", false, 0, 0, 2);
  auto start = std::chrono::steady_clock::now();
  std::string scale = setup() +
                      memory(u32, 1000000000, "old_data", "independent_ports", "1000000000") +
                      input() + read();
  for (unsigned i = 0; i < 32; ++i)
    scale += literal("a" + std::to_string(i), i, "!nodal.uint<64>") +
             write("w" + std::to_string(i), "a" + std::to_string(i), "data", "mask", u32, m4,
                   "!nodal.uint<64>");
  auto scaled = normalize("compact-depth-latency-32-ports", scale);
  auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                     std::chrono::steady_clock::now() - start)
                     .count();
  unsigned operations = 0;
  if (scaled)
    scaled->walk([&](Operation *) { ++operations; });
  llvm::outs() << "MEMORY_MODEL_MEASURE ports=32 pair_queries=496 depth=1000000000 "
                  "latency=1000000000 normalized_operations="
               << operations << " elapsed_ms=" << elapsed << '\n';
  if (scaled) {
    auto owner = *scaled->getOps<nodal::ModuleOp>().begin();
    nodal::MemoryModelLimits limit;
    limit.maximumPairQueries = 100;
    auto authored = parse(scale);
    auto authoredOwner = *authored->getOps<nodal::ModuleOp>().begin();
    report("raw-pair-budget-preflight-before-normalization",
           failed(nodal::preflightMemoryNormalization(authoredOwner, limit)));

    report("pair-budget-rejects-before-proof", failed(nodal::verifyMemoryModel(owner, limit)));
    limit = {};
    limit.maximumOperations = 5;
    report("authored-budget-rejects-before-expansion",
           failed(nodal::verifyMemoryModel(owner, limit)));
  }
  llvm::outs() << "MEMORY_MODEL_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}
