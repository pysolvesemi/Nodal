#include "nodal/Dialect/Nodal/DigitalRegionModel.h"

#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/EnumSwitchModel.h"
#include "nodal/Dialect/Nodal/FlatFSMModel.h"
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
FailureOr<DigitalRegionHeader> inspectDigitalRegion(Block *body) {
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
LogicalResult verifyDigitalValueScope(Value value, Operation *use,
                                      const ParameterIntegerEnvironment *) {
  if (!value || !use || !use->getBlock())
    return failure();
  Operation *module = owner(use);
  if (!module)
    return failure();
  if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
    auto h = inspectDigitalRegion(argument.getOwner());
    if (failed(h))
      return failure();
    Operation *visible = use;
    unsigned depth = 0;
    while (visible && visible->getBlock() != argument.getOwner()) {
      if (++depth > 128)
        return failure();
      Operation *parent = visible->getParentOp();
      if (!parent || parent == module)
        return failure();
      auto region = inspectDigitalRegion(visible->getBlock());
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
    if (++depth > 128)
      return failure();
    Operation *parent = visible->getParentOp();
    if (!parent || parent == module)
      return failure();
    if (isDigitalRegionOwner(parent)) {
      auto region = inspectDigitalRegion(visible->getBlock());
      if (failed(region) || region->isolated)
        return failure();
    }
    visible = parent;
  }
  return success(visible && definition != visible && definition->isBeforeInBlock(visible));
}
FailureOr<Value> digitalRegionCaptureSource(BlockArgument arg,
                                            const ParameterIntegerEnvironment *) {
  auto h = inspectDigitalRegion(arg.getOwner());
  if (failed(h))
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
  if (auto result = llvm::dyn_cast<OpResult>(value);
      result && llvm::isa<FlatFSMOp>(result.getOwner()) &&
      succeeded(inspectFlatFSMHeader(result.getOwner())))
    return (path(result.getOwner()) + (result.getResultNumber() ? ".taken" : ".next")).str();
  return path(value.getDefiningOp()).str();
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
    // The induction is deliberately absent: generated indices cannot become widths.
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
