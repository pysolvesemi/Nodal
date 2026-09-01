#ifndef NODAL_ANALOG_PROCEDURAL_RUNTIME_H
#define NODAL_ANALOG_PROCEDURAL_RUNTIME_H

#include <algorithm>
#include <cstddef>
#include <optional>
#include <set>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace nodal::analog::procedural {

enum class ScalarKind { Integer, Real, Boolean };

inline const char *scalarKindName(ScalarKind kind) {
  switch (kind) {
  case ScalarKind::Integer:
    return "integer";
  case ScalarKind::Real:
    return "real";
  case ScalarKind::Boolean:
    return "boolean";
  }
  return "unknown";
}

struct ValueType {
  ScalarKind kind = ScalarKind::Real;
  std::string dimension = "dimensionless";

  bool operator==(const ValueType &) const = default;
};

struct Source {
  std::string file;
  unsigned line = 1;
  unsigned column = 1;
};

struct Variable {
  std::string identity;
  std::string owner;
  std::vector<std::string> declarationScope;
  ValueType type;
};

struct Value {
  std::string rendered;
  ValueType type;
  std::vector<Variable> reads;
};

struct VariableRecord {
  Variable variable;
  std::optional<Value> initializer;
  std::optional<Source> source;
  std::size_t declarationOrder = 0;
  std::size_t operationOrder = 0;
};

struct AssignmentRecord {
  std::string identity;
  Variable target;
  Value value;
  std::size_t authoredOrder = 0;
  std::vector<std::string> scope;
  std::optional<Value> guard;
  std::vector<std::string> analyses;
  std::optional<Source> source;
  std::size_t operationOrder = 0;
};

struct Snapshot {
  std::string owner;
  std::vector<VariableRecord> variables;
  std::vector<AssignmentRecord> assignments;
};

struct Diagnostic {
  std::string code;
  std::string message;
  std::optional<std::string> path;
};

class Failure final : public std::invalid_argument {
public:
  explicit Failure(Diagnostic diagnostic)
      : std::invalid_argument(render(diagnostic)), diagnostic_(std::move(diagnostic)) {}

  const Diagnostic &diagnostic() const noexcept { return diagnostic_; }

private:
  static std::string render(const Diagnostic &diagnostic) {
    std::string text = diagnostic.code + ": " + diagnostic.message;
    if (diagnostic.path)
      text += " [" + *diagnostic.path + "]";
    return text;
  }

  Diagnostic diagnostic_;
};

class Recorder final {
public:
  explicit Recorder(std::string owner) : owner_(std::move(owner)) {
    if (owner_.empty())
      throw std::invalid_argument("procedural owner must be non-empty");
  }

  template <typename Body> decltype(auto) procedure(Body &&body) {
    if (procedureActive_)
      fail("NODAL-ANALOG-033-018", "nested analog procedural regions are not supported");
    procedureActive_ = true;
    scopeStack_ = {"procedure"};
    try {
      if constexpr (std::is_void_v<std::invoke_result_t<Body>>) {
        std::forward<Body>(body)();
        scopeStack_.clear();
        procedureActive_ = false;
      } else {
        auto result = std::forward<Body>(body)();
        scopeStack_.clear();
        procedureActive_ = false;
        return result;
      }
    } catch (...) {
      scopeStack_.clear();
      procedureActive_ = false;
      throw;
    }
  }

  template <typename Body> decltype(auto) scope(const std::string &name, Body &&body) {
    requireProcedure();
    if (trim(name).empty())
      fail("NODAL-ANALOG-033-016", "procedural lexical scope identity must be non-empty");
    ++scopeSerial_;
    scopeStack_.push_back(trim(name) + "#" + std::to_string(scopeSerial_));
    try {
      if constexpr (std::is_void_v<std::invoke_result_t<Body>>) {
        std::forward<Body>(body)();
        scopeStack_.pop_back();
      } else {
        auto result = std::forward<Body>(body)();
        scopeStack_.pop_back();
        return result;
      }
    } catch (...) {
      scopeStack_.pop_back();
      throw;
    }
  }

  Variable declareVariable(std::string identity, ValueType type,
                           std::optional<Value> initializer = std::nullopt,
                           std::optional<Source> source = std::nullopt) {
    requireProcedure(identity.empty() ? std::nullopt : std::optional<std::string>(identity));
    identity = trim(identity);
    if (identity.empty())
      fail("NODAL-ANALOG-033-001", "procedural variable identity must be non-empty");
    const std::string canonical = owner_ + "." + join(scopeStack_, ".") + "." + identity;
    if (variables_.contains(canonical))
      fail("NODAL-ANALOG-033-002", "duplicate procedural variable identity '" + canonical + "'",
           canonical);
    if (type.dimension.empty())
      throw std::invalid_argument("procedural value dimension must be non-empty");
    if (type.kind == ScalarKind::Boolean && type.dimension != "dimensionless")
      fail("NODAL-ANALOG-033-019", "Boolean procedural variables must be dimensionless",
           canonical);

    Variable variable{canonical, owner_, scopeStack_, std::move(type)};
    if (initializer) {
      validateInitializer(variable, *initializer);
      validateReads(*initializer);
    }
    VariableRecord record{variable, initializer, source, variables_.size(),
                          variables_.size() + assignments_.size()};
    variables_.emplace(canonical, MutableVariable{record, initializer.has_value()});
    return variable;
  }

  Value read(const Variable &variable) {
    requireProcedure(variable.identity);
    MutableVariable &state = resolve(variable);
    if (!state.initialized)
      fail("NODAL-ANALOG-033-011",
           "procedural variable '" + variable.identity +
               "' is read before initialization or an earlier assignment",
           variable.identity);
    return Value{variable.identity, variable.type, {variable}};
  }

  static Value reference(const Variable &variable) {
    return Value{variable.identity, variable.type, {variable}};
  }

  void assign(std::string identity, const Variable &target, const Value &value,
              std::optional<Value> guard = std::nullopt,
              std::set<std::string> analyses = {"dc", "transient"},
              std::optional<Source> source = std::nullopt) {
    requireProcedure(identity.empty() ? std::nullopt : std::optional<std::string>(identity));
    identity = trim(identity);
    if (identity.empty())
      fail("NODAL-ANALOG-033-006", "procedural statement identity must be non-empty");
    const std::string canonical = owner_ + "." + identity;
    if (statements_.contains(canonical))
      fail("NODAL-ANALOG-033-007", "duplicate procedural statement identity '" + canonical + "'",
           canonical);

    MutableVariable &targetState = resolve(target);
    validateReads(value);
    if (!compatible(value.type.kind, target.type.kind))
      fail("NODAL-ANALOG-033-012",
           "assigned kind '" + std::string(scalarKindName(value.type.kind)) +
               "' is incompatible with '" + scalarKindName(target.type.kind) + "'",
           canonical);
    if (value.type.dimension != target.type.dimension)
      fail("NODAL-ANALOG-033-013",
           "assigned dimension '" + value.type.dimension + "' does not match '" +
               target.type.dimension + "'",
           canonical);

    if (guard) {
      validateReads(*guard);
      if (!(guard->type == ValueType{ScalarKind::Boolean, "dimensionless"}))
        fail("NODAL-ANALOG-033-014",
             "procedural assignment guard must be a dimensionless Boolean value", canonical);
    }

    std::vector<std::string> canonicalAnalyses;
    for (const std::string &analysis : analyses) {
      const std::string valueAnalysis = trim(analysis);
      if (!valueAnalysis.empty())
        canonicalAnalyses.push_back(valueAnalysis);
    }
    std::sort(canonicalAnalyses.begin(), canonicalAnalyses.end());
    canonicalAnalyses.erase(std::unique(canonicalAnalyses.begin(), canonicalAnalyses.end()),
                            canonicalAnalyses.end());
    if (canonicalAnalyses.empty() ||
        !std::all_of(canonicalAnalyses.begin(), canonicalAnalyses.end(),
                     [&](const std::string &analysis) { return knownAnalyses_.contains(analysis); }))
      fail("NODAL-ANALOG-033-015", "invalid procedural analysis applicability", canonical);

    assignments_.push_back(AssignmentRecord{
        canonical, target, value, assignments_.size(), scopeStack_, guard,
        canonicalAnalyses, source, variables_.size() + assignments_.size()});
    statements_.insert(canonical);
    targetState.initialized = true;
  }

  Snapshot snapshot() const {
    Snapshot result;
    result.owner = owner_;
    result.variables.reserve(variables_.size());
    for (const auto &[identity, variable] : variables_) {
      (void)identity;
      result.variables.push_back(variable.record);
    }
    std::sort(result.variables.begin(), result.variables.end(),
              [](const VariableRecord &left, const VariableRecord &right) {
                return left.declarationOrder < right.declarationOrder;
              });
    result.assignments = assignments_;
    return result;
  }

private:
  struct MutableVariable {
    VariableRecord record;
    bool initialized = false;
  };

  [[noreturn]] static void fail(std::string code, std::string message,
                                std::optional<std::string> path = std::nullopt) {
    throw Failure(Diagnostic{std::move(code), std::move(message), std::move(path)});
  }

  static std::string trim(const std::string &value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos)
      return {};
    const auto last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
  }

  static std::string join(const std::vector<std::string> &values, const std::string &separator) {
    std::string result;
    for (std::size_t index = 0; index < values.size(); ++index) {
      if (index != 0)
        result += separator;
      result += values[index];
    }
    return result;
  }

  void requireProcedure(std::optional<std::string> path = std::nullopt) const {
    if (!procedureActive_)
      fail("NODAL-ANALOG-033-008",
           "analog procedural operation requires an active procedural region", std::move(path));
  }

  bool visible(const Variable &variable) const {
    if (scopeStack_.size() < variable.declarationScope.size())
      return false;
    return std::equal(variable.declarationScope.begin(), variable.declarationScope.end(),
                      scopeStack_.begin());
  }

  MutableVariable &resolve(const Variable &variable) {
    if (variable.owner != owner_)
      fail("NODAL-ANALOG-033-009",
           "variable '" + variable.identity + "' belongs to component '" + variable.owner +
               "', not '" + owner_ + "'",
           variable.identity);
    auto iterator = variables_.find(variable.identity);
    if (iterator == variables_.end())
      fail("NODAL-ANALOG-033-017", "unknown procedural variable '" + variable.identity + "'",
           variable.identity);
    if (!visible(variable))
      fail("NODAL-ANALOG-033-010",
           "procedural variable '" + variable.identity + "' is outside its lexical scope",
           variable.identity);
    return iterator->second;
  }

  static bool compatible(ScalarKind source, ScalarKind destination) {
    return source == destination ||
           (source == ScalarKind::Integer && destination == ScalarKind::Real);
  }

  void validateInitializer(const Variable &variable, const Value &initializer) {
    if (!compatible(initializer.type.kind, variable.type.kind))
      fail("NODAL-ANALOG-033-004", "initializer kind is incompatible with variable kind",
           variable.identity);
    if (initializer.type.dimension != variable.type.dimension)
      fail("NODAL-ANALOG-033-005", "initializer dimension does not match variable dimension",
           variable.identity);
  }

  void validateReads(const Value &value) {
    for (const Variable &readVariable : value.reads) {
      MutableVariable &state = resolve(readVariable);
      if (!state.initialized)
        fail("NODAL-ANALOG-033-011",
             "procedural variable '" + readVariable.identity +
                 "' is read before initialization or an earlier assignment",
             readVariable.identity);
    }
  }

  std::string owner_;
  bool procedureActive_ = false;
  std::vector<std::string> scopeStack_;
  std::size_t scopeSerial_ = 0;
  std::unordered_map<std::string, MutableVariable> variables_;
  std::vector<AssignmentRecord> assignments_;
  std::unordered_set<std::string> statements_;
  const std::unordered_set<std::string> knownAnalyses_ = {
      "initialization", "operating-point", "dc", "transient", "ac", "noise"};
};

} // namespace nodal::analog::procedural

#endif // NODAL_ANALOG_PROCEDURAL_RUNTIME_H
