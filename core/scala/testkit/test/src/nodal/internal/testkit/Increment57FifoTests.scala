package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

final class FifoProtocolCapture(mode: String = "ordinary", count: Int = 1) extends Module:
  val srcClock = in(Clock)
  val dstClock = in(Clock)
  val reset = in(Reset)
  val unknown = if mode == "unknown" then Some(in(Bool)) else None
  val source = ClockDomain.from(
    srcClock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    100.MHz,
    name = "source"
  )
  val destination = ClockDomain.from(
    dstClock,
    reset,
    ClockEdge.Falling,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    20.MHz,
    name = "destination"
  )
  var sourcePayload = Option.empty[Expr[Bool]]
  var sourceValid = Option.empty[Expr[Bool]]
  var sourceReady = Option.empty[Expr[Bool]]
  source:
    val payload = in(Bool)
    val valid = in(Bool)
    val ready = in(Bool)
    sourcePayload = Some(payload)
    sourceValid = Some(valid)
    sourceReady = Some(ready)
  var destinationControl = Option.empty[Expr[Bool]]
  destination:
    val control = in(Bool)
    destinationControl = Some(control)
  val feedback = if mode.startsWith("feedback") then Some(wire(Bool)) else None
  val payload = mode match
    case "unknown" => unknown.get
    case "constant-payload" | "constant-both" | "feedback-unanchored" => true.B
    case "pulse" => Pulse(sourcePayload.get).value
    case _ => sourcePayload.get
  val valid = mode match
    case value if value.startsWith("feedback") => feedback.get
    case "wrong-valid" => destinationControl.get
    case "constant-valid" => false.B
    case "constant-both" => true.B
    case _ => sourceValid.get
  val input = Stream(payload, valid)
  if mode == "callback" || mode == "caught-callback" then
    val values = in(Vec(Bool, 1))
    val _ = ConstructionKernel.collection("map", Vector(values), None): arguments =>
      if mode == "caught-callback" then
        try
          val _ = Stream(sourcePayload.get, sourceValid.get)
        catch case _: Throwable => ()
      else
        val _ = Stream(sourcePayload.get, sourceValid.get)
      arguments.head
  else if mode == "pipe" then
    val _ = pipe(input, PipelinePolicy())(value => value)
  else if mode == "delay" then
    val _ = input.delay(1)
  else if mode == "standalone" then input.accept(sourceReady.get)
  else if mode == "standalone-wrong-ready" then input.accept(destinationControl.get)
  else if mode == "caught-depth" then
    try
      val _ = Cdc.fifo(input, destination, 3)
    catch case _: Throwable => ()
  else
    if mode == "attach-before-fifo" then input.accept(sourceReady.get)
    val output = Cdc.fifo(input, if mode == "same" then source else destination)
    if mode == "attach-after-fifo" then input.accept(sourceReady.get)
    if mode == "duplicate-fifo" then
      val _ = Cdc.fifo(input, destination)
    feedback.foreach(wire =>
      wire :=
        (if mode == "feedback-conflicting" then input.ready && destinationControl.get
         else input.ready)
    )
    output.accept(if mode == "wrong-ready" || mode == "same" then sourceReady.get
    else destinationControl.get)
    if mode == "duplicate-sink" then output.accept(destinationControl.get)
    val others = Vector.tabulate(count - 1)(_ =>
      val additional = Cdc.fifo(Stream(payload, valid), destination)
      additional.accept(destinationControl.get)
      additional
    )
    destination:
      val state = Reg(false.B)
      state := output.payload &&
        (if mode == "reconverge" then others.head.valid else output.valid)
    source:
      val readyState = Reg(false.B)
      readyState := input.ready

final class FifoForeignCapture extends Module:
  val local = instance(new FifoProtocolCapture("standalone"))
  val target = ClockDomain.external(
    "target",
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    50.MHz
  )
  val crossed = Cdc.fifo(local.module.input, target)

object Increment57FifoTests extends TestSuite:
  private def capture[A <: Data](dataType: DataType[A], depth: Int = 4, mode: String = "ordinary") =
    ConstructionKernel.inspect(new Increment57FifoSource(dataType, depth, mode = mode))
  private def fixture(mode: String = "ordinary", count: Int = 1) =
    ConstructionKernel.inspect(new FifoProtocolCapture(mode, count))
  private def reject(body: => Any, fragment: String): Unit =
    val result = scala.util.Try(body)
    assert(result.isFailure, result.failed.get.getMessage.contains(fragment))
  private def bridge(snapshot: ConstructionSnapshot) = ScalaToMlirBridge.fromSnapshot(snapshot).text

  val tests = Tests:
    test("elastic source retains exact FIFO role identities and the three endpoint operands"):
      val snapshot = capture(UInt(8))
      val results = snapshot.digitalExpressions.filter(_.crossing.nonEmpty)
      assert(results.size == 3, results.map(_.operands).distinct.size == 1)
      val payload = results.find(_.crossing.get.resultRole == "payload").get
      assert(results.forall(_.crossing.get.identity.contains(payload.path)))
      assert(results.map(_.crossing.get.resultRole).toSet == Set("payload", "valid", "ready"))
      assert(payload.operands.size == 3, payload.dataType == "UInt(8)")
      assert(results.filterNot(_ == payload).forall(_.dataType == "Bool"))
      assert(results.forall(e =>
        e.operation == "cdc_fifo" && e.crossing.get.kind == "fifo" &&
          e.crossing.get.contract == "fifo_v1" && e.crossing.get.depth.contains(4)
      ))
      val text = bridge(snapshot)
      assert(text.sliding("\"nodal.async_fifo\"".length).count(_ == "\"nodal.async_fifo\"") == 1)
      assert(text.contains("depth = 4 : i64"), text.contains("valid_and_ready"))
      assert(
        text.contains("hold_payload_and_valid"),
        text.contains("settled_before_read_visibility")
      )
      assert(text.contains("semantic_path = \"" + payload.path + "\""))
    test("finite scalar Enum Vec and Struct payload family reuses exact storage ABI"):
      Vector(
        capture(Bool),
        capture(Bits(8)),
        capture(UInt(8)),
        capture(SInt(8)),
        capture(Increment57GrayPayloads.noZeroEnum),
        capture(Vec(UInt(4), 2)),
        capture(Increment57FifoPayloads.record)
      ).foreach(snapshot => assert(bridge(snapshot).contains("fifo_v1")))
      reject(capture(Integer), "finite Vec/Struct bit patterns")
      reject(capture(Real), "finite Vec/Struct bit patterns")
      reject(capture(Clock), "finite Vec/Struct bit patterns")
      reject(capture(Reset), "finite Vec/Struct bit patterns")
      reject(capture(Vec(Real, 2)), "finite Vec/Struct bit patterns")
      reject(
        capture(Struct("InvalidFifo", StructField("value", Real))),
        "finite Vec/Struct bit patterns"
      )
    test("exact depth and preallocation budget boundaries are neither rounded nor tripled"):
      Vector(2, 4, 8, 256).foreach(depth =>
        assert(bridge(capture(UInt(8), depth)).contains(
          s"depth = $depth : i64"
        ))
      )
      Vector(Int.MinValue, -1, 0, 1, 3, 5, 255, 257, Int.MaxValue).foreach(depth =>
        reject(capture(UInt(8), depth), "power of two from 2 through 256")
      )
      assert(ExecutableCrossingContract.fifoStateBits(BigInt(65531), 2) == 262142)
      assert(bridge(capture(Bits(65531), 2)).contains("fifo_v1"))
      reject(capture(Bits(65532), 2), "expansion budget")
      assert(bridge(capture(Bits(1015), 256)).contains("fifo_v1"))
      reject(capture(Bits(1016), 256), "expansion budget")
    test("ready attachment is unique and unattached sinks finalize as ordinary false drivers"):
      val snapshot = capture(UInt(8), mode = "unattached")
      val fifo = snapshot.digitalExpressions.find(_.crossing.exists(_.resultRole == "payload")).get
      val assignment = snapshot.digitalAssignments.find(_.target == fifo.operands(2)).get
      assert(snapshot.digitalExpressions.exists(value =>
        value.path == assignment.value && value.dataType == "Bool" &&
          value.literal.contains("false")
      ))
      assert(bridge(snapshot).contains("async_fifo"))
      Vector("attach-before-fifo", "attach-after-fifo", "duplicate-fifo", "duplicate-sink")
        .foreach(mode => reject(fixture(mode), "more than one owner"))
    test("actual sampled payload valid and ready domains govern ownership"):
      assert(bridge(fixture()).contains("async_fifo"))
      assert(bridge(fixture("constant-payload")).contains("async_fifo"))
      assert(bridge(fixture("constant-valid")).contains("async_fifo"))
      assert(bridge(fixture("standalone")).contains("nodal.digital_assign"))
      reject(fixture("constant-both"), "exactly one known source domain")
      reject(fixture("wrong-valid"), "exactly one known source domain")
      reject(fixture("wrong-ready"), "destination ready")
      reject(fixture("standalone-wrong-ready"), "actual carrier domain")
      reject(fixture("unknown"), "unknown or unresolved")
      reject(fixture("same"), "distinct source and destination")
      reject(fixture("pulse"), "Pulse-classified")
    test("state-derived ready feedback resolves actual source anchors without a false cycle"):
      assert(bridge(fixture("feedback")).contains("async_fifo"))
      reject(fixture("feedback-conflicting"), "exactly one known source domain")
      reject(fixture("feedback-unanchored"), "exactly one known source domain")
    test("coherent FIFO payload and valid share one identity but independent instances do not"):
      assert(bridge(fixture()).contains("async_fifo"))
      assert(bridge(fixture(count = 2)).contains("async_fifo"))
      reject(fixture("reconverge", 2), "NODAL-CDC-057-002")
      val chain = capture(UInt(8), mode = "chain")
      assert(chain.digitalExpressions.count(_.crossing.nonEmpty) == 6)
      assert(bridge(chain).sliding("\"nodal.async_fifo\"".length).count(_ ==
        "\"nodal.async_fifo\"") == 2)
    test("FIFO shares actual common reset epoch and independently safe release"):
      reject(capture(UInt(8), mode = "resetless"), "reset-bearing endpoints")
      reject(capture(UInt(8), mode = "different-reset"), "common physical reset roots")
      reject(bridge(capture(UInt(8), mode = "unsafe")), "release")
      assert(bridge(capture(UInt(8), mode = "sync")).contains("fifo_v1"))
      reject(
        capture(UInt(8), mode = "sync-bad-frequency"),
        "physical clock aliases have inconsistent frequencies"
      )
    test("continuous Stream keeps valid true without silently claiming consumption"):
      val snapshot = capture(UInt(8), mode = "continuous")
      val fifo = snapshot.digitalExpressions.find(_.crossing.exists(_.resultRole == "payload")).get
      assert(snapshot.digitalExpressions.exists(value =>
        value.path == fifo.operands(1) && value.literal.contains("true")
      ))
      assert(bridge(snapshot).contains("hold_payload_and_valid"))
    test("unsupported pipeline consumers reject instead of dropping explicit valid and ready"):
      reject(fixture("pipe"), "no executable elastic Stream contract")
      reject(fixture("delay"), "no executable elastic Stream contract")
      reject(fixture("caught-depth"), "captured constructor failure")
      reject(fixture("callback"), "requires module-level construction")
      reject(fixture("caught-callback"), "captured constructor failure")
      reject(ConstructionKernel.inspect(new FifoForeignCapture), "actual owned digital value")
    test(
      "bridge independently validates closed FIFO result groups operands roles types and bounds"
    ):
      val snapshot = capture(UInt(8))
      val payload =
        snapshot.digitalExpressions.find(_.crossing.exists(_.resultRole == "payload")).get
      def changed(value: KernelDigitalExpressionSnapshot): ConstructionSnapshot =
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(e =>
            if e.path == value.path then value else e
          )
        )
      def malformed(value: KernelDigitalExpressionSnapshot): Unit =
        assert(scala.util.Try(bridge(changed(value))).isFailure)
      malformed(payload.copy(crossing = None))
      malformed(payload.copy(dataType = "UInt(7)"))
      malformed(payload.copy(operands = payload.operands.take(2)))
      malformed(payload.copy(operands = payload.operands.updated(1, payload.operands.head)))
      malformed(payload.copy(crossing = payload.crossing.map(_.copy(resultRole = "ready"))))
      malformed(payload.copy(crossing = payload.crossing.map(_.copy(identity = Some("missing")))))
      malformed(payload.copy(crossing = payload.crossing.map(_.copy(contract = "gray_v1"))))
      malformed(payload.copy(crossing = payload.crossing.map(_.copy(depth = Some(3)))))
      malformed(payload.copy(crossing = payload.crossing.map(_.copy(depth = None))))
      malformed(payload.copy(crossing = payload.crossing.map(_.copy(stages = 3))))
      malformed(payload.copy(crossing = payload.crossing.map(_.copy(obligations = Vector.empty))))
      malformed(payload.copy(crossing =
        payload.crossing.map(c =>
          c.copy(obligations = c.obligations :+ ("extra" -> "ignored"))
        )
      ))
      val valid = snapshot.digitalExpressions.find(_.crossing.exists(_.resultRole == "valid")).get
      malformed(valid.copy(dataType = "UInt(1)"))
      assert(scala.util.Try(bridge(snapshot.copy(digitalExpressions =
        snapshot.digitalExpressions.filterNot(_.path == valid.path)
      ))).isFailure)
    test("representative FIFO capture is deterministic and linear in instance count"):
      val snapshot = fixture(count = 128)
      assert(snapshot.digitalExpressions.count(_.crossing.nonEmpty) == 384)
      assert(snapshot == fixture(count = 128))
      assert(bridge(snapshot).sliding("\"nodal.async_fifo\"".length).count(_ ==
        "\"nodal.async_fifo\"") == 128)
