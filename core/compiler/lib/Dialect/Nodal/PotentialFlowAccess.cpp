#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Dominance.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassRegistry.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"
#include "nodal/Dialect/Nodal/NatureDiscipline.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/ADT/Twine.h"
#include "llvm/Support/Casting.h"

#include <memory>
#include <string>

using namespace mlir;

namespace nodal {
namespace {

constexpr llvm::StringLiteral kGeneratedBy = "increment31-potential-flow-access";

bool isNamed(Operation *operation, llvm::StringRef name) {
  return operation && operation->getName().getStringRef() == name;
}

llvm::StringRef textAttr(Operation *operation, llvm::StringRef name) {
  if (auto value = operation->getAttrOfType<StringAttr>(name))
    return value.getValue();
  return {};
}

llvm::StringRef symbolName(Operation *operation) {
  if (auto value = operation->getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName()))
    return value.getValue();
  return {};
}

LogicalResult fail(Operation *operation, llvm::StringRef code, const llvm::Twine &message) {
  return emitMappedFailure(operation, code, message);
}

template <typename T>
FailureOr<T> failValue(Operation *operation, llvm::StringRef code, const llvm::Twine &message) {
  (void)fail(operation, code, message);
  return failure();
}

Operation *enclosingNodalModule(Operation *operation) {
  for (Operation *current = operation; current; current = current->getParentOp()) {
    if (isNamed(current, "nodal.module"))
      return current;
  }
  return nullptr;
}

Block *moduleBody(Operation *module) {
  if (!module || !isNamed(module, "nodal.module") || module->getNumRegions() != 1 ||
      module->getRegion(0).empty())
    return nullptr;
  return &module->getRegion(0).front();
}

bool isAccessOperation(Operation *operation) {
  return isNamed(operation, "nodal.access") || isNamed(operation, "nodal.terminal_access") ||
         isNamed(operation, "nodal.port_flow_access");
}

bool isCanonicalSourcePath(llvm::StringRef value) {
  return !value.empty() && value == value.trim() && llvm::all_of(value, [](char character) {
    const unsigned char byte = static_cast<unsigned char>(character);
    return byte >= 0x20 && byte != 0x7f;
  });
}

std::string accessSourcePath(Operation *operation) {
  if (llvm::StringRef value = textAttr(operation, "source_path"); isCanonicalSourcePath(value))
    return value.str();
  if (auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata")) {
    if (auto value = metadata.getAs<StringAttr>("source_path")) {
      if (isCanonicalSourcePath(value.getValue()))
        return value.getValue().str();
    }
    if (auto value = metadata.getAs<StringAttr>("semantic_path")) {
      if (isCanonicalSourcePath(value.getValue()))
        return value.getValue().str();
    }
  }
  return operation->getName().getStringRef().str();
}

std::string branchSourcePath(Operation *operation) {
  if (llvm::StringRef value = textAttr(operation, "source_path"); isCanonicalSourcePath(value))
    return value.str();
  if (auto name = operation->getAttrOfType<StringAttr>("name")) {
    if (isCanonicalSourcePath(name.getValue()))
      return name.getValue().str();
  }
  if (auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata")) {
    if (auto identity = metadata.getAs<StringAttr>("identity")) {
      if (isCanonicalSourcePath(identity.getValue()))
        return identity.getValue().str();
    }
  }
  if (auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata"))
    if (auto path = metadata.getAs<StringAttr>("semantic_path"))
      if (isCanonicalSourcePath(path.getValue()))
        return path.getValue().str();
  return "branch";
}

// Physical identity is derived at each actual source use. It is deliberately
// independent of the structural scope in which a derived record can be placed.
ConservativeAlias matchingTerminal(Value left, Operation *leftUse, Value right,
                                   Operation *rightUse) {
  if (!isNodeArraySelection(left.getDefiningOp()) && !isNodeArraySelection(right.getDefiningOp())) {
    // Preserve the scalar F31 branch identity policy. Conservative array
    // selections use the shared relation proof, including across occurrences.
    return left == right ? ConservativeAlias::Equal : ConservativeAlias::Distinct;
  }
  return compareConservativeEndpointOccurrences(left, leftUse, right, rightUse);
}

bool matchingTerminalPair(ValueRange left, Operation *leftUse, ValueRange right,
                          Operation *rightUse, LogicalResult &status) {
  if (left.size() != right.size())
    return false;
  auto equal = [](ConservativeAlias value) { return value == ConservativeAlias::Equal; };
  auto distinct = [](ConservativeAlias value) {
    return value == ConservativeAlias::Distinct || value == ConservativeAlias::Inert;
  };
  auto first = matchingTerminal(left[0], leftUse, right[0], rightUse);
  if (left.size() == 1) {
    if (!equal(first) && !distinct(first))
      status = failure();
    return equal(first);
  }
  auto second = matchingTerminal(left[1], leftUse, right[1], rightUse);
  auto reverseFirst = matchingTerminal(left[0], leftUse, right[1], rightUse);
  auto reverseSecond = matchingTerminal(left[1], leftUse, right[0], rightUse);
  if ((equal(first) && equal(second)) || (equal(reverseFirst) && equal(reverseSecond)))
    return true;
  // A proved-disjoint member excludes an orientation even if the other member
  // is unknown. Invalid endpoints never count as a distinct physical branch.
  if (first == ConservativeAlias::Invalid || second == ConservativeAlias::Invalid ||
      reverseFirst == ConservativeAlias::Invalid || reverseSecond == ConservativeAlias::Invalid ||
      (!distinct(first) && !distinct(second)) ||
      (!distinct(reverseFirst) && !distinct(reverseSecond)))
    status = failure();
  return false;
}

Operation *structuralScope(Operation *operation) {
  for (Operation *current = operation; current; current = current->getParentOp())
    if (isNamed(current, "nodal.module") || isNamed(current, "nodal.generate"))
      return current;
  return nullptr;
}

Block *structuralBody(Operation *scope) {
  if (!scope || (!isNamed(scope, "nodal.module") && !isNamed(scope, "nodal.generate")) ||
      scope->getNumRegions() != 1 || !llvm::hasSingleElement(scope->getRegion(0)))
    return nullptr;
  return &scope->getRegion(0).front();
}

// Query-local memoization of the existing generated-bound proof. No count or
// parameter algebra lives in the access owner.
struct ActiveSupport {
  llvm::DenseMap<Operation *, GeneratedScopeActivity> activity;

  FailureOr<GeneratedScopeActivity> get(Operation *scope) {
    auto found = activity.find(scope);
    if (found != activity.end())
      return found->second;
    auto result = analyzeGeneratedScopeActivity(scope);
    if (succeeded(result))
      activity.try_emplace(scope, *result);
    return result;
  }

  FailureOr<bool> empty(Operation *scope) {
    for (Operation *current = scope; current && !isNamed(current, "nodal.module");
         current = structuralScope(current->getParentOp())) {
      auto state = get(current);
      if (failed(state))
        return failure();
      if (*state == GeneratedScopeActivity::AlwaysEmpty)
        return true;
    }
    return false;
  }

  // Provider support exists whenever requested support exists. Below their
  // common ancestor only guaranteed-nonempty provider domains can discharge
  // this obligation. Correlated conditional domains remain unsupported.
  FailureOr<bool> covers(Operation *provider, Operation *requested) {
    for (Operation *current = provider; current;
         current = structuralScope(current->getParentOp())) {
      if (current == requested || current->isProperAncestor(requested))
        return true;
      if (isNamed(current, "nodal.module"))
        return false;
      auto state = get(current);
      if (failed(state))
        return failure();
      if (*state != GeneratedScopeActivity::AlwaysNonempty)
        return false;
    }
    return false;
  }
};

struct AccessOccurrence {
  Operation *source = nullptr;
  Operation *scope = nullptr;
  std::string form;
  llvm::SmallVector<Value, 2> subject;
  bool contribution = false;
};

struct AccessGroup {
  // Named branches are keyed by actual declaration. Implicit branch declarations
  // and terminal access use the same endpoint pair proof.
  Operation *namedBranch = nullptr;
  Operation *physicalUse = nullptr;
  llvm::SmallVector<Value, 2> physicalSubject;
  bool potential = false;
  bool flow = false;
  llvm::SmallVector<AccessOccurrence, 4> occurrences;
  llvm::SmallVector<AccessOccurrence, 2> placements;
  llvm::SmallVector<std::string, 4> provenance;
};

bool isImplicitBranchOperation(Operation *operation) {
  if (!isNamed(operation, "nodal.branch"))
    return false;
  llvm::StringRef declarationKind = textAttr(operation, "declaration_kind");
  return textAttr(operation, "name").empty() &&
         (declarationKind.empty() || declarationKind == "implicit");
}

bool subjectsVisibleAtEnd(ValueRange subjects, Operation *scope) {
  if (!structuralBody(scope))
    return false;
  for (Value subject : subjects) {
    Operation *definition = subject.getDefiningOp();
    if (!definition || enclosingNodalModule(definition) != enclosingNodalModule(scope))
      return false;
    Operation *position = scope;
    while (position && position->getBlock() != definition->getBlock())
      position = position->getParentOp();
    if (definition->getBlock() == structuralBody(scope))
      continue;
    if (!position || definition == position || !definition->isBeforeInBlock(position))
      return false;
  }
  return true;
}

LogicalResult collectAccessGroups(Operation *module, llvm::SmallVectorImpl<AccessGroup> &groups) {
  if (!moduleBody(module))
    return fail(module, "NODAL-ACCESS-FORM-001",
                "access normalization requires one Nodal module body");
  ActiveSupport support;
  LogicalResult result = success();
  module->walk<WalkOrder::PreOrder>([&](Operation *operation) {
    if (failed(result) || enclosingNodalModule(operation) != module)
      return;
    bool contribution = isNamed(operation, "nodal.contribute");
    bool branchAccess = isNamed(operation, "nodal.access");
    bool terminalAccess = isNamed(operation, "nodal.terminal_access");
    if (!contribution && !branchAccess && !terminalAccess)
      return;
    Operation *scope = structuralScope(operation);
    auto inactive = support.empty(scope);
    if (failed(inactive)) {
      result = failure();
      return;
    }
    if (*inactive)
      return;
    AccessOccurrence occurrence;
    occurrence.source = operation;
    occurrence.scope = scope;
    occurrence.contribution = contribution;
    Operation *branch = nullptr;
    llvm::SmallVector<Value, 2> physical;
    if (contribution || branchAccess) {
      if (operation->getNumOperands() == 0 ||
          !(branch = operation->getOperand(0).getDefiningOp()) ||
          !isNamed(branch, "nodal.branch") || branch->getNumOperands() != 2) {
        result = fail(operation, "NODAL-PROBE-SCOPE-001",
                      "access support requires an actual owned branch declaration");
        return;
      }
      occurrence.form = "branch";
      occurrence.subject.push_back(operation->getOperand(0));
      physical.append(branch->operand_begin(), branch->operand_end());
    } else {
      occurrence.form = operation->getNumOperands() == 1 ? "one-terminal" : "two-terminal";
      occurrence.subject.append(operation->operand_begin(), operation->operand_end());
      physical = occurrence.subject;
    }
    if (!subjectsVisibleAtEnd(occurrence.subject, scope)) {
      result = fail(operation, "NODAL-PROBE-SCOPE-001",
                    "access subject is not visible in its structural support scope");
      return;
    }
    Operation *named = branch && !isImplicitBranchOperation(branch) ? branch : nullptr;
    AccessGroup *group = nullptr;
    for (AccessGroup &candidate : groups) {
      if (named || candidate.namedBranch) {
        if (named && candidate.namedBranch == named) {
          group = &candidate;
          break;
        }
        continue;
      }
      LogicalResult comparison = success();
      if (matchingTerminalPair(candidate.physicalSubject, candidate.physicalUse, physical,
                               operation, comparison)) {
        group = &candidate;
        break;
      }
      if (failed(comparison)) {
        result = fail(operation, "NODAL-SHAPE-043-009",
                      "access grouping has unproved physical alias identity");
        return;
      }
    }
    if (!group) {
      AccessGroup created;
      created.namedBranch = named;
      created.physicalUse = operation;
      created.physicalSubject = std::move(physical);
      groups.push_back(std::move(created));
      group = &groups.back();
    }
    if (branch)
      group->provenance.push_back(branchSourcePath(branch));
    if (!contribution) {
      group->potential |= textAttr(operation, "kind") == "potential";
      group->flow |= textAttr(operation, "kind") == "flow";
      group->provenance.push_back(accessSourcePath(operation));
    }
    group->occurrences.push_back(std::move(occurrence));
  });
  if (failed(result))
    return failure();

  for (AccessGroup &group : groups) {
    llvm::sort(group.provenance);
    group.provenance.erase(std::unique(group.provenance.begin(), group.provenance.end()),
                           group.provenance.end());
    bool sourced = llvm::any_of(group.occurrences,
                                [](const AccessOccurrence &use) { return use.contribution; });
    if (sourced) {
      for (const AccessOccurrence &read : group.occurrences) {
        if (read.contribution)
          continue;
        bool covered = false;
        for (const AccessOccurrence &source : group.occurrences) {
          if (!source.contribution)
            continue;
          auto contains = support.covers(source.scope, read.scope);
          if (failed(contains))
            return failure();
          covered |= *contains;
        }
        if (!covered)
          return fail(read.source, "NODAL-PROBE-SCOPE-001",
                      "contribution support does not provably cover this physical access");
      }
      continue;
    }
    if (group.potential && group.flow) {
      for (const AccessOccurrence &left : group.occurrences)
        for (const AccessOccurrence &right : group.occurrences) {
          if (textAttr(left.source, "kind") == textAttr(right.source, "kind"))
            continue;
          auto forward = support.covers(left.scope, right.scope);
          auto reverse = support.covers(right.scope, left.scope);
          if (failed(forward) || failed(reverse))
            return failure();
          if (*forward || *reverse)
            return fail(group.physicalUse, "NODAL-PROBE-KIND-001",
                        "source-free branch cannot be both a potential and a flow probe");
        }
      return fail(group.physicalUse, "NODAL-PROBE-SCOPE-001",
                  "mixed probe kinds have unproved conditional support overlap");
    }
    // Keep the first actual source among equivalent supports. An ancestor or
    // guaranteed-active sibling can cover another source without relocating its
    // SSA subject or manufacturing unconditional support in the module.
    for (const AccessOccurrence &read : group.occurrences) {
      bool redundant = false;
      for (const AccessOccurrence &placement : group.placements) {
        auto contains = support.covers(placement.scope, read.scope);
        if (failed(contains))
          return failure();
        redundant |= *contains;
      }
      if (redundant)
        continue;
      for (auto iterator = group.placements.begin(); iterator != group.placements.end();) {
        auto contains = support.covers(read.scope, iterator->scope);
        if (failed(contains))
          return failure();
        if (*contains)
          iterator = group.placements.erase(iterator);
        else
          ++iterator;
      }
      group.placements.push_back(read);
    }
  }
  return success();
}

bool probeSubjectMatches(Operation *probe, const AccessOccurrence &placement) {
  return probe->getParentOp() == placement.scope && textAttr(probe, "form") == placement.form &&
         llvm::equal(probe->getOperands(), placement.subject);
}

bool probeProvenanceMatches(Operation *probe, const AccessGroup &group) {
  auto provenance = probe->getAttrOfType<ArrayAttr>("provenance");
  const size_t expectedSize = group.provenance.size();
  if (!provenance || provenance.size() != expectedSize)
    return false;
  for (auto [index, expected] : llvm::enumerate(group.provenance)) {
    auto value = llvm::dyn_cast<StringAttr>(provenance[index]);
    if (!value || value.getValue() != expected)
      return false;
  }
  return true;
}

LogicalResult verifyProbeOperation(Operation *operation) {
  Operation *scope = operation->getParentOp();
  const bool legacy = isNamed(scope, "nodal.generate") && !scope->hasAttr("region_id") &&
                      !scope->hasAttr("induction_path") && structuralBody(scope) &&
                      structuralBody(scope)->getNumArguments() == 0;
  if (legacy) {
    if (operation->hasAttr("generated_owner") || operation->hasAttr("generated_induction"))
      return fail(operation, "NODAL-ITERATION-043-004",
                  "legacy uncaptured probe cannot claim generated identities");
  } else if (failed(verifyGeneratedStructuralOwnership(operation))) {
    return failure();
  }
  Operation *module = enclosingNodalModule(operation);
  if (!module || !structuralBody(operation->getParentOp()))
    return fail(operation, "NODAL-PROBE-SCOPE-001",
                "probe requires a direct module or generated structural support");
  DominanceInfo dominance(module);
  for (Value subject : operation->getOperands()) {
    Operation *definition = subject.getDefiningOp();
    if (!definition || enclosingNodalModule(definition) != module ||
        !dominance.dominates(subject, operation))
      return fail(operation, "NODAL-PROBE-SCOPE-001",
                  "probe subject does not dominate its actual structural placement");
  }
  llvm::StringRef form = textAttr(operation, "form");
  llvm::StringRef kind = textAttr(operation, "kind");
  llvm::StringRef intent = textAttr(operation, "constraint_intent");
  if (form != "branch" && form != "one-terminal" && form != "two-terminal")
    return fail(operation, "NODAL-PROBE-KIND-001",
                "probe form must be branch, one-terminal, or two-terminal");
  if (kind != "potential" && kind != "flow")
    return fail(operation, "NODAL-PROBE-KIND-001", "probe kind must be potential or flow");
  if ((kind == "potential" && intent != "zero-flow") ||
      (kind == "flow" && intent != "zero-potential"))
    return fail(operation, "NODAL-PROBE-KIND-001",
                "probe kind and zero-constraint intent disagree");

  const unsigned expectedArity = form == "two-terminal" ? 2 : 1;
  if (operation->getNumOperands() != expectedArity)
    return fail(operation, "NODAL-PROBE-KIND-001", "probe subject arity does not match its form");
  if (form == "branch") {
    if (!llvm::isa<BranchType>(operation->getOperand(0).getType()))
      return fail(operation, "NODAL-PROBE-KIND-001", "branch probe requires one branch subject");
  } else {
    for (Value operand : operation->getOperands()) {
      if (!llvm::isa<TerminalType>(operand.getType()))
        return fail(operation, "NODAL-PROBE-KIND-001",
                    "terminal probe subjects require terminal types");
    }
  }

  auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata");
  auto compilerOwned = metadata ? metadata.getAs<BoolAttr>("compiler_owned") : BoolAttr();
  auto generatedBy = metadata ? metadata.getAs<StringAttr>("generated_by") : StringAttr();
  if (!compilerOwned || !compilerOwned.getValue() || !generatedBy ||
      generatedBy.getValue() != kGeneratedBy)
    return fail(operation, "NODAL-PROBE-PROVENANCE-001",
                "probe records are compiler-owned Increment 31 artifacts");

  auto provenance = operation->getAttrOfType<ArrayAttr>("provenance");
  if (!provenance || provenance.empty() || !llvm::all_of(provenance, [](Attribute value) {
        auto text = llvm::dyn_cast<StringAttr>(value);
        return text && isCanonicalSourcePath(text.getValue());
      }))
    return fail(operation, "NODAL-PROBE-PROVENANCE-001",
                "probe requires non-empty canonical source provenance");
  return success();
}

FailureOr<std::string> accessDiscipline(Operation *operation) {
  llvm::StringRef name = operation->getName().getStringRef();
  if (name == "nodal.access") {
    if (operation->getNumOperands() != 1)
      return failValue<std::string>(operation, "NODAL-ACCESS-FORM-001",
                                    "branch access requires exactly one operand");
    auto branch = llvm::dyn_cast<BranchType>(operation->getOperand(0).getType());
    if (!branch || branch.getDiscipline().trim().empty())
      return failValue<std::string>(operation, "NODAL-ACCESS-FORM-001",
                                    "branch access requires a disciplined branch");
    return branch.getDiscipline().str();
  }

  if (name == "nodal.terminal_access") {
    if (operation->getNumOperands() != 1 && operation->getNumOperands() != 2)
      return failValue<std::string>(operation, "NODAL-ACCESS-FORM-001",
                                    "terminal access requires one or two terminals");
  } else if (name == "nodal.port_flow_access") {
    if (operation->getNumOperands() != 1)
      return failValue<std::string>(operation, "NODAL-ACCESS-PORT-001",
                                    "port-flow access requires exactly one terminal");
  } else {
    return failure();
  }

  auto first = llvm::dyn_cast<TerminalType>(operation->getOperand(0).getType());
  if (!first || first.getDiscipline().trim().empty())
    return failValue<std::string>(operation, "NODAL-ACCESS-FORM-001",
                                  "terminal access requires disciplined terminal operands");
  if (operation->getNumOperands() == 2) {
    auto second = llvm::dyn_cast<TerminalType>(operation->getOperand(1).getType());
    if (!second)
      return failValue<std::string>(operation, "NODAL-ACCESS-FORM-001",
                                    "terminal access requires disciplined terminal operands");
    auto lhs = FlatSymbolRefAttr::get(operation->getContext(), first.getDiscipline());
    auto rhs = FlatSymbolRefAttr::get(operation->getContext(), second.getDiscipline());
    FailureOr<bool> compatible = areDisciplinesCompatible(operation, lhs, rhs);
    if (failed(compatible) || !*compatible)
      return failValue<std::string>(operation, "NODAL-ACCESS-DISCIPLINE-001",
                                    "terminal access operands use incompatible disciplines");
    ConservativeAlias identity = operation->getOperand(0) == operation->getOperand(1)
                                     ? ConservativeAlias::Equal
                                     : ConservativeAlias::Distinct;
    if (isNodeArraySelection(operation->getOperand(0).getDefiningOp()) ||
        isNodeArraySelection(operation->getOperand(1).getDefiningOp())) {
      identity = compareConservativeEndpoints(operation->getOperand(0), operation->getOperand(1),
                                              operation);
      if (identity != ConservativeAlias::Equal && identity != ConservativeAlias::Distinct &&
          identity != ConservativeAlias::Inert)
        return failValue<std::string>(operation, "NODAL-SHAPE-043-009",
                                      "terminal access has unproved physical alias identity");
    }
    if (textAttr(operation, "kind") == "flow" && identity == ConservativeAlias::Equal)
      return failValue<std::string>(operation, "NODAL-ACCESS-FORM-001",
                                    "oriented flow access requires distinct terminals");
  }
  return first.getDiscipline().str();
}

LogicalResult verifyAccessOperation(Operation *operation) {
  const llvm::StringRef name = operation->getName().getStringRef();
  const llvm::StringRef kind = textAttr(operation, "kind");
  if (kind != "potential" && kind != "flow")
    return fail(operation, "NODAL-ACCESS-FUNCTION-001", "access kind must be potential or flow");

  FailureOr<std::string> discipline = accessDiscipline(operation);
  if (failed(discipline))
    return failure();

  auto function = operation->getAttrOfType<StringAttr>("function");
  const bool legacyBranchAccess = name == "nodal.access" && !function;
  if (legacyBranchAccess)
    return verifyAnalogNumericOperation(operation);
  if (!function || function.getValue().trim().empty())
    return fail(operation, "NODAL-ACCESS-FUNCTION-001",
                "new typed access requires an authored function identity");

  if (name == "nodal.port_flow_access") {
    if (kind != "flow")
      return fail(operation, "NODAL-ACCESS-PORT-001", "angle-delimited port access is flow-only");
    if (failed(resolveConservativePortIncidence(operation->getOperand(0), operation)))
      return fail(operation, "NODAL-ACCESS-PORT-001",
                  "port-flow access requires a local boundary terminal");
  }

  FailureOr<ResolvedAccessNature> resolved =
      resolvePotentialFlowAccessNature(operation, *discipline, kind);
  if (failed(resolved))
    return failure();
  if (function.getValue() != kind && function.getValue() != resolved->accessFunction)
    return fail(operation, "NODAL-ACCESS-FUNCTION-001",
                llvm::Twine("function '") + function.getValue() +
                    "' does not name the semantic kind or canonical nature access");

  if (operation->getNumResults() != 1)
    return fail(operation, "NODAL-ACCESS-FORM-001", "access operation requires exactly one result");
  auto quantity = llvm::dyn_cast<QuantityType>(operation->getResult(0).getType());
  if (!quantity || quantity.getKind() != "real" ||
      quantity.getDimension() != llvm::StringRef(resolved->dimension))
    return fail(operation, "NODAL-ACCESS-DIMENSION-001",
                "access result must be a real quantity with the canonical nature dimension");

  if (operation->hasAttr("nodal.folded") || operation->hasAttr("nodal.folded_value"))
    return fail(operation, "NODAL-ACCESS-FORM-001",
                "potential and flow access values are dynamic and cannot be folded");

  if (name == "nodal.terminal_access") {
    auto reference = operation->getAttrOfType<StringAttr>("reference_identity");
    if (operation->getNumOperands() == 1) {
      std::string expected = "global::" + resolved->canonicalDiscipline;
      if (reference && reference.getValue() != expected)
        return fail(operation, "NODAL-ACCESS-REFERENCE-001",
                    "one-terminal access carries the wrong global reference identity");
    } else if (reference) {
      return fail(operation, "NODAL-ACCESS-FORM-001",
                  "two-terminal access must not carry a global reference identity");
    }
  }
  return success();
}

DictionaryAttr probeMetadata(Builder &builder) {
  return builder.getDictionaryAttr({
      builder.getNamedAttr("compiler_owned", builder.getBoolAttr(true)),
      builder.getNamedAttr("generated_by", builder.getStringAttr(kGeneratedBy)),
  });
}

ArrayAttr probeProvenance(Builder &builder, const AccessGroup &group) {
  llvm::SmallVector<Attribute, 4> values;
  for (const std::string &value : group.provenance)
    values.push_back(builder.getStringAttr(value));
  return builder.getArrayAttr(values);
}

void createProbe(const AccessGroup &group, const AccessOccurrence &placement,
                 llvm::StringRef kind) {
  OpBuilder builder(placement.scope->getContext());
  builder.setInsertionPointToEnd(structuralBody(placement.scope));
  OperationState state(placement.source->getLoc(), "nodal.probe");
  state.addOperands(placement.subject);
  state.addAttribute("form", builder.getStringAttr(placement.form));
  if (isNamed(placement.scope, "nodal.generate")) {
    if (Attribute owner = placement.scope->getAttr("region_id"))
      state.addAttribute("generated_owner", owner);
    if (Attribute induction = placement.scope->getAttr("induction_path"))
      state.addAttribute("generated_induction", induction);
  }
  state.addAttribute("kind", builder.getStringAttr(kind));
  state.addAttribute("constraint_intent",
                     builder.getStringAttr(kind == "potential" ? "zero-flow" : "zero-potential"));
  state.addAttribute("provenance", probeProvenance(builder, group));
  state.addAttribute("metadata", probeMetadata(builder));
  builder.create(state);
}

LogicalResult verifyNormalizedModule(Operation *module);

LogicalResult normalizeModule(Operation *module) {
  LogicalResult result = success();
  module->walk([&](Operation *operation) {
    if (failed(result) || enclosingNodalModule(operation) != module)
      return;
    if (isAccessOperation(operation))
      result = verifyAccessOperation(operation);
  });
  if (failed(result))
    return failure();

  module->walk([&](Operation *operation) {
    if (failed(result) || enclosingNodalModule(operation) != module ||
        !isNamed(operation, "nodal.terminal_access") || operation->getNumOperands() != 1)
      return;
    FailureOr<std::string> discipline = accessDiscipline(operation);
    if (failed(discipline)) {
      result = failure();
      return;
    }
    FailureOr<ResolvedAccessNature> resolved =
        resolvePotentialFlowAccessNature(operation, *discipline, textAttr(operation, "kind"));
    if (failed(resolved)) {
      result = failure();
      return;
    }
    std::string expected = "global::" + resolved->canonicalDiscipline;
    auto reference = operation->getAttrOfType<StringAttr>("reference_identity");
    if (!reference)
      operation->setAttr("reference_identity", StringAttr::get(operation->getContext(), expected));
    else if (reference.getValue() != expected)
      result = fail(operation, "NODAL-ACCESS-REFERENCE-001",
                    "one-terminal access carries the wrong global reference identity");
  });
  if (failed(result))
    return failure();

  llvm::SmallVector<Operation *, 8> existingProbes;
  module->walk([&](Operation *operation) {
    if (enclosingNodalModule(operation) == module && isNamed(operation, "nodal.probe"))
      existingProbes.push_back(operation);
  });
  for (Operation *probe : existingProbes) {
    if (failed(verifyProbeOperation(probe)))
      return failure();
  }
  if (!existingProbes.empty() && failed(verifyNormalizedModule(module)))
    return failure();
  llvm::SmallVector<AccessGroup, 8> groups;
  if (failed(collectAccessGroups(module, groups)))
    return failure();
  // Complete derivation precedes record mutation, including support failures.
  for (Operation *probe : existingProbes)
    probe->erase();
  for (const AccessGroup &group : groups)
    for (const AccessOccurrence &placement : group.placements)
      createProbe(group, placement,
                  group.potential ? llvm::StringRef("potential") : llvm::StringRef("flow"));
  return success();
}

LogicalResult verifyNormalizedModule(Operation *module) {
  LogicalResult result = success();
  module->walk([&](Operation *operation) {
    if (failed(result) || enclosingNodalModule(operation) != module)
      return;
    if (isAccessOperation(operation) || isNamed(operation, "nodal.probe"))
      result = verifyPotentialFlowAccessOperation(operation);
  });
  if (failed(result))
    return failure();

  llvm::SmallVector<AccessGroup, 8> groups;
  if (failed(collectAccessGroups(module, groups)))
    return failure();

  llvm::SmallVector<Operation *, 8> probes;
  module->walk([&](Operation *operation) {
    if (enclosingNodalModule(operation) == module && isNamed(operation, "nodal.probe"))
      probes.push_back(operation);
  });

  llvm::SmallVector<Operation *, 8> matched;
  for (const AccessGroup &group : groups) {
    for (const AccessOccurrence &placement : group.placements) {
      llvm::SmallVector<Operation *, 2> matching;
      for (Operation *probe : probes)
        if (probeSubjectMatches(probe, placement))
          matching.push_back(probe);
      if (matching.size() != 1)
        return fail(placement.source, "NODAL-PROBE-PROVENANCE-001",
                    "source-free support occurrence requires exactly one placed probe record");
      Operation *probe = matching.front();
      llvm::StringRef expected = group.potential ? "potential" : "flow";
      if (textAttr(probe, "kind") != expected)
        return fail(probe, "NODAL-PROBE-KIND-001",
                    "probe record kind does not match its access group");
      if (!probeProvenanceMatches(probe, group))
        return fail(probe, "NODAL-PROBE-PROVENANCE-001",
                    "probe provenance does not match its source-free access group");
      matched.push_back(probe);
    }
  }
  for (Operation *probe : probes)
    if (!llvm::is_contained(matched, probe))
      return fail(probe, "NODAL-PROBE-PROVENANCE-001",
                  "probe record does not resolve a source support occurrence");
  return success();
}

class NormalizePotentialFlowAccessPass final
    : public PassWrapper<NormalizePotentialFlowAccessPass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(NormalizePotentialFlowAccessPass)

  llvm::StringRef getArgument() const final { return "nodal-normalize-potential-flow-access"; }

  llvm::StringRef getDescription() const final {
    return "Resolve typed potential/flow access and materialize probe intent";
  }

  void runOnOperation() final {
    if (failed(normalizePotentialFlowAccess(getOperation())))
      signalPassFailure();
  }
};

static PassRegistration<NormalizePotentialFlowAccessPass> registerNormalizePotentialFlowAccessPass;

} // namespace

std::unique_ptr<Pass> createNormalizePotentialFlowAccessPass() {
  return std::make_unique<NormalizePotentialFlowAccessPass>();
}

FailureOr<ResolvedAccessNature> resolvePotentialFlowAccessNature(Operation *scope,
                                                                 llvm::StringRef discipline,
                                                                 llvm::StringRef kind) {
  if (!scope)
    return failure();
  if (discipline.trim().empty())
    return failValue<ResolvedAccessNature>(scope, "NODAL-ACCESS-DISCIPLINE-001",
                                           "access discipline identity is empty");
  if (kind != "potential" && kind != "flow")
    return failValue<ResolvedAccessNature>(scope, "NODAL-ACCESS-FUNCTION-001",
                                           "access kind must be potential or flow");

  auto disciplineReference = FlatSymbolRefAttr::get(scope->getContext(), discipline);
  FailureOr<Operation *> disciplineDeclaration =
      resolveDisciplineDeclaration(scope, disciplineReference);
  if (failed(disciplineDeclaration))
    return failValue<ResolvedAccessNature>(scope, "NODAL-ACCESS-DISCIPLINE-001",
                                           llvm::Twine("discipline '") + discipline +
                                               "' does not resolve canonically");
  auto domain = (*disciplineDeclaration)->getAttrOfType<StringAttr>("domain");
  if (!domain || domain.getValue() != "continuous")
    return failValue<ResolvedAccessNature>(
        scope, "NODAL-ACCESS-DISCIPLINE-001",
        "potential/flow access requires a continuous discipline");

  auto natureReference = (*disciplineDeclaration)->getAttrOfType<FlatSymbolRefAttr>(kind);
  if (!natureReference)
    return failValue<ResolvedAccessNature>(scope, "NODAL-ACCESS-NATURE-001",
                                           llvm::Twine("discipline has no ") + kind + " nature");
  FailureOr<Operation *> natureDeclaration =
      resolveNatureDeclaration(*disciplineDeclaration, natureReference);
  if (failed(natureDeclaration))
    return failValue<ResolvedAccessNature>(scope, "NODAL-ACCESS-NATURE-001",
                                           llvm::Twine(kind) +
                                               " nature does not resolve canonically");

  auto access = (*natureDeclaration)->getAttrOfType<StringAttr>("access");
  if (!access || access.getValue().trim().empty())
    return failValue<ResolvedAccessNature>(scope, "NODAL-ACCESS-NATURE-001",
                                           "resolved nature has no canonical access function");
  auto dimension = (*natureDeclaration)->getAttrOfType<StringAttr>("dimension");
  if (!dimension || !isCanonicalDimensionSignature(dimension.getValue()))
    return failValue<ResolvedAccessNature>(scope, "NODAL-ACCESS-DIMENSION-001",
                                           "resolved nature has no canonical access dimension");

  llvm::StringRef disciplineName = symbolName(*disciplineDeclaration);
  llvm::StringRef natureName = symbolName(*natureDeclaration);
  if (disciplineName.empty() || natureName.empty())
    return failValue<ResolvedAccessNature>(scope, "NODAL-ACCESS-NATURE-001",
                                           "resolved discipline or nature has no canonical symbol");

  ResolvedAccessNature result;
  result.discipline = *disciplineDeclaration;
  result.nature = *natureDeclaration;
  result.canonicalDiscipline = disciplineName.str();
  result.canonicalNature = natureName.str();
  result.accessFunction = access.getValue().str();
  result.dimension = dimension.getValue().str();
  return result;
}

LogicalResult verifyPotentialFlowAccessOperation(Operation *operation) {
  if (!operation)
    return failure();
  if (isNamed(operation, "nodal.probe"))
    return verifyProbeOperation(operation);
  if (isAccessOperation(operation))
    return verifyAccessOperation(operation);
  return fail(operation, "NODAL-ACCESS-FORM-001",
              "operation is not a potential/flow access surface");
}

LogicalResult normalizePotentialFlowAccess(mlir::ModuleOp module) {
  if (failed(normalizeNodeArraySelections(module)))
    return failure();
  llvm::SmallVector<Operation *, 8> modules;
  module.walk([&](Operation *operation) {
    if (isNamed(operation, "nodal.module"))
      modules.push_back(operation);
  });
  for (Operation *definition : modules) {
    if (failed(normalizeModule(definition)) || failed(verifyNormalizedModule(definition)))
      return failure();
  }
  return success();
}

LogicalResult verifyPotentialFlowAccessModel(mlir::ModuleOp module) {
  LogicalResult result = success();
  module.walk([&](Operation *operation) {
    if (failed(result) || !isAccessOperation(operation))
      return;
    result = verifyAccessOperation(operation);
  });
  if (failed(result))
    return failure();

  llvm::SmallVector<Operation *, 8> modules;
  module.walk([&](Operation *operation) {
    if (isNamed(operation, "nodal.module"))
      modules.push_back(operation);
  });
  for (Operation *definition : modules) {
    if (failed(verifyNormalizedModule(definition)))
      return failure();
  }
  return success();
}

} // namespace nodal
