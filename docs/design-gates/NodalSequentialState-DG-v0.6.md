# NodalSequentialState-DG v0.6 — explicit lifecycle and contextual reset intent

**Status:** Approved implementation contract; implementation and focused validation pending
**Scope:** public-api and compiler-boundary; next F-058.B.3 slices, not completion of B.3
**Baseline:** first immutable-definition checkpoint `bcb20291f06f4d86044157deb3d753fd6d1207e4`

## Authority, compatibility and owners

The Foundation implementation authority and prototype process recorded in
[increment 58 readiness](../implementation/increment58-readiness.md),
[ADR0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md) and
[v0.5](NodalSequentialState-DG-v0.5.md) authorize these necessary additive
execution contracts. This is an implementation selection, not a claim that the
owner individually chose these spellings. Implementation and validation remain
open until the corresponding production paths and focused witnesses exist.

Keep ordinary `fsm(...): Unit`, `FsmDefinition`, immutable `FsmDef`/fragments,
explicit slot bindings and physical `flat-fsm-v1` behavior unchanged. There is
no hidden boot state in an ordinary machine. Explicitly controlled instances
have an additional, visible lifecycle state; their dormant/start behavior is
opt-in. The existing flat-FSM owner remains the sole state/transition/action
executor. ConstructionSession owns capture, the existing sequential owner owns
updates, DomainModel/HierarchyModel own occurrence admission, and CIRCT target
variants own material clock/reset substitution. No second scheduler, mutable
definition registry, synthetic local binding or state-product flattening is
introduced.

## Selected controlled-instance API

```scala
FsmControl.apply(restart: Expr[Bool], cancel: Expr[Bool],
    enable: Expr[Bool]): FsmControl

enum FsmStatus:
  case Dormant, Running, Completed, Cancelled

FsmDef[S].instantiateControlled(bindings: FsmBindings[S], control: FsmControl,
    encoding: FsmEncoding = FsmEncoding.Compact,
    illegalState: IllegalStatePolicy = IllegalStatePolicy.Error)(
    observe: FsmRun[S] => Unit): Unit
FsmDef[S].instantiateControlled(bindings: FsmBindings[S], control: FsmControl,
    encoding: FsmLocalEncoding[S])(
    observe: FsmRun[S] => Unit): Unit
FsmDef[S].instantiateControlled(bindings: FsmBindings[S], control: FsmControl,
    encoding: FsmLocalEncoding[S], illegalState: IllegalStatePolicy)(
    observe: FsmRun[S] => Unit): Unit

FsmRun[S].state: FsmInstance[S]
FsmRun[S].status: DecodedEnum[Expr[EnumValue[FsmStatus]]]
FsmRun[S].running: Expr[Bool]
FsmRun[S].completed: Expr[Bool]
FsmRun[S].cancelled: Expr[Bool]
```

`FsmStatus` has explicit canonical two-bit codes 0, 1, 2, 3 in the displayed
order, supplied through the existing enum owner. A result is typed hardware,
not a host flag or mutable shared variable. `state` and all result observations
retain the existing instance lexical lifetime. Controlled `state.completed`
agrees with the valid Completed lifecycle result, not mere terminal membership
while Dormant. No runtime control or callback is retained by the immutable graph.
Controls are explicit instance bindings, validated for exact Bool, ownership,
domain sampling and effect context through existing owners.

## Edge semantics and action ordering

Every machine has its own real local-state register and controlled instances
also have their own two-bit lifecycle register. Completion and cancellation
remain latched until a restart or reset. All conditions sample pre-edge values.

Priority is reset, cancel of a Running instance, restart, then enabled normal
execution. A cancel request while not Running is a no-op and does not block a
restart. `enable = false` pauses normal transitions and active actions; it does
not block reset, restart or cancellation.

- Reset sets local state directly to initial and status Dormant. It runs no
  ordinary entry, active, exit or transition action.
- When restart wins the stated priority, it selects initial local state and runs that state's
  entry actions once. It runs no old-state exit or ordinary active/transition
  action. Status becomes Running, or Completed if initial itself is terminal.
  In a resetless domain, deterministic establishment requires `restart = true`
  and `cancel = false`; the old unspecified lifecycle may encode Running, so a
  simultaneous cancel can win. The restart path does not inspect unspecified
  old state to run exits.
- Cancel of Running suppresses active/transition/entry actions, executes current
  state exit actions once, retains the last local state and sets Cancelled.
- Normal enabled Running execution uses the existing selected-transition proof
  and phase order: active, exit, transition, entry. Entering a terminal state
  sets Completed on that edge. Completed does not keep executing terminal active
  actions. Ordinary `flat-fsm-v1` terminal behavior is not changed.
- Dormant, Completed and Cancelled hold until the controls above act. A resetless
  instance is deliberately unspecified until an accepted explicit restart; the contract
  does not fabricate power-up initialization.

`transitionTaken` denotes only an ordinary selected graph edge. `entering` also
covers restart into initial; `exiting` also covers cancellation from current.
Restart has no exiting event. Reset has neither event. Separate native event
results implement these distinctions; a cancellation is not misrepresented as
an ordinary self-transition.

Error assertions apply to operational state, not deliberately dormant or
uninitialized storage. Restart/reset can establish a legal activation without
asserting about old local state. Recovery from an invalid operational local code
selects initial, with Running or Completed selected from initial membership,
and runs no ordinary action for the invalid old code. Completed must be backed
by a valid terminal local state. Controls, status, valid membership and actual
phase dependencies participate in the same finite sampling proof.

## Canonical controlled native ingress

Extend the existing operation with `contract_version = "flat-fsm-v2"`; preserve
v1's exact two-result shape. V2 has operands in this closed order:

1. private local-state register;
2. private two-bit lifecycle register;
3. restart, cancel and enable, each exact one-bit Bool carrier;
4. the existing ordered transition guards;
5. the existing isolated action captures.

V2 results are next local state, ordinary transition-taken, entering-event and
exiting-event. Its action region prefix remains current local state, effective
next local state and ordinary transition-taken; no callback grammar is changed.
Header/graph/storage/domain/region verification must independently enforce
exact operand and result shapes, two distinct private registers, unique owned
storage and exact lifecycle initialization. The lifecycle code contract is
fixed, not arbitrary per-instance encoding. The existing graph and local-state
encoding checks are reused.

Expansion writes both registers through one existing machine root, gates all
phase controls and illegal-state assertions, and retains action order. Preflight
uses a finite checked structural upper bound for extra operations and captures
before allocation, with independent per-operation charging. This is a resource
bound, not an exact or minimal count; v1 retains its existing exact preflight.
Source and native dependency queries include controls and lifecycle state. Independent
machines still cannot silently acquire multiple-driver permission by claiming
mutually exclusive lifecycle states.

## Required-domain FSMs and contextual initialization

A reusable definition must not choose material semantics from the first child
instance. Add declared `initializer_policy = "if_reset"` to the shared native
register/update semantics for an exact unresolved local requirement. At source,
only the FSM owner requests this policy in this stage. It retains the exact
initial literal/type/ownership, not an invented physical reset. An ordinary
initialized Reg without this policy continues to require reset in every actual
occurrence; ordinary uninitialized Reg remains uninitialized.

The shared domain-control inspection returns either a verified actual local
binding or an exact local unresolved requirement. In the latter case reset
availability is Contextual, never inferred as absent from a missing projection.
Mandatory whole-root occurrence proof checks every actual use. There is no
permission-bearing metadata shortcut and no standalone unresolved acceptance.

Pin the normalized formal forms as follows:

- Conditional-initialization `sequential_update`: four operands
  `[state, next, exact local requirement Clock, initial literal]`,
  `initializer_policy = "if_reset"`, and no material edge/reset-policy/polarity.
  Existing uninitialized three-operand and mandatory-initialization five-operand
  forms retain their existing semantics.
- Formal `reset_asserted`: zero operands, exact local requirement identity,
  absent reset polarity. The physical form remains one exact Reset operand and
  explicit polarity. A formal query evaluates false only in an actually proved
  resetless occurrence; it does not manufacture a Clock input by itself.
- Formal `sequential_assert`: Bool plus the exact local requirement Clock,
  absent edge. The physical form retains its exact bound Clock and edge.

Target demand planning includes contextual reset availability/material policy,
even when there is no explicit RequirementReset projection. Variants partition
materially different reset-bearing/resetless bindings. A reset-bearing variant
gets the actual initial value and reset timing; a resetless variant gets no
initialization. The canonical dependency owner retains actual reset roots when
present. Shared SequentialModel/HierarchyModel/target owners implement these
forms; FlatFSM consumes their verified view instead of duplicating resolution.

## Composition sequence and required remaining work

This revision establishes the lifecycle substrate, not a completed nested or
parallel API. The next composition slice must add immutable typed child-instance
and join descriptors with explicit child bindings. ConstructionSession will
allocate the bounded occurrence inventory before capturing linked controls,
within one transaction, rather than permit arbitrary nested live builders.
Each occurrence keeps separate real storage. Completion reads registered child
status; parent exit cancels unfinished children. All/Any and ordered-winner
joins must explicitly define ties, losing-branch cancellation and reactivation.
A self-transition can restart a child without generating simultaneous leave
cancellation for the same owner state. No source-side Cartesian product is
allowed.

Before admitting composition, native topology verification must retain real
parent/child identity and independently check completion/cancellation controls;
metadata labels alone are insufficient. Dependency ordering must distinguish
current completion from future activation so native SSA remains acyclic.
Definition/fragment/slot identities, actual bindings and topology/encoding/report
metadata remain required. Structural cycle/depth/occurrence bounds, timers and
protocol waits, explicit bounded call/return stacks and overflow policy remain
open B.3 work. Structural terminal reachability must not be described as a proof
of environmental liveness.

## Focused acceptance

Require source/bridge/native positives and forged-ingress negatives, unchanged
v1 predecessors, independent controlled reuse, dormant/pause/restart/cancel,
reset/control/transition ties, resetless restart with every possible old status
and both cancel values, initial-terminal completion, action counts and
ordering, lifecycle observation escape and illegal-state behavior. Include a
transactional below-bound expansion-budget rejection and representative actual
generated-operation counts. Actual HDL
replay must demonstrate those controls with distinct causal values, not only
normalized attributes.

Required-domain evidence includes one identical generic FSM definition used in
reset-bearing and resetless occurrences, opposite edges/polarities where
supported, independent live state, exact forwarded domain identity and rejection
of an ordinary initialized Reg in a resetless occurrence. Inspect actual target
register reset presence and clock/reset operands. Record the verified source,
commands, results and limitations in the existing increment readiness note.
