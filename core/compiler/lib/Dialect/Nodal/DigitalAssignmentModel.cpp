#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
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
};
struct Node {
  Value value;
  Operation *use;
  SmallVector<unsigned, 2> dependencies;
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
  FailureOr<unsigned> valueNode(Value value, Operation *use);
  LogicalResult depend(unsigned node, Value value, Operation *use);
  LogicalResult edge(unsigned from, unsigned to);
  LogicalResult expand(unsigned node);
  FailureOr<Operation *> storageOwner(const StaticShapeProjection &access, Operation *use);
  FailureOr<bool> disjoint(const StaticShapeProjection &a, const StaticShapeProjection &b,
                           Operation *use);
  LogicalResult read(unsigned node, const StaticShapeProjection &access, Operation *use);

  Operation *module;
  DigitalAssignmentProofLimits limits;
  unsigned edgeCount = 0, relationCount = 0;
  std::vector<Node> nodes;
  std::vector<Write> writes;
  llvm::DenseMap<Value, unsigned> values;
  llvm::DenseMap<Operation *, SmallVector<unsigned, 2>> writers;
  llvm::DenseMap<Value, SmallVector<std::pair<Value, Operation *>, 2>> interfaceDependencies;
  llvm::DenseMap<std::pair<Value, Value>, bool> relations;
};

FailureOr<unsigned> AssignmentQuery::valueNode(Value value, Operation *use) {
  auto found = values.find(value);
  if (found != values.end())
    return found->second;
  if (!value || nodes.size() >= limits.maximumNodes) {
    (void)reject(use, "ordinary assignment dependency graph exceeds its finite node budget");
    return failure();
  }
  unsigned index = nodes.size();
  nodes.push_back({value, use, {}});
  values.try_emplace(value, index);
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
  auto dependency = valueNode(value, use);
  return failed(dependency) ? failure() : edge(node, *dependency);
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

LogicalResult AssignmentQuery::read(unsigned node, const StaticShapeProjection &access,
                                    Operation *use) {
  auto owner = storageOwner(access, use);
  if (failed(owner))
    return reject(use, "ordinary assignment read has no verified storage owner");
  if (!*owner)
    return failure();
  // State reads terminate the combinational graph. Initializers and next-state
  // intent are not same-cycle incoming edges, regardless of source order.
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
  Operation *coverageUse = use;
  while (coverageUse->getParentOp() != module)
    coverageUse = coverageUse->getParentOp();
  for (unsigned index : found->second) {
    const Write &write = writes[index];
    // Both actual chains must dominate this proof location. A read can occur
    // before an eventual writer, so compare at the later assignment/use.
    Operation *visibleUse = use;
    while (visibleUse->getParentOp() != module)
      visibleUse = visibleUse->getParentOp();
    Operation *at = write.operation->isBeforeInBlock(visibleUse) ? visibleUse : write.operation;
    auto separate = disjoint(access, write.target, at);
    if (failed(separate) || (!*separate && failed(edge(node, write.node))))
      return failure();
    driven |= !*separate;
    wholeWriter |= write.target.wholeRoot;
    if (write.target.root == access.root)
      coverage.push_back(write.target);
    if (coverageUse->isBeforeInBlock(write.operation))
      coverageUse = write.operation;
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
  if (llvm::isa<DigitalStorageOp, PortValueOp>(definition) ||
      (projected && definition->getParentOp() == module)) {
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
                    name == "nodal.const_expr" || name == "nodal.const_parameter_ref";
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

LogicalResult AssignmentQuery::verify() {
  if (!llvm::isa_and_nonnull<ModuleOp>(module) || module->getNumRegions() != 1 ||
      !module->getRegion(0).hasOneBlock())
    return failure();
  for (Operation &operation : module->getRegion(0).front()) {
    if (!llvm::isa<DigitalAssignOp>(operation))
      continue;
    if (failed(verifyDigitalAssignmentOperation(&operation)))
      return failure();
    if (text(&operation, "kind") == "register_next") {
      // State remains a same-cycle boundary, but the actual next-state RHS is
      // consumed now and must retain ordinary dependency/driver coverage.
      if (nodes.size() >= limits.maximumNodes)
        return reject(&operation, "ordinary assignment graph exceeds its finite node budget");
      unsigned node = nodes.size();
      nodes.push_back({{}, &operation, {}});
      if (failed(depend(node, operation.getOperand(1), &operation)))
        return failure();
      continue;
    }
    if (nodes.size() >= limits.maximumNodes)
      return reject(&operation, "ordinary assignment graph exceeds its finite node budget");
    auto target = resolveStaticShapeProjection(operation.getOperand(0), &operation);
    if (failed(target))
      return failure();
    auto owner = storageOwner(*target, &operation);
    if (failed(owner) || !*owner)
      return reject(&operation, "ordinary assignment target lacks its actual writable owner");
    unsigned node = nodes.size();
    nodes.push_back({{}, &operation, {}});
    for (unsigned previous : writers[*owner]) {
      auto separate = disjoint(writes[previous].target, *target, &operation);
      if (failed(separate))
        return failure();
      if (!*separate)
        return reject(&operation,
                      "ordinary digital assignments have overlapping or unproved-disjoint drivers");
    }
    writers[*owner].push_back(writes.size());
    writes.push_back({&operation, std::move(*target), node});
    if (failed(depend(node, operation.getOperand(1), &operation)))
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
