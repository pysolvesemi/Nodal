#ifndef NODAL_BACKEND_ANALOGEVENTBACKEND_H
#define NODAL_BACKEND_ANALOGEVENTBACKEND_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/raw_ostream.h"

namespace nodal {
struct AnalogEventRenderState {
  llvm::DenseMap<mlir::Operation *, std::string> names;
  llvm::DenseMap<mlir::Operation *, std::string> loops;
  llvm::StringMap<std::string> variables;
  llvm::StringSet<> reserved;
};
mlir::LogicalResult prepareAnalogEventBackend(mlir::Operation *module,
                                              AnalogEventRenderState &state,
                                              llvm::raw_ostream &declarations);
mlir::LogicalResult renderAnalogEventProcedure(mlir::Operation *procedure,
                                               AnalogEventRenderState &state,
                                               llvm::raw_ostream &output);
// Parse exactly the emitted procedural grammar. This is a structural acceptance
// gate, not a general Verilog-A parser or an analog simulator.
mlir::FailureOr<size_t> reparseAnalogEventBlock(llvm::StringRef source);
// Independent expression grammar for emitted noise calls; not permitted in events.
mlir::LogicalResult reparseAnalogNoiseCall(llvm::StringRef source,
                                           const llvm::StringMap<unsigned> *functions = nullptr);
// Exact ND coefficient-array and optional sampled-timing grammar, not numerical validation.
mlir::LogicalResult reparseAnalogTransferCall(llvm::StringRef source,
                                              const llvm::StringMap<unsigned> *functions = nullptr);
} // namespace nodal
#endif
