package reusable.fixture

import nodal.*

enum Mode derives HwEnum:
  case Idle, Done

object Library:
  val owner = FsmDefinition[Mode]("library")
  val go = FsmGuard(owner, "go")
  val action = FsmAction(owner, "action")
  val fragment = FsmFragment("all")(
    FsmState(Mode.Idle).onWithAction(go, action)(Mode.Done),
    FsmState(Mode.Done).terminal()
  )
  val graph = FsmDef(owner, Mode.Idle)(fragment)
  def update(target: Register[UInt], value: Expr[UInt]): Unit = target := value
