package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-UNAVAILABLE
object UnavailableSeparateHelper:
  def test(xs: Expr[Vec[UInt]]) = xs.map(SeparateHelper.next)
