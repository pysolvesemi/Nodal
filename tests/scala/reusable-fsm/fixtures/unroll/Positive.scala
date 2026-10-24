package unrollprobe
import nodal.*
enum Mode derives HwEnum:
  case Work, Done
object Helpers:
  def base: FsmDef[Mode] =
    val owner = FsmDefinition[Mode]("leaf")
    FsmDef(owner, Mode.Work)(FsmFragment("states")(
      FsmState(Mode.Work).after(1)(Mode.Done),
      FsmState(Mode.Done).terminal()
    ))
  def wrap(level: Int, previous: FsmDef[Mode]): FsmDef[Mode] =
    val _ = level
    val owner = FsmDefinition[Mode]("level")
    val child = FsmChild(owner, "child", previous)
    FsmDef(owner, Mode.Work)(FsmFragment("states")(
      FsmState(Mode.Work).children(child).onAllComplete()(Mode.Done),
      FsmState(Mode.Done).terminal()
    ))
object Positive:
  def main(args: Array[String]): Unit =
    val _ = args
    val base = Helpers.base
    val zero = FsmDef.unroll(0, base)((_, previous) => previous)
    val max = FsmDef.unroll(127, base)((_, previous) => previous)
    val nested = FsmDef.unroll(2, base)(Helpers.wrap)
    assert(zero eq base)
    assert(max eq base)
    assert(nested ne base)
    println("FINITE_UNROLL_PUBLIC_PASS")
