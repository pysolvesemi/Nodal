//===- NodalDialect.td - Nodal dialect definition ---------*- tablegen -*-===//
//
// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
//
//===----------------------------------------------------------------------===//

#ifndef NODAL_DIALECT_NODAL_NODALDIALECT_TD
#define NODAL_DIALECT_NODAL_NODALDIALECT_TD

include "mlir/IR/OpBase.td"

def Nodal_Dialect : Dialect {
  let name = "nodal";
  let cppNamespace = "::nodal";
  let summary = "Private compiler IR for Nodal hardware construction";
  let description = [{
    The Nodal dialect is the authoritative private compiler IR beneath the
    frozen Scala public API. Increment 18 establishes dialect registration and
    the verified semantics-free placeholder operation. Increment 19 adds the
    canonical type and structural operation schema while leaving lowering and
    backend behavior to later increments.
  }];
  let useDefaultTypePrinterParser = 1;
  let extraClassDeclaration = [{
    void registerTypes();
  }];
}

#endif // NODAL_DIALECT_NODAL_NODALDIALECT_TD
