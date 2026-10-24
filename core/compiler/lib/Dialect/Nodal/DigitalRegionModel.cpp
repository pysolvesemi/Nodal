#include "nodal/Dialect/Nodal/DigitalRegionModel.h"

#include "nodal/Dialect/Nodal/CrossingModel.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/EnumSwitchModel.h"
#include "nodal/Dialect/Nodal/FlatFSMModel.h"
#include "nodal/Dialect/Nodal/FsmCompositionModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Dialect/Nodal/StructuralIfModel.h"
using namespace mlir;
namespace nodal {
namespace {
bool named(Operation *op, StringRef name) { return op && op->getName().getStringRef() == name; }
StringRef text(Operation *op, StringRef key) {
  auto a = op ? op->getAttrOfType<StringAttr>(key) : StringAttr();
  return a ? a.getValue() : StringRef();
}
StringRef path(Operation *op) {
  auto m = op ? op->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
  auto a = m ? m.getAs<StringAttr>("semantic_path") : StringAttr();
  return a ? a.getValue() : StringRef();
}
Operation *owner(Operation *op) {
  return llvm::isa_and_nonnull<ModuleOp>(op) ? op : op ? op->getParentOfType<ModuleOp>() : nullptr;
}
} // namespace
bool isDigitalRegionOwner(Operation *op) {
  return named(op, "nodal.collection") || isBoundedDigitalLoop(op) ||
         named(op, "nodal.structural_if") || named(op, "nodal.behavioral_if") ||
         named(op, "nodal.flat_fsm") || named(op, "nodal.enum_switch") ||
         (named(op, "nodal.generate") && op->getNumRegions() == 1 &&
          op->getRegion(0).hasOneBlock() && op->getNumOperands() == 3 &&
          op->getRegion(0).front().getNumArguments() == 1);
}
namespace {
bool spendRegionWork(unsigned *remaining, uint64_t amount = 1) {
  if (!remaining)
    return true;
  if (amount > *remaining) {
    *remaining = 0;
    return false;
  }
  *remaining -= amount;
  return true;
}
// Raw header owners inspect their actual operand/argument signatures and lexical
// ancestry. Charge those scans before delegation, never a copied capture map.
bool chargeRegionHeader(Block *body, unsigned *remaining) {
  if (!spendRegionWork(remaining) || !body || !body->getParentOp())
    return false;
  if (!remaining)
    return true;
  Operation *operation = body->getParentOp();
  for (Operation *scope = operation; scope && !isa<ModuleOp>(scope); scope = scope->getParentOp())
    if (!spendRegionWork(remaining))
      return false;
  if (!spendRegionWork(remaining, operation->getNumOperands()))
    return false;
  for (Region &region : operation->getRegions()) {
    if (!spendRegionWork(remaining))
      return false;
    for (Block &block : region)
      if (!spendRegionWork(remaining, 1 + block.getNumArguments()))
        return false;
  }
  return true;
}
} // namespace
static FailureOr<DigitalRegionHeader> inspectDigitalRegionImpl(Block *body, unsigned *remaining) {
  if (!chargeRegionHeader(body, remaining))
    return failure();

  if (!body || !body->getParent())
    return failure();
  Operation *op = body->getParentOp();
  Operation *module = owner(op);
  if (!module)
    return failure();
  if (named(op, "nodal.collection") && succeeded(inspectDigitalCollectionRegion(body)))
    return DigitalRegionHeader{op, module, body, DigitalRegionKind::Collection, 0, true};
  if (isBoundedDigitalLoop(op)) {
    auto h = inspectDigitalLoopHeader(op);
    if (succeeded(h) && h->body == body)
      return DigitalRegionHeader{op, module, body, DigitalRegionKind::BoundedLoop, 0, true};
  }
  if (named(op, "nodal.flat_fsm")) {
    auto h = inspectFlatFSMHeader(op);
    if (succeeded(h))
      for (unsigned i = 0; i < h->bodies.size(); ++i)
        if (h->bodies[i] == body)
          return DigitalRegionHeader{op, module, body, DigitalRegionKind::FlatFSMAction, i, true};
  }
  if (named(op, "nodal.enum_switch")) {
    auto h = inspectEnumSwitchHeader(op);
    if (succeeded(h))
      for (unsigned i = 0; i < h->bodies.size(); ++i)
        if (h->bodies[i] == body)
          return DigitalRegionHeader{op, module, body, DigitalRegionKind::EnumSwitchArm, i, true};
  }
  if (named(op, "nodal.behavioral_if")) {
    auto h = inspectBehavioralIfHeader(op);
    if (succeeded(h))
      for (unsigned arm = 0; arm < 2; ++arm)
        if (h->bodies[arm] == body)
          return DigitalRegionHeader{op, module, body, DigitalRegionKind::BehavioralArm, arm, true};
  }
  if (named(op, "nodal.structural_if")) {
    auto h = inspectStructuralIfHeader(op);
    if (succeeded(h))
      for (unsigned arm = 0; arm < 2; ++arm)
        if (h->bodies[arm] == body)
          return DigitalRegionHeader{op, module, body, DigitalRegionKind::StructuralArm, arm, true};
  }
  if (named(op, "nodal.generate") && op->getNumRegions() == 1 && op->getRegion(0).hasOneBlock() &&
      &op->getRegion(0).front() == body && op->getNumOperands() == 3 &&
      body->getNumArguments() == 1 && body->getArgument(0).getType().isSignlessInteger(64) &&
      !text(op, "region_id").empty() && !text(op, "induction_path").empty())
    return DigitalRegionHeader{op, module, body, DigitalRegionKind::Generate, 0, false};
  return failure();
}
static LogicalResult verifyDigitalValueScopeImpl(Value value, Operation *use, unsigned *remaining) {
  if (!spendRegionWork(remaining))
    return failure();
  if (!value || !use || !use->getBlock())
    return failure();
  Operation *module = owner(use);
  if (!module)
    return failure();
  if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
    auto h = inspectDigitalRegionImpl(argument.getOwner(), remaining);
    if (failed(h))
      return failure();
    Operation *visible = use;
    unsigned depth = 0;
    while (visible && visible->getBlock() != argument.getOwner()) {
      if (!spendRegionWork(remaining) || ++depth > 128)
        return failure();
      Operation *parent = visible->getParentOp();
      if (!parent || parent == module)
        return failure();
      auto region = inspectDigitalRegionImpl(visible->getBlock(), remaining);
      if (failed(region) || region->isolated)
        return failure();
      visible = parent;
    }
    return success(visible != nullptr);
  }
  Operation *definition = value.getDefiningOp();
  if (!definition || owner(definition) != module || definition == use)
    return failure();
  Operation *visible = use;
  unsigned depth = 0;
  while (visible && visible->getBlock() != definition->getBlock()) {
    if (!spendRegionWork(remaining) || ++depth > 128)
      return failure();
    Operation *parent = visible->getParentOp();
    if (!parent || parent == module)
      return failure();
    if (isDigitalRegionOwner(parent)) {
      auto region = inspectDigitalRegionImpl(visible->getBlock(), remaining);
      if (failed(region) || region->isolated)
        return failure();
    }
    visible = parent;
  }
  return success(visible && definition != visible && definition->isBeforeInBlock(visible));
}
static FailureOr<Value> digitalRegionCaptureSourceImpl(BlockArgument arg, unsigned *remaining) {
  auto h = inspectDigitalRegionImpl(arg.getOwner(), remaining);
  if (failed(h))
    return failure();
  // Each delegated capture owner independently rechecks that same signature.
  if (!chargeRegionHeader(arg.getOwner(), remaining))
    return failure();
  switch (h->kind) {
  case DigitalRegionKind::Collection:
    return collectionCaptureSource(arg);
  case DigitalRegionKind::BoundedLoop:
    return digitalLoopCaptureSource(arg);
  case DigitalRegionKind::StructuralArm:
    return structuralIfCaptureSource(arg);
  case DigitalRegionKind::BehavioralArm:
    return behavioralCaptureSource(arg);
  case DigitalRegionKind::FlatFSMAction:
    return flatFSMCaptureSource(arg);
  case DigitalRegionKind::EnumSwitchArm:
    return enumSwitchCaptureSource(arg);
  case DigitalRegionKind::Generate:
    return Value();
  }
  return failure();
}
FailureOr<DigitalRegionHeader> inspectDigitalRegion(Block *body) {
  return inspectDigitalRegionImpl(body, nullptr);
}
FailureOr<DigitalRegionHeader> inspectDigitalRegion(Block *body, unsigned &remaining) {
  return inspectDigitalRegionImpl(body, &remaining);
}
LogicalResult verifyDigitalValueScope(Value value, Operation *use,
                                      const ParameterIntegerEnvironment *) {
  return verifyDigitalValueScopeImpl(value, use, nullptr);
}
LogicalResult verifyDigitalValueScope(Value value, Operation *use, unsigned &remaining) {
  return verifyDigitalValueScopeImpl(value, use, &remaining);
}
FailureOr<Value> digitalRegionCaptureSource(BlockArgument value,
                                            const ParameterIntegerEnvironment *) {
  return digitalRegionCaptureSourceImpl(value, nullptr);
}
FailureOr<Value> digitalRegionCaptureSource(BlockArgument value, unsigned &remaining) {
  return digitalRegionCaptureSourceImpl(value, &remaining);
}
LogicalResult digitalRegionArgumentDependencies(BlockArgument arg, SmallVectorImpl<Value> &deps) {
  auto h = inspectDigitalRegion(arg.getOwner());
  if (failed(h))
    return failure();
  if (h->kind == DigitalRegionKind::Collection)
    return collectionArgumentDependencies(arg, deps);
  if (h->kind == DigitalRegionKind::FlatFSMAction)
    return flatFSMArgumentDependencies(arg, deps);
  auto source = digitalRegionCaptureSource(arg);
  if (failed(source))
    return failure();
  if (*source)
    deps.push_back(*source);
  return success();
}
std::string digitalRegionSemanticPath(Block *body) {
  auto h = inspectDigitalRegion(body);
  if (failed(h))
    return {};
  if (h->kind == DigitalRegionKind::FlatFSMAction) {
    auto a =
        llvm::cast<DictionaryAttr>(h->operation->getAttrOfType<ArrayAttr>("actions")[h->ordinal]);
    return a.getAs<StringAttr>("region_id").getValue().str();
  }
  if (h->kind == DigitalRegionKind::EnumSwitchArm)
    return llvm::cast<StringAttr>(h->operation->getAttrOfType<ArrayAttr>("arm_ids")[h->ordinal])
        .getValue()
        .str();
  std::string result = text(h->operation, "region_id").str();
  if (h->kind == DigitalRegionKind::StructuralArm || h->kind == DigitalRegionKind::BehavioralArm)
    result += h->ordinal ? ".else" : ".then";
  return result;
}
std::string digitalValueSemanticPath(Value value) {
  if (auto arg = llvm::dyn_cast<BlockArgument>(value)) {
    auto h = inspectDigitalRegion(arg.getOwner());
    if (failed(h))
      return {};
    if (h->kind == DigitalRegionKind::Generate)
      return text(h->operation, "induction_path").str();
    return digitalRegionSemanticPath(arg.getOwner()) + ".arg" + std::to_string(arg.getArgNumber());
  }
  if (auto result = llvm::dyn_cast<OpResult>(value)) {
    Operation *definition = result.getOwner();
    if (llvm::isa<FlatFSMOp>(definition)) {
      StringRef role = flatFSMResultRole(value);
      return role.empty() ? std::string() : (path(definition) + "." + role).str();
    }
    if (StringRef role = fsmCompositionResultRole(value); !role.empty())
      return definition->getNumResults() == 1 ? path(definition).str()
                                              : (path(definition) + "." + role).str();
    if (llvm::isa<AsyncFifoOp>(definition)) {
      auto endpoint = inspectExecutableCrossingResult(value);
      if (failed(endpoint))
        return {};
      // The payload keeps its authored source identity, also consumed by shape
      // views/slices. Additional controls receive canonical distinct role paths.
      return result.getResultNumber() == 0 ? path(definition).str()
                                           : (path(definition) + "." + endpoint->role).str();
    }
    // A multi-result producer needs an owning semantic result contract. Its
    // operation path alone cannot identify distinct SSA results.
    if (definition->getNumResults() != 1)
      return {};
    return path(definition).str();
  }
  return {};
}
static FailureOr<Value> lookupDigitalRegionTokenImpl(Block *body, StringRef token, unsigned *work,
                                                     unsigned *remaining) {
  if (!spendRegionWork(remaining))
    return failure();
  if (!body || token.empty())
    return failure();
  Value result;
  unsigned localWork = 0;
  if (!work)
    work = &localWork;
  for (BlockArgument arg : body->getArguments()) {
    if (!spendRegionWork(remaining) || ++*work > 65536)
      return failure();
    if (!chargeRegionHeader(body, remaining) || !chargeRegionHeader(body, remaining))
      return failure();
    if (digitalValueSemanticPath(arg) == token) {
      if (result)
        return failure();
      result = arg;
    }
  }
  for (Operation &op : *body) {
    if (!spendRegionWork(remaining) || ++*work > 65536)
      return failure();
    if (path(&op) == token) {
      if (result || op.getNumResults() != 1)
        return failure();
      result = op.getResult(0);
    }
  }
  return result;
}
static bool isGeneratedTypeUseImpl(Operation *use, unsigned *remainingWork, bool chargeHeaders) {
  if (chargeHeaders && !spendRegionWork(remainingWork))
    return false;
  bool generated = false;
  unsigned depth = 0;
  while (use && !isa_and_nonnull<ModuleOp>(use->getParentOp())) {
    if (++depth > 128 || !spendRegionWork(remainingWork))
      return false;
    auto header =
        inspectDigitalRegionImpl(use->getBlock(), chargeHeaders ? remainingWork : nullptr);
    if (failed(header) || (header->kind != DigitalRegionKind::Generate &&
                           header->kind != DigitalRegionKind::StructuralArm &&
                           header->kind != DigitalRegionKind::Collection))
      return false;
    if (header->kind == DigitalRegionKind::Collection) {
      // Actual captures may carry generated coordinates; ordinary reducer width
      // formals keep their separately admitted ParameterIntegerEnvironment.
      unsigned localWork = 65536;
      auto applicable = inspectGeneratedCollectionTypeApplicability(
          header->body, remainingWork ? *remainingWork : localWork);
      if (failed(applicable) || !*applicable)
        return false;
    }
    generated |= header->kind == DigitalRegionKind::Generate;
    use = header->operation;
  }
  return use && generated;
}

bool isGeneratedTypeUse(Operation *use) {
  unsigned remainingWork = 65536;
  return isGeneratedTypeUseImpl(use, &remainingWork, false);
}
bool isGeneratedTypeUse(Operation *use, unsigned &remainingWork) {
  return isGeneratedTypeUseImpl(use, &remainingWork, true);
}
FailureOr<Value> lookupDigitalRegionToken(Block *body, StringRef token, unsigned *work) {
  return lookupDigitalRegionTokenImpl(body, token, work, nullptr);
}
FailureOr<Value> lookupDigitalRegionToken(Block *body, StringRef token, unsigned &remaining) {
  unsigned work = 0;
  return lookupDigitalRegionTokenImpl(body, token, &work, &remaining);
}

namespace detail {
class DigitalGenerateIntegerEnvironmentAdmission {
public:
  static FailureOr<ParameterIntegerEnvironment> create(Operation *use) {
    auto h =
        use ? inspectDigitalRegion(use->getBlock()) : FailureOr<DigitalRegionHeader>(failure());
    if (failed(h) || h->kind != DigitalRegionKind::Generate ||
        failed(resolveGeneratedDomain(h->operation)))
      return failure();
    // Retain the actual enclosing lexical context, but never bind the induction:
    // generated indices remain Shape/iteration facts, not type-width atoms.
    unsigned depth = 0;
    for (Operation *scope = h->operation; scope != h->module; scope = scope->getParentOp())
      if (!scope || !isDigitalRegionOwner(scope) || ++depth > 128)
        return failure();
    if (isDigitalRegionOwner(h->operation->getParentOp())) {
      auto parents = digitalIntegerContextsAtUse(h->operation);
      if (failed(parents) || parents->size() != 1)
        return failure(); // A single context consumer cannot select a reduction case.
      const auto &parent = parents->front();
      return ParameterIntegerEnvironment::create(h->module, h->body, use, parent.instance(), {}, {},
                                                 &parent);
    }
    return ParameterIntegerEnvironment::create(h->module, h->body, use, nullptr, {}, {});
  }
};
} // namespace detail
FailureOr<ParameterIntegerEnvironment> digitalGenerateIntegerContextAtUse(Operation *use) {
  return detail::DigitalGenerateIntegerEnvironmentAdmission::create(use);
}
FailureOr<SmallVector<ParameterIntegerEnvironment, 4>> digitalIntegerContextsAtUse(Operation *use) {
  auto h = use ? inspectDigitalRegion(use->getBlock()) : FailureOr<DigitalRegionHeader>(failure());
  if (failed(h))
    return failure();
  if (h->kind == DigitalRegionKind::Collection)
    return collectionOwnIntegerContextsAtUse(use);
  if (h->kind == DigitalRegionKind::StructuralArm)
    return structuralIfIntegerContextsAtUse(use);
  if (h->kind == DigitalRegionKind::BehavioralArm)
    return behavioralIntegerContextsAtUse(use);
  if (h->kind == DigitalRegionKind::FlatFSMAction)
    return flatFSMIntegerContextsAtUse(use);
  if (h->kind == DigitalRegionKind::EnumSwitchArm)
    return enumSwitchIntegerContextsAtUse(use);
  auto context = h->kind == DigitalRegionKind::Generate ? digitalGenerateIntegerContextAtUse(use)
                                                        : digitalLoopIntegerContextAtUse(use);
  if (failed(context))
    return failure();
  return SmallVector<ParameterIntegerEnvironment, 4>{*context};
}
} // namespace nodal
