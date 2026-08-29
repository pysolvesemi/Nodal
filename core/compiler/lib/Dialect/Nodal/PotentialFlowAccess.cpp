#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassRegistry.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NatureDiscipline.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"

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
  return "branch";
}

bool matchingTerminalPair(Value lhs0, Value lhs1, Value rhs0, Value rhs1) {
  return (lhs0 == rhs0 && lhs1 == rhs1) || (lhs0 == rhs1 && lhs1 == rhs0);
}

struct AccessGroup {
  std::string form;
  llvm::SmallVector<Value, 2> subject;
  Operation *branchOperation = nullptr;
  bool hasContribution = false;
  bool potential = false;
  bool flow = false;
  llvm::SmallVector<std::string, 4> provenance;
};

bool isImplicitBranchOperation(Operation *operation) {
  if (!isNamed(operation, "nodal.branch"))
    return false;
  llvm::StringRef declarationKind = textAttr(operation, "declaration_kind");
  llvm::StringRef name = textAttr(operation, "name");
  return name.empty() && (declarationKind.empty() || declarationKind == "implicit");
}

AccessGroup *findImplicitBranchGroup(llvm::MutableArrayRef<AccessGroup> groups, Value positive,
                                     Value negative) {
  for (AccessGroup &group : groups) {
    if (!isImplicitBranchOperation(group.branchOperation) ||
        group.branchOperation->getNumOperands() != 2)
      continue;
    if (matchingTerminalPair(group.branchOperation->getOperand(0),
                             group.branchOperation->getOperand(1), positive, negative))
      return &group;
  }
  return nullptr;
}

AccessGroup *findImplicitGroup(llvm::MutableArrayRef<AccessGroup> groups, llvm::StringRef form,
                               ValueRange subject) {
  for (AccessGroup &group : groups) {
    if (group.branchOperation || group.form != form || group.subject.size() != subject.size())
      continue;
    if (subject.size() == 1 && group.subject[0] == subject[0])
      return &group;
    if (subject.size() == 2 &&
        matchingTerminalPair(group.subject[0], group.subject[1], subject[0], subject[1]))
      return &group;
  }
  return nullptr;
}

void addAccessKind(AccessGroup &group, llvm::StringRef kind, Operation *operation) {
  if (kind == "potential")
    group.potential = true;
  else if (kind == "flow")
    group.flow = true;
  group.provenance.push_back(accessSourcePath(operation));
}

LogicalResult collectAccessGroups(Operation *module, llvm::SmallVectorImpl<AccessGroup> &groups) {
  if (!moduleBody(module))
    return fail(module, "NODAL-ACCESS-FORM-001",
                "access normalization requires one Nodal module body");

  module->walk([&](Operation *operation) {
    if (enclosingNodalModule(operation) != module || !isNamed(operation, "nodal.branch") ||
        operation->getNumResults() != 1)
      return;
    AccessGroup group;
    group.form = "branch";
    group.subject.push_back(operation->getResult(0));
    group.branchOperation = operation;
    group.provenance.push_back(branchSourcePath(operation));
    for (Operation *user : operation->getResult(0).getUsers()) {
      if (isNamed(user, "nodal.contribute") && user->getNumOperands() >= 1 &&
          user->getOperand(0) == operation->getResult(0)) {
        group.hasContribution = true;
      } else if (isNamed(user, "nodal.access") && user->getNumOperands() == 1 &&
                 user->getOperand(0) == operation->getResult(0)) {
        addAccessKind(group, textAttr(user, "kind"), user);
      }
    }
    groups.push_back(std::move(group));
  });

  module->walk([&](Operation *operation) {
    if (enclosingNodalModule(operation) != module || !isNamed(operation, "nodal.terminal_access"))
      return;
    const unsigned arity = operation->getNumOperands();
    if (arity != 1 && arity != 2)
      return;

    AccessGroup *group = nullptr;
    if (arity == 2)
      group = findImplicitBranchGroup(groups, operation->getOperand(0), operation->getOperand(1));
    if (!group) {
      llvm::StringRef form =
          arity == 1 ? llvm::StringRef("one-terminal") : llvm::StringRef("two-terminal");
      group = findImplicitGroup(groups, form, operation->getOperands());
      if (!group) {
        AccessGroup created;
        created.form = form.str();
        created.subject.append(operation->operand_begin(), operation->operand_end());
        groups.push_back(std::move(created));
        group = &groups.back();
      }
    }
    addAccessKind(*group, textAttr(operation, "kind"), operation);
  });

  for (AccessGroup &group : groups) {
    llvm::sort(group.provenance);
    group.provenance.erase(std::unique(group.provenance.begin(), group.provenance.end()),
                           group.provenance.end());
  }
  return success();
}

bool probeSubjectMatches(Operation *probe, const AccessGroup &group) {
  if (textAttr(probe, "form") != group.form || probe->getNumOperands() != group.subject.size())
    return false;
  if (group.subject.size() == 1)
    return probe->getOperand(0) == group.subject[0];
  return probe->getOperand(0) == group.subject[0] && probe->getOperand(1) == group.subject[1];
}

bool probeProvenanceMatches(Operation *probe, const AccessGroup &group) {
  auto provenance = probe->getAttrOfType<ArrayAttr>("provenance");
  const size_t expectedSize = group.provenance.empty() ? 1 : group.provenance.size();
  if (!provenance || provenance.size() != expectedSize)
    return false;
  if (group.provenance.empty()) {
    auto value = llvm::dyn_cast<StringAttr>(provenance[0]);
    return value && value.getValue() == group.form;
  }
  for (auto [index, expected] : llvm::enumerate(group.provenance)) {
    auto value = llvm::dyn_cast<StringAttr>(provenance[index]);
    if (!value || value.getValue() != expected)
      return false;
  }
  return true;
}

LogicalResult verifyProbeOperation(Operation *operation) {
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
    if (textAttr(operation, "kind") == "flow" &&
        operation->getOperand(0) == operation->getOperand(1))
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
    Operation *terminal = operation->getOperand(0).getDefiningOp();
    if (!isNamed(terminal, "nodal.terminal") ||
        enclosingNodalModule(terminal) != enclosingNodalModule(operation))
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
  if (values.empty())
    values.push_back(builder.getStringAttr(group.form));
  return builder.getArrayAttr(values);
}

void createProbe(Operation *module, const AccessGroup &group, llvm::StringRef kind) {
  Block *body = moduleBody(module);
  OpBuilder builder(module->getContext());
  builder.setInsertionPointToEnd(body);
  OperationState state(module->getLoc(), "nodal.probe");
  state.addOperands(group.subject);
  state.addAttribute("form", builder.getStringAttr(group.form));
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
  for (Operation *probe : existingProbes)
    probe->erase();

  llvm::SmallVector<AccessGroup, 8> groups;
  if (failed(collectAccessGroups(module, groups)))
    return failure();
  for (const AccessGroup &group : groups) {
    if (group.hasContribution || (!group.potential && !group.flow))
      continue;
    if (group.potential && group.flow)
      return fail(group.branchOperation ? group.branchOperation : module, "NODAL-PROBE-KIND-001",
                  "source-free branch cannot be both a potential and a flow probe");
    createProbe(module, group,
                group.potential ? llvm::StringRef("potential") : llvm::StringRef("flow"));
  }
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

  for (const AccessGroup &group : groups) {
    llvm::SmallVector<Operation *, 2> matching;
    for (Operation *probe : probes) {
      if (probeSubjectMatches(probe, group))
        matching.push_back(probe);
    }
    const bool needsProbe = !group.hasContribution && (group.potential != group.flow);
    if (group.potential && group.flow && !group.hasContribution)
      return fail(group.branchOperation ? group.branchOperation : module, "NODAL-PROBE-KIND-001",
                  "source-free branch cannot mix potential and flow access");
    if (!needsProbe && !matching.empty())
      return fail(matching.front(), "NODAL-PROBE-PROVENANCE-001",
                  "probe record has no matching source-free access group");
    if (needsProbe && matching.size() != 1)
      return fail(group.branchOperation ? group.branchOperation : module,
                  "NODAL-PROBE-PROVENANCE-001",
                  "source-free access group requires exactly one probe record");
    if (needsProbe) {
      llvm::StringRef expected =
          group.potential ? llvm::StringRef("potential") : llvm::StringRef("flow");
      if (textAttr(matching.front(), "kind") != expected)
        return fail(matching.front(), "NODAL-PROBE-KIND-001",
                    "probe record kind does not match its access group");
      if (!probeProvenanceMatches(matching.front(), group))
        return fail(matching.front(), "NODAL-PROBE-PROVENANCE-001",
                    "probe provenance does not match its source-free access group");
    }
  }

  for (Operation *probe : probes) {
    if (!llvm::any_of(groups,
                      [&](const AccessGroup &group) { return probeSubjectMatches(probe, group); }))
      return fail(probe, "NODAL-PROBE-PROVENANCE-001",
                  "probe record does not resolve a source access group");
  }
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
