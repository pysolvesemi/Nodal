package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-TOPOLOGY
object HostLoop:
  def test(xs: Expr[Vec[UInt]]) = xs.map: x =>
    while false do println("wrong")
    x
