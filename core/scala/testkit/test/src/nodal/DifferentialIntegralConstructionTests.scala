package nodal.internal.testkit

import nodal.*

import utest.*

final class DifferentialIntegralLegacyTop extends Module:
  analog:
    val _ = ddt(2.0.V)
    val _ = idt(1.0.A, 0.0.real)
    val _ = idt(2.0.A)

final class DifferentialIntegralEquationTop extends Module:
  equations:
    val _ = ddt(1.0.V)
    val _ = idt(1.0.A)

final class DifferentialIntegralContributionTop extends Module:
  contributions:
    val _ = ddt(1.0.V)
    val _ = idt(1.0.A)

final class DifferentialIntegralTypedInitialTop extends Module:
  analog:
    val initialCharge = 2.0.A * 3.0.s
    val _ = idt(1.0.A, initialCharge)

final class DifferentialIntegralMultipleStateTop extends Module:
  analog:
    val _ = idt(1.0.A)
    val _ = idt(2.0.A, 0.0.real)

final class DifferentialOutsideRegion extends Module:
  val invalid = ddt(1.0.V)

final class IntegralInitialEquationRegion extends Module:
  initialEquations:
    val _ = idt(1.0.A)

final class DifferentialProceduralRegion extends Module:
  analogProcedure:
    val _ = ddt(1.0.V)

final class IntegralInitialConditionMismatch extends Module:
  analog:
    val _ = idt(1.0.A, 1.0.V)

object DifferentialIntegralConstructionTests extends TestSuite:
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

  val tests: Tests = Tests:
    test("ddt and idt retain complete source semantics"):
      val snapshot = ConstructionKernel.inspect(new DifferentialIntegralLegacyTop)
      val operators = snapshot.continuousOperators

      assert(operators.size == 3)
      val derivative = operators.find(_.operation == "analog_ddt").get
      val integrals = operators.filter(_.operation == "analog_idt")
      val fixed = integrals.find(_.initialization == "fixed").get
      val solver = integrals.find(_.initialization == "solver-selected").get

      assert(derivative.context == "legacy-analog")
      assert(derivative.inputDimension == "voltage")
      assert(derivative.resultDimension == "time^-1*voltage")
      assert(derivative.stateId.isEmpty)
      assert(derivative.initialization == "none")
      assert(fixed.inputDimension == "current")
      assert(fixed.resultDimension == "current*time")
      assert(fixed.initialCondition.nonEmpty)
      assert(fixed.stateId.contains(s"${fixed.path}.state"))
      assert(solver.stateId.contains(s"${solver.path}.state"))
      assert(solver.initialCondition.isEmpty)
      assert(operators.forall(_.analyses == ExpectedAnalyses))
      assert(operators.forall(value => value.path.startsWith(s"${value.owner}.")))
      assert(operators.forall(_.source.nonEmpty))

    test("equation and contribution regions retain distinct declarative contexts"):
      val equations =
        ConstructionKernel.inspect(new DifferentialIntegralEquationTop).continuousOperators
      val contributions =
        ConstructionKernel.inspect(new DifferentialIntegralContributionTop).continuousOperators

      assert(equations.size == 2)
      assert(equations.forall(_.context == "equation"))
      assert(contributions.size == 2)
      assert(contributions.forall(_.context == "contribution"))
      assert(equations.map(_.operation).toSet == Set("analog_ddt", "analog_idt"))
      assert(contributions.map(_.operation).toSet == Set("analog_ddt", "analog_idt"))

    test("typed non-zero integral initial condition matches the integral result"):
      val operator = ConstructionKernel
        .inspect(new DifferentialIntegralTypedInitialTop)
        .continuousOperators
        .find(_.operation == "analog_idt")
        .get

      assert(operator.initialization == "fixed")
      assert(operator.inputDimension == "current")
      assert(operator.resultDimension == "current*time")
      assert(operator.initialCondition.nonEmpty)

    test("integral state identities are unique and deterministic"):
      val first = ConstructionKernel
        .inspect(new DifferentialIntegralMultipleStateTop)
        .continuousOperators
        .filter(_.operation == "analog_idt")
        .flatMap(_.stateId)
      val second = ConstructionKernel
        .inspect(new DifferentialIntegralMultipleStateTop)
        .continuousOperators
        .filter(_.operation == "analog_idt")
        .flatMap(_.stateId)

      assert(first.size == 2)
      assert(first.distinct.size == first.size)
      assert(first == second)
      assert(first.forall(_.endsWith(".state")))

    test("continuous operators outside declarative analog regions are rejected"):
      assert(failureCode(new DifferentialOutsideRegion) == "NODAL-ANALOG-035-001")

    test("continuous operators are rejected in initial-equation regions"):
      assert(failureCode(new IntegralInitialEquationRegion) == "NODAL-ANALOG-035-001")

    test("continuous operators are rejected in procedural regions"):
      assert(failureCode(new DifferentialProceduralRegion) == "NODAL-ANALOG-035-001")

    test("idt initial condition dimension must match its integral result"):
      assert(failureCode(new IntegralInitialConditionMismatch) == "NODAL-ANALOG-035-004")
