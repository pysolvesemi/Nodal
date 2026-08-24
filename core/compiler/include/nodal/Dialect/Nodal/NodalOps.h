#ifndef NODAL_DIALECT_NODAL_NODALOPS_H
#define NODAL_DIALECT_NODAL_NODALOPS_H

#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/OpDefinition.h"
#include "mlir/IR/OpImplementation.h"

#define GET_OP_CLASSES
#include "nodal/Dialect/Nodal/NodalOps.h.inc"

#endif // NODAL_DIALECT_NODAL_NODALOPS_H
