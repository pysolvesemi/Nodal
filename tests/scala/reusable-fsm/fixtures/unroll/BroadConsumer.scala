package unrollconsumer
import nodal.*
import unrollproducer.*
object BroadConsumer:
  def graph: FsmDef[Mode] = FsmDef.unroll(1, Graphs.base)(BroadPure.step)
