package nodal

/** An immutable child slot. Its definition contains no runtime hardware values or callbacks. */
final class FsmChild[P, C] private[nodal] (
    private[nodal] val owner: FsmDefinition[P],
    val name: String,
    val definition: FsmDef[C]
):
  def bind(
      bindings: FsmBindings[C],
      encoding: FsmEncoding = FsmEncoding.Compact,
      illegalState: IllegalStatePolicy = IllegalStatePolicy.Error
  ): FsmChildBinding[P] =
    FsmCompositionRuntime.binding(this, bindings, encoding, None, illegalState)

  def bind(bindings: FsmBindings[C], encoding: FsmLocalEncoding[C]): FsmChildBinding[P] =
    bind(bindings, encoding, IllegalStatePolicy.Error)

  def bind(
      bindings: FsmBindings[C],
      encoding: FsmLocalEncoding[C],
      illegalState: IllegalStatePolicy
  ): FsmChildBinding[P] =
    FsmCompositionRuntime.binding(this, bindings, FsmEncoding.Custom, Some(encoding), illegalState)

object FsmChild:
  def apply[P, C](owner: FsmDefinition[P], name: String, definition: FsmDef[C]): FsmChild[P, C] =
    FsmDefinitionRuntime.slot(owner, name)
    if definition == null then
      FsmDefinitionRuntime.fail("child slot requires an immutable definition")
    new FsmChild(owner, name, definition)

/** The existential child type remains paired with its exact typed slot and explicit bindings. */
sealed abstract class FsmChildBinding[P] private[nodal]:
  private[nodal] type Child
  private[nodal] val slot: FsmChild[P, Child]
  private[nodal] val instance: FsmInstanceSpec[Child]

/** Inactive child storage may be retained or uninitialized; qualify observations with active. */
final class FsmChildRun[C] private[nodal] (val active: Expr[Bool], val run: FsmRun[C])

private[nodal] final case class FsmInstanceSpec[S](
    definition: FsmDef[S],
    bindings: FsmBindings[S],
    encoding: FsmEncoding,
    custom: Option[FsmLocalEncoding[S]],
    illegalState: IllegalStatePolicy
)

private[nodal] final case class FsmCompositionCost(
    occurrences: Int,
    records: Int,
    depth: Int,
    completionPossible: Boolean
)

/** Bottom-up immutable admission. Local reachability remains owned by FlatFsmGraphContract. */
private[nodal] object FsmCompositionRuntime:
  private def fail(message: String): Nothing = FsmDefinitionRuntime.fail(message)

  def binding[P, C](
      child: FsmChild[P, C],
      bindings: FsmBindings[C],
      encoding: FsmEncoding,
      custom: Option[FsmLocalEncoding[C]],
      illegalState: IllegalStatePolicy
  ): FsmChildBinding[P] =
    if bindings == null || encoding == null || illegalState == null || custom.contains(null) then
      fail("child binding requires actual explicit bindings and encoding/policy")
    val spec = FsmInstanceSpec(child.definition, bindings, encoding, custom, illegalState)
    validateBindings(spec)
    new FsmChildBinding[P]:
      type Child = C
      val slot = child
      val instance = spec

  def children[S](state: FsmState[S], values: Seq[FsmChild[S, ?]]): FsmState[S] =
    val slots = FsmDefinitionRuntime.bounded(values, 4095, "child slots")
    if state.childSlots.nonEmpty || slots.isEmpty || slots.contains(null) ||
      slots.distinct.size != slots.size
    then fail("state requires one nonempty unique child inventory")
    if state.isTerminal then fail("terminal state cannot own children")
    new FsmState(state.value, state.commands, state.isTerminal, slots)

  def join[S](
      state: FsmState[S],
      policy: String,
      child: Option[FsmChild[S, ?]],
      mode: TransitionMode,
      destination: S
  ): FsmState[S] =
    if mode == null || child.contains(null) then
      fail("completion edge requires actual mode and child")
    FsmDefinitionRuntime.append(state, FsmDefinitionJoin(policy, child, mode, destination))

  def admitStates[S](
      owner: FsmDefinition[S],
      states: Vector[FsmState[S]],
      children: Vector[FsmChild[S, ?]]
  ): Unit =
    if children.distinct.size != children.size || children.exists(_.owner ne owner) then
      fail("child slot requires its exact parent definition and exactly one owner state")
    states.foreach: state =>
      if state.isTerminal && state.childSlots.nonEmpty then
        fail("terminal state cannot own children")
      state.commands.foreach:
        case join: FsmDefinitionJoin[S] =>
          if state.childSlots.isEmpty || !Set("all", "any")(join.policy) ||
            join.child.exists(child => !state.childSlots.contains(child))
          then fail("completion edge requires actual children of its exact owner state")
          val selected = join.child.toVector match
            case values if values.nonEmpty => values
            case _ => state.childSlots
          val possible = selected.map(_.definition.compositionCost.completionPossible)
          if !(if join.policy == "all" then possible.forall(identity)
               else possible.exists(identity))
          then
            fail("completion join has no structural completion possibility")
        case _ => ()
  def cost[S](
      states: Vector[FsmState[S]],
      commands: Int,
      children: Vector[FsmChild[S, ?]]
  ): FsmCompositionCost =
    var occurrences = 1L
    var records = states.size.toLong + commands + children.size
    var depth = 1
    // Each selected child produces an actual status operand. Charge before selected-vector
    // construction/admission, not only once for each join command or child slot.
    states.foreach: state =>
      state.commands.foreach:
        case join: FsmDefinitionJoin[S] =>
          records += (if join.child.nonEmpty then 1 else state.childSlots.size)
          if records > FlatFsmRuntime.MaximumRecords then
            fail("composition exceeds 65536 aggregate records including join operands")
        case _ => ()
    children.foreach: child =>
      val cost = child.definition.compositionCost
      occurrences += cost.occurrences
      records += cost.records
      depth = depth.max(cost.depth + 1)
      if occurrences > 4096 || records > FlatFsmRuntime.MaximumRecords || depth > 128 then
        fail("composition exceeds 4096 occurrences, depth 128 or 65536 aggregate records")
    if children.nonEmpty && records > FlatFsmRuntime.MaximumRecords then
      fail("composition exceeds 65536 aggregate records")
    FsmCompositionCost(occurrences.toInt, records.toInt, depth, states.exists(_.isTerminal))

  def definitionHash[S](
      owner: FsmDefinition[S],
      initial: String,
      fragments: Vector[FsmFragment[S]],
      evidence: HwEnum[S],
      stack: Option[FsmStackConfig[S]] = None
  ): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    def atom(value: String): Unit =
      val bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array())
      digest.update(bytes)
    val canonical = DigitalEnumContract.abi(owner.canonicalType)
    def caseId(value: S): String = DigitalEnumContract.selectedCase(value, evidence, canonical)
    atom("fsm-definition-v1")
    atom(owner.name)
    atom(canonical.hash)
    atom(initial)
    stack.foreach: config =>
      val value = FsmStackRuntime.contract(config, caseId)
      atom("bounded-stack"); atom(value.capacity.toString)
      Vector(value.overflow, value.underflow, value.invalidFrame).foreach: policy =>
        atom(if policy.isEmpty then "assert_hold" else "trap"); atom(policy.getOrElse(""))
    fragments.foreach: fragment =>
      atom("fragment"); atom(fragment.name)
      fragment.states.foreach: state =>
        atom("state"); atom(caseId(state.value)); atom(state.isTerminal.toString)
        state.childSlots.foreach: child =>
          atom("child"); atom(child.name); atom(child.definition.semanticHash)
        state.commands.foreach:
          case edge: FsmDefinitionEdge[S] =>
            atom("edge"); atom(edge.guard.name); atom(edge.mode.toString);
            atom(caseId(edge.destination))
            atom(edge.action.map(_.name).getOrElse(""))
          case call: FsmDefinitionCall[S] =>
            atom("call"); atom(call.guard.name); atom(call.mode.toString)
            atom(caseId(call.entry)); atom(caseId(call.resume))
          case returning: FsmDefinitionReturn[S] =>
            atom("return"); atom(returning.guard.name); atom(returning.mode.toString)
          case action: FsmDefinitionAction[S] =>
            atom("action"); atom(action.phase); atom(action.slot.name)
          case timer: FsmDefinitionTimer[S] =>
            atom("timer"); atom(timer.cycles.toString); atom(timer.mode.toString);
            atom(caseId(timer.destination))
          case transfer: FsmDefinitionTransfer[S] =>
            atom("transfer"); atom(transfer.slot.guard.name); atom(transfer.mode.toString);
            atom(caseId(transfer.destination))
          case join: FsmDefinitionJoin[S] =>
            atom("join"); atom(join.policy); atom(join.child.map(_.name).getOrElse(""))
            atom(join.mode.toString); atom(caseId(join.destination))
    digest.digest().map(value => f"${value & 0xff}%02x").mkString

  def validateBindings[S](spec: FsmInstanceSpec[S]): Unit =
    val bindings = spec.bindings
    if bindings == null then fail("instance requires explicit bindings")
    if bindings.guards.map(_.slot).toSet != spec.definition.guards.toSet ||
      bindings.actions.map(_.slot).toSet != spec.definition.actions.toSet ||
      bindings.children.map(_.slot).toSet != spec.definition.children.toSet
    then
      fail(
        "instance must bind every referenced slot exactly once without foreign or unused bindings"
      )
    val _ = FlatFsmRuntime.localCodes(spec.encoding, spec.custom, spec.definition.evidence)

/** One ephemeral construction plan; authoritative captured machines remain in Session.flatFsms. */
private[nodal] final class FsmOccurrence[S](val spec: FsmInstanceSpec[S]):
  var builder: Option[FsmBuilder[S]] = None
  var children = Vector.empty[FsmChildOccurrence[S, ?]]

private[nodal] final case class FsmChildOccurrence[P, C](
    slot: FsmChild[P, C],
    occurrence: FsmOccurrence[C]
)
