# Nodal writable Real arrays design gate v0.2

**Status:** Approved
**Scope:** core-library-boundary
**Increment:** Foundation 43
**Decision date:** 2026-10-18

## Authority and relationship

The owner's implementation authority is unchanged:

> start implementing all foundation unchecked increment items in nodal repo from increment 43 and continue until all foundation checklists are completed. Don't start another track just complete foundation track.

This is an implementation-selected extension of
[v0.1](NodalWritableRealArrays-DG-v0.1.md), not a separately owner-selected
signature. It adds no public method, changes no storage class, and closes no
Foundation parent checklist. The earlier gate's typed storage, snapshot, shape,
quantity, alias, definite-assignment and lexical/generated ownership contracts
remain binding.

## Exact event-independent procedural contract

An ordinary event-free analog procedure may use the same admitted typed Real
storage, static projections, immutable reads, fill values and exact-shaped
assignments as an event-containing procedure. Its ordinary statements execute
in authored order on each evaluation of the existing analog process. A fill
scalar is captured once per consuming statement evaluation. A copy reads every
RHS lane into its immutable snapshot before writing any destination lane.
Lexical initializer execution remains at scope entry; root declaration lifetime
is not rewritten as ordinary assignment or as an event reset.

Use one shared semantic procedure admission path for ordinary and generated
procedures. Preserve existing actual owner/scope proofs, control-flow restrictions,
loop placement and bounded-count checks, nearest-loop exits, generated-history
restrictions, supported guards/analyses and target integer bounds. Preserve the
existing placement rules for contributions and stateful analog operators;
admitting numeric array storage does not admit ddt, idt, noise or transfer
operators into procedural bodies.

Native captured-expression binding, kind/unit validation and static proofs apply
whether or not the procedure contains an event. Event placement remains a
separate validation responsibility. Target preparation and rendering consume
those verified semantic records; event presence and exact operation counts are
not substitutes for storage capability or expression validation.

## Compatibility and remaining limits

Existing scalar and event-controlled programs retain authored execution order,
source identity and target semantics. The ordinary and generated paths share
the same payload validator and target admission rather than separate operation
count recognizers. No additional simulator process or scheduler is introduced.
There is no implicit initial_step wrapper or zero-initialization.

Persistent shaped root declaration initializers remain rejected by this version.
A later explicit target contract must preserve declaration-equivalent lifetime.
Array-derived control/event predicates, selectors and loop bounds still need
actual typed reference bindings; this gate does not waive that required F43 work
or admit metadata-only lookup/eager snapshots. Static selections and the existing
signed-32 target layout bounds remain in force.

## Alternatives

Chosen: factor existing captured-expression validation and procedural admission,
then reuse the existing ordered renderer and compact snapshot/map loops.

Rejected: removing the event guard without its payload checks; wrapping ordinary
statements in an artificial event; adding another operation-count whitelist;
creating a parallel storage registry; changing declaration initialization to
initial_step or analog-initial execution; broadly admitting analog operators
because their surrounding procedure now supports arrays.

## Focused acceptance and architecture review

Positive public witnesses cover ordinary whole fill assignment to uninitialized
storage, overlap-safe copies, noncontiguous views, symbolic quantity units and
owned generated ordinary storage. Independent native negatives cover forged or
missing scalar capture bindings in event-free procedures and unsupported
owner/control/operator combinations. Check actual default semantic gates,
deterministic target emission and exact causal target rejections.

Run directly affected scalar, event and generated predecessors, with boundary
checks on existing static/target limits. Review that native validation no longer
short-circuits merely because events are absent, target admission is derived from
actual typed records, and no execution/lifetime order changes. Keep logs under
`.work` and concise results in the existing implementation note. No CI, broad
qualification or simulator/numerical completion claim is required.
