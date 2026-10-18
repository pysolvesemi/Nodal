#ifndef NODAL_BACKEND_ANALOGLIVECONTROLPLAN_H
#define NODAL_BACKEND_ANALOGLIVECONTROLPLAN_H

#include "nodal/Dialect/Nodal/AnalogEvents.h"

#include "llvm/ADT/ArrayRef.h"
#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/STLFunctionalExtras.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringSet.h"

#include <cstddef>
#include <cstdint>
#include <limits>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace nodal {

struct AnalogLiveControlAssignment {
  std::string target;
  std::string value;
};

struct AnalogLiveControlBlock {
  enum class Kind { Jump, Branch, Return };
  std::vector<AnalogLiveControlAssignment> assignments;
  Kind kind = Kind::Jump;
  // Jump destination, or Branch's true destination. Zero is the halt label.
  int32_t target = 0;
  int32_t falseTarget = 0;
  std::string condition;
  // A Return reads this fill's private, previously assigned continuation slot.
  std::string continuation;
};

struct AnalogLiveControlPlan {
  // Block labels are one plus their index in blocks; zero is halt.
  int32_t entry = 0;
  int32_t bound = 0;
  std::string result;
  // Reset these private integer identifiers on every reached consumer visit.
  std::vector<std::string> readyResets;
  std::vector<AnalogLiveControlBlock> blocks;
};

using AnalogLiveControlAllocator =
    llvm::function_ref<std::string(llvm::StringRef kind, llvm::StringRef label)>;
using AnalogLiveControlRenderer = llvm::function_ref<mlir::FailureOr<std::string>(
    const AnalogSourceExpression &, std::optional<llvm::ArrayRef<std::string>>)>;

namespace analog_live_control_detail {

inline bool canAppendBlock(size_t currentCount) {
  return currentCount < static_cast<size_t>(std::numeric_limits<int32_t>::max());
}

// This is a derived control-flow plan, not another expression parser. The
// verified program supplies type/effect/ownership checks and dependency order.
// Only paths containing deferred leaves are decomposed; all scalar rendering
// stays with the existing backend, including demand-free lazy expressions.
class Builder {
public:
  Builder(const AnalogLiveSourceProgram &program, AnalogLiveControlAllocator allocate,
          AnalogLiveControlRenderer render)
      : program(program), allocator(allocate), renderer(render) {}

  mlir::FailureOr<AnalogLiveControlPlan> build() {
    if (program.roots.size() != 1 || program.roots.front().argumentSlot || program.fills.empty())
      return mlir::failure();
    fills.reserve(program.fills.size());
    for (const auto &definition : program.fills) {
      if (!definition.reference || definition.body.kind != "real" ||
          !fillIndices.try_emplace(definition.reference, fills.size()).second)
        return mlir::failure();
      Fill fill;
      fill.cache = allocate("real", "live_cache");
      fill.ready = allocate("integer", "live_ready");
      fill.continuation = allocate("integer", "live_return");
      plan.readyResets.push_back(fill.ready);
      fills.push_back(std::move(fill));
    }
    // The single body for a fill is compiled only after its dependencies. A
    // body can therefore jump only to an already constructed, different body.
    for (size_t i = 0; i < program.fills.size(); ++i) {
      const auto &body = program.fills[i].body;
      if (mlir::failed(indexDemand(body, i)))
        return mlir::failure();
      auto &fill = fills[i];
      std::string bodyResult = allocate("real", "live_body");
      AnalogLiveControlBlock complete;
      complete.assignments = {{fill.cache, bodyResult}, {fill.ready, "1"}};
      complete.kind = AnalogLiveControlBlock::Kind::Return;
      complete.continuation = fill.continuation;
      fill.completion = append(std::move(complete));
      auto entry = lower(body, bodyResult, fill.completion);
      if (mlir::failed(entry))
        return mlir::failure();
      fill.entry = *entry;
    }
    const auto &root = program.roots.front().expression;
    if (mlir::failed(indexDemand(root, fills.size())) || !hasDemand.lookup(&root))
      return mlir::failure();
    plan.result = temporary(root, "live_result");
    auto entry = lower(root, plan.result, 0);
    if (mlir::failed(entry) || invalid)
      return mlir::failure();
    plan.entry = *entry;
    // append checked the bound before every insertion, including every body,
    // demand continuation and lazy branch. No narrowing precedes that check.
    plan.bound = static_cast<int32_t>(plan.blocks.size());
    if (mlir::failed(verify()))
      return mlir::failure();
    return std::move(plan);
  }

private:
  struct Demand {
    int32_t start;
    int32_t returned;
    std::string result;
  };

  struct Fill {
    std::string cache;
    std::string ready;
    std::string continuation;
    int32_t entry = 0;
    int32_t completion = 0;
    std::vector<Demand> demands;
  };

  std::string allocate(llvm::StringRef kind, llvm::StringRef label) {
    std::string name = allocator(kind, label);
    // The backend's reserved-name allocator owns collision avoidance against
    // source names. Also reject local aliasing and malformed identifiers here.
    if (name.empty() || !(llvm::isAlpha(name.front()) || name.front() == '_') ||
        !llvm::all_of(name, [](char c) { return llvm::isAlnum(c) || c == '_' || c == '$'; }) ||
        !privateNames.insert(name).second)
      invalid = true;
    return name;
  }

  std::string temporary(const AnalogSourceExpression &expression, llvm::StringRef label) {
    if (expression.kind != "real" && expression.kind != "boolean" && expression.kind != "integer")
      invalid = true;
    return allocate(expression.kind == "real" ? "real" : "integer", label);
  }

  mlir::LogicalResult indexDemand(const AnalogSourceExpression &expression, size_t beforeFill) {
    bool demand = expression.operation == "deferred_reference";
    if (demand) {
      auto found = fillIndices.find(expression.deferredReference);
      if (!expression.deferredReference || !expression.operands.empty() ||
          found == fillIndices.end() || found->second >= beforeFill || expression.kind != "real")
        return mlir::failure();
    } else if (expression.deferredReference) {
      return mlir::failure();
    }
    for (const auto &child : expression.operands) {
      if (mlir::failed(indexDemand(child, beforeFill)))
        return mlir::failure();
      demand |= hasDemand.lookup(&child);
    }
    hasDemand[&expression] = demand;
    return mlir::success();
  }

  int32_t append(AnalogLiveControlBlock block) {
    if (!canAppendBlock(plan.blocks.size())) {
      invalid = true;
      return 0;
    }
    plan.blocks.push_back(std::move(block));
    return static_cast<int32_t>(plan.blocks.size());
  }

  int32_t assign(llvm::StringRef destination, std::string value, int32_t next) {
    AnalogLiveControlBlock block;
    block.assignments.push_back({destination.str(), std::move(value)});
    block.target = next;
    return append(std::move(block));
  }

  int32_t branch(std::string condition, int32_t whenTrue, int32_t whenFalse) {
    AnalogLiveControlBlock block;
    block.kind = AnalogLiveControlBlock::Kind::Branch;
    block.condition = std::move(condition);
    block.target = whenTrue;
    block.falseTarget = whenFalse;
    return append(std::move(block));
  }

  // Branch alternatives share their enclosing result destination; each demand
  // still gets its own result slot. The returned entry establishes destination
  // before reaching `next`.
  mlir::FailureOr<int32_t> lower(const AnalogSourceExpression &expression,
                                 llvm::StringRef destination, int32_t next) {
    if (invalid)
      return mlir::failure();
    if (!hasDemand.lookup(&expression)) {
      auto value = renderer(expression, std::nullopt);
      if (mlir::failed(value))
        return mlir::failure();
      return assign(destination, std::move(*value), next);
    }
    if (expression.operation == "deferred_reference") {
      auto found = fillIndices.find(expression.deferredReference);
      if (found == fillIndices.end())
        return mlir::failure();
      auto &fill = fills[found->second];
      if (fill.entry == 0)
        return mlir::failure();
      // Keep a distinct result slot and return site for each demand, even
      // when mutually exclusive branches ultimately assign the same parent.
      std::string result = allocate("real", "live_demand");
      AnalogLiveControlBlock resume;
      resume.assignments = {{result, fill.cache}, {destination.str(), result}};
      resume.target = next;
      int32_t returned = append(std::move(resume));
      int32_t start = assign(fill.continuation, std::to_string(returned), fill.entry);
      fill.demands.push_back({start, returned, std::move(result)});
      return branch(fill.ready, returned, start);
    }
    if (expression.operation == "bool_and" || expression.operation == "bool_or") {
      if (expression.operands.size() != 2 || expression.kind != "boolean")
        return mlir::failure();
      const auto &left = expression.operands[0];
      const auto &right = expression.operands[1];
      bool isAnd = expression.operation == "bool_and";
      auto rightEntry = lower(right, destination, next);
      if (mlir::failed(rightEntry))
        return mlir::failure();
      int32_t shortEntry = assign(destination, isAnd ? "0" : "1", next);
      return lowerCondition(left, isAnd ? *rightEntry : shortEntry,
                            isAnd ? shortEntry : *rightEntry);
    }
    if (expression.operation == "analog_select") {
      if (expression.operands.size() != 3)
        return mlir::failure();
      auto whenTrue = lower(expression.operands[1], destination, next);
      auto whenFalse = lower(expression.operands[2], destination, next);
      if (mlir::failed(whenTrue) || mlir::failed(whenFalse))
        return mlir::failure();
      return lowerCondition(expression.operands[0], *whenTrue, *whenFalse);
    }

    // Strict operations retain the existing renderer. Only demand-bearing
    // children need scalar slots; a demand-free subtree is rendered once and
    // remains inside the reached parent expression, including its own laziness.
    std::vector<std::string> arguments;
    arguments.reserve(expression.operands.size());
    for (const auto &child : expression.operands) {
      if (hasDemand.lookup(&child)) {
        arguments.push_back(temporary(child, "live_value"));
      } else {
        auto value = renderer(child, std::nullopt);
        if (mlir::failed(value))
          return mlir::failure();
        arguments.push_back(std::move(*value));
      }
    }
    auto value = renderer(expression, llvm::ArrayRef<std::string>(arguments));
    if (mlir::failed(value))
      return mlir::failure();
    int32_t entry = assign(destination, std::move(*value), next);
    for (size_t i = expression.operands.size(); i > 0; --i) {
      const auto &child = expression.operands[i - 1];
      if (hasDemand.lookup(&child)) {
        auto childEntry = lower(child, arguments[i - 1], entry);
        if (mlir::failed(childEntry))
          return mlir::failure();
        entry = *childEntry;
      }
    }
    return entry;
  }

  mlir::FailureOr<int32_t> lowerCondition(const AnalogSourceExpression &condition, int32_t whenTrue,
                                          int32_t whenFalse) {
    if (condition.kind != "boolean")
      return mlir::failure();
    if (!hasDemand.lookup(&condition)) {
      auto value = renderer(condition, std::nullopt);
      if (mlir::failed(value))
        return mlir::failure();
      return branch(std::move(*value), whenTrue, whenFalse);
    }
    std::string value = temporary(condition, "live_condition");
    int32_t choose = branch(value, whenTrue, whenFalse);
    return lower(condition, value, choose);
  }

  mlir::LogicalResult verify() const {
    auto validTarget = [&](int32_t target) { return target >= 0 && target <= plan.bound; };
    if (plan.entry <= 0 || !validTarget(plan.entry) || plan.bound <= 0)
      return mlir::failure();
    llvm::StringSet<> continuations;
    llvm::DenseSet<int32_t> bodyEntries;
    llvm::DenseSet<int32_t> bodyCompletions;
    llvm::DenseSet<int32_t> demandReturns;
    for (const auto &fill : fills) {
      if (fill.entry <= 0 || !validTarget(fill.entry) || fill.completion <= 0 ||
          !validTarget(fill.completion) || fill.demands.empty() ||
          !continuations.insert(fill.continuation).second ||
          !bodyEntries.insert(fill.entry).second || !bodyCompletions.insert(fill.completion).second)
        return mlir::failure();
      const auto &completion = plan.blocks[fill.completion - 1];
      if (completion.kind != AnalogLiveControlBlock::Kind::Return ||
          completion.continuation != fill.continuation)
        return mlir::failure();
      for (const auto &demand : fill.demands) {
        if (demand.returned <= 0 || !validTarget(demand.returned) || demand.start <= 0 ||
            !validTarget(demand.start) || !demandReturns.insert(demand.returned).second)
          return mlir::failure();
        const auto &resume = plan.blocks[demand.returned - 1];
        const auto &start = plan.blocks[demand.start - 1];
        if (resume.kind != AnalogLiveControlBlock::Kind::Jump || resume.assignments.size() != 2 ||
            resume.assignments[0].target != demand.result ||
            resume.assignments[0].value != fill.cache ||
            resume.assignments[1].value != demand.result ||
            start.kind != AnalogLiveControlBlock::Kind::Jump || start.target != fill.entry ||
            start.assignments.size() != 1 || start.assignments[0].target != fill.continuation ||
            start.assignments[0].value != std::to_string(demand.returned))
          return mlir::failure();
      }
    }
    for (const auto &block : plan.blocks) {
      for (const auto &assignment : block.assignments)
        if (!privateNames.contains(assignment.target) || assignment.value.empty())
          return mlir::failure();
      switch (block.kind) {
      case AnalogLiveControlBlock::Kind::Jump:
        if (!validTarget(block.target))
          return mlir::failure();
        break;
      case AnalogLiveControlBlock::Kind::Branch:
        if (block.condition.empty() || !validTarget(block.target) ||
            !validTarget(block.falseTarget))
          return mlir::failure();
        break;
      case AnalogLiveControlBlock::Kind::Return:
        if (!continuations.contains(block.continuation))
          return mlir::failure();
        break;
      }
    }
    return mlir::success();
  }

  const AnalogLiveSourceProgram &program;
  AnalogLiveControlAllocator allocator;
  AnalogLiveControlRenderer renderer;
  AnalogLiveControlPlan plan;
  llvm::DenseMap<mlir::Value, size_t> fillIndices;
  llvm::DenseMap<const AnalogSourceExpression *, bool> hasDemand;
  llvm::StringSet<> privateNames;
  std::vector<Fill> fills;
  bool invalid = false;
};
} // namespace analog_live_control_detail

// The allocator declares and reserves a fresh private scalar for each call;
// kinds are exactly "real" and "integer". The renderer receives nullopt for a
// demand-free subtree, or one already-rendered string per immediate child for a
// strict operation. It must not traverse the original children in the latter
// case. No deferred leaf reaches the renderer.
//
// Definition-before-use follows construction: a child completes before its
// parent's assignment; each demand stores its continuation before body entry;
// completion assigns cache before ready; cache reads are reached only via a
// ready test or body completion. All ready flags must be reset before entry.
// Acyclic dependency-first fills cannot be reentered while active. Each unique
// fill body and each reached syntax/demand block therefore runs at most once
// per visit, so the number of non-halt blocks is a sufficient signed32 bound.
// Construction uses O(nodes + direct fill edges) blocks/storage, never expands
// bodies at references, and leaves statement placement and lifetime to backend.
inline mlir::FailureOr<AnalogLiveControlPlan>
buildAnalogLiveControlPlan(const AnalogLiveSourceProgram &program,
                           AnalogLiveControlAllocator allocate, AnalogLiveControlRenderer render) {
  return analog_live_control_detail::Builder(program, allocate, render).build();
}

} // namespace nodal

#endif
