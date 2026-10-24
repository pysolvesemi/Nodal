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

// Mutate only a parsed, verified positive. Queries and failed emission must be
// read-only; no hand-written alternate shape or cardinality evaluator is used.
int symbolicFamilyQuery(mlir::ModuleOp module, llvm::StringRef mutation) {
  Operation *boundary = nullptr;
  module.walk([&](nodal::InterfaceBoundaryOp op) {
    if (!boundary && op->getNumOperands() == 2) boundary = op;
  });
  if (!boundary) return 30;
  Operation *family = boundary->getOperand(0).getDefiningOp();
  Operation *parameter = probeParameter(module, "LANES");
  if (!family || !parameter) return 30;
  Operation *expectedParameter = parameter;
  Operation *upper = family->getOperand(1).getDefiningOp();
  Operation *body = nullptr;
  module.walk([&](nodal::InterfaceBoundaryOp op) {
    if (!body && op->getParentOp() == family &&
        op->getAttr("instance") == boundary->getAttr("instance")) body = op;
  });
  if (!body) return 30;
  mlir::OpBuilder builder(family);
  if (mutation == "shape-effect") {
    module.walk([&](nodal::ParameterEnvelopeOp op) {
      if (op->getParentOp() == family->getParentOp() &&
          op->getAttrOfType<mlir::FlatSymbolRefAttr>("parameter").getValue() == "LANES")
        op->setAttr("effects", builder.getArrayAttr({builder.getStringAttr("topology")}));
    });
  } else if (mutation == "upper-identity") {
    Operation *copy = parameter->clone();
    copy->setAttr("sym_name", builder.getStringAttr("OTHER"));
    builder.setInsertionPoint(parameter); builder.insert(copy);
    Operation *envelope = nullptr;
    module.walk([&](nodal::ParameterEnvelopeOp op) {
      if (op->getParentOp() == family->getParentOp() &&
          op->getAttrOfType<mlir::FlatSymbolRefAttr>("parameter").getValue() == "LANES")
        envelope = op;
    });
    if (!envelope) return 30;
    Operation *copyEnvelope = envelope->clone();
    copyEnvelope->setAttr("parameter", mlir::FlatSymbolRefAttr::get(builder.getContext(), "OTHER"));
    builder.setInsertionPointAfter(envelope); builder.insert(copyEnvelope);
    // Metadata is not a canonical finite-domain constraint. Collect before cloning.
    llvm::SmallVector<Operation *> constraints;
    module.walk([&](nodal::ParameterConstraintOp op) {
      if (op->getParentOp() == family->getParentOp() &&
          op->getAttrOfType<mlir::FlatSymbolRefAttr>("parameter").getValue() == "LANES")
        constraints.push_back(op);
    });
    if (constraints.empty()) return 30;
    for (Operation *constraint : constraints) {
      Operation *copyConstraint = constraint->clone();
      copyConstraint->setAttr("parameter", mlir::FlatSymbolRefAttr::get(builder.getContext(), "OTHER"));
      builder.setInsertionPointAfter(constraint); builder.insert(copyConstraint);
    }
    expectedParameter = copy;
    upper->setAttr("parameter", mlir::FlatSymbolRefAttr::get(builder.getContext(), "OTHER"));
  } else if (mutation == "upper-order") {
    upper->moveAfter(family);
  } else if (mutation == "count" || mutation == "maximum") {
    mlir::NamedAttrList attrs(family->getAttrOfType<mlir::DictionaryAttr>("metadata"));
    attrs.set(mutation == "count" ? "maximum_trip_count" : "declared_maximum",
              builder.getI64IntegerAttr(3));
    family->setAttr("metadata", builder.getDictionaryAttr(attrs));
  } else if (mutation == "body-incidence") {
    body->setAttr("endpoint", builder.getStringAttr("missing"));
  } else if (mutation == "local-family") {
    auto original = nodal::resolveConservativeGeneratedSelection(boundary);
    if (mlir::failed(original)) return 30;
    Operation *local = original->declaration->clone();
    local->setAttr("sym_name", builder.getStringAttr("local_pins"));
    family->getRegion(0).front().push_front(local);
    for (Operation &op : family->getRegion(0).front()) {
      if (llvm::isa<nodal::GeneratedExportOp>(op) && op.getAttr("member") == boundary->getAttr("member")) {
        op.setAttr("kind", builder.getStringAttr("interface"));
        op.setAttr("target", mlir::FlatSymbolRefAttr::get(builder.getContext(), "local_pins"));
      }
    }
    boundary->removeAttr("instance");
    boundary->setAttr("endpoint", builder.getStringAttr("local_pins"));
  } else if (mutation == "capability") {
    auto original = nodal::resolveConservativeGeneratedSelection(boundary);
    if (mlir::failed(original)) return 30;
    original->declaration->setAttr("role", builder.getStringAttr("missing_role"));
  } else if (mutation == "absent-endpoint") {
    boundary->setAttr("endpoint", builder.getStringAttr("missing"));
  } else if (mutation != "valid" && mutation != "budget-zero") {
    return 31;
  }
  const std::string before = probeText(module);
  auto selection = nodal::resolveConservativeGeneratedSelection(boundary);
  bool accepted = mlir::succeeded(selection);
  if (mutation == "budget-zero") {
    unsigned work = 0;
    nodal::InstantiatedIntegerExpression normalized;
    auto status = nodal::resolveGeneratedInductionOrdinal(
        family, family->getRegion(0).front().getArgument(0), body, normalized, work);
    llvm::outs() << "budget_exhausted="
                 << (status == nodal::ShapeRegionProofStatus::ResourceLimit)
                 << " unchanged=" << (before == probeText(module)) << "\n";
    return status == nodal::ShapeRegionProofStatus::ResourceLimit &&
                   before == probeText(module) ? 0 : 32;
  }
  bool exact = accepted && selection->extentParameter == expectedParameter && selection->cardinality == 5;
  llvm::outs() << "parsed_valid=1 cc=" << accepted << " exact_extent=" << exact
               << " unchanged=" << (before == probeText(module)) << "\n";
  if (mutation == "valid" || mutation == "upper-identity") return exact && before == probeText(module) ? 0 : 32;
  std::string target;
  llvm::raw_string_ostream output(target);
  auto status = nodal::emitBackend(module, nodal::BackendKind::VerilogA, output);
  output.flush();
  const bool transactional = mlir::failed(status) && target.empty() && before == probeText(module);
  llvm::outs() << "failure_is_transactional=" << transactional << "\n";
  const bool expectedQuery = mutation == "body-incidence" || mutation == "capability";
  return accepted == expectedQuery && transactional ? 0 : 33;
}

// Narrow view/slice and query-lifetime controls derived from ShapeRegionTest.
// The proof and mapper are production APIs; no local geometry evaluator exists.
int repairShapeQuery(mlir::ModuleOp module) {
  using S = nodal::ShapeRegionProofStatus;
  auto find = [&](llvm::StringRef name) {
    Value result;
    module.walk([&](Operation *op) {
      auto metadata = op->getAttrOfType<mlir::DictionaryAttr>("metadata");
      auto tag = metadata ? metadata.getAs<mlir::StringAttr>("test_name") : mlir::StringAttr();
      if (tag && tag.getValue() == name && op->getNumResults() == 1) result = op->getResult(0);
    });
    return result;
  };
  Value pointValue = find("point"), root = find("root"), zero = find("zero"), one = find("one");
  Value middle = find("middle"), marker = find("marker");
  if (!pointValue || !root || !zero || !one || !middle || !marker) return 40;
  Operation *use = marker.getDefiningOp();
  unsigned checks = 0, failures = 0;
  auto check = [&](bool condition, llvm::StringRef label) {
    ++checks; failures += !condition;
    llvm::outs() << label << "=" << condition << "\n";
  };
  const std::string original = probeText(module);
  auto point = [&](nodal::StaticShapePoint &output, nodal::ShapeRegionProofLimits limits = {}) {
    auto projection = nodal::resolveStaticShapeProjection(pointValue, use);
    return mlir::failed(projection) ? S::Invalid
        : nodal::resolveStaticShapePoint(*projection, use, output, limits);
  };
  nodal::StaticShapePoint initial;
  auto exact = [&](const nodal::StaticShapePoint &value, int64_t ordinal) {
    return value.root == root && value.flatOrdinal &&
        nodal::equivalentInstantiatedIntegerExpressions(
            *value.flatOrdinal, nodal::makeLiteralIntegerExpression(ordinal));
  };
  check(point(initial) == S::Proved && exact(initial, 2), "view-slice-original-point");
  auto middleProjection = nodal::resolveStaticShapeProjection(middle, use);
  nodal::StaticShapeRegion region;
  bool regionOK = mlir::succeeded(middleProjection) &&
      nodal::resolveStaticShapeRegion(*middleProjection, use, region) == S::Proved;
  check(regionOK, "view-slice-region");
  if (regionOK) {
    auto located = nodal::locateStaticShapeScalar(region, initial, use);
    check(located.status == S::Proved && located.membership == nodal::ShapeRegionMembership::Inside &&
          located.ordinal && nodal::equivalentInstantiatedIntegerExpressions(
              *located.ordinal, nodal::makeLiteralIntegerExpression(1)), "view-slice-inverse");
  }
  // Same complete point identity/bounds comparison as the canonical mapper.
  auto identical = [](const nodal::StaticShapePoint &a, const nodal::StaticShapePoint &b) {
    auto equalExpression = [](const nodal::InstantiatedIntegerExpression &x,
                              const nodal::InstantiatedIntegerExpression &y) {
      return x.root == y.root && x.bounds.lower == y.bounds.lower && x.bounds.upper == y.bounds.upper;
    };
    if (a.root != b.root || a.coordinates.size() != b.coordinates.size() ||
        a.flatOrdinal.has_value() != b.flatOrdinal.has_value()) return false;
    for (unsigned i = 0; i < a.coordinates.size(); ++i)
      if (!equalExpression(a.coordinates[i], b.coordinates[i])) return false;
    return !a.flatOrdinal || equalExpression(*a.flatOrdinal, *b.flatOrdinal);
  };
  for (unsigned work : {0u, 1u}) {
    nodal::StaticShapePoint output = initial;
    auto status = point(output, {work, 1});
    check(status == S::ResourceLimit && identical(output, initial), work ? "tiny-budget-transaction" : "zero-budget-transaction");
  }
  pointValue.getDefiningOp()->setOperand(1, zero);
  nodal::StaticShapePoint changed;
  check(point(changed) == S::Proved && exact(changed, 1), "fresh-query-after-selector-mutation");
  pointValue.getDefiningOp()->setOperand(1, one);
  // Keep the same typed value, but make its defining projection cyclic.
  Operation *slice = middle.getDefiningOp();
  Value source = slice->getOperand(0);
  slice->setOperand(0, middle);
  const auto cyclic = probeText(module);
  nodal::StaticShapePoint output = initial;
  check(point(output) == S::Invalid && probeText(module) == cyclic,
        "cycle-refusal-readonly");
  slice->setOperand(0, source);
  Operation *next = slice->getNextNode();
  slice->moveAfter(use);
  const auto reordered = probeText(module);
  check(point(output) == S::Invalid && probeText(module) == reordered,
        "order-refusal-readonly");
  slice->moveBefore(next);
  check(point(output) == S::Proved && exact(output, 2), "fresh-query-after-restore");
  check(probeText(module) == original, "fixture-restored");
  llvm::outs() << "SUMMARY checks=" << checks << " failures=" << failures << "\n";
  return failures ? 41 : 0;
}
} // namespace

namespace repair_mapper {
using S = nodal::ShapeRegionProofStatus;
using P = nodal::StaticShapePoint;
using Projection = nodal::StaticShapeProjection;
using Limits = nodal::ShapeRegionProofLimits;
static bool equalExpression(const nodal::InstantiatedIntegerExpression &a,
                            const nodal::InstantiatedIntegerExpression &b) {
  return a.root == b.root && a.bounds.lower == b.bounds.lower && a.bounds.upper == b.bounds.upper;
}
static bool identical(const P &a, const P &b) {
  if (a.root != b.root || a.coordinates.size() != b.coordinates.size() ||
      a.flatOrdinal.has_value() != b.flatOrdinal.has_value())
    return false;
  for (unsigned i = 0; i < a.coordinates.size(); ++i)
    if (!equalExpression(a.coordinates[i], b.coordinates[i]))
      return false;
  return !a.flatOrdinal || equalExpression(*a.flatOrdinal, *b.flatOrdinal);
}
static const char *name(S s) {
  switch (s) {
  case S::Proved:
    return "Proved";
  case S::Unsupported:
    return "Unsupported";
  case S::Invalid:
    return "Invalid";
  case S::ResourceLimit:
    return "ResourceLimit";
  }
  return "unknown";
}
static std::string print(mlir::Operation *op) {
  std::string s;
  llvm::raw_string_ostream os(s);
  op->print(os, mlir::OpPrintingFlags().printGenericOpForm().assumeVerified());
  return s;
}
int run(int argc, char **argv) {
  if (argc != 3) {
    llvm::errs() << "usage: map-controls FILE paired|anchor|reject|outside|capture\n";
    return 2;
  }
  const std::string mode = argv[2];
  if (mode != "paired" && mode != "anchor" && mode != "reject" && mode != "outside" &&
      mode != "capture")
    return 2;
  mlir::MLIRContext context;
  context.loadDialect<nodal::NodalDialect, circt::hw::HWDialect>();
  mlir::ParserConfig config(&context, false);
  auto module = mlir::parseSourceFile<mlir::ModuleOp>(argv[1], config);
  auto foreign = mlir::parseSourceFile<mlir::ModuleOp>(argv[1], config);
  if (!module || !foreign)
    return 3;
  const auto before = print(module->getOperation());
  llvm::SmallVector<mlir::Operation *> relations, foreignRelations;
  auto collect = [&](mlir::ModuleOp m, auto &out) {
    m.walk([&](nodal::ConnectOp op) {
      if (llvm::isa<nodal::GenerateOp>(op->getParentOp()) ||
          (mode == "capture" && llvm::isa<nodal::HardwareLoopOp>(op->getParentOp())))
        out.push_back(op);
    });
  };
  collect(*module, relations);
  collect(*foreign, foreignRelations);
  if (relations.empty() || foreignRelations.empty())
    return 4;
  auto *relation = relations.front();
  auto source = nodal::resolveStaticShapeProjection(relation->getOperand(0), relation);
  auto destination = nodal::resolveStaticShapeProjection(relation->getOperand(1), relation);
  if (mlir::failed(source) || mlir::failed(destination))
    return 5;
  llvm::SmallVector<std::pair<mlir::Operation *, P>> points;
  module->walk([&](nodal::ShapeIndexOp selection) {
    if (!llvm::isa<nodal::ModuleOp>(selection->getParentOp()))
      return;
    auto p = nodal::resolveStaticShapeProjection(selection->getResult(0), selection);
    P point;
    if (mlir::succeeded(p) && p->root == source->root &&
        nodal::resolveStaticShapePoint(*p, selection, point) == S::Proved)
      points.emplace_back(selection, point);
  });
  if (points.empty())
    return 6;
  // Existing outside fixtures also retain an unrelated in-image selected1.
  if (mode == "outside")
    points.resize(1);
  unsigned failures = 0, checks = 0;
  auto check = [&](bool ok, const std::string &label) {
    ++checks;
    failures += !ok;
    (ok ? llvm::outs() : llvm::errs()) << (ok ? "PASS " : "FAIL ") << label << '\n';
  };
  const P sentinel = points.front().second;
  auto call = [&](bool extended, const Projection &a, const P &point, const Projection &b,
                  mlir::Operation *use, Limits limits, const std::string &label, int expected,
                  bool report = true) {
    P output = sentinel;
    S status =
        extended
            ? nodal::mapStaticShapeSameOccurrenceOrInvariantPoint(a, point, b, use, output, limits)
            : nodal::mapStaticShapeSameOccurrencePoint(a, point, b, use, output, limits);
    if (report)
      llvm::outs() << label << " status=" << name(status)
                   << " unchanged=" << identical(output, sentinel) << '\n';
    if (expected >= 0)
      check(status == static_cast<S>(expected), label + "/status");
    else if (expected == -1)
      check(status != S::Proved, label + "/not-proved");
    if (status != S::Proved)
      check(identical(output, sentinel), label + "/transactional");
    else {
      check(output.root == b.root, label + "/destination-root");
      // These fixtures use fixed literal points; verify coordinates, not merely root.
      if (mode == "paired" && output.flatOrdinal && point.flatOrdinal)
        check(output.flatOrdinal->bounds.lower == point.flatOrdinal->bounds.lower &&
                  output.flatOrdinal->bounds.upper == point.flatOrdinal->bounds.upper,
              label + "/exact-fixed-coordinate");
      if (mode == "anchor")
        check(output.flatOrdinal && output.flatOrdinal->bounds.lower == 0 &&
                  output.flatOrdinal->bounds.upper == 0 && output.coordinates.empty(),
              label + "/exact-scalar-anchor");
    }
    return status;
  };
  for (unsigned i = 0; i < points.size(); ++i) {
    const auto &point = points[i].second;
    for (bool extended : {false, true}) {
      const std::string prefix =
          std::string(extended ? "new" : "old") + "/point" + std::to_string(i);
      const bool success = mode == "paired" || (mode == "anchor" && extended);
      const int expected =
          success ? int(S::Proved)
                  : (mode == "capture"
                         ? int(S::Invalid)
                         : (mode == "anchor" || mode == "outside" ? int(S::Unsupported) : -1));
      call(extended, *source, point, *destination, relation, {}, prefix, expected);
      call(extended, *source, point, *destination, nullptr, {}, prefix + "/null-use",
           int(S::Invalid));
      call(extended, *source, point, *destination, points[i].first, {}, prefix + "/not-relation",
           int(S::Invalid));
      call(extended, *source, point, *destination, foreignRelations.front(), {},
           prefix + "/foreign-use", int(S::Invalid));
      auto malformed = *source;
      malformed.root = destination->root;
      call(extended, malformed, point, *destination, relation, {}, prefix + "/wrong-source-root",
           int(S::Invalid));
      malformed = *source;
      malformed.projections.clear();
      call(extended, malformed, point, *destination, relation, {}, prefix + "/missing-projection",
           int(S::Invalid));
      auto foreignProjection = nodal::resolveStaticShapeProjection(
          foreignRelations.front()->getOperand(1), foreignRelations.front());
      if (mlir::succeeded(foreignProjection))
        call(extended, *source, point, *foreignProjection, relation, {},
             prefix + "/foreign-projection", int(S::Invalid));
      if (relations.size() > 1)
        call(extended, *source, point, *destination, relations.back(), {}, prefix + "/sibling-use",
             int(S::Invalid));
      call(extended, *source, point, *destination, relation, {0, 0}, prefix + "/zero-work",
           mode == "capture" ? int(S::Invalid) : int(S::ResourceLimit));
      if (success) {
        call(extended, *source, point, *destination, relation, {200000, 0}, prefix + "/state0",
             int(S::Proved));
        call(extended, *source, point, *destination, relation, {200000, 1}, prefix + "/state1",
             int(S::Proved));
        uint64_t low = 0, high = 200000;
        // Find adjacent exact limits, checking that every non-success is ResourceLimit.
        while (low + 1 < high) {
          uint64_t middle = low + (high - low) / 2;
          S status = call(extended, *source, point, *destination, relation, {middle, 1},
                          prefix + "/budget-search", -2, false);
          check(status == S::Proved || status == S::ResourceLimit,
                prefix + "/budget-monotone-status");
          if (status == S::Proved)
            high = middle;
          else
            low = middle;
        }
        llvm::outs() << prefix << " minimum-successful-work=" << high << '\n';
        call(extended, *source, point, *destination, relation, {high - 1, 1},
             prefix + "/minimum-minus1", int(S::ResourceLimit));
        call(extended, *source, point, *destination, relation, {high, 1}, prefix + "/minimum",
             int(S::Proved));
      }
    }
  }
  if (mode == "anchor") {
    P scalar;
    check(nodal::resolveStaticShapePoint(*destination, relation, scalar) == S::Proved,
          "scalar-point");
    for (bool extended : {false, true})
      call(extended, *destination, scalar, *source, relation, {},
           extended ? "new/reverse-no-guess" : "old/reverse-no-guess", int(S::Unsupported));
  }
  check(before == print(module->getOperation()), "all-queries-preserve-ir");
  llvm::outs() << "SUMMARY checks=" << checks << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}

} // namespace repair_mapper

int main(int argc, char **argv) {
  if (argc == 3 && llvm::StringRef(argv[2]).starts_with("repair-mapper-")) {
    std::string suffix = llvm::StringRef(argv[2]).drop_front(14).str();
    char *mapperArgs[] = {argv[0], argv[1], suffix.data()};
    return repair_mapper::run(argc, mapperArgs);
  }
  if (argc != 3) return 2;
  mlir::MLIRContext context;
  context.loadDialect<nodal::NodalDialect, circt::hw::HWDialect>();
  auto parsed=mlir::parseSourceFile<mlir::ModuleOp>(argv[1], &context);
  if (!parsed || mlir::failed(mlir::verify(*parsed))) return 3;
  const llvm::StringRef mode(argv[2]);
  if (mode == "repair-shape-controls") return repairShapeQuery(*parsed);
  if (mode.starts_with("parent-real-query-"))
    return parentRealQuery(*parsed, mode.drop_front(18));
  if (mode.starts_with("constant-evaluation-"))
    return constantEvaluation(*parsed, mode.drop_front(20));
  if (mode.starts_with("symbolic-family-query-"))
    return symbolicFamilyQuery(*parsed, mode.drop_front(22));
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
