package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}

object ReplayNestedConservativeAnchor:
  def main(args: Array[String]): Unit =
    val snapshot = ConstructionKernel.inspect(new NestedConservativeAnchorSource)
    val selected = snapshot.interfaceContracts.filter(_.boundary.exists(_.selection.nonEmpty))
    // Repeated selection of the same original child and tuple reuses the canonical boundary.
    assert(selected.size == 1, selected)
    assert(snapshot.interfaceContracts.count(_.boundary.exists(boundary =>
      boundary.selection.isEmpty && boundary.region.nonEmpty
    )) == 1)
    val selection = selected.head.boundary.get.selection.get
    assert(selection.selectionPrefix.size == 1)
    assert(selection.selectionPrefix.head.ordinal == "0" && selection.ordinal == "0")
    assert(snapshot.modules.flatMap(_.instances).map(_.parameterBindings)
      .contains(Vector("gain" -> "3.0")))
    val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
    assert(text.contains("nodal.generated_member"))
    val _ = Files.writeString(Path.of(args(0)), text)
    println("PASS default-policy shared-anchor routing and repeated tuple00 identity; native target check pending")
