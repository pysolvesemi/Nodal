# Nodal clock/reset execution design gate v0.1

**Status:** Approved
**Scope:** public-api

## Authority and allocation

This additive Foundation 57 gate implements the accepted clock/reset v0.2 and
unified v0.3 contracts. Approval is grounded in the owner's explicit instruction,
“start implementing all foundation unchecked increment items in nodal repo from
increment 43 and continue until all foundation checklists are completed. Don't
start another track just complete foundation track.” The owner also approved
public commits/pushes and completed-increment integration. These instructions
authorize required Foundation implementation; they are not a claim that the
owner separately selected every signature below.

The original F57 categories remain required. This version freezes the shared
domain/level-crossing foundation and the protocol compatibility decisions below.
Further reset/gate/mux/low-level executable details must be recorded before their
implementation depends on them. No checklist completion follows from this gate.
Full F58 hierarchy/statecharts and F59 pipeline scheduling remain their owners.

## One proof and identity owner

Existing domain declarations, requirements, bindings, relationships and actual
Clock/Reset values remain authoritative. A shared native domain model factors
F56's existing requirement resolution and physical-binding checks. Sequential,
memory and crossing consumers reuse it and the existing physical assignment
and dependency graph. No new textual CDC expression walker or duplicated driver
registry is introduced. Source validates construction; native independently
validates ingress against actual SSA producers, symbols and owned regions.

A crossing has actual input/output edges, exact complete value type, source and
destination identities, category, stable semantic identity and source mapping.
The result is owned by its destination, but its crossing/reset/waiver lineage is
retained. Graph cuts never make a crossing domainless. Independent synchronizers
are independent state and cannot be commoned. Existing F19 descriptors without
an executable contract remain descriptors and cannot authorize unsafe execution.

Only a proved Same relationship is directly interchangeable by default. Equal
frequency is not proof. Ratio, phase, synchronous, asynchronous, mutually
exclusive and unknown relations remain distinct evidence. A waiver has actual
endpoints, nonempty stable ID/reason and a retained relation; it is reported and
does not disable reconvergence or ownership checks.

## Level synchronizer foundation

`Cdc.sync(bit, to, stages = 2)` keeps its frozen spelling and Bool-only result.
Source domain is inferred from actual input dependency provenance, not from the
currently active destination scope or a default parameter. Ambiguous/unresolved
or mixed-domain inputs reject. Pulse-classified input cannot be relabeled as a
level. Source and destination must resolve to actual owned domains. An explicit
destination does not require an enclosing `ClockDomain.apply`: module-level
construction with known actual source provenance is valid. Stateful allocation
inside behavioral/value callbacks or generated scopes is outside this initial
level profile and rejects before construction.

The finite initial stage count is 2 through 32. Counts are checked before
allocation; total generated state also obeys the existing sequential expansion
budget. Each instance samples into an independent destination-clock chain. Its
result is the last stage. Reset-bearing destinations reset the chain to false;
resetless destinations retain uninitialized startup semantics. No initial value
is fabricated for a resetless chain and no metastability-probability guarantee
is claimed. Input changes need not be individually delivered by a level sampler.

The native executable crossing contract checks source sampling separately from
destination ownership. Lowering must preserve the synchronization boundary and
instance identity, rather than forge an ordinary safe cross-domain assignment.
Target support is reported per actual lowering; an unsupported target rejects
transactionally before output.

## Elastic Stream compatibility

The existing `Stream(payload)` spelling is retained as a continuously offered
valid payload, not as fixed-rate accepted transfers. Additive
`Stream(payload, valid)` supplies an explicit Bool valid expression. Both retain
payload and valid identities, source domain, exact payload ABI and ownership.
The carrier exposes `payload`, `valid` and upstream `ready` expressions. An
explicit `accept(ready: Expr[Bool]): Unit` attaches its sink-ready signal exactly
once. A FIFO connection owns its input carrier's ready attachment; a second
FIFO or explicit sink attachment to that carrier rejects. Fanout needs an
explicit future fork contract, not duplicated acknowledgements.

An unattached sink is not ready (false). A transfer occurs only when valid and
ready are both true at the relevant domain edge. While valid and not ready,
valid stays asserted and payload stays unchanged. This is a represented,
source-located protocol obligation, not something inferred from `Stream`'s host
wrapper. Continuously offered payloads obey the same stall rule. Merely reading
payload does not consume a transaction; when valid is false its value is not a
valid transaction. Existing payload access remains legal with that meaning.

`Cdc.fifo` must implement ordered elastic transfer/backpressure between actual
source/destination endpoints. It may not return its input object or implement
periodic unacknowledged sampling. FIFO depth, reset epoch, supported payload and
pointer/storage limits require explicit checked implementation clauses before
this category is admitted. Pipeline scheduling is not silently added here.

## Legacy handshake compatibility

`Cdc.handshake(payload, to)` retains its Expr result and is explicitly an
automatic coherent latest-snapshot transfer. When its internal source channel is
idle it samples a payload and starts a request; that sample remains stable until
acknowledged. Destination holds the last delivered snapshot. Source changes
while busy may coalesce: this API does not promise delivery of every update.
There is no fabricated public request/valid signal in the frozen Expr signature.
The destination value before first delivery is unspecified, like explicit
uninitialized storage; no hidden zero-value contract is introduced.

Control state requires a recorded coordinated reset epoch for both domains,
including per-domain safe release. One-sided reset/restart is not implicitly
safe. Its exact admission/recovery policy must be checked and retained before
implementation. A later explicit elastic overload can offer caller-controlled
accepted transactions through the same protocol owner; this gate does not
require an unrelated pipeline engine or silently change Expr into Stream.

## Derived domains and reset compatibility

`generated(name, clock, from, relation, reset)` preserves its frozen signature.
Edge derives from its actual parent; reset polarity and policy derive from its
actual reset capability and its relation to the destination. Missing facts do
not receive arbitrary defaults. Construction-form `Rdc.sync(reset, stages)`
binds to the actual receiving generated domain; ordinary transfer requires the
explicit destination. Reuse in incompatible destinations rejects.

A Bool-selected `ClockMux.glitchless` has exactly two distinct actual clock
parents in the initial profile. Both parent identities and the selection rule
must survive; false selects the first, true the second. Compatible common reset
provenance is required. Unrelated resets are not selected or combined silently.
Physical gate/mux identities remain distinct derived clocks, not Same aliases.
Their implementation must retain glitch-avoidance, test, mapping and timing
intent; an arbitrary Boolean-to-Clock cast is forbidden.

The original raw-Async reset-binding negative remains required even without
state. Async is not reinterpreted as AsyncAssertSyncRelease. Actual release proof
or an explicitly represented external safety contract is necessary; the precise
producer contract and F56 compatibility correction are recorded with the reset
implementation. Historical F56 acceptance is not retroactively renamed proof.

## Alternatives, checks and limitations

Rejected alternatives are payload-only FIFO identity, lossless-per-update claims
for the Expr handshake, silently ready/unconditionally consuming sinks, arbitrary
reset defaults, frequency-as-domain equality and metadata-only crossing safety.
An explicit ready/valid carrier preserves the existing v0.3 protocol and avoids
a second transport abstraction. Shared proof factoring preserves current users
before new policy is added.

Focused tests cover source/bridge/native identity and type parity; stage bounds;
ambiguous source domains; Pulse-vs-level misuse; independent chains and
reconvergence; resetless startup; duplicate ready ownership; valid/stall stability;
FIFO order/backpressure; handshake coalescing; reset epoch and one-sided-reset
negatives; exact generated-domain/reset/mux provenance. Assertions/assumptions
must be retained in the semantic model and any advertised target, not discarded
to claim safe execution. Required category checks remain open until implemented.

Resource bounds apply before graph/state expansion. Existing bounded dependency
queries are reused, without pairwise all-domain closure or copied transitive
sets. A representative measurement and concise final architecture review are
required before scale claims. No universal physical CDC proof, simulator
certification, CI or broad qualification is claimed by this gate.
