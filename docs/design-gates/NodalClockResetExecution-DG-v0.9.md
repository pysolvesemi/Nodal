# Nodal clock/reset execution design gate v0.9

**Status:** Approved
**Scope:** public-api

## Owned waiver execution

This remaining Foundation 57 slice makes the frozen Cdc.waive(value, to, waiver)
spelling executable. It is an explicit, source-located exception for one owned
value boundary, not a synchronizer, global permission, reset exemption or disabled
verification flag. The public CdcWaiver id, reason and typed ClockRelation remain
required. No new public framework or alternate source graph is introduced.

Reuse the existing fixed-whole finite digital payload ABI and exact source-domain
inference. The value must have one actual attributed source domain and an explicit
owned destination; Same does not choose a capture/reset identity among multiple
actual sources. Clock/Reset, analog values and unsupported storage projections
are not payloads. Constants alone do not invent a source domain. The result has
exactly the payload's type and bit layout and carries destination ownership for
this one explicitly waived boundary.

## Contract identity and retained provenance

Require a nonblank ID of at most 256 UTF-8 bytes and a nonblank reason of at
most 4096 UTF-8 bytes. IDs are unique among authored waiver
operations in one module definition; occurrence qualification distinguishes reused
instances. Reusing one result does not create a second waiver. Stable report
identity combines occurrence, source semantic path and authored ID, never an
allocation address or iteration order. Diagnostics and target obligations retain
the original source and reason with safe escaping.

A waiver has a distinct contract identity but creates no physical synchronizer
identity. Its payload remains an ordinary combinational dependency for driver and
cycle analysis: a loop through a waiver is not broken by invented state. Only
the domain-permission traversal cuts at the verified destination contract;
lineage traversal continues through the payload. Do not reuse a stateful crossing
graph cut for stateless dependency/cycle analysis. Preserve every upstream crossing identity, source root and Pulse
classification through it. Two independent synchronizers cannot become one by
wrapping their outputs in waivers; reusing one synchronizer through multiple
stateless aliases must not fabricate independent chains. Reconvergence is checked
before the waiver and at downstream observations using the existing shared
provenance engine. A reason string never suppresses ownership, reconvergence,
reset-release, domain binding or type checks.

Pulse-classified Bool data may remain Pulse-classified through the exception;
it must not become eligible for level/Gray/handshake/FIFO relabeling that would
otherwise reject that classification. The waiver promises no pulse delivery,
coherence, metastability resolution or event-spacing behavior. Those remain the
explicit user's physical boundary responsibility, retained as target obligations.

## Typed relation intent, not a global clock fact

Retain all six ClockRelation kinds and their exact fields using the existing
relationship field/arithmetic owner. No fallback string or default ratio/phase is
accepted. The waiver's relation is the declared rationale for this boundary, not
a new global Same equivalence edge or a timing fact authorizing other values.
Contradictions against independently established endpoint facts still reject;
Unknown is absence of proof, not a mechanism to erase stronger known facts.
Use a read-only boundary-intent consistency query on the existing relationship
index: compare kind-specific claims against independently established exact
identity, Same, timing, frequency, phase and exclusion facts. An absent proof alone
is not a contradiction. Never insert the waiver relation into the Same/timing
indices, even temporarily as an authorization shortcut. Reuse the existing typed
field and bounded arithmetic validation; do not build a second relationship engine.

## Native and target contract

Capture an immutable owned record with actual payload operand, exact source and
destination references, typed relation fields, ID, reason, contract version,
obligations and source identity. The bridge resolves actual operands independently;
native verification independently checks closed fields, exact ABI, ownership,
endpoint facts and uniqueness. Descriptor-only waiver reports never authorize
sampling. Hierarchical occurrences use the v0.8 context rather than string aliases.

Lower only the verified stateless identity transport and retained source-located
obligations. Do not insert hidden synchronizer state, force a reset value or claim
runtime certification of the waived physical hazard. Any target comment rendering
must escape authored strings without changing generated code. Unsupported target
hierarchy continues to reject transactionally under the existing F57/F58 boundary.

## Focused acceptance and remaining work

Positive cases cover scalar and fixed-whole aggregate payloads, typed relation
fields, one reused waived result, preserved upstream boundary lineage and stable
occurrence-qualified reporting. Causal negatives cover missing/blank/duplicate
IDs, missing reason, malformed relation fields, forged actual endpoints, mixed
source attribution, Clock/Reset payload casts, independent-chain reconvergence,
Pulse relabeling, combinational cycles through the exception and descriptor-only
authorization. Actual flat generated HDL
must demonstrate identity data behavior with no invented storage and retain the
exact exception obligation; no violated electrical assumption is advertised as
a successful runtime check.

The low-level event escape, remaining Interface observations, representative
scale and final integration remain required Foundation 57 work. This gate does
not mark any implementation or coarse checklist complete. No CI or broad
qualification is authorized by this gate.
