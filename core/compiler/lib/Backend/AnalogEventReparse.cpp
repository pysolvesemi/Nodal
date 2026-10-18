#include "nodal/Backend/AnalogEventBackend.h"
#include "nodal/Backend/AnalogUserFunctionBackend.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Dialect/Nodal/AnalogFunctions.h"

#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/Regex.h"

#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <limits>
#include <optional>
#include <string>
#include <vector>

using namespace mlir;
namespace nodal {
namespace {
// These spellings are a target ABI, not a comment or an externally supplied
// trust bit. The declaration and every output actual independently check them.
std::optional<llvm::StringRef> privateOrdinal(llvm::StringRef name, llvm::StringRef prefix) {
  if (!name.consume_front(prefix) || !name.consume_front("_") || name.empty() ||
      (name.size() > 1 && name.front() == '0') || !llvm::all_of(name, llvm::isDigit))
    return std::nullopt;
  return name;
}
bool privateFunctionName(llvm::StringRef name) {
  return name == "event_live_store" || privateOrdinal(name, "event_live_store").has_value();
}
constexpr uint64_t eventWrapperLimit = 4096, moduleWrapperLimit = 65536;
constexpr uint64_t eventByteLimit = 1024 * 1024, moduleByteLimit = 16 * 1024 * 1024;
// Independent recursive-descent check of the emitted subset. In particular,
// balanced parentheses alone must not accept extra tasks or raw source text.
class Parser {
public:
  explicit Parser(llvm::StringRef source, const llvm::StringMap<unsigned> *functions = nullptr,
                  const AnalogEventSymbols *symbols = nullptr,
                  AnalogEventPrivateFunctions *privateFunctions = nullptr)
      : source(source), functions(functions), symbols(symbols), privateFunctions(privateFunctions),
        invalid(source.contains('\0')) {
    next();
  }
  FailureOr<size_t> run() {
    if (!block(0))
      return failure();
    if (privateFunctions) {
      privateFunctions->demandWrappers += blockWrappers;
      privateFunctions->expressionBytes += blockExpressionBytes;
    }
    return tokenStart;
  }

  bool standaloneExpression() {
    transferExpressions = true;
    return expression() && token.empty() && !invalid;
  }

  bool declaration(AnalogEventSymbols &declared) {
    const bool real = token == "real";
    if (!eat("real") && !eat("integer") && !eat("genvar"))
      return false;
    auto name = token;
    if (!identifier() || declared.contains(name))
      return false;
    AnalogEventSymbol symbol{real ? AnalogEventSymbolKind::Real : AnalogEventSymbolKind::Integer,
                             true, std::nullopt, std::nullopt};
    const bool privateCache = privateOrdinal(name, "event_live_cache").has_value();
    const bool privateReady = privateOrdinal(name, "event_live_ready").has_value();
    if (privateCache || privateReady) {
      // Arrays, initialized storage, genvars and wrong types never acquire the
      // compiler-private output capability merely through a similar name.
      if (privateCache != real || token != ";" || source.ltrim().starts_with("genvar"))
        return false;
      symbol.privateLive = true;
    }
    if (eat("[")) {
      size_t extentIdentity = 0;
      if (!real || !integerExpression(symbol.lowerBound) || !symbol.lowerBound ||
          *symbol.lowerBound != 0 || !eat(":") ||
          !integerExpression(symbol.upperBound, 0, &extentIdentity) ||
          (symbol.upperBound &&
           (*symbol.upperBound < 0 || *symbol.upperBound >= std::numeric_limits<int32_t>::max())) ||
          !eat("]"))
        return false;
      symbol.kind = AnalogEventSymbolKind::RealArray;
      if (eat("=")) {
        // Only this declaration form has an immutable, parameter-only fill.
        // Ordinary scalar initializers and procedural expressions keep their
        // existing grammar. Bounds must also be static when initialized.
        staticExpression = true;
        std::optional<int64_t> count;
        size_t countIdentity = 0;
        CheckedValue value;
        if (mutableIntegerReference || !eat("'") || !eat("{") ||
            !integerExpression(count, 0, &countIdentity) || (count && *count <= 0) ||
            !initializerExtentMatches(symbol.upperBound, extentIdentity, count, countIdentity) ||
            !eat("{") || !expression(0, &value) || !eat("}") || !eat("}"))
          return false;
      }
    } else if (eat("=")) {
      CheckedValue value;
      if (!expression(0, real ? nullptr : &value))
        return false;
    }
    if (!eat(";") || !token.empty() || invalid)
      return false;
    return declared.try_emplace(name, symbol).second;
  }

  FailureOr<size_t> functionDeclaration(llvm::StringMap<unsigned> &known) {
    pureFunction = true;
    if (!eat("analog") || !eat("function") || (!eat("real") && !eat("integer")))
      return failure();
    auto functionName = token;
    if (!identifier() || known.contains(functionName) || !eat(";") || !eat("input"))
      return failure();
    llvm::StringSet<> inputs, declared, locals, assigned;
    do {
      auto name = token;
      if (name == functionName || known.contains(name) || !identifier() ||
          !inputs.insert(name).second)
        return failure();
    } while (eat(","));
    if (!eat(";") || inputs.empty())
      return failure();
    while (token == "real" || token == "integer") {
      next();
      auto name = token;
      if (name == functionName || known.contains(name) || !identifier() || !eat(";") ||
          !declared.insert(name).second)
        return failure();
      if (!inputs.contains(name))
        locals.insert(name);
    }
    for (const auto &input : inputs)
      if (!declared.contains(input.getKey()))
        return failure();
    if (!eat("begin"))
      return failure();
    for (const auto &input : inputs)
      available.insert(input.getKey());
    bool returned = false;
    while (token != "end") {
      if (returned)
        return failure();
      auto name = token;
      if (!identifier() || (name != functionName && !locals.contains(name)) ||
          !assigned.insert(name).second || !eat("=") || !expression() || !eat(";"))
        return failure();
      available.insert(name);
      returned = name == functionName;
    }
    if (!returned || assigned.size() != locals.size() + 1 || !eat("end") || !eat("endfunction"))
      return failure();
    known.try_emplace(functionName, static_cast<unsigned>(inputs.size()));
    return tokenStart;
  }

  FailureOr<size_t> privateFunctionDeclaration(AnalogEventPrivateFunctions &known) {
    if (!eat("analog") || !eat("function") || !eat("real"))
      return failure();
    auto name = token;
    if (!privateFunctionName(name) || known.names.contains(name) || !identifier())
      return failure();
    // Exact input/output directions, scalar types, assignment order and return.
    // No expression parsing fallback can admit probes, globals, recursive calls,
    // extra locals or additional effects in this compiler-private helper.
    for (llvm::StringRef expected :
         {";",      "input", "value",   ";",     "output", "cache", ";",
          "output", "ready", ";",       "real",  "value",  ";",     "real",
          "cache",  ";",     "integer", "ready", ";",      "begin", "cache",
          "=",      "value", ";",       "ready", "=",      "1",     ";"})
      if (!eat(expected))
        return failure();
    if (!eat(name) || !eat("=") || !eat("value") || !eat(";") || !eat("end") ||
        !eat("endfunction") || invalid)
      return failure();
    known.names.insert(name);
    return tokenStart;
  }

  bool transferCall() {
    transferExpressions = true;
    auto function = token;
    if ((function != "laplace_nd" && function != "zi_nd") || !eat(function) || !eat("(") ||
        !expression())
      return false;
    for (unsigned array = 0; array < 2; ++array) {
      if (!eat(",") || !eat("'") || !eat("{"))
        return false;
      do {
        if (!expression())
          return false;
      } while (eat(","));
      if (!eat("}"))
        return false;
    }
    unsigned timing = 0;
    while (eat(",")) {
      if (++timing > 3 || !expression())
        return false;
    }
    if ((function == "laplace_nd" && timing != 0) || (function == "zi_nd" && timing == 0))
      return false;
    return eat(")") && token.empty() && !invalid;
  }

  bool noiseCall() {
    auto function = token;
    if ((function != "white_noise" && function != "flicker_noise" && function != "noise_table") ||
        !eat(function) || !eat("("))
      return false;
    if (function == "noise_table") {
      if (!eat("'") || !eat("{"))
        return false;
      unsigned count = 0;
      do {
        if (!expression())
          return false;
        ++count;
      } while (eat(","));
      if (!count || count % 2 || !eat("}"))
        return false;
    } else {
      if (!expression())
        return false;
      if (function == "flicker_noise" && (!eat(",") || !expression()))
        return false;
    }
    return eat(",") && token.size() > 2 && string(false) && eat(")") && token.empty() && !invalid;
  }

private:
  llvm::StringRef source, token;
  const llvm::StringMap<unsigned> *functions;
  const AnalogEventSymbols *symbols;
  AnalogEventPrivateFunctions *privateFunctions;
  llvm::StringSet<> available;
  bool pureFunction = false;
  size_t cursor = 0, tokenStart = 0;
  bool invalid = false;
  bool transferExpressions = false;
  bool staticExpression = false;
  bool mutableIntegerReference = false;
  bool eventExpressions = false;
  size_t eventStart = 0;
  uint64_t eventWrappers = 0;
  uint64_t blockWrappers = 0, blockExpressionBytes = 0;
  std::vector<llvm::StringRef> liveInputAccesses;
  llvm::StringMap<llvm::StringRef> liveBodies;
  struct IntegerIdentity {
    std::optional<int64_t> constant;
    llvm::StringRef parameter;
    char operation = 0;
    size_t left = 0, right = 0;
  };
  // Only declaration bounds/counts request identities. Keep a linear arena
  // rather than repeatedly copying an ever-growing symbolic expression string.
  std::vector<IntegerIdentity> integerIdentities;
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
      // Copy only the numeric token, not the whole remaining module for every
      // literal in an admitted (potentially megabyte-sized) event expression.
      while (cursor < source.size() && (llvm::isDigit(source[cursor]) || source[cursor] == '.'))
        ++cursor;
      if (cursor < source.size() && (source[cursor] == 'e' || source[cursor] == 'E')) {
        ++cursor;
        if (cursor < source.size() && (source[cursor] == '+' || source[cursor] == '-'))
          ++cursor;
        while (cursor < source.size() && llvm::isDigit(source[cursor]))
          ++cursor;
      }
      if (eventExpressions && privateFunctions && cursor - eventStart > eventByteLimit) {
        invalid = true;
        token = source.slice(tokenStart, cursor);
        return;
      }
      std::string tail = source.slice(tokenStart, cursor).str();
      char *end = nullptr;
      errno = 0;
      double number = std::strtod(tail.c_str(), &end);
      if (end != tail.c_str() + tail.size() || errno == ERANGE || !std::isfinite(number))
        invalid = true;
      else {
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
    if (eventExpressions && privateFunctions && tokenStart - eventStart > eventByteLimit)
      invalid = true;
  }
  bool eat(llvm::StringRef value) {
    if (invalid || token != value)
      return false;
    next();
    return true;
  }
  bool identifier() {
    if (invalid || !isPortableVerilogIdentifier(token))
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
  // Shape maps have integer arithmetic, not the general analog-expression
  // grammar. Concrete checks here are independent of the native shape proof;
  // symbolic expressions retain their identifiers without substituting defaults.
  static bool portableInteger(int64_t value) {
    return value >= std::numeric_limits<int32_t>::min() &&
           value <= std::numeric_limits<int32_t>::max();
  }
  size_t rememberIntegerIdentity(const std::optional<int64_t> &constant,
                                 llvm::StringRef parameter = {}, char operation = 0,
                                 size_t left = 0, size_t right = 0) {
    // Fold only fully concrete checked arithmetic. Never substitute parameter
    // defaults or rewrite symbolic expressions by algebraic equivalence.
    integerIdentities.push_back({constant, constant ? llvm::StringRef() : parameter,
                                 constant ? char(0) : operation, left, right});
    return integerIdentities.size() - 1;
  }
  bool sameIntegerIdentity(size_t left, size_t right) const {
    std::vector<std::pair<size_t, size_t>> pending{{left, right}};
    while (!pending.empty()) {
      auto pair = pending.back();
      pending.pop_back();
      const auto &lhs = integerIdentities[pair.first];
      const auto &rhs = integerIdentities[pair.second];
      if (lhs.constant != rhs.constant || lhs.parameter != rhs.parameter ||
          lhs.operation != rhs.operation)
        return false;
      if (!lhs.operation)
        continue;
      pending.emplace_back(lhs.left, rhs.left);
      if (lhs.operation != '~')
        pending.emplace_back(lhs.right, rhs.right);
    }
    return true;
  }
  bool initializerExtentMatches(const std::optional<int64_t> &upper, size_t extentIdentity,
                                const std::optional<int64_t> &count, size_t countIdentity) const {
    if (upper || count)
      return upper && count && *upper + 1 == *count;
    // Symbolic declarations are emitted as [0:(COUNT - 1)]. Compare the
    // parsed COUNT subtree exactly, ignoring only parentheses and concrete
    // folding. Correlated symbolic bounds remain the native verifier's proof.
    const auto &extent = integerIdentities[extentIdentity];
    return extent.operation == '-' && integerIdentities[extent.right].constant == 1 &&
           sameIntegerIdentity(extent.left, countIdentity);
  }
  bool integerPrimary(std::optional<int64_t> &value, unsigned depth, size_t *identity = nullptr) {
    value.reset();
    if (invalid || depth > 256)
      return false;
    if (token == "+" || token == "-") {
      const bool negative = token == "-";
      next();
      // Parse a signed literal together so INT32_MIN remains representable.
      if (!token.empty() && llvm::all_of(token, llvm::isDigit)) {
        int64_t literal = 0;
        if (token.getAsInteger(10, literal))
          return false;
        value = negative ? -literal : literal;
        next();
        if (identity)
          *identity = rememberIntegerIdentity(value);
        return portableInteger(*value);
      }
      if (!integerPrimary(value, depth + 1, identity))
        return false;
      if (negative) {
        if (value)
          value = -*value;
        if (identity)
          *identity = rememberIntegerIdentity(value, {}, '~', *identity);
      }
      return !value || portableInteger(*value);
    }
    if (eat("("))
      return integerExpression(value, depth + 1, identity) && eat(")");
    if (!token.empty() && llvm::all_of(token, llvm::isDigit)) {
      int64_t literal = 0;
      if (token.getAsInteger(10, literal) || !portableInteger(literal))
        return false;
      value = literal;
      next();
      if (identity)
        *identity = rememberIntegerIdentity(value);
      return true;
    }
    auto name = token;
    if (!identifier() || !symbols)
      return false;
    auto symbol = symbols->find(name);
    if (symbol == symbols->end() || symbol->second.kind != AnalogEventSymbolKind::Integer)
      return false;
    mutableIntegerReference |= symbol->second.writable;
    if (staticExpression && symbol->second.writable)
      return false;
    if (eventExpressions)
      liveInputAccesses.push_back(name);
    if (identity)
      *identity = rememberIntegerIdentity(value, name);
    return true;
  }
  bool integerArithmetic(std::optional<int64_t> &left, char operation,
                         const std::optional<int64_t> &right) {
    if ((operation == '/' || operation == '%') && right && *right == 0)
      return false;
    if (!left || !right) {
      left.reset();
      return true;
    }
    if ((operation == '/' || operation == '%') && *left == std::numeric_limits<int32_t>::min() &&
        *right == -1)
      return false;
    // Both operands have already passed the signed-32 check, so these
    // intermediates are representable in int64_t even for multiplication.
    int64_t result = operation == '+'   ? *left + *right
                     : operation == '-' ? *left - *right
                     : operation == '*' ? *left * *right
                     : operation == '/' ? *left / *right
                                        : *left % *right;
    if (!portableInteger(result))
      return false;
    left = result;
    return true;
  }
  bool integerProduct(std::optional<int64_t> &value, unsigned depth, size_t *identity = nullptr) {
    if (!integerPrimary(value, depth, identity))
      return false;
    while (token == "*" || token == "/" || token == "%") {
      char operation = token.front();
      next();
      std::optional<int64_t> right;
      size_t rightIdentity = 0;
      if (!integerPrimary(right, depth + 1, identity ? &rightIdentity : nullptr) ||
          !integerArithmetic(value, operation, right))
        return false;
      if (identity)
        *identity = rememberIntegerIdentity(value, {}, operation, *identity, rightIdentity);
    }
    return true;
  }
  bool integerExpression(std::optional<int64_t> &value, unsigned depth = 0,
                         size_t *identity = nullptr) {
    if (!integerProduct(value, depth, identity))
      return false;
    while (token == "+" || token == "-") {
      char operation = token.front();
      next();
      std::optional<int64_t> right;
      size_t rightIdentity = 0;
      if (!integerProduct(right, depth + 1, identity ? &rightIdentity : nullptr) ||
          !integerArithmetic(value, operation, right))
        return false;
      if (identity)
        *identity = rememberIntegerIdentity(value, {}, operation, *identity, rightIdentity);
    }
    return true;
  }
  bool indexed(const AnalogEventSymbol &symbol, unsigned depth) {
    std::optional<int64_t> value;
    return eat("[") && integerExpression(value, depth + 1) && eat("]") &&
           (!value || ((!symbol.lowerBound || *value >= *symbol.lowerBound) &&
                       (!symbol.upperBound || *value <= *symbol.upperBound)));
  }
  bool storage(llvm::StringRef name, bool write, unsigned depth) {
    if (!symbols)
      return !pureFunction || available.contains(name);
    auto found = symbols->find(name);
    if (found == symbols->end() || (write && !found->second.writable))
      return false;
    const auto &symbol = found->second;
    if (staticExpression && (symbol.writable || (symbol.kind != AnalogEventSymbolKind::Real &&
                                                 symbol.kind != AnalogEventSymbolKind::Integer)))
      return false;
    if (eventExpressions)
      liveInputAccesses.push_back(name);
    if (symbol.kind == AnalogEventSymbolKind::RealArray)
      return indexed(symbol, depth);
    return token != "[" && (symbol.kind == AnalogEventSymbolKind::Real ||
                            symbol.kind == AnalogEventSymbolKind::Integer);
  }
  bool terminal(unsigned depth) {
    auto name = token;
    if (!identifier())
      return false;
    if (!symbols)
      return true;
    auto found = symbols->find(name);
    if (found == symbols->end())
      return false;
    if (found->second.kind == AnalogEventSymbolKind::TerminalArray)
      return indexed(found->second, depth);
    return token != "[" && found->second.kind == AnalogEventSymbolKind::Terminal;
  }
  struct CheckedValue {
    bool integer = false;
    std::optional<int64_t> constant;
  };
  static bool sameExpressionText(llvm::StringRef left, llvm::StringRef right) {
    // The independently parsed grammar has no escaped strings. Preserve string
    // contents, but permit harmless formatting differences between expansions.
    bool quoted = false;
    while (true) {
      if (!quoted) {
        left = left.ltrim();
        right = right.ltrim();
      }
      if (left.empty() || right.empty())
        return left.empty() && right.empty();
      if (left.front() != right.front())
        return false;
      if (left.front() == '"')
        quoted = !quoted;
      left = left.drop_front();
      right = right.drop_front();
    }
  }
  bool privateCall(unsigned depth) {
    if (!eventExpressions || !symbols || !privateFunctions || ++eventWrappers > eventWrapperLimit ||
        !eat("("))
      return false;
    const size_t inputStart = tokenStart, accessStart = liveInputAccesses.size();
    if (!expression(depth + 1))
      return false;
    auto input = source.slice(inputStart, tokenStart).trim();
    if (!eat(","))
      return false;
    auto cache = token;
    if (!identifier() || !eat(","))
      return false;
    auto ready = token;
    if (!identifier() || !eat(")"))
      return false;
    auto cacheOrdinal = privateOrdinal(cache, "event_live_cache");
    auto readyOrdinal = privateOrdinal(ready, "event_live_ready");
    auto cacheSymbol = symbols->find(cache), readySymbol = symbols->find(ready);
    if (!cacheOrdinal || !readyOrdinal || *cacheOrdinal != *readyOrdinal || cache == ready ||
        cacheSymbol == symbols->end() || readySymbol == symbols->end())
      return false;
    auto validOutput = [](const AnalogEventSymbol &symbol, AnalogEventSymbolKind kind) {
      return symbol.privateLive && symbol.writable && symbol.kind == kind && !symbol.lowerBound &&
             !symbol.upperBound;
    };
    if (!validOutput(cacheSymbol->second, AnalogEventSymbolKind::Real) ||
        !validOutput(readySymbol->second, AnalogEventSymbolKind::Integer))
      return false;
    // The numeric input must neither read nor write this call's outputs. The
    // log includes array-index reads and all descendant calls' copy-out effects.
    for (size_t index = accessStart; index < liveInputAccesses.size(); ++index)
      if (liveInputAccesses[index] == cache || liveInputAccesses[index] == ready)
        return false;
    auto inserted = liveBodies.try_emplace(cache, input);
    if (!inserted.second && !sameExpressionText(inserted.first->second, input))
      return false;
    liveInputAccesses.push_back(cache);
    liveInputAccesses.push_back(ready);
    return true;
  }
  bool primary(unsigned depth, CheckedValue *value = nullptr) {
    if (value)
      *value = {};
    if (invalid || depth > 256)
      return false;
    if (token == "-" || token == "+" || token == "!") {
      auto operation = token;
      next();
      // As in index expressions, the signed literal INT32_MIN is valid even
      // though its unsigned spelling is not a portable positive integer.
      if (value && operation != "!" && !token.empty() && llvm::all_of(token, llvm::isDigit)) {
        int64_t literal = 0;
        if (token.getAsInteger(10, literal))
          return false;
        *value = {true, operation == "-" ? -literal : literal};
        next();
        return portableInteger(*value->constant);
      }
      if (!primary(depth + 1, value))
        return false;
      if (value && operation == "!") {
        value->integer = true;
        if (value->constant)
          value->constant = !*value->constant;
      } else if (value && operation == "-" && value->constant) {
        value->constant = -*value->constant;
        if (value->integer && !portableInteger(*value->constant))
          return false;
      }
      return true;
    }
    if (eat("("))
      return expression(depth + 1, value) && eat(")");
    if (!token.empty() && (llvm::isDigit(token.front()) || token.front() == '.')) {
      if (value) {
        value->integer = llvm::all_of(token, llvm::isDigit);
        if (value->integer) {
          int64_t literal = 0;
          if (token.getAsInteger(10, literal) || !portableInteger(literal))
            return false;
          value->constant = literal;
        } else {
          // Retain exact small integral Real literals for concrete zero tests;
          // Real arithmetic does not acquire signed-integer overflow rules.
          double literal = std::strtod(token.str().c_str(), nullptr);
          if (literal >= std::numeric_limits<int32_t>::min() &&
              literal <= std::numeric_limits<int32_t>::max() && std::trunc(literal) == literal)
            value->constant = static_cast<int64_t>(literal);
        }
      }
      next();
      return !invalid;
    }
    if (transferExpressions && eat("$abstime"))
      return true;
    auto name = token;
    // Reserved analog names are legal only in the corresponding call grammar,
    // never as bare values, port references, assignment targets, or labels.
    if (!isPortableVerilogIdentifier(name)) {
      bool builtin = name == "analysis" || lookupAnalogFunctionTarget(name) ||
                     (transferExpressions && (name == "ddt" || name == "idt"));
      if (!builtin || !eat(name) || token != "(")
        return false;
    } else if (!identifier()) {
      return false;
    }
    if (token != "(") {
      if (!storage(name, false, depth))
        return false;
      if (value)
        value->integer = symbols->find(name)->second.kind == AnalogEventSymbolKind::Integer;
      return true;
    }
    if (staticExpression && !lookupAnalogFunctionTarget(name))
      return false;
    if (privateFunctions && privateFunctions->names.contains(name))
      return privateCall(depth);
    CheckedValue argument;
    auto argumentExpression = [&]() { return expression(depth + 1, value ? &argument : nullptr); };
    if (functions) {
      auto function = functions->find(name);
      if (function != functions->end()) {
        if (!eat("("))
          return false;
        for (unsigned index = 0; index < function->second; ++index)
          if ((index && !eat(",")) || !argumentExpression())
            return false;
        return eat(")");
      }
    }
    if (pureFunction && !lookupAnalogFunctionTarget(name))
      return false;
    if (transferExpressions && (name == "ddt" || name == "idt")) {
      if (!eat("(") || !argumentExpression())
        return false;
      if (name == "idt" && eat(",") && !argumentExpression())
        return false;
      return eat(")");
    }
    if (name == "analysis") {
      if (!eat("(") || token.size() < 2 || token.front() != '"' || token.back() != '"' ||
          !isAnalogAnalysisTarget(token.drop_front().drop_back()) || !string(true))
        return false;
      if (value)
        value->integer = true;
      return eat(")");
    }
    if (auto *entry = lookupAnalogFunctionTarget(name)) {
      if (!eat("("))
        return false;
      for (unsigned i = 0; i < entry->arity; ++i)
        if ((i && !eat(",")) || !argumentExpression())
          return false;
      return eat(")");
    }
    if (name != "V" && name != "I")
      return false;
    if (!eat("("))
      return false;
    if (transferExpressions && name == "I" && eat("<"))
      return identifier() && eat(">") && eat(")");
    if (!terminal(depth))
      return false;
    if (eat(",") && !terminal(depth))
      return false;
    return eat(")");
  }
  unsigned precedence(bool checked) const {
    if (token == "||")
      return 1;
    if (token == "&&")
      return 2;
    if (token == "==" || token == "!=")
      return 3;
    if (token == ">" || token == "<" || token == ">=" || token == "<=")
      return 4;
    if (token == "+" || token == "-")
      return 5;
    if (token == "*" || token == "/" || (checked && token == "%"))
      return 6;
    return 0;
  }
  bool checkedBinary(CheckedValue &left, llvm::StringRef operation, const CheckedValue &right) {
    if (operation == "+" || operation == "-" || operation == "*" || operation == "/" ||
        operation == "%") {
      if ((operation == "/" || operation == "%") && right.constant && *right.constant == 0)
        return false;
      if (left.integer && right.integer)
        return integerArithmetic(left.constant, operation.front(), right.constant);
      if (operation == "%")
        return false;
      left = {};
      return true;
    }
    std::optional<int64_t> result;
    if (left.constant && right.constant) {
      int64_t lhs = *left.constant, rhs = *right.constant;
      result = operation == ">"    ? lhs > rhs
               : operation == "<"  ? lhs < rhs
               : operation == ">=" ? lhs >= rhs
               : operation == "<=" ? lhs <= rhs
               : operation == "==" ? lhs == rhs
               : operation == "!=" ? lhs != rhs
               : operation == "&&" ? lhs && rhs
                                   : lhs || rhs;
    }
    left = {true, result};
    return true;
  }
  bool binaryExpression(unsigned depth, CheckedValue *value, unsigned minimum = 1) {
    if (!primary(depth, value))
      return false;
    while (unsigned priority = precedence(value != nullptr)) {
      if (priority < minimum)
        break;
      auto operation = token;
      next();
      CheckedValue right;
      if (!binaryExpression(depth + 1, value ? &right : nullptr, priority + 1) ||
          (value && !checkedBinary(*value, operation, right)))
        return false;
    }
    return true;
  }
  bool expression(unsigned depth = 0, CheckedValue *value = nullptr) {
    if (!binaryExpression(depth, value))
      return false;
    if (eat("?")) {
      CheckedValue yes, no;
      if (!expression(depth + 1, value ? &yes : nullptr) || !eat(":") ||
          !expression(depth + 1, value ? &no : nullptr))
        return false;
      if (value) {
        auto selected = value->constant ? (*value->constant ? yes.constant : no.constant)
                                        : std::optional<int64_t>();
        *value = {yes.integer && no.integer, selected};
      }
    }
    return true;
  }
  bool event() {
    eventExpressions = true;
    eventStart = tokenStart;
    eventWrappers = 0;
    liveInputAccesses.clear();
    liveBodies.clear();
    do {
      auto function = token;
      bool lifecycle = function == "initial_step" || function == "final_step";
      unsigned maximum = function == "cross" ? 5 : 4;
      if ((!lifecycle && function != "cross" && function != "above" && function != "timer") ||
          !eat(function))
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
    eventExpressions = false;
    if (privateFunctions && eventWrappers) {
      const uint64_t bytes = tokenStart - eventStart;
      if (bytes > eventByteLimit || privateFunctions->demandWrappers > moduleWrapperLimit ||
          blockWrappers + eventWrappers > moduleWrapperLimit - privateFunctions->demandWrappers ||
          privateFunctions->expressionBytes > moduleByteLimit ||
          blockExpressionBytes + bytes > moduleByteLimit - privateFunctions->expressionBytes)
        return false;
      blockWrappers += eventWrappers;
      blockExpressionBytes += bytes;
    }
    return true;
  }
  bool assignment(llvm::StringRef *loopCounter = nullptr) {
    auto name = token;
    if (symbols && loopCounter) {
      auto found = symbols->find(name);
      if (found == symbols->end() || found->second.kind != AnalogEventSymbolKind::Integer ||
          (!loopCounter->empty() && *loopCounter != name))
        return false;
      *loopCounter = name;
    }
    if (!identifier() || !storage(name, true, 0) || !eat("="))
      return false;
    // Boolean storage also uses target Integer. Validate all arithmetic inside
    // its comparisons and conditionals in the same pass; no unchecked retry may
    // turn a rejected integer map into an accepted scalar expression.
    CheckedValue value;
    const bool checked =
        symbols && symbols->find(name)->second.kind == AnalogEventSymbolKind::Integer;
    return expression(0, checked ? &value : nullptr);
  }
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
    if (eat("for")) {
      llvm::StringRef counter;
      return eat("(") && assignment(&counter) && eat(";") && expression() && eat(";") &&
             assignment(&counter) && eat(")") && block(depth + 1);
    }
    if (eat("$strobe"))
      return eat("(") && string(false) && eat(")") && eat(";");
    if (eat("$finish"))
      return eat(";");
    return assignment() && eat(";");
  }
};
} // namespace
FailureOr<size_t> reparseAnalogEventBlock(llvm::StringRef source, const AnalogEventSymbols *symbols,
                                          AnalogEventPrivateFunctions *privateFunctions) {
  return Parser(source, nullptr, symbols, privateFunctions).run();
}
FailureOr<size_t> reparseAnalogEventPrivateFunction(llvm::StringRef source,
                                                    AnalogEventPrivateFunctions &functions) {
  return Parser(source).privateFunctionDeclaration(functions);
}
LogicalResult reparseAnalogEventDeclaration(llvm::StringRef source, AnalogEventSymbols &symbols) {
  return success(Parser(source, nullptr, &symbols).declaration(symbols));
}
LogicalResult reparseAnalogTransferCall(llvm::StringRef source,
                                        const llvm::StringMap<unsigned> *functions) {
  return success(Parser(source, functions).transferCall());
}
FailureOr<size_t> reparseAnalogUserFunction(llvm::StringRef source,
                                            llvm::StringMap<unsigned> &functions) {
  return Parser(source, &functions).functionDeclaration(functions);
}
LogicalResult reparseAnalogUserExpression(llvm::StringRef source,
                                          const llvm::StringMap<unsigned> &functions) {
  return success(Parser(source, &functions).standaloneExpression());
}
LogicalResult reparseAnalogNoiseCall(llvm::StringRef source,
                                     const llvm::StringMap<unsigned> *functions) {
  return success(Parser(source, functions).noiseCall());
}
} // namespace nodal
