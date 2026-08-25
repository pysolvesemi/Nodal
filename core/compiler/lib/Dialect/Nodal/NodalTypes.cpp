#include "nodal/Dialect/Nodal/NodalTypes.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectImplementation.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/ADT/STLFunctionalExtras.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/ADT/TypeSwitch.h"

#include <cctype>

using namespace mlir;

#define GET_TYPEDEF_CLASSES
#include "nodal/Dialect/Nodal/NodalOpsTypes.cpp.inc"

namespace {

LogicalResult verifyWidth(llvm::function_ref<InFlightDiagnostic()> emitError, int64_t width,
                          llvm::StringRef label) {
  if (width <= 0)
    return emitError() << label << " width must be greater than zero";
  return success();
}

LogicalResult verifySymbol(llvm::function_ref<InFlightDiagnostic()> emitError,
                           llvm::StringRef symbol, llvm::StringRef label) {
  if (symbol.trim().empty())
    return emitError() << label << " must not be empty";
  return success();
}

bool isDimensionToken(llvm::StringRef token) {
  token = token.trim();
  if (token.empty())
    return false;

  bool digits = true;
  for (char character : token) {
    if (!std::isdigit(static_cast<unsigned char>(character))) {
      digits = false;
      break;
    }
  }
  if (digits)
    return token != "0";

  const unsigned char first = static_cast<unsigned char>(token.front());
  if (!(std::isalpha(first) || token.front() == '_'))
    return false;
  for (char character : token.drop_front()) {
    const unsigned char current = static_cast<unsigned char>(character);
    if (!(std::isalnum(current) || character == '_' || character == '.' || character == '+' ||
          character == '-' || character == '*' || character == '/' || character == '(' ||
          character == ')'))
      return false;
  }
  return true;
}

LogicalResult verifyPayload(llvm::function_ref<InFlightDiagnostic()> emitError, Type payload,
                            llvm::StringRef label) {
  if (!payload)
    return emitError() << label << " type must be present";
  return success();
}

} // namespace

void nodal::NodalDialect::registerTypes() {
  addTypes<
#define GET_TYPEDEF_LIST
#include "nodal/Dialect/Nodal/NodalOpsTypes.cpp.inc"
      >();
}

LogicalResult nodal::BitsType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                      int64_t width) {
  return verifyWidth(emitError, width, "bits");
}

LogicalResult nodal::UIntType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                      int64_t width) {
  return verifyWidth(emitError, width, "uint");
}

LogicalResult nodal::SIntType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                      int64_t width) {
  return verifyWidth(emitError, width, "sint");
}

LogicalResult nodal::ShapedType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                        llvm::StringRef dimensions, Type elementType) {
  if (!elementType)
    return emitError() << "shaped element type must be present";
  llvm::StringRef remaining = dimensions.trim();
  if (remaining.empty())
    return emitError() << "shaped dimensions must not be empty";
  while (!remaining.empty()) {
    const auto split = remaining.split(',');
    if (!isDimensionToken(split.first))
      return emitError() << "invalid shaped dimension token '" << split.first << "'";
    remaining = split.second;
  }
  return success();
}

LogicalResult nodal::InterfaceType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                           llvm::StringRef symbol) {
  return verifySymbol(emitError, symbol, "interface symbol");
}

LogicalResult nodal::ValidType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                       Type payloadType) {
  return verifyPayload(emitError, payloadType, "valid payload");
}

LogicalResult nodal::StreamType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                        Type payloadType) {
  return verifyPayload(emitError, payloadType, "stream payload");
}

LogicalResult nodal::ResolvedType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                          llvm::StringRef driveMode, Type elementType) {
  if (failed(verifyPayload(emitError, elementType, "resolved element")))
    return failure();
  if (driveMode != "push_pull" && driveMode != "open_drain" && driveMode != "open_source")
    return emitError() << "unsupported resolved drive mode '" << driveMode << "'";
  return success();
}

LogicalResult nodal::DriverType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                        Type elementType) {
  return verifyPayload(emitError, elementType, "driver element");
}

LogicalResult nodal::TerminalType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                          llvm::StringRef discipline) {
  return verifySymbol(emitError, discipline, "terminal discipline");
}

LogicalResult nodal::BranchType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                        llvm::StringRef discipline) {
  return verifySymbol(emitError, discipline, "branch discipline");
}

LogicalResult nodal::EnumType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                      llvm::StringRef symbol, int64_t width) {
  if (failed(verifySymbol(emitError, symbol, "enum symbol")))
    return failure();
  return verifyWidth(emitError, width, "enum");
}

LogicalResult nodal::DomainType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                        llvm::StringRef symbol) {
  return verifySymbol(emitError, symbol, "domain symbol");
}
