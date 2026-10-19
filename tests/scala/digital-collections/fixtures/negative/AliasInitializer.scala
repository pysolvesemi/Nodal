package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-UNAVAILABLE
object AliasInitializer:
  println("bad initializer")
  val callback: Expr[UInt] => Expr[UInt] = value => value

object AliasInitializerCaller:
  def test(value: Expr[Vec[UInt]]): Expr[Vec[UInt]] = value.map(AliasInitializer.callback)
