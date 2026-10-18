#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/Support/raw_ostream.h"

#include <string>
#include <vector>

namespace {
using Status = nodal::ShapeRegionProofStatus;
const std::string terminal = "!nodal.terminal<\"electrical\">";
std::string shaped(const std::string &dimensions) {
  return "!nodal.shaped<\"" + dimensions + "\", " + terminal + ">";
}
std::string model(const std::string &body) {
  return R"mlir(module {
"nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", dimension = "voltage", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
"nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", dimension = "current", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
"nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
"nodal.module"() <{metadata = {semantic_path = "Fixture"}, sym_name = "Fixture"}> ({
)mlir" + body +
         "}) : () -> ()\n}\n";
}
std::string array(const std::string &dimensions) {
  return "%nodes = \"nodal.node_array\"() <{metadata = {semantic_path = \"Fixture.nodes\", storage "
         "= \"structural\"}, name = \"nodes\", source_path = \"Fixture.nodes\"}> : () -> " +
         shaped(dimensions) + "\n";
}
std::string literal(const std::string &name, int64_t value) {
  return "%" + name + " = \"nodal.const_literal\"() <{metadata = {}, spelling = \"" +
         std::to_string(value) + "\", value = " + std::to_string(value) + " : i64}> : () -> i64\n";
}
std::string metadata(const std::string &name, const std::string &input) {
  return "metadata = {semantic_path = \"Fixture." + name + "\", input_path = \"Fixture." + input +
         "\", storage = \"structural\", access = \"read_only\"}";
}
std::string slice(const std::string &name, const std::string &input, const std::string &dims,
                  unsigned axis, const std::string &lo, const std::string &hi,
                  const std::string &length, const std::string &output) {
  return "%" + name + " = \"nodal.shape_slice\"(%" + input + ", %" + lo + ", %" + hi + ", %" +
         length + ") <{axis = " + std::to_string(axis) + " : i64, dimensions = \"" + output +
         "\", origin = \"Fixture." + input + "\", observability = \"source_mapped\", " +
         metadata(name, input) + "}> : (" + shaped(dims) + ", i64, i64, i64) -> " + shaped(output) +
         "\n";
}
std::string index(const std::string &name, const std::string &input, const std::string &dims,
                  const std::vector<std::string> &indices, const std::string &output = "") {
  std::string values = "%" + input, types = shaped(dims);
  for (const auto &value : indices) {
    values += ", %" + value;
    types += ", i64";
  }
  std::string attributes = metadata(name, input);
  if (output.empty()) {
    std::string path = "Fixture." + input + ".at(";
    for (unsigned i = 0; i != indices.size(); ++i) {
      if (i)
        path += ',';
      const auto &index = indices[i];
      int number = index == "zero"    ? 0
                   : index == "one"   ? 1
                   : index == "two"   ? 2
                   : index == "three" ? 3
                   : index == "four"  ? 4
                   : index == "five"  ? 5
                                      : -1;
      path += number >= 0 ? "literal:" + std::to_string(number) : "expression:Fixture." + index;
    }
    path += ')';
    attributes = "metadata = {semantic_path = \"" + path + "\", test_name = \"" + name +
                 "\", input_path = \"Fixture." + input +
                 "\", storage = \"structural\", selection_kind = \"node_array\", source_uses = "
                 "[\"Fixture.use_" +
                 name + "\"]}";
  }
  return "%" + name + " = \"nodal.shape_index\"(" + values + ") <{" + attributes + "}> : (" +
         types + ") -> " + (output.empty() ? terminal : shaped(output)) + "\n";
}
std::string view(const std::string &name, const std::string &input, const std::string &dims,
                 const std::string &output) {
  return "%" + name + " = \"nodal.shape_view\"(%" + input + ") <{dimensions = \"" + output +
         "\", origin = \"Fixture." + input +
         "\", materialization = \"view\", observability = \"source_mapped\", " +
         metadata(name, input) + "}> : (" + shaped(dims) + ") -> " + shaped(output) + "\n";
}
std::string parameter(const std::string &name, int64_t low, int64_t high) {
  return "\"nodal.parameter\"() <{classification = \"structural\", default_value = " +
         std::to_string(low) + " : i64, metadata = {semantic_path = \"Fixture." + name +
         "\"}, parameter_kind = \"integer\", sym_name = \"" + name +
         "\", type = i64, variability = \"symbolic\"}> : () -> ()\n" + literal(name + "_lo", low) +
         literal(name + "_hi", high) + "\"nodal.parameter_constraint\"(%" + name + "_lo, %" + name +
         "_hi) <{constraint_kind = \"range\", lower_inclusive = true, metadata = {}, parameter = "
         "@" +
         name + ", upper_inclusive = true}> : (i64, i64) -> ()\n" +
         "\"nodal.parameter_envelope\"() <{effects = [\"shape\"], metadata = {}, parameter = @" +
         name + ", policy = \"static_generate\"}> : () -> ()\n%" + name +
         " = \"nodal.const_parameter_ref\"() <{metadata = {}, parameter = @" + name +
         "}> : () -> i64\n";
}
std::string expression(const std::string &name, const std::string &opcode, const std::string &a,
                       const std::string &b) {
  return "%" + name + " = \"nodal.const_expr\"(%" + a + ", %" + b +
         ") <{metadata = {semantic_path = \"Fixture." + name + "\"}, operator_name = \"" + opcode +
         "\"}> : (i64, i64) -> i64\n";
}
mlir::Value value(mlir::ModuleOp model, const std::string &name) {
  mlir::Value result;
  model.walk([&](mlir::Operation *operation) {
    auto attributes = operation->getAttrOfType<mlir::DictionaryAttr>("metadata");
    auto path =
        attributes ? attributes.getAs<mlir::StringAttr>("semantic_path") : mlir::StringAttr();
    auto testName =
        attributes ? attributes.getAs<mlir::StringAttr>("test_name") : mlir::StringAttr();
    if (((path && path.getValue() == "Fixture." + name) ||
         (testName && testName.getValue() == name)) &&
        operation->getNumResults() == 1)
      result = operation->getResult(0);
  });
  return result;
}
mlir::Operation *use(mlir::ModuleOp model) {
  mlir::Operation *result = nullptr;
  model.walk([&](nodal::ModuleOp operation) { result = &operation->getRegion(0).front().back(); });
  return result;
}
} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  unsigned cases = 0, failures = 0;
  auto check = [&](bool good, llvm::StringRef name) {
    ++cases;
    if (!good) {
      ++failures;
      llvm::errs() << "SHAPE_REGION_FAIL " << name << '\n';
    }
  };
  auto parse = [&](const std::string &body, bool verify = true) {
    return mlir::parseSourceString<mlir::ModuleOp>(model(body + literal("marker", 997)),
                                                   mlir::ParserConfig(&context, verify));
  };
  auto rejectsAmbiguousSelectors = [&](mlir::ModuleOp module) {
    mlir::ScopedDiagnosticHandler silence(&context,
                                          [](mlir::Diagnostic &) { return mlir::success(); });
    return mlir::failed(mlir::verify(module));
  };
  auto region = [&](mlir::ModuleOp module, const std::string &name,
                    nodal::StaticShapeRegion &result) {
    auto projection = nodal::resolveStaticShapeProjection(value(module, name), use(module));
    return mlir::succeeded(projection)
               ? nodal::resolveStaticShapeRegion(*projection, use(module), result)
               : Status::Invalid;
  };
  const std::string constants = literal("zero", 0) + literal("one", 1) + literal("two", 2) +
                                literal("three", 3) + literal("four", 4) + literal("five", 5) +
                                literal("six", 6) + literal("eleven", 11);
  {
    auto module =
        parse(array("1000000000,3") + constants +
              slice("c0", "nodes", "1000000000,3", 1, "zero", "one", "one", "1000000000,1") +
              slice("c1", "nodes", "1000000000,3", 1, "one", "two", "one", "1000000000,1") +
              slice("c2", "nodes", "1000000000,3", 1, "two", "three", "one", "1000000000,1") +
              view("reshaped", "c1", "1000000000,1", "1000000000"));
    check(bool(module), "large-column-fixture");
    if (module) {
      std::vector<nodal::StaticShapeRegion> regions(3);
      bool resolved = true;
      for (unsigned i = 0; i != 3; ++i)
        resolved &= region(*module, "c" + std::to_string(i), regions[i]) == Status::Proved;
      check(resolved, "column-regions-exact");
      check(regions[0].kind == nodal::StaticShapeRegion::Kind::Box,
            "column-never-enclosed-interval");
      auto root = value(*module, "nodes");
      check(nodal::verifyStaticShapeRegionPartition(root, regions, use(*module)) == Status::Proved,
            "billion-row-column-partition-without-lanes");
      auto duplicate = regions;
      duplicate.push_back(regions[0]);
      check(nodal::verifyStaticShapeRegionPartition(root, duplicate, use(*module)) ==
                Status::Invalid,
            "duplicate-column-rejected");
      check(nodal::verifyStaticShapeRegionPartition(root, llvm::ArrayRef(regions).drop_back(),
                                                    use(*module)) == Status::Invalid,
            "missing-column-rejected");
      check(nodal::verifyStaticShapeRegionPartition(root, regions, use(*module), {1, 1}) ==
                Status::ResourceLimit,
            "explicit-work-resource-boundary");
      nodal::StaticShapeRegion reshaped;
      check(region(*module, "reshaped", reshaped) == Status::Proved &&
                reshaped.kind == nodal::StaticShapeRegion::Kind::Box,
            "reshape-preserves-selected-column-image");
      nodal::StaticShapePoint mapped;
      auto scalar = nodal::mapStaticShapeOrdinal(
          regions[1], nodal::makeLiteralIntegerExpression(999999999), use(*module), mapped);
      check(scalar == Status::Proved, "column-forward-map-compact");
    }
  }
  {
    auto module =
        parse(array("2,3") + constants + view("flat", "nodes", "2,3", "6") +
              slice("middle", "flat", "6", 0, "one", "five", "four", "4") +
              index("first", "flat", "6", {"zero"}) + index("last", "flat", "6", {"five"}) +
              index("point", "nodes", "2,3", {"one", "one"}));
    check(bool(module), "mixed-reshape-fixture");
    if (module) {
      std::vector<nodal::StaticShapeRegion> regions(3);
      check(region(*module, "middle", regions[0]) == Status::Proved &&
                region(*module, "first", regions[1]) == Status::Proved &&
                region(*module, "last", regions[2]) == Status::Proved,
            "flat-interval-and-scalar-certificates");
      check(regions[0].kind == nodal::StaticShapeRegion::Kind::Interval,
            "nonrectangular-contiguous-image-is-interval");
      check(nodal::verifyStaticShapeRegionPartition(value(*module, "nodes"), regions,
                                                    use(*module)) == Status::Proved,
            "mixed-prefix-and-flat-partition");
      auto projection = nodal::resolveStaticShapeProjection(value(*module, "point"), use(*module));
      nodal::StaticShapePoint point;
      check(mlir::succeeded(projection) &&
                nodal::resolveStaticShapePoint(*projection, use(*module), point) == Status::Proved,
            "root-scalar-point");
      auto location = nodal::locateStaticShapeScalar(regions[0], point, use(*module));
      check(location.status == Status::Proved &&
                location.membership == nodal::ShapeRegionMembership::Inside && location.ordinal &&
                nodal::equivalentInstantiatedIntegerExpressions(
                    *location.ordinal, nodal::makeLiteralIntegerExpression(3)),
            "inverse-ordinal-from-mixed-root-map");
      nodal::StaticShapePoint mapped;
      check(nodal::mapStaticShapeOrdinal(regions[0], *location.ordinal, use(*module), mapped) ==
                    Status::Proved &&
                mapped.flatOrdinal &&
                nodal::equivalentInstantiatedIntegerExpressions(*mapped.flatOrdinal,
                                                                *point.flatOrdinal),
            "forward-inverse-roundtrip");
      auto outside = nodal::locateStaticShapeScalar(regions[1], point, use(*module));
      check(outside.membership == nodal::ShapeRegionMembership::Outside, "scalar-outside-region");
    }
  }
  {
    auto module =
        parse(parameter("N", 2, 8) + constants + expression("tail", "sub", "N", "one") +
              array("N,3") + slice("head", "nodes", "N,3", 0, "zero", "one", "one", "1,3") +
              slice("rest", "nodes", "N,3", 0, "one", "N", "tail", "Fixture.tail,3"));
    check(bool(module), "symbolic-boundary-fixture");
    if (module) {
      std::vector<nodal::StaticShapeRegion> regions(2);
      check(region(*module, "head", regions[0]) == Status::Proved &&
                region(*module, "rest", regions[1]) == Status::Proved,
            "symbolic-exact-regions");
      check(nodal::verifyStaticShapeRegionPartition(value(*module, "nodes"), regions,
                                                    use(*module)) == Status::Proved,
            "symbolic-adjacent-authored-cut-partition");
    }
  }
  {
    auto module = parse(parameter("N", 2, 8) + constants + array("2,N") +
                        index("row0", "nodes", "2,N", {"zero"}, "N") +
                        index("row1", "nodes", "2,N", {"one"}, "N"));
    check(bool(module), "symbolic-suffix-row-partition-fixture");
    if (module) {
      std::vector<nodal::StaticShapeRegion> rows(2);
      check(region(*module, "row0", rows[0]) == Status::Proved &&
                region(*module, "row1", rows[1]) == Status::Proved,
            "symbolic-suffix-row-certificates");
      auto root = value(*module, "nodes");
      check(nodal::verifyStaticShapeRegionPartition(root, rows, use(*module)) == Status::Proved,
            "symbolic-suffix-rows-preserve-coordinate-proof");
      check(nodal::verifyStaticShapeRegionPartition(root, {rows[0]}, use(*module)) ==
                Status::Invalid,
            "symbolic-suffix-missing-row-rejected");
      rows.push_back(rows[1]);
      check(nodal::verifyStaticShapeRegionPartition(root, rows, use(*module)) == Status::Invalid,
            "symbolic-suffix-duplicate-row-rejected");
    }
  }
  {
    auto module =
        parse(parameter("P", 0, 0) + constants + expression("opaqueA", "add", "P", "zero") +
                  expression("opaqueB", "add", "P", "zero") + array("4") +
                  index("a", "nodes", "4", {"opaqueA"}) + index("b", "nodes", "4", {"opaqueB"}),
              false);
    check(bool(module) && rejectsAmbiguousSelectors(*module),
          "opaque-selector-fixture-independently-rejects-ambiguity");
    if (module) {
      auto a = nodal::resolveStaticShapeProjection(value(*module, "a"), use(*module));
      auto b = nodal::resolveStaticShapeProjection(value(*module, "b"), use(*module));
      check(mlir::succeeded(a) && mlir::succeeded(b), "opaque-selector-projections");
      if (mlir::succeeded(a) && mlir::succeeded(b)) {
        auto same = nodal::locateStaticShapeScalar(*a, *a, use(*module));
        auto different = nodal::locateStaticShapeScalar(*a, *b, use(*module));
        check(same.membership == nodal::ShapeRegionMembership::Inside,
              "same-opaque-selector-is-identical");
        check(different.membership == nodal::ShapeRegionMembership::Unknown &&
                  different.status == Status::Unsupported,
              "equal-dags-and-singleton-bounds-never-merge-opaque-selectors");
      }
    }
  }
  {
    auto module = parse(
        array("5,4") + constants + literal("twenty", 20) + view("flat", "nodes", "5,4", "20") +
        slice("middle", "flat", "20", 0, "five", "eleven", "six", "6") +
        index("row0", "nodes", "5,4", {"zero"}, "4") + index("row1", "nodes", "5,4", {"one"}, "4") +
        index("row2", "nodes", "5,4", {"two"}, "4") +
        slice("left", "row1", "4", 0, "zero", "one", "one", "1") +
        slice("right", "row2", "4", 0, "three", "four", "one", "1") +
        slice("bottom", "nodes", "5,4", 0, "three", "five", "two", "2,4") +
        slice("bottom0", "bottom", "2,4", 1, "zero", "one", "one", "2,1") +
        slice("bottomRest", "bottom", "2,4", 1, "one", "four", "three", "2,3"));
    check(bool(module), "mixed-nonrectangular-interval-box-fixture");
    if (module) {
      std::vector<nodal::StaticShapeRegion> regions;
      bool resolved = true;
      for (const auto &name : {"middle", "row0", "left", "right", "bottom0", "bottomRest"}) {
        regions.emplace_back();
        resolved &= region(*module, name, regions.back()) == Status::Proved;
      }
      check(resolved && nodal::verifyStaticShapeRegionPartition(value(*module, "nodes"), regions,
                                                                use(*module)) == Status::Proved,
            "bounded-interval-decomposition-mixed-with-column-boxes");
    }
  }
  {
    auto module = parse(parameter("P", 1, 3) + parameter("Q", 1, 3) + constants +
                        expression("tail", "sub", "four", "Q") + array("4") +
                        slice("head", "nodes", "4", 0, "zero", "P", "P", "P") +
                        slice("tailView", "nodes", "4", 0, "Q", "four", "tail", "Fixture.tail"));
    check(bool(module), "unproved-overlap-fixture");
    if (module) {
      std::vector<nodal::StaticShapeRegion> regions(2);
      check(region(*module, "head", regions[0]) == Status::Proved &&
                region(*module, "tailView", regions[1]) == Status::Proved &&
                nodal::verifyStaticShapeRegionPartition(value(*module, "nodes"), regions,
                                                        use(*module)) == Status::Unsupported,
            "equal-defaults-and-overlapping-bounds-do-not-prove-partition");
    }
  }
  {
    auto module = parse(
        parameter("P", 0, 0) + constants + expression("cutA", "add", "P", "zero") +
            expression("cutB", "add", "P", "zero") + expression("lengthA", "sub", "one", "cutA") +
            expression("lengthB", "sub", "one", "cutB") + array("4") +
            slice("a", "nodes", "4", 0, "cutA", "one", "lengthA", "Fixture.lengthA") +
            slice("b", "nodes", "4", 0, "cutB", "one", "lengthB", "Fixture.lengthB") +
            index("a0", "a", "Fixture.lengthA", {"zero"}) +
            index("b0", "b", "Fixture.lengthB", {"zero"}),
        false);
    check(bool(module) && rejectsAmbiguousSelectors(*module),
          "opaque-slice-fixture-independently-rejects-ambiguity");
    if (module) {
      nodal::StaticShapeRegion a, b;
      check(region(*module, "a", a) == Status::Proved && region(*module, "b", b) == Status::Proved,
            "opaque-slice-mathematical-certificates");
      auto aProjection = nodal::resolveStaticShapeProjection(value(*module, "a0"), use(*module));
      auto bProjection = nodal::resolveStaticShapeProjection(value(*module, "b0"), use(*module));
      nodal::StaticShapePoint aPoint, bPoint, mapped;
      bool points =
          mlir::succeeded(aProjection) && mlir::succeeded(bProjection) &&
          nodal::resolveStaticShapePoint(*aProjection, use(*module), aPoint) == Status::Proved &&
          nodal::resolveStaticShapePoint(*bProjection, use(*module), bPoint) == Status::Proved;
      check(points, "opaque-slice-scalar-points");
      if (points) {
        check(!nodal::equivalentInstantiatedIntegerExpressions(*aPoint.flatOrdinal,
                                                               *bPoint.flatOrdinal),
              "separately-authored-slice-selectors-retain-identity");
        check(nodal::locateStaticShapeScalar(a, aPoint, use(*module)).membership ==
                      nodal::ShapeRegionMembership::Inside &&
                  nodal::locateStaticShapeScalar(a, bPoint, use(*module)).membership ==
                      nodal::ShapeRegionMembership::Unknown,
              "slice-inverse-membership-preserves-opaque-identity");
        check(nodal::mapStaticShapeOrdinal(a, nodal::makeLiteralIntegerExpression(0), use(*module),
                                           mapped) == Status::Proved &&
                  nodal::equivalentInstantiatedIntegerExpressions(*mapped.flatOrdinal,
                                                                  *aPoint.flatOrdinal),
              "slice-forward-map-uses-actual-selector-chain");
      }
    }
  }
  {
    const std::string generated =
        "\"nodal.generate\"(%zero, %N, %one) <{induction = \"i\", region_id = \"Fixture.loop\", "
        "induction_path = \"Fixture.loop.index\", metadata = {maximum_trip_count = 8 : i64}}> "
        "({\n^bb0(%i: i64):\n"
        "%selected = \"nodal.shape_index\"(%nodes, %i) <{generated_owner = \"Fixture.loop\", "
        "generated_induction = \"Fixture.loop.index\", metadata = {semantic_path = "
        "\"Fixture.nodes.at(induction:Fixture.loop.index).in(Fixture.loop)\", test_name = "
        "\"point\", input_path = \"Fixture.nodes\", storage = \"structural\", selection_kind = "
        "\"node_array\", source_uses = [\"Fixture.use_point\"]}}> : (" +
        shaped("N") + ", i64) -> " + terminal + "\n}) : (i64, i64, i64) -> ()\n";
    auto module = parse(parameter("N", 2, 8) + constants + array("N") + generated);
    check(bool(module), "direct-induction-scalar-map-fixture");
    if (module) {
      auto scalar = value(*module, "point");
      auto *site = scalar.getDefiningOp();
      auto scalarProjection = nodal::resolveStaticShapeProjection(scalar, site);
      auto wholeProjection = nodal::resolveStaticShapeProjection(value(*module, "nodes"), site);
      nodal::StaticShapeRegion whole;
      nodal::StaticShapePoint point, mapped;
      bool resolved =
          mlir::succeeded(scalarProjection) && mlir::succeeded(wholeProjection) &&
          nodal::resolveStaticShapePoint(*scalarProjection, site, point) == Status::Proved &&
          nodal::resolveStaticShapeRegion(*wholeProjection, site, whole) == Status::Proved;
      check(resolved, "direct-induction-exact-scalar-certificate");
      if (resolved) {
        auto location = nodal::locateStaticShapeScalar(whole, point, site);
        check(location.membership == nodal::ShapeRegionMembership::Inside && location.ordinal &&
                  nodal::mapStaticShapeOrdinal(whole, *location.ordinal, site, mapped) ==
                      Status::Proved &&
                  nodal::equivalentInstantiatedIntegerExpressions(*point.flatOrdinal,
                                                                  *mapped.flatOrdinal),
              "active-induction-whole-map-reuses-domain-proof");
      }
    }
  }
  {
    std::string dims = "1";
    for (unsigned i = 1; i < 700; ++i)
      dims += ",1";
    auto module = parse(array(dims));
    check(bool(module), "high-rank-fixture");
    if (module) {
      nodal::StaticShapeRegion whole;
      check(region(*module, "nodes", whole) == Status::Proved &&
                nodal::verifyStaticShapeRegionPartition(value(*module, "nodes"), {whole},
                                                        use(*module)) == Status::Proved,
            "high-rank-iterative-partition");
      auto root = value(*module, "nodes");
      auto *block = root.getParentBlock();
      auto argument = block->addArgument(root.getType(), root.getLoc());
      check(nodal::verifyStaticShapeRegionPartition(argument, {}, use(*module)) == Status::Invalid,
            "block-argument-root-rejected-without-dereference");
      block->eraseArgument(block->getNumArguments() - 1);
    }
  }
  llvm::outs() << "SHAPE_REGION_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}
