// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
package nodal.memoryports.negative.loopwrite
import nodal.*
object Witness:
  def build(memory: Mem[UInt], address: Expr[UInt], data: Expr[UInt], flag: Expr[Bool], values: Expr[Vec[UInt]], mode: Param[Integer]): Unit =
    val _ = (memory, address, data, flag, values, mode)
    loop(LoopBound.Static(2)) { _ => memory.write(address, data, 1.U(1)) }
