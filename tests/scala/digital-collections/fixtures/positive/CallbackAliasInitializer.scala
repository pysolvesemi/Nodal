package nodal.callback.fixture

import nodal.*

object SafeCallbackAliasInitializer:
  val callback: Expr[UInt] => Expr[UInt] = value => value

object CallbackAliasInitializer:
  def test(value: Expr[Vec[UInt]]): Expr[Vec[UInt]] =
    value.map(SafeCallbackAliasInitializer.callback)
