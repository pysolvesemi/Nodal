package nodal.callback.fixture
import nodal.*
object Positive:
  private def bump(x: Expr[UInt], y: Expr[UInt]): Expr[UInt] = x + y
  def example(input: Expr[Vec[UInt]], mask: Expr[UInt]): Expr[Vec[UInt]] =
    input.map: x =>
      val next = bump(x, mask)
      select(x < mask, next, next).truncate(8)
  def binary(input: Expr[Vec[UInt]]): Expr[UInt] = input.reduce((a, b) => a + b)
  def acrossUnits(input: Expr[Vec[UInt]]): Expr[Vec[UInt]] =
    input.map(ArithmeticHelpers.renamedArithmetic)
