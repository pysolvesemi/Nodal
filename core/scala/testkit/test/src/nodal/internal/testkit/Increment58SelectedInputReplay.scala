package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}

/** Run only against an explicitly verified source projection and matched compiler freeze. */
object Increment58SelectedInputReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 2, "expected output directory and one explicit fixture mode")
    val mode = args(1)
    require(Set("two", "missing", "duplicate-alias", "output-write")(mode))
    val directory = Path.of(args(0))
    val _ = Files.createDirectories(directory)
    val snapshot = ConstructionKernel.inspect(new Increment58SelectedInputTwo(mode))
    val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
    val _ = Files.writeString(directory.resolve(mode + ".mlir"), text)
    println("CAPTURE selected input " + mode + "; native coverage and target checks remain required")
