import nodal.*
// Expected: NODAL-SEQUENTIAL-056-EFFECT
final class StructuralStatement extends Module:
  val MODE = param(0.integer)
  val flag = in(Bool)
  when(flag) { if MODE == 0 then () }
