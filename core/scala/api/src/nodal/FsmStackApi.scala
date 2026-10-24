package nodal

/** Closed failure behavior for one bounded same-enum procedure stack. */
enum FsmStackPolicy[+S]:
  case AssertHold extends FsmStackPolicy[Nothing]
  case Trap(to: S) extends FsmStackPolicy[S]

private[nodal] final case class FsmStackConfig[S](
    capacity: Int,
    overflow: FsmStackPolicy[S],
    underflow: FsmStackPolicy[S],
    invalidFrame: FsmStackPolicy[S]
)

private[nodal] object FsmStackRuntime:
  def configuration[S](
      capacity: Int,
      overflow: FsmStackPolicy[S],
      underflow: FsmStackPolicy[S],
      invalidFrame: FsmStackPolicy[S]
  ): FsmStackConfig[S] =
    if capacity < 1 || capacity > 128 || overflow == null || underflow == null ||
      invalidFrame == null
    then
      FsmDefinitionRuntime.fail(
        "stack requires capacity 1 through 128 and three actual closed policies"
      )
    FsmStackConfig(capacity, overflow, underflow, invalidFrame)

  def policy[S](value: FsmStackPolicy[S], caseId: S => String): Option[String] = value match
    case FsmStackPolicy.AssertHold => None
    case FsmStackPolicy.Trap(to) => Some(caseId(to))

  def contract[S](value: FsmStackConfig[S], caseId: S => String): FlatFsmGraphContract.Stack =
    val checked = configuration(value.capacity, value.overflow, value.underflow, value.invalidFrame)
    FlatFsmGraphContract.Stack(
      checked.capacity,
      policy(checked.overflow, caseId),
      policy(checked.underflow, caseId),
      policy(checked.invalidFrame, caseId)
    )

  def pointerWidth(capacity: Int): Int = BigInt(capacity).bitLength

  def call[S](
      state: FsmState[S],
      guard: FsmGuard[S],
      mode: TransitionMode,
      entry: S,
      resume: S
  ): FsmState[S] =
    if guard == null || mode == null then
      FsmDefinitionRuntime.fail("call requires an actual guard and mode")
    FsmDefinitionRuntime.append(state, FsmDefinitionCall(guard, mode, entry, resume))

  def returnOn[S](state: FsmState[S], guard: FsmGuard[S], mode: TransitionMode): FsmState[S] =
    if guard == null || mode == null then
      FsmDefinitionRuntime.fail("return requires an actual guard and mode")
    FsmDefinitionRuntime.append(state, FsmDefinitionReturn(guard, mode))
