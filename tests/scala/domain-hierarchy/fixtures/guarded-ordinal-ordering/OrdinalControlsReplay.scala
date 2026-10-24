package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_selected_target.*
import java.nio.file.{Files, Path}
object OrdinalControlsReplay:
  def main(args: Array[String]): Unit =
    val snapshot = ConstructionKernel.inspect(args.head match
      case "strict" => new GuardedOrdinalStrict
      case "inclusive" => new GuardedOrdinalInclusive
      case "weak" => new GuardedOrdinalWeak
      case "zero" => new GuardedOrdinalZero
      case "foreign" => new GuardedOrdinalForeign
      case "or" => new GuardedOrdinalOr)
    val _ = Files.writeString(Path.of(args(1)), ScalaToMlirBridge.fromSnapshot(snapshot).text)
    println("PASS " + args.head)
