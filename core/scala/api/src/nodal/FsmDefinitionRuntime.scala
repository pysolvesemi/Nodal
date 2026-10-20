package nodal

import scala.collection.mutable

private[nodal] sealed trait FsmDefinitionCommand[S]
private[nodal] final case class FsmDefinitionEdge[S](
    guard: FsmGuard[S],
    mode: TransitionMode,
    destination: S,
    action: Option[FsmAction[S]]
) extends FsmDefinitionCommand[S]
private[nodal] final case class FsmDefinitionAction[S](phase: String, slot: FsmAction[S])
    extends FsmDefinitionCommand[S]

private[nodal] final case class FsmDefinitionJoin[S](
    policy: String,
    child: Option[FsmChild[S, ?]],
    mode: TransitionMode,
    destination: S
) extends FsmDefinitionCommand[S]

private[nodal] final case class FsmDefinitionTimer[S](
    cycles: Int,
    mode: TransitionMode,
    destination: S
) extends FsmDefinitionCommand[S]
private[nodal] final case class FsmDefinitionTransfer[S](
    slot: FsmTransfer[S],
    mode: TransitionMode,
    destination: S
) extends FsmDefinitionCommand[S]

/** Immutable graph admission and explicit binding adapter. FlatFsm owns all executable semantics.
  */
private[nodal] object FsmDefinitionRuntime:
  def fail(message: String): Nothing = FlatFsmRuntime.fail(message, "NODAL-FSM-058-001")

  def unroll[S](levels: Int, base: FsmDef[S], step: (Int, FsmDef[S]) => FsmDef[S]): FsmDef[S] =
    if levels < 0 || levels > 127 || base == null || step == null then
      fail("immutable graph unroll requires 0 through 127 levels and an actual base/callback")
    DigitalCollectionCallback.requireImmutableFsmChecked(step)
    def admit(value: FsmDef[S]): Unit =
      if value == null || (value.evidence ne base.evidence) then
        fail("immutable graph unroll step must preserve the exact enum evidence")
      val cost = value.compositionCost
      if cost.occurrences < 1 || cost.occurrences > 4096 || cost.depth < 1 || cost.depth > 128 ||
        cost.records < 1 || cost.records > FlatFsmRuntime.MaximumRecords
      then fail("immutable graph unroll exceeds the shared finite graph budget")
    admit(base)
    var result = base
    var level = 1
    while level <= levels do
      val next = step(level, result)
      admit(next)
      result = next
      level += 1
    result

  private def name(value: String): Unit =
    if value == null || value.isEmpty || value.length > 4096 || value != value.trim then
      fail("definition, fragment and slot names must be nonempty bounded canonical names")

  def slot[S](owner: FsmDefinition[S], value: String): Unit =
    if owner == null then fail("slot requires an actual definition owner")
    name(value)

  def guardBinding[S](slot: FsmGuard[S], value: Expr[Bool]): FsmGuardBinding[S] =
    if slot == null || value == null then fail("guard binding cannot be null")
    if CandidateRuntime.expressionDataType(value).map(CandidateRuntime.typeDescriptor) !=
        Some(KernelTypeDescriptor("Bool"))
    then fail("guard binding requires exact Bool")
    new FsmGuardBinding(slot, value)

  def actionBinding[S](slot: FsmAction[S], body: () => Unit): FsmActionBinding[S] =
    if slot == null || body == null then fail("action binding cannot be null")
    DigitalCollectionCallback.requireSequentialChecked(body)
    new FsmActionBinding(slot, body)

  def bindings[S](
      guards: Seq[FsmGuardBinding[S]],
      actions: Seq[FsmActionBinding[S]],
      children: Seq[FsmChildBinding[S]] = Vector.empty[FsmChildBinding[S]]
  ): FsmBindings[S] =
    val predicates = bounded(guards, FlatFsmRuntime.MaximumRecords, "guard bindings")
    val effects =
      bounded(actions, FlatFsmRuntime.MaximumRecords - predicates.size, "action bindings")
    val descendants = bounded(
      children,
      FlatFsmRuntime.MaximumRecords - predicates.size - effects.size,
      "child bindings"
    )
    if predicates.contains(null) || effects.contains(null) || descendants.contains(null) then
      fail("runtime binding cannot be null")
    if predicates.map(_.slot).distinct.size != predicates.size ||
      effects.map(_.slot).distinct.size != effects.size ||
      descendants.map(_.slot).distinct.size != descendants.size
    then fail("runtime binding repeats a slot")
    new FsmBindings(predicates, effects, descendants)

  private[nodal] def append[S](state: FsmState[S], command: FsmDefinitionCommand[S]): FsmState[S] =
    if state.commands.size >= FlatFsmRuntime.MaximumRecords then
      fail("state declarations exceed 65536 records")
    new FsmState(state.value, state.commands :+ command, state.isTerminal, state.childSlots)

  def edge[S](
      state: FsmState[S],
      guard: FsmGuard[S],
      mode: TransitionMode,
      to: S,
      action: Option[FsmAction[S]]
  ): FsmState[S] =
    if guard == null || mode == null || action.contains(null) then
      fail("transition requires actual typed slots and mode")
    append(state, FsmDefinitionEdge(guard, mode, to, action))

  def stateAction[S](state: FsmState[S], phase: String, action: FsmAction[S]): FsmState[S] =
    if action == null then fail("state action requires an actual typed slot")
    append(state, FsmDefinitionAction(phase, action))

  private[nodal] def bounded[A](values: Seq[A], maximum: Int, label: String): Vector[A] =
    if values == null then fail(s"$label cannot be null")
    val result = values.iterator.take(maximum + 1).toVector
    if result.size > maximum then fail(s"$label exceed the bounded record limit")
    result

  def fragment[S](value: String, states: Seq[FsmState[S]]): FsmFragment[S] =
    name(value)
    val frozen = bounded(states, 4096, "fragment states")
    if frozen.isEmpty || frozen.contains(null) then
      fail("fragment requires 1 through 4096 actual state declarations")
    new FsmFragment(value, frozen)

  def definition[S](
      owner: FsmDefinition[S],
      initial: S,
      fragments: Seq[FsmFragment[S]],
      evidence: HwEnum[S]
  ): FsmDef[S] =
    val frozen = bounded(fragments, 4096, "definition fragments")
    if owner == null || frozen.isEmpty || frozen.contains(null) then
      fail("definition requires actual bounded fragments")
    if frozen.map(_.name).distinct.size != frozen.size then
      fail("definition repeats a fragment identity")
    val count = frozen.map(_.states.size.toLong).sum
    if count > 4096 then fail("definition exceeds 4096 states")
    val canonical = DigitalEnumContract.abi(owner.canonicalType)
    def caseId(value: S): String = DigitalEnumContract.selectedCase(value, evidence, canonical)
    val initialId = caseId(initial)
    val guards = mutable.LinkedHashSet.empty[FsmGuard[S]]
    val actions = mutable.LinkedHashSet.empty[FsmAction[S]]
    val transfers = mutable.LinkedHashSet.empty[FsmTransfer[S]]
    val states = frozen.flatMap(_.states)
    // Inspect vector sizes before traversing or copying any commands. A legal individual
    // state does not authorize 4096 copies of its maximum-size declaration inventory.
    if states.map(state => state.commands.size.toLong + state.childSlots.size).sum >
        FlatFsmRuntime.MaximumRecords
    then
      fail("definition transitions/actions exceed 65536 records")
    val edges = Vector.newBuilder[(String, String, TransitionMode)]
    var records = 0L
    states.foreach: state =>
      val source = caseId(state.value)
      state.commands.foreach:
        case edge: FsmDefinitionEdge[S] =>
          records += 1 + edge.action.size
          if records > FlatFsmRuntime.MaximumRecords then
            fail("definition transitions/actions exceed 65536 records")
          guards += edge.guard
          edge.action.foreach(actions += _)
          edges += ((source, caseId(edge.destination), edge.mode))
        case timer: FsmDefinitionTimer[S] =>
          records += 1
          edges += ((source, caseId(timer.destination), timer.mode))
        case transfer: FsmDefinitionTransfer[S] =>
          records += 1
          guards += transfer.slot.guard
          transfers += transfer.slot
          edges += ((source, caseId(transfer.destination), transfer.mode))
        case join: FsmDefinitionJoin[S] =>
          records += 1
          edges += ((source, caseId(join.destination), join.mode))
        case action: FsmDefinitionAction[S] =>
          records += 1
          if records > FlatFsmRuntime.MaximumRecords then
            fail("definition transitions/actions exceed 65536 records")
          actions += action.slot
    if records > FlatFsmRuntime.MaximumRecords then
      fail("definition transitions/actions exceed 65536 records")
    val children = states.flatMap(_.childSlots)
    val cost = FsmCompositionRuntime.cost(states, records.toInt, children)
    FsmCompositionRuntime.admitStates(owner, states, children)
    val named = guards.toVector.map(value => (value.name, value.owner)) ++
      actions.toVector.map(value => (value.name, value.owner)) ++
      children.map(value => (value.name, value.owner))
    if named.exists((_, definition) => definition ne owner) then
      fail("definition references a foreign guard, action or child slot")
    if named.map(_._1).distinct.size != named.size then
      fail("distinct slots must have distinct names within one definition")
    FlatFsmGraphContract.validateStructure(
      canonical.caseIds,
      initialId,
      states.map(state => caseId(state.value) -> state.isTerminal),
      edges.result(),
      fail
    )
    new FsmDef(
      owner,
      initial,
      frozen,
      guards.toVector,
      actions.toVector,
      evidence,
      children,
      cost,
      FsmCompositionRuntime.definitionHash(owner, initialId, frozen, evidence),
      transfers.toVector
    )

  def control(restart: Expr[Bool], cancel: Expr[Bool], enable: Expr[Bool]): FsmControl =
    if Vector(restart, cancel, enable).exists(value =>
        value == null ||
          CandidateRuntime.expressionDataType(value).map(CandidateRuntime.typeDescriptor) !=
          Some(KernelTypeDescriptor("Bool"))
      )
    then fail("instance controls require exact non-null Bool values")
    new FsmControl(restart, cancel, enable)

  def instantiate[S](
      definition: FsmDef[S],
      bindings: FsmBindings[S],
      encoding: FsmEncoding,
      custom: Option[FsmLocalEncoding[S]],
      illegalState: IllegalStatePolicy,
      observe: FsmInstance[S] => Unit
  ): Unit =
    if observe == null then fail("instance requires observations")
    if definition.children.nonEmpty then
      fail("definitions with children require instantiateControlled with explicit root controls")
    capture(
      definition,
      bindings,
      encoding,
      custom,
      illegalState,
      None,
      machine => observe(new FsmInstance(machine))
    )

  def instantiateControlled[S](
      definition: FsmDef[S],
      bindings: FsmBindings[S],
      control: FsmControl,
      encoding: FsmEncoding,
      custom: Option[FsmLocalEncoding[S]],
      illegalState: IllegalStatePolicy,
      observe: FsmRun[S] => Unit
  ): Unit =
    if control == null || observe == null then
      fail("controlled instance requires controls and observations")
    if definition.children.nonEmpty then
      ConstructionKernel.fsmComposition(
        FsmInstanceSpec(definition, bindings, encoding, custom, illegalState),
        control,
        observe
      )
    else
      capture(
        definition,
        bindings,
        encoding,
        custom,
        illegalState,
        Some(control),
        machine => observe(new FsmRun(machine))
      )

  private def capture[S](
      definition: FsmDef[S],
      bindings: FsmBindings[S],
      encoding: FsmEncoding,
      custom: Option[FsmLocalEncoding[S]],
      illegalState: IllegalStatePolicy,
      control: Option[FsmControl],
      observe: FsmBuilder[S] => Unit
  ): Unit =
    if bindings == null then fail("instance requires bindings")
    val guards = bindings.guards.map(value => value.slot -> value.value).toMap
    val actions = bindings.actions.map(value => value.slot -> value.body).toMap
    if guards.keySet != definition.guards.toSet || actions.keySet != definition.actions.toSet ||
      bindings.children.nonEmpty
    then
      fail(
        "instance must bind every referenced slot exactly once without foreign or unused bindings"
      )
    ConstructionKernel.flatFsm(
      definition.definition,
      definition.initial,
      encoding,
      custom,
      illegalState,
      machine => {
        definition.fragments.foreach: fragment =>
          fragment.states.foreach: state =>
            ConstructionKernel.flatFsmState(
              machine,
              state.value,
              builder => {
                state.commands.foreach:
                  case edge: FsmDefinitionEdge[S] =>
                    ConstructionKernel.flatFsmTransition(
                      builder,
                      guards(edge.guard),
                      edge.mode,
                      edge.destination,
                      edge.action.map(actions)
                    )
                  case timer: FsmDefinitionTimer[S] =>
                    ConstructionKernel.flatFsmTimer(
                      builder,
                      timer.cycles,
                      timer.mode,
                      timer.destination
                    )
                  case transfer: FsmDefinitionTransfer[S] =>
                    ConstructionKernel.flatFsmTransferTransition(
                      builder,
                      guards(transfer.slot.guard),
                      transfer.mode,
                      transfer.destination
                    )
                  case _: FsmDefinitionJoin[S] =>
                    fail("completion edges require the controlled composition capture owner")
                  case action: FsmDefinitionAction[S] =>
                    ConstructionKernel.flatFsmAction(builder, action.phase, actions(action.slot))
                if state.isTerminal then ConstructionKernel.flatFsmTerminal(builder)
              }
            )
        observe(machine)
      },
      definition.evidence,
      control
    )
