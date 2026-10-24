package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}
object Increment58LexicalInterfaceReplay:
  def main(args: Array[String]): Unit =
    val directory = Path.of(args.head)
    val _ = Files.createDirectories(directory)
    Vector[(String, () => Module)](
      "direct" -> (() => new Increment58LexicalInterfaceSource(false)),
      "selected" -> (() => new Increment58LexicalInterfaceSource(true)),
      "nested" -> (() => new Increment58LexicalNestedInterfaceSource),
      "guarded" -> (() => new Increment58LexicalGuardedInterfaceSource),
      "invariant-direct" -> (() => new Increment58InvariantLexicalInterfaceSource(false)),
      "invariant-selected" -> (() => new Increment58InvariantLexicalInterfaceSource(true)),
      "shared" -> (() => new Increment58SharedLexicalDefinitionSource),
      "siblings" -> (() => new Increment58SiblingLexicalDefinitionSource),
      "forward" -> (() => new Increment58SelectedInterfaceForwardSource)
    ).foreach: (name, source) =>
      val snapshot = ConstructionKernel.inspect(source())
      val _ = Files.writeString(
        directory.resolve(name + ".mlir"),
        ScalaToMlirBridge.fromSnapshot(snapshot).text
      )
      println("PASS lexical Interface " + name)
