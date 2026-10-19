#include "nodal/Dialect/Nodal/DigitalLoopPlanningModel.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalMaterializationModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/Support/Signals.h"
#include "llvm/Support/raw_ostream.h"

#include <memory>
#include <string>
#include <vector>
using namespace mlir;
#include "DigitalLoopTestFixtures.h"
namespace {
std::string incrementChain(int n) {
  auto source = chain(n);
  auto old = assign("dst", "src");
  auto replacement =
      "%oneValue = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", metadata = {}}> "
      ": () -> !nodal.uint<8>\n%sum = \"nodal.digital_expr\"(%src, %oneValue) <{operator_name = "
      "\"digital_add\", metadata = {}}> : (!nodal.uint<8>, !nodal.uint<8>) -> "
      "!nodal.uint<9>\n%wrapped = \"nodal.digital_expr\"(%sum) <{operator_name = \"digital_wrap\", "
      "metadata = {}}> : (!nodal.uint<9>) -> !nodal.uint<8>\n" +
      assign("dst", "wrapped");
  return replace(source, old, replacement);
}
struct ValueState {
  std::shared_ptr<std::vector<int64_t>> root;
  int64_t offset = 0, count = 1;
};
class Oracle {
public:
  llvm::DenseMap<Value, ValueState> values;
  std::vector<int64_t> output;
  bool execute(Block &block) {
    for (Operation &op : block) {
      StringRef name = op.getName().getStringRef();
      if (llvm::isa<nodal::PortOp>(op))
        continue;
      if (llvm::isa<nodal::PortValueOp, nodal::DigitalStorageOp>(op)) {
        int64_t count = 1;
        if (auto shape = llvm::dyn_cast<nodal::ShapedType>(op.getResult(0).getType()))
          if (shape.getDimensions().getAsInteger(10, count) || count < 1)
            return false;
        auto root = std::make_shared<std::vector<int64_t>>(count, 0);
        auto port = op.getAttrOfType<FlatSymbolRefAttr>("port");
        if (port && port.getValue() == "seed")
          (*root)[0] = 254;
        values[op.getResult(0)] = {root, 0, count};
        continue;
      }
      if (llvm::isa<nodal::ConstLiteralOp, nodal::ConstantOp>(op)) {
        auto value = op.getAttrOfType<IntegerAttr>("value");
        if (!value)
          return false;
        values[op.getResult(0)] = {std::make_shared<std::vector<int64_t>>(1, value.getInt()), 0, 1};
        continue;
      }
      auto scalar = [&](Value value) {
        auto state = values.lookup(value);
        return state.root ? (*state.root)[state.offset] : int64_t(-99999);
      };
      if (llvm::isa<nodal::IterationIndexOp, nodal::ConstExprOp>(op)) {
        auto opcode = op.getAttrOfType<StringAttr>("operator_name").getValue();
        int64_t n = opcode == "add" ? scalar(op.getOperand(0)) + scalar(op.getOperand(1))
                                    : scalar(op.getOperand(0)) - scalar(op.getOperand(1));
        values[op.getResult(0)] = {std::make_shared<std::vector<int64_t>>(1, n), 0, 1};
        continue;
      }
      if (llvm::isa<nodal::ShapeIndexOp>(op)) {
        auto source = values.lookup(op.getOperand(0));
        int64_t i = scalar(op.getOperand(1));
        if (!source.root || i < 0 || i >= source.count)
          return false;
        values[op.getResult(0)] = {source.root, source.offset + i, 1};
        continue;
      }
      if (llvm::isa<nodal::DigitalExprOp>(op)) {
        auto opcode = op.getAttrOfType<StringAttr>("operator_name").getValue();
        int64_t n = scalar(op.getOperand(0));
        if (opcode == "digital_add")
          n += scalar(op.getOperand(1));
        else if (opcode == "digital_wrap")
          n &= 255;
        else
          return false;
        values[op.getResult(0)] = {std::make_shared<std::vector<int64_t>>(1, n), 0, 1};
        continue;
      }
      if (llvm::isa<nodal::DigitalAssignOp>(op)) {
        auto target = values.lookup(op.getOperand(0)), source = values.lookup(op.getOperand(1));
        if (!target.root || !source.root || target.count != source.count)
          return false;
        for (int64_t i = 0; i < target.count; ++i)
          (*target.root)[target.offset + i] = (*source.root)[source.offset + i];
        continue;
      }
      if (nodal::isBoundedDigitalLoop(&op)) {
        auto &body = op.getRegion(0).front();
        for (unsigned i = 1; i < body.getNumArguments(); ++i)
          values[body.getArgument(i)] = values.lookup(op.getOperand(i + 2));
        for (int64_t i = scalar(op.getOperand(0)); i < scalar(op.getOperand(1));
             i += scalar(op.getOperand(2))) {
          values[body.getArgument(0)] = {std::make_shared<std::vector<int64_t>>(1, i), 0, 1};
          if (!execute(body))
            return false;
        }
        continue;
      }
      if (llvm::isa<nodal::HardwareLoopEndOp>(op))
        continue;
      return false;
    }
    for (Operation &op : block)
      if (auto port = llvm::dyn_cast<nodal::PortValueOp>(op))
        if (port->getAttrOfType<FlatSymbolRefAttr>("port").getValue() == "out") {
          auto state = values.lookup(port.getResult());
          output = *state.root;
        }
    return true;
  }
};
} // namespace
int main() {
  llvm::sys::PrintStackTraceOnErrorSignal("loop-plan-test");
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  unsigned cases = 0, failures = 0;
  auto report = [&](StringRef name, bool ok) {
    ++cases;
    failures += !ok;
    (ok ? llvm::outs() : llvm::errs())
        << "DIGITAL_LOOP_PLAN_" << (ok ? "PASS " : "FAIL ") << name << '\n';
  };
  auto parse = [&](std::string body) {
    return parseSourceString<mlir::ModuleOp>(
        "module { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({" + body +
            "}) : () -> () }",
        ParserConfig(&context, false));
  };
  {
    auto root = parse(nestedWideningReduction());
    nodal::ModuleOp module;
    nodal::HardwareLoopOp loop;
    root->walk([&](nodal::ModuleOp op) { module = op; });
    root->walk([&](nodal::HardwareLoopOp op) { loop = op; });
    auto plan = nodal::analyzeDigitalLoopPlans(module);
    auto expanded = nodal::materializeDigitalLoopUnrolled(loop);
    bool valid = succeeded(plan) && succeeded(expanded) && succeeded(verify(*root));
    if (valid) {
      module->setAttr("nodal.digital.loop_plans", *plan);
      valid = succeeded(verify(*root));
    }
    report("nested-widening-scoped-width-unroll", valid);
    if (succeeded(expanded)) {
      nodal::DigitalAssignOp assignment;
      (*expanded)->walk([&](nodal::DigitalAssignOp op) {
        if (!assignment)
          assignment = op;
      });
      assignment->setOperand(1, assignment->getOperand(0));
      ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
      report("expanded-mapped-operand-forgery-rejected", failed(verify(**expanded)));
    } else
      report("expanded-mapped-operand-forgery-rejected", false);
  }
  {
    auto root = parse(nestedWideningReduction());
    Builder b(&context);
    nodal::ModuleOp module;
    root->walk([&](nodal::ModuleOp op) { module = op; });
    unsigned ordinal = 0;
    module->walk([&](Operation *op) {
      auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
      NamedAttrList fields(metadata);
      if (!fields.get("semantic_path"))
        fields.set(
            "semantic_path",
            b.getStringAttr(op == module ? "Fixture" : "Fixture.node" + std::to_string(ordinal++)));
      op->setAttr("metadata", fields.getDictionary(&context));
    });
    root->getOperation()->setAttr(
        "nodal.digital.quality",
        b.getDictionaryAttr({b.getNamedAttr("version", b.getI64IntegerAttr(1)),
                             b.getNamedAttr("temporaries", b.getStringAttr("readable")),
                             b.getNamedAttr("naming", b.getStringAttr("semantic"))}));
    auto plan = nodal::analyzeDigitalMaterialization(module);
    bool valid = succeeded(plan);
    if (valid) {
      module->setAttr("nodal.digital.materialization", *plan);
      valid = succeeded(verify(*root));
    }
    report("scoped-materialization-report-type-recomputed", valid);
    if (valid) {
      NamedAttrList forged(*plan);
      auto entries = plan->getAs<ArrayAttr>("decisions");
      SmallVector<Attribute> values(entries.begin(), entries.end());
      NamedAttrList entry(llvm::cast<DictionaryAttr>(values.front()));
      entry.set("type", TypeAttr::get(
                            nodal::ParametricIntegerType::get(&context, "uint", "Foreign.width")));
      values.front() = entry.getDictionary(&context);
      forged.set("decisions", b.getArrayAttr(values));
      module->setAttr("nodal.digital.materialization", forged.getDictionary(&context));
      ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
      report("forged-scoped-report-type-rejected",
             failed(nodal::verifyDigitalTypeOperation(module)));
      module->removeAttr("nodal.digital.materialization");
      module->setAttr("example.unowned_type", TypeAttr::get(nodal::ParametricIntegerType::get(
                                                  &context, "uint", "Foreign.width")));
      report("arbitrary-typeattr-still-checked", failed(nodal::verifyDigitalTypeOperation(module)));
    } else {
      report("forged-scoped-report-type-rejected", false);
      report("arbitrary-typeattr-still-checked", false);
    }
  }
  for (int n : {0, 1, 2, 8, 9, 32, 33}) {
    auto root = parse(incrementChain(n));
    bool ok = root && succeeded(verify(*root));
    nodal::ModuleOp module;
    if (root)
      root->walk([&](nodal::ModuleOp op) { module = op; });
    auto plan = ok ? nodal::analyzeDigitalLoopPlans(module) : FailureOr<DictionaryAttr>(failure());
    ok &= succeeded(plan);
    if (succeeded(plan)) {
      auto plans = plan->getAs<ArrayAttr>("plans");
      auto entry = llvm::cast<DictionaryAttr>(plans[0]);
      ok &= entry.getAs<StringAttr>("selected").getValue() == (n <= 8 ? "unrolled" : "procedural");
      auto second = nodal::analyzeDigitalLoopPlans(module);
      ok &= succeeded(second) && *second == *plan;
      module->setAttr("nodal.digital.loop_plans", *plan);
      ok &= succeeded(verify(*root));
      if (n <= 8) {
        Operation *loop = nullptr;
        module->walk([&](nodal::HardwareLoopOp op) { loop = op; });
        auto expanded = nodal::materializeDigitalLoopUnrolled(loop);
        Oracle procedural, unrolled;
        bool same = procedural.execute(module->getRegion(0).front()) && succeeded(expanded);
        if (same) {
          nodal::ModuleOp lowered;
          (*expanded)->walk([&](nodal::ModuleOp op) { lowered = op; });
          same = unrolled.execute(lowered->getRegion(0).front()) &&
                 procedural.output == unrolled.output;
          for (unsigned i = 0; same && i < unrolled.output.size(); ++i)
            same = unrolled.output[i] == ((254 + i) & 255);
        }
        ok &= same;
      }
    }
    report("checked-candidates-and-oracle-N" + std::to_string(n), ok);
  }
  {
    auto root = parse(symbolicChain());
    nodal::ModuleOp module;
    root->walk([&](nodal::ModuleOp op) { module = op; });
    auto plan = nodal::analyzeDigitalLoopPlans(module);
    bool ok = succeeded(plan);
    if (ok) {
      auto entry = llvm::cast<DictionaryAttr>(plan->getAs<ArrayAttr>("plans")[0]);
      ok = entry.getAs<StringAttr>("selected").getValue() == "procedural" &&
           entry.getAs<ArrayAttr>("expanded_effects").empty();
    }
    report("symbolic-million-compact-procedural", ok);
  }
  {
    auto root = parse(incrementChain(2));
    nodal::ModuleOp module;
    root->walk([&](nodal::ModuleOp op) { module = op; });
    auto plan = nodal::analyzeDigitalLoopPlans(module);
    bool ok = succeeded(plan);
    if (ok) {
      module->setAttr("nodal.digital.loop_plans", *plan);
      module->walk([&](nodal::DigitalExprOp op) {
        if (op->getAttrOfType<StringAttr>("operator_name").getValue() == "digital_add")
          op->setOperand(1, op->getOperand(0));
      });
      ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
      ok = failed(nodal::verifyDigitalLoopPlanningReport(module));
    }
    report("stale-actual-edge-rejected", ok);
  }
  {
    auto root = parse(incrementChain(1));
    nodal::ModuleOp module;
    root->walk([&](nodal::ModuleOp op) { module = op; });
    Builder b(&context);
    module->setAttr("nodal.digital.loop_plans", b.getDictionaryAttr({}));
    ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
    report("forged-report-rejected", failed(nodal::verifyDigitalLoopPlanningReport(module)));
  }
  {
    auto body = incrementChain(8);
    std::string padding(1100000, 'x');
    body = replace(body, "metadata = {semantic_path = \"Fixture.chain\"}",
                   "metadata = {semantic_path = \"Fixture.chain\", padding = \"" + padding + "\"}");
    auto root = parse(body);
    nodal::ModuleOp module;
    root->walk([&](nodal::ModuleOp op) { module = op; });
    auto plan = nodal::analyzeDigitalLoopPlans(module);
    bool ok = succeeded(plan);
    if (ok) {
      auto entry = llvm::cast<DictionaryAttr>(plan->getAs<ArrayAttr>("plans")[0]);
      ok = entry.getAs<StringAttr>("selected").getValue() == "procedural" &&
           entry.getAs<StringAttr>("choice_reason").getValue() == "expansion-policy";
    }
    report("checked-byte-expansion-fallback", ok);
  }
  {
    auto body = incrementChain(1);
    body = replace(body, "metadata = {semantic_path = \"Fixture.chain\"}",
                   "metadata = {semantic_path = \"Fixture.chain\", padding = \"" +
                       std::string(8500000, 'x') + "\"}");
    auto root = parse(body);
    nodal::ModuleOp module;
    root->walk([&](nodal::ModuleOp op) { module = op; });
    ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
    report("authored-byte-budget-rejected-before-clone",
           failed(nodal::analyzeDigitalLoopPlans(module)));
  }
  {
    auto body = replace(
        incrementChain(1), literal("one", 1),
        "%one = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", metadata = "
        "{semantic_path = \"Fixture.chain.local\", source_file = \"Fixture.chain.scala\", "
        "source_path = \"Fixture.chain.scala\", source_text = \"Fixture.chain.author text\", "
        "source_line = 73 : i64}}> : () -> i64 loc(\"Fixture.chain.scala\":73:9)\n");
    auto root = parse(body);
    Operation *loop = nullptr;
    root->walk([&](nodal::HardwareLoopOp op) { loop = op; });
    auto expanded = nodal::materializeDigitalLoopUnrolled(loop);
    bool ok = succeeded(expanded), found = false;
    if (ok)
      (*expanded)->walk([&](Operation *op) {
        auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
        auto path = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
        if (path && path.getValue() == "Fixture.chain.local.unrolled0") {
          found = true;
          ok &=
              metadata.getAs<StringAttr>("source_file").getValue() == "Fixture.chain.scala" &&
              metadata.getAs<StringAttr>("source_path").getValue() == "Fixture.chain.scala" &&
              metadata.getAs<StringAttr>("source_text").getValue() == "Fixture.chain.author text" &&
              metadata.getAs<IntegerAttr>("source_line").getInt() == 73;
          auto location = llvm::dyn_cast<FileLineColLoc>(op->getLoc());
          ok &= location && location.getFilename().getValue() == "Fixture.chain.scala" &&
                location.getLine() == 73;
        }
      });
    report("matching-prefix-authored-provenance-retained", ok && found);
  }
  {
    auto root = parse(incrementChain(1));
    nodal::ModuleOp module;
    root->walk([&](nodal::ModuleOp op) { module = op; });
    Builder b(&context);
    OperationState state(UnknownLoc::get(&context), "nodal.module");
    state.addAttribute("sym_name", b.getStringAttr("Unrelated"));
    state.addAttribute("metadata", b.getDictionaryAttr({}));
    state.addRegion()->emplaceBlock();
    Operation *unrelated = Operation::create(state);
    root->getBody()->push_back(unrelated);
    auto plan = nodal::analyzeDigitalLoopPlans(module);
    bool ok = succeeded(plan);
    if (ok) {
      module->setAttr("nodal.digital.loop_plans", *plan);
      unrelated->setAttr("example.unrelated_report", b.getStringAttr("changed report"));
      root->getOperation()->setAttr("example.gate_report", b.getStringAttr("later gate result"));
      auto again = nodal::analyzeDigitalLoopPlans(module);
      ok = succeeded(again) && *again == *plan && succeeded(verify(*root));
    }
    report("unrelated-report-mutations-do-not-stale-owner-plan", ok);
  }
  {
    auto root = parse("^bb0:\n");
    nodal::ModuleOp module;
    root->walk([&](nodal::ModuleOp op) { module = op; });
    Builder b(&context);
    for (unsigned i = 0; i < 65537; ++i) {
      OperationState state(UnknownLoc::get(&context), "nodal.const_literal");
      state.addTypes(b.getI64Type());
      state.addAttribute("value", b.getI64IntegerAttr(0));
      state.addAttribute("spelling", b.getStringAttr("0"));
      state.addAttribute("metadata", b.getDictionaryAttr({}));
      module->getRegion(0).front().push_back(Operation::create(state));
    }
    ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
    report("inventory-node-cap-stops-retained-map-growth",
           failed(nodal::analyzeDigitalLoopPlans(module)));
  }
  llvm::outs() << "DIGITAL_LOOP_PLAN_CASES " << cases << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}
