package unrollconsumer
import nodal.*
import unrollproducer.*
object Consumer:
  def main(args: Array[String]): Unit =
    val _ = args
    val base = Graphs.base
    val result = FsmDef.unroll(2, base)(Graphs.step)
    assert(result ne base)
    println("FINITE_UNROLL_IMPORTED_PASS")
