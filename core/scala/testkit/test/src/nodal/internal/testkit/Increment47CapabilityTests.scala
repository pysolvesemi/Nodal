package nodal.internal.testkit

import nodal.*
import nodal.increment47fixture.*
import nodal.internal.bridge.ScalaToMlirBridge
import utest.*

/** Native tests own admission and rejection; these checks preserve the actual public source. */
object Increment47CapabilityTests extends TestSuite:
  private def operations(text: String, name: String): Vector[String] =
    text.linesIterator.filter(_.contains(s"\"nodal.$name\"(")).toVector

  val tests: Tests = Tests:
    test("analog hierarchy retains child query units contribution and connectivity"):
      val snapshot = ConstructionKernel.inspect(new AnalogCapabilityHierarchy)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA)
      assert(snapshot.modules.map(_.path) == Vector(
        "AnalogCapabilityHierarchy",
        "AnalogCapabilityHierarchy.child"
      ))
      assert(snapshot.analogRegions.head.module == "AnalogCapabilityHierarchy.child")
      assert(snapshot.analogRegions.head.contributions.size == 1)
      assert(operations(document.text, "instance").size == 1)
      assert(operations(document.text, "analog_context").size == 2)
      assert(operations(document.text, "contribute").size == 1)
      assert(document.text.contains("query_kind = \"temperature\""))
      assert(document.text.contains("query_kind = \"nominal_temperature\""))
      assert(document.text.contains("!nodal.quantity<\"real\", \"temperature\">"))
      assert(document.text.contains("nodal.target.profile = \"analog\""))
      assert(document.text.contains("Increment47ConstructionCheck.scala"))

    test("analog Boolean and Integer storage preserve analog types and event controls"):
      val snapshot = ConstructionKernel.inspect(new AnalogCapabilityStorage)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA)
      assert(snapshot.analogProcedural.size == 1)
      assert(document.text.contains("!nodal.variable<\"boolean\", \"1\">"))
      assert(document.text.contains("!nodal.variable<\"integer\", \"1\">"))
      assert(operations(document.text, "analog_initial_step").size == 1)
      assert(operations(document.text, "contribute").size == 1)
      assert(!document.text.contains("!nodal.bits<"))
      assert(!document.text.contains("\"nodal.port\""))
      assert(Nodal.emit(new AnalogCapabilityStorage).report.designKind == DesignKind.AnalogOnly)

    test("analog root retains a transitive digital child instead of hiding it in metadata"):
      val snapshot = ConstructionKernel.inspect(new AnalogRootWithDigitalDescendant)
      assert(snapshot.modules.map(_.path) == Vector(
        "AnalogRootWithDigitalDescendant",
        "AnalogRootWithDigitalDescendant.child",
        "AnalogRootWithDigitalDescendant.child.digital"
      ))
      assert(snapshot.modules.head.declarations.forall(_.kind == "analog-inout"))
      assert(snapshot.modules.last.declarations.exists(value =>
        value.name == "trigger" && value.kind == "input" && value.dataType.contains("Bool")
      ))
      Vector(Backend.VerilogA, Backend.VerilogAMS).foreach: backend =>
        val document = ScalaToMlirBridge.fromSnapshot(snapshot, backend)
        assert(operations(document.text, "instance").size == 2)
        val ports = operations(document.text, "port")
        assert(ports.size == 1)
        assert(ports.head.contains("!nodal.bits<1>"))
        assert(ports.head.contains("AnalogRootWithDigitalDescendant.child.digital.trigger"))
      assert(Nodal.emit(new AnalogRootWithDigitalDescendant).report.designKind ==
        DesignKind.MixedSignal)

    test("fresh public witnesses retain identical VA and AMS profile documents"):
      val sources = Vector[() => Module](
        () => new AnalogCapabilityHierarchy,
        () => new AnalogCapabilityStorage,
        () => new AnalogRootWithDigitalDescendant
      )
      sources.foreach: source =>
        val firstSnapshot = ConstructionKernel.inspect(source())
        val secondSnapshot = ConstructionKernel.inspect(source())
        assert(firstSnapshot == secondSnapshot)
        Vector(Backend.VerilogA, Backend.VerilogAMS).foreach: backend =>
          assert(ScalaToMlirBridge.fromSnapshot(firstSnapshot, backend) ==
            ScalaToMlirBridge.fromSnapshot(secondSnapshot, backend))
