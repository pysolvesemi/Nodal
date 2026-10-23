package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}
object ReplayLiteralControls:
  def main(args: Array[String]): Unit =
    val defaultSnapshot = ConstructionKernel.inspect(new SelectedLiteralDefault)
    val _ = Files.writeString(
      Path.of(args(0)),
      ScalaToMlirBridge.fromSnapshot(defaultSnapshot, Backend.VerilogA).text
    )
    val alternate = ConstructionKernel.inspect(new SelectedLiteralAlternate)
    val _ = Files.writeString(
      Path.of(args(0)).resolveSibling("alternate.mlir"),
      ScalaToMlirBridge.fromSnapshot(alternate, Backend.VerilogA).text
    )
    val cases: Vector[(String, () => Module, String)] = Vector(
      (
        "parent-reference",
        () => new SelectedLiteralParentReference,
        "ordinary dimensionless Real literals"
      ),
      (
        "expression-actual",
        () => new SelectedLiteralExpressionActual,
        "ordinary dimensionless Real literals"
      ),
      (
        "expression-default",
        () => new SelectedLiteralExpressionDefault,
        "ordinary dimensionless Real literals"
      ),
      ("unit-default", () => new SelectedLiteralUnitDefault, "NODAL-HIERARCHY-036"),
      ("duplicate", () => new SelectedLiteralDuplicate, "NODAL-HIERARCHY-030"),
      ("foreign", () => new SelectedLiteralForeign, "NODAL-PARAMETER")
    )
    cases.foreach: (name, make, expected) =>
      val result = scala.util.Try(ConstructionKernel.inspect(make()))
      assert(result.isFailure, name)
      val message = result.failed.get.toString
      assert(message.contains(expected), name + ": " + message)
      println("PASS " + name + ": " + message)
    val recovered = ConstructionKernel.inspect(new SelectedLiteralParameters)
    assert(recovered.interfaceContracts.count(_.boundary.exists(_.selection.nonEmpty)) == 1)
    println("PASS recovery after all source refusals")
