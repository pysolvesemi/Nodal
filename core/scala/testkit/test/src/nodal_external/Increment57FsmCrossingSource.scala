package nodal_external

import nodal.*

enum LevelFsmState derives HwEnum:
  case Idle, Done

final class Increment57FsmCrossingSource(phase: String, reuse: Boolean) extends Module:
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  var input = Option.empty[Register[Bool]]
  source:
    val state = Reg(false.B)
    state := true.B
    input = Some(state)
  destination:
    val first = Cdc.sync(input.get, destination, 2)
    val second = if reuse then first else Cdc.sync(input.get, destination, 3)
    val target = Reg(false.B)
    val output = out(Bool)
    val memory = Mem(Bool, 2, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, destination)
    fsm(FsmDefinition[LevelFsmState]("levelMachine"), LevelFsmState.Idle): machine =>
      machine.state(LevelFsmState.Idle): state =>
        if phase == "transition" then
          state.onWithAction(first)(LevelFsmState.Done) { target := second }
        else if phase == "memory" then
          state.onWithAction(first)(LevelFsmState.Done) { memory.write(0.U(1), second, 1.U(1)) }
        else if phase == "priority" then
          state.on(first, TransitionMode.Priority)(LevelFsmState.Done)
          state.onWithAction(true.B, TransitionMode.Priority)(LevelFsmState.Done) {
            target := second
          }
        else
          state.on(first)(LevelFsmState.Done)
          if phase == "exit" then state.exit { target := second }
          if phase == "active" then state.active { target := second }
          if phase == "active-taken" then
            state.active { when(machine.transitionTaken) { target := second } }
      machine.state(LevelFsmState.Done): state =>
        state.terminal()
        if phase == "entry" then state.entry { target := second }
    output := target
