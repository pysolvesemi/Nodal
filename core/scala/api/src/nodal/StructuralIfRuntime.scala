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

/** Read-only Boolean proof over actual predicate nodes. Integer identity, arithmetic and lexical
  * ownership remain with the caller's canonical query; this owns no retained graph or registry.
  */
private[nodal] object StructuralPredicateOrdering:
  sealed trait Node[P, I]
  final case class Compare[P, I](operation: String, left: I, right: I) extends Node[P, I]
  final case class Logic[P, I](operation: String, operands: Vector[P]) extends Node[P, I]

  final class Query[P, I](
      node: P => Node[P, I],
      bounds: I => IterationDomain.Bounds,
      same: (I, I) => Boolean
  ):
    private var remaining = 65536
    private def charge(count: Int = 1): Unit =
      remaining -= count
      if remaining < 0 then invalid("structural ordering proof exceeds its work budget")
    private def invalid(message: String): Nothing =
      scala.util.Failure[Nothing](new IllegalArgumentException(
        s"NODAL-STRUCTURAL-055-003: $message"
      )).get
    private def reverse(mask: Int): Int = ((mask & 1) << 2) | (mask & 2) | ((mask & 4) >> 2)
    private def outcomes(operation: String): Int = operation match
      case "lt" => 1
      case "eq" => 2
      case "le" => 3
      case "gt" => 4
      case "ne" => 5
      case "ge" => 6
      case _ => invalid("structural ordering requires an actual closed comparison")
    private def attainable(left: I, right: I): Int =
      charge()
      if same(left, right) then 2
      else
        val a = bounds(left)
        val b = bounds(right)
        (if a.lower < b.upper then 1 else 0) |
          (if a.lower <= b.upper && b.lower <= a.upper then 2 else 0) |
          (if a.upper > b.lower then 4 else 0)
    private final case class Literal(predicate: P, comparison: Compare[P, I], mask: Int)
    private type Cube = Vector[Literal]
    private type Dnf = Vector[Cube]
    private val active = scala.collection.mutable.HashSet.empty[(P, Boolean)]
    private def formula(predicate: P, negated: Boolean, depth: Int): Dnf =
      charge()
      if depth >= 128 || !active.add(predicate -> negated) then
        invalid("structural predicate is cyclic or exceeds its depth budget")
      try node(predicate) match
          case comparison: Compare[P, I] =>
            val requested = outcomes(comparison.operation) ^ (if negated then 7 else 0)
            val possible = attainable(comparison.left, comparison.right)
            if (possible & requested) == 0 then Vector.empty
            else if (possible & ~requested) == 0 then Vector(Vector.empty)
            else Vector(Vector(Literal(predicate, comparison, requested)))
          case Logic("not", Vector(operand)) => formula(operand, !negated, depth + 1)
          case Logic(operation, Vector(left, right)) if operation == "and" || operation == "or" =>
            val a = formula(left, negated, depth + 1)
            val b = formula(right, negated, depth + 1)
            if (operation == "and") != negated then
              val result = Vector.newBuilder[Cube]
              a.foreach: x =>
                b.foreach: y =>
                  charge()
                  val conflict = x.exists: first =>
                    y.exists: second =>
                      charge()
                      val c = first.comparison
                      val d = second.comparison
                      if first.predicate == second.predicate then (first.mask & second.mask) == 0
                      else if same(c.left, d.left) && same(c.right, d.right) then
                        (first.mask & second.mask) == 0
                      else if same(c.left, d.right) && same(c.right, d.left) then
                        (first.mask & reverse(second.mask)) == 0
                      else false
                  if !conflict then
                    charge(1 + x.size + y.size)
                    result += x ++ y
              result.result()
            else
              charge(a.size + b.size + a.map(_.size).sum + b.map(_.size).sum)
              a ++ b
          case _ => invalid("structural ordering requires a closed Boolean predicate DAG")
      finally active.remove(predicate -> negated)

    def less(left: I, right: I, inclusive: Boolean): Boolean =
      val wanted = if inclusive then 3 else 1
      (attainable(left, right) & ~wanted) == 0

    def under(predicate: P, negated: Boolean, left: I, right: I, inclusive: Boolean): Boolean =
      val cubes = formula(predicate, negated, 0)
      val wanted = if inclusive then 3 else 1
      cubes.nonEmpty && cubes.forall: cube =>
        cube.exists: literal =>
          charge()
          val comparison = literal.comparison
          val mask =
            if same(left, comparison.left) && same(right, comparison.right) then literal.mask
            else if same(left, comparison.right) && same(right, comparison.left) then
              reverse(literal.mask)
            else 7
          (mask & ~wanted) == 0
