#include "nodal/Transforms/AnalogSemanticLint.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Pass/PassRegistry.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogEquationModel.h"
#include "nodal/Dialect/Nodal/AnalogEvents.h"
#include "nodal/Dialect/Nodal/AnalogFunctions.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "nodal/Transforms/AnalogCanonicalization.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallVector.h"

#include <map>
#include <optional>
#include <set>
#include <string>
#include <utility>

using namespace mlir;

namespace nodal {
namespace {

enum class Category : unsigned {
  Divisor,
  Unreachable,
  Discontinuity,
  Portability,
  StructuralUnavailable,
  Count
};

llvm::StringRef text(Operation *operation, llvm::StringRef key) {
  if (auto attribute = operation->getAttrOfType<StringAttr>(key))
    return attribute.getValue();
  return {};
}

bool monitor(Operation *operation) {
  return llvm::isa<AnalogCrossOp, AnalogAboveOp, AnalogTimerOp>(operation);
}

LogicalResult verifyLintInput(mlir::ModuleOp module) {
  if (failed(mlir::verify(module)) || failed(verifyParameterModel(module)) ||
      failed(verifyAnalogNumericModel(module)) || failed(verifyAnalogSemanticInventory(module)))
    return failure();
  return success();
}

// Keep findings keyed by their actual source operation. Complete live consumers
// may be resolved after a fill's authored position; final emission still follows
// source order, independently of map order or dependency traversal order.
class Collector {
public:
  FailureOr<llvm::SmallVector<AnalogSemanticLintIssue>> collect(mlir::ModuleOp module) {
    auto structure = analyzeAnalogEquationModel(module);
    if (failed(structure))
      return failure();
    if (!structure->present) {
      if (auto inventory = module->getAttrOfType<ArrayAttr>("nodal.bridge.analog_semantics"))
        for (Attribute attribute : inventory) {
          auto kind = llvm::cast<DictionaryAttr>(attribute).getAs<StringAttr>("kind").getValue();
          if (kind == "equation" || kind == "initial_equation")
            add(module, Category::StructuralUnavailable);
        }
    } else if (!structure->complete) {
      add(module, Category::StructuralUnavailable);
      structuralUnavailableMessage =
          "analysis unavailable: typed source equation profile is incomplete";
      for (const auto &reason : structure->unsupported)
        structuralUnavailableMessage += "; " + reason;
      structuralUnavailableMessage +=
          "; no equation balance or incidence matching conclusion is available";
    }
    LogicalResult result = success();
    module.walk<WalkOrder::PreOrder>([&](Operation *operation) {
      if (succeeded(result))
        result = inspect(operation);
    });
    if (failed(result))
      return failure();
    llvm::SmallVector<AnalogSemanticLintIssue> issues;
    module.walk<WalkOrder::PreOrder>([&](Operation *operation) {
      auto found = findings.find(operation);
      if (found == findings.end())
        return;
      for (unsigned category = 0; category < static_cast<unsigned>(Category::Count); ++category) {
        if (!(found->second & (1u << category)))
          continue;
        issues.push_back({operation, "NODAL-ANALOG-LINT-046-00" + std::to_string(category + 1),
                          category == static_cast<unsigned>(Category::StructuralUnavailable) &&
                                  !structuralUnavailableMessage.empty()
                              ? structuralUnavailableMessage
                              : message(static_cast<Category>(category))});
      }
    });
    if (structure->complete)
      appendStructuralIssues(module, *structure, issues);
    return issues;
  }

private:
  DenseMap<Operation *, unsigned> findings;
  DenseMap<Value, std::optional<double>> constants;
  DenseMap<Value, ParameterZeroAdmissibility> valueZeroFacts;
  DenseMap<Operation *, ParameterZeroAdmissibility> parameterZeroFacts;
  DenseSet<Operation *> resolvedLiveRoots;
  std::string structuralUnavailableMessage;

  static bool structuralDefect(const AnalogStructuralMatch &match) {
    return match.equations.size() != match.unknowns.size() ||
           match.matched.size() < match.unknowns.size();
  }

  static AnalogSemanticLintIssue structuralIssue(mlir::ModuleOp module,
                                                 const AnalogStructuralMatch &match, bool global) {
    const bool imbalanced = match.equations.size() != match.unknowns.size();
    std::string message = std::string(global ? "root disjoint-union" : "component") + " '" +
                          match.component + "', analysis '" + match.analysis +
                          "': " + std::to_string(match.equations.size()) + " equations, " +
                          std::to_string(match.unknowns.size()) + " declared unknowns, " +
                          std::to_string(match.matched.size()) + " matched incidence pairs; ";
    message += imbalanced ? "equation and unknown counts are unequal"
                          : "square equation graph has deficient maximum incidence matching";
    message += "; this finite structural check does not establish numerical rank or solvability";
    return {match.origin ? match.origin : module.getOperation(),
            imbalanced ? "NODAL-ANALOG-LINT-046-006" : "NODAL-ANALOG-LINT-046-007",
            std::move(message), true};
  }

  static void appendStructuralIssues(mlir::ModuleOp module, const AnalogStructuralModel &structure,
                                     llvm::SmallVectorImpl<AnalogSemanticLintIssue> &issues) {
    struct ExplainedDefects {
      std::set<std::string> unmatchedEquations;
      std::set<std::string> unmatchedUnknowns;
    };
    std::map<std::string, ExplainedDefects> explained;
    for (const auto &match : structure.components) {
      if (!structuralDefect(match))
        continue;
      issues.push_back(structuralIssue(module, match, false));
      auto &local = explained[match.analysis];
      local.unmatchedEquations.insert(match.unmatchedEquations.begin(),
                                      match.unmatchedEquations.end());
      local.unmatchedUnknowns.insert(match.unmatchedUnknowns.begin(),
                                     match.unmatchedUnknowns.end());
    }
    for (const auto &match : structure.global) {
      if (!structuralDefect(match))
        continue;
      auto found = explained.find(match.analysis);
      // A disjoint-union defect caused entirely by already diagnosed local
      // unmatched vertices adds no information, including compensating local
      // count defects whose aggregate happens to be square.
      bool newCause = found == explained.end();
      if (!newCause) {
        const auto &local = found->second;
        newCause = llvm::any_of(match.unmatchedEquations,
                                [&](const std::string &identity) {
                                  return !local.unmatchedEquations.count(identity);
                                }) ||
                   llvm::any_of(match.unmatchedUnknowns, [&](const std::string &identity) {
                     return !local.unmatchedUnknowns.count(identity);
                   });
      }
      if (newCause)
        issues.push_back(structuralIssue(module, match, true));
    }
  }

  static std::string message(Category category) {
    switch (category) {
    case Category::Divisor:
      return "divisor is not proven nonzero over its legal values; a symbolic default is not a "
             "nonzero guarantee";
    case Category::Unreachable:
      return "event monitor cannot fire: its enable is proven zero or its monitored expression "
             "is constant outside the event's triggering semantics";
    case Category::Discontinuity:
      return "floor or ceil with an unproven constant argument may introduce value "
             "discontinuities; "
             "no continuity proof or smoothing is performed";
    case Category::Portability:
      return "lifecycle analysis filter is outside the portable analog analysis registry; "
             "simulator-specific support must be checked";
    case Category::StructuralUnavailable:
      return "analysis unavailable: F32 source inventory does not establish local/global equation "
             "balance or structural nonsingularity; residual/DAE structural analysis requires F135";
    case Category::Count:
      break;
    }
    llvm_unreachable("unknown analog lint category");
  }

  void add(Operation *operation, Category category) {
    findings[operation] |= 1u << static_cast<unsigned>(category);
  }

  FailureOr<std::optional<double>> constant(Value value) {
    auto found = constants.find(value);
    if (found != constants.end())
      return found->second;
    auto evaluated = getAnalogConstantRealValue(value);
    if (failed(evaluated))
      return failure();
    constants.try_emplace(value, *evaluated);
    return *evaluated;
  }

  template <typename Key>
  FailureOr<bool> excludesZero(Key key, DenseMap<Key, ParameterZeroAdmissibility> &cache) {
    auto found = cache.find(key);
    if (found != cache.end())
      return found->second == ParameterZeroAdmissibility::ZeroExcluded;
    auto fact = analyzeParameterZeroAdmissibility(key);
    if (failed(fact))
      return failure();
    cache.try_emplace(key, *fact);
    return *fact == ParameterZeroAdmissibility::ZeroExcluded;
  }

  LogicalResult inspectExpression(Operation *origin, const AnalogSourceExpression &expression) {
    // Only walk the bounded parsed expression bodies. A deferred reference is a
    // leaf; each actual fill is inspected once through the resolver's DAG below.
    llvm::SmallVector<const AnalogSourceExpression *, 16> pending{&expression};
    while (!pending.empty()) {
      const auto *current = pending.pop_back_val();
      if (current->operation == "analog_div" && current->operands.size() == 2) {
        const auto &denominator = current->operands[1];
        bool excluded = denominator.constant && *denominator.constant != 0.0;
        if (!excluded && denominator.declaration &&
            llvm::isa<ParameterOp>(denominator.declaration)) {
          auto fact = excludesZero(denominator.declaration, parameterZeroFacts);
          if (failed(fact))
            return failure();
          excluded = *fact;
        }
        if (!excluded)
          add(origin, Category::Divisor);
      }
      llvm::StringRef name = current->operation;
      if (name.starts_with(analogFunctionSourcePrefix)) {
        const auto *entry =
            lookupAnalogFunction(name.drop_front(analogFunctionSourcePrefix.size()));
        if (entry && entry->stepDiscontinuity && !current->constant)
          add(origin, Category::Discontinuity);
      }
      for (const auto &operand : current->operands)
        pending.push_back(&operand);
    }
    return success();
  }

  void inspectEventArgument(Operation *operation, unsigned slot,
                            const AnalogSourceExpression &expression) {
    const bool cross = llvm::isa<AnalogCrossOp>(operation);
    const unsigned enableSlot = cross ? 4 : 3;
    if (slot == enableSlot && expression.constant == 0.0)
      add(operation, Category::Unreachable);
    if (slot == 0 && expression.constant &&
        (cross || (llvm::isa<AnalogAboveOp>(operation) && *expression.constant <= 0.0)))
      add(operation, Category::Unreachable);
  }

  LogicalResult inspectLive(Operation *operation) {
    if (resolvedLiveRoots.contains(operation))
      return success();
    auto program = resolveAnalogLiveSourceProgram(operation);
    if (failed(program))
      return failure();
    for (const auto &fill : program->fills)
      if (failed(inspectExpression(fill.reference.getDefiningOp(), fill.body)))
        return failure();
    for (const auto &root : program->roots) {
      resolvedLiveRoots.insert(root.operation);
      if (failed(inspectExpression(root.operation, root.expression)))
        return failure();
      if (root.argumentSlot)
        inspectEventArgument(root.operation, *root.argumentSlot, root.expression);
    }
    return success();
  }

  LogicalResult inspect(Operation *operation) {
    if (llvm::isa<AnalogDivOp>(operation)) {
      auto denominator = constant(operation->getOperand(1));
      if (failed(denominator))
        return failure();
      if (!*denominator) {
        auto excluded = excludesZero(operation->getOperand(1), valueZeroFacts);
        if (failed(excluded))
          return failure();
        if (!*excluded)
          add(operation, Category::Divisor);
      }
    }
    if (llvm::isa<AnalogFunctionOp>(operation)) {
      const auto *entry = lookupAnalogFunction(text(operation, "function_id"));
      if (entry && entry->stepDiscontinuity) {
        auto argument = constant(operation->getOperand(0));
        if (failed(argument))
          return failure();
        if (!*argument)
          add(operation, Category::Discontinuity);
      }
    }
    if (llvm::isa<AnalogInitialStepOp, AnalogFinalStepOp>(operation)) {
      for (Attribute attribute : operation->getAttrOfType<ArrayAttr>("analyses"))
        if (!isAnalogAnalysisTarget(llvm::cast<StringAttr>(attribute).getValue()))
          add(operation, Category::Portability);
    }
    if ((monitor(operation) || llvm::isa<AnalogIfArmOp, AnalogCaseOp, AnalogLoopOp>(operation)) &&
        text(operation, "reference_form") == "live")
      return inspectLive(operation);
    if (!monitor(operation) || resolvedLiveRoots.contains(operation))
      return success();
    auto arguments = operation->getAttrOfType<ArrayAttr>("arguments");
    for (auto [slot, argument] : llvm::enumerate(arguments)) {
      auto source = llvm::cast<DictionaryAttr>(argument).getAs<StringAttr>("value").getValue();
      auto expression = parseAnalogSourceExpression(operation, source);
      if (failed(expression) || failed(inspectExpression(operation, *expression)))
        return failure();
      inspectEventArgument(operation, slot, *expression);
    }
    return success();
  }
};

class AnalogSemanticLintPass final
    : public PassWrapper<AnalogSemanticLintPass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(AnalogSemanticLintPass)
  llvm::StringRef getArgument() const final { return "nodal-lint-analog"; }
  llvm::StringRef getDescription() const final {
    return "Report read-only analog source risks without changing mathematical behavior";
  }
  void runOnOperation() final {
    auto module = getOperation();
    if (failed(verifyLintInput(module))) {
      signalPassFailure();
      return;
    }
    auto issues = collectAnalogSemanticLint(module);
    if (failed(issues)) {
      signalPassFailure();
      return;
    }
    bool hasErrors = false;
    for (const auto &issue : *issues) {
      if (issue.isError) {
        (void)emitMappedFailure(issue.operation, issue.code, issue.message);
        hasErrors = true;
      } else {
        emitMappedWarning(issue.operation, issue.code, issue.message);
      }
    }
    if (hasErrors) {
      signalPassFailure();
      return;
    }
    markAllAnalysesPreserved();
  }
};

class ReportAnalogStructurePass final
    : public PassWrapper<ReportAnalogStructurePass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(ReportAnalogStructurePass)
  llvm::StringRef getArgument() const final { return "nodal-report-analog-structure"; }
  llvm::StringRef getDescription() const final {
    return "Recompute diagnostic-only typed equation incidence facts without target acceptance";
  }
  void runOnOperation() final {
    auto module = getOperation();
    if (failed(verifyLintInput(module))) {
      signalPassFailure();
      return;
    }
    // Existing reports are never evidence. Defer replacement until all source
    // verification succeeds, so malformed input cannot gain a derived report.
    auto structure = analyzeAnalogEquationModel(module);
    if (failed(structure)) {
      signalPassFailure();
      return;
    }
    module->setAttr("nodal.analysis.analog_structure",
                    serializeAnalogStructuralModel(module.getContext(), *structure));
  }
};
} // namespace

FailureOr<llvm::SmallVector<AnalogSemanticLintIssue>>
collectAnalogSemanticLint(mlir::ModuleOp module) {
  return Collector().collect(module);
}

std::unique_ptr<Pass> createAnalogSemanticLintPass() {
  return std::make_unique<AnalogSemanticLintPass>();
}

std::unique_ptr<Pass> createReportAnalogStructurePass() {
  return std::make_unique<ReportAnalogStructurePass>();
}

void registerAnalogSemanticLintPass() {
  static PassRegistration<AnalogSemanticLintPass> registration;
  static PassRegistration<ReportAnalogStructurePass> reportRegistration;
  (void)registration;
  (void)reportRegistration;
}

} // namespace nodal
