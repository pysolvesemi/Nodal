#include "nodal/Support/AnalogHierarchySyntax.h"

#include <iostream>
#include <string>
#include <vector>

int main() {
  const std::vector<std::string> positive = {
      "Cell a(.p(input), .n(ground));", "Empty x();",
      "Cell #(.R((R * 2)), .N(-3)) a(.p(mid), .n(gnd));",
      "Cell #(.R((2.5e-12 + 1p) / (-R)), .N(3 % 2)) a(.p(mid), .n(gnd));",
      "Cell #(.R(+-+-1), .N((2))) array_3(.p(mid), .n(gnd));"};
  std::vector<std::string> negative;
  negative.push_back("Cell a(p, n);");
  negative.push_back("Cell a(.p());");
  negative.push_back("Cell a(.p(V(n)));");
  negative.push_back("Cell a(.p(n), .p(n));");
  negative.push_back("Cell a(.p(n),);");
  negative.push_back("Cell a[3:0](.p(n));");
  negative.push_back("Cell #() a();");
  negative.push_back("Cell #(.R()) a();");
  negative.push_back("Cell #(.R(R +)) a();");
  negative.push_back("Cell #(.R(1), .R(2)) a();");
  negative.push_back("Cell #(.R($abstime)) a();");
  negative.push_back("Cell #(.R(sin(R))) a();");
  negative.push_back("Cell #(.R(R R)) a();");
  negative.push_back("Cell #(.R(1e+)) a();");
  negative.push_back("Cell #(.R(1ee2)) a();");
  negative.push_back("Cell #(.R(1kp)) a();");
  negative.push_back("Cell #(.R(.)) a();");
  negative.push_back("Cell #(.R(())) a();");
  negative.push_back("Cell #(.R(1;2)) a();");
  negative.push_back("Cell #(.R((R))) a(); injected");
  negative.push_back("Cell #(.R(R > 0)) a();");
  negative.push_back("Cell #(.R(R ? 1 : 2)) a();");
  negative.push_back("Cell #(.R(1)) a(.p(n);");
  negative.push_back("Cell a(); Cell b();");
  negative.push_back("Cell a(.p(n))");
  negative.push_back("Cell 3a();");
  negative.push_back("Cell a(.p(n.x));");
  negative.push_back("Cell #(.R(1)) a(.p(n + x));");
  negative.push_back("Cell #(.R(1)) a(.p(n[0]));");
  unsigned checks = 0;
  bool ok = true;
  for (const auto &text : positive) {
    ++checks;
    if (!nodal::parseHierarchyInstance(text)) {
      std::cerr << "REJECTED: " << text << '\n';
      ok = false;
    }
  }
  for (const auto &text : negative) {
    ++checks;
    if (nodal::parseHierarchyInstance(text)) {
      std::cerr << "ACCEPTED: " << text << '\n';
      ok = false;
    }
  }
  auto parsed = nodal::parseHierarchyInstance(positive[2]);
  ++checks;
  if (!parsed || parsed->parameterReferences != std::set<std::string>({"R"}) ||
      parsed->parameters.at("R") != "(R * 2)" || parsed->ports.at("p") != "mid") {
    std::cerr << "FAIL: retained named structure and symbolic parameter\n";
    ok = false;
  }
  std::string deep = "Cell #(.R(";
  deep.append(100000, '(');
  deep += "R";
  deep.append(100000, ')');
  deep += ")) a();";
  ++checks;
  if (!nodal::parseHierarchyInstance(deep)) {
    std::cerr << "FAIL: stack-safe deep expression\n";
    ok = false;
  }
  nodal::HierarchyModuleSyntax leaf;
  leaf.name = "Cell";
  leaf.ports = {"p", "n"};
  leaf.nodes = leaf.ports;
  leaf.parameters = {"R", "N", "fixed"};
  leaf.fixedParameters = {"fixed"};
  nodal::HierarchyModuleSyntax parent;
  parent.name = "Top";
  parent.nodes = {"mid", "gnd"};
  parent.parameters = {"R"};
  parent.instances = {*nodal::parseHierarchyInstance(positive[2])};
  const std::vector<nodal::HierarchyModuleSyntax> modules{leaf, parent};
  ++checks;
  ok &= nodal::verifyHierarchySyntax(modules);
  auto rejectSurface = [&](std::vector<nodal::HierarchyModuleSyntax> altered) {
    ++checks;
    if (nodal::verifyHierarchySyntax(altered)) {
      std::cerr << "ACCEPTED: malformed hierarchy surface " << checks << '\n';
      ok = false;
    }
  };
  auto altered = modules;
  altered[1].instances[0].module = "Missing";
  rejectSurface(altered);
  altered = modules;
  altered[1].instances[0].ports.erase("p");
  rejectSurface(altered);
  altered = modules;
  altered[1].instances[0].ports["p"] = "foreign";
  rejectSurface(altered);
  altered = modules;
  altered[1].instances[0].ports["unknown"] = "mid";
  rejectSurface(altered);
  altered = modules;
  altered[1].instances[0].parameters["fixed"] = "1";
  rejectSurface(altered);
  altered = modules;
  altered[1].instances[0].parameters["unknown"] = "1";
  rejectSurface(altered);
  altered = modules;
  altered[1].instances[0].parameterReferences.insert("hidden");
  rejectSurface(altered);
  altered = modules;
  altered[1].instances.push_back(altered[1].instances.front());
  rejectSurface(altered);
  altered = modules;
  altered[1].instances[0].name = "R";
  rejectSurface(altered);
  altered = modules;
  altered.push_back(leaf);
  rejectSurface(altered);
  altered = modules;
  altered[0].instances.push_back(*nodal::parseHierarchyInstance("Top cycle();"));
  rejectSurface(altered);
  ++checks;
  ok &= !nodal::parseHierarchyInstance(std::string("Empty x();\0trailing", 19));
  std::cout << checks << " hierarchy-syntax checks " << (ok ? "passed" : "failed") << '\n';
  return ok ? 0 : 1;
}
