package nodal.internal.testkit

import nodal.internal.bridge.ScalaToMlirBridge

import utest.*

object DifferentialIntegralBridgeTests extends TestSuite:
  val tests: Tests = Tests:
    test("bridge emits first-class differential and integral operations"):
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
      assert(first.text.contains("result_dimension = \"current*time\""))
      assert(first.text.contains("DifferentialIntegralConstructionTests.scala"))
