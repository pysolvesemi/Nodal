// Expected: NODAL-SEQUENTIAL-056-UNAVAILABLE
package negative.transitionactionguard
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
    s.onWithAction({ target := input; condition })(Mode.Run) { () }
