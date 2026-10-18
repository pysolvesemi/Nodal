#include "nodal/Dialect/Nodal/AnalogEquationModel.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/PassManager.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "nodal/Transforms/AnalogCanonicalization.h"
#include "nodal/Transforms/AnalogSemanticLint.h"

#include "llvm/Support/raw_ostream.h"

#include <algorithm>
#include <functional>
#include <string>
#include <utility>
#include <vector>

namespace {
const std::string quantity = "!nodal.quantity<\"real\", \"1\">";
const std::vector<std::string> runtimeAnalyses = {"dc", "operating-point", "transient"};

std::string quote(const std::string &value) { return "\"" + value + "\""; }
std::string array(const std::vector<std::string> &values, bool quoted = true) {
  std::string result = "[";
  for (const auto &value : values) {
    if (result.size() > 1)
      result += ", ";
    result += quoted ? quote(value) : value;
  }
  return result + "]";
}
std::string boolean(bool value) { return value ? "true" : "false"; }
std::string componentId(const std::string &name) { return "T.component." + name; }
std::string stateId(const std::string &component, const std::string &name) {
  return componentId(component) + ".state." + name;
}
std::string equationId(const std::string &component, const std::string &name) {
  return componentId(component) + ".equation." + name;
}
std::string value(const std::string &component, const std::string &name) {
  return "%" + component + "_" + name;
}
std::string sourceSpan() {
  return "source_file = \"equations.scala\", source_line = 17 : i64, source_column = 5 : i64";
}
std::string metadata(const std::string &identity) {
  return "{semantic_path = " + quote(identity) + ", " + sourceSpan() + "}";
}

struct State {
  std::string name;
  std::string initialization = "solver-selected";
  bool typed = true;
};
struct Equation {
  std::string name;
  std::string left;
  std::string right = "zero";
  bool initial = false;
  bool typed = true;
  std::vector<std::string> analyses = runtimeAnalyses;
};
struct Component {
  std::string name;
  std::vector<State> states;
  std::vector<Equation> equations;
  std::string expressions;
  bool complete = true;
  std::vector<std::string> unsupported;
  std::string completeness = "concrete";
  std::string topology = "complete";
  std::string balance = "required";
  bool replaceable = false;
};

std::string binary(const std::string &component, const std::string &result, const std::string &left,
                   const std::string &right, const std::string &operation = "add") {
  return value(component, result) + " = \"nodal.analog_" + operation + "\"(" +
         value(component, left) + ", " + value(component, right) + ") <{metadata = {}}> : (" +
         quantity + ", " + quantity + ") -> " + quantity + "\n";
}

/// Build actual source operations and the independent authored/coverage records.
/// The authored strings are deliberately opaque: incidence must follow SSA.
std::string fixture(const std::vector<Component> &components, bool complete = true,
                    const std::vector<std::string> &unsupported = {}) {
  std::vector<std::string> componentIds, stateIds, equationIds, typedStates, typedEquations;
  std::vector<std::string> states, authored, regions;
  for (const auto &component : components) {
    const auto id = componentId(component.name);
    componentIds.push_back(id);
    std::vector<std::string> localStates, localEquations, localTypedStates, localTypedEquations;
    std::string body;
    for (const auto &[name, magnitude] : std::vector<std::pair<std::string, std::string>>{
             {"zero", "0.0"}, {"one", "1.0"}, {"two", "2.0"}})
      body += value(component.name, name) + " = \"nodal.real_literal\"() <{value = " + magnitude +
              " : f64, metadata = {}}> : () -> " + quantity + "\n";
    body += value(component.name, "p") +
            " = \"nodal.parameter_ref\"() <{parameter = @P, metadata = {}}> : () -> " + quantity +
            "\n";
    for (const auto &state : component.states) {
      const auto identity = stateId(component.name, state.name);
      const bool initialized = state.initialization == "fixed" || state.initialization == "guess";
      stateIds.push_back(identity);
      localStates.push_back(identity);
      states.push_back("{state_id = " + quote(identity) +
                       ", owner = \"T\", component = " + quote(id) +
                       ", dimension = \"1\", initialization = " + quote(state.initialization) +
                       ", has_initializer = " + boolean(initialized) + "}");
      if (!state.typed)
        continue;
      typedStates.push_back(identity);
      localTypedStates.push_back(identity);
      body += value(component.name, state.name) + " = \"nodal.analog_unknown\"(" +
              (initialized ? value(component.name, "zero") : "") +
              ") <{state_id = " + quote(identity) + ", owner = \"T\", component = " + quote(id) +
              ", initialization = " + quote(state.initialization) +
              ", metadata = " + metadata(identity) + "}> : (" + (initialized ? quantity : "") +
              ") -> " + quantity + "\n";
    }
    body += component.expressions;
    for (const auto &equation : component.equations) {
      const auto identity = equationId(component.name, equation.name);
      const std::string left = "opaque authored left " + equation.name;
      const std::string right = "opaque authored right " + equation.name;
      equationIds.push_back(identity);
      localEquations.push_back(identity);
      authored.push_back(
          "{kind = " + quote(equation.initial ? "initial_equation" : "equation") +
          ", identity = " + quote(identity) + ", owner = \"T\", authored_left = " + quote(left) +
          ", authored_right = " + quote(right) +
          ", dimension = \"1\", analyses = " + array(equation.analyses) +
          ", continuity = \"unspecified\", guard = \"\", " + sourceSpan() +
          ", residual_convention = \"lhs-minus-rhs-equals-zero\", causally_oriented = false, "
          "divided = false}");
      if (!equation.typed)
        continue;
      typedEquations.push_back(identity);
      localTypedEquations.push_back(identity);
      body += "\"nodal.analog_equation\"(" + value(component.name, equation.left) + ", " +
              value(component.name, equation.right) + ") <{equation_id = " + quote(identity) +
              ", owner = \"T\", component = " + quote(id) +
              ", initial_only = " + boolean(equation.initial) +
              ", analyses = " + array(equation.analyses) +
              ", continuity = \"unspecified\", metadata = {semantic_path = " + quote(identity) +
              ", authored_left = " + quote(left) + ", authored_right = " + quote(right) +
              ", dimension = \"1\", " + sourceSpan() + "}}> : (" + quantity + ", " + quantity +
              ") -> () loc(\"equations.scala\":17:5)\n";
    }
    const std::string contract =
        "{component_id = " + quote(id) + ", owner = \"T\", name = " + quote(component.name) +
        ", completeness = " + quote(component.completeness) +
        ", topology = " + quote(component.topology) +
        ", local_balance = " + quote(component.balance) +
        ", replaceable = " + boolean(component.replaceable) +
        ", profile_complete = " + boolean(component.complete) +
        ", unsupported = " + array(component.unsupported) + ", state_ids = " + array(localStates) +
        ", equation_ids = " + array(localEquations) +
        ", typed_state_ids = " + array(localTypedStates) +
        ", typed_equation_ids = " + array(localTypedEquations) + ", metadata = " + metadata(id) +
        "}";
    regions.push_back("\"nodal.analog\"() <{metadata = " + metadata(id) +
                      ", source_equations = " + contract + "}> ({\n" + body + "}) : () -> ()\n");
  }
  const std::string manifest =
      "{version = \"1\", root = \"T\", component_ids = " + array(componentIds) +
      ", state_ids = " + array(stateIds) + ", equation_ids = " + array(equationIds) +
      ", typed_state_ids = " + array(typedStates) +
      ", typed_equation_ids = " + array(typedEquations) +
      ", uncovered_component_ids = [], uncovered_state_ids = [], uncovered_equation_ids = [], "
      "profile_complete = " +
      boolean(complete) + ", unsupported = " + array(unsupported) +
      ", states = " + array(states, false) + "}";
  std::string source = "module attributes {nodal.bridge.source_equations = " + manifest +
                       ", nodal.bridge.analog_semantics = " + array(authored, false) +
                       "} {\n\"nodal.module\"() <{sym_name = \"T\", metadata = " + metadata("T") +
                       "}> ({\n\"nodal.parameter\"() <{sym_name = \"P\", type = f64, "
                       "default_value = 2.0 : f64, variability = \"symbolic\", metadata = " +
                       metadata("T.P") + "}> : () -> ()\n";
  for (const auto &region : regions)
    source += region;
  return source + "}) : () -> ()\n}\n";
}

void replaceAll(std::string &source, const std::string &from, const std::string &to) {
  size_t position = 0;
  while ((position = source.find(from, position)) != std::string::npos) {
    source.replace(position, from.size(), to);
    position += to.size();
  }
}
std::string physicalFixture() {
  std::string source = fixture({Component{"C", {{"x"}}, {{"e0", "x", "one"}}}});
  replaceAll(source, quantity, "!nodal.quantity<\"real\", \"current^-1*voltage\">");
  replaceAll(source, "dimension = \"1\"", "dimension = \"current^-1*voltage\"");
  replaceAll(source, "dimension = \"current^-1*voltage\", analyses",
             "dimension = \"resistance\", analyses");
  replaceAll(source, "type = f64, ", "type = f64, unit = @Resistance, ");
  for (const auto &[authored, canonical] : std::vector<std::pair<std::string, std::string>>{
           {"0.0", "0.0"}, {"1.0", "1.0e-3"}, {"2.0", "2.0e-3"}})
    replaceAll(source, "\"nodal.real_literal\"() <{value = " + authored + " : f64, metadata",
               "\"nodal.real_literal\"() <{unit = @Resistance, authored_value = " + authored +
                   " : f64, value = " + canonical + " : f64, metadata");
  source.insert(source.find("\"nodal.module\""),
                "\"nodal.unit\"() <{sym_name = \"Resistance\", symbol = \"mOhm\", "
                "dimension = \"current^-1*voltage\", scale = 1.0e-3 : f64, native_suffix = \"m\", "
                "metadata = {}}> : () -> ()\n");
  return source;
}

std::string print(mlir::Operation *operation) {
  std::string result;
  llvm::raw_string_ostream output(result);
  operation->print(output);
  return result;
}
std::string print(mlir::Attribute attribute) {
  std::string result;
  llvm::raw_string_ostream output(result);
  attribute.print(output);
  return result;
}
mlir::Operation *find(mlir::ModuleOp module, llvm::StringRef attribute,
                      const std::string &identity) {
  mlir::Operation *found = nullptr;
  module.walk([&](mlir::Operation *operation) {
    auto candidate = operation->getAttrOfType<mlir::StringAttr>(attribute);
    if (candidate && candidate.getValue() == identity)
      found = operation;
  });
  return found;
}
void setField(mlir::Operation *operation, llvm::StringRef attribute, llvm::StringRef field,
              mlir::Attribute value) {
  mlir::NamedAttrList fields(operation->getAttrOfType<mlir::DictionaryAttr>(attribute));
  fields.set(field, value);
  operation->setAttr(attribute, fields.getDictionary(operation->getContext()));
}
const nodal::AnalogStructuralMatch *report(const nodal::AnalogStructuralModel &model,
                                           const std::string &component,
                                           const std::string &analysis = "transient") {
  const auto &reports = component == "T" ? model.global : model.components;
  for (const auto &candidate : reports)
    if (candidate.component == component && candidate.analysis == analysis)
      return &candidate;
  return nullptr;
}
} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  std::string diagnostic;
  unsigned warnings = 0, errors = 0;
  mlir::ScopedDiagnosticHandler diagnostics(&context, [&](mlir::Diagnostic &message) {
    warnings += message.getSeverity() == mlir::DiagnosticSeverity::Warning;
    errors += message.getSeverity() == mlir::DiagnosticSeverity::Error;
    llvm::raw_string_ostream output(diagnostic);
    message.print(output);
    output << '\n';
    return mlir::success();
  });
  unsigned checks = 0, failures = 0;
  auto expect = [&](bool condition, const std::string &label) {
    ++checks;
    if (!condition) {
      ++failures;
      llvm::errs() << "FAIL: " << label << '\n' << diagnostic;
    }
  };
  auto load = [&](const std::string &source) {
    diagnostic.clear();
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    expect(module && mlir::succeeded(mlir::verify(*module)) &&
               mlir::succeeded(nodal::verifyAnalogNumericModel(*module)) &&
               mlir::succeeded(nodal::verifyParameterModel(*module)) &&
               mlir::succeeded(nodal::verifyAnalogSemanticInventory(*module)),
           "fixture parses and passes native typed/numeric verification");
    return module;
  };
  using Check = std::function<void(const nodal::AnalogStructuralModel &, mlir::DictionaryAttr)>;
  auto check = [&](const std::string &label, const std::string &source, const Check &inspect) {
    auto module = load(source);
    if (!module)
      return;
    const auto before = print(module->getOperation());
    auto model = nodal::analyzeAnalogEquationModel(*module);
    expect(mlir::succeeded(model), label + ": model verifies");
    if (mlir::failed(model))
      return;
    auto serialized = nodal::serializeAnalogStructuralModel(&context, *model);
    auto repeated = nodal::analyzeAnalogEquationModel(*module);
    expect(mlir::succeeded(repeated) &&
               serialized == nodal::serializeAnalogStructuralModel(&context, *repeated),
           label + ": repeated report is identical");
    expect(before == print(module->getOperation()), label + ": analysis does not mutate source IR");
    inspect(*model, serialized);
  };
  auto expectCounts = [&](const nodal::AnalogStructuralModel &model, const std::string &id,
                          size_t equations, size_t unknowns, size_t matched,
                          const std::string &analysis = "transient") {
    const auto *found = report(model, id, analysis);
    expect(found && found->equations.size() == equations && found->unknowns.size() == unknowns &&
               found->matched.size() == matched &&
               found->unmatchedEquations.size() == equations - matched &&
               found->unmatchedUnknowns.size() == unknowns - matched,
           id + "/" + analysis + ": exact equation, declaration and matching counts");
  };

  // e0 initially takes x. Maximum matching must find the augmenting path that
  // moves e0 to y when e1 can use only x; a greedy count cannot pass this case.
  Component balanced{
      "C", {{"x"}, {"y"}}, {{"e0", "sum"}, {"e1", "x", "p"}}, binary("C", "sum", "x", "y")};
  const std::string balancedSource = fixture({balanced});
  check("balanced augmenting path", balancedSource,
        [&](const auto &model, mlir::DictionaryAttr serialized) {
          expect(model.present && model.complete && model.unsupported.empty(),
                 "balanced profile is present and complete");
          expect(model.components.size() == 3 && model.global.size() == 3,
                 "only the three supported runtime analyses are reported");
          for (const auto &analysis : runtimeAnalyses) {
            expectCounts(model, componentId("C"), 2, 2, 2, analysis);
            expectCounts(model, "T", 2, 2, 2, analysis);
          }
          const auto *found = report(model, componentId("C"));
          expect(found && found->matched ==
                              std::vector<std::pair<std::string, std::string>>{
                                  {equationId("C", "e0"), stateId("C", "y")},
                                  {equationId("C", "e1"), stateId("C", "x")}},
                 "matching retains actual equation and unknown identities");
          expect(found && found->origin &&
                     found->origin->getName().getStringRef() == "nodal.analog" &&
                     report(model, "T")->origin->getName().getStringRef() == "nodal.module",
                 "component and disjoint-union origins are actual source owners");
          expect(serialized.getAs<mlir::BoolAttr>("complete").getValue() &&
                     serialized.getAs<mlir::ArrayAttr>("components").size() == 3 &&
                     serialized.getAs<mlir::ArrayAttr>("global").size() == 3,
                 "serialized report retains complete component/global results");
        });

  std::string dimensionless = balancedSource;
  replaceAll(dimensionless, "dimension = \"1\", analyses",
             "dimension = \"dimensionless\", analyses");
  check("dimensionless source alias", dimensionless,
        [&](const auto &model, auto) { expectCounts(model, "T", 2, 2, 2); });
  const std::string physicalSource = physicalFixture();
  check("compound physical quantity and source alias", physicalSource,
        [&](const auto &model, auto) { expectCounts(model, "T", 1, 1, 1); });

  Component under = balanced;
  under.equations.pop_back();
  check("underconstrained", fixture({under}), [&](const auto &model, auto) {
    expectCounts(model, componentId("C"), 1, 2, 1);
    expectCounts(model, "T", 1, 2, 1);
    expect(report(model, "T")->unmatchedUnknowns == std::vector<std::string>{stateId("C", "y")},
           "underconstraint reports the unmatched source declaration");
  });
  Component over{"C", {{"x"}}, {{"e0", "x"}, {"e1", "x", "one"}}};
  check("overconstrained", fixture({over}), [&](const auto &model, auto) {
    expectCounts(model, componentId("C"), 2, 1, 1);
    expectCounts(model, "T", 2, 1, 1);
    expect(report(model, "T")->unmatchedEquations ==
               std::vector<std::string>{equationId("C", "e1")},
           "overconstraint reports the unmatched authored equation");
  });
  Component singular{"C", {{"x"}, {"y"}}, {{"e0", "x"}, {"e1", "x", "one"}}};
  check("square structurally singular", fixture({singular}), [&](const auto &model, auto) {
    expectCounts(model, componentId("C"), 2, 2, 1);
    expectCounts(model, "T", 2, 2, 1);
    expect(report(model, "T")->unmatchedUnknowns == std::vector<std::string>{stateId("C", "y")},
           "square singularity retains the unused y declaration");
  });
  Component unused{"C", {{"x"}, {"y"}}, {{"e0", "x"}}};
  check("unused declared state", fixture({unused}), [&](const auto &model, auto) {
    expectCounts(model, "T", 1, 2, 1);
    expect(report(model, "T")->unknowns ==
               std::vector<std::string>{stateId("C", "x"), stateId("C", "y")},
           "declared unknown vertices are not inferred from reads");
  });
  check("unknown with no equations", fixture({Component{"C", {{"unused"}}, {}}}),
        [&](const auto &model, auto) { expectCounts(model, "T", 0, 1, 0); });

  Component repeated{"C",
                     {{"x"}},
                     {{"e0", "again", "x"}},
                     binary("C", "twice", "x", "x") + binary("C", "again", "twice", "twice")};
  check("repeated state and DAG reads", fixture({repeated}), [&](const auto &model, auto) {
    expectCounts(model, "T", 1, 1, 1);
    expect(report(model, "T")->unknowns == std::vector<std::string>{stateId("C", "x")},
           "repeated reads and shared DAG nodes do not manufacture unknowns");
  });
  check("constant equation", fixture({Component{"C", {}, {{"e0", "p", "one"}}}}),
        [&](const auto &model, auto) {
          expectCounts(model, "T", 1, 0, 0);
          expect(report(model, "T")->unknowns.empty(),
                 "parameters and literals never become unknown vertices");
        });

  Component localUnder{"A", {{"x"}, {"y"}}, {{"e0", "x"}}};
  Component localOver{"B", {{"z"}}, {{"e0", "z"}, {"e1", "z", "one"}}};
  const std::string compensating = fixture({localUnder, localOver});
  check("compensating local defects", compensating, [&](const auto &model, auto) {
    expectCounts(model, componentId("A"), 1, 2, 1);
    expectCounts(model, componentId("B"), 2, 1, 1);
    expectCounts(model, "T", 3, 3, 2);
    expect(report(model, "T")->unmatchedUnknowns == std::vector<std::string>{stateId("A", "y")} &&
               report(model, "T")->unmatchedEquations ==
                   std::vector<std::string>{equationId("B", "e1")},
           "equal global counts do not hide disconnected local defects");
  });

  // These rows are numerically dependent, but both have incidence on x and y.
  // Structural matching must not claim numerical Jacobian rank or uniqueness.
  Component dependent{"C",
                      {{"x"}, {"y"}},
                      {{"e0", "sum"}, {"e1", "double"}},
                      binary("C", "sum", "x", "y") + binary("C", "double", "two", "sum", "mul")};
  check("numerically dependent but structurally matched", fixture({dependent}),
        [&](const auto &model, mlir::DictionaryAttr serialized) {
          expectCounts(model, "T", 2, 2, 2);
          for (llvm::StringRef claim : {"numerical_rank", "numerically_nonsingular", "consistent",
                                        "unique_solution", "dae_index", "solver_converged"})
            expect(!serialized.get(claim), "report makes no " + claim.str() + " claim");
        });

  Component initialized{"C",
                        {{"fixed", "fixed"},
                         {"guess", "guess"},
                         {"initial", "initial-equation"},
                         {"steady", "steady-state"},
                         {"operating", "operating-point"},
                         {"solver", "solver-selected"}},
                        {}};
  for (const auto &state : initialized.states)
    initialized.equations.push_back({state.name, state.name});
  initialized.equations.push_back(
      {"initial_only", "initial", "one", true, true, {"initialization"}});
  check("initialization stays distinct", fixture({initialized}), [&](const auto &model, auto) {
    for (const auto &analysis : runtimeAnalyses)
      expectCounts(model, "T", 6, 6, 6, analysis);
    expect(!report(model, "T", "initialization"), "no initialization-solving report is invented");
    const auto &equations = report(model, "T")->equations;
    expect(std::find(equations.begin(), equations.end(), equationId("C", "initial_only")) ==
               equations.end(),
           "initial equation and fixed/guess initializers are excluded from runtime equations");
  });
  Component transient{"C", {{"x"}}, {{"e0", "x", "zero", false, true, {"transient"}}}};
  check("analysis-specific incidence", fixture({transient}), [&](const auto &model, auto) {
    expectCounts(model, "T", 0, 1, 0, "dc");
    expectCounts(model, "T", 0, 1, 0, "operating-point");
    expectCounts(model, "T", 1, 1, 1, "transient");
  });

  auto first = load(compensating);
  std::reverse(localOver.equations.begin(), localOver.equations.end());
  std::reverse(localUnder.states.begin(), localUnder.states.end());
  auto reordered = load(fixture({localOver, localUnder}));
  if (first && reordered) {
    auto a = nodal::analyzeAnalogEquationModel(*first);
    auto b = nodal::analyzeAnalogEquationModel(*reordered);
    expect(mlir::succeeded(a) && mlir::succeeded(b) &&
               print(nodal::serializeAnalogStructuralModel(&context, *a)) ==
                   print(nodal::serializeAnalogStructuralModel(&context, *b)),
           "component, equation and declaration reorderings preserve the exact report");
  }

  auto incomplete = [&](const auto &model, mlir::DictionaryAttr serialized) {
    expect(model.present && !model.complete && !model.unsupported.empty(),
           "unsupported source profile is explicitly incomplete");
    expect(model.components.empty() && model.global.empty() &&
               serialized.getAs<mlir::ArrayAttr>("components").empty() &&
               serialized.getAs<mlir::ArrayAttr>("global").empty(),
           "incomplete coverage produces zero fabricated matching reports");
  };
  Component unsupportedEquation = balanced;
  unsupportedEquation.equations[0].typed = false;
  unsupportedEquation.complete = false;
  unsupportedEquation.unsupported = {"unsupported expression operator"};
  check("unsupported equation coverage", fixture({unsupportedEquation}, false), incomplete);
  Component unsupportedState{"U",   {{"x", "solver-selected", false}}, {}, "",
                             false, {"unsupported state initializer"}};
  check("unsupported declaration coverage", fixture({unsupportedState}, false), incomplete);
  Component partial = balanced;
  partial.complete = false;
  partial.completeness = "partial";
  partial.topology = "extensible";
  partial.balance = "deferred-to-concrete";
  check("partial component contract", fixture({partial}, false), incomplete);
  check("supported component cannot mask another incomplete component",
        fixture({balanced, unsupportedState}, false), incomplete);
  check("other unrepresented source behavior", fixture({balanced}, false, {"generated hierarchy"}),
        incomplete);

  auto lint = [&](const std::string &label, const std::string &source,
                  const std::string &expectedCode, unsigned expectedCount, bool expectedError) {
    auto module = load(source);
    if (!module)
      return;
    // A stale fabricated success must not suppress real structural errors.
    (*module)->setAttr("nodal.analysis.analog_structure", mlir::DictionaryAttr::get(&context));
    const auto before = print(module->getOperation());
    auto issues = nodal::collectAnalogSemanticLint(*module);
    expect(mlir::succeeded(issues) && issues->size() == expectedCount,
           label + ": exact diagnostic count with no duplicate global cause");
    if (mlir::succeeded(issues))
      for (const auto &issue : *issues) {
        expect(issue.code == expectedCode && issue.isError == expectedError,
               label + ": diagnostic code and severity");
        expect(!expectedError || (issue.operation->getName().getStringRef() == "nodal.analog" &&
                                  issue.message.find("T.component.") != std::string::npos &&
                                  issue.message.find("analysis '") != std::string::npos),
               label + ": structural error identifies its actual component and phase");
      }
    diagnostic.clear();
    warnings = errors = 0;
    mlir::PassManager manager(&context);
    manager.addPass(nodal::createAnalogSemanticLintPass());
    expect(mlir::succeeded(manager.run(*module)) == !expectedError,
           label + ": structural defects fail the lint pass");
    expect(errors == (expectedError ? expectedCount : 0) &&
               warnings == (expectedError ? 0 : expectedCount),
           label + ": mapped native diagnostic severity");
    expect(before == print(module->getOperation()), label + ": lint leaves source IR unchanged");
  };
  lint("complete balanced lint", balancedSource, "", 0, false);
  lint("underconstrained lint", fixture({under}), "NODAL-ANALOG-LINT-046-006", 3, true);
  lint("overconstrained lint", fixture({over}), "NODAL-ANALOG-LINT-046-006", 3, true);
  lint("square singular lint", fixture({singular}), "NODAL-ANALOG-LINT-046-007", 3, true);
  lint("compensating local lint", compensating, "NODAL-ANALOG-LINT-046-006", 6, true);
  lint("incomplete lint", fixture({unsupportedEquation}, false), "NODAL-ANALOG-LINT-046-005", 1,
       false);
  lint("numerically dependent structural lint", fixture({dependent}), "", 0, false);

  auto reportModule = load(fixture({singular}));
  if (reportModule) {
    const auto before = print(reportModule->getOperation());
    const auto stale = mlir::DictionaryAttr::get(&context);
    (*reportModule)->setAttr("nodal.analysis.analog_structure", stale);
    mlir::PassManager manager(&context);
    manager.addPass(nodal::createReportAnalogStructurePass());
    expect(mlir::succeeded(manager.run(*reportModule)),
           "diagnostic-only report succeeds for a valid structurally defective model");
    auto model = nodal::analyzeAnalogEquationModel(*reportModule);
    expect(mlir::succeeded(model) && (*reportModule)->getAttr("nodal.analysis.analog_structure") ==
                                         nodal::serializeAnalogStructuralModel(&context, *model),
           "report pass replaces stale attributes with recomputed incidence");
    (*reportModule)->removeAttr("nodal.analysis.analog_structure");
    expect(before == print(reportModule->getOperation()),
           "report pass changes only its explicit derived report attribute");
    (*reportModule)->setAttr("nodal.analysis.analog_structure", stale);
    find(*reportModule, "state_id", stateId("C", "x"))
        ->setAttr("owner", mlir::StringAttr::get(&context, "Other"));
    const auto malformed = print(reportModule->getOperation());
    expect(mlir::failed(manager.run(*reportModule)) &&
               (*reportModule)->getAttr("nodal.analysis.analog_structure") == stale &&
               malformed == print(reportModule->getOperation()),
           "malformed input fails without replacing its existing report or rewriting source");
  }

  using Mutation = std::function<void(mlir::ModuleOp)>;
  auto reject = [&](const std::string &label, const std::string &source, const Mutation &mutate,
                    llvm::StringRef expectedDiagnostic = "NODAL-ANALOG-STRUCTURE-134-001") {
    auto module = load(source);
    if (!module)
      return;
    mutate(*module);
    diagnostic.clear();
    expect(mlir::failed(nodal::analyzeAnalogEquationModel(*module)), label + ": rejected natively");
    expect(diagnostic.find(expectedDiagnostic.str()) != std::string::npos,
           label + ": expected native source diagnostic is emitted");
  };
  auto text = [&](const std::string &value) { return mlir::StringAttr::get(&context, value); };
  reject("wrong state owner", balancedSource, [&](mlir::ModuleOp module) {
    find(module, "state_id", stateId("C", "x"))->setAttr("owner", text("Other"));
  });
  reject("wrong equation component", balancedSource, [&](mlir::ModuleOp module) {
    find(module, "equation_id", equationId("C", "e0"))
        ->setAttr("component", text(componentId("Other")));
  });
  reject("forged component provenance", balancedSource, [&](mlir::ModuleOp module) {
    auto *region = find(module, "state_id", stateId("C", "x"))->getParentOp();
    auto contract = region->getAttrOfType<mlir::DictionaryAttr>("source_equations");
    mlir::NamedAttrList fields(contract.getAs<mlir::DictionaryAttr>("metadata"));
    fields.set("semantic_path", text(componentId("Other")));
    setField(region, "source_equations", "metadata", fields.getDictionary(&context));
  });
  reject("state identity metadata mismatch", balancedSource, [&](mlir::ModuleOp module) {
    setField(find(module, "state_id", stateId("C", "x")), "metadata", "semantic_path",
             text(stateId("C", "other")));
  });
  reject("wrong equation operand dimension",
         fixture({Component{"C", {{"x"}, {"y"}}, {{"e0", "x", "y"}}}}), [&](mlir::ModuleOp module) {
           auto *equation = find(module, "equation_id", equationId("C", "e0"));
           equation->getOperand(1).setType(nodal::QuantityType::get(&context, "real", "voltage"));
         });
  reject("state declaration dimension mismatch", balancedSource, [&](mlir::ModuleOp module) {
    auto manifest = module->getAttrOfType<mlir::DictionaryAttr>("nodal.bridge.source_equations");
    auto inventory = manifest.getAs<mlir::ArrayAttr>("states");
    std::vector<mlir::Attribute> records(inventory.begin(), inventory.end());
    mlir::NamedAttrList fields(mlir::cast<mlir::DictionaryAttr>(records.front()));
    fields.set("dimension", text("voltage"));
    records.front() = fields.getDictionary(&context);
    setField(module, "nodal.bridge.source_equations", "states",
             mlir::ArrayAttr::get(&context, records));
  });
  for (const auto &[attribute, identity] : std::vector<std::pair<std::string, std::string>>{
           {"state_id", stateId("C", "x")}, {"equation_id", equationId("C", "e0")}})
    reject("duplicate " + attribute, balancedSource, [&](mlir::ModuleOp module) {
      auto *operation = find(module, attribute, identity);
      operation->getBlock()->getOperations().insertAfter(operation->getIterator(),
                                                         operation->clone());
    });
  reject(
      "duplicate authored identity", balancedSource,
      [&](mlir::ModuleOp module) {
        auto inventory = module->getAttrOfType<mlir::ArrayAttr>("nodal.bridge.analog_semantics");
        std::vector<mlir::Attribute> records(inventory.begin(), inventory.end());
        records.push_back(records.front());
        module->setAttr("nodal.bridge.analog_semantics", mlir::ArrayAttr::get(&context, records));
      },
      "NODAL-ANALOG-045-001");
  reject(
      "forged authored causal orientation", balancedSource,
      [&](mlir::ModuleOp module) {
        auto inventory = module->getAttrOfType<mlir::ArrayAttr>("nodal.bridge.analog_semantics");
        std::vector<mlir::Attribute> records(inventory.begin(), inventory.end());
        mlir::NamedAttrList fields(mlir::cast<mlir::DictionaryAttr>(records.front()));
        fields.set("causally_oriented", mlir::BoolAttr::get(&context, true));
        records.front() = fields.getDictionary(&context);
        module->setAttr("nodal.bridge.analog_semantics", mlir::ArrayAttr::get(&context, records));
      },
      "NODAL-ANALOG-045-001");
  reject("malformed ownership cannot hide in an incomplete profile",
         fixture({unsupportedEquation}, false), [&](mlir::ModuleOp module) {
           find(module, "state_id", stateId("C", "x"))->setAttr("owner", text("Other"));
         });
  reject("uncovered component cannot hide an emitted definition", balancedSource,
         [&](mlir::ModuleOp module) {
           setField(module, "nodal.bridge.source_equations", "uncovered_component_ids",
                    mlir::ArrayAttr::get(&context, {text(componentId("C"))}));
         });
  reject("uncovered component cannot claim complete matching", balancedSource,
         [&](mlir::ModuleOp module) {
           setField(
               module, "nodal.bridge.source_equations", "component_ids",
               mlir::ArrayAttr::get(&context, {text(componentId("C")), text(componentId("D"))}));
           setField(module, "nodal.bridge.source_equations", "uncovered_component_ids",
                    mlir::ArrayAttr::get(&context, {text(componentId("D"))}));
         });
  reject("missing typed equation coverage", balancedSource, [&](mlir::ModuleOp module) {
    find(module, "equation_id", equationId("C", "e0"))->erase();
  });
  reject("missing unused declaration coverage", fixture({unused}),
         [&](mlir::ModuleOp module) { find(module, "state_id", stateId("C", "y"))->erase(); });
  reject("malformed manifest without typed operations", "module {}", [&](mlir::ModuleOp module) {
    module->setAttr("nodal.bridge.source_equations", text("forged"));
  });
  reject("missing source manifest", balancedSource,
         [&](mlir::ModuleOp module) { module->removeAttr("nodal.bridge.source_equations"); });
  reject("missing authored equation coverage", balancedSource, [&](mlir::ModuleOp module) {
    module->setAttr("nodal.bridge.analog_semantics", mlir::ArrayAttr::get(&context, {}));
  });
  reject(
      "foreign component state read", compensating,
      [&](mlir::ModuleOp module) {
        find(module, "equation_id", equationId("A", "e0"))
            ->setOperand(0, find(module, "state_id", stateId("B", "z"))->getResult(0));
      },
      "does not dominate");
  reject("unsupported initialization enum", balancedSource, [&](mlir::ModuleOp module) {
    find(module, "state_id", stateId("C", "x"))->setAttr("initialization", text("magic"));
  });
  reject("fixed initializer missing", balancedSource, [&](mlir::ModuleOp module) {
    find(module, "state_id", stateId("C", "x"))->setAttr("initialization", text("fixed"));
  });
  reject("initializer wrong dimension", fixture({initialized}), [&](mlir::ModuleOp module) {
    find(module, "state_id", stateId("C", "fixed"))
        ->getResult(0)
        .setType(nodal::QuantityType::get(&context, "real", "voltage"));
  });
  reject(
      "initialization source policy mismatch", fixture({initialized}), [&](mlir::ModuleOp module) {
        find(module, "state_id", stateId("C", "fixed"))->setAttr("initialization", text("guess"));
      });
  reject("runtime equation relabeled initial", balancedSource, [&](mlir::ModuleOp module) {
    find(module, "equation_id", equationId("C", "e0"))
        ->setAttr("initial_only", mlir::BoolAttr::get(&context, true));
  });
  reject("authored expression correspondence", balancedSource, [&](mlir::ModuleOp module) {
    setField(find(module, "equation_id", equationId("C", "e0")), "metadata", "authored_left",
             text("different authored source"));
  });
  reject("authored source span correspondence", balancedSource, [&](mlir::ModuleOp module) {
    setField(find(module, "equation_id", equationId("C", "e0")), "metadata", "source_file",
             text("different.scala"));
  });
  reject("false complete root coverage", fixture({unsupportedEquation}, false),
         [&](mlir::ModuleOp module) {
           setField(module, "nodal.bridge.source_equations", "profile_complete",
                    mlir::BoolAttr::get(&context, true));
         });

  reject(
      "malformed internal arithmetic dimension", fixture({dependent}),
      [&](mlir::ModuleOp module) {
        auto *equation = find(module, "equation_id", equationId("C", "e1"));
        auto *multiply = equation->getOperand(0).getDefiningOp();
        // Both equation sides and the multiply result remain dimensionless. The
        // internal add is forged as voltage, so only shared DAG verification finds it.
        multiply->getOperand(1).setType(nodal::QuantityType::get(&context, "real", "voltage"));
      },
      "NODAL-ANALOG-PROMOTION-001");

  auto rejectLiteral = [&](const std::string &label, const Mutation &mutate) {
    auto module = load(physicalSource);
    if (!module)
      return;
    mutate(*module);
    diagnostic.clear();
    expect(mlir::failed(mlir::verify(*module)) &&
               mlir::failed(nodal::verifyAnalogNumericModel(*module)) &&
               mlir::failed(nodal::analyzeAnalogEquationModel(*module)),
           label + ": rejected by shared numeric verification and direct model analysis");
    expect(diagnostic.find("NODAL-ANALOG-LITERAL-001") != std::string::npos,
           label + ": owned source literal diagnostic");
  };
  auto physicalLiteral = [&](mlir::ModuleOp module) {
    return find(module, "equation_id", equationId("C", "e0"))->getOperand(1).getDefiningOp();
  };
  rejectLiteral("physical literal wrong dimension", [&](mlir::ModuleOp module) {
    physicalLiteral(module)->getResult(0).setType(
        nodal::QuantityType::get(&context, "real", "voltage"));
  });
  rejectLiteral("physical literal wrong scaled magnitude", [&](mlir::ModuleOp module) {
    physicalLiteral(module)->setAttr("value",
                                     mlir::FloatAttr::get(mlir::Float64Type::get(&context), 3.0));
  });
  rejectLiteral("physical literal forged outside source profile", [&](mlir::ModuleOp module) {
    physicalLiteral(module)->getParentOp()->removeAttr("source_equations");
  });

  Component sparse{"Sparse", {}, {}};
  constexpr unsigned sparseSize = 192;
  for (unsigned i = 0; i != sparseSize; ++i) {
    const auto id = "s" + std::to_string(i);
    sparse.states.push_back({id});
    sparse.equations.push_back({"e" + std::to_string(i), id});
  }
  check("sparse source graph", fixture({sparse}), [&](const auto &model, auto) {
    expectCounts(model, "T", sparseSize, sparseSize, sparseSize);
  });
  Component deep{"Deep", {{"x"}}, {}};
  std::string previous = "x";
  for (unsigned i = 0; i != 384; ++i) {
    const auto current = "sum" + std::to_string(i);
    deep.expressions += binary("Deep", current, previous, previous);
    previous = current;
  }
  deep.equations.push_back({"e0", previous});
  check("deep shared expression DAG", fixture({deep}),
        [&](const auto &model, auto) { expectCounts(model, "T", 1, 1, 1); });
  const std::string longName = "component_" + std::string(2048, 'x');
  check("long source identities", fixture({Component{longName, {{"x"}}, {{"e0", "x"}}}}),
        [&](const auto &model, auto) {
          expectCounts(model, "T", 1, 1, 1);
          expect(report(model, "T")->matched.front().first == equationId(longName, "e0"),
                 "long source paths retain exact identity without truncation");
        });

  llvm::outs() << checks << " analog equation-model checks, " << failures << " failures\n";
  return failures ? 1 : 0;
}
