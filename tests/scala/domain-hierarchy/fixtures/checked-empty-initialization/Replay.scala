package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_empty_init.*
import java.nio.file.{Files, Path}
object EmptyInitializedReplay:
  def main(args: Array[String]): Unit =
    val captured = ConstructionKernel.inspect(args.head match
      case mode if mode.startsWith("bridge-") => new EmptyInitializedSource
      case "empty" => new EmptyInitializedSource
      case "attained" => new AttainedInitializedSource
      case "negative" => new Case_negative
      case "zero" => new Case_zero
      case "badoperand" => new Case_badoperand
      case "runtime" => new Case_runtime
      case "unbounded" => new Case_unbounded
      case "parameter" => new Case_parameter
      case "attainedfit" => new Case_attainedfit
      case "attainedbad" => new Case_attainedbad
      case "attainedzero" => new Case_attainedzero
      case "signed" => new Case_signed
      case "numeric" => new Case_numeric
      case "foreign" => new Case_foreign
      case "nested" => new Case_nested)
    val snapshot = if args.head.startsWith("bridge-") then
      captured.copy(digitalExpressions = captured.digitalExpressions.map: expression =>
        if expression.literal.nonEmpty && expression.dataType.startsWith("UInt(") then
          args.head match
            case "bridge-negative" => expression.copy(literal = Some("-1"))
            case "bridge-malformed" => expression.copy(literal = Some("garbage"))
            case "bridge-canonical" => expression.copy(literal = Some("00"))
            case "bridge-owner" => expression.copy(owner = "Foreign")
            case _ => expression
        else expression
      )
    else captured
    val _ = Files.writeString(Path.of(".work/f58-empty-initialized-storage/" + args.head + ".mlir"), ScalaToMlirBridge.fromSnapshot(snapshot).text)
    println("PASS " + args.head)
