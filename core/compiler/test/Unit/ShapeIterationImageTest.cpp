#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/Support/raw_ostream.h"

#include <string>
using namespace mlir;
namespace {
using Status = nodal::ShapeRegionProofStatus;
std::string shaped(const std::string &dimensions) {
  return "!nodal.shaped<\"" + dimensions + "\", !nodal.uint<8>>";
}
std::string literal(const std::string &name, int64_t value) {
  return "%" + name + " = \"nodal.const_literal\"() <{value = " + std::to_string(value) +
         " : i64, spelling = \"" + std::to_string(value) + "\", metadata = {test_name = \"" + name +
         "\"}}> : () -> i64\n";
}
std::string index(const std::string &name, const std::string &root, const std::string &selector,
                  const std::string &type) {
  return "%" + name + " = \"nodal.shape_index\"(%" + root + ", %" + selector +
         ") <{metadata = {test_name = \"" + name + "\"}}> : (" + type +
         ", i64) -> !nodal.uint<8>\n";
}
std::string affine(const std::string &name, const std::string &offset) {
  return "%" + name + " = \"nodal.iteration_index\"(%iv, %" + offset +
         ") <{operator_name = \"add\", metadata = {}}> : (i64, i64) -> i64\n";
}
std::string parameter(const std::string &name) {
  return "\"nodal.parameter\"() <{classification = \"structural\", default_value = 2 : i64, "
         "metadata = {semantic_path = \"Fixture." +
         name + "\"}, parameter_kind = \"integer\", sym_name = \"" + name +
         "\", type = i64, variability = \"symbolic\"}> : () -> ()\n" + literal(name + "low", 0) +
         literal(name + "high", 1000000) + "\"nodal.parameter_constraint\"(%" + name + "low, %" +
         name +
         "high) <{constraint_kind = \"range\", "
         "lower_inclusive = true, upper_inclusive = true, metadata = {}, parameter = @" +
         name +
         "}> : (i64, i64) -> ()\n\"nodal.parameter_envelope\"() <{effects = [\"shape\", "
         "\"topology\"], "
         "metadata = {}, parameter = @" +
         name + ", policy = \"static_generate\"}> : () -> ()\n%" + name +
         " = \"nodal.const_parameter_ref\"() <{parameter = @" + name +
         ", metadata = {}}> : () -> i64\n";
}
std::string model(int64_t count, int64_t step, const std::string &dimension, bool symbolic = false,
                  bool views = false) {
  std::string type = shaped(dimension), rootType = views ? shaped("2,4") : type;
  std::string prefix = literal("lo", 0) + literal("step", step) + literal("one", 1);
  if (symbolic)
    prefix += parameter("N") +
              "%extent = \"nodal.const_expr\"(%N, %one) <{operator_name = \"add\", "
              "metadata = {semantic_path = \"Fixture.extent\"}}> : (i64, i64) -> i64\n";
  else
    prefix += literal("hi", count);
  prefix += "%cells = \"nodal.digital_storage\"() <{name = \"cells\", kind = \"wire\", metadata = "
            "{test_name = \"root\"}}> : () -> " +
            rootType + "\n";
  if (views)
    prefix += "%flat = \"nodal.shape_view\"(%cells) <{dimensions = \"8\", origin = "
              "\"Fixture.cells\", materialization = \"view\", observability = \"source_mapped\", "
              "metadata = {storage = \"structural\"}}> : (" +
              rootType + ") -> " + type + "\n";
  prefix += index("seed", views ? "flat" : "cells", "lo", type);
  prefix += "\"nodal.hardware_loop\"(%lo, %" + std::string(symbolic ? "N" : "hi") + ", %step, %" +
            std::string(views ? "flat" : "cells") +
            ") <{induction = \"i\", contract_version = \"bounded-digital-v1\", effect_policy = "
            "\"ordered\", region_id = \"Fixture.loop\", induction_path = \"Fixture.loop.i\", "
            "metadata = {semantic_path = \"Fixture.loop\"}}> ({^bb0(%iv: i64, %target: " +
            type + "):\n" + literal("localone", 1) + affine("next", "localone") +
            index("read", "target", "iv", type) + index("write", "target", "next", type) +
            "\"nodal.hardware_loop_end\"() : () -> ()\n}) : (i64, i64, i64, " + type + ") -> ()\n";
  return "module { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({" + prefix +
         literal("marker", 99) + "}) : () -> () }";
}
Value value(mlir::ModuleOp module, StringRef name) {
  Value found;
  module.walk([&](Operation *operation) {
    auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata");
    auto label = metadata ? metadata.getAs<StringAttr>("test_name") : StringAttr();
    if (label && label.getValue() == name && operation->getNumResults() == 1)
      found = operation->getResult(0);
  });
  return found;
}
} // namespace
int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  unsigned cases = 0, failures = 0;
  auto check = [&](bool result, const std::string &name) {
    ++cases;
    failures += !result;
    (result ? llvm::outs() : llvm::errs())
        << "SHAPE_ITERATION_" << (result ? "PASS " : "FAIL ") << name << '\n';
  };
  auto exercise = [&](int64_t count, int64_t step, const std::string &extent, bool symbolic = false,
                      bool views = false, bool rows = false, bool nested = false) {
    auto source = model(count, step, extent, symbolic, views);
    if (rows) {
      std::string from = "-> !nodal.uint<8>", to = "-> " + shaped("4");
      size_t position = 0;
      while ((position = source.find(from, position)) != std::string::npos) {
        source.replace(position, from.size(), to);
        position += to.size();
      }
    }
    if (nested) {
      const auto from = shaped(extent), to = "!nodal.shaped<\"" +
                                             extent.substr(0, extent.find(',')) + "\", " +
                                             shaped("4") + ">";
      size_t position = 0;
      while ((position = source.find(from, position)) != std::string::npos) {
        source.replace(position, from.size(), to);
        position += to.size();
      }
    }
    auto module = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    std::string name = (symbolic ? "symbolic" : std::to_string(count)) + "-s" +
                       std::to_string(step) + (views ? "-view" : "") + (rows ? "-rows" : "") +
                       (nested ? "-nested" : "");
    if (!module) {
      check(false, name + "-parse");
      return;
    }
    auto use = value(*module, "marker").getDefiningOp();
    auto project = [&](StringRef label) {
      auto v = value(*module, label);
      return nodal::resolveStaticShapeProjection(v, v.getDefiningOp());
    };
    auto read = project("read"), write = project("write"), seed = project("seed"),
         root = project("root");
    check(succeeded(read) && succeeded(write) && succeeded(seed) && succeeded(root),
          name + "-capture-root");
    if (failed(read) || failed(write) || failed(seed) || failed(root))
      return;
    check(read->root == root->root && write->root == root->root, name + "-actual-root");
    nodal::StaticShapeRegion image;
    auto resolved = nodal::resolveStaticShapeIterationImage(*write, use, image);
    check(resolved == Status::Proved && image.iteration && image.iteration->stride == step,
          name + "-family");
    if (step == 1) {
      auto predecessor = nodal::compareStaticShapeIterationPositions(*read, *write, use);
      check(succeeded(predecessor) && *predecessor == -1, name + "-predecessor");
      check(nodal::verifyStaticShapeIterationPredecessorSeed(*read, *write, *seed, use) ==
                Status::Proved,
            name + "-seed");
      check(nodal::verifyStaticShapeProjectionCoverage(*root, {*seed, *write}, use) ==
                Status::Proved,
            name + "-seed-plus-family");
      check(nodal::verifyStaticShapeProjectionCoverage(*read, {*seed, *write}, use) ==
                Status::Proved,
            name + "-predecessor-read-covered");
      check(nodal::verifyStaticShapeIterationPredecessorSeed(*write, *read, *seed, use) !=
                Status::Proved,
            name + "-forward-rejected");
    } else {
      auto overlap = nodal::compareStaticShapeProjections(*read, *write, use);
      check(overlap.status == Status::Proved &&
                overlap.overlap == nodal::ShapeRegionOverlap::Disjoint,
            name + "-residue-disjoint");
      check(nodal::verifyStaticShapeProjectionCoverage(*root, {*read, *write}, use) ==
                Status::Proved,
            name + "-residues-cover");
      check(nodal::verifyStaticShapeProjectionCoverage(*root, {*read}, use) != Status::Proved,
            name + "-holes-rejected");
      check(nodal::verifyStaticShapeProjectionCoverage(*read, {*read}, use) == Status::Proved,
            name + "-exact-strided-read");
      check(nodal::verifyStaticShapeProjectionCoverage(*read, {*write}, use) != Status::Proved,
            name + "-wrong-residue-read");
    }
    auto self = nodal::compareStaticShapeIterationPositions(*read, *read, use);
    check(succeeded(self) && *self == 0, name + "-same-iteration");
    check(nodal::resolveStaticShapeIterationImage(*write, use, image, {1, 1}) ==
              Status::ResourceLimit,
          name + "-bounded-work");
  };
  for (int n : {0, 1, 2, 8})
    exercise(n, 1, std::to_string(n + 1));
  exercise(0, 1, "Fixture.extent", true);
  exercise(1, 1, "2,4", false, false, true);
  exercise(1, 1, "2,4", false, false, true, true);
  exercise(0, 1, "Fixture.extent,4", true, false, true, true);
  exercise(7, 1, "8", false, true);
  exercise(8, 2, "8");
  exercise(8, 2, "8", false, true);
  {
    auto source = model(2, 1, "3");
    auto first = source.find("\"nodal.hardware_loop\"");
    auto marker = source.find("%marker");
    auto duplicate = source.substr(first, marker - first);
    auto replaceAll = [&](const std::string &from, const std::string &to) {
      size_t position = 0;
      while ((position = duplicate.find(from, position)) != std::string::npos) {
        duplicate.replace(position, from.size(), to);
        position += to.size();
      }
    };
    replaceAll("Fixture.loop", "Fixture.second");
    replaceAll("test_name = \"read\"", "test_name = \"read2\"");
    replaceAll("test_name = \"write\"", "test_name = \"write2\"");
    source.insert(marker, duplicate);
    auto module = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    check(static_cast<bool>(module), "cross-loop-parse");
    if (module) {
      auto a = value(*module, "read"), b = value(*module, "read2");
      auto left = nodal::resolveStaticShapeProjection(a, a.getDefiningOp());
      auto right = nodal::resolveStaticShapeProjection(b, b.getDefiningOp());
      auto use = value(*module, "marker").getDefiningOp();
      check(succeeded(left) && succeeded(right), "cross-loop-projections");
      if (succeeded(left) && succeeded(right)) {
        auto relation = nodal::compareStaticShapeProjections(*left, *right, use);
        check(relation.status == Status::Proved &&
                  relation.overlap == nodal::ShapeRegionOverlap::Overlapping,
              "cross-loop-physical-overlap");
        check(failed(nodal::compareStaticShapeIterationPositions(*left, *right, use)),
              "cross-loop-no-local-rank");
      }
    }
  }
  {
    auto source = model(8, 2, "10");
    auto offset = source.find("%localone =");
    auto end = source.find('\n', offset);
    source.replace(offset, end - offset + 1, literal("localone", 2));
    auto module = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    check(static_cast<bool>(module), "shifted-family-parse");
    if (module) {
      auto a = value(*module, "read"), b = value(*module, "write"), c = value(*module, "seed");
      auto read = nodal::resolveStaticShapeProjection(a, a.getDefiningOp());
      auto write = nodal::resolveStaticShapeProjection(b, b.getDefiningOp());
      auto seed = nodal::resolveStaticShapeProjection(c, c.getDefiningOp());
      check(succeeded(read) && succeeded(write) && succeeded(seed), "shifted-family-projections");
      if (succeeded(read) && succeeded(write) && succeeded(seed)) {
        auto use = value(*module, "marker").getDefiningOp();
        auto relation = nodal::compareStaticShapeProjections(*read, *write, use);
        check(relation.status == Status::Proved &&
                  relation.overlap == nodal::ShapeRegionOverlap::Overlapping,
              "shifted-family-cross-iteration-overlap");
        relation = nodal::compareStaticShapeProjections(*seed, *read, use);
        check(relation.status == Status::Proved &&
                  relation.overlap == nodal::ShapeRegionOverlap::Overlapping,
              "ordinary-vs-family-overlap");
      }
    }
  }
  {
    const auto type = shaped("3,2");
    auto source = model(2, 1, "3,2");
    auto at = source.find("%cells =");
    source.insert(at, "%column = \"nodal.const_expr\"(%one, %lo) <{operator_name = \"add\", "
                      "metadata = {semantic_path = \"Fixture.column\"}}> : (i64, i64) -> i64\n");
    auto replaceAll = [&](const std::string &from, const std::string &to) {
      size_t position = 0;
      while ((position = source.find(from, position)) != std::string::npos) {
        source.replace(position, from.size(), to);
        position += to.size();
      }
    };
    replaceAll("(%cells, %lo)", "(%cells, %lo, %column)");
    replaceAll("(%target, %iv)", "(%target, %iv, %columnCapture)");
    replaceAll("(%target, %next)", "(%target, %next, %columnCapture)");
    replaceAll("(" + type + ", i64)", "(" + type + ", i64, i64)");
    replaceAll("%step, %cells)", "%step, %cells, %column)");
    replaceAll("%target: " + type + ")", "%target: " + type + ", %columnCapture: i64)");
    replaceAll("(i64, i64, i64, " + type + ")", "(i64, i64, i64, " + type + ", i64)");
    auto module = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    check(static_cast<bool>(module), "static-expression-capture-parse");
    if (module) {
      auto a = value(*module, "read"), b = value(*module, "write"), c = value(*module, "seed");
      auto read = nodal::resolveStaticShapeProjection(a, a.getDefiningOp());
      auto write = nodal::resolveStaticShapeProjection(b, b.getDefiningOp());
      auto seed = nodal::resolveStaticShapeProjection(c, c.getDefiningOp());
      check(succeeded(read) && succeeded(write) && succeeded(seed),
            "static-expression-capture-projections");
      if (succeeded(read) && succeeded(write) && succeeded(seed))
        check(nodal::verifyStaticShapeIterationPredecessorSeed(
                  *read, *write, *seed, value(*module, "marker").getDefiningOp()) == Status::Proved,
              "static-expression-capture-preserves-seed-map");
    }
  }
  {
    const auto rowType = shaped("4"), rootType = "!nodal.shaped<\"2\", " + rowType + ">";
    auto row = [&](const std::string &name, const std::string &selector) {
      return "%" + name + " = \"nodal.shape_index\"(%cells, %" + selector +
             ") <{metadata = {test_name = \"" + name + "\"}}> : (" + rootType + ", i64) -> " +
             rowType + "\n";
    };
    auto source = "module { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({" +
                  literal("zero", 0) + literal("one", 1) + literal("three", 3) +
                  "%cells = \"nodal.digital_storage\"() <{name = \"cells\", kind = \"wire\", "
                  "metadata = {test_name = \"root\"}}> : () -> " +
                  rootType + "\n" + row("first", "zero") + row("second", "one") +
                  index("leaf", "second", "three", rowType) + literal("marker", 99) +
                  "}) : () -> () }";
    auto module = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    check(static_cast<bool>(module), "nested-scalar-parse");
    if (module) {
      auto use = value(*module, "marker").getDefiningOp();
      auto project = [&](StringRef label) {
        return nodal::resolveStaticShapeProjection(value(*module, label), use);
      };
      auto root = project("root"), first = project("first"), second = project("second"),
           leaf = project("leaf");
      check(succeeded(root) && succeeded(first) && succeeded(second) && succeeded(leaf),
            "nested-scalar-projections");
      if (succeeded(root) && succeeded(first) && succeeded(second) && succeeded(leaf)) {
        check(leaf->exactFixedCardinality == 8 && leaf->literalRootFlatOffset == 7 &&
                  leaf->rootCoordinateBounds.size() == 2 &&
                  leaf->rootCoordinateBounds[0].lower == 1 &&
                  leaf->rootCoordinateBounds[1].lower == 3,
              "nested-scalar-exact-physical-map");
        check(nodal::verifyStaticShapeProjectionCoverage(*root, {*first, *second}, use) ==
                  Status::Proved,
              "nested-ordinary-row-partition");
        auto relation = nodal::compareStaticShapeProjections(*first, *second, use);
        check(relation.status == Status::Proved &&
                  relation.overlap == nodal::ShapeRegionOverlap::Disjoint,
              "nested-ordinary-row-disjoint");
        nodal::StaticShapePoint point;
        check(nodal::mapStaticShapeOrdinal(*second, nodal::makeLiteralIntegerExpression(3), use,
                                           point) == Status::Proved &&
                  point.coordinates.size() == 2 &&
                  nodal::equivalentInstantiatedIntegerExpressions(
                      point.coordinates[0], nodal::makeLiteralIntegerExpression(1)) &&
                  nodal::equivalentInstantiatedIntegerExpressions(
                      point.coordinates[1], nodal::makeLiteralIntegerExpression(3)),
              "nested-row-ordinal-map");
      }
      auto selectedSecond = value(*module, "second");
      selectedSecond.setType(
          nodal::ShapedType::get(&context, "3", nodal::UIntType::get(&context, 8)));
      check(failed(nodal::resolveStaticShapeProjection(selectedSecond, use)),
            "nested-exact-result-type-still-required");
    }
    const std::string large = "!nodal.shaped<\"9223372036854775807\", " + shaped("2") + ">";
    source = "module { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({"
             "%cells = \"nodal.digital_storage\"() <{name = \"cells\", kind = \"wire\", "
             "metadata = {test_name = \"root\"}}> : () -> " +
             large + "\n" + literal("marker", 99) + "}) : () -> () }";
    module = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    check(static_cast<bool>(module), "nested-overflow-parse");
    if (module)
      check(failed(nodal::resolveStaticShapeProjection(value(*module, "root"),
                                                       value(*module, "marker").getDefiningOp())),
            "nested-total-cardinality-overflow-rejected");
  }
  llvm::outs() << "SHAPE_ITERATION_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}
