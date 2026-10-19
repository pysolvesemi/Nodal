import nodal.*
// Expected: NODAL-SEQUENTIAL-056-EFFECT
final class Loop extends Module:
  val MODE = param(0.integer)
  val enable = in(Bool)
  val other = in(Bool)
  val input = in(UInt(8))
  val state = Reg(0.U(8))
  when(enable) { loop(LoopBound.Static(1)) { _ => () } }
