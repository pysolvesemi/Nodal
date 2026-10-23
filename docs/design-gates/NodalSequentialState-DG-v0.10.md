# NodalSequentialState-DG v0.10 — bounded same-enum procedure stacks

**Status:** Implemented; focused prototype checks accepted (shared source 43, frontend 17, native 164, actual target 3)
**Scope:** public-api and compiler-boundary; runtime recursion part of F-058.B.3
**Baseline:** SequentialState v0.9 and the F58 readiness checkpoints

## Selected public surface and ownership

`FsmState[S].call(guard, mode = Exclusive)(entry: S, resume: S)` and
`returnOn(guard, mode = Exclusive)` are ordinary authored candidates in the one
FlatFSM transition selection. `FsmDef.withCallStack(owner, initial, capacity,
overflow, underflow, invalidFrame)(fragments*)` admits the complete immutable
graph with its stack contract. Every policy is `FsmStackPolicy.AssertHold` or
`FsmStackPolicy.Trap(to: S)`; trap targets use the exact definition enum evidence.
Capacity is 1..128. Configuration at construction, not a fluent modifier after
FsmDef admission, avoids a provisional graph whose validity depends on future
capacity or policies. Existing FsmDef construction still rejects call/return
without a stack. No callback, runtime value, second graph, clock or engine is added.

## Execution

Accepted call writes the exact local resume code at frame[pointer], increments
depth and enters entry. Accepted return selects frame[pointer-1], decrements depth
and enters that exact decoded state. The guard order and Exclusive/Priority proof
remain shared with ordinary, timed, transfer and join edges. Dynamic return is an
explicit graph edge, not a fixed fake destination or all-states edge expansion.

Overflow, underflow and invalid pointer/popped-frame codes apply their explicit
policies. Invalid pointer has precedence over call/return capacity checks. AssertHold
emits an enabled-operational sampled assertion, suppresses taken/transition/entry/
exit effects and stack writes, and preserves ordinary active actions. Ordinary
edges on an invalid pointer also apply invalidFrame, never preserve corrupted
private state silently. Trap performs one real entry and exit, clears depth and
stale lifecycle completion, and follows the canonical action precedence. A terminal
trap completes normally. No frame is silently dropped or wrapped.

Reset, accepted restart, recovery, cancellation and terminal completion clear
pointer. Frame registers are deliberately uninitialized, including in reset-bearing
and contextual instances. Pointer uses zero, uninitialized or if_reset as required
by the actual domain. Frames are selected by a balanced bounded network and only
one frame can be written on a successful call. Pointer width is
ceil(log2(capacity+1)); actual stack bits are pointerWidth+capacity*localWidth.
Frames must not be observed as valid data at zero/invalid depth. This register-backed
slice makes no RAM inference or minimum-area claim. Compact, one-hot and custom
local encodings retain exact frame widths. Gray local encoding is explicitly
unsupported for bounded dynamic returns; existing v1-v4 Gray behavior is unchanged.
The shared timer-plus-stack storage inventory is bounded to 65536 actual bits.

## Canonical bounded structural summary

The shared graph owner admits state/depth pairs for depths 0..capacity. It computes
may-return-without-clearing and may-complete from each pair using a monotone bounded
worklist. Ordinary edges preserve depth. Successful call depends on callee return
and resume; completion can instead occur inside the callee. Returns at positive
depth establish may-return; underflow/overflow Trap contributes only completion
from the trap at depth zero, never return to the old caller. Terminal states are
completion bases and clear every outstanding continuation. This is possibility
ignoring guards, not liveness or a guarantee of progress.

Reachability starts at (initial,0), visits call entries and only visits their
matching resume when may-return holds. Feasible overflow/underflow traps contribute
normal reachability. The invalidFrame trap is an explicit corruption-recovery
entry at depth zero for coverage only; it does not establish initial completion
possibility. All represented predicate nodes, rules and dependency operands are
charged before allocation/worklist execution against 65536 work. Composition adds
this actual proof work to its ordinary aggregate graph budget and consumes the
canonical initial completion result; no concrete stack-content enumeration occurs.

## Native v5 and independent ingress

V5 retains v4 lifecycle_mode, timer_edges and transfer_edges (possibly empty).
After the local/control prefix and optional elapsed operand, it contains the
pointer then exactly capacity frame operands, followed by guards and action
captures. The closed stack dictionary contains capacity:i64, pointer_width:i64,
overflow, underflow and invalid_frame. Each policy dictionary contains
kind="assert_hold" alone, or kind="trap", target_case="exact enum case".
Each v5 transition dictionary has kind="ordinary"|"call"|"return". Ordinary/call
have destination_case; only call has resume_case; return has neither. V1-v4 stay
unchanged. Role-aware private-state queries distinguish Local/Lifecycle/Elapsed/
StackPointer/StackFrame; shared sample, domain, storage and normalization owners
consume them. Native repeats graph, policy, width, storage and bounded-summary
checks independently. Metadata and source certificates confer no authority.

Focused checks cover capacity boundaries, nested returns, failed call/return and
active/action priority, invalid pointer/frame, terminal/nonterminal trap, lifecycle
clear, timer return entry, composed child completion/cancellation, resetless and
formal mixed-reset reuse, exact private-write exclusion, malformed native forms,
atomic proof/storage limits and actual source-to-HDL replay. No F58 parent closes
until these required execution checks pass.


## Focused prototype evidence

The native implementation passes 137 FlatFSM and 27 composition groups, including
all supported predecessors. Full production/test manifests stayed unchanged
during the accepted focused run. The exact compiler SHA-256 is
`5ec53036d0ccc2c9f14898e5d6e21c76693651b0dab564a75e0b19236d99375b`.
Native receipt paths are `.work/f58-statecharts/stack/native/{source6-*,build6.log,
flat6.log,composition6.log}`.

The maintained public source `Increment58StackFsmSource.scala` and
`Increment58StackFsmReplay` provide eight actual construction profiles. On the
same compiler, `tests/scala/reusable-fsm/run_stack_target.py` passes unchanged
source gate, CIRCT lowering, SystemVerilog export and Icarus waveforms for:

- nested calls/returns, exact continuations, priority, overflow/underflow,
  invalid pointer (including Completed without a candidate), invalid popped
  frame, action ordering, pause, cancellation/restart, recovery and pointer-only reset;
- one formal child definition reused with independent clocks and mixed actual reset policy;
- a composed stack child with registered completion join and owner lifecycle.

The emitted AssertHold witness retains its sampled `assert property`. It gates,
lowers and exports unchanged, but pinned Icarus rejects concurrent assertion
syntax. No assertion is removed or disabled. AssertHold fault/action/lifecycle
behavior is covered by the native normalized executor tests; no Icarus waveform
pass is claimed for that profile. Receipts are under
`.work/f58-statecharts/stack/shared-accept/target/`. Shared production Scala
integration passed 43 groups (stack 9, unroll 4, reusable 11, timers 9, composition
10) with matched source manifests in `.work/f58-stack-shared/`. The integrated
compiler artifacts then passed 17 frontend checks, all eight actual source
profiles and all three target waveforms again; receipts are in
`.work/f58-statecharts/stack/shared-accept/`. The frontend addition checks a
stack factory inside immutable unroll plus wrong-enum call and trap rejection.
This closes the bounded runtime-stack slice, not the F58 parent.

Architecture review: one immutable command graph and bounded summary feed the
existing FlatFSM owner. The bridge independently re-admits snapshots, and native
schema/graph/role checks remain authoritative at native ingress. The existing
normalization, sample, private-storage, domain and SequentialToCIRCT owners are
reused without a second executor or target-specific FSM engine. Balanced frame
selection and exact guarded writes scale with capacity, while summary work is
charged before worklist allocation. Tests cover supported predecessor behavior;
this is focused prototype acceptance, not global qualification or a universal
performance claim.
