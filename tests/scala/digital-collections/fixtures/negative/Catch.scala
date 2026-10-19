package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-EFFECT
object Catch:
  def test(xs: Expr[Vec[UInt]]) = xs.map: x =>
    try x
    catch case _: Exception => x
