package nodal.internal.testkit

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*

import utest.*

final class Increment43SymbolicGeneratedNode extends Module:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)

  hdlRange(0, lanes): _ =>
    val tap = node(Electrical)
    val _ = tap
    ()

final class Increment43LiteralGeneratedNode extends Module:
  hdlRange(0, 3): _ =>
    val tap = node(Electrical)
    val _ = tap
    ()

final class Increment43MissingRange extends Module:
  val lanes: Param[Integer] = param(2.integer)

  hdlRange(0, lanes): _ =>
    ()

final class Increment43AmbiguousDirection extends Module:
  val upper: Param[Integer] = param(1.integer, range = -1 to 3)

  hdlRange(0, upper): _ =>
    ()

final class Increment43MaximumTooSmall extends Module:
  val lanes: Param[Integer] = param(2.integer, range = 1 to 4)

  hdlRange(0, lanes, 1, 2): _ =>
    ()

final class Increment43UnsupportedGeneratedWire extends Module:
  hdlRange(0, 2): _ =>
    val generatedWire = wire(UInt(1))
    val _ = generatedWire
    ()

object Increment43RangeConstructionTests extends TestSuite:
  private def workDirectory(): Path =
    Files.createTempDirectory("nodal-increment43-range-")

  private def delete(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try
        val iterator = stream.iterator()
        while iterator.hasNext do delete(iterator.next())
      finally stream.close()
    val _ = Files.deleteIfExists(path)

  private def constructionFailure(body: => Any): ConstructionException =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException]

  val tests: Tests = Tests:
    test("bounded symbolic hdlRange captures stable generated ownership"):
      val first = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)
      val second = ConstructionKernel.inspect(new Increment43SymbolicGeneratedNode)

      assert(first == second)
      assert(first.generatedRegions.size == 1)
      val region = first.generatedRegions.head
      assert(region.owner == first.root)
      assert(region.parent.isEmpty)
      assert(region.lower == "0")
      assert(region.upperExclusive.endsWith(".lanes"))
      assert(region.step == "1")
      assert(region.maximum.isEmpty)
      assert(region.maximumTripCount == 4)
      assert(region.declarations.size == 1)
      assert(region.declarations.head.endsWith(".tap"))

      val parameter = first.modules.head.declarations.find(_.name == "lanes").get
      val attributes = parameter.attributes.toMap
      assert(attributes("integer_range_lower") == "1")
      assert(attributes("integer_range_upper") == "4")
      assert(attributes("classification") == "structural")
      assert(attributes("structural_effects") == "generate")

    test("literal hdlRange retains a concrete finite envelope"):
      val snapshot = ConstructionKernel.inspect(new Increment43LiteralGeneratedNode)
      val region = snapshot.generatedRegions.head
      assert(region.lower == "0")
      assert(region.upperExclusive == "3")
      assert(region.step == "1")
      assert(region.maximumTripCount == 3)

    test("bridge emits native parameter range envelope and generated analog node"):
      val first = ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNode)
      val second = ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNode)

      assert(first == second)
      assert(first.text.contains("\"nodal.parameter_constraint\""))
      assert(first.text.contains("\"nodal.parameter_envelope\""))
      assert(first.text.contains("classification = \"structural\""))
      assert(first.text.contains("policy = \"static_generate\""))
      assert(first.text.contains("\"nodal.generate\""))
      assert(first.text.contains("\"nodal.node\""))
      assert(first.text.contains("maximum_trip_count = 4 : i64"))
      assert(first.text.contains("upper = @lanes"))
      assert(first.text.indexOf("\"nodal.generate\"") < first.text.indexOf("\"nodal.node\""))

    test("symbolic generation rejects missing finite parameter ranges"):
      val failure = constructionFailure(
        ConstructionKernel.inspect(new Increment43MissingRange)
      )
      assert(failure.diagnostic.code == "NODAL-ITERATION-043-001")

    test("symbolic generation rejects direction that changes over legal settings"):
      val failure = constructionFailure(
        ConstructionKernel.inspect(new Increment43AmbiguousDirection)
      )
      assert(failure.diagnostic.code == "NODAL-ITERATION-043-003")

    test("explicit maximum is enforced against the proven parameter envelope"):
      val failure = constructionFailure(
        ConstructionKernel.inspect(new Increment43MaximumTooSmall)
      )
      assert(failure.diagnostic.code == "NODAL-ITERATION-043-001")

    test("unsupported generated object kinds fail closed"):
      val failure = constructionFailure(
        ConstructionKernel.inspect(new Increment43UnsupportedGeneratedWire)
      )
      assert(failure.diagnostic.code == "NODAL-ITERATION-043-004")

    test("locked nodalc verifies public symbolic hdlRange when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val document = ScalaToMlirBridge.lower(new Increment43SymbolicGeneratedNode)
            NativeCompilerClient.run(
              document,
              NativeCompilerRequest(
                executable = Path.of(executable).toAbsolutePath,
                arguments = Vector("--pass-pipeline=builtin.module(nodal-verify-parameters)"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            ) match
              case success: NativeCompilerSuccess =>
                assert(success.normalizedMlir.contains("\"nodal.generate\""))
                assert(success.normalizedMlir.contains("\"nodal.parameter_envelope\""))
              case failure: NativeCompilerFailure =>
                scala.Predef.assert(
                  false,
                  s"${failure.diagnostic}\n${failure.standardError}"
                )
          finally delete(directory)
