#include "nodal/Dialect/Nodal/NodalOps.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinTypes.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Diagnostics/DiagnosticSupport.h"
#include "nodal/Dialect/Nodal/AnalogEvents.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NatureDiscipline.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"
#include "nodal/Dialect/Nodal/TimeWaveform.h"

#include "llvm/ADT/APInt.h"
#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/SmallString.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/ADT/StringSet.h"

#include <initializer_list>
#include <iterator>
#include <optional>
#include <set>
#include <string>
#include <vector>

using namespace mlir;

#define GET_OP_CLASSES
#include "nodal/Dialect/Nodal/NodalOps.cpp.inc"

namespace {

llvm::StringRef textAttr(Operation *operation, llvm::StringRef name) {
  if (auto value = operation->getAttrOfType<StringAttr>(name))
    return value.getValue();
  return {};
}

LogicalResult requireText(Operation *operation, llvm::StringRef name, llvm::StringRef label) {
  if (textAttr(operation, name).trim().empty())
    return operation->emitOpError() << label << " must not be empty";
  return success();
}

bool oneOf(llvm::StringRef value, std::initializer_list<llvm::StringRef> choices) {
  for (llvm::StringRef choice : choices) {
    if (value == choice)
      return true;
  }
  return false;
}

LogicalResult requireSingleBlock(Operation *operation) {
  if (operation->getNumRegions() != 1 || operation->getRegion(0).getBlocks().size() != 1)
    return operation->emitOpError("requires exactly one body block");
  return success();
}

std::optional<unsigned> finiteWidth(Type type) {
  if (auto value = llvm::dyn_cast<nodal::BitsType>(type))
    return static_cast<unsigned>(value.getWidth());
  if (auto value = llvm::dyn_cast<nodal::UIntType>(type))
    return static_cast<unsigned>(value.getWidth());
  if (auto value = llvm::dyn_cast<nodal::SIntType>(type))
    return static_cast<unsigned>(value.getWidth());
  if (auto value = llvm::dyn_cast<nodal::EnumType>(type))
    return static_cast<unsigned>(value.getWidth());
  if (auto value = llvm::dyn_cast<IntegerType>(type))
    return value.getWidth();
  return std::nullopt;
}

bool isSignedType(Type type) {
  if (llvm::isa<nodal::SIntType>(type))
    return true;
  if (auto integer = llvm::dyn_cast<IntegerType>(type))
    return integer.isSigned();
  return false;
}

bool integerFits(IntegerAttr value, Type type) {
  const std::optional<unsigned> width = finiteWidth(type);
  if (!width)
    return false;
  if (isSignedType(type))
    return value.getValue().isSignedIntN(*width);
  return !value.getValue().isNegative() && value.getValue().isIntN(*width);
}

bool attributeFits(Attribute value, Type type) {
  if (auto typed = llvm::dyn_cast<TypedAttr>(value)) {
    if (typed.getType() == type)
      return true;
  }
  if (auto integer = llvm::dyn_cast<IntegerAttr>(value))
    return integerFits(integer, type);
  if (llvm::isa<BoolAttr>(value)) {
    if (type.isInteger(1))
      return true;
    if (auto bits = llvm::dyn_cast<nodal::BitsType>(type))
      return bits.getWidth() == 1;
  }
  return false;
}

unsigned shapedRank(llvm::StringRef dimensions) {
  if (dimensions.trim().empty())
    return 0;
  unsigned rank = 1;
  for (char character : dimensions) {
    if (character == ',')
      ++rank;
  }
  return rank;
}

LogicalResult verifyLoop(Operation *operation) {
  if (failed(requireText(operation, "induction", "induction name")))
    return failure();
  if (auto step = operation->getAttrOfType<IntegerAttr>("step")) {
    if (step.getValue().isZero())
      return operation->emitOpError("step must not be zero");
  }
  return requireSingleBlock(operation);
}

LogicalResult verifyRelation(Operation *operation, llvm::StringRef label,
                             std::initializer_list<llvm::StringRef> kinds) {
  const llvm::StringRef kind = textAttr(operation, "kind");
  if (!oneOf(kind, kinds))
    return operation->emitOpError() << "unsupported " << label << " kind '" << kind << "'";
  auto source = operation->getAttrOfType<FlatSymbolRefAttr>("source");
  auto destination = operation->getAttrOfType<FlatSymbolRefAttr>("destination");
  if (!source || !destination)
    return operation->emitOpError() << label << " requires both endpoints";
  if (source == destination && kind != "alias")
    return operation->emitOpError() << label << " endpoints may match only for alias";
  return success();
}

struct ProceduralValueInfo {
  std::string kind;
  std::string dimension;
};

FailureOr<ProceduralValueInfo> getProceduralValueInfo(Type type) {
  if (type.isInteger(1))
    return ProceduralValueInfo{"boolean", "1"};
  if (type.isF64())
    return ProceduralValueInfo{"real", "1"};
  if (auto quantity = llvm::dyn_cast<nodal::QuantityType>(type))
    return ProceduralValueInfo{quantity.getKind().str(), quantity.getDimension().str()};
  return failure();
}

bool proceduralKindsCompatible(llvm::StringRef source, llvm::StringRef destination) {
  return source == destination || (source == "integer" && destination == "real");
}

bool isCanonicalStructuredCaseLabel(llvm::StringRef label, llvm::StringRef kind) {
  if (kind == "boolean")
    return label == "boolean:true" || label == "boolean:false";
  if (kind != "integer" || !label.consume_front("integer:") || label.empty())
    return false;
  int64_t parsed = 0;
  if (label.getAsInteger(10, parsed))
    return false;
  return label == std::to_string(parsed);
}

bool isProceduralAncestor(Operation *operation) {
  return operation && static_cast<bool>(operation->getParentOfType<nodal::AnalogProcedureOp>());
}

LogicalResult requireProceduralAncestor(Operation *operation, llvm::StringRef code,
                                        llvm::StringRef label) {
  if (!isProceduralAncestor(operation))
    return nodal::emitMappedFailure(
        operation, code, llvm::Twine(label) + " requires an active analog procedural region");
  return success();
}

bool isVisibleFrom(Block *useBlock, Block *declarationBlock) {
  for (Block *current = useBlock; current;) {
    if (current == declarationBlock)
      return true;
    Operation *parent = current->getParentOp();
    current = parent ? parent->getBlock() : nullptr;
  }
  return false;
}

bool isDeclaredBeforeStructuredUse(Operation *declaration, Operation *use) {
  if (!declaration || !use)
    return false;
  Block *declarationBlock = declaration->getBlock();
  Block *currentBlock = use->getBlock();
  Operation *useAnchor = use;
  while (currentBlock && currentBlock != declarationBlock) {
    Operation *parent = currentBlock->getParentOp();
    if (!parent)
      return false;
    useAnchor = parent;
    currentBlock = parent->getBlock();
  }
  return currentBlock == declarationBlock && declaration != useAnchor &&
         declaration->isBeforeInBlock(useAnchor);
}

LogicalResult verifyStringArray(Operation *operation, llvm::StringRef attributeName,
                                llvm::StringRef code, llvm::StringRef label) {
  auto values = operation->getAttrOfType<ArrayAttr>(attributeName);
  if (!values)
    return nodal::emitMappedFailure(operation, code, llvm::Twine(label) + " array is required");
  for (Attribute value : values) {
    auto text = llvm::dyn_cast<StringAttr>(value);
    if (!text || text.getValue().trim().empty())
      return nodal::emitMappedFailure(operation, code,
                                      llvm::Twine(label) + " entries must be non-empty strings");
  }
  return success();
}

LogicalResult verifyAnalysisApplicability(Operation *operation) {
  if (failed(verifyStringArray(operation, "analyses", "NODAL-ANALOG-033-015",
                               "analysis applicability")))
    return failure();
  auto analyses = operation->getAttrOfType<ArrayAttr>("analyses");
  if (analyses.empty())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-015",
        "procedural assignment requires at least one analysis applicability");
  llvm::StringSet<> seen;
  for (Attribute value : analyses) {
    llvm::StringRef analysis = llvm::cast<StringAttr>(value).getValue();
    if (!oneOf(analysis, {"initialization", "operating-point", "dc", "transient", "ac", "noise"}) ||
        !seen.insert(analysis).second)
      return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-015",
                                      llvm::Twine("invalid or duplicate procedural analysis '") +
                                          analysis + "'");
  }
  return success();
}

LogicalResult verifyVariableInitializerShape(Operation *operation,
                                             nodal::VariableType variableType) {
  auto initialized = operation->getAttrOfType<BoolAttr>("initialized");
  if (!initialized)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-004",
                                    "procedural variable requires initialized metadata");

  llvm::StringRef value = textAttr(operation, "initializer_value");
  llvm::StringRef kind = textAttr(operation, "initializer_kind");
  llvm::StringRef dimension = textAttr(operation, "initializer_dimension");
  auto reads = operation->getAttrOfType<ArrayAttr>("initializer_reads");
  if (!reads)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-004",
                                    "procedural initializer read inventory is required");

  if (!initialized.getValue()) {
    if (!value.empty() || !kind.empty() || !dimension.empty() || !reads.empty())
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-033-004",
          "uninitialized procedural variable must not carry initializer metadata");
    return success();
  }

  if (value.empty() || kind.empty() || dimension.empty())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-004",
        "initialized procedural variable requires value, kind, and dimension metadata");
  if (!oneOf(kind, {"integer", "real", "boolean"}) ||
      !proceduralKindsCompatible(kind, variableType.getKind()))
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-004",
        "procedural initializer kind is incompatible with the declared variable kind");
  if (!nodal::isCanonicalDimensionSignature(dimension) || dimension != variableType.getDimension())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-005",
        "procedural initializer dimension does not match the declared variable dimension");
  return verifyStringArray(operation, "initializer_reads", "NODAL-ANALOG-033-017",
                           "initializer read");
}

LogicalResult verifyGuardShape(Operation *operation) {
  auto present = operation->getAttrOfType<BoolAttr>("guard_present");
  if (!present)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-014",
                                    "procedural assignment requires guard metadata");
  llvm::StringRef value = textAttr(operation, "guard_value");
  llvm::StringRef kind = textAttr(operation, "guard_kind");
  llvm::StringRef dimension = textAttr(operation, "guard_dimension");
  auto reads = operation->getAttrOfType<ArrayAttr>("guard_reads");
  if (!reads)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-014",
                                    "procedural guard read inventory is required");
  if (!present.getValue()) {
    if (!value.empty() || !kind.empty() || !dimension.empty() || !reads.empty())
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-033-014",
          "unguarded procedural assignment must not carry guard metadata");
    return success();
  }
  if (value.empty() || kind != "boolean" || dimension != "1")
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-014",
        "procedural assignment guard must be a dimensionless Boolean value");
  return verifyStringArray(operation, "guard_reads", "NODAL-ANALOG-033-017", "guard read");
}

struct ProceduralVariableState {
  Operation *declaration = nullptr;
  Block *declarationBlock = nullptr;
  nodal::VariableType type;
  bool initialized = false;
};

struct ProceduralVerificationState {
  llvm::StringRef owner;
  llvm::DenseMap<Value, ProceduralVariableState> variables;
  llvm::StringMap<Value> variablesByIdentity;
  llvm::StringSet<> statements;
  int64_t nextDeclarationOrder = 0;
  int64_t nextAssignmentOrder = 0;
};

LogicalResult resolveProceduralVariable(Operation *operation, Value value,
                                        ProceduralVerificationState &state,
                                        ProceduralVariableState *&resolved) {
  auto found = state.variables.find(value);
  if (found == state.variables.end())
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-017",
                                    "procedural operation references an unknown variable handle");
  resolved = &found->second;
  if (textAttr(operation, "owner") != state.owner)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-009",
        "procedural operation owner does not match the enclosing component");
  if (!isVisibleFrom(operation->getBlock(), resolved->declarationBlock))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-010",
                                    "procedural variable is outside its lexical declaration scope");
  return success();
}

LogicalResult verifyReadInventory(Operation *operation, ArrayAttr reads,
                                  ProceduralVerificationState &state) {
  for (Attribute attribute : reads) {
    auto identity = llvm::dyn_cast<StringAttr>(attribute);
    if (!identity || identity.getValue().trim().empty())
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-033-017",
          "procedural read inventory entries must be non-empty variable identities");
    auto value = state.variablesByIdentity.find(identity.getValue());
    if (value == state.variablesByIdentity.end())
      return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-017",
                                      llvm::Twine("unknown procedural variable '") +
                                          identity.getValue() + "'");
    ProceduralVariableState *variable = nullptr;
    if (failed(resolveProceduralVariable(operation, value->second, state, variable)))
      return failure();
    if (!variable->initialized)
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-033-011",
          llvm::Twine("procedural variable '") + identity.getValue() +
              "' is read before initialization or an earlier assignment");
  }
  return success();
}

LogicalResult verifyProceduralBlock(Block &block, ProceduralVerificationState &state);

LogicalResult verifyProceduralScope(Operation *operation, ProceduralVerificationState &state) {
  if (failed(requireSingleBlock(operation)))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-016",
                                    "procedural lexical scope requires one body block");
  if (textAttr(operation, "scope_id").trim().empty())
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-016",
                                    "procedural lexical scope identity must be non-empty");
  if (textAttr(operation, "owner") != state.owner)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-009",
        "procedural lexical scope owner does not match the enclosing component");
  return verifyProceduralBlock(operation->getRegion(0).front(), state);
}

LogicalResult verifyProceduralDeclaration(Operation *operation,
                                          ProceduralVerificationState &state) {
  llvm::StringRef identity = textAttr(operation, "identity");
  if (identity.trim().empty())
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-001",
                                    "procedural variable identity must be non-empty");
  if (textAttr(operation, "owner") != state.owner)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-009",
                                    "procedural variable belongs to a different component");
  auto order = operation->getAttrOfType<IntegerAttr>("declaration_order");
  if (!order || order.getInt() != state.nextDeclarationOrder)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-002",
        "procedural variable declaration order must be contiguous and authored");
  if (state.variablesByIdentity.count(identity) != 0)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-002",
                                    llvm::Twine("duplicate procedural variable identity '") +
                                        identity + "'");

  auto variableType = llvm::dyn_cast<nodal::VariableType>(operation->getResult(0).getType());
  if (!variableType)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-019",
                                    "procedural declaration result must use !nodal.variable");
  if (failed(verifyVariableInitializerShape(operation, variableType)))
    return failure();

  auto initializerReads = operation->getAttrOfType<ArrayAttr>("initializer_reads");
  if (failed(verifyReadInventory(operation, initializerReads, state)))
    return failure();

  auto initialized = operation->getAttrOfType<BoolAttr>("initialized");
  state.variables.try_emplace(operation->getResult(0),
                              ProceduralVariableState{operation, operation->getBlock(),
                                                      variableType, initialized.getValue()});
  state.variablesByIdentity[identity] = operation->getResult(0);
  ++state.nextDeclarationOrder;
  return success();
}

LogicalResult verifyProceduralRead(Operation *operation, ProceduralVerificationState &state) {
  ProceduralVariableState *variable = nullptr;
  if (failed(resolveProceduralVariable(operation, operation->getOperand(0), state, variable)))
    return failure();
  if (!variable->initialized)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-011",
        "procedural variable is read before initialization or an earlier assignment");
  auto result = getProceduralValueInfo(operation->getResult(0).getType());
  if (failed(result) || result->kind != variable->type.getKind() ||
      result->dimension != variable->type.getDimension())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-012",
        "procedural read result kind or dimension does not match its variable");
  return success();
}

LogicalResult verifyProceduralAssignment(Operation *operation, ProceduralVerificationState &state) {
  llvm::StringRef statement = textAttr(operation, "statement_id");
  if (statement.trim().empty())
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-006",
                                    "procedural statement identity must be non-empty");
  if (!state.statements.insert(statement).second)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-007",
                                    llvm::Twine("duplicate procedural statement identity '") +
                                        statement + "'");
  auto order = operation->getAttrOfType<IntegerAttr>("authored_order");
  if (!order || order.getInt() != state.nextAssignmentOrder)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-007",
                                    "procedural assignment order must be contiguous and authored");

  ProceduralVariableState *target = nullptr;
  if (failed(resolveProceduralVariable(operation, operation->getOperand(0), state, target)))
    return failure();

  llvm::StringRef valueKind = textAttr(operation, "value_kind");
  llvm::StringRef valueDimension = textAttr(operation, "value_dimension");
  if (!oneOf(valueKind, {"integer", "real", "boolean"}) ||
      !proceduralKindsCompatible(valueKind, target->type.getKind()))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-012",
                                    "assigned value kind is incompatible with the target variable");
  if (!nodal::isCanonicalDimensionSignature(valueDimension) ||
      valueDimension != target->type.getDimension())
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-013",
                                    "assigned value dimension does not match the target variable");

  for (Value value : operation->getOperands().drop_front()) {
    if (!value.getDefiningOp<nodal::AnalogVariableReadOp>())
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-033-017",
          "procedural assignment value operands must be explicit variable reads");
  }

  if (failed(verifyAnalysisApplicability(operation)) || failed(verifyGuardShape(operation)))
    return failure();
  if (auto guardReads = operation->getAttrOfType<ArrayAttr>("guard_reads")) {
    if (failed(verifyReadInventory(operation, guardReads, state)))
      return failure();
  }

  target->initialized = true;
  ++state.nextAssignmentOrder;
  return success();
}

LogicalResult verifyProceduralBlock(Block &block, ProceduralVerificationState &state) {
  for (Operation &operation : block) {
    if (llvm::isa<nodal::AnalogVariableOp>(operation)) {
      if (failed(verifyProceduralDeclaration(&operation, state)))
        return failure();
      continue;
    }
    if (llvm::isa<nodal::AnalogVariableReadOp>(operation)) {
      if (failed(verifyProceduralRead(&operation, state)))
        return failure();
      continue;
    }
    if (llvm::isa<nodal::AnalogAssignOp>(operation)) {
      if (failed(verifyProceduralAssignment(&operation, state)))
        return failure();
      continue;
    }
    if (llvm::isa<nodal::AnalogScopeOp>(operation)) {
      if (failed(verifyProceduralScope(&operation, state)))
        return failure();
      continue;
    }
    if (llvm::isa<nodal::AnalogProcedureOp>(operation))
      return nodal::emitMappedFailure(&operation, "NODAL-ANALOG-033-018",
                                      "nested analog procedural regions are not supported");
    return nodal::emitMappedFailure(&operation, "NODAL-ANALOG-033-008",
                                    "operation is not legal in an analog procedural region");
  }
  return success();
}

LogicalResult verifyStructuredProceduralBlock(Block &block, llvm::StringRef owner) {
  for (Operation &operation : block) {
    if (nodal::isAnalogEventExpression(&operation) || llvm::isa<nodal::AnalogOnOp>(operation)) {
      if (failed(nodal::verifyAnalogEventOperation(&operation)))
        return failure();
      if (llvm::isa<nodal::AnalogOnOp>(operation) &&
          failed(verifyStructuredProceduralBlock(operation.getRegion(0).front(), owner)))
        return failure();
      continue;
    }
    if (llvm::isa<nodal::AnalogVariableOp, nodal::AnalogVariableReadOp, nodal::AnalogAssignOp,
                  nodal::AnalogBreakOp, nodal::AnalogContinueOp>(operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured procedural operation owner does not match the enclosing component");
      continue;
    }

    if (auto scope = llvm::dyn_cast<nodal::AnalogScopeOp>(&operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured lexical scope owner does not match the enclosing component");
      if (failed(
              verifyStructuredProceduralBlock(scope.getOperation()->getRegion(0).front(), owner)))
        return failure();
      continue;
    }

    if (auto conditional = llvm::dyn_cast<nodal::AnalogIfOp>(&operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured conditional owner does not match the enclosing component");
      for (Operation &nested : conditional.getOperation()->getRegion(0).front()) {
        auto arm = llvm::dyn_cast<nodal::AnalogIfArmOp>(&nested);
        if (!arm)
          return nodal::emitMappedFailure(
              &nested, "NODAL-ANALOG-034-015",
              "analog conditional body may contain only analog_if_arm operations");
        if (failed(
                verifyStructuredProceduralBlock(arm.getOperation()->getRegion(0).front(), owner)))
          return failure();
      }
      continue;
    }

    if (auto selection = llvm::dyn_cast<nodal::AnalogCaseOp>(&operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured case owner does not match the enclosing component");
      for (Operation &nested : selection.getOperation()->getRegion(0).front()) {
        auto arm = llvm::dyn_cast<nodal::AnalogCaseArmOp>(&nested);
        if (!arm)
          return nodal::emitMappedFailure(
              &nested, "NODAL-ANALOG-034-015",
              "analog case body may contain only analog_case_arm operations");
        if (failed(
                verifyStructuredProceduralBlock(arm.getOperation()->getRegion(0).front(), owner)))
          return failure();
      }
      continue;
    }

    if (auto loop = llvm::dyn_cast<nodal::AnalogLoopOp>(&operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured loop owner does not match the enclosing component");
      if (failed(verifyStructuredProceduralBlock(loop.getOperation()->getRegion(0).front(), owner)))
        return failure();
      continue;
    }

    if (llvm::isa<nodal::AnalogIfArmOp, nodal::AnalogCaseArmOp>(operation))
      return nodal::emitMappedFailure(
          &operation, "NODAL-ANALOG-034-015",
          "analog control-flow arm must be nested directly under its owning selection");

    if (llvm::isa<nodal::AnalogProcedureOp>(operation))
      return nodal::emitMappedFailure(&operation, "NODAL-ANALOG-033-018",
                                      "nested analog procedural regions are not supported");

    return nodal::emitMappedFailure(
        &operation, "NODAL-ANALOG-034-014",
        "operation is not legal in structured analog procedural control flow");
  }
  return success();
}

using StructuredInitializedSet = std::set<std::string>;

struct StructuredVariableInfo {
  Operation *declaration = nullptr;
  Block *declarationBlock = nullptr;
};

struct StructuredDataflowContext {
  llvm::StringMap<StructuredVariableInfo> variables;
  llvm::StringSet<> operationIdentities;
  int64_t nextDeclarationOrder = 0;
  int64_t nextAssignmentOrder = 0;
};

struct StructuredFlow {
  std::optional<StructuredInitializedSet> normal;
  std::vector<StructuredInitializedSet> breaks;
  std::vector<StructuredInitializedSet> continues;
};

std::optional<StructuredInitializedSet>
intersectStructuredStates(const std::vector<StructuredInitializedSet> &states) {
  if (states.empty())
    return std::nullopt;
  StructuredInitializedSet result = states.front();
  for (auto state = std::next(states.begin()); state != states.end(); ++state) {
    for (auto value = result.begin(); value != result.end();) {
      if (state->find(*value) == state->end())
        value = result.erase(value);
      else
        ++value;
    }
  }
  return result;
}

void appendStructuredEscapes(StructuredFlow &destination, const StructuredFlow &source) {
  destination.breaks.insert(destination.breaks.end(), source.breaks.begin(), source.breaks.end());
  destination.continues.insert(destination.continues.end(), source.continues.begin(),
                               source.continues.end());
}

void removeStructuredLocals(StructuredFlow &flow, const std::vector<std::string> &locals) {
  auto remove = [&](StructuredInitializedSet &state) {
    for (const std::string &identity : locals)
      state.erase(identity);
  };
  if (flow.normal)
    remove(*flow.normal);
  for (StructuredInitializedSet &state : flow.breaks)
    remove(state);
  for (StructuredInitializedSet &state : flow.continues)
    remove(state);
}

bool isStructuredIdentityOperation(Operation *operation) {
  if (nodal::isAnalogEventExpression(operation) || llvm::isa<nodal::AnalogOnOp>(operation))
    return true;
  return llvm::isa<nodal::AnalogVariableOp, nodal::AnalogVariableReadOp, nodal::AnalogAssignOp,
                   nodal::AnalogScopeOp, nodal::AnalogIfOp, nodal::AnalogIfArmOp,
                   nodal::AnalogCaseOp, nodal::AnalogCaseArmOp, nodal::AnalogLoopOp,
                   nodal::AnalogBreakOp, nodal::AnalogContinueOp>(operation);
}

llvm::StringRef structuredOperationIdentity(Operation *operation) {
  if (nodal::isAnalogEventExpression(operation))
    return textAttr(operation, "event_id");
  if (llvm::isa<nodal::AnalogVariableOp>(operation))
    return textAttr(operation, "identity");
  if (llvm::isa<nodal::AnalogVariableReadOp>(operation))
    return textAttr(operation, "read_id");
  if (llvm::isa<nodal::AnalogScopeOp>(operation))
    return textAttr(operation, "scope_id");
  if (llvm::isa<nodal::AnalogIfArmOp, nodal::AnalogCaseArmOp>(operation))
    return textAttr(operation, "arm_id");
  return textAttr(operation, "statement_id");
}

LogicalResult registerStructuredOperationIdentity(Operation *operation,
                                                  StructuredDataflowContext &context) {
  if (!isStructuredIdentityOperation(operation))
    return success();
  llvm::StringRef identity = structuredOperationIdentity(operation);
  if (identity.trim().empty() || identity.trim() != identity)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-001",
        "structured operation identity must be non-empty and canonical");
  if (!context.operationIdentities.insert(identity).second)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-001",
                                    llvm::Twine("duplicate structured operation identity '") +
                                        identity + "'");
  return success();
}

LogicalResult collectStructuredVariables(Block &block, llvm::StringRef owner,
                                         StructuredDataflowContext &context) {
  for (Operation &operation : block) {
    if (failed(registerStructuredOperationIdentity(&operation, context)))
      return failure();
    if (llvm::isa<nodal::AnalogVariableOp>(operation)) {
      llvm::StringRef identity = textAttr(&operation, "identity");
      if (identity.trim().empty())
        return nodal::emitMappedFailure(&operation, "NODAL-ANALOG-034-014",
                                        "structured variable identity must be non-empty");
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured variable owner does not match the enclosing component");
      auto declarationOrder = operation.getAttrOfType<IntegerAttr>("declaration_order");
      if (!declarationOrder || declarationOrder.getInt() != context.nextDeclarationOrder)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured declaration order must be contiguous and authored");
      ++context.nextDeclarationOrder;
      if (!context.variables
               .try_emplace(identity, StructuredVariableInfo{&operation, operation.getBlock()})
               .second)
        return nodal::emitMappedFailure(&operation, "NODAL-ANALOG-034-014",
                                        llvm::Twine("duplicate structured variable identity '") +
                                            identity + "'");
    }
    if (llvm::isa<nodal::AnalogAssignOp>(operation)) {
      auto authoredOrder = operation.getAttrOfType<IntegerAttr>("authored_order");
      if (!authoredOrder || authoredOrder.getInt() != context.nextAssignmentOrder)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured assignment order must be contiguous and authored");
      ++context.nextAssignmentOrder;
    }
    for (Region &region : operation.getRegions()) {
      for (Block &nested : region) {
        if (failed(collectStructuredVariables(nested, owner, context)))
          return failure();
      }
    }
  }
  return success();
}

LogicalResult requireStructuredReference(Operation *operation, llvm::StringRef identity,
                                         const StructuredDataflowContext &context) {
  if (identity.trim().empty() || identity.trim() != identity)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        "structured variable reference must be non-empty and canonical");
  auto variable = context.variables.find(identity);
  if (variable == context.variables.end())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        llvm::Twine("structured control flow references unknown variable '") + identity + "'");
  if (!isVisibleFrom(operation->getBlock(), variable->second.declarationBlock))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                    llvm::Twine("structured variable '") + identity +
                                        "' is outside its lexical declaration scope");
  if (!isDeclaredBeforeStructuredUse(variable->second.declaration, operation))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                    llvm::Twine("structured variable '") + identity +
                                        "' must be declared before use");
  return success();
}

LogicalResult requireStructuredRead(Operation *operation, llvm::StringRef identity,
                                    const StructuredInitializedSet &initialized,
                                    const StructuredDataflowContext &context) {
  if (failed(requireStructuredReference(operation, identity, context)))
    return failure();
  if (initialized.find(identity.str()) == initialized.end())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-004",
        llvm::Twine("procedural variable '") + identity +
            "' is read before definite initialization on this control-flow path");
  return success();
}

LogicalResult requireStructuredReads(Operation *operation, llvm::StringRef attributeName,
                                     const StructuredInitializedSet &initialized,
                                     const StructuredDataflowContext &context) {
  auto reads = operation->getAttrOfType<ArrayAttr>(attributeName);
  if (!reads)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                    llvm::Twine("missing structured read inventory '") +
                                        attributeName + "'");
  for (Attribute attribute : reads) {
    auto identity = llvm::dyn_cast<StringAttr>(attribute);
    if (!identity || identity.getValue().trim().empty())
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-034-014",
          "structured read inventory entries must be non-empty identities");
    if (failed(requireStructuredRead(operation, identity.getValue(), initialized, context)))
      return failure();
  }
  return success();
}

FailureOr<std::string> structuredVariableIdentity(Operation *operation, Value value,
                                                  const StructuredDataflowContext &context,
                                                  bool requireInitialized,
                                                  const StructuredInitializedSet &initialized) {
  auto declaration = value.getDefiningOp<nodal::AnalogVariableOp>();
  if (!declaration) {
    (void)nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        "structured procedural variable operand must resolve to an analog_variable");
    return failure();
  }
  llvm::StringRef identity = textAttr(declaration.getOperation(), "identity");
  auto variable = context.variables.find(identity);
  if (variable == context.variables.end())
    return failure();
  if (!isVisibleFrom(operation->getBlock(), variable->second.declarationBlock)) {
    (void)nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                   llvm::Twine("structured variable '") + identity +
                                       "' is outside its lexical declaration scope");
    return failure();
  }
  if (!isDeclaredBeforeStructuredUse(variable->second.declaration, operation)) {
    (void)nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                   llvm::Twine("structured variable '") + identity +
                                       "' must be declared before use");
    return failure();
  }
  if (requireInitialized && initialized.find(identity.str()) == initialized.end()) {
    (void)nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-004",
        llvm::Twine("procedural variable '") + identity +
            "' is read before definite initialization on this control-flow path");
    return failure();
  }
  return identity.str();
}

LogicalResult verifyStructuredReferenceInventory(Operation *operation,
                                                 llvm::StringRef attributeName,
                                                 const StructuredDataflowContext &context) {
  auto references = operation->getAttrOfType<ArrayAttr>(attributeName);
  if (!references)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                    llvm::Twine("missing structured reference inventory '") +
                                        attributeName + "'");
  for (Attribute attribute : references) {
    auto identity = llvm::dyn_cast<StringAttr>(attribute);
    if (!identity)
      return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                      "structured reference inventory entries must be strings");
    if (failed(requireStructuredReference(operation, identity.getValue(), context)))
      return failure();
  }
  return success();
}

LogicalResult verifyStructuredReferences(Block &block, const StructuredDataflowContext &context) {
  const StructuredInitializedSet noInitializationRequirement;
  for (Operation &operation : block) {
    llvm::StringRef inventory;
    if (llvm::isa<nodal::AnalogVariableOp>(operation))
      inventory = "initializer_reads";
    else if (llvm::isa<nodal::AnalogAssignOp>(operation))
      inventory = "guard_reads";
    else if (llvm::isa<nodal::AnalogIfArmOp>(operation))
      inventory = "condition_reads";
    else if (llvm::isa<nodal::AnalogCaseOp>(operation))
      inventory = "selector_reads";
    else if (llvm::isa<nodal::AnalogLoopOp>(operation))
      inventory = "bound_reads";
    else if (nodal::isAnalogEventExpression(&operation) &&
             !llvm::isa<nodal::AnalogEventOrOp>(operation))
      inventory = "event_reads";

    if (!inventory.empty() &&
        failed(verifyStructuredReferenceInventory(&operation, inventory, context)))
      return failure();

    if (llvm::isa<nodal::AnalogVariableReadOp>(operation)) {
      if (failed(structuredVariableIdentity(&operation, operation.getOperand(0), context,
                                            /*requireInitialized=*/false,
                                            noInitializationRequirement)))
        return failure();
    }

    if (llvm::isa<nodal::AnalogAssignOp>(operation)) {
      if (failed(structuredVariableIdentity(&operation, operation.getOperand(0), context,
                                            /*requireInitialized=*/false,
                                            noInitializationRequirement)))
        return failure();
      for (Value value : operation.getOperands().drop_front()) {
        auto read = value.getDefiningOp<nodal::AnalogVariableReadOp>();
        if (!read)
          return nodal::emitMappedFailure(
              &operation, "NODAL-ANALOG-034-014",
              "structured assignment operands must be explicit variable reads");
        if (failed(structuredVariableIdentity(
                &operation, read.getOperation()->getOperand(0), context,
                /*requireInitialized=*/false, noInitializationRequirement)))
          return failure();
      }
    }

    for (Region &region : operation.getRegions()) {
      for (Block &nested : region) {
        if (failed(verifyStructuredReferences(nested, context)))
          return failure();
      }
    }
  }
  return success();
}

FailureOr<StructuredFlow> analyzeStructuredDataflowBlock(Block &block,
                                                         const StructuredInitializedSet &input,
                                                         StructuredDataflowContext &context,
                                                         bool retainLocals);

FailureOr<StructuredFlow> analyzeStructuredIf(nodal::AnalogIfOp conditional,
                                              const StructuredInitializedSet &input,
                                              StructuredDataflowContext &context) {
  StructuredFlow result;
  std::vector<StructuredInitializedSet> normalStates;
  bool unmatchedReachable = true;

  for (Operation &operation : conditional.getOperation()->getRegion(0).front()) {
    auto arm = llvm::cast<nodal::AnalogIfArmOp>(&operation);
    auto isElse = operation.getAttrOfType<BoolAttr>("is_else");
    bool reachable = unmatchedReachable;
    if (isElse.getValue()) {
      unmatchedReachable = false;
    } else {
      llvm::StringRef stage = textAttr(&operation, "stage");
      if (stage == "static") {
        auto value = operation.getAttrOfType<BoolAttr>("static_value");
        reachable = unmatchedReachable && value.getValue();
        if (value.getValue())
          unmatchedReachable = false;
      } else {
        reachable = unmatchedReachable;
      }
    }

    if (!reachable)
      continue;
    if (!isElse.getValue() &&
        failed(requireStructuredReads(&operation, "condition_reads", input, context)))
      return failure();
    auto branch =
        analyzeStructuredDataflowBlock(arm.getOperation()->getRegion(0).front(), input, context,
                                       /*retainLocals=*/false);
    if (failed(branch))
      return failure();
    if (branch->normal)
      normalStates.push_back(*branch->normal);
    appendStructuredEscapes(result, *branch);
  }

  if (unmatchedReachable)
    normalStates.push_back(input);
  result.normal = intersectStructuredStates(normalStates);
  return result;
}

FailureOr<StructuredFlow> analyzeStructuredCase(nodal::AnalogCaseOp selection,
                                                const StructuredInitializedSet &input,
                                                StructuredDataflowContext &context) {
  if (failed(requireStructuredReads(selection.getOperation(), "selector_reads", input, context)))
    return failure();

  StructuredFlow result;
  std::vector<StructuredInitializedSet> normalStates;
  auto staticPresent = selection.getOperation()->getAttrOfType<BoolAttr>("static_value_present");
  llvm::StringRef staticValue = textAttr(selection.getOperation(), "static_value");
  Operation *defaultArm = nullptr;

  if (staticPresent.getValue()) {
    Operation *selected = nullptr;
    for (Operation &operation : selection.getOperation()->getRegion(0).front()) {
      auto isDefault = operation.getAttrOfType<BoolAttr>("is_default");
      if (isDefault.getValue()) {
        defaultArm = &operation;
        continue;
      }
      auto labels = operation.getAttrOfType<ArrayAttr>("labels");
      for (Attribute attribute : labels) {
        if (llvm::cast<StringAttr>(attribute).getValue() == staticValue) {
          selected = &operation;
          break;
        }
      }
      if (selected)
        break;
    }
    Operation *reachable = selected ? selected : defaultArm;
    if (!reachable) {
      result.normal = input;
      return result;
    }
    auto branch = analyzeStructuredDataflowBlock(reachable->getRegion(0).front(), input, context,
                                                 /*retainLocals=*/false);
    if (failed(branch))
      return failure();
    return *branch;
  }

  bool hasDefault = false;
  for (Operation &operation : selection.getOperation()->getRegion(0).front()) {
    auto arm = llvm::cast<nodal::AnalogCaseArmOp>(&operation);
    if (operation.getAttrOfType<BoolAttr>("is_default").getValue())
      hasDefault = true;
    auto branch =
        analyzeStructuredDataflowBlock(arm.getOperation()->getRegion(0).front(), input, context,
                                       /*retainLocals=*/false);
    if (failed(branch))
      return failure();
    if (branch->normal)
      normalStates.push_back(*branch->normal);
    appendStructuredEscapes(result, *branch);
  }
  if (!hasDefault)
    normalStates.push_back(input);
  result.normal = intersectStructuredStates(normalStates);
  return result;
}

FailureOr<StructuredFlow> analyzeStructuredLoop(nodal::AnalogLoopOp loop,
                                                const StructuredInitializedSet &input,
                                                StructuredDataflowContext &context) {
  if (failed(requireStructuredReads(loop.getOperation(), "bound_reads", input, context)))
    return failure();

  llvm::StringRef stage = textAttr(loop.getOperation(), "stage");
  auto minimum = loop.getOperation()->getAttrOfType<IntegerAttr>("minimum_iterations");
  auto staticCount = loop.getOperation()->getAttrOfType<IntegerAttr>("static_trip_count");
  if (stage == "static" && staticCount.getInt() == 0)
    return StructuredFlow{input, {}, {}};

  auto body =
      analyzeStructuredDataflowBlock(loop.getOperation()->getRegion(0).front(), input, context,
                                     /*retainLocals=*/false);
  if (failed(body))
    return failure();

  std::vector<StructuredInitializedSet> exits;
  if (minimum.getInt() == 0)
    exits.push_back(input);
  if (body->normal)
    exits.push_back(*body->normal);
  exits.insert(exits.end(), body->breaks.begin(), body->breaks.end());
  exits.insert(exits.end(), body->continues.begin(), body->continues.end());

  StructuredFlow result;
  result.normal = intersectStructuredStates(exits);
  return result;
}

FailureOr<StructuredFlow> analyzeStructuredDataflowStatement(Operation *operation,
                                                             const StructuredInitializedSet &input,
                                                             StructuredDataflowContext &context) {
  if (llvm::isa<nodal::AnalogVariableOp>(operation)) {
    StructuredInitializedSet output = input;
    if (failed(requireStructuredReads(operation, "initializer_reads", input, context)))
      return failure();
    llvm::StringRef identity = textAttr(operation, "identity");
    if (operation->getAttrOfType<BoolAttr>("initialized").getValue())
      output.insert(identity.str());
    return StructuredFlow{output, {}, {}};
  }

  if (llvm::isa<nodal::AnalogVariableReadOp>(operation)) {
    auto identity = structuredVariableIdentity(operation, operation->getOperand(0), context,
                                               /*requireInitialized=*/true, input);
    if (failed(identity))
      return failure();
    return StructuredFlow{input, {}, {}};
  }

  if (llvm::isa<nodal::AnalogAssignOp>(operation)) {
    StructuredInitializedSet output = input;
    if (failed(requireStructuredReads(operation, "guard_reads", input, context)))
      return failure();
    auto target = structuredVariableIdentity(operation, operation->getOperand(0), context,
                                             /*requireInitialized=*/false, input);
    if (failed(target))
      return failure();
    for (Value value : operation->getOperands().drop_front()) {
      auto read = value.getDefiningOp<nodal::AnalogVariableReadOp>();
      if (!read) {
        (void)nodal::emitMappedFailure(
            operation, "NODAL-ANALOG-034-014",
            "structured assignment operands must be explicit variable reads");
        return failure();
      }
      auto identity =
          structuredVariableIdentity(operation, read.getOperation()->getOperand(0), context,
                                     /*requireInitialized=*/true, input);
      if (failed(identity))
        return failure();
    }
    output.insert(*target);
    return StructuredFlow{output, {}, {}};
  }

  if (nodal::isAnalogEventExpression(operation)) {
    if (!llvm::isa<nodal::AnalogEventOrOp>(operation) &&
        failed(requireStructuredReads(operation, "event_reads", input, context)))
      return failure();
    return StructuredFlow{input, {}, {}};
  }
  if (llvm::isa<nodal::AnalogOnOp>(operation)) {
    auto body = analyzeStructuredDataflowBlock(operation->getRegion(0).front(), input, context,
                                               /*retainLocals=*/false);
    if (failed(body))
      return failure();
    if (!body->breaks.empty() || !body->continues.empty()) {
      (void)nodal::emitMappedFailure(operation, "NODAL-ANALOG-037-007",
                                     "loop control cannot escape an event body");
      return failure();
    }
    // An event is not guaranteed to fire; body writes do not initialize following code.
    return StructuredFlow{input, {}, {}};
  }

  if (auto scope = llvm::dyn_cast<nodal::AnalogScopeOp>(operation))
    return analyzeStructuredDataflowBlock(scope.getOperation()->getRegion(0).front(), input,
                                          context,
                                          /*retainLocals=*/false);
  if (auto conditional = llvm::dyn_cast<nodal::AnalogIfOp>(operation))
    return analyzeStructuredIf(conditional, input, context);
  if (auto selection = llvm::dyn_cast<nodal::AnalogCaseOp>(operation))
    return analyzeStructuredCase(selection, input, context);
  if (auto loop = llvm::dyn_cast<nodal::AnalogLoopOp>(operation))
    return analyzeStructuredLoop(loop, input, context);
  if (llvm::isa<nodal::AnalogBreakOp>(operation))
    return StructuredFlow{std::nullopt, {input}, {}};
  if (llvm::isa<nodal::AnalogContinueOp>(operation))
    return StructuredFlow{std::nullopt, {}, {input}};

  (void)nodal::emitMappedFailure(
      operation, "NODAL-ANALOG-034-014",
      "unsupported operation reached structured definite-assignment analysis");
  return failure();
}

FailureOr<StructuredFlow> analyzeStructuredDataflowBlock(Block &block,
                                                         const StructuredInitializedSet &input,
                                                         StructuredDataflowContext &context,
                                                         bool retainLocals) {
  StructuredFlow flow{input, {}, {}};
  std::vector<std::string> locals;
  for (Operation &operation : block) {
    if (llvm::isa<nodal::AnalogVariableOp>(operation))
      locals.push_back(textAttr(&operation, "identity").str());
    if (!flow.normal)
      continue;
    auto statement = analyzeStructuredDataflowStatement(&operation, *flow.normal, context);
    if (failed(statement))
      return failure();
    appendStructuredEscapes(flow, *statement);
    flow.normal = statement->normal;
  }
  if (!retainLocals)
    removeStructuredLocals(flow, locals);
  return flow;
}

LogicalResult verifyStructuredDefiniteAssignment(Operation *procedure, llvm::StringRef owner) {
  StructuredDataflowContext context;
  Block &body = procedure->getRegion(0).front();
  if (failed(collectStructuredVariables(body, owner, context)))
    return failure();
  if (failed(verifyStructuredReferences(body, context)))
    return failure();
  auto flow = analyzeStructuredDataflowBlock(body, StructuredInitializedSet{}, context,
                                             /*retainLocals=*/true);
  if (failed(flow))
    return failure();
  if (!flow->breaks.empty())
    return nodal::emitMappedFailure(procedure, "NODAL-ANALOG-034-010",
                                    "break escaped the nearest runtime-bounded analog loop");
  if (!flow->continues.empty())
    return nodal::emitMappedFailure(procedure, "NODAL-ANALOG-034-011",
                                    "continue escaped the nearest runtime-bounded analog loop");
  return success();
}

LogicalResult verifySingleTopLevelProcedurePerModule(Operation *module) {
  bool seenProcedure = false;
  Operation *duplicateProcedure = nullptr;
  module->walk([&](nodal::AnalogProcedureOp procedure) {
    auto parentModule = procedure->getParentOfType<nodal::ModuleOp>();
    if (!parentModule || parentModule.getOperation() != module ||
        procedure->getParentOfType<nodal::AnalogProcedureOp>())
      return;
    if (seenProcedure && !duplicateProcedure)
      duplicateProcedure = procedure.getOperation();
    seenProcedure = true;
  });
  if (!duplicateProcedure)
    return success();
  return nodal::emitMappedFailure(duplicateProcedure, "NODAL-ANALOG-033-020",
                                  "multiple analog procedural regions per component are deferred");
}

LogicalResult verifyAnalogProcedure(Operation *operation) {
  if (failed(requireSingleBlock(operation)))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-008",
                                    "analog procedural region requires exactly one body block");
  llvm::StringRef owner = textAttr(operation, "owner");
  if (owner.trim().empty() || owner.trim() != owner)
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-009",
                                    "analog procedural owner must be non-empty and canonical");
  bool hasStructuredControl = false;
  operation->walk([&](Operation *nested) {
    if (llvm::isa<nodal::AnalogIfOp, nodal::AnalogCaseOp, nodal::AnalogLoopOp, nodal::AnalogBreakOp,
                  nodal::AnalogContinueOp, nodal::AnalogOnOp>(nested) ||
        nodal::isAnalogEventExpression(nested))
      hasStructuredControl = true;
  });
  if (hasStructuredControl) {
    if (failed(verifyStructuredProceduralBlock(operation->getRegion(0).front(), owner)))
      return failure();
    return verifyStructuredDefiniteAssignment(operation, owner);
  }

  ProceduralVerificationState state;
  state.owner = owner;
  return verifyProceduralBlock(operation->getRegion(0).front(), state);
}

} // namespace

LogicalResult nodal::PlaceholderOp::verify() {
  auto label = (*this)->getAttrOfType<StringAttr>("label");
  if (!label || label.getValue().empty())
    return emitOpError("requires a non-empty 'label' attribute");
  return success();
}

LogicalResult nodal::ModuleOp::verify() {
  if (failed(requireText(getOperation(), mlir::SymbolTable::getSymbolAttrName(), "module symbol")))
    return failure();
  if (failed(requireSingleBlock(getOperation())))
    return failure();
  return verifySingleTopLevelProcedurePerModule(getOperation());
}

LogicalResult nodal::PortOp::verify() {
  const llvm::StringRef direction = textAttr(getOperation(), "direction");
  if (!oneOf(direction, {"input", "output", "inout", "terminal"}))
    return emitOpError() << "unsupported port direction '" << direction << "'";
  if (!getOperation()->getAttrOfType<TypeAttr>("type") ||
      !getOperation()->getAttrOfType<FlatSymbolRefAttr>("domain"))
    return emitOpError("requires type and domain");
  return success();
}

LogicalResult nodal::ParameterOp::verify() {
  return nodal::verifyParameterDeclaration(getOperation());
}

LogicalResult nodal::InstanceOp::verify() {
  auto module = getOperation()->getAttrOfType<FlatSymbolRefAttr>("module");
  if (!module || module.getValue().empty())
    return emitOpError("requires a referenced module symbol");
  return success();
}

LogicalResult nodal::InterfaceOp::verify() {
  if (failed(
          requireText(getOperation(), mlir::SymbolTable::getSymbolAttrName(), "interface symbol")))
    return failure();
  return requireSingleBlock(getOperation());
}

LogicalResult nodal::InterfaceRoleOp::verify() {
  return requireText(getOperation(), "kind", "interface role kind");
}

LogicalResult nodal::InterfaceMemberOp::verify() {
  const llvm::StringRef protocol = textAttr(getOperation(), "protocol");
  if (!oneOf(protocol, {"plain", "valid", "stream", "resolved", "conservative", "signal_flow"}))
    return emitOpError() << "unsupported interface protocol '" << protocol << "'";
  auto type = getOperation()->getAttrOfType<TypeAttr>("type");
  auto roles = getOperation()->getAttrOfType<ArrayAttr>("roles");
  if (!type || !roles || roles.empty())
    return emitOpError("requires a type and at least one role");
  if (protocol == "valid" && !llvm::isa<nodal::ValidType>(type.getValue()))
    return emitOpError("valid protocol requires !nodal.valid type");
  if (protocol == "stream" && !llvm::isa<nodal::StreamType>(type.getValue()))
    return emitOpError("stream protocol requires !nodal.stream type");
  if (protocol == "resolved" && !llvm::isa<nodal::ResolvedType>(type.getValue()))
    return emitOpError("resolved protocol requires !nodal.resolved type");
  return success();
}

LogicalResult nodal::InterfaceInstanceOp::verify() {
  if (failed(requireText(getOperation(), "role", "interface role")))
    return failure();
  if (!getOperation()->getAttrOfType<FlatSymbolRefAttr>("definition"))
    return emitOpError("requires an Interface definition");
  return success();
}

LogicalResult nodal::MemberAccessOp::verify() {
  if (failed(requireText(getOperation(), "path", "member path")))
    return failure();
  if (!getOperation()->getAttrOfType<FlatSymbolRefAttr>("instance"))
    return emitOpError("requires an Interface instance");
  return success();
}

LogicalResult nodal::InterfaceAbiOp::verify() {
  if (failed(requireText(getOperation(), "logical_path", "logical path")) ||
      failed(requireText(getOperation(), "layout_policy", "layout policy")))
    return failure();
  auto members = getOperation()->getAttrOfType<ArrayAttr>("members");
  if (!members || members.empty())
    return emitOpError("requires at least one ABI member");
  return success();
}

LogicalResult nodal::DomainOp::verify() {
  const llvm::StringRef edge = textAttr(getOperation(), "edge");
  const llvm::StringRef reset = textAttr(getOperation(), "reset_policy");
  if (!oneOf(edge, {"rising", "falling", "both", "analog_event"}))
    return emitOpError() << "unsupported domain edge '" << edge << "'";
  if (!oneOf(reset, {"none", "sync", "async", "async_assert_sync_release"}))
    return emitOpError() << "unsupported reset policy '" << reset << "'";
  return success();
}

LogicalResult nodal::DomainRequirementOp::verify() {
  return requireText(getOperation(), mlir::SymbolTable::getSymbolAttrName(),
                     "domain requirement symbol");
}

LogicalResult nodal::DomainBindOp::verify() {
  if (!getOperation()->getAttrOfType<FlatSymbolRefAttr>("requirement") ||
      !getOperation()->getAttrOfType<FlatSymbolRefAttr>("actual"))
    return emitOpError("requires requirement and actual domains");
  return success();
}

LogicalResult nodal::ClockRelationOp::verify() {
  return verifyRelation(
      getOperation(), "clock relation",
      {"alias", "ratio", "synchronous", "mutually_exclusive", "asynchronous", "unknown"});
}

LogicalResult nodal::ResetRelationOp::verify() {
  return verifyRelation(
      getOperation(), "reset relation",
      {"alias", "synchronous", "async_assert_sync_release", "independent", "unknown"});
}

LogicalResult nodal::ConstantOp::verify() {
  Attribute value = getOperation()->getAttr("value");
  if (!value || getOperation()->getNumResults() != 1 ||
      !attributeFits(value, getOperation()->getResult(0).getType()))
    return emitOpError("value is incompatible with result type");
  return success();
}

LogicalResult nodal::ShapeIndexOp::verify() {
  auto shaped = llvm::dyn_cast<nodal::ShapedType>(getOperation()->getOperand(0).getType());
  if (!shaped)
    return emitOpError("input must have !nodal.shaped type");
  const unsigned indices = getOperation()->getNumOperands() - 1;
  if (indices != shapedRank(shaped.getDimensions()))
    return emitOpError("index rank does not match shaped rank");
  if (getOperation()->getResult(0).getType() != shaped.getElementType())
    return emitOpError("result must match shaped element type");
  return success();
}

LogicalResult nodal::ShapeFlattenOp::verify() {
  if (!llvm::isa<nodal::ShapedType>(getOperation()->getOperand(0).getType()))
    return emitOpError("input must have !nodal.shaped type");
  return requireText(getOperation(), "layout", "layout policy");
}

LogicalResult nodal::ShapeViewOp::verify() {
  if (failed(requireText(getOperation(), "dimensions", "dimensions")) ||
      failed(requireText(getOperation(), "origin", "origin")) ||
      failed(requireText(getOperation(), "materialization", "materialization")) ||
      failed(requireText(getOperation(), "observability", "observability")))
    return failure();
  return success();
}

LogicalResult nodal::GenerateOp::verify() { return verifyLoop(getOperation()); }

LogicalResult nodal::HardwareLoopOp::verify() {
  if (failed(verifyLoop(getOperation())))
    return failure();
  const llvm::StringRef policy = textAttr(getOperation(), "effect_policy");
  if (!oneOf(policy, {"pure", "ordered", "state_update"}))
    return emitOpError() << "unsupported effect policy '" << policy << "'";
  return success();
}

LogicalResult nodal::ResolvedNetOp::verify() {
  return requireText(getOperation(), "name", "resolved-net name");
}

LogicalResult nodal::NetReadOp::verify() {
  auto net = llvm::cast<nodal::ResolvedType>(getOperation()->getOperand(0).getType());
  if (getOperation()->getResult(0).getType() != net.getElementType())
    return emitOpError("result must match resolved-net element type");
  return success();
}

LogicalResult nodal::NetDriverOp::verify() {
  auto net = llvm::cast<nodal::ResolvedType>(getOperation()->getOperand(0).getType());
  auto driver = llvm::cast<nodal::DriverType>(getOperation()->getResult(0).getType());
  if (driver.getElementType() != net.getElementType())
    return emitOpError("driver must match resolved-net element type");
  return requireText(getOperation(), "driver_id", "driver identity");
}

LogicalResult nodal::NetDriveOp::verify() {
  auto net = llvm::cast<nodal::ResolvedType>(getOperation()->getOperand(0).getType());
  auto driver = llvm::cast<nodal::DriverType>(getOperation()->getOperand(1).getType());
  if (driver.getElementType() != net.getElementType() ||
      getOperation()->getOperand(2).getType() != net.getElementType())
    return emitOpError("driver and value types must match the resolved net");
  if (textAttr(getOperation(), "mode") != net.getDriveMode())
    return emitOpError("drive mode must match the resolved net");
  return success();
}

LogicalResult nodal::TerminalOp::verify() {
  return requireText(getOperation(), "name", "terminal name");
}

LogicalResult nodal::NodeOp::verify() { return requireText(getOperation(), "name", "node name"); }

LogicalResult nodal::BranchOp::verify() {
  auto positive = llvm::cast<nodal::TerminalType>(getOperation()->getOperand(0).getType());
  auto negative = llvm::cast<nodal::TerminalType>(getOperation()->getOperand(1).getType());
  auto branch = llvm::cast<nodal::BranchType>(getOperation()->getResult(0).getType());
  if (positive.getDiscipline() == negative.getDiscipline() &&
      positive.getDiscipline() == branch.getDiscipline())
    return success();

  auto positiveRef = FlatSymbolRefAttr::get(getOperation()->getContext(), positive.getDiscipline());
  auto negativeRef = FlatSymbolRefAttr::get(getOperation()->getContext(), negative.getDiscipline());
  auto branchRef = FlatSymbolRefAttr::get(getOperation()->getContext(), branch.getDiscipline());
  FailureOr<bool> endpoints =
      nodal::areDisciplinesCompatible(getOperation(), positiveRef, negativeRef);
  FailureOr<bool> result = nodal::areDisciplinesCompatible(getOperation(), positiveRef, branchRef);
  if (failed(endpoints) || failed(result) || !*endpoints || !*result)
    return emitOpError(
        "NODAL-BRANCH-DISCIPLINE-001: branch terminals and result must use compatible disciplines");
  return success();
}

LogicalResult nodal::AccessOp::verify() {
  return nodal::verifyPotentialFlowAccessOperation(getOperation());
}

LogicalResult nodal::TerminalAccessOp::verify() {
  return nodal::verifyPotentialFlowAccessOperation(getOperation());
}

LogicalResult nodal::PortFlowAccessOp::verify() {
  return nodal::verifyPotentialFlowAccessOperation(getOperation());
}

LogicalResult nodal::ProbeOp::verify() {
  return nodal::verifyPotentialFlowAccessOperation(getOperation());
}

LogicalResult nodal::AnalogOp::verify() {
  if (failed(requireSingleBlock(getOperation())))
    return emitOpError("NODAL-ANALOG-REGION-001: analog region requires one body block");
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    if (!llvm::isa<nodal::RealLiteralOp, nodal::AnalogIntegerLiteralOp, nodal::ParameterRefOp,
                   nodal::AnalogAddOp, nodal::AnalogSubOp, nodal::AnalogMulOp, nodal::AnalogDivOp,
                   nodal::AnalogNegOp, nodal::AnalogCompareOp, nodal::AnalogLogicOp,
                   nodal::AnalogSelectOp, nodal::AnalogDdtOp, nodal::AnalogIdtOp,
                   nodal::AnalogTransitionOp, nodal::AnalogSlewOp, nodal::AnalogAbsdelayOp,
                   nodal::AnalogAbstimeOp, nodal::AnalogBoundStepOp, nodal::AnalogHeldReadOp,
                   nodal::AccessOp, nodal::TerminalAccessOp, nodal::PortFlowAccessOp,
                   nodal::ContributeOp, nodal::AnalogProcedureOp>(operation))
      return operation.emitOpError(
          "NODAL-ANALOG-REGION-002: operation is not legal in the analog numeric region");
  }
  return success();
}

LogicalResult nodal::AnalogTransitionOp::verify() {
  return verifyTimeWaveformOperation(getOperation());
}
LogicalResult nodal::AnalogSlewOp::verify() { return verifyTimeWaveformOperation(getOperation()); }
LogicalResult nodal::AnalogAbsdelayOp::verify() {
  return verifyTimeWaveformOperation(getOperation());
}
LogicalResult nodal::AnalogAbstimeOp::verify() {
  return verifyTimeWaveformOperation(getOperation());
}
LogicalResult nodal::AnalogBoundStepOp::verify() {
  return verifyTimeWaveformOperation(getOperation());
}

LogicalResult nodal::AnalogProcedureOp::verify() {
  if (failed(verifyAnalogProcedure(getOperation())))
    return failure();
  return verifyAnalogEventProcedure(getOperation());
}

LogicalResult nodal::AnalogScopeOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-033-008",
                                       "procedural lexical scope")))
    return failure();
  if (failed(requireSingleBlock(getOperation())))
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-016",
                             "procedural lexical scope requires one body block");
  if (textAttr(getOperation(), "scope_id").trim().empty())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-016",
                             "procedural lexical scope identity must be non-empty");
  return requireText(getOperation(), "owner", "procedural lexical scope owner");
}

LogicalResult nodal::AnalogIfOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014",
                                       "structured analog conditional")))
    return failure();
  if (failed(requireText(getOperation(), "statement_id", "conditional statement identity")) ||
      failed(requireText(getOperation(), "owner", "conditional owner")) ||
      failed(requireSingleBlock(getOperation())))
    return failure();

  llvm::StringSet<> armIds;
  bool seenConditionalArm = false;
  bool seenElse = false;
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    auto arm = llvm::dyn_cast<nodal::AnalogIfArmOp>(&operation);
    if (!arm)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                               "analog_if body may contain only analog_if_arm operations");
    if (textAttr(&operation, "owner") != textAttr(getOperation(), "owner"))
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-014",
                               "conditional arm owner does not match its parent");
    llvm::StringRef armId = textAttr(&operation, "arm_id");
    if (armId.trim().empty() || !armIds.insert(armId).second)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-001",
                               "conditional arm identity must be non-empty and unique");
    const bool isElse = operation.getAttrOfType<BoolAttr>("is_else").getValue();
    if (isElse) {
      if (seenElse)
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                                 "analog conditional permits only one else arm");
      seenElse = true;
    } else {
      if (seenElse)
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                                 "conditional arm cannot follow the else arm");
      seenConditionalArm = true;
    }
  }
  if (!seenConditionalArm)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "analog conditional requires at least one condition arm");
  return success();
}

LogicalResult nodal::AnalogIfArmOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014",
                                       "analog conditional arm")) ||
      failed(requireSingleBlock(getOperation())) ||
      failed(requireText(getOperation(), "arm_id", "conditional arm identity")) ||
      failed(requireText(getOperation(), "owner", "conditional arm owner")))
    return failure();
  if (getOperation()->getRegion(0).front().empty())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "conditional arm must contain at least one statement");
  if (failed(verifyStringArray(getOperation(), "condition_reads", "NODAL-ANALOG-034-002",
                               "condition read")))
    return failure();

  auto isElse = getOperation()->getAttrOfType<BoolAttr>("is_else");
  auto staticPresent = getOperation()->getAttrOfType<BoolAttr>("static_value_present");
  auto staticValue = getOperation()->getAttrOfType<BoolAttr>("static_value");
  if (!isElse || !staticPresent || !staticValue)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                             "conditional arm requires explicit staging metadata");

  const llvm::StringRef stage = textAttr(getOperation(), "stage");
  const llvm::StringRef value = textAttr(getOperation(), "condition_value");
  const llvm::StringRef kind = textAttr(getOperation(), "condition_kind");
  const llvm::StringRef dimension = textAttr(getOperation(), "condition_dimension");
  auto reads = getOperation()->getAttrOfType<ArrayAttr>("condition_reads");
  if (isElse.getValue()) {
    if (stage != "else" || !value.empty() || !kind.empty() || !dimension.empty() ||
        !reads.empty() || staticPresent.getValue() || staticValue.getValue())
      return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                               "else arm must not carry a condition or static value");
    return success();
  }

  if (!oneOf(stage, {"static", "runtime"}) || value.trim().empty() || kind != "boolean" ||
      dimension != "1")
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-002",
                             "conditional arm requires a dimensionless Boolean condition");
  if (stage == "static" && (!staticPresent.getValue() || !reads.empty()))
    return emitMappedFailure(
        getOperation(), "NODAL-ANALOG-034-003",
        "static conditional arm requires a compile-time value without dynamic reads");
  if (stage == "runtime" && (staticPresent.getValue() || staticValue.getValue()))
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                             "runtime conditional arm cannot carry a compile-time selected value");
  return success();
}

LogicalResult nodal::AnalogCaseOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014",
                                       "structured analog case")) ||
      failed(requireText(getOperation(), "statement_id", "case statement identity")) ||
      failed(requireText(getOperation(), "owner", "case owner")) ||
      failed(requireSingleBlock(getOperation())) ||
      failed(verifyStringArray(getOperation(), "selector_reads", "NODAL-ANALOG-034-005",
                               "case selector read")))
    return failure();

  const llvm::StringRef kind = textAttr(getOperation(), "selector_kind");
  const llvm::StringRef dimension = textAttr(getOperation(), "selector_dimension");
  if (!oneOf(kind, {"integer", "boolean"}) || dimension != "1" ||
      textAttr(getOperation(), "selector_value").trim().empty())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-005",
                             "case selector must be a dimensionless integer or Boolean value");

  auto staticPresent = getOperation()->getAttrOfType<BoolAttr>("static_value_present");
  auto reads = getOperation()->getAttrOfType<ArrayAttr>("selector_reads");
  const llvm::StringRef staticValue = textAttr(getOperation(), "static_value");
  if (!staticPresent)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                             "case selector requires explicit staging metadata");
  if (staticPresent.getValue()) {
    if (staticValue.empty() || !reads.empty())
      return emitMappedFailure(
          getOperation(), "NODAL-ANALOG-034-003",
          "static case selector requires one exact value without dynamic reads");
    if (!isCanonicalStructuredCaseLabel(staticValue, kind))
      return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-007",
                               "static case selector value does not match selector kind");
  } else if (!staticValue.empty()) {
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                             "runtime case selector cannot carry a static value");
  }

  llvm::StringSet<> armIds;
  llvm::StringSet<> labels;
  bool seenOrdinary = false;
  bool seenDefault = false;
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    auto arm = llvm::dyn_cast<nodal::AnalogCaseArmOp>(&operation);
    if (!arm)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                               "analog_case body may contain only analog_case_arm operations");
    if (textAttr(&operation, "owner") != textAttr(getOperation(), "owner"))
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-014",
                               "case arm owner does not match its parent");
    llvm::StringRef armId = textAttr(&operation, "arm_id");
    if (armId.trim().empty() || !armIds.insert(armId).second)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-001",
                               "case arm identity must be non-empty and unique");
    auto isDefault = operation.getAttrOfType<BoolAttr>("is_default");
    auto armLabels = operation.getAttrOfType<ArrayAttr>("labels");
    if (isDefault.getValue()) {
      if (seenDefault || !armLabels.empty())
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                                 "case permits one label-free default arm");
      seenDefault = true;
      continue;
    }
    if (seenDefault)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                               "case arm cannot follow the default arm");
    seenOrdinary = true;
    for (Attribute attribute : armLabels) {
      auto label = llvm::dyn_cast<StringAttr>(attribute);
      if (!label || !isCanonicalStructuredCaseLabel(label.getValue(), kind))
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-007",
                                 "case label kind does not match the selector");
      if (!labels.insert(label.getValue()).second)
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-006", "duplicate case label");
    }
  }
  if (!seenOrdinary)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "analog case requires at least one labeled arm");
  return success();
}

LogicalResult nodal::AnalogCaseArmOp::verify() {
  if (failed(
          requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014", "analog case arm")) ||
      failed(requireSingleBlock(getOperation())) ||
      failed(requireText(getOperation(), "arm_id", "case arm identity")) ||
      failed(requireText(getOperation(), "owner", "case arm owner")) ||
      failed(verifyStringArray(getOperation(), "labels", "NODAL-ANALOG-034-007", "case label")))
    return failure();
  if (getOperation()->getRegion(0).front().empty())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "case arm must contain at least one statement");
  auto isDefault = getOperation()->getAttrOfType<BoolAttr>("is_default");
  auto labels = getOperation()->getAttrOfType<ArrayAttr>("labels");
  if (!isDefault)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "case arm requires explicit default metadata");
  if (isDefault.getValue() != labels.empty())
    return emitMappedFailure(
        getOperation(), "NODAL-ANALOG-034-015",
        "default case arm must be label-free and ordinary arms require labels");
  return success();
}

LogicalResult nodal::AnalogLoopOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014",
                                       "structured analog loop")) ||
      failed(requireSingleBlock(getOperation())) ||
      failed(requireText(getOperation(), "statement_id", "loop statement identity")) ||
      failed(requireText(getOperation(), "owner", "loop owner")) ||
      failed(requireText(getOperation(), "bound_value", "loop bound value")) ||
      failed(verifyStringArray(getOperation(), "bound_reads", "NODAL-ANALOG-034-008",
                               "loop bound read")))
    return failure();
  if (getOperation()->getRegion(0).front().empty())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "analog loop body must contain at least one statement");

  const llvm::StringRef stage = textAttr(getOperation(), "stage");
  const llvm::StringRef kind = textAttr(getOperation(), "bound_kind");
  const llvm::StringRef dimension = textAttr(getOperation(), "bound_dimension");
  auto minimum = getOperation()->getAttrOfType<IntegerAttr>("minimum_iterations");
  auto maximum = getOperation()->getAttrOfType<IntegerAttr>("maximum_iterations");
  auto staticPresent = getOperation()->getAttrOfType<BoolAttr>("static_trip_count_present");
  auto staticCount = getOperation()->getAttrOfType<IntegerAttr>("static_trip_count");
  auto reads = getOperation()->getAttrOfType<ArrayAttr>("bound_reads");
  if (!oneOf(stage, {"static", "runtime"}) || kind != "integer" || dimension != "1" || !minimum ||
      !maximum || !staticPresent || !staticCount)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-008",
                             "loop requires a dimensionless integer finite envelope");
  const int64_t minimumValue = minimum.getInt();
  const int64_t maximumValue = maximum.getInt();
  if (minimumValue < 0 || maximumValue < minimumValue)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-008",
                             "loop envelope must be finite, ordered, and non-negative");

  if (stage == "static") {
    if (!staticPresent.getValue() || staticCount.getInt() != minimumValue ||
        minimumValue != maximumValue || !reads.empty())
      return emitMappedFailure(
          getOperation(), "NODAL-ANALOG-034-009",
          "static loop requires one exact non-negative compile-time trip count");
  } else {
    if (maximumValue == 0 || staticPresent.getValue() || staticCount.getInt() != 0)
      return emitMappedFailure(
          getOperation(), "NODAL-ANALOG-034-008",
          "runtime loop requires a positive finite maximum and no static trip count");
  }
  return success();
}

LogicalResult nodal::AnalogBreakOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-010", "analog break")) ||
      failed(requireText(getOperation(), "statement_id", "break statement identity")) ||
      failed(requireText(getOperation(), "owner", "break owner")))
    return failure();
  auto loop = getOperation()->getParentOfType<nodal::AnalogLoopOp>();
  if (!loop || textAttr(loop.getOperation(), "stage") != "runtime")
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-010",
                             "break is legal only in the nearest runtime-bounded analog loop");
  return success();
}

LogicalResult nodal::AnalogContinueOp::verify() {
  if (failed(
          requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-011", "analog continue")) ||
      failed(requireText(getOperation(), "statement_id", "continue statement identity")) ||
      failed(requireText(getOperation(), "owner", "continue owner")))
    return failure();
  auto loop = getOperation()->getParentOfType<nodal::AnalogLoopOp>();
  if (!loop || textAttr(loop.getOperation(), "stage") != "runtime")
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-011",
                             "continue is legal only in the nearest runtime-bounded analog loop");
  return success();
}

LogicalResult nodal::AnalogVariableOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-033-003",
                                       "procedural variable declaration")))
    return failure();
  if (failed(requireText(getOperation(), "identity", "procedural variable identity")) ||
      failed(requireText(getOperation(), "owner", "procedural variable owner")))
    return failure();
  auto variableType = llvm::dyn_cast<nodal::VariableType>(getOperation()->getResult(0).getType());
  if (!variableType)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-019",
                             "procedural variable requires !nodal.variable result type");
  return verifyVariableInitializerShape(getOperation(), variableType);
}

LogicalResult nodal::AnalogVariableReadOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-033-008",
                                       "procedural variable read")))
    return failure();
  if (failed(requireText(getOperation(), "read_id", "procedural read identity")) ||
      failed(requireText(getOperation(), "owner", "procedural read owner")))
    return failure();
  auto variableType = llvm::dyn_cast<nodal::VariableType>(getOperation()->getOperand(0).getType());
  auto result = getProceduralValueInfo(getOperation()->getResult(0).getType());
  if (!variableType || failed(result) || result->kind != variableType.getKind() ||
      result->dimension != variableType.getDimension())
    return emitMappedFailure(
        getOperation(), "NODAL-ANALOG-033-012",
        "procedural read result kind or dimension does not match its variable");
  return success();
}

LogicalResult nodal::AnalogAssignOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-033-008",
                                       "procedural assignment")))
    return failure();
  if (failed(requireText(getOperation(), "statement_id", "procedural statement identity")) ||
      failed(requireText(getOperation(), "owner", "procedural assignment owner")))
    return failure();
  auto variableType = llvm::dyn_cast<nodal::VariableType>(getOperation()->getOperand(0).getType());
  if (!variableType)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-019",
                             "procedural assignment target requires !nodal.variable");
  llvm::StringRef valueKind = textAttr(getOperation(), "value_kind");
  llvm::StringRef valueDimension = textAttr(getOperation(), "value_dimension");
  if (!oneOf(valueKind, {"integer", "real", "boolean"}) ||
      !proceduralKindsCompatible(valueKind, variableType.getKind()))
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-012",
                             "assigned value kind is incompatible with the target variable");
  if (!nodal::isCanonicalDimensionSignature(valueDimension) ||
      valueDimension != variableType.getDimension())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-033-013",
                             "assigned value dimension does not match the target variable");
  if (failed(verifyAnalysisApplicability(getOperation())))
    return failure();
  return verifyGuardShape(getOperation());
}

LogicalResult nodal::RealLiteralOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}

LogicalResult nodal::AnalogIntegerLiteralOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}

LogicalResult nodal::ParameterRefOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}

LogicalResult nodal::AnalogAddOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogSubOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogMulOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogDivOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogNegOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogCompareOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogLogicOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogSelectOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogDdtOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogIdtOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::ContributeOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}

LogicalResult nodal::BridgeOp::verify() {
  if (failed(requireText(getOperation(), "kind", "bridge kind")) ||
      failed(requireText(getOperation(), "source_domain", "source domain")) ||
      failed(requireText(getOperation(), "destination_domain", "destination domain")))
    return failure();
  return success();
}

LogicalResult nodal::CrossingOp::verify() {
  auto source = getOperation()->getAttrOfType<FlatSymbolRefAttr>("source_domain");
  auto destination = getOperation()->getAttrOfType<FlatSymbolRefAttr>("destination_domain");
  if (!source || !destination || source == destination)
    return emitOpError("requires distinct crossing domains");
  const llvm::StringRef kind = textAttr(getOperation(), "kind");
  if (!oneOf(kind, {"sync", "gray", "pulse", "handshake", "fifo", "reset", "bridge", "waived"}))
    return emitOpError() << "unsupported crossing kind '" << kind << "'";
  return success();
}

LogicalResult nodal::StateOwnerOp::verify() {
  if (!getOperation()->getAttrOfType<SymbolRefAttr>("state") ||
      !getOperation()->getAttrOfType<FlatSymbolRefAttr>("domain"))
    return emitOpError("requires state and domain references");
  return success();
}

LogicalResult nodal::TimingProvenanceOp::verify() {
  if (!getOperation()->getAttrOfType<SymbolRefAttr>("owner"))
    return emitOpError("requires an owner reference");
  return requireText(getOperation(), "relationship", "timing relationship");
}

LogicalResult nodal::EnumOp::verify() {
  auto underlying = getOperation()->getAttrOfType<TypeAttr>("underlying_type");
  if (!underlying || !finiteWidth(underlying.getValue()))
    return emitOpError("underlying_type must be finite-width");
  if (!oneOf(textAttr(getOperation(), "encoding"), {"sequential", "one_hot", "gray", "custom"}))
    return emitOpError("unsupported enum encoding");
  if (failed(requireSingleBlock(getOperation())))
    return failure();
  llvm::StringSet<> values;
  unsigned cases = 0;
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    auto enumCase = llvm::dyn_cast<nodal::EnumCaseOp>(&operation);
    if (!enumCase)
      continue;
    ++cases;
    auto value = operation.getAttrOfType<IntegerAttr>("value");
    if (!value || !integerFits(value, underlying.getValue()))
      return enumCase.emitOpError("case value does not fit enum width");
    llvm::SmallString<32> key;
    value.getValue().toString(key, 10, true);
    if (!values.insert(key).second)
      return enumCase.emitOpError("duplicates an existing enum value");
  }
  if (cases == 0)
    return emitOpError("requires at least one enum case");
  return success();
}

LogicalResult nodal::EnumCaseOp::verify() {
  if (failed(
          requireText(getOperation(), mlir::SymbolTable::getSymbolAttrName(), "enum case symbol")))
    return failure();
  if (!getOperation()->getAttrOfType<IntegerAttr>("value"))
    return emitOpError("requires an integer value");
  return success();
}

LogicalResult nodal::FsmOp::verify() {
  auto stateType = getOperation()->getAttrOfType<TypeAttr>("state_type");
  if (!stateType || !llvm::isa<nodal::EnumType>(stateType.getValue()))
    return emitOpError("state_type must be !nodal.enum");
  if (!getOperation()->getAttrOfType<FlatSymbolRefAttr>("domain"))
    return emitOpError("requires one owning domain");
  if (!oneOf(textAttr(getOperation(), "encoding"),
             {"compact", "one_hot", "gray", "custom", "auto"}))
    return emitOpError("unsupported FSM encoding");
  if (!oneOf(textAttr(getOperation(), "illegal_policy"), {"error", "recover", "hold", "trap"}))
    return emitOpError("unsupported illegal-state policy");
  if (failed(requireSingleBlock(getOperation())))
    return failure();

  llvm::StringSet<> states;
  unsigned initialStates = 0;
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    auto state = llvm::dyn_cast<nodal::FsmStateOp>(&operation);
    if (!state)
      continue;
    if (auto name = operation.getAttrOfType<StringAttr>(mlir::SymbolTable::getSymbolAttrName()))
      states.insert(name.getValue());
    if (auto initial = operation.getAttrOfType<BoolAttr>("initial")) {
      if (initial.getValue())
        ++initialStates;
    }
  }
  if (states.empty())
    return emitOpError("requires at least one FSM state");
  if (initialStates != 1)
    return emitOpError("requires exactly one initial FSM state");

  for (Operation &operation : getOperation()->getRegion(0).front()) {
    auto state = llvm::dyn_cast<nodal::FsmStateOp>(&operation);
    if (!state)
      continue;
    for (Operation &nested : operation.getRegion(0).front()) {
      auto transition = llvm::dyn_cast<nodal::FsmTransitionOp>(&nested);
      if (!transition)
        continue;
      auto destination = nested.getAttrOfType<FlatSymbolRefAttr>("destination");
      if (!destination || states.find(destination.getValue()) == states.end())
        return transition.emitOpError("references an unknown destination state");
    }
  }
  return success();
}

LogicalResult nodal::FsmStateOp::verify() { return requireSingleBlock(getOperation()); }

LogicalResult nodal::FsmTransitionOp::verify() {
  auto priority = getOperation()->getAttrOfType<IntegerAttr>("priority");
  if (!priority || priority.getValue().isNegative())
    return emitOpError("priority must be non-negative");
  return requireText(getOperation(), "condition", "condition");
}

LogicalResult nodal::FsmActionOp::verify() {
  if (!oneOf(textAttr(getOperation(), "phase"), {"entry", "active", "exit", "transition"}))
    return emitOpError("unsupported FSM action phase");
  return requireText(getOperation(), "effect", "action effect");
}

LogicalResult nodal::FsmCompletionOp::verify() {
  auto source = getOperation()->getAttrOfType<FlatSymbolRefAttr>("source");
  auto destination = getOperation()->getAttrOfType<FlatSymbolRefAttr>("destination");
  if (!source || !destination || source == destination)
    return emitOpError("requires distinct completion endpoints");
  return requireText(getOperation(), "kind", "completion kind");
}

LogicalResult nodal::AnalogCrossOp::verify() { return verifyAnalogEventOperation(*this); }
LogicalResult nodal::AnalogAboveOp::verify() { return verifyAnalogEventOperation(*this); }
LogicalResult nodal::AnalogTimerOp::verify() { return verifyAnalogEventOperation(*this); }
LogicalResult nodal::AnalogInitialStepOp::verify() { return verifyAnalogEventOperation(*this); }
LogicalResult nodal::AnalogFinalStepOp::verify() { return verifyAnalogEventOperation(*this); }
LogicalResult nodal::AnalogEventOrOp::verify() { return verifyAnalogEventOperation(*this); }
LogicalResult nodal::AnalogOnOp::verify() { return verifyAnalogEventOperation(*this); }

LogicalResult nodal::AnalogHeldReadOp::verify() { return verifyAnalogHeldRead(getOperation()); }
