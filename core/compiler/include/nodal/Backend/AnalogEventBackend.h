#ifndef NODAL_BACKEND_ANALOGEVENTBACKEND_H
#define NODAL_BACKEND_ANALOGEVENTBACKEND_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Backend/ShapedValueBackend.h"
#include "nodal/Dialect/Nodal/AnalogEvents.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/raw_ostream.h"

#include <cstdint>
#include <memory>
#include <optional>

namespace nodal {
struct AnalogMappedStorageAccess {
  std::string text;
  std::string temporary;
  ShapedProjectionProgram program;
};
struct AnalogLiveControlPlan;
struct AnalogLiveFillTarget {
  std::string cache, ready;
};
struct AnalogLiveConsumerTarget {
  AnalogLiveSourceProgram program;
  llvm::DenseMap<mlir::Value, AnalogLiveFillTarget> fills;
  llvm::DenseMap<mlir::Value, unsigned> fillIndices;
  std::shared_ptr<AnalogLiveControlPlan> control;
  std::string pc, step;
  bool event = false;
  uint64_t declarationBytes = 0, reservedBytes = 0;
};
struct AnalogLiveModuleBudget {
  uint64_t wrappers = 0, bytes = 0;
};
struct AnalogEventRenderState {
  llvm::DenseMap<mlir::Operation *, std::string> names;
  llvm::DenseMap<mlir::Operation *, std::string> loops;
  llvm::DenseMap<mlir::Operation *, std::string> copyIndices;
  llvm::DenseMap<mlir::Operation *, std::pair<std::string, std::string>> mapNames;
  llvm::DenseMap<mlir::Value, TargetShapedLayout> shapes;
  // Derived target lifetime decisions, keyed only by the verified SSA graph.
  llvm::DenseMap<mlir::Operation *, std::string> declarationInitializers;
  llvm::DenseSet<mlir::Operation *> declarationOnlyValues;
  // Control/event references retain live storage loads. Only their verified
  // static coordinates have private setup programs, one per actual handle.
  llvm::DenseMap<mlir::Operation *, llvm::DenseMap<mlir::Value, AnalogMappedStorageAccess>>
      liveReferences;
  llvm::DenseMap<mlir::Operation *, AnalogLiveConsumerTarget> liveConsumers;
  llvm::DenseMap<mlir::Operation *, mlir::Operation *> liveConsumerOwners;
  std::string liveStoreFunction;
  uint64_t nextLiveFillOrdinal = 0, nextLiveControlOrdinal = 0;
  std::shared_ptr<AnalogLiveModuleBudget> liveBudget = std::make_shared<AnalogLiveModuleBudget>();
  llvm::StringMap<std::string> variables;
  llvm::StringSet<> reserved;
};
// Shared semantic admission used by generated capability checks and emission.
bool isAnalogEventStorageStatement(mlir::Operation *operation);
mlir::LogicalResult verifyAnalogEventStorageStatement(mlir::Operation *operation);
// One semantic target admission for ordinary and generated procedures. Storage
// counts do not define capability; ownership, effects and control placement do.
mlir::LogicalResult verifyAnalogProcedureTarget(mlir::Operation *procedure);
// A direct initialized shaped root needs declaration lifetime. This query only
// selects layout; native ownership, static fill and target checks still apply.
bool hasPersistentShapedInitializer(mlir::Operation *procedure);
struct GeneratedAnalogDeclarationContext {
  enum class Kind { AnalogIteration, StructuralOccurrence };
  Kind kind = Kind::AnalogIteration;
  // StructuralOccurrence requires the actual direct Generate/Analog/procedure
  // ancestry, independently verified by preparation. Source attributes alone
  // cannot request a declaration lifetime.
  mlir::Operation *scope = nullptr;
};
mlir::LogicalResult prepareAnalogEventBackend(mlir::Operation *module,
                                              AnalogEventRenderState &state,
                                              llvm::raw_ostream &declarations);
mlir::LogicalResult
prepareGeneratedAnalogEventBackend(mlir::Operation *procedure, AnalogEventRenderState &state,
                                   llvm::raw_ostream &declarations, unsigned indent,
                                   GeneratedAnalogDeclarationContext context = {});
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
  bool privateLive = false;
};
using AnalogEventSymbols = llvm::StringMap<AnalogEventSymbol>;
struct AnalogEventPrivateFunctions {
  llvm::StringSet<> names;
  uint64_t demandWrappers = 0, expressionBytes = 0;
};
mlir::FailureOr<size_t> reparseAnalogEventPrivateFunction(llvm::StringRef source,
                                                          AnalogEventPrivateFunctions &functions);
mlir::FailureOr<size_t>
reparseAnalogEventBlock(llvm::StringRef source, const AnalogEventSymbols *symbols = nullptr,
                        AnalogEventPrivateFunctions *privateFunctions = nullptr);
mlir::LogicalResult reparseAnalogEventDeclaration(llvm::StringRef source,
                                                  AnalogEventSymbols &symbols);
// Independently classify the finite simulator-context target primitives. Returns
// whether a runtime query occurred, rejects malformed names/arguments, and can
// forbid queries at static boundaries without narrowing unrelated target syntax.
mlir::FailureOr<bool> reparseAnalogContextQueries(llvm::StringRef source, bool allowRuntime);
// Independent expression grammar for emitted noise calls; not permitted in events.
mlir::LogicalResult reparseAnalogNoiseCall(llvm::StringRef source,
                                           const llvm::StringMap<unsigned> *functions = nullptr);
// Exact ND coefficient-array and optional sampled-timing grammar, not numerical validation.
mlir::LogicalResult reparseAnalogTransferCall(llvm::StringRef source,
                                              const llvm::StringMap<unsigned> *functions = nullptr);
} // namespace nodal
#endif
