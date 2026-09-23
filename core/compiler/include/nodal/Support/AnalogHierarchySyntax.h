#ifndef NODAL_SUPPORT_ANALOGHIERARCHYSYNTAX_H
#define NODAL_SUPPORT_ANALOGHIERARCHYSYNTAX_H

#include "nodal/Support/HierarchyOrder.h"

#include <cctype>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace nodal {

struct HierarchyInstanceSyntax {
  std::string module;
  std::string name;
  std::map<std::string, std::string> parameters;
  std::map<std::string, std::string> ports;
  std::set<std::string> parameterReferences;
};

/// Recognizes only the named scalar instance grammar emitted by Increment 42.
/// This does not claim to be an independent or general Verilog-A parser.
class HierarchySyntaxParser {
public:
  explicit HierarchySyntaxParser(std::string_view text) : text(text) {}

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
        auto actual = identifier();
        if (!actual || !consume(')') || !result.ports.emplace(*port, *actual).second)
          return std::nullopt;
      } while (consume(','));
    }
    if (!consume(')') || !consume(';') || peek() != '\0')
      return std::nullopt;
    return result;
  }

private:
  std::string_view text;
  std::size_t position = 0;

  void space() {
    while (position < text.size() &&
           std::isspace(static_cast<unsigned char>(text[position])))
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
    } else if (position < text.size() &&
               std::string_view("TGMKkmunpfa").find(text[position]) != std::string_view::npos) {
      ++position;
    }
    return true;
  }
  std::optional<std::string> constant(std::set<std::string> &references) {
    space();
    const auto start = position;
    std::size_t depth = 0;
    bool expectsValue = true;
    bool sawValue = false;
    while (position < text.size()) {
      const char token = peek();
      if (token == ')' && depth == 0)
        break;
      if (expectsValue) {
        if (token == '+' || token == '-') {
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
      } else if (token == '+' || token == '-' || token == '*' || token == '/' || token == '%') {
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

inline std::optional<HierarchyInstanceSyntax> parseHierarchyInstance(std::string_view text) {
  return HierarchySyntaxParser(text).instance();
}

struct HierarchyModuleSyntax {
  std::string name;
  std::set<std::string> ports;
  std::set<std::string> nodes;
  std::set<std::string> parameters;
  std::set<std::string> fixedParameters;
  std::vector<HierarchyInstanceSyntax> instances;
};

/// Verify only the instance boundary; the main backend reparser owns module bodies.
inline bool verifyHierarchySyntax(const std::vector<HierarchyModuleSyntax> &modules) {
  std::map<std::string, std::size_t> indices;
  for (std::size_t index = 0; index < modules.size(); ++index)
    if (!indices.emplace(modules[index].name, index).second)
      return false;
  std::vector<std::vector<std::size_t>> edges(modules.size());
  for (std::size_t index = 0; index < modules.size(); ++index) {
    const auto &parent = modules[index];
    std::set<std::string> instanceNames;
    for (const auto &instance : parent.instances) {
      auto found = indices.find(instance.module);
      if (found == indices.end() || !instanceNames.insert(instance.name).second ||
          parent.nodes.count(instance.name) || parent.parameters.count(instance.name))
        return false;
      const auto &child = modules[found->second];
      edges[index].push_back(found->second);
      if (instance.ports.size() != child.ports.size())
        return false;
      for (const auto &[port, actual] : instance.ports)
        if (!child.ports.count(port) || !parent.nodes.count(actual))
          return false;
      for (const auto &[parameter, value] : instance.parameters) {
        (void)value;
        if (!child.parameters.count(parameter) || child.fixedParameters.count(parameter))
          return false;
      }
      for (const auto &reference : instance.parameterReferences)
        if (!parent.parameters.count(reference))
          return false;
    }
  }
  return orderHierarchy(edges).status == HierarchyOrder::Status::Success;
}

} // namespace nodal

#endif // NODAL_SUPPORT_ANALOGHIERARCHYSYNTAX_H
