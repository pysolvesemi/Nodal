#include "nodal/Dialect/Nodal/DigitalLoopPlanningModel.h"

#include "mlir/IR/AttrTypeSubElements.h"
#include "mlir/IR/Builders.h"
#include "mlir/IR/IRMapping.h"
#include "mlir/IR/Verifier.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/Support/SHA256.h"
#include "llvm/Support/raw_ostream.h"

#include <functional>
#include <limits>
#include <set>
using namespace mlir;
namespace nodal {
namespace {
constexpr StringLiteral reportKey = "nodal.digital.loop_plans";
constexpr uint64_t maxIterations = 8, maxNodes = 65536, maxEdges = 262144,
                   maxBytes = 8 * 1024 * 1024;
LogicalResult reject(Operation *operation, StringRef message) {
  return operation->emitOpError("NODAL-DIGITAL-055-012: ") << message;
}
class BoundedText final : public llvm::raw_ostream {
public:
  BoundedText() : raw_ostream(true) {}
  std::string value;
  bool exhausted = false;

private:
  void write_impl(const char *data, size_t size) override {
    if (size > maxBytes - value.size()) {
      exhausted = true;
      return;
    }
    value.append(data, size);
  }
  uint64_t current_pos() const override { return value.size(); }
};
FailureOr<std::string> print(Operation *operation) {
  BoundedText out;
  operation->print(out, OpPrintingFlags().printGenericOpForm().assumeVerified());
  if (out.exhausted)
    return failure();
  return std::move(out.value);
}
FailureOr<std::string> typeText(Type type) {
  BoundedText out;
  type.print(out);
  if (out.exhausted)
    return failure();
  return std::move(out.value);
}
std::string digest(StringRef value) {
  auto bytes = llvm::SHA256::hash(llvm::arrayRefFromStringRef(value));
  return llvm::toHex(bytes, true);
}
StringRef text(Operation *op, StringRef key) {
  auto a = op->getAttrOfType<StringAttr>(key);
  return a ? a.getValue() : StringRef();
}
bool invariant(const InstantiatedIntegerExpression &value) {
  SmallVector<std::shared_ptr<const InstantiatedIntegerNode>, 8> todo{value.root};
  uint64_t work = 0;
  while (!todo.empty()) {
    auto n = todo.pop_back_val();
    if (!n || ++work > maxNodes || n->kind == InstantiatedIntegerNode::Kind::ParentParameter ||
        n->kind == InstantiatedIntegerNode::Kind::OpaqueValue)
      return false;
    llvm::append_range(todo, n->operands);
  }
  return value.bounds.lower == value.bounds.upper;
}
struct Cost {
  uint64_t iterations = 0, nodes = 0, edges = 0, bytes = 0;
  bool eligible = false;
  std::string reason;
};
FailureOr<Cost> cost(Operation *loop, const GeneratedDomain &domain) {
  Cost result;
  result.reason = "symbolic-domain";
  if (!invariant(domain.lower) || !invariant(domain.upperExclusive) || !invariant(domain.step))
    return result;
  result.iterations = domain.maximumTripCount;
  for (Operation &operation : loop->getRegion(0).front())
    if (llvm::isa<IterationIndexOp>(operation)) {
      auto affine = resolveDigitalIterationIndex(operation.getResult(0), operation.getNextNode());
      if (failed(affine))
        return failure();
      if (!invariant(affine->offset)) {
        result.reason = "symbolic-selector";
        return result;
      }
    }
  if (result.iterations > maxIterations) {
    result.reason = "iteration-policy";
    return result;
  }
  auto rendered = print(loop);
  if (failed(rendered))
    return failure();
  uint64_t nodes = 1, edges = loop->getNumOperands(), bytes = rendered->size();
  bool invalid = false;
  BoundedText expandedFields;
  loop->walk([&](Operation *op) {
    if (op == loop)
      return;
    if (nodes == maxNodes || op->getNumOperands() > maxEdges - edges) {
      invalid = true;
      return;
    }
    ++nodes;
    edges += op->getNumOperands();
    // Count concrete fields too: assembly aliases must not hide large nominal
    // aggregates or local symbolic type references from expansion accounting.
    op->getAttrDictionary().print(expandedFields);
    for (Type type : op->getResultTypes())
      type.print(expandedFields);
    for (Region &region : op->getRegions())
      for (Block &block : region)
        for (BlockArgument argument : block.getArguments())
          argument.getType().print(expandedFields);
    invalid |= expandedFields.exhausted;
  });
  if (invalid)
    return failure();
  bytes = std::max<uint64_t>(bytes, expandedFields.value.size());
  // Charge the actual retained template, capture/source identities and fresh name
  // suffixes. Every multiplication is checked before allocating candidate IR.
  uint64_t count = result.iterations;
  auto checked = [&](uint64_t value, uint64_t limit, uint64_t &out) {
    if (count && value > limit / count)
      return false;
    out = value * count;
    return true;
  };
  if (bytes > maxBytes || bytes > std::numeric_limits<uint64_t>::max() - nodes * 64 ||
      !checked(nodes, maxNodes, result.nodes) || !checked(edges, maxEdges, result.edges) ||
      !checked(bytes + nodes * 64, maxBytes, result.bytes)) {
    result.reason = "expansion-policy";
    return result;
  }
  result.eligible = true;
  result.reason = "verified-small-invariant";
  return result;
}
void clearReports(Operation *root) {
  root->walk([&](Operation *operation) {
    operation->removeAttr(reportKey);
    operation->removeAttr("nodal.digital.materialization");
  });
}
struct Inventory {
  llvm::DenseMap<Operation *, uint64_t> operations;
  llvm::DenseMap<Value, std::string> values;
  SmallVector<Attribute> records;
};
FailureOr<Inventory> inventory(Operation *module) {
  Inventory result;
  Builder b(module->getContext());
  uint64_t ordinal = 0, edges = 0, bytes = 0;
  bool invalid = false;
  uint64_t valueCount = 0;
  auto indexed = module->walk<WalkOrder::PreOrder>([&](Operation *op) -> WalkResult {
    if (op == module)
      return WalkResult::advance();
    if (ordinal >= maxNodes)
      return WalkResult::interrupt();
    result.operations.try_emplace(op, ordinal);
    for (auto [i, v] : llvm::enumerate(op->getResults())) {
      if (++valueCount > maxEdges)
        return WalkResult::interrupt();
      result.values.try_emplace(v, "op" + std::to_string(ordinal) + ".r" + std::to_string(i));
    }
    for (auto [ri, r] : llvm::enumerate(op->getRegions()))
      for (auto [bi, block] : llvm::enumerate(r))
        for (auto [ai, arg] : llvm::enumerate(block.getArguments())) {
          if (++valueCount > maxEdges)
            return WalkResult::interrupt();
          result.values.try_emplace(arg, "op" + std::to_string(ordinal) + ".region" +
                                             std::to_string(ri) + ".block" + std::to_string(bi) +
                                             ".arg" + std::to_string(ai));
        }
    ++ordinal;
    return WalkResult::advance();
  });
  if (indexed.wasInterrupted())
    return failure();
  module->walk([&](Operation *op) {
    if (op == module || invalid)
      return;
    NamedAttrList rec;
    rec.set("id", b.getI64IntegerAttr(result.operations.lookup(op)));
    rec.set("operation", b.getStringAttr(op->getName().getStringRef()));
    SmallVector<Attribute> inputs, types;
    for (Value value : op->getOperands()) {
      auto found = result.values.find(value);
      if (found == result.values.end() || ++edges > maxEdges) {
        invalid = true;
        return;
      }
      inputs.push_back(b.getStringAttr(found->second));
    }
    for (Type type : op->getResultTypes()) {
      auto renderedType = typeText(type);
      if (failed(renderedType) || renderedType->size() > maxBytes - bytes) {
        invalid = true;
        return;
      }
      bytes += renderedType->size();
      types.push_back(b.getStringAttr(*renderedType));
    }
    rec.set("inputs", b.getArrayAttr(inputs));
    rec.set("types", b.getArrayAttr(types));
    auto attrs = op->getAttrDictionary();
    BoundedText rendered;
    attrs.print(rendered);
    if (rendered.exhausted || rendered.value.size() > maxBytes - bytes) {
      invalid = true;
      return;
    }
    bytes += rendered.value.size();
    rec.set("attributes", b.getStringAttr(rendered.value));
    result.records.push_back(rec.getDictionary(module->getContext()));
  });
  if (invalid)
    return failure();
  return result;
}
FailureOr<Attribute> expressionRecord(const InstantiatedIntegerExpression &expression,
                                      const Inventory &inventory, Builder &b, uint64_t &work) {
  llvm::DenseMap<const InstantiatedIntegerNode *, Attribute> memo;
  std::function<FailureOr<Attribute>(const std::shared_ptr<const InstantiatedIntegerNode> &,
                                     unsigned)>
      visit;
  visit = [&](const std::shared_ptr<const InstantiatedIntegerNode> &node,
              unsigned depth) -> FailureOr<Attribute> {
    if (!node || depth > 128 || ++work > maxEdges)
      return failure();
    if (auto found = memo.find(node.get()); found != memo.end())
      return found->second;
    NamedAttrList record;
    record.set("minimum", b.getI64IntegerAttr(node->bounds.lower));
    record.set("maximum", b.getI64IntegerAttr(node->bounds.upper));
    if (node->kind == InstantiatedIntegerNode::Kind::Literal)
      record.set("literal", b.getI64IntegerAttr(node->literal));
    else if (node->kind == InstantiatedIntegerNode::Kind::ParentParameter) {
      auto found = inventory.operations.find(node->parameter);
      if (found == inventory.operations.end())
        return failure();
      record.set("parameter", b.getI64IntegerAttr(found->second));
    } else if (node->kind == InstantiatedIntegerNode::Kind::OpaqueValue) {
      auto found = inventory.values.find(node->identity);
      if (found == inventory.values.end())
        return failure();
      record.set("value", b.getStringAttr(found->second));
    } else {
      record.set("operator", b.getStringAttr(node->opcode));
      SmallVector<Attribute> children;
      for (auto &operand : node->operands) {
        auto child = visit(operand, depth + 1);
        if (failed(child))
          return failure();
        children.push_back(*child);
      }
      record.set("operands", b.getArrayAttr(children));
    }
    Attribute result = record.getDictionary(b.getContext());
    memo.try_emplace(node.get(), result);
    return result;
  };
  return visit(expression.root, 0);
}
FailureOr<DictionaryAttr> imageRecord(const StaticShapeProjection &projection, Operation *use,
                                      const Inventory &inventory, Builder &b, uint64_t &work) {
  StaticShapeRegion region;
  if (resolveStaticShapeRegion(projection, use, region, {4096, 4096}) !=
      ShapeRegionProofStatus::Proved)
    return failure();
  NamedAttrList record;
  record.set("kind", b.getI64IntegerAttr(static_cast<unsigned>(region.kind)));
  auto root = inventory.values.find(region.root);
  if (root == inventory.values.end())
    return failure();
  record.set("root", b.getStringAttr(root->second));
  SmallVector<Attribute> axes;
  auto axis = [&](const StaticShapeRegionAxis &value) -> LogicalResult {
    auto lower = expressionRecord(value.lower, inventory, b, work),
         upper = expressionRecord(value.upperExclusive, inventory, b, work);
    if (failed(lower) || failed(upper))
      return failure();
    axes.push_back(b.getDictionaryAttr(
        {b.getNamedAttr("lower", *lower), b.getNamedAttr("upper_exclusive", *upper)}));
    return success();
  };
  for (auto &value : region.axes)
    if (failed(axis(value)))
      return failure();
  if (region.kind == StaticShapeRegion::Kind::Interval && failed(axis(region.interval)))
    return failure();
  record.set("coordinates", b.getArrayAttr(axes));
  if (region.iteration) {
    record.set("loop", b.getI64IntegerAttr(inventory.operations.lookup(region.iteration->loop)));
    record.set("stride", b.getI64IntegerAttr(region.iteration->stride));
    record.set("empty", b.getBoolAttr(region.iteration->empty));
    record.set("varying_axis",
               b.getI64IntegerAttr(region.iteration->varyingAxis
                                       ? static_cast<int64_t>(*region.iteration->varyingAxis)
                                       : int64_t(-1)));
    auto offset = expressionRecord(region.iteration->offset, inventory, b, work),
         count = expressionRecord(region.iteration->tripCount, inventory, b, work);
    if (failed(offset) || failed(count))
      return failure();
    auto width = expressionRecord(region.iteration->occurrenceWidth, inventory, b, work);
    if (failed(width))
      return failure();
    record.set("occurrence_width", *width);
    record.set("offset", *offset);
    record.set("trip_count", *count);
  }
  return record.getDictionary(b.getContext());
}

} // namespace

FailureOr<OwningOpRef<mlir::ModuleOp>> materializeDigitalLoopUnrolled(Operation *loop) {
  auto header = inspectDigitalLoopHeader(loop);
  auto domain = resolveBoundedIterationDomain(loop);
  if (failed(header) || failed(domain) || failed(verifyDigitalLoopOperation(loop)) ||
      failed(verifyDigitalAssignmentModel(header->module)))
    return failure();
  auto budget = cost(loop, *domain);
  if (failed(budget) || !budget->eligible) {
    (void)reject(loop, "unrolled candidate exceeds its invariant node/edge/byte policy");
    return failure();
  }
  auto root = loop->getParentOfType<mlir::ModuleOp>();
  if (!root)
    return failure();
  // The private clone includes the surrounding design. Preflight that source as
  // well as expansion; shared report attributes are excluded from authored cost.
  uint64_t sourceNodes = 0, sourceEdges = 0;
  BoundedText sourceText;
  bool sourceTooLarge = false;
  root->walk([&](Operation *operation) {
    if (sourceTooLarge)
      return;
    if (++sourceNodes > maxNodes - budget->nodes ||
        operation->getNumOperands() > maxEdges - budget->edges - sourceEdges) {
      sourceTooLarge = true;
      return;
    }
    sourceEdges += operation->getNumOperands();
    sourceText << operation->getName().getStringRef();
    for (NamedAttribute attribute : operation->getAttrs())
      if (attribute.getName() != reportKey &&
          attribute.getName() != "nodal.digital.materialization")
        attribute.getValue().print(sourceText);
    for (Type type : operation->getResultTypes())
      type.print(sourceText);
    sourceTooLarge |= sourceText.exhausted || sourceText.value.size() > maxBytes - budget->bytes;
  });
  if (sourceTooLarge) {
    (void)reject(loop, "private candidate source plus expansion exceeds node/edge/byte bounds");
    return failure();
  }
  IRMapping original;
  OwningOpRef<mlir::ModuleOp> candidate = llvm::cast<mlir::ModuleOp>(root->clone(original));
  clearReports(*candidate);
  Operation *copy = original.lookupOrNull(loop);
  if (!copy)
    return failure();
  Block &body = copy->getRegion(0).front();
  Builder b(loop->getContext());
  SmallVector<std::pair<Operation *, Operation *>> effectMap;
  for (uint64_t q = 0; q < budget->iterations; ++q) {
    // Domain arithmetic has already proved each actual signed-i64 induction.
    auto product = composeIntegerExpression("mul", {domain->step, makeLiteralIntegerExpression(q)});
    auto iv = succeeded(product) ? composeIntegerExpression("add", {domain->lower, *product})
                                 : FailureOr<InstantiatedIntegerExpression>(failure());
    if (failed(iv) || !invariant(*iv))
      return failure();
    OperationState state(loop->getLoc(), "nodal.const_literal");
    state.addTypes(b.getI64Type());
    state.addAttribute("value", b.getI64IntegerAttr(iv->bounds.lower));
    state.addAttribute("spelling", b.getStringAttr(std::to_string(iv->bounds.lower)));
    state.addAttribute(
        "metadata",
        b.getDictionaryAttr({b.getNamedAttr(
            "semantic_path",
            b.getStringAttr(
                (text(copy, "region_id") + ".unrolled" + Twine(q) + ".index").str()))}));
    Operation *index = Operation::create(state);
    copy->getBlock()->getOperations().insert(copy->getIterator(), index);
    IRMapping mapping;
    mapping.map(body.getArgument(0), index->getResult(0));
    for (unsigned i = 1; i < body.getNumArguments(); ++i)
      mapping.map(body.getArgument(i), copy->getOperand(i + 2));
    std::string prefix = text(copy, "region_id").str();
    std::set<std::string> localIdentities{prefix};
    copy->walk([&](Operation *local) {
      auto metadata = local->getAttrOfType<DictionaryAttr>("metadata");
      auto identity = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
      if (identity && !identity.getValue().empty())
        localIdentities.insert(identity.getValue().str());
      auto region = local->getAttrOfType<StringAttr>("region_id");
      if (region)
        localIdentities.insert(region.getValue().str());
    });
    auto renamePath = [&](StringRef value) -> std::string {
      for (auto it = localIdentities.rbegin(); it != localIdentities.rend(); ++it)
        if (value == *it || value.starts_with(*it + "."))
          return *it + ".unrolled" + std::to_string(q) + value.drop_front(it->size()).str();
      return value.str();
    };
    AttrTypeReplacer renameTypes;
    renameTypes.addReplacement([&](ParametricIntegerType type) -> std::optional<Type> {
      return ParametricIntegerType::get(type.getContext(), type.getKind(),
                                        renamePath(type.getWidth()));
    });
    renameTypes.addReplacement([&](ShapedType type) -> std::optional<Type> {
      SmallVector<StringRef> tokens;
      type.getDimensions().split(tokens, ',');
      std::string dimensions;
      for (StringRef token : tokens) {
        if (!dimensions.empty())
          dimensions += ',';
        dimensions += renamePath(token);
      }
      return ShapedType::get(type.getContext(), dimensions, type.getElementType());
    });
    auto renameSemanticFields = [&](Operation *operation) {
      // Author source locations, source text, filenames, nominal names and spans
      // are provenance. Only declared semantic identity/reference fields move.
      operation->walk([&](Operation *local) {
        for (StringRef key : {"region_id", "induction_path"})
          if (auto value = local->getAttrOfType<StringAttr>(key))
            local->setAttr(key, b.getStringAttr(renamePath(value.getValue())));
        if (auto metadata = local->getAttrOfType<DictionaryAttr>("metadata")) {
          NamedAttrList changed(metadata);
          for (StringRef key : {"semantic_path", "input_path"})
            if (auto value = metadata.getAs<StringAttr>(key)) {
              std::string renamed = renamePath(value.getValue());
              if (key == "input_path" && local->getNumOperands()) {
                auto actual = digitalValueSemanticPath(local->getOperand(0));
                if (!actual.empty())
                  renamed = actual;
              }
              changed.set(key, b.getStringAttr(renamed));
            }
          local->setAttr("metadata", changed.getDictionary(b.getContext()));
        }
      });
      renameTypes.recursivelyReplaceElementsIn(operation, false, false, true);
    };
    for (Operation &op : body) {
      if (llvm::isa<HardwareLoopEndOp>(op))
        continue;
      Operation *cloned = nullptr;
      if (llvm::isa<IterationIndexOp>(op)) {
        auto affine = resolveDigitalIterationIndex(op.getResult(0), op.getNextNode());
        auto exact = succeeded(affine) ? composeIntegerExpression("add", {*iv, affine->offset})
                                       : FailureOr<InstantiatedIntegerExpression>(failure());
        if (failed(exact) || !invariant(*exact))
          return failure();
        OperationState replacementState(op.getLoc(), "nodal.const_literal");
        replacementState.addTypes(b.getI64Type());
        replacementState.addAttribute("value", b.getI64IntegerAttr(exact->bounds.lower));
        replacementState.addAttribute("spelling",
                                      b.getStringAttr(std::to_string(exact->bounds.lower)));
        NamedAttrList meta(op.getAttrOfType<DictionaryAttr>("metadata"));
        meta.set("semantic_path",
                 b.getStringAttr(prefix + ".index" +
                                 std::to_string(std::distance(body.begin(), op.getIterator()))));
        replacementState.addAttribute("metadata", meta.getDictionary(loop->getContext()));
        cloned = Operation::create(replacementState);
        mapping.map(op.getResult(0), cloned->getResult(0));
      } else
        cloned = op.clone(mapping);
      copy->getBlock()->getOperations().insert(copy->getIterator(), cloned);
      renameSemanticFields(cloned);
      if (llvm::isa<DigitalAssignOp>(op)) {
        // Exact mapped SSA fixes each operand's complete type. Local symbolic
        // width identities are intentionally renamed; comparing their raw old
        // spelling would reject the same proven invariant type after expansion.
        // The complete candidate is independently type-verified below.
        if (cloned->getOperand(0) != mapping.lookup(op.getOperand(0)) ||
            cloned->getOperand(1) != mapping.lookupOrDefault(op.getOperand(1))) {
          cloned->erase();
          return failure();
        }
        effectMap.emplace_back(&op, cloned);
      }
    }
  }
  uint64_t effects = 0;
  for (Operation &op : body)
    effects += llvm::isa<DigitalAssignOp>(op);
  if (effectMap.size() != effects * budget->iterations)
    return failure();
  copy->erase();
  if (failed(verify(*candidate)) || failed(verifyParameterModel(*candidate)))
    return failure();
  return std::move(candidate);
}

FailureOr<DictionaryAttr> analyzeDigitalLoopPlans(Operation *module) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module) ||
      failed(verifyDigitalAssignmentModel(module)))
    return failure();
  auto data = inventory(module);
  if (failed(data)) {
    (void)reject(module, "plan inventory exceeds actual authored node/edge/byte bounds");
    return failure();
  }
  Builder b(module->getContext());
  SmallVector<Attribute> plans;
  uint64_t proofWork = 0;
  for (Operation &op : module->getRegion(0).front()) {
    if (!isBoundedDigitalLoop(&op))
      continue;
    auto domain = resolveBoundedIterationDomain(&op);
    auto budget = succeeded(domain) ? cost(&op, *domain) : FailureOr<Cost>(failure());
    if (failed(domain) || failed(budget) || failed(verifyDigitalLoopOperation(&op)))
      return failure();
    NamedAttrList plan;
    plan.set("loop", b.getI64IntegerAttr(data->operations.lookup(&op)));
    plan.set("region", b.getStringAttr(text(&op, "region_id")));
    plan.set("maximum_trip_count", b.getI64IntegerAttr(domain->maximumTripCount));
    plan.set("semantics", b.getStringAttr("ordered-blocking-same-cycle-write-once"));
    plan.set("choice_reason", b.getStringAttr(budget->reason));
    plan.set("selected", b.getStringAttr(budget->eligible ? "unrolled" : "procedural"));
    plan.set("expanded_nodes", b.getI64IntegerAttr(budget->nodes));
    plan.set("expanded_edges", b.getI64IntegerAttr(budget->edges));
    plan.set("expanded_bytes", b.getI64IntegerAttr(budget->bytes));
    SmallVector<Attribute> captures, bounds, effects, prelude, expanded;
    for (auto [i, value] : llvm::enumerate(op.getOperands()))
      (i < 3 ? bounds : captures).push_back(b.getStringAttr(data->values.lookup(value)));
    plan.set("bounds", b.getArrayAttr(bounds));
    plan.set("captures", b.getArrayAttr(captures));
    for (Operation &earlier : module->getRegion(0).front()) {
      if (&earlier == &op)
        break;
      if (llvm::isa<DigitalAssignOp>(earlier))
        prelude.push_back(b.getI64IntegerAttr(data->operations.lookup(&earlier)));
    }
    plan.set("existing_effect_prelude", b.getArrayAttr(prelude));
    for (Operation &body : op.getRegion(0).front())
      if (llvm::isa<DigitalAssignOp>(body)) {
        NamedAttrList effect;
        effect.set("operation", b.getI64IntegerAttr(data->operations.lookup(&body)));
        effect.set("ordinal", b.getI64IntegerAttr(effects.size()));
        effect.set("target", b.getStringAttr(data->values.lookup(body.getOperand(0))));
        effect.set("value", b.getStringAttr(data->values.lookup(body.getOperand(1))));
        auto effectType = typeText(body.getOperand(0).getType());
        if (failed(effectType))
          return failure();
        effect.set("type", b.getStringAttr(*effectType));
        auto projection = resolveStaticShapeProjection(body.getOperand(0), &body);
        if (failed(projection))
          return failure();
        effect.set("physical_root", b.getStringAttr(data->values.lookup(projection->root)));
        auto image = imageRecord(*projection, &body, *data, b, proofWork);
        if (failed(image))
          return failure();
        effect.set("physical_image", *image);
        effects.push_back(effect.getDictionary(module->getContext()));
      }
    SmallVector<Attribute> accesses;
    for (Operation &body : op.getRegion(0).front())
      if (llvm::isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(body)) {
        auto projection = resolveStaticShapeProjection(body.getResult(0), body.getNextNode());
        if (failed(projection))
          return failure();
        if (llvm::isa_and_nonnull<DigitalStorageOp, PortValueOp>(
                projection->root.getDefiningOp())) {
          auto image = imageRecord(*projection, &body, *data, b, proofWork);
          if (failed(image))
            return failure();
          accesses.push_back(b.getDictionaryAttr(
              {b.getNamedAttr("operation", b.getI64IntegerAttr(data->operations.lookup(&body))),
               b.getNamedAttr("physical_image", *image)}));
        }
      }
    plan.set("access_maps", b.getArrayAttr(accesses));
    plan.set("ordered_effects", b.getArrayAttr(effects));
    plan.set("materialization",
             b.getStringAttr("retain-lexical-read-point-and-explicit-body-temporaries"));
    if (budget->eligible) {
      auto lowered = materializeDigitalLoopUnrolled(&op);
      if (failed(lowered))
        return failure();
      Operation *expandedOwner = nullptr;
      (*lowered)->walk([&](nodal::ModuleOp candidate) {
        if (text(candidate, "sym_name") == text(module, "sym_name"))
          expandedOwner = candidate;
      });
      if (!expandedOwner)
        return failure();
      auto rendered = print(expandedOwner);
      if (failed(rendered))
        return failure();
      plan.set("verified_expansion_sha256", b.getStringAttr(digest(*rendered)));
      for (uint64_t q = 0; q < budget->iterations; ++q)
        for (Attribute effect : effects) {
          NamedAttrList entry(llvm::cast<DictionaryAttr>(effect));
          entry.set("iteration", b.getI64IntegerAttr(q));
          entry.set("induction", b.getI64IntegerAttr(domain->lower.bounds.lower +
                                                     q * domain->step.bounds.lower));
          expanded.push_back(entry.getDictionary(module->getContext()));
        }
    }
    plan.set("expanded_effects", b.getArrayAttr(expanded));
    plans.push_back(plan.getDictionary(module->getContext()));
  }
  NamedAttrList result;
  result.set("version", b.getI64IntegerAttr(1));
  result.set("policy", b.getStringAttr("finite-digital-v1-i32-u8-n65536-e262144-b8388608"));
  result.set("stage", b.getStringAttr("target-neutral"));
  result.set("target_emission", b.getStringAttr("not-implemented"));
  result.set("inventory", b.getArrayAttr(data->records));
  result.set("plans", b.getArrayAttr(plans));
  auto report = result.getDictionary(module->getContext());
  BoundedText encoded;
  report.print(encoded);
  if (encoded.exhausted) {
    (void)reject(module, "serialized target-neutral plan exceeds its byte policy");
    return failure();
  }
  return report;
}
LogicalResult verifyDigitalLoopPlanningReport(Operation *module) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return failure();
  Attribute attached = module->getAttr(reportKey);
  auto root = module->getParentOfType<mlir::ModuleOp>();
  if (root && root->hasAttr(reportKey))
    return reject(module, "loop plans belong only to their owning nodal module");
  bool misplaced = false;
  module->walk([&](Operation *op) {
    if (op != module && op->hasAttr(reportKey))
      misplaced = true;
  });
  if (misplaced)
    return reject(module, "loop plans belong only to their owning module");
  if (!attached)
    return success();
  auto fresh = analyzeDigitalLoopPlans(module);
  if (failed(fresh) || attached != *fresh)
    return reject(module, "loop plan is stale, forged or inconsistent with actual ordered SSA");
  return success();
}
} // namespace nodal
