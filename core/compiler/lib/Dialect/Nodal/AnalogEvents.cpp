#include "nodal/Dialect/Nodal/AnalogEvents.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinTypes.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogFunctions.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/AnalogStorageModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/Regex.h"

#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <functional>
#include <map>
#include <memory>
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

bool storageBindingOwner(Operation *operation) {
  return llvm::isa<AnalogIfArmOp, AnalogCaseOp, AnalogLoopOp, AnalogCrossOp, AnalogAboveOp,
                   AnalogTimerOp, AnalogInitialStepOp, AnalogFinalStepOp>(operation);
}

// Parse the canonical source expression grammar, NOT HDL text. This boundary
// independently derives dimensions, reads and constants from actual declarations.
// In particular, parameter defaults never become constant-expression evidence.
class ExpressionParser {
public:
  explicit ExpressionParser(Operation *operation)
      : operation(operation), module(operation->getParentOfType<ModuleOp>()) {
    if (storageBindingOwner(operation)) {
      auto bindings = resolveAnalogSourceStorageBindings(operation);
      validBindings = succeeded(bindings);
      if (validBindings)
        storageBindings = std::move(*bindings);
      storageForm = operation->hasAttr("reference_form");
    }
  }

  ExpressionParser(Operation *operation, llvm::SmallVector<AnalogSourceStorageBinding> bindings,
                   std::function<FailureOr<Expression>(Value)> deferred)
      : operation(operation), module(operation->getParentOfType<ModuleOp>()),
        storageBindings(std::move(bindings)), storageForm(true), deferred(std::move(deferred)) {}

  FailureOr<Expression> parse(llvm::StringRef input, unsigned depth = 0) {
    if (!validBindings || depth > 128 || input.empty() || input != input.trim())
      return failure();
    for (const auto &binding : storageBindings) {
      if (binding.path != input)
        continue;
      if (llvm::isa<AnalogDeferredType>(binding.reference.getType()))
        return deferred ? deferred(binding.reference) : FailureOr<Expression>(failure());
      auto type = llvm::cast<VariableType>(binding.reference.getType());
      Expression result{type.getKind().str(),
                        type.getDimension().str(),
                        std::nullopt,
                        {input.str()},
                        "reference",
                        "",
                        binding.reference.getDefiningOp()};
      result.storageReference = binding.reference;
      return result;
    }
    // Captured selected leaves are bound to actual operand SSA before parsing
    // source function syntax. A label may contain parentheses but is never a
    // textual description from which storage coordinates can be manufactured.
    if (llvm::isa<AnalogAssignOp, AnalogArrayFillOp, AnalogVariableOp>(operation)) {
      auto captures = operation->getOperands();
      if (llvm::isa<AnalogAssignOp>(operation))
        captures = captures.drop_front();
      Value matched;
      for (Value capture : captures) {
        auto path = getAnalogCapturedReadPath(capture);
        if (succeeded(path) && *path == input) {
          if (matched)
            return failure();
          matched = capture;
        }
      }
      if (matched) {
        auto identity = getAnalogCapturedReadIdentity(matched);
        auto type = getAnalogNumericTypeInfo(matched.getType());
        if (failed(identity) || failed(type))
          return failure();
        const std::string kind = type->kind == AnalogNumericKind::Real      ? "real"
                                 : type->kind == AnalogNumericKind::Integer ? "integer"
                                 : type->kind == AnalogNumericKind::Boolean ? "boolean"
                                                                            : "";
        if (kind.empty())
          return failure();
        return Expression{kind, type->dimension, std::nullopt, {input.str()}, "reference",
                          "",   *identity};
      }
    }
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
    if (function.starts_with(analogAnalysisSourcePrefix)) {
      auto id = function.drop_front(analogAnalysisSourcePrefix.size());
      if (!body.empty() || !lookupAnalogAnalysis(id))
        return failure();
      return Expression{"boolean", "1", std::nullopt, {}, function.str(), "", nullptr, {}};
    }
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
    if (function.starts_with(analogFunctionSourcePrefix)) {
      auto *entry = lookupAnalogFunction(function.drop_front(analogFunctionSourcePrefix.size()));
      if (!entry || args.size() != entry->arity)
        return failure();
      llvm::SmallVector<std::string> dimensions;
      llvm::SmallVector<std::optional<double>> constants;
      for (const auto &argument : args) {
        if (argument.kind != "real")
          return failure();
        dimensions.push_back(argument.dimension);
        constants.push_back(argument.constant);
      }
      auto dimension = analogFunctionDimension(*entry, dimensions);
      auto evaluated = evaluateAnalogFunction(*entry, constants);
      if (failed(dimension) || failed(evaluated))
        return failure();
      result.dimension = *dimension;
      result.constant = *evaluated;
    } else if (function == "analog_select" && args.size() == 3) {
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
  llvm::SmallVector<AnalogSourceStorageBinding> storageBindings;
  bool validBindings = true;
  bool storageForm = false;
  std::function<FailureOr<Expression>(Value)> deferred;

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
      // Typed source form has no mutable textual fallback. Every mutable leaf
      // must have matched a verified actual operand above.
      if (storageForm)
        return failure();
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
  auto generated = op->getParentOfType<GenerateOp>();
  auto generatedOwner = op->getAttrOfType<StringAttr>("generated_owner");
  auto generatedInduction = op->getAttrOfType<StringAttr>("generated_induction");
  if (static_cast<bool>(generatedOwner) != static_cast<bool>(generatedInduction))
    return op->emitOpError(
        "NODAL-ITERATION-043-004: generated event ownership requires paired owner and induction "
        "identities");
  if (!generated) {
    if (generatedOwner)
      return op->emitOpError(
          "NODAL-ITERATION-043-004: ordinary analog event cannot claim generated ownership");
  } else {
    auto regionId = generated->getAttrOfType<StringAttr>("region_id");
    auto inductionPath = generated->getAttrOfType<StringAttr>("induction_path");
    auto procedureOwner = procedure->getAttrOfType<StringAttr>("generated_owner");
    auto procedureInduction = procedure->getAttrOfType<StringAttr>("generated_induction");
    if (!generatedOwner || !regionId || !inductionPath || !procedureOwner || !procedureInduction ||
        generatedOwner.getValue() != regionId.getValue() ||
        generatedInduction.getValue() != inductionPath.getValue() ||
        procedureOwner.getValue() != regionId.getValue() ||
        procedureInduction.getValue() != inductionPath.getValue())
      return op->emitOpError(
          "NODAL-ITERATION-043-004: generated event ownership or induction does not match its "
          "immediate generated procedure");
  }
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

struct ValueLess {
  bool operator()(Value left, Value right) const {
    return std::less<const void *>()(left.getAsOpaquePointer(), right.getAsOpaquePointer());
  }
};
using ValueSet = std::set<Value, ValueLess>;

bool liveControl(Operation *op) {
  return llvm::isa_and_nonnull<AnalogIfArmOp, AnalogCaseOp, AnalogLoopOp>(op);
}
bool liveForm(Operation *op) { return text(op, "reference_form") == "live"; }

// An unused outer event is still a complete event expression. Derive the
// terminal by actual SSA users; labels never authorize ownership or reuse.
FailureOr<Operation *> eventConsumer(Operation *op) {
  std::set<Operation *> seen;
  while (op && isAnalogEventExpression(op)) {
    if (!seen.insert(op).second || op->getNumResults() != 1)
      return failure();
    auto value = op->getResult(0);
    if (value.use_empty())
      return op;
    if (!value.hasOneUse())
      return failure();
    auto *next = *value.getUsers().begin();
    if (!llvm::isa<AnalogEventOrOp, AnalogOnOp>(next) || next->getBlock() != op->getBlock() ||
        !op->isBeforeInBlock(next))
      return failure();
    op = next;
  }
  return llvm::isa_and_nonnull<AnalogOnOp>(op) ? FailureOr<Operation *>(op)
                                               : FailureOr<Operation *>(failure());
}

class LiveSourceSession {
public:
  explicit LiveSourceSession(Operation *consumer) {
    program.consumer = consumer;
    if (auto module = consumer->getParentOfType<ModuleOp>())
      module->walk([&](Operation *candidate) {
        if (candidate->getParentOfType<ModuleOp>() != module ||
            !llvm::isa<ParameterOp, TerminalOp, NodeOp, AnalogVariableOp, AnalogLiveFillOp,
                       ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(candidate))
          return;
        if (llvm::isa<AnalogVariableOp, AnalogLiveFillOp, ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(
                candidate) &&
            candidate->getParentOfType<AnalogProcedureOp>() !=
                consumer->getParentOfType<AnalogProcedureOp>())
          return;
        auto identity = text(candidate, "identity");
        if (!identity.empty())
          declarations[identity.str()].insert(candidate);
        auto path = semanticPath(candidate);
        if (!path.empty())
          declarations[path].insert(candidate);
      });
  }

  FailureOr<AnalogLiveSourceProgram> resolve() {
    auto procedure = program.consumer->getParentOfType<AnalogProcedureOp>();
    auto module = program.consumer->getParentOfType<ModuleOp>();
    auto owner = module ? semanticPath(module) : std::string();
    if (module && owner.empty())
      owner = text(module, "sym_name").str();
    if (!procedure || !module || text(program.consumer, "owner") != owner ||
        text(procedure, "owner") != owner || failed(verifyGeneratedAnalogOwnership(procedure)) ||
        failed(verifyGeneratedAnalogOwnership(program.consumer)))
      return fail("live consumer requires its actual lexical/generated procedure owner");
    if (liveControl(program.consumer)) {
      if (!liveForm(program.consumer))
        return fail("live control requires explicit live reference form");
      llvm::StringRef attribute = llvm::isa<AnalogIfArmOp>(program.consumer)  ? "condition_value"
                                  : llvm::isa<AnalogCaseOp>(program.consumer) ? "selector_value"
                                                                              : "bound_value";
      if (failed(addRoots(program.consumer, attribute)))
        return failure();
    } else {
      Operation *event = program.consumer;
      if (llvm::isa<AnalogOnOp>(event)) {
        if (event->getNumOperands() != 1)
          return fail("live event statement requires one actual event");
        event = event->getOperand(0).getDefiningOp();
      }
      if (failed(addEvent(event)))
        return failure();
    }
    if (program.fills.empty())
      return fail("live consumer requires a deferred dependency");
    for (const auto &fill : program.fills) {
      Operation *definition = fill.reference.getDefiningOp();
      if (fill.reference.use_empty())
        return fail("live fill cannot be orphaned");
      for (Operation *user : fill.reference.getUsers()) {
        if (llvm::isa<AnalogLiveFillOp>(user)) {
          if (user->getNumResults() != 1 || !completed.count(user->getResult(0)))
            return fail("live fill escapes its complete consumer graph");
        } else if (!rootOwners.count(user))
          return fail("live fill has another consumer or an illegal escaping use");
      }
      if (definition->getParentOfType<AnalogProcedureOp>() !=
              program.consumer->getParentOfType<AnalogProcedureOp>() ||
          !analogPrecedesLexically(definition, program.consumer))
        return fail("live fill does not dominate its actual complete consumer");
    }
    if (!liveControl(program.consumer) && failed(checkEventBudget()))
      return failure();
    return std::move(program);
  }

private:
  AnalogLiveSourceProgram program;
  std::map<Value, Expression, ValueLess> completed;
  ValueSet active;
  std::map<std::string, Value> labels;
  std::map<std::string, std::set<Operation *>> declarations;
  std::set<Operation *> rootOwners;
  std::set<Operation *> eventNodes;
  ValueSet storageSeen;

  FailureOr<AnalogLiveSourceProgram> fail(llvm::StringRef message) {
    (void)reject(program.consumer, 10, message);
    return failure();
  }
  LogicalResult error(Operation *op, llvm::StringRef message) { return reject(op, 10, message); }
  LogicalResult label(Operation *use, llvm::StringRef name, Value reference) {
    if (name.empty() || name != name.trim())
      return error(use, "live binding label must be nonempty and canonical");
    auto found = declarations.find(name.str());
    if (found != declarations.end() &&
        (found->second.size() != 1 || !found->second.count(reference.getDefiningOp())))
      return error(use, "live label conflicts with another actual declaration");
    auto [it, inserted] = labels.emplace(name.str(), reference);
    if (!inserted && it->second != reference)
      return error(use, "live binding label identifies conflicting actual definitions");
    return success();
  }
  FailureOr<llvm::SmallVector<AnalogSourceStorageBinding>> bindings(Operation *op) {
    llvm::SmallVector<AnalogSourceStorageBinding> result;
    ValueSet seen;
    bool hasDeferred = false;
    auto procedure = op->getParentOfType<AnalogProcedureOp>();
    auto module = op->getParentOfType<ModuleOp>();
    auto owner = module ? semanticPath(module) : std::string();
    if (module && owner.empty())
      owner = text(module, "sym_name").str();
    if (!procedure || !module || text(op, "owner") != owner ||
        text(op, "owner") != text(procedure, "owner") || failed(verifyGeneratedAnalogOwnership(op)))
      return failure();
    if (!llvm::isa<AnalogLiveFillOp>(op)) {
      auto otherwise = op->getAttrOfType<BoolAttr>("is_else");
      auto selected = op->getAttrOfType<BoolAttr>("static_value_present");
      if (!liveForm(op) || (otherwise && otherwise.getValue()) || text(op, "stage") == "static" ||
          (selected && selected.getValue()) ||
          llvm::isa<AnalogInitialStepOp, AnalogFinalStepOp>(op))
        return failure();
    }
    for (Value reference : op->getOperands()) {
      if (!seen.insert(reference).second)
        return failure();
      Operation *definition = reference.getDefiningOp();
      std::string name;
      if (llvm::isa<AnalogDeferredType>(reference.getType())) {
        hasDeferred = true;
        if (!llvm::isa_and_nonnull<AnalogLiveFillOp>(definition) || definition == op ||
            definition->getParentOfType<AnalogProcedureOp>() != procedure ||
            definition->getParentOfType<GenerateOp>() != op->getParentOfType<GenerateOp>() ||
            !analogPrecedesLexically(definition, op))
          return failure();
        name = text(definition, "identity").str();
      } else {
        if (!llvm::isa<VariableType>(reference.getType()) ||
            failed(resolveAnalogStorageReference(reference, op)) ||
            failed(resolveAnalogStorageReference(reference, program.consumer)))
          return failure();
        name = llvm::isa<AnalogVariableOp>(definition) ? text(definition, "identity").str()
                                                       : semanticPath(definition);
        if (storageSeen.insert(reference).second)
          program.storageBindings.push_back({name, reference});
      }
      if (failed(label(op, name, reference)))
        return failure();
      result.push_back({name, reference});
    }
    if (!llvm::isa<AnalogLiveFillOp>(op) && !hasDeferred)
      return failure();
    return result;
  }
  void directReferences(const Expression &expression, ValueSet &used) {
    if (expression.storageReference)
      used.insert(expression.storageReference);
    if (expression.deferredReference)
      used.insert(expression.deferredReference);
    for (const auto &child : expression.operands)
      directReferences(child, used);
  }
  FailureOr<Expression> deferred(Value reference) {
    if (auto found = completed.find(reference); found != completed.end())
      return found->second;
    if (active.size() >= 128 || !active.insert(reference).second)
      return failure();
    Operation *op = reference.getDefiningOp();
    auto type = llvm::dyn_cast<AnalogDeferredType>(reference.getType());
    if (!type || type.getKind() != "real" || !isCanonicalDimensionSignature(type.getDimension()) ||
        !llvm::isa_and_nonnull<AnalogLiveFillOp>(op) || op->getNumResults() != 1 ||
        op->getNumRegions() || !op->getAttrOfType<DictionaryAttr>("metadata") ||
        op->hasAttr("reads") || op->hasAttr("reference_form") || op->hasAttr("static_value") ||
        op->hasAttr("uniform"))
      return failure();
    for (NamedAttribute attribute : op->getAttrs()) {
      auto name = attribute.getName().getValue();
      if (name != "identity" && name != "value" && name != "owner" && name != "generated_owner" &&
          name != "generated_induction" && name != "metadata")
        return failure();
    }
    auto owner = text(op, "owner");
    auto id = text(op, "identity");
    if (owner.empty() || !id.starts_with((owner + ".").str()) || id.size() <= owner.size() + 1 ||
        failed(label(op, id, reference)))
      return failure();
    auto direct = bindings(op);
    if (failed(direct))
      return failure();
    ExpressionParser parser(op, std::move(*direct), [&](Value v) { return deferred(v); });
    auto body = parser.parse(text(op, "value"));
    if (failed(body) || body->kind != "real" || body->dimension != type.getDimension())
      return failure();
    ValueSet used;
    directReferences(*body, used);
    if (used != ValueSet(op->operand_begin(), op->operand_end()))
      return failure();
    // The leaf carries transitive reads, but never embeds or copies its body.
    Expression leaf{"real",      type.getDimension().str(), std::nullopt,
                    body->reads, "deferred_reference",      id.str(),
                    op};
    leaf.deferredReference = reference;
    completed.emplace(reference, leaf);
    program.fills.push_back({reference, std::move(*body)});
    active.erase(reference);
    return leaf;
  }
  LogicalResult addRoots(Operation *op, llvm::StringRef attribute) {
    rootOwners.insert(op);
    auto direct = liveForm(op) ? bindings(op) : resolveAnalogSourceStorageBindings(op);
    if (failed(direct))
      return error(op, "live direct references violate ownership, type, form, or uniqueness");
    if (!liveForm(op))
      for (const auto &binding : *direct) {
        if (failed(label(op, binding.path, binding.reference)) ||
            failed(resolveAnalogStorageReference(binding.reference, program.consumer)))
          return failure();
        if (storageSeen.insert(binding.reference).second)
          program.storageBindings.push_back(binding);
      }
    std::unique_ptr<ExpressionParser> parser;
    if (liveForm(op))
      parser = std::make_unique<ExpressionParser>(op, std::move(*direct),
                                                  [&](Value v) { return deferred(v); });
    else
      parser = std::make_unique<ExpressionParser>(op);
    ValueSet used;
    auto add = [&](llvm::StringRef value, std::optional<unsigned> slot) -> LogicalResult {
      auto expression = parser->parse(value);
      if (failed(expression))
        return error(op, "live expression or fill body violates the canonical scalar contract");
      DictionaryAttr argument;
      llvm::StringRef kind, dimension;
      ArrayAttr inventory;
      if (slot) {
        argument = llvm::cast<DictionaryAttr>(op->getAttrOfType<ArrayAttr>("arguments")[*slot]);
        auto authoredSlot = argument.getAs<IntegerAttr>("slot");
        if (!authoredSlot || authoredSlot.getInt() != *slot)
          return error(op, "live event arguments require exact authored slots");
        kind = field(argument, "kind");
        dimension = field(argument, "dimension");
        inventory = argument.getAs<ArrayAttr>("reads");
      } else {
        llvm::StringRef prefix = attribute == "condition_value"  ? "condition"
                                 : attribute == "selector_value" ? "selector"
                                                                 : "bound";
        kind = text(op, (prefix + "_kind").str());
        dimension = text(op, (prefix + "_dimension").str());
        inventory = op->getAttrOfType<ArrayAttr>((prefix + "_reads").str());
      }
      auto reads = strings(inventory);
      if (failed(reads) || *reads != expression->reads || kind != expression->kind ||
          dimension != expression->dimension)
        return error(op,
                     "live source type or transitive read inventory differs from its expression");
      directReferences(*expression, used);
      program.roots.push_back({op, attribute.str(), slot, std::move(*expression)});
      return success();
    };
    if (attribute == "arguments") {
      auto arguments = op->getAttrOfType<ArrayAttr>(attribute);
      if (!arguments)
        return failure();
      unsigned slot = 0;
      for (auto entry : arguments) {
        auto argument = llvm::dyn_cast<DictionaryAttr>(entry);
        if (!argument || failed(add(field(argument, "value"), slot++)))
          return failure();
      }
    } else if (failed(add(text(op, attribute), std::nullopt)))
      return failure();
    if (attribute == "arguments") {
      std::set<std::string> allReads;
      for (const auto &root : program.roots)
        if (root.operation == op)
          allReads.insert(root.expression.reads.begin(), root.expression.reads.end());
      auto claimed = strings(op->getAttrOfType<ArrayAttr>("event_reads"));
      if (failed(claimed) || *claimed != allReads)
        return error(op, "live event read inventory differs from its actual arguments");
    }
    if (liveForm(op) && used != ValueSet(op->operand_begin(), op->operand_end()))
      return error(op, "live direct operand inventory differs from actual used bindings");
    return success();
  }
  LogicalResult addEvent(Operation *outer) {
    llvm::SmallVector<Operation *> pending{outer};
    while (!pending.empty()) {
      Operation *event = pending.pop_back_val();
      if (!event || !isAnalogEventExpression(event) || !eventNodes.insert(event).second ||
          event->getParentOfType<AnalogProcedureOp>() !=
              program.consumer->getParentOfType<AnalogProcedureOp>() ||
          text(event, "owner") != text(program.consumer, "owner") ||
          failed(verifyGeneratedAnalogOwnership(event)))
        return error(program.consumer,
                     "live event graph has invalid, cyclic, or reused occurrences");
      auto terminal = eventConsumer(event);
      if (failed(terminal) || *terminal != program.consumer)
        return error(event, "live event graph must have one actual complete consumer");
      if (llvm::isa<AnalogEventOrOp>(event)) {
        if (event->getNumOperands() < 2)
          return failure();
        for (Value child : llvm::reverse(event->getOperands()))
          pending.push_back(child.getDefiningOp());
      } else if (failed(addRoots(event, "arguments")))
        return failure();
    }
    return success();
  }
  LogicalResult checkEventBudget() {
    struct Cost {
      uint64_t wrappers = 0, bytes = 0;
    };
    std::map<Value, Cost, ValueLess> costs;
    auto add = [](uint64_t a, uint64_t b, uint64_t cap) {
      return a > cap || b > cap || a > cap - b ? cap + 1 : a + b;
    };
    std::function<Cost(const Expression &)> cost = [&](const Expression &expression) {
      Cost result;
      if (expression.deferredReference) {
        result = costs.at(expression.deferredReference);
        result.wrappers = add(result.wrappers, 1, analogLiveEventWrapperLimit);
        // Includes private helper call/conditional punctuation and identifier
        // allowance. Backend additionally accounts exact allocated names.
        result.bytes =
            add(result.bytes, 256 + expression.spelling.size() * 4, analogLiveEventByteLimit);
        return result;
      }
      result.bytes = 32 + expression.spelling.size() + expression.operation.size();
      if (expression.declaration)
        result.bytes = add(result.bytes,
                           text(expression.declaration, "identity").size() * 2 +
                               semanticPath(expression.declaration).size() * 2,
                           analogLiveEventByteLimit);
      for (const auto &child : expression.operands) {
        auto nested = cost(child);
        result.wrappers = add(result.wrappers, nested.wrappers, analogLiveEventWrapperLimit);
        result.bytes = add(result.bytes, nested.bytes, analogLiveEventByteLimit);
      }
      return result;
    };
    for (const auto &fill : program.fills)
      costs.emplace(fill.reference, cost(fill.body));
    for (const auto &root : program.roots) {
      auto value = cost(root.expression);
      program.eventDemandWrappers =
          add(program.eventDemandWrappers, value.wrappers, analogLiveEventWrapperLimit);
      program.eventExpressionBytes =
          add(program.eventExpressionBytes, value.bytes, analogLiveEventByteLimit);
    }
    for (const auto &fill : program.fills)
      program.eventExpressionBytes =
          add(program.eventExpressionBytes,
              256 + text(fill.reference.getDefiningOp(), "identity").size() * 4,
              analogLiveEventByteLimit);
    if (program.eventDemandWrappers > analogLiveEventWrapperLimit ||
        program.eventExpressionBytes > analogLiveEventByteLimit)
      return error(program.consumer, "live event resource limit exceeded before expansion");
    return success();
  }
};

FailureOr<Operation *> fillConsumer(Operation *definition, std::map<Operation *, Operation *> &memo,
                                    std::set<Operation *> &active) {
  if (auto found = memo.find(definition); found != memo.end())
    return found->second;
  if (!llvm::isa_and_nonnull<AnalogLiveFillOp>(definition) || definition->getNumResults() != 1 ||
      definition->getResult(0).use_empty() || active.size() >= 128 ||
      !active.insert(definition).second)
    return failure();
  Operation *consumer = nullptr;
  for (Operation *user : definition->getResult(0).getUsers()) {
    FailureOr<Operation *> candidate = failure();
    if (llvm::isa<AnalogLiveFillOp>(user))
      candidate = fillConsumer(user, memo, active);
    else if (liveControl(user) && liveForm(user))
      candidate = user;
    else if (monitor(user) && liveForm(user))
      candidate = eventConsumer(user);
    if (failed(candidate) || (consumer && consumer != *candidate))
      return failure();
    consumer = *candidate;
  }
  active.erase(definition);
  memo.emplace(definition, consumer);
  return consumer;
}
} // namespace

FailureOr<AnalogLiveSourceProgram> resolveAnalogLiveSourceProgram(Operation *consumer) {
  if (!consumer)
    return failure();
  if (isAnalogEventExpression(consumer)) {
    auto terminal = eventConsumer(consumer);
    if (failed(terminal))
      return failure();
    consumer = *terminal;
  }
  return LiveSourceSession(consumer).resolve();
}

LogicalResult verifyAnalogLiveFillOperation(Operation *definition) {
  std::map<Operation *, Operation *> memo;
  std::set<Operation *> active;
  auto consumer = fillConsumer(definition, memo, active);
  if (failed(consumer))
    return reject(definition, 10,
                  "live fill requires one actual ultimate control or event consumer");
  auto program = resolveAnalogLiveSourceProgram(*consumer);
  if (failed(program))
    return failure();
  return success();
}

LogicalResult verifyAnalogLiveModuleResources(Operation *module) {
  std::set<Operation *> consumers;
  uint64_t wrappers = 0, bytes = 0;
  LogicalResult result = success();
  module->walk([&](Operation *op) {
    if (failed(result) || op->getParentOfType<ModuleOp>().getOperation() != module)
      return;
    for (Type type : op->getResultTypes())
      if (llvm::isa<AnalogDeferredType>(type) && !llvm::isa<AnalogLiveFillOp>(op)) {
        result = reject(op, 10, "only analog_live_fill may define a deferred capability");
        return;
      }
    for (Region &region : op->getRegions())
      for (Block &block : region)
        for (BlockArgument argument : block.getArguments())
          if (llvm::isa<AnalogDeferredType>(argument.getType())) {
            result = reject(op, 10, "deferred capabilities cannot be block arguments");
            return;
          }
    if (!monitor(op) || !liveForm(op))
      return;
    auto consumer = eventConsumer(op);
    if (failed(consumer)) {
      result = reject(op, 10, "live event requires one actual complete consumer");
      return;
    }
    if (!consumers.insert(*consumer).second)
      return;
    auto program = resolveAnalogLiveSourceProgram(*consumer);
    if (failed(program)) {
      result = failure();
      return;
    }
    wrappers += program->eventDemandWrappers;
    bytes += program->eventExpressionBytes;
    if (consumers.size() == 1)
      bytes += 1024; // One private helper ABI/body, checked again by the backend.
    if (wrappers > analogLiveModuleWrapperLimit || bytes > analogLiveModuleByteLimit)
      result = reject(op, 10, "module live event resource limit exceeded before expansion");
  });
  return result;
}

FailureOr<llvm::SmallVector<AnalogSourceStorageBinding>>
resolveAnalogSourceStorageBindings(Operation *operation) {
  if (!operation || !storageBindingOwner(operation))
    return failure();
  auto fail =
      [&](llvm::StringRef message) -> FailureOr<llvm::SmallVector<AnalogSourceStorageBinding>> {
    (void)reject(operation, 10, message);
    return failure();
  };
  llvm::SmallVector<AnalogSourceStorageBinding> result;
  if (liveForm(operation)) {
    auto program = resolveAnalogLiveSourceProgram(operation);
    if (failed(program))
      return failure();
    std::set<std::string> reads;
    for (const auto &root : program->roots)
      if (root.operation == operation)
        reads.insert(root.expression.reads.begin(), root.expression.reads.end());
    for (const auto &binding : program->storageBindings)
      if (reads.count(binding.path))
        result.push_back(binding);
    return result;
  }
  auto form = operation->getAttrOfType<StringAttr>("reference_form");
  if (!operation->hasAttr("reference_form")) {
    if (operation->getNumOperands())
      return fail("storage reference operands require explicit storage reference form");
    return result;
  }
  if (!form || form.getValue() != "storage")
    return fail("unknown procedural storage reference form");
  auto otherwise = operation->getAttrOfType<BoolAttr>("is_else");
  if (llvm::isa<AnalogInitialStepOp, AnalogFinalStepOp>(operation) ||
      (otherwise && otherwise.getValue()))
    return fail("lifecycle events and else arms cannot bind storage references");
  auto staticSelector = operation->getAttrOfType<BoolAttr>("static_value_present");
  if (operation->getNumOperands() &&
      (text(operation, "stage") == "static" ||
       (llvm::isa<AnalogCaseOp>(operation) && staticSelector && staticSelector.getValue())))
    return fail("static controls cannot bind mutable storage references");
  std::set<std::string> paths;
  for (Value reference : operation->getOperands()) {
    if (!llvm::isa<VariableType>(reference.getType()) ||
        failed(resolveAnalogStorageReference(reference, operation)))
      return fail("control reference requires an actual owned scalar storage handle");
    Operation *definition = reference.getDefiningOp();
    std::string path = llvm::isa<AnalogVariableOp>(definition) ? text(definition, "identity").str()
                                                               : semanticPath(definition);
    if (path.empty() || path != llvm::StringRef(path).trim() || !paths.insert(path).second)
      return fail("control storage reference labels must be canonical and unique");
    result.push_back({std::move(path), reference});
  }
  return result;
}

FailureOr<AnalogSourceExpression> parseAnalogSourceExpression(Operation *context,
                                                              llvm::StringRef source) {
  if (llvm::isa<AnalogLiveFillOp>(context)) {
    std::map<Operation *, Operation *> memo;
    std::set<Operation *> active;
    auto consumer = fillConsumer(context, memo, active);
    if (failed(consumer) || text(context, "value") != source)
      return failure();
    auto program = resolveAnalogLiveSourceProgram(*consumer);
    if (failed(program))
      return failure();
    for (const auto &fill : program->fills)
      if (fill.reference.getDefiningOp() == context)
        return fill.body;
    return failure();
  }
  if (liveForm(context)) {
    auto program = resolveAnalogLiveSourceProgram(context);
    if (failed(program))
      return failure();
    for (const auto &root : program->roots) {
      if (root.operation != context)
        continue;
      llvm::StringRef value;
      if (root.argumentSlot) {
        auto arguments = context->getAttrOfType<ArrayAttr>("arguments");
        value = field(llvm::cast<DictionaryAttr>(arguments[*root.argumentSlot]), "value");
      } else
        value = text(context, root.attribute);
      if (value == source)
        return root.expression;
    }
    return failure();
  }
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

FailureOr<Operation *> getAnalogCapturedReadIdentity(Value value) {
  if (failed(getAnalogNumericTypeInfo(value.getType())))
    return failure();
  Operation *definition = value.getDefiningOp();
  if (auto read = llvm::dyn_cast_or_null<AnalogVariableReadOp>(definition)) {
    if (failed(resolveAnalogStorageReference(read->getOperand(0), read)))
      return failure();
    return read->getOperand(0).getDefiningOp();
  }
  if (!definition || !llvm::isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(definition) ||
      failed(resolveAnalogArrayValue(value, definition)))
    return failure();
  return definition;
}

FailureOr<std::string> getAnalogCapturedReadPath(Value value) {
  auto identity = getAnalogCapturedReadIdentity(value);
  if (failed(identity))
    return failure();
  llvm::StringRef path;
  std::string owned;
  if (llvm::isa<AnalogVariableOp>(*identity))
    path = text(*identity, "identity");
  else {
    owned = semanticPath(*identity);
    path = owned;
  }
  if (path.empty() || path != path.trim())
    return failure();
  return path.str();
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

// Retained procedural source strings have one semantic admission path, whether
// statements run ordinarily or under event control. Event placement is checked
// separately by verifyAnalogEventOperation.
LogicalResult verifyAnalogProceduralExpressions(Operation *procedure) {
  if (!llvm::isa_and_nonnull<AnalogProcedureOp>(procedure))
    return failure();
  LogicalResult result = success();
  procedure->walk([&](Operation *op) {
    if (failed(result))
      return;
    llvm::StringRef source, kind, dimension;
    ArrayAttr inventory;
    std::optional<double> claimedStatic;
    std::set<std::string> operandReads;
    bool actualCaptures = false;
    if (llvm::isa<AnalogVariableOp>(op)) {
      const bool expressionInitializer = text(op, "initializer_form") == "expression";
      if (!expressionInitializer &&
          (llvm::isa<ShapedType>(op->getResult(0).getType()) || op->getNumOperands()))
        return;
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
      if (expressionInitializer) {
        actualCaptures = true;
        for (Value value : op->getOperands()) {
          auto path = getAnalogCapturedReadPath(value);
          if (failed(path) || !operandReads.insert(*path).second) {
            result =
                reject(op, 10, "initializer captures must bind unique immutable scalar values");
            return;
          }
        }
      }
    } else if (llvm::isa<AnalogAssignOp>(op)) {
      if (text(op, "value_form") == "snapshot")
        return;
      auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
      source = metadata ? field(metadata, "value") : llvm::StringRef();
      kind = text(op, "value_kind");
      dimension = text(op, "value_dimension");
      actualCaptures = true;
      for (auto value : op->getOperands().drop_front()) {
        auto path = getAnalogCapturedReadPath(value);
        if (failed(path) || !operandReads.insert(*path).second) {
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
    if (storageBindingOwner(op) && op->hasAttr("reference_form")) {
      auto bindings = resolveAnalogSourceStorageBindings(op);
      std::set<std::string> bound;
      if (failed(bindings)) {
        result = failure();
        return;
      }
      for (const auto &binding : *bindings)
        bound.insert(binding.path);
      if (bound != expression->reads) {
        result = reject(op, 10, "control storage bindings differ from actual source leaves");
        return;
      }
    }
    auto reads =
        actualCaptures ? FailureOr<std::set<std::string>>(operandReads) : strings(inventory);
    if (failed(reads) || *reads != expression->reads ||
        (claimedStatic && expression->constant != claimedStatic))
      result = reject(op, 10, "procedural expression reads or static value were forged");
  });
  return result;
}

LogicalResult verifyAnalogEventProcedure(Operation *procedure) {
  return verifyAnalogProceduralExpressions(procedure);
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
      std::set<Operation *> observedRoots;
      if (op->hasAttr("reference_form")) {
        auto bindings = resolveAnalogSourceStorageBindings(op);
        if (failed(bindings))
          return failure();
        for (const auto &binding : *bindings) {
          auto reference = resolveAnalogStorageReference(binding.reference, op);
          if (failed(reference))
            return failure();
          observedRoots.insert(reference->declaration);
        }
      }
      bool changed = false;
      for (Operation *between = op->getNextNode(); between != consumer;
           between = between->getNextNode())
        between->walk([&](AnalogAssignOp assignment) {
          if (assignment->getNumOperands()) {
            auto reference = resolveAnalogStorageReference(assignment->getOperand(0), assignment);
            if (succeeded(reference))
              changed |= op->hasAttr("reference_form")
                             ? observedRoots.count(reference->declaration) != 0
                             : observed->count(text(reference->declaration, "identity").str()) != 0;
          }
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
  auto bindings = resolveAnalogSourceStorageBindings(op);
  if (failed(bindings))
    return failure();
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
    auto expression = liveForm(op) ? parseAnalogSourceExpression(op, field(arg, "value"))
                                   : parser.parse(field(arg, "value"));
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
    // The public Edge contract is closed: do not infer a direction from a
    // parameter default, a mutable initializer, or caller-supplied metadata.
    if (cross && i == 1 &&
        (!expression->constant || (*expression->constant != -1.0 && *expression->constant != 0.0 &&
                                   *expression->constant != 1.0)))
      return reject(op, 4, "cross direction must be a proven falling, either, or rising value");
    if (tolerance && expression->constant && *expression->constant < 0.0)
      return reject(op, 4, "event tolerances must be nonnegative; zero selects simulator defaults");
  }
  if (op->hasAttr("reference_form")) {
    std::set<std::string> bound;
    for (const auto &binding : *bindings)
      bound.insert(binding.path);
    if (bound != allReads)
      return reject(op, 5, "event storage bindings differ from actual source leaves");
  }
  if (allReads != *readInventory)
    return reject(op, 5, "event read inventory is incomplete");
  return success();
}
} // namespace nodal
