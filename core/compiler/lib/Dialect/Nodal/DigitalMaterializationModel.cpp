#include "nodal/Dialect/Nodal/DigitalMaterializationModel.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinOps.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/StructuralIfModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/SHA256.h"
#include "llvm/Support/raw_ostream.h"

#include <algorithm>

using namespace mlir;

namespace nodal {
namespace {
constexpr llvm::StringLiteral qualityKey = "nodal.digital.quality";
constexpr llvm::StringLiteral reportKey = "nodal.digital.materialization";
constexpr unsigned maximumNodes = 65536;
constexpr unsigned maximumEdges = 1048576;
constexpr unsigned maximumDepth = 128;
constexpr unsigned maximumIdentityBytes = 4096;

LogicalResult reject(Operation *operation, StringRef message) {
  return operation->emitOpError("NODAL-MATERIALIZATION-055-001: ") << message;
}

DictionaryAttr metadata(Operation *operation) {
  return operation->getAttrOfType<DictionaryAttr>("metadata");
}

StringRef text(DictionaryAttr dictionary, StringRef key) {
  auto value = dictionary ? dictionary.getAs<StringAttr>(key) : StringAttr();
  return value ? value.getValue() : StringRef();
}

StringRef text(Operation *operation, StringRef key) {
  auto value = operation->getAttrOfType<StringAttr>(key);
  return value ? value.getValue() : StringRef();
}

StringRef path(Operation *operation) { return text(metadata(operation), "semantic_path"); }

Operation *owner(Operation *operation) {
  if (llvm::isa_and_nonnull<nodal::ModuleOp>(operation))
    return operation;
  return operation ? operation->getParentOfType<nodal::ModuleOp>() : nullptr;
}

bool identifier(StringRef value) {
  if (value.empty() || value.size() > 255 ||
      !(llvm::isAlpha(value.front()) || value.front() == '_'))
    return false;
  return llvm::all_of(value.drop_front(), [](char character) {
    return llvm::isAlnum(character) || character == '_' || character == '$';
  });
}

bool canonicalText(StringRef value, unsigned maximum) {
  return !value.empty() && value.size() <= maximum && value == value.trim() &&
         llvm::none_of(value,
                       [](unsigned char character) { return character < 32 || character == 127; });
}

bool hasKeys(DictionaryAttr value, ArrayRef<StringRef> expected) {
  return value && value.size() == expected.size() &&
         llvm::all_of(expected, [&](StringRef key) { return value.get(key) != nullptr; });
}

bool alias(Operation *operation) {
  return operation->getName().getStringRef() == "nodal.digital_expr" &&
         text(operation, "operator_name") == "digital_alias";
}

bool candidate(Operation *operation) {
  if (operation->getNumResults() != 1)
    return false;
  Type type = operation->getResult(0).getType();
  // Classify only here; the existing exact type owner validates once per type
  // in analysis below. This does not turn a shape/analog owner into a candidate.
  if (!isDigitalScalarType(type) && !llvm::isa<EnumType, ShapedType, StructType>(type))
    return false;
  StringRef name = operation->getName().getStringRef();
  return name == "nodal.digital_expr" || name == "nodal.collection" ||
         name == "nodal.structural_if" ||
         (name == "nodal.enum_expr" && text(operation, "operator_name") != "enum_literal");
}

FailureOr<DictionaryAttr> quality(Operation *module) {
  auto root = module->getParentOfType<mlir::ModuleOp>();
  auto value = root ? root->getAttrOfType<DictionaryAttr>(qualityKey) : DictionaryAttr();
  auto version = value ? value.getAs<IntegerAttr>("version") : IntegerAttr();
  StringRef temporary = text(value, "temporaries"), naming = text(value, "naming");
  if (!hasKeys(value, {"version", "temporaries", "naming"}) || !version ||
      !version.getType().isInteger(64) || version.getInt() != 1 ||
      (temporary != "inline-safe" && temporary != "readable" && temporary != "debug") ||
      (naming != "semantic" && naming != "source-preferred" && naming != "debug-stable")) {
    (void)reject(module, "requires one closed versioned digital quality request");
    return failure();
  }
  return value;
}

FailureOr<std::string> identity(Value value, Operation *module) {
  if (Operation *definition = value.getDefiningOp()) {
    if (owner(definition) != module || !canonicalText(path(definition), maximumIdentityBytes))
      return failure();
    if (definition->getNumResults() != 1)
      return failure();
    return path(definition).str();
  }
  auto argument = llvm::dyn_cast<BlockArgument>(value);
  Operation *region = argument ? argument.getOwner()->getParentOp() : nullptr;
  if (!region || owner(region) != module || !isDigitalRegionOwner(region))
    return failure();
  auto result = digitalValueSemanticPath(value);
  return canonicalText(result, maximumIdentityBytes) ? FailureOr<std::string>(result)
                                                     : FailureOr<std::string>(failure());
}

/// Iterative preorder traversal with explicit budgets, even for malformed input.
/// Owning nested modules are separate roots and are not followed.
template <typename Callback> LogicalResult walkOwned(Operation *module, Callback callback) {
  llvm::SmallVector<std::pair<Operation *, unsigned>> pending{{module, 0}};
  unsigned visited = 0;
  while (!pending.empty()) {
    auto [operation, depth] = pending.pop_back_val();
    if (operation != module && llvm::isa<nodal::ModuleOp>(operation))
      continue;
    if (++visited > maximumEdges || depth > maximumDepth)
      return reject(operation, "materialization inspection exceeds its finite node/scope budget");
    WalkResult result = callback(operation);
    if (result.wasInterrupted())
      return failure();
    if (result.wasSkipped())
      continue;
    for (Region &region : llvm::reverse(operation->getRegions()))
      for (Block &block : llvm::reverse(region.getBlocks()))
        for (Operation &child : llvm::reverse(block.getOperations()))
          pending.emplace_back(&child, depth + 1);
  }
  return success();
}

std::string digest(Attribute value) {
  std::string bytes;
  llvm::raw_string_ostream output(bytes);
  value.print(output);
  llvm::SHA256 hash;
  hash.update(bytes);
  return llvm::toHex(hash.final(), true);
}
} // namespace

LogicalResult verifyDigitalMaterializationIntent(Operation *operation) {
  if (!operation)
    return failure();
  auto facts = metadata(operation);
  auto intent = facts ? facts.getAs<DictionaryAttr>("digital_intent") : DictionaryAttr();
  auto source = facts ? facts.getAs<DictionaryAttr>("digital_source") : DictionaryAttr();
  if (facts &&
      ((facts.get("digital_intent") && !intent) || (facts.get("digital_source") && !source)))
    return reject(operation, "digital intent/source records must be dictionaries");
  if (alias(operation) != static_cast<bool>(intent))
    return reject(operation, "one typed intent is required exactly on a digital alias");
  if (intent) {
    for (StringRef field : {"enum_ref", "case_ids", "value", "spelling"})
      if (operation->hasAttr(field))
        return reject(operation, "alias cannot carry enum-operation or literal attributes");
    for (StringRef field : {"abi_version", "semantic_type", "abi_hash"})
      if (facts.get(field))
        return reject(operation, "alias cannot carry enum-operation metadata");
    StringRef kind = text(intent, "kind"), value = text(intent, "value");
    if (!hasKeys(intent, {"kind", "value"}) ||
        (kind == "named" ? !identifier(value) : kind != "keep" || !canonicalText(value, 1024)))
      return reject(operation, "alias intent has an unknown kind or invalid bounded payload");
  }
  if (source) {
    StringRef provenance = text(source, "provenance");
    auto aliases = source.getAs<ArrayAttr>("aliases");
    if (!hasKeys(source, {"name", "provenance", "aliases"}) || !identifier(text(source, "name")) ||
        !aliases || aliases.size() > 128 ||
        (provenance != "explicit" && provenance != "scala-declaration" &&
         provenance != "source-digest" && provenance != "sink-affinity" &&
         provenance != "shaped-view"))
      return reject(operation, "source naming metadata requires closed provenance and aliases");
    llvm::StringSet<> seen;
    StringRef previous;
    for (Attribute attribute : aliases) {
      auto value = llvm::dyn_cast<StringAttr>(attribute);
      if (!value || !identifier(value.getValue()) || !seen.insert(value.getValue()).second ||
          (!previous.empty() && previous >= value.getValue()))
        return reject(operation, "source aliases must be bounded, unique and lexically sorted");
      previous = value.getValue();
    }
    if (intent && text(intent, "kind") == "named" &&
        (text(source, "name") != text(intent, "value") || provenance != "explicit"))
      return reject(operation, "explicit source name must agree with its typed alias intent");
  }
  if (source && !candidate(operation))
    return reject(operation, "digital source facts cannot reclassify a non-digital operation");
  return success();
}

FailureOr<DictionaryAttr> analyzeDigitalMaterialization(Operation *module) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return failure();
  auto selected = quality(module);
  if (failed(selected))
    return failure();
  Builder builder(module->getContext());
  StringRef modulePath = path(module);
  if (modulePath.empty())
    modulePath = text(module, "sym_name");
  if (!canonicalText(modulePath, maximumIdentityBytes)) {
    (void)reject(module, "materialization requires a bounded owning module identity");
    return failure();
  }
  const std::string prefix = (modulePath + ".").str();
  llvm::StringSet<> paths;
  llvm::StringMap<Value> valueIdentities;
  llvm::StringMap<Operation *> consumerIdentities;
  auto rememberValue = [&](Value value) -> FailureOr<std::string> {
    auto id = identity(value, module);
    if (failed(id) || !canonicalText(*id, maximumIdentityBytes) ||
        !StringRef(*id).starts_with(prefix))
      return failure();
    auto [position, inserted] = valueIdentities.try_emplace(*id, value);
    if (!inserted && position->second != value)
      return failure();
    return id;
  };
  // Semantic verification precedes this query. Type identity alone does not
  // identify dependent collection widths: retain the actual lexical use and
  // recheck each immutable context. The caches exist only for this analysis.
  llvm::DenseMap<Type, bool> moduleValidTypes;
  llvm::DenseMap<std::pair<Type, Operation *>, bool> validTypes;
  llvm::DenseMap<Block *, llvm::SmallVector<ParameterIntegerEnvironment, 4>> contexts;
  auto finiteDigitalType = [&](Type type, Operation *use) {
    const auto key = std::make_pair(type, use);
    auto known = validTypes.find(key);
    if (known != validTypes.end())
      return known->second;
    bool valid = false;
    if (!isDigitalRegionOwner(use->getParentOp())) {
      auto ordinary = moduleValidTypes.find(type);
      if (ordinary == moduleValidTypes.end())
        ordinary =
            moduleValidTypes.try_emplace(type, isDigitalCombinationalValueType(type, module)).first;
      valid = ordinary->second;
    } else if (isDigitalScalarType(type) || llvm::isa<EnumType, ShapedType, StructType>(type)) {
      auto found = contexts.find(use->getBlock());
      if (found == contexts.end()) {
        auto candidates = collectionIntegerContextsAtUse(use);
        if (failed(candidates))
          return false;
        found = contexts.try_emplace(use->getBlock(), std::move(*candidates)).first;
      }
      valid = !found->second.empty();
      for (const auto &candidate : found->second) {
        auto context = candidate.atUse(use);
        if (failed(context) || !isDigitalCombinationalValueType(type, module, &*context)) {
          valid = false;
          break;
        }
      }
    }
    validTypes.try_emplace(key, valid);
    return valid;
  };
  bool structural = false;
  if (failed(walkOwned(module, [&](Operation *operation) {
        structural |= llvm::isa<StructuralIfOp, StaticCompareOp, StaticLogicOp>(operation);
        return WalkResult::advance();
      })))
    return failure();
  llvm::SmallVector<std::pair<std::string, DictionaryAttr>> structuralRecords;
  llvm::StringSet<> structuralIdentities;
  llvm::SmallVector<std::pair<std::string, DictionaryAttr>> records;
  unsigned nodes = 0, inputEdges = 0, useEdges = 0, inspectedOperands = 0;
  // Structural evidence describes retained authored IR, even inside a singleton
  // reducer with no callback occurrence. It must not disappear when the decision
  // walk deliberately skips unreachable materialization candidates below.
  if (structural && failed(walkOwned(module, [&](Operation *operation) {
        const StringRef operationName = operation->getName().getStringRef();
        const bool staticIndex = operation->getNumResults() == 1 &&
                                 operation->getResult(0).getType().isSignlessInteger(64) &&
                                 llvm::isa<ConstantOp, ConstLiteralOp, ConstParameterRefOp,
                                           ConstExprOp, IterationIndexOp>(operation);
        if (structural &&
            (staticIndex || (llvm::isa<GenerateOp>(operation) && isDigitalRegionOwner(operation)) ||
             llvm::isa<StaticCompareOp, StaticLogicOp, StructuralIfOp, StructuralYieldOp>(
                 operation))) {
          if (!canonicalText(path(operation), maximumIdentityBytes) ||
              !structuralIdentities.insert(path(operation)).second ||
              structuralRecords.size() >= maximumNodes) {
            (void)reject(operation,
                         "structural inventory requires unique bounded real SSA identities");
            return WalkResult::interrupt();
          }
          NamedAttrList entry;
          entry.set("path", builder.getStringAttr(path(operation)));
          entry.set("operation", builder.getStringAttr(operationName));
          entry.set("region",
                    builder.getStringAttr(operation->getParentOp() == module
                                              ? ""
                                              : digitalRegionSemanticPath(operation->getBlock())));
          llvm::SmallVector<Attribute> inputs;
          for (Value value : operation->getOperands()) {
            auto id = rememberValue(value);
            if (failed(id) || ++inputEdges > maximumEdges)
              return WalkResult::interrupt();
            inputs.push_back(builder.getStringAttr(*id));
          }
          entry.set("inputs", builder.getArrayAttr(inputs));
          // Closed operation attributes retain comparison/operator/literal/parameter
          // identity as well as the exact two capture partitions. They are report
          // evidence only: verification always recomputes from actual SSA.
          NamedAttrList raw;
          for (NamedAttribute attribute : operation->getAttrs())
            if (attribute.getName() != "metadata")
              raw.set(attribute.getName(), attribute.getValue());
          entry.set("attributes", raw.getDictionary(module->getContext()));
          llvm::SmallVector<Attribute> types;
          for (Type type : operation->getResultTypes())
            types.push_back(TypeAttr::get(type));
          entry.set("result_types", builder.getArrayAttr(types));
          if (llvm::isa<GenerateOp>(operation)) {
            llvm::SmallVector<Attribute> arguments;
            for (BlockArgument argument : operation->getRegion(0).front().getArguments()) {
              NamedAttrList record;
              record.set("path", builder.getStringAttr(digitalValueSemanticPath(argument)));
              record.set("type", TypeAttr::get(argument.getType()));
              arguments.push_back(record.getDictionary(module->getContext()));
            }
            entry.set("induction_arguments", builder.getArrayAttr(arguments));
          }
          if (llvm::isa<StructuralIfOp>(operation)) {
            auto header = inspectStructuralIfHeader(operation);
            if (failed(header))
              return WalkResult::interrupt();
            llvm::SmallVector<Attribute> arms;
            for (unsigned arm = 0; arm < 2; ++arm) {
              Block *body = header->bodies[arm];
              if (body->empty() || !llvm::isa<StructuralYieldOp>(body->back()))
                return WalkResult::interrupt();
              NamedAttrList record;
              record.set("region", builder.getStringAttr(digitalRegionSemanticPath(body)));
              record.set("yield", builder.getStringAttr(path(&body->back())));
              llvm::SmallVector<Attribute> arguments;
              for (BlockArgument argument : body->getArguments()) {
                NamedAttrList argumentRecord;
                argumentRecord.set("path",
                                   builder.getStringAttr(digitalValueSemanticPath(argument)));
                argumentRecord.set("type", TypeAttr::get(argument.getType()));
                arguments.push_back(argumentRecord.getDictionary(module->getContext()));
              }
              record.set("arguments", builder.getArrayAttr(arguments));
              arms.push_back(record.getDictionary(module->getContext()));
            }
            entry.set("arms", builder.getArrayAttr(arms));
          }
          structuralRecords.emplace_back(path(operation).str(),
                                         entry.getDictionary(module->getContext()));
        }
        return WalkResult::advance();
      })))
    return failure();
  auto walk = walkOwned(module, [&](Operation *operation) {
    if (operation != module && llvm::isa<nodal::ModuleOp>(operation))
      return WalkResult::skip();
    if (failed(verifyDigitalMaterializationIntent(operation)))
      return WalkResult::interrupt();
    // A now-unused producer must not let a different SSA value steal its name
    // and hide an input-edge substitution from an attached inventory. Missing
    // identities on irrelevant old fixture nodes are still not required. Reserve
    // only complete finite digital values and actual Integer constant producers
    // admitted as static indices, never unrelated analog result provenance.
    const StringRef operationName = operation->getName().getStringRef();
    const bool staticIndex =
        operation->getNumResults() == 1 &&
        llvm::isa<IntegerType>(operation->getResult(0).getType()) &&
        (operationName == "nodal.constant" || operationName == "nodal.const_literal" ||
         operationName == "nodal.const_parameter_ref" || operationName == "nodal.const_expr" ||
         operationName == "nodal.iteration_index");
    const bool valueProducer =
        operation->getNumResults() == 1 &&
        (staticIndex || finiteDigitalType(operation->getResult(0).getType(), operation));
    if (valueProducer && !path(operation).empty()) {
      auto [position, inserted] =
          valueIdentities.try_emplace(path(operation), operation->getResult(0));
      if (!inserted && position->second != operation->getResult(0)) {
        (void)reject(operation, "distinct SSA producers cannot share a semantic identity");
        return WalkResult::interrupt();
      }
    }
    // Include noncandidate consumers such as digital-to-Real conversion, even
    // after rerouting to a literal. Their identity cannot be reused to hide a
    // same-count consumer substitution. Unrelated analog operands stay outside.
    bool valueConsumer = false;
    for (Value operand : operation->getOperands()) {
      if (++inspectedOperands > maximumEdges) {
        (void)reject(operation, "materialization operand inspection exceeds its finite budget");
        return WalkResult::interrupt();
      }
      if (finiteDigitalType(operand.getType(), operation)) {
        valueConsumer = true;
        break;
      }
    }
    if (valueConsumer && !path(operation).empty()) {
      const std::string key = (path(operation) + "|" + operationName).str();
      auto [position, inserted] = consumerIdentities.try_emplace(key, operation);
      if (!inserted && position->second != operation) {
        (void)reject(operation, "distinct digital consumers cannot share a semantic identity");
        return WalkResult::interrupt();
      }
    }
    if (!candidate(operation))
      return WalkResult::advance();
    Type resultType = operation->getResult(0).getType();
    if (!finiteDigitalType(resultType, operation)) {
      (void)reject(operation, "candidate result lacks a finite exact digital type proof");
      return WalkResult::interrupt();
    }
    StringRef semanticPath = path(operation);
    if (++nodes > maximumNodes || !canonicalText(semanticPath, maximumIdentityBytes) ||
        !semanticPath.starts_with(prefix) || semanticPath.size() <= prefix.size() ||
        !paths.insert(semanticPath).second) {
      (void)reject(operation, "candidate requires one unique bounded semantic identity");
      return WalkResult::interrupt();
    }
    if (failed(rememberValue(operation->getResult(0)))) {
      (void)reject(operation, "candidate identity aliases a different actual SSA value");
      return WalkResult::interrupt();
    }
    std::string region;
    Operation *parent = operation->getParentOp();
    if (parent != module) {
      if (!parent || !isDigitalRegionOwner(parent) ||
          !canonicalText(digitalRegionSemanticPath(operation->getBlock()), maximumIdentityBytes)) {
        (void)reject(operation, "candidate has an unsupported lexical scope");
        return WalkResult::interrupt();
      }
      region = digitalRegionSemanticPath(operation->getBlock());
    }
    llvm::SmallVector<Attribute> inputs;
    for (Value operand : operation->getOperands()) {
      auto id = rememberValue(operand);
      if (failed(id) || ++inputEdges > maximumEdges) {
        (void)reject(operation, "candidate operand lacks a real owned bounded identity");
        return WalkResult::interrupt();
      }
      inputs.push_back(builder.getStringAttr(*id));
    }
    unsigned uses = 0;
    llvm::SmallVector<std::pair<std::string, DictionaryAttr>> useRecords;
    llvm::StringSet<> useIdentities;
    for (OpOperand &use : operation->getResult(0).getUses()) {
      if (owner(use.getOwner()) != module || ++useEdges > maximumEdges) {
        (void)reject(operation, "candidate use is foreign or exceeds the finite edge budget");
        return WalkResult::interrupt();
      }
      StringRef userPath = path(use.getOwner());
      if (!canonicalText(userPath, maximumIdentityBytes) || !userPath.starts_with(prefix)) {
        (void)reject(operation, "candidate use requires a bounded actual consumer identity");
        return WalkResult::interrupt();
      }
      StringRef userKind = use.getOwner()->getName().getStringRef();
      const std::string userIdentity = (userPath + "|" + userKind).str();
      auto [position, inserted] = consumerIdentities.try_emplace(userIdentity, use.getOwner());
      if (!inserted && position->second != use.getOwner()) {
        (void)reject(operation, "consumer identity aliases a different actual operation");
        return WalkResult::interrupt();
      }
      const std::string key =
          (userPath + "|" + userKind + "|" + llvm::Twine(use.getOperandNumber())).str();
      if (!useIdentities.insert(key).second) {
        (void)reject(operation, "candidate uses have duplicate semantic identities");
        return WalkResult::interrupt();
      }
      NamedAttrList user;
      user.set("path", builder.getStringAttr(userPath));
      user.set("operation", builder.getStringAttr(userKind));
      user.set("operand", builder.getI64IntegerAttr(use.getOperandNumber()));
      useRecords.emplace_back(key, user.getDictionary(module->getContext()));
      ++uses;
    }
    llvm::sort(useRecords,
               [](const auto &left, const auto &right) { return left.first < right.first; });
    llvm::SmallVector<Attribute> orderedUses;
    for (const auto &use : useRecords)
      orderedUses.push_back(use.second);
    auto facts = metadata(operation);
    auto intent = facts.getAs<DictionaryAttr>("digital_intent");
    auto source = facts.getAs<DictionaryAttr>("digital_source");
    StringRef policy = text(*selected, "temporaries");
    llvm::SmallVector<Attribute> reasons;
    auto reason = [&](StringRef value) { reasons.push_back(builder.getStringAttr(value)); };
    if (text(intent, "kind") == "keep")
      reason("explicit-keep");
    if (uses > 1)
      reason("shared-value");
    if (policy != "inline-safe" &&
        (text(intent, "kind") == "named" || text(source, "provenance") == "explicit" ||
         text(source, "provenance") == "scala-declaration"))
      reason("source-name");
    if (policy == "debug")
      reason("debug-anchor");
    StringRef decision = "materialize-required";
    if (reasons.empty()) {
      decision = uses ? "inline-candidate" : "unused-candidate";
      reason(uses ? "pure-single-use" : "no-uses");
    }
    NamedAttrList entry;
    entry.set("path", builder.getStringAttr(semanticPath));
    entry.set("operation", builder.getStringAttr(operation->getName().getStringRef()));
    entry.set("operator", builder.getStringAttr(text(operation, "operator_name")));
    entry.set("region", builder.getStringAttr(region));
    if (isBoundedDigitalLoop(parent))
      entry.set("iteration_boundary", builder.getStringAttr("ordered-read-point"));
    entry.set("inputs", builder.getArrayAttr(inputs));
    entry.set("type", TypeAttr::get(operation->getResult(0).getType()));
    entry.set("use_count", builder.getI64IntegerAttr(uses));
    entry.set("uses", builder.getArrayAttr(orderedUses));
    entry.set("source", source ? source : builder.getDictionaryAttr({}));
    entry.set("intent", intent ? intent : builder.getDictionaryAttr({}));
    entry.set("decision", builder.getStringAttr(decision));
    entry.set("reasons", builder.getArrayAttr(reasons));
    records.emplace_back(semanticPath.str(), entry.getDictionary(module->getContext()));
    if (llvm::isa<CollectionOp>(operation)) {
      // A retained singleton reduction has no callback invocation. Its raw
      // closure/purity is verified by the collection owner; no body value is
      // materialized and no fictitious first-combine width proof is invented.
      if (operation->getNumRegions() != 1 || !llvm::hasSingleElement(operation->getRegion(0)) ||
          operation->getRegion(0).front().empty())
        return WalkResult::interrupt();
      Block &body = operation->getRegion(0).front();
      auto bodyContexts = collectionIntegerContextsAtUse(&body.front());
      if (failed(bodyContexts))
        return WalkResult::interrupt();
      const bool unreachable = bodyContexts->empty();
      contexts.try_emplace(&body, std::move(*bodyContexts));
      if (unreachable)
        return WalkResult::skip();
    }
    return WalkResult::advance();
  });
  if (failed(walk))
    return failure();
  llvm::sort(records, [](const auto &left, const auto &right) { return left.first < right.first; });
  llvm::SmallVector<Attribute> decisions;
  for (const auto &record : records)
    decisions.push_back(record.second);
  NamedAttrList result;
  result.set("version", builder.getI64IntegerAttr(structural ? 2 : 1));
  result.set("algorithm", builder.getStringAttr(structural ? "digital-ssa-v2" : "digital-ssa-v1"));
  if (structural) {
    llvm::sort(structuralRecords, [](const auto &a, const auto &b) { return a.first < b.first; });
    llvm::SmallVector<Attribute> entries;
    for (const auto &record : structuralRecords)
      entries.push_back(record.second);
    result.set("structural_choices", builder.getArrayAttr(entries));
  }
  result.set("stage", builder.getStringAttr("target-neutral"));
  result.set("target_requirements", builder.getStringAttr("pending"));
  result.set("owner", builder.getStringAttr(modulePath));
  result.set("quality", *selected);
  result.set("decisions", builder.getArrayAttr(decisions));
  result.set("sha256", builder.getStringAttr(digest(result.getDictionary(module->getContext()))));
  return result.getDictionary(module->getContext());
}

LogicalResult verifyDigitalMaterializationReport(Operation *module) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return failure();
  auto root = module->getParentOfType<mlir::ModuleOp>();
  if (root && root->hasAttr(reportKey))
    return reject(module, "derived reports belong to each owning nodal.module, not the root");
  if (root && root->hasAttr(qualityKey) && failed(quality(module)))
    return failure();
  // Verify every authored record even in old unannotated native fixtures.
  auto walk = walkOwned(module, [&](Operation *operation) {
    if (operation != module && llvm::isa<nodal::ModuleOp>(operation))
      return WalkResult::skip();
    if (operation->hasAttr(qualityKey)) {
      (void)reject(operation, "digital quality belongs to the enclosing builtin.module only");
      return WalkResult::interrupt();
    }
    if (operation != module && operation->hasAttr(reportKey)) {
      (void)reject(operation, "derived materialization reports belong to nodal.module only");
      return WalkResult::interrupt();
    }
    return failed(verifyDigitalMaterializationIntent(operation)) ? WalkResult::interrupt()
                                                                 : WalkResult::advance();
  });
  if (failed(walk))
    return failure();
  Attribute attached = module->getAttr(reportKey);
  if (!attached)
    return success();
  auto fresh = analyzeDigitalMaterialization(module);
  if (failed(fresh))
    return failure();
  if (attached != *fresh)
    return reject(module,
                  "materialization report is stale, forged or inconsistent with actual SSA");
  return success();
}
} // namespace nodal
