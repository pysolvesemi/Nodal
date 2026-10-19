#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/EnumSwitchModel.h"
#include "nodal/Dialect/Nodal/FlatFSMModel.h"
#include "nodal/Dialect/Nodal/MemoryModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Dialect/Nodal/StaticPredicateModel.h"
#include "nodal/Dialect/Nodal/StructuralIfModel.h"

#include "llvm/ADT/DenseMap.h"

#include <algorithm>
#include <limits>
#include <map>
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
  unsigned guard = 0;
};
struct Node {
  Value value;
  Operation *use;
  SmallVector<unsigned, 2> dependencies;
  Operation *family = nullptr;
  unsigned guard = 0;
  Operation *assignment = nullptr;
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
  FailureOr<DigitalDependencyGraph> graph(ArrayRef<std::pair<Value, Operation *>> seeds) {
    DigitalDependencyGraph result;
    for (auto [value, use] : seeds) {
      auto root = valueNode(value, use);
      if (failed(root))
        return failure();
      result.roots.push_back(*root);
    }
    if (failed(verify()))
      return failure();
    for (Node &node : nodes)
      result.nodes.push_back({node.value, node.use, std::move(node.dependencies),
                              interfaceDomains.lookup(node.value)});
    return result;
  }

private:
  FailureOr<unsigned> valueNode(Value value, Operation *use, Operation *family = nullptr,
                                unsigned guard = 0);
  FailureOr<unsigned> assignmentNode(Operation *operation, Operation *loop, unsigned guard);
  static constexpr unsigned impossibleGuard = std::numeric_limits<unsigned>::max();
  using Guard = std::vector<std::pair<unsigned, unsigned>>;
  struct Choice {
    Operation *operation;
    Operation *occurrence;
    StaticPredicateActivity activity;
  };
  FailureOr<unsigned> choice(Operation *operation);
  FailureOr<unsigned> extend(unsigned guard, unsigned condition, unsigned arm, Operation *use);
  FailureOr<unsigned> merge(unsigned left, unsigned right, Operation *use);
  FailureOr<unsigned> ancestry(Operation *operation);
  bool contradicts(unsigned left, unsigned right, bool generated = true) const;
  LogicalResult guardWork(Operation *use);
  LogicalResult readAlternative(unsigned node, const StaticShapeProjection &access, Operation *use,
                                unsigned guard);
  FailureOr<GeneratedDomain> iterationDomain(Operation *operation) {
    if (auto found = domains.find(operation); found != domains.end())
      return found->second;
    auto domain = isBoundedDigitalLoop(operation) ? resolveBoundedIterationDomain(operation)
                                                  : resolveGeneratedDomain(operation);
    if (succeeded(domain))
      domains.try_emplace(operation, *domain);
    return domain;
  }
  Operation *iterationScope(Operation *operation) const {
    for (Operation *scope = operation->getParentOp(); scope && scope != module;
         scope = scope->getParentOp())
      if (isBoundedDigitalLoop(scope) || llvm::isa<GenerateOp>(scope))
        return scope;
    return nullptr;
  }
  LogicalResult depend(unsigned node, Value value, Operation *use);
  LogicalResult edge(unsigned from, unsigned to);
  LogicalResult expand(unsigned node);
  FailureOr<Operation *> storageOwner(const StaticShapeProjection &access, Operation *use);
  FailureOr<bool> disjoint(const StaticShapeProjection &a, const StaticShapeProjection &b,
                           Operation *use);
  LogicalResult read(unsigned node, const StaticShapeProjection &access, Operation *use);
  LogicalResult collect(Operation *operation, Operation *loop, unsigned ordinal);
  LogicalResult predecessor(unsigned node, const StaticShapeProjection &access, const Write &write,
                            Operation *use, unsigned guard);
  Operation *anchor(Operation *operation) const {
    while (operation && operation->getParentOp() != module)
      operation = operation->getParentOp();
    return operation;
  }
  Operation *physicalUse(Operation *left, Operation *right) const {
    left = anchor(left);
    right = anchor(right);
    return left->isBeforeInBlock(right) ? right : left;
  }

  Operation *module;
  DigitalAssignmentProofLimits limits;
  unsigned edgeCount = 0, relationCount = 0, guardWorkCount = 0;
  std::vector<Guard> guards = {Guard{}};
  std::map<Guard, unsigned> guardIds = {{Guard{}, 0}};
  std::vector<Choice> choices;
  llvm::DenseMap<Operation *, unsigned> choiceIds, ancestryIds;
  llvm::DenseMap<Operation *, GeneratedDomain> domains;
  llvm::DenseMap<std::pair<Operation *, unsigned>, unsigned> assignmentNodes;
  std::vector<Node> nodes;
  std::vector<Write> writes;
  struct ReadUse {
    unsigned node;
    Value value;
    Operation *use;
  };
  SmallVector<ReadUse> orderedReadUses;
  llvm::DenseMap<std::pair<Value, std::pair<Operation *, unsigned>>, unsigned> values;
  llvm::DenseMap<Operation *, SmallVector<unsigned, 2>> writers;
  llvm::DenseMap<Value, SmallVector<std::pair<Value, Operation *>, 2>> interfaceDependencies;
  llvm::DenseMap<Value, FlatSymbolRefAttr> interfaceDomains;
  llvm::DenseMap<std::pair<Value, Value>, bool> relations;
};

LogicalResult AssignmentQuery::guardWork(Operation *use) {
  if (guardWorkCount >= limits.maximumGuardWork)
    return reject(use, "structural assignment alternatives exceed their finite work budget");
  ++guardWorkCount;
  return success();
}
FailureOr<unsigned> AssignmentQuery::choice(Operation *operation) {
  if (auto found = choiceIds.find(operation); found != choiceIds.end())
    return found->second;
  if (failed(guardWork(operation)))
    return failure();
  if (choices.size() >= limits.maximumGuardContexts) {
    (void)reject(operation, "structural assignment choices exceed their finite context budget");
    return failure();
  }
  auto header = inspectStructuralIfHeader(operation);
  if (failed(header))
    return failure();
  auto facts = analyzeStaticPredicate(operation->getOperand(0), operation);
  if (failed(facts))
    return failure();
  unsigned id = choices.size();
  choices.push_back({operation, iterationScope(operation), facts->activity});
  choiceIds.try_emplace(operation, id);
  return id;
}
FailureOr<unsigned> AssignmentQuery::extend(unsigned guard, unsigned condition, unsigned arm,
                                            Operation *use) {
  if (guard == impossibleGuard)
    return impossibleGuard;
  if (failed(guardWork(use)))
    return failure();
  auto activity = choices[condition].activity;
  if (activity == StaticPredicateActivity::Unreachable ||
      (activity == StaticPredicateActivity::AlwaysTrue && arm != 0) ||
      (activity == StaticPredicateActivity::AlwaysFalse && arm != 1))
    return impossibleGuard;
  Guard next = guards[guard];
  auto at = std::lower_bound(next.begin(), next.end(), std::make_pair(condition, 0u));
  if (at != next.end() && at->first == condition)
    return at->second == arm ? guard : impossibleGuard;
  if (next.size() >= limits.maximumGuardDepth) {
    (void)reject(use, "structural assignment guard exceeds its finite lexical depth budget");
    return failure();
  }
  next.insert(at, {condition, arm});
  if (auto found = guardIds.find(next); found != guardIds.end())
    return found->second;
  if (guards.size() >= limits.maximumGuardContexts) {
    (void)reject(use, "structural assignment graph exceeds its finite guard-context budget");
    return failure();
  }
  unsigned id = guards.size();
  guards.push_back(next);
  guardIds.emplace(std::move(next), id);
  return id;
}
FailureOr<unsigned> AssignmentQuery::merge(unsigned left, unsigned right, Operation *use) {
  if (left == impossibleGuard || right == impossibleGuard)
    return impossibleGuard;
  // Copy because extend may grow the intern table.
  Guard additions = guards[right];
  for (auto [condition, arm] : additions) {
    auto next = extend(left, condition, arm, use);
    if (failed(next))
      return failure();
    left = *next;
  }
  return left;
}
bool AssignmentQuery::contradicts(unsigned left, unsigned right, bool generated) const {
  if (left == impossibleGuard || right == impossibleGuard)
    return true;
  for (auto [condition, arm] : guards[left]) {
    if (!generated && choices[condition].occurrence)
      continue;
    for (auto [other, opposite] : guards[right])
      if (condition == other && arm != opposite)
        return true;
  }
  return false;
}
FailureOr<unsigned> AssignmentQuery::ancestry(Operation *operation) {
  if (auto found = ancestryIds.find(operation); found != ancestryIds.end())
    return found->second;
  unsigned guard = 0, depth = 0;
  for (Operation *at = operation; at && at->getParentOp() != module; at = at->getParentOp()) {
    if (++depth > limits.maximumGuardDepth) {
      (void)reject(operation, "structural assignment ancestry exceeds its finite depth budget");
      return failure();
    }
    Operation *parent = at->getParentOp();
    if (!named(parent, "nodal.structural_if"))
      continue;
    auto id = choice(parent);
    if (failed(id))
      return failure();
    auto next = extend(guard, *id, at->getParentRegion()->getRegionNumber(), operation);
    if (failed(next))
      return failure();
    guard = *next;
  }
  ancestryIds.try_emplace(operation, guard);
  return guard;
}
FailureOr<unsigned> AssignmentQuery::assignmentNode(Operation *operation, Operation *loop,
                                                    unsigned guard) {
  auto key = std::make_pair(operation, guard);
  if (auto found = assignmentNodes.find(key); found != assignmentNodes.end())
    return found->second;
  if (guard == impossibleGuard || nodes.size() >= limits.maximumNodes) {
    (void)reject(operation, "ordinary assignment dependency graph exceeds its finite node budget");
    return failure();
  }
  unsigned index = nodes.size();
  nodes.push_back({{}, operation, {}, loop ? operation : nullptr, guard, operation});
  assignmentNodes.try_emplace(key, index);
  return index;
}
FailureOr<unsigned> AssignmentQuery::valueNode(Value value, Operation *use, Operation *family,
                                               unsigned guard) {
  auto key = std::make_pair(value, std::make_pair(family, guard));
  auto found = values.find(key);
  if (found != values.end())
    return found->second;
  if (!value || nodes.size() >= limits.maximumNodes) {
    (void)reject(use, "ordinary assignment dependency graph exceeds its finite node budget");
    return failure();
  }
  unsigned index = nodes.size();
  nodes.push_back({value, use, {}, family, guard});
  values.try_emplace(key, index);
  return index;
}
LogicalResult AssignmentQuery::edge(unsigned from, unsigned to) {
  if (edgeCount >= limits.maximumEdges)
    return reject(nodes[from].use, "ordinary assignment dependency graph exceeds its edge budget");
  ++edgeCount;
  nodes[from].dependencies.push_back(to);
  return success();
}
LogicalResult AssignmentQuery::depend(unsigned node, Value value, Operation *use) {
  // Verify each actual edge's use, even when its value node is already cached.
  // Synthetic roots below are separately certified by the interface owner.
  if (failed(verifyDigitalValueScope(value, use)))
    return use->emitOpError("NODAL-DIGITAL-055-010: dependency value must retain exact module and "
                            "lexical ownership: ")
           << value;
  auto dependency = valueNode(value, use, nodes[node].family, nodes[node].guard);
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
  if (relationCount >= limits.maximumRelations) {
    (void)reject(use, "ordinary assignment physical relations exceed their finite proof budget");
    return failure();
  }
  ++relationCount;
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
                                           const Write &write, Operation *use, unsigned guard) {
  auto owner = storageOwner(write.target, use);
  if (failed(owner) || !*owner)
    return failure();
  const Write *seed = nullptr;
  for (unsigned index : writers[*owner]) {
    const Write &candidate = writes[index];
    if (candidate.loop || contradicts(guard, candidate.guard))
      continue;
    Operation *before = candidate.operation;
    while (before && before->getBlock() != write.loop->getBlock())
      before = before->getParentOp();
    if (!before || before == write.loop || !before->isBeforeInBlock(write.loop))
      continue;
    auto context = merge(guard, candidate.guard, use);
    if (failed(context))
      return failure();
    if (*context != guard)
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
  auto contextual = assignmentNode(seed->operation, seed->loop, guard);
  return failed(contextual) ? failure() : edge(node, *contextual);
}

LogicalResult AssignmentQuery::read(unsigned node, const StaticShapeProjection &access,
                                    Operation *use) {
  auto owner = storageOwner(access, use);
  if (failed(owner) || !*owner)
    return reject(use, "ordinary assignment read has no verified storage owner");
  if (llvm::isa<DigitalStorageOp>(*owner) && text(*owner, "kind") == "register")
    return success();
  auto found = writers.find(*owner);
  if (found == writers.end())
    return readAlternative(node, access, use, nodes[node].guard);
  SmallVector<unsigned, 8> pending{nodes[node].guard};
  for (unsigned cursor = 0; cursor < pending.size(); ++cursor) {
    unsigned guard = pending[cursor];
    std::optional<unsigned> unresolved;
    for (unsigned index : found->second) {
      if (failed(guardWork(use)))
        return failure();
      const Write &write = writes[index];
      if (write.guard == impossibleGuard || contradicts(guard, write.guard, false))
        continue;
      auto separate = disjoint(access, write.target, physicalUse(write.operation, use));
      if (failed(separate))
        return failure();
      if (*separate)
        continue;
      Operation *consumer = nodes[node].family;
      Operation *scope = consumer ? iterationScope(consumer) : nullptr;
      // Choices inside a generated scope describe one occurrence. Preserve
      // this obligation even after a dependency passes through a module-level
      // writer; that writer must not erase the requesting occurrence context.
      auto generatedChoice = [&](unsigned context) {
        return llvm::any_of(guards[context], [&](auto entry) {
          return choices[entry.first].occurrence == write.loop &&
                 llvm::isa_and_nonnull<GenerateOp>(write.loop);
        });
      };
      bool sameOccurrenceRequired = generatedChoice(nodes[node].guard) ||
                                    (scope == write.loop && generatedChoice(write.guard));
      if (sameOccurrenceRequired &&
          proveStaticShapeSameOccurrence(access, write.target, use,
                                         {limits.maximumShapeWork, limits.maximumNodes}) !=
              ShapeRegionProofStatus::Proved)
        return reject(use, "guarded generated dependency requires an exact same-occurrence map");
      if (contradicts(guard, write.guard))
        continue;
      for (auto [condition, arm] : guards[write.guard]) {
        bool selected =
            llvm::any_of(guards[guard], [=](auto entry) { return entry.first == condition; });
        if (!selected) {
          unresolved = condition;
          break;
        }
      }
      if (unresolved)
        break;
    }
    if (!unresolved) {
      if (failed(readAlternative(node, access, use, guard)))
        return failure();
      continue;
    }
    for (unsigned arm = 0; arm != 2; ++arm) {
      auto next = extend(guard, *unresolved, arm, use);
      if (failed(next))
        return failure();
      if (*next != impossibleGuard) {
        if (pending.size() >= limits.maximumGuardWork)
          return reject(use, "structural assignment coverage exceeds its finite state budget");
        pending.push_back(*next);
      }
    }
  }
  return success();
}

LogicalResult AssignmentQuery::readAlternative(unsigned node, const StaticShapeProjection &access,
                                               Operation *use, unsigned guard) {
  Operation *consumer = nodes[node].family;
  Operation *consumerLoop = consumer ? iterationScope(consumer) : nullptr;
  if (consumerLoop) {
    auto domain = iterationDomain(consumerLoop);
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
    if (contradicts(guard, write.guard))
      continue;
    if (write.loop) {
      auto domain = iterationDomain(write.loop);
      if (failed(domain))
        return failure();
      if (!domain->maximumTripCount)
        continue; // Retain authored output obligation, but no attained driver.
    }
    Operation *writeAnchor = anchor(write.operation);
    auto position =
        consumerLoop == write.loop && isBoundedDigitalLoop(write.loop)
            ? compareStaticShapeIterationPositions(access, write.target, use,
                                                   {limits.maximumShapeWork, limits.maximumNodes})
            : FailureOr<int64_t>(failure());
    if (consumerLoop && isBoundedDigitalLoop(consumerLoop) && consumerLoop == write.loop &&
        failed(position)) {
      auto domain = iterationDomain(write.loop);
      if (succeeded(domain) && domain->maximumTripCount <= 1 && access.root == write.target.root &&
          access.canonicalKey == write.target.canonicalKey)
        position = int64_t(0); // Exact shared Shape identity in the only possible occurrence.
    }
    bool descending = consumer == write.operation && succeeded(position) && *position == -1;
    if (descending && failed(predecessor(node, access, write, use, guard)))
      return failure();
    auto separate = disjoint(access, write.target, physicalUse(write.operation, use));
    if (failed(separate))
      return failure();
    if (*separate)
      continue;
    auto combined = merge(guard, write.guard, use);
    if (failed(combined))
      return failure();
    if (!*separate) {
      if (consumerLoop && isBoundedDigitalLoop(consumerLoop) && consumerLoop == write.loop &&
          !descending) {
        Operation *readPoint = use;
        while (readPoint && readPoint->getParentOp() != consumerLoop)
          readPoint = readPoint->getParentOp();
        if (failed(position) || *position != 0 || !readPoint ||
            !write.operation->isBeforeInBlock(readPoint))
          return reject(use, "ordered loop read requires an earlier same-iteration writer or "
                             "certified seeded predecessor");
      }
      if (!descending) {
        auto contextual = assignmentNode(write.operation, write.loop, *combined);
        if (failed(contextual) || failed(edge(node, *contextual)))
          return failure();
      }
    }
    driven |= !*separate;
    wholeWriter |= write.target.wholeRoot && !write.loop;
    bool attainedForEverySetting = true;
    if (write.loop) {
      auto domain = iterationDomain(write.loop);
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
    return nodes[node].assignment
               ? depend(node, nodes[node].assignment->getOperand(1), nodes[node].assignment)
               : success();
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
    if (failed(digitalRegionArgumentDependencies(argument, sources)))
      return reject(use, "dependency argument has no verified collection operand provenance");
    Operation *collection = argument.getOwner()->getParentOp();
    for (Value source : sources)
      if (failed(depend(node, source, collection)))
        return failure();
    return success();
  }
  Operation *definition = value.getDefiningOp();
  if (!definition || definition->getParentOfType<ModuleOp>() != module)
    return use->emitOpError("NODAL-DIGITAL-055-010: dependency value must retain exact module and "
                            "lexical ownership: ")
           << value;
  auto interface = interfaceDependencies.find(value);
  if (interface != interfaceDependencies.end()) {
    for (auto [source, at] : interface->second)
      if (failed(depend(node, source, at)))
        return failure();
    return success();
  }
  if (failed(verifyDigitalValueScope(value, use)))
    return use->emitOpError("NODAL-DIGITAL-055-010: dependency value must retain exact module and "
                            "lexical ownership: ")
           << value;
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
  if (llvm::isa<MemoryReadOp>(definition)) {
    auto port = inspectMemoryPort(definition);
    if (failed(port))
      return failure();
    // Synchronous response is state. Its sampled address is checked separately
    // by the same sampling owner; async response keeps its address edge here.
    return port->latency ? success() : depend(node, definition->getOperand(0), definition);
  }
  if (llvm::isa<FlatFSMOp>(definition)) {
    SmallVector<Value, 8> dependencies;
    if (failed(flatFSMResultDependencies(value, dependencies)))
      return reject(definition, "flat FSM result lacks checked input dependencies");
    for (Value input : dependencies)
      if (failed(depend(node, input, definition)))
        return failure();
    return success();
  }
  if (named(definition, "nodal.structural_if")) {
    auto header = inspectStructuralIfHeader(definition);
    auto id = choice(definition);
    if (failed(header) || failed(id) || definition->getNumResults() != 1)
      return failure();
    for (unsigned arm = 0; arm != 2; ++arm) {
      auto context = extend(nodes[node].guard, *id, arm, definition);
      if (failed(context))
        return failure();
      if (*context == impossibleGuard)
        continue;
      Operation &yield = header->bodies[arm]->back();
      if (!named(&yield, "nodal.structural_yield") || yield.getNumOperands() != 1 ||
          failed(verifyDigitalValueScope(yield.getOperand(0), &yield)))
        return reject(definition, "structural join requires its actual exact yield dependency");
      auto dependency = valueNode(yield.getOperand(0), &yield, nodes[node].family, *context);
      if (failed(dependency) || failed(edge(node, *dependency)))
        return failure();
    }
    return success();
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
                    name == "nodal.iteration_index" || name == "nodal.static_compare" ||
                    name == "nodal.static_logic" || name == "nodal.behavioral_predicate" ||
                    name == "nodal.sequential_select" || name == "nodal.reset_asserted";
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
  auto guard = ancestry(operation);
  if (failed(guard))
    return failure();
  if (text(operation, "kind") == "register_next") {
    if (*guard == impossibleGuard)
      return success();
    return succeeded(assignmentNode(operation, loop, *guard)) ? success() : failure();
  }
  auto target = resolveStaticShapeProjection(operation->getOperand(0), operation);
  if (failed(target))
    return failure();
  auto owner = storageOwner(*target, operation);
  if (failed(owner) || !*owner)
    return reject(operation, "ordinary assignment target lacks its actual writable owner");
  bool active = *guard != impossibleGuard;
  if (loop) {
    // The image owner admits one varying occurrence axis. A second enclosing
    // iteration would repeat that family, even if the nearest loop is injective.
    if (iterationScope(loop))
      return reject(operation,
                    "nested iteration assignment images are outside the bounded profile");
    StaticShapeRegion image;
    auto status = resolveStaticShapeIterationImage(*target, operation, image,
                                                   {limits.maximumShapeWork, limits.maximumNodes});
    auto domain = iterationDomain(loop);
    if (failed(domain) ||
        (domain->maximumTripCount > 1 && (status != ShapeRegionProofStatus::Proved ||
                                          !image.iteration || image.iteration->loop != loop)))
      return reject(operation, "iteration target must prove an injective physical write family");
    active &= domain->maximumTripCount != 0;
  }
  for (unsigned previous : writers[*owner]) {
    const Write &other = writes[previous];
    if (!active || other.guard == impossibleGuard)
      continue;
    if (other.loop) {
      auto domain = iterationDomain(other.loop);
      if (failed(domain))
        return failure();
      if (!domain->maximumTripCount)
        continue;
    }
    if (contradicts(*guard, other.guard, false))
      continue;
    if (contradicts(*guard, other.guard) && loop == other.loop && loop &&
        proveStaticShapeSameOccurrence(*target, other.target, operation,
                                       {limits.maximumShapeWork, limits.maximumNodes}) ==
            ShapeRegionProofStatus::Proved)
      continue;
    auto separate = disjoint(other.target, *target, physicalUse(other.operation, operation));
    if (failed(separate))
      return failure();
    if (!*separate)
      return reject(operation,
                    "ordinary digital assignments have overlapping or unproved-disjoint drivers");
  }
  unsigned node = impossibleGuard;
  if (active) {
    auto root = assignmentNode(operation, loop, *guard);
    if (failed(root))
      return failure();
    node = *root;
  }
  writers[*owner].push_back(writes.size());
  writes.push_back({operation, std::move(*target), node, loop, ordinal, *guard});
  return success();
}

LogicalResult AssignmentQuery::verify() {
  if (!llvm::isa_and_nonnull<ModuleOp>(module) || module->getNumRegions() != 1 ||
      !module->getRegion(0).hasOneBlock())
    return failure();
  struct Scope {
    Block *body;
    Operation *iteration;
    unsigned depth;
  };
  SmallVector<Scope, 8> scopes{{&module->getRegion(0).front(), nullptr, 0}};
  for (unsigned cursor = 0; cursor < scopes.size(); ++cursor) {
    Scope scope = scopes[cursor];
    unsigned ordinal = 0;
    for (Operation &operation : *scope.body) {
      if (failed(guardWork(&operation)))
        return failure();
      if (llvm::isa<DigitalAssignOp>(operation)) {
        if (failed(collect(&operation, scope.iteration, ordinal++)))
          return failure();
      } else if (isBoundedDigitalLoop(&operation) ||
                 (llvm::isa<GenerateOp>(operation) && operation.getNumOperands() == 3)) {
        if (isBoundedDigitalLoop(&operation) && failed(verifyDigitalLoopOperation(&operation)))
          return failure();
        if (failed(iterationDomain(&operation)))
          return failure();
        if (scope.depth >= limits.maximumGuardDepth)
          return reject(&operation, "digital assignment scope exceeds its finite depth budget");
        scopes.push_back({&operation.getRegion(0).front(), &operation, scope.depth + 1});
      } else if (llvm::isa<SequentialUpdateOp>(operation)) {
        if (failed(verifySequentialUpdate(&operation)) ||
            failed(assignmentNode(&operation, nullptr, 0)))
          return failure();
      } else if (llvm::isa<FlatFSMOp>(operation)) {
        auto header = inspectFlatFSMHeader(&operation);
        if (failed(header) || failed(verifyFlatFSMOperation(&operation)) ||
            scope.depth >= limits.maximumGuardDepth)
          return failure();
        for (unsigned i = 0; i < header->guards; ++i)
          if (failed(valueNode(operation.getOperand(1 + i), &operation)))
            return failure();
        for (Block *body : header->bodies)
          scopes.push_back({body, scope.iteration, scope.depth + 1});
      } else if (llvm::isa<EnumSwitchOp>(operation)) {
        auto header = inspectEnumSwitchHeader(&operation);
        if (failed(header) || failed(verifyEnumSwitchOperation(&operation)) ||
            scope.depth >= limits.maximumGuardDepth ||
            failed(valueNode(operation.getOperand(0), &operation)))
          return failure();
        for (Block *body : header->bodies)
          scopes.push_back({body, scope.iteration, scope.depth + 1});
      } else if (llvm::isa<SequentialAssertOp>(operation)) {
        if (failed(verifySequentialAssertion(&operation)) ||
            failed(valueNode(operation.getOperand(0), &operation)))
          return failure();
      } else if (llvm::isa<BehavioralIfOp>(operation)) {
        auto header = inspectBehavioralIfHeader(&operation);
        if (failed(header) || scope.depth >= limits.maximumGuardDepth)
          return reject(&operation, "behavioral dependency scope exceeds finite budget");
        if (failed(valueNode(operation.getOperand(0), &operation)))
          return failure();
        for (Block *body : header->bodies)
          scopes.push_back({body, scope.iteration, scope.depth + 1});
      } else if (named(&operation, "nodal.structural_if")) {
        auto header = inspectStructuralIfHeader(&operation);
        if (failed(header) || failed(verifyStructuralIfOperation(&operation)) ||
            failed(choice(&operation)))
          return failure();
        if (scope.depth >= limits.maximumGuardDepth)
          return reject(&operation, "digital assignment scope exceeds its finite depth budget");
        for (Block *body : header->bodies)
          scopes.push_back({body, scope.iteration, scope.depth + 1});
      }
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
    if (entry.domain)
      interfaceDomains.try_emplace(entry.value, entry.domain);
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
      if (write.node != impossibleGuard && incoming[write.node])
        return reject(write.operation,
                      "ordinary digital assignment contains a combinational storage-read cycle");
  if (pending.size() != nodes.size())
    return reject(module, "digital dependency graph contains a combinational value cycle");
  return success();
}
} // namespace

FailureOr<DigitalDependencyGraph>
analyzeDigitalAssignmentDependencies(Operation *module,
                                     ArrayRef<std::pair<Value, Operation *>> seeds,
                                     DigitalAssignmentProofLimits limits) {
  return AssignmentQuery(module, limits).graph(seeds);
}
LogicalResult verifyDigitalAssignmentModel(Operation *module, DigitalAssignmentProofLimits limits) {
  return AssignmentQuery(module, limits).verify();
}
} // namespace nodal
