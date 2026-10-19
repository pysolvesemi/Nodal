package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-DISPATCH
object UnclosedDispatch:
  def test(xs: Expr[Vec[UInt]], helper: OverrideableHelper) = xs.map(helper.next)

class OverrideableHelper:
  def next(x: Expr[UInt]): Expr[UInt] = x
