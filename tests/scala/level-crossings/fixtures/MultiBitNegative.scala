package levelnegative
import nodal.*
final class MultiBitNegative extends Module:
  val destination = ClockDomain.required("destination")
  val input = in(Bool)
  val output = out(Bool)
  destination { val crossed = Cdc.sync(in(UInt(2)), destination); val _ = crossed }
