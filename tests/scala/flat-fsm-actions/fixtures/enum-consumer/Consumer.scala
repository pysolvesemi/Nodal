package nodal.flatfsm.enums
import nodal.*
object EnumConsumer:
  def build(s: StateBuilder[Mode], condition: Expr[Bool], selector: Expr[EnumValue[Mode]]): Unit =
    s.on(condition)(EnumHelpers.destination)
    switchEnum(selector)(()) { cases =>
      cases.is(Mode.Idle)(())
      cases.is(Mode.Run)(())
    }(using EnumHelpers.evidence)
