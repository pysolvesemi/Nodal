package stackunrollprobe
import nodal.*
enum Mode derives HwEnum:
  case Main, Sub, Resume, Done
object StackPositive:
  def build(level: Int, previous: FsmDef[Mode]): FsmDef[Mode] =
    val _ = level
    val _ = previous
    val owner = FsmDefinition[Mode]("checked-stack")
    val go = FsmGuard(owner, "go")
    FsmDef.withCallStack(
      owner,
      Mode.Main,
      2,
      FsmStackPolicy.AssertHold,
      FsmStackPolicy.AssertHold,
      FsmStackPolicy.Trap(Mode.Done)
    )(
      FsmFragment("states")(
        FsmState(Mode.Main).call(go)(Mode.Sub, Mode.Resume),
        FsmState(Mode.Sub).returnOn(go),
        FsmState(Mode.Resume).on(go)(Mode.Done),
        FsmState(Mode.Done).terminal()
      )
    )
  def main(args: Array[String]): Unit =
    val _ = args
    val seedOwner = FsmDefinition[Mode]("seed")
    val go = FsmGuard(seedOwner, "go")
    val seed = FsmDef(seedOwner, Mode.Main)(FsmFragment("states")(
      FsmState(Mode.Main).on(go)(Mode.Sub),
      FsmState(Mode.Sub).on(go)(Mode.Resume),
      FsmState(Mode.Resume).on(go)(Mode.Done),
      FsmState(Mode.Done).terminal()
    ))
    val graph = FsmDef.unroll(1, seed)(build)
    assert(graph.guardSlots.size == 1)
    assert(graph.initial == Mode.Main)
    println("STACK_CHECKED_UNROLL_PASS")
