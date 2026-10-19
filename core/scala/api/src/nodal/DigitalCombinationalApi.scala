package nodal

/** Pure combinational selection. Both branches retain the same complete digital type. */
def select[A <: Data](condition: Expr[Bool], whenTrue: Expr[A], whenFalse: Expr[A]): Expr[A] =
  DigitalCombinationalContract.select(condition, whenTrue, whenFalse)

/** Companion-owned operators keep ordinary Scala overload lookup separate from analog operators. */
private[nodal] trait DigitalBitsOperators[A <: Data]:
  extension (left: Expr[A])
    def &(right: Expr[A]): Expr[A] =
      DigitalCombinationalContract.bitwise("digital_and", left, right)
    def |(right: Expr[A]): Expr[A] = DigitalCombinationalContract.bitwise("digital_or", left, right)
    def ^(right: Expr[A]): Expr[A] =
      DigitalCombinationalContract.bitwise("digital_xor", left, right)
    def unary_~ : Expr[A] = DigitalCombinationalContract.complement(left)
    infix def ===(right: Expr[A]): Expr[Bool] =
      DigitalCombinationalContract.compare("digital_eq", left, right)
    infix def =/=(right: Expr[A]): Expr[Bool] =
      DigitalCombinationalContract.compare("digital_ne", left, right)
    def concat(right: Expr[?]): Expr[Bits] = DigitalCombinationalContract.concatenate(left, right)
    def extract(lower: Int, upperExclusive: Int): Expr[Bits] =
      DigitalCombinationalContract.extract(left, lower, upperExclusive)
    def <<(amount: Int): Expr[A] = DigitalCombinationalContract.shift("digital_shl", left, amount)
    def logicalShiftRight(amount: Int): Expr[A] =
      DigitalCombinationalContract.shift("digital_logical_shr", left, amount)

private[nodal] trait DigitalNumericOperators[A <: Data] extends DigitalBitsOperators[A]:
  extension (left: Expr[A])
    def *(right: Expr[A]): Expr[A] =
      DigitalCombinationalContract.arithmetic("digital_mul", left, right)
    def /(right: Expr[A]): Expr[A] =
      DigitalCombinationalContract.arithmetic("digital_div", left, right)
    def %(right: Expr[A]): Expr[A] =
      DigitalCombinationalContract.arithmetic("digital_rem", left, right)
    def min(right: Expr[A]): Expr[A] =
      DigitalCombinationalContract.arithmetic("digital_min", left, right)
    def max(right: Expr[A]): Expr[A] =
      DigitalCombinationalContract.arithmetic("digital_max", left, right)
    def unary_- : Expr[SInt] = DigitalCombinationalContract.negate(left)
    def <(right: Expr[A]): Expr[Bool] =
      DigitalCombinationalContract.compare("digital_lt", left, right)
    def <=(right: Expr[A]): Expr[Bool] =
      DigitalCombinationalContract.compare("digital_le", left, right)
    def >(right: Expr[A]): Expr[Bool] =
      DigitalCombinationalContract.compare("digital_gt", left, right)
    def >=(right: Expr[A]): Expr[Bool] =
      DigitalCombinationalContract.compare("digital_ge", left, right)

private[nodal] trait DigitalUIntOperators extends DigitalNumericOperators[UInt]:
  extension (left: Expr[UInt])
    def -(right: Expr[UInt]): Expr[SInt] =
      DigitalCombinationalContract.arithmetic("digital_sub", left, right)
