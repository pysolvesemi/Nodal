#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "circt/Dialect/HW/HWDialect.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Backend/ScalarHierarchyMaterialization.h"
#include "llvm/Support/raw_ostream.h"
#include <string>
#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
int main(int argc, char **argv) {
  if (argc != 3) return 2;
  mlir::MLIRContext context;
  context.loadDialect<nodal::NodalDialect, circt::hw::HWDialect>();
  auto parsed=mlir::parseSourceFile<mlir::ModuleOp>(argv[1], &context);
  if (!parsed || mlir::failed(mlir::verify(*parsed))) return 3;
  // Candidate 2 test-only modes. Existing modes and their dispatch remain unchanged.
  if (std::string(argv[2]) == "parameter-contract")
    return mlir::failed(nodal::verifyParameterModel(*parsed)) ? 14 : 0;
  if (std::string(argv[2]) == "materialized-scalar") {
    std::string before;
    llvm::raw_string_ostream beforeStream(before);
    parsed->print(beforeStream);
    beforeStream.flush();
    std::string previous;
    for (unsigned repetition = 0; repetition < 2; ++repetition) {
      std::string target, after;
      llvm::raw_string_ostream output(target), afterStream(after);
      auto status = nodal::emitBackend(*parsed, nodal::BackendKind::VerilogA, output, nullptr,
                                       nodal::HierarchyEmission::MaterializedScalar);
      output.flush();
      parsed->print(afterStream);
      afterStream.flush();
      if (mlir::failed(status))
        return 15;
      if (after != before || (repetition && target != previous)) {
        llvm::errs() << "materialized-scalar changed source or was nondeterministic\n";
        return 16;
      }
      previous = target;
    }
    llvm::outs() << previous;
    return 0;
  }
  if (std::string(argv[2]) == "child-pairs") {
    std::string before, after; llvm::raw_string_ostream beforeStream(before), afterStream(after);
    parsed->print(beforeStream); beforeStream.flush();
    llvm::SmallVector<mlir::Value> values;
    parsed->walk([&](nodal::MemberAccessOp op) {
      auto member = nodal::inspectDigitalInterfaceMember(op);
      if (mlir::succeeded(member) && member->instance) values.push_back(op->getResult(0));
    });
    for (unsigned i=0;i<values.size();++i) for(unsigned j=0;j<i;++j) {
      auto use=&values[i].getDefiningOp()->getBlock()->back();
      auto relation=nodal::compareConservativeEndpoints(values[j],values[i],use);
      llvm::outs()<<j<<","<<i<<"="<<unsigned(relation)<<"\n";
    }
    parsed->print(afterStream); afterStream.flush();
    return before != after ? 13 : (values.empty()?12:0);
  }
  if (std::string(argv[2]) == "pair") {
    llvm::SmallVector<mlir::Value> values;
    mlir::Operation *use = nullptr;
    parsed->walk([&](nodal::MemberAccessOp op) {
      auto access = nodal::inspectDigitalInterfaceMember(op);
      if (mlir::succeeded(access) && access->selection) values.push_back(op->getResult(0));
    });
    parsed->walk([&](nodal::ConnectOp op) { if (llvm::isa<nodal::ModuleOp>(op->getParentOp())) use = op; });
    if (values.size() != 2 || !use) return 8;
    llvm::outs() << "electrical=" << unsigned(nodal::compareConservativeEndpoints(values[0], values[1], use)) << "\n";
    return 0;
  }
  if (std::string(argv[2]) == "verify") return 0;
  if (std::string(argv[2]) == "selected-aliases") {
    llvm::SmallVector<mlir::Value> values; mlir::Operation *use = nullptr;
    parsed->walk([&](nodal::MemberAccessOp op) {
      auto access = nodal::resolveDigitalInterfaceAccess(op->getResult(0));
      if (mlir::succeeded(access) && access->identity.selection) values.push_back(op->getResult(0));
    });
    parsed->walk([&](nodal::ConnectOp op) { if (llvm::isa<nodal::ModuleOp>(op->getParentOp())) use = op; });
    if (values.size() != 3 || !use) return 8;
    auto equal = nodal::compareConservativeEndpoints(values[0], values[1], use);
    auto distinct = nodal::compareConservativeEndpoints(values[0], values[2], use);
    llvm::outs() << "same_coordinate=" << unsigned(equal) << " distinct_coordinate=" << unsigned(distinct) << "\n";
    auto a = nodal::resolveConservativeEndpoint(values[0], use);
    auto b = nodal::resolveConservativeEndpoint(values[2], use);
    if (mlir::failed(a) || mlir::failed(b)) return 10;
    auto coordinates = nodal::compareStaticShapeProjections(a->shape.projection, b->shape.projection, use);
    llvm::outs() << "coordinate_status=" << unsigned(coordinates.status)
                 << " coordinate_overlap=" << unsigned(coordinates.overlap) << "\n";
    return equal == nodal::ConservativeAlias::Equal && distinct == nodal::ConservativeAlias::Unknown &&
        coordinates.status == nodal::ShapeRegionProofStatus::Proved &&
        coordinates.overlap == nodal::ShapeRegionOverlap::Disjoint ? 0 : 9;
  }
  if (std::string(argv[2]) == "selected-identity") {
    bool valid = true; unsigned selected = 0;
    parsed->walk([&](nodal::ConnectOp connection) {
      for (mlir::Value value : connection->getOperands()) {
        if (!llvm::isa<nodal::MemberAccessOp>(value.getDefiningOp())) continue;
        auto access = nodal::resolveDigitalInterfaceAccess(value);
        if (mlir::failed(access) || !access->identity.selection) continue;
        auto endpoint = nodal::resolveConservativeEndpoint(value, connection);
        ++selected;
        valid &= mlir::succeeded(endpoint) && !endpoint->port && !endpoint->instance &&
            endpoint->declarationScope == access->identity.endpoint->getParentOp() &&
            llvm::isa<nodal::GenerateOp>(endpoint->declarationScope) &&
            mlir::failed(nodal::resolveConservativePortIncidence(value, connection));
      }
    });
    llvm::outs() << "selected=" << selected << " internal_incidence=" << valid << "\n";
    return valid && selected ? 0 : 6;
  }

  if (std::string(argv[2]) == "identity") {
    bool valid = true; unsigned endpoints = 0;
    parsed->walk([&](nodal::ConnectOp connection) {
      for (mlir::Value value : connection->getOperands()) {
        auto endpoint = nodal::resolveConservativeEndpoint(value, connection);
        if (mlir::failed(endpoint)) { valid = false; continue; }
        ++endpoints;
        if (llvm::isa<nodal::MemberAccessOp>(value.getDefiningOp()) &&
            llvm::isa<nodal::GenerateOp>(connection->getParentOp())) {
          valid &= endpoint->declarationScope == connection->getParentOp();
          valid &= endpoint->instance || !endpoint->port;
          auto incidence = nodal::resolveConservativePortIncidence(value, connection);
          valid &= endpoint->instance || mlir::failed(incidence);
        }
      }
    });
    llvm::outs() << "endpoints=" << endpoints << " valid=" << valid << "\n";
    return valid && endpoints ? 0 : 6;
  }
  if (std::string(argv[2]) == "adapt") {
    if (mlir::failed(nodal::materializeConservativeInterfaceBoundary(*parsed))) return 4;
    parsed->print(llvm::outs()); return 0;
  }
  if (std::string(argv[2]) == "transaction") {
    std::string before, after, target;
    llvm::raw_string_ostream a(before), b(after), output(target);
    parsed->print(a); a.flush();
    auto status = nodal::emitBackend(*parsed, nodal::BackendKind::VerilogA, output);
    output.flush(); parsed->print(b); b.flush();
    const bool valid = mlir::failed(status) && target.empty() && before == after;
    llvm::outs() << "failure_is_transactional=" << valid << "\n";
    return valid ? 0 : 7;
  }
  std::string target; llvm::raw_string_ostream output(target);
  if (mlir::failed(nodal::emitBackend(*parsed, nodal::BackendKind::VerilogA, output))) return 5;
  llvm::outs()<<target; return 0;
}
