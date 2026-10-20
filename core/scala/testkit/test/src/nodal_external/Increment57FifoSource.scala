package nodal_external

import nodal.*

object Increment57FifoPayloads:
  val record: DataType[Struct] = Struct(
    "FifoPayload",
    StructField("flag", Bool),
    StructField("value", UInt(7))
  )

/** Public elastic FIFO witness with explicit controls. Accepted values are delivered in order. */
final class Increment57FifoSource[A <: Data](
    payloadType: DataType[A],
    depth: Int = 4,
    edge: ClockEdge = ClockEdge.Rising,
    polarity: ResetPolarity = ResetPolarity.ActiveHigh,
    mode: String = "ordinary"
) extends Module:
  val srcClock = in(Clock)
  val dstClock = in(Clock)
  val reset = in(Reset)
  val differentReset = if mode == "different-reset" then Some(in(Reset)) else None
  val midClock = if mode == "chain" then Some(in(Clock)) else None
  val policy = mode match
    case "resetless" => ResetPolicy.None
    case "unsafe" => ResetPolicy.Async
    case "sync" | "sync-bad-frequency" => ResetPolicy.Sync
    case _ => ResetPolicy.AsyncAssertSyncRelease(2)
  val source = ClockDomain.from(
    srcClock,
    reset,
    edge,
    policy,
    polarity,
    100.MHz,
    name = "source"
  )
  val destination = ClockDomain.from(
    if mode == "sync" || mode == "sync-bad-frequency" then srcClock else dstClock,
    differentReset.getOrElse(reset),
    edge,
    policy,
    polarity,
    if mode == "sync" then 100.MHz else 20.MHz,
    name = "destination"
  )
  val middle = midClock.map(clock =>
    ClockDomain.from(clock, reset, edge, policy, polarity, 50.MHz, name = "middle")
  )
  var input = Option.empty[Stream[A]]
  var upstream = Option.empty[Signal[Bool]]
  source:
    val inputPayload = in(payloadType)
    val inputValid = in(Bool)
    val inputReady = out(Bool)
    input = Some(
      if mode == "continuous" then Stream(inputPayload) else Stream(inputPayload, inputValid)
    )
    upstream = Some(inputReady)
  val first = Cdc.fifo(input.get, middle.getOrElse(destination), depth)
  val crossed = if mode == "chain" then Cdc.fifo(first, destination, depth) else first
  upstream.get := input.get.ready
  destination:
    val outputPayload = out(payloadType)
    val outputValid = out(Bool)
    val outputReady = in(Bool)
    outputPayload := crossed.payload
    outputValid := crossed.valid
    if mode != "unattached" then crossed.accept(outputReady)
