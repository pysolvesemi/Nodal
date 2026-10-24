package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}

object ReplayParentArithmetic:
  def main(args: Array[String]): Unit =
    val snapshot = ConstructionKernel.inspect(new SelectedParentArithmetic)
    val owner = snapshot.modules.find(_.className == "SelectedParentArithmetic").get
    val first = owner.declarations.find(_.name == "parentFirst").get
    val second = owner.declarations.find(_.name == "parentSecond").get
    assert(first.path != second.path)
    assert(first.attributes.contains("default" -> "5.0"))
    assert(second.attributes.contains("default" -> "5.0"))
    assert(owner.instances.size == 2)
    assert(owner.instances.map(_.parameterBindings.head._2).distinct.size == 2)
    val expressions = snapshot.parameterExpressions.filter(_.owner == owner.path)
    val shared = expressions.filter(_.operation == "analog_add")
    assert(shared.size == 1)
    assert(shared.head.operands == Vector(first.path, second.path))
    val amplified = expressions.filter(_.operation == "analog_mul")
    assert(amplified.size == 1 && amplified.head.operands.contains(shared.head.path))
    val differences = expressions.filter(_.operation == "analog_sub")
    assert(differences.size == 2)
    assert(differences.forall(_.operands.head == amplified.head.path))
    assert(expressions.count(_.operation == "analog_neg") == 2)
    val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
    assert(text == ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text)
    assert(text.linesIterator.count(_.contains("parameter = @parentFirst}")) == 1)
    assert(text.linesIterator.count(_.contains("parameter = @parentSecond}")) == 1)
    assert(snapshot.interfaceContracts.count(_.boundary.exists(_.selection.nonEmpty)) == 2)
    val _ = Files.writeString(Path.of(args(0)), text)

    def changed(replacement: Vector[KernelParameterExpressionSnapshot]): ConstructionSnapshot =
      snapshot.copy(parameterExpressions = replacement)
    val root = differences.head
    val controls = Vector(
      "division" -> changed(snapshot.parameterExpressions.map(value =>
        if value.path == root.path then value.copy(operation = "analog_div") else value
      )),
      "cycle" -> changed(snapshot.parameterExpressions.map(value =>
        if value.path == root.path then value.copy(operands = Vector(root.path, root.path)) else value
      )),
      "edge-order" -> changed(snapshot.parameterExpressions.filterNot(_.path == shared.head.path) :+
        shared.head)
    )
    controls.foreach: (name, forged) =>
      val attempt = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged, Backend.VerilogA))
      assert(attempt.isFailure, name)
      val diagnostic = attempt.failed.get.toString
      assert(diagnostic.contains("NODAL-BRIDGE-054-INTERFACE"), name + ": " + diagnostic)
      println("PASS " + name + ": " + diagnostic)
    assert(ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text == text)
    println("PASS all four arithmetic operators, shared DAG, equal-default distinct identities and recovery")
