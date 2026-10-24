# NodalSequentialState-DG v0.8 — bounded timers and transfer waits

**Status:** Implemented; focused prototype acceptance reconciled in F58 checkpoint 71
**Scope:** public-api and compiler-boundary; next F-058.B.3 slice, not completion of B.3
**Baseline:** composed source and native checkpoints in [increment 58 readiness](../implementation/increment58-readiness.md); [v0.7](NodalSequentialState-DG-v0.7.md) remains unchanged except its recorded resource clarification

The standing Foundation implementation authority and prototype process authorize
this necessary additive selection. This does not claim that the owner individually
chose its spellings. [ADR0015](../architecture/0015-native-scala-enum-and-hierarchical-fsm.md)
and v0.5–v0.7 remain the semantic foundation. Acceptance requires real source,
native, target and focused predecessor checks.

Current acceptance reconciliation: the bounded contract below is implemented by
the accepted lifecycle, composition and timing checkpoints. See the
[F58 B.3 crosswalk](../implementation/increment58-readiness.md#checkpoint-71--statechart-implementation-reconciliation)
for exact historical source/native/target boundaries and retained limitations.
This status update adds no API, production change or fresh execution claim.

## Selected source and ownership

- `FsmState[S].after(cycles: Int, mode: TransitionMode = Exclusive)(to: S)` adds an
  immutable timed edge. The existing `StateBuilder[S].after(cycles: Int)(to: S)`
  uses the same owner and retains default Exclusive selection.
- `FsmTransfer[S](owner: FsmDefinition[S], name: String)` is an immutable typed
  transfer slot, not a carrier or closure. Its `bind[A](carrier: Stream[A])`
  returns an ordinary typed `FsmGuardBinding[S]` for explicit instance bindings.
- `FsmState[S].awaitTransfer(slot: FsmTransfer[S], mode = Exclusive)(to: S)` waits
  on the bound carrier's actual valid-and-ready event. A live
  `StateBuilder[S].awaitTransfer(carrier: Stream[A], mode = Exclusive)(to: S)`
  uses the same transfer adapter. Exact parent-token identity remains mandatory.

ConstructionSession retains timer storage and edge/transfer facts in the existing
FlatFsmRecord. FsmDef keeps only immutable thresholds and slot identities. One
FlatFSM executor still selects all ordinary, timed and transfer edges and executes
actions. There is no timer scheduler, new clock, callback retained in a definition,
second mutable state registry, or separate transition engine.

## Timing convention and deliberate storage tradeoff

Cycles is a positive Int (1 through Int.MaxValue). Each timed occurrence owns one
31-bit unsigned elapsed register, saturating at Int.MaxValue-1. Fixed width permits
both immutable and live-builder capture without deferred type mutation or a second
predeclaration API. This is a deliberate bounded storage tradeoff, **not minimal
hardware**. Reports and preflight charge all 31 actual bits; any later width
optimization needs its own semantics-preserving review.

Accepted establishment or actual entry, including self-transition, clears elapsed
to zero. Accepted cancellation and reset also clear it. Enabled operational edges
while in a timed state and without entry increment with saturation. Paused,
inactive, terminal and untimed-state execution hold the counter. A deadline N is
eligible when registered elapsed is at least N-1. Thus entry on edge K makes
`after(1)` eligible on the next enabled operational edge, never on K. Deadline
eligibility remains asserted until entry/restart/cancellation clears it.

Reset and cancel/restart/enable priority is the existing executor contract. Reset
runs no ordinary action; illegal-state recovery uses the canonical recovery event,
not a guessed entering pulse. Resetless state has no promised initialization before
accepted establishment; restart must satisfy the existing cancel-Running priority.
Two overlapping deadlines or deadline/ordinary guards do not gain implicit priority:
Exclusive must prove disjointness or reject; Priority selects source order.

## Transfer observation, not sink acquisition

A wait samples actual valid AND ready at its machine's domain edge. It does not
drive ready, acquire the unique Stream sink, consume payload merely by reading,
allocate another carrier, or promise eventual progress. Existing Stream construction,
ready assignment, stall-stability and domain/CDC owners remain authoritative.

Source/bridge admission retains the exact existing carrier's payload/valid/ready
identity and rejects a forged or foreign carrier. Native ingress represents a
closed read-only transfer predicate over the actual valid/ready operands. Those
operands are checked by ordinary exact FSM/domain sampling and dependency owners;
source-only carrier provenance is descriptive, never a native permission token.
There is no native Stream registry today, so this slice does not claim a new
native proof of arbitrary producer protocol liveness or external stability.

## Additive native form and shared seams

A `flat-fsm-v4` form retains the existing local/control prefix. A closed
`lifecycle_mode` is `legacy`, `controlled` or `composed`, preserving respectively
2, 4 or 5 result roles. Existing v1/v2/v3 forms remain unchanged. V4 has bounded
`timer_edges` and `transfer_edges` tables keyed by exact actual transition ordinal;
at least one table is nonempty. A timer-bearing form adds its actual UInt(31)
private elapsed register after the local/control prefix and before guards.

A closed `nodal.fsm_elapsed` predicate reads that actual register and a positive
literal cycle attribute; its result must be the exact owned timed-edge guard.
A closed `nodal.fsm_transfer` predicate reads exactly two Bool valid/ready operands;
its result must be the exact owned transfer-edge guard. Unknown tables, unmatched
query/edge ownership, foreign registers, wrong threshold/type/domain, extra writes
and malformed lifecycle modes reject independently at native ingress. Queries are
pure registered/ordinary input predicates; they do not read future state or execute
state updates. The existing FlatFSM expansion consumes admitted queries and emits
ordinary comparison/Boolean logic plus the elapsed update through SequentialModel.

Replace prefix-count assumptions in shared consumers with admitted
`FlatFSMHeader::privateStateOperands()` and `sampledInputs()` queries. The first
returns actual local/status/elapsed storage; the second actual controls and guards,
not private state or action captures. This is the same seam needed by the later
bounded stack, not a second v4 decoder. Composition continues to use existing
named local/status/control/result accessors.

Private timer initialization is zero for reset-bearing instances, uninitialized
for actual resetless instances, and the existing `if_reset` intent for an unresolved
requirement. Closed-root per-occurrence proof and material variant emission are
mandatory. Ordinary Reg initialization rules do not change. Shared operation,
state-bit and graph/operand budgets are checked before expansion and charged during
transactional normalization. Reports retain thresholds, ordinal, actual storage,
31-bit cost, saturation/eligible-edge convention, transfer operands, domain and
occurrence/reset proof; metadata is not authorization.

## Focused acceptance and remaining scope

Check N=1/N=3, pause, self-entry, restart/cancel/reset, priority and rejected overlap,
invalid thresholds, reused independent timers, physical and mixed-reset formal
instances, stalled/nontransferring versus actual transfer, foreign bindings,
malformed native timer ownership, bounded atomic rejection and actual generated
HDL waveforms. Preserve live-builder and v1/v2/v3 predecessors. The dynamic legacy
parallel/submachine candidates must name the implemented immutable replacement
rather than claim that hierarchical statecharts are unavailable.

Finite elaboration recursion and runtime stacks remain distinct required B.3 work.
The selected next structural convenience is an explicitly bounded iterative
`FsmDef.unroll(levels, base)(step)` that never stores its callback and charges every
actual child occurrence. Arbitrary nonterminating host Scala is not an admitted
unbounded hardware graph. The subsequent procedure gate must freeze a typed return
state, finite capacity, explicit overflow/underflow AssertHold or Trap behavior,
invalid-private-state/reset/domain policies, actual pointer/frame storage, report
and proof metadata. Neither a blanket refusal nor ordinary Scala recursion counts
as an implemented runtime stack.
