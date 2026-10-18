#include "nodal/Support/AnalogHierarchySyntax.h"

#include <iostream>
#include <string>
#include <vector>

int main() {
  const std::vector<std::string> positive = {
      "Cell a(.p(input), .n(ground));",
      "Empty x();",
      "Cell #(.R((R * 2)), .N(-3)) a(.p(mid), .n(gnd));",
      "Cell #(.R((2.5e-12 + 1p) / (-R)), .N(3 % 2)) a(.p(mid), .n(gnd));",
      "Cell #(.R(1e-3k), .N(2E+4M)) scaled();",
      "Cell #(.R(((R >= 1) && !(R < 4))), .N((R > 0) || (R <= 2))) a();",
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
  negative.push_back("Cell #(.R(R &&)) a();");
  negative.push_back("Cell #(.R(R & 1)) a();");
  negative.push_back("Cell #(.R(R == 1)) a();");
  negative.push_back("Cell #(.R(R ? 1 : 2)) a();");
  negative.push_back("Cell #(.R(1)) a(.p(n);");
  negative.push_back("Cell a(); Cell b();");
  negative.push_back("Cell a(.p(n))");
  negative.push_back("Cell 3a();");
  negative.push_back("Cell a(.p(n.x));");
  negative.push_back("Cell #(.R(1)) a(.p(n + x));");
  negative.push_back("Cell #(.R(1)) a(.p(n[]));");
  negative.push_back("Cell #(.R(1)) a(.p(n[0][1]));");
  negative.push_back("Cell #(.R(1)) a(.p(n[(COUNT +)]));");
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
  for (const std::string text :
       {"Cell a(.p(n[0]), .n(gnd));", "Cell a(.p(n[((COUNT * 2) + 1)]), .n(gnd));"}) {
    ++checks;
    auto instance = nodal::parseHierarchyInstance(text);
    if (!instance || !nodal::parseHierarchyEndpoint(instance->ports.at("p"))) {
      std::cerr << "REJECTED: selected scalar endpoint " << text << '\n';
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
  ++checks;
  ok &= !nodal::parseHierarchyEndpoint(std::string("mid\0trailing", 12));
  altered = modules;
  altered[1].instances[0].ports["p"] = "mid[0]";
  rejectSurface(altered);
  altered[1].arrays.insert("mid");
  ++checks;
  ok &= nodal::verifyHierarchySyntax(altered);
  altered[1].instances[0].ports["p"] = "mid";
  rejectSurface(altered);
  altered[1].instances[0].ports["p"] = "mid[(UNKNOWN + 1)]";
  rejectSurface(altered);
  auto expect = [&](bool condition, const char *description) {
    ++checks;
    if (!condition) {
      std::cerr << "FAIL: " << description << '\n';
      ok = false;
    }
  };
  for (const std::string header : {"module Top(p, q);", "module Empty;", "module Empty();",
                                   "module ArbitraryName (.lhs(n), .rhs(n));"})
    expect(nodal::parseHierarchyModuleHeader(header).has_value(), "valid module header");
  for (const std::string header :
       {"module Top(p, p);", "module Top(.lhs(n), .lhs(n));", "module Top(.lhs(n), q);",
        "module Top(.lhs(n[0]));", "module Top(.lhs(n + m));", "module Top(.lhs());",
        "module Top(.lhs(n),);", "module Top(p); endmodule"})
    expect(!nodal::parseHierarchyModuleHeader(header), "malformed module header");
  auto header = nodal::parseHierarchyModuleHeader("module ArbitraryName (.lhs(n), .rhs(n));");
  expect(header && header->ports == std::set<std::string>({"lhs", "rhs"}) &&
             header->portAliases.at("lhs") == "n" && header->portAliases.at("rhs") == "n",
         "formal aliases retain external and internal identities");
  auto part = nodal::parseHierarchyEndpoint("bus[1:(COUNT - 1)]");
  expect(part && part->index == "1" && part->partSelectEnd == "(COUNT - 1)" &&
             part->parameterReferences == std::set<std::string>({"COUNT"}),
         "ascending part-select syntax and references");
  for (const std::string actual :
       {"bus[:1]", "bus[0:]", "bus[0:1:2]", "bus[0+:2]", "bus[1-:2]", "bus[0:1][0]"})
    expect(!nodal::parseHierarchyEndpoint(actual), "malformed part-select");

  nodal::HierarchyModuleSyntax vectorChild;
  vectorChild.name = "VectorChild";
  vectorChild.ports = {"p"};
  vectorChild.nodes = {"p"};
  vectorChild.arrays = {"p"};
  vectorChild.vectors["p"] = {"0", "(K - 1)"};
  vectorChild.parameters = {"K"};
  vectorChild.integerDeclarations = {{"K", "4", false}};
  nodal::HierarchyModuleSyntax vectorParent;
  vectorParent.name = "VectorParent";
  vectorParent.nodes = {"bus"};
  vectorParent.arrays = {"bus"};
  vectorParent.vectors["bus"] = {"0", "(N - 1)"};
  vectorParent.parameters = {"N", "M"};
  vectorParent.integerDeclarations = {{"N", "4", false}, {"M", "4", false}};
  vectorParent.instances = {*nodal::parseHierarchyInstance("VectorChild #(.K(N)) c(.p(bus));")};
  std::vector<nodal::HierarchyModuleSyntax> vectorModules{vectorChild, vectorParent};
  expect(nodal::verifyHierarchySyntax(vectorModules), "whole vector and exact override identity");
  auto changed = vectorModules;
  changed[1].instances[0].parameters["K"] = "M";
  rejectSurface(changed);
  changed = vectorModules;
  changed[1].instances[0].parameters.clear();
  rejectSurface(changed);
  changed = vectorModules;
  changed[1].instances[0].ports["p"] = "bus[0]";
  rejectSurface(changed);
  changed = vectorModules;
  changed[1].vectors["bus"] = {"8", "7"};
  rejectSurface(changed);
  changed = vectorModules;
  changed[1].integerDeclarations.erase(changed[1].integerDeclarations.begin());
  rejectSurface(changed);
  changed = vectorModules;
  changed[1].vectors["bus"] = {"0", "7"};
  changed[1].instances = {*nodal::parseHierarchyInstance("VectorChild c(.p(bus[2:5]));")};
  expect(nodal::verifyHierarchySyntax(changed), "concrete ascending part width and default");
  auto concretePart = changed;
  for (const std::string actual :
       {"bus[2:6]", "bus[5:2]", "bus[-1:2]", "bus[6:9]", "bus[0:1.0]", "bus[0:UNKNOWN]", "bus"}) {
    changed = concretePart;
    changed[1].instances[0].ports["p"] = actual;
    rejectSurface(changed);
  }
  changed = vectorModules;
  changed[0].integerDeclarations = {{"A", "3", false}, {"K", "(A + 1)", false}};
  changed[0].parameters.insert("A");
  changed[1].vectors["bus"] = {"0", "N"};
  changed[1].instances = {*nodal::parseHierarchyInstance("VectorChild #(.A(N)) c(.p(bus));")};
  expect(nodal::verifyHierarchySyntax(changed),
         "dependent default substitutes actual parent identity");
  auto dependent = changed;
  changed[0].integerDeclarations[1].initializer = "K";
  rejectSurface(changed);
  changed = dependent;
  changed[1].instances[0].parameters["A"] = "M";
  rejectSurface(changed);
  changed = vectorModules;
  changed[1].vectors["bus"] = {"0", "(N + 1)"};
  changed[1].instances[0].ports["p"] = "bus[1:N]";
  expect(nodal::verifyHierarchySyntax(changed), "symbolic ascending part width");
  changed = vectorModules;
  changed[0].vectors["p"] = {"0", "0"};
  changed[1].vectors["bus"] = {"0", "0"};
  expect(nodal::verifyHierarchySyntax(changed), "singleton remains a vector");
  changed[1].instances[0].ports["p"] = "bus[0]";
  rejectSurface(changed);

  const std::string generated = "generate\n"
                                "for (lane = 0; lane < 4; lane = lane + 1) begin : binding\n"
                                "localparam integer map0 = (lane / 2);\n"
                                "localparam integer map1 = (map0 * 4);\n"
                                "localparam integer map2 = (lane % 2);\n"
                                "localparam integer map3 = (map1 + map2);\n"
                                "localparam integer map4 = (map3 + 1);\n"
                                "ArbitraryName link(.lhs(root[map4]), .rhs(bus[lane]));\n"
                                "end\nendgenerate";
  auto scope = nodal::parseStructuralGenerate(generated);
  expect(scope && scope->genvar == "lane" && scope->count == "4" && scope->label == "binding" &&
             scope->localParameters.size() == 5,
         "structural generate retains immutable map stages");
  for (const auto &[from, to] : std::vector<std::pair<std::string, std::string>>{
           {"lane = 0", "lane = 1"},
           {"lane + 1", "lane + 2"},
           {"lane < 4", "other < 4"},
           {"integer map0", "real map0"},
           {"end\nendgenerate",
            "ArbitraryName extra(.lhs(root[0]), .rhs(bus[0]));\nend\nendgenerate"},
           {"end\nendgenerate", "analog begin\nend\nend\nendgenerate"}}) {
    auto malformed = generated;
    malformed.replace(malformed.find(from), from.size(), to);
    expect(!nodal::parseStructuralGenerate(malformed), "malformed structural scope syntax");
  }
  nodal::HierarchyModuleSyntax alias;
  alias.name = header->name;
  alias.ports = header->ports;
  alias.portAliases = header->portAliases;
  alias.nodes = {"n"};
  alias.electricalNodes = {"n"};
  alias.portDirections["n"] = "inout";
  nodal::HierarchyModuleSyntax adapter;
  adapter.name = "Adapter";
  adapter.nodes = {"root", "bus"};
  adapter.arrays = adapter.nodes;
  adapter.vectors = {{"root", {"0", "7"}}, {"bus", {"0", "3"}}};
  adapter.genvars = {"lane"};
  adapter.generatedScopes = {*scope};
  const std::vector<nodal::HierarchyModuleSyntax> structuralModules{alias, adapter};
  expect(nodal::verifyHierarchySyntax(structuralModules),
         "actual shared-net structure and bounded map");
  changed = structuralModules;
  changed[0].portAliases["rhs"] = "m";
  changed[0].nodes.insert("m");
  changed[0].electricalNodes.insert("m");
  changed[0].portDirections["m"] = "inout";
  rejectSurface(changed);
  changed = structuralModules;
  changed[0].portDirections["n"] = "input";
  rejectSurface(changed);
  changed = structuralModules;
  changed[0].electricalNodes.clear();
  rejectSurface(changed);
  changed = structuralModules;
  changed[0].hasBehavior = true;
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].genvars.clear();
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].localParameters[0].initializer = "map1";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].localParameters[0].initializer = "UNKNOWN";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].parameters.insert("REAL");
  changed[1].generatedScopes[0].localParameters[0].initializer = "REAL";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].localParameters.back().initializer = "(map3 + 4)";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].localParameters.back().initializer = "(lane * 2147483647)";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].localParameters.back().initializer = "(lane / 0)";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].count = "2147483648";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes.push_back(changed[1].generatedScopes.front());
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].instance.ports["rhs"] = "bus";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].instances.push_back(changed[1].generatedScopes[0].instance);
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].localParameters = {{"map4", "lane", true}};
  for (unsigned i = 0; i != 2000; ++i) {
    auto previous = changed[1].generatedScopes[0].localParameters.back().name;
    changed[1].generatedScopes[0].localParameters.push_back(
        {"stage" + std::to_string(i), "(" + previous + " + 0)", true});
  }
  changed[1].generatedScopes[0].instance.ports["lhs"] = "root[stage1999]";
  expect(nodal::verifyHierarchySyntax(changed), "deep immutable stage chain stays compact");
  changed = vectorModules;
  changed[1].integerDeclarations[0].initializer = "((1 > 0) && (2 < 3))";
  changed[1].integerDeclarations.push_back({"unused", "(1 || 0)", true});
  changed[1].parameters.insert("unused");
  changed[1].fixedParameters.insert("unused");
  expect(nodal::verifyHierarchySyntax(changed), "unused scalar defaults retain broader grammar");
  changed[1].instances[0].parameters["K"] = "unused";
  rejectSurface(changed);
  changed = concretePart;
  changed[1].vectors["bus"] = {"-4", "3"};
  changed[1].instances[0].ports["p"] = "bus[-3:0]";
  expect(nodal::verifyHierarchySyntax(changed), "existing signed nonzero vector bounds survive");
  changed = structuralModules;
  changed[1].generatedScopes[0].localParameters = {{"twice", "(lane * 2)", true},
                                                   {"map4", "(twice - lane)", true}};
  expect(nodal::verifyHierarchySyntax(changed),
         "correlated interval overlap is not a counterexample");
  changed = structuralModules;
  changed[1].generatedScopes[0].localParameters = {{"middle", "(lane % 3)", true},
                                                   {"map4", "(middle * 100)", true}};
  rejectSurface(changed);
  changed[1].generatedScopes[0].localParameters.back().initializer = "(middle * 2147483647)";
  rejectSurface(changed);
  for (const std::string operation : {"/", "%"}) {
    const std::string zeroInside = "generate\n"
                                   "for (lane = 0; lane < 3; lane = lane + 1) begin : binding\n"
                                   "localparam integer map4 = lane;\n"
                                   "localparam integer bad = (1 " +
                                   operation +
                                   " (lane - 1));\n"
                                   "ArbitraryName link(.lhs(root[map4]), .rhs(bus[lane]));\n"
                                   "end\nendgenerate";
    auto zeroScope = nodal::parseStructuralGenerate(zeroInside);
    expect(zeroScope.has_value(), "interior-zero divisor has valid structural syntax");
    if (zeroScope) {
      changed = structuralModules;
      changed[1].vectors["bus"] = {"0", "2"};
      changed[1].generatedScopes = {*zeroScope};
      rejectSurface(changed);
    }
    changed = structuralModules;
    changed[1].generatedScopes[0].count = "2";
    changed[1].vectors["bus"] = {"0", "1"};
    changed[1].generatedScopes[0].localParameters = {
        {"map4", "lane", true}, {"safe", "(1 " + operation + " ((lane * 2) - 1))", true}};
    expect(nodal::verifyHierarchySyntax(changed), "non-dense divisor interval can skip zero");
  }
  std::cout << checks << " hierarchy-syntax checks " << (ok ? "passed" : "failed") << '\n';
  return ok ? 0 : 1;
}
