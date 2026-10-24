package nodal.internal.testkit
import java.nio.file.{Files, Paths}
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
object Increment58DeferredJoinReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1)
    val directory = Paths.get(args(0))
    val _ = Files.createDirectories(directory)
    for
      mode <- Vector("direct", "nested", "when", "family"); concrete <- Vector(false, true);
      late <- Vector(false, true)
    do
      val snapshot =
        ConstructionKernel.inspect(new Increment58DeferredJoinSource(late, concrete, mode))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      val path = directory.resolve(s"$mode-$concrete-$late.mlir")
      val _ = Files.writeString(path, document.text)
      println(s"$path ${document.sha256}")
