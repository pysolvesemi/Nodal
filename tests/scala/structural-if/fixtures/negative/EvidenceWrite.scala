import nodal.*
final class EvidenceWrite extends Module:
  val MODE = param(1.integer, 0 to 2)
  val input = in(Bits(8))
  val output = out(Bits(8))
  val selected =
    if MODE.==(0)(using { output := input; summon[Integer <:< Integer] }) then ~input
    else input
