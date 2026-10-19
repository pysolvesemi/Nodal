package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.ScalaToMlirBridge
import nodal.sim.*
import utest.*

object Increment52HierarchyTests extends TestSuite:
  val tests: Tests = Tests:
    test("whole reusable hierarchy preserves actual nested leaf behavior and parameter bindings"):
      val snapshot = ConstructionKernel.inspect(new Increment52HierarchyTop)
      assert(snapshot.analogRegions.flatMap(_.contributions).size == 4)
      assert(snapshot.analogFunctions.size == 4)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
      assert(text.contains("\"nodal.instance\""))
      assert(text.contains("\"nodal.contribute\""))
      assert(text.contains("\"nodal.parameter_override\""))
      assert(text.contains("rootGain"), text.contains("scaleVoltage"))
      assert(!text.contains("\"nodal.analog_equation\""))

    test("hierarchy public source keeps every boundary and is deterministic"):
      val first = Increment52HierarchySource.capture().toOption.get
      val second = Increment52HierarchySource.capture().toOption.get
      assert(first.canonicalConstruction == second.canonicalConstruction)
      assert(first.ports.map(_.label) == Vector("p0", "p1", "p2", "p3", "ground"))
      assert(first.ports.map(_.semanticPath).distinct.size == 5)

    test("named hierarchy remains default and materialization requires explicit selection"):
      val path = java.nio.file.Path.of(".")
      val tools = CompilationTools(path, path, path, "test", path)
      assert(tools.hierarchy == AnalogHierarchyMode.PreserveNamed)
      assert(tools.copy(hierarchy = AnalogHierarchyMode.MaterializeScalar).hierarchy ==
        AnalogHierarchyMode.MaterializeScalar)
