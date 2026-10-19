package iteration_external
import nodal.*
// Expected detail: cannot be accessed
object PrivateLoopIndex:
  val forged = new LoopIndex(0.integer)
