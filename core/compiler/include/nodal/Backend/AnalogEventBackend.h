#ifndef NODAL_BACKEND_ANALOGEVENTBACKEND_H
#define NODAL_BACKEND_ANALOGEVENTBACKEND_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Backend/ShapedValueBackend.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/raw_ostream.h"

#include <optional>

namespace nodal {
struct AnalogEventRenderState {
  llvm::DenseMap<mlir::Operation *, std::string> names;
  llvm::DenseMap<mlir::Operation *, std::string> loops;
  llvm::DenseMap<mlir::Operation *, std::string> copyIndices;
  llvm::DenseMap<mlir::Operation *, std::pair<std::string, std::string>> mapNames;
  llvm::DenseMap<mlir::Value, TargetShapedLayout> shapes;
  // Derived target lifetime decisions, keyed only by the verified SSA graph.
  llvm::DenseMap<mlir::Operation *, std::string> declarationInitializers;
  llvm::DenseSet<mlir::Operation *> declarationOnlyValues;
  llvm::StringMap<std::string> variables;
  llvm::StringSet<> reserved;
};
// Shared semantic admission used by generated capability checks and emission.
bool isAnalogEventStorageStatement(mlir::Operation *operation);
mlir::LogicalResult verifyAnalogEventStorageStatement(mlir::Operation *operation);
// One semantic target admission for ordinary and generated procedures. Storage
// counts do not define capability; ownership, effects and control placement do.
mlir::LogicalResult verifyAnalogProcedureTarget(mlir::Operation *procedure);
mlir::LogicalResult prepareAnalogEventBackend(mlir::Operation *module,
                                              AnalogEventRenderState &state,
                                              llvm::raw_ostream &declarations);
mlir::LogicalResult prepareGeneratedAnalogEventBackend(mlir::Operation *procedure,
                                                       AnalogEventRenderState &state,
                                                       llvm::raw_ostream &declarations,
                                                       unsigned indent);
mlir::LogicalResult renderAnalogEventProcedure(mlir::Operation *procedure,
                                               AnalogEventRenderState &state,
                                               llvm::raw_ostream &output);
// Parse exactly the emitted procedural grammar. This is a structural acceptance
// gate, not a general Verilog-A parser or an analog simulator.
enum class AnalogEventSymbolKind { Real, Integer, RealArray, Terminal, TerminalArray };
struct AnalogEventSymbol {
  AnalogEventSymbolKind kind;
  bool writable = false;
  std::optional<int64_t> lowerBound;
  std::optional<int64_t> upperBound;
};
using AnalogEventSymbols = llvm::StringMap<AnalogEventSymbol>;
mlir::FailureOr<size_t> reparseAnalogEventBlock(llvm::StringRef source,
                                                const AnalogEventSymbols *symbols = nullptr);
mlir::LogicalResult reparseAnalogEventDeclaration(llvm::StringRef source,
                                                  AnalogEventSymbols &symbols);
// Independent expression grammar for emitted noise calls; not permitted in events.
mlir::LogicalResult reparseAnalogNoiseCall(llvm::StringRef source,
                                           const llvm::StringMap<unsigned> *functions = nullptr);
// Exact ND coefficient-array and optional sampled-timing grammar, not numerical validation.
mlir::LogicalResult reparseAnalogTransferCall(llvm::StringRef source,
                                              const llvm::StringMap<unsigned> *functions = nullptr);
} // namespace nodal
#endif
