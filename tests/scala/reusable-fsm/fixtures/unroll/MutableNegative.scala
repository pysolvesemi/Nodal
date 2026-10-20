package unrollnegative
import nodal.*
import unrollproducer.*
object MutableNegative:
  def graph: FsmDef[Mode] = FsmDef.unroll(1, Graphs.base)((_, previous) => {
    var count = 0; count += 1; previous
  })
