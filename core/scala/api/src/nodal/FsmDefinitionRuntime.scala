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

/** Immutable graph admission and explicit binding adapter. FlatFsm owns all executable semantics.
  */
private[nodal] object FsmDefinitionRuntime:
  def fail(message: String): Nothing = FlatFsmRuntime.fail(message, "NODAL-FSM-058-001")

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
      actions: Seq[FsmActionBinding[S]]
  ): FsmBindings[S] =
    val predicates = bounded(guards, FlatFsmRuntime.MaximumRecords, "guard bindings")
    val effects =
      bounded(actions, FlatFsmRuntime.MaximumRecords - predicates.size, "action bindings")
    if predicates.contains(null) || effects.contains(null) then
      fail("runtime binding cannot be null")
    if predicates.map(_.slot).distinct.size != predicates.size ||
      effects.map(_.slot).distinct.size != effects.size
    then fail("runtime binding repeats a slot")
    new FsmBindings(predicates, effects)

  private def append[S](state: FsmState[S], command: FsmDefinitionCommand[S]): FsmState[S] =
    if state.commands.size >= FlatFsmRuntime.MaximumRecords then
      fail("state declarations exceed 65536 records")
    new FsmState(state.value, state.commands :+ command, state.isTerminal)

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

  private def bounded[A](values: Seq[A], maximum: Int, label: String): Vector[A] =
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
    val states = frozen.flatMap(_.states)
    // Inspect vector sizes before traversing or copying any commands. A legal individual
    // state does not authorize 4096 copies of its maximum-size declaration inventory.
    if states.map(_.commands.size.toLong).sum > FlatFsmRuntime.MaximumRecords then
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
        case action: FsmDefinitionAction[S] =>
          records += 1
          if records > FlatFsmRuntime.MaximumRecords then
            fail("definition transitions/actions exceed 65536 records")
          actions += action.slot
    val named = guards.toVector.map(value => (value.name, value.owner)) ++
      actions.toVector.map(value => (value.name, value.owner))
    if named.exists((_, definition) => definition ne owner) then
      fail("definition references a foreign guard or action slot")
    if named.map(_._1).distinct.size != named.size then
      fail("distinct slots must have distinct names within one definition")
    FlatFsmGraphContract.validateStructure(
      canonical.caseIds,
      initialId,
      states.map(state => caseId(state.value) -> state.isTerminal),
      edges.result(),
      fail
    )
    new FsmDef(owner, initial, frozen, guards.toVector, actions.toVector, evidence)

  def instantiate[S](
      definition: FsmDef[S],
      bindings: FsmBindings[S],
      encoding: FsmEncoding,
      custom: Option[FsmLocalEncoding[S]],
      illegalState: IllegalStatePolicy,
      observe: FsmInstance[S] => Unit
  ): Unit =
    if bindings == null || observe == null then fail("instance requires bindings and observations")
    val guards = bindings.guards.map(value => value.slot -> value.value).toMap
    val actions = bindings.actions.map(value => value.slot -> value.body).toMap
    if guards.keySet != definition.guards.toSet || actions.keySet != definition.actions.toSet then
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
                  case action: FsmDefinitionAction[S] =>
                    ConstructionKernel.flatFsmAction(builder, action.phase, actions(action.slot))
                if state.isTerminal then ConstructionKernel.flatFsmTerminal(builder)
              }
            )
        observe(new FsmInstance(machine))
      },
      definition.evidence
    )
