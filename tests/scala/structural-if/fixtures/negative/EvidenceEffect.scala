import nodal.*
final class EvidenceEffect extends Module:
  val MODE = param(1.integer, 0 to 2)
  val input = in(Bits(8))
  final def evidence: Integer <:< Integer =
    println("hidden marker evidence effect")
    summon[Integer <:< Integer]
  val selected = if MODE.==(0)(using evidence) then ~input else input
