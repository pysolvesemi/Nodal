# Nodal roadmap index

The [main Foundation and dependent-track roadmap](nodal-development-todo.md)
retains existing increment IDs, parent/child progress and historical evidence.
Read its forward plans together with the approved scoped amendments below.
The main file is not rewritten by this index.

## Current approved amendments

### Lightweight hierarchy and unified HDL iteration

[Lightweight hierarchy, unified HDL iteration and frontend scalability](lightweight-hierarchy-iteration-v0.1-plan.md)
records the owner's 2026-09-24 decisions: constructor parameters and direct child
ports, conservative `<>`, a common `hdlRange` with typed mixed-effect lowering,
safe process fusion, and measured frontend/native performance and Rust evaluation.
Its [versioned design gate](../design-gates/NodalLightweightHierarchyIteration-DG-v0.1.md)
records the approved forward language contract and outstanding validation.

For the named future API/range requirements, that amendment takes precedence
over the older two-range-only or explicit-only proposal in the main file and
older candidate plans. It does not rewrite historical API acceptance, remove
exposed compatibility forms, change completed checkboxes or waive obligations.
Its new descendants remain there; existing main-file checkboxes are not copied.

### Foundation 160: construction frontend modularization

[Construction frontend modularization and scalability baseline](construction-frontend-modularization-v0.1-plan.md)
adds **Foundation Increment 160**, the next unused numbered Foundation ID.
Its sole parent/child checklist lives in that plan; it is not a separate track.

The execution order is **completed 42 -> completed 160 -> start 43**. The ID does
not defer implementation until the end of Foundation. This is an explicit added
prerequisite for F-043.A/B, with all existing 43 obligations retained. Increment
42 has no reverse dependency on 160; it still completes its own required work.
Foundation 160 is included in the existing all-Foundation completion barrier.

The new plan owns a bounded, behavior-preserving internal decomposition, parity
and failure-safety tests, and a proportionate before/after scalability baseline.
Increment 96 retains the comprehensive benchmark tiers, further profile-guided
optimization and optional Rust comparison/adoption decision. F-096.B.2.2 now
references 160 rather than duplicating the early extraction work.

## Ownership and status

The main file owns its existing states, the lightweight amendment owns its
previously added descendants, and the modularization plan owns only F-160 and
its descendants. Parent completion requires all applicable children and explicit
prerequisites across these linked documents. No checkbox is completed by this
planning update; existing IDs and accepted evidence remain unchanged.

Owners are 42 (hierarchy), 160 (pre-43 modularization), 43 (analog generation and
shared capture), 55-58/159 (digital semantics and unified iteration), and 96
(measured performance and optional Rust). No new repository, mandatory Rust
rewrite, public syntax redesign or reverse prerequisite for 42 is introduced.
`AGENTS.md` is unchanged. The owner waived CI for this roadmap publication only;
future implementation and closure still require their applicable qualification.
