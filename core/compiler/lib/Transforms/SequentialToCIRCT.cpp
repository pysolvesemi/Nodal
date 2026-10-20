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
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ResetModel.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
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
    SmallVector<std::pair<Value, Operation *>> seeds;
    for (Operation &op : source.getBody().front()) {
      StringRef name = op.getName().getStringRef();
      if (op.getNumRegions() || op.getNumSuccessors())
        return reject(&op, "target profile requires flat normalized scalar operations");
      for (Type type : op.getOperandTypes())
        if (!targetType(type))
          return reject(&op, "operand requires fixed scalar width 1..4096 or a direct clock/reset");
      for (Type type : op.getResultTypes())
        if (!targetType(type))
          return reject(&op, "result requires fixed scalar width 1..4096 or a direct clock/reset");
      uint64_t expansion = 1;
      if (isa<PortOp>(op)) {
        auto type = op.getAttrOfType<TypeAttr>("type");
        if (!type || !targetType(type.getValue()) ||
            (text(&op, "direction") != "input" && text(&op, "direction") != "output"))
          return reject(
              &op, "only fixed scalar input/output and direct Clock/Reset ports are supported");
        if (text(&op, "direction") == "output" && isa<ControlCapabilityType>(type.getValue()))
          return reject(&op, "clock/reset output capabilities require a later boundary profile");
        ports.push_back(&op);
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
        auto crossing = inspectLevelCrossing(&op, domains);
        if (failed(crossing))
          return failure();
        // The canonical model verifies type, endpoints, source sampling and
        // independent identity. Only the finite target expansion belongs here.
        crossings.push_back(*crossing);
        expansion = 2 * crossing->stages + 5;
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
      for (unsigned stage = 0; stage < crossing.stages; ++stage) {
        std::string base = "__nodal_sync_" + std::to_string(crossingNames.size()) + "_stage_" +
                           std::to_string(stage);
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
      info.type = targetType(port->getAttrOfType<TypeAttr>("type").getValue());
      bool input = text(port, "direction") == "input";
      info.dir = input ? circt::hw::ModulePort::Input : circt::hw::ModulePort::Output;
      info.argNum = input ? inputIndex++ : outputIndex++;
      info.loc = port->getLoc();
      if (!sourcePath(port).empty())
        info.attrs = builder.getDictionaryAttr(
            {builder.getNamedAttr("nodal.source_path", builder.getStringAttr(sourcePath(port)))});
      targetPorts.push_back(info);
    }
    builder.setInsertionPoint(source);
    auto target = circt::hw::HWModuleOp::create(builder, source.getLoc(),
                                                source->getAttrOfType<StringAttr>("sym_name"),
                                                circt::hw::ModulePortInfo(targetPorts));
    correlate(target, source);
    Block *body = target.getBodyBlock();
    builder.setInsertionPoint(body->getTerminator());
    circt::BackedgeBuilder backedges(builder, source.getLoc());
    inputIndex = 0;
    for (Operation *port : ports) {
      if (text(port, "direction") != "input")
        continue;
      for (Value value : portValues[port])
        values[value] = body->getArgument(inputIndex);
      ++inputIndex;
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
      auto edge = backedges.get(targetType(original.getType()), op.getLoc());
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
      auto edge = backedges.get(targetType(reg->getResult(0).getType()), reg->getLoc());
      Operation *state;
      StringRef policy = text(update, "reset_policy");
      if (policy == "async" || policy == "async_assert_sync_release") {
        Value rawReset = values.lookup(update->getOperand(3));
        if (!rawReset)
          return reject(update, "effective reset did not map before consumer");
        auto initial = backedges.get(targetType(reg->getResult(0).getType()), reg->getLoc());
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
      Value clock = values.lookup(crossing.destinationBinding->getOperand(0));
      if (!clock) {
        backedges.abandon();
        return reject(op, "crossing destination clock was not mapped to a module input");
      }
      if (text(crossing.destinationDomain, "edge") == "falling")
        clock = circt::seq::ClockInverterOp::create(builder, op->getLoc(), clock);
      Value reset, zero;
      if (text(crossing.destinationDomain, "reset_policy") != "none") {
        reset = values.lookup(crossing.destinationBinding->getOperand(1));
        if (!reset) {
          backedges.abandon();
          return reject(op, "crossing destination reset was not mapped to a module input");
        }
        reset = resetActive(reset, crossing.destinationBinding);
        zero = constant(op, 1, 0);
      }
      auto input = backedges.get(builder.getI1Type(), op->getLoc());
      Value previous = input;
      const auto &names = crossingNames.find(op)->second;
      for (unsigned stage = 0; stage < crossing.stages; ++stage) {
        bool async =
            text(crossing.destinationDomain, "reset_policy") == "async" ||
            text(crossing.destinationDomain, "reset_policy") == "async_assert_sync_release";
        Value next = reset && !async ? mux(op, reset, zero, previous) : previous;
        Operation *state;
        if (async)
          state =
              circt::seq::FirRegOp::create(builder, op->getLoc(), next, clock, names[stage], reset,
                                           zero, circt::hw::InnerSymAttr::get(names[stage]), true);
        else
          state = circt::seq::CompRegOp::create(builder, op->getLoc(), next, clock,
                                                circt::StringAttrOrRef(names[stage]));
        correlate(state, op);
        // Keep the complete canonical source mapping and stage identity. The
        // symbol, rather than an advisory metadata marker, prevents CSE merging.
        state->setAttr("nodal.crossing", op->getAttr("metadata"));
        state->setAttr("nodal.crossing_contract", op->getAttr("contract"));
        state->setAttr("nodal.crossing_stages", op->getAttr("stages"));
        state->setAttr("nodal.crossing_source_domain", op->getAttr("source_domain"));
        state->setAttr("nodal.crossing_destination_domain", op->getAttr("destination_domain"));
        state->setAttr("nodal.crossing_stage", builder.getI64IntegerAttr(stage));
        previous = state->getResult(0);
      }
      values[op->getResult(0)] = previous;
      crossingEdges.emplace_back(op, input);
    }
    for (Operation &op : source.getBody().front()) {
      if (!op.getNumResults() || isa<DigitalStorageOp, PortValueOp, CrossingOp, ResetBoundaryOp,
                                     ResetSyncOp, ResetCombineOp>(op))
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
  SmallVector<LevelCrossing> crossings;
  SmallVector<Operation *> resets;
  llvm::DenseMap<Operation *, SmallVector<StringAttr>> resetNames;
  llvm::DenseMap<Operation *, SmallVector<StringAttr>> crossingNames;
  SmallVector<Operation *> ports, registers;
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
    return "Transactionally lower verified finite scalar register/FSM/level-crossing dataflow to "
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
