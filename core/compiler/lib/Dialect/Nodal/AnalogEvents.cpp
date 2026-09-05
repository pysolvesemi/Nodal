#include "nodal/Dialect/Nodal/AnalogEvents.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinTypes.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/Regex.h"

#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <optional>
#include <set>
#include <string>
#include <vector>

using namespace mlir;

namespace nodal {
namespace {
llvm::StringRef text(Operation *op, llvm::StringRef key) {
  if (auto value = op->getAttrOfType<StringAttr>(key))
    return value.getValue();
  return {};
}
llvm::StringRef field(DictionaryAttr attrs, llvm::StringRef key) {
  if (auto value = attrs.getAs<StringAttr>(key))
    return value.getValue();
  return {};
}
LogicalResult reject(Operation *op, unsigned number, llvm::StringRef message) {
  std::string suffix = std::to_string(number);
  std::string code = "NODAL-ANALOG-037-" + std::string(3 - suffix.size(), '0') + suffix;
  return emitMappedFailure(op, code, message);
}
bool identifier(llvm::StringRef value) {
  if (value.empty() || !(llvm::isAlpha(value.front()) || value.front() == '_'))
    return false;
  return llvm::all_of(value, [](char c) { return llvm::isAlnum(c) || c == '_'; });
}
std::optional<std::string> unitDimension(llvm::StringRef unit) {
  if (unit.empty())
    return "1";
  if (unit == "s")
    return "time";
  if (unit == "V")
    return "voltage";
  if (unit == "A")
    return "current";
  if (unit == "Ohm")
    return "current^-1*voltage";
  if (unit == "F")
    return "current*time*voltage^-1";
  return std::nullopt;
}
std::string semanticPath(Operation *op) {
  if (auto metadata = op->getAttrOfType<DictionaryAttr>("metadata"))
    if (auto path = metadata.getAs<StringAttr>("semantic_path"))
      return path.getValue().str();
  return {};
}
using Expression = AnalogSourceExpression;

// Parse the canonical source expression grammar, NOT HDL text. This boundary
// independently derives dimensions, reads and constants from actual declarations.
// In particular, parameter defaults never become constant-expression evidence.
class ExpressionParser {
public:
  explicit ExpressionParser(Operation *operation)
      : operation(operation), module(operation->getParentOfType<ModuleOp>()) {}

  FailureOr<Expression> parse(llvm::StringRef input, unsigned depth = 0) {
    if (depth > 128 || input.empty() || input != input.trim())
      return failure();
    if (input == "true" || input == "false")
      return Expression{"boolean", "1", input == "true" ? 1.0 : 0.0, {}, "literal", input.str()};
    std::string storage = input.str();
    char *end = nullptr;
    errno = 0;
    double value = std::strtod(storage.c_str(), &end);
    if (end != storage.c_str()) {
      llvm::StringRef tail(end);
      auto dimension = unitDimension(tail.trim());
      if (dimension && (tail.empty() || tail.front() == ' ')) {
        if (errno == ERANGE || !std::isfinite(value)) {
          (void)reject(operation, 4, "event constant arithmetic must remain finite");
          return failure();
        }
        auto numeric = input.take_front(static_cast<size_t>(end - storage.c_str()));
        if (!llvm::Regex("^[+-]?[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$").match(numeric))
          return failure();
        bool real = numeric.contains('.') || numeric.contains('e') || numeric.contains('E') ||
                    !tail.empty();
        if (!real) {
          int64_t integer = 0;
          if (numeric.getAsInteger(10, integer))
            return failure();
        }
        return Expression{
            real ? "real" : "integer", *dimension, value, {}, "literal", numeric.str()};
      }
    }
    size_t open = input.find('(');
    if (open == llvm::StringRef::npos)
      return reference(input);
    if (!input.ends_with(")"))
      return failure();
    llvm::StringRef function = input.take_front(open);
    llvm::StringRef body = input.slice(open + 1, input.size() - 1);
    std::vector<llvm::StringRef> pieces;
    unsigned nesting = 0;
    size_t start = 0;
    for (size_t i = 0; i < body.size(); ++i) {
      if (body[i] == '(')
        ++nesting;
      else if (body[i] == ')') {
        if (nesting == 0)
          return failure();
        --nesting;
      } else if (body[i] == ',' && nesting == 0) {
        pieces.push_back(body.slice(start, i));
        start = i + 1;
      }
    }
    if (nesting != 0)
      return failure();
    pieces.push_back(body.drop_front(start));
    if (function == "potential_access" || function == "flow_access") {
      if (pieces.empty() || pieces.size() > 2)
        return failure();
      std::vector<Expression> terminals;
      for (auto piece : pieces) {
        Operation *terminal = declaration(piece);
        if (!terminal || !llvm::isa<TerminalOp, NodeOp>(terminal) || terminal->getNumResults() != 1)
          return failure();
        auto type = llvm::dyn_cast<TerminalType>(terminal->getResult(0).getType());
        if (!type || type.getDiscipline() != "electrical")
          return failure();
        terminals.push_back(
            Expression{"terminal", "", std::nullopt, {}, "reference", "", terminal});
      }
      return Expression{"real",         function == "potential_access" ? "voltage" : "current",
                        std::nullopt,   {},
                        function.str(), "",
                        nullptr,        std::move(terminals)};
    }
    if (pieces.empty() || pieces.size() > 3)
      return failure();
    std::vector<Expression> args;
    std::set<std::string> reads;
    for (auto piece : pieces) {
      auto child = parse(piece, depth + 1);
      if (failed(child))
        return failure();
      reads.insert(child->reads.begin(), child->reads.end());
      args.push_back(*child);
    }
    const auto &left = args[0];
    Expression result{left.kind, left.dimension, std::nullopt, reads, function.str(),
                      "",        nullptr,        args};
    if (function == "analog_select" && args.size() == 3) {
      if (left.kind != "boolean" || left.dimension != "1" || args[1].kind != args[2].kind ||
          args[1].dimension != args[2].dimension)
        return failure();
      result.kind = args[1].kind;
      result.dimension = args[1].dimension;
      if (left.constant)
        result.constant = args[*left.constant != 0.0 ? 1 : 2].constant;
    } else if (function == "bool_not" && args.size() == 1 && left.kind == "boolean") {
      if (left.constant)
        result.constant = *left.constant == 0.0 ? 1.0 : 0.0;
    } else if (function == "analog_neg" && args.size() == 1 && left.kind == "real") {
      if (left.constant)
        result.constant = -*left.constant;
    } else if (args.size() == 2) {
      const auto &right = args[1];
      if ((function == "bool_and" || function == "bool_or") && left.kind == "boolean" &&
          right.kind == "boolean") {
        if (left.constant && right.constant)
          result.constant = function == "bool_and"
                                ? (*left.constant != 0.0 && *right.constant != 0.0)
                                : (*left.constant != 0.0 || *right.constant != 0.0);
      } else if (left.kind == "real" && right.kind == "real") {
        if (function == "analog_add" || function == "analog_sub") {
          if (left.dimension != right.dimension)
            return failure();
          if (left.constant && right.constant)
            result.constant = function == "analog_add" ? *left.constant + *right.constant
                                                       : *left.constant - *right.constant;
        } else if (function == "analog_mul" || function == "analog_div") {
          auto dimension =
              combineAnalogDimensions(left.dimension, right.dimension, function == "analog_div");
          if (failed(dimension))
            return failure();
          result.dimension = *dimension;
          if (function == "analog_div" && right.constant && *right.constant == 0.0) {
            (void)reject(operation, 4, "source expression has a provably zero divisor");
            return failure();
          }
          if (left.constant && right.constant)
            result.constant = function == "analog_mul" ? *left.constant * *right.constant
                                                       : *left.constant / *right.constant;
        } else if (function == "real_gt" || function == "real_ge" || function == "real_lt" ||
                   function == "real_le") {
          if (left.dimension != right.dimension)
            return failure();
          result.kind = "boolean";
          result.dimension = "1";
          if (left.constant && right.constant) {
            const double a = *left.constant, b = *right.constant;
            result.constant = function == "real_gt"   ? a > b
                              : function == "real_ge" ? a >= b
                              : function == "real_lt" ? a < b
                                                      : a <= b;
          }
        } else
          return failure();
      } else
        return failure();
    } else
      return failure();
    if (result.constant && !std::isfinite(*result.constant)) {
      (void)reject(operation, 4, "source constant arithmetic must remain finite");
      return failure();
    }
    return result;
  }

private:
  Operation *operation;
  ModuleOp module;

  Operation *declaration(llvm::StringRef path) {
    Operation *found = nullptr;
    unsigned matches = 0;
    if (!module)
      return nullptr;
    module->walk([&](Operation *candidate) {
      if (candidate->getParentOfType<ModuleOp>() != module ||
          !llvm::isa<ParameterOp, TerminalOp, NodeOp, AnalogVariableOp>(candidate))
        return;
      auto id = text(candidate, "identity");
      if ((!id.empty() && id == path) ||
          (!semanticPath(candidate).empty() && semanticPath(candidate) == path)) {
        found = candidate;
        ++matches;
      }
    });
    return matches == 1 ? found : nullptr;
  }
  FailureOr<Expression> reference(llvm::StringRef path) {
    Operation *decl = declaration(path);
    if (!decl)
      return failure();
    if (auto variable = llvm::dyn_cast<AnalogVariableOp>(decl)) {
      auto type = llvm::dyn_cast<VariableType>(variable->getResult(0).getType());
      if (!type)
        return failure();
      return Expression{type.getKind().str(),
                        type.getDimension().str(),
                        std::nullopt,
                        {path.str()},
                        "reference",
                        "",
                        decl};
    }
    if (llvm::isa<ParameterOp>(decl)) {
      auto dimension = unitDimension(getParameterUnitSymbol(decl));
      if (!dimension)
        return failure();
      auto kind = text(decl, "parameter_kind");
      if (kind.empty()) {
        auto type = decl->getAttrOfType<TypeAttr>("type");
        if (!type)
          return failure();
        if (type.getValue().isInteger(1))
          kind = "boolean";
        else if (llvm::isa<FloatType>(type.getValue()))
          kind = "real";
        else if (llvm::isa<IntegerType>(type.getValue()))
          kind = "integer";
        else
          return failure();
      }
      // Legacy bridge unit metadata is canonical but is not a constant value.
      if (auto metadata = decl->getAttrOfType<DictionaryAttr>("metadata")) {
        auto unit = metadata.getAs<StringAttr>("unit");
        if (unit && getParameterUnitSymbol(decl).empty())
          dimension = unitDimension(unit.getValue());
      }
      if (!dimension)
        return failure();
      return Expression{kind.str(), *dimension, std::nullopt, {}, "reference", "", decl};
    }
    return failure();
  }
};

FailureOr<std::set<std::string>> strings(ArrayAttr array) {
  if (!array)
    return failure();
  std::set<std::string> result;
  for (auto entry : array) {
    auto value = llvm::dyn_cast<StringAttr>(entry);
    if (!value || !result.insert(value.getValue().str()).second)
      return failure();
  }
  return result;
}
bool monitor(Operation *op) { return llvm::isa<AnalogCrossOp, AnalogAboveOp, AnalogTimerOp>(op); }
LogicalResult context(Operation *op) {
  auto procedure = op->getParentOfType<AnalogProcedureOp>();
  auto module = op->getParentOfType<ModuleOp>();
  if (!procedure || !module)
    return reject(op, 1, "analog event requires an analog procedural region");
  std::string owner = semanticPath(module);
  if (owner.empty())
    owner = text(module, "sym_name").str();
  if (text(op, "owner") != owner || text(procedure, "owner") != owner)
    return reject(op, 5, "analog event owner differs from the enclosing component");
  bool underRuntime = false;
  for (Operation *parent = op->getParentOp(); parent != procedure; parent = parent->getParentOp()) {
    if (!parent || llvm::isa<AnalogOnOp>(parent))
      return reject(op, 7, "analog event controls and monitors cannot be nested in event bodies");
    if (auto conditional = llvm::dyn_cast<AnalogIfArmOp>(parent)) {
      auto group = conditional->getParentOp();
      for (auto &arm : group->getRegion(0).front())
        underRuntime |= text(&arm, "stage") == "runtime";
    }
    if (llvm::isa<AnalogCaseArmOp>(parent)) {
      auto present = parent->getParentOp()->getAttrOfType<BoolAttr>("static_value_present");
      underRuntime |= !present || !present.getValue();
    }
    if (llvm::isa<AnalogLoopOp>(parent))
      underRuntime |= text(parent, "stage") != "static";
  }
  if (underRuntime && monitor(op))
    return reject(op, 1, "history-bearing analog monitor is nested under runtime control");
  return success();
}
} // namespace

FailureOr<AnalogSourceExpression> parseAnalogSourceExpression(Operation *context,
                                                              llvm::StringRef source) {
  return ExpressionParser(context).parse(source);
}

bool isStaticAnalogSourceExpression(const AnalogSourceExpression &value) {
  if (value.operation == "literal")
    return true;
  if (value.operation == "reference")
    return value.declaration && llvm::isa<ParameterOp>(value.declaration);
  if (value.operation == "potential_access" || value.operation == "flow_access")
    return false;
  return !value.operands.empty() && llvm::all_of(value.operands, isStaticAnalogSourceExpression);
}

FailureOr<Operation *> resolveAnalogHeldVariable(Operation *op) {
  auto module = op->getParentOfType<nodal::ModuleOp>();
  if (!module || !llvm::isa<AnalogOp>(op->getParentOp()))
    return failure();
  std::string owner = semanticPath(module.getOperation());
  if (owner.empty())
    owner = text(module, "sym_name").str();
  if (text(op, "owner") != owner)
    return failure();
  Operation *variable = nullptr;
  unsigned matches = 0;
  module.walk([&](AnalogVariableOp declaration) {
    if (declaration->getParentOfType<nodal::ModuleOp>() == module &&
        text(declaration, "identity") == text(op, "variable")) {
      variable = declaration.getOperation();
      ++matches;
    }
  });
  if (matches != 1 || !llvm::isa<AnalogProcedureOp>(variable->getParentOp()) ||
      text(variable, "owner") != text(op, "owner"))
    return failure();
  auto type = llvm::dyn_cast<VariableType>(variable->getResult(0).getType());
  auto initialized = variable->getAttrOfType<BoolAttr>("initialized");
  if (!type || type.getKind() != "real" || !initialized || !initialized.getValue())
    return failure();
  auto initial = parseAnalogSourceExpression(variable, text(variable, "initializer_value"));
  auto initialReads = variable->getAttrOfType<ArrayAttr>("initializer_reads");
  if (failed(initial) || !isStaticAnalogSourceExpression(*initial) || !initial->reads.empty() ||
      !initialReads || !initialReads.empty() || initial->dimension != type.getDimension() ||
      initial->kind != text(variable, "initializer_kind") ||
      initial->dimension != text(variable, "initializer_dimension"))
    return failure();
  bool valid = true;
  unsigned writes = 0;
  for (Operation *user : variable->getResult(0).getUsers()) {
    if (llvm::isa<AnalogVariableReadOp>(user))
      continue;
    if (!llvm::isa<AnalogAssignOp>(user) || user->getOperand(0).getDefiningOp() != variable ||
        !user->getParentOfType<AnalogOnOp>()) {
      valid = false;
      continue;
    }
    ++writes;
  }
  return valid && writes ? FailureOr<Operation *>(variable) : FailureOr<Operation *>(failure());
}

LogicalResult verifyAnalogHeldRead(Operation *op) {
  if (op->getNumResults() != 1 || !op->getResult(0).getType().isF64() ||
      failed(resolveAnalogHeldVariable(op)))
    return reject(
        op, 9,
        "continuous variable read requires initialized, root-local, event-only real storage");
  return success();
}

bool hasAnalogEvents(Operation *op) {
  bool found = false;
  op->walk([&](Operation *child) {
    found |= llvm::isa<AnalogOnOp>(child) || isAnalogEventExpression(child);
  });
  return found;
}

// The older procedural model retains source expression strings. Before they can
// cross the event backend boundary, independently bind and type every payload.
LogicalResult verifyAnalogEventProcedure(Operation *procedure) {
  if (!hasAnalogEvents(procedure))
    return success();
  LogicalResult result = success();
  procedure->walk([&](Operation *op) {
    if (failed(result))
      return;
    llvm::StringRef source, kind, dimension;
    ArrayAttr inventory;
    std::optional<double> claimedStatic;
    std::set<std::string> operandReads;
    bool assignment = false;
    if (llvm::isa<AnalogVariableOp>(op)) {
      auto initialized = op->getAttrOfType<BoolAttr>("initialized");
      if (!initialized) {
        result = reject(op, 10, "missing initialized flag");
        return;
      }
      if (!initialized.getValue())
        return;
      source = text(op, "initializer_value");
      kind = text(op, "initializer_kind");
      dimension = text(op, "initializer_dimension");
      inventory = op->getAttrOfType<ArrayAttr>("initializer_reads");
    } else if (llvm::isa<AnalogAssignOp>(op)) {
      auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
      source = metadata ? field(metadata, "value") : llvm::StringRef();
      kind = text(op, "value_kind");
      dimension = text(op, "value_dimension");
      assignment = true;
      for (auto value : op->getOperands().drop_front()) {
        auto read = value.getDefiningOp<AnalogVariableReadOp>();
        auto variable =
            read ? read->getOperand(0).getDefiningOp<AnalogVariableOp>() : AnalogVariableOp();
        if (!variable || !operandReads.insert(text(variable, "identity").str()).second) {
          result = reject(op, 10, "assignment reads must bind unique variable-read operations");
          return;
        }
      }
    } else if (llvm::isa<AnalogIfArmOp>(op)) {
      auto otherwise = op->getAttrOfType<BoolAttr>("is_else");
      if (!otherwise) {
        result = reject(op, 10, "missing condition arm kind");
        return;
      }
      if (otherwise.getValue())
        return;
      source = text(op, "condition_value");
      kind = text(op, "condition_kind");
      dimension = text(op, "condition_dimension");
      inventory = op->getAttrOfType<ArrayAttr>("condition_reads");
      if (text(op, "stage") == "static") {
        auto value = op->getAttrOfType<BoolAttr>("static_value");
        if (!value) {
          result = reject(op, 10, "missing static condition proof");
          return;
        }
        claimedStatic = value.getValue() ? 1.0 : 0.0;
      }
    } else if (llvm::isa<AnalogCaseOp>(op)) {
      source = text(op, "selector_value");
      kind = text(op, "selector_kind");
      dimension = text(op, "selector_dimension");
      inventory = op->getAttrOfType<ArrayAttr>("selector_reads");
    } else if (llvm::isa<AnalogLoopOp>(op)) {
      source = text(op, "bound_value");
      kind = text(op, "bound_kind");
      dimension = text(op, "bound_dimension");
      inventory = op->getAttrOfType<ArrayAttr>("bound_reads");
      if (text(op, "stage") == "static") {
        auto value = op->getAttrOfType<IntegerAttr>("static_trip_count");
        if (!value) {
          result = reject(op, 10, "missing static loop proof");
          return;
        }
        claimedStatic = value.getInt();
      }
    } else
      return;
    auto expression = parseAnalogSourceExpression(op, source);
    if (failed(expression) || expression->kind != kind || expression->dimension != dimension) {
      result = reject(op, 10, "procedural source expression does not match its scalar contract");
      return;
    }
    if (llvm::isa<AnalogCaseOp>(op) && !op->getAttrOfType<BoolAttr>("static_value_present")) {
      result = reject(op, 10, "missing static selector flag");
      return;
    }
    if (llvm::isa<AnalogCaseOp>(op) &&
        op->getAttrOfType<BoolAttr>("static_value_present").getValue()) {
      auto claimed = text(op, "static_value");
      auto label = claimed.split(':');
      auto literal = parseAnalogSourceExpression(op, label.second);
      if (label.first != expression->kind || failed(literal) || !expression->constant ||
          literal->constant != expression->constant) {
        result = reject(op, 10, "static case selector differs from its source expression");
        return;
      }
    }
    auto reads = assignment ? FailureOr<std::set<std::string>>(operandReads) : strings(inventory);
    if (failed(reads) || *reads != expression->reads ||
        (claimedStatic && expression->constant != claimedStatic))
      result = reject(op, 10, "procedural expression reads or static value were forged");
  });
  return result;
}

bool isAnalogEventExpression(Operation *op) {
  return llvm::isa<AnalogCrossOp, AnalogAboveOp, AnalogTimerOp, AnalogInitialStepOp,
                   AnalogFinalStepOp, AnalogEventOrOp>(op);
}

LogicalResult verifyAnalogEventOperation(Operation *op) {
  if (failed(context(op)))
    return failure();
  auto owner = text(op, "owner");
  auto id = text(op, llvm::isa<AnalogOnOp>(op) ? "statement_id" : "event_id");
  if (!id.starts_with((owner + ".").str()) || id.size() <= owner.size() + 1)
    return reject(op, 2, "event identity must be owner-qualified");
  if (llvm::isa<AnalogOnOp>(op)) {
    if (op->getNumRegions() != 1 || op->getRegion(0).getBlocks().size() != 1)
      return reject(op, 7, "event-controlled statement requires one body block");
    Operation *event = op->getOperand(0).getDefiningOp();
    if (!event || !isAnalogEventExpression(event) || event->getBlock() != op->getBlock() ||
        !event->isBeforeInBlock(op) || text(event, "owner") != owner)
      return reject(op, 5, "event control requires a dominating local analog event expression");
    return success();
  }
  if (!op->getResult(0).use_empty() && !op->getResult(0).hasOneUse())
    return reject(op, 2, "an analog event occurrence must have one owning use");
  if (op->getResult(0).hasOneUse()) {
    Operation *consumer = *op->getResult(0).getUsers().begin();
    std::set<Operation *> seen;
    while (llvm::isa<AnalogEventOrOp>(consumer) && consumer->getNumResults() == 1 &&
           consumer->getResult(0).hasOneUse() && seen.insert(consumer).second)
      consumer = *consumer->getResult(0).getUsers().begin();
    if (consumer->getBlock() != op->getBlock() || !op->isBeforeInBlock(consumer))
      return reject(op, 5, "event consumer must follow its occurrence in the same block");
    auto inventory = op->getAttrOfType<ArrayAttr>("event_reads");
    if (inventory && !inventory.empty()) {
      auto observed = strings(inventory);
      if (failed(observed))
        return reject(op, 5, "invalid event read inventory");
      bool changed = false;
      for (Operation *between = op->getNextNode(); between != consumer;
           between = between->getNextNode())
        between->walk([&](AnalogAssignOp assignment) {
          if (assignment->getNumOperands())
            if (auto variable = assignment->getOperand(0).getDefiningOp<AnalogVariableOp>())
              changed |= observed->count(text(variable, "identity").str()) != 0;
        });
      if (changed)
        return reject(op, 5, "event expression cannot move across a write to observed storage");
    }
  }
  if (text(op, "contract") != "increment37" ||
      (!text(op, "name").empty() && !identifier(text(op, "name"))))
    return reject(op, 2, "invalid event contract or semantic name");
  for (NamedAttribute attr : op->getAttrs())
    if (attr.getName().getValue().starts_with("nodal.folded") ||
        attr.getName().getValue().starts_with("nodal.simplif"))
      return reject(op, 2, "event observations and histories cannot be constant-folded");
  if (llvm::isa<AnalogEventOrOp>(op)) {
    if (op->getNumOperands() < 2)
      return reject(op, 6, "event OR requires at least two events");
    for (Value value : op->getOperands()) {
      Operation *child = value.getDefiningOp();
      if (!child || !isAnalogEventExpression(child) || text(child, "owner") != owner ||
          child->getBlock() != op->getBlock() || !child->isBeforeInBlock(op))
        return reject(op, 6, "event OR requires dominating local analog event operands");
    }
    return success();
  }
  auto arguments = op->getAttrOfType<ArrayAttr>("arguments");
  auto analyses = op->getAttrOfType<ArrayAttr>("analyses");
  auto readInventory = strings(op->getAttrOfType<ArrayAttr>("event_reads"));
  if (!arguments || !analyses || failed(readInventory))
    return reject(op, 2, "invalid event inventory");
  bool lifecycle = llvm::isa<AnalogInitialStepOp, AnalogFinalStepOp>(op);
  if (lifecycle) {
    auto filters = strings(analyses);
    if (!arguments.empty() || failed(filters) || !readInventory->empty())
      return reject(op, 8, "lifecycle events accept only unique analysis filters");
    for (const auto &filter : *filters)
      if (!identifier(filter) || !llvm::isAlpha(filter.front()))
        return reject(op, 8, "analysis filters must start with an alphabetic character");
    return success();
  }
  if (!analyses.empty())
    return reject(op, 8, "monitor events do not accept analysis filters");
  bool cross = llvm::isa<AnalogCrossOp>(op);
  bool above = llvm::isa<AnalogAboveOp>(op);
  unsigned maximum = cross ? 5 : 4;
  if (arguments.empty() || arguments.size() > maximum)
    return reject(op, 2, "invalid analog event argument count");
  ExpressionParser parser(op);
  std::set<std::string> allReads;
  std::string monitoredDimension;
  for (unsigned i = 0; i < arguments.size(); ++i) {
    auto arg = llvm::dyn_cast<DictionaryAttr>(arguments[i]);
    auto slot = arg ? arg.getAs<IntegerAttr>("slot") : IntegerAttr();
    if (!slot || slot.getInt() != i)
      return reject(op, 2, "event arguments must retain contiguous authored slots");
    auto expression = parser.parse(field(arg, "value"));
    if (failed(expression))
      return reject(op, 9, "event source expression is not in the supported canonical grammar");
    auto reads = strings(arg.getAs<ArrayAttr>("reads"));
    if (failed(reads) || *reads != expression->reads)
      return reject(op, 5, "event argument read inventory differs from its expression");
    allReads.insert(reads->begin(), reads->end());
    bool integer = cross ? (i == 1 || i == 4) : i == 3;
    bool timing = cross ? i == 2 : above ? i == 1 : i <= 2;
    bool tolerance = cross ? (i == 2 || i == 3) : above ? (i == 1 || i == 2) : i == 2;
    bool valueTolerance = cross ? i == 3 : above && i == 2;
    if (i == 0)
      monitoredDimension = expression->dimension;
    if (expression->kind != (integer ? "integer" : "real") ||
        field(arg, "kind") != expression->kind ||
        field(arg, "dimension") != expression->dimension ||
        (integer && expression->dimension != "1") ||
        (timing && expression->dimension != "time" &&
         !(expression->dimension == "1" && expression->constant == 0.0)) ||
        (valueTolerance && expression->dimension != monitoredDimension))
      return reject(op, 3, "event scalar kind or physical dimension mismatch");
    if (tolerance && expression->constant && *expression->constant < 0.0)
      return reject(op, 4, "event tolerances must be nonnegative; zero selects simulator defaults");
  }
  if (allReads != *readInventory)
    return reject(op, 5, "event read inventory is incomplete");
  return success();
}
} // namespace nodal
