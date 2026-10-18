#include "nodal/Transforms/AnalogCanonicalization.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/OwningOpRef.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Pass/PassRegistry.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/AnalogUserFunctions.h"
#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/Hashing.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"

#include <string>
#include <unordered_map>
#include <utility>

using namespace mlir;

namespace nodal {
namespace {

constexpr llvm::StringLiteral inventoryName = "nodal.bridge.analog_semantics";
constexpr llvm::StringLiteral journalName = "nodal.canonical.analog_journal";

LogicalResult fail(Operation *op, llvm::StringRef code, const llvm::Twine &message) {
  return emitMappedFailure(op, code, message);
}

bool nonempty(DictionaryAttr record, llvm::StringRef key) {
  auto value = record.getAs<StringAttr>(key);
  return value && !value.getValue().trim().empty();
}

bool isScalarCandidateName(llvm::StringRef name) {
  return name == "nodal.real_literal" || name == "nodal.analog_integer_literal" ||
         name == "nodal.parameter_ref" || name == "nodal.analog_add" ||
         name == "nodal.analog_sub" || name == "nodal.analog_mul" || name == "nodal.analog_div" ||
         name == "nodal.analog_neg";
}

bool isScalarCandidate(Operation *op) {
  if (!isScalarCandidateName(op->getName().getStringRef()) || op->getNumResults() != 1 ||
      op->getNumRegions() || op->getNumSuccessors())
    return false;
  auto type = getAnalogNumericTypeInfo(op->getResult(0).getType());
  return succeeded(type) &&
         (type->kind == AnalogNumericKind::Real || type->kind == AnalogNumericKind::Integer);
}

// This is an explicit source-only allowlist, never a rule to discard arbitrary
// metadata. Owner, dimension, continuity and all unknown attributes remain keys.
bool isSourceField(llvm::StringRef name) {
  return name == "semantic_path" || name == "source_path" || name == "source_file" ||
         name == "source_line" || name == "source_column" || name == "source_end_line" ||
         name == "source_end_column";
}

DictionaryAttr semanticMetadata(Operation *op) {
  SmallVector<NamedAttribute> attributes;
  if (auto metadata = op->getAttrOfType<DictionaryAttr>("metadata"))
    for (NamedAttribute attribute : metadata)
      if (!isSourceField(attribute.getName().getValue()))
        attributes.push_back(attribute);
  return DictionaryAttr::get(op->getContext(), attributes);
}

DictionaryAttr semanticAttributes(Operation *op) {
  NamedAttrList attributes(op->getAttrs());
  if (op->getAttr("metadata"))
    attributes.set("metadata", semanticMetadata(op));
  return attributes.getDictionary(op->getContext());
}

bool hasOnlyNegationAttributes(Operation *op) {
  for (NamedAttribute attr : op->getAttrs()) {
    llvm::StringRef name = attr.getName().getValue();
    if (name != "metadata" && name != "nodal.folded" && name != "nodal.folded_kind" &&
        name != "nodal.folded_dimension" && name != "nodal.folded_provenance" &&
        name != "nodal.folded_value")
      return false;
  }
  return true;
}

struct ExpressionKey {
  OperationName name;
  Type type;
  DictionaryAttr attributes;
  SmallVector<Value, 2> operands;

  explicit ExpressionKey(Operation *op)
      : name(op->getName()), type(op->getResult(0).getType()), attributes(semanticAttributes(op)),
        operands(op->getOperands()) {}

  bool operator==(const ExpressionKey &other) const {
    return name == other.name && type == other.type && attributes == other.attributes &&
           operands == other.operands;
  }
};

struct ExpressionHash {
  size_t operator()(const ExpressionKey &key) const {
    llvm::hash_code hash = llvm::hash_combine(key.name.getStringRef(), key.type, key.attributes);
    for (Value operand : key.operands)
      hash = llvm::hash_combine(hash, operand);
    return static_cast<size_t>(hash);
  }
};

// One flat source record per removed/marked operation. IDs refer to the original
// graph in this transaction, with a journal epoch preventing cross-run collisions.
// No operand tree is serialized, so a shared DAG never expands into a tree.
class RewriteJournal {
public:
  RewriteJournal(mlir::ModuleOp module, ArrayAttr previous)
      : module(module), builder(module.getContext()), previous(previous), epoch(previous.size()) {
    unsigned operationOrdinal = 0;
    unsigned blockOrdinal = 0;
    module.walk<WalkOrder::PreOrder>([&](Operation *op) {
      const std::string id =
          "e" + std::to_string(epoch) + ".o" + std::to_string(operationOrdinal++);
      operationIds[op] = builder.getStringAttr(id);
      for (OpResult value : op->getResults())
        valueIds[value] =
            builder.getStringAttr(id + ":r" + std::to_string(value.getResultNumber()));
      for (Region &region : op->getRegions())
        for (Block &block : region) {
          const std::string blockId =
              "e" + std::to_string(epoch) + ".b" + std::to_string(blockOrdinal++);
          for (BlockArgument argument : block.getArguments())
            valueIds[argument] =
                builder.getStringAttr(blockId + ":a" + std::to_string(argument.getArgNumber()));
        }
    });
    module.walk([&](Operation *op) {
      if (isScalarCandidate(op) || op->getName().getStringRef() == "nodal.analog_function_value")
        originals[op] = snapshot(op);
    });
  }

  void append(Operation *original, llvm::StringRef reason, Value replacement = {},
              Operation *initializer = nullptr) {
    NamedAttrList fields;
    fields.append("reason", builder.getStringAttr(reason));
    fields.append("original", originals.lookup(original));
    fields.append("replacement",
                  replacement ? valueIds.lookup(replacement) : builder.getStringAttr(""));
    if (replacement && replacement.getDefiningOp()) {
      Operation *target = replacement.getDefiningOp();
      fields.append("replacement_origin",
                    originals.count(target) ? originals.lookup(target) : snapshot(target));
    }
    if (initializer)
      fields.append("initializer_origin", originals.lookup(initializer));
    records.push_back(fields.getDictionary(module.getContext()));
  }

  void finish() {
    if (records.empty())
      return;
    SmallVector<Attribute> all(previous.begin(), previous.end());
    all.append(records.begin(), records.end());
    module->setAttr(journalName, builder.getDictionaryAttr(
                                     {builder.getNamedAttr("version", builder.getI64IntegerAttr(1)),
                                      builder.getNamedAttr("records", builder.getArrayAttr(all))}));
  }

private:
  DictionaryAttr snapshot(Operation *op) {
    SmallVector<Attribute> operands;
    for (Value operand : op->getOperands())
      operands.push_back(valueIds.lookup(operand));
    SmallVector<Attribute> types;
    for (Type type : op->getResultTypes())
      types.push_back(TypeAttr::get(type));
    return builder.getDictionaryAttr(
        {builder.getNamedAttr("identity", operationIds.lookup(op)),
         builder.getNamedAttr("operation", builder.getStringAttr(op->getName().getStringRef())),
         builder.getNamedAttr("types", builder.getArrayAttr(types)),
         builder.getNamedAttr("attributes", op->getAttrDictionary()),
         builder.getNamedAttr("location", op->getLoc()),
         builder.getNamedAttr("operands", builder.getArrayAttr(operands))});
  }

  mlir::ModuleOp module;
  Builder builder;
  ArrayAttr previous;
  size_t epoch;
  DenseMap<Operation *, StringAttr> operationIds;
  DenseMap<Value, StringAttr> valueIds;
  DenseMap<Operation *, DictionaryAttr> originals;
  SmallVector<Attribute> records;
};

bool validSourceRecord(DictionaryAttr record) {
  if (!record || !nonempty(record, "identity") || !nonempty(record, "operation") ||
      !record.getAs<DictionaryAttr>("attributes") || !record.getAs<LocationAttr>("location"))
    return false;
  auto types = record.getAs<ArrayAttr>("types");
  auto operands = record.getAs<ArrayAttr>("operands");
  return types && operands &&
         llvm::all_of(types, [](Attribute type) { return llvm::isa<TypeAttr>(type); }) &&
         llvm::all_of(operands, [](Attribute operand) {
           auto text = llvm::dyn_cast<StringAttr>(operand);
           return text && !text.getValue().empty();
         });
}

FailureOr<ArrayAttr> readJournal(mlir::ModuleOp module) {
  Attribute attribute = module->getAttr(journalName);
  if (!attribute)
    return ArrayAttr::get(module.getContext(), {});
  auto journal = llvm::dyn_cast<DictionaryAttr>(attribute);
  auto version = journal ? journal.getAs<IntegerAttr>("version") : IntegerAttr();
  auto records = journal ? journal.getAs<ArrayAttr>("records") : ArrayAttr();
  bool valid = version && version.getType().isInteger(64) && version.getInt() == 1 && records;
  llvm::StringSet<> identities;
  if (valid)
    for (Attribute entry : records) {
      auto record = llvm::dyn_cast<DictionaryAttr>(entry);
      auto original = record ? record.getAs<DictionaryAttr>("original") : DictionaryAttr();
      auto reason = record ? record.getAs<StringAttr>("reason") : StringAttr();
      auto replacement = record ? record.getAs<StringAttr>("replacement") : StringAttr();
      if (!validSourceRecord(original) || !reason || !replacement ||
          !identities.insert(original.getAs<StringAttr>("identity").getValue()).second) {
        valid = false;
        break;
      }
      llvm::StringRef kind = reason.getValue();
      if (kind != "exact-cse" && kind != "double-negation" && kind != "dead-pure-expression" &&
          kind != "dead-literal-local") {
        valid = false;
        break;
      }
      if ((kind == "exact-cse" || kind == "double-negation") && replacement.getValue().empty())
        valid = false;
      if (kind == "dead-literal-local" &&
          !validSourceRecord(record.getAs<DictionaryAttr>("initializer_origin")))
        valid = false;
      if (!replacement.getValue().empty() &&
          !validSourceRecord(record.getAs<DictionaryAttr>("replacement_origin")))
        valid = false;
    }
  if (!valid) {
    (void)fail(module, "NODAL-ANALOG-045-002", "analog rewrite journal has an invalid schema");
    return failure();
  }
  return records;
}

void canonicalizeBlock(Block &block, RewriteJournal &journal) {
  DenseSet<Operation *> pure;
  std::unordered_map<ExpressionKey, Operation *, ExpressionHash> expressions;
  for (Operation &operation : llvm::make_early_inc_range(block)) {
    Operation *op = &operation;
    if (!isScalarCandidate(op) || !llvm::all_of(op->getOperands(), [&](Value value) {
          Operation *definition = value.getDefiningOp();
          return definition && definition->getBlock() == &block && pure.contains(definition);
        }))
      continue;
    pure.insert(op);
    if (op->getName().getStringRef() == "nodal.analog_neg" &&
        getAnalogNumericTypeInfo(op->getResult(0).getType())->kind == AnalogNumericKind::Real) {
      // Dynamic integer negation may overflow at the target's signed minimum.
      // The finite rule does not invent a range proof or erase that boundary.
      Operation *inner = op->getOperand(0).getDefiningOp();
      if (inner && inner->getName().getStringRef() == "nodal.analog_neg" && pure.contains(inner) &&
          hasOnlyNegationAttributes(op) && hasOnlyNegationAttributes(inner)) {
        Value base = inner->getOperand(0);
        if (base.getType() == op->getResult(0).getType() &&
            inner->getResult(0).getType() == base.getType() &&
            semanticMetadata(op) == semanticMetadata(inner) &&
            semanticMetadata(op) == semanticMetadata(base.getDefiningOp())) {
          journal.append(op, "double-negation", base);
          op->getResult(0).replaceAllUsesWith(base);
          pure.erase(op);
          op->erase();
          continue;
        }
      }
    }
    auto [found, inserted] = expressions.emplace(ExpressionKey(op), op);
    if (!inserted) {
      Value replacement = found->second->getResult(0);
      journal.append(op, "exact-cse", replacement);
      op->getResult(0).replaceAllUsesWith(replacement);
      pure.erase(op);
      op->erase();
    }
  }
  // Reverse SSA order removes newly dead dependencies in one linear sweep.
  for (Operation &operation : llvm::make_early_inc_range(llvm::reverse(block))) {
    Operation *op = &operation;
    if (pure.contains(op) && op->getResult(0).use_empty()) {
      journal.append(op, "dead-pure-expression");
      op->erase();
    }
  }
}

llvm::StringRef semanticOwner(Operation *op) {
  if (auto metadata = op->getAttrOfType<DictionaryAttr>("metadata"))
    if (auto path = metadata.getAs<StringAttr>("semantic_path"))
      return path.getValue();
  if (auto symbol = op->getAttrOfType<StringAttr>("sym_name"))
    return symbol.getValue();
  return {};
}

// F32 inventories include authored occurrence owners even when the bridge emits
// only one shared module definition. Resolve the existing instance child_path
// aliases lazily, per requested owner; do not expand the instantiated hierarchy.
class InventoryOwners {
public:
  explicit InventoryOwners(mlir::ModuleOp module) {
    llvm::StringMap<Operation *> definitions;
    for (Operation &op : module.getBody()->getOperations()) {
      if (op.getName().getStringRef() != "nodal.module")
        continue;
      auto symbol = op.getAttrOfType<StringAttr>("sym_name");
      llvm::StringRef path = semanticOwner(&op);
      if (!symbol || path.empty() || !definitions.try_emplace(symbol.getValue(), &op).second ||
          !owners.insert(path).second)
        valid = false;
    }
    module.walk([&](Operation *op) {
      if (op->getName().getStringRef() != "nodal.instance")
        return;
      auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
      auto child = metadata ? metadata.getAs<StringAttr>("child_path") : StringAttr();
      if (!child)
        return; // Hand-authored native instances need not carry bridge aliases.
      auto reference = op->getAttrOfType<FlatSymbolRefAttr>("module");
      Operation *target = reference ? definitions.lookup(reference.getValue()) : nullptr;
      Operation *parent = op->getParentOp();
      while (parent && parent->getName().getStringRef() != "nodal.module")
        parent = parent->getParentOp();
      llvm::StringRef parentPath = parent ? semanticOwner(parent) : llvm::StringRef();
      llvm::StringRef childPath = child.getValue();
      auto instancePath = metadata.getAs<StringAttr>("semantic_path");
      if (!instancePath || instancePath.getValue() != childPath.str() + "_instance") {
        valid = false;
        return;
      }
      if (!target || parentPath.empty() || !childPath.starts_with(parentPath) ||
          !childPath.drop_front(parentPath.size()).starts_with(".") ||
          childPath.size() <= parentPath.size() + 1 ||
          childPath.drop_front(parentPath.size() + 1).contains('.')) {
        valid = false;
        return;
      }
      auto [entry, inserted] = aliases.try_emplace(childPath, semanticOwner(target).str());
      if (!inserted && entry->second != semanticOwner(target))
        valid = false;
    });
  }

  bool contains(llvm::StringRef owner) {
    if (!valid)
      return false;
    if (auto cached = resolved.find(owner); cached != resolved.end())
      return cached->second;
    std::string current = owner.str();
    bool found = false;
    for (size_t step = 0; step <= aliases.size(); ++step) {
      if (owners.contains(current)) {
        found = true;
        break;
      }
      llvm::StringRef prefix = current;
      bool mapped = false;
      while (!prefix.empty()) {
        auto alias = aliases.find(prefix);
        if (alias != aliases.end()) {
          std::string next =
              alias->second + llvm::StringRef(current).drop_front(prefix.size()).str();
          if (next != current) {
            current = std::move(next);
            mapped = true;
          }
          break;
        }
        size_t dot = prefix.rfind('.');
        prefix = dot == llvm::StringRef::npos ? llvm::StringRef() : prefix.take_front(dot);
      }
      if (!mapped)
        break;
    }
    resolved.try_emplace(owner, found);
    return found;
  }

private:
  bool valid = true;
  llvm::StringSet<> owners;
  llvm::StringMap<std::string> aliases;
  llvm::StringMap<bool> resolved;
};

LogicalResult canonicalizeWorking(mlir::ModuleOp module) {
  // Validate original operations before any cleanup can hide a diagnostic.
  auto previous = readJournal(module);
  if (failed(previous) || failed(verifyAnalogSemanticInventory(module)) ||
      failed(verifyAnalogDeadLocalMarkers(module)) || failed(mlir::verify(module)) ||
      failed(verifyAnalogNumericModel(module)) || failed(normalizePotentialFlowAccess(module)) ||
      failed(foldAnalogNumericConstants(module)))
    return failure();
  Attribute inventory = module->getAttr(inventoryName);
  RewriteJournal journal(module, *previous);
  DenseSet<Operation *> alreadyMarked;
  module.walk([&](Operation *op) {
    if (isNormalizedAnalogDeadLocal(op))
      alreadyMarked.insert(op);
  });
  auto deadLocals = normalizeAnalogDeadLocals(module);
  if (failed(deadLocals))
    return failure();
  for (const AnalogDeadLocalEvidence &dead : *deadLocals)
    if (!alreadyMarked.contains(dead.local))
      journal.append(dead.local, "dead-literal-local", {}, dead.initializer);
  module.walk([&](Operation *op) {
    if (op->getName().getStringRef() == "nodal.analog")
      for (Region &region : op->getRegions())
        for (Block &block : region)
          canonicalizeBlock(block, journal);
  });
  journal.finish();
  if (module->getAttr(inventoryName) != inventory)
    return fail(module, "NODAL-ANALOG-045-003",
                "canonicalization changed authored analog inventory");
  if (failed(mlir::verify(module)) || failed(verifyAnalogNumericModel(module)) ||
      failed(verifyAnalogSemanticInventory(module)) || failed(readJournal(module)))
    return failure();
  return success();
}

class CanonicalizeAnalogPass final
    : public PassWrapper<CanonicalizeAnalogPass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(CanonicalizeAnalogPass)
  llvm::StringRef getArgument() const final { return "nodal-canonicalize-analog"; }
  llvm::StringRef getDescription() const final {
    return "Transactionally canonicalize the finite validated pure analog scalar profile";
  }
  void runOnOperation() final {
    if (failed(canonicalizeAnalog(getOperation())))
      signalPassFailure();
  }
};

} // namespace

LogicalResult verifyAnalogSemanticInventory(mlir::ModuleOp module) {
  Attribute attribute = module->getAttr(inventoryName);
  if (!attribute)
    return success();
  auto inventory = llvm::dyn_cast<ArrayAttr>(attribute);
  auto invalid = [&]() {
    return fail(module, "NODAL-ANALOG-045-001", "invalid structural F32 analog semantic inventory");
  };
  if (!inventory)
    return invalid();
  llvm::StringSet<> equationIdentities;
  llvm::StringSet<> contributionIdentities;
  InventoryOwners owners(module);
  for (Attribute entry : inventory) {
    auto record = llvm::dyn_cast<DictionaryAttr>(entry);
    if (!record)
      return invalid();
    for (llvm::StringRef key : {"kind", "identity", "owner", "continuity", "source_file"})
      if (!nonempty(record, key))
        return invalid();
    if (!record.getAs<StringAttr>("guard"))
      return invalid();
    llvm::StringRef owner = record.getAs<StringAttr>("owner").getValue();
    llvm::StringRef identity = record.getAs<StringAttr>("identity").getValue();
    if (!owners.contains(owner) || !identity.starts_with(owner) ||
        !identity.drop_front(owner.size()).starts_with(".") || identity.size() <= owner.size() + 1)
      return invalid();
    for (llvm::StringRef key : {"source_line", "source_column"}) {
      auto number = record.getAs<IntegerAttr>(key);
      if (!number || !number.getType().isInteger(64) || number.getInt() < 1)
        return invalid();
    }
    auto analyses = record.getAs<ArrayAttr>("analyses");
    if (!analyses || analyses.empty())
      return invalid();
    llvm::StringSet<> uniqueAnalyses;
    for (Attribute analysis : analyses) {
      auto text = llvm::dyn_cast<StringAttr>(analysis);
      if (!text)
        return invalid();
      llvm::StringRef name = text.getValue();
      // F32 source applicability spells operating-point with a hyphen. The
      // activity-query enum in F44 is a different contract.
      if ((name != "initialization" && name != "dc" && name != "operating-point" &&
           name != "transient" && name != "ac" && name != "noise") ||
          !uniqueAnalyses.insert(name).second)
        return invalid();
    }
    llvm::StringRef kind = record.getAs<StringAttr>("kind").getValue();
    if (kind == "equation" || kind == "initial_equation") {
      if (!equationIdentities.insert(identity).second)
        return invalid();
      for (llvm::StringRef key : {"authored_left", "authored_right", "dimension"})
        if (!nonempty(record, key))
          return invalid();
      auto convention = record.getAs<StringAttr>("residual_convention");
      auto oriented = record.getAs<BoolAttr>("causally_oriented");
      auto divided = record.getAs<BoolAttr>("divided");
      if (!isCanonicalDimensionSignature(record.getAs<StringAttr>("dimension").getValue()) ||
          !convention || convention.getValue() != "lhs-minus-rhs-equals-zero" || !oriented ||
          oriented.getValue() || !divided || divided.getValue() ||
          (kind == "initial_equation" &&
           (uniqueAnalyses.size() != 1 || !uniqueAnalyses.contains("initialization"))))
        return invalid();
    } else if (kind == "contribution") {
      if (!contributionIdentities.insert(identity).second)
        return invalid();
      for (llvm::StringRef key :
           {"target_identity", "target_kind", "target_dimension", "target_orientation", "value"})
        if (!nonempty(record, key))
          return invalid();
      llvm::StringRef targetKind = record.getAs<StringAttr>("target_kind").getValue();
      if ((targetKind != "potential" && targetKind != "flow") ||
          !isCanonicalDimensionSignature(record.getAs<StringAttr>("target_dimension").getValue()))
        return invalid();
    } else {
      return invalid();
    }
  }
  return success();
}

LogicalResult canonicalizeAnalog(mlir::ModuleOp module) {
  OwningOpRef<mlir::ModuleOp> working(llvm::cast<mlir::ModuleOp>(module->clone()));
  if (failed(canonicalizeWorking(*working)))
    return failure();
  module->setLoc((*working)->getLoc());
  module->setAttrs((*working)->getAttrDictionary());
  module.getBodyRegion().takeBody(working->getBodyRegion());
  return success();
}

std::unique_ptr<Pass> createCanonicalizeAnalogPass() {
  return std::make_unique<CanonicalizeAnalogPass>();
}

void registerAnalogCanonicalizationPass() {
  static PassRegistration<CanonicalizeAnalogPass> registration;
  (void)registration;
}

} // namespace nodal
