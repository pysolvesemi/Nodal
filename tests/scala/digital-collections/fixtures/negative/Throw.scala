package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-UNAVAILABLE
object Throw:
  def test(xs: Expr[Vec[UInt]]) = xs.map: x =>
    throw new IllegalStateException("wrong")
    x
