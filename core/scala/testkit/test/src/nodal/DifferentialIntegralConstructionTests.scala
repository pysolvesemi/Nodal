package nodal.internal.testkit

import nodal.*

import utest.*

final class DifferentialIntegralLegacyTop extends Module:
  analog:
    val derivative = ddt(2.0.V)
    val fixedIntegral = idt(1.0.A, 0.0.real)
    val solverIntegral = idt(2.0.A)

final class DifferentialIntegralEquationTop extends Module:
  equations:
    val derivative = ddt(1.0.V)
    val integral = idt(1.0.A)

final class DifferentialOutsideRegion extends Module:
  val invalid = ddt(1.0.V)

final class IntegralInitialEquationRegion extends Module:
  initialEquations:
    val invalid = idt(1.0.A)

final class DifferentialProceduralRegion extends Module:
  analogProcedure:
    val invalid = ddt(1.0.V)

final class IntegralInitialConditionMismatch extends Module:
  analog:
    val invalid = idt(1.0.A, 1.0.V)

object DifferentialIntegralConstructionTests extends TestSuite:
  private def failureCode(top: => Module): String =
    scala.util
      .Try(ConstructionKernel.inspect(top))
      .failed
      .get
      .asInstanceOf[ConstructionException]
      .diagnostic
      .code

  val tests: Tests = Tests:
    test("ddt and idt record dimensions, state, initialization, and source"):
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
      assert(operators.forall(_.analyses.contains("transient")))
      assert(operators.forall(_.source.nonEmpty))

    test("equation regions retain an explicit continuous operator context"):
      val operators =
        ConstructionKernel.inspect(new DifferentialIntegralEquationTop).continuousOperators

      assert(operators.size == 2)
      assert(operators.forall(_.context == "equation"))
      assert(operators.map(_.operation).toSet == Set("analog_ddt", "analog_idt"))

    test("continuous operators outside declarative analog regions are rejected"):
      assert(failureCode(new DifferentialOutsideRegion) == "NODAL-ANALOG-035-001")

    test("continuous operators are rejected in initial-equation regions"):
      assert(failureCode(new IntegralInitialEquationRegion) == "NODAL-ANALOG-035-001")

    test("continuous operators are rejected in procedural regions"):
      assert(failureCode(new DifferentialProceduralRegion) == "NODAL-ANALOG-035-001")

    test("idt initial condition dimension must match its integral result"):
      assert(failureCode(new IntegralInitialConditionMismatch) == "NODAL-ANALOG-035-004")
