package nodal.internal.testkit
import nodal.*
import nodal_external.*
import nodal.internal.bridge.*
import java.nio.file.{Files, Path}

object Increment58SelectedDependentAreaReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val output = Path.of(args(0)).toAbsolutePath.normalize
    require(
      output.startsWith(Path.of(".work").toAbsolutePath.normalize),
      "output must be under .work"
    )
    val _ = Files.createDirectories(output)
    val cases = Vector[(String, () => Module)](
      "selected" -> (() => new Increment58SelectedDependentArea(1, 1)),
      "nonunit-selected" -> (() => new Increment58SelectedDependentArea(1, 4, true)),
      "symbolic-selected" -> (() => new Increment58SymbolicSelectedDependentArea),
      "symbolic-step-selected" -> (() => new Increment58SymbolicStepSelectedDependentArea),
      "active-selected" -> (() => new Increment58ActiveSelectedDependentArea),
      "guarded-selected" -> (() => new Increment58GuardedSelectedDependentArea(true)),
      "foreign-pair" -> (() => new Increment58ForeignSelectedDependentArea),
      "symbolic-bounds-fixed-step" -> (() => new Increment58SymbolicBoundsFixedStepRegisters),
      "symbolic-domain" -> (() => new Increment58SymbolicDomainRegisters),
      "nonunit-dependent-registers" -> (() => new Increment58NonunitDependentRegisters)
    )
    cases.foreach: (name, make) =>
      val document = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(make()))
      val _ = Files.writeString(output.resolve(name + ".mlir"), document.text)
      println(s"F58_SELECTED_DEPENDENT_SOURCE $name ${document.sha256}")
