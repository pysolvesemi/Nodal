#include "nodal/Backend/AnalogEventBackend.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogEvents.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"

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
                                    const Captures &captures = Captures()) {
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
    auto rendered = renderSource(child, state, captures);
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
                        : renderSource(*parsed, state, captures);
}
bool staticInitializer(const AnalogSourceExpression &value) {
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
struct LoopContext {
  std::string stop;
  std::string next;
};
LogicalResult renderBlock(Block &block, AnalogEventRenderState &state, llvm::raw_ostream &out,
                          unsigned indent, std::optional<LoopContext> loop = std::nullopt);
LogicalResult renderStatement(Operation *op, AnalogEventRenderState &state, llvm::raw_ostream &out,
                              unsigned indent, std::optional<LoopContext> loop) {
  auto line = [&](const llvm::Twine &value) { out.indent(indent) << value << "\n"; };
  if (auto variable = llvm::dyn_cast<AnalogVariableOp>(op)) {
    if (llvm::isa<AnalogProcedureOp>(op->getParentOp()))
      return success();
    if (op->getAttrOfType<BoolAttr>("initialized").getValue()) {
      auto rhs = expression(op, text(op, "initializer_value"), state);
      if (failed(rhs))
        return reject(op, "local initializer cannot be emitted losslessly");
      line(state.names[op] + " = " + *rhs + ";");
    }
  } else if (auto read = llvm::dyn_cast<AnalogVariableReadOp>(op)) {
    auto variable = read->getOperand(0).getDefiningOp();
    line(state.names[op] + " = " + state.names[variable] + ";");
  } else if (llvm::isa<AnalogAssignOp>(op)) {
    if (op->getAttrOfType<BoolAttr>("guard_present").getValue())
      return reject(op, "legacy guarded assignments require separate target legalization");
    auto analyses = op->getAttrOfType<ArrayAttr>("analyses");
    if (analyses.size() != 2 || llvm::cast<StringAttr>(analyses[0]).getValue() != "dc" ||
        llvm::cast<StringAttr>(analyses[1]).getValue() != "transient")
      return reject(op, "analysis-restricted assignment requires separate target legalization");
    Captures captures;
    for (Value value : op->getOperands().drop_front()) {
      auto read = value.getDefiningOp<AnalogVariableReadOp>();
      captures[read->getOperand(0).getDefiningOp()] = state.names[read];
    }
    auto rhs = expression(op, field(op->getAttrOfType<DictionaryAttr>("metadata"), "value"), state,
                          captures);
    if (failed(rhs))
      return reject(op, "assignment cannot be emitted losslessly");
    line(state.names[op->getOperand(0).getDefiningOp()] + " = " + *rhs + ";");
  } else if (isAnalogEventExpression(op)) {
    // Unused monitors still request evaluation points. Used primitives are emitted
    // once, at their owning event-controlled statement, not as numeric temporaries.
    if (op->getResult(0).use_empty()) {
      auto value = eventExpression(op, state);
      if (failed(value))
        return reject(op, "unused event cannot be emitted losslessly");
      line("@(" + *value + ") begin");
      line("end");
    }
  } else if (llvm::isa<AnalogOnOp>(op)) {
    auto value = eventExpression(op->getOperand(0).getDefiningOp(), state);
    if (failed(value))
      return reject(op, "event expression cannot be emitted losslessly");
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
} // namespace

LogicalResult prepareAnalogEventBackend(Operation *module, AnalogEventRenderState &state,
                                        llvm::raw_ostream &out) {
  module->walk([&](Operation *op) {
    for (auto key : {"name", "sym_name"})
      if (!text(op, key).empty())
        state.reserved.insert(text(op, key));
  });
  LogicalResult result = success();
  module->walk([&](Operation *op) {
    if (failed(result))
      return;
    auto procedure = op->getParentOfType<AnalogProcedureOp>();
    if (!procedure || !hasAnalogEvents(procedure))
      return;
    if (llvm::isa<AnalogVariableOp, AnalogVariableReadOp>(op)) {
      auto id = text(op, llvm::isa<AnalogVariableOp>(op) ? "identity" : "read_id");
      auto type = llvm::isa<AnalogVariableOp>(op)
                      ? llvm::cast<VariableType>(op->getResult(0).getType())
                      : llvm::cast<VariableType>(op->getOperand(0).getType());
      auto authored = field(op->getAttrOfType<DictionaryAttr>("metadata"), "authored_path");
      auto generated = nameFor(authored.empty() ? id : authored, state);
      state.names[op] = generated;
      if (llvm::isa<AnalogVariableOp>(op))
        state.variables[id] = generated;
      out << "  " << (type.getKind() == "real" ? "real " : "integer ") << generated;
      if (llvm::isa<AnalogVariableOp>(op) && op->getParentOp() == procedure.getOperation() &&
          op->getAttrOfType<BoolAttr>("initialized").getValue()) {
        auto value = parseAnalogSourceExpression(op, text(op, "initializer_value"));
        auto rendered =
            failed(value) ? FailureOr<std::string>(failure()) : renderSource(*value, state);
        if (failed(rendered) || !staticInitializer(*value)) {
          result = reject(op, "persistent event-variable initializers must be static expressions");
          return;
        }
        out << " = " << *rendered;
      }
      out << ";\n";
    } else if (llvm::isa<AnalogLoopOp>(op)) {
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
        out << (generateIndex ? "  genvar " : "  integer ") << base << suffix << ";\n";
      }
    }
  });
  return result;
}
LogicalResult renderAnalogEventProcedure(Operation *procedure, AnalogEventRenderState &state,
                                         llvm::raw_ostream &out) {
  if (!hasAnalogEvents(procedure))
    return reject(procedure, "ordinary procedure lowering remains gated");
  if (failed(verifyAnalogEventProcedure(procedure)))
    return failure();
  out << "    begin : " << nameFor((text(procedure, "owner") + ".procedure").str(), state) << "\n";
  if (failed(renderBlock(procedure->getRegion(0).front(), state, out, 6)))
    return failure();
  out << "    end\n";
  return success();
}
} // namespace nodal
