import nodal.*
final class MixedHost extends Module:
  val MODE = param(1.integer, 0 to 3)
  val host = true
  val input = in(Bits(8))
  val result = if host && MODE == 0 then ~input else input
