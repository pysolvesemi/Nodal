package nodal.internal.testkit

import java.nio.file.{Files, Paths}
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*

object Increment57HierarchyReplay:
  def main(args: Array[String]): Unit =
    val directory = Paths.get(args(0))
    val _ = Files.createDirectories(directory)
    def save(name: String, module: => Module): Unit =
      val document = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(module))
      val _ = Files.writeString(directory.resolve(name + ".mlir"), document.text)
    Vector("default", "named", "inherited").foreach(kind =>
      save(kind, new Increment57HierarchySource(binding = kind))
    )
    save("grandchild-reset", new Increment57HierarchySource(nested = true))
    save("child-crossing", new Increment57HierarchyCrossingSource)
    save("parent-crossing", new Increment57HierarchyParentCrossingSource)
    save("siblings", new HierarchyBridgeRepeatedBindings)
    save("siblings-resetless", new HierarchyBridgeRepeatedBindings(resetlessSecond = true))
