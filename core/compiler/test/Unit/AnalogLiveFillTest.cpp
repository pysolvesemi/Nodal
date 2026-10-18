#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/AnalogEvents.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/Signals.h"
#include "llvm/Support/raw_ostream.h"

#include <string>

namespace {
const std::string variableType = "!nodal.variable<\"real\", \"1\">";
const std::string deferredType = "!nodal.analog_deferred<\"real\", \"1\">";
std::string fill(const std::string &name, const std::string &input, const std::string &inputType,
                 const std::string &value) {
  return "%" + name + " = \"nodal.analog_live_fill\"(%" + input + ") <{identity = \"T." + name +
         "\", value = \"" + value + "\", owner = \"T\", metadata = {}}> : (" + inputType + ") -> " +
         deferredType + "\n";
}
std::string arm(const std::string &name, const std::string &input, const std::string &value) {
  return "\"nodal.analog_if\"() <{statement_id = \"T.if" + name +
         "\", owner = \"T\", metadata = {}}> ({\n"
         "\"nodal.analog_if_arm\"(%" +
         input + ") <{arm_id = \"T." + name +
         "\", owner = \"T\", reference_form = \"live\", is_else = false, "
         "stage = \"runtime\", condition_value = \"" +
         value +
         "\", condition_kind = \"boolean\", condition_dimension = \"1\", "
         "condition_reads = [\"T.x\"], static_value_present = false, "
         "static_value = false, metadata = {}}> ({\n"
         "\"nodal.analog_scope\"() <{owner = \"T\", scope_id = \"T." +
         name + ".scope\", metadata = {}}> ({^bb0:\n}) : () -> ()\n}) : (" + deferredType +
         ") -> ()\n}) : () -> ()\n";
}
std::string event(const std::string &name, const std::string &input) {
  return "%" + name + " = \"nodal.analog_above\"(%" + input + ") <{event_id = \"T." + name +
         "\", owner = \"T\", reference_form = \"live\", contract = \"increment37\", "
         "name = \"\", arguments = [{slot = 0 : i64, value = \"T." +
         input +
         "\", kind = \"real\", dimension = \"1\", reads = [\"T.x\"]}], "
         "analyses = [], event_reads = [\"T.x\"], metadata = {}}> : (" +
         deferredType + ") -> !nodal.analog_event\n";
}
std::string eventConsumer(const std::string &input) {
  return event("a", input) + event("b", input) + R"mlir(
%either = "nodal.analog_event_or"(%a, %b) <{event_id = "T.either", owner = "T",
contract = "increment37", name = "", metadata = {}}>
: (!nodal.analog_event, !nodal.analog_event) -> !nodal.analog_event
"nodal.analog_on"(%either) <{statement_id = "T.on", owner = "T", metadata = {}}>
({
"nodal.analog_scope"() <{owner = "T", scope_id = "T.on.scope", metadata = {}}>
({^bb0:}) : () -> ()
}) : (!nodal.analog_event) -> ()
)mlir";
}
std::string wrap(const std::string &body) {
  return R"mlir(module {
"nodal.module"() <{sym_name = "T", metadata = {semantic_path = "T"}}> ({
"nodal.analog"() <{metadata = {}}> ({
"nodal.analog_procedure"() <{owner = "T", metadata = {}}> ({
%x = "nodal.analog_variable"() <{identity = "T.x", declaration_order = 0 : i64,
initialized = true, initializer_kind = "real", initializer_dimension = "1",
initializer_value = "2.0", initializer_reads = [], owner = "T", metadata = {}}>
: () -> !nodal.variable<"real", "1">
)mlir" + body +
         "}) : () -> ()\n}) : () -> ()\n}) : () -> ()\n}\n";
}
bool replace(std::string &text, const std::string &from, const std::string &to) {
  auto position = text.find(from);
  if (position == std::string::npos)
    return false;
  text.replace(position, from.size(), to);
  return true;
}
} // namespace

int main() {
  llvm::sys::PrintStackTraceOnErrorSignal("AnalogLiveFillTest");
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  mlir::ScopedDiagnosticHandler diagnostics(&context,
                                            [](mlir::Diagnostic &) { return mlir::success(); });
  mlir::ParserConfig config(&context, false);
  unsigned checks = 0, failures = 0;
  auto expect = [&](bool condition, const std::string &label) {
    ++checks;
    if (!condition) {
      ++failures;
      llvm::errs() << "FAIL: " << label << '\n';
    }
  };
  auto check = [&](const std::string &source, bool accepted, const std::string &label,
                   unsigned expectedFills = 0) {
    llvm::errs() << "CASE: " << label << '\n';
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, config);
    if (!module) {
      expect(false, label + " fixture parses independently of verification");
      return;
    }
    mlir::Operation *consumer = nullptr;
    module->walk([&](nodal::AnalogIfArmOp candidate) {
      if (!consumer)
        consumer = candidate.getOperation();
    });
    auto program = nodal::resolveAnalogLiveSourceProgram(consumer);
    expect(mlir::succeeded(program) == accepted, label);
    if (accepted && mlir::succeeded(program)) {
      expect(program->consumer == consumer && program->roots.size() == 1 &&
                 program->fills.size() == expectedFills && program->storageBindings.size() == 1 &&
                 program->storageBindings.front().path == "T.x" &&
                 program->roots.front().expression.reads == std::set<std::string>{"T.x"},
             label + " exact graph and transitive inventory");
      if (expectedFills == 2)
        expect(program->fills.back().body.operands.size() == 2 &&
                   program->fills.back().body.operands[0].deferredReference ==
                       program->fills.front().reference &&
                   program->fills.back().body.operands[1].deferredReference ==
                       program->fills.front().reference,
               label + " shared child remains one actual deferred leaf");
    }
  };
  const auto first = fill("f", "x", variableType, "analog_div(1.0,T.x)");
  const auto condition = arm("condition", "f", "real_gt(analog_add(T.f,T.f),0.0)");
  const auto simple = wrap(first + condition);
  check(simple, true, "repeated fill references share one body", 1);
  auto mutateGraph = [&](bool self, const std::string &label) {
    auto module = mlir::parseSourceString<mlir::ModuleOp>(simple, config);
    if (!module) {
      expect(false, label + " base parses");
      return;
    }
    nodal::AnalogLiveFillOp definition;
    mlir::Operation *consumer = nullptr;
    module->walk([&](nodal::AnalogLiveFillOp candidate) { definition = candidate; });
    module->walk([&](nodal::AnalogIfArmOp candidate) { consumer = candidate.getOperation(); });
    if (self)
      definition->setOperand(0, definition->getResult(0));
    else {
      auto input = definition->getOperand(0);
      definition->setOperands(llvm::SmallVector<mlir::Value>{input, input});
    }
    expect(mlir::failed(nodal::resolveAnalogLiveSourceProgram(consumer)), label);
  };
  mutateGraph(false, "duplicate actual storage operand cannot duplicate its binding label");
  mutateGraph(true, "self-referential deferred capability rejects without recursive expansion");
  const auto nested = wrap(first + fill("g", "f", deferredType, "analog_add(T.f,T.f)") +
                           arm("condition", "g", "real_gt(analog_add(T.g,T.g),0.0)"));
  check(nested, true, "nested diamond remains dependency-first DAG", 2);
  auto mutate = [&](const std::string &from, const std::string &to, const std::string &label) {
    auto source = simple;
    expect(replace(source, from, to), label + " mutation found");
    check(source, false, label);
  };
  mutate("condition_reads = [\"T.x\"]", "condition_reads = []", "missing transitive read");
  mutate("analog_div(1.0,T.x)", "1.0", "unused direct storage binding");
  mutate("analog_div(1.0,T.x)", "analog_div(1.0,T.missing)", "unbound source leaf");
  mutate("real_gt(analog_add(T.f,T.f),0.0)", "true", "unused deferred binding");
  mutate("reference_form = \"live\"", "reference_form = \"storage\"",
         "legacy storage form cannot hide deferred capability");
  mutate("identity = \"T.f\"", "identity = \"Other.f\"", "foreign deferred identity");
  mutate("analog_div(1.0,T.x)", "analog_add(T.x,1.0 V)", "deferred body unit mismatch");
  mutate("value = \"analog_div(1.0,T.x)\"",
         "generated_owner = \"forged\", generated_induction = \"lane\", "
         "value = \"analog_div(1.0,T.x)\"",
         "claimed generated attributes cannot replace actual generated ownership");
  check(wrap(first + condition + arm("second", "f", "real_gt(T.f,0.0)")), false,
        "one deferred definition cannot span two consumers");
  auto verifyProgram = [&](const std::string &source, bool accepted, const std::string &label) {
    llvm::errs() << "CASE: " << label << '\n';
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, config);
    expect(module && (mlir::succeeded(mlir::verify(*module)) == accepted), label);
  };
  verifyProgram(simple, true, "full procedure admits actual initialized live graph");
  {
    auto malformed = mlir::parseSourceString<mlir::ModuleOp>(simple, config);
    if (malformed) {
      malformed->walk(
          [](nodal::AnalogIfArmOp candidate) { candidate->getRegion(0).getBlocks().clear(); });
      expect(mlir::failed(mlir::verify(*malformed)),
             "zero-block structured arm rejects before procedure dataflow traversal");
    } else {
      expect(false, "malformed-region regression base parses");
    }
  }
  verifyProgram(wrap(first), false, "orphan deferred capability has no consumer");
  verifyProgram(wrap(first + fill("unused", "x", variableType, "analog_div(1.0,T.x)") + condition),
                false, "unreferenced equal-text fill cannot disappear by value CSE");
  auto uninitialized = simple;
  replace(uninitialized, "initialized = true", "initialized = false");
  replace(uninitialized, "initializer_kind = \"real\"", "initializer_kind = \"\"");
  replace(uninitialized, "initializer_dimension = \"1\"", "initializer_dimension = \"\"");
  replace(uninitialized, "initializer_value = \"2.0\"", "initializer_value = \"\"");
  verifyProgram(uninitialized, false, "nested deferred read remains subject to selected DA");
  const std::string initialize = R"mlir(
"nodal.analog_assign"(%x) <{statement_id = "T.initialize", owner = "T", authored_order = 0 : i64,
value_kind = "real", value_dimension = "1", analyses = ["dc", "transient"],
guard_present = false, guard_value = "", guard_kind = "", guard_dimension = "", guard_reads = [],
metadata = {value = "2.0"}}> : (!nodal.variable<"real", "1">) -> ()
)mlir";
  auto initializedAtConsumer = uninitialized;
  replace(initializedAtConsumer, condition, initialize + condition);
  verifyProgram(initializedAtConsumer, true,
                "non-evaluating fill can precede initialization before its consumer");
  auto checkEvents = [&](const std::string &body, bool accepted, unsigned fills, uint64_t wrappers,
                         const std::string &label) {
    llvm::errs() << "CASE: " << label << '\n';
    auto module = mlir::parseSourceString<mlir::ModuleOp>(wrap(body), config);
    if (!module) {
      expect(false, label + " fixture parses independently of verification");
      return;
    }
    mlir::Operation *consumer = nullptr;
    module->walk([&](nodal::AnalogOnOp candidate) { consumer = candidate.getOperation(); });
    auto program = nodal::resolveAnalogLiveSourceProgram(consumer);
    expect(mlir::succeeded(program) == accepted, label);
    if (accepted && mlir::succeeded(program))
      expect(program->consumer == consumer && program->roots.size() == 2 &&
                 program->fills.size() == fills && program->storageBindings.size() == 1 &&
                 program->eventDemandWrappers == wrappers,
             label + " complete OR shares identity and counts unexpanded recurrence");
  };
  checkEvents(first + eventConsumer("f"), true, 1, 2, "two monitors share one deferred fill");
  {
    auto module = mlir::parseSourceString<mlir::ModuleOp>(wrap(first + eventConsumer("f")), config);
    if (module) {
      nodal::AnalogLiveFillOp definition;
      mlir::Operation *consumer = nullptr;
      module->walk([&](nodal::AnalogLiveFillOp candidate) { definition = candidate; });
      module->walk([&](nodal::AnalogOnOp candidate) { consumer = candidate.getOperation(); });
      mlir::OperationState state(module->getLoc(), "nodal.analog_live_fill");
      state.addOperands(definition->getResult(0));
      state.addAttributes(definition->getAttrs());
      auto *malformed = mlir::Operation::create(state);
      malformed->setAttr("identity", mlir::StringAttr::get(&context, "T.broken"));
      malformed->setAttr("value", mlir::StringAttr::get(&context, "T.f"));
      consumer->getBlock()->getOperations().insert(consumer->getIterator(), malformed);
      expect(mlir::failed(nodal::resolveAnalogLiveSourceProgram(consumer)),
             "extra zero-result fill user rejects before result lookup");
      expect(mlir::failed(mlir::verify(*module)),
             "module resource verification rejects zero-result fill user without assertion");
    } else {
      expect(false, "zero-result user regression base parses");
    }
  }

  auto forgedEventReads = first + eventConsumer("f");
  expect(replace(forgedEventReads, "reads = [\"T.x\"]", "reads = []"),
         "event argument inventory mutation found");
  checkEvents(forgedEventReads, false, 0, 0, "event argument cannot hide transitive storage reads");
  auto forgedEventUnion = first + eventConsumer("f");
  expect(replace(forgedEventUnion, "event_reads = [\"T.x\"]", "event_reads = []"),
         "event union inventory mutation found");
  checkEvents(forgedEventUnion, false, 0, 0, "event union cannot hide deferred storage reads");
  std::string diamond = first;
  std::string previous = "f";
  for (unsigned depth = 2; depth <= 40; ++depth) {
    std::string name = "f" + std::to_string(depth);
    diamond +=
        fill(name, previous, deferredType, "analog_add(T." + previous + ",T." + previous + ")");
    previous = name;
    if (depth == 8)
      checkEvents(diamond + eventConsumer(name), true, depth, 510,
                  "eight-level diamond has bounded inline event expansion");
    if (depth == 12 || depth == 40)
      checkEvents(diamond + eventConsumer(name), false, depth, 0,
                  std::to_string(depth) + "-level event rejects before expansion");
  }
  for (unsigned depth = 41; depth <= 140; ++depth) {
    std::string name = "f" + std::to_string(depth);
    diamond += fill(name, previous, deferredType, "analog_add(T." + previous + ",1.0)");
    previous = name;
  }
  verifyProgram(wrap(diamond + arm("condition", previous, "real_gt(T." + previous + ",0.0)")),
                false, "over-depth native graph rejects before recursive consumer discovery");
  {
    auto deep = mlir::parseSourceString<mlir::ModuleOp>(
        wrap(diamond + arm("condition", previous, "real_gt(T." + previous + ",0.0)")), config);
    if (deep) {
      mlir::Operation *firstDefinition = nullptr;
      deep->walk([&](nodal::AnalogLiveFillOp candidate) {
        if (!firstDefinition)
          firstDefinition = candidate.getOperation();
      });
      std::string emitted;
      mlir::ScopedDiagnosticHandler capture(&context, [&](mlir::Diagnostic &diagnostic) {
        llvm::raw_string_ostream stream(emitted);
        diagnostic.print(stream);
        return mlir::success();
      });
      expect(mlir::failed(nodal::verifyAnalogLiveFillOperation(firstDefinition)) &&
                 emitted.find("one actual ultimate control or event consumer") != std::string::npos,
             "reverse consumer traversal depth rejects before forward expression parsing");
    } else {
      expect(false, "reverse-depth regression base parses");
    }
  }
  llvm::outs() << "AnalogLiveFillTest: " << checks << " checks, " << failures << " failures\n";
  return failures ? 1 : 0;
}
