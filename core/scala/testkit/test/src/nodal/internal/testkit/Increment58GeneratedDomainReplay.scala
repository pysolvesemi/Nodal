package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}
object Increment58GeneratedDomainReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val output = Path.of(args(0)).toAbsolutePath.normalize
    require(
      output.startsWith(Path.of(".work").toAbsolutePath.normalize),
      "output must be under .work"
    )
    val _ = Files.createDirectories(output)
    Vector[(String, () => Module)](
      "nonzero" -> (() => new Increment58GeneratedNonzeroBounds),
      "nonunit" -> (() => new Increment58GeneratedNonunitBounds),
      "empty" -> (() => new Increment58GeneratedEmptyBounds),
      "maybe-empty" -> (() => new Increment58GeneratedMaybeEmptyBounds),
      "guarded" -> (() => new Increment58GeneratedGuardedBounds),
      "captured" -> (() => new Increment58GeneratedCapturedBounds),
      "captured-nonunit" -> (() => new Increment58GeneratedCapturedNonunitBounds),
      "captured-selected" -> (() => new Increment58GeneratedCapturedSelectedBounds),
      "guarded-scalar" -> (() => new Increment58GeneratedGuardedScalarExpression),
      "guarded-symbolic" -> (() => new Increment58GeneratedGuardedSymbolicExpression),
      "guarded-nested" -> (() => new Increment58GeneratedGuardedNestedExpression),
      "nested-guard" -> (() => new Increment58NestedGuardedRegisters)
    ).foreach: (name, make) =>
      val document = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(make()))
      val _ = Files.writeString(output.resolve(name + ".mlir"), document.text)
      println(s"F58_GENERATED_DOMAIN_SOURCE $name ${document.sha256}")
