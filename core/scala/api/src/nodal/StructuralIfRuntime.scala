package nodal

/** Compiler-owned static predicate carrier. It is deliberately neither a host Boolean nor a runtime
  * hardware value. Its identity and operands belong to the active construction session.
  */
private[nodal] final class StaticPredicate private[nodal] (
    val operation: String,
    val operands: Vector[Any]
)

private[nodal] object StructuralIfRuntime:
  val Operation = "structural_if"

  def integerOperand(value: Int | Expr[Integer]): Expr[Integer] = value match
    case literal: Int => CandidateRuntime.literalInteger(literal)
    case expression: Expr[Integer] => expression

  def compare(left: Expr[Integer], right: Expr[Integer], operation: String): StaticPredicate =
    ConstructionKernel.staticPredicate(operation, Vector(left, right))

  def and(left: StaticPredicate, right: StaticPredicate): StaticPredicate =
    ConstructionKernel.staticPredicate("and", Vector(left, right))

  def or(left: StaticPredicate, right: StaticPredicate): StaticPredicate =
    ConstructionKernel.staticPredicate("or", Vector(left, right))

  def not(value: StaticPredicate): StaticPredicate =
    ConstructionKernel.staticPredicate("not", Vector(value))

  private def checked[A](whenTrue: AnyRef, whenFalse: AnyRef)(body: => A): A =
    try
      // Check both attestations before either callback can execute, even if the condition is
      // invariant. A caught failure still invalidates the surrounding construction transaction.
      DigitalCollectionCallback.requireStructuralChecked(whenTrue)
      DigitalCollectionCallback.requireStructuralChecked(whenFalse)
      body
    catch
      case failure: Throwable =>
        ConstructionKernel.poisonConstructor(failure, "structural if")
        scala.util.Failure[Nothing](failure).get

  def value[A <: Data](
      predicate: StaticPredicate,
      whenTrue: () => Expr[A],
      whenFalse: () => Expr[A]
  ): Expr[A] = checked(whenTrue, whenFalse):
    ConstructionKernel.structuralIf(predicate, true)(
      () => Some(whenTrue()),
      () => Some(whenFalse())
    )
      .get.asInstanceOf[Expr[A]]

  def statement(
      predicate: StaticPredicate,
      whenTrue: () => Unit,
      whenFalse: () => Unit
  ): Unit = checked(whenTrue, whenFalse):
    val _ = ConstructionKernel.structuralIf(predicate, false)(
      () => { whenTrue(); None },
      () => { whenFalse(); None }
    )
