import nodal.*
final class ValueJoin extends Module:
  val MODE = param(1.integer, 0 to 3)
  val first = in(Bits(8))
  val second = in(Bits(8))
  val mixed = if MODE == 0 then ~first else second
  val explicit: Expr[Bits] = if MODE == 0 then first else second
  val armTyped = if MODE == 0 then (first: Expr[Bits]) else (second: Expr[Bits])
