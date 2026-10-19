package levelnegative
import nodal.*
final class BehavioralNegative extends Module:
  val destination = ClockDomain.required("destination")
  val input = in(Bool)
  val output = out(Bool)
  destination { when(true.B) { val crossed = Cdc.sync(input, destination); output := crossed } }
