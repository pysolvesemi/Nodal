#include "nodal/Dialect/Nodal/EnumValueModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/SmallString.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/ConvertUTF.h"
#include "llvm/Support/SHA256.h"

#include <algorithm>
#include <string>

using namespace mlir;

namespace nodal {
namespace {
constexpr unsigned maximumCases = 4096;
constexpr int64_t maximumWidth = 1048576;
constexpr uint64_t maximumCodeBits = 16777216;
constexpr unsigned maximumTypeNodes = 65536;
constexpr unsigned maximumTypeDepth = 128;

StringRef text(Operation *operation, StringRef key) {
  auto value = operation->getAttrOfType<StringAttr>(key);
  return value ? value.getValue() : StringRef();
}

LogicalResult reject(Operation *operation, StringRef message) {
  return operation->emitOpError("NODAL-ENUM-054-020: ") << message;
}

struct Case {
  StringRef id;
  IntegerAttr code;
  llvm::SmallVector<llvm::UTF16, 0> order;
};
struct Definition {
  Operation *operation;
  StringRef identity;
  StringRef hash;
  int64_t width;
  SmallVector<Case> cases;
  llvm::StringSet<> caseIds;
};

bool validUtf8(StringRef value) {
  llvm::SmallVector<llvm::UTF16, 32> codeUnits;
  return llvm::convertUTF8ToUTF16String(value, codeUnits);
}

bool abiMetadata(DictionaryAttr metadata) {
  if (!metadata)
    return false;
  auto version = metadata.getAs<IntegerAttr>("abi_version");
  auto identity = metadata.getAs<StringAttr>("semantic_type");
  auto hash = metadata.getAs<StringAttr>("abi_hash");
  return version && version.getType().isSignlessInteger(64) && version.getInt() == 1 && identity &&
         !identity.getValue().empty() && validUtf8(identity.getValue()) &&
         identity.getValue() == identity.getValue().trim() && hash &&
         hash.getValue().size() == 64 && llvm::all_of(hash.getValue(), [](char c) {
           return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
         });
}

bool isNonnegative(IntegerAttr value) {
  if (!value)
    return false;
  auto type = llvm::dyn_cast<IntegerType>(value.getType());
  return type && (type.isUnsigned() || !value.getValue().isNegative());
}

Definition facts(Operation *definition) {
  auto metadata = definition->getAttrOfType<DictionaryAttr>("metadata");
  auto underlying =
      llvm::cast<UIntType>(definition->getAttrOfType<TypeAttr>("underlying_type").getValue());
  Definition result{definition,
                    metadata.getAs<StringAttr>("semantic_type").getValue(),
                    metadata.getAs<StringAttr>("abi_hash").getValue(),
                    underlying.getWidth(),
                    {},
                    {}};
  for (Operation &entry : definition->getRegion(0).front()) {
    auto caseMetadata = entry.getAttrOfType<DictionaryAttr>("metadata");
    auto id = caseMetadata.getAs<StringAttr>("case_id").getValue();
    llvm::SmallVector<llvm::UTF16, 0> order;
    (void)llvm::convertUTF8ToUTF16String(id, order);
    result.cases.push_back({id, entry.getAttrOfType<IntegerAttr>("value"), std::move(order)});
    result.caseIds.insert(id);
  }
  return result;
}

std::string computeHash(const Definition &definition) {
  llvm::SHA256 hash;
  hash.update("nodal.enum.abi.v1\n");
  auto field = [&](StringRef value) {
    hash.update(std::to_string(value.size()));
    hash.update(":");
    hash.update(value);
    hash.update("\n");
  };
  field(definition.identity);
  field(std::to_string(definition.width));
  SmallVector<const Case *> cases;
  for (const Case &entry : definition.cases)
    cases.push_back(&entry);
  // Scala/Java String ordering is lexicographic UTF-16 code units, not UTF-8
  // bytes or Unicode scalar order. Retain that exact cross-language ABI order;
  // each field is still length-framed using its original UTF-8 byte count.
  llvm::sort(cases, [](const Case *left, const Case *right) {
    return std::lexicographical_compare(left->order.begin(), left->order.end(),
                                        right->order.begin(), right->order.end());
  });
  for (const Case *entry : cases) {
    field(entry->id);
    llvm::SmallString<32> magnitude;
    entry->code.getValue().toString(magnitude, 10, false);
    field(magnitude);
  }
  return llvm::toHex(hash.final(), true);
}

class ValueVerifier {
public:
  explicit ValueVerifier(Operation *scope, const ParameterIntegerEnvironment *environment = nullptr)
      : root(scope->getParentOfType<mlir::ModuleOp>()), environment(environment) {}

  FailureOr<Definition *> resolve(Operation *use, StringRef symbol) {
    auto known = definitions.find(symbol);
    if (known != definitions.end())
      return &known->second;
    if (!root || symbol.empty()) {
      (void)reject(use, "typed enum requires an enclosing root and a named enum definition");
      return failure();
    }
    Operation *found = nullptr;
    for (Operation &candidate : *root.getBody()) {
      if (text(&candidate, SymbolTable::getSymbolAttrName()) != symbol)
        continue;
      if (found) {
        (void)reject(use, "enum symbol is ambiguous in the root symbol table");
        return failure();
      }
      found = &candidate;
    }
    auto definition = llvm::dyn_cast_or_null<EnumOp>(found);
    if (!definition) {
      (void)reject(use, "enum type must reference an actual root enum symbol");
      return failure();
    }
    if (!abiMetadata(found->getAttrOfType<DictionaryAttr>("metadata"))) {
      (void)reject(
          use, "typed enum requires complete version 1 semantic identity and ABI hash metadata");
      return failure();
    }
    if (failed(definition.verify()))
      return failure();
    auto inserted = definitions.try_emplace(symbol, facts(found));
    return &inserted.first->second;
  }

  LogicalResult checkType(Operation *use, Type type, unsigned depth = 1) {
    if (depth > maximumTypeDepth)
      return reject(use, "recursive enum type nesting exceeds the finite metadata limit");
    if (!types.insert(type).second)
      return success();
    if (++typeNodes > maximumTypeNodes)
      return reject(use, "recursive enum type graph exceeds the finite metadata limit");
    if (auto enumeration = llvm::dyn_cast<EnumType>(type)) {
      auto definition = resolve(use, enumeration.getSymbol());
      if (failed(definition))
        return failure();
      if (enumeration.getWidth() != (*definition)->width)
        return reject(use, "enum type width must exactly match its referenced definition");
    }
    bool valid = true;
    type.walkImmediateSubElements(
        [&](Attribute attribute) { valid &= succeeded(checkAttribute(use, attribute, depth + 1)); },
        [&](Type child) { valid &= succeeded(checkType(use, child, depth + 1)); });
    return success(valid);
  }

  LogicalResult checkAttribute(Operation *use, Attribute attribute, unsigned depth = 1) {
    if (depth > maximumTypeDepth)
      return reject(use, "recursive enum attribute nesting exceeds the finite metadata limit");
    if (!attributes.insert(attribute).second)
      return success();
    bool valid = true;
    attribute.walkImmediateSubElements(
        [&](Attribute child) { valid &= succeeded(checkAttribute(use, child, depth + 1)); },
        [&](Type type) { valid &= succeeded(checkType(use, type, depth + 1)); });
    return success(valid);
  }

  LogicalResult codeValue(Operation *operation, EnumType type, Attribute candidate) {
    if (failed(checkType(operation, type)))
      return failure();
    auto definition = resolve(operation, type.getSymbol());
    if (failed(definition))
      return failure();
    auto value = llvm::dyn_cast_or_null<IntegerAttr>(candidate);
    if (!isNonnegative(value) || llvm::none_of((*definition)->cases, [&](const Case &entry) {
          return llvm::APInt::isSameValue(value.getValue(), entry.code.getValue());
        }))
      return reject(operation, "enum value must be an exact legal case code");
    return success();
  }

  LogicalResult parameter(Operation *operation, Attribute candidate) {
    auto declared = operation->getAttrOfType<TypeAttr>("type");
    auto type = declared ? llvm::dyn_cast<EnumType>(declared.getValue()) : EnumType();
    return type ? codeValue(operation, type, candidate) : success();
  }

  LogicalResult literal(Operation *operation) {
    auto type = operation->getNumResults() == 1
                    ? llvm::dyn_cast<EnumType>(operation->getResult(0).getType())
                    : EnumType();
    if (!type)
      return success();
    if (!llvm::isa<ConstantOp, ConstLiteralOp>(operation) ||
        !operation->getParentOfType<nodal::ModuleOp>() || operation->getNumOperands() != 0 ||
        operation->getNumRegions() != 0 || operation->getNumSuccessors() != 0 ||
        operation->hasAttr("unit"))
      return reject(operation, "enum literal requires an owned pure constant producer");
    return codeValue(operation, type, operation->getAttr("value"));
  }

  LogicalResult expression(Operation *operation) {
    if (!operation->getParentOfType<nodal::ModuleOp>())
      return reject(operation, "enum expression requires an owning nodal module");
    for (Value operand : operation->getOperands())
      if (failed(verifyCollectionValueScope(operand, operation, environment)))
        return reject(operation,
                      "enum operands require visible owned values and explicit region captures");
    auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("enum_ref");
    auto ids = operation->getAttrOfType<ArrayAttr>("case_ids");
    auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata");
    if (!reference || !ids || !abiMetadata(metadata) || operation->getNumResults() != 1)
      return reject(
          operation,
          "enum expression requires one result, enum_ref, case_ids and complete ABI metadata");
    auto resolved = resolve(operation, reference.getValue());
    if (failed(resolved))
      return failure();
    const Definition &definition = **resolved;
    if (metadata.getAs<StringAttr>("semantic_type").getValue() != definition.identity ||
        metadata.getAs<StringAttr>("abi_hash").getValue() != definition.hash)
      return reject(operation,
                    "enum expression semantic identity or ABI hash disagrees with its definition");
    llvm::StringSet<> selected;
    for (Attribute id : ids) {
      auto value = llvm::dyn_cast<StringAttr>(id);
      if (!value || !selected.insert(value.getValue()).second ||
          !definition.caseIds.contains(value.getValue()))
        return reject(operation,
                      "enum case IDs must be unique and belong to the referenced definition");
    }
    auto enumType = EnumType::get(operation->getContext(), reference.getValue(), definition.width);
    auto bitsType = BitsType::get(operation->getContext(), definition.width);
    const Type result = operation->getResult(0).getType();
    const bool booleanResult =
        result == IntegerType::get(operation->getContext(), 1) ||
        result == BitsType::get(operation->getContext(), 1) ||
        (environment &&
         equivalentDigitalStorageTypes(result, BitsType::get(operation->getContext(), 1),
                                       environment->module(), environment));
    const StringRef opcode = text(operation, "operator_name");
    const unsigned count = operation->getNumOperands();
    auto operand = [&](unsigned index, Type expected) {
      return index < count && operation->getOperand(index).getType() == expected;
    };
    auto bitsInput = [&] {
      if (count == 0)
        return false;
      Type input = operation->getOperand(0).getType();
      if (input == bitsType || input == UIntType::get(operation->getContext(), definition.width))
        return true;
      auto symbolic = llvm::dyn_cast<ParametricIntegerType>(input);
      if (!symbolic || (symbolic.getKind() != "bits" && symbolic.getKind() != "uint"))
        return false;
      auto owner = operation->getParentOfType<nodal::ModuleOp>();
      auto width = inferDigitalWidthBounds(input, owner, environment);
      return succeeded(width) && width->lower == definition.width &&
             width->upper == definition.width;
    };
    bool valid = false;
    if (opcode == "enum_literal")
      valid = count == 0 && ids.size() == 1 && result == enumType;
    else if (opcode == "enum_decode")
      valid = count == 1 && ids.size() == 1 && bitsInput() && result == enumType;
    else if (opcode == "enum_is_valid")
      valid = count == 1 && ids.empty() && (bitsInput() || operand(0, enumType)) && booleanResult;
    else if (opcode == "enum_as_bits")
      valid = count == 1 && ids.empty() && operand(0, enumType) && result == bitsType;
    else if (opcode == "enum_eq")
      valid = count == 2 && ids.empty() && operand(0, enumType) && operand(1, enumType) &&
              booleanResult;
    else if (opcode == "enum_is_one_of")
      valid = count == 1 && operand(0, enumType) && booleanResult;
    else if (opcode == "enum_select") {
      auto module = operation->getParentOfType<nodal::ModuleOp>();
      if (!isDigitalStorageType(result) ||
          !equivalentDigitalStorageTypes(result, result, module, environment))
        return reject(
            operation,
            "enum selection requires directionless storage data, not Clock/Reset capabilities");
      valid = count == definition.cases.size() + 2 && ids.size() == definition.cases.size() &&
              operand(0, enumType);
      if (valid) {
        for (unsigned index = 0; index < ids.size(); ++index)
          valid &= llvm::cast<StringAttr>(ids[index]).getValue() == definition.cases[index].id;
        for (unsigned index = 1; index < count; ++index)
          valid &= operand(index, result) ||
                   equivalentDigitalStorageTypes(operation->getOperand(index).getType(), result,
                                                 module, environment);
      }
    } else {
      return reject(operation, "unsupported closed enum operation");
    }
    if (!valid)
      return reject(
          operation,
          "enum expression arity, exact type, default or complete selection coverage is invalid");
    for (Type type : operation->getOperandTypes())
      if (failed(checkType(operation, type)))
        return failure();
    return checkType(operation, result);
  }

private:
  mlir::ModuleOp root;
  const ParameterIntegerEnvironment *environment;
  llvm::StringMap<Definition> definitions;
  llvm::DenseSet<Type> types;
  llvm::DenseSet<Attribute> attributes;
  unsigned typeNodes = 0;
};
} // namespace

LogicalResult verifyEnumDefinitionAbi(Operation *definition) {
  auto metadata = definition->getAttrOfType<DictionaryAttr>("metadata");
  if (!metadata)
    return reject(definition, "enum definition requires metadata");
  // Non-ABI historical metadata remains supported for F19 declaration/FSM IR.
  if (!metadata.get("abi_version") && !metadata.get("semantic_type") && !metadata.get("abi_hash"))
    return success();
  if (!abiMetadata(metadata))
    return reject(
        definition,
        "enum ABI metadata must contain version 1, semantic_type and a lowercase SHA-256 hash");
  if (!llvm::isa_and_nonnull<mlir::ModuleOp>(definition->getParentOp()))
    return reject(definition, "ABI enum definition must be a direct root symbol");
  auto underlying = definition->getAttrOfType<TypeAttr>("underlying_type");
  auto type = underlying ? llvm::dyn_cast<UIntType>(underlying.getValue()) : UIntType();
  if (!type || type.getWidth() <= 0 || type.getWidth() > maximumWidth ||
      definition->getNumRegions() != 1 || !definition->getRegion(0).hasOneBlock())
    return reject(definition, "typed enum requires a bounded unsigned width and one case block");
  const StringRef identity = metadata.getAs<StringAttr>("semantic_type").getValue();
  llvm::StringSet<> ids;
  uint64_t codeBits = 0;
  unsigned count = 0;
  for (Operation &entry : definition->getRegion(0).front()) {
    auto caseMetadata = entry.getAttrOfType<DictionaryAttr>("metadata");
    auto id = caseMetadata ? caseMetadata.getAs<StringAttr>("case_id") : StringAttr();
    auto code = entry.getAttrOfType<IntegerAttr>("value");
    const std::string expectedId =
        (identity + "." + text(&entry, SymbolTable::getSymbolAttrName())).str();
    if (!llvm::isa<EnumCaseOp>(entry) || !id || !validUtf8(id.getValue()) ||
        id.getValue() != expectedId || !ids.insert(id.getValue()).second || !isNonnegative(code))
      return reject(
          &entry,
          "enum ABI requires unique qualified case IDs matching declaration identity and name");
    if (++count > maximumCases ||
        (codeBits += std::max(1u, code.getValue().getActiveBits())) > maximumCodeBits)
      return reject(definition, "enum ABI exceeds its finite case/code metadata budget");
  }
  if (!count)
    return reject(definition, "enum ABI requires at least one legal case");
  const StringRef policy = text(definition, "encoding");
  const int64_t compactWidth = std::max(1u, llvm::APInt(64, count - 1).getActiveBits());
  if (((policy == "sequential" || policy == "gray") && type.getWidth() != compactWidth) ||
      (policy == "one_hot" && type.getWidth() != count))
    return reject(definition, "typed enum policy requires its exact canonical width; padded widths "
                              "require custom policy");
  Definition resolved = facts(definition);
  if (computeHash(resolved) != resolved.hash)
    return reject(definition,
                  "enum ABI hash does not match canonical identity, width and case/code bytes");
  for (Operation &other : *llvm::cast<mlir::ModuleOp>(definition->getParentOp()).getBody()) {
    if (&other == definition || !llvm::isa<EnumOp>(other))
      continue;
    auto otherMetadata = other.getAttrOfType<DictionaryAttr>("metadata");
    if (!otherMetadata)
      continue;
    auto otherIdentity = otherMetadata.getAs<StringAttr>("semantic_type");
    auto otherHash = otherMetadata.getAs<StringAttr>("abi_hash");
    if (otherIdentity && otherIdentity.getValue() == identity &&
        (!otherHash || otherHash.getValue() != resolved.hash))
      return reject(definition, "one semantic enum identity cannot have conflicting ABIs");
  }
  return success();
}

LogicalResult verifyEnumValueOperation(Operation *operation,
                                       const ParameterIntegerEnvironment *environment) {
  if (!environment && operation &&
      (operation->getParentOfType<CollectionOp>() ||
       isBoundedDigitalLoop(operation->getParentOp()))) {
    auto contexts = collectionIntegerContextsAtUse(operation);
    if (failed(contexts))
      return failure();
    for (const auto &context : *contexts)
      if (failed(verifyEnumValueOperation(operation, &context)))
        return failure();
    return success();
  }
  if (environment) {
    auto context = environment->atUse(operation);
    if (failed(context))
      return failure();
    return ValueVerifier(operation, &*context).expression(operation);
  }
  ValueVerifier verifier(operation);
  return verifier.expression(operation);
}

LogicalResult verifyEnumLiteralOperation(Operation *operation,
                                         const ParameterIntegerEnvironment *environment) {
  if (!environment && operation &&
      (operation->getParentOfType<CollectionOp>() ||
       isBoundedDigitalLoop(operation->getParentOp()))) {
    auto contexts = collectionIntegerContextsAtUse(operation);
    if (failed(contexts))
      return failure();
    for (const auto &context : *contexts)
      if (failed(verifyEnumLiteralOperation(operation, &context)))
        return failure();
    return success();
  }
  if (environment) {
    auto context = environment->atUse(operation);
    if (failed(context))
      return failure();
    return ValueVerifier(operation, &*context).literal(operation);
  }
  ValueVerifier verifier(operation);
  return verifier.literal(operation);
}

LogicalResult verifyEnumParameterDefault(Operation *parameter) {
  ValueVerifier verifier(parameter);
  return verifier.parameter(parameter, parameter->getAttr("default_value"));
}

LogicalResult verifyEnumParameterValue(Operation *parameter, Attribute value) {
  ValueVerifier verifier(parameter);
  return verifier.parameter(parameter, value);
}

LogicalResult verifyEnumTypeOperation(Operation *operation,
                                      const ParameterIntegerEnvironment *environment) {
  if (!operation)
    return failure();
  if (!environment && (operation->getParentOfType<CollectionOp>() ||
                       isBoundedDigitalLoop(operation->getParentOp()))) {
    auto contexts = collectionIntegerContextsAtUse(operation);
    if (failed(contexts))
      return failure();
    for (const auto &context : *contexts)
      if (failed(verifyEnumTypeOperation(operation, &context)))
        return failure();
    return success();
  }
  std::optional<ParameterIntegerEnvironment> context;
  if (environment) {
    auto scoped = environment->atUse(operation);
    if (failed(scoped) || scoped->module() != operation->getParentOfType<nodal::ModuleOp>())
      return failure();
    context.emplace(*scoped);
    environment = &*context;
  }
  ValueVerifier verifier(operation, environment);
  bool valid = true;
  for (Type type : operation->getOperandTypes())
    valid &= succeeded(verifier.checkType(operation, type));
  for (Type type : operation->getResultTypes())
    valid &= succeeded(verifier.checkType(operation, type));
  if (!llvm::isa<CollectionOp>(operation))
    for (Region &region : operation->getRegions())
      for (Block &block : region)
        for (BlockArgument argument : block.getArguments())
          valid &= succeeded(verifier.checkType(operation, argument.getType()));
  for (NamedAttribute attribute : operation->getAttrs()) {
    if (llvm::isa<FsmOp>(operation) && attribute.getName() == "state_type")
      continue;
    valid &= succeeded(verifier.checkAttribute(operation, attribute.getValue()));
  }
  return success(valid);
}

LogicalResult verifyEnumValueModel(Operation *module, bool includeCollections) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return failure();
  ValueVerifier verifier(module);
  auto walk = module->walk<WalkOrder::PreOrder>([&](Operation *operation) {
    if (operation != module && llvm::isa<nodal::ModuleOp>(operation))
      return WalkResult::skip();
    bool valid = true;
    for (Type type : operation->getOperandTypes())
      valid &= succeeded(verifier.checkType(operation, type));
    for (Type type : operation->getResultTypes())
      valid &= succeeded(verifier.checkType(operation, type));
    if (!llvm::isa<CollectionOp>(operation))
      for (Region &region : operation->getRegions())
        for (Block &block : region)
          for (BlockArgument argument : block.getArguments())
            valid &= succeeded(verifier.checkType(operation, argument.getType()));
    for (NamedAttribute attribute : operation->getAttrs()) {
      if (llvm::isa<FsmOp>(operation) && attribute.getName() == "state_type")
        continue;
      valid &= succeeded(verifier.checkAttribute(operation, attribute.getValue()));
    }
    if (llvm::isa<ConstantOp, ConstLiteralOp>(operation))
      valid &= succeeded(verifier.literal(operation));
    if (llvm::isa<ParameterOp>(operation))
      valid &= succeeded(verifier.parameter(operation, operation->getAttr("default_value")));
    if (operation->getName().getStringRef() == "nodal.enum_expr")
      valid &= succeeded(verifier.expression(operation));
    if (!valid)
      return WalkResult::interrupt();
    if (llvm::isa<CollectionOp>(operation)) {
      if (includeCollections && failed(verifyDigitalCollectionOperation(operation)))
        return WalkResult::interrupt();
      return WalkResult::skip();
    }
    return WalkResult::advance();
  });
  return failure(walk.wasInterrupted());
}
} // namespace nodal

// Preserve the ordinary entry-point ABI for existing non-contextual consumers.
namespace nodal {
LogicalResult verifyEnumValueOperation(Operation *operation) {
  return verifyEnumValueOperation(operation, nullptr);
}
LogicalResult verifyEnumLiteralOperation(Operation *operation) {
  return verifyEnumLiteralOperation(operation, nullptr);
}
} // namespace nodal

namespace nodal {
LogicalResult verifyEnumValueModel(Operation *module) { return verifyEnumValueModel(module, true); }
} // namespace nodal
