package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import nodal.*
import nodal.increment46structuralfixture.*
import nodal.internal.bridge.ScalaToMlirBridge

/** Actual public Scala source exported for independent native structural checking. */
object TypedEquationMlirCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.nonEmpty, "expected output directory and optional source names")
    val directory = Files.createDirectories(Paths.get(arguments(0)))
    val sources = Vector[(String, () => Module)](
      "balanced" -> (() => new BalancedEquationSource),
      "underconstrained" -> (() => new UnderconstrainedEquationSource),
      "overconstrained" -> (() => new OverconstrainedEquationSource),
      "singular" -> (() => new SingularEquationSource),
      "unused" -> (() => new UnusedEquationSource),
      "repeated-read" -> (() => new RepeatedReadEquationSource),
      "compensating" -> (() => new CompensatingEquationSource),
      "unsupported" -> (() => new UnsupportedEquationSource),
      "guarded" -> (() => new GuardedEquationSource),
      "partial" -> (() => new PartialEquationSource),
      "unscoped" -> (() => new UnscopedEquationSource),
      "cross-component" -> (() => new CrossComponentEquationSource),
      "initialization" -> (() => new InitializationEquationSource),
      "hierarchical" -> (() => new HierarchicalEquationSource),
      "unsupported-initializer" -> (() => new UnsupportedInitializerEquationSource),
      "sparse" -> (() => new SparseEquationSource),
      "shared-dag" -> (() => new SharedDagEquationSource),
      "repeated-hierarchy" -> (() => new RepeatedHierarchyEquationSource),
      "empty-component" -> (() => new EmptyComponentEquationSource)
    )
    val selected = arguments.drop(1).toSet
    sources.filter((name, _) => selected.isEmpty || selected.contains(name)).foreach:
      (name, source) =>
        val document = ScalaToMlirBridge.lower(source(), EmitOptions(backend = Backend.VerilogA))
        val replay = ScalaToMlirBridge.lower(source(), EmitOptions(backend = Backend.VerilogA))
        if document != replay then
          Files.writeString(
            directory.resolve(s"$name.first.mlir"),
            document.text,
            StandardCharsets.UTF_8
          )
          Files.writeString(
            directory.resolve(s"$name.replay.mlir"),
            replay.text,
            StandardCharsets.UTF_8
          )
        require(document == replay, s"unstable source equation export: $name")
        Files.writeString(directory.resolve(s"$name.mlir"), document.text, StandardCharsets.UTF_8)
        println(s"Typed source equation $name: ${document.sha256}")
