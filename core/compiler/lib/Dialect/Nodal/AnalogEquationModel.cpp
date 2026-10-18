#include "nodal/Dialect/Nodal/AnalogEquationModel.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Verifier.h"
#include "nodal/Diagnostics/DiagnosticSupport.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"

#include <algorithm>
#include <deque>
#include <iterator>
#include <map>
#include <set>
#include <tuple>

using namespace mlir;
namespace nodal {
namespace {
StringRef name(Operation *op) { return op->getName().getStringRef(); }
bool canonicalIdentity(StringRef value) {
  return !value.empty() && value == value.trim() && llvm::all_of(value, [](char c) {
    auto byte = static_cast<unsigned char>(c);
    return byte >= 0x20 && byte != 0x7f;
  });
}
StringRef text(Operation *op, StringRef key) {
  auto value = op->getAttrOfType<StringAttr>(key);
  return value ? value.getValue() : StringRef();
}
StringRef text(DictionaryAttr dict, StringRef key) {
  auto value = dict ? dict.getAs<StringAttr>(key) : StringAttr();
  return value ? value.getValue() : StringRef();
}
LogicalResult reject(Operation *op, StringRef message) {
  return emitMappedFailure(op, "NODAL-ANALOG-STRUCTURE-134-001", message);
}
FailureOr<std::set<std::string>> strings(DictionaryAttr dict, StringRef key) {
  auto array = dict ? dict.getAs<ArrayAttr>(key) : ArrayAttr();
  if (!array)
    return failure();
  std::set<std::string> result;
  for (Attribute attribute : array) {
    auto value = dyn_cast<StringAttr>(attribute);
    if (!value || value.getValue().empty() || !result.insert(value.getValue().str()).second)
      return failure();
  }
  return result;
}
StringRef moduleIdentity(Operation *module) {
  return text(module ? module->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr(),
              "semantic_path");
}
DictionaryAttr component(Operation *op) {
  Operation *region = op->getParentOp();
  return region && name(region) == "nodal.analog"
             ? region->getAttrOfType<DictionaryAttr>("source_equations")
             : DictionaryAttr();
}
LogicalResult owned(Operation *op, StringRef identity) {
  auto contract = component(op);
  Operation *region = op->getParentOp();
  Operation *module = region ? region->getParentOp() : nullptr;
  StringRef owner = text(contract, "owner"), id = text(contract, "component_id");
  if (!contract || !module || name(module) != "nodal.module" || !canonicalIdentity(owner) ||
      owner != moduleIdentity(module) || id.empty() || !id.starts_with(owner.str() + ".") ||
      text(op, "owner") != owner || text(op, "component") != id || !canonicalIdentity(identity) ||
      !identity.starts_with(id.str() + "."))
    return reject(op,
                  "source equation/state identity must resolve to its actual component and module");
  auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
  if (!metadata || text(metadata, "semantic_path") != identity)
    return reject(op, "source equation/state metadata must retain its exact semantic identity");
  return success();
}
bool realQuantity(Type type) {
  auto quantity = dyn_cast<QuantityType>(type);
  auto info = getAnalogNumericTypeInfo(type);
  return quantity && succeeded(info) && info->kind == AnalogNumericKind::Real &&
         isCanonicalDimensionSignature(info->dimension);
}
bool arithmetic(Operation *op) {
  StringRef n = name(op);
  return n == "nodal.analog_add" || n == "nodal.analog_sub" || n == "nodal.analog_mul" ||
         n == "nodal.analog_div" || n == "nodal.analog_neg";
}
using Dependencies = std::set<std::string>;
class Incidence {
public:
  explicit Incidence(Operation *region) : region(region) {}
  FailureOr<Dependencies> resolve(Value value) {
    SmallVector<std::pair<Value, bool>> pending{{value, false}};
    DenseSet<Value> active;
    while (!pending.empty()) {
      auto [current, finishing] = pending.pop_back_val();
      if (cache.count(current))
        continue;
      Operation *definition = current.getDefiningOp();
      if (!definition || definition->getParentOp() != region || definition->getNumResults() != 1 ||
          !realQuantity(current.getType()))
        return failure();
      if (name(definition) == "nodal.analog_unknown") {
        cache[current] = {text(definition, "state_id").str()};
        continue;
      }
      if (name(definition) == "nodal.real_literal" || name(definition) == "nodal.parameter_ref") {
        cache[current] = {};
        continue;
      }
      if (!arithmetic(definition))
        return failure();
      if (finishing) {
        Dependencies result;
        for (Value operand : definition->getOperands()) {
          auto found = cache.find(operand);
          if (found == cache.end())
            return failure();
          result.insert(found->second.begin(), found->second.end());
        }
        cache[current] = std::move(result);
        active.erase(current);
      } else {
        if (!active.insert(current).second)
          return failure();
        pending.push_back({current, true});
        for (Value operand : definition->getOperands())
          if (!cache.count(operand))
            pending.push_back({operand, false});
      }
    }
    return cache.lookup(value);
  }

private:
  Operation *region;
  DenseMap<Value, Dependencies> cache;
};

AnalogStructuralMatch match(Operation *origin, StringRef componentId, StringRef analysis,
                            const std::set<std::string> &unknowns,
                            const std::map<std::string, Dependencies> &edges) {
  AnalogStructuralMatch result;
  result.origin = origin;
  result.component = componentId.str();
  result.analysis = analysis.str();
  result.unknowns.assign(unknowns.begin(), unknowns.end());
  std::map<std::string, std::string> unknownToEquation, equationToUnknown;
  for (const auto &[equation, dependencies] : edges) {
    (void)dependencies;
    result.equations.push_back(equation);
    std::deque<std::string> queue{equation};
    std::set<std::string> visitedEquations{equation};
    std::map<std::string, std::string> predecessor;
    std::string freeUnknown;
    while (!queue.empty() && freeUnknown.empty()) {
      std::string next = std::move(queue.front());
      queue.pop_front();
      for (const auto &unknown : edges.at(next)) {
        if (!predecessor.emplace(unknown, next).second)
          continue;
        auto assigned = unknownToEquation.find(unknown);
        if (assigned == unknownToEquation.end()) {
          freeUnknown = unknown;
          break;
        }
        if (visitedEquations.insert(assigned->second).second)
          queue.push_back(assigned->second);
      }
    }
    while (!freeUnknown.empty()) {
      std::string nextEquation = predecessor.at(freeUnknown);
      auto old = equationToUnknown.find(nextEquation);
      std::string previous = old == equationToUnknown.end() ? std::string() : old->second;
      unknownToEquation[freeUnknown] = nextEquation;
      equationToUnknown[nextEquation] = freeUnknown;
      freeUnknown = std::move(previous);
    }
  }
  for (const auto &[equation, unknown] : equationToUnknown)
    result.matched.emplace_back(equation, unknown);
  for (const auto &equation : result.equations)
    if (!equationToUnknown.count(equation))
      result.unmatchedEquations.push_back(equation);
  for (const auto &unknown : result.unknowns)
    if (!unknownToEquation.count(unknown))
      result.unmatchedUnknowns.push_back(unknown);
  return result;
}
ArrayAttr stringArray(MLIRContext *context, const std::vector<std::string> &values) {
  SmallVector<Attribute> attrs;
  for (const auto &value : values)
    attrs.push_back(StringAttr::get(context, value));
  return ArrayAttr::get(context, attrs);
}
} // namespace

LogicalResult verifyAnalogUnknownOperation(Operation *op) {
  if (op->getNumResults() != 1 || !realQuantity(op->getResult(0).getType()) ||
      op->getNumOperands() > 1 || failed(owned(op, text(op, "state_id"))))
    return reject(op, "source unknown requires one owned typed Real quantity result");
  StringRef init = text(op, "initialization");
  bool hasValue = init == "fixed" || init == "guess";
  if ((!hasValue && init != "initial-equation" && init != "steady-state" &&
       init != "operating-point" && init != "solver-selected") ||
      op->getNumOperands() != static_cast<unsigned>(hasValue) ||
      (hasValue && op->getOperand(0).getType() != op->getResult(0).getType()))
    return reject(op, "source unknown initialization kind, arity and dimension must agree");
  return success();
}
LogicalResult verifyAnalogEquationOperation(Operation *op) {
  if (op->getNumResults() || op->getNumOperands() != 2 ||
      !realQuantity(op->getOperand(0).getType()) ||
      op->getOperand(0).getType() != op->getOperand(1).getType() ||
      failed(owned(op, text(op, "equation_id"))))
    return reject(op, "unordered source equation requires two owned equal-dimension Real operands");
  if (!op->getAttrOfType<BoolAttr>("initial_only") || !op->getAttrOfType<ArrayAttr>("analyses") ||
      !op->getAttrOfType<StringAttr>("continuity"))
    return reject(op, "source equation must retain phase, applicability and continuity metadata");
  return success();
}

FailureOr<AnalogStructuralModel> analyzeAnalogEquationModel(mlir::ModuleOp module) {
  if (failed(verifyAnalogSemanticInventory(module)))
    return failure();
  AnalogStructuralModel result;
  auto manifest = module->getAttrOfType<DictionaryAttr>("nodal.bridge.source_equations");
  if (!manifest && module->hasAttr("nodal.bridge.source_equations")) {
    (void)reject(module, "source equation manifest must be a dictionary");
    return failure();
  }
  bool typed = false;
  module.walk([&](Operation *op) {
    typed |= name(op) == "nodal.analog_unknown" || name(op) == "nodal.analog_equation" ||
             op->hasAttr("source_equations");
  });
  if (!manifest) {
    if (typed) {
      (void)reject(module, "typed source equations require their complete source manifest");
      return failure();
    }
    return result;
  }
  if (failed(mlir::verify(module)) || failed(verifyParameterModel(module)) ||
      failed(verifyAnalogNumericModel(module)))
    return failure();
  result.present = true;
  auto componentIds = strings(manifest, "component_ids");
  auto stateIds = strings(manifest, "state_ids");
  auto typedStateIds = strings(manifest, "typed_state_ids");
  auto equationIds = strings(manifest, "equation_ids");
  auto typedEquationIds = strings(manifest, "typed_equation_ids");
  auto uncoveredComponents = strings(manifest, "uncovered_component_ids");
  auto uncoveredStates = strings(manifest, "uncovered_state_ids");
  auto uncoveredEquations = strings(manifest, "uncovered_equation_ids");
  auto unsupported = strings(manifest, "unsupported");
  auto claimedComplete = manifest.getAs<BoolAttr>("profile_complete");
  if (text(manifest, "version") != "1" || text(manifest, "root").empty() || !claimedComplete ||
      failed(componentIds) || failed(stateIds) || failed(typedStateIds) || failed(equationIds) ||
      failed(typedEquationIds) || failed(uncoveredComponents) || failed(uncoveredStates) ||
      failed(uncoveredEquations) || failed(unsupported)) {
    (void)reject(module,
                 "source equation manifest requires canonical complete identity inventories");
    return failure();
  }
  std::set<std::string> reasons = *unsupported;
  auto incomplete = [&](StringRef reason) { reasons.insert(reason.str()); };
  std::map<std::string, DictionaryAttr> authored, states;
  if (auto inventory = module->getAttrOfType<ArrayAttr>("nodal.bridge.analog_semantics")) {
    for (Attribute attribute : inventory) {
      auto record = dyn_cast<DictionaryAttr>(attribute);
      StringRef kind = text(record, "kind");
      if (kind != "equation" && kind != "initial_equation")
        continue;
      if (!authored.emplace(text(record, "identity").str(), record).second) {
        (void)reject(module, "duplicate authored equation identity");
        return failure();
      }
    }
  }
  std::set<std::string> actualAuthored;
  for (const auto &[id, record] : authored) {
    (void)record;
    actualAuthored.insert(id);
  }
  if (actualAuthored != *equationIds) {
    (void)reject(module, "source equation manifest must cover every authored F32 equation");
    return failure();
  }
  auto stateInventory = manifest.getAs<ArrayAttr>("states");
  if (!stateInventory) {
    (void)reject(module, "source state declaration inventory is required");
    return failure();
  }
  for (Attribute attribute : stateInventory) {
    auto record = dyn_cast<DictionaryAttr>(attribute);
    StringRef stateId = text(record, "state_id"), owner = text(record, "owner");
    StringRef initialization = text(record, "initialization");
    auto hasInitializer = record ? record.getAs<BoolAttr>("has_initializer") : BoolAttr();
    bool valueInitialization = initialization == "fixed" || initialization == "guess";
    bool knownInitialization = valueInitialization || initialization == "initial-equation" ||
                               initialization == "steady-state" ||
                               initialization == "operating-point" ||
                               initialization == "solver-selected";
    if (!record || !canonicalIdentity(stateId) || !canonicalIdentity(owner) ||
        !stateId.starts_with(owner.str() + ".") ||
        !isCanonicalDimensionSignature(text(record, "dimension")) || !knownInitialization ||
        !hasInitializer || hasInitializer.getValue() != valueInitialization ||
        !states.emplace(stateId.str(), record).second) {
      (void)reject(module,
                   "source state declarations require unique typed initialization inventories");
      return failure();
    }
  }
  std::set<std::string> actualStates;
  for (const auto &[id, record] : states) {
    (void)record;
    actualStates.insert(id);
  }
  if (actualStates != *stateIds) {
    (void)reject(module, "source state identities disagree with declarations");
    return failure();
  }
  Operation *root = nullptr;
  unsigned moduleCount = 0;
  module.walk([&](Operation *op) {
    if (name(op) != "nodal.module")
      return;
    ++moduleCount;
    if (moduleIdentity(op) == text(manifest, "root"))
      root = op;
  });
  if (!root) {
    (void)reject(module, "source equation root must resolve to an actual module");
    return failure();
  }
  if (moduleCount != 1)
    incomplete("multiple module definitions require hierarchy-aware structural analysis");
  root->walk([&](Operation *op) {
    StringRef n = name(op);
    if (n == "nodal.instance" || n == "nodal.generate" || n == "nodal.contribute" ||
        n == "nodal.analog_procedure" || n == "nodal.terminal" || n == "nodal.terminal_array" ||
        n == "nodal.node" || n == "nodal.node_array" || n == "nodal.connect" ||
        n == "nodal.alias" || n == "nodal.branch" || n == "nodal.connection_set" ||
        n == "nodal.reference")
      incomplete("hierarchy, generated, conservative or procedural behavior is outside the "
                 "algebraic checkpoint");
    if (n == "nodal.analog" && !op->hasAttr("source_equations"))
      incomplete("ordinary analog behavior is not part of the source equation graph");
  });
  std::map<std::string, Operation *> regions;
  std::set<std::string> coveredStates, coveredEquations, actualTypedStates, actualTypedEquations;
  std::map<std::string, Dependencies> globalEdges[3];
  const StringRef analyses[] = {"dc", "operating-point", "transient"};
  LogicalResult valid = success();
  module.walk([&](Operation *region) {
    if (failed(valid) || !region->hasAttr("source_equations"))
      return;
    auto contract = region->getAttrOfType<DictionaryAttr>("source_equations");
    auto fail = [&](StringRef message) { valid = reject(region, message); };
    StringRef id = text(contract, "component_id");
    auto regionStates = strings(contract, "state_ids"),
         regionEquations = strings(contract, "equation_ids");
    auto typedStates = strings(contract, "typed_state_ids"),
         typedEquations = strings(contract, "typed_equation_ids");
    auto gaps = strings(contract, "unsupported");
    auto complete = contract ? contract.getAs<BoolAttr>("profile_complete") : BoolAttr();
    Operation *regionModule = region->getParentOp();
    if (name(region) != "nodal.analog" || !regionModule || name(regionModule) != "nodal.module" ||
        region->getNumRegions() != 1 || !llvm::hasSingleElement(region->getRegion(0)) ||
        id != moduleIdentity(regionModule).str() + ".component." + text(contract, "name").str() ||
        text(contract.getAs<DictionaryAttr>("metadata"), "semantic_path") != id ||
        text(contract, "owner") != moduleIdentity(regionModule) || text(contract, "name").empty() ||
        !regions.emplace(id.str(), region).second || !complete || failed(regionStates) ||
        failed(regionEquations) || failed(typedStates) || failed(typedEquations) || failed(gaps)) {
      fail("component region identity, ownership and coverage must be independently valid");
      return;
    }
    if (regionModule != root)
      incomplete("child component requires hierarchy-aware structural analysis");
    for (const auto &state : *regionStates)
      if (!coveredStates.insert(state).second || !states.count(state) ||
          text(states.at(state), "component") != id) {
        fail("state belongs to exactly one actual component");
        return;
      }
    for (const auto &equation : *regionEquations)
      if (!coveredEquations.insert(equation).second || !authored.count(equation)) {
        fail("equation belongs to exactly one actual component");
        return;
      }
    bool contractSupported = text(contract, "completeness") == "concrete" &&
                             text(contract, "topology") == "complete" &&
                             text(contract, "local_balance") == "required";
    if (auto replaceable = contract.getAs<BoolAttr>("replaceable"))
      contractSupported &= !replaceable.getValue();
    else {
      fail("component replaceable contract is missing");
      return;
    }
    if (!contractSupported)
      incomplete("component contract is not concrete/complete/required/nonreplaceable");
    for (const auto &gap : *gaps)
      reasons.insert(id.str() + ": " + gap);
    if (!complete.getValue())
      incomplete("component source coverage is incomplete");
    std::set<std::string> foundStates, foundEquations;
    std::map<std::string, Dependencies> edges[3];
    Incidence incidence(region);
    for (Operation &op : region->getRegion(0).front()) {
      if (name(&op) == "nodal.analog_unknown") {
        if (failed(verifyAnalogUnknownOperation(&op))) {
          valid = failure();
          return;
        }
        std::string state = text(&op, "state_id").str();
        if (!foundStates.insert(state).second || !actualTypedStates.insert(state).second ||
            !states.count(state)) {
          fail("typed unknown must have a unique original declaration");
          return;
        }
        auto record = states.at(state);
        auto info = getAnalogNumericTypeInfo(op.getResult(0).getType());
        if (text(record, "owner") != text(&op, "owner") || text(record, "component") != id ||
            text(record, "dimension") != info->dimension ||
            text(record, "initialization") != text(&op, "initialization") ||
            record.getAs<BoolAttr>("has_initializer").getValue() != (op.getNumOperands() == 1)) {
          fail("typed unknown dimension, owner and initialization must match original declaration");
          return;
        }
        if (op.getNumOperands() && failed(incidence.resolve(op.getOperand(0)))) {
          fail("initializer must retain an owned typed scalar expression");
          return;
        }
      } else if (name(&op) == "nodal.analog_equation") {
        if (failed(verifyAnalogEquationOperation(&op))) {
          valid = failure();
          return;
        }
        std::string equation = text(&op, "equation_id").str();
        if (!foundEquations.insert(equation).second ||
            !actualTypedEquations.insert(equation).second || !authored.count(equation)) {
          fail("typed equation must have one unique original authored record");
          return;
        }
        auto record = authored.at(equation),
             metadata = op.getAttrOfType<DictionaryAttr>("metadata");
        auto applicable = strings(op.getAttrDictionary(), "analyses"),
             originalAnalyses = strings(record, "analyses");
        bool initial = op.getAttrOfType<BoolAttr>("initial_only").getValue();
        auto info = getAnalogNumericTypeInfo(op.getOperand(0).getType());
        auto authoredDimension = canonicalizeAnalogSourceDimension(text(record, "dimension"));
        if (text(record, "owner") != text(&op, "owner") ||
            initial != (text(record, "kind") == "initial_equation") || failed(authoredDimension) ||
            *authoredDimension != info->dimension ||
            text(record, "continuity") != text(&op, "continuity") || failed(applicable) ||
            failed(originalAnalyses) || *applicable != *originalAnalyses ||
            text(record, "authored_left") != text(metadata, "authored_left") ||
            text(record, "authored_right") != text(metadata, "authored_right")) {
          fail("typed equation must preserve authored sides, dimensions, phase and applicability");
          return;
        }
        for (StringRef field : {"source_file", "source_line", "source_column"})
          if (record.get(field) != metadata.get(field)) {
            fail("typed equation source span differs from authored record");
            return;
          }
        if (!text(record, "guard").empty())
          incomplete("guarded equations require analysis-specific structural expansion");
        auto left = incidence.resolve(op.getOperand(0)),
             right = incidence.resolve(op.getOperand(1));
        if (failed(left) || failed(right)) {
          fail("typed equation operands must retain owned finite algebraic DAGs");
          return;
        }
        left->insert(right->begin(), right->end());
        for (const auto &state : *left)
          if (!regionStates->count(state)) {
            fail("equation references an undeclared or foreign component unknown");
            return;
          }
        if (!initial)
          for (unsigned a = 0; a != 3; ++a)
            if (applicable->count(analyses[a].str()))
              edges[a].emplace(equation, *left);
      } else if (!arithmetic(&op) && name(&op) != "nodal.real_literal" &&
                 name(&op) != "nodal.parameter_ref") {
        fail("source equation region contains an unsupported operation");
        return;
      }
    }
    if (foundStates != *typedStates || foundEquations != *typedEquations ||
        !std::includes(regionStates->begin(), regionStates->end(), foundStates.begin(),
                       foundStates.end()) ||
        !std::includes(regionEquations->begin(), regionEquations->end(), foundEquations.begin(),
                       foundEquations.end())) {
      fail("typed component operations disagree with explicit coverage inventory");
      return;
    }
    bool fullCoverage = foundStates == *regionStates && foundEquations == *regionEquations;
    if (complete.getValue() && (!fullCoverage || !gaps->empty() || !contractSupported)) {
      fail("complete component claim contradicts source coverage or contract");
      return;
    }
    if (!fullCoverage)
      incomplete("source component contains unrepresented equations or unknowns");
    for (unsigned a = 0; a != 3; ++a) {
      result.components.push_back(match(region, id, analyses[a], foundStates, edges[a]));
      globalEdges[a].insert(edges[a].begin(), edges[a].end());
    }
  });
  if (failed(valid))
    return failure();
  std::set<std::string> actualComponents;
  for (const auto &[id, region] : regions) {
    (void)region;
    actualComponents.insert(id);
  }
  std::set<std::string> missingComponents, missingStates, missingEquations;
  std::set_difference(componentIds->begin(), componentIds->end(), actualComponents.begin(),
                      actualComponents.end(),
                      std::inserter(missingComponents, missingComponents.end()));
  std::set_difference(stateIds->begin(), stateIds->end(), coveredStates.begin(),
                      coveredStates.end(), std::inserter(missingStates, missingStates.end()));
  std::set_difference(equationIds->begin(), equationIds->end(), coveredEquations.begin(),
                      coveredEquations.end(),
                      std::inserter(missingEquations, missingEquations.end()));
  if (!std::includes(componentIds->begin(), componentIds->end(), actualComponents.begin(),
                     actualComponents.end()) ||
      missingComponents != *uncoveredComponents || actualTypedStates != *typedStateIds ||
      actualTypedEquations != *typedEquationIds || missingStates != *uncoveredStates ||
      missingEquations != *uncoveredEquations) {
    (void)reject(
        module,
        "global source equation coverage does not match actual components and declarations");
    return failure();
  }
  if (!missingComponents.empty())
    incomplete("source component occurrences are not represented by distinct emitted definitions");
  if (!missingStates.empty() || !missingEquations.empty())
    incomplete("source states or equations are outside emitted explicit components");
  if (actualTypedStates != *stateIds || actualTypedEquations != *equationIds)
    incomplete("global typed source coverage is incomplete");
  if (claimedComplete.getValue() && !reasons.empty()) {
    (void)reject(module, "complete root claim contradicts actual source profile and coverage");
    return failure();
  }
  result.complete = claimedComplete.getValue() && reasons.empty();
  if (!result.complete) {
    if (reasons.empty())
      incomplete("source profile explicitly incomplete");
    result.unsupported.assign(reasons.begin(), reasons.end());
    result.components.clear();
    return result;
  }
  std::sort(result.components.begin(), result.components.end(), [](const auto &a, const auto &b) {
    return std::tie(a.component, a.analysis) < std::tie(b.component, b.analysis);
  });
  for (unsigned a = 0; a != 3; ++a)
    result.global.push_back(
        match(root, text(manifest, "root"), analyses[a], *stateIds, globalEdges[a]));
  return result;
}

DictionaryAttr serializeAnalogStructuralModel(MLIRContext *context,
                                              const AnalogStructuralModel &model) {
  auto records = [&](const std::vector<AnalogStructuralMatch> &input) {
    SmallVector<Attribute> results;
    for (const auto &report : input) {
      SmallVector<Attribute> pairs;
      for (const auto &[equation, unknown] : report.matched)
        pairs.push_back(
            DictionaryAttr::get(context, {NamedAttribute(StringAttr::get(context, "equation"),
                                                         StringAttr::get(context, equation)),
                                          NamedAttribute(StringAttr::get(context, "unknown"),
                                                         StringAttr::get(context, unknown))}));
      results.push_back(DictionaryAttr::get(
          context,
          {NamedAttribute(StringAttr::get(context, "component"),
                          StringAttr::get(context, report.component)),
           NamedAttribute(StringAttr::get(context, "analysis"),
                          StringAttr::get(context, report.analysis)),
           NamedAttribute(StringAttr::get(context, "equations"),
                          stringArray(context, report.equations)),
           NamedAttribute(StringAttr::get(context, "unknowns"),
                          stringArray(context, report.unknowns)),
           NamedAttribute(StringAttr::get(context, "matched"), ArrayAttr::get(context, pairs)),
           NamedAttribute(StringAttr::get(context, "unmatched_equations"),
                          stringArray(context, report.unmatchedEquations)),
           NamedAttribute(StringAttr::get(context, "unmatched_unknowns"),
                          stringArray(context, report.unmatchedUnknowns))}));
    }
    return ArrayAttr::get(context, results);
  };
  return DictionaryAttr::get(
      context,
      {NamedAttribute(StringAttr::get(context, "version"), StringAttr::get(context, "1")),
       NamedAttribute(StringAttr::get(context, "present"), BoolAttr::get(context, model.present)),
       NamedAttribute(StringAttr::get(context, "complete"), BoolAttr::get(context, model.complete)),
       NamedAttribute(StringAttr::get(context, "unsupported"),
                      stringArray(context, model.unsupported)),
       NamedAttribute(StringAttr::get(context, "components"), records(model.components)),
       NamedAttribute(StringAttr::get(context, "global"), records(model.global))});
}
} // namespace nodal
