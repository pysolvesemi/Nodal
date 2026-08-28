#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/Support/Casting.h"
#include "llvm/Support/raw_ostream.h"

#include <string>

namespace {

int fail(llvm::StringRef message) {
  llvm::errs() << "ConservativeConnectivityTest: " << message << '\n';
  return 1;
}

constexpr llvm::StringLiteral kValid = R"mlir(
module {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({
  ^bb0:
    "nodal.component_contract"() <{connectivity_ownership = "local", kind = "concrete", metadata = {}, source_path = "Fixture"}> : () -> ()
    %p = "nodal.terminal"() <{direction = "output", flow_orientation = "into_component", metadata = {}, name = "p", source_path = "Fixture.p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{direction = "input", flow_orientation = "into_component", metadata = {}, name = "n", source_path = "Fixture.n"}> : () -> !nodal.terminal<"electrical">
    %mid = "nodal.node"() <{metadata = {}, name = "mid", source_path = "Fixture.mid"}> : () -> !nodal.terminal<"electrical">
    %g = "nodal.node"() <{metadata = {}, name = "g", source_path = "Fixture.g"}> : () -> !nodal.terminal<"electrical">
    "nodal.connect"(%n, %mid) <{connection_id = "n-mid", metadata = {}, source_path = "Fixture.connect.n-mid"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
    "nodal.reference"(%g) <{metadata = {}, scope = "global", source_path = "Fixture.reference.g"}> : (!nodal.terminal<"electrical">) -> ()
    %input = "nodal.branch"(%p, %mid) <{declaration_kind = "named", metadata = {}, name = "input", source_path = "Fixture.branch.input"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    %ground = "nodal.branch"(%mid, %g) <{declaration_kind = "implicit", metadata = {}, source_path = "Fixture.branch.ground"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
  }) : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Partial"}> ({
  ^bb0:
    "nodal.component_contract"() <{connectivity_ownership = "extensible", kind = "partial", metadata = {}, source_path = "Partial"}> : () -> ()
    %ext = "nodal.terminal"() <{direction = "inout", flow_orientation = "into_component", metadata = {allow_floating = true}, name = "ext", source_path = "Partial.ext"}> : () -> !nodal.terminal<"electrical">
    "nodal.reference"(%ext) <{metadata = {}, scope = "module", source_path = "Partial.reference.ext"}> : (!nodal.terminal<"electrical">) -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kDuplicateImplicit = R"mlir(
module {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Duplicate"}> ({
  ^bb0:
    "nodal.component_contract"() <{connectivity_ownership = "local", kind = "concrete", metadata = {}, source_path = "Duplicate"}> : () -> ()
    %p = "nodal.terminal"() <{direction = "inout", flow_orientation = "into_component", metadata = {}, name = "p", source_path = "Duplicate.p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{direction = "inout", flow_orientation = "into_component", metadata = {}, name = "n", source_path = "Duplicate.n"}> : () -> !nodal.terminal<"electrical">
    %a = "nodal.branch"(%p, %n) <{declaration_kind = "implicit", metadata = {}, source_path = "Duplicate.a"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    %b = "nodal.branch"(%n, %p) <{declaration_kind = "implicit", metadata = {}, source_path = "Duplicate.b"}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kInvalidComponent = R"mlir(
module {
  "nodal.module"() <{metadata = {}, sym_name = "Invalid"}> ({
  ^bb0:
    "nodal.component_contract"() <{connectivity_ownership = "local", kind = "partial", metadata = {}, source_path = "Invalid"}> : () -> ()
  }) : () -> ()
}
)mlir";

std::string printModule(mlir::ModuleOp module) {
  std::string text;
  llvm::raw_string_ostream stream(text);
  module.print(stream);
  stream.flush();
  return text;
}

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();

  auto valid = mlir::parseSourceString<mlir::ModuleOp>(kValid, &context);
  if (!valid || mlir::failed(nodal::materializeConservativeConnectivity(*valid)) ||
      mlir::failed(mlir::verify(*valid)))
    return fail("valid conservative connectivity did not normalize and verify");

  unsigned sets = 0;
  unsigned equalities = 0;
  unsigned references = 0;
  unsigned flows = 0;
  bool partialIncomplete = false;
  bool outputDirectionKeptIndependent = false;
  bool moduleReferenceRetained = false;
  valid->walk([&](mlir::Operation *operation) {
    sets += llvm::isa<nodal::ConnectionSetOp>(operation);
    equalities += llvm::isa<nodal::PotentialEqualityOp>(operation);
    references += llvm::isa<nodal::ReferencePotentialOp>(operation);
    if (llvm::isa<nodal::ReferencePotentialOp>(operation)) {
      auto identity = operation->getAttrOfType<mlir::StringAttr>("reference_identity");
      if (identity && identity.getValue() == "Partial::reference::electrical")
        moduleReferenceRetained = true;
    }
    if (llvm::isa<nodal::FlowConservationOp>(operation)) {
      ++flows;
      auto complete = operation->getAttrOfType<mlir::BoolAttr>("complete");
      auto ownership = operation->getAttrOfType<mlir::StringAttr>("ownership");
      if (complete && !complete.getValue() && ownership && ownership.getValue() == "extensible")
        partialIncomplete = true;
      auto signs = operation->getAttrOfType<mlir::ArrayAttr>("signs");
      for (auto [operand, sign] : llvm::zip(operation->getOperands(), signs)) {
        mlir::Operation *definition = operand.getDefiningOp();
        auto name =
            definition ? definition->getAttrOfType<mlir::StringAttr>("name") : mlir::StringAttr();
        auto integer = llvm::dyn_cast<mlir::IntegerAttr>(sign);
        if (name && name.getValue() == "p" && integer && integer.getInt() == -1)
          outputDirectionKeptIndependent = true;
      }
    }
  });
  if (sets != 4 || equalities != 1 || references != 2 || flows != 4)
    return fail("normalized topology/equation inventory is incorrect");
  if (!moduleReferenceRetained)
    return fail("module-local reference identity was not retained");
  if (!partialIncomplete)
    return fail("partial component did not retain incomplete extensible ownership");
  if (!outputDirectionKeptIndependent)
    return fail("port direction incorrectly changed conservative flow orientation");

  const std::string once = printModule(*valid);
  if (mlir::failed(nodal::materializeConservativeConnectivity(*valid)) ||
      once != printModule(*valid))
    return fail("connectivity materialization is not deterministic and idempotent");

  auto duplicate = mlir::parseSourceString<mlir::ModuleOp>(kDuplicateImplicit, &context);
  if (!duplicate || mlir::succeeded(nodal::materializeConservativeConnectivity(*duplicate)))
    return fail("duplicate implicit branch was accepted");

  if (mlir::parseSourceString<mlir::ModuleOp>(kInvalidComponent, &context))
    return fail("invalid partial/concrete ownership was accepted");

  return 0;
}
