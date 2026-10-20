package nodal_external

import nodal.*

/** Public coherent latest-snapshot transfer. Intermediate public changes may coalesce. */
final class Increment57HandshakeSource[A <: Data](
    payloadType: DataType[A],
    edge: ClockEdge = ClockEdge.Rising,
    polarity: ResetPolarity = ResetPolarity.ActiveHigh,
    mode: String = "ordinary"
) extends Module:
  val srcClock = in(Clock)
  val dstClock = in(Clock)
  val reset = in(Reset)
  val differentReset = if mode == "different-reset" then Some(in(Reset)) else None
  val policy = mode match
    case "resetless" => ResetPolicy.None
    case "unsafe" => ResetPolicy.Async
    case "sync" => ResetPolicy.Sync
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
    if mode == "sync" then srcClock else dstClock,
    differentReset.getOrElse(reset),
    edge,
    policy,
    polarity,
    20.MHz,
    name = "destination"
  )
  var sampled = Option.empty[Expr[A]]
  source:
    val inputValue = in(payloadType)
    sampled = Some(inputValue)
  val crossed = Cdc.handshake(sampled.get, destination)
  destination:
    val outputValue = out(payloadType)
    outputValue := crossed
