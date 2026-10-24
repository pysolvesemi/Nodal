package unrollproducer
import nodal.*
enum Mode derives HwEnum:
  case Work, Done
object Graphs:
  def base: FsmDef[Mode] =
    val owner = FsmDefinition[Mode]("base")
    FsmDef(owner, Mode.Work)(FsmFragment("states")(
      FsmState(Mode.Work).after(1)(Mode.Done),
      FsmState(Mode.Done).terminal()
    ))
  def step(level: Int, previous: FsmDef[Mode]): FsmDef[Mode] =
    val _ = level
    val owner = FsmDefinition[Mode]("parent")
    val child = FsmChild(owner, "child", previous)
    FsmDef(owner, Mode.Work)(FsmFragment("states")(
      FsmState(Mode.Work).children(child).onAllComplete()(Mode.Done),
      FsmState(Mode.Done).terminal()
    ))
object BroadPure:
  val expression = true.B
  def step(level: Int, previous: FsmDef[Mode]): FsmDef[Mode] =
    val _ = level
    previous
