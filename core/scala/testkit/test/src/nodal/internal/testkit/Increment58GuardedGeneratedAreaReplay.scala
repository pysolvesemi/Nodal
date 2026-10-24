package nodal.internal.testkit
import nodal.*
import nodal_external.*
import nodal.internal.bridge.*
import java.nio.file.{Files, Path}
import java.security.MessageDigest
object Increment58GuardedGeneratedAreaReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val output = Path.of(args(0)).toAbsolutePath.normalize
    require(output.startsWith(Path.of(".work").toAbsolutePath.normalize), "output must be under .work")
    val _ = Files.createDirectories(output)
    val profiles = Vector[(String, () => Module)](
      "guarded" -> (() => new Increment58GuardedFamilySource),
      "nested" -> (() => new Increment58GuardedNestedFamilySource),
      "helper" -> (() => new Increment58GuardedHelperSource),
      "empty" -> (() => new Increment58GuardedMaybeEmptySource(false)))
    profiles.foreach: (name, make) =>
      val text = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(make())).text
      val path = output.resolve(name + ".mlir")
      val _ = Files.writeString(path, text)
      val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        .map(value => f"${value & 0xff}%02x").mkString
      println(s"F58_GUARDED_GENERATED_AREA_SOURCE $name $sha")
