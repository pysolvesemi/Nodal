#include "nodal/Dialect/Nodal/NodalOps.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/Support/LogicalResult.h"

using namespace mlir;

#define GET_OP_CLASSES
#include "nodal/Dialect/Nodal/NodalOps.cpp.inc"

LogicalResult nodal::PlaceholderOp::verify() {
  auto label = (*this)->getAttrOfType<StringAttr>("label");
  if (!label || label.getValue().empty())
    return emitOpError("requires a non-empty 'label' attribute");
  return success();
}
