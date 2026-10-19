package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-MUTABLE
object MutableHelper:
  var count = 0
  private def effect(x: Expr[UInt]): Expr[UInt] =
    count += 1
    x
  def test(xs: Expr[Vec[UInt]]) = xs.map(effect)
