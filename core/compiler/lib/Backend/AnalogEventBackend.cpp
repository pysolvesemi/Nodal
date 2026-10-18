#include "nodal/Backend/AnalogEventBackend.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogEvents.h"
#include "nodal/Dialect/Nodal/AnalogFunctions.h"
#include "nodal/Dialect/Nodal/AnalogStorageModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/StringExtras.h"

#include <limits>
#include <optional>

using namespace mlir;
namespace nodal {
namespace {
llvm::StringRef text(Operation *op, llvm::StringRef key) {
  if (auto attr = op->getAttrOfType<StringAttr>(key))
    return attr.getValue();
  return {};
}
llvm::StringRef field(DictionaryAttr attrs, llvm::StringRef key) {
  if (attrs)
    if (auto attr = attrs.getAs<StringAttr>(key))
      return attr.getValue();
  return {};
}
LogicalResult reject(Operation *op, llvm::StringRef reason) {
  return emitMappedFailure(op, "NODAL-BACKEND-EVENT-001", reason);
}
bool persistentShapedInitializer(Operation *operation) {
  auto variable = llvm::dyn_cast_or_null<AnalogVariableOp>(operation);
  auto initialized = variable ? variable->getAttrOfType<BoolAttr>("initialized") : BoolAttr();
  return variable && operation->getNumResults() == 1 && initialized && initialized.getValue() &&
         llvm::isa<ShapedType>(variable.getResult().getType()) &&
         llvm::isa_and_nonnull<AnalogProcedureOp>(operation->getParentOp());
}
bool identifier(llvm::StringRef value) {
  return !value.empty() && (llvm::isAlpha(value.front()) || value.front() == '_') &&
         llvm::all_of(value, [](char c) { return llvm::isAlnum(c) || c == '_' || c == '$'; });
}
std::string nameFor(llvm::StringRef identity, AnalogEventRenderState &state) {
  std::string root = "event_";
  for (char c : identity)
    root += llvm::isAlnum(c) || c == '_' ? c : '_';
  std::string name = root;
  unsigned collision = 0;
  while (!state.reserved.insert(name).second)
    name = root + "_" + std::to_string(++collision);
  return name;
}
using Captures = llvm::DenseMap<Operation *, std::string>;
FailureOr<std::string> renderSource(const AnalogSourceExpression &value,
                                    AnalogEventRenderState &state,
                                    const Captures &captures = Captures(),
                                    Operation *consumer = nullptr) {
  if (value.operation == "literal") {
    if (value.kind == "boolean")
      return value.spelling == "true" ? std::string("1") : std::string("0");
    if (value.kind == "integer") {
      int64_t integer = 0;
      if (llvm::StringRef(value.spelling).getAsInteger(10, integer) ||
          integer < std::numeric_limits<int32_t>::min() ||
          integer > std::numeric_limits<int32_t>::max())
        return failure();
    }
    return value.spelling;
  }
  if (value.operation == "reference") {
    if (value.storageReference) {
      auto references = state.liveReferences.find(consumer);
      if (references == state.liveReferences.end())
        return failure();
      auto access = references->second.find(value.storageReference);
      return access == references->second.end() ? FailureOr<std::string>(failure())
                                                : access->second.text;
    }
    if (auto found = captures.find(value.declaration); found != captures.end())
      return found->second;
    if (auto found = state.names.find(value.declaration); found != state.names.end())
      return found->second;
    if (!value.declaration)
      return failure();
    auto name =
        text(value.declaration, llvm::isa<ParameterOp>(value.declaration) ? "sym_name" : "name");
    if (!identifier(name))
      return failure();
    return name.str();
  }
  std::vector<std::string> args;
  for (const auto &child : value.operands) {
    auto rendered = renderSource(child, state, captures, consumer);
    if (failed(rendered))
      return failure();
    args.push_back(*rendered);
  }
  if (value.operation == "potential_access" || value.operation == "flow_access") {
    std::string result = value.operation == "potential_access" ? "V(" : "I(";
    for (unsigned i = 0; i < args.size(); ++i)
      result += (i ? ", " : "") + args[i];
    return result + ")";
  }
  if (llvm::StringRef(value.operation).starts_with(analogFunctionSourcePrefix)) {
    auto *entry = lookupAnalogFunction(
        llvm::StringRef(value.operation).drop_front(analogFunctionSourcePrefix.size()));
    if (!entry || args.size() != entry->arity)
      return failure();
    std::string result = entry->verilogA.str() + "(";
    for (unsigned i = 0; i < args.size(); ++i)
      result += (i ? ", " : "") + args[i];
    return result + ")";
  }
  if (llvm::StringRef(value.operation).starts_with(analogAnalysisSourcePrefix)) {
    auto *entry = lookupAnalogAnalysis(
        llvm::StringRef(value.operation).drop_front(analogAnalysisSourcePrefix.size()));
    if (!entry || !args.empty())
      return failure();
    return "analysis(\"" + entry->verilogA.str() + "\")";
  }
  if (value.operation == "analog_neg" || value.operation == "bool_not")
    return std::string("(") + (value.operation == "analog_neg" ? "-" : "!") + args[0] + ")";
  if (value.operation == "analog_select")
    return "(" + args[0] + " ? " + args[1] + " : " + args[2] + ")";
  llvm::StringRef symbol;
  if (value.operation == "analog_add")
    symbol = "+";
  else if (value.operation == "analog_sub")
    symbol = "-";
  else if (value.operation == "analog_mul")
    symbol = "*";
  else if (value.operation == "analog_div")
    symbol = "/";
  else if (value.operation == "real_gt")
    symbol = ">";
  else if (value.operation == "real_ge")
    symbol = ">=";
  else if (value.operation == "real_lt")
    symbol = "<";
  else if (value.operation == "real_le")
    symbol = "<=";
  else if (value.operation == "bool_and")
    symbol = "&&";
  else if (value.operation == "bool_or")
    symbol = "||";
  else
    return failure();
  return "(" + args[0] + " " + symbol.str() + " " + args[1] + ")";
}
FailureOr<std::string> expression(Operation *op, llvm::StringRef source,
                                  AnalogEventRenderState &state,
                                  const Captures &captures = Captures()) {
  auto parsed = parseAnalogSourceExpression(op, source);
  return failed(parsed) ? FailureOr<std::string>(failure())
                        : renderSource(*parsed, state, captures, op);
}
bool staticInitializer(const AnalogSourceExpression &value) {
  if (llvm::StringRef(value.operation).starts_with(analogAnalysisSourcePrefix))
    return false;
  if (value.operation == "reference")
    return value.declaration && llvm::isa<ParameterOp>(value.declaration);
  if (value.operation == "potential_access" || value.operation == "flow_access")
    return false;
  return llvm::all_of(value.operands, staticInitializer);
}
FailureOr<std::string> eventExpression(Operation *event, AnalogEventRenderState &state) {
  if (llvm::isa<AnalogEventOrOp>(event)) {
    std::string result;
    for (Value input : event->getOperands()) {
      auto child = eventExpression(input.getDefiningOp(), state);
      if (failed(child))
        return failure();
      result += (result.empty() ? "" : " or ") + *child;
    }
    return result;
  }
  const bool lifecycle = llvm::isa<AnalogInitialStepOp, AnalogFinalStepOp>(event);
  std::string function = llvm::isa<AnalogCrossOp>(event)         ? "cross"
                         : llvm::isa<AnalogAboveOp>(event)       ? "above"
                         : llvm::isa<AnalogTimerOp>(event)       ? "timer"
                         : llvm::isa<AnalogInitialStepOp>(event) ? "initial_step"
                                                                 : "final_step";
  auto arguments = event->getAttrOfType<ArrayAttr>(lifecycle ? "analyses" : "arguments");
  if (lifecycle && arguments.empty())
    return function;
  function += "(";
  for (unsigned i = 0; i < arguments.size(); ++i) {
    if (i)
      function += ", ";
    if (lifecycle) {
      auto name = llvm::cast<StringAttr>(arguments[i]).getValue();
      if (!identifier(name))
        return failure();
      function += "\"" + name.str() + "\"";
    } else {
      auto source = field(llvm::cast<DictionaryAttr>(arguments[i]), "value");
      auto rendered = expression(event, source, state);
      if (failed(rendered))
        return failure();
      function += *rendered;
    }
  }
  return function + ")";
}

using MappedAccess = AnalogMappedStorageAccess;
void renderMap(const MappedAccess &access, llvm::raw_ostream &out, unsigned indent) {
  for (const auto &step : access.program.steps)
    out.indent(indent) << access.temporary << " = " << step.expression << ";\n";
}
FailureOr<MappedAccess> storageAccess(Value handle, Operation *use, llvm::StringRef ordinal,
                                      AnalogEventRenderState &state,
                                      llvm::StringRef coordinateTemporary = {}) {
  auto reference = resolveAnalogStorageReference(handle, use);
  if (failed(reference))
    return failure();
  auto name = state.names.find(reference->declaration);
  if (name == state.names.end())
    return failure();
  std::string temporary = coordinateTemporary.str();
  if (temporary.empty())
    if (auto maps = state.mapNames.find(use); maps != state.mapNames.end())
      temporary = maps->second.second;
  MappedAccess result{name->second, std::move(temporary), {}};
  if (!llvm::isa<ShapedType>(reference->root.getType()))
    return result;
  auto program = renderShapedProjectionProgram(handle, ordinal, result.temporary);
  if (failed(program))
    return failure();
  result.program = *program;
  result.text += "[" + program->result + "]";
  return result;
}
void renderReferenceMaps(Operation *consumer, AnalogEventRenderState &state, llvm::raw_ostream &out,
                         unsigned indent) {
  auto references = state.liveReferences.find(consumer);
  if (references == state.liveReferences.end())
    return;
  // Operands retain authored binding order; DenseMap iteration does not.
  llvm::DenseSet<Value> emitted;
  for (Value operand : consumer->getOperands()) {
    auto access = references->second.find(operand);
    if (access != references->second.end() && emitted.insert(operand).second)
      renderMap(access->second, out, indent);
  }
}
void renderEventReferenceMaps(Operation *event, AnalogEventRenderState &state,
                              llvm::raw_ostream &out, unsigned indent) {
  llvm::SmallVector<Operation *> pending{event};
  llvm::DenseSet<Operation *> visited;
  while (!pending.empty()) {
    Operation *current = pending.pop_back_val();
    if (!visited.insert(current).second)
      continue;
    if (llvm::isa<AnalogEventOrOp>(current)) {
      for (Value operand : llvm::reverse(current->getOperands()))
        pending.push_back(operand.getDefiningOp());
    } else {
      renderReferenceMaps(current, state, out, indent);
    }
  }
}
FailureOr<MappedAccess> snapshotAccess(Value source, Operation *use, llvm::StringRef ordinal,
                                       AnalogEventRenderState &state) {
  auto value = resolveAnalogArrayValue(source, use);
  if (failed(value))
    return failure();
  auto found = state.names.find(value->producer);
  if (found == state.names.end())
    return failure();
  MappedAccess result{found->second, state.mapNames[use].first, {}};
  if (llvm::isa<AnalogArrayFillOp>(value->producer))
    return result;
  auto program = renderShapedProjectionProgram(source, ordinal, result.temporary);
  if (failed(program))
    return failure();
  result.program = *program;
  result.text += "[" + program->result + "]";
  return result;
}
FailureOr<Captures> readCaptures(Operation *consumer, ValueRange operands,
                                 AnalogEventRenderState &state) {
  Captures result;
  for (Value value : operands) {
    auto identity = getAnalogCapturedReadIdentity(value);
    if (failed(identity) || llvm::isa<ShapedType>(value.getType()))
      return failure();
    if (auto read = value.getDefiningOp<AnalogVariableReadOp>()) {
      if (!state.names.count(read) ||
          failed(resolveAnalogStorageReference(read->getOperand(0), consumer)))
        return failure();
      result[*identity] = state.names[read];
      continue;
    }
    if (failed(resolveAnalogArrayValue(value, consumer)) ||
        !state.names.count(value.getDefiningOp()))
      return failure();
    result[*identity] = state.names[value.getDefiningOp()];
  }
  return result;
}
LogicalResult renderArrayCopy(Operation *consumer, Value target, Value source,
                              AnalogEventRenderState &state, llvm::raw_ostream &out,
                              unsigned indent) {
  auto targetShape = state.shapes.find(target);
  auto counter = state.copyIndices.find(consumer);
  if (targetShape == state.shapes.end() || counter == state.copyIndices.end())
    return reject(consumer, "shaped assignment has no admitted storage/counter layout");
  auto rhs = snapshotAccess(source, consumer, counter->second, state);
  auto lhs = storageAccess(target, consumer, counter->second, state);
  if (failed(lhs) || failed(rhs))
    return reject(consumer, "copy map exceeds the portable target integer profile");
  const auto &index = counter->second;
  out.indent(indent) << "for (" << index << " = 0; " << index << " < "
                     << targetShape->second.count.text << "; " << index << " = " << index
                     << " + 1) begin\n";
  renderMap(*rhs, out, indent + 2);
  renderMap(*lhs, out, indent + 2);
  out.indent(indent + 2) << lhs->text << " = " << rhs->text << ";\n";
  out.indent(indent) << "end\n";
  return success();
}
struct LoopContext {
  std::string stop;
  std::string next;
};
LogicalResult renderBlock(Block &block, AnalogEventRenderState &state, llvm::raw_ostream &out,
                          unsigned indent, std::optional<LoopContext> loop = std::nullopt);
LogicalResult renderStatement(Operation *op, AnalogEventRenderState &state, llvm::raw_ostream &out,
                              unsigned indent, std::optional<LoopContext> loop) {
  if (state.declarationOnlyValues.contains(op))
    return success();
  auto line = [&](const llvm::Twine &value) { out.indent(indent) << value << "\n"; };
  if (llvm::isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(op)) {
    if (state.names.count(op)) {
      auto value = snapshotAccess(op->getResult(0), op, "0", state);
      if (failed(value))
        return reject(op, "scalar snapshot projection has no portable target map");
      renderMap(*value, out, indent);
      line(state.names[op] + " = " + value->text + ";");
    }
    return success();
  } else if (llvm::isa<AnalogArrayFillOp>(op)) {
    auto captures = readCaptures(op, op->getOperands(), state);
    auto rhs = failed(captures) ? FailureOr<std::string>(failure())
                                : expression(op, text(op, "value"), state, *captures);
    if (failed(rhs))
      return reject(op, "fill scalar cannot be captured losslessly");
    line(state.names[op] + " = " + *rhs + ";");
  } else if (auto variable = llvm::dyn_cast<AnalogVariableOp>(op)) {
    if (llvm::isa<AnalogProcedureOp>(op->getParentOp()))
      return success();
    if (op->getAttrOfType<BoolAttr>("initialized").getValue()) {
      if (text(op, "initializer_form") == "expression") {
        auto captures = readCaptures(op, op->getOperands(), state);
        auto rhs = failed(captures)
                       ? FailureOr<std::string>(failure())
                       : expression(op, text(op, "initializer_value"), state, *captures);
        if (failed(rhs))
          return reject(op, "local initializer expression cannot be captured losslessly");
        line(state.names[op] + " = " + *rhs + ";");
        return success();
      }
      if (llvm::isa<ShapedType>(op->getResult(0).getType())) {
        if (op->getNumOperands() != 1)
          return reject(op, "shaped initializer requires one typed snapshot");
        return renderArrayCopy(op, op->getResult(0), op->getOperand(0), state, out, indent);
      }
      if (op->getNumOperands() == 1) {
        auto captures = readCaptures(op, op->getOperands(), state);
        auto identity = getAnalogCapturedReadIdentity(op->getOperand(0));
        if (failed(captures) || failed(identity) || !captures->count(*identity))
          return reject(op, "scalar initializer requires its owned immutable captured read");
        line(state.names[op] + " = " + captures->lookup(*identity) + ";");
        return success();
      }
      auto rhs = expression(op, text(op, "initializer_value"), state);
      if (failed(rhs))
        return reject(op, "local initializer cannot be emitted losslessly");
      line(state.names[op] + " = " + *rhs + ";");
    }
  } else if (auto read = llvm::dyn_cast<AnalogVariableReadOp>(op)) {
    Value handle = read->getOperand(0);
    auto shaped = state.shapes.find(handle);
    if (shaped == state.shapes.end()) {
      auto value = storageAccess(handle, op, "0", state);
      if (failed(value))
        return reject(op, "scalar selection cannot be emitted losslessly");
      renderMap(*value, out, indent);
      line(state.names[op] + " = " + value->text + ";");
    } else {
      auto index = state.copyIndices.find(op);
      if (index == state.copyIndices.end())
        return reject(op, "snapshot has no owned copy counter");
      auto value = storageAccess(handle, op, index->second, state);
      if (failed(value))
        return reject(op, "snapshot source map exceeds the portable target integer profile");
      line("for (" + index->second + " = 0; " + index->second + " < " + shaped->second.count.text +
           "; " + index->second + " = " + index->second + " + 1) begin");
      renderMap(*value, out, indent + 2);
      line("  " + state.names[op] + "[" + index->second + "] = " + value->text + ";");
      line("end");
    }
  } else if (llvm::isa<AnalogAssignOp>(op)) {
    if (op->getAttrOfType<BoolAttr>("guard_present").getValue())
      return reject(op, "legacy guarded assignments require separate target legalization");
    auto analyses = op->getAttrOfType<ArrayAttr>("analyses");
    if (analyses.size() != 2 || llvm::cast<StringAttr>(analyses[0]).getValue() != "dc" ||
        llvm::cast<StringAttr>(analyses[1]).getValue() != "transient")
      return reject(op, "analysis-restricted assignment requires separate target legalization");
    if (text(op, "value_form") == "snapshot") {
      if (op->getNumOperands() != 2)
        return reject(op, "shaped assignment requires exactly one immutable RHS");
      return renderArrayCopy(op, op->getOperand(0), op->getOperand(1), state, out, indent);
    }
    auto captures = readCaptures(op, op->getOperands().drop_front(), state);
    auto rhs = failed(captures)
                   ? FailureOr<std::string>(failure())
                   : expression(op, field(op->getAttrOfType<DictionaryAttr>("metadata"), "value"),
                                state, *captures);
    auto lhs = storageAccess(op->getOperand(0), op, "0", state);
    if (failed(rhs) || failed(lhs))
      return reject(op, "assignment cannot be emitted losslessly");
    renderMap(*lhs, out, indent);
    line(lhs->text + " = " + *rhs + ";");
  } else if (isAnalogEventExpression(op)) {
    // Unused monitors still request evaluation points. Used primitives are emitted
    // once, at their owning event-controlled statement, not as numeric temporaries.
    if (op->getResult(0).use_empty()) {
      auto value = eventExpression(op, state);
      if (failed(value))
        return reject(op, "unused event cannot be emitted losslessly");
      renderEventReferenceMaps(op, state, out, indent);
      line("@(" + *value + ") begin");
      line("end");
    }
  } else if (llvm::isa<AnalogOnOp>(op)) {
    auto value = eventExpression(op->getOperand(0).getDefiningOp(), state);
    if (failed(value))
      return reject(op, "event expression cannot be emitted losslessly");
    renderEventReferenceMaps(op->getOperand(0).getDefiningOp(), state, out, indent);
    line("@(" + *value + ") begin");
    if (failed(renderBlock(op->getRegion(0).front(), state, out, indent + 2)))
      return failure();
    line("end");
  } else if (llvm::isa<AnalogScopeOp>(op)) {
    line("begin");
    if (failed(renderBlock(op->getRegion(0).front(), state, out, indent + 2, loop)))
      return failure();
    line("end");
  } else if (llvm::isa<AnalogIfOp>(op)) {
    // Coordinate setup is parameter/literal-only. Do it before the whole
    // first-match chain; numeric loads stay inside their reached predicates.
    for (Operation &arm : op->getRegion(0).front())
      renderReferenceMaps(&arm, state, out, indent);
    unsigned index = 0;
    for (Operation &arm : op->getRegion(0).front()) {
      bool otherwise = arm.getAttrOfType<BoolAttr>("is_else").getValue();
      std::string prefix = index++ ? "else " : "";
      if (!otherwise) {
        auto condition = expression(&arm, text(&arm, "condition_value"), state);
        if (failed(condition))
          return reject(&arm, "condition cannot be emitted losslessly");
        prefix += "if (" + *condition + ") ";
      }
      line(prefix + "begin");
      if (failed(renderBlock(arm.getRegion(0).front(), state, out, indent + 2, loop)))
        return failure();
      line("end");
    }
  } else if (llvm::isa<AnalogCaseOp>(op)) {
    renderReferenceMaps(op, state, out, indent);
    auto selector = expression(op, text(op, "selector_value"), state);
    if (failed(selector))
      return reject(op, "case selector cannot be emitted losslessly");
    line("case (" + *selector + ")");
    for (Operation &arm : op->getRegion(0).front()) {
      std::string labels;
      if (arm.getAttrOfType<BoolAttr>("is_default").getValue())
        labels = "default";
      else
        for (Attribute label : arm.getAttrOfType<ArrayAttr>("labels")) {
          auto value = llvm::cast<StringAttr>(label).getValue();
          auto literal = value.drop_front(value.find(':') + 1);
          auto parsed = expression(&arm, literal, state);
          if (failed(parsed))
            return reject(&arm, "case label is outside the integer target range");
          labels += (labels.empty() ? "" : ", ") + *parsed;
        }
      line(labels + ": begin");
      if (failed(renderBlock(arm.getRegion(0).front(), state, out, indent + 2, loop)))
        return failure();
      line("end");
    }
    line("endcase");
  } else if (llvm::isa<AnalogLoopOp>(op)) {
    renderReferenceMaps(op, state, out, indent);
    auto bound = expression(op, text(op, "bound_value"), state);
    if (failed(bound))
      return reject(op, "loop bound cannot be emitted losslessly");
    std::string base = state.loops[op], limit = base + "_limit", index = base + "_index";
    const auto maximum = op->getAttrOfType<IntegerAttr>("maximum_iterations").getInt();
    const auto minimum = op->getAttrOfType<IntegerAttr>("minimum_iterations").getInt();
    if (maximum > std::numeric_limits<int32_t>::max())
      return reject(op, "loop envelope exceeds the portable integer range");
    if (hasAnalogEvents(op)) {
      if (text(op, "stage") != "static")
        return reject(op, "monitors require a static generate loop");
      line("for (" + index + " = 0; " + index + " < " + *bound + "; " + index + " = " + index +
           " + 1) begin");
      if (failed(renderBlock(op->getRegion(0).front(), state, out, indent + 2)))
        return failure();
      line("end");
      return success();
    }
    LoopContext inner{base + "_break", base + "_continue"};
    line(limit + " = " + *bound + ";");
    line("if ((" + limit + " < " + std::to_string(minimum) + ") || (" + limit + " > " +
         std::to_string(maximum) + ")) begin");
    line("  $strobe(\"NODAL-ANALOG-034-008: runtime loop bound outside its declared envelope\");");
    line("  $finish;");
    line("end else begin");
    line("  " + inner.stop + " = 0;");
    line("  for (" + index + " = 0; (" + index + " < " + limit + ") && !" + inner.stop + "; " +
         index + " = " + index + " + 1) begin");
    line("    " + inner.next + " = 0;");
    if (failed(renderBlock(op->getRegion(0).front(), state, out, indent + 4, inner)))
      return failure();
    line("  end");
    line("end");
  } else if (llvm::isa<AnalogBreakOp, AnalogContinueOp>(op)) {
    if (!loop)
      return reject(op, "loop exit has no active bounded loop");
    line((llvm::isa<AnalogBreakOp>(op) ? loop->stop : loop->next) + " = 1;");
  } else
    return reject(op, "unsupported event-controlled statement");
  return success();
}
LogicalResult renderBlock(Block &block, AnalogEventRenderState &state, llvm::raw_ostream &out,
                          unsigned indent, std::optional<LoopContext> loop) {
  for (Operation &op : block) {
    if (loop)
      out.indent(indent) << "if (!" << loop->stop << " && !" << loop->next << ") begin\n";
    if (failed(renderStatement(&op, state, out, indent + (loop ? 2 : 0), loop)))
      return failure();
    if (loop)
      out.indent(indent) << "end\n";
  }
  return success();
}

LogicalResult prepareEventStorage(Operation *procedure, AnalogEventRenderState &state,
                                  llvm::raw_ostream &out, unsigned indent, bool allowLoops,
                                  bool structuralOccurrence = false) {
  if (failed(verifyAnalogProcedureTarget(procedure)))
    return failure();
  LogicalResult result = success();
  auto module = procedure->getParentOfType<ModuleOp>();
  auto admitShape = [&](Value value) -> LogicalResult {
    auto type = llvm::dyn_cast<ShapedType>(value.getType());
    if (!type || state.shapes.count(value))
      return success();
    auto layout = resolveTargetShapedLayout(module, type.getDimensions());
    if (failed(layout))
      return reject(value.getDefiningOp(),
                    "array shape/count intermediate exceeds portable signed32 indices");
    state.shapes.try_emplace(value, *layout);
    return success();
  };
  // Persistent roots have declaration lifetime. Resolve their actual uniform
  // fill before allocating runtime carriers; a pure view changes the logical
  // destination count, never the uniform scalar initializer.
  llvm::DenseSet<Operation *> initializerValues;
  for (Operation &operation : procedure->getRegion(0).front()) {
    if (!persistentShapedInitializer(&operation))
      continue;
    auto variable = llvm::dyn_cast<AnalogVariableOp>(operation);
    auto shape = llvm::cast<ShapedType>(variable.getResult().getType());
    if (procedure->hasAttr("generated_owner") && !structuralOccurrence)
      return reject(
          &operation,
          "generated root shaped initializer requires per-occurrence declaration lifetime");
    auto leaf = llvm::dyn_cast<VariableType>(shape.getElementType());
    auto source = operation.getNumOperands() == 1
                      ? resolveAnalogArrayValue(operation.getOperand(0), &operation)
                      : FailureOr<AnalogArrayValue>(failure());
    auto fill = succeeded(source) ? llvm::dyn_cast<AnalogArrayFillOp>(source->producer)
                                  : AnalogArrayFillOp();
    auto parsed = fill ? parseAnalogSourceExpression(fill, text(fill, "value"))
                       : FailureOr<AnalogSourceExpression>(failure());
    if (!leaf || leaf.getKind() != "real" || !fill || fill->getNumOperands() || failed(parsed) ||
        parsed->kind != "real" || parsed->dimension != leaf.getDimension() ||
        !isStaticAnalogSourceExpression(*parsed))
      return reject(&operation, "persistent shaped initializer requires an owned static Real fill");
    auto rendered = renderSource(*parsed, state);
    if (failed(rendered) || failed(admitShape(variable.getResult())) ||
        failed(admitShape(fill.getResult())) ||
        failed(renderShapedProjectionProgram(operation.getOperand(0), "0", "initializer_index")))
      return reject(&operation, "persistent shaped initializer exceeds the portable target layout");
    for (Operation *projection : source->projections) {
      if (failed(admitShape(projection->getResult(0))))
        return failure();
      initializerValues.insert(projection);
    }
    initializerValues.insert(fill);
    state.declarationInitializers[&operation] = *rendered;
  }
  // Peel only paths whose every actual use is a declaration initialization.
  // A shared scalar projection or ordinary copy keeps the producer materialized.
  // Use counts and a worklist make branching/deep view chains linear in edges.
  llvm::DenseMap<Operation *, unsigned> remainingUses;
  llvm::SmallVector<Operation *> ready;
  for (Operation *value : initializerValues) {
    unsigned remaining = 0;
    for (OpOperand &use : value->getResult(0).getUses())
      if (!state.declarationInitializers.contains(use.getOwner()) || use.getOperandNumber() != 0)
        ++remaining;
    remainingUses[value] = remaining;
    if (!remaining)
      ready.push_back(value);
  }
  while (!ready.empty()) {
    Operation *value = ready.pop_back_val();
    state.declarationOnlyValues.insert(value);
    for (Value operand : value->getOperands()) {
      auto found = remainingUses.find(operand.getDefiningOp());
      if (found != remainingUses.end() && --found->second == 0)
        ready.push_back(found->first);
    }
  }
  auto declareCounter = [&](Operation *op) {
    auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
    auto identity = field(metadata, "semantic_path");
    if (identity.empty())
      identity = text(op, "statement_id");
    if (identity.empty())
      identity = text(op, "read_id");
    if (identity.empty())
      identity = text(op, "identity");
    auto counter = nameFor((identity + ".copy_index").str(), state);
    state.copyIndices[op] = counter;
    out.indent(indent) << "integer " << counter << ";\n";
  };
  auto declareMaps = [&](Operation *op) {
    auto identity = field(op->getAttrOfType<DictionaryAttr>("metadata"), "semantic_path");
    if (identity.empty())
      identity = text(op, "statement_id");
    if (identity.empty())
      identity = text(op, "read_id");
    if (identity.empty())
      identity = text(op, "identity");
    auto source = nameFor((identity + ".source_map").str(), state);
    auto target = nameFor((identity + ".target_map").str(), state);
    state.mapNames[op] = {source, target};
    out.indent(indent) << "integer " << source << ";\n";
    out.indent(indent) << "integer " << target << ";\n";
  };
  procedure->walk([&](Operation *op) {
    auto owner = op->getParentOfType<AnalogProcedureOp>();
    if (failed(result) || !owner || owner.getOperation() != procedure ||
        state.declarationOnlyValues.contains(op))
      return;
    if (llvm::isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(op)) {
      if (llvm::isa<QuantityType>(op->getResult(0).getType())) {
        if (failed(resolveAnalogArrayValue(op->getResult(0), op))) {
          result = reject(op, "scalar immutable projection has no owned snapshot producer");
          return;
        }
        auto identity = field(op->getAttrOfType<DictionaryAttr>("metadata"), "semantic_path");
        auto name = nameFor(identity, state);
        state.names[op] = name;
        out.indent(indent) << "real " << name << ";\n";
        declareMaps(op);
      }
    } else if (llvm::isa<AnalogAssignOp>(op)) {
      Value target = op->getOperand(0);
      if (failed(resolveAnalogStorageReference(target, op)) || failed(admitShape(target))) {
        result = reject(op, "assignment target has no verified storage layout");
        return;
      }
      if (llvm::isa<ShapedType>(target.getType()))
        declareCounter(op);
      if (llvm::isa<ShapedType>(target.getType()) ||
          llvm::isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(target.getDefiningOp()))
        declareMaps(op);
    } else if (llvm::isa<AnalogVariableOp, AnalogVariableReadOp, AnalogArrayFillOp>(op)) {
      // Generated procedures put declarations inside their owning genvar block.
      // An ordinary monitor loop cannot silently manufacture independent state.
      if (llvm::isa<AnalogVariableOp>(op)) {
        for (Operation *scope = op->getParentOp(); scope != procedure;
             scope = scope->getParentOp()) {
          if (llvm::isa<AnalogLoopOp>(scope) && hasAnalogEvents(scope)) {
            result = reject(op, "loop-local event state requires per-generated-instance storage");
            return;
          }
        }
      }
      const bool variable = llvm::isa<AnalogVariableOp>(op);
      const bool read = llvm::isa<AnalogVariableReadOp>(op);
      const bool fill = llvm::isa<AnalogArrayFillOp>(op);
      Value value = op->getResult(0);
      if (failed(admitShape(value)) || (read && failed(admitShape(op->getOperand(0))))) {
        result = failure();
        return;
      }
      Type type = read ? op->getOperand(0).getType() : value.getType();
      auto shape = llvm::dyn_cast<ShapedType>(type);
      if (shape)
        type = shape.getElementType();
      auto storageType = llvm::dyn_cast<VariableType>(type);
      auto quantity = llvm::dyn_cast<QuantityType>(type);
      llvm::StringRef kind = storageType ? storageType.getKind()
                             : quantity  ? quantity.getKind()
                                         : llvm::StringRef();
      if (kind.empty() || (shape && kind != "real")) {
        result = reject(op, "event storage requires scalar values or shaped Real quantities");
        return;
      }
      if (read && failed(resolveAnalogStorageReference(op->getOperand(0), op))) {
        result = reject(op, "read requires an owned storage reference");
        return;
      }
      auto id = text(op, variable ? "identity" : "read_id");
      auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
      if (fill)
        id = field(metadata, "semantic_path");
      auto authored = field(metadata, "authored_path");
      auto generated = nameFor(authored.empty() ? id : authored, state);
      state.names[op] = generated;
      if (variable)
        state.variables[id] = generated;
      out.indent(indent) << (kind == "real" ? "real " : "integer ") << generated;
      if (shape && !fill) {
        auto &count = state.shapes[value].count;
        int64_t constant = 0;
        out << "[0:";
        if (!llvm::StringRef(count.text).getAsInteger(10, constant))
          out << constant - 1;
        else
          out << "(" << count.text << " - 1)";
        out << "]";
      }
      if (auto initial = state.declarationInitializers.find(op);
          initial != state.declarationInitializers.end())
        out << " = '{" << state.shapes[value].count.text << "{" << initial->second << "}}";
      if (variable && !shape && op->getParentOp() == procedure &&
          op->getAttrOfType<BoolAttr>("initialized").getValue()) {
        auto initial = parseAnalogSourceExpression(op, text(op, "initializer_value"));
        auto rendered =
            failed(initial) ? FailureOr<std::string>(failure()) : renderSource(*initial, state);
        if (failed(rendered) || !staticInitializer(*initial)) {
          result = reject(op, "persistent event-variable initializers must be static expressions");
          return;
        }
        out << " = " << *rendered;
      }
      out << ";\n";
      if (shape && !fill && !state.declarationInitializers.contains(op) &&
          (read || op->getAttrOfType<BoolAttr>("initialized").getValue()))
        declareCounter(op);
      if (!fill && !state.declarationInitializers.contains(op) &&
          (shape || (read && llvm::isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(
                                 op->getOperand(0).getDefiningOp()))))
        declareMaps(op);
    } else if (llvm::isa<AnalogLoopOp>(op)) {
      if (!allowLoops) {
        result = reject(op, "generated event storage does not admit nested procedural loops");
        return;
      }
      auto base = nameFor(text(op, "statement_id"), state);
      // Reserve all derived names as a group, including adversarial authored names.
      while (llvm::any_of(
          std::initializer_list<const char *>{"_index", "_limit", "_break", "_continue"},
          [&](const char *suffix) { return state.reserved.contains(base + suffix); }))
        base = nameFor(base, state);
      state.loops[op] = base;
      for (auto suffix : {"_index", "_limit", "_break", "_continue"}) {
        state.reserved.insert(base + suffix);
        bool generateIndex = hasAnalogEvents(op) && llvm::StringRef(suffix) == "_index";
        out.indent(indent) << (generateIndex ? "genvar " : "integer ") << base << suffix << ";\n";
      }
    }
  });
  if (failed(result))
    return result;
  // All declarations are named before resolving direct live storage accesses.
  // Binding truth remains the native per-owner operand/descriptor helper.
  procedure->walk([&](Operation *op) {
    if (failed(result) || !op->hasAttr("reference_form"))
      return;
    auto bindings = resolveAnalogSourceStorageBindings(op);
    if (failed(bindings)) {
      result = reject(op, "control/event references have no owned native storage bindings");
      return;
    }
    auto identity = field(op->getAttrOfType<DictionaryAttr>("metadata"), "semantic_path");
    for (auto key : {"arm_id", "event_id", "statement_id"})
      if (identity.empty())
        identity = text(op, key);
    for (auto [ordinal, binding] : llvm::enumerate(*bindings)) {
      auto reference = resolveAnalogStorageReference(binding.reference, op);
      if (failed(reference)) {
        result = reject(op, "live control/event leaf requires actual scalar storage");
        return;
      }
      std::string temporary;
      if (llvm::isa<ShapedType>(reference->root.getType()))
        temporary =
            nameFor((identity + ".reference_" + llvm::Twine(ordinal) + ".map").str(), state);
      auto access = storageAccess(binding.reference, op, "0", state, temporary);
      if (failed(access)) {
        result = reject(op, "live reference map exceeds the portable target integer profile");
        return;
      }
      if (!temporary.empty())
        out.indent(indent) << "integer " << temporary << ";\n";
      state.liveReferences[op].try_emplace(binding.reference, std::move(*access));
    }
  });
  return result;
}
} // namespace

bool hasPersistentShapedInitializer(Operation *procedure) {
  return llvm::isa_and_nonnull<AnalogProcedureOp>(procedure) &&
         llvm::hasSingleElement(procedure->getRegion(0)) &&
         llvm::any_of(procedure->getRegion(0).front(),
                      [](Operation &operation) { return persistentShapedInitializer(&operation); });
}

bool isAnalogEventStorageStatement(Operation *operation) {
  return llvm::isa<AnalogVariableOp, AnalogVariableReadOp, AnalogAssignOp, AnalogArrayFillOp,
                   ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(operation);
}
LogicalResult verifyAnalogEventStorageStatement(Operation *operation) {
  if (llvm::isa<AnalogVariableOp>(operation))
    return succeeded(resolveAnalogStorageReference(operation->getResult(0), operation)) ? success()
                                                                                        : failure();
  if (llvm::isa<AnalogVariableReadOp, AnalogAssignOp>(operation)) {
    if (failed(resolveAnalogStorageReference(operation->getOperand(0), operation)))
      return failure();
    if (llvm::isa<AnalogAssignOp>(operation) && text(operation, "value_form") == "snapshot")
      return operation->getNumOperands() == 2 &&
                     succeeded(resolveAnalogArrayValue(operation->getOperand(1), operation))
                 ? success()
                 : failure();
    return success();
  }
  if (llvm::isa<AnalogArrayFillOp>(operation))
    return succeeded(resolveAnalogArrayValue(operation->getResult(0), operation)) ? success()
                                                                                  : failure();
  if (llvm::isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(operation)) {
    Type type = operation->getResult(0).getType();
    if (auto shaped = llvm::dyn_cast<ShapedType>(type))
      type = shaped.getElementType();
    if (llvm::isa<VariableType>(type))
      return verifyAnalogStorageProjection(operation);
    return succeeded(resolveAnalogArrayValue(operation->getResult(0), operation)) ? success()
                                                                                  : failure();
  }
  return failure();
}

LogicalResult verifyAnalogProcedureTarget(Operation *procedure) {
  if (!llvm::isa_and_nonnull<AnalogProcedureOp>(procedure) ||
      !llvm::hasSingleElement(procedure->getRegion(0)) ||
      failed(verifyGeneratedAnalogOwnership(procedure)) ||
      failed(verifyAnalogProceduralExpressions(procedure)))
    return failure();
  const bool generated = procedure->hasAttr("generated_owner");
  const bool events = hasAnalogEvents(procedure);
  const auto owner = text(procedure, "owner");
  llvm::SmallVector<Operation *> loops;
  LogicalResult result = success();
  procedure->walk([&](Operation *op) {
    if (op == procedure || failed(result))
      return;
    const bool storage = isAnalogEventStorageStatement(op);
    const bool control =
        llvm::isa<AnalogScopeOp, AnalogIfOp, AnalogIfArmOp, AnalogCaseOp, AnalogCaseArmOp,
                  AnalogLoopOp, AnalogBreakOp, AnalogContinueOp, AnalogOnOp>(op) ||
        isAnalogEventExpression(op);
    if ((!storage && !control) || text(op, "owner") != owner) {
      result = reject(op, "procedure contains an unsupported effect or mismatched owner");
      return;
    }
    if (storage && failed(verifyAnalogEventStorageStatement(op))) {
      result = reject(op, "procedure storage has no verified native capability");
      return;
    }
    if (llvm::isa<AnalogAssignOp>(op)) {
      auto guard = op->getAttrOfType<BoolAttr>("guard_present");
      auto analyses = op->getAttrOfType<ArrayAttr>("analyses");
      if (!guard || guard.getValue() || !analyses || analyses.size() != 2 ||
          analyses[0] != StringAttr::get(op->getContext(), "dc") ||
          analyses[1] != StringAttr::get(op->getContext(), "transient"))
        result =
            reject(op, "guarded or analysis-restricted assignment requires target legalization");
    }
    if (!generated || failed(result))
      return;
    if ((storage || op->hasAttr("reference_form")) && failed(verifyGeneratedAnalogOwnership(op))) {
      result = failure();
      return;
    }
    if (llvm::isa<AnalogLoopOp>(op))
      loops.push_back(op);
    if (llvm::isa<AnalogVariableOp>(op) && op->getParentOfType<AnalogLoopOp>())
      result = reject(op, "generated loop-local declarations would create unsupported histories");
    if (!events && llvm::isa<AnalogScopeOp>(op) && op->getRegion(0).front().empty())
      result = reject(op, "generated lexical scopes require an authored storage body");
  });
  if (failed(result) || !generated)
    return result;
  if (loops.size() > 1)
    return reject(procedure, "generated procedure admits at most one nonnested runtime loop");
  auto supportedGeneratedControlRead = [&](Operation *op, llvm::StringRef inventory,
                                           llvm::StringRef source, bool allowBoolean = false) {
    if (op->hasAttr("reference_form")) {
      auto bindings = resolveAnalogSourceStorageBindings(op);
      auto value = parseAnalogSourceExpression(op, text(op, source));
      return succeeded(bindings) && succeeded(value) &&
             (value->kind == "integer" || (allowBoolean && value->kind == "boolean"));
    }
    auto reads = op->getAttrOfType<ArrayAttr>(inventory);
    auto path = reads && reads.size() == 1 ? llvm::dyn_cast<StringAttr>(reads[0]) : StringAttr();
    return llvm::any_of(procedure->getRegion(0).front(), [&](Operation &candidate) {
      auto variable = llvm::dyn_cast<AnalogVariableOp>(candidate);
      auto type =
          variable ? llvm::dyn_cast<VariableType>(variable.getResult().getType()) : VariableType();
      return path && type && type.getKind() == "integer" &&
             text(&candidate, "identity") == path.getValue();
    });
  };
  for (Operation *loop : loops) {
    if (text(loop, "stage") != "runtime" || hasAnalogEvents(loop) ||
        (events ? !llvm::isa<AnalogOnOp>(loop->getParentOp()) : loop->getParentOp() != procedure))
      return reject(loop, "generated runtime loop has unsupported event or lexical placement");
    if (!events && !supportedGeneratedControlRead(loop, "bound_reads", "bound_value"))
      return reject(loop, "generated runtime bound requires an owned Integer source");
    auto directArm = [&](Operation *op) {
      return op && llvm::isa<AnalogIfArmOp, AnalogCaseArmOp>(op) && op->getParentOp() &&
             llvm::isa<AnalogIfOp, AnalogCaseOp>(op->getParentOp()) &&
             op->getParentOp()->getParentOp() == loop;
    };
    auto storageScope = [&](Operation *parent) {
      return parent == loop || directArm(parent) ||
             (parent && llvm::isa<AnalogScopeOp>(parent) &&
              (parent->getParentOp() == loop || directArm(parent->getParentOp())));
    };
    unsigned conditionals = 0, cases = 0, scopes = 0, breaks = 0, continues = 0, armExits = 0;
    loop->walk([&](Operation *op) {
      if (op == loop || failed(result))
        return;
      Operation *parent = op->getParentOp();
      if (llvm::isa<AnalogIfOp, AnalogCaseOp>(op)) {
        if (parent != loop || (llvm::isa<AnalogIfOp>(op) ? ++conditionals > 1 : ++cases > 1) ||
            (!events && (conditionals && cases)) ||
            (!events && llvm::isa<AnalogCaseOp>(op) &&
             !supportedGeneratedControlRead(op, "selector_reads", "selector_value", true)))
          result = reject(op, "generated loop controls must be direct and nonnested");
        return;
      }
      if (llvm::isa<AnalogIfArmOp, AnalogCaseArmOp>(op)) {
        if (!directArm(op))
          result = reject(op, "generated loop arm is outside its direct control");
        return;
      }
      if (llvm::isa<AnalogScopeOp>(op)) {
        bool hasStorage = llvm::any_of(op->getRegion(0).front(), [](Operation &nested) {
          return llvm::isa<AnalogVariableReadOp, AnalogAssignOp, AnalogArrayFillOp>(nested);
        });
        if (++scopes > 1 || (parent != loop && !directArm(parent)) || (!events && !hasStorage))
          result = reject(op, "generated loop admits one direct or arm-local storage scope");
        return;
      }
      if (llvm::isa<AnalogBreakOp, AnalogContinueOp>(op)) {
        const bool scoped = llvm::isa<AnalogScopeOp>(parent);
        const bool inArm = directArm(scoped ? parent->getParentOp() : parent);
        if (!storageScope(parent) || (events && scoped && parent->getParentOp() == loop) ||
            (!events && (llvm::isa<AnalogBreakOp>(op) ? ++breaks > 1 : ++continues > 1)) ||
            (inArm && (!events || scoped) && ++armExits > 1))
          result = reject(op, "generated loop exit exceeds its supported lexical control profile");
        return;
      }
      if (!isAnalogEventStorageStatement(op) || llvm::isa<AnalogVariableOp>(op) ||
          !storageScope(parent))
        result = reject(op, "generated runtime loop body has unsupported storage or effects");
    });
    if (failed(result))
      return result;
  }
  return success();
}

LogicalResult prepareAnalogEventBackend(Operation *module, AnalogEventRenderState &state,
                                        llvm::raw_ostream &out) {
  module->walk([&](Operation *op) {
    for (auto key : {"name", "sym_name", "induction"})
      if (!text(op, key).empty())
        state.reserved.insert(text(op, key));
  });
  LogicalResult result = success();
  module->walk([&](AnalogProcedureOp procedure) {
    if (failed(result) || procedure->hasAttr("generated_owner"))
      return;
    result = prepareEventStorage(procedure.getOperation(), state, out, 2, true);
  });
  return result;
}

LogicalResult prepareGeneratedAnalogEventBackend(Operation *procedure,
                                                 AnalogEventRenderState &state,
                                                 llvm::raw_ostream &out, unsigned indent,
                                                 GeneratedAnalogDeclarationContext context) {
  auto eventProcedure = llvm::dyn_cast_or_null<AnalogProcedureOp>(procedure);
  if (!eventProcedure || !procedure->hasAttr("generated_owner") ||
      !procedure->hasAttr("generated_induction"))
    return reject(procedure, "generated storage requires one owned analog procedure");
  const bool structuralOccurrence =
      context.kind == GeneratedAnalogDeclarationContext::Kind::StructuralOccurrence;
  if (structuralOccurrence) {
    auto analog = llvm::dyn_cast_or_null<AnalogOp>(procedure->getParentOp());
    auto generate = llvm::dyn_cast_or_null<GenerateOp>(context.scope);
    if (!analog || !generate || analog->getParentOp() != generate.getOperation() ||
        failed(verifyGeneratedAnalogOwnership(analog)) ||
        failed(verifyGeneratedAnalogOwnership(procedure)) ||
        failed(resolveGeneratedDomain(generate)))
      return reject(procedure,
                    "structural storage declaration requires its actual generated occurrence");
  } else if (context.scope) {
    return reject(procedure, "analog-iteration storage cannot claim a structural occurrence");
  }
  if (auto module = procedure->getParentOfType<ModuleOp>())
    module->walk([&](Operation *op) {
      for (auto key : {"name", "sym_name", "induction"})
        if (!text(op, key).empty())
          state.reserved.insert(text(op, key));
    });
  return prepareEventStorage(procedure, state, out, indent, true, structuralOccurrence);
}
LogicalResult renderAnalogEventProcedure(Operation *procedure, AnalogEventRenderState &state,
                                         llvm::raw_ostream &out) {
  if (failed(verifyAnalogProcedureTarget(procedure)))
    return failure();
  out << "    begin : " << nameFor((text(procedure, "owner") + ".procedure").str(), state) << "\n";
  if (failed(renderBlock(procedure->getRegion(0).front(), state, out, 6)))
    return failure();
  out << "    end\n";
  return success();
}
} // namespace nodal
