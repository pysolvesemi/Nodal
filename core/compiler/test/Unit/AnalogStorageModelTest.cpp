#include "nodal/Dialect/Nodal/AnalogStorageModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/raw_ostream.h"

#include <limits>
#include <string>
#include <vector>

namespace {
const std::string scalar = "!nodal.variable<\"real\", \"1\">";
std::string shape(const std::string &dimensions) {
  return "!nodal.shaped<\"" + dimensions + "\", " + scalar + ">";
}
std::string metadata(const std::string &name, const std::string &input) {
  return "metadata = {semantic_path = \"T." + name + "\", input_path = \"T." + input +
         "\", storage = \"procedural\", access = \"read_write\"}, owner = \"T\"";
}
std::string index(const std::string &name, const std::string &input, const std::string &dimensions,
                  const std::vector<int> &indices, const std::string &result = "") {
  std::string operands = "%" + input, types = shape(dimensions);
  for (int value : indices) {
    operands += ", %c" + std::to_string(value);
    types += ", i64";
  }
  return "%" + name + " = \"nodal.shape_index\"(" + operands + ") <{" + metadata(name, input) +
         "}> : (" + types + ") -> " + (result.empty() ? scalar : shape(result)) + "\n";
}
std::string view(const std::string &name, const std::string &input, const std::string &from,
                 const std::string &to) {
  return "%" + name + " = \"nodal.shape_view\"(%" + input + ") <{" + metadata(name, input) +
         ", dimensions = \"" + to + "\", origin = \"T." + input +
         "\", materialization = \"view\", observability = \"source_mapped\"}> : (" + shape(from) +
         ") -> " + shape(to) + "\n";
}
std::string slice(const std::string &name, int upper, const std::string &result) {
  return "%" + name + " = \"nodal.shape_slice\"(%a, %c0, %c" + std::to_string(upper) + ", %c" +
         std::to_string(upper) + ") <{" + metadata(name, "a") +
         ", axis = 1 : i64, dimensions = \"" + result +
         "\", origin = \"T.a\", observability = \"source_mapped\"}> : (" + shape("2,3") +
         ", i64, i64, i64) -> " + shape(result) + "\n";
}
std::string variable(const std::string &name, const std::string &dimensions, int order) {
  return "%" + name +
         " = \"nodal.analog_variable\"() <{declaration_order = " + std::to_string(order) +
         " : i64, identity = \"T." + name +
         "\", initialized = false, initializer_dimension = \"\", initializer_kind = \"\", "
         "initializer_reads = [], initializer_value = \"\", metadata = {semantic_path = \"T." +
         name + "\"}, owner = \"T\"}> : () -> " + shape(dimensions) + "\n";
}
} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  mlir::ScopedDiagnosticHandler diagnostics(&context,
                                            [](mlir::Diagnostic &) { return mlir::success(); });
  std::string source = "module {\n\"nodal.module\"() <{metadata = {semantic_path = \"T\"}, "
                       "sym_name = \"T\"}> ({\n";
  for (int value = 0; value <= 3; ++value)
    source += "%c" + std::to_string(value) +
              " = \"nodal.const_literal\"() <{metadata = {}, "
              "spelling = \"" +
              std::to_string(value) + "\", value = " + std::to_string(value) +
              " : i64}> : () -> i64\n";
  source += R"mlir(
"nodal.parameter"() <{classification = "structural", default_value = 2 : i64,
metadata = {semantic_path = "T.COLS"}, parameter_kind = "integer", sym_name = "COLS",
type = i64, variability = "symbolic"}> : () -> ()
"nodal.parameter_constraint"(%c2, %c3) <{constraint_kind = "range", lower_inclusive = true,
upper_inclusive = true, metadata = {}, parameter = @COLS}> : (i64, i64) -> ()
"nodal.parameter_envelope"() <{effects = ["shape"], metadata = {}, parameter = @COLS,
policy = "static_generate"}> : () -> ()
)mlir";
  source += "\"nodal.analog\"() <{metadata = {}}> ({\n"
            "\"nodal.analog_procedure\"() <{metadata = {}, owner = \"T\"}> ({\n";
  source += variable("a", "2,3", 0);
  source += index("row", "a", "2,3", {1}, "3");
  source += index("row_again", "a", "2,3", {1}, "3");
  source += index("row_element", "row", "3", {2});
  source += index("direct", "a", "2,3", {1, 2});
  source += index("first_tuple", "a", "2,3", {1, 0});
  source += index("sibling", "a", "2,3", {0, 2});
  source += view("reshape", "a", "2,3", "3,2");
  source += index("second_tuple", "reshape", "3,2", {1, 0});
  source += slice("partial", 2, "2,2");
  source += view("partial_flat", "partial", "2,2", "4");
  source += index("partial_element", "partial_flat", "4", {1});
  source += slice("full", 3, "2,3");
  source += variable("one", "1,3", 1);
  source += index("one_row", "one", "1,3", {0}, "3");
  source += "\"nodal.analog_scope\"() <{metadata = {}, owner = \"T\", scope_id = \"inner\"}> ({\n";
  source += variable("inner", "2", 2);
  source += "}) : () -> ()\n";
  source += variable("symbolic", "2,COLS", 6);
  source += index("symbolic_row", "symbolic", "2,COLS", {1}, "COLS");
  source += index("symbolic_other", "symbolic", "2,COLS", {0}, "COLS");
  source += "%symbolic_slice = \"nodal.shape_slice\"(%symbolic, %c1, %c2, %c1) <{" +
            metadata("symbolic_slice", "symbolic") +
            ", axis = 0 : i64, dimensions = \"1,COLS\", origin = \"T.symbolic\", "
            "observability = \"source_mapped\"}> : (" +
            shape("2,COLS") + ", i64, i64, i64) -> " + shape("1,COLS") + "\n";
  source += view("symbolic_flat", "symbolic_slice", "1,COLS", "COLS");
  source += variable("huge", "1000000,1000000", 3);
  source += variable("maximum", "9223372036854775807", 4);
  source += variable("overflow", "3037000500,3037000500", 5);
  source += "%use = \"nodal.analog_variable_read\"(%a) <{metadata = {}, owner = \"T\", "
            "read_id = \"T.use\"}> : (" +
            shape("2,3") + ") -> !nodal.shaped<\"2,3\", !nodal.quantity<\"real\", \"1\">>\n";
  source += "}) : () -> ()\n}) : () -> ()\n}) : () -> ()\n}\n";
  // This unit tests the shared descriptor, independently of initialization
  // analysis. Its intentionally uninitialized roots are not a valid program.
  mlir::ParserConfig config(&context, false);
  auto module = mlir::parseSourceString<mlir::ModuleOp>(source, config);
  if (!module) {
    llvm::errs() << "storage descriptor fixture did not parse\n";
    return 1;
  }
  auto find = [&](llvm::StringRef name) -> mlir::Operation * {
    mlir::Operation *found = nullptr;
    module->walk([&](mlir::Operation *operation) {
      auto meta = operation->getAttrOfType<mlir::DictionaryAttr>("metadata");
      auto path = meta ? meta.getAs<mlir::StringAttr>("semantic_path") : mlir::StringAttr();
      if (path && path.getValue() == ("T." + name).str())
        found = operation;
      auto id = operation->getAttrOfType<mlir::StringAttr>("read_id");
      if (id && id.getValue() == ("T." + name).str())
        found = operation;
    });
    return found;
  };
  auto *use = find("use");
  unsigned checks = 0, failures = 0;
  auto expect = [&](bool condition, llvm::StringRef label) {
    ++checks;
    if (!condition) {
      ++failures;
      llvm::errs() << "FAIL: " << label << '\n';
    }
  };
  auto resolve = [&](llvm::StringRef name) {
    auto *operation = find(name);
    return operation ? nodal::resolveAnalogStorageReference(operation->getResult(0), use)
                     : mlir::FailureOr<nodal::AnalogStorageReference>(mlir::failure());
  };
  auto root = resolve("a"), row = resolve("row"), rowAgain = resolve("row_again");
  auto direct = resolve("direct"), viaRow = resolve("row_element");
  auto first = resolve("first_tuple"), second = resolve("second_tuple");
  auto partial = resolve("partial"), partialFlat = resolve("partial_flat");
  auto partialElement = resolve("partial_element"), sibling = resolve("sibling");
  auto full = resolve("full"), oneRow = resolve("one_row");
  bool all = mlir::succeeded(root) && mlir::succeeded(row) && mlir::succeeded(rowAgain) &&
             mlir::succeeded(direct) && mlir::succeeded(viaRow) && mlir::succeeded(first) &&
             mlir::succeeded(second) && mlir::succeeded(partial) && mlir::succeeded(partialFlat) &&
             mlir::succeeded(partialElement) && mlir::succeeded(sibling) && mlir::succeeded(full) &&
             mlir::succeeded(oneRow);
  expect(all, "all legal descriptor chains resolve");
  if (all) {
    expect(root->wholeRoot && root->exactFixedCardinality == 6,
           "root cardinality and whole coverage");
    expect(row->canonicalKey == rowAgain->canonicalKey,
           "source occurrences do not change region identity");
    expect(direct->canonicalKey == viaRow->canonicalKey && direct->literalRootFlatOffset == 5,
           "composed and direct scalar offsets share identity");
    expect(first->canonicalKey != second->canonicalKey && first->literalRootFlatOffset == 3 &&
               second->literalRootFlatOffset == 2,
           "reshape changes tuple interpretation");
    expect(partial->canonicalKey == partialFlat->canonicalKey, "reshape preserves selected region");
    expect(nodal::analogStorageReferenceCovers(*partial, *partialElement),
           "validated descendant is covered");
    expect(!nodal::analogStorageReferenceCovers(*partial, *sibling),
           "noncontiguous sibling is not covered");
    expect(nodal::analogStorageReferenceCovers(*row, *direct),
           "constant contiguous row covers direct element");
    expect(!nodal::analogStorageReferenceCovers(*row, *sibling), "row does not cover sibling row");
    expect(full->wholeRoot && oneRow->wholeRoot,
           "full slice and singleton prefix preserve whole coverage");
    expect(!nodal::analogStorageReferenceCovers(*root, *oneRow),
           "different roots never share coverage");
  }
  auto symbolicRow = resolve("symbolic_row"), symbolicFlat = resolve("symbolic_flat");
  auto symbolicOther = resolve("symbolic_other");
  expect(mlir::succeeded(symbolicRow) && mlir::succeeded(symbolicFlat) &&
             symbolicRow->canonicalKey == symbolicFlat->canonicalKey &&
             nodal::analogStorageReferenceCovers(*symbolicRow, *symbolicFlat) &&
             nodal::analogStorageReferenceCovers(*symbolicFlat, *symbolicRow),
         "symbolic row and flattened leading slice share physical coverage");
  expect(mlir::succeeded(symbolicRow) && mlir::succeeded(symbolicOther) &&
             !nodal::analogStorageReferenceCovers(*symbolicRow, *symbolicOther),
         "symbolic sibling rows remain distinct");
  auto huge = resolve("huge"), maximum = resolve("maximum");
  expect(mlir::succeeded(huge) && huge->exactFixedCardinality == 1000000000000LL &&
             huge->projections.empty(),
         "trillion-lane root remains one descriptor");
  expect(mlir::succeeded(maximum) &&
             maximum->exactFixedCardinality == std::numeric_limits<int64_t>::max(),
         "signed64 maximum cardinality is accepted semantically");
  expect(mlir::failed(resolve("overflow")), "signed64 product overflow rejects");
  expect(mlir::failed(resolve("inner")), "nested lexical storage cannot escape");
  auto *a = find("a");
  auto self = nodal::resolveAnalogStorageReference(a->getResult(0), a);
  expect(mlir::succeeded(self), "declaration initializer may target its own storage");
  use->moveBefore(a);
  expect(mlir::failed(resolve("a")), "use before declaration rejects");
  use->moveAfter(find("one_row"));
  auto *rowOp = find("row");
  auto owner = rowOp->getAttr("owner");
  rowOp->setAttr("owner", mlir::StringAttr::get(&context, "Foreign"));
  expect(mlir::failed(resolve("row")), "forged projection owner rejects");
  rowOp->setAttr("owner", owner);
  llvm::outs() << checks << " storage descriptor checks, " << failures << " failures\n";
  return failures ? 1 : 0;
}
