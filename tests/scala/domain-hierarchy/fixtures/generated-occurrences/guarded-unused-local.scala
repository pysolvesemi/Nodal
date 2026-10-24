package nodal.guarded.summary
import nodal.*
final class GuardedUnusedLocal extends Module:
  val flag = param(1.integer, range = 0 to 1)
  val output = out(UInt(8))
  if flag == 1 then
    val unused = 7
    output := 0.U(8)
  else output := 1.U(8)
