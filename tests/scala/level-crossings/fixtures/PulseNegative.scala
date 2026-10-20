package levelnegative
import nodal.*
final class PulseNegative extends Module:
  val destination = ClockDomain.required("destination")
  val input = in(Bool)
  val output = out(Bool)
  destination { val crossed = Cdc.sync(Pulse(input), destination); val _ = crossed }
