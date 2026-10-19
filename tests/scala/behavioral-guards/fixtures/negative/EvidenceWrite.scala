import nodal.*
// Expected: NODAL-SEQUENTIAL-056-UNAVAILABLE
final class EvidenceWrite extends Module:
  val MODE = param(0.integer)
  val enable = in(Bool)
  val input = in(UInt(8))
  val state = Reg(0.U(8))
  when(enable && MODE.==(0)(using { state := input; summon[Integer <:< Integer] })) {
    state := input
  }
