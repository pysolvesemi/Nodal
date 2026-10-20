package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

final class GrayPulseCapture(kind: String, mode: String = "ordinary", count: Int = 2)
    extends Module:
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
  var input = Option.empty[Expr[Bool]]
  var separate = Option.empty[Expr[Bool]]
  source:
    val value = in(Bool)
    val other = in(Bool)
    input = Some(value)
    separate = Some(other)
  var foreign = Option.empty[Expr[Bool]]
  destination:
    val value = in(Bool)
    foreign = Some(value)
  val selected = mode match
    case "unknown" => unknown.get
    case "mixed" => input.get && foreign.get
    case "constant" => true.B
    case "pulse-as-gray" => Pulse(input.get).value
    case _ => input.get
  if mode == "forged" then
    ConstructionKernel.expression(new KernelExpr[Bool](
      Vector(selected),
      resultType = Some(KernelTypeDescriptor("Bool")),
      operation = Some("cdc_" + kind)
    ))
  else
    val crossings = Vector.tabulate(count)(index =>
      val value = if mode == "separate" && index != 0 then separate.get else selected
      val target = if mode == "same" then source else destination
      kind match
        case "gray" => Cdc.gray(Gray(value), target)
        case "handshake" => Cdc.handshake(value, target)
        case _ => Cdc.pulse(Pulse(value), target)
    )
    if mode == "pulse-result-level" then
      val _ = Cdc.sync(crossings.head && true.B, source)
    if mode == "pulse-result-gray" then
      val _ = Cdc.gray(Gray(crossings.head && true.B), source)
    if mode == "pulse-result-pulse" then
      val _ = Cdc.pulse(Pulse(crossings.head), source)
    destination:
      val state = Reg(false.B)
      state :=
        (if mode == "uncrossed" then input.get
         else if mode == "reconverge" || mode == "separate" then crossings.head && crossings.last
         else crossings.head)
      val outputValue = out(Bool)
      outputValue := crossings.last

final class ShapedPulseCapture(sameRoot: Boolean, kind: String = "pulse") extends Module:
  val clocks = in(Vec(Clock, 2))
  val resets = in(Vec(Reset, 2))
  val source = ClockDomain.from(
    clocks.at(0),
    resets.at(0),
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    100.MHz,
    name = "source"
  )
  val destination = ClockDomain.from(
    clocks.at(1),
    resets.reshape(1, 2).at(0, if sameRoot then 0 else 1),
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    20.MHz,
    name = "destination"
  )
  var input = Option.empty[Expr[Bool]]
  source:
    val value = in(Bool)
    input = Some(value)
  val pulse =
    if kind == "handshake" then Cdc.handshake(input.get, destination)
    else Cdc.pulse(Pulse(input.get), destination)
  destination:
    val outputValue = out(Bool)
    outputValue := pulse

object Increment57GrayPulseTests extends TestSuite:
  private def gray[A <: Data](dataType: DataType[A], stages: Int = 2, resetless: Boolean = false) =
    ConstructionKernel.inspect(new Increment57GraySource(dataType, stages, resetless = resetless))
  private def fixture(kind: String, mode: String = "ordinary", count: Int = 2) =
    ConstructionKernel.inspect(new GrayPulseCapture(kind, mode, count))
  private def reject(body: => Any, fragment: String): Unit =
    val result = scala.util.Try(body)
    assert(result.isFailure, result.failed.get.getMessage.contains(fragment))
  private def bridge(snapshot: ConstructionSnapshot) = ScalaToMlirBridge.fromSnapshot(snapshot).text

  val tests = Tests:
    test("Gray preserves exact scalar Enum and aggregate ABI with explicit obligations"):
      val values = Vector(
        gray(Bool),
        gray(Bits(4)),
        gray(UInt(4)),
        gray(SInt(4)),
        gray(Increment57GrayPayloads.noZeroEnum),
        gray(Vec(UInt(2), 2)),
        gray(Increment57GrayPayloads.record)
      )
      values.foreach: snapshot =>
        val expression = snapshot.digitalExpressions.find(_.crossing.nonEmpty).get
        val crossing = expression.crossing.get
        assert(crossing.kind == "gray", crossing.contract == "gray_v1")
        assert(crossing.obligations.toMap == Map(
          "transition" -> "one_bit_per_source_sample",
          "skew" -> "less_than_source_period",
          "stability" -> "stable_between_source_samples"
        ))
        assert(
          crossing.sourceDomain.endsWith(".source"),
          crossing.destinationDomain.endsWith(".destination")
        )
        val text = bridge(snapshot)
        assert(text.contains("contract = \"gray_v1\""), text.contains("obligations = {"))
        assert(
          text.contains("stable_between_source_samples"),
          text.contains("semantic_path = \"" + expression.path + "\"")
        )
        assert(
          ReproducibilityContract.canonicalSnapshot(snapshot).contains("one_bit_per_source_sample")
        )
      assert(values(4).enumDefinitions.head.cases.forall(_.code != 0))
      assert(values(5).digitalExpressions.find(_.crossing.nonEmpty).get.dataType ==
        "Vec(UInt(2);2)")
    test("Gray stages and checked bit-state expansion have exact finite boundaries"):
      Vector(2, 32).foreach(stages =>
        assert(bridge(gray(UInt(4), stages)).contains(s"stages = $stages : i64"))
      )
      Vector(Int.MinValue, 1, 33, Int.MaxValue).foreach(stages =>
        reject(gray(UInt(4), stages), "between 2 and 32")
      )
      assert(bridge(gray(Bits(87381))).contains("gray_v1"))
      reject(gray(Bits(87382)), "expansion budget")
    test("Gray excludes runtime Integer Real and capability leaves recursively"):
      reject(gray(Integer), "finite Vec/Struct bit patterns")
      reject(gray(Real), "finite Vec/Struct bit patterns")
      reject(gray(Clock), "finite Vec/Struct bit patterns")
      reject(gray(Vec(Real, 2)), "finite Vec/Struct bit patterns")
      reject(
        gray(Struct("InvalidGray", StructField("value", Real))),
        "finite Vec/Struct bit patterns"
      )
    test("resetless Gray and Enum without zero remain uninitialized contracts"):
      val snapshot = gray(Increment57GrayPayloads.noZeroEnum, resetless = true)
      assert(snapshot.digitalExpressions.forall(_.reset.isEmpty))
      assert(bridge(snapshot).contains("gray_v1"))
    test("pulse retains fixed depth exact Bool and coordinated physical reset facts"):
      val snapshot = ConstructionKernel.inspect(new Increment57PulseSource())
      val expression = snapshot.digitalExpressions.find(_.crossing.nonEmpty).get
      val crossing = expression.crossing.get
      assert(expression.dataType == "Bool", crossing.kind == "pulse", crossing.stages == 2)
      assert(crossing.obligations.toMap == Map(
        "spacing" -> "returned_acknowledgement",
        "width" -> "single_source_cycle",
        "reset_epoch" -> "common_assertion"
      ))
      assert(bridge(snapshot).contains("pulse_v1"))
      assert(
        ReproducibilityContract.canonicalSnapshot(snapshot).contains("returned_acknowledgement")
      )
    test("pulse rejects resetless endpoints and unequal actual reset roots"):
      reject(
        ConstructionKernel.inspect(new Increment57PulseSource(mode = "resetless")),
        "reset-bearing endpoints"
      )
      reject(
        ConstructionKernel.inspect(new Increment57PulseSource(mode = "different-reset")),
        "common physical reset roots"
      )
    test(
      "pulse reset epoch uses canonical shaped element identity rather than projection spelling"
    ):
      assert(bridge(ConstructionKernel.inspect(new ShapedPulseCapture(true))).contains("pulse_v1"))
      reject(
        ConstructionKernel.inspect(new ShapedPulseCapture(false)),
        "common physical reset roots"
      )
    test("both categories reuse actual provenance and do not bless uncrossed input"):
      for kind <- Vector("gray", "pulse") do
        reject(fixture(kind, "unknown"), "unknown or unresolved")
        reject(fixture(kind, "mixed"), "exactly one known")
        reject(fixture(kind, "constant"), "exactly one known")
        reject(fixture(kind, "same"), "distinct source")
        reject(fixture(kind, "uncrossed"), "NODAL-CDC-001")
        reject(fixture(kind, "forged"), "owned Cdc.sync entrypoint")
      reject(fixture("gray", "pulse-as-gray"), "Pulse-classified")
    test("Pulse results retain category through Boolean composition and explicit retransfers"):
      reject(fixture("pulse", "pulse-result-level"), "Pulse-classified")
      reject(fixture("pulse", "pulse-result-gray"), "Pulse-classified")
      assert(bridge(fixture("pulse", "pulse-result-pulse")).contains("pulse_v1"))
    test("independent instances retain identities and shared-root reconvergence rejects"):
      for kind <- Vector("gray", "pulse") do
        val snapshot = fixture(kind)
        val expressions = snapshot.digitalExpressions.filter(_.crossing.nonEmpty)
        assert(expressions.size == 2, expressions.map(_.path).distinct.size == 2)
        assert(expressions.map(_.operands).distinct.size == 1)
        reject(fixture(kind, "reconverge"), "NODAL-CDC-057-002")
        assert(bridge(fixture(kind, "separate")).contains("nodal.crossing"))
    test("bridge independently rejects malformed kind ABI stages and obligations"):
      for snapshot <-
          Vector(gray(UInt(4)), ConstructionKernel.inspect(new Increment57PulseSource()))
      do
        val expression = snapshot.digitalExpressions.find(_.crossing.nonEmpty).get
        def malformed(value: KernelDigitalExpressionSnapshot): Unit =
          val changed = snapshot.copy(digitalExpressions =
            snapshot.digitalExpressions.map(e => if e.path == value.path then value else e)
          )
          assert(scala.util.Try(bridge(changed)).isFailure)
        val reordered = snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(e =>
            if e.path == expression.path then
              e.copy(crossing = e.crossing.map(c => c.copy(obligations = c.obligations.reverse)))
            else e
          )
        )
        assert(bridge(reordered).contains("nodal.crossing"))
        malformed(expression.copy(crossing = None))
        malformed(expression.copy(dataType = "UInt(7)"))
        malformed(expression.copy(operands = Vector.empty))
        malformed(expression.copy(crossing =
          expression.crossing.map(_.copy(contract = "level_v1"))
        ))
        malformed(expression.copy(crossing = expression.crossing.map(_.copy(kind = "unknown"))))
        malformed(expression.copy(crossing = expression.crossing.map(_.copy(stages = 33))))
        malformed(expression.copy(crossing =
          expression.crossing.map(_.copy(obligations = Vector.empty))
        ))
        malformed(expression.copy(crossing =
          expression.crossing.map(c =>
            c.copy(obligations = c.obligations :+ ("extra" -> "ignored"))
          )
        ))
    test("bridge rejects forged pulse epoch evidence against actual reset producers"):
      val snapshot = ConstructionKernel.inspect(new Increment57PulseSource())
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
    test("representative Gray and pulse record growth stays linear and deterministic"):
      for kind <- Vector("gray", "pulse") do
        val first = fixture(kind, count = 128)
        assert(first.digitalExpressions.count(_.crossing.nonEmpty) == 128)
        assert(first == fixture(kind, count = 128))
