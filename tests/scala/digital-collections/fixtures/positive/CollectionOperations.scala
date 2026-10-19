package nodal.callback.fixture

import nodal.*

object CollectionOperations:
  def zip(left: Expr[Vec[UInt]], right: Expr[Vec[UInt]]): Expr[Vec[UInt]] =
    left.zipWith(right)((a, b) => (a + b).truncate(8))

  def fold(input: Expr[Vec[UInt]], initial: Expr[UInt]): Expr[UInt] =
    input.fold(initial)((acc, element) => (acc + element).truncate(8))

  def scan(input: Expr[Vec[UInt]], initial: Expr[UInt]): Expr[Vec[UInt]] =
    input.scan(initial)((acc, element) => (acc + element).truncate(8))
