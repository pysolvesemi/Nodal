package nodal

/** Runtime half of the typed frontend's collection callback protocol.
  *
  * The private final carriers preserve the original callback and invoke it exactly once when the
  * collection owner captures its template. No user annotation or public marker can assert purity.
  * The compiler rejects authored references to the checked factories before injecting its calls.
  */
private[nodal] object DigitalCollectionCallback:
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
