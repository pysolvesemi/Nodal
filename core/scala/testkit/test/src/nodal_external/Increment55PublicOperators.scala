package nodal_external

import nodal.*

/** Compiled against the separate API module, without package-private implementation access. */
object Increment55PublicOperators:
  def unsigned(left: Expr[UInt], right: Expr[UInt]): Vector[Expr[?]] =
    val difference: Expr[SInt] = left - right
    val product: Expr[UInt] = left * right
    val negative: Expr[SInt] = -left
    Vector(
      left + right,
      difference,
      product,
      left / 2.U(2),
      left % 2.U(2),
      left.min(right),
      left.max(right),
      negative,
      left < right,
      left <= right,
      left > right,
      left >= right,
      left === right,
      left =/= right,
      left & right,
      left | right,
      left ^ right,
      ~left,
      left << 1,
      left >> 1,
      left.logicalShiftRight(1),
      left.concat(right),
      left.extract(0, 1),
      select(left < right, left, right)
    )

  def signed(left: Expr[SInt], right: Expr[SInt]): Vector[Expr[?]] =
    val difference: Expr[SInt] = left - right
    val product: Expr[SInt] = left * right
    val negative: Expr[SInt] = -left
    Vector(
      left + right,
      difference,
      product,
      left / (-1).S(2),
      left % (-1).S(2),
      left.min(right),
      left.max(right),
      negative,
      left < right,
      left <= right,
      left > right,
      left >= right,
      left === right,
      left =/= right,
      left & right,
      left | right,
      left ^ right,
      ~left,
      left << 1,
      left >> 1,
      left.logicalShiftRight(1),
      left.concat(right),
      left.extract(0, 1),
      select(left < right, left, right)
    )

  def bits(left: Expr[Bits], right: Expr[Bits]): Vector[Expr[?]] =
    val conjunction: Expr[Bits] = left & right
    Vector(
      conjunction,
      left | right,
      left ^ right,
      ~left,
      left === right,
      left =/= right,
      left << 1,
      left >> 1,
      left.logicalShiftRight(1),
      left.concat(right),
      left.extract(0, 1)
    )

  def analog(left: Expr[Real], right: Expr[Real]): Expr[Real] = -(left * right) + left
