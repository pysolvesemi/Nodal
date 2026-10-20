package nodal_external

import nodal.*

/** Public constructor-captured bit-pattern transport, including enum and aggregate ABI witnesses.
  */
final class Increment57GraySource[A <: Data](
    payloadType: DataType[A],
    stages: Int = 2,
    edge: ClockEdge = ClockEdge.Rising,
    polarity: ResetPolarity = ResetPolarity.ActiveHigh,
    resetless: Boolean = false
) extends Module:
  val srcClock = in(Clock)
  val dstClock = in(Clock)
  val reset = in(Reset)
  val source = ClockDomain.from(
    srcClock,
    reset,
    edge,
    if resetless then ResetPolicy.None else ResetPolicy.AsyncAssertSyncRelease(2),
    polarity,
    100.MHz,
    name = "source"
  )
  val destination = ClockDomain.from(
    dstClock,
    reset,
    edge,
    if resetless then ResetPolicy.None else ResetPolicy.AsyncAssertSyncRelease(2),
    polarity,
    20.MHz,
    name = "destination"
  )
  var sampled = Option.empty[Expr[A]]
  source:
    val inputValue = in(payloadType)
    sampled = Some(inputValue)
  val crossed = Cdc.gray(Gray(sampled.get), destination, stages)
  destination:
    val outputValue = out(payloadType)
    outputValue := crossed

/** A source pulse is an event only under the retained single-cycle/acknowledgement/epoch contract.
  */
final class Increment57PulseSource(
    edge: ClockEdge = ClockEdge.Rising,
    polarity: ResetPolarity = ResetPolarity.ActiveHigh,
    mode: String = "ordinary"
) extends Module:
  val srcClock = in(Clock)
  val dstClock = in(Clock)
  val reset = in(Reset)
  val differentReset = if mode == "different-reset" then Some(in(Reset)) else None
  val source = ClockDomain.from(
    srcClock,
    reset,
    edge,
    if mode == "resetless" then ResetPolicy.None else ResetPolicy.AsyncAssertSyncRelease(2),
    polarity,
    100.MHz,
    name = "source"
  )
  val destination = ClockDomain.from(
    dstClock,
    differentReset.getOrElse(reset),
    edge,
    if mode == "resetless" then ResetPolicy.None else ResetPolicy.AsyncAssertSyncRelease(2),
    polarity,
    20.MHz,
    name = "destination"
  )
  var sampled = Option.empty[Expr[Bool]]
  source:
    val inputValue = in(Bool)
    sampled = Some(inputValue)
  val crossed = Cdc.pulse(Pulse(sampled.get), destination)
  destination:
    val outputValue = out(Bool)
    outputValue := crossed

enum Increment57GrayCode derives HwEnum:
  case First, Second, Third

object Increment57GrayPayloads:
  def noZeroEnum: DataType[EnumValue[Increment57GrayCode]] =
    EnumType.custom(
      3,
      enumEncoding(
        Increment57GrayCode.First -> BigInt(1),
        Increment57GrayCode.Second -> BigInt(3),
        Increment57GrayCode.Third -> BigInt(2)
      )
    )
  def record: DataType[Struct] = Struct(
    "GrayPacket",
    StructField("flag", Bool),
    StructField("payload", UInt(3))
  )
