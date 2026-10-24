package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}
object SelectedChildConservativeReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1)
    val snapshot = ConstructionKernel.inspect(new SelectedChildConservativeSource)
    val selected = snapshot.interfaceContracts.filter(_.boundary.exists(_.selection.nonEmpty))
    assert(selected.size == 1, selected)
    val leaves = snapshot.modules.flatMap(_.declarations).filter(_.conservativeMember.exists(_.endpoint == selected.head.path))
    assert(leaves.size == 1, leaves)
    assert(leaves.head.conservativeMember.get.member == "p")
    assert(snapshot.interfaceContracts.exists(_.boundary.exists(boundary => boundary.selection.isEmpty && boundary.region.nonEmpty)))
    val selectedEdges = snapshot.topology.filter(edge => edge.left == leaves.head.path || edge.right == leaves.head.path)
    assert(selectedEdges.size == 2 && selectedEdges.map(_.path).distinct.size == 2 && selectedEdges.forall(_.path.nonEmpty))
    val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
    val dir = Path.of(args(0))
    val _ = Files.createDirectories(dir)
    val _ = Files.writeString(dir.resolve("selected-child.mlir"), text)
    println("PASS actual selected child, real body projection, reconstructed alias")
