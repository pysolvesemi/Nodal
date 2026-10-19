package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

/** Uses the public source APIs so singleton bypass and topology remain source witnesses. */
final class WideningReductionNativeSource(count: Int, balanced: Boolean, mode: String)
    extends Module, ReductionClock:
  val input = in(Vec(UInt(4), count))
  val result = if balanced then
    mode match
      case "product" => input.reduceBalancedTree((left, right) => left * right)
      case "fixed" => input.reduceBalancedTree((left, right) => (left + right).wrap(8))
      case _ => input.reduceBalancedTree((left, right) => left + right)
  else
    mode match
      case "product" => input.reduce((left, right) => left * right)
      case "fixed" => input.reduce((left, right) => (left + right).wrap(8))
      case _ => input.reduce((left, right) => left + right)

object Increment55WideningReductionNativeTests extends TestSuite:
  private def failure(message: String): Nothing =
    scala.util.Failure[Nothing](new java.lang.AssertionError(message)).get

  private def directory(): Path =
    Files.createDirectories(Path.of(
      ".work",
      "increment55-widening-reduction-native"
    ).toAbsolutePath)

  private def native(document: NodalMlirDocument): NativeCompilerResult =
    val compiler = sys.env.getOrElse("NODAL_NODALC", failure("configured nodalc is required"))
    NativeCompilerClient.run(
      document,
      NativeCompilerRequest(
        Path.of(compiler).toAbsolutePath,
        Vector.empty,
        directory(),
        timeout = Duration.ofSeconds(90)
      )
    )

  private def accepted(document: NodalMlirDocument): String = native(document) match
    case success: NativeCompilerSuccess => success.normalizedMlir
    case rejected: NativeCompilerFailure =>
      failure(s"${rejected.diagnostic}\n${rejected.standardError}")

  private def rejected(name: String, document: NodalMlirDocument, diagnostics: String*): Unit =
    val _ = Files.writeString(directory().resolve(name + ".mlir"), document.text)
    native(document) match
      case result: NativeCompilerFailure =>
        assert(result.exitCode.exists(_ != 0))
        diagnostics.foreach(diagnostic => assert(result.standardError.contains(diagnostic)))
      case _: NativeCompilerSuccess => failure(s"native accepted forged witness $name")

  private def changedLines(document: NodalMlirDocument)(select: String => Boolean)(
      update: String => String
  ): NodalMlirDocument =
    val lines = document.text.linesIterator.toVector
    assert(lines.count(select) == 1)
    val text = lines.map(line => if select(line) then update(line) else line).mkString("\n") + "\n"
    assert(text != document.text)
    document.copy(text = text)

  private def changedIntermediate(
      snapshot: ConstructionSnapshot,
      document: NodalMlirDocument,
      replacement: String,
      opcode: String = "digital_add"
  ): NodalMlirDocument =
    val addition = snapshot.digitalExpressions.find(_.operation == "digital_add").get
    val width = addition.dataType.stripPrefix("UInt(").stripSuffix(")")
    val actual = s"!nodal.digital<\"uint\", \"$width\">"
    assert(document.text.contains(actual))
    document.copy(text =
      document.text.replace(actual, replacement).replace(
        "operator_name = \"digital_add\"",
        s"operator_name = \"$opcode\""
      )
    )

  val tests: Tests = Tests:
    test("35 public widening witnesses verify and round trip with configured native compiler"):
      val named = Vector[(String, () => Module)](
        "symbolic-sums" -> (() => new PublicWideningSums),
        "whole-records" -> (() => new PublicRecordReductions),
        "nested-scopes" -> (() => new PublicNestedReduction),
        "ordered-concat-subtract" -> (() => new PublicOrderedReductions),
        "all-invariant" -> (() => new ReductionInvariantBridgeSource)
      )
      val counted =
        for
          count <- Vector(1, 2, 3, 5, 6)
          balanced <- Vector(false, true)
          mode <- Vector("sum", "fixed", "product")
        yield
          val name = s"$mode-${if balanced then "tree" else "left"}-n$count"
          name -> (() => new WideningReductionNativeSource(count, balanced, mode))
      val witnesses = named ++ counted
      assert(witnesses.size == 35)
      witnesses.foreach: (name, source) =>
        val snapshot = ConstructionKernel.inspect(source())
        val document = ScalaToMlirBridge.fromSnapshot(snapshot)
        val _ = Files.writeString(directory().resolve(name + ".mlir"), document.text)
        val normalized = accepted(document)
        val _ = Files.writeString(directory().resolve(name + ".normalized.mlir"), normalized)
        assert(normalized.contains("\"nodal.collection\"") == snapshot.digitalCollections.nonEmpty)
        assert(accepted(document.copy(text = normalized)) == normalized)

    test("native independently rejects a forged final complete reduction width"):
      val document = ScalaToMlirBridge.lower(new WideningReductionNativeSource(3, false, "sum"))
      val forged = changedLines(document)(line =>
        line.trim.startsWith("}) : (!nodal.shaped<")
      )(line => line.take(line.indexOf(" -> ")) + " -> !nodal.uint<1>")
      rejected(
        "wrong-final-width",
        forged,
        "NODAL-DIGITAL-055-001",
        "exact all-legal topology-derived complete type"
      )

    test("native independently rejects an aliased paired template"):
      val snapshot = ConstructionKernel.inspect(new WideningReductionNativeSource(3, true, "sum"))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      val reducer = snapshot.digitalCollections.head
      val left = s"!nodal.digital<\"uint\", \"${reducer.path}.arg0\">"
      val right = s"!nodal.digital<\"uint\", \"${reducer.path}.arg1\">"
      assert(document.text.contains(left))
      rejected(
        "aliased-paired-template",
        document.copy(text = document.text.replace(left, right)),
        "NODAL-DIGITAL-055-001",
        "invalid raw collection header or finite reduction transfer"
      )

    test("native rejects a narrow intermediate despite a lawful fixed final width"):
      val snapshot = ConstructionKernel.inspect(new WideningReductionNativeSource(3, true, "fixed"))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      rejected(
        "narrow-intermediate",
        changedIntermediate(snapshot, document, "!nodal.uint<1>"),
        "NODAL-DIGITAL-054-008",
        "invalid exact digital kind, width, static index or result proof"
      )

    test("native checks later reachable contexts for both left and balanced grouping"):
      for balanced <- Vector(false, true) do
        def forged(count: Int): NodalMlirDocument =
          val snapshot = ConstructionKernel.inspect(
            new WideningReductionNativeSource(count, balanced, "fixed")
          )
          val document = ScalaToMlirBridge.fromSnapshot(snapshot)
          val reducer = snapshot.digitalCollections.head
          changedIntermediate(
            snapshot,
            document,
            s"!nodal.digital<\"uint\", \"${reducer.path}.arg0\">",
            "digital_xor"
          )
        val first = accepted(forged(2))
        assert(first.contains("operator_name = \"digital_xor\""))
        rejected(
          s"later-invalid-${if balanced then "tree" else "left"}",
          forged(3),
          "NODAL-DIGITAL-054-008",
          "invalid exact digital kind, width, static index or result proof"
        )
