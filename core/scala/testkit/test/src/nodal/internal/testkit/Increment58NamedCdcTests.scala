package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*
import java.nio.file.{Files, Paths}

object Increment58NamedCdcTests extends TestSuite:
  private def snapshot(mode: String) =
    ConstructionKernel.inspect(new Increment58NamedCdcSource(mode))
  val tests = Tests:
    test("three protocols retain nested unequal leaves and independent observations"):
      for mode <- Vector("gray", "handshake", "fifo") do
        val captured = snapshot(mode)
        val crossings = captured.digitalExpressions.filter(_.crossing.nonEmpty)
        val payloads = crossings.filter(_.crossing.exists(_.resultRole == "payload"))
        assert(payloads.size == 1)
        assert(payloads.head.dataType.contains("NamedCdcPayload"))
        assert(captured.shapeIndices.size == 6)
        val text = ScalaToMlirBridge.fromSnapshot(captured).text
        assert(text.contains("NamedCdcCell"), text.contains("NamedCdcPayload"))
        assert(text.contains("firstSigned"), text.contains("secondHigh"))
        assert(text.contains(if mode == "fifo" then "nodal.async_fifo" else "nodal.crossing"))
    test("capture and bridge are deterministic for each protocol"):
      for mode <- Vector("gray", "handshake", "fifo") do
        val first = snapshot(mode)
        val second = snapshot(mode)
        assert(first == second)
        assert(
          ScalaToMlirBridge.fromSnapshot(first).text ==
            ScalaToMlirBridge.fromSnapshot(second).text
        )
    test("forged crossing payload type cannot replace its exact nested schema"):
      for mode <- Vector("gray", "handshake", "fifo") do
        val captured = snapshot(mode)
        val payload =
          captured.digitalExpressions.find(_.crossing.exists(_.resultRole == "payload")).get
        val forged = captured.copy(digitalExpressions =
          captured.digitalExpressions.map(value =>
            if value.path == payload.path then value.copy(dataType = "Bits(19)") else value
          )
        )
        assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).isFailure)

object Increment58NamedCdcReplay:
  def main(args: Array[String]): Unit =
    val directory = Paths.get(args(0))
    val _ = Files.createDirectories(directory)
    for mode <- Vector("gray", "handshake", "fifo") do
      val captured = ConstructionKernel.inspect(new Increment58NamedCdcSource(mode))
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      val _ = Files.writeString(directory.resolve(mode + ".mlir"), text)
