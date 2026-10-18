#ifndef NODAL_SUPPORT_ANALOGHIERARCHYSYNTAX_H
#define NODAL_SUPPORT_ANALOGHIERARCHYSYNTAX_H

#include "nodal/Support/HierarchyOrder.h"

#include <algorithm>
#include <cctype>
#include <charconv>
#include <cstdint>
#include <limits>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <string_view>
#include <tuple>
#include <utility>
#include <vector>

namespace nodal {

struct HierarchyEndpointSyntax {
  std::string name;
  std::string index;
  std::string spelling;
  std::string partSelectEnd;
  std::set<std::string> parameterReferences;
};

struct HierarchyInstanceSyntax {
  std::string module;
  std::string name;
  std::map<std::string, std::string> parameters;
  std::map<std::string, std::string> ports;
  std::set<std::string> parameterReferences;
};

struct HierarchyModuleHeaderSyntax {
  std::string name;
  std::set<std::string> ports;
  std::map<std::string, std::string> portAliases;
};

struct HierarchyVectorSyntax {
  std::string lower;
  std::string upper;
};

struct HierarchyIntegerParameterSyntax {
  std::string name;
  std::string initializer;
  bool fixed = false;
};

struct HierarchyStructuralGenerateSyntax {
  std::string genvar;
  std::string count;
  std::string label;
  std::vector<HierarchyIntegerParameterSyntax> localParameters;
  HierarchyInstanceSyntax instance;
};

/// Recognizes the emitted conservative hierarchy profile, including vector actuals.
/// This does not claim to be an independent or general Verilog-A parser.
class HierarchySyntaxParser {
public:
  explicit HierarchySyntaxParser(std::string_view text) : text(text) {}

  std::optional<HierarchyEndpointSyntax> terminal() {
    if (text.find('\0') != std::string_view::npos)
      return std::nullopt;
    auto result = endpoint();
    return result && peek() == '\0' ? result : std::nullopt;
  }

  std::optional<HierarchyInstanceSyntax> instance() {
    if (text.find('\0') != std::string_view::npos)
      return std::nullopt;
    HierarchyInstanceSyntax result;
    auto module = identifier();
    if (!module)
      return std::nullopt;
    result.module = *module;
    if (consume('#')) {
      if (!consume('(') || peek() != '.')
        return std::nullopt;
      do {
        if (!consume('.'))
          return std::nullopt;
        auto name = identifier();
        if (!name || !consume('('))
          return std::nullopt;
        auto value = constant(result.parameterReferences);
        if (!value || !consume(')') || !result.parameters.emplace(*name, *value).second)
          return std::nullopt;
      } while (consume(','));
      if (!consume(')'))
        return std::nullopt;
    }
    auto name = identifier();
    if (!name || !consume('('))
      return std::nullopt;
    result.name = *name;
    if (peek() != ')') {
      do {
        if (!consume('.'))
          return std::nullopt;
        auto port = identifier();
        if (!port || !consume('('))
          return std::nullopt;
        auto actual = endpoint();
        if (!actual || !consume(')') || !result.ports.emplace(*port, actual->spelling).second)
          return std::nullopt;
        result.parameterReferences.insert(actual->parameterReferences.begin(),
                                          actual->parameterReferences.end());
      } while (consume(','));
    }
    if (!consume(')') || !consume(';') || peek() != '\0')
      return std::nullopt;
    return result;
  }

  std::optional<HierarchyModuleHeaderSyntax> moduleHeader() {
    if (text.find('\0') != std::string_view::npos || !keyword("module"))
      return std::nullopt;
    HierarchyModuleHeaderSyntax result;
    auto name = identifier();
    if (!name)
      return std::nullopt;
    result.name = *name;
    if (consume('(')) {
      const bool aliases = peek() == '.';
      if (peek() != ')') {
        do {
          if (aliases && !consume('.'))
            return std::nullopt;
          auto port = identifier();
          if (!port || !result.ports.insert(*port).second)
            return std::nullopt;
          if (aliases) {
            if (!consume('('))
              return std::nullopt;
            auto internal = identifier();
            if (!internal || !consume(')'))
              return std::nullopt;
            result.portAliases.emplace(*port, *internal);
          }
        } while (consume(','));
      }
      if (!consume(')'))
        return std::nullopt;
    }
    if (!consume(';') || peek() != '\0')
      return std::nullopt;
    return result;
  }

  std::optional<HierarchyStructuralGenerateSyntax> structuralGenerate() {
    if (text.find('\0') != std::string_view::npos || !keyword("generate") || !keyword("for") ||
        !consume('('))
      return std::nullopt;
    HierarchyStructuralGenerateSyntax result;
    auto induction = identifier();
    std::set<std::string> references;
    if (!induction || !consume('='))
      return std::nullopt;
    auto initial = constant(references, ';');
    if (!initial || *initial != "0" || !consume(';'))
      return std::nullopt;
    auto condition = identifier();
    if (!condition || *condition != *induction || !consume('<'))
      return std::nullopt;
    auto count = constant(references, ';');
    if (!count || !consume(';'))
      return std::nullopt;
    auto destination = identifier();
    if (!destination || *destination != *induction || !consume('='))
      return std::nullopt;
    auto update = identifier();
    if (!update || *update != *induction || !consume('+'))
      return std::nullopt;
    auto step = constant(references);
    if (!step || *step != "1" || !consume(')') || !keyword("begin") || !consume(':'))
      return std::nullopt;
    auto label = identifier();
    if (!label)
      return std::nullopt;
    result.genvar = *induction;
    result.count = *count;
    result.label = *label;
    while (keyword("localparam")) {
      if (!keyword("integer"))
        return std::nullopt;
      auto name = identifier();
      if (!name || !consume('='))
        return std::nullopt;
      auto initializer = constant(references, ';');
      if (!initializer || !consume(';'))
        return std::nullopt;
      result.localParameters.push_back({*name, *initializer, true});
    }
    space();
    const auto start = position;
    auto semicolon = text.find(';', start);
    if (semicolon == std::string_view::npos)
      return std::nullopt;
    auto parsed = HierarchySyntaxParser(text.substr(start, semicolon - start + 1)).instance();
    if (!parsed)
      return std::nullopt;
    result.instance = std::move(*parsed);
    position = semicolon + 1;
    if (!keyword("end") || !keyword("endgenerate") || peek() != '\0')
      return std::nullopt;
    return result;
  }

private:
  std::string_view text;
  std::size_t position = 0;

  void space() {
    while (position < text.size() && std::isspace(static_cast<unsigned char>(text[position])))
      ++position;
  }
  char peek() {
    space();
    return position == text.size() ? '\0' : text[position];
  }
  bool consume(char value) {
    if (peek() != value)
      return false;
    ++position;
    return true;
  }
  static bool identifierStart(char value) {
    return value == '_' || (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
  }
  static bool digit(char value) { return value >= '0' && value <= '9'; }
  std::optional<std::string> identifier() {
    space();
    const std::size_t start = position;
    if (position == text.size() || !identifierStart(text[position]))
      return std::nullopt;
    ++position;
    while (position < text.size() &&
           (identifierStart(text[position]) || digit(text[position]) || text[position] == '$'))
      ++position;
    return std::string(text.substr(start, position - start));
  }
  bool keyword(std::string_view expected) {
    auto saved = position;
    auto value = identifier();
    if (value && *value == expected)
      return true;
    position = saved;
    return false;
  }
  std::optional<HierarchyEndpointSyntax> endpoint() {
    auto name = identifier();
    if (!name)
      return std::nullopt;
    HierarchyEndpointSyntax result;
    result.name = *name;
    result.spelling = *name;
    if (consume('[')) {
      auto index = constant(result.parameterReferences, ']', ':');
      if (!index)
        return std::nullopt;
      result.index = *index;
      result.spelling += "[" + *index;
      if (consume(':')) {
        auto end = constant(result.parameterReferences, ']');
        if (!end)
          return std::nullopt;
        result.partSelectEnd = *end;
        result.spelling += ":" + *end;
      }
      if (!consume(']'))
        return std::nullopt;
      result.spelling += "]";
    }
    return result;
  }
  bool number() {
    const std::size_t start = position;
    while (position < text.size() && digit(text[position]))
      ++position;
    bool digits = position != start;
    if (position < text.size() && text[position] == '.') {
      ++position;
      const auto fractional = position;
      while (position < text.size() && digit(text[position]))
        ++position;
      digits |= fractional != position;
    }
    if (!digits)
      return false;
    if (position < text.size() && (text[position] == 'e' || text[position] == 'E')) {
      ++position;
      if (position < text.size() && (text[position] == '+' || text[position] == '-'))
        ++position;
      const auto exponent = position;
      while (position < text.size() && digit(text[position]))
        ++position;
      if (position == exponent)
        return false;
    }
    if (position < text.size() &&
        std::string_view("TGMKkmunpfa").find(text[position]) != std::string_view::npos) {
      ++position;
    }
    return true;
  }
  std::optional<std::string> constant(std::set<std::string> &references, char delimiter = ')',
                                      char alternateDelimiter = '\0') {
    space();
    const auto start = position;
    std::size_t depth = 0;
    bool expectsValue = true;
    bool sawValue = false;
    while (position < text.size()) {
      const char token = peek();
      if ((token == delimiter || token == alternateDelimiter) && depth == 0)
        break;
      if (expectsValue) {
        if (token == '+' || token == '-' || token == '!') {
          ++position;
          continue;
        }
        if (token == '(') {
          ++position;
          ++depth;
          continue;
        }
        if (identifierStart(token)) {
          auto name = identifier();
          if (!name)
            return std::nullopt;
          references.insert(*name);
        } else if (!number()) {
          return std::nullopt;
        }
        sawValue = true;
        expectsValue = false;
      } else if (token == ')' && depth) {
        ++position;
        --depth;
      } else if (text.substr(position, 2) == "&&" || text.substr(position, 2) == "||" ||
                 text.substr(position, 2) == ">=" || text.substr(position, 2) == "<=") {
        position += 2;
        expectsValue = true;
      } else if (token == '+' || token == '-' || token == '*' || token == '/' || token == '%' ||
                 token == '>' || token == '<') {
        ++position;
        expectsValue = true;
      } else {
        return std::nullopt;
      }
    }
    if (!sawValue || expectsValue || depth)
      return std::nullopt;
    auto end = position;
    while (end > start && std::isspace(static_cast<unsigned char>(text[end - 1])))
      --end;
    return std::string(text.substr(start, end - start));
  }
};

inline std::optional<HierarchyEndpointSyntax> parseHierarchyEndpoint(std::string_view text) {
  return HierarchySyntaxParser(text).terminal();
}

inline std::optional<HierarchyInstanceSyntax> parseHierarchyInstance(std::string_view text) {
  return HierarchySyntaxParser(text).instance();
}

inline std::optional<HierarchyModuleHeaderSyntax>
parseHierarchyModuleHeader(std::string_view text) {
  return HierarchySyntaxParser(text).moduleHeader();
}

inline std::optional<HierarchyStructuralGenerateSyntax>
parseStructuralGenerate(std::string_view text) {
  return HierarchySyntaxParser(text).structuralGenerate();
}

struct HierarchyModuleSyntax {
  std::string name;
  std::set<std::string> ports;
  std::set<std::string> nodes;
  std::set<std::string> arrays;
  std::set<std::string> parameters;
  std::set<std::string> fixedParameters;
  std::vector<HierarchyInstanceSyntax> instances;
  std::map<std::string, HierarchyVectorSyntax> vectors;
  std::vector<HierarchyIntegerParameterSyntax> integerDeclarations;
  std::map<std::string, std::string> portAliases;
  std::map<std::string, std::string> portDirections;
  std::set<std::string> electricalNodes;
  std::vector<HierarchyStructuralGenerateSyntax> generatedScopes;
  std::set<std::string> genvars;
  bool hasBehavior = false;
};

namespace hierarchy_detail {

/// Target-only immutable arithmetic. Node IDs preserve declaration identity and
/// ordered expression structure without recursively expanding staged maps.
class IntegerExpressions {
public:
  using Id = std::size_t;
  using Environment = std::map<std::string, Id>;
  static constexpr Id invalid = std::numeric_limits<Id>::max();
  struct Node {
    char operation;
    Id left = invalid;
    Id right = invalid;
    std::optional<int64_t> constant;
    std::optional<int64_t> lower;
    std::optional<int64_t> upper;
    // Exact values at the first/last generated occurrence, when known.
    // Interval extremes alone can be unattainable for correlated expressions.
    std::optional<int64_t> first = {};
    std::optional<int64_t> last = {};
    bool lowerAttained = false;
    bool upperAttained = false;
    bool dense = false;
  };

  Id literal(int64_t value) {
    return intern('n', std::to_string(value), invalid, invalid,
                  Node{'n', invalid, invalid, value, value, value, value, value, true, true, true});
  }
  Id symbol(const std::string &name, std::optional<int64_t> lower = {},
            std::optional<int64_t> upper = {}) {
    return intern('s', name, invalid, invalid,
                  Node{'s',
                       invalid,
                       invalid,
                       {},
                       lower,
                       upper,
                       lower,
                       upper,
                       lower.has_value(),
                       upper.has_value(),
                       lower.has_value() && upper.has_value()});
  }
  const Node &node(Id id) const { return nodes[id]; }

  Id apply(char operation, Id left, Id right) {
    if (left == invalid || right == invalid)
      return invalid;
    // Copy before interning, which can reallocate the arena.
    const auto lhs = node(left), rhs = node(right);
    // A dense generated divisor can attain zero between nonzero endpoint
    // witnesses. Reject that actual undefined occurrence, including unused stages.
    if ((operation == '/' || operation == '%') &&
        (rhs.constant == 0 ||
         (rhs.dense && rhs.lower && rhs.upper && *rhs.lower <= 0 && *rhs.upper >= 0)))
      return invalid;
    if (lhs.constant && rhs.constant) {
      auto value = compute(operation, *lhs.constant, *rhs.constant);
      return value ? literal(*value) : invalid;
    }
    if ((operation == '+' || operation == '-') && rhs.constant == 0)
      return left;
    if (operation == '+' && lhs.constant == 0)
      return right;
    if (operation == '*' && rhs.constant == 1)
      return left;
    if (operation == '*' && lhs.constant == 1)
      return right;
    if (operation == '/' && rhs.constant == 1)
      return left;
    if (operation == '-' && left == right)
      return literal(0);
    // Recover vector widths from emitted [first:(first + count) - 1]
    // spelling. This is bounded cancellation, not an algebraic equivalence solver.
    if (operation == '+' && lhs.operation == '-' && lhs.right == right)
      return lhs.left;
    if (operation == '-' && lhs.operation == '+') {
      if (lhs.left == right)
        return lhs.right;
      if (lhs.right == right)
        return lhs.left;
    }
    Node result{operation, left, right, {}, {}, {}};
    if (lhs.first && rhs.first) {
      result.first = compute(operation, *lhs.first, *rhs.first);
      if (!result.first)
        return invalid;
    }
    if (lhs.last && rhs.last) {
      result.last = compute(operation, *lhs.last, *rhs.last);
      if (!result.last)
        return invalid;
    }
    if (lhs.lower && lhs.upper && rhs.lower && rhs.upper) {
      if (operation == '%') {
        if (*rhs.lower > 0) {
          result.lower = *lhs.lower < 0 ? std::max(*lhs.lower, -(*rhs.upper - 1)) : 0;
          result.upper = *lhs.upper > 0 ? std::min(*lhs.upper, *rhs.upper - 1) : 0;
        }
      } else if (operation != '/' || *rhs.lower > 0 || *rhs.upper < 0) {
        std::vector<int64_t> corners;
        for (auto a : {*lhs.lower, *lhs.upper})
          for (auto b : {*rhs.lower, *rhs.upper}) {
            auto value = compute(operation, a, b);
            if (value)
              corners.push_back(*value);
          }
        if (corners.size() == 4) {
          result.lower = *std::min_element(corners.begin(), corners.end());
          result.upper = *std::max_element(corners.begin(), corners.end());
        }
      }
    }
    if (operation == '%' && rhs.constant && *rhs.constant > 0 && lhs.dense && lhs.lower &&
        lhs.upper && *lhs.lower >= 0) {
      if (*lhs.lower / *rhs.constant == *lhs.upper / *rhs.constant) {
        result.lower = *lhs.lower % *rhs.constant;
        result.upper = *lhs.upper % *rhs.constant;
        result.dense = true;
      } else {
        result.lower = 0;
        result.upper = *rhs.constant - 1;
        result.dense = *lhs.upper - *lhs.lower >= *rhs.constant - 1;
      }
      result.lowerAttained = result.upperAttained = true;
    } else if (operation != '%' && result.lower && result.upper &&
               ((lhs.constant && rhs.lowerAttained && rhs.upperAttained) ||
                (rhs.constant && lhs.lowerAttained && lhs.upperAttained))) {
      result.lowerAttained = result.upperAttained = true;
      result.dense = (operation == '+' || operation == '-') &&
                     ((lhs.constant && rhs.dense) || (rhs.constant && lhs.dense));
      if (operation == '/' && rhs.constant && lhs.dense)
        result.dense = true;
    }
    result.lowerAttained |=
        result.lower && (result.lower == result.first || result.lower == result.last);
    result.upperAttained |=
        result.upper && (result.upper == result.first || result.upper == result.last);
    return intern(operation, {}, left, right, result);
  }

  Id parse(std::string_view text, const Environment &environment, bool signed32 = false,
           const Environment *fallback = nullptr) {
    std::vector<Id> values;
    std::vector<char> operations;
    bool expectsValue = true;
    auto reduce = [&]() {
      if (operations.empty() || values.empty())
        return false;
      char operation = operations.back();
      operations.pop_back();
      Id right = values.back();
      values.pop_back();
      if (operation == '~')
        values.push_back(apply('-', literal(0), right));
      else if (operation == '`')
        values.push_back(right);
      else {
        if (values.empty())
          return false;
        Id left = values.back();
        values.back() = apply(operation, left, right);
      }
      return values.back() != invalid && (!signed32 || fits(values.back()));
    };
    for (std::size_t position = 0; position < text.size();) {
      char token = text[position];
      if (std::isspace(static_cast<unsigned char>(token))) {
        ++position;
        continue;
      }
      if (expectsValue) {
        if (token == '(') {
          operations.push_back(token);
          ++position;
          continue;
        }
        if ((token == '-' || token == '+') &&
            !(token == '-' && position + 1 < text.size() && digit(text[position + 1]))) {
          operations.push_back(token == '-' ? '~' : '`');
          ++position;
          continue;
        }
        Id value = invalid;
        const auto start = position;
        if (digit(token) || token == '-') {
          if (token == '-')
            ++position;
          while (position < text.size() && digit(text[position]))
            ++position;
          int64_t number = 0;
          auto parsed = std::from_chars(text.data() + start, text.data() + position, number);
          if (parsed.ec != std::errc() || parsed.ptr != text.data() + position)
            return invalid;
          value = literal(number);
        } else if (identifierStart(token)) {
          ++position;
          while (position < text.size() && (identifierStart(text[position]) ||
                                            digit(text[position]) || text[position] == '$'))
            ++position;
          auto found = environment.find(std::string(text.substr(start, position - start)));
          if (found != environment.end())
            value = found->second;
          else if (fallback) {
            auto other = fallback->find(std::string(text.substr(start, position - start)));
            if (other != fallback->end())
              value = other->second;
          }
        }
        if (value == invalid || (signed32 && !fits(value)))
          return invalid;
        values.push_back(value);
        expectsValue = false;
      } else if (token == ')') {
        while (!operations.empty() && operations.back() != '(')
          if (!reduce())
            return invalid;
        if (operations.empty())
          return invalid;
        operations.pop_back();
        ++position;
      } else if (std::string_view("+-*/%").find(token) != std::string_view::npos) {
        while (!operations.empty() && operations.back() != '(' &&
               precedence(operations.back()) >= precedence(token))
          if (!reduce())
            return invalid;
        operations.push_back(token);
        ++position;
        expectsValue = true;
      } else {
        return invalid;
      }
    }
    if (expectsValue)
      return invalid;
    while (!operations.empty())
      if (operations.back() == '(' || !reduce())
        return invalid;
    return values.size() == 1 ? values.front() : invalid;
  }

private:
  std::vector<Node> nodes;
  std::map<std::tuple<char, std::string, Id, Id>, Id> identities;

  Id intern(char operation, std::string name, Id left, Id right, Node value) {
    auto [found, inserted] =
        identities.emplace(std::make_tuple(operation, name, left, right), nodes.size());
    if (inserted)
      nodes.push_back(value);
    return found->second;
  }
  bool fits(Id value) const {
    const auto &record = node(value);
    auto representable = [](std::optional<int64_t> value) {
      return !value || (*value >= std::numeric_limits<int32_t>::min() &&
                        *value <= std::numeric_limits<int32_t>::max());
    };
    return representable(record.constant) && representable(record.first) &&
           representable(record.last) && (!record.lowerAttained || representable(record.lower)) &&
           (!record.upperAttained || representable(record.upper));
  }
  static bool digit(char value) { return value >= '0' && value <= '9'; }
  static bool identifierStart(char value) {
    return value == '_' || (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
  }
  static int precedence(char operation) {
    return operation == '~' || operation == '`'                       ? 3
           : operation == '*' || operation == '/' || operation == '%' ? 2
                                                                      : 1;
  }
  static std::optional<int64_t> compute(char operation, int64_t left, int64_t right) {
    int64_t result = 0;
    if ((operation == '+' && __builtin_add_overflow(left, right, &result)) ||
        (operation == '-' && __builtin_sub_overflow(left, right, &result)) ||
        (operation == '*' && __builtin_mul_overflow(left, right, &result)))
      return std::nullopt;
    if (operation == '/' || operation == '%') {
      if (right == 0 || (left == std::numeric_limits<int64_t>::min() && right == -1))
        return std::nullopt;
      result = operation == '/' ? left / right : left % right;
    }
    return result;
  }
};

/// Resolve only declarations reached by a structural use. Ordinary parent
/// parameters remain declaration-identity atoms, regardless of their defaults.
/// Effective child defaults and fixed localparams use an explicit postorder stack.
class ParameterEnvironment {
public:
  using Id = IntegerExpressions::Id;
  using Environment = IntegerExpressions::Environment;
  ParameterEnvironment(IntegerExpressions &expressions, const HierarchyModuleSyntax &module,
                       ParameterEnvironment *parent = nullptr,
                       const HierarchyInstanceSyntax *instance = nullptr,
                       const Environment *parentLocals = nullptr)
      : expressions(expressions), module(module), parent(parent), instance(instance),
        parentLocals(parentLocals) {
    for (std::size_t index = 0; index < module.integerDeclarations.size(); ++index) {
      const auto &parameter = module.integerDeclarations[index];
      if (!module.parameters.count(parameter.name) ||
          !declarations.emplace(parameter.name, index).second ||
          parameter.fixed != (module.fixedParameters.count(parameter.name) != 0))
        valid = false;
      if (!parent && !parameter.fixed)
        values.emplace(parameter.name, expressions.symbol(module.name + "." + parameter.name));
    }
  }
  bool wellFormed() const { return valid; }
  bool contains(const std::string &name) const { return declarations.count(name) != 0; }

  Id parse(std::string_view text, const Environment &locals = {}, bool signed32 = false) {
    if (!valid)
      return IntegerExpressions::invalid;
    for (const auto &reference : references(text))
      if (!locals.count(reference) && !resolve(reference))
        return IntegerExpressions::invalid;
    return expressions.parse(text, locals, signed32, &values);
  }

private:
  IntegerExpressions &expressions;
  const HierarchyModuleSyntax &module;
  ParameterEnvironment *parent;
  const HierarchyInstanceSyntax *instance;
  const Environment *parentLocals;
  std::map<std::string, std::size_t> declarations;
  Environment values;
  bool valid = true;

  static std::set<std::string> references(std::string_view text) {
    std::set<std::string> result;
    for (std::size_t position = 0; position < text.size();) {
      const unsigned char token = static_cast<unsigned char>(text[position]);
      if (std::isalpha(token) || token == '_') {
        auto start = position++;
        while (position < text.size() &&
               (std::isalnum(static_cast<unsigned char>(text[position])) || text[position] == '_' ||
                text[position] == '$'))
          ++position;
        result.emplace(text.substr(start, position - start));
      } else {
        ++position;
      }
    }
    return result;
  }

  bool resolve(const std::string &name) {
    std::vector<std::pair<std::string, bool>> pending{{name, false}};
    while (!pending.empty()) {
      auto [current, expanded] = pending.back();
      pending.pop_back();
      if (values.count(current))
        continue;
      auto found = declarations.find(current);
      if (found == declarations.end())
        return false;
      const auto &parameter = module.integerDeclarations[found->second];
      if (instance) {
        auto override = instance->parameters.find(current);
        if (override != instance->parameters.end()) {
          auto value =
              parent->parse(override->second, parentLocals ? *parentLocals : Environment{});
          if (value == IntegerExpressions::invalid)
            return false;
          values.emplace(current, value);
          continue;
        }
      }
      if (expanded) {
        auto value = expressions.parse(parameter.initializer, values);
        if (value == IntegerExpressions::invalid)
          return false;
        values.emplace(current, value);
        continue;
      }
      pending.emplace_back(current, true);
      for (const auto &dependency : references(parameter.initializer)) {
        auto input = declarations.find(dependency);
        if (input == declarations.end() || input->second >= found->second)
          return false;
        if (!values.count(dependency))
          pending.emplace_back(dependency, false);
      }
    }
    return true;
  }
};

inline bool isSharedNetAlias(const HierarchyModuleSyntax &module) {
  if (module.portAliases.size() != 2 || module.ports.size() != 2 || module.nodes.size() != 1 ||
      module.electricalNodes != module.nodes || !module.arrays.empty() || !module.vectors.empty() ||
      !module.parameters.empty() || !module.instances.empty() || !module.generatedScopes.empty() ||
      module.hasBehavior)
    return false;
  const auto &net = *module.nodes.begin();
  auto direction = module.portDirections.find(net);
  if (direction == module.portDirections.end() || direction->second != "inout")
    return false;
  for (const auto &[external, internal] : module.portAliases)
    if (!module.ports.count(external) || internal != net)
      return false;
  return true;
}

} // namespace hierarchy_detail

/// Verify actual target declarations and instance boundaries. Source projection
/// correspondence is deliberately not inferred from this standalone target text.
inline bool verifyHierarchySyntax(const std::vector<HierarchyModuleSyntax> &modules) {
  using Expressions = hierarchy_detail::IntegerExpressions;
  Expressions expressions;
  std::map<std::string, std::size_t> indices;
  std::vector<hierarchy_detail::ParameterEnvironment> environments;
  environments.reserve(modules.size());
  for (std::size_t index = 0; index < modules.size(); ++index) {
    const auto &module = modules[index];
    if (!indices.emplace(module.name, index).second ||
        (!module.portAliases.empty() && !hierarchy_detail::isSharedNetAlias(module)))
      return false;
    environments.emplace_back(expressions, module);
    auto &environment = environments.back();
    if (!environment.wellFormed())
      return false;
    for (const auto &[name, vector] : module.vectors) {
      if (!module.nodes.count(name) || !module.arrays.count(name))
        return false;
      auto lower = environment.parse(vector.lower);
      auto upper = environment.parse(vector.upper);
      if (lower == Expressions::invalid || upper == Expressions::invalid ||
          (expressions.node(lower).constant && expressions.node(upper).constant &&
           *expressions.node(lower).constant > *expressions.node(upper).constant))
        return false;
    }
  }
  std::vector<std::vector<std::size_t>> edges(modules.size());
  for (std::size_t index = 0; index < modules.size(); ++index) {
    const auto &parent = modules[index];
    auto verifyInstance = [&](const HierarchyInstanceSyntax &instance,
                              const Expressions::Environment &locals, bool structural) {
      auto found = indices.find(instance.module);
      if (found == indices.end() || parent.nodes.count(instance.name) ||
          parent.parameters.count(instance.name) || locals.count(instance.name))
        return false;
      const auto &child = modules[found->second];
      if (structural && !hierarchy_detail::isSharedNetAlias(child))
        return false;
      edges[index].push_back(found->second);
      if (instance.ports.size() != child.ports.size())
        return false;
      for (const auto &[parameter, value] : instance.parameters) {
        (void)value;
        if (!child.parameters.count(parameter) || child.fixedParameters.count(parameter))
          return false;
      }
      for (const auto &reference : instance.parameterReferences)
        if (!parent.parameters.count(reference) && !locals.count(reference))
          return false;
      hierarchy_detail::ParameterEnvironment childEnvironment(
          expressions, child, &environments[index], &instance, &locals);
      if (!childEnvironment.wellFormed())
        return false;
      for (const auto &[port, actual] : instance.ports) {
        auto endpoint = parseHierarchyEndpoint(actual);
        if (!child.ports.count(port) || !endpoint || !parent.nodes.count(endpoint->name))
          return false;
        for (const auto &reference : endpoint->parameterReferences)
          if (!parent.parameters.count(reference) && !locals.count(reference))
            return false;
        std::string internal = child.portAliases.empty() ? port : child.portAliases.at(port);
        auto formalVector = child.vectors.find(internal);
        auto actualVector = parent.vectors.find(endpoint->name);
        const bool actualArray = parent.arrays.count(endpoint->name) != 0;
        const bool selected = !endpoint->index.empty();
        const bool part = !endpoint->partSelectEnd.empty();
        if ((!selected && actualArray) || part) {
          if (formalVector == child.vectors.end() || actualVector == parent.vectors.end())
            return false;
        } else if (formalVector != child.vectors.end() || selected != actualArray) {
          return false;
        }
        if (actualVector == parent.vectors.end()) {
          // Compatibility for scalar hierarchy users without vector metadata.
          if (part)
            return false;
          continue;
        }
        auto lower = environments[index].parse(actualVector->second.lower, locals);
        auto upper = environments[index].parse(actualVector->second.upper, locals);
        auto first =
            selected ? environments[index].parse(endpoint->index, locals, structural) : lower;
        auto last = part ? environments[index].parse(endpoint->partSelectEnd, locals, structural)
                    : selected ? first
                               : upper;
        if (lower == Expressions::invalid || upper == Expressions::invalid ||
            first == Expressions::invalid || last == Expressions::invalid)
          return false;
        const auto low = expressions.node(lower), high = expressions.node(upper);
        const auto begin = expressions.node(first), end = expressions.node(last);
        auto outside = [&](std::optional<int64_t> value) {
          return value && ((low.constant && *value < *low.constant) ||
                           (high.constant && *value > *high.constant));
        };
        if (outside(begin.first) || outside(begin.last) || outside(end.first) ||
            outside(end.last) || (begin.lowerAttained && outside(begin.lower)) ||
            (begin.upperAttained && outside(begin.upper)) ||
            (end.lowerAttained && outside(end.lower)) ||
            (end.upperAttained && outside(end.upper)) ||
            (begin.constant && end.constant && *begin.constant > *end.constant))
          return false;
        if (formalVector != child.vectors.end()) {
          auto formalLower = childEnvironment.parse(formalVector->second.lower);
          auto formalUpper = childEnvironment.parse(formalVector->second.upper);
          auto one = expressions.literal(1);
          auto width = expressions.apply('-', expressions.apply('+', last, one), first);
          auto formalWidth =
              expressions.apply('-', expressions.apply('+', formalUpper, one), formalLower);
          if (width == Expressions::invalid || formalWidth == Expressions::invalid ||
              width != formalWidth)
            return false;
        }
      }
      return true;
    };
    std::set<std::string> instanceNames;
    for (const auto &instance : parent.instances)
      if (!instanceNames.insert(instance.name).second || !verifyInstance(instance, {}, false))
        return false;
    std::set<std::string> labels;
    for (const auto &scope : parent.generatedScopes) {
      if (!parent.genvars.count(scope.genvar) || parent.parameters.count(scope.genvar) ||
          parent.nodes.count(scope.genvar) || !labels.insert(scope.label).second ||
          parent.nodes.count(scope.label) || parent.parameters.count(scope.label) ||
          instanceNames.count(scope.label))
        return false;
      Expressions::Environment environment;
      auto count = environments[index].parse(scope.count, {}, true);
      if (count == Expressions::invalid)
        return false;
      auto constantCount = expressions.node(count).constant;
      if (constantCount && *constantCount <= 0)
        return false;
      environment.emplace(
          scope.genvar,
          expressions.symbol(parent.name + "." + scope.label + "." + scope.genvar, 0,
                             constantCount ? std::optional<int64_t>(*constantCount - 1)
                                           : std::nullopt));
      for (const auto &stage : scope.localParameters) {
        if (!stage.fixed || environment.count(stage.name) || parent.nodes.count(stage.name) ||
            parent.parameters.count(stage.name) || parent.genvars.count(stage.name))
          return false;
        auto value = environments[index].parse(stage.initializer, environment, true);
        if (value == Expressions::invalid)
          return false;
        environment.emplace(stage.name, value);
      }
      if (!verifyInstance(scope.instance, environment, true))
        return false;
    }
  }
  return orderHierarchy(edges).status == HierarchyOrder::Status::Success;
}

} // namespace nodal

#endif // NODAL_SUPPORT_ANALOGHIERARCHYSYNTAX_H
