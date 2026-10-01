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

### Foundation 97: compact scalar declaration API

[Compact scalar declaration API](scalar-declaration-ergonomics-v0.1-plan.md)
records the owner's 2026-10-01 request for `Real(value)` and
`Real(init = value)`, with optional argument labels, a distinct `Real()`
no-initializer form, existing dotted units and qualified optional postfix units.
It extends the existing open F-097 API-review/refinement increment, not F-160.

That amendment owns only its new F-097.B.1/C.1/D.1/G.1 descendants. Existing
F-097 parent/A-G obligations remain in the main file and require these linked
children before completion. F-092/F-093 consume accepted documentation/examples
through their existing obligations; they are not reverse prerequisites.
No new numbered increment, F-043 prerequisite or implementation start is added.
The versioned additive API gate and actual compiler/HDL parity are still required.

## Ownership and status

The main file owns its existing states, the lightweight amendment owns its
previously added descendants, the modularization plan owns only F-160 and its
descendants, and the compact scalar declaration amendment owns only its listed
new F-097 descendants.
Parent completion requires all applicable children and explicit prerequisites
across these linked documents. Planning updates do not complete checkboxes;
existing IDs and accepted evidence remain unchanged.

Owners are 42 (hierarchy), 160 (pre-43 modularization), 43 (analog generation and
shared capture), 55-58/159 (digital semantics and unified iteration), 96
(measured performance and optional Rust), and 97 (compact declaration API
refinement). No new repository, mandatory Rust rewrite, semantic redesign or
reverse prerequisite for 42 is introduced. `AGENTS.md` is unchanged.
These planning amendments are not implementation or acceptance evidence;
future implementation and closure still require their applicable qualification.
