//===- NodalOps.td - Nodal operation definitions ----------*- tablegen -*-===//
//
// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
//
//===----------------------------------------------------------------------===//

#ifndef NODAL_DIALECT_NODAL_NODALOPS_TD
#define NODAL_DIALECT_NODAL_NODALOPS_TD

include "nodal/Dialect/Nodal/NodalDialect.td"
include "mlir/IR/OpBase.td"

class Nodal_Op<string mnemonic, list<Trait> traits = []>
    : Op<Nodal_Dialect, mnemonic, traits>;

def Nodal_PlaceholderOp : Nodal_Op<"placeholder"> {
  let summary = "Verified semantics-free dialect bootstrap operation";
  let description = [{
    `nodal.placeholder` proves dialect registration, generated
    parser/printer integration, and operation verification. It deliberately
    carries no hardware, timing, hierarchy, analog, scheduling, or backend
    semantics.

    The required `label` is diagnostic bootstrap metadata and must be
    non-empty.
  }];

  let arguments = (ins StrAttr:$label);
  let assemblyFormat = "$label attr-dict";
  let hasVerifier = 1;
}

#endif // NODAL_DIALECT_NODAL_NODALOPS_TD
