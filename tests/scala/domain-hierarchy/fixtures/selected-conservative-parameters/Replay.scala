package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}
object ReplayLiteralParameters:
  def main(args: Array[String]): Unit =
    val snapshot = ConstructionKernel.inspect(new SelectedLiteralParameters)
    val selected = snapshot.interfaceContracts.filter(_.boundary.exists(_.selection.nonEmpty))
    assert(selected.size == 1, selected)
    assert(snapshot.interfaceContracts.count(_.boundary.exists(b =>
      b.selection.isEmpty && b.region.nonEmpty
    )) == 1)
    val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
    val _ = Files.writeString(Path.of(args(0)), text)
    println("PASS distinct literal source bindings and authentic selected/body endpoints")
