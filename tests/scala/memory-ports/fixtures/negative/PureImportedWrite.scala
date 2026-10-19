// Expected: NODAL-COLLECTION-CALLBACK-SUMMARY
package memoryports.negative.imported
import nodal.*
import _root_.memoryports.separate.Producer
object Witness:
  def build(memory: Mem[UInt], address: Expr[UInt], values: Expr[Vec[UInt]]): Expr[Vec[UInt]] =
    values.map { item => Producer.store(memory, address, item); item }
