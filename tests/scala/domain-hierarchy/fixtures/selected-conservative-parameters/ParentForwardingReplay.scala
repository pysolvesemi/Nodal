package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}

object ReplayParentForwarding:
  def main(args: Array[String]): Unit =
    val output = Path.of(args(0))
    val snapshot = ConstructionKernel.inspect(new SelectedLiteralParentReference)
    val parent = snapshot.modules.find(_.className == "SelectedLiteralParentReference").get
    val actual = parent.declarations.find(_.name == "parentGain").get
    val instance = parent.instances.head
    val child = snapshot.modules.find(_.path == instance.childModule).get
    assert(parent.instances.size == 1)
    assert(actual.path == parent.path + ".parentGain")
    assert(actual.generatedScope.isEmpty && actual.attributes.contains("default" -> "5.0"))
    assert(child.declarations.find(_.name == "gain").get.attributes.contains("default" -> "2.0"))
    assert(instance.parameterBindings == Vector("gain" -> actual.path, "offset" -> "7.0"))
    assert(snapshot.interfaceContracts.count(_.boundary.exists(_.selection.nonEmpty)) == 1)
    val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
    assert(text == ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text)
    val reference = text.linesIterator.filter(line =>
      line.contains("\"nodal.const_parameter_ref\"") && line.contains("parameter = @parentGain")
    ).toVector
    val overrides = text.linesIterator.filter(_.contains("\"nodal.parameter_override\"")).toVector
    assert(reference.size == 1 && overrides.size == 1)
    assert(reference.head.startsWith("    %") && !reference.head.startsWith("      %"))
    assert(overrides.head.startsWith("      ") && overrides.head.contains("parameter = @gain"))
    val value = reference.head.trim.takeWhile(_ != ' ')
    assert(overrides.head.contains("\"nodal.parameter_override\"(" + value + ")"))
    assert(overrides.head.contains("source_value = \"" + actual.path + "\""))
    assert(overrides.head.contains("generated_owner = \"" + instance.generatedScope.get.owner +
      "\""))
    val _ = Files.writeString(output, text)
    val _ = Files.writeString(
      output.resolveSibling("parent-reference.snapshot.txt"),
      s"parent=${parent.path}\nactual=${actual.path}\nparent-default=5.0\nchild-default=2.0\nbindings=${instance.parameterBindings}\n"
    )

    Vector(
      ("two-parent-references", () => new SelectedTwoParentReferences, "9.0"),
      ("equal-parent-references", () => new SelectedEqualParentReferences, "5.0")
    ).foreach: (name, make, secondDefault) =>
      val two = ConstructionKernel.inspect(make())
      val owner = two.modules.find(_.instances.size == 2).get
      val first = owner.declarations.find(_.name == "parentFirst").get
      val second = owner.declarations.find(_.name == "parentSecond").get
      assert(first.path != second.path)
      assert(first.attributes.contains("default" -> "5.0"))
      assert(second.attributes.contains("default" -> secondDefault))
      val associations = Vector("first" -> first, "second" -> second).map: (member, source) =>
        val path = owner.path + ".generate_0_lanes_" + member + "_instance"
        val instances = owner.instances.filter(_.path == path)
        assert(instances.size == 1, path)
        assert(instances.head.parameterBindings == Vector("gain" -> source.path), path)
        instances.head -> source
      assert(two.interfaceContracts.count(_.boundary.exists(_.selection.nonEmpty)) == 2)
      assert(two.interfaceContracts.count(_.boundary.exists(b =>
        b.selection.isEmpty && b.region.nonEmpty
      )) == 2)
      assert(owner.instances.map(_.childModule).forall(path =>
        two.modules.find(_.path == path).exists(_.className == "SelectedTwoChildConservativeLeaf")
      ))
      val emitted = ScalaToMlirBridge.fromSnapshot(two, Backend.VerilogA).text
      assert(emitted == ScalaToMlirBridge.fromSnapshot(two, Backend.VerilogA).text)
      associations.foreach: (childInstance, source) =>
        val symbol = childInstance.path.split('.').last
        val references = emitted.linesIterator.filter(line =>
          line.contains("\"nodal.const_parameter_ref\"") &&
            line.contains("parameter = @" + source.name + "}")
        ).toVector
        val instances = emitted.linesIterator.filter(line =>
          line.contains("\"nodal.instance\"()") &&
            line.contains("sym_name = \"" + symbol + "\"") &&
            line.contains("semantic_path = \"" + childInstance.path + "\"")
        ).toVector
        val bindings = emitted.linesIterator.filter(line =>
          line.contains("\"nodal.parameter_override\"") &&
            line.contains("instance = @" + symbol + ",")
        ).toVector
        assert(references.size == 1 && instances.size == 1 && bindings.size == 1)
        val sourceValue = references.head.trim.takeWhile(_ != ' ')
        assert(bindings.head.contains("\"nodal.parameter_override\"(" + sourceValue + ")"))
        assert(bindings.head.contains("parameter = @gain"))
        assert(bindings.head.contains("source_value = \"" + source.path + "\""))
      val _ = Files.writeString(output.resolveSibling(name + ".mlir"), emitted)
    val expression = scala.util.Try(ConstructionKernel.inspect(new SelectedParentExpressionActual))
    assert(expression.isFailure)
    assert(expression.failed.get.toString.contains("ordinary dimensionless Real literals"))
    val foreign = scala.util.Try(ConstructionKernel.inspect(new SelectedParentForeignActual))
    assert(foreign.isFailure)
    assert(foreign.failed.get.toString.contains("NODAL-HIERARCHY-031"))
    assert(ConstructionKernel.inspect(new SelectedLiteralParentReference).modules.nonEmpty)
    println(
      "PASS exact parent identity, differing/equal defaults, deterministic symbolic transport, expression refusal and recovery"
    )
