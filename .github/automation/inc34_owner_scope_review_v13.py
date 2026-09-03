#!/usr/bin/env python3
"""Apply Increment 34 owner canonicality and lexical-scope alignment fixes."""

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


def patch_control_construction(root: Path) -> None:
    path = root / "core/scala/api/src/nodal/AnalogControlFlowConstruction.scala"
    text = read(path)

    marker = '''  import AnalogControlFlowRuntime.*

'''
    helper = '''  import AnalogControlFlowRuntime.*

  private def requireCanonicalOwner(value: String, role: String): String =
    val canonical = value.trim
    if canonical.isEmpty || canonical != value then
      AnalogControlFlowRuntime.fail(
        "NODAL-ANALOG-034-001",
        s"$role must be non-empty and canonical"
      )
    canonical

'''
    text = replace_once(text, marker, helper, "canonical owner helper")

    old = '''    def remapOwner(newOwner: String): Snapshot =
      val canonical = newOwner.trim
      if canonical.isEmpty then
        AnalogControlFlowRuntime.fail(
          "NODAL-ANALOG-034-001",
          "control-flow owner must be non-empty"
        )

      def remapPath(value: String): String =
        if value == owner then canonical
        else if value.startsWith(s"$owner.") then
          s"$canonical${value.drop(owner.length)}"
        else value
'''
    new = '''    def remapOwner(newOwner: String): Snapshot =
      val currentOwner = requireCanonicalOwner(owner, "control-flow source owner")
      val canonical = requireCanonicalOwner(newOwner, "control-flow destination owner")

      def remapPath(value: String): String =
        if value == currentOwner then canonical
        else if value.startsWith(s"$currentOwner.") then
          s"$canonical${value.drop(currentOwner.length)}"
        else value
'''
    text = replace_once(text, old, new, "safe owner remapping")
    text = replace_once(
        text,
        '''          val matchIndex = value.indexOf(owner, cursor)
''',
        '''          val matchIndex = value.indexOf(currentOwner, cursor)
''',
        "rendered owner search",
    )
    text = replace_once(
        text,
        '''            val end = matchIndex + owner.length
''',
        '''            val end = matchIndex + currentOwner.length
''',
        "rendered owner match length",
    )

    old = '''  final class Builder(val owner: String):
    private val root = new MutableBlock(s"$owner.procedure", None)
'''
    new = '''  final class Builder(val owner: String):
    private val canonicalOwner = requireCanonicalOwner(owner, "control-flow owner")
    private val root = new MutableBlock(s"$canonicalOwner.procedure", None)
'''
    text = replace_once(text, old, new, "builder owner validation")
    text = replace_once(
        text,
        '''      val identity = s"$owner.${kind}_$controlSerial"
''',
        '''      val identity = s"$canonicalOwner.${kind}_$controlSerial"
''',
        "control identity owner",
    )
    text = replace_once(
        text,
        '''      val identity = s"$owner.scope_$lexicalSerial"
''',
        '''      val identity = s"$canonicalOwner.scope_$lexicalSerial"
''',
        "lexical identity owner",
    )

    old = '''    def lexicalScope[A](
        source: Option[AnalogProceduralRuntime.Source]
    )(body: String => A): A =
      requireStatementPosition()
      val identity = nextLexicalIdentity()
'''
    new = '''    def lexicalScope[A](
        source: Option[AnalogProceduralRuntime.Source],
        identityOverride: Option[String] = None
    )(body: String => A): A =
      requireStatementPosition()
      val identity = identityOverride.getOrElse(nextLexicalIdentity())
'''
    text = replace_once(text, old, new, "lexical identity override")
    text = replace_once(
        text,
        '''      Snapshot(owner, frozen, AnalogControlFlowRuntime.analyze(frozen))
''',
        '''      Snapshot(canonicalOwner, frozen, AnalogControlFlowRuntime.analyze(frozen))
''',
        "canonical snapshot owner",
    )
    write(path, text)


def patch_procedural_construction(root: Path) -> None:
    path = root / "core/scala/api/src/nodal/AnalogProceduralConstruction.scala"
    text = read(path)
    old = '''      val builder = activeBuilder(module)
      val source = ConstructionKernel.captureAnalogProceduralSource
      builder.lexicalScope(source): builderIdentity =>
        if builder.hasStructuredControl then
          withControlScope(module, builderIdentity)(body)
        else
          val recorderIdentity = s"block_${module.scopeSerial}"
          module.scopeSerial += 1
          val stableScope = s"$recorderIdentity#${module.scopeSerial}"
          module.controlScope = module.controlScope :+ stableScope
          try module.recorder.scope(recorderIdentity)(body)
          finally module.controlScope = module.controlScope.dropRight(1)
'''
    new = '''      val builder = activeBuilder(module)
      val source = ConstructionKernel.captureAnalogProceduralSource
      if builder.hasStructuredControl then
        builder.lexicalScope(source): builderIdentity =>
          withControlScope(module, builderIdentity)(body)
      else
        val recorderIdentity = s"block_${module.scopeSerial}"
        module.scopeSerial += 1
        val stableScope = s"$recorderIdentity#${module.scopeSerial}"
        val builderIdentity =
          s"${module.owner}.${(module.controlScope :+ stableScope).mkString(".")}"
        builder.lexicalScope(source, Some(builderIdentity)): _ =>
          module.controlScope = module.controlScope :+ stableScope
          try module.recorder.scope(recorderIdentity)(body)
          finally module.controlScope = module.controlScope.dropRight(1)
'''
    text = replace_once(text, old, new, "pre-control lexical scope alignment")
    write(path, text)


def patch_construction_tests(root: Path) -> None:
    path = root / "core/scala/testkit/test/src/nodal/AnalogControlFlowConstructionTests.scala"
    text = read(path)
    class_marker = '''final class PublicAnalogControlChild extends Module:
'''
    class_block = '''final class PublicAnalogScopeBeforeStructuredControl extends Module:
  val select: Variable[Bool] = variable(Bool, false.B)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    initial:
      val local = variable(Real, 1.0.real)
      local := 2.0.real
    analogConditional:
      analogWhen(select):
        sink := 3.0.real
      analogOtherwise:
        sink := 4.0.real

'''
    if "PublicAnalogScopeBeforeStructuredControl" not in text:
        text = replace_once(text, class_marker, class_block + class_marker, "scope-before-control fixture")

    test_marker = '''    test("child control-flow snapshot resolves to the authored instance path"):
'''
    tests = '''    test("lexical scope before first control retains one aligned semantic path"):
      val inspection = AnalogControlFlowInspection.inspect(
        new PublicAnalogScopeBeforeStructuredControl
      )
      val snapshot = inspection.controlFlow.head
      val scope = snapshot.root.statements.collectFirst:
        case value: AnalogControlFlowRuntime.Statement.Scope => value
      assert(scope.nonEmpty)
      val declaration = scope.toVector.flatMap(_.body.statements).collectFirst:
        case value: AnalogControlFlowRuntime.Statement.Declare => value
      assert(declaration.nonEmpty)
      assert(declaration.exists(_.variable.startsWith(s"${scope.get.identity}.")))
      val program = inspection.construction.analogProcedural.head
      val record = program.variables.find(record =>
        declaration.exists(_.variable == record.variable.identity)
      )
      assert(record.nonEmpty)
      assert(
        record.exists(
          _.variable.declarationScope.mkString(".") ==
            scope.get.identity.stripPrefix(s"${snapshot.owner}.")
        )
      )

    test("control-flow owners are non-empty and canonical"):
      val empty = scala.util
        .Try(new AnalogControlFlowConstruction.Builder(""))
        .failed
        .get
        .asInstanceOf[AnalogControlFlowRuntime.Failure]
      assert(empty.diagnostic.code == "NODAL-ANALOG-034-001")
      val snapshot = AnalogControlFlowInspection
        .inspect(new PublicAnalogConditionalComplete)
        .controlFlow
        .head
      val padded = scala.util
        .Try(snapshot.remapOwner(" padded.owner"))
        .failed
        .get
        .asInstanceOf[AnalogControlFlowRuntime.Failure]
      assert(padded.diagnostic.code == "NODAL-ANALOG-034-001")

'''
    if "lexical scope before first control retains one aligned semantic path" not in text:
        text = replace_once(text, test_marker, tests + test_marker, "owner and scope tests")
    write(path, text)


def patch_construction_witness(root: Path) -> None:
    path = root / "examples/continuousTimeApi/src/nodal/increment34fixture/Increment34ConstructionCheck.scala"
    text = read(path)
    class_marker = '''final class Increment34MissingElseFixture extends Module:
'''
    class_block = '''final class Increment34PreControlScopeFixture extends Module:
  val select: Variable[Bool] = variable(Bool, false.B)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    initial:
      val local = variable(Real, 1.0.real)
      local := 2.0.real
    analogConditional:
      analogWhen(select):
        sink := 3.0.real
      analogOtherwise:
        sink := 4.0.real

'''
    if "Increment34PreControlScopeFixture" not in text:
        text = replace_once(text, class_marker, class_block + class_marker, "scope witness fixture")

    old = '''    val selected = AnalogControlFlowInspection.inspect(new Increment34CaseFixture)
    val missingElse = failureCode(new Increment34MissingElseFixture)
'''
    new = '''    val selected = AnalogControlFlowInspection.inspect(new Increment34CaseFixture)
    val scoped = AnalogControlFlowInspection.inspect(new Increment34PreControlScopeFixture)
    val missingElse = failureCode(new Increment34MissingElseFixture)
'''
    text = replace_once(text, old, new, "scope witness inspection")

    old = '''    assert(conditional.construction.analogProcedural.head.assignments.isEmpty)

    val lines = Vector(
'''
    new = '''    assert(conditional.construction.analogProcedural.head.assignments.isEmpty)
    val scope = scoped.controlFlow.head.root.statements.collectFirst:
      case value: AnalogControlFlowRuntime.Statement.Scope => value
    val declaration = scope.toVector.flatMap(_.body.statements).collectFirst:
      case value: AnalogControlFlowRuntime.Statement.Declare => value
    val scopeAligned =
      scope.nonEmpty && declaration.exists(_.variable.startsWith(s"${scope.get.identity}."))
    assert(scopeAligned)
    val emptyOwner = scala.util
      .Try(new AnalogControlFlowConstruction.Builder(""))
      .failed
      .get
      .asInstanceOf[AnalogControlFlowRuntime.Failure]
      .diagnostic
      .code
    val paddedOwner = scala.util
      .Try(conditional.controlFlow.head.remapOwner(" padded.owner"))
      .failed
      .get
      .asInstanceOf[AnalogControlFlowRuntime.Failure]
      .diagnostic
      .code
    assert(emptyOwner == "NODAL-ANALOG-034-001")
    assert(paddedOwner == "NODAL-ANALOG-034-001")

    val lines = Vector(
'''
    text = replace_once(text, old, new, "owner and scope witness assertions")

    old = '''      s"flat_assignments=${conditional.construction.analogProcedural.head.assignments.size}"
'''
    new = '''      s"flat_assignments=${conditional.construction.analogProcedural.head.assignments.size}",
      s"precontrol_scope_aligned=$scopeAligned",
      s"empty_owner=$emptyOwner",
      s"padded_owner=$paddedOwner"
'''
    text = replace_once(text, old, new, "owner and scope witness report")
    write(path, text)


def patch_manifest(root: Path) -> None:
    path = root / "tests/compiler/fixtures/increment34/manifest.json"
    document = json.loads(read(path))
    semantics = document.setdefault("semantics", {})
    semantics["canonical_control_owner_identity"] = True
    semantics["pre_control_lexical_scope_alignment"] = True
    write(path, json.dumps(document, indent=2) + "\n")


def patch_checker(root: Path) -> None:
    path = root / "scripts/check_increment34.py"
    text = read(path)
    old = '''        "native_missing_else_intersection",
    ):
'''
    new = '''        "native_missing_else_intersection",
        "canonical_control_owner_identity",
        "pre_control_lexical_scope_alignment",
    ):
'''
    text = replace_once(text, old, new, "owner and scope manifest semantics")

    old = '''            "final class Builder",
            "def conditionalBranch",
'''
    new = '''            "final class Builder",
            "private def requireCanonicalOwner",
            "control-flow source owner",
            "control-flow destination owner",
            "private val canonicalOwner",
            "identityOverride: Option[String] = None",
            "def conditionalBranch",
'''
    text = replace_once(text, old, new, "owner construction checker tokens")

    old = '''            "if !builder.hasStructuredControl then",
            "def conditionalBranch",
'''
    new = '''            "if !builder.hasStructuredControl then",
            "builder.lexicalScope(source, Some(builderIdentity))",
            "module.controlScope :+ stableScope",
            "def conditionalBranch",
'''
    text = replace_once(text, old, new, "scope integration checker tokens")

    old = '''            "structured branch assignment rejects a foreign component variable",
            "assignments.isEmpty",
'''
    new = '''            "structured branch assignment rejects a foreign component variable",
            "lexical scope before first control retains one aligned semantic path",
            "control-flow owners are non-empty and canonical",
            "assignments.isEmpty",
'''
    text = replace_once(text, old, new, "owner and scope test checker tokens")

    old = '''            "public_case_snapshots=",
        ),
'''
    new = '''            "public_case_snapshots=",
            "precontrol_scope_aligned=",
            "empty_owner=NODAL-ANALOG-034-001",
            "padded_owner=NODAL-ANALOG-034-001",
        ),
'''
    text = replace_once(text, old, new, "owner and scope witness checker tokens")

    old = '''            "false flat Increment 33",
'''
    new = '''            "false flat Increment 33",
            "canonical owner identities",
            "pre-control lexical scopes",
'''
    text = replace_once(text, old, new, "owner and scope README checker tokens")
    write(path, text)


def patch_contract_tests(root: Path) -> None:
    path = root / "tests/compiler/test_increment34.py"
    text = read(path)
    marker = '''    def test_write_enabled_workflow_is_rejected(self) -> None:
'''
    addition = '''    def test_control_owner_validation_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowConstruction.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "control-flow source owner",
                    "unvalidated source owner",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "control-flow construction bridge is missing")

    def test_pre_control_scope_alignment_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogProceduralConstruction.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "builder.lexicalScope(source, Some(builderIdentity))",
                    "builder.lexicalScope(source)",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "procedural construction integration is missing")

    def test_owner_scope_manifest_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["semantics"]["pre_control_lexical_scope_alignment"] = False
            path.write_text(json.dumps(document, indent=2) + "\\n", encoding="utf-8")
            self.assert_rejected(root, "semantic contract")

'''
    if "test_pre_control_scope_alignment_mutation_is_rejected" not in text:
        text = replace_once(text, marker, addition + marker, "owner and scope mutation tests")
    write(path, text)


def patch_readme(root: Path) -> None:
    path = root / "tests/compiler/fixtures/increment34/README.md"
    text = read(path)
    marker = '''Solver execution and target lowering remain deferred.
'''
    addition = '''Control-flow snapshots require non-empty canonical owner identities. Pre-control lexical scopes retain one shared recorder/structured identity when a later conditional, case, or loop converts the procedure to structured form.

'''
    if "canonical owner identities" not in text:
        text = replace_once(text, marker, addition + marker, "owner and scope README evidence")
    write(path, text)


def patch_design_gate(root: Path) -> None:
    path = root / "docs/design-gates/NodalAnalogControlFlow-DG-v0.1.md"
    text = read(path)
    marker = '''- Control identities are unique within the procedure and remain stable through serialization.
'''
    addition = marker + '''- Control-flow owner identities are non-empty and canonical before construction or remapping.
- A lexical scope authored before the first explicit control statement keeps the same semantic identity if the procedure later becomes structured.
'''
    text = replace_once(text, marker, addition, "owner and scope design rules")
    write(path, text)


def patch_implementation(root: Path) -> None:
    path = root / "docs/implementation/increment34-analog-control-flow.md"
    text = read(path)
    marker = '''- [x] Reject nested source-semantic declarations that are not marked block-local.
'''
    addition = marker + '''- [x] Reject empty or padded control-flow owners before construction or remapping.
- [x] Keep recorder and structured identities aligned for lexical scopes authored before the first explicit control construct.
'''
    text = replace_once(text, marker, addition, "owner and scope implementation evidence")
    write(path, text)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    patch_control_construction(root)
    patch_procedural_construction(root)
    patch_construction_tests(root)
    patch_construction_witness(root)
    patch_manifest(root)
    patch_checker(root)
    patch_contract_tests(root)
    patch_readme(root)
    patch_design_gate(root)
    patch_implementation(root)
    print("Increment 34 owner and scope fresh-review fixes applied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
