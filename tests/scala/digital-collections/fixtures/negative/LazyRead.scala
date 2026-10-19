package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-MUTABLE
object LazyRead:
  lazy val saved = 1.U(8)
  def test(xs: Expr[Vec[UInt]]) = xs.map(x => x + saved)
