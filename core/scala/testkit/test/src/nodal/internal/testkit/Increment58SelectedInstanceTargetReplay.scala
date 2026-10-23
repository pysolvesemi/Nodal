package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}

object Increment58SelectedInstanceTargetReplay:
  def main(args: Array[String]): Unit =
    val directory = Path.of(args(0))
    val _ = Files.createDirectories(directory)
    for mode <- List(
        "two",
        "state",
        "nonunit",
        "nested",
        "bindings",
        "symbolic",
        "vec",
        "guarded",
        "struct",
        "varying",
        "varying-nonunit"
      )
    do
      def source: Module = mode match
        case "two" => new Increment58SelectedInstanceTargetTwo
        case "state" => new Increment58SelectedInstanceTargetTwo(stateful = true)
        case "nonunit" => new Increment58SelectedInstanceTargetNonunit
        case "nested" => new Increment58SelectedInstanceTargetNested
        case "bindings" => new Increment58SelectedInstanceTargetBindings
        case "symbolic" => new Increment58SelectedInstanceTargetSymbolic
        case "vec" => new Increment58SelectedInstanceTargetVec
        case "guarded" => new Increment58SelectedInstanceTargetGuarded
        case "struct" => new Increment58SelectedInstanceTargetStruct
        case "varying" => new Increment58SelectedInstanceTargetVarying
        case "varying-nonunit" => new Increment58SelectedInstanceTargetVarying(lower = 2, step = 2)
      val snapshot = ConstructionKernel.inspect(source)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      val _ = Files.writeString(directory.resolve(mode + ".mlir"), text)
      println("PASS selected Instance target source " + mode)
