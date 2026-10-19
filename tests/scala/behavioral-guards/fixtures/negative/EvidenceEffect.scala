import nodal.*
// Expected: NODAL-SEQUENTIAL-056-UNAVAILABLE
final class EvidenceEffect extends Module:
  val MODE = param(0.integer)
  val enable = in(Bool)
  final def evidence: Integer <:< Integer = {
    println("must never execute evidence")
    summon[Integer <:< Integer]
  }
  when(enable && MODE.==(0)(using evidence)) { () }
