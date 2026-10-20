#include "circt/Dialect/Comb/CombDialect.h"
#include "circt/Dialect/Comb/CombOps.h"
#include "circt/Dialect/HW/HWDialect.h"
#include "circt/Dialect/HW/HWOps.h"
#include "circt/Dialect/SV/SVDialect.h"
#include "circt/Dialect/SV/SVOps.h"
#include "circt/Dialect/Seq/SeqDialect.h"
#include "circt/Dialect/Seq/SeqOps.h"
#include "circt/Dialect/Verif/VerifDialect.h"
#include "circt/Dialect/Verif/VerifOps.h"
#include "circt/Support/BackedgeBuilder.h"
#include "mlir/IR/Builders.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassRegistry.h"
#include "nodal/Dialect/Nodal/CrossingModel.h"
#include "nodal/Dialect/Nodal/DigitalMaterializationModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/PhysicalControlModel.h"
#include "nodal/Dialect/Nodal/ResetModel.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/ADT/StringSwitch.h"

#include <string>

using namespace mlir;
namespace nodal {
namespace {
constexpr unsigned maximumWidth = 4096;
constexpr unsigned maximumAuthoredOperations = 65536;
constexpr unsigned maximumGeneratedOperations = 262144;
constexpr StringLiteral qualityKey = "nodal.digital.quality";
constexpr StringLiteral reportKey = "nodal.digital.materialization";

LogicalResult reject(Operation *op, const Twine &message) {
  return op->emitOpError("NODAL-CIRCT-056-001: ") << message;
}
StringRef text(Operation *op, StringRef key) {
  auto value = op->getAttrOfType<StringAttr>(key);
  return value ? value.getValue() : StringRef();
}
StringRef text(DictionaryAttr attr, StringRef key) {
  auto value = attr ? attr.getAs<StringAttr>(key) : StringAttr();
  return value ? value.getValue() : StringRef();
}
StringRef sourcePath(Operation *op) {
  return text(op->getAttrOfType<DictionaryAttr>("metadata"), "semantic_path");
}
bool named(Operation *op, StringRef name) { return op->getName().getStringRef() == name; }
unsigned width(Type type) {
  if (auto t = dyn_cast<BitsType>(type))
    return t.getWidth();
  if (auto t = dyn_cast<UIntType>(type))
    return t.getWidth();
  if (auto t = dyn_cast<SIntType>(type))
    return t.getWidth();
  if (auto t = dyn_cast<EnumType>(type))
    return t.getWidth();
  if (type.isInteger(1))
    return 1;
  return 0;
}
Type targetType(Type type) {
  if (auto capability = dyn_cast<ControlCapabilityType>(type)) {
    if (capability.getKind() == "clock")
      return circt::seq::ClockType::get(type.getContext());
    if (capability.getKind() == "reset")
      return IntegerType::get(type.getContext(), 1);
  }
  unsigned bits = width(type);
  return bits && bits <= maximumWidth ? IntegerType::get(type.getContext(), bits) : Type();
}
/// Fixed physical control arrays use one bit per row-major scalar element.
/// ShapeModel remains the owner of extent proof; defaults never size an ABI.
Type targetControlShapeType(Type type, Operation *module) {
  if (!isa<nodal::ShapedType>(type) || getDigitalControlBoundaryKind(type).empty())
    return {};
  unsigned bits = 1;
  while (auto shape = dyn_cast<nodal::ShapedType>(type)) {
    SmallVector<StringRef> dimensions;
    shape.getDimensions().split(dimensions, ',');
    for (StringRef dimension : dimensions) {
      auto extent = resolveStaticShapeDimension(module, dimension);
      if (failed(extent) || extent->bounds.lower != extent->bounds.upper ||
          extent->bounds.lower <= 0 || extent->bounds.lower > maximumWidth / bits)
        return {};
      bits *= extent->bounds.lower;
    }
    type = shape.getElementType();
  }
  return IntegerType::get(type.getContext(), bits);
}
bool scalarOpcode(StringRef op) {
  return llvm::StringSwitch<bool>(op)
      .Cases({"digital_alias", "digital_select", "bool_and", "bool_or", "bool_not"}, true)
      .Cases({"digital_and", "digital_or", "digital_xor", "digital_not"}, true)
      .Cases({"digital_add", "digital_sub", "digital_mul", "digital_neg"}, true)
      .Cases({"digital_eq", "digital_ne", "digital_lt", "digital_le", "digital_gt", "digital_ge"},
             true)
      .Cases({"digital_min", "digital_max", "digital_concat"}, true)
      .Cases({"digital_extend", "digital_truncate", "digital_wrap", "digital_resize_checked"}, true)
      .Cases({"digital_to_signed", "digital_to_unsigned", "digital_reinterpret_signed",
              "digital_reinterpret_unsigned"},
             true)
      .Default(false);
}
void correlate(Operation *target, Operation *source) {
  if (!sourcePath(source).empty())
    target->setAttr("nodal.source_path", StringAttr::get(source->getContext(), sourcePath(source)));
}

/// Only the target representation lives here. Priority, graph cuts, physical
/// driver ownership, enum ABI and materialization decisions stay with their
/// existing independently verified Nodal owners.
class ModuleLowering {
public:
  ModuleLowering(nodal::ModuleOp source, OpBuilder &builder, uint64_t &cost)
      : source(source), builder(builder), cost(cost), domains(source) {}

  LogicalResult preflight() {
    if (failed(planControlProjections()))
      return failure();
    SmallVector<std::pair<Value, Operation *>> seeds;
    for (Operation &op : source.getBody().front()) {
      StringRef name = op.getName().getStringRef();
      if (op.getNumRegions() || op.getNumSuccessors())
        return reject(&op, "target profile requires flat normalized scalar operations");
      bool structural = controlProjections.contains(&op) || controlSelectors.contains(&op);
      if (!structural) {
        bool aggregate = llvm::any_of(op.getOperandTypes(), aggregatePayload) ||
                         llvm::any_of(op.getResultTypes(), aggregatePayload);
        if (aggregate && !isa<PortValueOp, CrossingOp, DigitalAssignOp>(op) &&
            !(isa<DigitalExprOp>(op) && text(&op, "operator_name") == "digital_alias"))
          return reject(&op, "fixed aggregate target profile supports only whole-value Gray "
                             "crossings and direct boundary/alias connections; projections "
                             "require an explicit packed-offset lowering");
        for (Type type : op.getOperandTypes())
          if (!targetValueType(type))
            return reject(
                &op, "operand requires fixed scalar/packed width 1..4096 or a direct clock/reset");
        for (Type type : op.getResultTypes())
          if (!targetValueType(type) &&
              !(isa<PortValueOp>(op) && targetControlShapeType(type, source)))
            return reject(
                &op, "result requires fixed scalar/packed width 1..4096 or a fixed control input");
      }
      uint64_t expansion = 1;
      if (structural) {
        // Shape/index expressions are compile-time proof, not hardware values.
        expansion = controlProjections.contains(&op) ? 2 : 0;
      } else if (isa<PortOp>(op)) {
        auto type = op.getAttrOfType<TypeAttr>("type");
        Type lowered = type ? targetValueType(type.getValue()) : Type();
        if (type && !lowered)
          lowered = targetControlShapeType(type.getValue(), source);
        if (!type || !lowered ||
            (text(&op, "direction") != "input" && text(&op, "direction") != "output"))
          return reject(&op, "only fixed scalar/packed input/output and fixed Clock/Reset input "
                             "ports are supported");
        if (text(&op, "direction") == "output" &&
            !getDigitalControlBoundaryKind(type.getValue()).empty())
          return reject(&op, "clock/reset output capabilities require a later boundary profile");
        ports.push_back(&op);
        portTypes.try_emplace(&op, lowered);
      } else if (isa<PortValueOp>(op)) {
        auto ref = op.getAttrOfType<FlatSymbolRefAttr>("port");
        auto *port = SymbolTable::lookupSymbolIn(source, ref);
        portValues[port].push_back(op.getResult(0));
        if (text(port, "direction") == "output")
          seeds.emplace_back(op.getResult(0), &op);
      } else if (isa<DomainOp>(op)) {
        // Unused descriptors remain descriptors. Executable bindings and every
        // stateful consumer are independently verified before this transaction.
      } else if (isa<DomainRequirementOp, DomainBindOp, LocalDomainBindingOp>(op)) {
        // Verified provenance only; actual values already carry the connection.
      } else if (isa<ResetBoundaryOp, ResetSyncOp, ResetCombineOp>(op)) {
        if (failed(verifyResetProducer(&op)))
          return failure();
        resets.push_back(&op);
        expansion = isa<ResetSyncOp>(op) ? 3 * op.getAttrOfType<IntegerAttr>("stages").getInt() + 5
                                         : 2 * op.getNumOperands() + 2;
      } else if (isa<CrossingOp>(op)) {
        auto crossing = inspectExecutableCrossing(&op, domains);
        if (failed(crossing))
          return failure();
        // The canonical model verifies type, endpoints, source sampling and
        // independent identity. Only the finite target expansion belongs here.
        crossings.push_back(*crossing);
        // Bounds cover state, reset logic, source-clock runtime monitors and
        // retained obligations. Payload bits were independently bounded by the
        // native model; this profile additionally requires fixed scalar types.
        expansion = crossing->kind == CrossingKind::Pulse  ? 64
                    : crossing->kind == CrossingKind::Gray ? 2 * crossing->stages + 32
                                                           : 3 * crossing->stages + 8;
      } else if (isa<DigitalStorageOp>(op)) {
        if (text(&op, "kind") == "register")
          registers.push_back(&op);
        else if (text(&op, "kind") == "wire")
          seeds.emplace_back(op.getResult(0), &op);
        else
          return reject(&op, "unknown scalar storage kind");
        expansion = 4;
      } else if (isa<SequentialUpdateOp>(op)) {
        if (!updates.try_emplace(op.getOperand(0), &op).second)
          return reject(&op, "register requires exactly one normalized clocked update");
        expansion = 5;
      } else if (isa<DigitalAssignOp>(op)) {
        if (text(&op, "kind") != "continuous")
          return reject(&op, "unnormalized or unbound register intent is not executable");
      } else if (isa<ConstantOp, ConstLiteralOp>(op)) {
        if (!op.getAttrOfType<IntegerAttr>("value"))
          return reject(&op, "scalar literal requires an integer bit pattern");
      } else if (isa<SequentialSelectOp>(op) || name == "nodal.reset_asserted") {
        expansion = 2;
      } else if (name == "nodal.sequential_assert") {
        expansion = 2;
      } else if (isa<DigitalExprOp>(op)) {
        if (!scalarOpcode(text(&op, "operator_name")))
          return reject(&op,
                        "unsupported fixed-scalar operation '" + text(&op, "operator_name") + "'");
        if (text(&op, "operator_name") == "digital_alias" &&
            !source->getParentOfType<mlir::ModuleOp>()->hasAttr(qualityKey))
          return reject(&op, "authored alias/materialization intent requires explicit "
                             "nodal.digital.quality for the canonical analyzer");
        expansion = 12;
      } else if (isa<EnumExprOp>(op)) {
        StringRef code = text(&op, "operator_name");
        if (code != "enum_literal" && code != "enum_eq" && code != "enum_as_bits" &&
            code != "enum_is_one_of" && code != "enum_is_valid")
          return reject(&op, "unsupported canonical enum operation '" + code + "'");
        auto reference = op.getAttrOfType<FlatSymbolRefAttr>("enum_ref");
        auto inserted = enumCodes.try_emplace(reference);
        if (inserted.second) {
          auto *definition =
              SymbolTable::lookupSymbolIn(source->getParentOfType<mlir::ModuleOp>(), reference);
          for (Operation &entry : definition->getRegion(0).front()) {
            auto value = entry.getAttrOfType<IntegerAttr>("value");
            inserted.first->second.byId.try_emplace(
                text(entry.getAttrOfType<DictionaryAttr>("metadata"), "case_id"), value);
            inserted.first->second.inDeclarationOrder.push_back(value);
          }
        }
        auto cases = op.getAttrOfType<ArrayAttr>("case_ids");
        uint64_t tests = code == "enum_is_valid" ? inserted.first->second.inDeclarationOrder.size()
                                                 : cases.size();
        expansion = code == "enum_is_one_of" || code == "enum_is_valid" ? 3 * tests + 2 : 3;
      } else
        return reject(&op, "operation is outside the finite scalar CIRCT profile");
      if (expansion > maximumGeneratedOperations - cost)
        return reject(&op, "target expansion exceeds 262144 operations before allocation");
      cost += expansion;
    }
    for (Operation *reg : registers)
      if (!updates.count(reg->getResult(0)))
        return reject(reg, "declaration-only register lacks an executable physical binding/update");
    for (Operation *port : ports)
      if (text(port, "direction") == "output" && portValues[port].empty())
        return reject(port, "output has no actual value/driver; no output is invented");
    // Values are read at the final module boundary, never at their own producer
    // operation (which would violate the shared lexical SSA-use contract).
    for (auto &seed : seeds)
      seed.second = &source.getBody().front().back();
    auto graph = analyzeDigitalAssignmentDependencies(source, seeds);
    if (failed(graph))
      return failure();
    // The finite profile has only direct whole-scalar connections. Read the
    // canonical graph's unique write edge, rather than collecting another set
    // of mutable driver facts or reimplementing physical overlap/coverage.
    for (unsigned i = 0; i < seeds.size(); ++i) {
      const auto &root = graph->nodes[graph->roots[i]];
      if (root.dependencies.size() != 1)
        return reject(seeds[i].second,
                      "scalar connection requires one verified whole-value writer");
      const auto &write = graph->nodes[root.dependencies.front()];
      if (write.value || !isa<DigitalAssignOp>(write.use) || write.dependencies.size() != 1)
        return reject(seeds[i].second,
                      "scalar connection requires a direct canonical assignment edge");
      Value value = graph->nodes[write.dependencies.front()].value;
      if (!value)
        return reject(seeds[i].second, "canonical assignment lacks its actual RHS value");
      aliases.try_emplace(seeds[i].first, value);
    }
    llvm::StringSet<> targetNames;
    for (Operation *port : ports)
      targetNames.insert(text(port, "sym_name"));
    for (Operation &op : source.getBody().front())
      if (isa<DigitalStorageOp>(op) && !targetNames.insert(text(&op, "name")).second)
        return reject(&op, "target storage name collides with an existing port/storage name");
    if (source->getParentOfType<mlir::ModuleOp>()->hasAttr(qualityKey)) {
      auto report = analyzeDigitalMaterialization(source);
      if (failed(report))
        return failure();
      for (Attribute attr : report->getAs<ArrayAttr>("decisions")) {
        auto decision = cast<DictionaryAttr>(attr);
        if (text(decision, "decision") != "materialize-required")
          continue;
        auto intent = decision.getAs<DictionaryAttr>("intent");
        if (text(intent, "kind") == "named" && !targetNames.insert(text(intent, "value")).second)
          return reject(
              source,
              "authored exact target name collides with another port/storage/materialization name");
        materializations.try_emplace(text(decision, "path"), decision);
      }
    }
    // Reserve generated names after every authored exact name is known.
    // Symbols keep even identical-source chains distinct through target CSE.
    for (const auto &crossing : crossings) {
      SmallVector<StringAttr> names;
      SmallVector<std::string> roles;
      if (crossing.kind == CrossingKind::Pulse)
        roles = {"source_toggle",       "previous_pulse", "forward_0", "forward_1",
                 "destination_history", "return_0",       "return_1"};
      else {
        for (unsigned stage = 0; stage < crossing.stages; ++stage)
          roles.push_back("stage_" + std::to_string(stage));
        if (crossing.kind == CrossingKind::Gray) {
          roles.push_back("previous_payload");
          roles.push_back("seen_sample");
        }
      }
      StringRef kind = crossing.kind == CrossingKind::Level  ? "sync"
                       : crossing.kind == CrossingKind::Gray ? "gray"
                                                             : "pulse";
      for (const std::string &role : roles) {
        std::string base =
            "__nodal_" + kind.str() + "_" + std::to_string(crossingNames.size()) + "_" + role;
        std::string name = base;
        unsigned collision = 0;
        while (!targetNames.insert(name).second)
          name = base + "_" + std::to_string(++collision);
        names.push_back(builder.getStringAttr(name));
      }
      crossingNames.try_emplace(crossing.operation, std::move(names));
    }
    for (Operation *reset : resets) {
      if (!isa<ResetSyncOp>(reset))
        continue;
      SmallVector<StringAttr> names;
      unsigned stages = reset->getAttrOfType<IntegerAttr>("stages").getInt();
      for (unsigned i = 0; i < stages; ++i) {
        std::string base =
            "__nodal_reset_" + std::to_string(resetNames.size()) + "_" + std::to_string(i);
        std::string name = base;
        unsigned collision = 0;
        while (!targetNames.insert(name).second)
          name = base + "_" + std::to_string(++collision);
        names.push_back(builder.getStringAttr(name));
      }
      resetNames.try_emplace(reset, std::move(names));
    }
    return success();
  }

  LogicalResult lower() {
    SmallVector<circt::hw::PortInfo> targetPorts;
    unsigned inputIndex = 0, outputIndex = 0;
    for (Operation *port : ports) {
      circt::hw::PortInfo info;
      info.name = port->getAttrOfType<StringAttr>("sym_name");
      info.type = portTypes.lookup(port);
      bool input = text(port, "direction") == "input";
      info.dir = input ? circt::hw::ModulePort::Input : circt::hw::ModulePort::Output;
      info.argNum = input ? inputIndex++ : outputIndex++;
      info.loc = port->getLoc();
      NamedAttrList attributes;
      if (!sourcePath(port).empty())
        attributes.set("nodal.source_path", builder.getStringAttr(sourcePath(port)));
      Type originalType = port->getAttrOfType<TypeAttr>("type").getValue();
      if (aggregatePayload(originalType))
        attributes.set("nodal.packed_abi", packedLayout(originalType));
      info.attrs = builder.getDictionaryAttr(attributes);
      targetPorts.push_back(info);
    }
    builder.setInsertionPoint(source);
    auto target = circt::hw::HWModuleOp::create(builder, source.getLoc(),
                                                source->getAttrOfType<StringAttr>("sym_name"),
                                                circt::hw::ModulePortInfo(targetPorts));
    correlate(target, source);
    Block *body = target.getBodyBlock();
    builder.setInsertionPoint(body->getTerminator());
    for (Operation *port : ports) {
      Type type = port->getAttrOfType<TypeAttr>("type").getValue();
      if (!aggregatePayload(type))
        continue;
      std::string contract;
      llvm::raw_string_ostream stream(contract);
      stream << "// NODAL packed ABI: " << text(port, "sym_name")
             << "; layout=recursive-row-major-fields-lsb-first; " << packedLayout(type);
      auto comment =
          circt::sv::VerbatimOp::create(builder, port->getLoc(), builder.getStringAttr(contract),
                                        ValueRange{}, builder.getArrayAttr({}));
      correlate(comment, port);
    }
    circt::BackedgeBuilder backedges(builder, source.getLoc());
    inputIndex = 0;
    for (Operation *port : ports) {
      if (text(port, "direction") != "input")
        continue;
      for (Value value : portValues[port])
        values[value] = body->getArgument(inputIndex);
      ++inputIndex;
    }
    // A projection is a statically proved reference into its actual input pin,
    // not a runtime array operation. Install scalar mappings before any reset
    // producers, synchronizer stages, assertions or registers consume them.
    for (const auto &[original, control] : physicalControls) {
      Value input = values.lookup(control.root);
      if (!input)
        return reject(original.getDefiningOp(), "physical control root did not map");
      Value bit = circt::comb::ExtractOp::create(builder, original.getLoc(), input,
                                                 *control.literalOffset, 1);
      correlate(bit.getDefiningOp(), original.getDefiningOp());
      if (cast<ControlCapabilityType>(original.getType()).getKind() == "clock") {
        bit = circt::seq::ToClockOp::create(builder, original.getLoc(), bit);
        correlate(bit.getDefiningOp(), original.getDefiningOp());
      }
      values[original] = bit;
    }
    // Reset producers precede consumers in the authored SSA graph. Lower them
    // before state allocation so no derived release is replaced by the raw pin.
    for (Operation *op : resets) {
      if (isa<ResetBoundaryOp>(op)) {
        Value raw = values.lookup(op->getOperand(0));
        if (!raw)
          return reject(op, "physical reset boundary did not map");
        values[op->getResult(0)] = raw;
        if (text(op, "release") == "external") {
          auto binding = domains.resolveClockBinding(op->getAttr("release_domain"));
          auto domain = domains.resolveDomain(op->getAttr("release_domain"));
          Value clock = circt::seq::FromClockOp::create(builder, op->getLoc(),
                                                        values.lookup((*binding)->getOperand(0)));
          std::string comment =
              "// NODAL external reset release assumption: " + text(*domain, "sym_name").str() +
              " " + text(*domain, "edge").str() + " physical clock {{0}}; " + sourcePath(op).str();
          circt::sv::VerbatimOp::create(builder, op->getLoc(), builder.getStringAttr(comment),
                                        ValueRange{clock}, builder.getArrayAttr({}));
        }
      } else if (isa<ResetCombineOp>(op)) {
        SmallVector<Value> asserted;
        for (Value input : op->getOperands()) {
          Value raw = values.lookup(input);
          if (!raw)
            return reject(op, "reset composition input did not map");
          asserted.push_back(resetActive(raw, input.getDefiningOp()));
        }
        Value joined = asserted.size() == 1 ? asserted.front()
                                            : make(op, "comb.or", builder.getI1Type(), asserted);
        values[op->getResult(0)] = resetActive(joined, op);
      } else {
        Value raw = values.lookup(op->getOperand(0));
        Value clock = values.lookup(op->getOperand(1));
        if (!raw || !clock)
          return reject(op, "reset synchronizer input/clock did not map");
        auto domain = domains.resolveDomain(op->getAttr("destination_domain"));
        if (text(*domain, "edge") == "falling")
          clock = circt::seq::ClockInverterOp::create(builder, op->getLoc(), clock);
        Value asserted = resetActive(raw, op->getOperand(0).getDefiningOp());
        Value one = constant(op, 1, 1), previous = constant(op, 1, 0);
        for (StringAttr name : resetNames.find(op)->second) {
          auto state =
              circt::seq::FirRegOp::create(builder, op->getLoc(), previous, clock, name, asserted,
                                           one, circt::hw::InnerSymAttr::get(name), true);
          correlate(state, op);
          previous = state.getResult();
        }
        values[op->getResult(0)] = resetActive(previous, op);
      }
    }
    SmallVector<std::pair<Value, circt::Backedge>> connectionEdges;
    // Install graph cuts first. No combinational value can accidentally become
    // state: all these references are resolved before the candidate verifies.
    for (Operation &op : source.getBody().front()) {
      if (!op.getNumResults())
        continue;
      Value original = op.getResult(0);
      if (!aliases.count(original))
        continue;
      auto edge = backedges.get(targetValueType(original.getType()), op.getLoc());
      values[original] = edge;
      connectionEdges.emplace_back(original, edge);
    }
    SmallVector<std::pair<Operation *, circt::Backedge>> stateEdges, resetValueEdges;
    for (Operation *reg : registers) {
      Operation *update = updates.lookup(reg->getResult(0));
      Value clock = values.lookup(update->getOperand(2));
      if (!clock) {
        backedges.abandon();
        return reject(update, "physical clock was not mapped to a module input");
      }
      if (text(update, "edge") == "falling")
        clock = circt::seq::ClockInverterOp::create(builder, update->getLoc(), clock);
      auto edge = backedges.get(targetValueType(reg->getResult(0).getType()), reg->getLoc());
      Operation *state;
      StringRef policy = text(update, "reset_policy");
      if (policy == "async" || policy == "async_assert_sync_release") {
        Value rawReset = values.lookup(update->getOperand(3));
        if (!rawReset)
          return reject(update, "effective reset did not map before consumer");
        auto initial = backedges.get(targetValueType(reg->getResult(0).getType()), reg->getLoc());
        state = circt::seq::FirRegOp::create(
            builder, reg->getLoc(), Value(edge), clock, reg->getAttrOfType<StringAttr>("name"),
            resetActive(rawReset, update), Value(initial), circt::hw::InnerSymAttr(), true);
        resetValueEdges.emplace_back(update, initial);
      } else {
        state = circt::seq::CompRegOp::create(builder, reg->getLoc(), Value(edge), clock);
        state->setAttr("name", reg->getAttr("name"));
      }
      correlate(state, reg);
      values[reg->getResult(0)] = state->getResult(0);
      stateEdges.emplace_back(update, edge);
    }
    SmallVector<std::pair<Operation *, circt::Backedge>> crossingEdges;
    for (const auto &crossing : crossings) {
      Operation *op = crossing.operation;
      auto destination = crossingControls(crossing.destinationDomain, crossing.destinationBinding);
      if (failed(destination)) {
        backedges.abandon();
        return reject(op, "crossing destination physical clock/reset did not map");
      }
      auto input = backedges.get(targetValueType(op->getOperand(0).getType()), op->getLoc());
      crossingEdges.emplace_back(op, input);
      Value previous = input;
      if (crossing.kind == CrossingKind::Level || crossing.kind == CrossingKind::Gray) {
        for (unsigned stage = 0; stage < crossing.stages; ++stage)
          previous = crossingState(crossing, stage, previous, *destination, "forward",
                                   crossing.kind != CrossingKind::Gray);
        values[op->getResult(0)] = previous;
        if (crossing.kind == CrossingKind::Level)
          continue;
      }
      auto sourceControl = crossingControls(crossing.sourceDomain, crossing.sourceBinding);
      if (failed(sourceControl)) {
        backedges.abandon();
        return reject(op, "crossing source physical clock/reset did not map");
      }
      if (crossing.kind == CrossingKind::Gray) {
        // The payload history has no reset value. In particular, an enum with
        // no zero encoding must not acquire a fabricated source reset state.
        // Only the seen marker resets, and the first real sample seeds history.
        Value history = crossingState(crossing, crossing.stages, input, *sourceControl,
                                      "previous_payload", false);
        Value seen = crossingState(crossing, crossing.stages + 1, constant(op, 1, 1),
                                   *sourceControl, "seen_sample");
        unsigned bits = cast<IntegerType>(history.getType()).getWidth();
        Value delta = make(op, "comb.xor", history.getType(), {Value(input), history});
        Value minusOne = make(op, "comb.sub", history.getType(), {delta, constant(op, bits, 1)});
        Value changed = make(op, "comb.and", history.getType(), {delta, minusOne});
        Value legal = compare(op, "digital_eq", changed, constant(op, bits, 0), false);
        Value enable = sourceControl->reset ? make(op, "comb.and", builder.getI1Type(),
                                                   {seen, invert(op, sourceControl->reset)})
                                            : seen;
        crossingAssertion(crossing, *sourceControl, enable, legal,
                          "NODAL Gray transition must change at most one bit per source sample");
        crossingComment(crossing,
                        "Gray obligations: transition=one_bit_per_source_sample (checked); "
                        "skew=less_than_source_period; stability=stable_between_source_samples. "
                        "Payload stages have no invented reset value; output is unspecified during "
                        "startup and N-sample reset recovery. Physical skew and between-sample "
                        "stability are environment obligations, "
                        "not proved by the digital monitor.");
      } else {
        // Acknowledgement samples destination HISTORY, not the second forward
        // stage: readiness therefore means the destination has delivered the
        // event, rather than merely bringing a pending event into its domain.
        auto toggleNext = backedges.get(builder.getI1Type(), op->getLoc());
        Value toggle = crossingState(crossing, 0, toggleNext, *sourceControl, "source_toggle");
        Value priorPulse = crossingState(crossing, 1, input, *sourceControl, "previous_pulse");
        Value forward = crossingState(crossing, 2, toggle, *destination, "forward");
        forward = crossingState(crossing, 3, forward, *destination, "forward");
        Value history = crossingState(crossing, 4, forward, *destination, "destination_history");
        Value ack = crossingState(crossing, 5, history, *sourceControl, "return_acknowledgement");
        ack = crossingState(crossing, 6, ack, *sourceControl, "return_acknowledgement");
        toggleNext.setValue(make(op, "comb.xor", builder.getI1Type(), {toggle, Value(input)}));
        values[op->getResult(0)] = make(op, "comb.xor", builder.getI1Type(), {forward, history});
        Value ready = compare(op, "digital_eq", toggle, ack, false);
        Value legalPulse =
            make(op, "comb.and", builder.getI1Type(), {ready, invert(op, priorPulse)});
        Value legal = make(op, "comb.or", builder.getI1Type(), {invert(op, input), legalPulse});
        crossingAssertion(
            crossing, *sourceControl, invert(op, sourceControl->reset), legal,
            "NODAL pulse requires returned acknowledgement and single-source-cycle width");
        crossingComment(crossing,
                        "Pulse obligations: spacing=returned_acknowledgement; "
                        "width=single_source_cycle; reset_epoch=common_assertion. Hold common "
                        "assertion long enough for both domains to observe reset; no one-sided "
                        "restart. Delivery is not promised before the common reset epoch or "
                        "after a spacing/width violation.");
      }
    }
    for (Operation &op : source.getBody().front()) {
      if (!op.getNumResults() || controlProjections.contains(&op) ||
          controlSelectors.contains(&op) ||
          isa<DigitalStorageOp, PortValueOp, CrossingOp, ResetBoundaryOp, ResetSyncOp,
              ResetCombineOp>(op))
        continue;
      SmallVector<Value> inputs;
      for (Value old : op.getOperands()) {
        Value mapped = values.lookup(old);
        if (!mapped) {
          backedges.abandon();
          return reject(&op, "actual scalar operand has no target value");
        }
        inputs.push_back(mapped);
      }
      Value result = lowerValue(&op, inputs);
      if (!result) {
        backedges.abandon();
        return failure();
      }
      // An identity conversion may return a temporary graph reference. Keep an
      // SSA use so Backedge RAUW also updates it; a raw Value map is not a use.
      if (isa_and_nonnull<UnrealizedConversionCastOp>(result.getDefiningOp()))
        result = circt::hw::WireOp::create(builder, op.getLoc(), result).getResult();
      if (auto found = materializations.find(sourcePath(&op)); found != materializations.end()) {
        auto record = found->second;
        auto intent = record.getAs<DictionaryAttr>("intent");
        // The shared analyzer decides whether materialization is required. Only
        // an authored exact name is assigned here; source hints remain source
        // identity, rather than becoming a competing target naming policy.
        StringRef name = text(intent, "kind") == "named" ? text(intent, "value") : StringRef();
        auto wire = circt::hw::WireOp::create(builder, op.getLoc(), result, name);
        correlate(wire, &op);
        result = wire.getResult();
      }
      if (!llvm::is_contained(inputs, result) && result.getDefiningOp())
        correlate(result.getDefiningOp(), &op);
      values[op.getResult(0)] = result;
    }
    llvm::DenseMap<Value, circt::Backedge> connectionByValue;
    for (auto &[original, edge] : connectionEdges)
      connectionByValue.try_emplace(original, edge);
    llvm::DenseSet<Value> resolved;
    for (auto &[original, unused] : connectionEdges) {
      SmallVector<Value> pending{original};
      while (!pending.empty()) {
        Value current = pending.back();
        if (resolved.contains(current)) {
          pending.pop_back();
          continue;
        }
        Value rhs = aliases.lookup(current);
        if (aliases.count(rhs) && !resolved.contains(rhs)) {
          pending.push_back(rhs);
          continue;
        }
        Value mapped = values.lookup(rhs);
        if (!mapped) {
          backedges.abandon();
          return reject(current.getDefiningOp(), "canonical scalar driver did not lower");
        }
        if (auto *op = current.getDefiningOp(); isa<DigitalStorageOp>(op)) {
          auto wire = circt::hw::WireOp::create(builder, op->getLoc(), mapped, text(op, "name"));
          correlate(wire, op);
          mapped = wire.getResult();
        }
        connectionByValue[current].setValue(mapped);
        values[current] = mapped;
        resolved.insert(current);
        pending.pop_back();
      }
    }
    for (auto &[crossing, edge] : crossingEdges) {
      Value input = values.lookup(crossing->getOperand(0));
      if (!input) {
        backedges.abandon();
        return reject(crossing, "crossing actual source input did not lower");
      }
      edge.setValue(input);
    }
    for (auto &[update, edge] : resetValueEdges)
      edge.setValue(values.lookup(update->getOperand(4)));
    for (auto &[update, edge] : stateEdges) {
      Value next = values.lookup(update->getOperand(1));
      if (text(update, "reset_policy") == "sync") {
        Value reset = resetActive(values.lookup(update->getOperand(3)), update);
        next = mux(update, reset, values.lookup(update->getOperand(4)), next);
      }
      edge.setValue(next);
    }
    for (Operation &op : source.getBody().front()) {
      if (!named(&op, "nodal.sequential_assert"))
        continue;
      auto clock =
          circt::seq::FromClockOp::create(builder, op.getLoc(), values.lookup(op.getOperand(1)));
      auto assertion = circt::verif::ClockedAssertOp::create(
          builder, op.getLoc(), values.lookup(op.getOperand(0)),
          text(&op, "edge") == "falling" ? circt::verif::ClockEdge::Neg
                                         : circt::verif::ClockEdge::Pos,
          clock, Value(), op.getAttrOfType<StringAttr>("root_id"));
      correlate(assertion, &op);
    }
    SmallVector<Value> outputs;
    for (Operation *port : ports)
      if (text(port, "direction") == "output")
        outputs.push_back(values.lookup(portValues[port].front()));
    body->getTerminator()->setOperands(outputs);
    if (failed(backedges.clearOrEmitError()))
      return failure();
    source.erase();
    return success();
  }

private:
  static bool aggregatePayload(Type type) {
    return isa<nodal::ShapedType, StructType>(type) && getDigitalControlBoundaryKind(type).empty();
  }
  Type targetValueType(Type type) {
    if (Type scalar = targetType(type))
      return scalar;
    if (!aggregatePayload(type))
      return {};
    auto [entry, inserted] = packedTypes.try_emplace(type, Type());
    if (inserted) {
      // Canonical type/enum/shape owners establish exactness. A finite maximum
      // or an overridable parameter default is never an actual packed width.
      auto bits = inferDigitalBitPatternFixedBits(type, source);
      if (succeeded(bits) && *bits > 0 && *bits <= maximumWidth)
        entry->second = builder.getIntegerType(*bits);
    }
    return entry->second;
  }
  DictionaryAttr packedLayout(Type type) {
    auto found = packedLayouts.find(type);
    if (found != packedLayouts.end())
      return found->second;
    // This is target representation metadata, not a second type validator.
    // Exact width/shape facts were established by the canonical owners above.
    // Record structural strides rather than expanding physical vector lanes.
    auto bits = inferDigitalBitPatternFixedBits(type, source);
    assert(succeeded(bits) && *bits <= maximumWidth);
    NamedAttrList layout;
    std::string identity;
    llvm::raw_string_ostream stream(identity);
    type.print(stream);
    layout.set("source_type", builder.getStringAttr(identity));
    layout.set("width", builder.getI64IntegerAttr(*bits));
    layout.set("order", builder.getStringAttr("recursive-row-major-fields-lsb-first"));
    if (auto shape = dyn_cast<nodal::ShapedType>(type)) {
      layout.set("kind", builder.getStringAttr("vec"));
      DictionaryAttr element = packedLayout(shape.getElementType());
      layout.set("element", element);
      SmallVector<StringRef> dimensions;
      shape.getDimensions().split(dimensions, ',');
      SmallVector<Attribute> extents, strides(dimensions.size());
      int64_t stride = element.getAs<IntegerAttr>("width").getInt();
      for (StringRef dimension : dimensions) {
        auto extent = resolveStaticShapeDimension(source, dimension);
        assert(succeeded(extent) && extent->bounds.lower == extent->bounds.upper);
        extents.push_back(builder.getI64IntegerAttr(extent->bounds.lower));
      }
      for (unsigned i = dimensions.size(); i; --i) {
        strides[i - 1] = builder.getI64IntegerAttr(stride);
        stride *= cast<IntegerAttr>(extents[i - 1]).getInt();
      }
      layout.set("dimensions", builder.getArrayAttr(extents));
      layout.set("bit_strides", builder.getArrayAttr(strides));
    } else if (auto structure = dyn_cast<StructType>(type)) {
      layout.set("kind", builder.getStringAttr("struct"));
      SmallVector<Attribute> fields;
      int64_t offset = 0;
      for (Attribute attribute : structure.getFields()) {
        auto field = cast<DictionaryAttr>(attribute);
        DictionaryAttr child = packedLayout(field.getAs<TypeAttr>("type").getValue());
        fields.push_back(builder.getDictionaryAttr(
            {builder.getNamedAttr("name", field.getAs<StringAttr>("name")),
             builder.getNamedAttr("bit_offset", builder.getI64IntegerAttr(offset)),
             builder.getNamedAttr("layout", child)}));
        offset += child.getAs<IntegerAttr>("width").getInt();
      }
      layout.set("fields", builder.getArrayAttr(fields));
    } else
      layout.set("kind", builder.getStringAttr("scalar"));
    auto result = builder.getDictionaryAttr(layout);
    packedLayouts.try_emplace(type, result);
    return result;
  }
  struct CrossingControls {
    Value clock;
    Value assertionClock;
    Value reset;
    bool asynchronous = false;
    bool falling = false;
  };
  FailureOr<CrossingControls> crossingControls(Operation *domain, Operation *binding) {
    CrossingControls result;
    result.clock = values.lookup(binding->getOperand(0));
    if (!result.clock)
      return failure();
    result.assertionClock =
        circt::seq::FromClockOp::create(builder, binding->getLoc(), result.clock);
    result.falling = text(domain, "edge") == "falling";
    if (result.falling)
      result.clock = circt::seq::ClockInverterOp::create(builder, binding->getLoc(), result.clock);
    StringRef policy = text(domain, "reset_policy");
    result.asynchronous = policy == "async" || policy == "async_assert_sync_release";
    if (policy != "none") {
      result.reset = values.lookup(binding->getOperand(1));
      if (!result.reset)
        return failure();
      result.reset = resetActive(result.reset, binding);
    }
    return result;
  }
  Value crossingState(const ExecutableCrossing &crossing, unsigned index, Value input,
                      const CrossingControls &control, StringRef role, bool resetState = true) {
    Operation *op = crossing.operation;
    StringAttr name = crossingNames.find(op)->second[index];
    Value reset = resetState ? control.reset : Value();
    Value zero = reset ? constant(op, cast<IntegerType>(input.getType()).getWidth(), 0) : Value();
    Value next = reset && !control.asynchronous ? mux(op, reset, zero, input) : input;
    Operation *state;
    if (reset && control.asynchronous)
      state = circt::seq::FirRegOp::create(builder, op->getLoc(), next, control.clock, name, reset,
                                           zero, circt::hw::InnerSymAttr::get(name), true);
    else
      state = circt::seq::CompRegOp::create(builder, op->getLoc(), next, control.clock,
                                            circt::StringAttrOrRef(name));
    correlate(state, op);
    // Symbols preserve independent chains through CSE. Metadata retains the
    // canonical source contract, every state role and the original source path.
    state->setAttr("nodal.crossing", op->getAttr("metadata"));
    state->setAttr("nodal.crossing_contract", op->getAttr("contract"));
    state->setAttr("nodal.crossing_stages", op->getAttr("stages"));
    state->setAttr("nodal.crossing_source_domain", op->getAttr("source_domain"));
    state->setAttr("nodal.crossing_destination_domain", op->getAttr("destination_domain"));
    state->setAttr("nodal.crossing_stage", builder.getI64IntegerAttr(index));
    state->setAttr("nodal.crossing_role", builder.getStringAttr(role));
    if (Attribute obligations = op->getAttr("obligations"))
      state->setAttr("nodal.crossing_obligations", obligations);
    if (aggregatePayload(op->getOperand(0).getType()))
      state->setAttr("nodal.packed_abi", packedLayout(op->getOperand(0).getType()));
    return state->getResult(0);
  }
  std::string crossingSourceComment(const ExecutableCrossing &crossing) {
    std::string path = sourcePath(crossing.operation).str();
    for (char &character : path)
      if (character == '\n' || character == '\r')
        character = ' ';
      else if (character == '{')
        character = '(';
      else if (character == '}')
        character = ')';
    return "// NODAL crossing " + path + ": ";
  }
  void crossingComment(const ExecutableCrossing &crossing, StringRef comment) {
    auto emitted = circt::sv::VerbatimOp::create(
        builder, crossing.operation->getLoc(),
        builder.getStringAttr(crossingSourceComment(crossing) + comment.str()), ValueRange{},
        builder.getArrayAttr({}));
    correlate(emitted, crossing.operation);
  }
  void crossingAssertion(const ExecutableCrossing &crossing, const CrossingControls &control,
                         Value enable, Value property, StringRef message) {
    // Pinned firtool emits verif.clocked_assert as concurrent SVA, unsupported
    // by Icarus. A clocked immediate assertion preserves the runtime contract
    // there too. Gray skips unknown seen-sample state deterministically. Pulse
    // skips only a known asserted reset: an unknown release chain cannot hide
    // an attempted pulse before reset. No initial block invents checker state.
    std::string assertion =
        crossingSourceComment(crossing) + message.str() + "\n" + "always @(" +
        (control.falling ? "negedge" : "posedge") + " {{0}}) begin\n" +
        (crossing.kind == CrossingKind::Pulse ? "  if (({{1}}) !== 1'b0) begin\n"
                                              : "  if (({{1}}) === 1'b1) begin\n") +
        "    assert (({{2}}) === 1'b1) else $fatal(1, \"NODAL-CROSSING-057: " + message.str() +
        "\");\n  end\nend";
    auto emitted = circt::sv::VerbatimOp::create(
        builder, crossing.operation->getLoc(), builder.getStringAttr(assertion),
        ValueRange{control.assertionClock, enable, property}, builder.getArrayAttr({}));
    correlate(emitted, crossing.operation);
    emitted->setAttr("nodal.crossing_check", crossing.operation->getAttr("contract"));
  }
  LogicalResult planControlProjections() {
    if (source.getBody().front().empty())
      return success();
    Operation *use = &source.getBody().front().back();
    SmallVector<Value> selectors;
    for (Operation &op : source.getBody().front()) {
      if (!isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(op) ||
          getDigitalControlBoundaryKind(op.getResult(0).getType()).empty())
        continue;
      auto projection = resolveStaticShapeProjection(op.getResult(0), use);
      if (failed(projection) || !projection->exactFixedCardinality ||
          !targetControlShapeType(projection->root.getType(), source))
        return reject(&op,
                      "physical control projection requires a fixed input shape of 1..4096 bits");
      auto root = projection->root.getDefiningOp<PortValueOp>();
      auto ref = root ? root->getAttrOfType<FlatSymbolRefAttr>("port") : FlatSymbolRefAttr();
      Operation *port = ref ? SymbolTable::lookupSymbolIn(source, ref) : nullptr;
      if (!port || text(port, "direction") != "input")
        return reject(&op, "physical control projection requires an owned input port");
      if (auto scalar = dyn_cast<ControlCapabilityType>(op.getResult(0).getType())) {
        if (!projection->literalRootFlatOffset)
          return reject(&op, "physical control projection requires a proved literal input offset");
        // A dead last projection has no later use at which to establish scope.
        // Its shape/root proof is still checked, but it needs no target value.
        if (!op.getResult(0).use_empty()) {
          auto control = resolvePhysicalControl(op.getResult(0), use, scalar.getKind());
          if (failed(control) || !control->literalOffset)
            return reject(&op,
                          "physical control projection requires a proved literal input offset");
          physicalControls.emplace_back(op.getResult(0), *control);
        }
      }
      controlProjections.insert(&op);
      llvm::append_range(selectors, op.getOperands().drop_front());
    }
    while (!selectors.empty()) {
      Value value = selectors.pop_back_val();
      Operation *op = value.getDefiningOp();
      if (!op || !isa<ConstantOp, ConstLiteralOp, ConstExprOp>(op) ||
          (!value.getType().isIndex() && !value.getType().isInteger(64)))
        return reject(source, "physical control selectors require fixed compile-time expressions");
      if (!controlSelectors.insert(op).second)
        continue;
      llvm::append_range(selectors, op->getOperands());
    }
    for (Operation *op : controlSelectors)
      for (Operation *user : op->getUsers())
        if (!controlSelectors.contains(user) && !controlProjections.contains(user))
          return reject(op, "physical control selector escapes its compile-time projection");
    return success();
  }
  Value constant(Operation *at, unsigned bits, const llvm::APInt &number) {
    return circt::hw::ConstantOp::create(
        builder, at->getLoc(),
        builder.getIntegerAttr(builder.getIntegerType(bits), number.zextOrTrunc(bits)));
  }
  Value constant(Operation *at, unsigned bits, uint64_t number) {
    return constant(at, bits, llvm::APInt(bits, number));
  }
  Value make(Operation *at, StringRef name, Type type, ValueRange operands,
             ArrayRef<NamedAttribute> attrs = {}) {
    OperationState state(at->getLoc(), name);
    state.addOperands(operands);
    state.addTypes(type);
    state.addAttributes(attrs);
    Operation *op = builder.create(state);
    correlate(op, at);
    return op->getResult(0);
  }
  Value mux(Operation *at, Value cond, Value a, Value b) {
    return make(at, "comb.mux", a.getType(), {cond, a, b});
  }
  Value invert(Operation *at, Value value) {
    unsigned bits = cast<IntegerType>(value.getType()).getWidth();
    return make(at, "comb.xor", value.getType(),
                {value, constant(at, bits, llvm::APInt::getAllOnes(bits))});
  }
  Value resetActive(Value value, Operation *at) {
    return text(at, "reset_polarity") == "active_low" ? invert(at, value) : value;
  }
  Value resize(Operation *at, Value value, unsigned bits, bool sign) {
    unsigned old = cast<IntegerType>(value.getType()).getWidth();
    if (old == bits)
      return value;
    if (old > bits)
      return circt::comb::ExtractOp::create(builder, at->getLoc(), value, 0, bits);
    Value prefix;
    if (sign) {
      Value high = circt::comb::ExtractOp::create(builder, at->getLoc(), value, old - 1, 1);
      prefix = circt::comb::ReplicateOp::create(builder, at->getLoc(), high, bits - old);
    } else
      prefix = constant(at, bits - old, 0);
    return circt::comb::ConcatOp::create(builder, at->getLoc(), prefix, value);
  }
  Value compare(Operation *at, StringRef comparison, Value a, Value b, bool sign) {
    using P = circt::comb::ICmpPredicate;
    P p = llvm::StringSwitch<P>(comparison)
              .Cases({"digital_eq", "enum_eq"}, P::eq)
              .Case("digital_ne", P::ne)
              .Case("digital_lt", sign ? P::slt : P::ult)
              .Case("digital_le", sign ? P::sle : P::ule)
              .Case("digital_gt", sign ? P::sgt : P::ugt)
              .Case("digital_ge", sign ? P::sge : P::uge)
              .Default(P::eq);
    return make(at, "comb.icmp", builder.getI1Type(), {a, b},
                {builder.getNamedAttr(
                    "predicate", circt::comb::ICmpPredicateAttr::get(builder.getContext(), p))});
  }
  IntegerAttr enumCode(Operation *op, StringRef id) {
    return enumCodes.find(op->getAttr("enum_ref"))->second.byId.lookup(id);
  }
  Value lowerValue(Operation *op, ArrayRef<Value> inputs) {
    unsigned bits = width(op->getResult(0).getType());
    if (isa<ConstantOp, ConstLiteralOp>(op)) {
      auto attr = op->getAttrOfType<IntegerAttr>("value");
      bool negative =
          !cast<IntegerType>(attr.getType()).isUnsigned() && attr.getValue().isNegative();
      return constant(op, bits,
                      negative ? attr.getValue().sextOrTrunc(bits)
                               : attr.getValue().zextOrTrunc(bits));
    }
    if (isa<SequentialSelectOp>(op))
      return mux(op, inputs[0], inputs[1], inputs[2]);
    if (named(op, "nodal.reset_asserted"))
      return resetActive(inputs[0], op);
    StringRef code = text(op, "operator_name");
    if (isa<EnumExprOp>(op)) {
      auto ids = op->getAttrOfType<ArrayAttr>("case_ids");
      if (code == "enum_literal")
        return constant(op, bits, enumCode(op, cast<StringAttr>(ids[0]).getValue()).getValue());
      if (code == "enum_as_bits")
        return inputs[0];
      if (code == "enum_eq")
        return compare(op, code, inputs[0], inputs[1], false);
      SmallVector<IntegerAttr> codes;
      if (code == "enum_is_valid")
        codes = enumCodes.find(op->getAttr("enum_ref"))->second.inDeclarationOrder;
      else
        for (Attribute id : ids)
          codes.push_back(enumCode(op, cast<StringAttr>(id).getValue()));
      SmallVector<Value> tests;
      for (IntegerAttr value : codes) {
        Value number = constant(op, width(op->getOperand(0).getType()), value.getValue());
        tests.push_back(compare(op, "enum_eq", inputs[0], number, false));
      }
      if (tests.empty())
        return constant(op, 1, 0);
      return tests.size() == 1 ? tests.front() : make(op, "comb.or", builder.getI1Type(), tests);
    }
    if (code == "digital_alias")
      return inputs[0];
    if (code == "digital_select")
      return mux(op, inputs[0], inputs[1], inputs[2]);
    bool sign = isa<SIntType>(op->getOperand(0).getType());
    if (code == "digital_not" || code == "bool_not")
      return invert(op, inputs[0]);
    if (code == "digital_concat")
      return circt::comb::ConcatOp::create(builder, op->getLoc(), inputs[0], inputs[1]);
    if (inputs.size() == 1) {
      Value value = resize(op, inputs[0], bits, sign);
      if (code == "digital_neg")
        return make(op, "comb.sub", value.getType(), {constant(op, bits, 0), value});
      return value;
    }
    bool comparison = code == "digital_eq" || code == "digital_ne" || code == "digital_lt" ||
                      code == "digital_le" || code == "digital_gt" || code == "digital_ge";
    unsigned common = comparison ? std::max(width(op->getOperand(0).getType()),
                                            width(op->getOperand(1).getType()))
                                 : bits;
    Value a = resize(op, inputs[0], common, sign), b = resize(op, inputs[1], common, sign);
    if (comparison)
      return compare(op, code, a, b, sign);
    if (code == "digital_min" || code == "digital_max")
      return mux(op, compare(op, code == "digital_min" ? "digital_lt" : "digital_gt", a, b, sign),
                 a, b);
    StringRef target = llvm::StringSwitch<StringRef>(code)
                           .Cases({"digital_and", "bool_and"}, "comb.and")
                           .Cases({"digital_or", "bool_or"}, "comb.or")
                           .Case("digital_xor", "comb.xor")
                           .Case("digital_add", "comb.add")
                           .Case("digital_sub", "comb.sub")
                           .Case("digital_mul", "comb.mul")
                           .Default("");
    return make(op, target, builder.getIntegerType(bits), {a, b});
  }
  nodal::ModuleOp source;
  OpBuilder &builder;
  uint64_t &cost;
  DomainModel domains;
  SmallVector<ExecutableCrossing> crossings;
  SmallVector<Operation *> resets;
  llvm::DenseMap<Operation *, SmallVector<StringAttr>> resetNames;
  llvm::DenseMap<Operation *, SmallVector<StringAttr>> crossingNames;
  SmallVector<Operation *> ports, registers;
  llvm::DenseMap<Operation *, Type> portTypes;
  llvm::DenseMap<Type, Type> packedTypes;
  llvm::DenseMap<Type, DictionaryAttr> packedLayouts;
  llvm::DenseSet<Operation *> controlProjections, controlSelectors;
  SmallVector<std::pair<Value, PhysicalControl>> physicalControls;
  llvm::DenseMap<Operation *, SmallVector<Value>> portValues;
  llvm::DenseMap<Value, Operation *> updates;
  llvm::DenseMap<Value, Value> aliases, values;
  llvm::StringMap<DictionaryAttr> materializations;
  struct EnumCodes {
    llvm::StringMap<IntegerAttr> byId;
    SmallVector<IntegerAttr> inDeclarationOrder;
  };
  llvm::DenseMap<Attribute, EnumCodes> enumCodes;
};

class SequentialToCIRCTPass final
    : public PassWrapper<SequentialToCIRCTPass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(SequentialToCIRCTPass)
  StringRef getArgument() const final { return "nodal-sequential-to-circt"; }
  StringRef getDescription() const final {
    return "Transactionally lower verified finite scalar register/FSM/CDC dataflow to "
           "HW/Comb/Seq/Verif";
  }
  void getDependentDialects(DialectRegistry &registry) const final {
    registry.insert<circt::hw::HWDialect, circt::comb::CombDialect, circt::seq::SeqDialect,
                    circt::verif::VerifDialect, circt::sv::SVDialect>();
  }
  void runOnOperation() final {
    auto input = getOperation();
    unsigned count = 0;
    auto walk = input.walk([&](Operation *op) {
      if (++count > maximumAuthoredOperations) {
        (void)reject(op, "authored whole-module operation budget exceeds 65536");
        return WalkResult::interrupt();
      }
      return WalkResult::advance();
    });
    if (walk.wasInterrupted() || failed(verify(input))) {
      signalPassFailure();
      return;
    }
    OwningOpRef<mlir::ModuleOp> candidate = cast<mlir::ModuleOp>(input->clone());
    SmallVector<nodal::ModuleOp> owners;
    SmallVector<Operation *> enums;
    for (Operation &op : *candidate->getBody()) {
      if (auto owner = dyn_cast<nodal::ModuleOp>(op))
        owners.push_back(owner);
      else if (isa<EnumOp>(op))
        enums.push_back(&op);
      else {
        (void)reject(
            &op,
            "whole-module profile admits only Nodal modules and checked enum ABI declarations");
        signalPassFailure();
        return;
      }
    }
    for (auto owner : owners) {
      // The original verifier checked any attached receipt. Normalization changes
      // actual SSA uses, so recompute canonical target requirements on the private
      // candidate instead of accepting the old report as a semantic proof.
      owner->removeAttr(reportKey);
      if (failed(normalizeSequentialModel(owner))) {
        signalPassFailure();
        return;
      }
    }
    OpBuilder builder(input.getContext());
    uint64_t cost = 2 * owners.size();
    SmallVector<std::unique_ptr<ModuleLowering>> lowerings;
    for (auto owner : owners) {
      auto lowering = std::make_unique<ModuleLowering>(owner, builder, cost);
      if (failed(lowering->preflight())) {
        signalPassFailure();
        return;
      }
      lowerings.push_back(std::move(lowering));
    }
    SmallVector<Attribute> abi;
    for (Operation *definition : enums) {
      auto underlying = definition->getAttrOfType<TypeAttr>("underlying_type");
      if (!underlying || !targetType(underlying.getValue())) {
        (void)reject(definition, "enum ABI exceeds the fixed scalar width profile");
        signalPassFailure();
        return;
      }
      auto metadata = definition->getAttrOfType<DictionaryAttr>("metadata");
      if (!metadata || !metadata.getAs<IntegerAttr>("abi_version")) {
        (void)reject(definition, "legacy enum descriptors are not a checked executable ABI");
        signalPassFailure();
        return;
      }
      NamedAttrList record;
      record.set("symbol", definition->getAttr("sym_name"));
      record.set("metadata", metadata);
      SmallVector<Attribute> cases;
      for (Operation &entry : definition->getRegion(0).front()) {
        NamedAttrList item;
        item.set("name", entry.getAttr("sym_name"));
        item.set("code", entry.getAttr("value"));
        item.set("metadata", entry.getAttr("metadata"));
        cases.push_back(builder.getDictionaryAttr(item));
      }
      record.set("cases", builder.getArrayAttr(cases));
      abi.push_back(builder.getDictionaryAttr(record));
    }
    for (auto &lowering : lowerings)
      if (failed(lowering->lower())) {
        signalPassFailure();
        return;
      }
    for (Operation *definition : enums)
      definition->erase();
    if (!abi.empty())
      candidate->getOperation()->setAttr("nodal.enum_abi", builder.getArrayAttr(abi));
    if (failed(verify(*candidate))) {
      signalPassFailure();
      return;
    }
    input->setAttrs(candidate->getOperation()->getAttrs());
    input.getBodyRegion().takeBody(candidate->getBodyRegion());
  }
};
} // namespace
std::unique_ptr<Pass> createSequentialToCIRCTPass() {
  return std::make_unique<SequentialToCIRCTPass>();
}
void registerSequentialToCIRCTPass() {
  static PassRegistration<SequentialToCIRCTPass> registration;
}
} // namespace nodal
