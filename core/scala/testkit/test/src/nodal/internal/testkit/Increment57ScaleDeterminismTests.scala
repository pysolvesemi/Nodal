package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import utest.*

final class RepresentativeDomainWaivers(count: Int) extends Module:
  val domains = Vector.tabulate(count)(index =>
    ClockDomain.external(
      s"domain_$index",
      ClockEdge.Rising,
      ResetPolicy.None,
      ResetPolarity.ActiveHigh,
      100.MHz
    )
  )
  domains.zipWithIndex.foreach: (source, index) =>
    var captured = Option.empty[Signal[Bool]]
    source:
      val input = in(Bool)
      captured = Some(input)
    val destination = domains((index + 1) % count)
    val transport = Cdc.waive(
      captured.get,
      destination,
      CdcWaiver(
        s"boundary_$index",
        "explicit representative physical assumption",
        ClockRelation.Unknown
      )
    )
    destination:
      val output = out(Bool)
      output := transport

final class RepresentativeNestedRelationships(count: Int) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  var previous = root
  (0 until count).foreach: index =>
    previous = ClockDomain.generated(
      s"derived_$index",
      root.clock.get,
      previous,
      ClockRelation.Same,
      root.reset
    )

object Increment57ScaleDeterminismTests extends TestSuite:
  val tests = Tests:
    test("many domains preserve independent deterministic authored waiver identities"):
      val start = System.nanoTime()
      val first = ConstructionKernel.inspect(new RepresentativeDomainWaivers(64))
      val captured = System.nanoTime()
      val text = ScalaToMlirBridge.fromSnapshot(first).text
      val emitted = System.nanoTime()
      val second = ConstructionKernel.inspect(new RepresentativeDomainWaivers(64))
      assert(first == second)
      assert(first.modules.flatMap(_.domains).size == 64)
      val waivers = first.digitalExpressions.flatMap(_.waiver)
      assert(waivers.size == 64, waivers.map(_.id).distinct.size == 64)
      assert(text == ScalaToMlirBridge.fromSnapshot(second).text)
      assert(ReproducibilityContract.describe(first).manifest ==
        ReproducibilityContract.describe(second).manifest)
      println(s"F57 representative domains=64 waivers=64 capture_ms=${(captured - start) /
          1000000L} bridge_ms=${(emitted - captured) / 1000000L} mlir_chars=${text.length}")
    test("nested physical Same relationships preserve deterministic constraint identities"):
      val start = System.nanoTime()
      val first = ConstructionKernel.inspect(new RepresentativeNestedRelationships(64))
      val captured = System.nanoTime()
      val text = ScalaToMlirBridge.fromSnapshot(first).text
      val emitted = System.nanoTime()
      val second = ConstructionKernel.inspect(new RepresentativeNestedRelationships(64))
      assert(first == second)
      assert(first.modules.flatMap(_.domains).flatMap(_.relationship).size == 64)
      assert(text == ScalaToMlirBridge.fromSnapshot(second).text)
      assert(ReproducibilityContract.describe(first).manifest ==
        ReproducibilityContract.describe(second).manifest)
      println(s"F57 representative nested_relationships=64 capture_ms=${(captured - start) /
          1000000L} bridge_ms=${(emitted - captured) / 1000000L} mlir_chars=${text.length}")

object Increment57ScaleReplay:
  def main(args: Array[String]): Unit =
    val directory = java.nio.file.Files.createDirectories(java.nio.file.Path.of(args(0)))
    Vector(
      "domains-waivers" -> (() => new RepresentativeDomainWaivers(64)),
      "nested-relationships" -> (() => new RepresentativeNestedRelationships(64))
    ).foreach: (name, build) =>
      val snapshot = ConstructionKernel.inspect(build())
      val _ = java.nio.file.Files.writeString(
        directory.resolve(name + ".mlir"),
        ScalaToMlirBridge.fromSnapshot(snapshot).text
      )
