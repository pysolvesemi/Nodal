package stackunrollnegative
import nodal.*
enum State derives HwEnum:
  case Main, Sub
enum Foreign derives HwEnum:
  case Resume
object StackCallForeign:
  val owner = FsmDefinition[State]("owner")
  val go = FsmGuard(owner, "go")
  val invalid = FsmState(State.Main).call(go)(State.Sub, Foreign.Resume)
