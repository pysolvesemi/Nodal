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
  std::string code = "NODAL-ANALOG-037-00" + std::to_string(number);
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
struct Expression {
  std::string kind;
  std::string dimension;
  std::optional<double> constant;
  std::set<std::string> reads;
};

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
        bool real = numeric.contains('.') || numeric.contains('e') || numeric.contains('E') ||
                    !tail.empty();
        return Expression{real ? "real" : "integer", *dimension, value, {}};
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
      for (auto piece : pieces) {
        Operation *terminal = declaration(piece);
        if (!terminal || !llvm::isa<TerminalOp, NodeOp>(terminal) || terminal->getNumResults() != 1)
          return failure();
        auto type = llvm::dyn_cast<TerminalType>(terminal->getResult(0).getType());
        if (!type || type.getDiscipline() != "electrical")
          return failure();
      }
      return Expression{
          "real", function == "potential_access" ? "voltage" : "current", std::nullopt, {}};
    }
    if (pieces.empty() || pieces.size() > 2)
      return failure();
    auto left = parse(pieces[0], depth + 1);
    if (failed(left) || left->kind != "real")
      return failure();
    if (function == "analog_neg" && pieces.size() == 1) {
      if (left->constant)
        left->constant = -*left->constant;
      return *left;
    }
    if (pieces.size() != 2)
      return failure();
    auto right = parse(pieces[1], depth + 1);
    if (failed(right) || right->kind != "real")
      return failure();
    Expression result{"real", left->dimension, std::nullopt, left->reads};
    result.reads.insert(right->reads.begin(), right->reads.end());
    if (function == "analog_add" || function == "analog_sub") {
      if (left->dimension != right->dimension)
        return failure();
      if (left->constant && right->constant)
        result.constant = function == "analog_add" ? *left->constant + *right->constant
                                                   : *left->constant - *right->constant;
    } else if (function == "analog_mul" || function == "analog_div") {
      auto dimension =
          combineAnalogDimensions(left->dimension, right->dimension, function == "analog_div");
      if (failed(dimension))
        return failure();
      result.dimension = *dimension;
      if (function == "analog_div" && right->constant && *right->constant == 0.0) {
        (void)reject(operation, 4, "event expression has a provably zero divisor");
        return failure();
      }
      if (left->constant && right->constant)
        result.constant = function == "analog_mul" ? *left->constant * *right->constant
                                                   : *left->constant / *right->constant;
    } else
      return failure();
    if (result.constant && !std::isfinite(*result.constant)) {
      (void)reject(operation, 4, "event constant arithmetic must remain finite");
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
      return Expression{
          type.getKind().str(), type.getDimension().str(), std::nullopt, {path.str()}};
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
      return Expression{kind.str(), *dimension, std::nullopt, {}};
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
