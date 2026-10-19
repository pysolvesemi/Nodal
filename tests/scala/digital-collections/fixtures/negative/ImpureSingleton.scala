package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-UNAVAILABLE
object ImpureSingleton:
  def test(xs: Expr[Vec[UInt]]) = xs.map(InitializingHelper.next)

object InitializingHelper:
  println("effect during singleton initialization")
  def next(x: Expr[UInt]): Expr[UInt] = x
