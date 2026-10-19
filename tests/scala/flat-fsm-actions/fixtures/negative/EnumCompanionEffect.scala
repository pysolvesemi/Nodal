// Expected: NODAL-SEQUENTIAL-056-UNAVAILABLE
package negative.enumcompanioneffect
import nodal.*
enum Mode derives HwEnum:
  case Idle, Run
object Mode:
  println("authored companion effect")
object Witness:
  def build(s: StateBuilder[Mode], condition: Expr[Bool]): Unit = s.on(condition)(Mode.Run)
