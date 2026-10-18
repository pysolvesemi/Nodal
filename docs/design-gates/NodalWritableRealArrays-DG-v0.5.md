# Nodal writable Real arrays design gate v0.5

**Status:** Approved
**Scope:** core-library-boundary
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and relationship

The owner's standing Foundation implementation authority is:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

This implementation-selected additive extension follows
[v0.1](NodalWritableRealArrays-DG-v0.1.md) and
[v0.4](NodalWritableRealArrays-DG-v0.4.md). It changes no public signature and
closes no Foundation parent checklist. Preserve static shapes/indices, exact
units, actual ownership, incoming selected definite assignment and all existing
operator/event placement restrictions.

## Selected scalar-source contract

Retain actual typed uniform-fill-derived scalar leaves inside the existing
procedural Value, including leaves nested under scalar arithmetic, comparisons,
Boolean expressions and registered pure math. Retain the complete source Value
on conditions/selectors until bridge validation; loop/event records already
retain Values. Attach actual immutable source Values, not separately trusted
text labels and scalar overrides. Derive each token from the retained source;
missing, unused, conflicting or cyclic attachments reject. Existing depth bounds
apply. Preserve source occurrences and generated/lexical remapping.

Derive a uniform scalar only by walking the actual retained Fill/View chain:

- Check each step's input identity against the actual retained input Value and
  its result identity against the enclosing Value. Do not compare an asserted
  label to itself. Validate owner, rank, result shape, cardinality, positive
  dimensions, slice/index bounds over every legal parameter value and units.
- Fill must have shaped Real result and scalar Real source of the same physical
  dimension. Validate the actual source recursively, including its reads and
  effects. A Read root is storage and stays on the existing reference path.
- Derive the scalar and all reads from that checked chain; never trust a uniform
  flag, independent scalar payload, parameter default or rendered-text identity.
- Only after full source/bridge validation, erase pure shape structure in a
  derived serialization view. Original records/source-map provenance remain.
  Native verification checks the resulting scalar expression and actual bindings;
  it does not revalidate erased source index/view history. Provenance is not a
  native proof operand. Forged retained snapshots must fail before erasure.

This tightens an inherited pure-View label check: prior emission followed the
actual retained input and repaired emitted operand paths, so the gap was not a
shown numeric miscompile. Checked erasure must nevertheless reject malformed
input/result identities rather than relying on that repair.

## Evaluation, sharing and lifetime

Preserve fill scalar evaluation once per consuming statement. Assignment/local
initializer consumers may retain the existing immutable fill and scalar
projection captures, sharing by actual retained fill identity within that
consumer. Two distinct fills with identical text remain distinct. Reusing a
source after a write makes fresh captures; all destination writes follow source
capture. Do not duplicate F when two scalar selections come from the same fill.

For live conditions/case/loop/event expressions, this bounded extension admits
one occurrence of each actual retained fill per complete consumer expression.
Substitute its checked scalar at that reached leaf; do not emit an eager numeric
fill/read prelude. Preserve first-match, short-circuit, loop-bound-on-entry and
monitor reevaluation semantics. Count repeated tokens and distinct projections
of the same fill, across all primitive-event argument slots, by actual source
identity. Reject repeated shared-fill live sources explicitly, including a fill
shared across OR primitive occurrences in this bounded profile. Do not invent
once-per-OR caching or silently choose per-slot evaluation. Distinct fill sources
in separate monitor occurrences retain their existing monitor evaluation sites.

Repeated shared-fill live/event evaluation remains required follow-on work;
this restriction does not close it. Any later demanded-value binding must cache
on first reached use and reset per consumer reevaluation. Existing isolated
user-function calls with eager arguments do not already provide that contract.
No new function placement or broad expression engine is authorized here.

Local declaration initialization occurs on each reached lexical/event/loop scope
entry. Generated locals retain actual per-occurrence ownership. Persistent roots
remain static-only; a genuinely static checked scalar can use the unchanged
legacy scalar declaration initializer. A fill wrapper cannot make mutable reads,
time, probes, analysis or stateful operators static. Validate the original
dependency/effect tree before erasing structure, so wrappers cannot hide illegal
operator placement or unsupported user-function calls.

## Additive native initializer expression form

Add optional String initializer_form to existing nodal.analog_variable. The only
new spelling is "expression"; absent form preserves legacy scalar text, direct
scalar snapshot and shaped snapshot forms.

The new form requires an initialized scalar Variable result in a lexical local
declaration, never a persistent root or shaped/uninitialized destination.
initializer_value is canonical scalar source; initializer_kind/dimension match
its independently parsed type and existing destination compatibility rules.
initializer_reads is empty: actual immutable operands are authoritative, as in
the existing snapshot form. Reuse verifyCapturedScalarBindings,
verifySnapshotCapture and the existing source parser to require exact used scalar
captures, producer order, lexical/generated ownership and no crossed ordered
writes. Reject live storage handles, missing/extra/duplicate captures, wrong
units/kinds, foreign/future/self references and unknown form strings.

Incoming selected DA checks all initializer sources before marking the new
variable initialized. A fill/projection capture must follow its actual producer
reads, not infer initialization from an empty textual inventory. Backend form
dispatch precedes the current one-operand copy branch: selected + 1.0 with one
capture is still an expression. Reuse readCaptures/expression rendering and emit
one destination assignment at scope entry. No new expression op, source grammar,
storage registry or independent DA engine is introduced.

## Alternatives and compatibility

Chosen: existing immutable capture machinery for compound local initialization;
checked source/bridge structural erasure for single-occurrence live uniform
scalar leaves; actual fill sharing for statement consumers.

Rejected: fake storage roots; proof-only metadata; discarded eager fill graphs;
text-only scalar substitutions without retained-source validation; duplicate
fill evaluation; hidden event snapshots; default specialization; unsupported
user-function placement; a broad parallel expression framework.

Existing scalar forms, mutable storage reference rules and source maps retain
their semantics. Static-only index/dimension and Real-array capability limits
remain. Compound local expressions do not authorize mutable root initialization,
sibling-generated storage or illegal event/operator placement.

## Focused acceptance and remaining scope

Public/native/target positives cover one/multiple selected captures, exact units,
Boolean comparison initializers, lexical/event/generated locals, later input
mutation, uniform view/slice/index chains under arithmetic/math, symbolic bounds,
once-only shared fill in statement consumers, and single live fill occurrence
under short circuit/loop/event evaluation. Retain actual VA/AMS witnesses.

Negatives cover invalid bounds/rank/cardinality, forged retained identities/units,
missing/unused/conflicting typed source leaves, ownership and incoming-DA failures,
effects hidden by wrappers, repeated shared-fill live sources, new form on root/
shaped/uninitialized declarations, operand type/count/order/corridor errors and
transactional target rejection. Include prefix-similar tokens, distinct equal-text
fills, repeated source reuse and existing dependency-depth boundaries.

Run only focused new/affected cases and directly affected predecessors, with
concise source/bridge/native/target architecture review. Record executed results
in the existing implementation note, outputs in .work. No CI, full qualification,
speedup or independent simulator/consumer claim is implied. Repeated live fill
sharing, conservative array ports/views/bindings and broader generated topology/
state/lifetime remain open wherever not implemented.
