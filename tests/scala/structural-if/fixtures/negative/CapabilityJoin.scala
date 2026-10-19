import nodal.*
final class CapabilityJoin extends Module:
  val MODE = param(1.integer, 0 to 3)
  val first = in(Bits(8))
  val second = in(Bits(8))
  val inferred = if MODE == 0 then first else second
  val illegalSelection = (if MODE == 0 then first else second).writable
