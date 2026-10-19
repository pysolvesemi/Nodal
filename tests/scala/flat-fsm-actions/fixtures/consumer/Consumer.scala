package nodal.flatfsm.separate
import nodal.*
enum Mode derives HwEnum:
  case Idle, Run
object Consumer:
  def build(
      s: StateBuilder[Mode],
      condition: Expr[Bool],
      target: Register[UInt],
      input: Expr[UInt]
  ): Unit =
    s.on(Producer.condition(condition))(Mode.Run)
    s.onWithAction(Producer.condition(condition))(Mode.Run) { Producer.action(target, input) }
    s.active { Producer.action(target, input) }
