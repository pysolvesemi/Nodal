package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-UNAVAILABLE
object UnknownCallback:
  def test(xs: Expr[Vec[UInt]], callback: Expr[UInt] => Expr[UInt]) = xs.map(callback)
