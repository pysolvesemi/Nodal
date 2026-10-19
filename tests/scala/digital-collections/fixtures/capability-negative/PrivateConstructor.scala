// Expected detail: cannot be accessed
package nodal_external
import nodal.*
object WritablePrivateConstructor:
  def invalid(value: Expr[UInt]): SignalSelection[UInt] = new SignalSelection(value)
