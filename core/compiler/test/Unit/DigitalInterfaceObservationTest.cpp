#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"

#include "llvm/Support/raw_ostream.h"

#include <string>
#include <vector>

using namespace mlir;
namespace {
const std::string bit = "!nodal.bits<1>";
const std::string resolved = "!nodal.resolved<\"push_pull\", " + bit + ">";
const std::string tag = "interface_abi_version = 1 : i64";
struct Leaf {
  std::string path, protocol;
  std::string type() const { return protocol == "resolved" ? resolved : bit; }
  std::string access(StringRef role) const {
    if (role == "monitor")
      return "observe";
    if (protocol == "resolved")
      return "connect";
    bool forward = !StringRef(path).ends_with(".ready") || protocol != "stream";
    return (role == "source") == forward ? "out" : "in";
  }
};
std::vector<Leaf> protocol(std::string prefix = "channel", std::string kind = "stream") {
  std::vector<Leaf> result = {{prefix + ".payload", kind}, {prefix + ".valid", kind}};
  if (kind == "stream")
    result.push_back({prefix + ".ready", kind});
  return result;
}
std::string entry(const Leaf &leaf, StringRef role, bool abi = false) {
  return "{path = \"" + leaf.path + "\", access = \"" + leaf.access(role) + "\", " +
         (abi ? "type = " + leaf.type() + ", role = " : "leaf_role = ") + "\"" + role.str() +
         "\", nested_roles = [], nested_interfaces = []}";
}
std::string entries(const std::vector<Leaf> &leaves, StringRef role, bool abi = false) {
  std::string result;
  for (const Leaf &leaf : leaves)
    result += (result.empty() ? "" : ", ") + entry(leaf, role, abi);
  return result;
}
std::string definition(const std::vector<Leaf> &leaves) {
  std::string result = "\"nodal.interface\"() <{sym_name = \"Bus\", metadata = {" + tag +
                       ", semantic_identity = \"pkg.Bus\"}}> ({\n";
  for (StringRef role : {"source", "sink", "monitor"})
    result += "\"nodal.interface_role\"() <{sym_name = \"" + role.str() +
              "\", kind = \"explicit\", metadata = {" + tag + ", access = [" +
              entries(leaves, role) + "]}}> : () -> ()\n";
  for (const Leaf &leaf : leaves)
    result += "\"nodal.interface_member\"() <{sym_name = \"" + leaf.path +
              "\", type = " + leaf.type() + ", protocol = \"" + leaf.protocol +
              "\", roles = [\"source\", \"sink\", \"monitor\"], metadata = {" + tag +
              "}}> : () -> ()\n";
  return result + "}) : () -> ()\n";
}
std::string instance(const std::vector<Leaf> &leaves, StringRef name = "left",
                     StringRef role = "source") {
  return "\"nodal.interface_instance\"() <{sym_name = \"" + name.str() +
         "\", definition = @Bus, role = \"" + role.str() + "\", metadata = {" + tag +
         ", domain = @b, dimensions = [], semantic_path = \"Fixture." + name.str() +
         "\"}}> : () -> ()\n"
         "\"nodal.interface_abi\"() <{logical_path = \"Fixture." +
         name.str() + "\", layout_policy = \"structural\", members = [" +
         entries(leaves, role, true) + "], metadata = {" + tag + ", instance = @" + name.str() +
         "}}> : () -> ()\n";
}
std::string member(StringRef name, StringRef path, StringRef endpoint = "left", StringRef role = "",
                   StringRef type = bit) {
  return "%" + name.str() + " = \"nodal.member_access\"() <{instance = @" + endpoint.str() +
         ", path = \"" + path.str() + "\", metadata = {" + tag +
         (role.empty() ? "" : ", role = \"" + role.str() + "\"") + "}}> : () -> " + type.str() +
         "\n";
}
std::string drive(StringRef target, StringRef source) {
  return "\"nodal.interface_drive\"(%" + target.str() + ", %" + source.str() + ") <{metadata = {" +
         tag + "}}> : (" + bit + ", " + bit + ") -> ()\n";
}
std::string connect(StringRef target, StringRef source) {
  return "\"nodal.interface_connect\"(%" + target.str() + ", %" + source.str() +
         ") <{metadata = {" + tag + "}}> : (" + bit + ", " + bit + ") -> ()\n";
}
std::string port(std::string name, std::string domain, std::string type = bit,
                 std::string direction = "input") {
  return "\"nodal.port\"() <{sym_name = \"" + name + "\", direction = \"" + direction +
         "\", domain = @" + domain + ", type = " + type + ", metadata = {}}> : () -> ()\n%" + name +
         " = \"nodal.port_value\"() <{port = @" + name + ", metadata = {}}> : () -> " + type + "\n";
}
std::string assign(StringRef target, StringRef source) {
  return "\"nodal.digital_assign\"(%" + target.str() + ", %" + source.str() +
         ") <{kind = \"continuous\", metadata = {}}> : (" + bit + ", " + bit + ") -> ()\n";
}
std::string combine(StringRef left, StringRef right) {
  return "%joined = \"nodal.digital_expr\"(%" + left.str() + ", %" + right.str() +
         ") <{operator_name = \"bool_and\", metadata = {}}> : (" + bit + ", " + bit + ") -> " +
         bit + "\n";
}
std::string select(StringRef condition) {
  return "%zero = \"nodal.const_literal\"() <{kind = \"integer\", spelling = \"0\", "
         "value = 0 : i64, metadata = {}}> : () -> " +
         bit + "\n%joined = \"nodal.sequential_select\"(%" + condition.str() +
         ", %sync, %zero) <{metadata = {}}> : (" + bit + ", " + bit + ", " + bit + ") -> " + bit +
         "\n";
}
std::string domain(std::string name) {
  const std::string clock = "!nodal.capability<\"clock\">";
  return "\"nodal.domain\"() <{sym_name = \"" + name +
         "\", edge = \"rising\", reset_policy = \"none\", metadata = {}}> : () -> ()\n" +
         port(name + "Clock", name, clock) + "\"nodal.local_domain_binding\"(%" + name +
         "Clock) <{domain = @" + name + ", reset_polarity = \"active_high\", metadata = {}}> : (" +
         clock + ") -> ()\n";
}
std::string crossing(StringRef name, StringRef source = "input") {
  return "%" + name.str() + " = \"nodal.crossing\"(%" + source.str() +
         ") <{source_domain = @a, destination_domain = @b, kind = \"sync\", "
         "contract = \"level_v1\", stages = 2 : i64, metadata = {semantic_path = \"Fixture." +
         name.str() + "\"}}> : (" + bit + ") -> " + bit + "\n";
}
std::string setup(const std::vector<Leaf> &leaves) {
  return domain("a") + domain("b") + port("input", "a") + crossing("sync") + crossing("other") +
         definition(leaves) + instance(leaves);
}
std::string net(StringRef name) {
  return "%" + name.str() + " = \"nodal.resolved_net\"() <{name = \"" + name.str() +
         "\", metadata = {" + tag +
         ", placement = \"top_level_pin\", profile = \"portable_boundary_only\"}}> : () -> " +
         resolved + "\n";
}
std::string through(StringRef left, StringRef right) {
  return "\"nodal.net_pass_through\"(%" + left.str() + ", %" + right.str() + ") <{metadata = {" +
         tag + "}}> : (" + resolved + ", " + resolved + ") -> ()\n";
}
std::string netDrive(StringRef name, StringRef target, StringRef value, StringRef enable) {
  return "%" + name.str() + " = \"nodal.net_driver\"(%" + target.str() + ") <{driver_id = \"" +
         name.str() + "\", metadata = {" + tag + "}}> : (" + resolved + ") -> !nodal.driver<" +
         bit + ">\n\"nodal.net_drive\"(%" + target.str() + ", %" + name.str() + ", %" +
         value.str() + ", %" + enable.str() + ") <{mode = \"push_pull\", metadata = {" + tag +
         "}}> : (" + resolved + ", !nodal.driver<" + bit + ">, " + bit + ", " + bit + ") -> ()\n";
}
std::string identity(Value value) {
  Operation *op = value.getDefiningOp();
  if (llvm::isa<nodal::MemberAccessOp>(op))
    return op->getAttrOfType<FlatSymbolRefAttr>("instance").getValue().str() + ":" +
           op->getAttrOfType<StringAttr>("path").getValue().str();
  return op->getAttrOfType<StringAttr>("name").getValue().str();
}
std::vector<std::string> signatures(ArrayRef<nodal::DigitalInterfaceObservationGroup> groups) {
  std::vector<std::string> result;
  for (const auto &group : groups) {
    std::string name;
    for (Value value : group.values)
      name += (name.empty() ? "" : ",") + identity(value);
    result.push_back(name);
  }
  return result;
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
  auto report = [&](StringRef name, bool passed) {
    ++cases;
    failures += !passed;
    if (!passed)
      llvm::errs() << diagnosticText;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_INTERFACE_OBSERVATION_" << (passed ? "PASS " : "FAIL ") << name << '\n';
  };
  auto inspect = [&](const std::string &body) {
    diagnosticText.clear();
    return parseSourceString<mlir::ModuleOp>(
        "module { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({\n" + body +
            "}) : () -> () }",
        ParserConfig(&context, false));
  };
  auto check = [&](StringRef name, const std::string &body,
                   const std::vector<std::string> &expected, bool causal = true) {
    auto parsed = inspect(body);
    if (!parsed) {
      report(name, false);
      return;
    }
    auto module = *parsed->getOps<nodal::ModuleOp>().begin();
    SmallVector<nodal::DigitalInterfaceValueDependencies> dependencies;
    SmallVector<nodal::DigitalInterfaceObservationGroup> groups;
    bool passed =
        succeeded(nodal::collectDigitalInterfaceDependencies(module, dependencies, &groups)) &&
        signatures(groups) == expected;
    SmallVector<nodal::SequentialSample> samples;
    for (const auto &group : groups) {
      passed &= !group.values.empty() && group.use == group.values.front().getDefiningOp();
      for (Value value : group.values) {
        nodal::SequentialSample sample{value, group.domain, group.use};
        sample.group = group.use;
        sample.observationOnly = true;
        samples.push_back(sample);
      }
    }
    if (passed) {
      bool valid = succeeded(nodal::verifySequentialSamples(module, samples));
      passed = valid == causal &&
               (causal || diagnosticText.find("NODAL-CDC-057-002") != std::string::npos);
    }
    // These fixtures have no registers or memory effects. The real production
    // entry points must still consume Interface and written-output observations
    // instead of relying on our manually supplied sample list.
    if (passed) {
      diagnosticText.clear();
      bool valid = succeeded(nodal::verifySequentialSamples(module, {}));
      passed = valid == causal &&
               (causal || diagnosticText.find("NODAL-CDC-057-002") != std::string::npos);
    }
    if (passed) {
      diagnosticText.clear();
      bool valid = succeeded(nodal::verifySequentialModel(module));
      passed = valid == causal &&
               (causal || diagnosticText.find("NODAL-CDC-057-002") != std::string::npos);
    }
    report(name, passed);
  };

  const auto stream = protocol();
  const std::string base = setup(stream);
  const std::string forward =
      member("payload", "channel.payload") + member("valid", "channel.valid");
  const std::vector<std::string> forwardGroup = {"left:channel.payload,left:channel.valid"};
  check("forward-independent-synchronizers-reconverge",
        base + forward + drive("payload", "sync") + drive("valid", "other"), forwardGroup, false);
  check("forward-one-crossing-reused",
        base + forward + drive("payload", "sync") + drive("valid", "sync"), forwardGroup);
  check("forward-disjoint-source-synchronizers",
        base + port("unrelated", "a") + crossing("third", "unrelated") + forward +
            drive("payload", "sync") + drive("valid", "third"),
        forwardGroup);
  check("ready-is-a-separate-observation",
        base + forward + member("ready", "channel.ready", "left", "sink") +
            drive("payload", "sync") + drive("valid", "sync") + drive("ready", "other"),
        {forwardGroup.front(), "left:channel.ready"});
  check("undriven-and-unused-leaves-have-no-observation", base + forward, {});
  check("only-the-actual-driven-forward-leaf-is-observed",
        base + forward + drive("payload", "sync"), {"left:channel.payload"});
  check("monitor-and-complementary-aliases-have-one-group",
        base + member("payloadView", "channel.payload", "left", "monitor") + forward +
            member("validView", "channel.valid", "left", "sink") + drive("payload", "sync") +
            drive("valid", "sync"),
        forwardGroup);
  check("monitor-alias-retains-the-real-crossing-cones",
        base + member("payloadView", "channel.payload", "left", "monitor") + forward +
            drive("payload", "sync") + drive("valid", "other"),
        forwardGroup, false);
  check("distinct-endpoints-are-not-one-observation",
        base + instance(stream, "right") + forward +
            member("rightPayload", "channel.payload", "right") +
            member("rightValid", "channel.valid", "right") + drive("payload", "sync") +
            drive("valid", "sync") + drive("rightPayload", "other") + drive("rightValid", "other"),
        {forwardGroup.front(), "right:channel.payload,right:channel.valid"});
  check("connections-retain-forward-reconvergence",
        base + instance(stream, "right", "sink") + forward +
            member("rightPayload", "channel.payload", "right") +
            member("rightValid", "channel.valid", "right") + drive("payload", "sync") +
            drive("valid", "other") + connect("rightPayload", "payload") +
            connect("rightValid", "valid"),
        {forwardGroup.front(), "right:channel.payload,right:channel.valid"}, false);
  auto twoProtocols = protocol("nested.first", "valid");
  auto second = protocol("nested.second", "valid");
  twoProtocols.insert(twoProtocols.end(), second.begin(), second.end());
  check("nested-distinct-protocol-prefixes-stay-independent",
        setup(twoProtocols) + member("firstPayload", "nested.first.payload") +
            member("firstValid", "nested.first.valid") +
            member("secondPayload", "nested.second.payload") +
            member("secondValid", "nested.second.valid") + drive("firstPayload", "sync") +
            drive("firstValid", "sync") + drive("secondPayload", "other") +
            drive("secondValid", "other"),
        {"left:nested.first.payload,left:nested.first.valid",
         "left:nested.second.payload,left:nested.second.valid"});
  const std::vector<Leaf> plain = {{"first", "plain"}, {"second", "plain"}};
  check("unrelated-plain-members-stay-independent",
        setup(plain) + member("first", "first") + member("second", "second") +
            drive("first", "sync") + drive("second", "other"),
        {"left:first", "left:second"});
  check("valid-protocol-has-one-forward-group",
        setup(protocol("channel", "valid")) + forward + drive("payload", "sync") +
            drive("valid", "other"),
        forwardGroup, false);
  auto mixed = protocol("channel", "valid");
  mixed.push_back({"channel", "plain"});
  check("plain-path-does-not-collide-with-protocol-prefix",
        setup(mixed) + forward + member("plain", "channel") + drive("payload", "sync") +
            drive("valid", "sync") + drive("plain", "other"),
        {forwardGroup.front(), "left:channel"});

  const std::string ordinary =
      domain("a") + domain("b") + port("input", "a") + crossing("sync") + crossing("other");
  const std::string output = port("out", "b", bit, "output");
  check("unused-ordinary-output-reconvergence",
        ordinary + combine("sync", "other") + output + assign("out", "joined"), {}, false);
  check("distinct-ordinary-outputs-are-not-one-observation",
        ordinary + output + port("out2", "b", bit, "output") + assign("out", "sync") +
            assign("out2", "other"),
        {});
  const std::string alias =
      "%alias = \"nodal.digital_storage\"() <{name = \"alias\", kind = \"wire\", "
      "metadata = {}}> : () -> " +
      bit + "\n";
  check("ordinary-output-one-crossing-through-wire-alias",
        ordinary + alias + assign("alias", "sync") + combine("alias", "sync") + output +
            assign("out", "joined"),
        {});
  check("ordinary-output-selector-retains-causal-reconvergence",
        ordinary + select("other") + output + assign("out", "joined"), {}, false);
  check("ordinary-output-selector-reuses-one-crossing",
        ordinary + select("sync") + output + assign("out", "joined"), {});

  const std::vector<Leaf> resolvedLeaves = {{"data", "resolved"}};
  const std::string resolvedBase =
      setup(resolvedLeaves) + net("net") + member("member", "data", "left", "", resolved) +
      member("alias", "data", "left", "monitor", resolved) + net("otherNet") +
      through("net", "member") + through("net", "otherNet");
  check("resolved-pass-through-aliases-are-one-observation",
        resolvedBase + netDrive("driver", "net", "sync", "sync"), {"net"});
  check("resolved-data-and-enable-retain-causal-reconvergence",
        resolvedBase + netDrive("driver", "net", "sync", "other"), {"net"}, false);
  check("resolved-all-conditional-drivers-retain-causality",
        resolvedBase + netDrive("driver", "net", "sync", "sync") +
            netDrive("driver2", "otherNet", "other", "other"),
        {"net"}, false);
  check("resolved-undriven-pass-through-is-not-an-observation", resolvedBase, {});

  {
    auto parsed = inspect(resolvedBase + netDrive("driver", "net", "sync", "other"));
    bool passed = static_cast<bool>(parsed);
    if (parsed) {
      auto module = *parsed->getOps<nodal::ModuleOp>().begin();
      SmallVector<nodal::DigitalInterfaceValueDependencies> dependencies;
      SmallVector<nodal::DigitalInterfaceObservationGroup> groups;
      passed =
          succeeded(nodal::collectDigitalInterfaceDependencies(module, dependencies, &groups)) &&
          groups.size() == 1 && groups[0].domain.getValue() == "b" && groups[0].values.size() == 1;
      if (passed) {
        Value canonical = groups[0].values.front();
        unsigned canonicalDrivers = 0, aliases = 0;
        for (const auto &dependency : dependencies)
          if (dependency.value == canonical) {
            canonicalDrivers = dependency.sources.size();
            passed &= dependency.sources.size() == 2 &&
                      dependency.sources[0].first != dependency.sources[1].first &&
                      dependency.sources[0].second == dependency.sources[1].second;
          } else if (dependency.sources.size() == 1 && dependency.sources[0].first == canonical) {
            ++aliases;
          }
        passed &= canonicalDrivers == 2 && aliases == 3;
      }
    }
    report("owner-retains-canonical-domain-driver-and-enable-edges", passed);
  }
  {
    auto parsed = inspect(base + forward + drive("payload", "sync") + drive("payload", "other"));
    bool passed = static_cast<bool>(parsed);
    if (parsed) {
      auto module = *parsed->getOps<nodal::ModuleOp>().begin();
      SmallVector<nodal::DigitalInterfaceValueDependencies> dependencies(1);
      SmallVector<nodal::DigitalInterfaceObservationGroup> groups(1);
      passed = failed(nodal::collectDigitalInterfaceDependencies(module, dependencies, &groups)) &&
               dependencies.size() == 1 && groups.size() == 1 &&
               diagnosticText.find("multiple authored drivers") != std::string::npos;
    }
    report("failed-owner-validation-does-not-append-partial-results", passed);
  }
  llvm::outs() << "DIGITAL_INTERFACE_OBSERVATION_RESULT " << cases - failures << '/' << cases
               << '\n';
  return failures ? 1 : 0;
}
