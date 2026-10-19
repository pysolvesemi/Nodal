package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-MUTABLE
object MutableWrite:
  var count = 0
  def test(xs: Expr[Vec[UInt]]) = xs.map: x =>
    count += 1
    x
