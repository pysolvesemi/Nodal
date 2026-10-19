#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"

#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Support/BackendCapabilityRegistry.h"

#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/StringSet.h"

using namespace mlir;

namespace nodal {
namespace {
constexpr unsigned maximumRegionDepth = 128;
constexpr unsigned maximumRegionNodes = 65536;

bool named(Operation *operation, StringRef name) {
  return operation && operation->getName().getStringRef() == name;
}
StringRef text(Operation *operation, StringRef name) {
  auto attribute = operation ? operation->getAttrOfType<StringAttr>(name) : StringAttr();
  return attribute ? attribute.getValue() : StringRef();
}
StringRef path(Operation *operation) {
  auto metadata =
      operation ? operation->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
  auto value = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
  return value ? value.getValue() : StringRef();
}
Operation *owner(Operation *operation) {
  if (!operation)
    return nullptr;
  if (llvm::isa<ModuleOp>(operation))
    return operation;
  return operation->getParentOfType<ModuleOp>();
}
LogicalResult reject(Operation *operation, StringRef message) {
  return operation->emitOpError("NODAL-DIGITAL-055-001: ") << message;
}

struct Signature {
  Operation *module = nullptr;
  Block *body = nullptr;
  unsigned inputs = 0;
  unsigned seeds = 0;
  unsigned captures = 0;
  unsigned ordinaryArguments = 0;
  ShapedType inputType;
  StringRef kind;
};

/// Header-only contract, shared by argument use verification. In particular this
/// never follows a capture operand, visits a body operation or invokes value
/// opcode verification, avoiding cyclic verifier dependencies.
FailureOr<Signature> signature(Operation *operation) {
  Signature result;
  if (!named(operation, "nodal.collection") || operation->getNumRegions() != 1 ||
      operation->getNumResults() != 1 || operation->getNumSuccessors() != 0 ||
      operation->getNumOperands() > maximumRegionNodes || operation->hasAttr("unit") ||
      !operation->getAttrOfType<DictionaryAttr>("metadata"))
    return failure();
  result.module = owner(operation);
  Operation *parent = operation->getParentOp();
  if (!result.module ||
      (!llvm::isa_and_nonnull<ModuleOp>(parent) && !named(parent, "nodal.collection")))
    return failure();
  unsigned depth = 0;
  for (Operation *scope = parent; named(scope, "nodal.collection"); scope = scope->getParentOp())
    if (++depth >= maximumRegionDepth)
      return failure();
  auto segments = operation->getAttrOfType<DenseI32ArrayAttr>("operandSegmentSizes");
  if (!segments || segments.size() != 3)
    return failure();
  auto sizes = segments.asArrayRef();
  uint64_t total = 0;
  for (int32_t size : sizes) {
    if (size < 0)
      return failure();
    total += static_cast<uint64_t>(size);
  }
  if (total != operation->getNumOperands())
    return failure();
  result.kind = text(operation, "kind");
  const bool folded = result.kind == "fold_left" || result.kind == "scan_left";
  if (result.kind != "map" && result.kind != "zip_with" && !folded)
    return failure();
  result.inputs = result.kind == "zip_with" ? 2 : 1;
  result.seeds = folded ? 1 : 0;
  result.captures = sizes[2];
  result.ordinaryArguments = folded ? 2 : result.inputs;
  if (sizes[0] != static_cast<int32_t>(result.inputs) ||
      sizes[1] != static_cast<int32_t>(result.seeds))
    return failure();
  Region &region = operation->getRegion(0);
  if (!llvm::hasSingleElement(region))
    return failure();
  result.body = &region.front();
  if (result.body->getNumArguments() != result.ordinaryArguments + result.captures)
    return failure();
  auto validType = [&](Type type) { return isDigitalCombinationalValueType(type, result.module); };
  for (Value operand : operation->getOperands())
    if (!validType(operand.getType()))
      return failure();
  if (!validType(operation->getResult(0).getType()))
    return failure();
  for (BlockArgument argument : result.body->getArguments())
    if (!validType(argument.getType()))
      return failure();
  auto equal = [&](Type a, Type b) { return equivalentDigitalStorageTypes(a, b, result.module); };
  result.inputType = llvm::dyn_cast<ShapedType>(operation->getOperand(0).getType());
  if (!result.inputType)
    return failure();
  for (unsigned input = 0; input < result.inputs; ++input) {
    auto type = llvm::dyn_cast<ShapedType>(operation->getOperand(input).getType());
    if (!type || !equal(ShapedType::get(operation->getContext(), type.getDimensions(),
                                        result.inputType.getElementType()),
                        result.inputType))
      return failure();
    unsigned argument = folded ? 1 : input;
    if (!equal(result.body->getArgument(argument).getType(), type.getElementType()))
      return failure();
  }
  if (folded &&
      !equal(result.body->getArgument(0).getType(), operation->getOperand(result.inputs).getType()))
    return failure();
  for (unsigned capture = 0; capture < result.captures; ++capture)
    if (!equal(result.body->getArgument(result.ordinaryArguments + capture).getType(),
               operation->getOperand(result.inputs + result.seeds + capture).getType()))
      return failure();
  StringRef id = text(operation, "region_id");
  // Source identity belongs to the single module expression registry. Lexical
  // ownership is the actual region parent, not an inferred string hierarchy.
  StringRef moduleId = path(result.module);
  if (moduleId.empty())
    moduleId = text(result.module, "sym_name");
  const std::string prefix = (moduleId + ".").str();
  if (id.empty() || id.size() > digitalTypeMaximumIdentifierBytes || id != id.trim() ||
      id.contains(' ') || id.contains('\t') || id.contains('\n') || id.contains('\r') ||
      !id.starts_with(prefix) || id.size() <= prefix.size() || path(operation) != id)
    return failure();
  return result;
}

// Value-producing ancestors are already checked by their semantic owners. This
// bounded dependency walk retains the digital/analog capability boundary across
// shared neutral shape carriers; wrapping an analog producer never makes it a
// valid collection input or capture. It does not evaluate or expand the value.
class CaptureBoundaryVerifier {
public:
  bool verify(Value value, unsigned depth = 0, bool staticSelector = false) {
    if (depth > maximumRegionDepth || ++work > maximumRegionNodes)
      return false;
    Region *region = value.getParentRegion();
    if (!region)
      return false;
    if (!isDigitalCombinationalValueType(value.getType(), owner(region->getParentOp())) &&
        !(staticSelector && (value.getType().isInteger(64) || value.getType().isIndex())))
      return false;
    if (finished.contains(value))
      return true;
    if (!active.insert(value).second)
      return false;
    if (Operation *definition = value.getDefiningOp()) {
      auto *descriptor = lookupBackendOperation(definition->getName().getStringRef());
      if (!descriptor || isAnalogBackendOperation(descriptor->operation) ||
          definition->hasAttr("unit"))
        return false;
      const bool projection =
          named(definition, "nodal.shape_index") || named(definition, "nodal.shape_slice");
      for (auto [index, operand] : llvm::enumerate(definition->getOperands()))
        if (failed(verifyCollectionValueScope(operand, definition)) ||
            !verify(operand, depth + 1, staticSelector || (projection && index > 0)))
          return false;
    } else if (!llvm::isa<BlockArgument>(value)) {
      return false;
    }
    active.erase(value);
    finished.insert(value);
    return true;
  }

private:
  unsigned work = 0;
  llvm::DenseSet<Value> active, finished;
};

LogicalResult verifyBodyOperation(Operation *operation) {
  if (operation->hasAttr("unit"))
    return reject(operation, "collection body must not introduce analog units");
  if (named(operation, "nodal.collection"))
    return success(succeeded(signature(operation)));
  if (operation->getNumRegions() || operation->getNumSuccessors() ||
      operation->getNumResults() != 1 || !operation->getAttrOfType<DictionaryAttr>("metadata"))
    return reject(operation, "collection body requires a registered pure single-result value");
  StringRef name = operation->getName().getStringRef();
  if (name != "nodal.constant" && name != "nodal.const_literal" &&
      !isDigitalCombinationalValueType(operation->getResult(0).getType(), owner(operation)))
    return reject(operation,
                  "collection intermediates must retain directionless digital value types");
  if (name == "nodal.digital_expr")
    return verifyDigitalValueOperation(operation);
  if (name == "nodal.enum_expr")
    return verifyEnumValueOperation(operation);
  if (name == "nodal.constant" || name == "nodal.const_literal") {
    Type type = operation->getResult(0).getType();
    // Local static Integer/index literals are selector intermediates, never
    // data args. Their ordinary literal owner still checks value and spelling.
    if (operation->getNumOperands() || operation->hasAttr("unit") ||
        (!isDigitalCombinationalValueType(type, owner(operation)) && !type.isInteger(64) &&
         !type.isIndex()))
      return reject(operation, "collection literal must be digital data or a static selector");
    return name == "nodal.constant" ? llvm::cast<ConstantOp>(operation).verify()
                                    : llvm::cast<ConstLiteralOp>(operation).verify();
  }
  if (name == "nodal.shape_index" && operation->getNumOperands() >= 2)
    return verifyShapeIndexMapping(operation);
  if (name == "nodal.shape_view" && operation->getNumOperands() == 1 &&
      llvm::isa<ShapedType>(operation->getOperand(0).getType()) &&
      text(operation, "materialization") == "view")
    return verifyShapeViewMapping(operation);
  if (name == "nodal.shape_slice" && operation->getNumOperands() == 4)
    return verifyShapeSliceMapping(operation);
  return reject(operation, "operation is outside the closed pure digital collection profile");
}
} // namespace

LogicalResult verifyCollectionValueScope(Value value, Operation *use) {
  if (!value || !use || !use->getBlock())
    return failure();
  Operation *module = owner(use);
  if (!module)
    return failure();
  if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
    Block *block = argument.getOwner();
    if (block != use->getBlock() || !named(block->getParentOp(), "nodal.collection"))
      return failure();
    return success(succeeded(signature(block->getParentOp())));
  }
  Operation *definition = value.getDefiningOp();
  if (!definition || owner(definition) != module || definition == use)
    return failure();
  // Preserve ordinary lexical module/generated uses. Crossing a collection
  // boundary is always an explicit operand/argument edge, never an implicit use.
  Operation *visibleUse = use;
  while (visibleUse && visibleUse->getBlock() != definition->getBlock()) {
    Operation *parent = visibleUse->getParentOp();
    if (!parent || named(parent, "nodal.collection") || parent == module)
      return failure();
    visibleUse = parent;
  }
  return success(visibleUse && definition != visibleUse && definition->isBeforeInBlock(visibleUse));
}

FailureOr<Value> digitalCollectionCaptureSource(BlockArgument argument) {
  Operation *collection = argument.getOwner()->getParentOp();
  auto contract = signature(collection);
  if (failed(contract) || contract->body != argument.getOwner())
    return failure();
  unsigned ordinal = argument.getArgNumber();
  if (ordinal < contract->ordinaryArguments)
    return Value();
  Value source = collection->getOperand(contract->inputs + contract->seeds + ordinal -
                                        contract->ordinaryArguments);
  if (failed(verifyCollectionValueScope(source, collection)))
    return failure();
  return source;
}

LogicalResult digitalCollectionArgumentDependencies(BlockArgument argument,
                                                    llvm::SmallVectorImpl<Value> &dependencies) {
  Operation *collection = argument.getOwner()->getParentOp();
  auto contract = signature(collection);
  if (failed(contract) || contract->body != argument.getOwner())
    return failure();
  llvm::SmallVector<Value, 4> sources;
  unsigned ordinal = argument.getArgNumber();
  if (ordinal >= contract->ordinaryArguments) {
    sources.push_back(collection->getOperand(contract->inputs + contract->seeds + ordinal -
                                             contract->ordinaryArguments));
  } else if (contract->seeds && ordinal == 0) {
    // The accumulator is a bounded recurrence. Seed-only provenance would hide
    // reads from previous iterations; keep all actual recurrent inputs/captures.
    sources.append(collection->operand_begin(), collection->operand_end());
  } else {
    sources.push_back(collection->getOperand(contract->seeds ? 0 : ordinal));
  }
  for (Value source : sources)
    if (failed(verifyCollectionValueScope(source, collection)))
      return failure();
  dependencies.append(sources.begin(), sources.end());
  return success();
}

std::string digitalValueSemanticPath(Value value) {
  if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
    Operation *scope = argument.getOwner()->getParentOp();
    if (named(scope, "nodal.collection"))
      return (text(scope, "region_id") + ".arg" + std::to_string(argument.getArgNumber())).str();
    return {};
  }
  return path(value.getDefiningOp()).str();
}

LogicalResult verifyDigitalCollectionYield(Operation *operation) {
  Operation *parent = operation->getParentOp();
  if (!named(operation, "nodal.collection_yield") || !named(parent, "nodal.collection") ||
      operation->getNumOperands() != 1 || operation->getNumResults() ||
      operation->getNumRegions() || operation->getNumSuccessors() || operation->hasAttr("unit") ||
      !operation->getAttrOfType<DictionaryAttr>("metadata") || operation->getBlock()->empty() ||
      &operation->getBlock()->back() != operation ||
      failed(verifyCollectionValueScope(operation->getOperand(0), operation)))
    return reject(operation, "yield must be the final single visible value of its collection");
  return success();
}

LogicalResult verifyDigitalCollectionOperation(Operation *operation) {
  auto contract = signature(operation);
  if (failed(contract))
    return reject(operation,
                  "invalid collection kind, operand segments, shape, identity or argument types");
  CaptureBoundaryVerifier boundary;
  for (Value operand : operation->getOperands())
    if (failed(verifyCollectionValueScope(operand, operation)) || !boundary.verify(operand))
      return reject(operation,
                    "collection input, seed and captures must be visible owned digital SSA values");
  if (contract->body->empty())
    return reject(operation, "collection requires one final value yield");
  Operation *terminator = &contract->body->back();
  if (failed(verifyDigitalCollectionYield(terminator)))
    return failure();
  Type yielded = terminator->getOperand(0).getType();
  if (!isDigitalCombinationalValueType(yielded, contract->module))
    return reject(operation, "collection yield must be a pure digital combinational value");
  Type expected = yielded;
  if (contract->seeds) {
    expected = operation->getOperand(contract->inputs).getType();
    if (!equivalentDigitalStorageTypes(yielded, expected, contract->module))
      return reject(operation, "fold and scan must yield the seed's exact complete type");
  }
  if (contract->kind != "fold_left")
    expected =
        ShapedType::get(operation->getContext(), contract->inputType.getDimensions(), expected);
  if (!equivalentDigitalStorageTypes(operation->getResult(0).getType(), expected, contract->module))
    return reject(operation,
                  "result must preserve the input dimensions and exact yielded element type");
  unsigned nodes = 0;
  for (Operation &bodyOperation : *contract->body) {
    if (++nodes > maximumRegionNodes)
      return reject(operation, "collection body exceeds its finite operation budget");
    if (&bodyOperation == terminator)
      continue;
    for (Value operand : bodyOperation.getOperands())
      if (failed(verifyCollectionValueScope(operand, &bodyOperation)))
        return reject(&bodyOperation,
                      "body operands must use local SSA or explicit capture arguments");
    if (failed(verifyBodyOperation(&bodyOperation)))
      return failure();
  }
  return success();
}

LogicalResult verifyDigitalCollectionModel(Operation *module) {
  if (!llvm::isa_and_nonnull<ModuleOp>(module))
    return failure();
  llvm::StringSet<> identities;
  unsigned nodes = 0, bodyNodes = 0;
  WalkResult result = module->walk([&](Operation *operation) {
    if (operation == module)
      return WalkResult::advance();
    if (owner(operation) != module)
      return WalkResult::advance();
    if (named(operation->getParentOp(), "nodal.collection") && ++bodyNodes > maximumRegionNodes) {
      (void)reject(operation, "collection bodies exceed the module's finite operation budget");
      return WalkResult::interrupt();
    }
    if (!named(operation, "nodal.collection"))
      return WalkResult::advance();
    if (++nodes > maximumRegionNodes || !identities.insert(text(operation, "region_id")).second ||
        failed(verifyDigitalCollectionOperation(operation))) {
      (void)reject(operation, "collection identity must be unique with bounded verified regions");
      return WalkResult::interrupt();
    }
    return WalkResult::advance();
  });
  return failure(result.wasInterrupted());
}
} // namespace nodal
