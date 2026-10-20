package nodal

/** Immutable compiler certificate for a captured event body, not a purity assertion. The original
  * body still executes once at elaboration, including permitted ordinary Scala bookkeeping. Only
  * the actual digital dispatch consumes the control verdict; analog on(event) remains unchanged.
  */
private[nodal] object DigitalEventCallback:
  private final class Checked(function: () => Unit, val rejection: String) extends (() => Unit):
    def apply(): Unit = function()

  // Authored references to this factory are rejected by the compiler before its own insertion.
  def checked(function: () => Unit, rejection: String): () => Unit =
    new Checked(function, rejection)

  private def reject(code: String, message: String): Nothing =
    val error = new ConstructionException(KernelDiagnostic(code, message))
    ConstructionKernel.poisonConstructor(error, "digital event control")
    throw error

  def requireChecked(function: AnyRef): Unit = function match
    case value: Checked if value.rejection.isEmpty => ()
    case value: Checked => reject("NODAL-EVENT-057-CONTROL", value.rejection)
    case _ => reject(
        "NODAL-EVENT-057-MISSING",
        "digital event body requires typed control validation by the Nodal compiler plugin"
      )
