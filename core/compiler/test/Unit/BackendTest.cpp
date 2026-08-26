#include "nodal/Backend/Backend.h"

#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/MLIRContext.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

#include <string>

namespace {

constexpr llvm::StringLiteral kAnalogModule = R"mlir(
module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = false}, sym_name = "Zeta"}> ({
  ^bb0:
  }) : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "Alpha"}> ({
  ^bb0:
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kUnsupportedModule = R"mlir(
module attributes {
  nodal.backend.profile = "verilog-a",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "Top"}> ({
  ^bb0:
    %zero = "nodal.constant"() <{metadata = {}, value = 0 : i64}> :
      () -> !nodal.uint<8>
  }) : () -> ()
}
)mlir";

class RejectingReparseHooks final : public nodal::TargetVerificationHooks {
public:
  mlir::LogicalResult verifyTarget(llvm::StringRef,
                                   const nodal::BackendConfiguration &) const final {
    return mlir::success();
  }

  mlir::LogicalResult reparseTarget(llvm::StringRef,
                                    const nodal::BackendConfiguration &) const final {
    return mlir::failure();
  }
};

int fail(llvm::StringRef message) {
  llvm::errs() << "BackendTest: " << message << '\n';
  return 1;
}

mlir::OwningOpRef<mlir::ModuleOp> parse(mlir::MLIRContext &context, llvm::StringRef text) {
  return mlir::parseSourceString<mlir::ModuleOp>(text, &context);
}

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<circt::hw::HWDialect, nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();

  auto analog = parse(context, kAnalogModule);
  if (!analog)
    return fail("could not parse the analog backend fixture");

  const nodal::BackendProfile &verilogA = nodal::getBackendProfile(nodal::BackendKind::VerilogA);
  const nodal::BackendProfile &verilogAMS =
      nodal::getBackendProfile(nodal::BackendKind::VerilogAMS);
  if (verilogA.id != "verilog-a" || verilogA.translation != "nodal-to-verilog-a" ||
      verilogA.shapedValueLayout != nodal::ShapedValueLayout::ScalarOrFlat ||
      verilogA.materialization != nodal::MaterializationPolicy::SafeInline ||
      verilogA.supportsMixedSignal)
    return fail("Verilog-A profile contract is incorrect");
  if (verilogAMS.id != "verilog-ams" || verilogAMS.translation != "nodal-to-verilog-ams" ||
      verilogAMS.shapedValueLayout != nodal::ShapedValueLayout::FlatPacked ||
      verilogAMS.materialization != nodal::MaterializationPolicy::Readable ||
      !verilogAMS.supportsMixedSignal)
    return fail("Verilog-AMS profile contract is incorrect");

  std::string first;
  llvm::raw_string_ostream firstStream(first);
  if (mlir::failed(nodal::emitBackend(*analog, nodal::BackendKind::VerilogA, firstStream)))
    return fail("first deterministic emission failed");
  firstStream.flush();

  std::string second;
  llvm::raw_string_ostream secondStream(second);
  if (mlir::failed(nodal::emitBackend(*analog, nodal::BackendKind::VerilogA, secondStream)))
    return fail("second deterministic emission failed");
  secondStream.flush();

  if (first != second)
    return fail("repeated emissions are not byte-identical");
  if (first.find(" * check-profile: release\n") == std::string::npos)
    return fail("CheckProfile configuration was not retained");
  const size_t alpha = first.find("module Alpha;");
  const size_t zeta = first.find("module Zeta;");
  if (alpha == std::string::npos || zeta == std::string::npos || alpha >= zeta)
    return fail("module output is not sorted by semantic name");

  auto unsupported = parse(context, kUnsupportedModule);
  if (!unsupported)
    return fail("could not parse the unsupported-operation fixture");
  std::string unchanged = "sentinel";
  llvm::raw_string_ostream unsupportedStream(unchanged);
  if (mlir::succeeded(
          nodal::emitBackend(*unsupported, nodal::BackendKind::VerilogA, unsupportedStream)))
    return fail("unsupported operation was accepted");
  unsupportedStream.flush();
  if (unchanged != "sentinel")
    return fail("failed capability checking published partial output");

  RejectingReparseHooks rejectingHooks;
  std::string rejected = "sentinel";
  llvm::raw_string_ostream rejectedStream(rejected);
  if (mlir::succeeded(nodal::emitBackend(*analog, nodal::BackendKind::VerilogA, rejectedStream,
                                         &rejectingHooks)))
    return fail("rejecting reparse hook was ignored");
  rejectedStream.flush();
  if (rejected != "sentinel")
    return fail("failed target reparse published partial output");

  return 0;
}
