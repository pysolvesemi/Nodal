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
             scope->body.size() == 6,
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
    const bool newlySupported = from == "lane = 0" || from == "lane + 1" ||
                                to.find("ArbitraryName extra") != std::string::npos;
    expect(nodal::parseStructuralGenerate(malformed).has_value() == newlySupported,
           "structural scope syntax and extended origin/stride/multiple statements");
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
  changed[1].generatedScopes[0].body[0].parameter.initializer = "map1";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].body[0].parameter.initializer = "UNKNOWN";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].parameters.insert("REAL");
  changed[1].generatedScopes[0].body[0].parameter.initializer = "REAL";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].body[4].parameter.initializer = "(map3 + 4)";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].body[4].parameter.initializer = "(lane * 2147483647)";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].body[4].parameter.initializer = "(lane / 0)";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].count = "2147483648";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes.push_back(changed[1].generatedScopes.front());
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].generatedScopes[0].body.back().instance.ports["rhs"] = "bus";
  rejectSurface(changed);
  changed = structuralModules;
  changed[1].instances.push_back(changed[1].generatedScopes[0].body.back().instance);
  rejectSurface(changed);
  auto setStages = [](nodal::HierarchyStructuralGenerateSyntax &scope,
                      const std::vector<nodal::HierarchyIntegerParameterSyntax> &stages) {
    auto instance = scope.body.back();
    scope.body.clear();
    for (const auto &parameter : stages) {
      nodal::HierarchyStructuralStatementSyntax statement;
      statement.kind = nodal::HierarchyStructuralStatementSyntax::Kind::LocalParameter;
      statement.parameter = parameter;
      scope.body.push_back(statement);
    }
    scope.body.push_back(instance);
  };
  changed = structuralModules;
  std::vector<nodal::HierarchyIntegerParameterSyntax> stages{{"map4", "lane", true}};
  for (unsigned i = 0; i != 2000; ++i) {
    auto previous = stages.back().name;
    stages.push_back({"stage" + std::to_string(i), "(" + previous + " + 0)", true});
  }
  setStages(changed[1].generatedScopes[0], stages);
  changed[1].generatedScopes[0].body.back().instance.ports["lhs"] = "root[stage1999]";
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
  setStages(changed[1].generatedScopes[0],
            {{"twice", "(lane * 2)", true}, {"map4", "(twice - lane)", true}});
  expect(nodal::verifyHierarchySyntax(changed),
         "correlated interval overlap is not a counterexample");
  changed = structuralModules;
  setStages(changed[1].generatedScopes[0],
            {{"middle", "(lane % 3)", true}, {"map4", "(middle * 100)", true}});
  rejectSurface(changed);
  changed[1].generatedScopes[0].body[1].parameter.initializer = "(middle * 2147483647)";
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
    setStages(changed[1].generatedScopes[0],
              {{"map4", "lane", true}, {"safe", "(1 " + operation + " ((lane * 2) - 1))", true}});
    expect(nodal::verifyHierarchySyntax(changed), "non-dense divisor interval can skip zero");
  }
  // One scoped statement tree supports ordinary components, local nodes and
  // nested rectangular scopes without elaborating occurrences.
  nodal::HierarchyModuleSyntax ordinary;
  ordinary.name = "Ordinary";
  ordinary.ports = {"p", "n"};
  ordinary.nodes = ordinary.ports;
  ordinary.electricalNodes = ordinary.nodes;
  ordinary.parameters = {"R"};
  ordinary.hasBehavior = true;
  nodal::HierarchyModuleSyntax bank;
  bank.name = "Bank";
  bank.nodes = {"bus", "common"};
  bank.electricalNodes = bank.nodes;
  bank.arrays = {"bus"};
  bank.vectors["bus"] = {"0", "7"};
  bank.genvars = {"i", "j", "k"};
  bank.parameters = {"N", "M", "R"};
  bank.integerDeclarations = {{"N", "8", false}, {"M", "8", false}};
  auto topology = [&](const std::string &body, const std::string &lower = "1",
                      const std::string &upper = "8", const std::string &step = "2") {
    auto parsed = nodal::parseStructuralGenerate("generate for (i = " + lower + "; i < " + upper +
                                                 "; i = i + " + step + ") begin : cells " + body +
                                                 " end endgenerate");
    auto result = std::vector<nodal::HierarchyModuleSyntax>{ordinary, bank};
    if (parsed)
      result.back().generatedScopes.push_back(std::move(*parsed));
    else
      result.back().name = "Ordinary"; // Invalid syntax never masquerades as an empty body.
    return result;
  };
  const std::string ordinaryInstance = "Ordinary #(.R(R)) cell(.p(bus[i]), .n(common));";
  auto topologyModules = topology(ordinaryInstance);
  expect(nodal::verifyHierarchySyntax(topologyModules),
         "ordinary child retains nonzero source origin and positive stride");
  expect(nodal::verifyHierarchySyntax(topology("electrical mid; "
                                               "Ordinary first(.p(bus[i]), .n(mid)); "
                                               "localparam integer lane = i; "
                                               "Ordinary second(.p(mid), .n(common));")),
         "multiple ordered children and per-occurrence scalar electrical node");
  const std::string nested = "electrical mid; Ordinary cell(.p(bus[i]), .n(mid)); "
                             "for (j = 0; j < 2; j = j + 1) begin : inner "
                             "localparam integer selected = (i + j); "
                             "Ordinary cell(.p(bus[selected]), .n(mid)); end";
  expect(nodal::verifyHierarchySyntax(topology(nested, "0", "7", "2")),
         "nested rectangular loop resolves ancestor induction and node with local instance name");
  expect(nodal::verifyHierarchySyntax(topology("for (j = 0; j < 1; j = j + 1) begin : left " +
                                               ordinaryInstance +
                                               " end "
                                               "for (j = 0; j < 1; j = j + 1) begin : right " +
                                               ordinaryInstance + " end")),
         "sibling scopes independently reuse child instance and induction spellings");
  expect(nodal::verifyHierarchySyntax(topology("localparam integer width = 2; "
                                               "for (j = 0; j < width; j = j + 1) begin : inner " +
                                               ordinaryInstance + " end")),
         "nested bound can reference an invariant lexical integer stage");
  expect(nodal::verifyHierarchySyntax(topology(ordinaryInstance, "1", "N", "2")),
         "symbolic static upper retains module declaration identity");
  expect(nodal::verifyHierarchySyntax(topology(ordinaryInstance, "0", "(N + 0)", "1")),
         "compound static symbolic header");
  expect(nodal::verifyHierarchySyntax(topology(ordinaryInstance, "3", "4", "2")),
         "singleton positive-stride occurrence");
  expect(nodal::verifyHierarchySyntax(topology(ordinaryInstance, "9", "2", "1")),
         "empty domain has no attained induction coordinate");
  expect(nodal::verifyHierarchySyntax(topology(ordinaryInstance, "0", "0", "1")),
         "zero count is a legal empty structural scope");
  for (const std::string &body : std::vector<std::string>{
           "Ordinary cell(.p(bus[i]));",
           "Missing cell(.p(bus[i]), .n(common));",
           "Ordinary cell(.p(bus[i]), .n(foreign));",
           "Ordinary first(.p(bus[later]), .n(common)); localparam integer later = i;",
           "localparam integer first = later; localparam integer later = i; " + ordinaryInstance,
           "localparam integer first = unknown; " + ordinaryInstance,
           "localparam integer first = R; " + ordinaryInstance,
           "localparam integer first = 1.0; " + ordinaryInstance,
           "localparam integer first = (i / 0); " + ordinaryInstance,
           "localparam integer first = i; localparam integer first = 0; " + ordinaryInstance,
           "electrical mid; electrical mid; " + ordinaryInstance,
           "Ordinary cell(.p(bus[i]), .n(common)); " + ordinaryInstance,
           "Ordinary cell(.p(bus[i]), .n(mid)); electrical mid;",
           "for (j = i; j < 3; j = j + 1) begin : inner " + ordinaryInstance + " end",
           "localparam integer bound = (i + 1); "
           "for (j = 0; j < bound; j = j + 1) begin : inner " +
               ordinaryInstance + " end",
           "for (i = 0; i < 3; i = i + 1) begin : inner " + ordinaryInstance + " end",
           "for (j = 0; j < 1; j = j + 1) begin : child " + ordinaryInstance +
               " end "
               "Ordinary escaped(.p(bus[j]), .n(common));",
           "for (j = 0; j < 1; j = j + 1) begin : child electrical local; end "
           "Ordinary escaped(.p(local), .n(common));",
           "for (j = 0; j < 1; j = j + 1) begin : left localparam integer local = j; end "
           "for (k = 0; k < 1; k = k + 1) begin : right "
           "Ordinary escaped(.p(bus[local]), .n(common)); end",
           "Ordinary #(.R(i)) cell(.p(bus[i]), .n(common));",
           "localparam integer derived = (i + 1); "
           "Ordinary #(.R(derived)) cell(.p(bus[i]), .n(common));"})
    rejectSurface(topology(body));
  for (const std::string &body :
       std::vector<std::string>{"Missing cell();", "Ordinary cell(.p(bus[i]));",
                                "localparam integer invalid = (1 / 0); " + ordinaryInstance,
                                "Ordinary cell(.p(bus[i]), .n(missing));"})
    rejectSurface(topology(body, "0", "0", "1"));
  for (const std::string step : {"0", "-1", "1.0", "R", "2147483648", "(2147483647 + 1)"})
    rejectSurface(topology(ordinaryInstance, "1", "8", step));
  rejectSurface(topology(ordinaryInstance, "-2147483648", "2147483647", "1"));
  rejectSurface(topology(ordinaryInstance, "2147483646", "2147483647", "2"));
  rejectSurface(topology(ordinaryInstance, "0", "2147483648", "1"));
  rejectSurface(topology(ordinaryInstance, "2147483648", "2147483648", "1"));
  rejectSurface(topology(ordinaryInstance, "0", "9", "1"));
  auto lastCarrier =
      topology("Ordinary cell(.p(common), .n(common));", "2147483646", "2147483647", "1");
  expect(nodal::verifyHierarchySyntax(lastCarrier), "signed32 final update at maximum is legal");
  auto whole = topology("VectorChild #(.K(N)) vector(.p(bus));");
  whole.insert(whole.begin(), vectorChild);
  whole.back().vectors["bus"] = {"0", "(N - 1)"};
  expect(nodal::verifyHierarchySyntax(whole),
         "ordinary generated child accepts invariant whole array and parameter override");
  changed = whole;
  changed.back().generatedScopes[0].body[0].instance.parameters["K"] = "M";
  rejectSurface(changed);
  changed = whole;
  changed.back().generatedScopes[0].body[0].instance.parameters["K"] = "i";
  rejectSurface(changed);
  changed = whole;
  changed.back().generatedScopes[0].body[0].instance.ports["p"] = "bus[i:(i + N - 1)]";
  rejectSurface(changed);
  auto invariantPart = topology("VectorChild #(.K(2)) vector(.p(bus[2:3]));");
  invariantPart.insert(invariantPart.begin(), vectorChild);
  expect(nodal::verifyHierarchySyntax(invariantPart),
         "generated child accepts invariant ascending whole-formal vector actual");
  auto join = topology("electrical mid; ArbitraryName link(.lhs(bus[i]), .rhs(mid)); "
                       "Ordinary cell(.p(mid), .n(common));");
  join.insert(join.begin(), alias);
  expect(nodal::verifyHierarchySyntax(join),
         "ordinary instance and verified shared-net alias coexist");
  changed = join;
  changed.front().portAliases["rhs"] = "other";
  changed.front().nodes.insert("other");
  changed.front().electricalNodes.insert("other");
  changed.front().portDirections["other"] = "inout";
  rejectSurface(changed);
  changed = join;
  changed.front().name = "__nodal_alias_helper";
  changed.front().portAliases["rhs"] = "other";
  changed.back().generatedScopes[0].body[1].instance.module = "__nodal_alias_helper";
  rejectSurface(changed);
  for (const std::string operation : {"/", "%"}) {
    rejectSurface(
        topology("localparam integer invalid = (1 " + operation + " (i - 2)); " + ordinaryInstance,
                 "0", "5", "1"));
    rejectSurface(
        topology("localparam integer invalid = (1 " + operation + " (i - 4)); " + ordinaryInstance,
                 "1", "8", "3"));
    expect(nodal::verifyHierarchySyntax(topology("localparam integer safe = (1 " + operation +
                                                 " (i - 2)); " + ordinaryInstance)),
           "positive-stride non-dense divisor may skip zero");
  }
  changed = concretePart;
  changed[1].vectors["bus"] = {"2147483648", "2147483651"};
  changed[1].instances[0].ports["p"] = "bus[2147483648:2147483651]";
  expect(nodal::verifyHierarchySyntax(changed), "direct hierarchy retains signed64 coordinates");

  auto localAdapter =
      topology("localparam integer width = 2; electrical [0:(width - 1)] local_bus; genvar lane; "
               "for (lane = 0; lane < width; lane = lane + 1) begin : binding "
               "localparam integer map = (lane * 2); "
               "ArbitraryName link(.lhs(bus[map]), .rhs(local_bus[lane])); end "
               "VectorChild #(.K(width)) child(.p(local_bus));");
  localAdapter.insert(localAdapter.begin(), alias);
  localAdapter.insert(localAdapter.begin(), vectorChild);
  expect(nodal::verifyHierarchySyntax(localAdapter),
         "nested noncontiguous whole-array adapter owns local vector and genvar");
  for (const auto &[from, to] : std::vector<std::pair<std::string, std::string>>{
           {"electrical [0:1] local_bus;", "electrical [0:-1] local_bus;"},
           {"electrical [0:1] local_bus;", "electrical [0:i] local_bus;"},
           {"electrical [0:1] local_bus;", "electrical [0:2147483648] local_bus;"},
           {"electrical [0:1] local_bus;", "electrical [-2147483648:2147483647] local_bus;"},
           {"genvar lane;", "genvar lane; genvar lane;"},
           {"genvar lane;", ""},
           {".rhs(local_bus[lane])", ".rhs(local_bus[(lane + 1)])"}}) {
    std::string body = "electrical [0:1] local_bus; genvar lane; "
                       "for (lane = 0; lane < 2; lane = lane + 1) begin : binding "
                       "ArbitraryName link(.lhs(bus[lane]), .rhs(local_bus[lane])); end "
                       "VectorChild #(.K(2)) child(.p(local_bus));";
    body.replace(body.find(from), from.size(), to);
    auto malformed = topology(body);
    malformed.insert(malformed.begin(), alias);
    malformed.insert(malformed.begin(), vectorChild);
    rejectSurface(malformed);
  }
  auto commented =
      topology("// nodal.array_binding {\"claimed_valid\": false, \"map\": \"foreign[9999]\"}\n"
               "/* misleading Ordinary missing(); */ electrical local; "
               "localparam integer lane = (i /* ignored unknown / 0 */ + 0); "
               "Ordinary /* ignored ; */ cell(.p(bus[lane]), .n(common));");
  expect(nodal::verifyHierarchySyntax(commented),
         "line/block comments are lexical trivia and carry no structural authority");
  auto commentedHeader =
      topology(ordinaryInstance, "1 /* lower */", "8 // upper\n", "2 /* step */");
  expect(nodal::verifyHierarchySyntax(commentedHeader),
         "commented static loop expressions use the same integer semantics");
  rejectSurface(topology("// nodal.array_binding {\"claimed_valid\": true}\n"
                         "Ordinary cell(.p(bus[9999]), .n(common));"));
  expect(!nodal::parseStructuralGenerate("/* unterminated " + generated),
         "unterminated leading structural comment rejects");
  expect(!nodal::parseStructuralGenerate(generated + " /* unterminated"),
         "unterminated trailing structural comment rejects");
  expect(!nodal::parseHierarchyInstance("Ordinary cell(.p(common)); /* unterminated"),
         "unterminated trailing instance comment rejects");
  expect(!nodal::parseHierarchyEndpoint("common /* unterminated"),
         "unterminated trailing endpoint comment rejects");
  expect(nodal::parseStructuralGenerate(generated + " // trailing comment").has_value(),
         "line comment can terminate at end of file");
  // Analog statements are consumed and verified by a caller-owned grammar.
  // The unit callback is intentionally a test witness, not an analog parser.
  const std::string analogText = "analog begin I(local, common) <+ V(bus[i], common); end";
  auto consumeAnalog = [&](std::string_view text) -> std::optional<std::size_t> {
    return text.substr(0, analogText.size()) == analogText
               ? std::optional<std::size_t>(analogText.size())
               : std::nullopt;
  };
  const std::string mixedText =
      "generate for (i = 1; i < 8; i = i + 2) begin : mixed "
      "electrical local; localparam integer lane = i; real event_storage; " +
      ordinaryInstance + " " + analogText + " end endgenerate";
  auto mixedScope = nodal::parseStructuralGenerate(mixedText, consumeAnalog);
  expect(mixedScope && mixedScope->body.size() == 5,
         "mixed structural body delegates analog consumption and retains statement order");
  expect(!nodal::parseStructuralGenerate(mixedText), "no fallback analog grammar");
  bool sawAnalog = false, sawDeclaration = false;
  auto validateAnalog = [&](std::string_view text, const nodal::HierarchyModuleSyntax &visible,
                            const std::vector<std::string> &declarations) {
    bool context = visible.nodes.count("local") && visible.nodes.count("common") &&
                   visible.arrays.count("bus") && visible.genvars.count("i") &&
                   !visible.genvars.count("j") && visible.parameters.count("lane") &&
                   declarations == std::vector<std::string>{"real event_storage;"};
    if (text.empty())
      sawDeclaration = context;
    else
      sawAnalog = context && text == analogText;
    return context && (text.empty() || text == analogText);
  };
  if (mixedScope) {
    auto mixed = std::vector<nodal::HierarchyModuleSyntax>{ordinary, bank};
    mixed.back().generatedScopes = {*mixedScope};
    expect(nodal::verifyHierarchySyntax(mixed, validateAnalog) && sawAnalog && sawDeclaration,
           "existing analog owner receives exact lexical nodes, stages and declarations");
    rejectSurface(mixed);
    expect(!nodal::verifyHierarchySyntax(mixed,
                                         [](std::string_view, const nodal::HierarchyModuleSyntax &,
                                            const std::vector<std::string> &) { return false; }),
           "caller rejection cannot be bypassed by structural analog syntax");
  }
  auto branchedText = mixedText;
  branchedText.insert(branchedText.find(ordinaryInstance),
                      "branch (bus[(i)], local) generated_branch; ");
  auto branchedScope = nodal::parseStructuralGenerate(branchedText, consumeAnalog);
  expect(branchedScope && branchedScope->body[3].name == "generated_branch",
         "named branch declaration retains identifier after nested endpoint parentheses");
  if (branchedScope) {
    auto branched = std::vector<nodal::HierarchyModuleSyntax>{ordinary, bank};
    branched.back().generatedScopes = {*branchedScope};
    bool sawBranch = false;
    expect(nodal::verifyHierarchySyntax(
               branched,
               [&](std::string_view text, const nodal::HierarchyModuleSyntax &view,
                   const std::vector<std::string> &declarations) {
                 if (declarations.size() == 1)
                   return text.empty() && declarations[0] == "real event_storage;";
                 sawBranch = declarations.size() == 2 &&
                             declarations[1] == "branch (bus[(i)], local) generated_branch;" &&
                             view.nodes.count("local");
                 return sawBranch && (text.empty() || text == analogText);
               }) &&
               sawBranch,
           "branch grammar is delegated with all prior storage declarations");
    branched.back().generatedScopes[0].body[3].name = "local";
    expect(!nodal::verifyHierarchySyntax(branched,
                                         [](std::string_view, const nodal::HierarchyModuleSyntax &,
                                            const std::vector<std::string> &) { return true; }),
           "named branch still obeys structural declaration collisions");
  }
  for (const std::string declaration : {"real event_storage = 1;", "integer event_storage = 1;"}) {
    auto initialized = mixedText;
    initialized.replace(initialized.find("real event_storage;"),
                        std::string("real event_storage;").size(), declaration);
    auto initializedScope = nodal::parseStructuralGenerate(initialized, consumeAnalog);
    expect(initializedScope && initializedScope->body[2].text == declaration,
           "scalar initializer is retained for the existing declaration grammar");
    expect(!nodal::parseStructuralGenerate(initialized),
           "initialized mixed scope still requires caller-owned analog parsing");
    if (initializedScope) {
      auto initializedModules = std::vector<nodal::HierarchyModuleSyntax>{ordinary, bank};
      initializedModules.back().generatedScopes = {*initializedScope};
      bool sawInitializer = false;
      expect(nodal::verifyHierarchySyntax(
                 initializedModules,
                 [&](std::string_view text, const nodal::HierarchyModuleSyntax &,
                     const std::vector<std::string> &declarations) {
                   sawInitializer = declarations == std::vector<std::string>{declaration};
                   return sawInitializer && (text.empty() || text == analogText);
                 }) &&
                 sawInitializer,
             "existing owner validates scalar declaration initializer in structural scope");
      rejectSurface(initializedModules);
    }
  }
  for (const std::string declaration :
       {"real event_storage = ;", "real event_storage [0:1] = {1, 2};"}) {
    auto initialized = mixedText;
    initialized.replace(initialized.find("real event_storage;"),
                        std::string("real event_storage;").size(), declaration);
    auto initializedScope = nodal::parseStructuralGenerate(initialized, consumeAnalog);
    expect(initializedScope.has_value(),
           "scalar and shaped initializer legality reaches the existing declaration owner");
    if (initializedScope) {
      auto initializedModules = std::vector<nodal::HierarchyModuleSyntax>{ordinary, bank};
      initializedModules.back().generatedScopes = {*initializedScope};
      bool delegated = false;
      expect(!nodal::verifyHierarchySyntax(
                 initializedModules,
                 [&](std::string_view, const nodal::HierarchyModuleSyntax &,
                     const std::vector<std::string> &declarations) {
                   delegated = declarations == std::vector<std::string>{declaration};
                   return false;
                 }) &&
                 delegated,
             "malformed or unsupported initializer rejection belongs to declaration validator");
    }
  }
  std::cout << checks << " hierarchy-syntax checks " << (ok ? "passed" : "failed") << '\n';
  return ok ? 0 : 1;
}
