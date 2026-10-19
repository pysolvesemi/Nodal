package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-UNAVAILABLE
object UnknownIO:
  def test(xs: Expr[Vec[UInt]]) = xs.map: x =>
    println("wrong")
    x
