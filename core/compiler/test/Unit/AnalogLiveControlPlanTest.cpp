#include "nodal/Backend/AnalogLiveControlPlan.h"

#include "mlir/IR/Block.h"
#include "mlir/IR/BuiltinTypes.h"
#include "mlir/IR/MLIRContext.h"

#include "llvm/Support/raw_ostream.h"

#include <cmath>
#include <cstdlib>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <utility>
#include <vector>

namespace {
using Expression = nodal::AnalogSourceExpression;

Expression literal(double value, bool boolean = false) {
  Expression result;
  result.kind = boolean ? "boolean" : "real";
  result.dimension = "1";
  result.operation = "literal";
  result.spelling = boolean ? (value ? "true" : "false") : std::to_string(value);
  return result;
}

Expression source(llvm::StringRef name, bool boolean = false) {
  Expression result;
  result.kind = boolean ? "boolean" : "real";
  result.dimension = "1";
  result.operation = "reference";
  result.spelling = name.str();
  return result;
}

Expression deferred(mlir::Value reference) {
  Expression result;
  result.kind = "real";
  result.dimension = "1";
  result.operation = "deferred_reference";
  result.deferredReference = reference;
  return result;
}

Expression operation(llvm::StringRef name, std::vector<Expression> children) {
  Expression result;
  result.kind = name.starts_with("bool_") || name.starts_with("real_") ? "boolean" : "real";
  result.dimension = "1";
  result.operation = name.str();
  result.operands = std::move(children);
  return result;
}

// These block arguments supply distinct opaque identities only to the planner
// unit fixture. They are deliberately not submitted to the production verifier:
// actual deferred producers and ownership are tested by AnalogLiveFillTest.
struct Fixture {
  mlir::MLIRContext context;
  mlir::Block identities;
  nodal::AnalogLiveSourceProgram program;

  mlir::Value fill(Expression body) {
    auto identity =
        identities.addArgument(mlir::Float64Type::get(&context), mlir::UnknownLoc::get(&context));
    program.fills.push_back({identity, std::move(body)});
    return identity;
  }

  void root(Expression expression) {
    program.roots.clear();
    nodal::AnalogLiveSourceRoot root;
    root.expression = std::move(expression);
    program.roots.push_back(std::move(root));
  }
};

// The renderer returns opaque expression tokens. Their test-only AST lets the
// interpreter execute plan assignments without introducing a target parser or
// relying on C++ evaluation order. No token can conceal a deferred leaf.
struct Runtime {
  struct Rendered {
    std::string operation;
    std::vector<std::string> arguments;
  };
  std::map<std::string, Rendered> expressions;
  std::map<std::string, double> values;
  std::set<std::string> privateNames;
  std::set<std::string> cacheNames;
  std::map<std::string, unsigned> cacheWrites;
  std::map<std::string, unsigned> sourceReads;
  unsigned nextName = 0;
  bool invalidArithmetic = false;
  bool deferredRendered = false;
  bool repeatedBlock = false;
  unsigned steps = 0;

  std::string allocate(llvm::StringRef kind, llvm::StringRef label) {
    if (kind != "real" && kind != "integer")
      return {};
    std::string name = "p_" + label.str() + "_" + std::to_string(nextName++);
    privateNames.insert(name);
    if (label == "live_cache")
      cacheNames.insert(name);
    return name;
  }

  mlir::FailureOr<std::string> render(const Expression &expression,
                                      std::optional<llvm::ArrayRef<std::string>> substituted) {
    if (expression.operation == "deferred_reference") {
      deferredRendered = true;
      return mlir::failure();
    }
    if (expression.operation == "literal")
      return expression.kind == "boolean" ? std::string(expression.spelling == "true" ? "1" : "0")
                                          : expression.spelling;
    if (expression.operation == "reference")
      return expression.spelling;
    std::vector<std::string> arguments;
    if (substituted) {
      arguments.assign(substituted->begin(), substituted->end());
    } else {
      for (const auto &child : expression.operands) {
        auto value = render(child, std::nullopt);
        if (mlir::failed(value))
          return mlir::failure();
        arguments.push_back(std::move(*value));
      }
    }
    std::string token = "e_" + std::to_string(expressions.size());
    expressions.emplace(token, Rendered{expression.operation, std::move(arguments)});
    return token;
  }

  mlir::FailureOr<nodal::AnalogLiveControlPlan>
  build(const nodal::AnalogLiveSourceProgram &program) {
    return nodal::buildAnalogLiveControlPlan(
        program, [&](llvm::StringRef kind, llvm::StringRef label) { return allocate(kind, label); },
        [&](const Expression &expression,
            std::optional<llvm::ArrayRef<std::string>> substitutions) {
          return render(expression, substitutions);
        });
  }

  std::optional<double> evaluate(const std::string &expression) {
    auto variable = values.find(expression);
    if (variable != values.end()) {
      if (!privateNames.count(expression))
        ++sourceReads[expression];
      return variable->second;
    }
    char *end = nullptr;
    double number = std::strtod(expression.c_str(), &end);
    if (end != expression.c_str() && *end == '\0')
      return number;
    auto found = expressions.find(expression);
    if (found == expressions.end() || found->second.arguments.empty())
      return std::nullopt;
    const auto &node = found->second;
    auto left = evaluate(node.arguments[0]);
    if (!left)
      return std::nullopt;
    if (node.operation == "bool_and" && !*left)
      return 0;
    if (node.operation == "bool_or" && *left)
      return 1;
    if (node.operation == "analog_select")
      return evaluate(node.arguments[*left ? 1 : 2]);
    if (node.operation == "bool_not")
      return !*left;
    if (node.operation == "analog_neg")
      return -*left;
    if (node.arguments.size() != 2)
      return std::nullopt;
    auto right = evaluate(node.arguments[1]);
    if (!right)
      return std::nullopt;
    if (node.operation == "bool_and" || node.operation == "bool_or")
      return *right != 0;
    if (node.operation == "analog_add")
      return *left + *right;
    if (node.operation == "analog_mul")
      return *left * *right;
    if (node.operation == "real_gt")
      return *left > *right;
    if (node.operation == "analog_div") {
      if (*right == 0) {
        invalidArithmetic = true;
        return std::nullopt;
      }
      return *left / *right;
    }
    return std::nullopt;
  }

  std::optional<double> run(const nodal::AnalogLiveControlPlan &plan,
                            std::map<std::string, double> sources) {
    // Keep old cache/result slots across visits intentionally: only ready flags
    // reset, just as on repeated same-time execution of an analog procedure.
    for (const auto &source : sources)
      values[source.first] = source.second;
    for (const auto &ready : plan.readyResets)
      values[ready] = 0;
    cacheWrites.clear();
    sourceReads.clear();
    invalidArithmetic = false;
    repeatedBlock = false;
    steps = 0;
    std::set<int32_t> visited;
    int32_t pc = plan.entry;
    while (pc != 0 && steps < static_cast<unsigned>(plan.bound)) {
      if (pc < 1 || pc > plan.bound)
        return std::nullopt;
      if (!visited.insert(pc).second) {
        repeatedBlock = true;
        return std::nullopt;
      }
      ++steps;
      const auto &block = plan.blocks[pc - 1];
      for (const auto &assignment : block.assignments) {
        if (!privateNames.count(assignment.target))
          return std::nullopt;
        auto value = evaluate(assignment.value);
        if (!value)
          return std::nullopt;
        values[assignment.target] = *value;
        if (cacheNames.count(assignment.target))
          ++cacheWrites[assignment.target];
      }
      switch (block.kind) {
      case nodal::AnalogLiveControlBlock::Kind::Jump:
        pc = block.target;
        break;
      case nodal::AnalogLiveControlBlock::Kind::Branch: {
        auto condition = evaluate(block.condition);
        if (!condition)
          return std::nullopt;
        pc = *condition ? block.target : block.falseTarget;
        break;
      }
      case nodal::AnalogLiveControlBlock::Kind::Return: {
        auto continuation = evaluate(block.continuation);
        if (!continuation || *continuation < 1 || *continuation > plan.bound ||
            std::floor(*continuation) != *continuation)
          return std::nullopt;
        pc = static_cast<int32_t>(*continuation);
        break;
      }
      }
    }
    return pc == 0 ? evaluate(plan.result) : std::nullopt;
  }

  bool eachFillOnce(size_t count) const {
    return cacheWrites.size() == count &&
           llvm::all_of(cacheWrites, [](const auto &entry) { return entry.second == 1; });
  }
};
} // namespace

int main() {
  unsigned checks = 0;
  unsigned failures = 0;
  auto expect = [&](bool condition, const std::string &label) {
    ++checks;
    if (!condition) {
      ++failures;
      llvm::errs() << "FAIL: " << label << '\n';
    }
  };
  auto equals = [](std::optional<double> actual, double expected) {
    return actual && *actual == expected;
  };

  for (llvm::StringRef lazy : {"bool_and", "bool_or", "analog_select"}) {
    Fixture fixture;
    auto fill = fixture.fill(operation("analog_div", {literal(1), source("x")}));
    auto positive = operation("real_gt", {deferred(fill), literal(0)});
    if (lazy == "analog_select")
      fixture.root(operation(lazy, {source("gate", true), deferred(fill), literal(7)}));
    else
      fixture.root(operation(lazy, {source("gate", true), positive}));
    Runtime runtime;
    auto plan = runtime.build(fixture.program);
    expect(mlir::succeeded(plan), lazy.str() + " builds");
    if (mlir::failed(plan))
      continue;
    double skipGate = lazy == "bool_or" ? 1 : 0;
    double skippedValue = lazy == "analog_select" ? 7 : skipGate;
    expect(equals(runtime.run(*plan, {{"x", 0}, {"gate", skipGate}}), skippedValue) &&
               !runtime.invalidArithmetic && runtime.cacheWrites.empty() &&
               runtime.sourceReads["x"] == 0,
           lazy.str() + " skips dynamic zero divisor and its storage read");
    double reachedValue = lazy == "analog_select" ? 0.5 : 1;
    expect(equals(runtime.run(*plan, {{"x", 2}, {"gate", 1 - skipGate}}), reachedValue) &&
               runtime.eachFillOnce(1) && runtime.sourceReads["x"] == 1,
           lazy.str() + " evaluates reached fill exactly once");
    expect(!runtime.deferredRendered && !runtime.repeatedBlock &&
               plan->bound == static_cast<int32_t>(plan->blocks.size()),
           lazy.str() + " retains derived block bound and rendering boundary");
  }

  {
    Fixture fixture;
    auto fill = fixture.fill(operation("analog_div", {literal(1), source("x")}));
    auto positive = operation("real_gt", {deferred(fill), literal(0)});
    fixture.root(
        operation("bool_or", {operation("bool_and", {literal(0, true), positive}), positive}));
    Runtime runtime;
    auto plan = runtime.build(fixture.program);
    expect(mlir::succeeded(plan), "skipped first demand builds");
    if (mlir::succeeded(plan))
      expect(equals(runtime.run(*plan, {{"x", 2}}), 1) && runtime.eachFillOnce(1) &&
                 runtime.sourceReads["x"] == 1,
             "later reached demand computes fill skipped at first authored occurrence");
  }

  {
    Fixture fixture;
    auto fill = fixture.fill(operation("analog_div", {literal(1), source("x")}));
    fixture.root(operation("analog_add", {deferred(fill), deferred(fill)}));
    Runtime runtime;
    auto plan = runtime.build(fixture.program);
    expect(mlir::succeeded(plan), "shared repeated demand builds");
    if (mlir::succeeded(plan)) {
      expect(equals(runtime.run(*plan, {{"x", 2}}), 1) && runtime.eachFillOnce(1) &&
                 runtime.sourceReads["x"] == 1,
             "two demands share one body evaluation");
      expect(equals(runtime.run(*plan, {{"x", 4}}), 0.5) && runtime.eachFillOnce(1) &&
                 runtime.sourceReads["x"] == 1,
             "same-time reentry resets readiness and sees changed storage");
    }
  }

  {
    Fixture fixture;
    auto first = fixture.fill(source("x"));
    auto second = fixture.fill(source("x"));
    fixture.root(operation("analog_add", {deferred(first), deferred(second)}));
    Runtime runtime;
    auto plan = runtime.build(fixture.program);
    expect(mlir::succeeded(plan), "equal-text distinct fills build");
    if (mlir::succeeded(plan))
      expect(equals(runtime.run(*plan, {{"x", 3}}), 6) && runtime.eachFillOnce(2) &&
                 runtime.sourceReads["x"] == 2,
             "equal text does not merge actual fill identities");
  }

  {
    Fixture fixture;
    auto good = fixture.fill(source("x"));
    auto invalid = fixture.fill(operation("analog_div", {literal(1), source("zero")}));
    auto parent =
        fixture.fill(operation("analog_select", {operation("real_gt", {deferred(good), literal(0)}),
                                                 deferred(good), deferred(invalid)}));
    fixture.root(operation("analog_add", {deferred(parent), deferred(parent)}));
    Runtime runtime;
    auto plan = runtime.build(fixture.program);
    expect(mlir::succeeded(plan), "nested lazy fill body builds");
    if (mlir::succeeded(plan))
      expect(equals(runtime.run(*plan, {{"x", 3}, {"zero", 0}}), 6) && runtime.eachFillOnce(2) &&
                 runtime.sourceReads["x"] == 1 && runtime.sourceReads["zero"] == 0 &&
                 !runtime.invalidArithmetic,
             "demand-bearing selector shares good fill and skips invalid nested fill");
  }

  for (llvm::StringRef lazy : {"bool_and", "bool_or"}) {
    Fixture fixture;
    auto gate = fixture.fill(source("gate"));
    auto invalid = fixture.fill(operation("analog_div", {literal(1), source("zero")}));
    fixture.root(operation(lazy, {operation("real_gt", {deferred(gate), literal(0)}),
                                  operation("real_gt", {deferred(invalid), literal(0)})}));
    Runtime runtime;
    auto plan = runtime.build(fixture.program);
    expect(mlir::succeeded(plan), lazy.str() + " demand-bearing condition builds");
    if (mlir::succeeded(plan)) {
      double skipGate = lazy == "bool_or" ? 1 : 0;
      expect(equals(runtime.run(*plan, {{"gate", skipGate}, {"zero", 0}}), skipGate) &&
                 runtime.eachFillOnce(1) && runtime.sourceReads["zero"] == 0 &&
                 !runtime.invalidArithmetic,
             lazy.str() + " evaluates condition fill before skipping invalid right fill");
    }
  }

  {
    Fixture first;
    Fixture second;
    auto firstFill = first.fill(source("x"));
    auto secondFill = second.fill(source("x"));
    first.root(operation("analog_add", {deferred(firstFill), deferred(firstFill)}));
    second.root(operation("analog_add", {deferred(secondFill), deferred(secondFill)}));
    Runtime runtime;
    auto firstPlan = runtime.build(first.program);
    auto secondPlan = runtime.build(second.program);
    expect(mlir::succeeded(firstPlan) && mlir::succeeded(secondPlan),
           "independent consumers with cloned definitions build");
    if (mlir::succeeded(firstPlan) && mlir::succeeded(secondPlan)) {
      expect(firstPlan->readyResets != secondPlan->readyResets &&
                 firstPlan->result != secondPlan->result,
             "independent consumers allocate disjoint private state");
      expect(equals(runtime.run(*firstPlan, {{"x", 2}}), 4) && runtime.eachFillOnce(1) &&
                 equals(runtime.run(*secondPlan, {{"x", 5}}), 10) && runtime.eachFillOnce(1) &&
                 runtime.sourceReads["x"] == 1,
             "second consumer observes a source write instead of previous consumer cache");
      expect(equals(runtime.run(*firstPlan, {{"x", 8}}), 16) && runtime.eachFillOnce(1),
             "first consumer reentry remains fresh after independent consumer execution");
    }
  }

  {
    Fixture fixture;
    auto fill = fixture.fill(source("x"));
    constexpr unsigned depth = 40;
    for (unsigned i = 1; i < depth; ++i)
      fill = fixture.fill(operation("analog_add", {deferred(fill), deferred(fill)}));
    fixture.root(operation("analog_add", {deferred(fill), deferred(fill)}));
    Runtime runtime;
    auto plan = runtime.build(fixture.program);
    expect(mlir::succeeded(plan), "40-level shared diamond builds");
    if (mlir::succeeded(plan)) {
      expect(plan->blocks.size() <= 10 * depth + 10 && plan->readyResets.size() == depth,
             "40-level diamond has linear blocks and one ready slot per fill");
      expect(equals(runtime.run(*plan, {{"x", 3}}), std::ldexp(3.0, depth)) &&
                 runtime.eachFillOnce(depth) && runtime.sourceReads["x"] == 1 &&
                 !runtime.repeatedBlock && runtime.steps <= static_cast<unsigned>(plan->bound),
             "40-level diamond evaluates every unique body once within bound");
      expect(equals(runtime.run(*plan, {{"x", 7}}), std::ldexp(7.0, depth)) &&
                 runtime.eachFillOnce(depth) && runtime.sourceReads["x"] == 1,
             "40-level diamond freshness survives repeated visits");
      llvm::outs() << "40-level control diamond: " << plan->blocks.size() << " blocks, "
                   << runtime.privateNames.size() << " private scalars, " << runtime.steps
                   << " executed blocks\n";
    }
  }

  {
    Fixture fixture;
    auto fill = fixture.fill(source("x"));
    fixture.root(deferred(fill));
    Runtime runtime;
    auto duplicate = fixture.program;
    duplicate.fills.push_back(duplicate.fills.front());
    expect(mlir::failed(runtime.build(duplicate)), "duplicate actual fill identity rejected");
    auto selfReference = fixture.program;
    selfReference.fills.front().body = deferred(fill);
    expect(mlir::failed(runtime.build(selfReference)), "self/forward body dependency rejected");
    auto event = fixture.program;
    event.roots.front().argumentSlot = 0;
    expect(mlir::failed(runtime.build(event)), "event root cannot enter control dispatcher");
    auto orphan = fixture.program;
    orphan.roots.front().expression = literal(1);
    expect(mlir::failed(runtime.build(orphan)), "demand-free root with orphan fills rejected");
    auto alias = nodal::buildAnalogLiveControlPlan(
        fixture.program, [](llvm::StringRef, llvm::StringRef) { return std::string("alias"); },
        [&](const Expression &expression,
            std::optional<llvm::ArrayRef<std::string>> substitutions) {
          return runtime.render(expression, substitutions);
        });
    expect(mlir::failed(alias), "allocator aliasing rejected");
    auto renderFailure = nodal::buildAnalogLiveControlPlan(
        fixture.program,
        [&](llvm::StringRef kind, llvm::StringRef label) { return runtime.allocate(kind, label); },
        [](const Expression &, std::optional<llvm::ArrayRef<std::string>>)
            -> mlir::FailureOr<std::string> { return mlir::failure(); });
    expect(mlir::failed(renderFailure), "scalar-render failure propagated");
  }

  constexpr size_t maximum = static_cast<size_t>(std::numeric_limits<int32_t>::max());
  expect(nodal::analog_live_control_detail::canAppendBlock(maximum - 1),
         "last signed32 block label admitted");
  expect(!nodal::analog_live_control_detail::canAppendBlock(maximum) &&
             !nodal::analog_live_control_detail::canAppendBlock(std::numeric_limits<size_t>::max()),
         "signed32 overflow rejected before appending or narrowing");
  llvm::outs() << checks << " live-control plan checks, " << failures << " failures\n";
  return failures ? 1 : 0;
}
