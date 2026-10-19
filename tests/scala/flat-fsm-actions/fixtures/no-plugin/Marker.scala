// Expected: NODAL-SEQUENTIAL-056-MISSING
import nodal.*
object Marker:
  def actions[S](
      state: StateBuilder[S],
      cases: EnumSwitchBuilder[S],
      selector: Expr[EnumValue[S]],
      flag: Expr[Bool],
      value: S
  )(using HwEnum[S]): Unit =
    state.entry(())
    state.active(())
    state.exit(())
    state.onWithAction(flag)(value)(())
    cases.is(value)(())
    switchEnum(selector)(())(_ => ())
