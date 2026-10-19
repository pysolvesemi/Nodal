# NodalSequentialState-DG v0.2 — exhaustive actions and flat FSMs

**Status:** Implemented; focused source, frontend, bridge and native checks pass
**Scope:** public-api
**Baseline:** `18acaa6b1443607908babfbc6f4fec28999062c4`

## Authority and boundary

The owner's Foundation-only implementation and public publication authority quoted
in [v0.1](NodalSequentialState-DG-v0.1.md#authority-and-original-scope) covers these
necessary F56 additions. This records the selected contract, not a claim that the
owner separately selected each signature. The prototype policy applies.

Preserve v0.1 register/domain/reset/behavioral semantics and the canonical external
enum ABI. This revision supplies exhaustive statement switching and flat FSM
construction, actions, observations, encoding and illegal-state behavior. It does
not close F56. Real typed memory ports and approved CIRCT lowering remain required;
full F57/F58 hierarchy, CDC/reset controllers and timed/reusable statecharts retain
their original owners.

## Additive public surface and compatibility

Preserve existing `fsm(...): Unit`, `StateBuilder.on(...)(to): Unit`, their defaults,
and `FsmBuilder.encoding: FsmEncoding`. Add the following source forms, compile
probed with pinned Scala 3.8.4:

```scala
final class EnumSwitchBuilder[S] private[nodal] (...):
  def is(value: S)(body: => Unit): Unit

def switchEnum[S](selector: Expr[EnumValue[S]])(invalid: => Unit)(
    body: EnumSwitchBuilder[S] => Unit
)(using HwEnum[S]): Unit

object FsmDefinition:
  def apply[S](name: String, canonicalType: DataType[EnumValue[S]])(
      using HwEnum[S]
  ): FsmDefinition[S]

final class FsmLocalEncoding[S] private[nodal] (...)
object FsmLocalEncoding:
  def custom[S](width: Int, encoding: EnumEncoding[S])(
      using HwEnum[S]
  ): FsmLocalEncoding[S]

def fsm[S](definition: FsmDefinition[S], initial: S, encoding: FsmLocalEncoding[S])(
    body: FsmBuilder[S] => Unit
)(using HwEnum[S]): Unit

def fsm[S](definition: FsmDefinition[S], initial: S, encoding: FsmLocalEncoding[S],
    illegalState: IllegalStatePolicy)(body: FsmBuilder[S] => Unit)(using HwEnum[S]): Unit

// Add to StateBuilder[S], leaving on unchanged:
def onWithAction(condition: Expr[Bool], mode: TransitionMode = TransitionMode.Exclusive)(
    to: S
)(body: => Unit): Unit

// Add to FsmBuilder[S]:
def currentState: DecodedEnum[Expr[EnumValue[S]]]
def nextState: DecodedEnum[Expr[EnumValue[S]]]
def entering(state: S): Expr[Bool]
def exiting(state: S): Expr[Bool]
def transitionTaken: Expr[Bool]
def completed: Expr[Bool]
```

The two new `fsm` overloads have no defaults; the short form selects Error.
Append zero-argument `FsmEncoding.Custom` after the existing four cases. Their
ordinals remain stable and the encoding property truthfully describes a custom
request. Exhaustive host matches over the old four cases need a Custom arm;
this is an explicit source compatibility change, not full source compatibility.
Bare Custom without a typed map rejects. An explicit null in the four-argument
overload can become ambiguous; null is not a supported hardware configuration.

The existing `FsmDefinition(name)` chooses the standard sequential canonical enum
ABI. The explicit type overload retains its exact ABI; competing canonical types
reject. Local maps reuse the shared complete enum-case/code validator without
creating or registering a second canonical ABI. Require complete belonging cases,
unique nonnegative exact-width codes and bounded metadata.

The scoped switch keeps actions at exact owned methods, unlike tuple/vararg
callbacks that need additional container/alias recognition. Example:

```scala
switchEnum(state)(invalid = { state := initial }): cases =>
  cases.is(Mode.Idle):
    state := next
  cases.is(Mode.Run):
    ()
```

All legal cases are present exactly once, regardless of the explicit invalid
body. The selector retains its actual enum identity/ABI. This is a statement
switch; expression-valued `selectEnum` is unchanged. Missing assignments hold
registers. Invalid physical encodings reach the explicit invalid body, never a
Scala MatchError or a fabricated legal enum value.

## Construction, action ownership and lifetime

Graph-builder callbacks are ordinary typed construction, so actual outer wiring
such as `output := builder.completed` remains possible. Only entry/active/exit,
transition action, switch case and invalid bodies enter the existing checked
Sequential effect profile and callback carrier. Public action entrypoints fail
closed without frontend capture. Transition condition/mode/destination and
erased eager arguments use exact-symbol Pure validation, including `on` without
an action callback; helper calls retain checked transitive dependencies. Runtime
checks builder owner/open lifetime and
the shared kernel attestation before executing an action callback. The frontend
rejects authored calls to its private runtime entrypoints; native validation is
independent. Do not create a second effect grammar or value graph.

Every enum case has one state declaration, including exactly one initial case.
State/transition/action declaration order is retained. Builder handles and scoped
observations cannot be newly consumed after closure. Deferred resolution at the
final snapshot does not invalidate output wiring performed while the builder was
open. Conditions and action RHS values use actual typed SSA, not source strings.

Current observations use actual stored local bits. Action regions have an exact
context prefix `(currentLocal: Bits<W>, nextLocal: Bits<W>, taken: Bool)` followed
by explicit ordinary captures. Next/event observations in actions use those
arguments, avoiding a result-to-own-region SSA cycle. Next/event feedback into
transition selection rejects. Graph observations may drive ordinary outer output
wiring through the existing assignment owner.

## Native ingress and shared normalization

Use a distinct executable `nodal.flat_fsm` contract, not the legacy F19 condition
and effect strings. Its actual operands are private local Bits state, ordered Bool
transition guards and flattened action captures. Results are effective next-local
Bits and transition-taken Bool. Closed metadata retains machine/source identity,
actual enum/domain, initial case, requested/resolved encoding, exact ordered case
and code inventory, state/terminal inventory, transition endpoints/mode/order and
action phase/owner/order/region identity with capture segments. Each action is an
isolated single-block region with the checked context prefix and exact captures.

A narrow typed `nodal.enum_switch` ingress retains the actual selector, exact enum,
complete unique legal-case inventory, explicit invalid region and capture edges.
Verify these independently before normalizing to the existing behavioral root;
do not erase exhaustive-switch obligations at the bridge boundary.

The FSM owner validates graph/encoding/regions and builds one sequential root,
then delegates write accumulation, reset/hold/priority and transactional commit to
SequentialModel. The private state belongs to this root even without actions;
external or action writes to the private handle reject. Canonical observations
reuse balanced ordinary equality/select/enum-literal expressions; no second enum
conversion engine is needed. Completion resolves from the finalized terminal
inventory as current-valid AND terminal membership.

Dependency facts for next/taken derive from state, guards and physical reset,
never from action captures. Action context facts resolve to these real inputs,
not the owning operation's results. Existing region/value/type/scope verifiers
must explicitly admit verified action regions. Unknown effects, including memory
until its real owner integrates, reject rather than entering a pure whitelist.

## Edge, reset, action and observation semantics

Guards read pre-edge values. A selected edge executes active, selected source exit,
selected transition, then destination entry actions, retaining source order within
each phase. Last write wins only within this single root. A selected self-edge
runs exit/transition/entry. When no transition is selected, active actions run
and local state holds.
Unrelated roots or machines writing the same register reject.

Reset goes directly to initial without entry/exit/active/transition actions or
hidden boot state. Initialized action registers retain their own reset values;
uninitialized action registers hold while FSM actions are suppressed during reset.
Use a narrowly checked `reset_asserted` on the actual bound Reset/polarity, never
initializer presence or a dummy Bool. Resetless machines use uninitialized local
state; initial is their explicit recovery target, not a power-up promise.

`currentState` is actual stored state. `nextState` includes reset dominance.
Entering/exiting/taken are current-edge intents, false during reset and recovery,
not hidden history pulses; a selected self-edge asserts all applicable intents.
Completion is a valid terminal-state level. Invalid local codes safe-decode to
the initial canonical value with valid=false, which must not assert completion.

## Graph, encoding, illegal state and bounded profile

Reachability means declared structural graph reachability from initial, not a
runtime-feasibility/SAT claim. Unknown destinations, missing/duplicate states,
unreachable declarations, nonterminal dead ends and terminal outgoing edges reject.
Each state's transitions use one mode. Exclusive uses conservative actual guard
facts; unknown overlap rejects with Priority guidance. Priority selects the first
true transition in authored order. No default parameter chooses a transition.

Compact follows enum case order. OneHot uses one bit per case. Gray uses reflected
codes and requires every declared reachable non-self edge to change one bit;
incompatible graphs reject, with no embedding-search promise. Auto records named
policy `auto-compact-v1` and resolved codes, independent of installed tools.
Custom preserves the complete supplied local map and external canonical ABI.

RecoverToInitial chooses initial and suppresses ordinary actions and transition
events on invalid local code. Error holds state and retains an actual owning-clock
sampled validity assertion on every non-reset active edge. The assertion has real
property/Clock/edge/source operands and metadata; no metadata-only error policy,
reset-seen latch, hidden boot or implicit enable. Downstream assertion failure is
not a promise of process termination. Future enable semantics require an explicit
contract rather than silently masking invalid held state.

At most 4096 states, 65536 transitions/actions combined and local width 4096 are
admitted. Reuse the enum code metadata ceiling of 16,777,216 bits; check code
carriers/tokens before widening or shifting. Existing 65,536 operation/dependency
proof-work and 262,144 normalized-operation limits remain authoritative and may
reject earlier. Each Exclusive guard comparison consumes the bounded proof budget.
There are at most 4095 ordinary captures per FSM action (three context arguments
within the existing 4098 transport bound), plus the native 8192 aggregate capture
limit. Preflight real table/expansion cost, use balanced selection trees, and never
enumerate the encoding space. No whole-pipeline linearity claim is made.

## Alternatives, focused checks and remaining scope

Rejected alternatives: changing frozen Unit returns; disguising Custom as Auto;
manufacturing a local canonical ABI; source-only switch exhaustiveness; parsing
F19 strings; separate action/write scheduling; hidden reset entry/boot behavior;
unbounded pairwise exclusivity or encoding-space expansion; metadata-only Error.

Focused checks must cover actual public source through bridge/native, canonical
ABI versus every local encoding, manual enum state and exhaustive switches,
complete/duplicate/foreign cases, builder/observation escape, callback forgery and
separate-library proofs, action ordering/self-edge/hold/reset/uninitialized state,
exclusive overlap versus priority, graph diagnostics, invalid recovery/Error,
malformed actual region/capture/guard/domain edges, transactional normalization,
deterministic bounded many-state witnesses and affected v0.1/F54/F55 predecessors.
Pinned target probes establish feasibility only until the actual Nodal/CIRCT pass
and its focused cases execute. Memory-port semantics and approved target lowering
receive their precise subsequent revision; full F56 remains open.

## Focused implementation acceptance

The integrated checkpoint passes the maintained 83-case source/bridge and affected
predecessor selection, 17 selected identity/discovery predecessors, the public
flat-FSM frontend harness (23 exact negatives plus positive, separate-JAR,
replacement, no-plugin and runtime controls), 59 native FSM and 71 sequential
cases. Actual public witnesses normalize/reparse and the approved fixed-scalar
subset exports through the v0.3 CIRCT adapter. Details, causal repairs, resource
limits and final artifact commands are in
[increment56 readiness](../implementation/increment56-readiness.md#flat-fsm-and-fixed-scalar-target-integration).
This completes this bounded contract, not the remaining memory-port or full F56
acceptance.
