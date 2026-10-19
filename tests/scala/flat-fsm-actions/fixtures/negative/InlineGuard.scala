// Expected: NODAL-SEQUENTIAL-056-UNAVAILABLE
package negative.inlineguard
import nodal.*
enum Mode derives HwEnum:
  case Idle, Run
object Witness:
  transparent inline def effect(inline flag: Expr[Bool]): Expr[Bool] =
    println("inlined eager effect")
    flag
  def build(state: StateBuilder[Mode], flag: Expr[Bool]): Unit =
    state.on(effect(flag))(Mode.Run)
