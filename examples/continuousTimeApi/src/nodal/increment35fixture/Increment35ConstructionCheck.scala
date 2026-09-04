package nodal.increment35fixture

import java.nio.file.{Files, Path}

import nodal.*
import nodal.internal.bridge.ScalaToMlirBridge

final class Increment35LegacyFixture extends Module:
  analog:
    val derivative = ddt(2.0.V)
    val fixedIntegral = idt(1.0.A, 0.0.real)
    val solverIntegral = idt(2.0.A)

final class Increment35EquationFixture extends Module:
  equations:
    val derivative = ddt(1.0.V)
    val integral = idt(1.0.A)

final class Increment35OutsideFixture extends Module:
  val invalid = idt(1.0.A)

final class Increment35InitialFixture extends Module:
  initialEquations:
    val invalid = ddt(1.0.V)

final class Increment35ProceduralFixture extends Module:
  analogProcedure:
    val invalid = idt(1.0.A)

final class Increment35MismatchFixture extends Module:
  analog:
    val invalid = idt(1.0.A, 1.0.V)

object Increment35ConstructionCheck:
  private def failureCode(top: => Module): String =
    scala.util
      .Try(ConstructionKernel.inspect(top))
      .failed
      .get
      .asInstanceOf[ConstructionException]
      .diagnostic
      .code

  def main(args: Array[String]): Unit =
    val report = args.headOption
      .map(Path.of(_))
      .getOrElse(Path.of("/tmp/increment35-construction-check.txt"))

    val legacy = ConstructionKernel.inspect(new Increment35LegacyFixture)
    val equation = ConstructionKernel.inspect(new Increment35EquationFixture)
    val fixed = legacy.continuousOperators
      .find(operator =>
        operator.operation == "analog_idt" && operator.initialization == "fixed"
      )
      .get
    val solver = legacy.continuousOperators
      .find(operator =>
        operator.operation == "analog_idt" && operator.initialization == "solver-selected"
      )
      .get
    val derivative = legacy.continuousOperators.find(_.operation == "analog_ddt").get
    val bridge = ScalaToMlirBridge.lower(new Increment35LegacyFixture)

    val lines = Vector(
      s"operator_count=${legacy.continuousOperators.size}",
      s"ddt_result_dimension=${derivative.resultDimension}",
      s"idt_fixed_state=${fixed.stateId.getOrElse("")}",
      s"idt_fixed_initialization=${fixed.initialization}",
      s"idt_solver_initialization=${solver.initialization}",
      s"equation_context=${equation.continuousOperators.forall(_.context == "equation")}",
      s"outside_context=${failureCode(new Increment35OutsideFixture)}",
      s"initial_context=${failureCode(new Increment35InitialFixture)}",
      s"procedural_context=${failureCode(new Increment35ProceduralFixture)}",
      s"initial_mismatch=${failureCode(new Increment35MismatchFixture)}",
      s"bridge_has_idt=${bridge.text.contains("\"nodal.analog_idt\"")}"
    )
    Files.writeString(
      report,
      lines.mkString("", System.lineSeparator(), System.lineSeparator())
    )
