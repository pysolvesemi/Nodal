package nodal.increment35fixture

import java.nio.file.{Files, Path}

import nodal.*

final class Increment35LegacyFixture extends Module:
  analog:
    val _ = ddt(2.0.V)
    val _ = idt(1.0.A, 0.0.real)
    val _ = idt(2.0.A)

final class Increment35EquationFixture extends Module:
  equations:
    val _ = ddt(1.0.V)
    val _ = idt(1.0.A)

final class Increment35ContributionFixture extends Module:
  contributions:
    val _ = ddt(1.0.V)
    val _ = idt(1.0.A)

final class Increment35TypedInitialFixture extends Module:
  analog:
    val initialCharge = 2.0.A * 3.0.s
    val _ = idt(1.0.A, initialCharge)

final class Increment35MultipleStateFixture extends Module:
  analog:
    val _ = idt(1.0.A)
    val _ = idt(2.0.A, 0.0.real)

final class Increment35OutsideFixture extends Module:
  val invalid = idt(1.0.A)

final class Increment35InitialFixture extends Module:
  initialEquations:
    val _ = ddt(1.0.V)

final class Increment35ProceduralFixture extends Module:
  analogProcedure:
    val _ = idt(1.0.A)

final class Increment35MismatchFixture extends Module:
  analog:
    val _ = idt(1.0.A, 1.0.V)

object Increment35ConstructionCheck:
  private val ExpectedAnalyses = Vector(
    "ac",
    "dc",
    "initialization",
    "noise",
    "operating-point",
    "transient"
  )

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
    val replay = ConstructionKernel.inspect(new Increment35LegacyFixture)
    val equation = ConstructionKernel.inspect(new Increment35EquationFixture)
    val contribution = ConstructionKernel.inspect(new Increment35ContributionFixture)
    val typedInitial = ConstructionKernel.inspect(new Increment35TypedInitialFixture)
    val firstStates = ConstructionKernel
      .inspect(new Increment35MultipleStateFixture)
      .continuousOperators
      .flatMap(_.stateId)
    val secondStates = ConstructionKernel
      .inspect(new Increment35MultipleStateFixture)
      .continuousOperators
      .flatMap(_.stateId)
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
    val typed = typedInitial.continuousOperators.find(_.operation == "analog_idt").get
    val analysisInventoryExact =
      legacy.continuousOperators.forall(_.analyses == ExpectedAnalyses)
    val ownerQualified =
      legacy.continuousOperators.forall(value => value.path.startsWith(s"${value.owner}."))
    val equationContext = equation.continuousOperators.forall(_.context == "equation")
    val contributionContext =
      contribution.continuousOperators.forall(_.context == "contribution")
    val stateIdsUnique = firstStates.distinct.size == firstStates.size
    val stateIdsStable = firstStates == secondStates
    val operatorPathsStable =
      legacy.continuousOperators.map(_.path) == replay.continuousOperators.map(_.path)

    val lines = Vector(
      s"operator_count=${legacy.continuousOperators.size}",
      s"ddt_result_dimension=${derivative.resultDimension}",
      s"idt_fixed_state=${fixed.stateId.getOrElse("")}",
      s"idt_fixed_initialization=${fixed.initialization}",
      s"idt_solver_initialization=${solver.initialization}",
      s"analysis_inventory_exact=$analysisInventoryExact",
      s"owner_qualified=$ownerQualified",
      s"equation_context=$equationContext",
      s"contribution_context=$contributionContext",
      s"typed_initial_dimension=${typed.resultDimension}",
      s"state_ids_unique=$stateIdsUnique",
      s"state_ids_stable=$stateIdsStable",
      s"operator_paths_stable=$operatorPathsStable",
      s"outside_context=${failureCode(new Increment35OutsideFixture)}",
      s"initial_context=${failureCode(new Increment35InitialFixture)}",
      s"procedural_context=${failureCode(new Increment35ProceduralFixture)}",
      s"initial_mismatch=${failureCode(new Increment35MismatchFixture)}"
    )
    Files.writeString(
      report,
      lines.mkString("", System.lineSeparator(), System.lineSeparator())
    )
