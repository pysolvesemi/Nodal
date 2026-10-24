package nodal

/** Immutable transfer slot. Runtime carrier values enter only through explicit bindings. */
final class FsmTransfer[S] private[nodal] (private[nodal] val guard: FsmGuard[S]):
  def bind[A <: Data](carrier: Stream[A]): FsmGuardBinding[S] =
    FsmDefinitionRuntime.guardBinding(guard, ConstructionKernel.fsmTransfer(carrier))

object FsmTransfer:
  def apply[S](owner: FsmDefinition[S], name: String): FsmTransfer[S] =
    new FsmTransfer(FsmGuard(owner, name))

private[nodal] object FsmTimingRuntime:
  val ElapsedOperation = "fsm_elapsed"
  val TransferOperation = "fsm_transfer"
  val Width = 31
  val Saturation = Int.MaxValue - 1
  val MaximumStateBits = 65536

  def cycles(value: Int): Unit =
    if value <= 0 then FsmDefinitionRuntime.fail("timer cycles must be a positive Int")

  def after[S](state: FsmState[S], count: Int, mode: TransitionMode, to: S): FsmState[S] =
    cycles(count)
    if mode == null then FsmDefinitionRuntime.fail("timer requires an actual transition mode")
    FsmDefinitionRuntime.append(state, FsmDefinitionTimer(count, mode, to))

  def transfer[S](
      state: FsmState[S],
      slot: FsmTransfer[S],
      mode: TransitionMode,
      to: S
  ): FsmState[S] =
    if slot == null || mode == null then
      FsmDefinitionRuntime.fail("transfer wait requires an actual typed slot and mode")
    FsmDefinitionRuntime.append(state, FsmDefinitionTransfer(slot, mode, to))
