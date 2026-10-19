// Expected: NODAL-SEQUENTIAL-056-UNAVAILABLE
package negative.enumhelpereffect
import nodal.*
enum Mode derives HwEnum:
  case Idle, Run
object Helpers:
  def effect(): Unit = println("transitive companion effect")
object Mode:
  Helpers.effect()
object Witness:
  def build(s: StateBuilder[Mode], condition: Expr[Bool]): Unit = s.on(condition)(Mode.Run)
