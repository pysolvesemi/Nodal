package memoryports.positive
import nodal.*
object Positive:
  def checkedWrite(memory: Mem[UInt], address: Expr[UInt], data: Expr[UInt], flag: Expr[Bool], existing: Expr[UInt]): Unit =
    when(flag):
      memory.write(address, data, 15.U(4))
      memory.write(address, existing, 0.U(4))
