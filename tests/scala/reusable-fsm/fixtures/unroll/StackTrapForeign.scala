package stackunrollnegative
import nodal.*
enum State derives HwEnum:
  case Main, Done
enum Foreign derives HwEnum:
  case Trap
object StackTrapForeign:
  val owner = FsmDefinition[State]("owner")
  val invalid = FsmDef.withCallStack(
    owner,
    State.Main,
    2,
    FsmStackPolicy.Trap(Foreign.Trap),
    FsmStackPolicy.AssertHold,
    FsmStackPolicy.AssertHold
  )(
    FsmFragment("states")(FsmState(State.Main), FsmState(State.Done).terminal())
  )
