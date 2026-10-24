package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.Increment58SymbolicHierarchySource
import utest.*

object Increment58SymbolicHierarchyTests extends TestSuite:
  private def rejected(body: => Any): Unit =
    val failure = scala.util.Try(body).failed.get
    assert(failure.isInstanceOf[BridgeException])

  val tests = Tests:
    test("ordinary Integer ranges are executable constraints without structural effects"):
      val snapshot = ConstructionKernel.inspect(new Increment58SymbolicHierarchySource)
      val root = snapshot.modules.find(_.path == snapshot.root).get
      val other = root.declarations.find(_.name == "other").get
      assert(!other.attributes.toMap.contains("structural_effects"))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.sliding("\"nodal.parameter_constraint\"".length)
        .count(_ == "\"nodal.parameter_constraint\"") == 5)
      assert(text.sliding("\"nodal.parameter_envelope\"".length)
        .count(_ == "\"nodal.parameter_envelope\"") == 2)
      val forged = snapshot.copy(modules =
        snapshot.modules.map(module =>
          module.copy(declarations =
            module.declarations.map(declaration =>
              if declaration.path == other.path then
                declaration.copy(attributes =
                  declaration.attributes.filterNot(_._1 == "integer_range_upper")
                )
              else declaration
            )
          )
        )
      )
      rejected:
        ScalaToMlirBridge.fromSnapshot(forged)

    test("whole scalar and Vec ports preserve finalized parent symbolic types"):
      for vector <- Vector(false, true); late <- Vector(false, true) do
        val snapshot =
          ConstructionKernel.inspect(new Increment58SymbolicHierarchySource(vector, late))
        val root = snapshot.modules.find(_.path == snapshot.root).get
        val actual = root.declarations.filter(_.instancePort.nonEmpty)
        assert(actual.size == 2)
        val expected = root.declarations.find(_.name == "inputData").get.dataType
        assert(actual.forall(_.dataType == expected))
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        assert(text.contains("nodal.instance_port"), text.contains("nodal.parameter"))

    test("same bounds and defaults do not prove an instantiated width"):
      val snapshot = ConstructionKernel.inspect(new Increment58SymbolicHierarchySource)
      val forged = snapshot.copy(modules =
        snapshot.modules.map(module =>
          if module.path != snapshot.root then module
          else
            module.copy(declarations =
              module.declarations.map(declaration =>
                if declaration.instancePort.nonEmpty then
                  declaration.copy(dataType = Some("UInt(other)"))
                else declaration
              )
            )
        )
      )
      rejected:
        ScalaToMlirBridge.fromSnapshot(forged)

    test("identical formal spelling does not bypass finalized substitution"):
      val snapshot = ConstructionKernel.inspect(new Increment58SymbolicHierarchySource)
      val forged = snapshot.copy(modules =
        snapshot.modules.map(module =>
          if module.path != snapshot.root then module
          else
            module.copy(instances =
              module.instances.map(instance =>
                instance.copy(parameterBindings = instance.parameterBindings.map:
                  case ("width", _) => "width" -> "other"
                  case binding => binding)
              )
            )
        )
      )
      rejected:
        ScalaToMlirBridge.fromSnapshot(forged)
