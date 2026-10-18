package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.{AnalogProceduralMlir, ScalaToMlirBridge}
import utest.*

/** Source inventories remain the canonical authored provenance before native statement mapping. */
object Increment48SourceTests extends TestSuite:
  private def inventory(text: String): String =
    val start = text.indexOf("nodal.bridge.source_map = [")
    val end = text.indexOf("nodal.bridge.topology", start)
    assert(start >= 0, end > start)
    text.substring(start, end)

  private def entries(text: String): Vector[String] =
    "\\{[^{}]*\\}".r.findAllIn(inventory(text)).toVector

  private def operationCount(text: String, name: String): Int =
    text.linesIterator.count(_.contains(s"\"nodal.$name\"("))

  private lazy val captures = Increment48PublicSources.all.map(witness =>
    witness -> Increment48PublicSources.capture(witness)
  )

  val tests: Tests = Tests:
    test("default and explicit false preserve identical bridge bytes and identity"):
      captures.foreach: (_, snapshot) =>
        val default = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA)
        val disabled = ScalaToMlirBridge.fromSnapshot(
          snapshot,
          Backend.VerilogA,
          sourceProvenance = false
        )
        assert(default == disabled)
        assert(!default.text.contains("nodal.backend.source_provenance"))

    test("opted in provenance changes only the backend request flag"):
      captures.foreach: (_, snapshot) =>
        val default = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA)
        val enabled = ScalaToMlirBridge.fromSnapshot(
          snapshot,
          Backend.VerilogA,
          sourceProvenance = true
        )
        assert(enabled.text.contains("nodal.backend.source_provenance = true"))
        assert(enabled.text.replace("nodal.backend.source_provenance = true, ", "") == default.text)
        assert(enabled.sha256 != default.sha256)
        assert(inventory(default.text) == inventory(enabled.text))
        assert(enabled == ScalaToMlirBridge.fromSnapshot(
          snapshot,
          Backend.VerilogA,
          sourceProvenance = true
        ))

    test("RC storage and hierarchy retain every authored source entry and full graph"):
      captures.filter((witness, _) =>
        Set("rc", "storage", "hierarchy").contains(witness.name)
      ).foreach: (witness, snapshot) =>
        val document = ScalaToMlirBridge.fromSnapshot(
          snapshot,
          Backend.VerilogA,
          sourceProvenance = true
        )
        val mapped = entries(document.text)
        assert(snapshot.sourceMap.nonEmpty)
        snapshot.sourceMap.foreach: entry =>
          val matching = mapped.filter(_.contains(s"semantic_path = \"${entry.semanticPath}\""))
          assert(matching.size == 1)
          val span = entry.source
          val fields = Vector(
            s"source_path = \"${span.path}\"",
            s"source_line = ${span.line} : i64",
            s"source_column = ${span.column} : i64",
            s"source_end_line = ${span.endLine} : i64",
            s"source_end_column = ${span.endColumn} : i64"
          )
          assert(fields.forall(matching.head.contains))
          assert(span.path.endsWith(witness.sourceFile))
          assert(span.line > 0, span.column > 0, span.endLine >= span.line)
          assert(span.endLine > span.line || span.endColumn >= span.column)
        val procedural = AnalogProceduralMlir.sourceMapEntries(snapshot)
        assert(procedural.forall(mapped.contains))
        assert(mapped.size == snapshot.sourceMap.size + procedural.size)
        assert(operationCount(document.text, "instance") == witness.modulePaths.size - 1)
        assert(operationCount(document.text, "contribute") == 1)
        assert(inventory(document.text) == inventory(ScalaToMlirBridge.fromSnapshot(
          snapshot.copy(sourceMap = snapshot.sourceMap.reverse),
          Backend.VerilogA,
          sourceProvenance = true
        ).text))

    test("hierarchy predecessor witnesses preserve repeated nested and parent-owned behavior"):
      captures.filter((witness, _) => witness.name.startsWith("hierarchy"))
        .foreach: (witness, snapshot) =>
          val document = ScalaToMlirBridge.fromSnapshot(
            snapshot,
            Backend.VerilogA,
            sourceProvenance = true
          )
          // Compatible branch definitions are emitted once; both top instances stay present.
          val emittedInstances = if witness.name == "hierarchy-nested" then 3
          else witness.modulePaths.size - 1
          assert(operationCount(document.text, "instance") == emittedInstances)
          assert(operationCount(document.text, "instance_terminal") == 2 * emittedInstances)
          assert(operationCount(document.text, "connect") == 2 * emittedInstances)
          assert(witness.modulePaths.forall(path => inventory(document.text).contains(path)))
          witness.name match
            case "hierarchy-equation" =>
              assert(snapshot.analogSemantics.equations.size == 1)
              assert(document.text.contains("nodal.bridge.analog_semantics"))
              assert(document.text.contains(snapshot.analogSemantics.equations.head.identity.value))
            case "hierarchy-function" => assert(snapshot.analogFunctions.size == 1)
            case "hierarchy-event" =>
              assert(operationCount(document.text, "analog_initial_step") == 1)
            case "hierarchy" => assert(operationCount(document.text, "analog_context") == 2)
            case _ => ()
