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
std::string generatedMatrix(int64_t rows, int64_t columns, int64_t step = 1,
                            bool nestedType = false, bool symbolic = false, bool repeated = false,
                            bool missing = false, bool swapped = false) {
  std::string r = symbolic ? "R" : std::to_string(rows);
  std::string c = symbolic ? "C" : std::to_string(columns);
  const std::string rowType = shaped(c);
  const std::string rootType =
      nestedType ? "!nodal.shaped<\"" + r + "\", " + rowType + ">" : shaped(r + "," + c);
  std::string body = literal("zero", 0) + literal("one", 1) + literal("step", step);
  if (symbolic) {
    for (auto name : {"R", "C"}) {
      std::string declaration = parameter(name);
      auto at = declaration.find(literal(std::string(name) + "low", 0));
      declaration.replace(at, literal(std::string(name) + "low", 0).size(),
                          literal(std::string(name) + "low", 2));
      body += declaration;
    }
  } else {
    body += literal("R", rows) + literal("C", columns);
  }
  body += "%cells = \"nodal.digital_storage\"() <{name = \"cells\", kind = \"wire\", "
          "metadata = {test_name = \"root\"}}> : () -> " +
          rootType + "\n";
  auto begin = [&](std::string name, std::string upper, std::string stepName) {
    int64_t maximum = symbolic ? 1000000 : upper == "R" ? rows : columns;
    int64_t stride = stepName == "step" ? step : 1;
    maximum = (maximum + stride - 1) / stride;
    return "\"nodal.generate\"(%zero, %" + upper + ", %" + stepName + ") <{induction = \"" + name +
           "\", region_id = \"Fixture." + name + "\", induction_path = \"Fixture." + name +
           ".index\", metadata = {maximum_trip_count = " + std::to_string(maximum) +
           " : i64}}> ({^bb0(%" + name + ": i64):\n";
  };
  body += begin("i", swapped ? "C" : "R", "one");
  body += "%row = \"nodal.shape_index\"(%cells, %" + std::string(missing ? "zero" : "i") +
          ") <{metadata = {test_name = \"row\"}}> : (" + rootType + ", i64) -> " + rowType + "\n";
  body += begin("j", swapped ? "R" : "C", "step");
  body += index("leaf", "row", repeated ? "i" : "j", rowType);
  body += index("leaf2", "row", repeated ? "i" : "j", rowType);
  body += "}) : (i64, i64, i64) -> ()\n}) : (i64, i64, i64) -> ()\n";
  return "module { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({" + body +
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
    struct Case {
      const char *name;
      int64_t upper, stride, extent, parameterLower, parameterUpper, tripsLower, tripsUpper;
    };
    for (const auto &test :
         {Case{"singleton-Vec1", 1, 2, 1, -1, -1, 1, 1},
          Case{"singleton-wide-bound-Vec2", 2, 3, 2, -1, -1, 1, 1},
          Case{"singleton-symbolic-count", 0, 3, 2, 1, 2, 1, 1},
          Case{"singleton-empty-control", 0, 2, 1, -1, -1, 0, 0},
          Case{"singleton-maybe-empty-control", 0, 3, 1, 0, 1, 0, 1},
          Case{"singleton-default-one-variable-count", 0, 3, 4, 1, 4, 1, 2},
          Case{"singleton-multiple-trip-control", 4, 2, 4, -1, -1, 2, 2}}) {
      const bool symbolic = test.parameterLower >= 0;
      const bool singleton = test.tripsLower == 1 && test.tripsUpper == 1;
      const std::string name = test.name, type = shaped(std::to_string(test.extent));
      auto source = model(test.upper, test.stride, std::to_string(test.extent), symbolic);
      auto replace = [&](const std::string &from, const std::string &to) {
        source.replace(source.find(from), from.size(), to);
      };
      // Keep every selector valid even for Vec1: the second projection is an
      // actual duplicate occurrence, not the model's usual predecessor write.
      replace(index("write", "target", "next", type), index("write", "target", "iv", type));
      if (symbolic) {
        replace(literal("Nlow", 0), literal("Nlow", test.parameterLower));
        replace(literal("Nhigh", 1000000), literal("Nhigh", test.parameterUpper));
        replace("default_value = 2 : i64", "default_value = 1 : i64");
      }
      if (singleton && test.extent == 2)
        source.insert(source.find("\"nodal.hardware_loop\""),
                      index("lane1", "cells", "one", type));
      if (!symbolic && test.tripsLower == 2)
        source.insert(source.find("\"nodal.hardware_loop\""),
                      literal("two", 2) + literal("three", 3) +
                          index("tail1", "cells", "one", type) +
                          index("tail2", "cells", "two", type) +
                          index("tail3", "cells", "three", type));
      auto module = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
      check(bool(module), name + "-parse");
      if (!module)
        continue;
      auto use = value(*module, "marker").getDefiningOp();
      auto project = [&](StringRef label) {
        auto selected = value(*module, label);
        return nodal::resolveStaticShapeProjection(selected, selected.getDefiningOp());
      };
      auto root = project("root"), read = project("read"), duplicate = project("write"),
           seed = project("seed");
      check(succeeded(root) && succeeded(read) && succeeded(duplicate) && succeeded(seed),
            name + "-projections");
      if (failed(root) || failed(read) || failed(duplicate) || failed(seed))
        continue;
      nodal::StaticShapeRegion image;
      auto status = nodal::resolveStaticShapeIterationImage(*read, use, image);
      check(status == Status::Proved && image.iterationCount() == 1 && image.projection &&
                image.iteration->stride == test.stride &&
                image.iteration->tripCount.bounds.lower == test.tripsLower &&
                image.iteration->tripCount.bounds.upper == test.tripsUpper,
            name + "-actual-count-and-stride");
      if (status != Status::Proved || image.iterationCount() != 1)
        continue;
      if (symbolic && singleton)
        check(image.iteration->tripCount.root &&
                  image.iteration->tripCount.root->kind !=
                      nodal::InstantiatedIntegerNode::Kind::Literal,
              name + "-canonical-symbolic-count-not-default");
      if (test.tripsUpper == 0)
        check(image.kind == nodal::StaticShapeRegion::Kind::Empty, name + "-actual-empty-image");
      const bool whole = singleton && test.extent == 1;
      check((nodal::verifyStaticShapeProjectionCoverage(*root, {*read}, use) == Status::Proved) ==
                whole,
            name + "-root-coverage");
      check((nodal::verifyStaticShapeRegionPartition(root->root, {image}, use) == Status::Proved) ==
                whole,
            name + "-root-partition");
      check(nodal::verifyStaticShapeProjectionCoverage(*read, {*read}, use) == Status::Proved,
            name + "-exact-family-read-preserved");
      if (singleton) {
        check(nodal::verifyStaticShapeProjectionCoverage(*read, {*seed}, use) == Status::Proved &&
                  nodal::verifyStaticShapeProjectionCoverage(*seed, {*read}, use) == Status::Proved,
              name + "-ordinary-lane-and-singleton-cover-each-other");
        check(nodal::verifyStaticShapeProjectionCoverage(*read, {*read, *duplicate}, use) !=
                  Status::Proved,
              name + "-duplicate-writer-rejected");
        check(nodal::verifyStaticShapeRegionPartition(root->root, {image, image}, use) !=
                  Status::Proved,
              name + "-duplicate-partition-rejected");
        auto overlap = nodal::compareStaticShapeProjections(*read, *duplicate, use);
        auto order = nodal::compareStaticShapeIterationPositions(*read, *duplicate, use);
        check(overlap.status == Status::Proved &&
                  overlap.overlap == nodal::ShapeRegionOverlap::Overlapping &&
                  nodal::proveStaticShapeSameOccurrence(*read, *duplicate, use) == Status::Proved &&
                  succeeded(order) && *order == 0,
              name + "-occurrence-overlap-and-order-preserved");
        if (test.extent == 2) {
          auto lane1 = project("lane1");
          nodal::StaticShapeRegion other;
          const bool resolved = succeeded(lane1) &&
                                nodal::resolveStaticShapeRegion(*lane1, use, other) == Status::Proved;
          check(resolved, name + "-ordinary-complement-resolved");
          if (resolved) {
            check(nodal::verifyStaticShapeProjectionCoverage(*root, {*read, *lane1}, use) ==
                      Status::Proved &&
                      nodal::verifyStaticShapeProjectionCoverage(*root, {*lane1, *read}, use) ==
                          Status::Proved,
                  name + "-complement-completes-root-in-either-order");
            check(nodal::verifyStaticShapeRegionPartition(root->root, {image, other}, use) ==
                      Status::Proved &&
                      nodal::verifyStaticShapeRegionPartition(root->root, {other, image}, use) ==
                          Status::Proved,
                  name + "-complement-completes-partition-in-either-order");
            check(nodal::verifyStaticShapeRegionPartition(root->root, {image, other, image}, use) !=
                      Status::Proved,
                  name + "-complete-but-duplicate-partition-rejected");
          }
        }
        check(nodal::verifyStaticShapeProjectionCoverage(*root, {*read}, use, {1, 1}) ==
                  Status::ResourceLimit &&
                  nodal::verifyStaticShapeRegionPartition(root->root, {image}, use, {1, 1}) ==
                      Status::ResourceLimit,
              name + "-coverage-and-partition-budget");
      }
      if (!symbolic && test.tripsLower == 2) {
        auto forged = image;
        forged.iteration->tripCount = nodal::makeLiteralIntegerExpression(1);
        llvm::SmallVector<nodal::StaticShapeRegion, 4> regions{forged};
        bool resolved = true;
        for (auto label : {"tail1", "tail2", "tail3"}) {
          auto projection = project(label);
          nodal::StaticShapeRegion region;
          resolved &= succeeded(projection) &&
                      nodal::resolveStaticShapeRegion(*projection, use, region) == Status::Proved;
          regions.push_back(std::move(region));
        }
        check(resolved, name + "-forged-count-complement-resolved");
        if (resolved)
          // Trusting the forged count would turn lanes {0,2} into {0}, making
          // the ordinary {1,2,3} complement appear to be an exact partition.
          check(nodal::verifyStaticShapeRegionPartition(root->root, regions, use) != Status::Proved,
                name + "-forged-count-rederived-from-actual-SSA");
      }
      check(image.projection && image.iteration && image.iteration->stride == test.stride &&
                image.iteration->tripCount.bounds.lower == test.tripsLower &&
                image.iteration->tripCount.bounds.upper == test.tripsUpper &&
                image.iteration->domain.upperExclusive.bounds.upper ==
                    (symbolic ? test.parameterUpper : test.upper),
            name + "-original-map-retained-after-coverage");
    }
  }
  for (bool nested : {false, true}) {
    auto source = generatedMatrix(1, 1, 2);
    if (!nested) {
      // Reuse the checked Generate fixture, selecting its only row outside
      // the inner family instead of retaining a second occurrence map.
      auto begin = source.find("\"nodal.generate\"");
      source.erase(begin, source.find("%row =", begin) - begin);
      const std::string from = "(%cells, %i)", to = "(%cells, %zero)";
      source.replace(source.find(from), from.size(), to);
      const std::string end = "}) : (i64, i64, i64) -> ()\n";
      source.erase(source.find(end), end.size());
    }
    const std::string name = nested ? "singleton-nested-Generate" : "singleton-direct-Generate";
    auto module = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    check(bool(module), name + "-parse");
    if (!module)
      continue;
    auto use = value(*module, "marker").getDefiningOp();
    auto leaf = value(*module, "leaf"), other = value(*module, "leaf2");
    auto read = nodal::resolveStaticShapeProjection(leaf, leaf.getDefiningOp());
    auto duplicate = nodal::resolveStaticShapeProjection(other, other.getDefiningOp());
    auto root = nodal::resolveStaticShapeProjection(value(*module, "root"), use);
    check(succeeded(read) && succeeded(duplicate) && succeeded(root), name + "-projections");
    if (failed(read) || failed(duplicate) || failed(root))
      continue;
    nodal::StaticShapeRegion image;
    auto status = nodal::resolveStaticShapeIterationImage(*read, use, image);
    check(status == Status::Proved && image.iterationCount() == (nested ? 2u : 1u) &&
              isa<nodal::GenerateOp>(image.iteration->loop) && image.iteration->stride == 2,
          name + "-actual-occurrence-maps");
    if (status != Status::Proved)
      continue;
    check((nodal::verifyStaticShapeProjectionCoverage(*root, {*read}, use) == Status::Proved) ==
              !nested &&
              (nodal::verifyStaticShapeRegionPartition(root->root, {image}, use) == Status::Proved) ==
                  !nested,
          name + "-only-single-map-normalized");
    check(nodal::verifyStaticShapeProjectionCoverage(*read, {*duplicate}, use) == Status::Proved &&
              failed(nodal::compareStaticShapeIterationPositions(*read, *duplicate, use)),
          name + "-exact-family-read-without-loop-ordering");
  }
  {
    auto source = model(1, 2, "8", false, true);
    auto begin = source.find("%cells ="), end = source.find('\n', begin);
    source.replace(begin, end - begin + 1,
                   "%parent = \"nodal.digital_storage\"() <{name = \"parent\", kind = \"wire\", "
                   "metadata = {test_name = \"root\"}}> : () -> " + shaped("2,2,4") + "\n" +
                       "%cells = \"nodal.shape_index\"(%parent, %one) <{metadata = {}}> : (" +
                       shaped("2,2,4") + ", i64) -> " + shaped("2,4") + "\n");
    source.insert(source.find("\"nodal.hardware_loop\""),
                  "%physicalZero = \"nodal.shape_index\"(%parent, %lo, %lo, %lo) "
                  "<{metadata = {test_name = \"physicalZero\"}}> : (" +
                      shaped("2,2,4") + ", i64, i64, i64) -> !nodal.uint<8>\n");
    auto module = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    check(bool(module), "singleton-shifted-flat-image-parse");
    if (module) {
      auto use = value(*module, "marker").getDefiningOp();
      auto project = [&](StringRef label) {
        auto selected = value(*module, label);
        return nodal::resolveStaticShapeProjection(selected, selected.getDefiningOp());
      };
      auto read = project("read"), seed = project("seed"), zero = project("physicalZero");
      check(succeeded(read) && succeeded(seed) && succeeded(zero),
            "singleton-shifted-flat-image-projections");
      if (succeeded(read) && succeeded(seed) && succeeded(zero)) {
        nodal::StaticShapeRegion image;
        auto status = nodal::resolveStaticShapeIterationImage(*read, use, image);
        check(status == Status::Proved && image.kind == nodal::StaticShapeRegion::Kind::Interval &&
                  image.iteration && !image.iteration->varyingAxis &&
                  image.iteration->stride == 2 && image.interval.lower.bounds.lower == 8 &&
                  image.interval.upperExclusive.bounds.upper == 9 &&
                  image.iteration->offset.bounds.lower == 0,
              "singleton-shifted-flat-image-exact-physical-map");
        if (status == Status::Proved) {
          check(nodal::verifyStaticShapeProjectionCoverage(*read, {*seed}, use) == Status::Proved &&
                    nodal::verifyStaticShapeProjectionCoverage(*seed, {*read}, use) == Status::Proved,
                "singleton-shifted-flat-image-covers-actual-physical-lane");
          check(nodal::verifyStaticShapeProjectionCoverage(*read, {*zero}, use) != Status::Proved &&
                    nodal::verifyStaticShapeProjectionCoverage(*zero, {*read}, use) != Status::Proved,
                "singleton-shifted-flat-image-does-not-rebase-to-domain-lower");
        }
      }
    }
  }
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
  for (auto [name, source] :
       {std::make_pair("matrix", generatedMatrix(3, 4)),
        std::make_pair("matrix-nested-Vec", generatedMatrix(3, 4, 1, true)),
        std::make_pair("matrix-trillion-lanes", generatedMatrix(1000000, 1000000)),
        std::make_pair("matrix-independent-parameters", generatedMatrix(2, 2, 1, false, true)),
        std::make_pair("matrix-nonunit-stride", generatedMatrix(4, 4, 2))}) {
    auto module = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    check(bool(module), std::string(name) + "-parse");
    if (!module)
      continue;
    auto leaf = value(*module, "leaf"), leaf2 = value(*module, "leaf2");
    auto use = leaf2.getDefiningOp();
    auto selected = nodal::resolveStaticShapeProjection(leaf, use);
    auto other = nodal::resolveStaticShapeProjection(leaf2, use);
    auto root = nodal::resolveStaticShapeProjection(value(*module, "root"), use);
    check(succeeded(selected) && succeeded(other) && succeeded(root),
          std::string(name) + "-projection");
    if (failed(selected) || failed(other) || failed(root))
      continue;
    nodal::StaticShapeRegion image;
    check(nodal::resolveStaticShapeIterationImage(*selected, use, image) == Status::Proved &&
              image.iterationCount() == 2 && image.iterationAt(0).varyingAxis == 0 &&
              image.iterationAt(1).varyingAxis == 1 &&
              image.iterationAt(0).loop->isProperAncestor(image.iterationAt(1).loop),
          std::string(name) + "-ordered-Cartesian-image");
    check(nodal::proveStaticShapeSameOccurrence(*selected, *other, use) == Status::Proved,
          std::string(name) + "-exact-occurrence-vector");
    const bool strided = std::string(name) == "matrix-nonunit-stride";
    auto marker = value(*module, "marker").getDefiningOp();
    check((nodal::verifyStaticShapeProjectionCoverage(*root, {*selected}, marker) ==
           Status::Proved) == !strided,
          std::string(name) + "-complete-or-strided-holes");
    check(nodal::verifyStaticShapeProjectionCoverage(*selected, {*other}, marker) == Status::Proved,
          std::string(name) + "-exact-family-read");
    auto overlap = nodal::compareStaticShapeProjections(*selected, *other, marker);
    check(overlap.status == Status::Proved &&
              overlap.overlap == nodal::ShapeRegionOverlap::Overlapping,
          std::string(name) + "-duplicate-writer-overlap");
    check(nodal::resolveStaticShapeIterationImage(*selected, use, image, {200000, 1}) ==
              Status::ResourceLimit,
          std::string(name) + "-axis-state-budget");
  }
  for (auto [name, source] :
       {std::make_pair("repeated-outer-axis", generatedMatrix(4, 4, 1, false, false, true)),
        std::make_pair("missing-outer-axis", generatedMatrix(4, 4, 1, false, false, false, true)),
        std::make_pair("swapped-same-envelope-parameters",
                       generatedMatrix(2, 2, 1, false, true, false, false, true))}) {
    ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
    auto module = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    bool rejected = false;
    if (module) {
      auto leaf = value(*module, "leaf");
      auto selected = nodal::resolveStaticShapeProjection(leaf, leaf.getDefiningOp());
      nodal::StaticShapeRegion image;
      rejected = failed(selected) || nodal::resolveStaticShapeIterationImage(
                                         *selected, leaf.getDefiningOp(), image) != Status::Proved;
    }
    check(bool(module) && rejected, std::string(name) + "-rejected");
  }
  llvm::outs() << "SHAPE_ITERATION_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}
