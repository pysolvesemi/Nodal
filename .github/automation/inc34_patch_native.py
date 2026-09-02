#!/usr/bin/env python3
from __future__ import annotations

import argparse
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


def patch_tablegen(root: Path) -> None:
    path = root / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
    text = read(path)
    if 'Nodal_Op<"analog_if"' in text:
        return

    definitions = r'''
def Nodal_AnalogIfOp : Nodal_Op<"analog_if", [NoTerminator]> {
  let summary = "First-match structured analog conditional";
  let arguments = (ins
    StrAttr:$statement_id,
    StrAttr:$owner,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogIfArmOp
    : Nodal_Op<"analog_if_arm", [HasParent<"AnalogIfOp">, NoTerminator]> {
  let summary = "One retained arm of a structured analog conditional";
  let arguments = (ins
    StrAttr:$arm_id,
    StrAttr:$owner,
    BoolAttr:$is_else,
    StrAttr:$stage,
    StrAttr:$condition_value,
    StrAttr:$condition_kind,
    StrAttr:$condition_dimension,
    ArrayAttr:$condition_reads,
    BoolAttr:$static_value_present,
    BoolAttr:$static_value,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogCaseOp : Nodal_Op<"analog_case", [NoTerminator]> {
  let summary = "Exact non-fall-through structured analog case selection";
  let arguments = (ins
    StrAttr:$statement_id,
    StrAttr:$owner,
    StrAttr:$selector_value,
    StrAttr:$selector_kind,
    StrAttr:$selector_dimension,
    ArrayAttr:$selector_reads,
    BoolAttr:$static_value_present,
    StrAttr:$static_value,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogCaseArmOp
    : Nodal_Op<"analog_case_arm", [HasParent<"AnalogCaseOp">, NoTerminator]> {
  let summary = "One exact non-fall-through analog case arm";
  let arguments = (ins
    StrAttr:$arm_id,
    StrAttr:$owner,
    BoolAttr:$is_default,
    ArrayAttr:$labels,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogLoopOp : Nodal_Op<"analog_loop", [NoTerminator]> {
  let summary = "Exact static or finite runtime-bounded analog loop";
  let arguments = (ins
    StrAttr:$statement_id,
    StrAttr:$owner,
    StrAttr:$stage,
    I64Attr:$minimum_iterations,
    I64Attr:$maximum_iterations,
    StrAttr:$bound_value,
    StrAttr:$bound_kind,
    StrAttr:$bound_dimension,
    ArrayAttr:$bound_reads,
    BoolAttr:$static_trip_count_present,
    I64Attr:$static_trip_count,
    DictionaryAttr:$metadata
  );
  let regions = (region SizedRegion<1>:$body);
  let hasVerifier = 1;
}

def Nodal_AnalogBreakOp : Nodal_Op<"analog_break"> {
  let summary = "Exit the nearest runtime-bounded analog loop";
  let arguments = (ins
    StrAttr:$statement_id,
    StrAttr:$owner,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

def Nodal_AnalogContinueOp : Nodal_Op<"analog_continue"> {
  let summary = "Continue the nearest runtime-bounded analog loop";
  let arguments = (ins
    StrAttr:$statement_id,
    StrAttr:$owner,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

'''
    text = insert_before(
        text,
        'def Nodal_AnalogVariableOp : Nodal_Op<"analog_variable"> {\n',
        definitions,
        "analog control-flow operation definitions",
    )
    write(path, text)


def patch_verifier(root: Path) -> None:
    path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
    text = read(path)

    if "verifyStructuredProceduralBlock" not in text:
        structured_block = r'''
LogicalResult verifyStructuredProceduralBlock(Block &block, llvm::StringRef owner) {
  for (Operation &operation : block) {
    if (llvm::isa<nodal::AnalogVariableOp, nodal::AnalogVariableReadOp,
                  nodal::AnalogAssignOp, nodal::AnalogBreakOp,
                  nodal::AnalogContinueOp>(operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured procedural operation owner does not match the enclosing component");
      continue;
    }

    if (auto scope = llvm::dyn_cast<nodal::AnalogScopeOp>(&operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured lexical scope owner does not match the enclosing component");
      if (failed(verifyStructuredProceduralBlock(scope.getOperation()->getRegion(0).front(), owner)))
        return failure();
      continue;
    }

    if (auto conditional = llvm::dyn_cast<nodal::AnalogIfOp>(&operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured conditional owner does not match the enclosing component");
      for (Operation &nested : conditional.getOperation()->getRegion(0).front()) {
        auto arm = llvm::dyn_cast<nodal::AnalogIfArmOp>(&nested);
        if (!arm)
          return nodal::emitMappedFailure(
              &nested, "NODAL-ANALOG-034-015",
              "analog conditional body may contain only analog_if_arm operations");
        if (failed(verifyStructuredProceduralBlock(arm.getOperation()->getRegion(0).front(), owner)))
          return failure();
      }
      continue;
    }

    if (auto selection = llvm::dyn_cast<nodal::AnalogCaseOp>(&operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured case owner does not match the enclosing component");
      for (Operation &nested : selection.getOperation()->getRegion(0).front()) {
        auto arm = llvm::dyn_cast<nodal::AnalogCaseArmOp>(&nested);
        if (!arm)
          return nodal::emitMappedFailure(
              &nested, "NODAL-ANALOG-034-015",
              "analog case body may contain only analog_case_arm operations");
        if (failed(verifyStructuredProceduralBlock(arm.getOperation()->getRegion(0).front(), owner)))
          return failure();
      }
      continue;
    }

    if (auto loop = llvm::dyn_cast<nodal::AnalogLoopOp>(&operation)) {
      if (textAttr(&operation, "owner") != owner)
        return nodal::emitMappedFailure(
            &operation, "NODAL-ANALOG-034-014",
            "structured loop owner does not match the enclosing component");
      if (failed(verifyStructuredProceduralBlock(loop.getOperation()->getRegion(0).front(), owner)))
        return failure();
      continue;
    }

    if (llvm::isa<nodal::AnalogIfArmOp, nodal::AnalogCaseArmOp>(operation))
      return nodal::emitMappedFailure(
          &operation, "NODAL-ANALOG-034-015",
          "analog control-flow arm must be nested directly under its owning selection");

    if (llvm::isa<nodal::AnalogProcedureOp>(operation))
      return nodal::emitMappedFailure(
          &operation, "NODAL-ANALOG-033-018",
          "nested analog procedural regions are not supported");

    return nodal::emitMappedFailure(
        &operation, "NODAL-ANALOG-034-014",
        "operation is not legal in structured analog procedural control flow");
  }
  return success();
}

'''
        text = insert_before(
            text,
            "LogicalResult verifySingleTopLevelProcedurePerModule(Operation *module) {\n",
            structured_block,
            "structured procedural block verifier",
        )

        old_tail = '''  ProceduralVerificationState state;
  state.owner = owner;
  return verifyProceduralBlock(operation->getRegion(0).front(), state);
}
'''
        new_tail = '''  bool hasStructuredControl = false;
  operation->walk([&](Operation *nested) {
    if (llvm::isa<nodal::AnalogIfOp, nodal::AnalogCaseOp,
                  nodal::AnalogLoopOp, nodal::AnalogBreakOp,
                  nodal::AnalogContinueOp>(nested))
      hasStructuredControl = true;
  });
  if (hasStructuredControl)
    return verifyStructuredProceduralBlock(operation->getRegion(0).front(), owner);

  ProceduralVerificationState state;
  state.owner = owner;
  return verifyProceduralBlock(operation->getRegion(0).front(), state);
}
'''
        text = replace_once(
            text,
            old_tail,
            new_tail,
            "structured procedure dispatch",
        )

    if "LogicalResult nodal::AnalogIfOp::verify()" not in text:
        methods = r'''
LogicalResult nodal::AnalogIfOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014",
                                       "structured analog conditional")))
    return failure();
  if (failed(requireText(getOperation(), "statement_id", "conditional statement identity")) ||
      failed(requireText(getOperation(), "owner", "conditional owner")) ||
      failed(requireSingleBlock(getOperation())))
    return failure();

  llvm::StringSet<> armIds;
  bool seenConditionalArm = false;
  bool seenElse = false;
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    auto arm = llvm::dyn_cast<nodal::AnalogIfArmOp>(&operation);
    if (!arm)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                               "analog_if body may contain only analog_if_arm operations");
    llvm::StringRef armId = textAttr(&operation, "arm_id");
    if (armId.trim().empty() || !armIds.insert(armId).second)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-001",
                               "conditional arm identity must be non-empty and unique");
    const bool isElse =
        operation.getAttrOfType<BoolAttr>("is_else").getValue();
    if (isElse) {
      if (seenElse)
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                                 "analog conditional permits only one else arm");
      seenElse = true;
    } else {
      if (seenElse)
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                                 "conditional arm cannot follow the else arm");
      seenConditionalArm = true;
    }
  }
  if (!seenConditionalArm)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "analog conditional requires at least one condition arm");
  return success();
}

LogicalResult nodal::AnalogIfArmOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014",
                                       "analog conditional arm")) ||
      failed(requireSingleBlock(getOperation())) ||
      failed(requireText(getOperation(), "arm_id", "conditional arm identity")) ||
      failed(requireText(getOperation(), "owner", "conditional arm owner")))
    return failure();
  if (getOperation()->getRegion(0).front().empty())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "conditional arm must contain at least one statement");
  if (failed(verifyStringArray(getOperation(), "condition_reads",
                               "NODAL-ANALOG-034-002", "condition read")))
    return failure();

  auto isElse = getOperation()->getAttrOfType<BoolAttr>("is_else");
  auto staticPresent =
      getOperation()->getAttrOfType<BoolAttr>("static_value_present");
  auto staticValue = getOperation()->getAttrOfType<BoolAttr>("static_value");
  if (!isElse || !staticPresent || !staticValue)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                             "conditional arm requires explicit staging metadata");

  const llvm::StringRef stage = textAttr(getOperation(), "stage");
  const llvm::StringRef value = textAttr(getOperation(), "condition_value");
  const llvm::StringRef kind = textAttr(getOperation(), "condition_kind");
  const llvm::StringRef dimension = textAttr(getOperation(), "condition_dimension");
  auto reads = getOperation()->getAttrOfType<ArrayAttr>("condition_reads");
  if (isElse.getValue()) {
    if (stage != "else" || !value.empty() || !kind.empty() ||
        !dimension.empty() || !reads.empty() || staticPresent.getValue())
      return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                               "else arm must not carry a condition or static value");
    return success();
  }

  if (!oneOf(stage, {"static", "runtime"}) || value.trim().empty() ||
      kind != "boolean" || dimension != "1")
    return emitMappedFailure(
        getOperation(), "NODAL-ANALOG-034-002",
        "conditional arm requires a dimensionless Boolean condition");
  if (stage == "static" &&
      (!staticPresent.getValue() || !reads.empty()))
    return emitMappedFailure(
        getOperation(), "NODAL-ANALOG-034-003",
        "static conditional arm requires a compile-time value without dynamic reads");
  if (stage == "runtime" && staticPresent.getValue())
    return emitMappedFailure(
        getOperation(), "NODAL-ANALOG-034-003",
        "runtime conditional arm cannot carry a compile-time selected value");
  return success();
}

LogicalResult nodal::AnalogCaseOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014",
                                       "structured analog case")) ||
      failed(requireText(getOperation(), "statement_id", "case statement identity")) ||
      failed(requireText(getOperation(), "owner", "case owner")) ||
      failed(requireSingleBlock(getOperation())) ||
      failed(verifyStringArray(getOperation(), "selector_reads",
                               "NODAL-ANALOG-034-005", "case selector read")))
    return failure();

  const llvm::StringRef kind = textAttr(getOperation(), "selector_kind");
  const llvm::StringRef dimension = textAttr(getOperation(), "selector_dimension");
  if (!oneOf(kind, {"integer", "boolean"}) || dimension != "1" ||
      textAttr(getOperation(), "selector_value").trim().empty())
    return emitMappedFailure(
        getOperation(), "NODAL-ANALOG-034-005",
        "case selector must be a dimensionless integer or Boolean value");

  auto staticPresent =
      getOperation()->getAttrOfType<BoolAttr>("static_value_present");
  auto reads = getOperation()->getAttrOfType<ArrayAttr>("selector_reads");
  const llvm::StringRef staticValue = textAttr(getOperation(), "static_value");
  if (!staticPresent)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                             "case selector requires explicit staging metadata");
  if (staticPresent.getValue()) {
    if (staticValue.empty() || !reads.empty())
      return emitMappedFailure(
          getOperation(), "NODAL-ANALOG-034-003",
          "static case selector requires one exact value without dynamic reads");
    if (!staticValue.starts_with((kind + ":").str()))
      return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-007",
                               "static case selector value does not match selector kind");
  } else if (!staticValue.empty()) {
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-003",
                             "runtime case selector cannot carry a static value");
  }

  llvm::StringSet<> armIds;
  llvm::StringSet<> labels;
  bool seenOrdinary = false;
  bool seenDefault = false;
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    auto arm = llvm::dyn_cast<nodal::AnalogCaseArmOp>(&operation);
    if (!arm)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                               "analog_case body may contain only analog_case_arm operations");
    llvm::StringRef armId = textAttr(&operation, "arm_id");
    if (armId.trim().empty() || !armIds.insert(armId).second)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-001",
                               "case arm identity must be non-empty and unique");
    auto isDefault = operation.getAttrOfType<BoolAttr>("is_default");
    auto armLabels = operation.getAttrOfType<ArrayAttr>("labels");
    if (isDefault.getValue()) {
      if (seenDefault || !armLabels.empty())
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                                 "case permits one label-free default arm");
      seenDefault = true;
      continue;
    }
    if (seenDefault)
      return emitMappedFailure(&operation, "NODAL-ANALOG-034-015",
                               "case arm cannot follow the default arm");
    seenOrdinary = true;
    for (Attribute attribute : armLabels) {
      auto label = llvm::dyn_cast<StringAttr>(attribute);
      if (!label || !label.getValue().starts_with((kind + ":").str()))
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-007",
                                 "case label kind does not match the selector");
      if (!labels.insert(label.getValue()).second)
        return emitMappedFailure(&operation, "NODAL-ANALOG-034-006",
                                 "duplicate case label");
    }
  }
  if (!seenOrdinary)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "analog case requires at least one labeled arm");
  return success();
}

LogicalResult nodal::AnalogCaseArmOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014",
                                       "analog case arm")) ||
      failed(requireSingleBlock(getOperation())) ||
      failed(requireText(getOperation(), "arm_id", "case arm identity")) ||
      failed(requireText(getOperation(), "owner", "case arm owner")) ||
      failed(verifyStringArray(getOperation(), "labels",
                               "NODAL-ANALOG-034-007", "case label")))
    return failure();
  if (getOperation()->getRegion(0).front().empty())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "case arm must contain at least one statement");
  auto isDefault = getOperation()->getAttrOfType<BoolAttr>("is_default");
  auto labels = getOperation()->getAttrOfType<ArrayAttr>("labels");
  if (!isDefault)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "case arm requires explicit default metadata");
  if (isDefault.getValue() != labels.empty())
    return emitMappedFailure(
        getOperation(), "NODAL-ANALOG-034-015",
        "default case arm must be label-free and ordinary arms require labels");
  return success();
}

LogicalResult nodal::AnalogLoopOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-014",
                                       "structured analog loop")) ||
      failed(requireSingleBlock(getOperation())) ||
      failed(requireText(getOperation(), "statement_id", "loop statement identity")) ||
      failed(requireText(getOperation(), "owner", "loop owner")) ||
      failed(requireText(getOperation(), "bound_value", "loop bound value")) ||
      failed(verifyStringArray(getOperation(), "bound_reads",
                               "NODAL-ANALOG-034-008", "loop bound read")))
    return failure();
  if (getOperation()->getRegion(0).front().empty())
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-015",
                             "analog loop body must contain at least one statement");

  const llvm::StringRef stage = textAttr(getOperation(), "stage");
  const llvm::StringRef kind = textAttr(getOperation(), "bound_kind");
  const llvm::StringRef dimension = textAttr(getOperation(), "bound_dimension");
  auto minimum = getOperation()->getAttrOfType<IntegerAttr>("minimum_iterations");
  auto maximum = getOperation()->getAttrOfType<IntegerAttr>("maximum_iterations");
  auto staticPresent =
      getOperation()->getAttrOfType<BoolAttr>("static_trip_count_present");
  auto staticCount = getOperation()->getAttrOfType<IntegerAttr>("static_trip_count");
  auto reads = getOperation()->getAttrOfType<ArrayAttr>("bound_reads");
  if (!oneOf(stage, {"static", "runtime"}) || kind != "integer" ||
      dimension != "1" || !minimum || !maximum || !staticPresent || !staticCount)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-008",
                             "loop requires a dimensionless integer finite envelope");
  const int64_t minimumValue = minimum.getInt();
  const int64_t maximumValue = maximum.getInt();
  if (minimumValue < 0 || maximumValue < minimumValue)
    return emitMappedFailure(getOperation(), "NODAL-ANALOG-034-008",
                             "loop envelope must be finite, ordered, and non-negative");

  if (stage == "static") {
    if (!staticPresent.getValue() || staticCount.getInt() != minimumValue ||
        minimumValue != maximumValue || !reads.empty())
      return emitMappedFailure(
          getOperation(), "NODAL-ANALOG-034-009",
          "static loop requires one exact non-negative compile-time trip count");
  } else {
    if (maximumValue == 0 || staticPresent.getValue())
      return emitMappedFailure(
          getOperation(), "NODAL-ANALOG-034-008",
          "runtime loop requires a positive finite maximum and no static trip count");
  }
  return success();
}

LogicalResult nodal::AnalogBreakOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-010",
                                       "analog break")) ||
      failed(requireText(getOperation(), "statement_id", "break statement identity")) ||
      failed(requireText(getOperation(), "owner", "break owner")))
    return failure();
  auto loop = getOperation()->getParentOfType<nodal::AnalogLoopOp>();
  if (!loop || textAttr(loop.getOperation(), "stage") != "runtime")
    return emitMappedFailure(
        getOperation(), "NODAL-ANALOG-034-010",
        "break is legal only in the nearest runtime-bounded analog loop");
  return success();
}

LogicalResult nodal::AnalogContinueOp::verify() {
  if (failed(requireProceduralAncestor(getOperation(), "NODAL-ANALOG-034-011",
                                       "analog continue")) ||
      failed(requireText(getOperation(), "statement_id", "continue statement identity")) ||
      failed(requireText(getOperation(), "owner", "continue owner")))
    return failure();
  auto loop = getOperation()->getParentOfType<nodal::AnalogLoopOp>();
  if (!loop || textAttr(loop.getOperation(), "stage") != "runtime")
    return emitMappedFailure(
        getOperation(), "NODAL-ANALOG-034-011",
        "continue is legal only in the nearest runtime-bounded analog loop");
  return success();
}

'''
        text = insert_before(
            text,
            "LogicalResult nodal::AnalogVariableOp::verify() {\n",
            methods,
            "analog control-flow verifier methods",
        )

    write(path, text)


BASE_PREFIX = r'''module {
  "nodal.module"() <{metadata = {}, sym_name = "ControlTop"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {semantic_path = "ControlTop.analogProcedural"}}> ({
    ^bb0:
      "nodal.analog_procedure"() <{metadata = {semantic_path = "ControlTop.analogProcedure"}, owner = "ControlTop"}> ({
      ^bb0:
'''


BASE_SUFFIX = r'''      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
'''


DECLARATIONS = r'''        %mode = "nodal.analog_variable"() <{declaration_order = 0 : i64, identity = "ControlTop.procedure.mode", initialized = true, initializer_dimension = "1", initializer_kind = "integer", initializer_reads = [], initializer_value = "0", metadata = {semantic_path = "ControlTop.procedure.mode"}, owner = "ControlTop"}> : () -> !nodal.variable<"integer", "1"> loc("AnalogControlFlow.scala":10:5)
        %select = "nodal.analog_variable"() <{declaration_order = 1 : i64, identity = "ControlTop.procedure.select", initialized = true, initializer_dimension = "1", initializer_kind = "boolean", initializer_reads = [], initializer_value = "false", metadata = {semantic_path = "ControlTop.procedure.select"}, owner = "ControlTop"}> : () -> !nodal.variable<"boolean", "1"> loc("AnalogControlFlow.scala":11:5)
        %value = "nodal.analog_variable"() <{declaration_order = 2 : i64, identity = "ControlTop.procedure.value", initialized = false, initializer_dimension = "", initializer_kind = "", initializer_reads = [], initializer_value = "", metadata = {semantic_path = "ControlTop.procedure.value"}, owner = "ControlTop"}> : () -> !nodal.variable<"real", "1"> loc("AnalogControlFlow.scala":12:5)
'''


def assign(identity: str, order: int, value: str, indent: str = "            ") -> str:
    return (
        f'{indent}"nodal.analog_assign"(%value) '
        f'<{{analyses = ["dc", "transient"], authored_order = {order} : i64, '
        f'guard_dimension = "", guard_kind = "", guard_present = false, '
        f'guard_reads = [], guard_value = "", '
        f'metadata = {{semantic_path = "{identity}", value = "{value}"}}, '
        f'owner = "ControlTop", statement_id = "{identity}", '
        f'value_dimension = "1", value_kind = "real"}}> '
        f': (!nodal.variable<"real", "1">) -> () '
        f'loc("AnalogControlFlow.scala":{20 + order}:9)\n'
    )


def patch_fixtures(root: Path) -> None:
    ir = root / "core/compiler/test/IR"

    valid = BASE_PREFIX + DECLARATIONS
    valid += r'''        "nodal.analog_case"() <{metadata = {semantic_path = "ControlTop.case_0.selector"}, owner = "ControlTop", selector_dimension = "1", selector_kind = "integer", selector_reads = ["ControlTop.procedure.mode"], selector_value = "ControlTop.procedure.mode", statement_id = "ControlTop.case_0", static_value = "", static_value_present = false}> ({
        ^bb0:
          "nodal.analog_case_arm"() <{arm_id = "ControlTop.case_0.arm_0", is_default = false, labels = ["integer:0"], metadata = {semantic_path = "ControlTop.case_0.arm_0"}, owner = "ControlTop"}> ({
          ^bb0:
'''
    valid += assign("ControlTop.statement_0", 0, "1.0", "            ")
    valid += r'''          }) : () -> ()
          "nodal.analog_case_arm"() <{arm_id = "ControlTop.case_0.default", is_default = true, labels = [], metadata = {semantic_path = "ControlTop.case_0.default"}, owner = "ControlTop"}> ({
          ^bb0:
'''
    valid += assign("ControlTop.statement_1", 1, "2.0", "            ")
    valid += r'''          }) : () -> ()
        }) : () -> ()
        "nodal.analog_loop"() <{bound_dimension = "1", bound_kind = "integer", bound_reads = ["ControlTop.procedure.mode"], bound_value = "ControlTop.procedure.mode", maximum_iterations = 4 : i64, metadata = {semantic_path = "ControlTop.loop_1"}, minimum_iterations = 1 : i64, owner = "ControlTop", stage = "runtime", statement_id = "ControlTop.loop_1", static_trip_count = 0 : i64, static_trip_count_present = false}> ({
        ^bb0:
          "nodal.analog_if"() <{metadata = {semantic_path = "ControlTop.if_2"}, owner = "ControlTop", statement_id = "ControlTop.if_2"}> ({
          ^bb0:
            "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_2.branch_0", condition_dimension = "1", condition_kind = "boolean", condition_reads = ["ControlTop.procedure.select"], condition_value = "ControlTop.procedure.select", is_else = false, metadata = {semantic_path = "ControlTop.if_2.condition_0"}, owner = "ControlTop", stage = "runtime", static_value = false, static_value_present = false}> ({
            ^bb0:
'''
    valid += assign("ControlTop.statement_2", 2, "3.0", "              ")
    valid += r'''              "nodal.analog_continue"() <{metadata = {semantic_path = "ControlTop.continue_3"}, owner = "ControlTop", statement_id = "ControlTop.continue_3"}> : () -> () loc("AnalogControlFlow.scala":31:11)
            }) : () -> ()
            "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_2.otherwise", condition_dimension = "", condition_kind = "", condition_reads = [], condition_value = "", is_else = true, metadata = {semantic_path = "ControlTop.if_2.otherwise"}, owner = "ControlTop", stage = "else", static_value = false, static_value_present = false}> ({
            ^bb0:
'''
    valid += assign("ControlTop.statement_3", 3, "4.0", "              ")
    valid += r'''              "nodal.analog_break"() <{metadata = {semantic_path = "ControlTop.break_4"}, owner = "ControlTop", statement_id = "ControlTop.break_4"}> : () -> () loc("AnalogControlFlow.scala":35:11)
            }) : () -> ()
          }) : () -> ()
        }) : () -> ()
'''
    valid += BASE_SUFFIX
    write(ir / "analog-control-flow.mlir", valid)

    invalid_break = BASE_PREFIX + DECLARATIONS
    invalid_break += r'''        "nodal.analog_break"() <{metadata = {semantic_path = "ControlTop.break_0"}, owner = "ControlTop", statement_id = "ControlTop.break_0"}> : () -> ()
'''
    invalid_break += BASE_SUFFIX
    write(ir / "analog-control-flow-invalid-break.mlir", invalid_break)

    invalid_loop = BASE_PREFIX + DECLARATIONS
    invalid_loop += r'''        "nodal.analog_loop"() <{bound_dimension = "1", bound_kind = "integer", bound_reads = ["ControlTop.procedure.mode"], bound_value = "ControlTop.procedure.mode", maximum_iterations = 0 : i64, metadata = {semantic_path = "ControlTop.loop_0"}, minimum_iterations = 0 : i64, owner = "ControlTop", stage = "runtime", statement_id = "ControlTop.loop_0", static_trip_count = 0 : i64, static_trip_count_present = false}> ({
        ^bb0:
'''
    invalid_loop += assign("ControlTop.statement_0", 0, "1.0", "          ")
    invalid_loop += r'''        }) : () -> ()
'''
    invalid_loop += BASE_SUFFIX
    write(ir / "analog-control-flow-invalid-loop.mlir", invalid_loop)

    invalid_condition = BASE_PREFIX + DECLARATIONS
    invalid_condition += r'''        "nodal.analog_if"() <{metadata = {semantic_path = "ControlTop.if_0"}, owner = "ControlTop", statement_id = "ControlTop.if_0"}> ({
        ^bb0:
          "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_0.branch_0", condition_dimension = "voltage", condition_kind = "boolean", condition_reads = ["ControlTop.procedure.select"], condition_value = "ControlTop.procedure.select", is_else = false, metadata = {semantic_path = "ControlTop.if_0.condition_0"}, owner = "ControlTop", stage = "runtime", static_value = false, static_value_present = false}> ({
          ^bb0:
'''
    invalid_condition += assign("ControlTop.statement_0", 0, "1.0", "            ")
    invalid_condition += r'''          }) : () -> ()
        }) : () -> ()
'''
    invalid_condition += BASE_SUFFIX
    write(ir / "analog-control-flow-invalid-condition.mlir", invalid_condition)

    invalid_case = BASE_PREFIX + DECLARATIONS
    invalid_case += r'''        "nodal.analog_case"() <{metadata = {semantic_path = "ControlTop.case_0.selector"}, owner = "ControlTop", selector_dimension = "1", selector_kind = "integer", selector_reads = ["ControlTop.procedure.mode"], selector_value = "ControlTop.procedure.mode", statement_id = "ControlTop.case_0", static_value = "", static_value_present = false}> ({
        ^bb0:
          "nodal.analog_case_arm"() <{arm_id = "ControlTop.case_0.arm_0", is_default = false, labels = ["integer:0"], metadata = {semantic_path = "ControlTop.case_0.arm_0"}, owner = "ControlTop"}> ({
          ^bb0:
'''
    invalid_case += assign("ControlTop.statement_0", 0, "1.0", "            ")
    invalid_case += r'''          }) : () -> ()
          "nodal.analog_case_arm"() <{arm_id = "ControlTop.case_0.arm_1", is_default = false, labels = ["integer:0"], metadata = {semantic_path = "ControlTop.case_0.arm_1"}, owner = "ControlTop"}> ({
          ^bb0:
'''
    invalid_case += assign("ControlTop.statement_1", 1, "2.0", "            ")
    invalid_case += r'''          }) : () -> ()
        }) : () -> ()
'''
    invalid_case += BASE_SUFFIX
    write(ir / "analog-control-flow-invalid-case.mlir", invalid_case)


def patch_cmake(root: Path) -> None:
    path = root / "core/compiler/test/CMakeLists.txt"
    text = read(path)
    if "nodal.native.analog-control-flow-roundtrip" in text:
        return
    tests = r'''
add_test(
  NAME nodal.native.analog-control-flow-roundtrip
  COMMAND nodalc
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow.mlir"
)
set_tests_properties(
  nodal.native.analog-control-flow-roundtrip
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]analog_loop"
)

add_test(
  NAME nodal.native.analog-control-flow-source-map-roundtrip
  COMMAND nodalc
    --mlir-print-op-generic
    --mlir-print-debuginfo
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow.mlir"
)
set_tests_properties(
  nodal.native.analog-control-flow-source-map-roundtrip
  PROPERTIES
    PASS_REGULAR_EXPRESSION "AnalogControlFlow[.]scala"
)

foreach(_fixture IN ITEMS break loop condition case)
  add_test(
    NAME nodal.native.analog-control-flow-rejects-${_fixture}
    COMMAND "${CMAKE_COMMAND}"
      "-DNODALC=$<TARGET_FILE:nodalc>"
      "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-${_fixture}.mlir"
      "-DDIAGNOSTIC=NODAL-ANALOG-034-"
      -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
  )
endforeach()

'''
    text = insert_before(
        text,
        "add_custom_target(check-nodal-native\n",
        tests,
        "native control-flow tests",
    )
    write(path, text)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    patch_tablegen(root)
    patch_verifier(root)
    patch_fixtures(root)
    patch_cmake(root)
    print("Increment 34 native structured IR patch materialized.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
