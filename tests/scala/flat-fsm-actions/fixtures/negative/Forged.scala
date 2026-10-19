// Expected: NODAL-SEQUENTIAL-056-FORGED
package nodal.flatfsm.forged
import nodal.*
object Forged:
  def calls[S](
      s: StateBuilder[S],
      cases: EnumSwitchBuilder[S],
      selector: Expr[EnumValue[S]],
      flag: Expr[Bool],
      value: S,
      evidence: HwEnum[S]
  ): Unit =
    FlatFsmRuntime.action(s, "entry", () => ())
    FlatFsmRuntime.transitionAction(s, flag, TransitionMode.Exclusive, value, () => ())
    FlatFsmRuntime.switchCase(cases, value, () => ())
    FlatFsmRuntime.switchEnum(selector, () => (), _ => (), evidence)
    DigitalCollectionCallback.checkedSequential(() => ())
