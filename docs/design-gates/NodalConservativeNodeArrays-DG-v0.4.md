# Nodal conservative node arrays design gate v0.4

**Status:** Approved
**Scope:** public-api, core-library-boundary
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and scope

The owner's standing implementation request is:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

This implementation-selected behavioral extension follows
[v0.3](NodalConservativeNodeArrays-DG-v0.3.md), published as `b534451`.
No separate signature approval is asserted. Extend existing `hdlRange` with
source-generated structural topology: multiple child templates/connections,
generated local scalar Electrical nodes, nested rectangular scopes and direct
active-induction selection of existing owned arrays/views. Reuse whole child-array
bindings with invariant actuals and invariant parameter overrides. No new public
loop syntax, overload, collection/yield or dynamic topology contract is introduced.
Full Foundation 43 remains open while required profiles are unfinished.

## Exact ownership and range contract

Capture each callback once, including an empty domain. A reusable child definition
owns its own parameters, terminals and body; the enclosing parent's active range
belongs only to the attached instance occurrence. A child may not capture the
parent induction in its reusable defaults or internal expressions. Retain source
identity and construction rollback on failure. An instance handle and its bindings
belong to the exact lexical Module/Generate scope; sibling or escaped handles
reject. Resolve module definitions in the top-level definition namespace, parameters
in their actual owning module and instance symbols only in the immediate scope.

Use the existing positive half-open domain i = lower + ordinal * step and its
checked count envelope. Induction is an actual optional i64 Generate block argument,
not a name-based proof. Legacy argument-free generated analog bodies remain valid.
Generate is a native lexical SymbolTable so its direct instance symbols are lawful.
Bounds remain module-owned static Integer DAGs; nested bounds are rectangular.
Preserve existing native legacy directional-loop behavior outside this extension.

A selected direct induction must be lexically visible at the use, share the actual
module owner and have positive step. For a possibly nonempty domain, prove a
nonnegative lower bound and upperExclusive <= extent by exact owned DAG identity
or conservative whole-envelope ordering. A proven empty domain has no attained
coordinates, including for a negative source origin, but does not waive
shape, type, ownership, child-reference or binding validation. Nonzero origins and
positive strides retain source indices; occurrence ordinals are separate. Existing
literal/static selection proofs remain unchanged. General induction arithmetic,
nonrectangular bounds and per-occurrence parameter actuals remain unfinished
compositions unless independently implemented with the same shared proof owner.

## Shared native and target representation

Keep one GeneratedRegion/Generate owner, one parameter proof engine and one neutral
shape-map representation. Derive lexical structural-scope facts from actual SSA and
symbols. Each scope validates its direct child bindings, missing/duplicate ports,
local nodes and connections. Key coverage by actual InstanceOp, not display names.
Do not flatten descendant templates into the module union-find or replace SSA
across sibling scopes. Exact same-root/map/context selection may normalize within
its scope; uncertain inter-occurrence overlap does not prove alias or disjointness.
Selected local port-current incidence remains distinct from physical node identity.

Emit compact module-scope structural generates with one reusable child definition
and one statement per source template. Parent arrays keep ascending row-major
vectors. Render source induction and ordinal distinctly, using checked signed32
structural target carriers without narrowing existing signed64 source/native or
legacy direct target paths. Reuse immutable projection stages and the v0.3
single-internal-net alias helper where a real net join needs it. Never substitute
numeric ideal-wire equations, runtime assignments or per-lane source expansion.

A derived structural scope layout must reserve per-occurrence declaration lifetime
for subsequent persistent storage work. Existing supported analog payload may
coexist only while preserving procedure/continuous-read ordering, operator state
ownership and once-only effects. Do not move child instances into analog execution,
or remove generated persistent-initializer rejection before its declaration
lifetime is actually implemented.

Extend the existing target grammar with ordered lexical scopes, ordinary child
instances, multiple statements, local declarations and nested rectangular loops.
Reuse existing expression/parameter/analog parsing. Alias helpers remain verified
by actual shared-net definition, not a name privilege. Preserve independent native
and target bounds/type/ownership checks. Target parsing cannot recover arbitrary
unrepresented source intent; exact emitted map correspondence remains a backend
obligation. Generic language semantics are VAMS2023; no independent consumer or
simulator acceptance follows from internal parsing.

## Alternatives, compatibility and focused validation

Chosen: focused lexical-scope and direct-induction extensions at existing capture,
parameter, shape, connectivity, hierarchy and target owners. Rejected: a new range
DSL/registry, fixture-shaped one-child whitelist, default-value specialization,
module-global generated endpoint union, per-instance cloned definitions and lane
unrolling. Retain old scalar hierarchy, array bindings, generated analog/state
profiles, source identities and transactional diagnostics.

Focused positives cover symbolic and compound-exact bounds, empty/singleton/nonzero
origin/stride, nested axes, multiple children/connections, generated local nodes,
shared parent scalar endpoints, static views, invariant child parameters and whole
array bindings. Child definitions constructed inside a range retain their own
ordinary definitions and nested ranges. Actual Scala/native/VA/AMS witnesses must
retain compact structure and deterministic identities.

Negatives cover escaped/sibling/foreign induction, same-name symbol confusion,
missing/duplicate ports, invalid owner/type/rank/domain/count metadata, runtime or
unproved indices, child-default capture, cross-scope overrides, invalid target
carrier/stage dependencies and malformed alias connectivity. Empty loops cannot
conceal malformed source. Run only focused changed-path and affected predecessor
checks, record results in existing implementation notes and keep raw logs in
.work. No CI, exhaustive qualification or new evidence ledger.

Remaining required profiles include generated persistent-state initialization,
partial child-array bindings/broader joins, richer induction arithmetic and
per-occurrence parameters, cross-range storage, remaining operator/state/result
fan-out and shared-live-fill demand lowering where required by full F43 scope.
This gate selects an implementation checkpoint; it does not close those items.
