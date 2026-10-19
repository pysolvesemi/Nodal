package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-UNAVAILABLE
object StatefulHardware:
  def test(xs: Expr[Vec[UInt]]) = xs.map(x => Reg(x))
