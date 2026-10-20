# Nodal clock/reset execution design gate v0.7

**Status:** Approved
**Scope:** public-api

## Allocation and frozen compatibility

This additive Foundation 57 gate implements executable typed relationship proof
through the existing ClockDomain.generated spelling. The owner's standing
Foundation-only implementation instruction supplies its allocation. It does not
make equal frequency a crossing proof or replace actual clock/reset identity.
Actual hierarchy provenance, observed-output reconvergence, waivers and restricted
events remain required subsequent F57 slices; full F58 hierarchy lowering is not
a reverse prerequisite.

The frozen language reference permits direct interchange only for Same. Existing
DomainStimulus fixtures use a distinct external sameClock input; restricting Same
to physical-pin equality would silently narrow that surface. Those electrical
reference projections are compatibility evidence, not native execution evidence.

## Same and actual identity

Same grants direct data sampling between validated equivalent domains. On one
actual Clock identity with compatible active edges it has structural evidence.
Between distinct owned external Clock input elements, it is an explicit,
source-located environment contract promising identical active sampling-edge
streams: no missing, extra or phase-shifted active edges. Full electrical duty
cycle/ramp equality is not promised. Equal numerical frequency alone is never
sufficient. The bounded profile requires matching active-edge polarity.

The contract retains both physical inputs, domain identities, state owners,
reset release chains and crossing instances. Do not change ClockModel.sameClock,
physical identity or ResetModel's exact receiving-clock comparisons to use Same.
A release chain for one input is not release proof for another input; a receiving
domain still needs its own Rdc.sync or an independently valid release contract.
Same does not permit independent synchronizers to be merged or hide reconvergence.

Distinct owned gate/mux producers cannot become Same with a parent or each other
merely through authored metadata, including a gate with constant enable. An alias
of the exact same owned producer may establish structural equality; distinct
external input elements may use the explicit environment contract above.

Add a distinct direct-sampling permission query to the shared domain owner;
existing exact-domain/clock identity queries keep their meaning. Ordinary data
sampling may combine roots in one verified Same class. Existing explicit CDC
construction still requires one unambiguous actual source domain: equivalence
permission does not arbitrarily choose a physical capture clock or reset from
multiple actual domains. A caller can register in its chosen domain first.

## Typed relationship meanings

- Same: validated equivalence closure supplies direct-sampling permission only.
- Ratio: multiply/divide are positive exact integers. Its period convention is
  Pchild = Pparent * divide/multiply, and its offset convention is
  Ochild = Oparent + phase/360 * Pchild. Ratio(1,1,0) is not promoted to Same.
- Synchronous: phaseKnown is retained exactly. True does not invent a numeric
  phase or grant ordinary direct sampling.
- MutuallyExclusive: an explicit operating-mode exclusion obligation, meaning
  the two sources are never simultaneously enabled as active clock sources in
  one operating mode. Noncoincident edge timestamps alone do not satisfy it.
  It does not authorize direct sampling.
- Asynchronous: explicit absence of synchronous timing authorization, not an
  exception to crossing checks.
- Unknown: absence of proof, never permission and not by itself a contradictory
  assertion against stronger known facts.

Every non-Same foreign-domain data sample still needs an appropriate explicit
crossing or, after its separate implementation, an owned waiver.

## Exact bounded arithmetic and consistency

Capture Phase(Double) using its finite canonical round-trip decimal spelling,
then convert that spelling to an exact reduced rational. Preserve the authored
value/source for diagnostics. NaN and infinity reject. Multiply/divide retain
positive signed-32-bit API bounds; zero and negative values reject. Intermediate
rational numerators and denominators are bounded to 4096 bits, with overflow
checked before allocating or multiplying larger values. Graph work remains
within existing finite operation limits; unsupported extremes reject rather
than falling back to floating epsilon or default parameters.

Normalize ratios and compare periodic phase modulo the relevant period using
exact arithmetic. Resolve relative period and offset constraints over bounded
adjacency/spanning components, with Same equivalence indexing separately. No
all-domain pairwise closure or copied transitive provenance sets are introduced.
Absent absolute frequency remains absent; relative constraints do not fabricate
an external clock period. Known supplied frequency claims must be consistent.

Reject inconsistent ratio/phase claims on one physical Clock element, nonidentity
timing claims inside a Same class, Same versus asynchronous/exclusive claims,
inconsistent alternative paths/diamonds, parent cycles, foreign endpoints,
incompatible active edges, malformed fields and contradictory duplicate claims.
Physical aliases and requirement bindings participate in consistency checks.
Reciprocal ratios cannot erase accumulated phase merely by restoring frequency.
Unknown cannot override stronger evidence; asynchronous and exclusive relations
are not transitively composable Same facts. Conditional gate/mux ancestry alone
supplies no exact periodic relationship or direct-sampling permission.

## Executable ownership and target boundary

Extend the shared DomainModel using ClockModel's actual producer identities,
without adding another CDC dependency engine. A closed executable relationship
record retains exact domain references, actual Clock operands independently
matched to their bindings, typed kind-specific fields, stable source identity,
structural-proof versus external-contract classification and obligations.
Legacy descriptor-only clock_relation fields and unverified metadata never
authorize sampling. Source, bridge and native checks must independently validate
these facts and preserve source diagnostics.

Relationships add no invented clock hardware or state. Flat target lowering
retains source-located relationship/environment obligations and both physical
clocks while using the verified direct-sampling permission. Comments/constraints
record an assumption, not an executed proof that unrelated electrical inputs
satisfy it. This gate does not advertise runtime certification of the external
Same/exclusion/timing obligations. Actual flat simulation witnesses use clocks
that satisfy their declared contract and prove supported data behavior.

F57.B.2 separately requires real typed instance binding operands and
instance-qualified domain/reset/crossing provenance through bridge/native IR.
Metadata-only hierarchy is insufficient. F58 owns inferred structural ports,
domain-polymorphic modules, specialization/variants and general hierarchy target
execution. A truthful transactional target rejection for out-of-profile hierarchy
is required until that support is implemented; no hierarchy HDL is claimed here.

## Focused acceptance

Source/native positives cover structural and explicit-external Same, nested
Same chains, normalized ratios, signed/wrapped phase, all six categories, shaped
Clock elements and deterministic declaration ordering. Negatives cover forged
actual operands or contract classifications, missing obligations, contradictory
aliases/diamonds, cycles, invalid arithmetic, resource overflow, and every
non-Same category incorrectly used as direct-crossing permission.

Actual flat Scala-to-native-to-target witnesses retain separate physical clocks,
registers and reset chains while demonstrating legal Same sampling. Equal
frequency, Ratio(1,1,0), Synchronous(true), gate ancestry and descriptor-only
metadata must still fail causal direct-crossing negatives. Existing clock,
reset, crossing/FIFO and descriptor stimulus profiles are affected predecessors.
No CI, broad proof campaign or physical timing certification is authorized or
claimed. No coarse F57 checklist closes from this relationship slice alone.
