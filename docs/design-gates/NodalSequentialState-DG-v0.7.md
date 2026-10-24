# NodalSequentialState-DG v0.7 — bounded reusable child composition

**Status:** Implemented; focused prototype acceptance reconciled in F58 checkpoint 71
**Scope:** public-api and compiler-boundary; nested/parallel F-058.B.3 slice, not completion of B.3
**Baseline:** canonical native checkpoint `8753d4c1b5e717a4a0621f59e6e8647ae87326e0` and source checkpoint `d5dc7e84b672a15d1ce95e9f20fa4e89d5ec316c`; publication mapping in [increment 58 readiness](../implementation/increment58-readiness.md)

The standing Foundation implementation authority and prototype process in the readiness record, [ADR0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md), and [v0.6](NodalSequentialState-DG-v0.6.md) authorize these necessary additive contracts. This is an implementation selection, not a claim that the owner individually chose these spellings. Existing approved gates remain historical contracts. Implementation and acceptance remain open until the real source/native/target paths pass focused checks.

Current acceptance reconciliation: the bounded contract below is implemented by
the accepted lifecycle, composition and timing checkpoints. See the
[F58 B.3 crosswalk](../implementation/increment58-readiness.md#checkpoint-71--statechart-implementation-reconciliation)
for exact historical source/native/target boundaries and retained limitations.
This status update adds no API, production change or fresh execution claim.

## Selected ownership

One immutable definition graph owns typed child slots and state ownership. Runtime `FsmBindings` supplies all guard/action/child bindings. ConstructionSession owns one transactional finite occurrence plan, then each occurrence goes through the existing FlatFSM transition/action executor and shared sequential update/domain/target owners. No Cartesian product, child callback stored in a definition, second mutable registry, or first-occurrence material choice.

## Selected additive API

The signatures below extend the existing immutable definition/binding surface. A definition with child slots requires `instantiateControlled`; ordinary `instantiate` rejects it with that precise replacement guidance rather than introducing hidden boot behavior. A child slot belongs to its exact parent token and one state, even when two slots reuse the identical child definition. Names are bounded and unique across that definition's guard/action/child slots.

- `FsmChild[P,C](owner: FsmDefinition[P], name: String, definition: FsmDef[C])` is an immutable typed child slot.
- `FsmChild[P,C].bind(bindings: FsmBindings[C], encoding: FsmEncoding = Compact, illegalState: IllegalStatePolicy = Error): FsmChildBinding[P]`. A custom local-encoding overload follows the existing API.
- `FsmBindings[P] (guards, actions, children: Seq[FsmChildBinding[P]])` is a new overload; existing overloads remain unchanged.
- `FsmState[P].children(slots: FsmChild[P,?]*): FsmState[P]` returns a fresh declaration. Each slot occurs in exactly one nonterminal state of its parent definition.
- `FsmState[P].onAllComplete(mode: TransitionMode = Exclusive)(to: P)` and `onAnyComplete` add a structural join edge over that state's children.
- `FsmState[P].onChildComplete(child: FsmChild[P,?], mode: TransitionMode = Priority)(to: P)` expresses ordered winner branches through the existing source-order Priority owner. There is no new winner scheduler or implicit numeric priority. Distinct completion conditions may coincide; Exclusive must independently prove disjointness or reject, Priority picks first source ordinal. Same ordinal is malformed native input and rejects.
- A read-only `FsmRun[P].child[C](slot: FsmChild[P,C]): FsmChildRun[C]` carries an explicit `active: Expr[Bool]` ownership qualifier and the scoped child `run: FsmRun[C]`. It is scoped to the exact composition observation callback. It does not grant nested builder mutation. Missing/foreign/same-name-other-token slots reject.

These spellings are selected by this gate; source compilation remains an implementation obligation. Existing overloads keep their semantics and source compatibility. Every `children` operation returns a fresh state declaration and rejects duplicate ownership or a second child inventory for the same state.

## State and edge semantics

Every occurrence has separate local-state and lifecycle registers, including repeated uses of the identical child definition. Child completion queries read registered status only. Therefore a child completing on edge N can enable its parent's join on edge N+1, never the same edge. Parent join transitions are ordinary FlatFSM edges with the existing active/exit/transition/entry ordering.

The pure join owner reads child lifecycle registers only, not parent future state. A separate pure activation owner reads exact parent current local/status, effective next, taken, entered, exited, establishment, and enable. Exact native data operands link to real FlatFSM owners; names/metadata are not permissions. A composed-only additive flat-fsm-v3 form exposes a fifth canonical establishment result, computed by the existing FlatFSM owner as accepted restart OR operational illegal-state recovery, excluding reset. Recovery is not guessed from the v2 entering event. Ordinary v1/v2 retain their existing shape and behavior.

For owner-state equality predicates currentOwner and nextOwner:
- activationEntryForOwner = (parent.entered OR parent.establishment) AND nextOwner;
- child restart = activationEntryForOwner;
- child cancel = (parent.exited OR parent.establishment) AND !activationEntryForOwner;
- child enable = parent Running && currentOwner && parent enable.

Cancellation intentionally cleans all non-entered children, without currentOwner gating. This handles arbitrary resetless parent codes, parent cancellation with invalid local state, and recovery/restart when a child happens to encode Running outside the selected owner. The exact child cancel-Running rule makes cleanup idempotent. The active subtree is deterministic after accepted establishment; inactive child local/status bits are not promised initialized. Inactive observations must be qualified with current ownership/active, and reports retain that limitation. No implicit initialization is added to user storage.

Each controlled child resolves those controls with reset > cancel-Running > restart > enabled normal. Parent cancellation cancels unfinished children on that edge. An Any/ordered-winner exit cancels still-running losing branches; already Completed children remain Completed. Reentry, including an ordinary self-transition, restarts children without simultaneously asserting cancellation. Parent restart into another owner state cancels all non-entered running subtrees, despite restart intentionally having no exiting event. RecoverToInitial establishes the initial active subtree through the canonical establishment event while retaining the parent's existing no-ordinary-action recovery semantics. Error does not invent recovery or establishment. Reset independently sets every private lifecycle Dormant and local state initial, running no entry/exit action. Formal/resetless behavior remains the v0.6 occurrence contract.

## Bounded join-deadlock admission

Admission proves only structural possibility, never arbitrary runtime guard liveness.

1. Enforce exact owner/slot identity, one parent per occurrence, one owner state per child slot, nonempty child sets, no duplicate slots, legal destinations, and a finite acyclic occurrence graph. No terminal state owns live children or outgoing edges.
2. Existing FlatFSM reachability ignores arbitrary runtime guard truth and still requires every declared local state to be reachable from its initial state.
3. Validate child/join prerequisites bottom-up over the acyclic definition/occurrence graph. After every join prerequisite in a definition passes, its existing all-state reachability proof makes structural completion possibility exactly a nonempty terminal-state set. Reuse that shared graph fact rather than introducing a second local-state reachability engine. A bounded topology worklist charges every occurrence and child/join edge; a failed join prerequisite rejects the definition before its terminal set can support an ancestor.
4. All requires every participating child to have possible completion from its initial state. Any requires at least one; a deliberately nonterminating sibling may be cancelled when another completes. Per-child completion edges require that exact child to have possible completion. A join whose prerequisites are impossible rejects even if an unrelated escape edge exists. Missing/foreign children, empty joins, unknown policies and impossible destinations reject before capture.
5. An ordinary standalone machine is still allowed to have no terminal state, preserving v1/v2 behavior. It cannot satisfy a join prerequisite that needs completion. No assumption that a hardware input eventually asserts is admitted. Reports call the fact structural-completion-possible, not deadlock-free or eventually-completes.

Use an iterative worklist, not recursive traversal. Preflight checked sums before graph/storage construction: at most 4096 occurrences, depth 128, aggregate state/command/slot/selected-join-operand work at most the shared 65536 record budget, and existing capture/normalization budgets. The aggregate count is a conservative structural resource bound, not an exact generated-operation count. Repeated joins charge every selected child operand before copying any selected inventories. Per-operation native charging remains authoritative. Reused definitions are admitted once by identity where safe; physical occurrence/storage counts still charge each use. Exceeding a bound leaves the construction/normalization transaction unchanged.

## Proof and reports

Keep immutable definition/fragment/slot correlation separate from actual occurrence topology and encoding. Report definition identity/hash, fragment/state/slot source identity, actual parent machine/private storage operands, owner state, join mode/source ordinal, child occurrence, domain requirement and actual occurrence proof, local encoding, aggregate expansion counts, completion-possibility result, and source locations. Native verification derives authority from actual operands/closed schema/whole-root proof, never metadata.

Focused cases: nested child, same definition twice, parallel All, Any with nonterminating cancelled sibling, ordered simultaneous completion, Exclusive overlap rejection, missing/foreign/duplicate slot, no structurally possible completion, cyclic/over-budget forged topology, pause, cancellation/reentry/reset, self-transition reactivation, restart to a different owner, arbitrary binary initial parent/child lifecycle and invalid local codes under Error/RecoverToInitial, actual mixed-reset reusable target hierarchy, separate storage and stable report identity. Runtime protocol liveness is deliberately not claimed.

Timers/protocol waits and explicit bounded runtime call/return stacks with overflow policy remain separate required B.3 slices, not silently dropped by this draft.

## Native graph linkage and source capture details

The closed join and activation operations carry actual parent/child private-state operands for independent ownership validation, not only descriptive machine IDs. Their shared dependency query distinguishes semantic inputs from identity-only operands. Join output must be an actual parent FlatFSM guard for the declared owner state; activation results must be the exact restart/cancel/enable operands of the owned child FlatFSMs. Parent future operands are the exact v3 results, not equivalent-looking arbitrary expressions. Parent and child have the same exact local domain declaration/requirement. Unknown/duplicate ownership, multiple activation producers, a child also externally controlled, cross-domain composition and topology cycles reject.

Whole-module composition admission builds a bounded actual-owner index once in the shared sequential verification path. Individual op verification still checks closed local arity/type/form. No module-wide scan per query and no persistent permission cache are needed. The same admitted topology/semantics owner supplies source/native lowering and report facts. After proof, join/activation expand to existing Bool/integer expressions; actual state execution remains FlatFSM plus SequentialModel.

Source preparation separates existing captureFlatFsm allocation from graph/action capture internally. It allocates all actual registers/record identities first, then registered-status join expressions, then parents before children with activation expressions between. The public live FsmBuilder nesting prohibition stays intact. A composition observation scope can permit read-only FsmRun.child access to already captured records without reopening their builders or leaking them outside the one callback. This scope is not a second state registry: canonical actual records remain in Session.flatFsms and carry exact parent/slot links.


## Closed native ingress and normalization order

`flat-fsm-v3` uses v2 operands/private-state/action shape plus one fifth one-bit
`establishment` result. The canonical FlatFSM owner derives accepted restart and
RecoverToInitial establishment, with reset suppressing events. V1 has two results
and v2 four, unchanged. V3 is admitted only as a member of an actual finite
composition topology, not by a permission-bearing label.

The `fsm-composition-v1` join form has parent state/lifecycle operands followed
by ordered child state/lifecycle pairs, one Bool result, exact domain and owner
case, and closed `all`/`any` policy. A per-child completion edge is a one-child
join. The result must feed an actual matching parent guard; ordered winner
selection remains the existing parent transition source ordinal/priority.

The activation form has exact parent state/lifecycle, next, taken, entered,
exited, establishment and enable operands, followed by its ordered child
state/lifecycle pairs. Its three Bool results are restart/cancel/enable and must
be the exact child control operands. Descriptive parent/child IDs accompany the
actual ownership edges but cannot establish permission. Join and activation
inventories agree with one actual owner state and child set. No externally
controlled child, orphan activation, duplicate parent, cross-domain composition
or alternative equivalent-looking future expression is accepted.

Shared sequential verification builds the bounded owner/topology index once,
then normalizes pure joins/activation into existing digital expressions before
FlatFSM expansion. Activation may initially reference exact FlatFSM results;
normalization replaces those uses through the existing executor. Current child
status, not future child events, controls joins, so the graph is acyclic. All
expansion occurs on the existing detached sequential candidate, charges its
shared operation budget and commits only after verification. Target lowering
consumes the resulting ordinary sequential operations, not a second statechart
backend.

## Existing live-builder candidates

The historical `FsmBuilder.parallel(name)(body)` and
`submachine(name, FsmDefinition)` candidates do not carry immutable child graphs,
explicit child bindings or owner-state/join semantics. They are not compatible
adapters for the selected API. Once this slice is implemented they remain
explicitly gated unsupported dynamic forms with precise guidance to
`FsmChild`, `FsmState.children`, completion edges and child bindings; they must
not keep a blanket message claiming hierarchical FSMs are unavailable. Supported
ordinary live-builder state/transition/action methods remain unchanged.

`StateBuilder.after(cycles)(to)` is a candidate for direct integration into the
shared timed-state owner in the required subsequent timing slice.
`FsmBuilder.boundedCallStack(depth)` alone omits a return-state type, call/return
semantics and overflow policy; the required runtime-procedure gate must either
provide those explicitly or replace that underspecified form with precise
guidance. Neither refusal counts as completed timing/stack implementation.
Finite decreasing elaboration recursion and runtime stack execution are
separate contracts, resources and test obligations.
