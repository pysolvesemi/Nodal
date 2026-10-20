package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment57HandshakeTests extends TestSuite:
  private def capture[A <: Data](dataType: DataType[A], mode: String = "ordinary") =
    ConstructionKernel.inspect(new Increment57HandshakeSource(dataType, mode = mode))
  private def fixture(mode: String = "ordinary", count: Int = 2) =
    ConstructionKernel.inspect(new GrayPulseCapture("handshake", mode, count))
  private def reject(body: => Any, fragment: String): Unit =
    val result = scala.util.Try(body)
    assert(result.isFailure, result.failed.get.getMessage.contains(fragment))
  private def bridge(snapshot: ConstructionSnapshot) = ScalaToMlirBridge.fromSnapshot(snapshot).text

  val tests = Tests:
    test("snapshot handshake preserves exact finite scalar Enum Vec and Struct payloads"):
      val snapshots = Vector(
        capture(Bool),
        capture(Bits(4)),
        capture(UInt(4)),
        capture(SInt(4)),
        capture(Increment57GrayPayloads.noZeroEnum),
        capture(Vec(UInt(2), 2)),
        capture(Increment57GrayPayloads.record)
      )
      snapshots.foreach: snapshot =>
        val expression = snapshot.digitalExpressions.find(_.crossing.nonEmpty).get
        val crossing = expression.crossing.get
        assert(
          expression.operation == "cdc_handshake",
          crossing.kind == "handshake",
          crossing.contract == "handshake_snapshot_v1",
          crossing.stages == 2
        )
        assert(crossing.obligations.toMap == Map(
          "delivery" -> "latest_snapshot_coalescing",
          "bundled_data" -> "settled_before_request_capture",
          "reset_epoch" -> "common_assertion"
        ))
        assert(
          crossing.sourceDomain.endsWith(".source"),
          crossing.destinationDomain.endsWith(".destination")
        )
        val text = bridge(snapshot)
        assert(
          text.contains("contract = \"handshake_snapshot_v1\""),
          text.contains("latest_snapshot_coalescing"),
          text.contains("settled_before_request_capture"),
          text.contains("semantic_path = \"" + expression.path + "\"")
        )
        assert(
          ReproducibilityContract.canonicalSnapshot(snapshot).contains("latest_snapshot_coalescing")
        )
      assert(snapshots(4).enumDefinitions.head.cases.forall(_.code != 0))
      assert(snapshots(5).digitalExpressions.find(_.crossing.nonEmpty).get.dataType ==
        "Vec(UInt(2);2)")
    test("snapshot storage accounting has exact two payload plus six control boundary"):
      assert(bridge(capture(Bits(131069))).contains("handshake_snapshot_v1"))
      reject(capture(Bits(131070)), "expansion budget")
      assert(ExecutableCrossingContract.stateBits("handshake", BigInt(131069), 2) == 262144)
    test("handshake excludes non-bit-pattern scalar and recursive leaves"):
      reject(capture(Integer), "finite Vec/Struct bit patterns")
      reject(capture(Real), "finite Vec/Struct bit patterns")
      reject(capture(Clock), "finite Vec/Struct bit patterns")
      reject(capture(Reset), "finite Vec/Struct bit patterns")
      reject(capture(Vec(Real, 2)), "finite Vec/Struct bit patterns")
      reject(
        capture(Struct("InvalidSnapshot", StructField("value", Real))),
        "finite Vec/Struct bit patterns"
      )
    test("handshake shares Pulse nonempty actual reset epoch and safe release proof"):
      reject(capture(UInt(4), "resetless"), "reset-bearing endpoints")
      reject(capture(UInt(4), "different-reset"), "common physical reset roots")
      reject(bridge(capture(UInt(4), "unsafe")), "release")
      assert(bridge(capture(UInt(4), "sync")).contains("handshake_snapshot_v1"))
    test("canonical shaped reset element aliases agree but distinct elements reject"):
      assert(bridge(ConstructionKernel.inspect(new ShapedPulseCapture(
        true,
        "handshake"
      ))).contains("handshake_snapshot_v1"))
      reject(
        ConstructionKernel.inspect(new ShapedPulseCapture(false, "handshake")),
        "common physical reset roots"
      )
    test("handshake retains explicit source provenance and rejects Pulse relabeling"):
      reject(fixture("unknown"), "unknown or unresolved")
      reject(fixture("mixed"), "exactly one known")
      reject(fixture("constant"), "exactly one known")
      reject(fixture("same"), "distinct source")
      reject(fixture("uncrossed"), "NODAL-CDC-001")
      reject(fixture("forged"), "owned Cdc.sync entrypoint")
      reject(fixture("pulse-as-gray"), "Pulse-classified")
    test("independent handshake state retains identity and rejects shared-root reconvergence"):
      val snapshot = fixture()
      val expressions = snapshot.digitalExpressions.filter(_.crossing.nonEmpty)
      assert(expressions.size == 2, expressions.map(_.path).distinct.size == 2)
      assert(expressions.map(_.operands).distinct.size == 1)
      reject(fixture("reconverge"), "NODAL-CDC-057-002")
      assert(bridge(fixture("separate")).contains("handshake_snapshot_v1"))
    test("bridge independently rejects malformed handshake contracts and closed obligations"):
      val snapshot = capture(UInt(4))
      val expression = snapshot.digitalExpressions.find(_.crossing.nonEmpty).get
      def changed(value: KernelDigitalExpressionSnapshot): ConstructionSnapshot =
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(e => if e.path == value.path then value else e)
        )
      def malformed(value: KernelDigitalExpressionSnapshot): Unit =
        assert(scala.util.Try(bridge(changed(value))).isFailure)
      assert(bridge(changed(expression.copy(crossing =
        expression.crossing.map(c => c.copy(obligations = c.obligations.reverse))
      ))).contains("handshake_snapshot_v1"))
      malformed(expression.copy(crossing = None))
      malformed(expression.copy(dataType = "UInt(7)"))
      malformed(expression.copy(operands = Vector.empty))
      malformed(expression.copy(crossing = expression.crossing.map(_.copy(contract = "pulse_v1"))))
      malformed(expression.copy(crossing = expression.crossing.map(_.copy(kind = "pulse"))))
      Vector(0, 1, 3, 32, Int.MaxValue).foreach(stages =>
        malformed(expression.copy(crossing = expression.crossing.map(_.copy(stages = stages))))
      )
      malformed(expression.copy(crossing =
        expression.crossing.map(_.copy(obligations = Vector.empty))
      ))
      malformed(expression.copy(crossing =
        expression.crossing.map(c => c.copy(obligations = c.obligations :+ ("extra" -> "ignored")))
      ))
      malformed(expression.copy(crossing =
        expression.crossing.map(c =>
          c.copy(obligations = c.obligations.updated(0, "delivery" -> "lossless"))
        )
      ))
      malformed(expression.copy(crossing =
        expression.crossing.map(c =>
          c.copy(obligations = c.obligations.updated(0, c.obligations(1)))
        )
      ))
    test("bridge rejects forged reset epoch facts against actual producers"):
      val snapshot = capture(UInt(4))
      val changed = snapshot.copy(modules =
        snapshot.modules.map(m =>
          m.copy(domains =
            m.domains.map(d =>
              if d.name == "destination" then
                d.copy(
                  resetPolicy = Some("none"),
                  attributes = d.attributes.filterNot(_._1 == "effective_reset_source")
                )
              else d
            )
          )
        )
      )
      assert(scala.util.Try(bridge(changed)).isFailure)
    test("representative handshake records remain deterministic and linear"):
      val snapshot = fixture(count = 128)
      assert(snapshot.digitalExpressions.count(_.crossing.nonEmpty) == 128)
      assert(snapshot == fixture(count = 128))
