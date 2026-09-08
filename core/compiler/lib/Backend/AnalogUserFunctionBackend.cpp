#include "nodal/Backend/AnalogUserFunctionBackend.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogFunctions.h"
#include "nodal/Dialect/Nodal/AnalogUserFunctions.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallString.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringSet.h"

#include <charconv>
#include <string>
#include <system_error>

using namespace mlir;
namespace nodal {
namespace {
llvm::StringRef text(Operation *op, llvm::StringRef key) {
  auto value = op->getAttrOfType<StringAttr>(key);
  return value ? value.getValue() : llvm::StringRef();
}
llvm::StringRef valueKind(Operation *op) {
  if (op->getName().getStringRef() != "nodal.analog_function_value")
    return {};
  return text(op, "kind");
}
llvm::StringRef scalarSpelling(Type type) {
  auto quantity = llvm::dyn_cast<QuantityType>(type);
  return quantity ? quantity.getKind() : llvm::StringRef();
}
FailureOr<std::string> literal(Operation *op) {
  if (auto real = op->getAttrOfType<FloatAttr>("value")) {
    char buffer[64];
    auto result = std::to_chars(buffer, buffer + sizeof(buffer), real.getValueAsDouble());
    if (result.ec != std::errc())
      return failure();
    std::string value(buffer, result.ptr);
    // Real-only expressions such as 1.0 / 2.0 must not become integer division.
    if (value.find_first_of(".eE") == std::string::npos)
      value += ".0";
    return value;
  }
  if (auto boolean = op->getAttrOfType<BoolAttr>("value"))
    return boolean.getValue() ? std::string("1") : std::string("0");
  if (auto integer = op->getAttrOfType<IntegerAttr>("value")) {
    llvm::SmallString<32> value;
    integer.getValue().toString(value, 10, true);
    return value.str().str();
  }
  return failure();
}
FailureOr<std::string> expression(Operation *op, const llvm::DenseMap<Value, std::string> &values) {
  auto kind = valueKind(op);
  if (kind == "input" || kind == "local")
    return text(op, "name").str();
  if (kind == "literal")
    return literal(op);
  llvm::SmallVector<std::string> args;
  for (Value input : op->getOperands()) {
    auto value = values.find(input);
    if (value == values.end())
      return failure();
    args.push_back(value->second);
  }
  if (op->getName().getStringRef() == "nodal.analog_user_call" || kind == "math") {
    std::string name;
    if (kind == "math") {
      auto *entry = lookupAnalogFunction(text(op, "function_id"));
      if (!entry)
        return failure();
      name = entry->verilogA.str();
    } else {
      auto callee = op->getAttrOfType<FlatSymbolRefAttr>("callee");
      if (!callee)
        return failure();
      name = callee.getValue().str();
    }
    return name + "(" + llvm::join(args, ", ") + ")";
  }
  if (kind == "select" && args.size() == 3)
    return "(" + args[0] + " ? " + args[1] + " : " + args[2] + ")";
  if ((kind == "neg" || kind == "not") && args.size() == 1)
    return std::string("(") + (kind == "neg" ? "-" : "!") + args[0] + ")";
  llvm::StringRef binary = kind == "add"   ? "+"
                           : kind == "sub" ? "-"
                           : kind == "mul" ? "*"
                           : kind == "div" ? "/"
                           : kind == "lt"  ? "<"
                           : kind == "le"  ? "<="
                           : kind == "gt"  ? ">"
                           : kind == "ge"  ? ">="
                           : kind == "and" ? "&&"
                           : kind == "or"  ? "||"
                                           : "";
  if (!binary.empty() && args.size() == 2)
    return "(" + args[0] + " " + binary.str() + " " + args[1] + ")";
  return failure();
}
} // namespace

LogicalResult renderAnalogUserFunctions(Operation *module, llvm::raw_ostream &output) {
  auto ordered = orderedAnalogUserFunctions(module);
  if (failed(ordered))
    return emitMappedFailure(module, "NODAL-ANALOG-041-007",
                             "unresolved or recursive function graph");
  llvm::StringSet<> functionNames;
  for (Operation *function : *ordered)
    functionNames.insert(text(function, "sym_name"));
  for (Operation *function : *ordered) {
    if (failed(verifyAnalogUserFunctionOperation(function)))
      return failure();
    auto name = text(function, "sym_name");
    if (!isPortableVerilogIdentifier(name))
      return emitMappedFailure(function, "NODAL-BACKEND-NAMING-001",
                               "reserved function identifier");
    auto resultType = function->getAttrOfType<TypeAttr>("return_type").getValue();
    llvm::SmallVector<Operation *> inputs, locals;
    for (Operation &op : function->getRegion(0).front()) {
      auto kind = valueKind(&op);
      if (kind != "input" && kind != "local")
        continue;
      auto localName = text(&op, "name");
      if (!isPortableVerilogIdentifier(localName) || functionNames.contains(localName))
        return emitMappedFailure(&op, "NODAL-BACKEND-NAMING-001",
                                 "function argument/local is reserved or shadows a function");
      (kind == "input" ? inputs : locals).push_back(&op);
    }
    output << "  analog function " << scalarSpelling(resultType) << " " << name << ";\n";
    output << "    input ";
    llvm::interleaveComma(inputs, output, [&](Operation *input) { output << text(input, "name"); });
    output << ";\n";
    for (const auto *group : {&inputs, &locals})
      for (Operation *value : *group)
        output << "    " << scalarSpelling(value->getResult(0).getType()) << " "
               << text(value, "name") << ";\n";
    output << "    begin\n";
    llvm::DenseMap<Value, std::string> values;
    for (Operation &op : function->getRegion(0).front()) {
      if (op.getName().getStringRef() == "nodal.analog_function_return") {
        auto value = values.find(op.getOperand(0));
        if (value == values.end())
          return failure();
        output << "      " << name << " = " << value->second << ";\n";
        continue;
      }
      if (valueKind(&op) == "local") {
        auto value = values.find(op.getOperand(0));
        if (value == values.end())
          return failure();
        output << "      " << text(&op, "name") << " = " << value->second << ";\n";
      }
      auto value = expression(&op, values);
      if (failed(value))
        return emitMappedFailure(&op, "NODAL-BACKEND-FUNCTION-001",
                                 "cannot render typed function value");
      values[op.getResult(0)] = *value;
    }
    output << "    end\n  endfunction\n\n";
  }
  return success();
}
} // namespace nodal
