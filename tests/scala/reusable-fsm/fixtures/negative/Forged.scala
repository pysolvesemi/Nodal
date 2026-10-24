// Expected: NODAL-SEQUENTIAL-056-FORGED
package nodal
object ForgedReusable:
  def action[S](slot: FsmAction[S]): FsmActionBinding[S] =
    FsmDefinitionRuntime.actionBinding(slot, () => ())
