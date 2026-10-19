package memoryports.separate
import nodal.*
object Producer:
  def store(memory: Mem[UInt], address: Expr[UInt], data: Expr[UInt]): Unit =
    memory.write(address, data, 1.U(1))
