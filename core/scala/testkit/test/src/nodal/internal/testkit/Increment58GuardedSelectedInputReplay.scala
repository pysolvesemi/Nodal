package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}

/** Actual authored captures; default construction limits are unchanged. */
object Increment58GuardedSelectedInputReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 2, "expected output directory and fixture mode")
    val directory = Path.of(args(0))
    val mode = args(1)
    val snapshot = mode match
      case "guarded-two" => ConstructionKernel.inspect(new Increment58GuardedSelectedInputTwo)
      case "guarded-default-one" => ConstructionKernel.inspect(new Increment58GuardedSelectedInputTwo(1))
      case "guarded-else" => ConstructionKernel.inspect(new Increment58GuardedSelectedInputElse)
      case "guarded-missing" => ConstructionKernel.inspect(new Increment58GuardedSelectedInputMissing)
      case "guarded-duplicate" => ConstructionKernel.inspect(new Increment58GuardedSelectedInputDuplicate)
      case "guarded-local-rhs" => ConstructionKernel.inspect(new Increment58GuardedSelectedInputLocalRhs)
      case "guarded-other-arm" => ConstructionKernel.inspect(new Increment58GuardedSelectedInputOtherArm)
      case _ => throw new IllegalArgumentException("unknown guarded selected-input fixture")
    val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
    val _ = Files.createDirectories(directory)
    val _ = Files.writeString(directory.resolve(mode + ".mlir"), text)
    println("CAPTURE guarded selected input " + mode + "; native target checks remain required")
