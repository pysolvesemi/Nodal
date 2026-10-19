package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-CYCLE
object RecursiveHelper:
  private def again(x: Expr[UInt]): Expr[UInt] = again(x)
  def test(xs: Expr[Vec[UInt]]) = xs.map(again)
