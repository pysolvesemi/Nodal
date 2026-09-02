#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"missing required file: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def insert_before(text: str, marker: str, addition: str, label: str) -> str:
    if addition.strip() in text:
        return text
    return replace_once(text, marker, addition + marker, label)


def patch_native_dataflow(root: Path) -> None:
    path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
    text = read(path)
    if "verifyStructuredDefiniteAssignment" in text:
        return

    if "#include <set>" not in text:
        text = replace_once(
            text,
            "#include <optional>\n",
            "#include <optional>\n#include <set>\n#include <vector>\n",
            "structured dataflow includes",
        )

    implementation = r'''
using StructuredInitializedSet = std::set<std::string>;

struct StructuredVariableInfo {
  Block *declarationBlock = nullptr;
};

struct StructuredDataflowContext {
  llvm::StringMap<StructuredVariableInfo> variables;
};

struct StructuredFlow {
  std::optional<StructuredInitializedSet> normal;
  std::vector<StructuredInitializedSet> breaks;
  std::vector<StructuredInitializedSet> continues;
};

std::optional<StructuredInitializedSet>
intersectStructuredStates(const std::vector<StructuredInitializedSet> &states) {
  if (states.empty())
    return std::nullopt;
  StructuredInitializedSet result = states.front();
  for (auto state = std::next(states.begin()); state != states.end(); ++state) {
    for (auto value = result.begin(); value != result.end();) {
      if (state->find(*value) == state->end())
        value = result.erase(value);
      else
        ++value;
    }
  }
  return result;
}

void appendStructuredEscapes(StructuredFlow &destination,
                             const StructuredFlow &source) {
  destination.breaks.insert(destination.breaks.end(), source.breaks.begin(),
                            source.breaks.end());
  destination.continues.insert(destination.continues.end(),
                               source.continues.begin(),
                               source.continues.end());
}

void removeStructuredLocals(StructuredFlow &flow,
                            const std::vector<std::string> &locals) {
  auto remove = [&](StructuredInitializedSet &state) {
    for (const std::string &identity : locals)
      state.erase(identity);
  };
  if (flow.normal)
    remove(*flow.normal);
  for (StructuredInitializedSet &state : flow.breaks)
    remove(state);
  for (StructuredInitializedSet &state : flow.continues)
    remove(state);
}

LogicalResult collectStructuredVariables(Block &block, llvm::StringRef owner,
                                         StructuredDataflowContext &context) {
  for (Operation &operation : block) {
    if (auto declaration = llvm::dyn_cast<nodal::AnalogVariableOp>(&operation)) {
      llvm::StringRef identity = textAttr(&operation, "identity");
      if (identity.trim().empty())
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured variable identity must be non-empty");
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured variable owner does not match the enclosing component");
      if (!context.variables
               .try_emplace(identity,
                            StructuredVariableInfo{operation.getBlock()})
               .second)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            llvm::Twine("duplicate structured variable identity '") + identity +
                "'");
    }
    for (Region &region : operation.getRegions()) {
      for (Block &nested : region) {
        if (failed(collectStructuredVariables(nested, owner, context)))
          return failure();
      }
    }
  }
  return success();
}

LogicalResult requireStructuredRead(
    Operation *operation, llvm::StringRef identity,
    const StructuredInitializedSet &initialized,
    const StructuredDataflowContext &context) {
  auto variable = context.variables.find(identity);
  if (variable == context.variables.end())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        llvm::Twine("structured control flow references unknown variable '") +
            identity + "'");
  if (!isVisibleFrom(operation->getBlock(), variable->second.declarationBlock))
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        llvm::Twine("structured variable '") + identity +
            "' is outside its lexical declaration scope");
  if (initialized.find(identity.str()) == initialized.end())
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-004",
        llvm::Twine("procedural variable '") + identity +
            "' is read before definite initialization on this control-flow path");
  return success();
}

LogicalResult requireStructuredReads(
    Operation *operation, llvm::StringRef attributeName,
    const StructuredInitializedSet &initialized,
    const StructuredDataflowContext &context) {
  auto reads = operation->getAttrOfType<ArrayAttr>(attributeName);
  if (!reads)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        llvm::Twine("missing structured read inventory '") + attributeName +
            "'");
  for (Attribute attribute : reads) {
    auto identity = llvm::dyn_cast<StringAttr>(attribute);
    if (!identity || identity.getValue().trim().empty())
      return nodal::emitMappedFailure(
          operation, "NODAL-ANALOG-034-014",
          "structured read inventory entries must be non-empty identities");
    if (failed(requireStructuredRead(operation, identity.getValue(), initialized,
                                     context)))
      return failure();
  }
  return success();
}

FailureOr<std::string> structuredVariableIdentity(
    Operation *operation, Value value,
    const StructuredDataflowContext &context, bool requireInitialized,
    const StructuredInitializedSet &initialized) {
  auto declaration = value.getDefiningOp<nodal::AnalogVariableOp>();
  if (!declaration)
    return nodal::emitMappedFailure(
               operation, "NODAL-ANALOG-034-014",
               "structured procedural variable operand must resolve to an analog_variable")
        .failed()
        ? failure()
        : failure();
  llvm::StringRef identity = textAttr(declaration.getOperation(), "identity");
  auto variable = context.variables.find(identity);
  if (variable == context.variables.end())
    return failure();
  if (!isVisibleFrom(operation->getBlock(), variable->second.declarationBlock)) {
    (void)nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        llvm::Twine("structured variable '") + identity +
            "' is outside its lexical declaration scope");
    return failure();
  }
  if (requireInitialized && initialized.find(identity.str()) == initialized.end()) {
    (void)nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-004",
        llvm::Twine("procedural variable '") + identity +
            "' is read before definite initialization on this control-flow path");
    return failure();
  }
  return identity.str();
}

FailureOr<StructuredFlow> analyzeStructuredDataflowBlock(
    Block &block, const StructuredInitializedSet &input,
    StructuredDataflowContext &context, bool retainLocals);

FailureOr<StructuredFlow> analyzeStructuredIf(
    nodal::AnalogIfOp conditional, const StructuredInitializedSet &input,
    StructuredDataflowContext &context) {
  StructuredFlow result;
  std::vector<StructuredInitializedSet> normalStates;
  bool unmatchedReachable = true;

  for (Operation &operation : conditional.getOperation()->getRegion(0).front()) {
    auto arm = llvm::cast<nodal::AnalogIfArmOp>(&operation);
    auto isElse = operation.getAttrOfType<BoolAttr>("is_else");
    bool reachable = unmatchedReachable;
    if (isElse.getValue()) {
      unmatchedReachable = false;
    } else {
      llvm::StringRef stage = textAttr(&operation, "stage");
      if (stage == "static") {
        auto value = operation.getAttrOfType<BoolAttr>("static_value");
        reachable = unmatchedReachable && value.getValue();
        if (value.getValue())
          unmatchedReachable = false;
      } else {
        reachable = unmatchedReachable;
      }
    }

    if (!reachable)
      continue;
    if (!isElse.getValue() &&
        failed(requireStructuredReads(&operation, "condition_reads", input,
                                      context)))
      return failure();
    auto branch = analyzeStructuredDataflowBlock(
        arm.getOperation()->getRegion(0).front(), input, context,
        /*retainLocals=*/false);
    if (failed(branch))
      return failure();
    if (branch->normal)
      normalStates.push_back(*branch->normal);
    appendStructuredEscapes(result, *branch);
  }

  if (unmatchedReachable)
    normalStates.push_back(input);
  result.normal = intersectStructuredStates(normalStates);
  return result;
}

FailureOr<StructuredFlow> analyzeStructuredCase(
    nodal::AnalogCaseOp selection, const StructuredInitializedSet &input,
    StructuredDataflowContext &context) {
  if (failed(requireStructuredReads(selection.getOperation(), "selector_reads",
                                    input, context)))
    return failure();

  StructuredFlow result;
  std::vector<StructuredInitializedSet> normalStates;
  auto staticPresent =
      selection.getOperation()->getAttrOfType<BoolAttr>("static_value_present");
  llvm::StringRef staticValue =
      textAttr(selection.getOperation(), "static_value");
  nodal::AnalogCaseArmOp defaultArm;

  if (staticPresent.getValue()) {
    nodal::AnalogCaseArmOp selected;
    for (Operation &operation : selection.getOperation()->getRegion(0).front()) {
      auto arm = llvm::cast<nodal::AnalogCaseArmOp>(&operation);
      auto isDefault = operation.getAttrOfType<BoolAttr>("is_default");
      if (isDefault.getValue()) {
        defaultArm = arm;
        continue;
      }
      auto labels = operation.getAttrOfType<ArrayAttr>("labels");
      for (Attribute attribute : labels) {
        if (llvm::cast<StringAttr>(attribute).getValue() == staticValue) {
          selected = arm;
          break;
        }
      }
      if (selected)
        break;
    }
    nodal::AnalogCaseArmOp reachable = selected ? selected : defaultArm;
    if (!reachable) {
      result.normal = input;
      return result;
    }
    auto branch = analyzeStructuredDataflowBlock(
        reachable.getOperation()->getRegion(0).front(), input, context,
        /*retainLocals=*/false);
    if (failed(branch))
      return failure();
    return *branch;
  }

  bool hasDefault = false;
  for (Operation &operation : selection.getOperation()->getRegion(0).front()) {
    auto arm = llvm::cast<nodal::AnalogCaseArmOp>(&operation);
    if (operation.getAttrOfType<BoolAttr>("is_default").getValue())
      hasDefault = true;
    auto branch = analyzeStructuredDataflowBlock(
        arm.getOperation()->getRegion(0).front(), input, context,
        /*retainLocals=*/false);
    if (failed(branch))
      return failure();
    if (branch->normal)
      normalStates.push_back(*branch->normal);
    appendStructuredEscapes(result, *branch);
  }
  if (!hasDefault)
    normalStates.push_back(input);
  result.normal = intersectStructuredStates(normalStates);
  return result;
}

FailureOr<StructuredFlow> analyzeStructuredLoop(
    nodal::AnalogLoopOp loop, const StructuredInitializedSet &input,
    StructuredDataflowContext &context) {
  if (failed(requireStructuredReads(loop.getOperation(), "bound_reads", input,
                                    context)))
    return failure();

  llvm::StringRef stage = textAttr(loop.getOperation(), "stage");
  auto minimum =
      loop.getOperation()->getAttrOfType<IntegerAttr>("minimum_iterations");
  auto staticCount =
      loop.getOperation()->getAttrOfType<IntegerAttr>("static_trip_count");
  if (stage == "static" && staticCount.getInt() == 0)
    return StructuredFlow{input, {}, {}};

  auto body = analyzeStructuredDataflowBlock(
      loop.getOperation()->getRegion(0).front(), input, context,
      /*retainLocals=*/false);
  if (failed(body))
    return failure();

  std::vector<StructuredInitializedSet> exits;
  if (minimum.getInt() == 0)
    exits.push_back(input);
  if (body->normal)
    exits.push_back(*body->normal);
  exits.insert(exits.end(), body->breaks.begin(), body->breaks.end());
  exits.insert(exits.end(), body->continues.begin(), body->continues.end());

  StructuredFlow result;
  result.normal = intersectStructuredStates(exits);
  return result;
}

FailureOr<StructuredFlow> analyzeStructuredDataflowStatement(
    Operation *operation, const StructuredInitializedSet &input,
    StructuredDataflowContext &context) {
  if (auto declaration = llvm::dyn_cast<nodal::AnalogVariableOp>(operation)) {
    StructuredInitializedSet output = input;
    if (failed(requireStructuredReads(operation, "initializer_reads", input,
                                      context)))
      return failure();
    llvm::StringRef identity = textAttr(operation, "identity");
    if (operation->getAttrOfType<BoolAttr>("initialized").getValue())
      output.insert(identity.str());
    return StructuredFlow{output, {}, {}};
  }

  if (auto read = llvm::dyn_cast<nodal::AnalogVariableReadOp>(operation)) {
    auto identity = structuredVariableIdentity(
        operation, operation->getOperand(0), context,
        /*requireInitialized=*/true, input);
    if (failed(identity))
      return failure();
    return StructuredFlow{input, {}, {}};
  }

  if (auto assignment = llvm::dyn_cast<nodal::AnalogAssignOp>(operation)) {
    StructuredInitializedSet output = input;
    auto target = structuredVariableIdentity(
        operation, operation->getOperand(0), context,
        /*requireInitialized=*/false, input);
    if (failed(target))
      return failure();
    for (Value value : operation->getOperands().drop_front()) {
      auto read = value.getDefiningOp<nodal::AnalogVariableReadOp>();
      if (!read) {
        (void)nodal::emitMappedFailure(
            operation, "NODAL-ANALOG-034-014",
            "structured assignment operands must be explicit variable reads");
        return failure();
      }
      auto identity = structuredVariableIdentity(
          operation, read.getOperation()->getOperand(0), context,
          /*requireInitialized=*/true, input);
      if (failed(identity))
        return failure();
    }
    output.insert(*target);
    return StructuredFlow{output, {}, {}};
  }

  if (auto scope = llvm::dyn_cast<nodal::AnalogScopeOp>(operation))
    return analyzeStructuredDataflowBlock(
        scope.getOperation()->getRegion(0).front(), input, context,
        /*retainLocals=*/false);
  if (auto conditional = llvm::dyn_cast<nodal::AnalogIfOp>(operation))
    return analyzeStructuredIf(conditional, input, context);
  if (auto selection = llvm::dyn_cast<nodal::AnalogCaseOp>(operation))
    return analyzeStructuredCase(selection, input, context);
  if (auto loop = llvm::dyn_cast<nodal::AnalogLoopOp>(operation))
    return analyzeStructuredLoop(loop, input, context);
  if (llvm::isa<nodal::AnalogBreakOp>(operation))
    return StructuredFlow{std::nullopt, {input}, {}};
  if (llvm::isa<nodal::AnalogContinueOp>(operation))
    return StructuredFlow{std::nullopt, {}, {input}};

  (void)nodal::emitMappedFailure(
      operation, "NODAL-ANALOG-034-014",
      "unsupported operation reached structured definite-assignment analysis");
  return failure();
}

FailureOr<StructuredFlow> analyzeStructuredDataflowBlock(
    Block &block, const StructuredInitializedSet &input,
    StructuredDataflowContext &context, bool retainLocals) {
  StructuredFlow flow{input, {}, {}};
  std::vector<std::string> locals;
  for (Operation &operation : block) {
    if (auto declaration = llvm::dyn_cast<nodal::AnalogVariableOp>(&operation))
      locals.push_back(textAttr(&operation, "identity").str());
    if (!flow.normal)
      continue;
    auto statement =
        analyzeStructuredDataflowStatement(&operation, *flow.normal, context);
    if (failed(statement))
      return failure();
    appendStructuredEscapes(flow, *statement);
    flow.normal = statement->normal;
  }
  if (!retainLocals)
    removeStructuredLocals(flow, locals);
  return flow;
}

LogicalResult verifyStructuredDefiniteAssignment(Operation *procedure,
                                                 llvm::StringRef owner) {
  StructuredDataflowContext context;
  Block &body = procedure->getRegion(0).front();
  if (failed(collectStructuredVariables(body, owner, context)))
    return failure();
  auto flow = analyzeStructuredDataflowBlock(
      body, StructuredInitializedSet{}, context, /*retainLocals=*/true);
  if (failed(flow))
    return failure();
  if (!flow->breaks.empty())
    return nodal::emitMappedFailure(
        procedure, "NODAL-ANALOG-034-010",
        "break escaped the nearest runtime-bounded analog loop");
  if (!flow->continues.empty())
    return nodal::emitMappedFailure(
        procedure, "NODAL-ANALOG-034-011",
        "continue escaped the nearest runtime-bounded analog loop");
  return success();
}

'''
    text = insert_before(
        text,
        "LogicalResult verifySingleTopLevelProcedurePerModule(Operation *module) {\n",
        implementation,
        "native branch-sensitive dataflow",
    )

    old = '''  if (hasStructuredControl)
    return verifyStructuredProceduralBlock(operation->getRegion(0).front(), owner);

  ProceduralVerificationState state;
'''
    new = '''  if (hasStructuredControl) {
    if (failed(
            verifyStructuredProceduralBlock(operation->getRegion(0).front(), owner)))
      return failure();
    return verifyStructuredDefiniteAssignment(operation, owner);
  }

  ProceduralVerificationState state;
'''
    text = replace_once(
        text,
        old,
        new,
        "structured definite-assignment dispatch",
    )
    write(path, text)


def variable_declarations() -> str:
    return r'''        %mode = "nodal.analog_variable"() <{declaration_order = 0 : i64, identity = "ControlTop.procedure.mode", initialized = true, initializer_dimension = "1", initializer_kind = "integer", initializer_reads = [], initializer_value = "0", metadata = {semantic_path = "ControlTop.procedure.mode"}, owner = "ControlTop"}> : () -> !nodal.variable<"integer", "1"> loc("AnalogControlFlow.scala":10:5)
        %select = "nodal.analog_variable"() <{declaration_order = 1 : i64, identity = "ControlTop.procedure.select", initialized = true, initializer_dimension = "1", initializer_kind = "boolean", initializer_reads = [], initializer_value = "false", metadata = {semantic_path = "ControlTop.procedure.select"}, owner = "ControlTop"}> : () -> !nodal.variable<"boolean", "1"> loc("AnalogControlFlow.scala":11:5)
        %value = "nodal.analog_variable"() <{declaration_order = 2 : i64, identity = "ControlTop.procedure.value", initialized = false, initializer_dimension = "", initializer_kind = "", initializer_reads = [], initializer_value = "", metadata = {semantic_path = "ControlTop.procedure.value"}, owner = "ControlTop"}> : () -> !nodal.variable<"real", "1"> loc("AnalogControlFlow.scala":12:5)
'''


def prefix() -> str:
    return r'''module {
  "nodal.module"() <{metadata = {}, sym_name = "ControlTop"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {semantic_path = "ControlTop.analogProcedural"}}> ({
    ^bb0:
      "nodal.analog_procedure"() <{metadata = {semantic_path = "ControlTop.analogProcedure"}, owner = "ControlTop"}> ({
      ^bb0:
'''


def suffix() -> str:
    return r'''      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
'''


def assign(identity: str, order: int, indent: str = "            ") -> str:
    return (
        f'{indent}"nodal.analog_assign"(%value) '
        f'<{{analyses = ["dc", "transient"], authored_order = {order} : i64, '
        f'guard_dimension = "", guard_kind = "", guard_present = false, '
        f'guard_reads = [], guard_value = "", '
        f'metadata = {{semantic_path = "{identity}", value = "1.0"}}, '
        f'owner = "ControlTop", statement_id = "{identity}", '
        f'value_dimension = "1", value_kind = "real"}}> '
        f': (!nodal.variable<"real", "1">) -> () '\
        f'loc("AnalogControlFlow.scala":{30 + order}:9)\n'
    )


def read_value(identity: str, indent: str = "        ") -> str:
    return (
        f'{indent}%{identity} = "nodal.analog_variable_read"(%value) '
        f'<{{metadata = {{semantic_path = "ControlTop.{identity}"}}, '
        f'owner = "ControlTop", read_id = "ControlTop.{identity}"}}> '
        f': (!nodal.variable<"real", "1">) -> '
        f'!nodal.quantity<"real", "1"> '
        f'loc("AnalogControlFlow.scala":80:7)\n'
    )


def patch_dataflow_fixtures(root: Path) -> None:
    ir = root / "core/compiler/test/IR"

    valid_path = ir / "analog-control-flow.mlir"
    valid = read(valid_path)
    if "ControlTop.final_read" not in valid:
        valid = replace_once(
            valid,
            "      }) : () -> ()\n    }) : () -> ()\n",
            read_value("final_read") + "      }) : () -> ()\n    }) : () -> ()\n",
            "positive structured final read",
        )
        write(valid_path, valid)

    missing_else = prefix() + variable_declarations()
    missing_else += r'''        "nodal.analog_if"() <{metadata = {semantic_path = "ControlTop.if_0"}, owner = "ControlTop", statement_id = "ControlTop.if_0"}> ({
        ^bb0:
          "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_0.branch_0", condition_dimension = "1", condition_kind = "boolean", condition_reads = ["ControlTop.procedure.select"], condition_value = "ControlTop.procedure.select", is_else = false, metadata = {semantic_path = "ControlTop.if_0.condition_0"}, owner = "ControlTop", stage = "runtime", static_value = false, static_value_present = false}> ({
          ^bb0:
'''
    missing_else += assign("ControlTop.statement_0", 0)
    missing_else += r'''          }) : () -> ()
        }) : () -> ()
'''
    missing_else += read_value("read_after_if") + suffix()
    write(ir / "analog-control-flow-invalid-missing-else.mlir", missing_else)

    missing_default = prefix() + variable_declarations()
    missing_default += r'''        "nodal.analog_case"() <{metadata = {semantic_path = "ControlTop.case_0.selector"}, owner = "ControlTop", selector_dimension = "1", selector_kind = "integer", selector_reads = ["ControlTop.procedure.mode"], selector_value = "ControlTop.procedure.mode", statement_id = "ControlTop.case_0", static_value = "", static_value_present = false}> ({
        ^bb0:
          "nodal.analog_case_arm"() <{arm_id = "ControlTop.case_0.arm_0", is_default = false, labels = ["integer:0"], metadata = {semantic_path = "ControlTop.case_0.arm_0"}, owner = "ControlTop"}> ({
          ^bb0:
'''
    missing_default += assign("ControlTop.statement_0", 0)
    missing_default += r'''          }) : () -> ()
        }) : () -> ()
'''
    missing_default += read_value("read_after_case") + suffix()
    write(ir / "analog-control-flow-invalid-missing-default.mlir", missing_default)

    zero_trip = prefix() + variable_declarations()
    zero_trip += r'''        "nodal.analog_loop"() <{bound_dimension = "1", bound_kind = "integer", bound_reads = ["ControlTop.procedure.mode"], bound_value = "ControlTop.procedure.mode", maximum_iterations = 4 : i64, metadata = {semantic_path = "ControlTop.loop_0"}, minimum_iterations = 0 : i64, owner = "ControlTop", stage = "runtime", statement_id = "ControlTop.loop_0", static_trip_count = 0 : i64, static_trip_count_present = false}> ({
        ^bb0:
'''
    zero_trip += assign("ControlTop.statement_0", 0, "          ")
    zero_trip += r'''        }) : () -> ()
'''
    zero_trip += read_value("read_after_loop") + suffix()
    write(ir / "analog-control-flow-invalid-zero-trip.mlir", zero_trip)

    continue_path = prefix() + variable_declarations()
    continue_path += r'''        "nodal.analog_loop"() <{bound_dimension = "1", bound_kind = "integer", bound_reads = ["ControlTop.procedure.mode"], bound_value = "ControlTop.procedure.mode", maximum_iterations = 4 : i64, metadata = {semantic_path = "ControlTop.loop_0"}, minimum_iterations = 1 : i64, owner = "ControlTop", stage = "runtime", statement_id = "ControlTop.loop_0", static_trip_count = 0 : i64, static_trip_count_present = false}> ({
        ^bb0:
          "nodal.analog_if"() <{metadata = {semantic_path = "ControlTop.if_1"}, owner = "ControlTop", statement_id = "ControlTop.if_1"}> ({
          ^bb0:
            "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_1.branch_0", condition_dimension = "1", condition_kind = "boolean", condition_reads = ["ControlTop.procedure.select"], condition_value = "ControlTop.procedure.select", is_else = false, metadata = {semantic_path = "ControlTop.if_1.condition_0"}, owner = "ControlTop", stage = "runtime", static_value = false, static_value_present = false}> ({
            ^bb0:
              "nodal.analog_continue"() <{metadata = {semantic_path = "ControlTop.continue_2"}, owner = "ControlTop", statement_id = "ControlTop.continue_2"}> : () -> ()
            }) : () -> ()
            "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_1.otherwise", condition_dimension = "", condition_kind = "", condition_reads = [], condition_value = "", is_else = true, metadata = {semantic_path = "ControlTop.if_1.otherwise"}, owner = "ControlTop", stage = "else", static_value = false, static_value_present = false}> ({
            ^bb0:
'''
    continue_path += assign("ControlTop.statement_0", 0, "              ")
    continue_path += r'''            }) : () -> ()
          }) : () -> ()
        }) : () -> ()
'''
    continue_path += read_value("read_after_continue_loop") + suffix()
    write(ir / "analog-control-flow-invalid-continue-path.mlir", continue_path)


def patch_cmake(root: Path) -> None:
    path = root / "core/compiler/test/CMakeLists.txt"
    text = read(path)
    if "analog-control-flow-rejects-missing-else" in text:
        return
    tests = r'''
foreach(_fixture IN ITEMS missing-else missing-default zero-trip continue-path)
  add_test(
    NAME nodal.native.analog-control-flow-rejects-${_fixture}
    COMMAND "${CMAKE_COMMAND}"
      "-DNODALC=$<TARGET_FILE:nodalc>"
      "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-${_fixture}.mlir"
      "-DDIAGNOSTIC=NODAL-ANALOG-034-004"
      -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
  )
endforeach()

'''
    text = insert_before(
        text,
        "add_custom_target(check-nodal-native\n",
        tests,
        "native branch-sensitive dataflow tests",
    )
    write(path, text)


def patch_contract(root: Path) -> None:
    manifest_path = root / "tests/compiler/fixtures/increment34/manifest.json"
    document = json.loads(read(manifest_path))
    document["tranche"] = "34c-native-branch-sensitive-dataflow"
    document["integration"]["native_branch_sensitive_definite_assignment"] = True
    document["semantics"]["native_missing_else_intersection"] = True
    document["semantics"]["native_missing_default_intersection"] = True
    document["semantics"]["native_zero_trip_loop_conservative"] = True
    document["semantics"]["native_continue_exit_intersection"] = True
    document["deferred"] = [
        value
        for value in document["deferred"]
        if value != "native-branch-sensitive-definite-assignment"
    ]
    document["validation"] = None
    write(manifest_path, json.dumps(document, indent=2) + "\n")

    implementation_path = root / "docs/implementation/increment34-analog-control-flow.md"
    implementation = read(implementation_path).replace(
        "- [ ] Implement native branch-sensitive definite-assignment dataflow over the\n"
        "  first-class regions.\n",
        "- [x] Implement native branch-sensitive definite-assignment dataflow over the\n"
        "  first-class regions, including unmatched selection and bounded-loop exits.\n",
        1,
    )
    implementation = implementation.replace(
        "- run branch-sensitive definite-assignment as a native dataflow analysis over\n"
        "  the first-class regions;\n",
        "- complete the exact-head inherited workflow matrix and fresh review;\n",
        1,
    )
    implementation = implementation.replace(
        "- complete the exact-head inherited workflow matrix and fresh review;\n"
        "- complete the exact-head inherited workflow matrix and fresh review;\n",
        "- complete the exact-head inherited workflow matrix and fresh review;\n",
        1,
    )
    write(implementation_path, implementation)

    readme_path = root / "tests/compiler/fixtures/increment34/README.md"
    readme = read(readme_path).replace(
        "Native branch-sensitive definite-assignment, solver construction, target\n"
        "legalization, and Verilog-A or Verilog-AMS procedural lowering remain active\n"
        "Increment 34 work.\n",
        "Native branch-sensitive definite-assignment now intersects all reachable\n"
        "normal, unmatched, `break`, and `continue` exits and rejects reachable reads\n"
        "after incomplete conditionals, cases, and zero-minimum loops. Solver\n"
        "construction, target legalization, and Verilog-A or Verilog-AMS procedural\n"
        "lowering remain deferred to their owning increments.\n",
        1,
    )
    write(readme_path, readme)

    checker_path = root / "scripts/check_increment34.py"
    checker = read(checker_path)
    checker = checker.replace(
        'and manifest.get("tranche") == "34c-structured-compiler-ir",',
        'and manifest.get("tranche") == "34c-native-branch-sensitive-dataflow",',
        1,
    )
    for token in (
        '        "native_missing_else_intersection",\n',
        '        "native_missing_default_intersection",\n',
        '        "native_zero_trip_loop_conservative",\n',
        '        "native_continue_exit_intersection",\n',
    ):
        if token not in checker:
            checker = checker.replace(
                '        "structured_source_map_entries",\n',
                '        "structured_source_map_entries",\n' + token,
                1,
            )
    checker = checker.replace(
        '''    for key in (
        "native_branch_sensitive_definite_assignment",
        "target_lowering",
    ):
''',
        '''    for key in (
        "target_lowering",
    ):
''',
        1,
    )
    checker = checker.replace(
        '''        and "source-map-roundtrip" not in deferred
        and "native-branch-sensitive-definite-assignment" in deferred,
''',
        '''        and "source-map-roundtrip" not in deferred
        and "native-branch-sensitive-definite-assignment" not in deferred,
''',
        1,
    )
    dataflow_checks = r'''
    require_tokens(
        native_verifier,
        (
            "verifyStructuredDefiniteAssignment",
            "analyzeStructuredIf",
            "analyzeStructuredCase",
            "analyzeStructuredLoop",
            "intersectStructuredStates",
            '"NODAL-ANALOG-034-004"',
            "body->continues.begin()",
            "minimum.getInt() == 0",
            "unmatchedReachable",
        ),
        "NODAL-INC34-036",
        "native branch-sensitive definite assignment",
    )
    for fixture in (
        "missing-else",
        "missing-default",
        "zero-trip",
        "continue-path",
    ):
        require(
            (root / f"core/compiler/test/IR/analog-control-flow-invalid-{fixture}.mlir").is_file(),
            f"NODAL-INC34-037: missing native dataflow fixture {fixture}",
        )
    require_tokens(
        native_cmake,
        (
            "analog-control-flow-rejects-missing-else",
            "missing-default",
            "zero-trip",
            "continue-path",
            "NODAL-ANALOG-034-004",
        ),
        "NODAL-INC34-038",
        "native branch-sensitive dataflow tests",
    )

'''
    if "NODAL-INC34-036" not in checker:
        checker = insert_before(
            checker,
            "    forbidden_names = {\n",
            dataflow_checks,
            "native dataflow checker contracts",
        )
    write(checker_path, checker)

    tests_path = root / "tests/compiler/test_increment34.py"
    tests = read(tests_path)
    required_marker = '    "core/compiler/test/IR/analog-control-flow.mlir",\n'
    required = '''    "core/compiler/test/IR/analog-control-flow-invalid-missing-else.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-missing-default.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-zero-trip.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-continue-path.mlir",
'''
    if required.strip() not in tests:
        tests = replace_once(
            tests,
            required_marker,
            required_marker + required,
            "native dataflow mutation fixture inventory",
        )
    tests = tests.replace(
        'document["integration"]["native_branch_sensitive_definite_assignment"] = True',
        'document["integration"]["target_lowering"] = True',
        1,
    )
    mutation = r'''
    def test_native_branch_intersection_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "intersectStructuredStates",
                    "removedIntersectStructuredStates",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native branch-sensitive definite assignment is missing")

'''
    if "test_native_branch_intersection_mutation_is_rejected" not in tests:
        tests = insert_before(
            tests,
            "    def test_write_enabled_workflow_is_rejected(self) -> None:\n",
            mutation,
            "native dataflow mutation test",
        )
    write(tests_path, tests)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    patch_native_dataflow(root)
    patch_dataflow_fixtures(root)
    patch_cmake(root)
    patch_contract(root)
    print("Increment 34 native branch-sensitive dataflow materialized.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
