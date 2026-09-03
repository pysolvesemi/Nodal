#!/usr/bin/env python3
"""Apply final Increment 34 reference-visibility and declaration-order hardening."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)


def patch_scala_runtime(root: Path) -> None:
    path = root / "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
    text = read(path)
    old = '''    validateBlock(root, identities, declarations, Vector.empty, isRoot = true)
'''
    new = '''    initiallyInitialized.foreach: identity =>
      validateVariable(identity, "initially initialized variable", root.identity)
    validateBlock(
      root,
      identities,
      declarations,
      initiallyInitialized,
      Vector.empty,
      isRoot = true
    )
'''
    text = replace_once(text, old, new, "Scala analyzer visible root set")

    start = text.index("  private def validateBlock(")
    end = text.index("\n  private def checkReads", start)
    replacement = '''  private def requireVisibleReferences(
      references: Iterable[String],
      visible: Set[String],
      label: String,
      path: String
  ): Unit =
    references.foreach: reference =>
      validateVariable(reference, label, path)
    val missing = references.filterNot(visible.contains).toVector.distinct.sorted
    if missing.nonEmpty then
      fail(
        "NODAL-ANALOG-034-014",
        s"$label references variables outside their declaration scope: ${missing.mkString(",")}",
        Some(path)
      )

  private def validateBlock(
      block: Block,
      identities: mutable.Set[String],
      declarations: mutable.Set[String],
      visibleAtEntry: Set[String],
      loopStack: Vector[LoopStage],
      isRoot: scala.Boolean
  ): Unit =
    validateIdentity(block.identity, identities)
    var visible = visibleAtEntry
    block.statements.foreach:
      case declaration: Statement.Declare =>
        validateIdentity(declaration.identity, identities)
        validateVariable(declaration.variable, "declaration variable", declaration.identity)
        requireVisibleReferences(
          declaration.initializerReads,
          visible,
          "declaration initializer",
          declaration.identity
        )
        if declarations.contains(declaration.variable) then
          fail(
            "NODAL-ANALOG-034-014",
            s"duplicate control-flow variable '${declaration.variable}'",
            Some(declaration.identity)
          )
        declarations += declaration.variable
        if isRoot && declaration.local then
          fail(
            "NODAL-ANALOG-034-014",
            "root declaration cannot be marked local",
            Some(declaration.identity)
          )
        if !isRoot && !declaration.local then
          fail(
            "NODAL-ANALOG-034-014",
            "nested declaration must be block-local",
            Some(declaration.identity)
          )
        visible = visible + declaration.variable
      case assignment: Statement.Assign =>
        validateIdentity(assignment.identity, identities)
        requireVisibleReferences(
          Vector(assignment.target) ++ assignment.reads.toVector,
          visible,
          "control-flow assignment",
          assignment.identity
        )
      case read: Statement.Read =>
        validateIdentity(read.identity, identities)
        requireVisibleReferences(
          Vector(read.variable),
          visible,
          "control-flow read",
          read.identity
        )
      case scope: Statement.Scope =>
        validateIdentity(scope.identity, identities)
        validateBlock(
          scope.body,
          identities,
          declarations,
          visible,
          loopStack,
          isRoot = false
        )
      case conditional: Statement.IfThenElse =>
        validateIdentity(conditional.identity, identities)
        if conditional.branches.isEmpty then
          fail(
            "NODAL-ANALOG-034-015",
            "conditional requires at least one branch",
            Some(conditional.identity)
          )
        conditional.branches.zipWithIndex.foreach: (branch, index) =>
          val conditionPath = s"${conditional.identity}.condition_$index"
          validateCondition(branch.condition, conditionPath)
          requireVisibleReferences(
            branch.condition.reads,
            visible,
            "conditional condition",
            conditionPath
          )
          requireNonEmptyBlock(
            branch.body,
            "conditional branch",
            s"${conditional.identity}.branch_$index"
          )
          validateBlock(
            branch.body,
            identities,
            declarations,
            visible,
            loopStack,
            isRoot = false
          )
        conditional.otherwise.foreach: alternative =>
          requireNonEmptyBlock(alternative, "conditional else branch", conditional.identity)
          validateBlock(
            alternative,
            identities,
            declarations,
            visible,
            loopStack,
            isRoot = false
          )
      case selection: Statement.CaseStatement =>
        validateIdentity(selection.identity, identities)
        validateSelector(selection.selector, selection.identity)
        requireVisibleReferences(
          selection.selector.reads,
          visible,
          "case selector",
          selection.identity
        )
        if selection.arms.isEmpty then
          fail(
            "NODAL-ANALOG-034-015",
            "case statement requires at least one explicit arm",
            Some(selection.identity)
          )
        val labels = mutable.HashSet.empty[String]
        selection.arms.foreach: arm =>
          if arm.labels.isEmpty then
            fail(
              "NODAL-ANALOG-034-015",
              "case arm requires at least one label",
              Some(selection.identity)
            )
          arm.labels.foreach: label =>
            if labelKind(label) != selection.selector.kind then
              fail(
                "NODAL-ANALOG-034-007",
                "case label kind does not match selector kind",
                Some(selection.identity)
              )
            val key = labelKey(label)
            if labels.contains(key) then
              fail(
                "NODAL-ANALOG-034-006",
                s"duplicate case label '$key'",
                Some(selection.identity)
              )
            labels += key
        selection.arms.zipWithIndex.foreach: (arm, index) =>
          requireNonEmptyBlock(arm.body, "case arm", s"${selection.identity}.arm_$index")
          validateBlock(
            arm.body,
            identities,
            declarations,
            visible,
            loopStack,
            isRoot = false
          )
        selection.default.foreach: alternative =>
          requireNonEmptyBlock(alternative, "case default arm", selection.identity)
          validateBlock(
            alternative,
            identities,
            declarations,
            visible,
            loopStack,
            isRoot = false
          )
      case loop: Statement.Loop =>
        validateIdentity(loop.identity, identities)
        val expectedBoundType = AnalogProceduralRuntime.ValueType(
          AnalogProceduralRuntime.ScalarKind.Integer,
          "dimensionless"
        )
        if loop.boundValueType != expectedBoundType then
          fail(
            "NODAL-ANALOG-034-008",
            "bounded loop requires a dimensionless integer bound",
            Some(loop.identity)
          )
        requireVisibleReferences(
          loop.boundReads,
          visible,
          "loop bound",
          loop.identity
        )
        if loop.minimumIterations < 0 ||
          loop.maximumIterations < 0 ||
          loop.minimumIterations > loop.maximumIterations
        then
          fail(
            "NODAL-ANALOG-034-008",
            "bounded loop requires 0 <= minimum <= maximum",
            Some(loop.identity)
          )
        loop.stage match
          case LoopStage.Static
              if loop.minimumIterations != loop.maximumIterations ||
                loop.boundReads.nonEmpty ||
                !loop.staticTripCount.contains(loop.minimumIterations) =>
            fail(
              "NODAL-ANALOG-034-009",
              "static loop requires one exact compile-time trip count",
              Some(loop.identity)
            )
          case LoopStage.RuntimeBounded
              if loop.maximumIterations == 0 || loop.staticTripCount.nonEmpty =>
            fail(
              "NODAL-ANALOG-034-008",
              "runtime loop requires a positive finite maximum and a dynamic bound",
              Some(loop.identity)
            )
          case _ => ()
        validateBlock(
          loop.body,
          identities,
          declarations,
          visible,
          loopStack :+ loop.stage,
          isRoot = false
        )
      case exit: Statement.Break =>
        validateIdentity(exit.identity, identities)
        if loopStack.lastOption != Some(LoopStage.RuntimeBounded) then
          fail(
            "NODAL-ANALOG-034-010",
            "break is legal only in the nearest runtime-bounded loop",
            Some(exit.identity)
          )
      case next: Statement.Continue =>
        validateIdentity(next.identity, identities)
        if loopStack.lastOption != Some(LoopStage.RuntimeBounded) then
          fail(
            "NODAL-ANALOG-034-011",
            "continue is legal only in the nearest runtime-bounded loop",
            Some(next.identity)
          )
'''
    text = text[:start] + replacement + text[end:]
    write(path, text)


def patch_runtime_witness(root: Path) -> None:
    path = root / "examples/continuousTimeApi/src/nodal/increment34fixture/Increment34RuntimeCheck.scala"
    text = read(path)
    old = '''  private def assignment(identity: String, target: String): Statement.Assign =
    Statement.Assign(identity, target)
'''
    new = old + '''
  private def declaration(
      identity: String,
      variable: String,
      initialized: Boolean = false,
      local: Boolean = false
  ): Statement.Declare =
    Statement.Declare(identity, variable, initialized = initialized, local = local)
'''
    text = replace_once(text, old, new, "runtime declaration helper")

    replacements = (
        (
            '''        Vector(
          Statement.IfThenElse(
            "conditional",
''',
            '''        Vector(
          declaration("conditional-value-declaration", "value"),
          Statement.IfThenElse(
            "conditional",
''',
            "conditional declaration",
        ),
        (
            '''          Vector(
            Statement.IfThenElse(
              "missing-else",
''',
            '''          Vector(
            declaration("missing-else-value-declaration", "value"),
            Statement.IfThenElse(
              "missing-else",
''',
            "missing else declaration",
        ),
        (
            '''        Vector(
          Statement.CaseStatement(
            "case",
''',
            '''        Vector(
          declaration("case-value-declaration", "value"),
          Statement.CaseStatement(
            "case",
''',
            "case declaration",
        ),
        (
            '''          Vector(
            Statement.CaseStatement(
              "duplicate-case",
''',
            '''          Vector(
            declaration("duplicate-case-value-declaration", "value"),
            Statement.CaseStatement(
              "duplicate-case",
''',
            "duplicate case declaration",
        ),
        (
            '''        Vector(
          Statement.Loop(
            "guaranteed-loop",
''',
            '''        Vector(
          declaration("guaranteed-loop-value-declaration", "value"),
          Statement.Loop(
            "guaranteed-loop",
''',
            "guaranteed loop declaration",
        ),
        (
            '''          Vector(
            Statement.Loop(
              "zero-trip-loop",
''',
            '''          Vector(
            declaration("zero-trip-loop-value-declaration", "value"),
            Statement.Loop(
              "zero-trip-loop",
''',
            "zero trip declaration",
        ),
        (
            '''          Vector(
            Statement.Loop(
              "continue-loop",
''',
            '''          Vector(
            declaration("continue-loop-value-declaration", "value"),
            Statement.Loop(
              "continue-loop",
''',
            "continue loop declaration",
        ),
        (
            '''        Vector(
          Statement.IfThenElse(
            "static-if",
''',
            '''        Vector(
          declaration("static-uninitialized-declaration", "uninitialized"),
          declaration("static-value-declaration", "value"),
          Statement.IfThenElse(
            "static-if",
''',
            "static declarations",
        ),
    )
    for old_text, new_text, label in replacements:
        text = replace_once(text, old_text, new_text, label)

    marker = '''    expect("NODAL-ANALOG-034-014"):
      AnalogControlFlowRuntime.analyze(
        Block(
          "nested-nonlocal-root",
'''
    addition = '''    expect("NODAL-ANALOG-034-014"):
      AnalogControlFlowRuntime.analyze(
        Block(
          "unreachable-unknown-root",
          Vector(
            Statement.IfThenElse(
              "unreachable-unknown-if",
              Vector(
                ConditionalBranch(
                  Condition.static(false),
                  Block(
                    "unreachable-unknown-body",
                    Vector(Statement.Read("unreachable-unknown-read", "missing"))
                  )
                )
              ),
              None
            )
          )
        )
      )

    expect("NODAL-ANALOG-034-014"):
      AnalogControlFlowRuntime.analyze(
        Block(
          "escaped-local-root",
          Vector(
            Statement.Scope(
              "escaped-local-scope",
              Block(
                "escaped-local-body",
                Vector(
                  declaration(
                    "escaped-local-declaration",
                    "escaped-local",
                    initialized = true,
                    local = true
                  )
                )
              )
            ),
            Statement.Read("escaped-local-read", "escaped-local")
          )
        )
      )

    expect("NODAL-ANALOG-034-014"):
      AnalogControlFlowRuntime.analyze(
        Block(
          "forward-reference-root",
          Vector(
            Statement.Read("forward-reference-read", "future"),
            declaration("forward-reference-declaration", "future")
          )
        )
      )

'''
    if "unreachable-unknown-root" not in text:
        text = replace_once(text, marker, addition + marker, "runtime structural reference witnesses")

    old = '''      "nested_nonlocal=NODAL-ANALOG-034-014"
'''
    new = '''      "nested_nonlocal=NODAL-ANALOG-034-014",
      "unreachable_unknown=NODAL-ANALOG-034-014",
      "escaped_local=NODAL-ANALOG-034-014",
      "forward_reference=NODAL-ANALOG-034-014"
'''
    text = replace_once(text, old, new, "runtime structural report")
    write(path, text)


def patch_native_verifier(root: Path) -> None:
    path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
    text = read(path)

    old = '''bool isVisibleFrom(Block *useBlock, Block *declarationBlock) {
  for (Block *current = useBlock; current;) {
    if (current == declarationBlock)
      return true;
    Operation *parent = current->getParentOp();
    current = parent ? parent->getBlock() : nullptr;
  }
  return false;
}
'''
    new = old + '''
bool isDeclaredBeforeStructuredUse(Operation *declaration, Operation *use) {
  if (!declaration || !use)
    return false;
  Block *declarationBlock = declaration->getBlock();
  Block *currentBlock = use->getBlock();
  Operation *useAnchor = use;
  while (currentBlock && currentBlock != declarationBlock) {
    Operation *parent = currentBlock->getParentOp();
    if (!parent)
      return false;
    useAnchor = parent;
    currentBlock = parent->getBlock();
  }
  return currentBlock == declarationBlock && declaration != useAnchor &&
         declaration->isBeforeInBlock(useAnchor);
}
'''
    text = replace_once(text, old, new, "native declaration-before-use helper")

    text = replace_once(
        text,
        '''struct StructuredVariableInfo {
  Block *declarationBlock = nullptr;
};
''',
        '''struct StructuredVariableInfo {
  Operation *declaration = nullptr;
  Block *declarationBlock = nullptr;
};
''',
        "native structured variable declaration pointer",
    )
    text = replace_once(
        text,
        '''      if (!context.variables.try_emplace(identity, StructuredVariableInfo{operation.getBlock()})
''',
        '''      if (!context.variables
               .try_emplace(identity, StructuredVariableInfo{&operation, operation.getBlock()})
''',
        "native structured variable registration",
    )

    old = '''  if (!isVisibleFrom(operation->getBlock(), variable->second.declarationBlock))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                    llvm::Twine("structured variable '") + identity +
                                        "' is outside its lexical declaration scope");
  return success();
}
'''
    new = '''  if (!isVisibleFrom(operation->getBlock(), variable->second.declarationBlock))
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-034-014",
                                    llvm::Twine("structured variable '") + identity +
                                        "' is outside its lexical declaration scope");
  if (!isDeclaredBeforeStructuredUse(variable->second.declaration, operation))
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        llvm::Twine("structured variable '") + identity + "' must be declared before use");
  return success();
}
'''
    text = replace_once(text, old, new, "native structured reference order")

    old = '''  llvm::StringRef owner = textAttr(operation, "owner");
  if (owner.trim().empty())
    return nodal::emitMappedFailure(operation, "NODAL-ANALOG-033-009",
                                    "analog procedural owner must be non-empty");
'''
    new = '''  llvm::StringRef owner = textAttr(operation, "owner");
  if (owner.trim().empty() || owner.trim() != owner)
    return nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-033-009",
        "analog procedural owner must be non-empty and canonical");
'''
    text = replace_once(text, old, new, "native canonical procedure owner")
    write(path, text)


def patch_native_fixtures(root: Path) -> None:
    forward = root / "core/compiler/test/IR/analog-control-flow-invalid-forward-reference.mlir"
    write(
        forward,
        '''module {
  "nodal.module"() <{metadata = {}, sym_name = "ControlTop"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {semantic_path = "ControlTop.analogProcedural"}}> ({
    ^bb0:
      "nodal.analog_procedure"() <{metadata = {semantic_path = "ControlTop.analogProcedure"}, owner = "ControlTop"}> ({
      ^bb0:
        %value = "nodal.analog_variable"() <{declaration_order = 0 : i64, identity = "ControlTop.procedure.value", initialized = false, initializer_dimension = "", initializer_kind = "", initializer_reads = [], initializer_value = "", metadata = {semantic_path = "ControlTop.procedure.value"}, owner = "ControlTop"}> : () -> !nodal.variable<"real", "1">
        "nodal.analog_if"() <{metadata = {semantic_path = "ControlTop.if_0"}, owner = "ControlTop", statement_id = "ControlTop.if_0"}> ({
        ^bb0:
          "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_0.branch_0", condition_dimension = "1", condition_kind = "boolean", condition_reads = [], condition_value = "false", is_else = false, metadata = {semantic_path = "ControlTop.if_0.condition_0"}, owner = "ControlTop", stage = "static", static_value = false, static_value_present = true}> ({
          ^bb0:
            "nodal.analog_assign"(%value) <{analyses = ["dc", "transient"], authored_order = 0 : i64, guard_dimension = "1", guard_kind = "boolean", guard_present = true, guard_reads = ["ControlTop.procedure.future"], guard_value = "future", metadata = {semantic_path = "ControlTop.statement_0", value = "1.0"}, owner = "ControlTop", statement_id = "ControlTop.statement_0", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">) -> ()
          }) : () -> ()
        }) : () -> ()
        %future = "nodal.analog_variable"() <{declaration_order = 1 : i64, identity = "ControlTop.procedure.future", initialized = false, initializer_dimension = "", initializer_kind = "", initializer_reads = [], initializer_value = "", metadata = {semantic_path = "ControlTop.procedure.future"}, owner = "ControlTop"}> : () -> !nodal.variable<"boolean", "1">
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
''',
    )

    owner = root / "core/compiler/test/IR/analog-control-flow-invalid-owner.mlir"
    write(
        owner,
        '''module {
  "nodal.module"() <{metadata = {}, sym_name = "ControlTop"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {semantic_path = "ControlTop.analogProcedural"}}> ({
    ^bb0:
      "nodal.analog_procedure"() <{metadata = {semantic_path = "ControlTop.analogProcedure"}, owner = " ControlTop"}> ({
      ^bb0:
        %value = "nodal.analog_variable"() <{declaration_order = 0 : i64, identity = "ControlTop.procedure.value", initialized = true, initializer_dimension = "1", initializer_kind = "real", initializer_reads = [], initializer_value = "1.0", metadata = {semantic_path = "ControlTop.procedure.value"}, owner = " ControlTop"}> : () -> !nodal.variable<"real", "1">
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
''',
    )


def patch_native_cmake(root: Path) -> None:
    path = root / "core/compiler/test/CMakeLists.txt"
    text = read(path)
    marker = '''add_test(
  NAME nodal.native.analog-control-flow-rejects-case-label
'''
    addition = '''add_test(
  NAME nodal.native.analog-control-flow-rejects-forward-reference
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-forward-reference.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-014"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

add_test(
  NAME nodal.native.analog-control-flow-rejects-owner
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-owner.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-033-009"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

'''
    if "analog-control-flow-rejects-forward-reference" not in text:
        text = replace_once(text, marker, addition + marker, "native order and owner tests")
    write(path, text)


def patch_manifest(root: Path) -> None:
    path = root / "tests/compiler/fixtures/increment34/manifest.json"
    document = json.loads(read(path))
    semantics = document.setdefault("semantics", {})
    semantics["scala_structural_reference_visibility"] = True
    semantics["native_declaration_before_reference"] = True
    semantics["native_canonical_procedure_owner"] = True
    write(path, json.dumps(document, indent=2) + "\n")


def patch_checker(root: Path) -> None:
    path = root / "scripts/check_increment34.py"
    text = read(path)
    old = '''        "native_missing_else_intersection",
'''
    new = old + '''        "scala_structural_reference_visibility",
        "native_declaration_before_reference",
        "native_canonical_procedure_owner",
'''
    text = replace_once(text, old, new, "final reference manifest semantics")

    old = '''            "nested declaration must be block-local",
            "states.tail.foldLeft(first)(_ intersect _)",
'''
    new = '''            "nested declaration must be block-local",
            "private def requireVisibleReferences",
            "references variables outside their declaration scope",
            "visibleAtEntry: Set[String]",
            "states.tail.foldLeft(first)(_ intersect _)",
'''
    text = replace_once(text, old, new, "Scala structural reference checker tokens")

    old = '''            "nested_nonlocal=NODAL-ANALOG-034-014",
        ),
'''
    new = '''            "nested_nonlocal=NODAL-ANALOG-034-014",
            "unreachable_unknown=NODAL-ANALOG-034-014",
            "escaped_local=NODAL-ANALOG-034-014",
            "forward_reference=NODAL-ANALOG-034-014",
        ),
'''
    text = replace_once(text, old, new, "Scala structural witness checker tokens")

    old = '''            "verifyStructuredReferences",
            "structured declaration order must be contiguous and authored",
'''
    new = '''            "verifyStructuredReferences",
            "isDeclaredBeforeStructuredUse",
            "must be declared before use",
            "analog procedural owner must be non-empty and canonical",
            "structured declaration order must be contiguous and authored",
'''
    text = replace_once(text, old, new, "native order and owner checker tokens")

    old = '''        "unreachable-reference",
    ):
'''
    new = '''        "unreachable-reference",
        "forward-reference",
        "owner",
    ):
'''
    text = replace_once(text, old, new, "native order and owner fixture inventory")

    old = '''            "analog-control-flow-rejects-unreachable-reference",
        ),
'''
    new = '''            "analog-control-flow-rejects-unreachable-reference",
            "analog-control-flow-rejects-forward-reference",
            "analog-control-flow-rejects-owner",
        ),
'''
    text = replace_once(text, old, new, "native order and owner CMake tokens")
    write(path, text)


def patch_contract_tests(root: Path) -> None:
    path = root / "tests/compiler/test_increment34.py"
    text = read(path)
    old = '''    "core/compiler/test/IR/analog-control-flow-invalid-unreachable-reference.mlir",
'''
    new = old + '''    "core/compiler/test/IR/analog-control-flow-invalid-forward-reference.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-owner.mlir",
'''
    text = replace_once(text, old, new, "required order and owner fixtures")

    marker = '''    def test_write_enabled_workflow_is_rejected(self) -> None:
'''
    addition = '''    def test_scala_structural_reference_visibility_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "private def requireVisibleReferences",
                    "private def removedRequireVisibleReferences",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "Scala control-flow runtime is missing")

    def test_native_declaration_order_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "isDeclaredBeforeStructuredUse",
                    "removedIsDeclaredBeforeStructuredUse",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured verifier hardening is missing")

    def test_native_owner_canonicality_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "analog procedural owner must be non-empty and canonical",
                    "analog procedural owner must be non-empty",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured verifier hardening is missing")

    def test_final_reference_manifest_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["semantics"]["native_declaration_before_reference"] = False
            path.write_text(json.dumps(document, indent=2) + "\\n", encoding="utf-8")
            self.assert_rejected(root, "semantic contract")

'''
    if "test_native_declaration_order_mutation_is_rejected" not in text:
        text = replace_once(text, marker, addition + marker, "final reference mutation tests")
    write(path, text)


def patch_readme(root: Path) -> None:
    path = root / "tests/compiler/fixtures/increment34/README.md"
    text = read(path)
    marker = '''Solver execution and target lowering remain deferred.
'''
    addition = '''All source-semantic references are checked against the declaration set visible at their authored position, including statically unreachable blocks. Native identity inventories additionally require declarations to dominate their uses, and direct native procedures reject non-canonical owners.

'''
    if "declarations to dominate their uses" not in text:
        text = replace_once(text, marker, addition + marker, "final reference README evidence")
    write(path, text)


def patch_design_gate(root: Path) -> None:
    path = root / "docs/design-gates/NodalAnalogControlFlow-DG-v0.1.md"
    text = read(path)
    marker = '''- A lexical scope authored before the first explicit control statement keeps the same semantic identity if the procedure later becomes structured.
'''
    addition = marker + '''- Every variable reference is resolved against declarations visible at the authored position, even when static staging makes the containing block unreachable for dataflow.
- Direct native procedures require canonical owner identities and declarations that dominate all identity-based reads.
'''
    text = replace_once(text, marker, addition, "final reference design rules")
    write(path, text)


def patch_implementation(root: Path) -> None:
    path = root / "docs/implementation/increment34-analog-control-flow.md"
    text = read(path)
    marker = '''- [x] Keep recorder and structured identities aligned for lexical scopes authored before the first explicit control construct.
'''
    addition = marker + '''- [x] Resolve source-semantic references against declarations visible at each authored position, including static-dead blocks.
- [x] Require native variable declarations to dominate identity-based uses and reject non-canonical native procedure owners.
'''
    text = replace_once(text, marker, addition, "final reference implementation evidence")
    write(path, text)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    patch_scala_runtime(root)
    patch_runtime_witness(root)
    patch_native_verifier(root)
    patch_native_fixtures(root)
    patch_native_cmake(root)
    patch_manifest(root)
    patch_checker(root)
    patch_contract_tests(root)
    patch_readme(root)
    patch_design_gate(root)
    patch_implementation(root)
    print("Increment 34 final reference-order hardening applied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
