package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-MUTABLE
object EffectOwner:
  var count = 0
  def mutate(value: Expr[UInt]): Expr[UInt] =
    count += 1
    value
