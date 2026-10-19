package nodal.callback.fixture

import nodal.*

/** The use site is a different source file in the same compiler run. */
object ArithmeticHelpers:
  def renamedArithmetic(value: Expr[UInt]): Expr[UInt] = (value + 1.U(8)).truncate(8)
