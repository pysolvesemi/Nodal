import nodal.*
final class MarkerNoPlugin extends Module:
  val MODE = param(1.integer, 0 to 2)
  val input = in(Bits(8))
  val selected = if MODE == 0 then ~input else input
