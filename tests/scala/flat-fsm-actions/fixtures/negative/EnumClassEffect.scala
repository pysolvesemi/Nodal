// Expected: NODAL-SEQUENTIAL-056-UNAVAILABLE
package negative.enumclasseffect
import nodal.*
enum Mode derives HwEnum:
  case Idle, Run
  println("enum class effect")
object Witness:
  def build(s: StateBuilder[Mode], condition: Expr[Bool]): Unit = s.on(condition)(Mode.Run)
