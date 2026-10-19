#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/DenseMap.h"

#include <limits>
#include <vector>

using namespace mlir;
namespace nodal {
namespace {
LogicalResult reject(Operation *operation, StringRef message) {
  return operation->emitOpError("NODAL-DIGITAL-055-010: ") << message;
}
StringRef text(Operation *operation, StringRef name) {
  auto attribute = operation->getAttrOfType<StringAttr>(name);
  return attribute ? attribute.getValue() : StringRef();
}
bool named(Operation *operation, StringRef name) {
  return operation && operation->getName().getStringRef() == name;
}
bool projection(Operation *operation) {
  return llvm::isa<ShapeIndexOp, ShapeSliceOp, ShapeViewOp>(operation);
}

struct Write {
  Operation *operation;
  StaticShapeProjection target;
  unsigned node;
  Operation *loop = nullptr;
  unsigned ordinal = 0;
};
struct Node {
  Value value;
  Operation *use;
  SmallVector<unsigned, 2> dependencies;
  Operation *family = nullptr;
};

/// One graph for actual SSA dependencies and implicit reads through ordinary
/// storage. Value nodes are memoized without copying transitive leaf sets.
/// Assignment nodes are physical writes; a projected read only links writers
/// that cannot be proved disjoint by the canonical shape owner. Every traversal
/// and cycle check is iterative, so deep/shared DAGs do not consume C++ stack.
class AssignmentQuery {
public:
  AssignmentQuery(Operation *module, DigitalAssignmentProofLimits limits)
      : module(module), limits(limits) {}
  LogicalResult verify();

private:
  FailureOr<unsigned> valueNode(Value value, Operation *use, Operation *family = nullptr);
  LogicalResult depend(unsigned node, Value value, Operation *use);
  LogicalResult edge(unsigned from, unsigned to);
  LogicalResult expand(unsigned node);
  FailureOr<Operation *> storageOwner(const StaticShapeProjection &access, Operation *use);
  FailureOr<bool> disjoint(const StaticShapeProjection &a, const StaticShapeProjection &b,
                           Operation *use);
  LogicalResult read(unsigned node, const StaticShapeProjection &access, Operation *use);
  LogicalResult collect(Operation *operation, Operation *loop, unsigned ordinal);
  LogicalResult predecessor(unsigned node, const StaticShapeProjection &access, const Write &write,
                            Operation *use);
  Operation *anchor(Operation *operation) const {
    while (operation && operation->getParentOp() != module)
      operation = operation->getParentOp();
    return operation;
  }

  Operation *module;
  DigitalAssignmentProofLimits limits;
  unsigned edgeCount = 0, relationCount = 0;
  std::vector<Node> nodes;
  std::vector<Write> writes;
  struct ReadUse {
    unsigned node;
    Value value;
    Operation *use;
  };
  SmallVector<ReadUse> orderedReadUses;
  llvm::DenseMap<std::pair<Value, Operation *>, unsigned> values;
  llvm::DenseMap<Operation *, SmallVector<unsigned, 2>> writers;
  llvm::DenseMap<Value, SmallVector<std::pair<Value, Operation *>, 2>> interfaceDependencies;
  llvm::DenseMap<std::pair<Value, Value>, bool> relations;
};

FailureOr<unsigned> AssignmentQuery::valueNode(Value value, Operation *use, Operation *family) {
  auto key = std::make_pair(value, family);
  auto found = values.find(key);
  if (found != values.end())
    return found->second;
  if (!value || nodes.size() >= limits.maximumNodes) {
    (void)reject(use, "ordinary assignment dependency graph exceeds its finite node budget");
    return failure();
  }
  unsigned index = nodes.size();
  nodes.push_back({value, use, {}, family});
  values.try_emplace(key, index);
  return index;
}
LogicalResult AssignmentQuery::edge(unsigned from, unsigned to) {
  if (++edgeCount > limits.maximumEdges)
    return reject(nodes[from].use, "ordinary assignment dependency graph exceeds its edge budget");
  nodes[from].dependencies.push_back(to);
  return success();
}
LogicalResult AssignmentQuery::depend(unsigned node, Value value, Operation *use) {
  // Verify each actual edge's use, even when its value node is already cached.
  // Synthetic roots below are separately certified by the interface owner.
  if (failed(verifyCollectionValueScope(value, use)))
    return reject(use, "dependency value must retain exact module and lexical ownership");
  auto dependency = valueNode(value, use, nodes[node].family);
  if (failed(dependency))
    return failure();
  if (nodes[node].family &&
      (llvm::isa<BlockArgument>(value) ||
       llvm::isa_and_nonnull<DigitalStorageOp, PortValueOp, ShapeIndexOp, ShapeSliceOp,
                             ShapeViewOp>(value.getDefiningOp())))
    orderedReadUses.push_back({*dependency, value, use});
  return edge(node, *dependency);
}

FailureOr<Operation *> AssignmentQuery::storageOwner(const StaticShapeProjection &access,
                                                     Operation *use) {
  Operation *root = access.root.getDefiningOp();
  if (!root || root->getParentOp() != module)
    return failure();
  if (llvm::isa<DigitalStorageOp>(root)) {
    if (failed(verifyDigitalStorageOperation(root)))
      return failure();
    return root;
  }
  if (!llvm::isa<PortValueOp>(root))
    return static_cast<Operation *>(nullptr);
  auto reference = root->getAttrOfType<FlatSymbolRefAttr>("port");
  Operation *port = reference ? SymbolTable::lookupSymbolIn(module, reference) : nullptr;
  auto type = port ? port->getAttrOfType<TypeAttr>("type") : TypeAttr();
  if (!llvm::isa_and_nonnull<PortOp>(port) || port->getParentOp() != module || !type ||
      type.getValue() != access.root.getType() || failed(llvm::cast<PortValueOp>(root).verify())) {
    (void)reject(use, "ordinary assignment read requires actual exact owned port provenance");
    return failure();
  }
  // Distinct port_value SSA aliases do not create distinct physical outputs.
  return port;
}

FailureOr<bool> AssignmentQuery::disjoint(const StaticShapeProjection &a,
                                          const StaticShapeProjection &b, Operation *use) {
  Value first = a.projections.empty() ? a.root : a.projections.back()->getResult(0);
  Value second = b.projections.empty() ? b.root : b.projections.back()->getResult(0);
  auto key = std::make_pair(first, second);
  auto known = relations.find(key);
  if (known != relations.end())
    return known->second;
  if (++relationCount > limits.maximumRelations) {
    (void)reject(use, "ordinary assignment physical relations exceed their finite proof budget");
    return failure();
  }
  // Same physical port with independently authored root aliases cannot use a
  // different-root shape proof. Until canonical alias rebasing exists, retain
  // the possible overlap. This is conservative, never independent fake storage.
  bool independent = false;
  if (a.root == b.root) {
    auto relation =
        compareStaticShapeProjections(a, b, use, {limits.maximumShapeWork, limits.maximumNodes});
    if (relation.status == ShapeRegionProofStatus::Invalid ||
        relation.status == ShapeRegionProofStatus::ResourceLimit) {
      (void)reject(use, "ordinary assignment physical projection proof is invalid or exhausted");
      return failure();
    }
    independent = relation.status == ShapeRegionProofStatus::Proved &&
                  relation.overlap == ShapeRegionOverlap::Disjoint;
  }
  relations.try_emplace(key, independent);
  relations.try_emplace(std::make_pair(second, first), independent);
  return independent;
}

LogicalResult AssignmentQuery::predecessor(unsigned node, const StaticShapeProjection &access,
                                           const Write &write, Operation *use) {
  auto owner = storageOwner(write.target, use);
  if (failed(owner) || !*owner)
    return failure();
  const Write *seed = nullptr;
  for (unsigned index : writers[*owner]) {
    const Write &candidate = writes[index];
    if (candidate.loop || !candidate.operation->isBeforeInBlock(write.loop))
      continue;
    if (verifyStaticShapeIterationPredecessorSeed(access, write.target, candidate.target, use,
                                                  {limits.maximumShapeWork, limits.maximumNodes}) ==
        ShapeRegionProofStatus::Proved) {
      if (seed)
        return reject(use, "predecessor chain requires exactly one proved ordinary seed");
      seed = &candidate;
    }
  }
  if (!seed)
    return reject(use, "predecessor chain lacks its exact unique seed authored before the loop");
  // Keep the seed and every external edge in the global graph. Only the exact
  // same-family strictly descending ordinal edge is absent from this graph.
  return edge(node, seed->node);
}

LogicalResult AssignmentQuery::read(unsigned node, const StaticShapeProjection &access,
                                    Operation *use) {
  Operation *consumer = nodes[node].family;
  Operation *consumerLoop =
      consumer && isBoundedDigitalLoop(consumer->getParentOp()) ? consumer->getParentOp() : nullptr;
  if (consumerLoop) {
    auto domain = resolveBoundedIterationDomain(consumerLoop);
    if (failed(domain))
      return failure();
    if (!domain->maximumTripCount)
      return success(); // Source/body validation has already run for the empty template.
  }
  auto owner = storageOwner(access, use);
  if (failed(owner))
    return reject(use, "ordinary assignment read has no verified storage owner");
  if (!*owner)
    return failure();
  if (llvm::isa<DigitalStorageOp>(*owner) && text(*owner, "kind") == "register")
    return success();
  const bool needsDriver =
      llvm::isa<DigitalStorageOp>(*owner) || text(*owner, "direction") == "output";
  auto found = writers.find(*owner);
  if (found == writers.end()) {
    if (needsDriver)
      return reject(use, "ordinary assignment consumes an undriven combinational wire");
    return success();
  }
  bool driven = false, wholeWriter = false;
  SmallVector<StaticShapeProjection, 4> coverage;
  Operation *coverageUse = anchor(use);
  for (unsigned index : found->second) {
    const Write &write = writes[index];
    if (write.loop) {
      auto domain = resolveBoundedIterationDomain(write.loop);
      if (failed(domain))
        return failure();
      if (!domain->maximumTripCount)
        continue; // Retain authored output obligation, but no attained driver.
    }
    Operation *writeAnchor = anchor(write.operation), *visibleUse = anchor(use);
    Operation *at = writeAnchor->isBeforeInBlock(visibleUse) ? visibleUse : writeAnchor;
    auto position =
        consumerLoop == write.loop && write.loop
            ? compareStaticShapeIterationPositions(access, write.target, use,
                                                   {limits.maximumShapeWork, limits.maximumNodes})
            : FailureOr<int64_t>(failure());
    if (consumerLoop && consumerLoop == write.loop && failed(position)) {
      auto domain = resolveBoundedIterationDomain(write.loop);
      if (succeeded(domain) && domain->maximumTripCount <= 1 && access.root == write.target.root &&
          access.canonicalKey == write.target.canonicalKey)
        position = int64_t(0); // Exact shared Shape identity in the only possible occurrence.
    }
    bool descending = consumer == write.operation && succeeded(position) && *position == -1;
    if (descending && failed(predecessor(node, access, write, use)))
      return failure();
    auto separate = disjoint(access, write.target, at);
    if (failed(separate))
      return failure();
    if (!*separate) {
      if (consumerLoop && consumerLoop == write.loop && !descending) {
        Operation *readPoint = use;
        while (readPoint && readPoint->getParentOp() != consumerLoop)
          readPoint = readPoint->getParentOp();
        if (failed(position) || *position != 0 || !readPoint ||
            !write.operation->isBeforeInBlock(readPoint))
          return reject(use, "ordered loop read requires an earlier same-iteration writer or "
                             "certified seeded predecessor");
      }
      if (!descending && failed(edge(node, write.node)))
        return failure();
    }
    driven |= !*separate;
    wholeWriter |= write.target.wholeRoot && !write.loop;
    bool attainedForEverySetting = true;
    if (write.loop) {
      auto domain = resolveBoundedIterationDomain(write.loop);
      StaticShapeRegion image;
      auto status = resolveStaticShapeIterationImage(
          write.target, write.operation, image, {limits.maximumShapeWork, limits.maximumNodes});
      // An invariant C<=1 destination has no strided image. If C can be zero,
      // it cannot establish unconditional coverage; retain its possible graph
      // edges and write conflicts without inventing an attained write.
      attainedForEverySetting = status == ShapeRegionProofStatus::Proved && image.iteration;
      attainedForEverySetting |=
          succeeded(domain) && domain->lower.bounds.upper < domain->upperExclusive.bounds.lower;
    }
    if (write.target.root == access.root && attainedForEverySetting)
      coverage.push_back(write.target);
    if (coverageUse->isBeforeInBlock(writeAnchor))
      coverageUse = writeAnchor;
  }
  if (!driven && needsDriver)
    return reject(use, "ordinary assignment consumes an undriven combinational wire region");
  if (needsDriver && !wholeWriter &&
      verifyStaticShapeProjectionCoverage(access, coverage, coverageUse,
                                          {limits.maximumShapeWork, limits.maximumNodes}) !=
          ShapeRegionProofStatus::Proved)
    return reject(use, "ordinary assignment read lacks proved complete physical driver coverage");
  return success();
}

LogicalResult AssignmentQuery::expand(unsigned node) {
  Value value = nodes[node].value;
  Operation *use = nodes[node].use;
  if (!value)
    return success(); // Assignment dependencies were installed during collection.
  if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
    // A captured storage reference is still read at this actual body use, not
    // when its reference crossed the region header. Preserve that read point.
    auto access = resolveStaticShapeProjection(argument, use);
    if (succeeded(access) &&
        llvm::isa_and_nonnull<DigitalStorageOp, PortValueOp>(access->root.getDefiningOp())) {
      for (Operation *step : access->projections)
        for (Value selector : step->getOperands().drop_front())
          if (failed(depend(node, selector, step)))
            return failure();
      return read(node, *access, use);
    }
    SmallVector<Value, 4> sources;
    if (failed(digitalCollectionArgumentDependencies(argument, sources)))
      return reject(use, "dependency argument has no verified collection operand provenance");
    Operation *collection = argument.getOwner()->getParentOp();
    for (Value source : sources)
      if (failed(depend(node, source, collection)))
        return failure();
    return success();
  }
  Operation *definition = value.getDefiningOp();
  if (!definition || definition->getParentOfType<ModuleOp>() != module)
    return reject(use, "dependency value must retain exact module and lexical ownership");
  auto interface = interfaceDependencies.find(value);
  if (interface != interfaceDependencies.end()) {
    for (auto [source, at] : interface->second)
      if (failed(depend(node, source, at)))
        return failure();
    return success();
  }
  if (failed(verifyCollectionValueScope(value, use)))
    return reject(use, "dependency value must retain exact module and lexical ownership");
  const bool projected = projection(definition);
  if (llvm::isa<DigitalStorageOp, PortValueOp>(definition) || projected) {
    auto access = resolveStaticShapeProjection(value, use);
    if (failed(access))
      return reject(use, "dependency projection has no verified actual root");
    Operation *root = access->root.getDefiningOp();
    if (llvm::isa<DigitalStorageOp, PortValueOp>(root)) {
      for (Operation *step : access->projections)
        for (Value selector : step->getOperands().drop_front())
          if (failed(depend(node, selector, step)))
            return failure();
      return read(node, *access, use);
    }
  }
  if (named(definition, "nodal.collection")) {
    if (failed(verifyDigitalCollectionOperation(definition)))
      return failure();
    Operation &yield = definition->getRegion(0).front().back();
    return depend(node, yield.getOperand(0), &yield);
  }
  StringRef name = definition->getName().getStringRef();
  const bool pure = projected || name == "nodal.digital_expr" || name == "nodal.enum_expr" ||
                    name == "nodal.constant" || name == "nodal.const_literal" ||
                    name == "nodal.const_expr" || name == "nodal.const_parameter_ref" ||
                    name == "nodal.iteration_index";
  if (!pure || definition->getNumRegions() || definition->getNumSuccessors() ||
      definition->getNumResults() != 1 || definition->hasAttr("unit"))
    return reject(definition,
                  "ordinary assignment dependency is outside the verified combinational profile; "
                  "capability-only operations do not acquire execution semantics");
  for (Value operand : definition->getOperands())
    if (failed(depend(node, operand, definition)))
      return failure();
  return success();
}

LogicalResult AssignmentQuery::collect(Operation *operation, Operation *loop, unsigned ordinal) {
  if (failed(verifyDigitalAssignmentOperation(operation)))
    return failure();
  if (text(operation, "kind") == "register_next") {
    // State remains a same-cycle boundary, but the actual next-state RHS is
    // consumed now and must retain ordinary dependency/driver coverage.
    if (nodes.size() >= limits.maximumNodes)
      return reject(operation, "ordinary assignment graph exceeds its finite node budget");
    unsigned node = nodes.size();
    nodes.push_back({{}, operation, {}, loop ? operation : nullptr});
    if (failed(depend(node, operation->getOperand(1), operation)))
      return failure();
    return success();
  }
  if (nodes.size() >= limits.maximumNodes)
    return reject(operation, "ordinary assignment graph exceeds its finite node budget");
  auto target = resolveStaticShapeProjection(operation->getOperand(0), operation);
  if (failed(target))
    return failure();
  auto owner = storageOwner(*target, operation);
  if (failed(owner) || !*owner)
    return reject(operation, "ordinary assignment target lacks its actual writable owner");
  if (loop) {
    StaticShapeRegion image;
    auto status = resolveStaticShapeIterationImage(*target, operation, image,
                                                   {limits.maximumShapeWork, limits.maximumNodes});
    auto domain = resolveBoundedIterationDomain(loop);
    if (failed(domain) || (domain->maximumTripCount > 1 &&
                           (status != ShapeRegionProofStatus::Proved || !image.iteration)))
      return reject(operation, "loop target must prove an injective physical write family");
  }
  unsigned node = nodes.size();
  nodes.push_back({{}, operation, {}, loop ? operation : nullptr});
  for (unsigned previous : writers[*owner]) {
    auto active = [&](Operation *scope) -> FailureOr<bool> {
      if (!scope)
        return true;
      auto domain = resolveBoundedIterationDomain(scope);
      return failed(domain) ? FailureOr<bool>(failure())
                            : FailureOr<bool>(domain->maximumTripCount != 0);
    };
    auto currentActive = active(loop), previousActive = active(writes[previous].loop);
    if (failed(currentActive) || failed(previousActive))
      return failure();
    if (!*currentActive || !*previousActive)
      continue;
    auto separate = disjoint(writes[previous].target, *target, operation);
    if (failed(separate))
      return failure();
    if (!*separate)
      return reject(operation,
                    "ordinary digital assignments have overlapping or unproved-disjoint drivers");
  }
  writers[*owner].push_back(writes.size());
  writes.push_back({operation, std::move(*target), node, loop, ordinal});
  if (failed(depend(node, operation->getOperand(1), operation)))
    return failure();
  return success();
}

LogicalResult AssignmentQuery::verify() {
  if (!llvm::isa_and_nonnull<ModuleOp>(module) || module->getNumRegions() != 1 ||
      !module->getRegion(0).hasOneBlock())
    return failure();
  for (Operation &operation : module->getRegion(0).front()) {
    if (llvm::isa<DigitalAssignOp>(operation)) {
      if (failed(collect(&operation, nullptr, 0)))
        return failure();
    } else if (isBoundedDigitalLoop(&operation)) {
      if (failed(verifyDigitalLoopOperation(&operation)))
        return failure();
      unsigned ordinal = 0;
      for (Operation &body : operation.getRegion(0).front())
        if (llvm::isa<DigitalAssignOp>(body) && failed(collect(&body, &operation, ordinal++)))
          return failure();
    }
  }
  // Written externally observable outputs are final whole-value reads. Merely
  // declaring an output remains a capability fixture, without an execution duty.
  for (const auto &entry : writers) {
    if (!llvm::isa<PortOp>(entry.first) || text(entry.first, "direction") != "output")
      continue;
    const Write &first = writes[entry.second.front()];
    Operation *at = &module->getRegion(0).front().back();
    auto output = resolveStaticShapeProjection(first.target.root, at);
    if (failed(output) || nodes.size() >= limits.maximumNodes)
      return reject(at, "written output final-read obligation has no bounded physical root");
    unsigned node = nodes.size();
    nodes.push_back({{}, at, {}});
    if (failed(read(node, *output, at)))
      return failure();
  }
  SmallVector<DigitalInterfaceValueDependencies> interface;
  if (failed(collectDigitalInterfaceDependencies(module, interface)))
    return failure();
  for (auto &entry : interface) {
    interfaceDependencies.try_emplace(entry.value, std::move(entry.sources));
    // Actual typed member/net values are graph roots even without an ordinary
    // digital_assign consumer. Otherwise standalone drive/read feedback would
    // disappear. InterfaceModel already certified each root's ownership and
    // implicit edge uses; empty boundary roots retain its definedness policy.
    if (failed(valueNode(entry.value, entry.value.getDefiningOp())))
      return failure();
  }
  for (unsigned cursor = 0; cursor < nodes.size(); ++cursor)
    if (failed(expand(cursor)))
      return failure();
  // Memoizing a storage-reference value must not merge its distinct authored
  // reads. Recheck every actual loop use after the complete writer inventory and
  // SSA graph exist; a shared DAG may discover a later read before an early one.
  for (const ReadUse &occurrence : orderedReadUses) {
    auto access = resolveStaticShapeProjection(occurrence.value, occurrence.use);
    if (succeeded(access) &&
        llvm::isa_and_nonnull<DigitalStorageOp, PortValueOp>(access->root.getDefiningOp()) &&
        failed(read(occurrence.node, *access, occurrence.use)))
      return failure();
  }
  // Kahn's algorithm with reversed semantic edges still detects the same cycles.
  // It visits each represented node/edge exactly once and does not recurse.
  std::vector<unsigned> incoming(nodes.size(), 0), pending;
  for (const Node &node : nodes)
    for (unsigned dependency : node.dependencies)
      ++incoming[dependency];
  for (unsigned index = 0; index < nodes.size(); ++index)
    if (!incoming[index])
      pending.push_back(index);
  for (unsigned cursor = 0; cursor < pending.size(); ++cursor)
    for (unsigned dependency : nodes[pending[cursor]].dependencies)
      if (--incoming[dependency] == 0)
        pending.push_back(dependency);
  if (pending.size() != nodes.size())
    for (const Write &write : writes)
      if (incoming[write.node])
        return reject(write.operation,
                      "ordinary digital assignment contains a combinational storage-read cycle");
  if (pending.size() != nodes.size())
    return reject(module, "digital dependency graph contains a combinational value cycle");
  return success();
}
} // namespace

LogicalResult verifyDigitalAssignmentModel(Operation *module, DigitalAssignmentProofLimits limits) {
  return AssignmentQuery(module, limits).verify();
}
} // namespace nodal
