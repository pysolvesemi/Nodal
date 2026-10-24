// Focused native proof, not frontend, adapter, full-module or target qualification.
// Authored IR preserves the real conservative ownership/projection contracts.
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "llvm/Support/raw_ostream.h"
#include <string>
#include <utility>
#include <vector>
using namespace mlir;
namespace {
using S = nodal::ShapeRegionProofStatus;
using A = nodal::ConservativeAlias;
using P = nodal::StaticShapePoint;
using Projection = nodal::StaticShapeProjection;
unsigned checks = 0, failures = 0, mapperCalls = 0, budgetCalls = 0, imageCalls = 0, topologyCalls = 0;
void check(bool ok, const std::string &name) {
  ++checks;
  if (!ok) ++failures;
  (ok ? llvm::outs() : llvm::errs()) << (ok ? "PASS " : "FAIL ") << name << '\n';
}
const char *name(S s) {
  switch (s) {
  case S::Proved: return "Proved";
  case S::Unsupported: return "Unsupported";
  case S::Invalid: return "Invalid";
  case S::ResourceLimit: return "ResourceLimit";
  }
  return "bad-status";
}
const char *name(A s) {
  switch (s) {
  case A::Equal: return "Equal";
  case A::Distinct: return "Distinct";
  case A::Unknown: return "Unknown";
  case A::ResourceLimit: return "ResourceLimit";
  case A::Invalid: return "Invalid";
  case A::Inert: return "Inert";
  }
  return "bad-alias";
}
std::string terminal() { return "!nodal.terminal<\"electrical\">"; }
std::string shaped(const std::string &dims) {
  return "!nodal.shaped<\"" + dims + "\", " + terminal() + ">";
}
std::string literal(const std::string &id, int64_t n) {
  return "%" + id + " = \"nodal.const_literal\"() <{value = " + std::to_string(n) +
      " : i64, spelling = \"" + std::to_string(n) + "\", metadata = {test_name = \"" + id +
      "\"}}> : () -> i64\n";
}
std::string expr(const std::string &id, const std::string &lhs, const std::string &rhs) {
  return "%" + id + " = \"nodal.const_expr\"(%" + lhs + ", %" + rhs +
      ") <{operator_name = \"add\", metadata = {semantic_path = \"Fixture." + id +
      "\"}}> : (i64, i64) -> i64\n";
}
std::string parameter(const std::string &id, int64_t low = 2, int64_t high = 6,
                      int64_t value = 3) {
  return "\"nodal.parameter\"() <{classification = \"structural\", default_value = " +
      std::to_string(value) + " : i64, metadata = {semantic_path = \"Fixture." + id +
      "\"}, parameter_kind = \"integer\", sym_name = \"" + id +
      "\", type = i64, variability = \"symbolic\"}> : () -> ()\n" +
      literal(id + "lo", low) + literal(id + "hi", high) +
      "\"nodal.parameter_constraint\"(%" + id + "lo, %" + id +
      "hi) <{constraint_kind = \"range\", lower_inclusive = true, upper_inclusive = true, "
      "metadata = {}, parameter = @" + id + "}> : (i64, i64) -> ()\n" +
      "\"nodal.parameter_envelope\"() <{effects = [\"shape\", \"topology\"], "
      "metadata = {}, parameter = @" + id + ", policy = \"static_generate\"}> : () -> ()\n%" +
      id + " = \"nodal.const_parameter_ref\"() <{parameter = @" + id +
      ", metadata = {}}> : () -> i64\n";
}
std::string owner(const std::string &scope) {
  return scope.empty() ? "" : "generated_owner = \"Fixture." + scope +
      "\", generated_induction = \"Fixture." + scope + ".index\", ";
}
std::string array(const std::string &id, const std::string &dims,
                  const std::string &scope = "") {
  return "%" + id + " = \"nodal.node_array\"() <{" + owner(scope) + "name = \"" + id +
      "\", source_path = \"Fixture." + id + "\", metadata = {test_name = \"" + id +
      "\", semantic_path = \"Fixture." + id + "\", storage = \"structural\"}}> : () -> " +
      shaped(dims) + "\n";
}
std::string index(const std::string &id, const std::string &input, const std::string &selectors,
                  unsigned rank, const std::string &inType, const std::string &outType,
                  const std::string &inputPath, const std::string &tokens,
                  const std::string &scope = "") {
  std::string path = inputPath + ".at(" + tokens + ")";
  if (!scope.empty()) path += ".in(Fixture." + scope + ")";
  std::string args;
  for (unsigned i = 0; i != rank; ++i) args += ", i64";
  return "%" + id + " = \"nodal.shape_index\"(%" + input + ", " + selectors + ") <{" +
      owner(scope) + "metadata = {test_name = \"" + id + "\", input_path = \"" + inputPath +
      "\", semantic_path = \"" + path + "\", storage = \"structural\", " +
      (outType == terminal() ? "selection_kind = \"node_array\", allow_floating = true, " :
                              "access = \"read_only\", ") +
      "source_uses = [\"Fixture.use_" + id + "\"]}}> : (" + inType + args + ") -> " +
      outType + "\n";
}
std::string begin(const std::string &scope, const std::string &iv, const std::string &hi,
                  int64_t maximum, const std::string &step = "one") {
  return "\"nodal.generate\"(%zero, %" + hi + ", %" + step + ") <{induction = \"" + iv +
      "\", region_id = \"Fixture." + scope + "\", induction_path = \"Fixture." + scope +
      ".index\", metadata = {maximum_trip_count = " + std::to_string(maximum) +
      " : i64}}> ({^bb0(%" + iv + ": i64):\n";
}
std::string end() { return "}) : (i64, i64, i64) -> ()\n"; }
std::string connection(const std::string &lhs, const std::string &rhs,
                       const std::string &id, const std::string &scope) {
  return "\"nodal.connect\"(%" + lhs + ", %" + rhs + ") <{" + owner(scope) +
      "connection_id = \"" + id + "\", source_path = \"Fixture." + scope + "." + id +
      "\", metadata = {test_name = \"" + id + "\"}}> : (" + terminal() + ", " + terminal() +
      ") -> ()\n";
}
std::string wrap(const std::string &body) {
  return "module {\n"
      "\"nodal.nature\"() <{abstol = 1.0e-6 : f64, access = \"V\", metadata = {}, "
      "sym_name = \"Voltage\", units = \"V\"}> : () -> ()\n"
      "\"nodal.nature\"() <{abstol = 1.0e-12 : f64, access = \"I\", metadata = {}, "
      "sym_name = \"Current\", units = \"A\"}> : () -> ()\n"
      "\"nodal.discipline\"() <{domain = \"continuous\", flow = @Current, metadata = {}, "
      "potential = @Voltage, sym_name = \"electrical\"}> : () -> ()\n"
      "\"nodal.module\"() <{sym_name = \"Fixture\", metadata = {semantic_path = \"Fixture\"}}> ({\n" +
      body + literal("marker", 99) + "}) : () -> () }\n";
}
// Faults change original authored operands/ownership, never supplied image flags.
std::string model(bool symbolic, bool anchor, const std::string &fault = "",
                  int64_t fixedRows = 3, int64_t fixedCols = 4) {
  const bool square = fault == "repeat-index" || fault == "swap-indices";
  std::string r = symbolic ? "R" : std::to_string(fixedRows);
  std::string c = symbolic ? (fault == "same-bound" ? "R" : "C") :
                             std::to_string(square ? fixedRows : fixedCols);
  const std::string type = shaped(r + "," + c), rowType = shaped(c);
  std::string body = literal("zero", 0) + literal("one", 1) + literal("two", 2);
  body += symbolic ? parameter("R") + parameter("C", 2, 6, 4) + parameter("F") :
                     literal("R", fixedRows) + literal("C", square ? fixedRows : fixedCols);
  if (fault == "expr-step") body += expr("stepExpr", "one", "zero");
  if (fault == "expr-upper") body += expr("upperExpr", "R", "zero");
  if (fault != "outer-local") body += array("p", r + "," + c) + array("n", r + "," + c);
  if (anchor)
    body += "%anchor = \"nodal.node\"() <{name = \"anchor\", source_path = \"Fixture.anchor\", "
        "metadata = {test_name = \"anchor\", semantic_path = \"Fixture.anchor\", "
        "allow_floating = true}}> : () -> " + terminal() + "\n";
  std::string outerUpper = fault == "swap-bounds" ? "C" : fault == "foreign-bound" ? "F" :
                           fault == "expr-upper" ? "upperExpr" : "R";
  body += begin("outer", "i", outerUpper, symbolic ? 6 : fixedRows);
  if (fault == "outer-local") body += array("p", r + "," + c, "outer") +
                                            array("n", r + "," + c, "outer");
  const std::string outerSelector = fault == "missing-axis" ? "%zero" : "%i";
  const std::string outerToken = fault == "missing-axis" ? "literal:0" : "induction:Fixture.outer.index";
  for (auto root : {"p", "n"}) {
    if (fault == "swap-indices" && std::string(root) == "n") continue;
    const bool missingDestination = fault == "destination-missing-axis" && std::string(root) == "n";
    body += index(std::string(root) + "row", root, missingDestination ? "%zero" : outerSelector,
                  1, type, rowType, std::string("Fixture.") + root,
                  missingDestination ? "literal:0" : outerToken, "outer");
  }
  std::string innerUpper = fault == "same-bound" || fault == "repeat-bound" || fault == "swap-bounds" ? "R" : "C";
  if (fault == "local-bound") {
    body += "%localC = \"nodal.const_parameter_ref\"() <{parameter = @C, metadata = {}}> : () -> i64\n";
    innerUpper = "localC";
  }
  if (fault == "third-axis") body += begin("outer.extra", "k", "R", symbolic ? 6 : fixedRows);
  body += begin("outer.inner", "j", innerUpper, symbolic ? 6 : (square ? fixedRows : fixedCols),
                fault == "expr-step" ? "stepExpr" : fault == "nonunit" ? "two" : "one");
  for (auto root : {"p", "n"}) {
    const bool swap = fault == "swap-indices" && std::string(root) == "n";
    std::string rowOuterToken = fault == "destination-missing-axis" && std::string(root) == "n"
                                   ? "literal:0" : outerToken;
    std::string rowPath = std::string("Fixture.") + root + ".at(" + rowOuterToken + ").in(Fixture.outer)";
    if (swap) {
      body += index("nrow", "n", "%j", 1, type, rowType, "Fixture.n",
                    "induction:Fixture.outer.inner.index", "outer.inner");
      rowPath = "Fixture.n.at(induction:Fixture.outer.inner.index).in(Fixture.outer.inner)";
    }
    const bool repeated = fault == "repeat-index" || swap;
    body += index(std::string(root) + "lane", std::string(root) + "row", repeated ? "%i" : "%j",
                  1, rowType, terminal(), rowPath,
                  repeated ? "induction:Fixture.outer.index" : "induction:Fixture.outer.inner.index",
                  "outer.inner");
  }
  if (fault == "fresh-tree") {
    // Seventeen independently fresh leaves share only the original Module array.
    // Charging a new 1024-work nested certificate per shared occurrence would
    // wrongly exhaust the old sufficient one-shared-root tree proof.
    for (unsigned i = 0; i != 17; ++i) {
      std::string id = "fresh" + std::to_string(i);
      body += "%" + id + " = \"nodal.node\"() <{" + owner("outer.inner") +
          "name = \"" + id + "\", source_path = \"Fixture." + id +
          "\", metadata = {semantic_path = \"Fixture." + id +
          "\", allow_floating = true}}> : () -> " + terminal() + "\n";
      body += connection("plane", id, "freshjoin" + std::to_string(i), "outer.inner");
    }
  } else {
    body += connection("plane", anchor ? "anchor" : "nlane", "join", "outer.inner");
  }
  body += end();
  if (fault == "third-axis") body += end();
  body += end();
  if (fault != "outer-local") {
    for (auto root : {"p", "n"}) {
      body += index(std::string(root) + "00", root, "%zero, %zero", 2, type, terminal(),
                    std::string("Fixture.") + root, "literal:0,literal:0");
      body += index(std::string(root) + "01", root, "%zero, %one", 2, type, terminal(),
                    std::string("Fixture.") + root, "literal:0,literal:1");
      body += index(std::string(root) + "11", root, "%one, %one", 2, type, terminal(),
                    std::string("Fixture.") + root, "literal:1,literal:1");
    }
    body += index("p01again", "p", "%zero, %one", 2, type, terminal(), "Fixture.p",
                  "literal:0,literal:1");
  }
  return wrap(body);
}
Operation *operation(mlir::ModuleOp module, StringRef id) {
  Operation *found = nullptr;
  module.walk([&](Operation *op) {
    auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
    auto label = metadata ? metadata.getAs<StringAttr>("test_name") : StringAttr();
    if (label && label.getValue() == id) found = op;
  });
  return found;
}
Value value(mlir::ModuleOp module, StringRef id) {
  Operation *op = operation(module, id);
  return op && op->getNumResults() == 1 ? op->getResult(0) : Value();
}
std::string print(mlir::ModuleOp module) {
  std::string text;
  llvm::raw_string_ostream stream(text);
  module->print(stream, OpPrintingFlags().printGenericOpForm().assumeVerified());
  return text;
}
bool exact(const P &a, const P &b) {
  if (a.root != b.root || a.coordinates.size() != b.coordinates.size() ||
      bool(a.flatOrdinal) != bool(b.flatOrdinal)) return false;
  for (unsigned i = 0; i != a.coordinates.size(); ++i)
    if (a.coordinates[i].root != b.coordinates[i].root ||
        a.coordinates[i].bounds.lower != b.coordinates[i].bounds.lower ||
        a.coordinates[i].bounds.upper != b.coordinates[i].bounds.upper) return false;
  return !a.flatOrdinal || (a.flatOrdinal->root == b.flatOrdinal->root &&
      a.flatOrdinal->bounds.lower == b.flatOrdinal->bounds.lower &&
      a.flatOrdinal->bounds.upper == b.flatOrdinal->bounds.upper);
}
S map(const Projection &from, const P &point, const Projection &to, Operation *use,
      bool enhanced, const std::string &label, S expected, nodal::ShapeRegionProofLimits limits = {512, 64},
      P *saved = nullptr) {
  ++mapperCalls;
  P sentinel = point, output = sentinel;
  S status = enhanced ? nodal::mapStaticShapeSameOccurrenceOrInvariantPoint(from, point, to, use,
                                                                         output, limits) :
                        nodal::mapStaticShapeSameOccurrencePoint(from, point, to, use, output, limits);
  llvm::outs() << "MAP " << label << " status=" << name(status) << " allowance=" << limits.maximumWork
               << " states=" << limits.maximumStates << '\n';
  check(status == expected, label + "/status");
  if (status != S::Proved) check(exact(output, sentinel), label + "/transactional");
  else {
    check(output.root == to.root, label + "/destination-root");
    if (saved) *saved = std::move(output);
    if (limits.maximumWork == 512 && limits.maximumStates == 64) {
      // A bounded public-limit measurement, not an internal charged-work counter.
      uint64_t low = 0, high = 512;
      bool lawful = true;
      auto at = [&](uint64_t allowance) {
        ++mapperCalls;
        ++budgetCalls;
        P probe = sentinel;
        S result = enhanced ? nodal::mapStaticShapeSameOccurrenceOrInvariantPoint(
            from, point, to, use, probe, {allowance, 64}) :
            nodal::mapStaticShapeSameOccurrencePoint(from, point, to, use, probe, {allowance, 64});
        lawful &= result == S::Proved || result == S::ResourceLimit;
        if (result != S::Proved) lawful &= exact(probe, sentinel);
        return result;
      };
      while (low + 1 < high && lawful) {
        uint64_t middle = low + (high - low) / 2;
        S result = at(middle);
        if (result == S::Proved) high = middle;
        else low = middle;
      }
      S below = at(high - 1), enough = at(high);
      bool established = lawful && below == S::ResourceLimit && enough == S::Proved;
      check(established, label + "/adjacent-public-budget-boundary");
      if (established)
        llvm::outs() << "MINIMUM " << label << " minimum_sufficient_allowance=" << high
                     << " maximum_searched=512 states=64\n";
      else
        llvm::outs() << "MINIMUM " << label << " not-established maximum_searched=512\n";
    }
  }
  return status;
}
void alias(mlir::ModuleOp module, StringRef left, StringRef right, A expected,
           const std::string &label) {
  ++topologyCalls;
  auto status = nodal::compareConservativeEndpoints(value(module, left), value(module, right),
                                                   operation(module, "marker"));
  llvm::outs() << "CC " << label << " status=" << name(status) << '\n';
  check(status == expected, label);
}
void positive(MLIRContext &context, bool symbolic, bool anchor, const std::string &variant = "",
              int64_t rows = 3, int64_t cols = 4) {
  std::string label = (symbolic ? "symbolic" : "literal") + std::string(anchor ? "-anchor" : "-paired") + variant;
  if (rows != 3 || cols != 4) label += "-signed64-product";
  auto module = parseSourceString<mlir::ModuleOp>(model(symbolic, anchor, variant, rows, cols),
                                                ParserConfig(&context, false));
  check(bool(module), label + "/parse");
  if (!module) return;
  std::string before = print(*module);
  Operation *use = operation(*module, "join"), *marker = operation(*module, "marker");
  auto from = nodal::resolveStaticShapeProjection(use->getOperand(0), use);
  auto to = nodal::resolveStaticShapeProjection(use->getOperand(1), use);
  check(succeeded(from) && succeeded(to), label + "/projections");
  check(succeeded(nodal::resolveConservativeEndpoint(use->getOperand(0), use)) &&
        succeeded(nodal::resolveConservativeEndpoint(use->getOperand(1), use)), label + "/actual-CC-endpoints");
  check(succeeded(nodal::verifyGeneratedStructuralOwnership(use)), label + "/relation-owner");
  if (failed(from) || failed(to)) return;
  nodal::StaticShapeRegion image;
  ++imageCalls;
  auto imageStatus = nodal::resolveStaticShapeIterationImage(*from, use, image, {512, 64});
  llvm::outs() << "IMAGE " << label << " status=" << name(imageStatus) << '\n';
  bool completeImage = imageStatus == S::Proved && image.iterationCount() == 2 &&
      image.iterationAt(0).varyingAxis == 0 && image.iterationAt(1).varyingAxis == 1;
  check(completeImage, label + "/actual-ordered-image512");
  if (!completeImage) return;
  for (auto selected : {"p00", "p01", "p11"}) {
    auto selectedProjection = nodal::resolveStaticShapeProjection(value(*module, selected), marker);
    P point;
    bool validPoint = succeeded(selectedProjection) &&
        nodal::resolveStaticShapePoint(*selectedProjection, marker, point, {512, 64}) == S::Proved;
    check(validPoint, label + "/point-" + selected);
    if (!validPoint) continue;
    for (bool enhanced : {false, true}) {
      std::string call = label + "/" + selected + (enhanced ? "/enhanced" : "/paired");
      P mapped;
      S expected = anchor && !enhanced ? S::Unsupported : S::Proved;
      if (map(*from, point, *to, use, enhanced, call, expected, {512, 64}, &mapped) == S::Proved) {
        if (anchor) {
          check(mapped.coordinates.empty() && mapped.flatOrdinal && mapped.flatOrdinal->bounds.lower == 0 &&
                mapped.flatOrdinal->bounds.upper == 0, call + "/exact-anchor");
        } else {
          bool same = mapped.coordinates.size() == point.coordinates.size();
          if (same) for (unsigned i = 0; i != point.coordinates.size(); ++i)
            same &= nodal::equivalentInstantiatedIntegerExpressions(point.coordinates[i], mapped.coordinates[i]);
          check(same, call + "/full-tuple");
          P reverse;
          map(*to, mapped, *from, use, enhanced, call + "/reverse", S::Proved, {512, 64}, &reverse);
          check(reverse.root == point.root && reverse.flatOrdinal && point.flatOrdinal &&
                nodal::equivalentInstantiatedIntegerExpressions(*reverse.flatOrdinal, *point.flatOrdinal),
                call + "/reverse-roundtrip");
        }
      }
      map(*from, point, *to, use, enhanced, call + "/work0", S::ResourceLimit, {0, 64});
      map(*from, point, *to, use, enhanced, call + "/work1", S::ResourceLimit, {1, 64});
      map(*from, point, *to, use, enhanced, call + "/state0", S::ResourceLimit, {512, 0});
      map(*from, point, *to, use, enhanced, call + "/state1", S::ResourceLimit, {512, 1});
    }
    // These controls mutate only public query facts, never the fixture IR.
    P malformed = point;
    malformed.root = to->root;
    map(*from, malformed, *to, use, true, label + "/wrong-point-root", S::Invalid);
    malformed = point;
    malformed.flatOrdinal = nodal::makeLiteralIntegerExpression(987654321);
    map(*from, malformed, *to, use, true, label + "/contradictory-flat", S::Invalid);
    malformed = point;
    malformed.coordinates.clear();
    map(*from, malformed, *to, use, true, label + "/flat-only-outside-slice", S::Unsupported);
    for (unsigned axis = 0; axis != 2; ++axis) {
      malformed = point;
      malformed.flatOrdinal.reset();
      malformed.coordinates[axis] = nodal::makeLiteralIntegerExpression(symbolic ? 2 : axis ? cols : rows);
      map(*from, malformed, *to, use, true, label + "/universal-bound-axis" + std::to_string(axis), S::Unsupported);
    }
    malformed = point;
    malformed.flatOrdinal.reset();
    malformed.coordinates[0] = nodal::makeLiteralIntegerExpression(-1);
    map(*from, malformed, *to, use, true, label + "/negative-coordinate", S::Unsupported);
    malformed = point;
    malformed.flatOrdinal.reset();
    malformed.coordinates[0] = nodal::makeOpaqueIntegerExpression(image.iterationAt(0).domain.induction,
                                                                 *image.iterationAt(0).domain.attainedInductionBounds);
    map(*from, malformed, *to, use, true, label + "/nonliteral-coordinate", S::Unsupported);
    map(*from, point, *to, nullptr, true, label + "/null-use", S::Invalid);
  }
  if (anchor) {
    P scalar;
    check(nodal::resolveStaticShapePoint(*to, use, scalar, {512, 64}) == S::Proved, label + "/scalar-point");
    map(*to, scalar, *from, use, true, label + "/reverse-anchor-no-guess", S::Unsupported);
    alias(*module, "p01", "p11", A::Equal, label + "/all-tuples-through-anchor");
    alias(*module, "p00", "p01", A::Equal, label + "/inner-change-through-anchor");
    alias(*module, "p00", "p11", A::Equal, label + "/both-axes-through-anchor");
    alias(*module, "p01", "anchor", A::Equal, label + "/first-tuple-anchor");
    alias(*module, "p11", "anchor", A::Equal, label + "/second-tuple-anchor");
  } else {
    alias(*module, "p01", "n01", A::Equal, label + "/same-tuple-paired");
    alias(*module, "n11", "p11", A::Equal, label + "/reverse-same-tuple");
    alias(*module, "p01", "p11", A::Distinct, label + "/different-outer-same-inner");
    alias(*module, "p00", "p01", A::Distinct, label + "/same-outer-different-inner");
    alias(*module, "p00", "p11", A::Distinct, label + "/both-axes-different");
    alias(*module, "n01", "n11", A::Distinct, label + "/other-root-different-outer");
    alias(*module, "p01", "n11", symbolic ? A::Unknown : A::Distinct,
          label + "/cross-root-different-tuple");
  }
  alias(*module, "p01", "p01again", A::Equal, label + "/independent-repeated-tuple");
  auto foreign = parseSourceString<mlir::ModuleOp>(model(symbolic, anchor, variant, rows, cols),
                                                 ParserConfig(&context, false));
  check(bool(foreign), label + "/foreign-parse");
  if (foreign) {
    auto selected = nodal::resolveStaticShapeProjection(value(*module, "p01"), marker);
    P point;
    if (succeeded(selected) && nodal::resolveStaticShapePoint(*selected, marker, point) == S::Proved)
      map(*from, point, *to, operation(*foreign, "join"), true, label + "/foreign-use", S::Invalid);
    ++topologyCalls;
    check(nodal::compareConservativeEndpointOccurrences(value(*module, "p01"), marker,
          value(*foreign, "p01"), operation(*foreign, "marker")) == A::Invalid,
          label + "/foreign-CC-occurrence");
  }
  check(before == print(*module), label + "/all-queries-preserve-IR");
}
void negative(MLIRContext &context, const std::string &fault, bool symbolic) {
  ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
  auto module = parseSourceString<mlir::ModuleOp>(model(symbolic, false, fault), ParserConfig(&context, false));
  check(bool(module), fault + "/parse");
  if (!module) return;
  std::string before = print(*module);
  Operation *use = operation(*module, "join");
  auto from = nodal::resolveStaticShapeProjection(use->getOperand(0), use);
  auto to = nodal::resolveStaticShapeProjection(use->getOperand(1), use);
  bool rejected = failed(from) || failed(to);
  if (!rejected) {
    P point;
    point.root = from->root;
    point.coordinates = {nodal::makeLiteralIntegerExpression(0), nodal::makeLiteralIntegerExpression(1)};
    P sentinel = point, output = sentinel;
    for (bool enhanced : {false, true}) {
      ++mapperCalls;
      output = sentinel;
      S status = enhanced ? nodal::mapStaticShapeSameOccurrenceOrInvariantPoint(*from, point, *to, use, output, {512,64}) :
                           nodal::mapStaticShapeSameOccurrencePoint(*from, point, *to, use, output, {512,64});
      llvm::outs() << "MAP negative/" << fault << " enhanced=" << enhanced << " status=" << name(status) << '\n';
      check(status != S::Proved && status != S::ResourceLimit, fault + "/bounded-refusal");
      check(exact(output, sentinel), fault + "/transactional");
      rejected |= status != S::Proved;
    }
  }
  check(rejected, fault + "/original-SSA-rejected");
  if (fault != "outer-local") {
    ++topologyCalls;
    auto result = nodal::compareConservativeEndpoints(value(*module, "p01"), value(*module, "p11"),
                                                     operation(*module, "marker"));
    llvm::outs() << "CC negative/" << fault << " status=" << name(result) << '\n';
    check(result != A::Distinct && result != A::Equal && result != A::ResourceLimit,
          fault + "/unsupported-wiring-never-proves-distinct");
  }
  check(before == print(*module), fault + "/unchanged-IR");
}
void predecessors(MLIRContext &context) {
  auto fresh = parseSourceString<mlir::ModuleOp>(model(false, false, "fresh-tree"),
                                               ParserConfig(&context, false));
  check(bool(fresh), "predecessor/fresh-tree-parse");
  if (fresh) {
    auto relation = operation(*fresh, "freshjoin0");
    check(relation && succeeded(nodal::resolveConservativeEndpoint(relation->getOperand(0), relation)) &&
          succeeded(nodal::resolveConservativeEndpoint(relation->getOperand(1), relation)),
          "predecessor/fresh-tree-real-endpoints");
    alias(*fresh, "p01", "p11", A::Distinct, "predecessor/17-fresh-leaves-one-shared-root");
  }
  std::string body = literal("zero", 0) + literal("one", 1) + literal("two", 2) +
      literal("three", 3) + literal("four", 4) + literal("hi", 7) + array("p", "9") + array("n", "9");
  std::string generated = begin("single", "iv", "hi", 3, "two");
  generated.replace(generated.find("%zero"), 5, "%two");
  body += generated;
  for (auto root : {"p", "n"})
    body += index(std::string(root) + "lane", root, "%iv", 1, shaped("9"), terminal(),
                  std::string("Fixture.") + root, "induction:Fixture.single.index", "single");
  body += connection("plane", "nlane", "join", "single") + end();
  for (auto root : {"p", "n"})
    body += index(std::string(root) + "2", root, "%two", 1, shaped("9"), terminal(),
                  std::string("Fixture.") + root, "literal:2");
  body += index("p4", "p", "%four", 1, shaped("9"), terminal(), "Fixture.p", "literal:4");
  body += index("p3", "p", "%three", 1, shaped("9"), terminal(), "Fixture.p", "literal:3");
  auto module = parseSourceString<mlir::ModuleOp>(wrap(body), ParserConfig(&context, false));
  check(bool(module), "predecessor/nonunit-parse");
  if (!module) return;
  Operation *use = operation(*module, "join"), *marker = operation(*module, "marker");
  auto from = nodal::resolveStaticShapeProjection(use->getOperand(0), use);
  auto to = nodal::resolveStaticShapeProjection(use->getOperand(1), use);
  auto selected = nodal::resolveStaticShapeProjection(value(*module, "p2"), marker);
  check(succeeded(from) && succeeded(to) && succeeded(selected), "predecessor/nonunit-projections");
  if (failed(from) || failed(to) || failed(selected)) return;
  P point;
  check(nodal::resolveStaticShapePoint(*selected, marker, point) == S::Proved,
        "predecessor/nonunit-selected-point");
  for (bool enhanced : {false, true}) {
    P mapped;
    std::string label = enhanced ? "predecessor/nonunit-enhanced" : "predecessor/nonunit-old";
    if (map(*from, point, *to, use, enhanced, label, S::Proved, {512,64}, &mapped) == S::Proved)
      map(*to, mapped, *from, use, enhanced, label + "/reverse", S::Proved);
    P hole = point;
    hole.coordinates = {nodal::makeLiteralIntegerExpression(3)};
    hole.flatOrdinal = nodal::makeLiteralIntegerExpression(3);
    map(*from, hole, *to, use, enhanced, label + "/residue-hole", S::Unsupported);
  }
  alias(*module, "p2", "n2", A::Equal, "predecessor/nonunit-actual-CC-equal");
  alias(*module, "p2", "p4", A::Distinct, "predecessor/nonunit-actual-CC-distinct");
}

// Boundary freshness controls, not claims that a malformed operand reaches a
// private memo hit. Independent actual-use verifiers may reject it earlier.
// Every mutation is between completed public queries, never during a proof.
void contextFreshness(MLIRContext &context) {
  ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
  auto module = parseSourceString<mlir::ModuleOp>(model(false, false), ParserConfig(&context, false));
  check(bool(module), "context-freshness/parse");
  if (!module) return;
  std::string originalIR = print(*module);
  Operation *use = operation(*module, "join");
  auto from = nodal::resolveStaticShapeProjection(use->getOperand(0), use);
  auto to = nodal::resolveStaticShapeProjection(use->getOperand(1), use);
  check(succeeded(from) && succeeded(to), "context-freshness/projections");
  if (failed(from) || failed(to)) return;
  check(from->root != to->root && from->root.getType() == to->root.getType(),
        "context-freshness/same-type-distinct-original-SSA");
  P point;
  point.root = from->root;
  point.coordinates = {nodal::makeLiteralIntegerExpression(0), nodal::makeLiteralIntegerExpression(1)};
  point.flatOrdinal = nodal::makeLiteralIntegerExpression(1);
  for (bool enhanced : {false, true}) {
    std::string route = enhanced ? "enhanced" : "old";
    map(*from, point, *to, use, enhanced, "context-freshness/initial-" + route, S::Proved);
    P foreignRoot = point;
    foreignRoot.root = to->root;
    map(*from, foreignRoot, *to, use, enhanced, "context-freshness/same-type-wrong-SSA-" + route,
        S::Invalid);
  }
  auto refused = [&](const std::string &label) {
    for (bool enhanced : {false, true}) {
      P output = point;
      ++mapperCalls;
      auto status = enhanced ? nodal::mapStaticShapeSameOccurrenceOrInvariantPoint(
          *from, point, *to, use, output, {512,64}) : nodal::mapStaticShapeSameOccurrencePoint(
          *from, point, *to, use, output, {512,64});
      llvm::outs() << "MAP " << label << " enhanced=" << enhanced << " status=" << name(status)
                   << " allowance=512 states=64\n";
      check(status == S::Invalid || status == S::Unsupported, label + "/classified-refusal");
      check(exact(output, point), label + "/transactional");
    }
  };
  Operation *inner = use->getParentOp();
  Value oldStep = inner->getOperand(2);
  inner->setOperand(2, value(*module, "two"));
  refused("context-freshness/changed-step-between-queries");
  inner->setOperand(2, oldStep);
  Attribute region = inner->getAttr("region_id");
  inner->setAttr("region_id", StringAttr::get(&context, ""));
  refused("context-freshness/changed-header-between-queries");
  inner->setAttr("region_id", region);
  for (bool enhanced : {false, true})
    map(*from, point, *to, use, enhanced,
        enhanced ? "context-freshness/restored-enhanced" : "context-freshness/restored-old", S::Proved);
  check(originalIR == print(*module), "context-freshness/fixture-restored-exactly");
}

// Closed-tree controls are real native topology, including actual child
// terminal incidences. Refusal cases must authenticate before alias queries;
// malformed input is not evidence for cycle/child-uncertainty conservatism.
std::string closedPathModel(const std::string &kind) {
  const unsigned count = kind == "budget" ? 8 : kind == "chain" || kind == "cycle" ? 3 : 2;
  std::vector<std::string> names{"p", "n"};
  for (unsigned i = 2; i < count; ++i) names.push_back("q" + std::to_string(i));
  std::string body = literal("zero", 0) + literal("one", 1) + literal("R", 3) + literal("C", 4);
  auto width = [&](const std::string &root) { return kind == "shift" && root == "n" ? "5" : "4"; };
  for (const auto &root : names) body += array(root, "3," + std::string(width(root)));
  if (kind == "anchor")
    body += "%anchor = \"nodal.node\"() <{name = \"anchor\", source_path = \"Fixture.anchor\", "
        "metadata = {test_name = \"anchor\", semantic_path = \"Fixture.anchor\", "
        "allow_floating = true}}> : () -> " + terminal() + "\n";
  auto family = [&](const std::string &outer, const std::string &inner, const std::string &suffix) {
    std::string result = begin(outer, "i" + suffix, "R", 3);
    for (const auto &root : names)
      result += index(root + "row" + suffix, root, "%i" + suffix, 1, shaped("3," + std::string(width(root))), shaped(width(root)),
                      "Fixture." + root, "induction:Fixture." + outer + ".index", outer);
    result += begin(inner, "j" + suffix, "C", 4);
    if (kind == "shift") result += expr("shifted", "j", "one");
    for (const auto &root : names)
      result += index(root + "lane" + suffix, root + "row" + suffix,
                      kind == "shift" && root == "n" ? "%shifted" : "%j" + suffix, 1,
                      shaped(width(root)), terminal(), "Fixture." + root + ".at(induction:Fixture." +
                      outer + ".index).in(Fixture." + outer + ")",
                      kind == "shift" && root == "n" ? "expression:Fixture.shifted" :
                      "induction:Fixture." + inner + ".index", inner);
    // A chain has a genuinely nonadjacent target; no tuple-only root rebasing.
    for (unsigned i = 1; i < names.size(); ++i)
      result += connection(names[i - 1] + "lane" + suffix, names[i] + "lane" + suffix,
                           "path" + suffix + std::to_string(i), inner);
    if (kind == "parallel") result += connection("plane", "nlane", "parallel", inner);
    if (kind == "cycle") result += connection(names.back() + "lane", "plane", "cycle", inner);
    if (kind == "anchor") result += connection("plane", "anchor", "anchor_join", inner);
    if (kind == "fresh-array") {
      result += array("fresh_array", "1", inner);
      result += index("fresh_element", "fresh_array", "%zero", 1, shaped("1"), terminal(),
                      "Fixture.fresh_array", "literal:0", inner);
      result += connection("plane", "fresh_element", "fresh_array_join", inner);
    }
    if (kind == "pendant" || kind == "child" || kind == "uncertain-child") {
      if (kind == "pendant") {
        result += "%fresh = \"nodal.node\"() <{" + owner(inner) +
            "name = \"fresh\", source_path = \"Fixture.fresh\", metadata = {test_name = \"fresh\", "
            "semantic_path = \"Fixture.fresh\", allow_floating = true}}> : () -> " + terminal() + "\n";
        result += connection("plane", "fresh", "pendant_join", inner);
      } else {
        result += "\"nodal.instance\"() <{" + owner(inner) +
            "sym_name = \"leaf\", module = @Child, parameter_bindings = {}, domain_bindings = {}, "
            "metadata = {test_name = \"child_instance\", semantic_path = \"Fixture.leaf\"}}> : () -> ()\n";
        for (auto port : {"a", "b"}) {
          std::string id = std::string("child_") + port;
          result += "%" + id + " = \"nodal.instance_terminal\"() <{" + owner(inner) +
              "instance = @leaf, port = \"" + port + "\", name = \"" + id +
              "\", direction = \"inout\", flow_orientation = \"out_of_component\", source_path = \"Child." +
              port + "\", metadata = {test_name = \"" + id + "\", semantic_path = \"Fixture." +
              id + "\", allow_floating = true}}> : () -> " + terminal() + "\n";
        }
        result += connection("plane", "child_a", "child_a_join", inner);
        result += connection("nlane", "child_b", "child_b_join", inner);
      }
    }
    return result + end() + end();
  };
  body += family("outer", "outer.inner", "");
  if (kind == "foreign-scope") body += family("other", "other.inner", "Other");
  for (const auto &root : names)
    for (auto tuple : {"00", "01", "11"}) {
      std::string pair = tuple;
      body += index(root + pair, root, pair == "00" ? "%zero, %zero" :
                    pair == "01" ? "%zero, %one" : "%one, %one", 2, shaped("3," + std::string(width(root))), terminal(),
                    "Fixture." + root, pair == "00" ? "literal:0,literal:0" :
                    pair == "01" ? "literal:0,literal:1" : "literal:1,literal:1");
    }
  std::string text = wrap(body);
  if (kind == "child" || kind == "uncertain-child") {
    std::string child = "\"nodal.module\"() <{sym_name = \"Child\", metadata = {semantic_path = \"Child\"}}> ({\n";
    for (auto port : {"a", "b"})
      child += "%" + std::string(port) + " = \"nodal.terminal\"() <{name = \"" + port +
          "\", direction = \"inout\", flow_orientation = \"into_component\", source_path = \"Child." +
          port + "\", metadata = {semantic_path = \"Child." + port +
          "\", allow_floating = true}}> : () -> " + terminal() + "\n";
    if (kind == "uncertain-child")
      child += "\"nodal.connect\"(%a, %b) <{connection_id = \"Child.short\", source_path = \"Child.short\", "
               "metadata = {}}> : (" + terminal() + ", " + terminal() + ") -> ()\n";
    child += "}) : () -> ()\n";
    const std::string marker = "\"nodal.module\"() <{sym_name = \"Fixture\"";
    text.insert(text.find(marker), child);
  }
  return text;
}
// The Module/Generate verifier also requires every selected pair to have a
// proved topology result. Unknown/resource controls intentionally cannot pass
// that global gate; do not confuse its refusal with malformed original IR.
bool authenticateClosedPath(mlir::ModuleOp module, const std::string &label,
                            bool expectWholeModule) {
  auto verifyGate = [&](Operation *op, bool recursive, bool requireSuccess) {
    unsigned diagnostics = 0;
    bool topologyOnly = true;
    ScopedDiagnosticHandler capture(op->getContext(), [&](Diagnostic &diagnostic) {
      ++diagnostics;
      std::string message = diagnostic.str();
      topologyOnly &= diagnostic.getSeverity() == DiagnosticSeverity::Error &&
          (StringRef(message).ends_with("NODAL-SHAPE-043-009: scalar endpoint relation has unproved physical alias identity") ||
           StringRef(message).ends_with("NODAL-SHAPE-043-009: scalar endpoint relation proof resource limit exceeded"));
      return success();
    });
    bool valid = succeeded(verify(op, recursive));
    return requireSuccess ? valid && diagnostics == 0 :
                            !valid && diagnostics != 0 && topologyOnly;
  };
  bool gate = verifyGate(module, true, expectWholeModule);
  check(gate, label + (expectWholeModule ? "/whole-module-verified" :
                                          "/whole-module-topology-refusal"));
  bool authentic = true;
  module.walk([&](Operation *op) {
    bool topologyOwner = llvm::isa<nodal::ModuleOp, nodal::GenerateOp>(op);
    // Independently visit every original operation. A Module's early global
    // refusal must not conceal malformed operations later in its body.
    bool local = verifyGate(op, false, true);
    if (!local && topologyOwner && !expectWholeModule)
      local = verifyGate(op, false, false);
    authentic &= local;
    if (topologyOwner)
      authentic &= succeeded(nodal::resolveConservativeStructuralScope(op));
    if (llvm::isa<nodal::GenerateOp>(op))
      authentic &= succeeded(nodal::resolveGeneratedDomain(op));
    if (llvm::isa<nodal::ShapeIndexOp>(op)) {
      authentic &= succeeded(nodal::resolveStaticShapeProjection(op->getResult(0), op));
      if (llvm::isa<nodal::TerminalType>(op->getResult(0).getType()))
        authentic &= succeeded(nodal::resolveConservativeEndpoint(op->getResult(0), op));
    }
    if (llvm::isa<nodal::ConnectOp>(op))
      for (Value operand : op->getOperands())
        authentic &= succeeded(nodal::resolveConservativeEndpoint(operand, op));
  });
  check(authentic, label + "/authenticated-original-operations-and-endpoints");
  return gate && authentic;
}
void closedPathControls(MLIRContext &context) {
  ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
  for (const auto &kind : {"direct", "chain", "pendant", "child", "parallel", "cycle",
                           "foreign-scope", "anchor", "uncertain-child", "shift", "fresh-array", "budget"}) {
    auto module = parseSourceString<mlir::ModuleOp>(closedPathModel(kind), ParserConfig(&context, false));
    std::string label = std::string("closed-path/") + kind;
    check(bool(module), label + "/parse");
    if (!module) continue;
    std::string before = print(*module);
    bool accepted = StringRef(kind) == "direct" || StringRef(kind) == "chain" ||
                    StringRef(kind) == "pendant" || StringRef(kind) == "child";
    if (!authenticateClosedPath(*module, label, accepted || StringRef(kind) == "anchor")) continue;
    if (StringRef(kind) == "child" || StringRef(kind) == "uncertain-child") {
      auto childA = operation(*module, "child_a"), childB = operation(*module, "child_b");
      auto a = nodal::resolveConservativeEndpoint(childA->getResult(0), childA);
      auto b = nodal::resolveConservativeEndpoint(childB->getResult(0), childB);
      check(succeeded(a) && succeeded(b) && a->instance == b->instance && a->port != b->port,
            label + "/authentic-independent-port-identities");
      if (failed(a) || failed(b)) continue;
      ++topologyCalls;
      auto group = nodal::compareConservativeEndpoints(a->port->getResult(0), b->port->getResult(0),
                                                     &a->port->getBlock()->back());
      check(group == (StringRef(kind) == "child" ? A::Distinct : A::Equal),
            label + "/actual-child-boundary-groups");
    }
    std::string destination = StringRef(kind) == "chain" ? "q2" : StringRef(kind) == "budget" ? "q7" : "n";
    A expected = accepted ? A::Distinct : StringRef(kind) == "anchor" ? A::Equal :
                 StringRef(kind) == "budget" ? A::ResourceLimit : A::Unknown;
    for (auto tuples : {std::pair{"00", "01"}, std::pair{"01", "11"}, std::pair{"00", "11"}}) {
      std::string from = "p" + std::string(tuples.first), to = destination + tuples.second;
      alias(*module, from, to, expected, label + "/" + from + "-" + to);
      alias(*module, to, from, expected, label + "/reverse-" + to + "-" + from);
    }
    if (accepted || StringRef(kind) == "anchor")
      alias(*module, "p01", destination + "01", A::Equal, label + "/same-tuple-equality-preserved");
    check(before == print(*module), label + "/transactional-query-IR");
  }
  for (auto fault : {"repeat-index", "swap-indices", "destination-missing-axis"}) {
    auto module = parseSourceString<mlir::ModuleOp>(model(false, false, fault), ParserConfig(&context, false));
    std::string label = std::string("closed-path/") + fault;
    check(bool(module), label + "/parse");
    if (!module) continue;
    std::string before = print(*module);
    if (!authenticateClosedPath(*module, label, false)) continue;
    alias(*module, "p00", "n11", A::Unknown, label + "/no-false-cross-root-distinct");
    alias(*module, "n11", "p00", A::Unknown, label + "/reverse-no-false-cross-root-distinct");
    check(before == print(*module), label + "/transactional-query-IR");
  }
}

void literalGroundwork(MLIRContext &context) {
  llvm::outs() << "LITERAL_GROUNDWORK_SCOPE symbolic_two_axis=blocked target_handoff=pending "
                  "cache_identity=source-reviewed freshness=public-boundary-controls\n";
  positive(context, false, false);
  positive(context, false, true);
  positive(context, false, false, "", 46341, 46341);
  for (const auto &fault : {"missing-axis", "destination-missing-axis", "repeat-index",
                            "swap-indices", "nonunit", "expr-step", "expr-upper",
                            "outer-local", "third-axis"})
    negative(context, fault, false);
  predecessors(context);
  contextFreshness(context);
  closedPathControls(context);
}

} // namespace
int main(int argc, char **argv) {
  if (argc != 1 && !(argc == 2 && StringRef(argv[1]) == "--literal-groundwork")) {
    llvm::errs() << "usage: nested-proof [--literal-groundwork]\n";
    return 2;
  }
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  if (argc == 1) {
  for (bool symbolic : {false, true})
    for (bool anchor : {false, true}) positive(context, symbolic, anchor);
  positive(context, true, false, "same-bound");
  // Semantic signed64 ownership is intentionally not narrowed to target signed32.
  positive(context, false, false, "", 46341, 46341);
  for (const auto &fault : {"missing-axis", "repeat-index", "swap-indices", "nonunit", "expr-step", "outer-local", "third-axis"})
    negative(context, fault, false);
  for (const auto &fault : {"swap-bounds", "repeat-bound", "foreign-bound", "expr-upper", "local-bound"})
    negative(context, fault, true);
  predecessors(context);
  } else {
    literalGroundwork(context);
  }
  llvm::outs() << "NESTED_CONSERVATIVE_SUMMARY checks=" << checks << " failures=" << failures
               << " mapper_calls=" << mapperCalls << " budget_queries=" << budgetCalls
               << " image_calls=" << imageCalls
               << " topology_calls=" << topologyCalls << " mapper_allowance=512 mapper_states=64"
               << " topology_work_limit=16384 topology_states=1024 target_handoff=pending\n";
  return failures ? 1 : 0;
}
