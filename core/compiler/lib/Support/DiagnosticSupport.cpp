#include "nodal/Diagnostics/DiagnosticSupport.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/Location.h"
#include "mlir/IR/SymbolTable.h"

#include "llvm/ADT/SmallVector.h"
#include "llvm/Support/Casting.h"

#include <algorithm>
#include <cstdint>
#include <optional>
#include <string>

using namespace mlir;

namespace nodal {
namespace {

DictionaryAttr metadata(Operation *operation) {
  return operation ? operation->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
}

StringAttr stringAttribute(DictionaryAttr values, llvm::StringRef name) {
  return values ? values.getAs<StringAttr>(name) : StringAttr();
}

std::optional<int64_t> integerAttribute(DictionaryAttr values, llvm::StringRef name) {
  if (!values)
    return std::nullopt;
  if (auto integer = values.getAs<IntegerAttr>(name))
    return integer.getInt();
  if (auto text = values.getAs<StringAttr>(name)) {
    int64_t result = 0;
    if (!text.getValue().getAsInteger(10, result))
      return result;
  }
  return std::nullopt;
}

llvm::StringRef semanticPath(Operation *operation) {
  for (Operation *current = operation; current; current = current->getParentOp()) {
    if (auto value = stringAttribute(metadata(current), "semantic_path"))
      return value.getValue();
  }
  return {};
}

llvm::StringRef explicitContext(Operation *operation, llvm::StringRef name) {
  for (Operation *current = operation; current; current = current->getParentOp()) {
    if (auto value = stringAttribute(metadata(current), name))
      return value.getValue();
  }
  return {};
}

FileLineColLoc findFileLocation(Location location) {
  if (auto file = llvm::dyn_cast<FileLineColLoc>(location))
    return file;
  if (auto name = llvm::dyn_cast<NameLoc>(location))
    return findFileLocation(name.getChildLoc());
  if (auto call = llvm::dyn_cast<CallSiteLoc>(location)) {
    if (FileLineColLoc caller = findFileLocation(call.getCaller()))
      return caller;
    return findFileLocation(call.getCallee());
  }
  if (auto fused = llvm::dyn_cast<FusedLoc>(location)) {
    for (Location nested : fused.getLocations()) {
      if (FileLineColLoc file = findFileLocation(nested))
        return file;
    }
  }
  return {};
}

std::string hierarchyFallback(Operation *operation) {
  llvm::SmallVector<std::string, 8> symbols;
  for (Operation *current = operation; current; current = current->getParentOp()) {
    if (auto symbol = current->getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName())) {
      if (!symbol.getValue().empty())
        symbols.push_back(symbol.getValue().str());
    }
  }
  std::reverse(symbols.begin(), symbols.end());
  std::string result;
  for (const std::string &symbol : symbols) {
    if (!result.empty())
      result.append("::");
    result.append(symbol);
  }
  return result;
}

std::string indexFallback(llvm::StringRef path) {
  const size_t start = path.find('[');
  if (start == llvm::StringRef::npos)
    return {};
  return path.drop_front(start).str();
}

std::string formatSourceRange(llvm::StringRef path, int64_t line, int64_t column,
                    int64_t endLine, int64_t endColumn) {
  if (path.empty() || line <= 0 || column <= 0)
    return {};
  if (endLine <= 0)
    endLine = line;
  if (endColumn <= 0)
    endColumn = column;
  return (path + ":" + llvm::Twine(line) + ":" + llvm::Twine(column) + "-" +
llvm::Twine(endLine) + ":" + llvm::Twine(endColumn))
      .str();
}

void appendContext(InFlightDiagnostic &diagnostic, const DiagnosticContext &context) {
  if (!context.semanticPath.empty())
    diagnostic << " [semantic-path=" << context.semanticPath << "]";
  if (!context.hierarchyPath.empty())
    diagnostic << " [hierarchy-path=" << context.hierarchyPath << "]";
  if (!context.indexPath.empty())
    diagnostic << " [index-path=" << context.indexPath << "]";
  if (!context.sourceRange.empty())
    diagnostic << " [source-range=" << context.sourceRange << "]";
}

LogicalResult emitWithContext(Operation *operation, llvm::StringRef code,
                    const llvm::Twine &message,
                    const DiagnosticContext &context) {
  InFlightDiagnostic diagnostic = operation->emitError();
  diagnostic << code << ": " << message.str();
  appendContext(diagnostic, context);
  return failure();
}

Operation *findOperationByPath(mlir::ModuleOp module, llvm::StringRef path) {
  Operation *found = nullptr;
  module.walk([&](Operation *operation) {
    if (!found && semanticPath(operation) == path)
      found = operation;
  });
  return found;
}

DiagnosticContext sourceMapContext(mlir::ModuleOp module, llvm::StringRef path) {
  DiagnosticContext context;
  context.semanticPath = path.str();
  context.indexPath = indexFallback(path);

  ArrayAttr sourceMap = module->getAttrOfType<ArrayAttr>("nodal.bridge.source_map");
  if (!sourceMap)
    return context;
  for (Attribute attribute : sourceMap) {
    auto entry = llvm::dyn_cast<DictionaryAttr>(attribute);
    if (!entry)
      continue;
    auto semantic = entry.getAs<StringAttr>("semantic_path");
    if (!semantic || semantic.getValue() != path)
      continue;
    if (auto hierarchy = entry.getAs<StringAttr>("hierarchy_path"))
      context.hierarchyPath = hierarchy.getValue().str();
    auto source = entry.getAs<StringAttr>("source_path");
    auto line = entry.getAs<IntegerAttr>("source_line");
    auto column = entry.getAs<IntegerAttr>("source_column");
    auto endLine = entry.getAs<IntegerAttr>("source_end_line");
    auto endColumn = entry.getAs<IntegerAttr>("source_end_column");
    if (source && line && column) {
      context.sourceRange = formatSourceRange(
source.getValue(), line.getInt(), column.getInt(),
endLine ? endLine.getInt() : line.getInt(),
endColumn ? endColumn.getInt() : column.getInt());
    }
    break;
  }
  return context;
}

} // namespace

DiagnosticContext collectDiagnosticContext(Operation *operation) {
  DiagnosticContext context;
  llvm::StringRef semantic = semanticPath(operation);
  context.semanticPath = semantic.str();
  llvm::StringRef hierarchy = explicitContext(operation, "hierarchy_path");
  context.hierarchyPath = hierarchy.empty() ? hierarchyFallback(operation) : hierarchy.str();
  llvm::StringRef index = explicitContext(operation, "index_path");
  context.indexPath = index.empty() ? indexFallback(semantic) : index.str();

  FileLineColLoc file;
  for (Operation *current = operation; current && !file; current = current->getParentOp())
    file = findFileLocation(current->getLoc());
  if (file) {
    int64_t endLine = file.getLine();
    int64_t endColumn = file.getColumn();
    for (Operation *current = operation; current; current = current->getParentOp()) {
      DictionaryAttr values = metadata(current);
      if (std::optional<int64_t> value = integerAttribute(values, "source_end_line"))
        endLine = *value;
      if (std::optional<int64_t> value = integerAttribute(values, "source_end_column"))
        endColumn = *value;
      if (values && (values.get("source_end_line") || values.get("source_end_column")))
        break;
    }
    context.sourceRange = formatSourceRange(file.getFilename(), file.getLine(),
                                  file.getColumn(), endLine, endColumn);
  }
  return context;
}

LogicalResult emitMappedFailure(Operation *operation, llvm::StringRef code,
                      const llvm::Twine &message) {
  return emitWithContext(operation, code, message, collectDiagnosticContext(operation));
}

LogicalResult emitMappedFailureForPath(mlir::ModuleOp module,
                             llvm::StringRef semanticPathValue,
                             llvm::StringRef code,
                             const llvm::Twine &message) {
  if (Operation *operation = findOperationByPath(module, semanticPathValue))
    return emitMappedFailure(operation, code, message);
  return emitWithContext(module.getOperation(), code, message,
               sourceMapContext(module, semanticPathValue));
}

} // namespace nodal
