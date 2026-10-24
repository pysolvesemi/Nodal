package nodal

/** Runtime half of the typed frontend's collection callback protocol.
  *
  * The private final carriers preserve the original callback and invoke it exactly once when the
  * collection owner captures its template. No user annotation or public marker can assert purity.
  * The compiler rejects authored references to the checked factories before injecting its calls.
  */
private[nodal] object DigitalCollectionCallback:
  /** Compiler-authored global immutable Interface initializer, not a factory call marker. */
  def initializeGlobalInterface[A](initializer: () => A): A =
    ConstructionKernel.initializeGlobalInterface(initializer)

  // Structural callbacks carry a distinct profile. A checked collection function cannot attest
  // a branch, and an ordinary Function0 cannot bypass validation of either authored arm.
  private final class Checked0[A](function: () => A) extends (() => A):
    def apply(): A = function()

  def checked0[A](function: () => A): () => A = new Checked0(function)

  def requireStructuralChecked(function: AnyRef): Unit = function match
    case _: Checked0[?] => ()
    case _ =>
      scala.util.Failure[Nothing](new ConstructionException(KernelDiagnostic(
        "NODAL-STRUCTURAL-055-MISSING",
        "structural branch requires typed effect validation by the Nodal compiler plugin"
      ))).get
  private final class CheckedSequential(function: () => Unit) extends (() => Unit):
    def apply(): Unit = function()

  def checkedSequential(function: () => Unit): () => Unit = new CheckedSequential(function)

  def requireSequentialChecked(function: AnyRef): Unit = function match
    case _: CheckedSequential => ()
    case _ =>
      scala.util.Failure[Nothing](new ConstructionException(KernelDiagnostic(
        "NODAL-SEQUENTIAL-056-MISSING",
        "behavioral branch requires typed effect validation by the Nodal compiler plugin"
      ))).get

  private final class Checked1[A, B](function: A => B) extends (A => B):
    def apply(value: A): B = function(value)

  private final class Checked2[A, B, C](function: (A, B) => C) extends ((A, B) => C):
    def apply(left: A, right: B): C = function(left, right)

  def checked1[A, B](function: A => B): A => B = new Checked1(function)

  def checked2[A, B, C](function: (A, B) => C): (A, B) => C = new Checked2(function)

  def requireChecked(function: AnyRef): Unit = function match
    case _: Checked1[?, ?] | _: Checked2[?, ?, ?] => ()
    case _ =>
      scala.util.Failure[Nothing](new ConstructionException(KernelDiagnostic(
        "NODAL-COLLECTION-CALLBACK-MISSING",
        "collection callback requires typed effect validation by the Nodal compiler plugin"
      ))).get

  /** Preserve the exact compiler carrier across the vector-argument adapter. */
  final class ReductionBody private[DigitalCollectionCallback] (
      val checked: AnyRef,
      invoke: Vector[Expr[?]] => Expr[?]
  ) extends (Vector[Expr[?]] => Expr[?]):
    def apply(arguments: Vector[Expr[?]]): Expr[?] = invoke(arguments)

  def reductionBody[A <: Data](function: (Expr[A], Expr[A]) => Expr[A]): ReductionBody =
    function match
      case _: Checked2[?, ?, ?] => ()
      case _ => requireChecked(new Object)
    new ReductionBody(
      function,
      arguments =>
        function(arguments(0).asInstanceOf[Expr[A]], arguments(1).asInstanceOf[Expr[A]])
    )

  // Immutable graph construction is strictly narrower than ordinary expression purity.
  private final class CheckedFsmGraph2[S](function: (Int, FsmDef[S]) => FsmDef[S])
      extends ((Int, FsmDef[S]) => FsmDef[S]):
    def apply(level: Int, previous: FsmDef[S]): FsmDef[S] = function(level, previous)

  def checkedFsmGraph2[S](function: (Int, FsmDef[S]) => FsmDef[S])
      : (Int, FsmDef[S]) => FsmDef[S] = new CheckedFsmGraph2(function)

  def requireImmutableFsmChecked(function: AnyRef): Unit = function match
    case _: CheckedFsmGraph2[?] => ()
    case _ =>
      scala.util.Failure[Nothing](new ConstructionException(KernelDiagnostic(
        "NODAL-FSM-058-UNROLL-MISSING",
        "immutable graph unroll requires typed effect validation by the Nodal compiler plugin"
      ))).get
