package unrollnegative
import nodal.*
import unrollproducer.*
object NestedNegative:
  def graph: FsmDef[Mode] = FsmDef.unroll(1, Graphs.base)((_, previous) => {
    FsmDef.unroll(1, previous)((_, prior) => prior)
  })
