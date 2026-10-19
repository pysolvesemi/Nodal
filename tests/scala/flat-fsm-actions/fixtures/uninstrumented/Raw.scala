package nodal.flatfsm.fixture
import nodal.*
object Raw:
  var calls = 0
  def action[S](builder: StateBuilder[S]): Unit =
    FlatFsmRuntime.action(builder, "entry", () => calls += 1)
  def transition[S](builder: StateBuilder[S], condition: Expr[Bool], to: S): Unit =
    FlatFsmRuntime.transitionAction(
      builder,
      condition,
      TransitionMode.Exclusive,
      to,
      () => calls += 1
    )
  def wrongProfile[S](builder: StateBuilder[S]): Unit =
    FlatFsmRuntime.action(builder, "entry", DigitalCollectionCallback.checked0(() => calls += 1))
  def switch[S](selector: Expr[EnumValue[S]])(using evidence: HwEnum[S]): Unit =
    FlatFsmRuntime.switchEnum(selector, () => calls += 1, _ => calls += 1, evidence)
  def caseAction[S](builder: EnumSwitchBuilder[S], value: S): Unit =
    FlatFsmRuntime.switchCase(builder, value, () => calls += 1)
