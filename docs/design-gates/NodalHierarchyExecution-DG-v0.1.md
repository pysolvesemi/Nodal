# Nodal hierarchy execution design gate v0.1

**Status:** Approved
**Scope:** implementation-internal
**Owner:** F-058.B.2 concrete digital normalization and target checkpoint

## Boundary and canonical owners

This additive checkpoint implements real reusable digital module emission after
the mandatory closed-root occurrence proof established by
[clock/reset execution v0.8](NodalClockResetExecution-DG-v0.8.md).
It does not change the public default, inherited or typed named-domain syntax.
The existing construction snapshot, native `HierarchyModel`, `DomainModel`,
assignment dependency graph and Clock/Reset/Crossing proof owners remain
authoritative. A target variant is a read-only emission plan, not a new mutable
module registry, physical binding certificate or cloned occurrence graph.

The source gate still validates every definition's structure and cycles, and
every occurrence in the selected root's closure. Unselected well-formed templates
are not execution evidence and are not emitted by this hierarchy target profile.
Legacy rootless flat target units retain their previous behavior.

## Polymorphic normalization

The existing `nodal.sequential_update` operation has two closed forms:

- Concrete: the existing exact local Clock/Reset operands and exact edge,
  reset policy and polarity fields remain required and independently verified.
- Formal: the exact local unresolved requirement owns local
  `requirement_clock` and, for initialized state, `requirement_reset` operands.
  Edge, reset policy and polarity are all absent. Partial material metadata or
  foreign physical operands reject. Mandatory occurrence proof discharges the
  actual edge, reset presence, polarity and release lineage for every instance.

Normalization preserves generic shared definitions. It may materialize an
otherwise absent local formal projection when initialized state demands it;
projection absence is not evidence of resetlessness. It never copies parent SSA,
selects the first instance's material semantics, creates synthetic local physical
bindings or inserts another reset synchronizer to replace inherited release.
Normalization and target lowering remain transactional on detached candidates.
The target transaction normalizes all definitions inside its one private root
candidate and re-verifies that root before emission. It does not clone the entire
design once per definition. The standalone normalization entry retains its own
clone/verify/commit boundary.

## Inferred ABI and reusable target definitions

Each emitted definition retains its authored data ports. Only demanded formal
domain capabilities add target-local input ports named
`__nodal_<requirement>_clock` and `__nodal_<requirement>_reset`, with deterministic
collision handling and requirement metadata. Neutral combinational and analog
modules do not acquire clocks because they inherit `Module`.
Semantic endpoint proof alone does not force an unused physical input: a level
synchronizer needs only its destination controls; Gray destination payload
stages have no Reset input unless another actual consumer demands it.

Actual `hw.instance` operands carry the exact parent bindings and typed digital
input connections; child outputs retain actual result edges. Independent
instances share one target definition without sharing state. Forwarding a domain
through another module adds wiring, not a new physical clock or reset chain.

Target definitions are partitioned by demanded material edge/reset semantics,
not clock identity, instance path, frequency, parameter default or override value.
A single material profile retains the source module symbol; multiple profiles
receive deterministic `__domain_<ordinal>` names ordered by material signature.
The target plan retains original definitions and emits one body per material
profile. It neither flattens the hierarchy nor specializes once per occurrence.

## Finite profile and follow-on boundaries

This first checkpoint admits concrete immediate-child input/output data ports
and the existing fixed scalar/whole-packed digital target operations. Every
emitted child input needs an explicit typed connection. Source and native
capability checks, exact drivers, crossing/reset proof and independent state
identities remain unchanged.

Symbolic parameters and shaped overrides, structural generated hierarchy,
layout-independent recursive named-field-vector adapters, Interface roles,
resolved inout and conservative/signal-flow target boundaries retain their
separate required F58 children. Unsupported requests reject before publishing
target output. Existing conservative hierarchy emission is not replaced by the
digital path, and scalar electrical flattening is not a digital hierarchy
implementation. Full portable-Verilog configuration/emission remains F65-owned.

The existing hierarchy limits bound occurrence inventory, depth and dependency
edges. Target body expansion is additionally bounded by the existing authored
and generated operation budgets. Variant bodies are emitted only for distinct
material contexts, not for every clock origin or instance.
Definition/material-signature indexes select variants without a pairwise scan
over unrelated definitions; actual binding lookup is indexed within each
represented occurrence.

## Focused acceptance

Required checks cover shared definitions with independent clocks/state, material
edge and reset-polarity variants, resetless uninitialized state, nested forwarded
bindings, absent-but-required Reset projections, and actual `hw.module`/
`hw.instance` output. Negatives retain swapped actual operands with unchanged
dictionaries, initialized resetless state, missing/duplicate bindings, forged
material fields, incompatible ownership and transactional unsupported symbolic
target requests. Direct F56/F57 sequential, hierarchy and crossing predecessors
remain applicable.

Actual source-to-native-to-SystemVerilog/Icarus witnesses and measured repeated
definition observations belong in the F58 readiness record. This gate records
the contract, not a claim that tests, target execution or F58 acceptance already
passed. No CI, full qualification or downstream consumer closure is required.
