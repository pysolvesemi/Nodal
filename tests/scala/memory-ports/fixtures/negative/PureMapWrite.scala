// Expected: NODAL-COLLECTION-CALLBACK-UNAVAILABLE
package nodal.memoryports.negative.puremapwrite
import nodal.*
object Witness:
  def build(memory: Mem[UInt], address: Expr[UInt], data: Expr[UInt], flag: Expr[Bool], values: Expr[Vec[UInt]], mode: Param[Integer]): Unit =
    val _ = (memory, address, data, flag, values, mode)
    val _ = values.map { item => memory.write(address, data, 1.U(1)); item }
