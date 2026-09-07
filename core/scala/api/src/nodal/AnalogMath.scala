package nodal

/** Pure real-valued analog mathematics. Units are checked at construction and again by the
  * compiler. Parameters remain symbolic; their defaults are not constant-evaluation inputs.
  */
object AnalogMath:
  def abs(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("abs", Vector(value))
  def min(left: Expr[Real], right: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("min", Vector(left, right))
  def max(left: Expr[Real], right: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("max", Vector(left, right))
  def sqrt(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("sqrt", Vector(value))
  def hypot(left: Expr[Real], right: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("hypot", Vector(left, right))
  def atan2(left: Expr[Real], right: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("atan2", Vector(left, right))
  def pow(left: Expr[Real], right: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("pow", Vector(left, right))
  def exp(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("exp", Vector(value))
  def ln(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("ln", Vector(value))
  def log10(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("log10", Vector(value))
  def sin(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("sin", Vector(value))
  def cos(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("cos", Vector(value))
  def tan(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("tan", Vector(value))
  def asin(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("asin", Vector(value))
  def acos(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("acos", Vector(value))
  def atan(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("atan", Vector(value))
  def sinh(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("sinh", Vector(value))
  def cosh(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("cosh", Vector(value))
  def tanh(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("tanh", Vector(value))
  def asinh(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("asinh", Vector(value))
  def acosh(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("acosh", Vector(value))
  def atanh(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("atanh", Vector(value))
  def floor(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("floor", Vector(value))
  def ceil(value: Expr[Real]): Expr[Real] =
    AnalogFunctionContract.call("ceil", Vector(value))
