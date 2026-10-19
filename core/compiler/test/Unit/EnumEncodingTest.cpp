#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/APInt.h"
#include "llvm/ADT/SmallString.h"
#include "llvm/Support/raw_ostream.h"

#include <functional>
#include <optional>
#include <string>

namespace {

std::string enumCase(llvm::StringRef name, llvm::StringRef value, llvm::StringRef carrier = "i64") {
  return "\"nodal.enum_case\"() <{metadata = {}, sym_name = \"" + name.str() +
         "\", value = " + value.str() + " : " + carrier.str() + "}> : () -> ()\n";
}

std::string enumModel(llvm::StringRef body, llvm::StringRef encoding = "sequential",
                      llvm::StringRef underlying = "!nodal.uint<2>") {
  return "module { \"nodal.enum\"() <{metadata = {}, sym_name = \"State\", encoding = \"" +
         encoding.str() + "\", underlying_type = " + underlying.str() + "}> ({\n" + body.str() +
         "}) : () -> () }";
}

using Mutation = std::function<void(nodal::EnumOp)>;

bool check(mlir::MLIRContext &context, llvm::StringRef name, llvm::StringRef source,
           bool expectedValid, llvm::StringRef expectedDiagnostic = {}, Mutation mutate = {},
           std::optional<bool> expectedCaseValid = std::nullopt) {
  std::string diagnostic;
  mlir::ScopedDiagnosticHandler capture(&context, [&](mlir::Diagnostic &message) {
    llvm::raw_string_ostream stream(diagnostic);
    message.print(stream);
    return mlir::success();
  });
  auto fail = [&](llvm::StringRef reason) {
    llvm::errs() << "EnumEncodingTest: " << name << ": " << reason << '\n' << diagnostic << '\n';
    return false;
  };

  // Direct custom-verifier calls deliberately bypass ODS and recursive checks.
  auto parsed =
      mlir::parseSourceString<mlir::ModuleOp>(source, mlir::ParserConfig(&context, false));
  if (!parsed)
    return fail("fixture did not parse");
  auto definition = llvm::dyn_cast<nodal::EnumOp>(parsed->getBody()->front());
  if (!definition)
    return fail("fixture has no enum definition");
  if (mutate)
    mutate(definition);
  diagnostic.clear();
  if (mlir::succeeded(definition.verify()) != expectedValid)
    return fail("direct enum verifier returned the wrong validity");
  if (!expectedDiagnostic.empty() && !llvm::StringRef(diagnostic).contains(expectedDiagnostic))
    return fail("direct enum verifier omitted the expected diagnostic");
  if (expectedCaseValid) {
    auto firstCase = llvm::dyn_cast<nodal::EnumCaseOp>(definition->getRegion(0).front().front());
    if (!firstCase)
      return fail("fixture has no first case");
    diagnostic.clear();
    if (mlir::succeeded(firstCase.verify()) != *expectedCaseValid)
      return fail("direct case verifier returned the wrong validity");
  }
  diagnostic.clear();
  if (mlir::succeeded(mlir::verify(*parsed)) != expectedValid)
    return fail("recursive verifier returned the wrong validity");
  llvm::outs() << "ENUM_ENCODING_TEST_PASS " << name << '\n';
  return true;
}

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  unsigned cases = 0;
  unsigned failures = 0;
  auto expect = [&](llvm::StringRef name, llvm::StringRef source, bool valid,
                    llvm::StringRef diagnostic = {}, Mutation mutate = {},
                    std::optional<bool> caseValid = std::nullopt) {
    ++cases;
    failures += !check(context, name, source, valid, diagnostic, mutate, caseValid);
  };
  const std::string sequential = enumCase("Idle", "0") + enumCase("Run", "1");
  const std::string gray = sequential + enumCase("Wait", "3") + enumCase("Done", "2");
  const std::string oneHot =
      enumCase("Idle", "1") + enumCase("Run", "2") + enumCase("Wait", "4") + enumCase("Done", "8");

  expect("F19-padded-sequential-width", enumModel(sequential), true, {}, {}, true);
  expect("minimum-sequential-width", enumModel(sequential, "sequential", "!nodal.uint<1>"), true);
  expect("singleton-sequential", enumModel(enumCase("Only", "0"), "sequential", "!nodal.uint<1>"),
         true);
  expect("singleton-gray", enumModel(enumCase("Only", "0"), "gray", "!nodal.uint<1>"), true);
  expect("singleton-one-hot", enumModel(enumCase("Only", "1", "ui1"), "one_hot", "!nodal.uint<1>"),
         true, {}, {}, true);
  expect("singleton-custom", enumModel(enumCase("Only", "1"), "custom", "!nodal.uint<1>"), true);
  expect("gray-declaration-order", enumModel(gray, "gray"), true);
  expect("one-hot-declaration-order", enumModel(oneHot, "one_hot", "!nodal.uint<4>"), true);
  expect("padded-one-hot", enumModel(oneHot, "one_hot", "!nodal.uint<16>"), true);
  expect("custom-sparse-order",
         enumModel(enumCase("Load", "3") + enumCase("Jump", "111") + enumCase("Store", "35"),
                   "custom", "!nodal.uint<7>"),
         true);
  expect("custom-unsigned-high-bit",
         enumModel(enumCase("High", "255", "ui8"), "custom", "!nodal.uint<8>"), true, {}, {}, true);
  expect("custom-beyond-host-integer",
         enumModel(enumCase("High", "18446744073709551616", "i66"), "custom", "!nodal.uint<65>"),
         true);
  expect("custom-full-128-bit-code",
         enumModel(enumCase("High", "340282366920938463463374607431768211455", "ui128"), "custom",
                   "!nodal.uint<128>"),
         true);
  expect("width-does-not-wrap-to-unsigned",
         enumModel(enumCase("High", "3"), "custom", "!nodal.uint<4294967297>"), true);
  expect("width-does-not-allocate-storage",
         enumModel(enumCase("High", "3"), "custom", "!nodal.uint<9223372036854775807>"), true);

  std::string wideOneHot;
  for (unsigned index = 0; index < 65; ++index) {
    llvm::SmallString<32> value;
    llvm::APInt::getOneBitSet(65, index).toString(value, 10, false);
    wideOneHot += enumCase("Case" + std::to_string(index), value, "ui65");
  }
  expect("one-hot-crosses-host-shift-boundary", enumModel(wideOneHot, "one_hot", "!nodal.uint<65>"),
         true);
  std::string manyCases;
  for (unsigned index = 0; index < 1024; ++index)
    manyCases += enumCase("Case" + std::to_string(index), std::to_string(index));
  expect("representative-1024-case-inventory",
         enumModel(manyCases, "sequential", "!nodal.uint<10>"), true);

  expect("empty-inventory", enumModel("^bb0:\n"), false, "at least one enum case");
  expect("signed-underlying", enumModel(sequential, "sequential", "!nodal.sint<2>"), false,
         "underlying_type must be !nodal.uint", {}, false);
  expect("signless-underlying", enumModel(sequential, "sequential", "!nodal.bits<2>"), false,
         "underlying_type must be !nodal.uint");
  expect("builtin-underlying", enumModel(sequential, "sequential", "ui2"), false,
         "underlying_type must be !nodal.uint");
  expect("enum-underlying", enumModel(sequential, "sequential", "!nodal.enum<\"Other\", 2>"), false,
         "underlying_type must be !nodal.uint");
  expect("unknown-policy", enumModel(sequential, "compact"), false, "unsupported enum encoding");
  expect("empty-case-name", enumModel(enumCase("", "0")), false,
         "enum case symbol must not be empty", {}, false);
  expect("whitespace-case-name", enumModel(enumCase("   ", "0")), false,
         "enum case symbol must not be empty", {}, false);
  expect("duplicate-case-names", enumModel(enumCase("Same", "0") + enumCase("Same", "1")), false,
         "duplicates an existing enum case name");
  expect("duplicate-values", enumModel(enumCase("First", "0") + enumCase("Second", "0")), false,
         "duplicates an existing enum value");
  expect("duplicate-values-across-carriers",
         enumModel(enumCase("First", "1", "i64") + enumCase("Second", "1", "ui1"), "custom"), false,
         "duplicates an existing enum value");
  expect("negative-signless-value", enumModel(enumCase("Negative", "-1"), "custom"), false,
         "does not fit unsigned enum width", {}, false);
  expect("negative-signed-value", enumModel(enumCase("Negative", "-1", "si8"), "custom"), false,
         "does not fit unsigned enum width", {}, false);
  expect("value-outside-width", enumModel(enumCase("High", "4"), "custom"), false,
         "does not fit unsigned enum width", {}, false);
  expect("wide-value-outside-width",
         enumModel(enumCase("High", "18446744073709551616", "ui65"), "custom", "!nodal.uint<64>"),
         false, "does not fit unsigned enum width");
  expect("one-hot-inventory-exceeds-width", enumModel(oneHot, "one_hot", "!nodal.uint<3>"), false,
         "does not fit unsigned enum width");
  expect("sequential-reordered", enumModel(enumCase("First", "1") + enumCase("Second", "0")), false,
         "sequential code does not match its declaration index");
  expect("sequential-skipped", enumModel(enumCase("First", "0") + enumCase("Second", "2")), false,
         "sequential code does not match its declaration index");
  expect("sequential-relabeled-one-hot", enumModel(sequential, "one_hot"), false,
         "one_hot code must equal");
  expect("one-hot-multiple-bits",
         enumModel(enumCase("First", "1") + enumCase("Second", "3"), "one_hot"), false,
         "one_hot code must equal");
  expect("one-hot-skipped-bit",
         enumModel(enumCase("First", "1") + enumCase("Second", "4"), "one_hot", "!nodal.uint<3>"),
         false, "one_hot code must equal");
  expect("one-hot-reordered",
         enumModel(enumCase("First", "2") + enumCase("Second", "1"), "one_hot"), false,
         "one_hot code must equal");
  expect("gray-relabeled-sequential", enumModel(gray, "sequential"), false,
         "sequential code does not match its declaration index");
  expect("gray-noncanonical-neighbors",
         enumModel(enumCase("First", "0") + enumCase("Second", "2") + enumCase("Third", "3") +
                       enumCase("Fourth", "1"),
                   "gray"),
         false, "gray code does not match its declaration index");
  expect("gray-nonadjacent-codes",
         enumModel(sequential + enumCase("Third", "2") + enumCase("Fourth", "3"), "gray"), false,
         "gray code does not match its declaration index");
  expect("non-case-body-operation",
         enumModel(sequential + "\"nodal.placeholder\"() <{label = \"stray\"}> : () -> ()"), false,
         "enum body accepts only enum_case operations");

  const std::string valid = enumModel(sequential);
  expect(
      "missing-underlying", valid, false, "underlying_type must be !nodal.uint",
      [](nodal::EnumOp op) { op->removeAttr("underlying_type"); }, false);
  expect("missing-encoding", valid, false, "unsupported enum encoding",
         [](nodal::EnumOp op) { op->removeAttr("encoding"); });
  expect("empty-enum-symbol", valid, false, "enum symbol must not be empty", [](nodal::EnumOp op) {
    op->setAttr("sym_name", mlir::StringAttr::get(op.getContext(), ""));
  });
  expect(
      "missing-case-value", valid, false, "requires an integer value",
      [](nodal::EnumOp op) { op->getRegion(0).front().front().removeAttr("value"); }, false);
  expect(
      "non-integer-case-value", valid, false, "requires an integer value",
      [](nodal::EnumOp op) {
        op->getRegion(0).front().front().setAttr("value",
                                                 mlir::StringAttr::get(op.getContext(), "0"));
      },
      false);
  expect(
      "missing-case-name", valid, false, "enum case symbol must not be empty",
      [](nodal::EnumOp op) { op->getRegion(0).front().front().removeAttr("sym_name"); }, false);
  expect("multiple-body-blocks", valid, false, "exactly one body block",
         [](nodal::EnumOp op) { op->getRegion(0).emplaceBlock(); });
  expect("missing-body-block", valid, false, "exactly one body block",
         [](nodal::EnumOp op) { op->getRegion(0).getBlocks().clear(); });

  // The type parser rejects zero-width storage even for a singleton enum.
  {
    mlir::ScopedDiagnosticHandler silence(&context,
                                          [](mlir::Diagnostic &) { return mlir::success(); });
    ++cases;
    if (mlir::parseSourceString<mlir::ModuleOp>(
            enumModel(enumCase("Only", "0"), "sequential", "!nodal.uint<0>"), &context)) {
      llvm::errs() << "EnumEncodingTest: zero-width singleton enum was accepted\n";
      ++failures;
    }
  }

  // An orphaned case must also fail when its verifier is called directly.
  {
    auto parsed = mlir::parseSourceString<mlir::ModuleOp>(valid, &context);
    if (!parsed)
      return 1;
    auto definition = llvm::cast<nodal::EnumOp>(parsed->getBody()->front());
    auto firstCase = llvm::cast<nodal::EnumCaseOp>(definition->getRegion(0).front().front());
    firstCase->moveBefore(definition);
    mlir::ScopedDiagnosticHandler silence(&context,
                                          [](mlir::Diagnostic &) { return mlir::success(); });
    ++cases;
    if (mlir::succeeded(firstCase.verify()) || mlir::succeeded(mlir::verify(*parsed))) {
      llvm::errs() << "EnumEncodingTest: orphaned enum case was accepted\n";
      ++failures;
    }
  }

  llvm::outs() << "EnumEncodingTest: " << cases << " cases, " << failures << " failures\n";
  return failures ? 1 : 0;
}
