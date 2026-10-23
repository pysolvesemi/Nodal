#include "circt/Dialect/Comb/CombDialect.h"
#include "circt/Dialect/Comb/CombOps.h"
#include "circt/Dialect/HW/HWAttributes.h"
#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/Builders.h"
#include "mlir/IR/Matchers.h"
#include "llvm/Support/raw_ostream.h"
#include "llvm/Support/KnownBits.h"
using namespace mlir;
using namespace circt;
int main() {
  MLIRContext context;
  context.loadDialect<hw::HWDialect, comb::CombDialect>();
  OpBuilder b(&context);
  Block block;
  b.setInsertionPointToEnd(&block);
  auto loc = b.getUnknownLoc();
  auto width = hw::ParamDeclRefAttr::get(b.getStringAttr("W"), b.getI32Type());
  auto padWidth = hw::ParamExprAttr::get(hw::PEO::Add, {
      hw::ParamExprAttr::get(hw::PEO::Mul, {width, b.getI32IntegerAttr(-1)}),
      b.getI32IntegerAttr(9)});
  auto data = block.addArgument(hw::IntType::get(width), loc);
  auto padding = block.addArgument(hw::IntType::get(padWidth), loc);
  auto one = block.addArgument(b.getI1Type(), loc);
  unsigned checks = 0, failures = 0;
  auto check = [&](bool valid, const char *name) {
    ++checks; failures += !valid;
    llvm::outs() << (valid ? "PASS " : "FAIL ") << name << "\n";
  };
  auto variable = comb::ConcatOp::create(b, loc, data, ValueRange{one});
  check(variable.getType() == hw::IntType::get(hw::ParamExprAttr::get(
      hw::PEO::Add, {width, b.getI32IntegerAttr(1)})), "head-tail builder retains canonical symbolic sum");
  auto fixed = comb::ConcatOp::create(b, loc, ValueRange{padding, data});
  check(fixed.getType() == b.getIntegerType(9), "variadic builder proves exact constant sum");
  Value captured;
  check(!matchPattern(fixed.getResult(), comb::m_SextBy(matchers::m_Any(&captured))),
        "sign-extension matcher does not infer absent bit positions");
  auto known = comb::computeKnownBits(fixed.getResult());
  check(known.getBitWidth() == 9 && known.Zero.isZero() && known.One.isZero(),
        "known-bits analysis retains unknown symbolic bit positions");
  bool called = false;
  fixed.inferResultRanges({}, [&](Value, const ConstantIntRanges &) { called = true; });
  check(!called, "range analysis supplies no symbolic operand range");
  auto single = comb::ConcatOp::create(b, loc, ValueRange{data});
  check(single.getType() == data.getType(), "single operand preserves exact symbolic identity");
  llvm::outs() << checks << " checks, " << failures << " failures\n";
  return failures != 0;
}
