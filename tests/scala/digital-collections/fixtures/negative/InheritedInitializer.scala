package nodal.callback.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-EFFECT
object InheritedInitializer:
  def test(input: Expr[Vec[UInt]]) = input.map(InheritedHelper.next)

class InitializerParent:
  println("initialization hidden in an inherited constructor")

object InheritedHelper extends InitializerParent:
  def next(value: Expr[UInt]): Expr[UInt] = value
