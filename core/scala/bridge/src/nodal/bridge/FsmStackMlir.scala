package nodal.internal.bridge

import nodal.*

/** Snapshot re-admission into the canonical bounded control graph and storage owner. */
private[nodal] object FsmStackMlir:
  def contract(value: KernelFsmStack): FlatFsmGraphContract.Stack =
    FlatFsmGraphContract.Stack(value.capacity, value.overflow, value.underflow, value.invalidFrame)

  def structure(value: KernelFlatFsmSnapshot, fail: (String, String) => Nothing)
      : FlatFsmGraphContract.Structure =
    FlatFsmGraphContract.validateControlStructure(
      value.localCodes.map(_.caseId).toSet,
      value.initialCase,
      value.states.map(state => state.caseId -> state.terminal),
      value.transitions.map(edge =>
        FlatFsmGraphContract.Edge(
          edge.sourceCase,
          edge.destinationCase,
          edge.mode match
            case "exclusive" => TransitionMode.Exclusive
            case "priority" => TransitionMode.Priority
            case _ => fail("unknown FSM transition mode", value.path),
          edge.kind,
          edge.resumeCase
        )
      ),
      value.stack.map(contract),
      message => fail(message, value.path)
    )

  final class Plan(snapshot: ConstructionSnapshot, fail: (String, String) => Nothing):
    if snapshot.flatFsms.size > 4096 then
      fail("stack machine inventory exceeds 4096", snapshot.root)
    private var bits = 0L
    snapshot.flatFsms.foreach: machine =>
      bits += machine.timerStorage.size * FsmTimingRuntime.Width
      machine.stack.foreach: stack =>
        if machine.localWidth < 1 || machine.localWidth > 4096 || stack.capacity < 1 ||
          stack.capacity > 128 ||
          stack.pointerWidth != FsmStackRuntime.pointerWidth(stack.capacity) ||
          stack.frameStorage.size != stack.capacity || machine.requestedEncoding == "gray"
        then
          fail(
            "stack requires exact bounded capacity, pointer width, frame inventory and supported encoding",
            machine.path
          )
        bits += stack.pointerWidth + stack.capacity.toLong * machine.localWidth
      if bits > FsmTimingRuntime.MaximumStateBits then
        fail("FSM timer and stack private storage exceed 65536 actual bits", machine.path)
    val summaries: Map[String, FlatFsmGraphContract.Structure] = snapshot.flatFsms
      .filter(_.stack.nonEmpty).map(value => value.path -> structure(value, fail)).toMap
