package nodal.internal.testkit

import utest.*

object Increment49NumericalTests extends TestSuite:
  import Increment49References.*

  val tests: Tests = Tests:
    test("RC references use independent signs, units and finite-rise initial conditions"):
      assert(dcSourceCurrent(1) == -0.001)
      val admittance = acSourceCurrent(1000)
      assert(admittance.real == -0.001)
      assert(math.abs(admittance.imaginary + 2 * math.Pi * 0.001) < 1e-15)
      assert(rcVoltage(0) == 0, rcVoltage(DelaySeconds) == 0)
      val end = DelaySeconds + RiseSeconds
      val voltage = rcVoltage(end)
      assert(voltage > 0.048, voltage < 0.049)
      assert(math.abs(rcVoltage(end - 1e-12) - voltage) < 1e-9)
      assert(math.abs(rcVoltage(end + 1e-12) - voltage) < 1e-9)
      assert(rcVoltage(0.1) == 1)
      val samples = (0 to 1000).map(index => rcVoltage(index * 1e-5))
      assert(samples.sliding(2).forall(pair => pair(1) >= pair(0)))

    test("declared component tolerances reject fixed errors and nonfinite output"):
      assert(CurrentTolerance.accepts(-0.001, -0.001))
      assert(!CurrentTolerance.accepts(0.001, -0.001))
      assert(TransientTolerance.accepts(1 + 0.002, 1))
      assert(!TransientTolerance.accepts(1 + 0.003, 1))
      assert(!TransientTolerance.accepts(Double.NaN, 0))
      assert(!TransientTolerance.accepts(Double.PositiveInfinity, 0))
      assert(!TransientTolerance.accepts(0, Double.NaN))
      assert(!TransientTolerance.accepts(2.1e-5, 0))

    test("context and safe-function references retain kelvin and second conversion"):
      assert(contextVoltage(0, 27, 27) == 1)
      assert(math.abs(contextVoltage(0.5, 37, 27) - (310.15 / 300.15 + 0.5)) < 1e-15)
      assert(safeFunctionVoltage(0) == 3.5)
      assert(safeFunctionVoltage(math.Pi / 2) == 4.5)
