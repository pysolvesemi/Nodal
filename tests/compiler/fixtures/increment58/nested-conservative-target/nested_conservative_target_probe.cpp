// Focused integration wrapper: existing predecessor modes remain unchanged.
#define main nestedConservativePredecessorMain
#include "../../../../../core/compiler/test/Unit/NestedConservativeProofTest.cpp"
#undef main
#define main generatedConservativeAdapterMain
#include "../generated-conservative-adapter/generated_conservative_adapter_probe.cpp"
#undef main

#include <map>
#include <set>
namespace {
std::string nestedPath(mlir::Operation *op) {
  auto metadata = op->getAttrOfType<mlir::DictionaryAttr>("metadata");
  auto value = metadata ? metadata.getAs<mlir::StringAttr>("semantic_path") : mlir::StringAttr();
  if (!value) value = op->getAttrOfType<mlir::StringAttr>("sym_name");
  return value ? value.getValue().str() : "";
}
struct NestedExpectedPoint {
  mlir::Value outer, inner;
  std::string bodyLeaf;
};
int nestedTarget(mlir::ModuleOp original, bool emit) {
  const std::string before = probeText(original);
  mlir::OwningOpRef<mlir::ModuleOp> adapted = llvm::cast<mlir::ModuleOp>(original->clone());
  llvm::SmallVector<mlir::Operation *> loops;
  llvm::SmallVector<mlir::Value> inductions;
  adapted->walk<mlir::WalkOrder::PreOrder>([&](nodal::GenerateOp op) {
    loops.push_back(op); inductions.push_back(op->getRegion(0).front().getArgument(0));
  });
  if (loops.size() != 2 || loops[1]->getParentOp() != loops[0] ||
      !llvm::isa<nodal::ModuleOp>(loops[0]->getParentOp())) return 80;
  std::map<std::string, NestedExpectedPoint> expected;
  for (mlir::Operation &module : adapted->getBody()->getOperations()) {
    if (!llvm::isa<nodal::ModuleOp>(module)) continue;
    llvm::SmallVector<nodal::DigitalInterfaceEndpoint> endpoints;
    if (mlir::failed(nodal::collectDigitalInterfaceEndpoints(&module, endpoints))) return 85;
    for (const auto &endpoint : endpoints) {
      if (!endpoint.selection) continue;
      auto proof = nodal::resolveConservativeGeneratedSelection(endpoint.operation);
      if (mlir::failed(proof) || proof->selection.selections.size() != 2) return 86;
      const nodal::DigitalInterfaceEndpoint *body = nullptr;
      for (const auto &candidate : endpoints)
        if (!candidate.selection && candidate.instance == endpoint.instance &&
            candidate.declaration == endpoint.declaration) {
          if (body) return 87;
          body = &candidate;
        }
      if (!body) return 87;
      for (const auto &leaf : endpoint.leaves) {
        std::string key = nestedPath(endpoint.operation) + "." + leaf.path.getValue().str();
        NestedExpectedPoint point{proof->selection.selections[0].ordinal,
          proof->selection.selections[1].ordinal,
          nestedPath(body->operation) + "." + leaf.path.getValue().str()};
        if (!expected.emplace(key, point).second) return 88;
      }
    }
  }
  if (expected.size() != 8) return 89;
  if (mlir::failed(nodal::materializeConservativeInterfaceBoundary(*adapted)) ||
      mlir::failed(mlir::verify(*adapted))) return 81;
  llvm::SmallVector<mlir::Operation *> retained;
  adapted->walk<mlir::WalkOrder::PreOrder>([&](nodal::GenerateOp op) { retained.push_back(op); });
  if (retained != loops || loops[0]->getRegion(0).front().getArgument(0) != inductions[0] ||
      loops[1]->getRegion(0).front().getArgument(0) != inductions[1]) return 82;
  std::map<std::string, std::string> physicalTargets;
  auto correspondence = adapted->getOperation()->getAttrOfType<mlir::ArrayAttr>(
      "nodal.backend.interface_correspondence");
  if (!correspondence) return 90;
  for (mlir::Attribute item : correspondence) {
    auto entry = llvm::dyn_cast<mlir::DictionaryAttr>(item);
    auto source = entry ? entry.getAs<mlir::StringAttr>("source") : mlir::StringAttr();
    auto target = entry ? entry.getAs<mlir::StringAttr>("target") : mlir::StringAttr();
    if (!source || !target) return 90;
    // Child incidence has an additional record; the local-node record names its carrier.
    auto orientation = entry.getAs<mlir::StringAttr>("flow_orientation");
    if (orientation && orientation.getValue() == "local_node")
      physicalTargets.emplace(source.getValue().str(), target.getValue().str());
  }
  std::set<std::string> seenSelections;
  unsigned carriers = 0, bodyPoints = 0, selectedPoints = 0, stale = 0;
  bool valid = true;
  adapted->walk([&](nodal::NodeArrayOp op) {
    auto type = llvm::dyn_cast<nodal::ShapedType>(op->getResult(0).getType());
    if (!type || type.getDimensions() != "2,3" ||
        !llvm::isa<nodal::ModuleOp>(op->getParentOp())) valid = false;
    ++carriers;
  });
  adapted->walk([&](nodal::ShapeIndexOp op) {
    if (!llvm::isa<nodal::TerminalType>(op->getResult(0).getType())) return;
    auto root = op->getOperand(0).getDefiningOp<nodal::NodeArrayOp>();
    if (!root || op->getNumOperands() != 3) { valid = false; return; }
    if (op->getParentOp() == loops[1]) {
      valid &= op->getOperand(1) == inductions[0] && op->getOperand(2) == inductions[1];
      ++bodyPoints;
    } else if (llvm::isa<nodal::ModuleOp>(op->getParentOp())) {
      auto metadata = op->getAttrOfType<mlir::DictionaryAttr>("metadata");
      auto uses = metadata ? metadata.getAs<mlir::ArrayAttr>("source_uses") : mlir::ArrayAttr();
      auto use = uses && uses.size() == 1 ? llvm::dyn_cast<mlir::StringAttr>(uses[0]) : mlir::StringAttr();
      auto found = use ? expected.find(use.getValue().str()) : expected.end();
      auto rootName = root->getAttrOfType<mlir::StringAttr>("name");
      if (found == expected.end() || !rootName) { valid = false; return; }
      auto bodyTarget = physicalTargets.find(found->second.bodyLeaf);
      valid &= op->getOperand(1) == found->second.outer && op->getOperand(2) == found->second.inner &&
          bodyTarget != physicalTargets.end() && bodyTarget->second == rootName.getValue() &&
          seenSelections.insert(found->first).second;
      ++selectedPoints;
    } else valid = false;
  });
  adapted->walk([&](mlir::Operation *op) {
    if (llvm::isa<nodal::InterfaceInstanceOp, nodal::InterfaceBoundaryOp,
                  nodal::GeneratedMemberOp>(op)) ++stale;
    if (llvm::isa<nodal::GeneratedExportOp>(op)) {
      auto kind = op->getAttrOfType<mlir::StringAttr>("kind");
      if (kind && (kind.getValue() == "interface" || kind.getValue() == "family")) ++stale;
    }
  });
  if (!valid || carriers != 4 || bodyPoints != 4 || selectedPoints != 8 || seenSelections.size() != 8 || stale ||
      before != probeText(original)) return 83;
  if (!emit) { adapted->print(llvm::outs()); return 0; }
  std::string firstTarget;
  for (unsigned repetition = 0; repetition != 2; ++repetition) {
    std::string target; llvm::raw_string_ostream output(target);
    auto result = nodal::emitBackend(original, nodal::BackendKind::VerilogA, output);
    output.flush();
    if (mlir::failed(result) || target.empty() || before != probeText(original) ||
        (repetition && target != firstTarget)) return 84;
    firstTarget = std::move(target);
  }
  llvm::outs() << firstTarget;
  return 0;
}
}
int main(int argc, char **argv) {
  if (argc == 2 && llvm::StringRef(argv[1]) == "--literal-groundwork")
    return nestedConservativePredecessorMain(argc, argv);
  if (argc == 3 && (llvm::StringRef(argv[2]) == "nested-adapt" ||
                    llvm::StringRef(argv[2]) == "nested-emit")) {
    mlir::MLIRContext context;
    context.loadDialect<nodal::NodalDialect, circt::hw::HWDialect>();
    auto parsed = mlir::parseSourceFile<mlir::ModuleOp>(argv[1], &context);
    if (!parsed || mlir::failed(mlir::verify(*parsed))) return 3;
    return nestedTarget(*parsed, llvm::StringRef(argv[2]) == "nested-emit");
  }
  return generatedConservativeAdapterMain(argc, argv);
}
