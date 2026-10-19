// Expected: NODAL-STRUCTURAL-055-UNAVAILABLE
package nodal.memoryports.negative.structuralwrite
import nodal.*
final class Witness(memory: Mem[UInt], address: Expr[UInt], data: Expr[UInt]) extends Module:
  val MODE = param(0.integer, 0 to 2)
  val result: Expr[UInt] = if MODE == 0 then
    memory.write(address, data, 1.U(1))
    data
  else data
