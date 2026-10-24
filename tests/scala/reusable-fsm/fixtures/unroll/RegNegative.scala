package unrollnegative
import nodal.*
import unrollproducer.*
object RegNegative:
  def graph: FsmDef[Mode] = FsmDef.unroll(1, Graphs.base)((_, previous) => {
    val _ = Reg(true.B); previous
  })
