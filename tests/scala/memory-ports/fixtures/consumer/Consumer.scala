package memoryports.separate
import nodal.*
object Consumer:
  def call(memory: Mem[UInt], address: Expr[UInt], data: Expr[UInt], flag: Expr[Bool]): Unit =
    when(flag) { Producer.store(memory, address, data) }
