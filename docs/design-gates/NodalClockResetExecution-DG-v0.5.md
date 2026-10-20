# Nodal elastic asynchronous FIFO execution gate v0.5

**Status:** Approved
**Scope:** public-api

## Retained Stream API and transfer meaning

This required F57 continuation implements the elastic Stream contract approved
in v0.1. Preserve `Stream(payload)` as continuously offered valid data. Add
`Stream(payload, valid)`, read-only `payload`, `valid`, `ready` expressions and
`accept(ready: Expr[Bool]): Unit`. A transfer occurs only on valid AND ready at
the corresponding domain edge. Reading payload does not consume it; payload
when valid is false is not a transaction. Unattached sink readiness is false.

Each carrier has exactly one ready owner. `Cdc.fifo` owns its input carrier's
ready attachment; a second FIFO or explicit sink attachment rejects. Explicit
fanout is not invented. Source valid must remain asserted and payload unchanged
while stalled, including the continuously-valid spelling. Ready may change each
cycle. Existing owned wires, single-driver checks and finalization represent
forward ready references, including FIFO chains; do not build a second mutable
connectivity graph or cyclic SSA. Constructor-effect and ownership checks apply,
and caught errors still poison the construction transaction.

Existing pipeline/external placeholders cannot silently rebuild a continuously
valid Stream while discarding an explicit valid or ready connection. Descriptor
records remain descriptors; unsupported executable consumers reject under their
existing owner rather than acquiring FIFO safety from a host wrapper. Full F59
pipeline scheduling remains outside this F57 slice.

## Closed FIFO model and bounds

The initial native owned operation is `async_fifo` with contract `fifo_v1`:

- Operands, in order: source payload, source valid, destination ready.
- Results, in order: destination payload, destination valid, source ready.
- Actual source/destination domains, exact payload ABI, stable crossing identity,
  source location, i64 depth and closed typed obligations are retained.
- Obligations: `transfer = valid_and_ready`,
  `stall = hold_payload_and_valid`, `reset_epoch = common_assertion`,
  `pointer_skew = less_than_launch_period`,
  `bundled_data = settled_before_read_visibility`.

Depth is exactly the requested capacity: powers of two from 2 through 256,
default 4. Other depths reject, never round. Payload admission reuses the exact
finite bit-pattern family and checked maximum footprint from Gray/handshake.
Target support reuses the exact fixed whole-value scalar/Vec/Struct ABI, 1–4096
bits; unresolved widths/layouts and unsupported operations reject transactionally.
For P = log2(depth) + 1 and W maximum payload bits, charge
`depth * W + 8 * P + 2 * (W + 1)` before allocation against the existing 262144-bit
budget. Arithmetic and target operation budgets are checked before expansion.
There is no parameter-default specialization or per-bit graph expansion.

## Hardware, flow control and physical assumptions

Use source binary/registered-Gray write pointers and destination binary/registered-
Gray read pointers, each P bits, with two-stage Gray-pointer synchronizers in
both directions. The source-owned bank has exactly depth payload entries with
no fabricated initialization. A bounded register bank and read mux are an honest
initial target; do not promise block-RAM inference or disguise a single-clock
memory interface as a dual-clock memory.

Source ready is not-full outside local reset. Full compares current write Gray
against synchronized read Gray with its top two bits inverted. Destination valid
is not-empty outside local reset. Empty compares current read Gray against
synchronized write Gray. Bank write and write-pointer advance occur only on
accepted source transfer; read-pointer advance only on accepted destination
transfer. Stale synchronized pointers may conservatively delay ready or valid,
never lose or duplicate an accepted item. There is no combinational cross-domain
ready/valid path. Ordinary nonblocking old-state semantics apply at simultaneous
edges. Both clocks continuing and the receiver eventually accepting are explicit
liveness conditions, not fixed wall-clock latency promises.

Registered internal Gray pointers change by at most one bit per accepted step.
Physical pointer skew below the relevant launch period and payload-bank settling
before synchronized write visibility remain source-located timing obligations,
not digital metastability proofs. FIFO payload and valid share one coherent
boundary identity; they are not unrelated synchronizers.

## Common reset epoch and executed protocol checks

Reuse shared crossing/reset proof: equal nonempty actual physical reset-root,
element and polarity sets, with independently safe effective release. Reset
flushes the queue, masks source ready and destination valid, and resets all
pointer/synchronizer controls to zero through real reset. Empty payload and all
bank contents remain unspecified. The environment must assert a common reset
long enough for both domains to observe it and must not restart one side alone.

Each local edge outside known asserted reset requires all local pointer and
synchronizer control bits to be known. Unknown reset cannot suppress this check.
As with handshake, this is startup protection, not universal detection of every
unilateral reset after a prior epoch.

Both endpoint stall monitors retain the previous payload (uninitialized) and a
real-reset stalled marker. If previously stalled, require valid still asserted
and payload unchanged at the next local edge, unless reset is known asserted.
Source-side violations are genuine caller protocol failures; destination-side
checks also verify the owned output stability guarantee. Source/target mapping
and immediate fatal checks survive export. No assertion is stripped to make a
simulation pass.

## Shared graph correction and focused delivery

The existing crossing/dependency owners must explicitly describe each operand's
sampling domain and each result's domain/role. Source ready is source-owned;
destination payload and valid are destination-owned. Retain all actual sampled
roots needed for provenance and one coherent FIFO identity across result roles.
Do not coerce all results into destination ownership or bypass reconvergence for
independent instances. Domainless constants are neutral inputs, not inferred
clock provenance; actual source payload/valid facts establish one source domain,
and actual destination-ready facts must agree with the destination.

Use ConstructionSession/records and the bridge for actual endpoint identities,
existing physical wires for ready attachment, CrossingModel plus the existing
digital dependency graph for native proof, and the shared target planner for
bounded atomic lowering. Reuse Interface stream leaf/type/direction semantics;
do not introduce a competing protocol abstraction.

Focused tests cover exact-capacity fill/stall/drain, ordered wraparound, false
valid/ready, stall violations, unattached and duplicate ready owners, real source
and ready domains, malformed types/depth/roles/budgets, chained FIFOs without false
combinational cycles, reset flush and startup, independent coherent identities,
and actual source-to-SystemVerilog execution with source locations. Run directly
affected crossing/Interface predecessors, not CI or exhaustive qualification.
Other F57 categories remain required and open; this gate closes no checklist.
