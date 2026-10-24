#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "mlir/IR/Verifier.h"
#include "mlir/IR/Builders.h"
#include <limits>
#include "mlir/Parser/Parser.h"
#include "circt/Dialect/HW/HWDialect.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Backend/ScalarHierarchyMaterialization.h"
#include "llvm/Support/raw_ostream.h"
#include <string>
#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
// Focused arithmetic-parent controls mutate only after successful parse and IR
// verification. They call the production queries directly, not a test evaluator.
namespace {
using mlir::Operation;
using mlir::Value;

std::string probeText(mlir::ModuleOp module) {
  std::string text;
  llvm::raw_string_ostream stream(text);
  module.print(stream);
  stream.flush();
  return text;
}

Operation *probeOp(mlir::OpBuilder &builder, llvm::StringRef name,
                   mlir::Type type, llvm::ArrayRef<Value> operands,
                   llvm::ArrayRef<mlir::NamedAttribute> attributes) {
  mlir::OperationState state(builder.getUnknownLoc(), name);
  state.addOperands(operands);
  if (type) state.addTypes(type);
  state.addAttributes(attributes);
  state.addAttribute("metadata", builder.getDictionaryAttr({}));
  return builder.create(state);
}

Value probeExpr(mlir::OpBuilder &builder, llvm::StringRef opcode,
                llvm::ArrayRef<Value> operands) {
  return probeOp(builder, "nodal.const_expr", builder.getF64Type(), operands,
                 {builder.getNamedAttr("operator_name", builder.getStringAttr(opcode))})
      ->getResult(0);
}
Value probeLiteral(mlir::OpBuilder &builder, double number) {
  return probeOp(builder, "nodal.const_literal", builder.getF64Type(), {},
                 {builder.getNamedAttr("value", builder.getF64FloatAttr(number)),
                  builder.getNamedAttr("spelling", builder.getStringAttr(std::to_string(number)))})
      ->getResult(0);
}
Operation *probeParameter(mlir::ModuleOp module, llvm::StringRef name) {
  Operation *found = nullptr;
  module.walk([&](nodal::ParameterOp op) {
    if (op->getAttrOfType<mlir::StringAttr>("sym_name").getValue() == name && !found)
      found = op;
  });
  return found;
}

int parentRealQuery(mlir::ModuleOp module, llvm::StringRef mutation) {
  Operation *override = nullptr;
  module.walk([&](nodal::ParameterOverrideOp op) {
    if (!override && llvm::isa<nodal::GenerateOp>(op->getParentOp())) override = op;
  });
  if (!override) return 20;
  Operation *instance = nodal::resolveStructuralInstance(
      override, override->getAttrOfType<mlir::FlatSymbolRefAttr>("instance"));
  Operation *child = nodal::resolveInstanceModuleDefinition(instance);
  Operation *formal = nullptr;
  if (!child) return 20;
  for (Operation &op : child->getRegion(0).front())
    if (llvm::isa<nodal::ParameterOp>(op) &&
        op.getAttrOfType<mlir::StringAttr>("sym_name").getValue() ==
            override->getAttrOfType<mlir::FlatSymbolRefAttr>("parameter").getValue())
      formal = &op;
  Operation *boundary = nullptr;
  module.walk([&](nodal::InterfaceBoundaryOp op) {
    if (!boundary && op->getNumOperands() == 2 &&
        op->getAttrOfType<mlir::FlatSymbolRefAttr>("instance") ==
            override->getAttrOfType<mlir::FlatSymbolRefAttr>("instance"))
      boundary = op;
  });
  if (!formal || !boundary) return 20;
  Operation *root = override->getOperand(0).getDefiningOp();
  Operation *family = override->getParentOp();
  Operation *parent = family->getParentOp();
  Operation *reference = nullptr;
  for (Operation &op : parent->getRegion(0).front())
    if (llvm::isa<nodal::ConstParameterRefOp>(op) && !reference) reference = &op;
  if (!root || !reference || !llvm::isa<nodal::ConstExprOp>(root)) return 20;
  mlir::OpBuilder builder(root);
  if (mutation == "foreign") {
    Operation *foreign = reference->clone();
    child->getRegion(0).front().push_front(foreign);
    root->setOperand(0, foreign->getResult(0));
  } else if (mutation == "runtime") {
    auto runtime = probeOp(builder, "nodal.dynamic_value", builder.getF64Type(),
                           {reference->getResult(0)},
                           {builder.getNamedAttr("origin", builder.getStringAttr("runtime"))});
    root->setOperand(0, runtime->getResult(0));
  } else if (mutation == "nested") {
    root->moveBefore(instance);
  } else if (mutation == "type") {
    root->getResult(0).setType(builder.getI64Type());
  } else if (mutation == "unit") {
    root->setAttr("unit", mlir::FlatSymbolRefAttr::get(builder.getContext(), "ProbeUnit"));
  } else if (mutation == "operator") {
    root->setAttr("operator_name", builder.getStringAttr("div"));
  } else if (mutation == "edge-order") {
    root->getOperand(0).getDefiningOp()->moveAfter(root);
  } else if (mutation == "cycle") {
    root->setOperand(0, root->getResult(0));
  } else if (mutation == "depth" || mutation == "render-limit") {
    builder.setInsertionPoint(family);
    Value value = root->getResult(0);
    const unsigned count = mutation == "depth" ? 512 : 20;
    for (unsigned i = 0; i < count; ++i)
      value = mutation == "depth" ? probeExpr(builder, "neg", {value})
                                  : probeExpr(builder, "add", {value, value});
    override->setOperand(0, value);
  } else if (mutation == "fixed") {
    formal->setAttr("variability", builder.getStringAttr("fixed"));
  } else if (mutation == "child-expression-default") {
    builder.setInsertionPointToEnd(&child->getRegion(0).front());
    Value literal = probeLiteral(builder, 2.0);
    Value expression = probeExpr(builder, "add", {literal, literal});
    probeOp(builder, "nodal.parameter_value", {}, {expression},
            {builder.getNamedAttr("parameter", override->getAttr("parameter"))});
  } else if (mutation == "nonfinite") {
    builder.setInsertionPoint(root);
    Value huge = probeLiteral(builder, std::numeric_limits<double>::max());
    Value overflow = probeExpr(builder, "mul", {huge, huge});
    root->setOperand(0, overflow);
  } else if (mutation != "valid") {
    return 21;
  }
  const std::string before = probeText(module);
  const bool pm = nodal::isGeneratedParentRealParameterOverride(override, instance, formal);
  // Intentionally independent of verifyParameterModel and the PM classifier.
  const bool cc = mlir::succeeded(nodal::resolveConservativeGeneratedSelection(boundary));
  const bool unchanged = before == probeText(module);
  llvm::outs() << "parsed_valid=1 pm=" << pm << " cc=" << cc
               << " unchanged=" << unchanged << "\n";
  if (mutation == "fixed" || mutation == "nonfinite")
    llvm::outs() << "parameter_contract=" << mlir::succeeded(nodal::verifyParameterModel(module))
                 << "\n";
  return unchanged ? 0 : 22;
}

int constantEvaluation(mlir::ModuleOp module, llvm::StringRef mutation) {
  Operation *parameter = probeParameter(module, "P");
  if (!parameter) return 20;
  mlir::OpBuilder builder(parameter);
  builder.setInsertionPointAfter(parameter);
  Value literal = probeLiteral(builder, 5.0);
  Value value = literal;
  if (mutation == "sharing") {
    // Forty unique nodes represent > 2^40 evaluation edges without memoization.
    for (unsigned i = 0; i < 40; ++i) value = probeExpr(builder, "sub", {value, value});
    parameter->setAttr("default_value", builder.getF64FloatAttr(0.0));
  } else if (mutation == "depth-510" || mutation == "depth-511") {
    const unsigned count = mutation == "depth-510" ? 510 : 511;
    for (unsigned i = 0; i < count; ++i) value = probeExpr(builder, "neg", {value});
  } else if (mutation == "memo-depth") {
    for (unsigned i = 0; i < 510; ++i) value = probeExpr(builder, "neg", {value});
    // Shallow left visit caches literal; right visit must still honor active depth.
    value = probeExpr(builder, "add", {literal, value});
  } else if (mutation == "attribute-boundary" || mutation == "attachment-boundary") {
    Operation *terminal = parameter->clone();
    terminal->setAttr("sym_name", builder.getStringAttr("Q"));
    builder.insert(terminal);
    if (mutation == "attachment-boundary")
      probeOp(builder, "nodal.parameter_value", {}, {literal},
              {builder.getNamedAttr("parameter", mlir::FlatSymbolRefAttr::get(builder.getContext(), "Q"))});
    value = probeOp(builder, "nodal.const_parameter_ref", builder.getF64Type(), {},
                    {builder.getNamedAttr("parameter", mlir::FlatSymbolRefAttr::get(builder.getContext(), "Q"))})
                ->getResult(0);
    for (unsigned i = 0; i < 510; ++i) value = probeExpr(builder, "neg", {value});
  } else if (mutation == "cycle") {
    value = probeExpr(builder, "neg", {value});
    value.getDefiningOp()->setOperand(0, value);
  } else if (mutation == "parameter-cycle") {
    value = probeOp(builder, "nodal.const_parameter_ref", builder.getF64Type(), {},
                    {builder.getNamedAttr("parameter", mlir::FlatSymbolRefAttr::get(builder.getContext(), "P"))})
                ->getResult(0);
  } else {
    return 21;
  }
  probeOp(builder, "nodal.parameter_value", {}, {value},
          {builder.getNamedAttr("parameter", mlir::FlatSymbolRefAttr::get(builder.getContext(), "P"))});
  // Remove the unrelated child override so these are solely generic default
  // evaluator checks, unconstrained by the new forwarding/rendering profile.
  llvm::SmallVector<Operation *> overrides;
  module.walk([&](nodal::ParameterOverrideOp op) { overrides.push_back(op); });
  for (Operation *op : overrides) op->erase();
  const std::string before = probeText(module);
  const bool accepted = mlir::succeeded(nodal::verifyParameterModel(module));
  const bool unchanged = before == probeText(module);
  llvm::outs() << "parsed_valid=1 parameter_contract=" << accepted
               << " unchanged=" << unchanged << "\n";
  return unchanged ? 0 : 22;
}
} // namespace

int main(int argc, char **argv) {
  if (argc != 3) return 2;
  mlir::MLIRContext context;
  context.loadDialect<nodal::NodalDialect, circt::hw::HWDialect>();
  auto parsed=mlir::parseSourceFile<mlir::ModuleOp>(argv[1], &context);
  if (!parsed || mlir::failed(mlir::verify(*parsed))) return 3;
  const llvm::StringRef mode(argv[2]);
  if (mode.starts_with("parent-real-query-"))
    return parentRealQuery(*parsed, mode.drop_front(18));
  if (mode.starts_with("constant-evaluation-"))
    return constantEvaluation(*parsed, mode.drop_front(20));
  // Candidate 2 test-only modes. Existing modes and their dispatch remain unchanged.
  if (std::string(argv[2]) == "parameter-contract")
    return mlir::failed(nodal::verifyParameterModel(*parsed)) ? 14 : 0;
  if (std::string(argv[2]) == "materialized-scalar") {
    std::string before;
    llvm::raw_string_ostream beforeStream(before);
    parsed->print(beforeStream);
    beforeStream.flush();
    std::string previous;
    for (unsigned repetition = 0; repetition < 2; ++repetition) {
      std::string target, after;
      llvm::raw_string_ostream output(target), afterStream(after);
      auto status = nodal::emitBackend(*parsed, nodal::BackendKind::VerilogA, output, nullptr,
                                       nodal::HierarchyEmission::MaterializedScalar);
      output.flush();
      parsed->print(afterStream);
      afterStream.flush();
      if (mlir::failed(status))
        return 15;
      if (after != before || (repetition && target != previous)) {
        llvm::errs() << "materialized-scalar changed source or was nondeterministic\n";
        return 16;
      }
      previous = target;
    }
    llvm::outs() << previous;
    return 0;
  }
  if (std::string(argv[2]) == "child-pairs") {
    std::string before, after; llvm::raw_string_ostream beforeStream(before), afterStream(after);
    parsed->print(beforeStream); beforeStream.flush();
    llvm::SmallVector<mlir::Value> values;
    parsed->walk([&](nodal::MemberAccessOp op) {
      auto member = nodal::inspectDigitalInterfaceMember(op);
      if (mlir::succeeded(member) && member->instance) values.push_back(op->getResult(0));
    });
    for (unsigned i=0;i<values.size();++i) for(unsigned j=0;j<i;++j) {
      auto use=&values[i].getDefiningOp()->getBlock()->back();
      auto relation=nodal::compareConservativeEndpoints(values[j],values[i],use);
      llvm::outs()<<j<<","<<i<<"="<<unsigned(relation)<<"\n";
    }
    parsed->print(afterStream); afterStream.flush();
    return before != after ? 13 : (values.empty()?12:0);
  }
  if (std::string(argv[2]) == "pair") {
    llvm::SmallVector<mlir::Value> values;
    mlir::Operation *use = nullptr;
    parsed->walk([&](nodal::MemberAccessOp op) {
      auto access = nodal::inspectDigitalInterfaceMember(op);
      if (mlir::succeeded(access) && access->selection) values.push_back(op->getResult(0));
    });
    parsed->walk([&](nodal::ConnectOp op) { if (llvm::isa<nodal::ModuleOp>(op->getParentOp())) use = op; });
    if (values.size() != 2 || !use) return 8;
    llvm::outs() << "electrical=" << unsigned(nodal::compareConservativeEndpoints(values[0], values[1], use)) << "\n";
    return 0;
  }
  if (std::string(argv[2]) == "verify") return 0;
  if (std::string(argv[2]) == "selected-aliases") {
    llvm::SmallVector<mlir::Value> values; mlir::Operation *use = nullptr;
    parsed->walk([&](nodal::MemberAccessOp op) {
      auto access = nodal::resolveDigitalInterfaceAccess(op->getResult(0));
      if (mlir::succeeded(access) && access->identity.selection) values.push_back(op->getResult(0));
    });
    parsed->walk([&](nodal::ConnectOp op) { if (llvm::isa<nodal::ModuleOp>(op->getParentOp())) use = op; });
    if (values.size() != 3 || !use) return 8;
    auto equal = nodal::compareConservativeEndpoints(values[0], values[1], use);
    auto distinct = nodal::compareConservativeEndpoints(values[0], values[2], use);
    llvm::outs() << "same_coordinate=" << unsigned(equal) << " distinct_coordinate=" << unsigned(distinct) << "\n";
    auto a = nodal::resolveConservativeEndpoint(values[0], use);
    auto b = nodal::resolveConservativeEndpoint(values[2], use);
    if (mlir::failed(a) || mlir::failed(b)) return 10;
    auto coordinates = nodal::compareStaticShapeProjections(a->shape.projection, b->shape.projection, use);
    llvm::outs() << "coordinate_status=" << unsigned(coordinates.status)
                 << " coordinate_overlap=" << unsigned(coordinates.overlap) << "\n";
    return equal == nodal::ConservativeAlias::Equal && distinct == nodal::ConservativeAlias::Unknown &&
        coordinates.status == nodal::ShapeRegionProofStatus::Proved &&
        coordinates.overlap == nodal::ShapeRegionOverlap::Disjoint ? 0 : 9;
  }
  if (std::string(argv[2]) == "selected-identity") {
    bool valid = true; unsigned selected = 0;
    parsed->walk([&](nodal::ConnectOp connection) {
      for (mlir::Value value : connection->getOperands()) {
        if (!llvm::isa<nodal::MemberAccessOp>(value.getDefiningOp())) continue;
        auto access = nodal::resolveDigitalInterfaceAccess(value);
        if (mlir::failed(access) || !access->identity.selection) continue;
        auto endpoint = nodal::resolveConservativeEndpoint(value, connection);
        ++selected;
        valid &= mlir::succeeded(endpoint) && !endpoint->port && !endpoint->instance &&
            endpoint->declarationScope == access->identity.endpoint->getParentOp() &&
            llvm::isa<nodal::GenerateOp>(endpoint->declarationScope) &&
            mlir::failed(nodal::resolveConservativePortIncidence(value, connection));
      }
    });
    llvm::outs() << "selected=" << selected << " internal_incidence=" << valid << "\n";
    return valid && selected ? 0 : 6;
  }

  if (std::string(argv[2]) == "identity") {
    bool valid = true; unsigned endpoints = 0;
    parsed->walk([&](nodal::ConnectOp connection) {
      for (mlir::Value value : connection->getOperands()) {
        auto endpoint = nodal::resolveConservativeEndpoint(value, connection);
        if (mlir::failed(endpoint)) { valid = false; continue; }
        ++endpoints;
        if (llvm::isa<nodal::MemberAccessOp>(value.getDefiningOp()) &&
            llvm::isa<nodal::GenerateOp>(connection->getParentOp())) {
          valid &= endpoint->declarationScope == connection->getParentOp();
          valid &= endpoint->instance || !endpoint->port;
          auto incidence = nodal::resolveConservativePortIncidence(value, connection);
          valid &= endpoint->instance || mlir::failed(incidence);
        }
      }
    });
    llvm::outs() << "endpoints=" << endpoints << " valid=" << valid << "\n";
    return valid && endpoints ? 0 : 6;
  }
  if (std::string(argv[2]) == "adapt") {
    if (mlir::failed(nodal::materializeConservativeInterfaceBoundary(*parsed))) return 4;
    parsed->print(llvm::outs()); return 0;
  }
  if (std::string(argv[2]) == "transaction") {
    std::string before, after, target;
    llvm::raw_string_ostream a(before), b(after), output(target);
    parsed->print(a); a.flush();
    auto status = nodal::emitBackend(*parsed, nodal::BackendKind::VerilogA, output);
    output.flush(); parsed->print(b); b.flush();
    const bool valid = mlir::failed(status) && target.empty() && before == after;
    llvm::outs() << "failure_is_transactional=" << valid << "\n";
    return valid ? 0 : 7;
  }
  std::string target; llvm::raw_string_ostream output(target);
  if (mlir::failed(nodal::emitBackend(*parsed, nodal::BackendKind::VerilogA, output))) return 5;
  llvm::outs()<<target; return 0;
}
