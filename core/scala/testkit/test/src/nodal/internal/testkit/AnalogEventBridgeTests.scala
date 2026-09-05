package nodal.internal.testkit

import nodal.*
import nodal.increment37fixture.AnalogEventSource
import nodal.internal.bridge.ScalaToMlirBridge
import utest.*

object AnalogEventBridgeTests extends TestSuite:
  val tests: Tests = Tests:
    test("actual public source emits every event operation and source maps"):
      val first = ScalaToMlirBridge.lower(new AnalogEventSource)
      val second = ScalaToMlirBridge.lower(new AnalogEventSource)
      assert(first == second)
      Vector("cross", "above", "timer", "initial_step", "final_step", "event_or", "on").foreach:
        name =>
          assert(first.text.contains(s"\"nodal.analog_$name\""))
      assert(first.text.contains("!nodal.analog_event"))
      assert(first.text.contains("contract = \"increment37\""))
      assert(first.text.contains("threshold_crossing"))
      assert(first.text.contains("Increment37ConstructionCheck.scala"))
      assert(!first.text.contains("@event_reference_"))
      assert(first.text.contains("nodal.target.profile = \"analog\""))

    test("event nodes survive source inventory and are not flattened into unconditional writes"):
      val snapshot = ConstructionKernel.inspect(new AnalogEventSource)
      assert(snapshot.analogProcedural.head.assignments.isEmpty)
      val mlir = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(mlir.contains("control_event"))
      assert(mlir.contains("event_expression"))

    test("empty controlled statements retain an explicit native block"):
      val mlir =
        ScalaToMlirBridge.lower(new AnalogEventBodySource(() => on(initialStep) { () })).text
      val controlled = mlir.substring(mlir.indexOf("\"nodal.analog_on\""))
      assert(controlled.contains("^bb0:"))
