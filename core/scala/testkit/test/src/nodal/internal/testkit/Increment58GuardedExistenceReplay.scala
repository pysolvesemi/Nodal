package nodal.internal.testkit
import nodal.*
import nodal_external.*
import nodal.internal.bridge.*
import java.nio.file.{Files, Path}
import java.security.MessageDigest
object Increment58GuardedExistenceReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val output = Path.of(args(0)).toAbsolutePath.normalize
    require(
      output.startsWith(Path.of(".work").toAbsolutePath.normalize),
      "output must be under .work"
    )
    val _ = Files.createDirectories(output)
    val profiles = Vector[(String, () => Module)](
      "positive" -> (() => new Increment58GuardedExistenceSource),
      "negated" -> (() => new Increment58GuardedExistenceNegated),
      "false-arm" -> (() => new Increment58GuardedExistenceFalseArm),
      "and" -> (() => new Increment58GuardedExistenceAnd),
      "or" -> (() => new Increment58GuardedExistenceOr)
    )
    profiles.foreach: (name, make) =>
      val text = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(make())).text
      val path = output.resolve(name + ".mlir")
      val _ = Files.writeString(path, text)
      val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        .map(value => f"${value & 0xff}%02x").mkString
      println(s"F58_GUARDED_EXISTENCE_SOURCE $name $sha")
