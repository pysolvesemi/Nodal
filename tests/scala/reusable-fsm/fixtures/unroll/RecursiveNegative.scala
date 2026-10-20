package unrollnegative
import nodal.*
import unrollproducer.*
object RecursiveNegative:
  def graph: FsmDef[Mode] = FsmDef.unroll(1, Graphs.base)((_, previous) => {
    def recur(): FsmDef[Mode] = recur(); recur()
  })
