# Typed algebraic structural checkpoint design gate v0.1

**Increments:** 46 dependency checkpoint in 134/135
**Status:** Approved
**Scope:** core-library-boundary

## Required dependency and ownership

F-046.B requires genuine bounded detection of equation imbalance and structural
singularity. Its F-046.A boundary assigns the full residual/DAE machinery to F-135;
F-134 owns the typed source representation that machinery consumes. Implement the
minimum source/analysis checkpoint at those owners and reuse it from semantic lint.
The full F-134 and F-135 parents remain open beyond this checkpoint.

The public `physicalComponent`, `analogState` and `equation` signatures are already
frozen. Activate a finite typed construction profile rather than inventing a new
source API or interpreting F-032 rendered expression strings. One canonical source
record retains authored left/right expressions, their real typed DAG roots, actual
component/state identities, dimensions, source spans and analysis metadata.

## Complete finite profile

The analyzed design is one actual root module without instances or generated
hierarchy. It contains explicit `physicalComponent` scopes with Concrete,
Complete and Required contracts. Modules are not silently treated as components.
Unknowns are declared scalar Real `AnalogState` objects, including unused states;
repeated reads reference the same declaration. Equations are unguarded scalar
algebraic expressions using literals, parameters, state reads and existing typed
add/subtract/multiply/divide/negate operations.

Each component owns its states and equations. Cross-component state references,
conservative terminals/connections/contributions, unsupported expression operators,
partial/extensible/deferred contracts and unrepresented source behavior make the
profile incomplete. An incomplete graph cannot yield a structural-success claim.
Existing source inventory is preserved outside the analyzed profile. Reusable
module definitions retain authored occurrence inventories separately from emitted
typed definitions; explicit uncovered component/state/equation partitions must
match the actual emitted coverage and always force incompleteness. Malformed
identity/type/ownership data remains an error rather than an incompleteness escape.

## Native source representation and analysis

Represent source state unknowns and unordered equations as distinct typed operations
inside explicit component-owned analog regions. Reuse the existing quantity type,
arithmetic verifier and source mapping. Physical literals retain a resolved existing
unit declaration and original canonical magnitude; native verification checks the
unit dimension and finite scale conversion independently. Preserve both equation operands and F-032
authored records; independently verify correspondence and coverage. Target
legalization must reject these source-only equations explicitly until their real
lowering owner is implemented. Never omit or orient them into assignments.

The shared equation-model owner derives equation-to-unknown incidence from actual
SSA edges, with memoized dependency traversal. Parameters/literals are not unknowns.
Include declared-but-unused vertices. Run deterministic maximum bipartite matching,
retaining matched and unmatched identities, counts and actual component ownership.
Check each local component and the root's disjoint union; equal aggregate counts
must not conceal compensating local defects or disconnected singular blocks.

An opt-in native report pass serializes recomputed diagnostic incidence facts,
including valid defective models, under `nodal.analysis.analog_structure`. It does
not accept target output, and no consumer trusts an incoming report attribute.
Normal gate lint remains read-only and reports proved imbalance or unmatched square
incidence as errors; unsupported profiles remain explicit incompleteness advisories.

A full incidence matching proves structural rank only for this finite graph model. It
is not numerical Jacobian rank, consistency, solution uniqueness, DAE index,
solver convergence or simulation. Correlated coefficients can make a structurally
matched model numerically singular. No equation orientation, residual division,
reassociation or mathematical repair is introduced.

## Initialization and analysis phases

Fixed, Guess, InitialEquation, SteadyState, OperatingPoint and SolverSelected
initialization metadata remain distinct. Initializer expressions are separately
retained and dimension-checked; they do not become ordinary runtime equations.
Initial equations retain their identity and are excluded from ordinary runtime
matching. This checkpoint's complete runtime analyses are dc, operating point and
transient algebraic incidence. Initialization solving and later DAE/linearized
analysis remain explicitly unsupported, not silently inferred from runtime counts.

## Alternatives, compatibility and focused checks

Reject a parallel string-expression parser, counting equation dictionaries against
nodes, treating branch accesses as independent unknowns without topology expansion,
and aggregating uninstantiated module templates. Reuse existing source capture,
quantity arithmetic and diagnostic owners. No new public solver or waiver API.

Before F-032 authored text is materialized, identity-memoized checked size/depth
preflight rejects cyclic graphs, depth 512 or greater, and expanded text above
1,048,576 UTF-16 code units. Accepted text remains byte-for-byte compatible with
ordinary prior rendering; shared nodes are counted with their actual occurrence
multiplicity before allocation. This is a bounded source profile, not unlimited
expression expansion. Native incidence traversal itself is iterative.

Required causal witnesses are balanced matching, underconstraint, overconstraint,
a square but structurally singular graph, unused unknowns, repeated read identity,
and compensating local defects with equal global totals. Retain actual public Scala
through typed native IR and diagnostics. Mutate ownership, dimensions, duplicate
identities, missing typed coverage and initializer metadata. Unsupported profile
cases must remain explicitly incomplete. Repeated/reordered reports must be stable
and source-correlated; retain sparse graph and source-path scale controls.

## Approval evidence

The owner explicitly requested all unchecked Foundation work starting at 43 and
only the Foundation track. This essential dependency checkpoint is within that
scope, with the existing 2026-10-18 prototype policy. It does not close unrelated
F-134/F-135 deliverables or waive any F-046 requirement. Focused implementation,
validation, architecture review and verified integration remain outstanding until
recorded in the increment readiness note.
