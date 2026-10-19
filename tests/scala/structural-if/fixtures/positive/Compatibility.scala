import nodal.*
object Compatibility:
  def realComparison(a: Expr[Real], b: Expr[Real]): Expr[Bool] =
    (a < b) && (a <= b) && (a > b) && (a >= b)
  def uintComparison(a: Expr[UInt], b: Expr[UInt]): Expr[Bool] =
    (a < b) &&
      (a <= b) &&
      (a > b) &&
      (a >= b) &&
      (a === b) &&
      (a =/= b)
  def sintComparison(a: Expr[SInt], b: Expr[SInt]): Expr[Bool] =
    (a < b) &&
      (a <= b) &&
      (a > b) &&
      (a >= b) &&
      (a === b) &&
      (a =/= b)
  def boolIdentity(a: Expr[Bool], b: Expr[Bool]): Boolean = a == b || a != b
  def realIdentity(a: Expr[Real], b: Expr[Real]): Boolean = a == b || a != b
  def integerIdentity(a: Expr[Integer], b: Expr[Integer]): Boolean = a == b || a != b
  def paramIdentity(a: Param[Integer], b: Expr[Integer]): Boolean = a == b || a != b
  def hostOnly(a: Int, b: Int, flag: Boolean): Int =
    if a == b && flag then 1 else if a < b then 2 else 3
