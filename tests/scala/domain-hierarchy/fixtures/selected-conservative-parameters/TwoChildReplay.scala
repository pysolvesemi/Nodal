package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}
object ReplayTwoLiteralChildren:
  def main(args: Array[String]): Unit =
    val snapshot = ConstructionKernel.inspect(new SelectedTwoLiteralParameters)
    assert(snapshot.interfaceContracts.count(_.boundary.exists(_.selection.nonEmpty)) == 2)
    assert(snapshot.interfaceContracts.count(_.boundary.exists(b =>
      b.selection.isEmpty && b.region.nonEmpty
    )) == 2)
    val bindings = snapshot.modules.flatMap(_.instances).map(_.parameterBindings)
    assert(bindings.contains(Vector("gain" -> "3.0")))
    assert(bindings.contains(Vector("gain" -> "7.0")))
    val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
    val _ = Files.writeString(Path.of(args(0)), text)
    println(
      "PASS two-child authored topology and bindings; native shared-anchor output checks required"
    )
