# Nodal reset execution design gate v0.2

**Status:** Approved
**Scope:** public-api

## Authority and bounded allocation

This additive F57 implementation gate follows v0.1 and the owner's instruction
to implement all remaining Foundation work. It fixes executable reset semantics
before their implementation; it does not close any F57 parent or child. Prior
unpublished reset implementation was lost during workspace recovery. Its reported
checks are historical, not evidence for this reconstruction.

The initial executable profile uses scalar owned Clock and Reset boundaries.
Shaped boundaries, remaining relationships, protocol crossings, gates/muxes and
low-level events remain required work, not silently waived by this profile.

## Public meaning and effective binding

Existing None, Sync, Async and AsyncAssertSyncRelease policies retain their
meaning. Raw Async never means that release is safe. Even an otherwise unused
executable scalar domain with raw Async must reject unless the reset producer
provides release evidence applicable to the actual receiving clock and edge.
Additive `ResetPolicy.AsyncExternalRelease` explicitly asserts that the external
provider guarantees safe release for that receiving clock and edge. This is a
retained source-located environment obligation, not a proved synchronizer.

`ClockDomain.reset` remains the declared reset reference. The effective reset
used by actual state is separate. AsyncAssertSyncRelease creates an independent
chain for its domain; it does not replace the declared raw identity used by
stimulus references or generated-child construction. A generated domain inherits
its actual parent's edge. Its reset policy/polarity derive from the actual reset
capability; missing facts never receive arbitrary defaults.

`Rdc.sync(reset, stages)` is a construction form bound to the actual receiving
generated domain. `Rdc.sync(reset, to, stages)` is an explicit transfer and can
form an intentional serial cascade. Reusing a construction-form producer for
incompatible receiving domains rejects. The finite initial stage range is 2–32;
resource bounds apply before allocation. Stage errors and forbidden construction
effects poison the transaction, including when user code catches the exception.

DomainHarness uses an internal descriptor-only capture entry point to retain
existing selected-domain stimulus validation, including invalid stage/reference
negatives. It does not allocate executable typed reset proof. Ordinary inspect,
capture and emission remain strict; a stimulus snapshot cannot acquire execution
safety by crossing the bridge without the required actual producers and bindings.

`ResetController.combine` combines actual reset capabilities by asserted OR,
normalizing their explicit polarities. It is nonempty. Distinct physical roots
with different polarities may compose, but conflicting interpretations of one
physical root and reconvergent independent release chains from the same root
reject. Safe release must remain applicable to the consuming clock and edge.
A wrapper or copied string does not confer reset safety.

## Typed producer and proof ownership

Native operations have actual SSA edges and one Reset capability result:

- reset_boundary(rawReset) records polarity and raw/external release. External
  evidence names its release domain and therefore exact receiving clock/edge.
- reset_sync(inputReset, actualClock) records destination domain, polarity and
  stages. Independent instances remain independent physical state.
- reset_combine(resets...) records output polarity and actual input lineage.

DomainModel provides a clock-only query to avoid recursive dependence between
binding validation and reset proof. ResetModel owns a bounded producer graph;
source capture and native ingress independently validate it. Native physical-root
identity includes polarity. Generated-domain parent graphs reject cycles,
including multi-node cycles and resetless bindings. Same-leaf foreign domain
paths cannot alias local owned domains. Owned unattributed clock inputs can be
explicitly bound, including aliases of one physical input. Existing attributed
clock inputs are reusable only through explicit generated-parent ancestry, not
through equal frequency or unrelated foreign-domain attribution.

Resolved source snapshots require the actual clock source even without state.
Missing or malformed operands must reject, not omit an executable binding.
Declared and effective reset identities, source ownership, policy, parent and
release facts survive the bridge. Raw or projected Sync release may not be
silently applied to unrelated receiving clocks.

## Target behavior and finite verification

A release chain asynchronously asserts all stages and shifts deassertion on the
receiving domain's selected edge. After N receiving edges the final stage releases;
a consumer at that same edge still sees the old asserted reset and can update on
the following edge. Reassertion is immediate, even with a stopped clock. A child
using its own release chain can release while its parent clock is stopped.
Explicit serial chains retain their producer-to-consumer latency.

Lowering plans transactionally before output, emits actual owned independent
state, and retains external-provider clock/edge assumptions. It does not invent
startup initialization: behavior before a real reset assertion is unspecified.
CSE/canonicalization must not merge independently authored release chains.

Focused checks cover both edges/polarities; reassertion; stopped-parent/live-child;
serial cascades; mixed-polarity roots; declaration/effective separation; malformed
arity/type/identity/parent/source fields; cycles; raw Async with and without state;
release-to-wrong-clock/edge; shared-root reconvergence; and bounded expansion.
Original F12 raw-Async and stimulus-reference negatives remain required. Target
claims need actual source-to-native-to-SystemVerilog simulation, not only textual
shape checks. No physical metastability qualification, CI or exhaustive matrix is
claimed. Ancestry traversals and retained lineage copies are charged or bounded.

## Required shaped physical control extension

The next reset slice extends actual owned physical boundaries to statically
proved scalar projections of finite Clock/Reset shapes. It reuses the existing
shape snapshot and native ShapeModel projection proof, not a reset-specific
projection representation. A shared physical-control resolver owns boundary
identity: actual local input port, scalar element identity/offset, capability
kind and scope. Domain clock proof and reset lineage consume that same identity.
Two different elements of one reset array are distinct roots; equivalent static
projection chains to one element remain the same root. Existing attributed-domain
constraints and clock-edge proof remain applicable.

The target uses a bounded fixed packed input for a finite control shape, extracts
the proved scalar element and converts only Clock elements to target clock type.
It does not reinterpret arbitrary bits as trusted control or guess an index.
Malformed, dynamic, foreign, non-scalar or target-unresolved projections reject.
Source and native ingress independently validate authoritative shape facts.
Parameter-shaped controls must follow actual shape proof and target materialization
constraints; this bounded fixed-shape slice does not silently close wider required
relationship, propagation or shaped support. The existing projected-clock
predecessor is upgraded to actual binding/state execution, not changed to a
rejection merely to make the scalar implementation pass.

For shaped Sync declarations, aggregate port attribution is not per-element
ownership. The actual owned Sync domain's existing clock-only physical binding
must directly consume that exact reset boundary result. This supplies declaration
ownership without a parallel element-domain map or reset-proof recursion. Claims
for the same physical reset element must agree on actual clock and edge; distinct
elements may have independent clocks/edges. Scalar Sync ownership remains unchanged.
