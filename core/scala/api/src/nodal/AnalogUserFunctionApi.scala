package nodal

/** A module-local, nonrecursive native analog function. This is not an elaboration-time Scala
  * helper: one typed declaration is retained and calls are emitted without body expansion.
  */
final class AnalogFunction[A <: Data] private[nodal] (
    private[nodal] val registry: AnalogUserFunctionRuntime.Registry,
    private[nodal] val module: Long,
    private[nodal] val definition: AnalogUserFunctionRuntime.Definition,
    val resultType: DataType[A]
):
  def apply(arguments: Expr[?]*): Expr[A] =
    AnalogUserFunctionRuntime.call(this, arguments.toVector)

object AnalogFunction:
  /** Arguments and the result are scalar Real or Integer. Dimensions describe physical types; all
    * dynamic inputs, including module parameters, must be passed explicitly at a call. The final
    * expression is the total return; locals are initialized, immutable values.
    */
  def apply[A <: Data](
      name: String,
      resultType: DataType[A],
      dimension: PhysicalDimension = PhysicalDimension.Dimensionless
  )(body: AnalogFunctionBody => Expr[A]): AnalogFunction[A] =
    ConstructionKernel.defineUserFunction(name, resultType, dimension, body)

/** Lexical body builder. Ordinary Real arithmetic, comparisons and AnalogMath calls work here.
  * Numeric helpers also support Integer without introducing implicit numeric conversions.
  */
final class AnalogFunctionBody private[nodal] (
    private val recorder: AnalogUserFunctionRuntime.Body
):
  def input[A <: Data](
      name: String,
      dataType: DataType[A],
      dimension: PhysicalDimension = PhysicalDimension.Dimensionless
  ): Expr[A] = recorder.input(name, dataType, dimension)

  def local[A <: Data](name: String, value: Expr[A]): Expr[A] = recorder.local(name, value)

  def select[A <: Data](condition: Expr[Bool], whenTrue: Expr[A], whenFalse: Expr[A]): Expr[A] =
    recorder.expression("select", Vector(condition, whenTrue, whenFalse))

  def add[A <: Data](left: Expr[A], right: Expr[A]): Expr[A] =
    recorder.expression("add", Vector(left, right))

  def subtract[A <: Data](left: Expr[A], right: Expr[A]): Expr[A] =
    recorder.expression("sub", Vector(left, right))

  def multiply[A <: Data](left: Expr[A], right: Expr[A]): Expr[A] =
    recorder.expression("mul", Vector(left, right))

  def divide(left: Expr[Real], right: Expr[Real]): Expr[Real] =
    recorder.expression("div", Vector(left, right))

  def negate[A <: Data](value: Expr[A]): Expr[A] = recorder.expression("neg", Vector(value))

  def lessThan[A <: Data](left: Expr[A], right: Expr[A]): Expr[Bool] =
    recorder.expression("lt", Vector(left, right))
