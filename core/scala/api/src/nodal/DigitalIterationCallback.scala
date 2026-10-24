package nodal

/** Compiler-owned effect attestation for the two source iteration entry points.
  *
  * Separate exact final carriers prevent a range's structural/analog permissions from authorizing
  * an explicit digital loop. The runtime checks these before capturing even an empty domain. The
  * frontend rejects authored factory references; this protocol is not a general security sandbox.
  */
private[nodal] object DigitalIterationCallback:
  private final class CheckedLoop(function: LoopIndex => Unit) extends (LoopIndex => Unit):
    def apply(index: LoopIndex): Unit = function(index)

  private final class CheckedRange(function: Expr[Integer] => Unit)
      extends (Expr[Integer] => Unit):
    def apply(index: Expr[Integer]): Unit = function(index)

  private final class CheckedAreaRange[A <: Area](function: Expr[Integer] => A)
      extends (Expr[Integer] => A):
    def apply(index: Expr[Integer]): A = function(index)

  def checkedAreaRange[A <: Area](function: Expr[Integer] => A): Expr[Integer] => A =
    new CheckedAreaRange(function)

  def requireAreaRange(function: AnyRef): Unit = function match
    case _: CheckedAreaRange[?] => ()
    case _ => missing("AREA-RANGE")

  def checkedLoop(function: LoopIndex => Unit): LoopIndex => Unit = new CheckedLoop(function)

  def checkedRange(function: Expr[Integer] => Unit): Expr[Integer] => Unit =
    new CheckedRange(function)

  def requireLoop(function: AnyRef): Unit = function match
    case _: CheckedLoop => ()
    case _ => missing("LOOP")

  def requireRange(function: AnyRef): Unit = function match
    case _: CheckedRange => ()
    case _ => missing("RANGE")

  private def missing(kind: String): Nothing =
    scala.util.Failure[Nothing](new ConstructionException(KernelDiagnostic(
      s"NODAL-$kind-CALLBACK-MISSING",
      "iteration callback requires typed effect validation by the Nodal compiler plugin"
    ))).get
