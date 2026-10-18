# Nodal conservative node arrays design gate v0.2

**Status:** Approved
**Scope:** public-api, core-library-boundary
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and relationship

The owner's standing Foundation implementation authority is:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

The additive signatures and implementation below are selected within that scope,
not separately owner-selected signatures. This extends
[v0.1](NodalConservativeNodeArrays-DG-v0.1.md) with local conservative views and
scalar physical endpoint mapping. It does not close Foundation 43 or claim array
ports, bulk connections, generated topology or whole-array hierarchy binding.
Those required features remain open and will build on this shared shape layer.

## Public contract

Extend the existing module-local Electrical NodeArray carrier:

```scala
final class NodeArray[D <: Discipline] private[nodal] (...):
  def at(indices: Dimension*): Node[D]
  def row(index: Dimension): NodeArray[D]
  def plane(index: Dimension): NodeArray[D]
  def slice(lower: Dimension, upperExclusive: Dimension,
            axis: Int = 0): NodeArray[D]
  def flatten: NodeArray[D]
  def reshape(dimensions: Dimension*): NodeArray[D]
```

Dimension remains Int or Expr[Integer]. Row requires rank two and selects the
first axis; plane requires rank three and selects the first axis. At remains
full-rank scalar selection. Slice preserves rank, element contract and all other
axes, uses zero-based axis and half-open unit-stride bounds, and requires
0 <= lower < upperExclusive <= extent over every legal parameter value. Empty
shapes remain illegal. Slice length retains the canonical symbolic difference;
reshape requires exact cardinality proof and flatten uses logical row-major order.

A view is an immutable structural reference to the same physical net storage,
not a new declaration, a numeric Expr, mutable Variable, Signal or digital port.
Only an owned root performs declaration registration/context checks. Views and
selections may occur wherever existing static selections may occur; they do not
permit runtime or generated indices, foreign ownership or new disciplines.
Root dimensions, finite signed-64 cardinality and all existing static proofs
remain authoritative. No parameter-default specialization is introduced.

## Shared shape and physical identity

Use existing structural shape-index/view records, their actual declaration or
expression input, and the dependency scheduler. Do not add a separate node-view
registry or coerce conservative capabilities through numeric/storage carriers.
Expose only the element-neutral projection traversal and local transformation
proofs needed by existing consumers. Conservative connectivity owns physical
endpoint identity; numeric storage keeps its own rights and DA owner.

Native verification follows actual acyclic SSA projection inputs to an owned
NodeArray root and verifies every intermediate mapping, owner, kind, rank,
dimension and bound. ShapeIndexOp, ShapeViewOp and ShapeSliceOp remain the actual
projection operations. Arbitrary shaped producers do not become conservative
roots merely because their element type is TerminalType. No supplied path,
ordinal spelling or metadata field grants identity or proves a mapping.

A scalar selection resolves to the actual owned root and a canonical root map.
Direct selection and row/plane selection of the same coordinate must normalize
together. Fixed selections through flatten/reshape/slice and equivalent composed
maps must also agree. Identity/full-range transformations preserve their input;
reshape/flatten preserve logical ordinal. Use checked literal arithmetic,
identity operations and exact structural cancellation where justified, with
actual parameter declarations and captured expression roots as opaque atoms.
Independent roots with equal text/defaults/envelopes remain independent. No
unbounded algebraic solver or symbolic lane enumeration is introduced.

Preserve root coordinate facts when available, including existing disjoint-axis
proofs, and use conservative map bounds where needed. Distinct selections on the
same root require a proof of disjointness; unresolved possible aliases reject
rather than becoming separate physical nets. Repeated equivalent selections
share the deterministic physical endpoint and retain every authored source use.
Source occurrence, intermediate view provenance and physical identity are distinct.

Existing scalar connections, V/I, contributions, branches and immediate-child
scalar-port actuals consume the resulting scalar endpoint unchanged. Sparse
selected lanes do not prove complete-array component coverage. This extension
introduces no array boundary-current incidence or bulk connectivity semantics.

## Target layout and evaluation

Keep v0.1's current local unpacked electrical net declaration and zero-based
row-major physical ordinal. Array-port vector layout and exact instance bindings
need a separate versioned transition; do not silently emit unpacked ports.

Lower actual verified maps compactly using the shared projection-program owner.
Static stage values may be emitted as ordered module-level localparam integer
constants with collision-safe names and exact dependencies. They are elaboration
constants, never mutable procedural temporaries or numeric runtime snapshots.
Existing direct node-array declarations/selections retain their checked signed-64
profile, including compact very-large extents. New composed maps that require
Integer localparam stages use the shared mapper's signed-32 target limits; a
proved fixed/direct-equivalent map may avoid stages without skipping source
verification. Reject unsupported stage widths explicitly rather than narrowing
the accepted direct profile.
No map stage may depend on a voltage/current probe, time, mutable storage or a
parameter default substitution. Deep composition must not duplicate an entire
previous expression at every step. Preserve target signed-width overflow checks,
source maps, deterministic output and transactional rejection.

The strict target parser must independently validate the emitted constant-map
syntax, declaration-before-use, static integer dependencies and concrete
overflow/selected-endpoint bounds. Correlated all-parameter symbolic map bounds
remain native proof obligations; the target parser is not a new algebra engine.
Reject malformed/missing stage dependencies and bare array scalar uses. A target
constant does not bypass native source ownership or static shape obligations.
The generic repository language baseline remains Verilog-AMS 2023. These net
and localparam constructs also occur in the 2.4 grammar; that fact does not
downgrade other emitted features, such as live event tolerances, to 2.4 semantics.
The actual OpenVAF versions already tested reject all arrays. No independent tool acceptance
or numerical equivalence is claimed from internal parsing.

## Alternatives and focused acceptance

Chosen: reuse the existing shape graph plus an element-neutral verified mapping
facade and consumer-specific conservative identity. Rejected: per-view/per-lane
net declarations, rendered-text aliases, copied storage engines, default-based
identity, exponential source substitution, runtime map variables and numerical
branch equations used as fake physical aliases.

Focused positives cover direct/row/plane equivalence, literal and symbolic views,
nonzero slices, reshape/flatten chains, repeated provenance, selected scalar
hierarchy actuals and deterministic VA/AMS output. Negatives cover rank, bounds,
cardinality, owner, root capability, forged intermediate input/result/type,
possible aliasing and target constant overflow/dependency/range failures.
Exercise a representative deep compact chain and retain applicable predecessor
checks. Report measured facts separately from complexity expectations; no claim
that every pre-existing lookup becomes linear is intended.

Record actual commands, results and limitations in the existing implementation
note, with raw output under .work. No CI/full qualification is authorized.
Conservative boundary arrays and instance mappings, generated selections/
connections/instances, broader state/result fan-out and repeated shared-fill live
consumption remain required open work where not implemented.
