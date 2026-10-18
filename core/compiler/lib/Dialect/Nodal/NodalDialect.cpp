#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "nodal/Dialect/Nodal/AnalogUserFunctions.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"

using namespace mlir;

#include "nodal/Dialect/Nodal/NodalOpsDialect.cpp.inc"

void nodal::NodalDialect::initialize() {
  addOperations<
#define GET_OP_LIST
#include "nodal/Dialect/Nodal/NodalOps.cpp.inc"
      >();
  registerTypes();
}

mlir::LogicalResult nodal::NodalDialect::verifyOperationAttribute(mlir::Operation *operation,
                                                                  mlir::NamedAttribute attribute) {
  if (attribute.getName().getValue() == "nodal.canonical.dead_local")
    return verifyAnalogDeadLocalMarker(operation);
  return mlir::success();
}
