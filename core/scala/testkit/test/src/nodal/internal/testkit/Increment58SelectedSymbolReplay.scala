package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}
object Increment58SelectedSymbolReplay:
  def main(args: Array[String]): Unit =
    val output = Path.of(args.head)
    val _ = Files.createDirectories(output)
    Vector[(String, () => Module)](
      "fixed" -> (() => new Increment58SelectedInstanceSource),
      "two" -> (() => new Increment58TwoSelectedSource),
      "state" -> (() => new Increment58TwoSelectedSource(true)),
      "width" -> (() => new Increment58WidthSelectedSource),
      "nonunit" -> (() => new Increment58WidthSelectedSource(2, 2)),
      "interface-direct" -> (() => new Increment58DirectSelectedInterface),
      "interface-child" -> (() => new Increment58ChildSelectedInterface),
      "interface-width" -> (() => new Increment58WidthSelectedInterface),
      "interface-connect" -> (() => new Increment58ConnectedSelectedInterface),
      "interface-guarded" -> (() => new Increment58GuardedSelectedInterface),
      "interface-array" -> (() => new Increment58ArraySelectedInterface)
    ).foreach: (name, source) =>
      val snapshot = ConstructionKernel.inspect(source())
      val _ = Files.writeString(
        output.resolve(name + ".mlir"),
        ScalaToMlirBridge.fromSnapshot(snapshot).text
      )
      println("PASS selected symbol " + name)
