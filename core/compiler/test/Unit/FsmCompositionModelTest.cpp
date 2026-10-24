#include "nodal/Dialect/Nodal/FsmCompositionModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/FlatFSMModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/Support/raw_ostream.h"

#include <cstring>
#include <functional>
using namespace mlir;
namespace {
std::string bits(unsigned n) { return "!nodal.bits<" + std::to_string(n) + ">"; }
std::string replace(std::string source, StringRef from, StringRef to) {
  auto at = source.find(from.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing causal mutation");
  source.replace(at, from.size(), to.str());
  return source;
}
std::string port(std::string name, std::string type) {
  return "\"nodal.port\"() <{sym_name = \"" + name + "\", direction = \"input\", type = " + type +
         ", domain = @core, metadata = {}}> : () -> ()\n%" + name +
         " = \"nodal.port_value\"() <{port = @" + name + ", metadata = {}}> : () -> " + type + "\n";
}
std::string storage(std::string name) {
  return "%" + name + " = \"nodal.digital_storage\"() <{name = \"" + name +
         "\", kind = \"register\", domain = @core, metadata = {}}> : () -> " + bits(2) + "\n";
}
std::string machine(std::string name, bool parent = false) {
  std::string controls =
      parent ? "%restart, %cancel, %enable" : "%startChild, %cancelChild, %enableChild";
  return "%" + name + "Next, %" + name + "Taken, %" + name + "Entered, %" + name + "Exited, %" +
         name + "Established = \"nodal.flat_fsm\"(%" + name + "Local, %" + name + "Status, " +
         controls + ", %go, " + (parent ? "%joined" : "%go") +
         ") <{contract_version = \"flat-fsm-v3\", machine_id = \"Fixture." + name +
         "\", enum_ref = @Mode, domain = @core, initial_case = \"example.Mode.Idle\", "
         "requested_encoding = \"compact\", resolved_encoding = \"compact\", local_width = 2 : "
         "i64, case_ids = [\"example.Mode.Idle\", \"example.Mode.Run\", \"example.Mode.Stop\"], "
         "local_codes = [0 : ui2, 1 : ui2, 2 : ui2], illegal_policy = \"recover_to_initial\", "
         "states = [{case_id = \"example.Mode.Idle\", terminal = false, ordinal = 0 : i64}, "
         "{case_id = \"example.Mode.Run\", terminal = false, ordinal = 1 : i64}, {case_id = "
         "\"example.Mode.Stop\", terminal = true, ordinal = 2 : i64}], transitions = [{source_case "
         "= \"example.Mode.Idle\", destination_case = \"example.Mode.Run\", mode = \"priority\", "
         "ordinal = 0 : i64}, {source_case = \"example.Mode.Run\", destination_case = "
         "\"example.Mode.Stop\", mode = \"priority\", ordinal = 1 : i64}], actions = [], "
         "action_capture_counts = array<i32>, metadata = {semantic_path = \"Fixture." +
         name + "\"}}> : (" + bits(2) + ", " + bits(2) + ", " + bits(1) + ", " + bits(1) + ", " +
         bits(1) + ", " + bits(1) + ", " + bits(1) + ") -> (" + bits(2) + ", " + bits(1) + ", " +
         bits(1) + ", " + bits(1) + ", " + bits(1) + ")\n";
}
std::string fixture(std::string mode = "all") {
  std::string result = R"(module {
"nodal.enum"() <{sym_name = "Mode", underlying_type = !nodal.uint<3>, encoding = "custom", metadata = {abi_version = 1 : i64, semantic_type = "example.Mode", abi_hash = "cb807326542df7db5314f8d795ea4384c2b40209497084c69a41f9ccd9faf46b"}}> ({
"nodal.enum_case"() <{sym_name = "Idle", value = 0 : i64, metadata = {case_id = "example.Mode.Idle"}}> : () -> ()
"nodal.enum_case"() <{sym_name = "Run", value = 3 : i64, metadata = {case_id = "example.Mode.Run"}}> : () -> ()
"nodal.enum_case"() <{sym_name = "Stop", value = 7 : i64, metadata = {case_id = "example.Mode.Stop"}}> : () -> ()
}) : () -> ()
"nodal.module"() <{sym_name = "Fixture", metadata = {}}> ({
"nodal.domain"() <{sym_name = "core", edge = "rising", reset_policy = "none", metadata = {}}> : () -> ()
)";
  result += port("clock", "!nodal.capability<\"clock\">");
  result += "\"nodal.local_domain_binding\"(%clock) <{domain = @core, reset_polarity = "
            "\"active_high\", metadata = {}}> : (!nodal.capability<\"clock\">) -> ()\n";
  for (auto name : {"restart", "cancel", "enable", "go"})
    result += port(name, bits(1));
  for (std::string name : {"parent", "first", "second"})
    result += storage(name + "Local") + storage(name + "Status");
  result +=
      "%joined = \"nodal.fsm_join\"(%parentLocal, %parentStatus, %firstLocal, %firstStatus, "
      "%secondLocal, %secondStatus) <{composition_contract = \"fsm-composition-v1\", domain = "
      "@core, owner_case = \"example.Mode.Run\", mode = \"" +
      mode +
      "\", selection = \"all_children\", metadata = {semantic_path = \"Fixture.join\"}}> : (" +
      bits(2) + ", " + bits(2) + ", " + bits(2) + ", " + bits(2) + ", " + bits(2) + ", " + bits(2) +
      ") -> " + bits(1) + "\n";
  result += machine("parent", true);
  result += "%startChild, %cancelChild, %enableChild = \"nodal.fsm_activation\"(%parentLocal, "
            "%parentStatus, %parentNext, %parentTaken, %parentEntered, %parentExited, "
            "%parentEstablished, %enable, %firstLocal, %firstStatus, %secondLocal, %secondStatus) "
            "<{composition_contract = \"fsm-composition-v1\", domain = @core, owner_case = "
            "\"example.Mode.Run\", metadata = {semantic_path = \"Fixture.activation\"}}> : (" +
            bits(2) + ", " + bits(2) + ", " + bits(2) + ", " + bits(1) + ", " + bits(1) + ", " +
            bits(1) + ", " + bits(1) + ", " + bits(1) + ", " + bits(2) + ", " + bits(2) + ", " +
            bits(2) + ", " + bits(2) + ") -> (" + bits(1) + ", " + bits(1) + ", " + bits(1) + ")\n";
  return result + machine("first") + machine("second") + "}) : () -> ()\n}";
}
std::string nonterminatingSecond(std::string source) {
  auto at = source.find("%secondNext");
  auto second = source.substr(at);
  second = replace(second, "terminal = true", "terminal = false");
  second = replace(second, "%go, %go) <{", "%go, %go, %go) <{");
  second = replace(second, "ordinal = 1 : i64}], actions",
                   "ordinal = 1 : i64}, {source_case = \"example.Mode.Stop\", destination_case = "
                   "\"example.Mode.Run\", mode = \"priority\", ordinal = 2 : i64}], actions");
  second = replace(second, bits(1) + ") -> (", bits(1) + ", " + bits(1) + ") -> (");
  return source.substr(0, at) + second;
}
std::string stackedSecond(std::string source, StringRef mode = "returning", unsigned capacity = 2) {
  auto original = machine("second"), child = original;
  unsigned pointerWidth = llvm::APInt(64, capacity).getActiveBits();
  std::string storagePrefix =
      "%secondDepth = \"nodal.digital_storage\"() <{name = \"secondDepth\", kind = \"register\", "
      "domain = @core, metadata = {}}> : () -> !nodal.uint<" +
      std::to_string(pointerWidth) + ">\n";
  std::string operands = "%enableChild, %secondDepth",
              types = "!nodal.uint<" + std::to_string(pointerWidth) + ">";
  for (unsigned i = 0; i < capacity; ++i) {
    auto name = "secondFrame" + std::to_string(i);
    storagePrefix += storage(name);
    operands += ", %" + name;
    types += ", " + bits(2);
  }
  child = replace(child, "flat-fsm-v3", "flat-fsm-v5");
  child = replace(
      child, "machine_id =",
      "lifecycle_mode = \"composed\", timer_edges = [], transfer_edges = [], stack = {capacity = " +
          std::to_string(capacity) + " : i64, pointer_width = " + std::to_string(pointerWidth) +
          " : i64, overflow = {kind = \"trap\", target_case = \"example.Mode." +
          (mode == "overflow_completes" ? "Stop" : "Idle") +
          "\"}, underflow = {kind = \"assert_hold\"}, invalid_frame = {kind = \"trap\", "
          "target_case = \"example.Mode.Stop\"}}, machine_id =");
  child = replace(child, "%enableChild, %go", operands + ", %go");
  auto prefix =
      " : (" + bits(2) + ", " + bits(2) + ", " + bits(1) + ", " + bits(1) + ", " + bits(1) + ", ";
  child = replace(child, prefix, prefix + types + ", ");
  child =
      replace(child, "source_case = \"example.Mode.Idle\", destination_case = \"example.Mode.Run\"",
              "kind = \"call\", source_case = \"example.Mode.Idle\", destination_case = "
              "\"example.Mode.Run\", resume_case = \"example.Mode.Stop\"");
  std::string secondEdge =
      mode == "returning" ? "kind = \"return\", source_case = \"example.Mode.Run\""
      : mode == "callee_completes"
          ? "kind = \"ordinary\", source_case = \"example.Mode.Run\", destination_case = "
            "\"example.Mode.Stop\""
          : "kind = \"call\", source_case = \"example.Mode.Run\", destination_case = "
            "\"example.Mode.Run\", resume_case = \"example.Mode.Stop\"";
  child =
      replace(child, "source_case = \"example.Mode.Run\", destination_case = \"example.Mode.Stop\"",
              secondEdge);
  return replace(source, original, storagePrefix + child);
}
FailureOr<uint64_t> evaluate(Value value, const llvm::DenseMap<Value, uint64_t> &inputs,
                             unsigned depth = 0) {
  if (depth > 128)
    return failure();
  auto found = inputs.find(value);
  if (found != inputs.end())
    return found->second;
  auto *op = value.getDefiningOp();
  if (!op)
    return failure();
  if (auto literal = op->getAttrOfType<IntegerAttr>("value"))
    return literal.getValue().getZExtValue();
  auto name = op->getAttrOfType<StringAttr>("operator_name");
  if (llvm::isa<nodal::SequentialSelectOp>(op) || (name && name.getValue() == "digital_select")) {
    auto condition = evaluate(op->getOperand(0), inputs, depth + 1);
    return failed(condition) ? FailureOr<uint64_t>(failure())
                             : evaluate(op->getOperand(*condition ? 1 : 2), inputs, depth + 1);
  }
  if (!name || op->getNumOperands() < 1)
    return failure();
  auto a = evaluate(op->getOperand(0), inputs, depth + 1);
  if (failed(a))
    return failure();
  if (name.getValue() == "bool_not")
    return uint64_t(!*a);
  if (name.getValue() == "digital_extend")
    return *a;
  if (name.getValue() == "digital_truncate") {
    auto type = value.getType();
    unsigned width = llvm::isa<nodal::UIntType>(type)
                         ? llvm::cast<nodal::UIntType>(type).getWidth()
                         : llvm::cast<nodal::BitsType>(type).getWidth();
    return *a & ((uint64_t(1) << width) - 1);
  }
  if (op->getNumOperands() != 2)
    return failure();
  auto b = evaluate(op->getOperand(1), inputs, depth + 1);
  if (failed(b))
    return failure();
  if (name.getValue() == "bool_and")
    return uint64_t(*a && *b);
  if (name.getValue() == "bool_or")
    return uint64_t(*a || *b);
  if (name.getValue() == "digital_eq")
    return uint64_t(*a == *b);
  if (name.getValue() == "digital_le")
    return uint64_t(*a <= *b);
  if (name.getValue() == "digital_add")
    return *a + *b;
  return failure();
}
} // namespace
int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  unsigned total = 0, failures = 0;
  auto check = [&](StringRef name, std::string source, bool expected,
                   std::function<bool(nodal::ModuleOp)> extra = {}) {
    ++total;
    ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
    ParserConfig config(&context, false);
    auto root = parseSourceString<mlir::ModuleOp>(source, config);
    nodal::ModuleOp module;
    if (root)
      for (auto op : root->getOps<nodal::ModuleOp>())
        module = op;
    if (!module) {
      ++failures;
      llvm::errs() << "FAIL " << name << " (fixture did not parse)\n";
      return;
    }
    bool good = succeeded(nodal::verifyFsmCompositionModel(module));
    if (good && extra)
      good = extra(module);
    if (good != expected) {
      ++failures;
      llvm::errs() << "FAIL " << name << "\n";
    }
  };
  for (std::string mode : {"all", "any"})
    check(mode, fixture(mode), true, [&](nodal::ModuleOp module) {
      SmallVector<nodal::FlatFSMOp> machines;
      for (auto op : module.getBody().front().getOps<nodal::FlatFSMOp>())
        machines.push_back(op);
      auto parent = nodal::inspectFlatFSMHeader(machines[0]);
      auto first = nodal::inspectFlatFSMHeader(machines[1]);
      auto second = nodal::inspectFlatFSMHeader(machines[2]);
      if (failed(parent) || failed(first) || failed(second))
        return false;
      unsigned generated = 0;
      if (failed(nodal::expandFsmCompositionModel(module, generated, 65536)))
        return false;
      for (unsigned a = 0; a < 4; ++a)
        for (unsigned b = 0; b < 4; ++b) {
          auto joined = evaluate(parent->operation->getOperand(parent->guardOffset + 1),
                                 {{first->currentStatus(), a}, {second->currentStatus(), b}});
          bool expected = mode == "all" ? a == 2 && b == 2 : a == 2 || b == 2;
          if (failed(joined) || *joined != expected)
            return false;
        }
      for (unsigned current = 0; current < 4; ++current)
        for (unsigned next = 0; next < 4; ++next)
          for (unsigned status = 0; status < 4; ++status)
            for (unsigned flags = 0; flags < 16; ++flags) {
              bool entered = flags & 1, exited = flags & 2, established = flags & 4,
                   enabled = flags & 8;
              llvm::DenseMap<Value, uint64_t> inputs{{parent->currentLocal(), current},
                                                     {parent->currentStatus(), status},
                                                     {parent->next(), next},
                                                     {parent->entered(), entered},
                                                     {parent->exited(), exited},
                                                     {parent->establishment(), established},
                                                     {parent->enable(), enabled}};
              bool start = (entered || established) && next == 1;
              auto restart = evaluate(first->restart(), inputs),
                   cancel = evaluate(first->cancel(), inputs),
                   enable = evaluate(first->enable(), inputs);
              if (failed(restart) || failed(cancel) || failed(enable) || *restart != start ||
                  *cancel != ((exited || established) && !start) ||
                  *enable != (status == 1 && current == 1 && enabled))
                return false;
            }
      return generated < 32;
    });
  check("all-impossible-child", nonterminatingSecond(fixture("all")), false);
  check("any-nonterminating-sibling", nonterminatingSecond(fixture("any")), true);
  check("activation-local-operand-boundary", fixture(), true, [](nodal::ModuleOp module) {
    Operation *activation = nullptr;
    module.walk([&](Operation *op) {
      if (op->getName().getStringRef() == "nodal.fsm_activation")
        activation = op;
    });
    SmallVector<Value> inputs(activation->getOperands().take_front(8));
    Value local = activation->getOperand(8), status = activation->getOperand(9);
    for (unsigned i = 0; i < 4095; ++i) {
      inputs.push_back(local);
      inputs.push_back(status);
    }
    activation->setOperands(inputs);
    if (failed(nodal::verifyFsmCompositionOperation(activation)))
      return false;
    inputs.push_back(local);
    inputs.push_back(status);
    activation->setOperands(inputs);
    return failed(nodal::verifyFsmCompositionOperation(activation));
  });
  check("result-role-probe-is-nondiagnostic", fixture(), true, [](nodal::ModuleOp module) {
    unsigned diagnostics = 0, roles = 0;
    ScopedDiagnosticHandler handler(module->getContext(), [&](Diagnostic &) {
      ++diagnostics;
      return success();
    });
    module.walk([&](Operation *op) {
      for (Value result : op->getResults()) {
        auto role = nodal::fsmCompositionResultRole(result);
        if (!role.empty())
          ++roles;
      }
    });
    return diagnostics == 0 && roles == 4;
  });
  check("semantic-dependencies-exclude-identity-operands", fixture(), true,
        [](nodal::ModuleOp module) {
          bool good = true;
          module.walk([&](Operation *op) {
            bool join = op->getName().getStringRef() == "nodal.fsm_join";
            if (!join && op->getName().getStringRef() != "nodal.fsm_activation")
              return;
            for (auto result : op->getResults()) {
              SmallVector<Value> deps;
              good &= succeeded(nodal::fsmCompositionResultDependencies(result, deps));
              good &= deps.size() == (join ? 2 : result.getResultNumber() == 1 ? 4 : 3);
              for (unsigned i = join ? 2 : 8; i < op->getNumOperands(); i += 2)
                good &= !llvm::is_contained(deps, op->getOperand(i));
            }
          });
          return good;
        });
  // A composed child forwards only its exact enable into its own activation.
  auto nested = fixture();
  auto activationStart = nested.find("%startChild, %cancelChild, %enableChild =");
  auto activationEnd = nested.find(machine("first"), activationStart);
  auto nestedActivation = nested.substr(activationStart, activationEnd - activationStart);
  for (auto pair : {std::make_pair("parent", "first"), std::make_pair("startChild", "startGrand"),
                    std::make_pair("cancelChild", "cancelGrand"),
                    std::make_pair("enableChild", "enableGrand")}) {
    std::size_t position = 0;
    while ((position = nestedActivation.find(pair.first, position)) != std::string::npos) {
      nestedActivation.replace(position, std::strlen(pair.first), pair.second);
      position += std::strlen(pair.second);
    }
  }
  nestedActivation = replace(nestedActivation,
                             "%enable, %firstLocal, %firstStatus, "
                             "%secondLocal, %secondStatus",
                             "%enableChild, %grandLocal, %grandStatus");
  nestedActivation =
      replace(nestedActivation, bits(2) + ", " + bits(2) + ", " + bits(2) + ", " + bits(2) + ") ->",
              bits(2) + ", " + bits(2) + ") ->");
  auto grand = replace(replace(replace(machine("grand"), "%startChild", "%startGrand"),
                               "%cancelChild", "%cancelGrand"),
                       "%enableChild", "%enableGrand");
  nested = replace(nested, machine("second"),
                   machine("second") + storage("grandLocal") + storage("grandStatus") +
                       nestedActivation + grand);
  check("nested-exact-enable", nested, true);
  check("nested-cancel-as-enable",
        replace(nested, "%enableChild, %grandLocal", "%cancelChild, %grandLocal"), false);
  check("nested-foreign-parent",
        replace(nested, "\"nodal.fsm_activation\"(%firstLocal",
                "\"nodal.fsm_activation\"(%secondLocal"),
        false);
  check("foreign-status",
        replace(fixture(), "%parentLocal, %parentStatus, %firstLocal, %firstStatus",
                "%parentLocal, %firstStatus, %firstLocal, %firstStatus"),
        false);
  check("foreign-domain",
        replace(fixture(), "composition_contract = \"fsm-composition-v1\", domain = @core",
                "composition_contract = \"fsm-composition-v1\", domain = @other"),
        false);
  check("missing-child",
        replace(fixture(),
                "%firstLocal, %firstStatus, %secondLocal, %secondStatus) <{composition_contract",
                "%firstLocal, %firstStatus, %firstLocal, %firstStatus) <{composition_contract"),
        false);
  check("wrong-parent-future",
        replace(fixture(), "%parentLocal, %parentStatus, %parentNext",
                "%parentLocal, %parentStatus, %parentLocal"),
        false);
  check("wrong-owner",
        replace(fixture(), "owner_case = \"example.Mode.Run\", mode",
                "owner_case = \"example.Mode.Idle\", mode"),
        false);
  check("unknown-policy",
        replace(fixture(), "mode = \"all\", selection", "mode = \"winner\", selection"), false);
  check("whole-as-single",
        replace(fixture(), "selection = \"all_children\"", "selection = \"one_child\""), false);
  check("depth-budget", fixture(), true, [](nodal::ModuleOp module) {
    return failed(nodal::verifyFsmCompositionModel(module, 65536, 1));
  });
  check("work-budget", fixture(), true,
        [](nodal::ModuleOp module) { return failed(nodal::verifyFsmCompositionModel(module, 1)); });
  check("expansion-budget", fixture(), true, [](nodal::ModuleOp module) {
    unsigned generated = 0;
    return failed(nodal::expandFsmCompositionModel(module, generated, 1)) && generated == 0;
  });
  check("stacked-child-matching-return-completes-all", stackedSecond(fixture()), true,
        [](nodal::ModuleOp module) {
          for (auto op : module.getOps<nodal::FlatFSMOp>()) {
            auto summary = nodal::inspectFlatFSMGraphSummary(op);
            if (failed(summary) || !summary->completionPossible)
              return false;
            if (summary->header.stacked && summary->structuralWork == 0)
              return false;
          }
          return true;
        });
  check("stacked-child-terminal-callee-completes-all", stackedSecond(fixture(), "callee_completes"),
        true);
  check("stacked-child-overflow-trap-completes-all", stackedSecond(fixture(), "overflow_completes"),
        true);
  check("stacked-child-invalid-trap-coverage-is-not-completion",
        stackedSecond(fixture(), "no_return"), false);
  check("stacked-child-any-permits-noncompleting-sibling",
        stackedSecond(fixture("any"), "no_return"), true, [](nodal::ModuleOp module) {
          for (auto op : module.getOps<nodal::FlatFSMOp>()) {
            auto summary = nodal::inspectFlatFSMGraphSummary(op);
            if (failed(summary) || (summary->header.stacked && summary->completionPossible))
              return false;
          }
          return true;
        });
  check("stacked-child-summary-charged-to-composition-budget",
        stackedSecond(fixture(), "returning", 128), true, [](nodal::ModuleOp module) {
          unsigned proofWork = 0;
          for (auto op : module.getOps<nodal::FlatFSMOp>()) {
            auto summary = nodal::inspectFlatFSMGraphSummary(op);
            if (failed(summary))
              return false;
            proofWork += summary->structuralWork;
          }
          return proofWork > 1000 &&
                 failed(nodal::verifyFsmCompositionModel(module, proofWork - 1));
        });
  check("stacked-child-normalized-completion-and-parent-cancellation", stackedSecond(fixture()),
        true, [](nodal::ModuleOp module) {
          // Normalization commits a detached replacement, so collect inputs from
          // the resulting IR rather than retaining stale source SSA values.
          if (failed(nodal::normalizeSequentialModel(module)))
            return false;
          llvm::DenseMap<Value, uint64_t> inputs;
          Value cancel;
          module.walk([&](nodal::PortValueOp op) {
            auto port = op->getAttrOfType<FlatSymbolRefAttr>("port").getValue();
            inputs[op->getResult(0)] = port == "enable" || port == "go";
            if (port == "cancel")
              cancel = op->getResult(0);
          });
          module.walk([&](nodal::DigitalStorageOp op) {
            auto name = op->getAttrOfType<StringAttr>("name").getValue();
            inputs[op->getResult(0)] =
                name.starts_with("first") || name.starts_with("secondFrame") ? 2 : 1;
          });
          if (!cancel)
            return false;
          bool good = true;
          for (unsigned cancelling : {0u, 1u}) {
            inputs[cancel] = cancelling;
            unsigned observations = 0;
            module.walk([&](nodal::SequentialUpdateOp op) {
              auto name =
                  op->getOperand(0).getDefiningOp()->getAttrOfType<StringAttr>("name").getValue();
              if (name != "secondDepth" && name != "secondStatus")
                return;
              ++observations;
              auto result = evaluate(op->getOperand(1), inputs);
              unsigned expected = name == "secondDepth" ? 0 : cancelling ? 3 : 2;
              if (failed(result) || *result != expected)
                llvm::errs() << "stack child cancel=" << cancelling << " " << name << ": "
                             << (failed(result) ? -1 : int64_t(*result)) << " != " << expected
                             << '\n';
              good &= succeeded(result) && *result == expected;
            });
            if (observations != 2)
              llvm::errs() << "stack child observations=" << observations << '\n';
            good &= observations == 2;
          }
          return good;
        });
  llvm::outs() << total << " FSM composition cases, " << failures << " failures\n";
  return failures ? 1 : 0;
}
