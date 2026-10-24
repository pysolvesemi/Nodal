package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}
object Increment58ResolvedArrayReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 2)
    val snapshot = capture(args(0))
    val _ = Files.writeString(Path.of(args(1)), ScalaToMlirBridge.fromSnapshot(snapshot).text)
    println("PASS " + args(0))
  def capture(mode: String): ConstructionSnapshot =
    ConstructionKernel.inspect(mode match
      case "child" => new Increment58ResolvedArrayParent
      case "child-wrong-type" => new Increment58ResolvedArrayParent(true)
      case "low" => new Increment58ResolvedArrayLow
      case "high" => new Increment58ResolvedArrayHigh
      case "pass" => new Increment58ResolvedArrayPass("ok")
      case "pass-shape" => new Increment58ResolvedArrayPass("shape")
      case "pass-domain" => new Increment58ResolvedArrayPass("domain")
      case other => new Increment58ResolvedArrayLocal(other))
