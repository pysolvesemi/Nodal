# NodalSequentialState-DG v0.4 — owned memory ports

**Status:** Approved additive design; implementation and focused checks pending
**Scope:** public-api and compiler-boundary
**Baseline:** `18acaa6b1443607908babfbc6f4fec28999062c4`

## Authority, original obligations and alternatives

The explicit Foundation implementation authority recorded in
[v0.1](NodalSequentialState-DG-v0.1.md) covers this required F-056.B.2 work.
This gate selects the bounded missing port semantics; it does not assert that
the user separately selected each signature or policy detail. The prototype
policy applies, and all implementation/acceptance claims remain pending.

[ADR0009 memory contracts](../architecture/0009-core-semantic-contracts.md#memory-contracts)
requires explicit latency, masks, port domains, ordering/collisions and supported
initialization/reset. The frozen
[v0.3 API](NodalCoreSemanticsPipelineApi-DG-v0.3.md) exposes `read(address)` without
a read-enable or disabled-response policy. Its actual maintained source example
constructs reads at module level. Neither that gate, ADR0009 nor the original
[policy-space plan](../roadmap/core-semantics-api-v0.3-plan.md#memory-contracts)
promises conditional read-port allocation. The initial continuously enabled
profile below is explicit; unsupported branch-local construction must reject at
its source rather than silently choose a hold policy. Existing read values remain
usable in branches/actions.

Reuse the storage/dependency/sequential owners rather than a new memory graph or
fixture evaluator. A full target RAM backend is unnecessary for this checkpoint;
a declaration-only policy report is insufficient. Generic candidate statements
are replaced by owned typed ports through the actual production pipeline.

## Public compatibility and owning layer

Preserve Mem's constructor, read(UInt), and write(UInt, A, Bits) spellings. Keep
one actual MemoryOp declaration and typed read/write operands; no string effects,
second storage registry, default specialization, or fixture-only evaluator.
Extract shared domain sampling from SequentialModel for both register and memory
consumers. Extend the common dependency graph deliberately: synchronous reads are
state cuts whose sampled inputs remain checked; asynchronous reads retain address
dependencies and therefore cannot hide an address feedback cycle.

All previously admitted storage element kinds retain whole-element access,
including Integer, Real, enum, recursive Struct and Vec. Runtime scalar storage
capture and static Integer proof roles remain distinct at their shared owner.

## Accepted port semantics

- Module-owned memory, one actual local physical domain for all ports. Calls need
  not be lexically inside that domain; a present conflicting active domain rejects.
  Resolve actual requirements through the existing unique local binding rules.
- Module-level read calls are continuously enabled ports. Behavioral/action-local
  read creation is initially rejected explicitly; this avoids creating a hidden
  enable/hold rule from a value callback. A previously constructed read value may
  be captured into a behavioral update or action with normal lifetime checks.
- Writes at module level are continuously enabled edge effects. Writes inside
  checked behavioral regions carry their actual path guard. The sequential owner
  must normalize their guards and order alongside actual statement/action order,
  rather than use a separate branch parser. Pure collections still reject writes.
- Read latency zero is asynchronous; positive latency L denotes exactly L domain
  edges from address sampling to response. Keep L compact and never allocate L
  nodes during admission. Memory and read pipeline start unspecified; no reset or
  initialization is implied by the domain's reset input.
- At an edge, write data/address/mask and read addresses use pre-edge values.
  Ordered writes merge in authored semantic order, later active writes winning
  per selected byte. Compose order from actual module root source order and then
  the existing within-root body/FSM phase order. Multiple memory write roots are
  legal under this explicit multiport policy; register single-root ownership is
  unchanged. Dense normalized per-memory ordinals are independently checked
  against that real sequence. Host source read placement does not turn reads
  into mutation.
- Synchronous OldData returns pre-edge contents, NewData post-merge contents.
  NoChange holds the first response stage on an active same-address nonzero-mask
  write; later stages advance. Undefined imposes no value promise on collision.
  NoChange is real state, never synthesized zero or a combinational latch.
- Async reads with writes admit Undefined only in this first profile. Other
  latency-zero collision policy declarations remain legal, while actual mixed
  access rejects precisely rather than reinterpreting the policy.
- IndependentPorts requires proved disjoint simultaneous writes. Address, mask
  or guard disjointness may discharge a pair; unknown overlap rejects. An all-zero
  mask or false guard is inactive. No implicit priority follows from this name.

## Mask and address safety

A one-bit mask enables a whole element of every supported storage type. Fixed or
symbolically proved bitvector words additionally allow exact byte masks with
wordWidth == 8 * maskWidth over all legal values. Bit zero selects bits 7:0.
UInt mask remains accepted as Bits per the frozen API. Preserve the actual
UInt32/data + UInt4/mask example. Partial enum/Real/Integer/aggregate masks reject;
whole-element writes remain admitted. No illegal enum code can be assembled.

Accept any-width constant address proved in range and runtime UInt addresses
whose full checked envelope fits the depth over every legal parameter setting.
Do not require minimal bit width or truncate/wrap addresses. Unknown bounds reject
with a capability diagnostic. Preserve the compile-only F15 source fixture
unchanged; use a separate safe-address witness for actual 32/4 execution.

## Normalization and independent checks

Actual normalized ports retain actual memory identity, domain/clock edge, typed
operands, guard, authored order and latency/collision policy. Native verification
must recompute admission and conflict proofs from operands, not trust a report.
Policy-dependent normalization exposes selected forwarding/hold expressions or a
canonical checked memory-effect representation; unused descriptive attributes
alone are not implementation. The chosen representation must permit direct causal
checks of old/new/hold and per-byte ordering without inventing a target RAM backend.

Budget authored operations and pair proof work with the existing finite owner
limits. Pairwise collision checks may be quadratic in port count; charge each
query, reject before exceeding the budget, and report actual measurements. No
memory-depth expansion or general linearity claim.

## Required replay and target allocation

Actual public capture -> bridge -> native for all whole-element types, byte masks,
zero masks, safe/unsafe symbolic bounds, foreign Interface/requirement/register
provenance, priority/disjoint writes, and async versus synchronous dependencies.
Causal distinct values must establish old/new/hold, latency 1 versus 2 and ordered
write reversal. Forged symbols, arities, types, enum ABI, guards, domains, address
proofs and order inventories reject independently and transactionally.

[CIRCT v0.3](NodalSequentialState-DG-v0.3.md) continues to reject complete memory-backed designs. F56 memory-port
semantics are not a claim of RAM target emission or numerical simulation. F63
scheduling and F65 portable memory emission retain their original ownership.
