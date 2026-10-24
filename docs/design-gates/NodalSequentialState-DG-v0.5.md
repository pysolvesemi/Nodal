# NodalSequentialState-DG v0.5 — immutable reusable graph bindings

**Status:** Approved implementation contract; focused immutable-slice validation passed
**Scope:** public-api; first F-058.B.3 checkpoint, not completion of B.3
**Baseline:** `9f6d1a57652312f568398b41dcfb9a6779144232`

## Authority and boundary

The Foundation implementation authority and prototype process recorded in
[increment 58 readiness](../implementation/increment58-readiness.md) cover this
necessary additive implementation of the approved
[statechart architecture](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md).
This selects concrete signatures, not a claim of separate owner approval of each
spelling. Preserve [v0.2](NodalSequentialState-DG-v0.2.md) flat execution and
all existing `FsmDefinition`, `fsm(...): Unit`, encoding and action contracts.

This checkpoint supplies a real immutable reusable graph, reusable fragments,
typed explicit predicate/action bindings, independent instances and scoped
read-only observations through the existing executable flat-FSM pipeline.
Nested/parallel lifecycle, typed completion/cancellation results, timers,
protocol helpers, structural recursion, bounded call stacks and their dedicated
native report/proof contracts remain required open F-058.B.3 work.

## Selected source surface

```scala
val owner = FsmDefinition[Mode]("transfer")
val start = FsmGuard(owner, "start")
val advance = FsmGuard(owner, "advance")
val tick = FsmAction(owner, "tick")

val graph = FsmDef(owner, Mode.Idle)(
  FsmFragment("work")(
    FsmState(Mode.Idle).on(start)(Mode.Run),
    FsmState(Mode.Run).active(tick).on(advance)(Mode.Done)
  ),
  FsmFragment("completion")(FsmState(Mode.Done).terminal())
)

// These are instance bindings, not values retained by graph.
val bindings = FsmBindings(
  guards = Seq(start.bind(request), advance.bind(finished)),
  actions = Seq(tick.bind { count := input })
)
graph.instantiate(bindings): instance =>
  completed := instance.completed
```

All types above are parameterized by the state enum `S`. Constructors are
private; public companion factories admit immutable records. `FsmState` fluent
operations return a new value; they never mutate a previous node:

```scala
FsmGuard.apply[S](owner: FsmDefinition[S], name: String): FsmGuard[S]
FsmAction.apply[S](owner: FsmDefinition[S], name: String): FsmAction[S]
FsmGuard[S].bind(value: Expr[Bool]): FsmGuardBinding[S]
FsmAction[S].bind(body: => Unit): FsmActionBinding[S]
FsmBindings.apply[S](guards: Seq[FsmGuardBinding[S]]): FsmBindings[S]
FsmBindings.apply[S](guards: Seq[FsmGuardBinding[S]],
    actions: Seq[FsmActionBinding[S]]): FsmBindings[S]

FsmState.apply[S](value: S): FsmState[S]
FsmState[S].on(guard: FsmGuard[S],
    mode: TransitionMode = TransitionMode.Exclusive)(to: S): FsmState[S]
FsmState[S].onWithAction(guard: FsmGuard[S], action: FsmAction[S],
    mode: TransitionMode = TransitionMode.Exclusive)(to: S): FsmState[S]
FsmState[S].entry(action: FsmAction[S]): FsmState[S]
FsmState[S].active(action: FsmAction[S]): FsmState[S]
FsmState[S].exit(action: FsmAction[S]): FsmState[S]
FsmState[S].terminal(): FsmState[S]
FsmFragment.apply[S](name: String)(states: FsmState[S]*): FsmFragment[S]
FsmDef.apply[S](definition: FsmDefinition[S], initial: S)(
    fragments: FsmFragment[S]*)(using HwEnum[S]): FsmDef[S]

FsmDef[S].instantiate(bindings: FsmBindings[S],
    encoding: FsmEncoding = FsmEncoding.Compact,
    illegalState: IllegalStatePolicy = IllegalStatePolicy.Error)(
    observe: FsmInstance[S] => Unit): Unit
FsmDef[S].instantiate(bindings: FsmBindings[S], encoding: FsmLocalEncoding[S])(
    observe: FsmInstance[S] => Unit): Unit
FsmDef[S].instantiate(bindings: FsmBindings[S], encoding: FsmLocalEncoding[S],
    illegalState: IllegalStatePolicy)(observe: FsmInstance[S] => Unit): Unit
```

`FsmInstance` exposes only `currentState`, `nextState`, `entering`, `exiting`,
`transitionTaken` and `completed`, with exactly the v0.2 return types and lexical
lifetime. It cannot append graph declarations. Its callback is construction
observation/wiring, not a stored graph or deferred hardware action.

## Immutability, binding and executable ownership

Definition records contain enum cases, immutable vectors, typed slot tokens and
static metadata only. They never retain an `Expr`, register, domain, live
builder, arbitrary configuration object or authored callback. This prevents
accidental dynamic graph capture structurally, including separately compiled
library definitions. It does not claim to prohibit arbitrary host side effects
in unrelated Scala code that prepares static declarations.

Each slot belongs to its exact `FsmDefinition` owner. All referenced slots must
have unique names across predicate and action kinds. Distinct same-named token
objects are not interchangeable. All and only referenced exact tokens must be
bound once per instance. Foreign, duplicate, extra and missing bindings reject
before instance construction. The binding collection owns its immutable vector
copies. The user may intentionally reuse a predicate binding; separate machine
actions still may not create unrelated drivers of the same target.

An action callback exists only inside the explicitly supplied action binding.
The frontend recognizes the exact `FsmAction.bind` method, applies the existing
Sequential effect validator and emits the existing private checked callback
carrier. Missing-plugin, raw-carrier and authored private-runtime calls fail
closed. Predicate binding operands use the existing Pure validator. Binding
construction inside a hardware action is not permitted.

Definition closure shares structural admission with `FlatFsmGraphContract`:
complete unique enum states, valid endpoints, one initial case, terminal/dead-end
rules, consistent transition modes and structural reachability. Actual guard
overlap, local encoding, lexical scope, write ownership and domain proofs remain
instance obligations of the existing capture/bridge/native owners.

Instantiation allocates fresh private state in the current supported domain,
replays ordered immutable declarations into `ConstructionSession` and then
permits scoped observations. Every bound action is captured through the existing
checked action region path. No alternate update scheduler or effect grammar is
introduced. Native output is the unchanged `flat-fsm-v1` executable contract;
native flat graph, operand, region, storage, reset and domain checks remain
independent. Legacy string-valued `nodal.fsm` descriptors are not used.

## Metadata, limits and compatibility

The current snapshot and native output retain each actual instance's state,
transition, action, capture, encoding, domain and source identities as in v0.2.
Definition names, fragment names and slot names remain available on immutable
source records. They are not yet a dedicated native definition/fragment report
or a completed statechart proof contract; that remains open with composition.

Reuse shares immutable vectors until instantiation. Each instance requires its
own real state and action regions. Definition admission is linear in declaration
count, except the existing bounded guard proof after runtime binding. It does
not enumerate encoded state space or a Cartesian product of machines.
Existing limits remain: at most 4096 states/fragments and 65536 transition/action
records per complete definition, bounded canonical names, existing instance,
capture, proof-work and normalized-operation ceilings. These are resource limits,
not authorization for arbitrary JVM recursion or unbounded hardware stacks.

Rejected alternatives: closure-backed definitions, reflection to discover dynamic
capture, string-keyed untyped runtime lookup, mutable fragment registries,
duplicated structural/guard engines, frontend-only guard proofs, and changing
the frozen `fsm` return type. Additive `instantiate` avoids overload/default
ambiguity with existing `fsm` source.

## Focused checks

Maintain public source and external-library compilation, missing-plugin and
forged-callback negatives, typed/foreign/missing/extra binding negatives,
immutable state/fragment reuse, two instances with independent guards/storage,
custom versus canonical encoding, scoped observation escape, graph rejection,
same-domain and foreign-domain behavior, source-to-bridge/native parity and
selected F56/F57 predecessors. Record actual executed results in the existing
increment readiness note; do not infer native execution or generated output
from source compilation.
