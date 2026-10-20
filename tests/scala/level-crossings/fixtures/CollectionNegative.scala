package levelnegative
import nodal.*
final class CollectionNegative extends Module:
  val destination = ClockDomain.required("destination")
  val input = in(Bool)
  val output = out(Bool)
  destination {
    val crossed = map(in(Vec(Bool, 2))) { _ => Cdc.sync(input, destination) }; val _ = crossed
  }
