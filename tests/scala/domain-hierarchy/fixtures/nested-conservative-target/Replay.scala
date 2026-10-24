package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}
object ReplayNestedConservative:
  def main(args: Array[String]): Unit =
    // Deliberately ordinary inspect: no explicit larger construction policy.
    val snapshot = ConstructionKernel.inspect(new NestedConservativeSource)
    val selected = snapshot.interfaceContracts.filter(_.boundary.exists(_.selection.nonEmpty))
    assert(selected.size == 4, selected)
    assert(snapshot.interfaceContracts.count(_.boundary.exists(b =>
      b.selection.isEmpty && b.region.nonEmpty
    )) == 2)
    val bindings = snapshot.modules.flatMap(_.instances).map(_.parameterBindings)
    assert(bindings.contains(Vector("gain" -> "3.0")))
    assert(bindings.contains(Vector("gain" -> "7.0")))
    val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
    val _ = Files.writeString(Path.of(args(0)), text)
    println("PASS default-policy nested source capture; actual native topology and target output still required")
