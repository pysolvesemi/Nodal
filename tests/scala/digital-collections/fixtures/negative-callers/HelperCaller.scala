package nodal.callback.fixture

import nodal.*

/** The diagnostic must preserve the defining helper's file and source position. */
object HelperCaller:
  def test(input: Expr[Vec[UInt]]): Expr[Vec[UInt]] = input.map(EffectOwner.mutate)
