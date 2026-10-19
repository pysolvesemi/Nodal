// Expected: NODAL-SEQUENTIAL-056-UNAVAILABLE
package negative.nestedgrapheffect
import nodal.*
enum Mode derives HwEnum:
  case Idle, Run
object Witness:
  def build(
      s: StateBuilder[Mode],
      selector: Expr[EnumValue[Mode]],
      condition: Expr[Bool],
      target: Register[UInt],
      input: Expr[UInt],
      parameter: Param[Integer]
  ): Unit =
    val _ = (selector, condition, target, input, parameter)
    s.entry {
      switchEnum(selector)(()) { cases =>
        println("nested effect"); cases.is(Mode.Idle)(()); cases.is(Mode.Run)(())
      }
    }
