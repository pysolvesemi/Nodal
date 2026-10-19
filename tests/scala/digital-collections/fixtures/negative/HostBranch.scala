package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-TOPOLOGY
object HostBranch:
  def test(xs: Expr[Vec[UInt]], flag: Boolean) = xs.map(x => if flag then x else x)
