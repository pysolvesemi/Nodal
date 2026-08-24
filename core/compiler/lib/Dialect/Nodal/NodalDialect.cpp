#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

using namespace mlir;

#include "nodal/Dialect/Nodal/NodalOpsDialect.cpp.inc"

void nodal::NodalDialect::initialize() {
  addOperations<
#define GET_OP_LIST
#include "nodal/Dialect/Nodal/NodalOps.cpp.inc"
      >();
}
