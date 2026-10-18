# Nodal conservative node arrays design gate v0.5

**Status:** Approved
**Scope:** public-api
**Scope:** core-library-boundary
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and relationship

The owner's standing implementation request is:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

This implementation-selected extension follows [v0.4](NodalConservativeNodeArrays-DG-v0.4.md)
and the generated lifetime checkpoint `be57bd4cd206fd6f8b0a57af3462cce57c1b0d4a`.
No separate signature choice is attributed to the owner. Original F43.B.1–B.3,
the lightweight hierarchy amendment and ADR0017 require analog shape/index/slice,
static ownership/bounds and shape-aware connections. This gate selects one
integrated conservative relation/child-coverage path; it does not close F43 or
make every presently rejected generated expression a required feature.

## Public behavior and exact endpoint ownership

Use existing `connect`, `<>`, `at`, `row`, `plane`, `slice`, `flatten` and `reshape`.
Admit local/local, local/child and child/child conservative array connections.
Selected child-port views and scalar child-array selections retain their actual
instance, declared boundary port, formal rank and lexical Module/Generate scope.
A child declaration alone is not an instance occurrence and is never reclassified
as a parent-owned local declaration. Detached, foreign, internal and escaped
child handles reject. Existing scalar port-current capabilities remain separate;
connectivity access does not grant probing of a child boundary current.

Each relation requires matching Electrical capabilities, rank and corresponding
finalized extents. Scalar `at` connections feed the same singleton-region child
coverage path while preserving existing F42 scalar ownership/connectivity rules.
No implicit broadcast, truncation, zero extent or dynamic topology is introduced.
Existing half-open unit-stride slices preserve rank, element contract and actual
source mapping. Prefix/reshape/flatten use the established row-major rules.

Module-owned roots remain visible in legal descendant generated scopes. Captured
views/relations carry the exact use scope; sibling/escaped views reject. Direct
active induction retains the existing shared domain/ownership proof wherever that
profile is supported. No new induction arithmetic, per-occurrence parameter
actual or nonrectangular range semantics are introduced. Fixed generated local
array declarations remain an explicit subsequent legal composition if not
implemented here; boundary ports remain module-owned. Do not call the broader
F43 generated-object obligation complete while required compositions remain.

## Deferred instance shape capture

Extend/replace the existing private NodeArray root/projection representation with
typed shape intent: declared dimensions, exact instance-port root, or a transform
of an actual input. Reuse existing expression/operation capture, not a parallel
projection registry or second proof engine. Preserve current eager local paths
where their ownership and dimensions are already known.

Instance-rooted rank is known at capture; its effective dimensions are deferred.
`slice`, `row`, `plane` and `at` retain authored parent-owned bounds/indices;
`reshape` retains authored parent-owned result dimensions; `flatten` retains an
operation instead of multiplying raw child-formal parameters in the parent.
No ordinary parent expression may contain a foreign child Param, and no current
binding/default may be frozen merely because a view was captured early.

After all overrides, reuse StructuralParameterBinding to resolve each full child
port shape through the exact finalized instance environment, including dependent
defaults. Preserve actual parent parameter identity, checked Integer DAG structure
and bounds. Reuse existing parent literals/parameters/actual expressions and
materialize any missing derived static DAG nodes without inventing parameters or
specialized module definitions. Evaluate retained transforms into existing shape
snapshots only after that substitution. An override authored after a view or
connection changes its entire effective shape before validation. Reject cycles,
missing/forged references and incompatible finalized shapes independently.

## Shared native relation and child coverage

Generalize the existing ConservativeConnectivity owner to resolve either endpoint:
actual SSA reference, physical root/projection, optional exact instance/declared
port, declaration scope and use scope. One two-sided relation replaces the
asymmetric whole-child binding interpretation. Whole binding is the whole-region
case, not a second representation. Keep one full instance-terminal-array root per
exact instance/port/scope and independently compare its complete effective type
with ParameterModel substitution; a partial actual cannot define the full bus.

Permit ordinary verified projections over that root. Native verification derives
all maps, shapes, ownership and coverage from actual operations and symbols;
source metadata and an asserted coverage flag are not evidence. Scalar child-array
connections participate as singleton relations. Existing scalar-only connection
semantics remain in their current shared owner.

Each child boundary array must be covered exactly once by incident endpoint
regions. This preserves duplicate-whole-binding rejection. Local joins are
physical net relations and do not acquire child coverage or sequential-storage DA
rules. Port coverage is not proof of complete component equations.

The selected compact coverage proof admits exact whole roots, root-coordinate
boxes from prefix/axis slices, and exact root-ordinal intervals where contiguity
is proven. Reshaping an already selected region preserves its image. An enclosing
flat interval is not an exact certificate for a column or other noncontiguous
selection. Existing verified maps remain usable on parent actuals; child-side
coverage has this additional finite proof obligation.

Partition using authored boundary cuts, actual endpoint identity and existing
all-legal ordering proofs. Check missing, multiply covered and unproved regions;
never enumerate symbolic lanes or compare evaluated parameter defaults. Reuse
canonical expression/shape proofs rather than introduce general algebra solving.
Memoize repeated active-region subproblems and avoid recursion proportional to an
unbounded source rank. Worst-case work may grow combinatorially with authored cuts
and rank; no whole-pipeline linearity claim is permitted. Any explicit proof-work
resource limit must reject transactionally and be documented/tested as a resource
boundary, never presented as a semantic proof.

## Physical alias consumers and target layout

Use the same relation for native topology/alias queries and target emission.
Where a scalar consumer needs alias membership or an inverse ordinal map, derive
it from the actual projection chain and exact region certificate. Preserve opaque
selector identity; overlapping bounds or equal defaults do not prove equality.
Do not expand buses into scalar union-find lanes, invent distinct physical nodes
when aliasing is unknown, or claim general symbolic cycle closure. An unsupported
required alias proof must report a precise capability diagnostic. Keep source
occurrence, physical net identity, instance-port incidence and generated scope
separate.

Allocate one complete ascending electrical bus for each exact child array port
using its finalized full shape and bind the child once. Lower each authored
relation through two actual root maps over the same logical ordinal, reusing the
existing structural alias helper and checked map-stage renderer. Local/local
relations need no user child instance; child/child relations connect their two
full buses; partial bindings connect selected regions. A whole-binding direct
actual optimization may remain only when independently equivalent. Preserve
source maps for both sides, target orientation, per-occurrence identity and F42
flow incidence. Reuse real target grammar and transactional output; helper names
or comments confer no privileged acceptance. No new numerical ideal-wire model
or simulator qualification claim is introduced.

## Alternatives, compatibility and focused validation

Rejected alternatives: copy a partial actual type as the child's full port type;
freeze defaults at view capture; treat child declarations as parent roots; maintain
separate local-join and partial-binding engines; use array storage DA for physical
coverage; approximate columns by enclosing intervals; enumerate lanes; silently
short nets when alias proof is uncertain; trust bridge coverage metadata.

Keep the public signature/inventory unchanged. Versioned internal snapshots and
native operation contracts may evolve in this same implementation checkpoint,
with independent verification and directly affected source/native regressions.
The target baseline remains generic VAMS2023. Existing external consumer limits
remain separately documented; no unexecuted consumer success may be claimed.

Focused positives: adjacent child partitions, row/column and multi-axis partitions,
override after view/connection, dependent child defaults, local/local and
child/child joins, scalar child-array selection, distinct instances of one reusable
definition, exact generated use scopes, symbolic/singleton/boundary shapes and
compact large cardinality. Negatives: missing/duplicate/unproved overlap,
foreign/sibling ownership, unsupported alias proof, default freezing, wrong
shape/discipline/rank, forged effective root/map and invalid bounds even in an
empty generated domain. Verify actual public Scala→native default gate→VA/AMS,
repeat/reparse/source-map correspondence and only affected predecessors. Record
commands, observed limits and concise architecture review in the existing
implementation note. No CI, full qualification or separate evidence-closure PR.
