# Nodal Gray and pulse execution design gate v0.3

**Status:** Approved
**Scope:** public-api

## Selected contracts and ownership

This additive F57 gate follows v0.1 and v0.2 under the owner's instruction to
complete Foundation. Existing `Gray(value)`, `Pulse(value)`, `Cdc.gray` and
`Cdc.pulse` signatures remain unchanged. No ready signal, initializer, general
handshake API or implicit lossless-event promise is added. F57 remains open.

Executable crossings retain actual input/output SSA, source and destination
bindings, source location, stable physical identity and typed obligations.
`CrossingModel` owns category validation; the existing digital-assignment graph
owns provenance, crossing cuts and reconvergence. `DigitalTypeModel` owns finite
storage ABI queries. Existing reset lineage and physical-control owners establish
reset-root/element/polarity identity. Source construction mirrors these contracts
without creating a second provenance or reset graph. Descriptor-only historical
records do not acquire executable proof by naming a contract.

The executable contracts are `level_v1`, `gray_v1` and `pulse_v1`; their kinds are
respectively sync, gray and pulse. Obligation dictionaries have closed, typed
fields. Unknown, missing or incompatible fields reject transactionally.

## Gray meaning, state and obligations

Gray accepts exact equivalent finite digital storage types: Bool, Bits, UInt,
SInt, Enum and bounded Vec/Struct compositions of these. Integer, Real, Clock,
Reset and Analog are not Gray payloads. Stages range from 2 through 32. Checked
allocation accounts for `(stages + 1) * maximumPayloadBits + 1`: the destination
chain, source previous sample and source seen marker. Shared bounded storage
queries apply before allocation; no independent width evaluator is introduced.

The caller retains three explicit, source-located environment obligations:

- `transition = one_bit_per_source_sample`;
- `skew = less_than_source_period`;
- `stability = stable_between_source_samples`.

The latter two are physical/environment assumptions, not digital proofs of
metastability safety. Input ports and combinational values are admissible only
under these obligations and actual source-provenance checks. Typed IR/report
metadata and generated target comments retain them.

A source-clock monitor compares consecutive sampled payload bit patterns. Once
its seen marker is known true, and source reset is deasserted, it checks that
`delta == 0 || (delta & (delta - 1)) == 0`. The previous payload has no invented
initial value. The seen marker clears only on real source reset; without reset
it starts unspecified and becomes one on the first actual source edge. Target
checking must use explicit four-state knownness so first-sample handling is
intentional, not an accidental unknown assertion result.

Payload stages have no fabricated initializer, including in reset-bearing
destinations: the frozen API provides none, and zero need not represent a valid
Enum value. Output is unspecified before a complete chain of valid destination
samples, and during reset/recovery until that chain refills from valid source
values. Distinct crossing instances retain distinct physical synchronizer state.

An initial implementation checkpoint may lower fixed scalar storage widths
1–4096 and reject aggregate/unresolved target shapes transactionally. Aggregate
Gray target support remains required F57 work before Gray completion; this is
not a permanent scope waiver.

## Pulse meaning, spacing and reset epoch

A single asserted source-clock sample represents one event. The executable
implementation uses seven control bits: source toggle and previous source pulse,
two destination synchronizer stages and destination history, and two source-clock
return-acknowledgement synchronizer stages. The destination pulse is the XOR of
the synchronized toggle and history. Acknowledgement samples destination history,
not merely its synchronizer output, so completion corresponds to reconstruction
and the detector returning low. Simultaneous clock edges do not bypass the return
synchronizers.

Retained obligations are `spacing = returned_acknowledgement`,
`width = single_source_cycle` and `reset_epoch = common_assertion`. At an asserted
source sample, the monitor requires the source toggle to equal returned
acknowledgement and the preceding source sample not to be asserted. The source
toggle responds to every asserted sample, never to checker success: an invalid
busy pulse is diagnosed, not silently discarded by hidden ready gating. The
actual return path is bounded verification-support hardware, not a public ready
interface or a guessed fixed clock-ratio spacing guarantee. Behavior outside the
stated assumptions is unspecified.

Source and destination require nonempty equal actual reset-root/element/polarity
sets and independently safe release. All seven bits clear only through real
reset, not preset simulation initialization. The caller must provide a common
assertion observed in both domains with adequate duration, and no one-sided epoch
restart. Pulse use before a known reset epoch must fail the target check rather
than pass vacuously through unknown state. Reset identity is statically checked;
physical duration and epoch alignment remain explicit source-located obligations.

## Focused acceptance and architecture review

Checks cover actual Scala capture and native malformed IR; exact ABI and resource
bounds; provenance and reconvergence; both edges; reset identity/release failures;
independent physical state after canonicalization; and directly affected level
and reset predecessors. Actual Scala-emitted IR must lower through CIRCT and
export to executable target simulation. Positive first-sample/startup and legal
Gray/pulse sequences accompany deliberate invalid transitions, widths, spacing
and reset-epoch negative simulations. Checks must execute and fail appropriately,
not merely survive as comments. If concurrent assertions are unsupported by the
selected simulator, use equivalent source-located clocked immediate checks;
never strip assertions to make simulation pass.

This design extends the narrow existing owners, uses bounded state proportional
to payload width and stages, and reuses canonical physical/reset identity. It
adds no parallel registry, graph engine or speculative general CDC framework.
Prototype acceptance is focused local evidence, not exhaustive qualification or
a claim that digital simulation proves physical metastability behavior. No CI is
run. Required remaining F57 categories and aggregate lowering stay open.

## Fixed whole-aggregate Gray target ABI

The required fixed Vec/Struct continuation uses one flat packed integer for each
whole payload boundary and state value. Canonical Vec row-major element zero
occupies the least-significant element bits; Struct fields follow declaration
order with the first field least-significant, recursively. The complete canonical
source ABI and explicit layout convention accompany the target representation.
This extends the existing fixed-control row-major bit-offset convention, not
source-level shape identity or structural assignment equivalence.

A shared DigitalTypeModel fixed-bit query first reuses validated bounded storage
footprints, then requires singleton bounds for every scalar width and shape
extent. A maximum bound or a parameter default is not an exact target width.
The initial target bound remains 1–4096 packed bits. No lane expansion or second
width engine is introduced. Whole-value transport is the selected executable
profile; unsupported aggregate projections or unresolved layout reject
transactionally rather than silently changing packing. Source/native complete
aggregate admission and existing general projection semantics are unchanged.
