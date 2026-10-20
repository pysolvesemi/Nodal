package nodal

/** A typed runtime predicate slot. A reusable definition contains the slot, never an Expr. */
final class FsmGuard[S] private[nodal] (
    private[nodal] val owner: FsmDefinition[S],
    val name: String
):
  def bind(value: Expr[Bool]): FsmGuardBinding[S] =
    FsmDefinitionRuntime.guardBinding(this, value)

object FsmGuard:
  def apply[S](owner: FsmDefinition[S], name: String): FsmGuard[S] =
    FsmDefinitionRuntime.slot(owner, name)
    new FsmGuard(owner, name)

/** A typed action slot. Checked callbacks belong to instance bindings, not the definition graph. */
final class FsmAction[S] private[nodal] (
    private[nodal] val owner: FsmDefinition[S],
    val name: String
):
  @scala.annotation.compileTimeOnly(
    "NODAL-SEQUENTIAL-056-MISSING: action binding requires the Nodal compiler plugin"
  )
  def bind(body: => Unit): FsmActionBinding[S] =
    val _ = () => body
    FlatFsmRuntime.unchecked(() => ())

object FsmAction:
  def apply[S](owner: FsmDefinition[S], name: String): FsmAction[S] =
    FsmDefinitionRuntime.slot(owner, name)
    new FsmAction(owner, name)

final class FsmGuardBinding[S] private[nodal] (
    private[nodal] val slot: FsmGuard[S],
    private[nodal] val value: Expr[Bool]
)

final class FsmActionBinding[S] private[nodal] (
    private[nodal] val slot: FsmAction[S],
    private[nodal] val body: () => Unit
)

/** Immutable explicit bindings. Completeness and exact slot ownership are checked before capture.
  */
final class FsmBindings[S] private[nodal] (
    private[nodal] val guards: Vector[FsmGuardBinding[S]],
    private[nodal] val actions: Vector[FsmActionBinding[S]]
)

object FsmBindings:
  def apply[S](guards: Seq[FsmGuardBinding[S]]): FsmBindings[S] =
    FsmDefinitionRuntime.bindings(guards, Vector.empty)

  def apply[S](
      guards: Seq[FsmGuardBinding[S]],
      actions: Seq[FsmActionBinding[S]]
  ): FsmBindings[S] =
    FsmDefinitionRuntime.bindings(guards, actions)

/** An immutable state declaration. Every fluent operation returns a new declaration. */
final class FsmState[S] private[nodal] (
    val value: S,
    private[nodal] val commands: Vector[FsmDefinitionCommand[S]],
    private[nodal] val isTerminal: Boolean
):
  def on(guard: FsmGuard[S], mode: TransitionMode = TransitionMode.Exclusive)(to: S): FsmState[S] =
    FsmDefinitionRuntime.edge(this, guard, mode, to, None)

  def onWithAction(
      guard: FsmGuard[S],
      action: FsmAction[S],
      mode: TransitionMode = TransitionMode.Exclusive
  )(to: S): FsmState[S] =
    FsmDefinitionRuntime.edge(this, guard, mode, to, Some(action))

  def entry(action: FsmAction[S]): FsmState[S] =
    FsmDefinitionRuntime.stateAction(this, "entry", action)

  def active(action: FsmAction[S]): FsmState[S] =
    FsmDefinitionRuntime.stateAction(this, "active", action)

  def exit(action: FsmAction[S]): FsmState[S] =
    FsmDefinitionRuntime.stateAction(this, "exit", action)

  def terminal(): FsmState[S] =
    if isTerminal then FsmDefinitionRuntime.fail("state repeats terminal declaration")
    new FsmState(value, commands, true)

object FsmState:
  def apply[S](value: S): FsmState[S] = new FsmState(value, Vector.empty, false)

/** A frozen, reusable contribution. A fragment may be partial; its complete definition may not. */
final class FsmFragment[S] private[nodal] (
    val name: String,
    val states: Vector[FsmState[S]]
)

object FsmFragment:
  def apply[S](name: String)(states: FsmState[S]*): FsmFragment[S] =
    FsmDefinitionRuntime.fragment(name, states)

/** Read-only observations valid only inside the instance's construction callback. */
final class FsmInstance[S] private[nodal] (private val builder: FsmBuilder[S]):
  def currentState: DecodedEnum[Expr[EnumValue[S]]] = builder.currentState
  def nextState: DecodedEnum[Expr[EnumValue[S]]] = builder.nextState
  def entering(state: S): Expr[Bool] = builder.entering(state)
  def exiting(state: S): Expr[Bool] = builder.exiting(state)
  def transitionTaken: Expr[Bool] = builder.transitionTaken
  def completed: Expr[Bool] = builder.completed

/** Explicit per-instance controls. Restart/cancel are independent of normal execution enable. */
final class FsmControl private[nodal] (
    private[nodal] val restart: Expr[Bool],
    private[nodal] val cancel: Expr[Bool],
    private[nodal] val enable: Expr[Bool]
)

object FsmControl:
  def apply(restart: Expr[Bool], cancel: Expr[Bool], enable: Expr[Bool]): FsmControl =
    FsmDefinitionRuntime.control(restart, cancel, enable)

/** Fixed canonical lifecycle ABI. This is independent of the machine's local state encoding. */
enum FsmStatus:
  case Dormant, Running, Completed, Cancelled

object FsmStatus:
  given HwEnum[FsmStatus] = HwEnum.fromDeclaration(EnumDeclaration(
    "nodal.FsmStatus",
    Vector(
      "Dormant" -> Dormant,
      "Running" -> Running,
      "Completed" -> Completed,
      "Cancelled" -> Cancelled
    ).map((name, value) =>
      EnumDeclaredCase(s"nodal.FsmStatus.$name", name, value, None)
    ),
    None
  ))

/** Read-only controlled observations with the same lexical lifetime as the machine instance. */
final class FsmRun[S] private[nodal] (private val builder: FsmBuilder[S]):
  val state: FsmInstance[S] = new FsmInstance(builder)
  def status: DecodedEnum[Expr[EnumValue[FsmStatus]]] = ConstructionKernel.flatFsmStatus(builder)
  def running: Expr[Bool] = status.value.isOneOf(FsmStatus.Running)
  def completed: Expr[Bool] = state.completed
  def cancelled: Expr[Bool] = status.value.isOneOf(FsmStatus.Cancelled)

/** Reusable control graph. It retains only enum cases, immutable declarations and typed slots. */
final class FsmDef[S] private[nodal] (
    val definition: FsmDefinition[S],
    val initial: S,
    val fragments: Vector[FsmFragment[S]],
    private[nodal] val guards: Vector[FsmGuard[S]],
    private[nodal] val actions: Vector[FsmAction[S]],
    private[nodal] val evidence: HwEnum[S]
):
  def instantiate(
      bindings: FsmBindings[S],
      encoding: FsmEncoding = FsmEncoding.Compact,
      illegalState: IllegalStatePolicy = IllegalStatePolicy.Error
  )(observe: FsmInstance[S] => Unit): Unit =
    FsmDefinitionRuntime.instantiate(this, bindings, encoding, None, illegalState, observe)

  def instantiate(bindings: FsmBindings[S], encoding: FsmLocalEncoding[S])(
      observe: FsmInstance[S] => Unit
  ): Unit =
    instantiate(bindings, encoding, IllegalStatePolicy.Error)(observe)

  def instantiate(
      bindings: FsmBindings[S],
      encoding: FsmLocalEncoding[S],
      illegalState: IllegalStatePolicy
  )(observe: FsmInstance[S] => Unit): Unit =
    FsmDefinitionRuntime.instantiate(
      this,
      bindings,
      FsmEncoding.Custom,
      Some(encoding),
      illegalState,
      observe
    )

  def instantiateControlled(
      bindings: FsmBindings[S],
      control: FsmControl,
      encoding: FsmEncoding = FsmEncoding.Compact,
      illegalState: IllegalStatePolicy = IllegalStatePolicy.Error
  )(observe: FsmRun[S] => Unit): Unit =
    FsmDefinitionRuntime.instantiateControlled(
      this,
      bindings,
      control,
      encoding,
      None,
      illegalState,
      observe
    )

  def instantiateControlled(
      bindings: FsmBindings[S],
      control: FsmControl,
      encoding: FsmLocalEncoding[S]
  )(observe: FsmRun[S] => Unit): Unit =
    instantiateControlled(bindings, control, encoding, IllegalStatePolicy.Error)(observe)

  def instantiateControlled(
      bindings: FsmBindings[S],
      control: FsmControl,
      encoding: FsmLocalEncoding[S],
      illegalState: IllegalStatePolicy
  )(observe: FsmRun[S] => Unit): Unit =
    FsmDefinitionRuntime.instantiateControlled(
      this,
      bindings,
      control,
      FsmEncoding.Custom,
      Some(encoding),
      illegalState,
      observe
    )

object FsmDef:
  def apply[S](definition: FsmDefinition[S], initial: S)(fragments: FsmFragment[S]*)(using
      evidence: HwEnum[S]
  ): FsmDef[S] =
    FsmDefinitionRuntime.definition(definition, initial, fragments, evidence)
