package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment58GeneratedOccurrenceTests extends TestSuite:
  private def capture() = ConstructionKernel.inspect(new Increment58GeneratedOccurrenceSource)
  private def rejected(body: => Module): Unit =
    val failure = scala.util.Try(ConstructionKernel.inspect(body)).failed.get
    assert(failure.isInstanceOf[ConstructionException])
    assert(failure.getMessage.contains("NODAL-COLLECTION-055-005"))

  val tests = Tests:
    test("million-lane domain retains one actual typed child occurrence template"):
      val snapshot = capture()
      val root = snapshot.modules.find(_.path == snapshot.root).get
      val family = snapshot.generatedRegions.head
      val instance = root.instances.head
      assert(snapshot.modules.size == 2, root.instances.size == 1)
      assert(snapshot.generatedRegions.size == 1, family.maximumTripCount == 1000000)
      assert(instance.generatedScope.exists(_.owner == family.path))
      assert(instance.actualBindings.size == 1, instance.parameterBindings.size == 1)
      assert(root.declarations.filter(_.instancePort.nonEmpty).forall(
        _.generatedScope == instance.generatedScope
      ))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.linesIterator.count(_.contains("\"nodal.instance\"(")) == 1)
      assert(text.linesIterator.count(_.contains("\"nodal.parameter_override\"(")) == 1)
    test("sealed proxy descriptors recheck late scalar and Struct assignment types"):
      val _ = ScalaToMlirBridge.lower(new Increment58DirectOccurrenceSource)
      val _ = ScalaToMlirBridge.lower(new Increment58GeneratedOccurrenceStructSource)
      rejected(new Increment58GeneratedOccurrenceSource("wrong"))
      rejected(new Increment58GeneratedOccurrenceSource("foreign"))
      rejected(new Increment58DirectOccurrenceSource(true))
      rejected(new Increment58GeneratedOccurrenceStructSource(true))
    test("nested actual families retain one child and ordered selection prefixes"):
      val snapshot = ConstructionKernel.inspect(new Increment58OccurrenceNestedSource)
      assert(snapshot.generatedRegions.size == 2)
      assert(snapshot.modules.flatMap(_.instances).size == 1)
      val member = snapshot.digitalExpressions.flatMap(_.generatedMember).head
      assert(member.selectionPrefix.size == 1)
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("child constructor owns its local state and required domain"):
      val snapshot = ConstructionKernel.inspect(new Increment58OccurrenceStateSource)
      val child = snapshot.modules.find(_.path != snapshot.root).get
      assert(child.domains.count(_.kind == "required") == 1)
      assert(child.declarations.count(_.kind == "register") == 1)
      assert(child.declarations.forall(_.generatedScope.isEmpty))
      val _ = ScalaToMlirBridge.fromSnapshot(snapshot)
    test("ordinary Scala loops remain elaboration-only and empty hardware loops retain templates"):
      val ordinary = ConstructionKernel.inspect(new Increment58OccurrenceElaborationLoopSource)
      assert(ordinary.generatedRegions.isEmpty)
      assert(ordinary.modules.flatMap(_.instances).size == 3)
      assert(ordinary.modules.flatMap(_.instances).forall(_.generatedScope.isEmpty))
      val empty = ConstructionKernel.inspect(new Increment58OccurrenceEmptySource)
      assert(empty.generatedRegions.head.maximumTripCount == 0)
      assert(empty.modules.flatMap(_.instances).size == 1)
      val _ = ScalaToMlirBridge.fromSnapshot(empty)
    test("child capture selector ownership and output drive capability cannot escape"):
      val cases: Vector[(String, () => Module)] = Vector(
        "NODAL-COLLECTION-055-005" -> (() => new Increment58OccurrenceForeignCaptureSource),
        "NODAL-BINDING-019" -> (() => new Increment58OccurrenceForeignSelectorSource),
        "NODAL-DIGITAL-054-010" -> (() => new Increment58OccurrenceOutputDriveSource)
      )
      cases.foreach: (code, source) =>
        val failure = scala.util.Try(ConstructionKernel.inspect(source())).failed.get
        assert(failure.isInstanceOf[ConstructionException], failure.getMessage.contains(code))
    test("bridge independently rejects forged occurrence scope and port direction"):
      val snapshot = capture()
      val root = snapshot.modules.find(_.path == snapshot.root).get
      val original = root.instances.head
      val proxy = root.declarations.find(_.instancePort.nonEmpty).get
      val forgedScope = root.copy(instances =
        Vector(original.copy(
          generatedScope = original.generatedScope.map(_.copy(owner = "foreign"))
        ))
      )
      val forgedPort = root.copy(declarations =
        root.declarations.map(value =>
          if value.path == proxy.path then
            value.copy(instancePort =
              value.instancePort.map(
                _.copy(direction = "output")
              )
            )
          else value
        )
      )
      Vector(forgedScope, forgedPort).foreach: forged =>
        val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot.copy(
          modules = snapshot.modules.map(value => if value.path == root.path then forged else value)
        ))).failed.get
        assert(failure.isInstanceOf[BridgeException])

object Increment58GeneratedOccurrenceNativeTests extends TestSuite:
  private def gate(name: String, document: NodalMlirDocument, accepted: Boolean): Unit =
    val directory = Path.of(sys.env("NODAL_ROOT"), ".work", "f58-generated-occurrence-tests", name)
      .toAbsolutePath
    val _ = Files.createDirectories(directory)
    val _ = Files.writeString(directory.resolve("input.mlir"), document.text)
    val result = NativeCompilerClient.run(
      document,
      NativeCompilerRequest(
        Path.of(sys.env("NODAL_NODALC")).toAbsolutePath,
        Vector("--nodal-gate-default"),
        directory
      )
    )
    val _ = Files.writeString(directory.resolve("diagnostics.txt"), result.standardError)
    assert(result.isInstanceOf[NativeCompilerSuccess] == accepted)
    if !accepted then assert(result.standardError.contains("NODAL-"))

  val tests = Tests:
    test("actual public scalar Struct nested stateful empty and elaboration-only sources pass"):
      val sources: Vector[(String, () => Module)] = Vector(
        "scalar" -> (() => new Increment58GeneratedOccurrenceSource),
        "struct" -> (() => new Increment58GeneratedOccurrenceStructSource),
        "nested" -> (() => new Increment58OccurrenceNestedSource),
        "state" -> (() => new Increment58OccurrenceStateSource),
        "empty" -> (() => new Increment58OccurrenceEmptySource),
        "scala-loop" -> (() => new Increment58OccurrenceElaborationLoopSource)
      )
      sources.foreach((name, source) => gate(name, ScalaToMlirBridge.lower(source()), true))
    test("generated Integer actuals retain exact formal owner envelope and closed schema"):
      val document = ScalaToMlirBridge.lower(new Increment58GeneratedOccurrenceSource)
      val original = document.text
      val line = original.linesIterator.find(_.contains("\"nodal.parameter_override\"(")).get
      val induction = "\\^bb0\\((%[^:]+): i64\\)".r.findFirstMatchIn(original).get.group(1)
      val zeroLine = original.linesIterator.find(value =>
        value.contains("value = 0 : i64") && value.contains(": () -> i64")
      ).get
      val zero = "(%[A-Za-z0-9_]+) =".r.findFirstMatchIn(zeroLine).get.group(1)
      def operand(value: String): String =
        "\"nodal.parameter_override\"\\([^)]*\\)".r.replaceFirstIn(
          line,
          java.util.regex.Matcher.quoteReplacement(s"\"nodal.parameter_override\"($value)")
        )
      val variants = Vector(
        "missing-formal" -> line.replace("parameter = @WIDTH", "parameter = @MISSING"),
        "missing-instance" -> "instance = @[^,]+".r.replaceFirstIn(line, "instance = @missing"),
        "forged-owner" -> "generated_owner = \"[^\"]+\"".r.replaceFirstIn(
          line,
          "generated_owner = \"foreign\""
        ),
        "duplicate-actual" -> (line + "\n" + line),
        "outside-envelope" -> operand(zero),
        "induction-actual" -> operand(induction),
        "asserted-proof" -> line.replace("}> :", "}> {claimed_invariant = true} :")
      )
      gate("actual-parameterized", document, true)
      variants.foreach: (name, changed) =>
        assert(changed != line)
        val text = original.replace(line, changed)
        val hash =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))
            .map(value => f"${value & 0xff}%02x").mkString
        gate(name, document.copy(text = text, sha256 = hash), false)
