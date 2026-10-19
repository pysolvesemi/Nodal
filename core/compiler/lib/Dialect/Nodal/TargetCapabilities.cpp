#include "nodal/Dialect/Nodal/TargetCapabilities.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Visitors.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Support/BackendCapabilityRegistry.h"

#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/Twine.h"
#include "llvm/Support/Casting.h"

using namespace mlir;

namespace nodal {
namespace {

/// An iterative graph traversal avoids recursion on deeply nested type/attribute
/// containers. Retaining visited elements across a module scan bounds repeated
/// visits to shared analog carriers. A digital finding stops that scan, so no
/// cached negative can hide a later digital use.
class DigitalCarrierSearch {
public:
  bool containsDigitalCapability(Operation *operation) {
    if (isDigitalBackendOperation(operation->getName().getStringRef()))
      return true;

    for (Type type : operation->getOperandTypes())
      enqueue(type);
    for (Type type : operation->getResultTypes())
      enqueue(type);
    for (Region &region : operation->getRegions())
      for (Block &block : region)
        for (BlockArgument argument : block.getArguments())
          enqueue(argument.getType());
    enqueue(operation->getAttrDictionary());

    while (!attributes.empty() || !types.empty()) {
      if (!types.empty()) {
        Type type = types.pop_back_val();
        if (llvm::isa<BitsType, UIntType, SIntType, EnumType, DomainType, ResolvedType, DriverType,
                      StreamType, ValidType, ControlCapabilityType, StaticPredicateType>(type))
          return true;
        // ShapedType, builtin containers and future structural wrappers expose
        // their actual element types through MLIR's canonical subelement API.
        // Quantity, Variable, Terminal and Branch are intentionally not digital.
        // InterfaceType names an identity whose members may all be analog. The
        // all-definition IR walk inspects member carriers; this name alone makes
        // no domain claim. Existing verifiers still own unresolved identities.
        type.walkImmediateSubElements([&](Attribute attr) { enqueue(attr); },
                                      [&](Type nested) { enqueue(nested); });
      } else {
        Attribute attribute = attributes.pop_back_val();
        attribute.walkImmediateSubElements([&](Attribute nested) { enqueue(nested); },
                                           [&](Type type) { enqueue(type); });
      }
    }
    return false;
  }

private:
  void enqueue(Type type) {
    if (type && visitedTypes.insert(type).second)
      types.push_back(type);
  }

  void enqueue(Attribute attribute) {
    if (attribute && visitedAttributes.insert(attribute).second)
      attributes.push_back(attribute);
  }

  llvm::DenseSet<Type> visitedTypes;
  llvm::DenseSet<Attribute> visitedAttributes;
  llvm::SmallVector<Type> types;
  llvm::SmallVector<Attribute> attributes;
};

} // namespace

bool hasDigitalTargetCapability(Operation *operation) {
  DigitalCarrierSearch search;
  return search.containsDigitalCapability(operation);
}

LogicalResult verifyTargetLanguageCapabilities(mlir::ModuleOp module,
                                               llvm::StringRef languageProfile) {
  if (languageProfile != "verilog-a" && languageProfile != "verilog-ams")
    return emitMappedFailure(module.getOperation(), "NODAL-VERIFY-CAPABILITY-005",
                             llvm::Twine("unknown target language profile '") + languageProfile +
                                 "'");
  if (languageProfile == "verilog-ams")
    return success();

  DigitalCarrierSearch search;
  WalkResult result = module.walk<WalkOrder::PreOrder>([&](Operation *operation) {
    if (!search.containsDigitalCapability(operation))
      return WalkResult::advance();
    (void)emitMappedFailure(operation, "NODAL-VERIFY-CAPABILITY-006",
                            llvm::Twine("target language profile 'verilog-a' rejects digital ") +
                                "operation or type in '" + operation->getName().getStringRef() +
                                "'");
    return WalkResult::interrupt();
  });
  return failure(result.wasInterrupted());
}

} // namespace nodal
