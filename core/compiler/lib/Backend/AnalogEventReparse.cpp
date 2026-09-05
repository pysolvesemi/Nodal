#include "nodal/Backend/AnalogEventBackend.h"

#include "llvm/ADT/StringExtras.h"
#include "llvm/Support/Regex.h"

#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <string>

using namespace mlir;
namespace nodal {
namespace {
// Independent recursive-descent check of the emitted subset. In particular,
// balanced parentheses alone must not accept extra tasks or raw source text.
class Parser {
public:
  explicit Parser(llvm::StringRef source) : source(source) { next(); }
  FailureOr<size_t> run() {
    if (!block(0))
      return failure();
    return tokenStart;
  }

private:
  llvm::StringRef source, token;
  size_t cursor = 0, tokenStart = 0;
  bool invalid = false;
  unsigned eventDepth = 0;
  void next() {
    while (cursor < source.size() && llvm::isSpace(source[cursor]))
      ++cursor;
    tokenStart = cursor;
    if (cursor == source.size()) {
      token = {};
      return;
    }
    char c = source[cursor++];
    if (llvm::isAlpha(c) || c == '_' || c == '$') {
      while (cursor < source.size() &&
             (llvm::isAlnum(source[cursor]) || source[cursor] == '_' || source[cursor] == '$'))
        ++cursor;
    } else if (llvm::isDigit(c) || c == '.') {
      std::string tail = source.drop_front(tokenStart).str();
      char *end = nullptr;
      errno = 0;
      double number = std::strtod(tail.c_str(), &end);
      if (end == tail.c_str() || errno == ERANGE || !std::isfinite(number))
        invalid = true;
      else {
        cursor = tokenStart + static_cast<size_t>(end - tail.c_str());
        if (!llvm::Regex("^[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$")
                 .match(source.slice(tokenStart, cursor)))
          invalid = true;
      }
    } else if (c == '"') {
      while (cursor < source.size() && source[cursor] != '"') {
        if (source[cursor] == '\\' || source[cursor] < 0x20)
          invalid = true;
        ++cursor;
      }
      if (cursor == source.size())
        invalid = true;
      else
        ++cursor;
    } else if (cursor < source.size()) {
      auto pair = source.substr(tokenStart, 2);
      if (pair == "&&" || pair == "||" || pair == "<=" || pair == ">=" || pair == "==" ||
          pair == "!=")
        ++cursor;
    }
    token = source.slice(tokenStart, cursor);
  }
  bool eat(llvm::StringRef value) {
    if (invalid || token != value)
      return false;
    next();
    return true;
  }
  bool identifier() {
    if (invalid || token.empty() || !(llvm::isAlpha(token.front()) || token.front() == '_'))
      return false;
    next();
    return true;
  }
  bool string(bool analysis) {
    if (invalid || token.size() < 2 || token.front() != '"' || token.back() != '"')
      return false;
    auto value = token.drop_front().drop_back();
    if (analysis && (value.empty() || !llvm::isAlpha(value.front()) ||
                     !llvm::all_of(value, [](char c) { return llvm::isAlnum(c) || c == '_'; })))
      return false;
    next();
    return true;
  }
  bool primary(unsigned depth) {
    if (invalid || depth > 256)
      return false;
    if (eat("-") || eat("+") || eat("!"))
      return primary(depth + 1);
    if (eat("("))
      return expression(depth + 1) && eat(")");
    if (!token.empty() && (llvm::isDigit(token.front()) || token.front() == '.')) {
      next();
      return !invalid;
    }
    auto name = token;
    if (!identifier())
      return false;
    if (token != "(")
      return true;
    if (name != "V" && name != "I")
      return false;
    if (!eat("(") || !identifier())
      return false;
    if (eat(",") && !identifier())
      return false;
    return eat(")");
  }
  bool expression(unsigned depth = 0) {
    if (!primary(depth))
      return false;
    while (token == "+" || token == "-" || token == "*" || token == "/" || token == ">" ||
           token == "<" || token == ">=" || token == "<=" || token == "&&" || token == "||" ||
           token == "==" || token == "!=") {
      next();
      if (!primary(depth + 1))
        return false;
    }
    if (eat("?"))
      return expression(depth + 1) && eat(":") && expression(depth + 1);
    return true;
  }
  bool event() {
    do {
      auto function = token;
      if (!identifier())
        return false;
      bool lifecycle = function == "initial_step" || function == "final_step";
      unsigned maximum = function == "cross" ? 5 : 4;
      if (!lifecycle && function != "cross" && function != "above" && function != "timer")
        return false;
      if (!eat("(")) {
        if (!lifecycle)
          return false;
      } else {
        unsigned count = 0;
        do {
          if (lifecycle ? !string(true) : !expression())
            return false;
          ++count;
        } while (eat(","));
        if ((!lifecycle && count > maximum) || !eat(")"))
          return false;
      }
    } while (eat("or"));
    return true;
  }
  bool assignment() { return identifier() && eat("=") && expression(); }
  bool block(unsigned depth) {
    if (depth > 256 || !eat("begin"))
      return false;
    if (eat(":") && !identifier())
      return false;
    while (token != "end")
      if (!statement(depth + 1))
        return false;
    return eat("end");
  }
  bool statement(unsigned depth) {
    if (depth > 256 || invalid)
      return false;
    if (token == "begin")
      return block(depth);
    if (eat("@")) {
      if (eventDepth || !eat("(") || !event() || !eat(")"))
        return false;
      ++eventDepth;
      bool valid = block(depth + 1);
      --eventDepth;
      return valid;
    }
    if (eat("if")) {
      if (!eat("(") || !expression() || !eat(")") || !statement(depth + 1))
        return false;
      return !eat("else") || statement(depth + 1);
    }
    if (eat("case")) {
      if (!eat("(") || !expression() || !eat(")"))
        return false;
      bool sawDefault = false;
      while (token != "endcase") {
        if (eat("default")) {
          if (sawDefault)
            return false;
          sawDefault = true;
        } else {
          do {
            eat("-");
            if (token.empty() || !llvm::isDigit(token.front()))
              return false;
            next();
          } while (eat(","));
        }
        if (!eat(":") || !block(depth + 1))
          return false;
      }
      return eat("endcase");
    }
    if (eat("for"))
      return eat("(") && assignment() && eat(";") && expression() && eat(";") && assignment() &&
             eat(")") && block(depth + 1);
    if (eat("$strobe"))
      return eat("(") && string(false) && eat(")") && eat(";");
    if (eat("$finish"))
      return eat(";");
    return assignment() && eat(";");
  }
};
} // namespace
FailureOr<size_t> reparseAnalogEventBlock(llvm::StringRef source) { return Parser(source).run(); }
} // namespace nodal
