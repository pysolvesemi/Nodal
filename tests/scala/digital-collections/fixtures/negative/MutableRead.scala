package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-MUTABLE
object MutableRead:
  var count = 8
  def test(xs: Expr[Vec[UInt]]) = xs.map(x => x.truncate(count))
