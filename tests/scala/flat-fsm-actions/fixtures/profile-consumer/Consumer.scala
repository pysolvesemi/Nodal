package nodal.flatfsm.separate
import nodal.*
enum ProfileMode derives HwEnum:
  case Idle, Run
object ProfileConsumer:
  def build(
      state: StateBuilder[ProfileMode],
      flag: Expr[Bool],
      target: Register[UInt],
      input: Expr[UInt]
  ): Unit =
    state.on(Producer.effectfulCondition(target, input, flag))(ProfileMode.Run)
