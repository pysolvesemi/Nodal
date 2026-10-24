package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_selected_target.*
import java.nio.file.{Files, Path}
object SelectedGuardedReplay:
  def main(args: Array[String]): Unit =
    def source: Module = args.head match
      case "guarded" => new GuardedSelectedTarget
    val snapshot = ConstructionKernel.inspect(source)
    val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
    val _ = Files.writeString(Path.of(args(1)), text)
    println("PASS selected target source " + args.head)
