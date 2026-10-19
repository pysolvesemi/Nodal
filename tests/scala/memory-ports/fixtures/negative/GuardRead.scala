// Expected: NODAL-SEQUENTIAL-056-MEMORY-READ
package nodal.memoryports.negative.guardread
import nodal.*
object Witness:
  def build(memory: Mem[UInt], address: Expr[UInt], data: Expr[UInt], flag: Expr[Bool], values: Expr[Vec[UInt]], mode: Param[Integer]): Unit =
    val _ = (memory, address, data, flag, values, mode)
    when(memory.read(address) === data) { () }
