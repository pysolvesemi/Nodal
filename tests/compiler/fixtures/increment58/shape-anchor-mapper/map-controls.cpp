// Scratch-only assertion probe. Parses fixtures without whole-program verification.
// No mutation of fixture IR; malformed controls alter only copied query facts.
#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/raw_ostream.h"

#include <string>
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
int main(int argc, char **argv) {
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
