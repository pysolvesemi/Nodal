# NodalDigitalDrivers-DG v0.1

**Status:** Approved
**Scope:** F55 shared combinational driver, coverage and dependency validation
**Decision:** actual SSA dependencies and canonical physical storage regions

## Authority and compatibility

The explicit all-unchecked-Foundation implementation authority recorded in
[NodalDigitalExpressions-DG v0.1](NodalDigitalExpressions-DG-v0.1.md) covers this
required F55 prerequisite. This does not claim separate user selection of an API.
Existing assignment spellings and exact type rules remain unchanged. Previously
accepted consumed-undriven wires, conflicting ordinary writers and combinational
feedback now reject; an actual complete driver repairs an undriven source, rather
than a fabricated default. Unused declarations remain legal.

## Contract and ownership

1. The canonical DigitalAssignmentModel derives a bounded graph from actual SSA operands plus implicit storage write/read edges. DigitalStorageModel invokes it through the existing module verifier. Metadata, names and cached proof flags cannot substitute for an edge or physical identity.
2. Ordinary writes to one physical root must be proved disjoint. Every consumed wire/output region needs complete driver coverage. Unknown overlap, incomplete coverage and cyclic combinational dependencies reject. Distinct port_value aliases resolve to the actual port declaration, so spelling aliases cannot conceal writers or feedback.
3. ShapeModel owns physical projection, relation and cut/partition coverage proofs. Direct static projections retain lane-sensitive relations without enumerating physical lanes. Computed aggregate inputs, Struct get/make, aliases and collection element/accumulator transfer remain conservatively whole-input dependencies where a more precise canonical certificate is unavailable.
4. Collection dependencies use the validated collection signature, actual inputs/captures/yield and seed/recurrence operands. An unused map capture is not a read. A block argument never becomes independent storage or a constant merely because its first seed is constant.
5. DigitalInterfaceModel exports dependencies from its independently verified registry. All typed Interface/net values seed the graph, including graphs with no ordinary assignment. Actual directional connections, driver data/enables, role aliases and split provenance participate. Resolved pass-through unions are physical aliases, not causal cycles; resolved multiple-driver policy remains with the canonical Interface owner.
6. Input/inout and existing Interface/resolved boundary sources retain their current semantics. This gate does not impose a new global Interface definedness policy. Registers terminate same-cycle reads; register_next remains checked intent and is not a combinational writer. Its actual RHS still seeds ordinary dependency and coverage checks; the state target is not a feedback edge. Next-state priority/reset behavior remains F56 work.
7. Unsupported capability-only producers cannot become executable by feeding an assignment. All existing type, enum, schema, lexical/module ownership and source provenance checks still apply independently.
8. Default per-query bounds are 65,536 nodes, 262,144 edges, 16,384 physical relation queries and 4,096 shape-proof work units per relation/coverage query. Exhaustion rejects with a diagnostic before assuming a proof. Expansion and cycle detection are iterative. Relation construction can be quadratic in authored regions within these bounds; no whole-pipeline linearity or speedup claim is made.

## Finite profile and remaining work

This checkpoint covers direct-module continuous assignments and canonical typed
Interface/net dependency graphs. Ordinary read-only Expr projections do not gain
write authority. Public writable Signal selections require a separate additive
capability contract. Partial writes through distinct port-value aliases remain
unsupported where canonical shape rebasing is unavailable. Conservative aggregate
coarsening can reject otherwise independent lane transformations; it must never
invent independence.

Bounded hardware loops must extend this same owner with actual iteration-domain,
ordering and cross-iteration facts. A body-level acyclic graph alone is not proof
of loop legality. This gate adds no clocked execution, memory behavior, scheduling,
procedural/unrolled lowering, HDL emission or target qualification.

## Alternatives and architecture

Reject name-keyed graphs, independent Interface registries, duplicated shape
algebras, transitive copied read sets, per-lane expansion and assumed zero drivers.
Reuse actual canonical owners and query-local memoized edges. Materialization
inventory remains a separate derived consumer of the same real SSA graph; naming
intent cannot sever dependencies. Exact complete Struct ABI remains unchanged.

## Focused acceptance

Require causal positive/negative/boundary cases for actual public scalar and Vec
write/read graphs, duplicate writers, consumed-undriven versus unused storage,
named/keep feedback, port aliases, register boundaries and exact type identity.
Native cases additionally cover disjoint/overlapping/unknown projected regions,
coverage, collection captures/recurrence, standalone Interface/resolved feedback,
conditional enable edges, acyclic multiple drivers and alias-only unions.
Negatives must reach the intended NODAL-DIGITAL-055-010 diagnostic; unrelated parse
or type failure is not evidence. Positives retain nonmutation and normalization
idempotence. Rebuild affected storage, Interface, Shape, value/type, collection and
materialization owners and replay actual tracked-source bridge witnesses.
Execution results belong in the increment readiness record; this approved design
is not a claim that pending tests or F55 are complete.
