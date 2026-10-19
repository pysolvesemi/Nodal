// Expected: NODAL-SEQUENTIAL-056-FORGED
package nodal.memoryports.negative.forgedread
import nodal.*
object Witness:
  def build(memory: Mem[UInt], address: Expr[UInt], data: Expr[UInt], flag: Expr[Bool], values: Expr[Vec[UInt]], mode: Param[Integer]): Unit =
    val _ = (memory, address, data, flag, values, mode)
    val _ = ConstructionKernel.memoryRead(memory, address)
