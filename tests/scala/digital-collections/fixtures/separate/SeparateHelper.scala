package nodal.callback.fixture

import nodal.*

/** Even a pure-looking public signature cannot attest an unavailable method body. */
object SeparateHelper:
  def next(value: Expr[UInt]): Expr[UInt] = value
