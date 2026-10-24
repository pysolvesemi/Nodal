package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}

object Increment58SelectedInputDomainReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 2, "expected output directory and domain mode")
    val mode = args(1)
    require(Set("matching", "foreign", "neutral-attributed")(mode))
    val snapshot =
      if mode == "neutral-attributed" then
        ConstructionKernel.inspect(new Increment58SelectedInputNeutralAttributed)
      else
        ConstructionKernel.inspect(new Increment58SelectedInputDomainTwo(mode == "foreign"))
    val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
    val directory = Path.of(args(0))
    val _ = Files.createDirectories(directory)
    val _ = Files.writeString(directory.resolve(mode + ".mlir"), text)
    println("CAPTURE selected input domain " + mode + "; native checks remain required")
