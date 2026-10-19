// Expected: NODAL-SEQUENTIAL-056-FORGED
package nodal.flatfsm.forged
import nodal.*
object KernelForged:
  def calls[S](
      definition: FsmDefinition[S],
      machine: FsmBuilder[S],
      state: StateBuilder[S],
      cases: EnumSwitchBuilder[S],
      selector: Expr[EnumValue[S]],
      flag: Expr[Bool],
      value: S,
      evidence: HwEnum[S]
  ): Unit =
    ConstructionKernel.flatFsm(
      definition,
      value,
      FsmEncoding.Compact,
      None,
      IllegalStatePolicy.Error,
      _ => (),
      evidence
    )
    ConstructionKernel.flatFsmState(machine, value, _ => ())
    ConstructionKernel.flatFsmTerminal(state)
    ConstructionKernel.flatFsmTransition(state, flag, TransitionMode.Exclusive, value, None)
    ConstructionKernel.flatFsmAction(state, "entry", () => ())
    val _ = ConstructionKernel.flatFsmStateView(machine, false)
    val _ = ConstructionKernel.flatFsmEvent(machine, value, false)
    val _ = ConstructionKernel.flatFsmTaken(machine)
    val _ = ConstructionKernel.flatFsmCompleted(machine)
    ConstructionKernel.enumSwitch(selector, () => (), _ => (), evidence)
    ConstructionKernel.enumSwitchCase(cases, value, () => ())
