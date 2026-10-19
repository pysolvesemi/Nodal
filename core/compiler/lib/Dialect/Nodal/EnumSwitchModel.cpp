#include "nodal/Dialect/Nodal/EnumSwitchModel.h"

#include "mlir/IR/IRMapping.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"

#include "llvm/ADT/SetVector.h"
#include "llvm/ADT/StringSet.h"
using namespace mlir;
namespace nodal {
namespace {
StringRef text(Operation *op, StringRef key) {
  auto a = op ? op->getAttrOfType<StringAttr>(key) : StringAttr();
  return a ? a.getValue() : StringRef();
}
StringRef path(Operation *op) {
  auto a = op->getAttrOfType<DictionaryAttr>("metadata");
  auto p = a ? a.getAs<StringAttr>("semantic_path") : StringAttr();
  return p ? p.getValue() : StringRef();
}
bool canonical(StringRef s) { return !s.empty() && s.size() <= 4096 && s == s.trim(); }
LogicalResult reject(Operation *op, StringRef why) {
  return op->emitOpError("NODAL-SEQUENTIAL-056-002: ") << why;
}
bool closed(Operation *op) {
  return llvm::all_of(op->getAttrs(), [](NamedAttribute a) {
    return llvm::is_contained(ArrayRef<StringRef>{"contract_version", "region_id", "enum_ref",
                                                  "case_ids", "capture_counts", "arm_ids",
                                                  "metadata"},
                              a.getName().getValue());
  });
}
} // namespace
FailureOr<EnumSwitchHeader> inspectEnumSwitchHeader(Operation *op) {
  if (!llvm::isa_and_nonnull<EnumSwitchOp>(op) || op->getNumOperands() < 1 ||
      op->getNumOperands() > 8193 || op->getNumResults() || op->getNumSuccessors() ||
      text(op, "contract_version") != "enum-switch-v1" || !canonical(text(op, "region_id")) ||
      path(op) != text(op, "region_id") || !closed(op) ||
      !llvm::isa<ModuleOp, BehavioralIfOp, FlatFSMOp, EnumSwitchOp>(op->getParentOp()))
    return failure();
  auto cases = op->getAttrOfType<ArrayAttr>("case_ids");
  auto counts = op->getAttrOfType<DenseI32ArrayAttr>("capture_counts");
  auto ids = op->getAttrOfType<ArrayAttr>("arm_ids");
  auto module = op->getParentOfType<ModuleOp>();
  if (!module || !cases || cases.empty() || cases.size() > 4096 || !counts || !ids ||
      size_t(counts.size()) != cases.size() + 1 || ids.size() != size_t(counts.size()) ||
      op->getNumRegions() != counts.size())
    return failure();
  StringRef prefix = path(module).empty() ? text(module, "sym_name") : path(module);
  if (!text(op, "region_id").starts_with((prefix + ".").str()))
    return failure();
  EnumSwitchHeader h{op, module, {}, {}, {}};
  unsigned offset = 1;
  llvm::StringSet<> identities;
  for (unsigned i = 0; i < counts.size(); ++i) {
    auto id = llvm::dyn_cast<StringAttr>(ids[i]);
    if (!id || !canonical(id.getValue()) ||
        !id.getValue().starts_with((text(op, "region_id") + ".").str()) ||
        !identities.insert(id.getValue()).second || counts[i] < 0 || counts[i] > 4098 ||
        unsigned(counts[i]) > op->getNumOperands() - offset || !op->getRegion(i).hasOneBlock())
      return failure();
    Block *body = &op->getRegion(i).front();
    if (body->getNumArguments() != unsigned(counts[i]))
      return failure();
    for (unsigned j = 0; j < unsigned(counts[i]); ++j)
      if (body->getArgument(j).getType() != op->getOperand(offset + j).getType())
        return failure();
    h.bodies.push_back(body);
    h.captureOffsets.push_back(offset);
    h.captureCounts.push_back(counts[i]);
    offset += counts[i];
  }
  if (offset != op->getNumOperands())
    return failure();
  return h;
}
FailureOr<Value> enumSwitchCaptureSource(BlockArgument arg) {
  auto h = inspectEnumSwitchHeader(arg.getOwner()->getParentOp());
  if (failed(h))
    return failure();
  for (unsigned i = 0; i < h->bodies.size(); ++i)
    if (h->bodies[i] == arg.getOwner() && arg.getArgNumber() < h->captureCounts[i])
      return h->operation->getOperand(h->captureOffsets[i] + arg.getArgNumber());
  return failure();
}
namespace detail {
class EnumSwitchIntegerEnvironmentAdmission {
public:
  static FailureOr<ParameterIntegerEnvironment> create(const EnumSwitchHeader &h, Block *body,
                                                       Operation *use,
                                                       const ParameterIntegerEnvironment *outer) {
    SmallVector<ParameterIntegerBinding, 8> bindings;
    for (BlockArgument arg : body->getArguments()) {
      if (!arg.getType().isSignlessInteger(64))
        continue;
      auto source = enumSwitchCaptureSource(arg);
      if (failed(source) || failed(verifyDigitalValueScope(*source, h.operation)))
        return failure();
      auto facts = probeBehavioralIntegerCapture(*source, h.operation);
      // Only actual proved static origins enter the parameter environment.
      if (failed(facts) || !facts->expression)
        continue;
      bindings.push_back({arg, *facts->expression, *source});
    }
    return ParameterIntegerEnvironment::create(
        h.module, body, use, outer ? outer->instance() : nullptr, bindings, {}, outer);
  }
};
} // namespace detail
FailureOr<SmallVector<ParameterIntegerEnvironment, 4>>
enumSwitchIntegerContextsAtUse(Operation *use) {
  auto h =
      use ? inspectEnumSwitchHeader(use->getParentOp()) : FailureOr<EnumSwitchHeader>(failure());
  if (failed(h))
    return failure();
  SmallVector<ParameterIntegerEnvironment, 4> outer;
  if (isDigitalRegionOwner(h->operation->getParentOp())) {
    auto contexts = digitalIntegerContextsAtUse(h->operation);
    if (failed(contexts) || contexts->size() != 1)
      return failure();
    outer = std::move(*contexts);
  }
  auto context = detail::EnumSwitchIntegerEnvironmentAdmission::create(
      *h, use->getBlock(), use, outer.empty() ? nullptr : &outer.front());
  if (failed(context))
    return failure();
  return SmallVector<ParameterIntegerEnvironment, 4>{*context};
}
LogicalResult verifyEnumSwitchOperation(Operation *op) {
  auto h = inspectEnumSwitchHeader(op);
  if (failed(h))
    return reject(op, "exhaustive switch requires exact isolated capture/region header");
  auto definition =
      inspectCanonicalEnumDefinition(op, op->getAttrOfType<FlatSymbolRefAttr>("enum_ref"));
  if (failed(definition) || op->getOperand(0).getType() != definition->type)
    return reject(op, "switch selector must retain its exact canonical enum ABI");
  auto ids = op->getAttrOfType<ArrayAttr>("case_ids");
  llvm::DenseSet<Attribute> seen;
  for (Attribute id : ids)
    if (!llvm::isa<StringAttr>(id) || !seen.insert(id).second ||
        !llvm::is_contained(definition->caseIds, id))
      return reject(op, "switch cases must be unique and belong to its actual enum");
  if (ids.size() != definition->caseIds.size())
    return reject(op, "switch requires every legal case even with an invalid arm");
  for (Value value : op->getOperands())
    if (failed(verifyDigitalValueScope(value, op)))
      return reject(op, "switch operands require actual owned selector and captures");
  for (Block *body : h->bodies)
    if (failed(verifySequentialBody(body)))
      return failure();
  return success();
}
namespace {
class Expansion {
public:
  Expansion(EnumSwitchHeader h, CanonicalEnumDefinition definition, unsigned &generated,
            unsigned maximum)
      : h(std::move(h)), definition(std::move(definition)), generated(generated), maximum(maximum),
        builder(this->h.operation->getContext()) {}
  LogicalResult run() {
    uint64_t cost = 2 * (h.bodies.size() - 1);
    for (Block *body : h.bodies)
      for (Operation &child : *body)
        child.walk([&](Operation *) { ++cost; });
    if (cost > maximum - std::min(maximum, generated))
      return failure();
    builder.setInsertionPoint(h.operation);
    IRMapping mapping;
    auto property = predicate(mapping, "enum_is_valid", 0, 0, ".valid");
    if (failed(property))
      return failure();
    auto legal = captures(0, h.bodies.size() - 1, true),
         invalid = captures(h.bodies.size() - 1, h.bodies.size(), false);
    auto root = branch(*property, mapping, legal, invalid, text(h.operation, "region_id"));
    if (failed(root))
      return failure();
    IRMapping yes = map(legal, (*root)->getRegion(0).front()),
              no = map(invalid, (*root)->getRegion(1).front());
    builder.setInsertionPointToEnd(&(*root)->getRegion(0).front());
    if (failed(tree(0, h.bodies.size() - 1, yes, ".tree")))
      return failure();
    builder.setInsertionPointToEnd(&(*root)->getRegion(1).front());
    if (failed(leaf(h.bodies.size() - 1, no)))
      return failure();
    h.operation->erase();
    return success();
  }

private:
  EnumSwitchHeader h;
  CanonicalEnumDefinition definition;
  unsigned &generated, maximum;
  OpBuilder builder;
  bool charge(unsigned count = 1) {
    if (count > maximum - std::min(maximum, generated))
      return false;
    generated += count;
    return true;
  }
  DictionaryAttr metadata(StringRef suffix) {
    NamedAttrList attrs(h.operation->getAttrOfType<DictionaryAttr>("metadata"));
    attrs.set("semantic_path",
              builder.getStringAttr((text(h.operation, "region_id") + suffix).str()));
    return attrs.getDictionary(builder.getContext());
  }
  SmallVector<Value, 8> captures(unsigned first, unsigned last, bool selector) {
    llvm::SetVector<Value> values;
    if (selector)
      values.insert(h.operation->getOperand(0));
    for (unsigned a = first; a < last; ++a)
      for (unsigned i = 0; i < h.captureCounts[a]; ++i)
        values.insert(h.operation->getOperand(h.captureOffsets[a] + i));
    return SmallVector<Value, 8>(values.begin(), values.end());
  }
  IRMapping map(ArrayRef<Value> values, Block &body) {
    IRMapping result;
    for (unsigned i = 0; i < values.size(); ++i)
      result.map(values[i], body.getArgument(i));
    return result;
  }
  FailureOr<Value> predicate(IRMapping &mapping, StringRef opcode, unsigned first, unsigned last,
                             StringRef suffix) {
    if (!charge())
      return failure();
    OperationState op(h.operation->getLoc(), "nodal.enum_expr");
    op.addOperands(mapping.lookupOrDefault(h.operation->getOperand(0)));
    op.addTypes(BitsType::get(builder.getContext(), 1));
    op.addAttribute("operator_name", builder.getStringAttr(opcode));
    op.addAttribute("enum_ref", h.operation->getAttr("enum_ref"));
    auto ids = h.operation->getAttrOfType<ArrayAttr>("case_ids");
    op.addAttribute("case_ids", builder.getArrayAttr(ids.getValue().slice(first, last - first)));
    NamedAttrList attrs(definition.operation->getAttrOfType<DictionaryAttr>("metadata"));
    attrs.set("semantic_path",
              builder.getStringAttr((text(h.operation, "region_id") + suffix).str()));
    op.addAttribute("metadata", attrs.getDictionary(builder.getContext()));
    return builder.create(op)->getResult(0);
  }
  FailureOr<Operation *> branch(Value condition, IRMapping &mapping, ArrayRef<Value> a,
                                ArrayRef<Value> b, StringRef id) {
    if (a.size() + b.size() > 8192 || !charge())
      return failure();
    OperationState op(h.operation->getLoc(), "nodal.behavioral_if");
    op.addOperands(condition);
    for (Value v : a)
      op.addOperands(mapping.lookupOrDefault(v));
    for (Value v : b)
      op.addOperands(mapping.lookupOrDefault(v));
    op.addAttribute("contract_version", builder.getStringAttr("behavioral-if-v1"));
    op.addAttribute("region_id", builder.getStringAttr(id));
    op.addAttribute("operandSegmentSizes",
                    builder.getDenseI32ArrayAttr({1, int32_t(a.size()), int32_t(b.size())}));
    NamedAttrList attrs(h.operation->getAttrOfType<DictionaryAttr>("metadata"));
    attrs.set("semantic_path", builder.getStringAttr(id));
    op.addAttribute("metadata", attrs.getDictionary(builder.getContext()));
    for (auto values : {a, b}) {
      auto *body = new Block;
      op.addRegion()->push_back(body);
      for (Value v : values)
        body->addArgument(v.getType(), h.operation->getLoc());
    }
    return builder.create(op);
  }
  LogicalResult leaf(unsigned arm, IRMapping &mapping) {
    IRMapping local = mapping;
    for (unsigned i = 0; i < h.captureCounts[arm]; ++i)
      local.map(h.bodies[arm]->getArgument(i),
                mapping.lookupOrDefault(h.operation->getOperand(h.captureOffsets[arm] + i)));
    for (Operation &op : *h.bodies[arm]) {
      unsigned count = 0;
      op.walk([&](Operation *) { ++count; });
      if (!charge(count))
        return reject(h.operation, "switch expansion exceeds finite operation budget");
      builder.clone(op, local);
    }
    return success();
  }
  LogicalResult tree(unsigned first, unsigned last, IRMapping &mapping, std::string suffix) {
    if (last - first == 1)
      return leaf(first, mapping);
    unsigned middle = first + (last - first) / 2;
    auto condition = predicate(mapping, "enum_is_one_of", first, middle, suffix + ".condition");
    if (failed(condition))
      return failure();
    auto a = captures(first, middle, true), b = captures(middle, last, true);
    auto choice =
        branch(*condition, mapping, a, b, (text(h.operation, "region_id") + suffix).str());
    if (failed(choice))
      return failure();
    IRMapping yes = map(a, (*choice)->getRegion(0).front()),
              no = map(b, (*choice)->getRegion(1).front());
    builder.setInsertionPointToEnd(&(*choice)->getRegion(0).front());
    if (failed(tree(first, middle, yes, suffix + ".left")))
      return failure();
    builder.setInsertionPointToEnd(&(*choice)->getRegion(1).front());
    return tree(middle, last, no, suffix + ".right");
  }
};
} // namespace
LogicalResult expandEnumSwitchModel(Operation *module, unsigned &generated, unsigned maximum) {
  SmallVector<Operation *, 8> switches;
  module->walk<WalkOrder::PostOrder>([&](EnumSwitchOp op) {
    if (op->getParentOfType<ModuleOp>() == module)
      switches.push_back(op);
  });
  for (Operation *op : switches) {
    auto h = inspectEnumSwitchHeader(op);
    auto definition =
        inspectCanonicalEnumDefinition(op, op->getAttrOfType<FlatSymbolRefAttr>("enum_ref"));
    if (failed(h) || failed(definition) || failed(verifyEnumSwitchOperation(op)) ||
        failed(Expansion(*h, *definition, generated, maximum).run()))
      return reject(op, "switch normalization failed its checked bounded candidate");
  }
  return success();
}
} // namespace nodal
