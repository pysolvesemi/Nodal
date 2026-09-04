package nodal.internal.testkit

import nodal.internal.bridge.ScalaToMlirBridge

import utest.*

object DifferentialIntegralBridgeTests extends TestSuite:
  val tests: Tests = Tests:
    test("bridge emits deterministic first-class differential and integral operations"):
      val first = ScalaToMlirBridge.lower(new DifferentialIntegralLegacyTop)
      val second = ScalaToMlirBridge.lower(new DifferentialIntegralLegacyTop)

      assert(first == second)
      assert(first.sha256 == second.sha256)
      assert(first.text.contains("nodal.bridge.continuous_operators"))
      assert(first.text.contains("\"nodal.analog_ddt\""))
      assert(first.text.contains("\"nodal.analog_idt\""))
      assert(first.text.contains("operator_contract = \"increment35\""))
      assert(first.text.contains("initialization = \"fixed\""))
      assert(first.text.contains("initialization = \"solver-selected\""))
      assert(first.text.contains("state_id = \""))
      assert(first.text.contains("initial_dimension = \"current*time\""))
      assert(first.text.contains("result_dimension = \"current*time\""))
      assert(first.text.contains("context = \"legacy-analog\""))
      assert(first.text.contains("\"ac\""))
      assert(first.text.contains("\"dc\""))
      assert(first.text.contains("\"initialization\""))
      assert(first.text.contains("\"noise\""))
      assert(first.text.contains("\"operating-point\""))
      assert(first.text.contains("\"transient\""))
      assert(first.text.contains("DifferentialIntegralConstructionTests.scala"))

    test("bridge retains equation and contribution operator contexts"):
      val equation = ScalaToMlirBridge.lower(new DifferentialIntegralEquationTop)
      val contribution = ScalaToMlirBridge.lower(new DifferentialIntegralContributionTop)

      assert(equation.text.contains("context = \"equation\""))
      assert(contribution.text.contains("context = \"contribution\""))
      assert(equation.text.contains("\"nodal.analog_ddt\""))
      assert(equation.text.contains("\"nodal.analog_idt\""))
      assert(contribution.text.contains("nodal.bridge.continuous_operators"))

    test("bridge retains a typed non-zero integral initial condition"):
      val document = ScalaToMlirBridge.lower(new DifferentialIntegralTypedInitialTop)

      assert(document.text.contains("\"nodal.analog_idt\""))
      assert(document.text.contains("initialization = \"fixed\""))
      assert(document.text.contains("initial_dimension = \"current*time\""))
      assert(document.text.contains("result_dimension = \"current*time\""))
