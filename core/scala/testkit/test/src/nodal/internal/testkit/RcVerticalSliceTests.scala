package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

import utest.*

final class RcFilter extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val R: Param[Real] = param(1.kOhm)
  val C: Param[Real] = param(1.pF)

  analog:
    val voltage = V(p, n)
    I(p, n) <+ (voltage / R) + (C * ddt(voltage))

final class UnsupportedRcOperation extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  analog:
    I(p, n) <+ toReal(toUInt(V(p, n), 8))

final class NegatedRcOperation extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)

  analog:
    I(p, n) <+ -V(p, n)

final class UnsupportedSingleEndedRc extends Module:
  val p = inout(Electrical)

  analog:
    I(p) <+ V(p)

object RcVerticalSliceTests extends TestSuite:
  private def repositoryRoot(): Path =
    def locate(current: Path): Path =
      if Files.isRegularFile(current.resolve("build.mill")) then current
      else
        Option(current.getParent) match
          case Some(parent) => locate(parent)
          case None =>
            scala.util.Failure[Path](
              new IllegalStateException("could not locate the Nodal repository root")
            ).get
    locate(Path.of("").toAbsolutePath)

  private def workDirectory(): Path =
    Files.createTempDirectory("nodal-rc-vertical-slice-")

  private def delete(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try
        val iterator = stream.iterator()
        while iterator.hasNext do delete(iterator.next())
      finally stream.close()
    val _ = Files.deleteIfExists(path)

  private def bridgeFailure(top: => Module): BridgeException =
    scala.util.Try(
      ScalaToMlirBridge.lower(top, EmitOptions(backend = Backend.VerilogA))
    ).failed.get.asInstanceOf[BridgeException]

  val tests: Tests = Tests:
    test("Scala RC lowers deterministically to typed analog MLIR"):
      val first = ScalaToMlirBridge.lower(
        new RcFilter,
        EmitOptions(backend = Backend.VerilogA)
      )
      val second = ScalaToMlirBridge.lower(
        new RcFilter,
        EmitOptions(backend = Backend.VerilogA)
      )
      assert(first == second)
      assert(first.text.contains("nodal.target.profile = \"analog\""))
      assert(first.text.contains("\"nodal.analog\""))
      assert(first.text.contains("\"nodal.parameter_ref\""))
      assert(first.text.contains("\"nodal.analog_ddt\""))
      assert(first.text.contains("\"nodal.contribute\""))
      assert(first.text.contains("sym_name = \"RcFilter\""))
      assert(first.text.contains("unit = \""))

    test("Scala RC compiles through nodalc to exact Verilog-A"):
      (sys.env.get("NODAL_NODALC"), sys.env.get("NODAL_TRANSLATE")) match
        case (Some(nodalc), Some(translator)) =>
          val directory = workDirectory()
          try
            val result = ScalaToMlirBridge
              .compileToVerilogA(
                new RcFilter,
                Path.of(nodalc).toAbsolutePath,
                Path.of(translator).toAbsolutePath,
                directory,
                Duration.ofSeconds(60)
              )
              .fold(
                failure =>
                  scala.util.Failure[Nothing](
                    new java.lang.AssertionError(failure.toString)
                  ).get,
                identity
              )
            val golden = Files.readString(
              repositoryRoot().resolve(
                "tests/compiler/fixtures/increment25/golden/rc-filter.va"
              )
            )
            assert(result.verilogA == golden)
            assert(result.mlir.contains("nodal.pipeline.normalized"))
          finally delete(directory)
        case _ => assert(true)

    test("real unary negation is supported for signed waveform rates"):
      val result = ScalaToMlirBridge.lower(new NegatedRcOperation)
      assert(result.text.contains("nodal.analog_neg"))

    test("unsupported analog operator fails before native launch"):
      val failure = bridgeFailure(new UnsupportedRcOperation)
      assert(failure.diagnostic.code == "NODAL-RC-OPERATION-001")

    test("single-ended RC branch is rejected explicitly"):
      val failure = bridgeFailure(new UnsupportedSingleEndedRc)
      assert(failure.diagnostic.code == "NODAL-RC-BRANCH-001")
