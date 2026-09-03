package nodal.increment34fixture

import java.nio.file.Files
import java.nio.file.Path

import nodal.*

final class Increment34ConditionalFixture extends Module:
  val select: Variable[Bool] = variable(Bool, false.B)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogConditional:
      analogWhen(select):
        value := 1.0.real
      analogOtherwise:
        value := 2.0.real
    sink := value

final class Increment34PreControlScopeFixture extends Module:
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

final class Increment34MissingElseFixture extends Module:
  val select: Variable[Bool] = variable(Bool, false.B)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogConditional:
      analogWhen(select):
        value := 1.0.real
    sink := value

final class Increment34CaseFixture extends Module:
  val mode: Variable[Integer] = variable(Integer, 0.integer)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogCase(mode):
      analogCaseArm(0):
        value := 1.0.real
      analogCaseArm(1, 2):
        value := 2.0.real
      analogCaseDefault:
        value := 3.0.real
    sink := value

object Increment34ConstructionCheck:
  private def failureCode(top: => Module): String =
    scala.util
      .Try(AnalogControlFlowInspection.inspect(top))
      .failed
      .get
      .asInstanceOf[AnalogControlFlowRuntime.Failure]
      .diagnostic
      .code

  def main(args: Array[String]): Unit =
    val report = args.headOption
      .map(value => Path.of(value))
      .getOrElse(Path.of("/tmp/increment34-construction-check.txt"))

    val conditional = AnalogControlFlowInspection.inspect(
      new Increment34ConditionalFixture
    )
    val selected = AnalogControlFlowInspection.inspect(new Increment34CaseFixture)
    val scoped = AnalogControlFlowInspection.inspect(new Increment34PreControlScopeFixture)
    val missingElse = failureCode(new Increment34MissingElseFixture)

    assert(conditional.controlFlow.size == 1)
    assert(selected.controlFlow.size == 1)
    assert(missingElse == "NODAL-ANALOG-034-004")
    assert(conditional.construction.analogProcedural.head.assignments.isEmpty)
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
      s"public_conditional_snapshots=${conditional.controlFlow.size}",
      s"public_case_snapshots=${selected.controlFlow.size}",
      s"public_missing_else=$missingElse",
      s"flat_assignments=${conditional.construction.analogProcedural.head.assignments.size}",
      s"precontrol_scope_aligned=$scopeAligned",
      s"empty_owner=$emptyOwner",
      s"padded_owner=$paddedOwner"
    )
    Files.writeString(
      report,
      lines.mkString("", System.lineSeparator(), System.lineSeparator())
    )
