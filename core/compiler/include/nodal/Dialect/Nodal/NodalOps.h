#ifndef NODAL_DIALECT_NODAL_NODALOPS_H
#define NODAL_DIALECT_NODAL_NODALOPS_H

#include "mlir/Bytecode/BytecodeOpInterface.h"
#include "mlir/IR/Builders.h"
#include "mlir/IR/OpDefinition.h"
#include "mlir/IR/OpImplementation.h"
#include "mlir/IR/SymbolInterfaces.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"

#define GET_OP_CLASSES
#include "nodal/Dialect/Nodal/NodalOps.h.inc"

#endif // NODAL_DIALECT_NODAL_NODALOPS_H
