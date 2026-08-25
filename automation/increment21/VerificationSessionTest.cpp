//===- VerificationSessionTest.cpp - Transactional verifier smoke test ---===//
//
// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
//
//===----------------------------------------------------------------------===//

#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Transforms/Verification.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/MLIRContext.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/Support/raw_ostream.h"

#include <string>

using namespace mlir;

namespace {

constexpr llvm::StringLiteral kValid = R"mlir(
module {
  "nodal.module"() <{
    sym_name = "Top",
    metadata = {}
  }> ({
  ^bb0:
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kInvalid = R"mlir(
module {
  "nodal.module"() <{
    sym_name = "Top",
    metadata = {}
  }> ({
  ^bb0:
    "nodal.instance"() <{
      sym_name = "missing",
      module = @Missing,
      parameter_bindings = {},
      domain_bindings = {},
      metadata = {}
    }> : () -> ()
  }) : () -> ()
}
)mlir";

int fail(llvm::StringRef message) {
  llvm::errs() << "NODAL-INC21-UNIT: " << message << '\n';
  return 1;
}

} // namespace

int main() {
  MLIRContext context;
  context.getOrLoadDialect<nodal::NodalDialect>();

  OwningOpRef<ModuleOp> valid = parseSourceString<ModuleOp>(kValid, &context);
  OwningOpRef<ModuleOp> invalid = parseSourceString<ModuleOp>(kInvalid, &context);
  if (!valid || !invalid)
    return fail("failed to parse unit fixtures");

  nodal::VerificationSession session;
  if (failed(session.accept(*valid)))
    return fail("valid design was rejected");
  if (!session.getAcceptedIR())
    return fail("accepted design was not retained");
  const std::string accepted = *session.getAcceptedIR();

  if (succeeded(session.accept(*invalid)))
    return fail("invalid hierarchy was accepted");
  if (!session.getAcceptedIR() || *session.getAcceptedIR() != accepted)
    return fail("failed candidate replaced the last accepted state");

  OwningOpRef<ModuleOp> recovered = parseSourceString<ModuleOp>(kValid, &context);
  if (!recovered || failed(session.accept(*recovered)))
    return fail("session did not recover after rejection");
  if (!session.getAcceptedIR() || *session.getAcceptedIR() != accepted)
    return fail("deterministic accepted state changed after recovery");

  llvm::outs() << "Increment 21 transactional verification session passed\n";
  return 0;
}
