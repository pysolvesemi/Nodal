#include "nodal/Dialect/Nodal/NodalOps.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinTypes.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Diagnostics/DiagnosticSupport.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NatureDiscipline.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"

#include "llvm/ADT/APInt.h"
#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/SmallString.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/ADT/StringSet.h"

#include <initializer_list>
#include <optional>
#include <string>

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

LogicalResult verifyAnalogProcedure(Operation *operation) {
  if (failed(requireSingleBlock(operation)))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-008",
                                    "analog procedural region requires exactly one body block");
  llvm::StringRef owner = textAttr(operation, "owner");
  if (owner.trim().empty())
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-009",
                                    "analog procedural owner must be non-empty");
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
  return requireSingleBlock(getOperation());
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
                   nodal::AnalogSelectOp, nodal::AnalogDdtOp, nodal::AccessOp,
                   nodal::TerminalAccessOp, nodal::PortFlowAccessOp, nodal::ContributeOp,
                   nodal::AnalogProcedureOp>(operation))
      return operation.emitOpError(
          "NODAL-ANALOG-REGION-002: operation is not legal in the analog numeric region");
  }
  return success();
}

LogicalResult nodal::AnalogProcedureOp::verify() { return verifyAnalogProcedure(getOperation()); }

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
