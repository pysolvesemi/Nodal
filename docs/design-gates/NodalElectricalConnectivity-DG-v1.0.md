# Nodal electrical conservative connectivity design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** compiler-ir
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Increment 28 adds the first normalized conservative-connectivity layer for
scalar continuous physical components. The implementation is discipline-generic
but is qualified with the electrical voltage/current discipline.

An opted-in physical module owns exactly one `nodal.component_contract`:

- `concrete` components use `local` connectivity ownership and publish complete
  local conservation records;
- `partial` components use `extensible` ownership and publish local records with
  `complete = false`, allowing a later concrete composition to close topology
  and equation ownership.

A conservative boundary terminal records `input`, `output`, or `inout` as port
metadata and separately records whether positive flow is into or out of the
component. Port direction never creates causal signal-flow assignment semantics.
Internal nodes have no port direction.

`nodal.connect` and `nodal.alias` form deterministic connection sets. A
`nodal.reference` marks a module or global zero-potential identity. Explicit
branches are `named` or `implicit`; flow is positive from the positive operand
to the negative operand, and only one implicit branch is permitted for an
unordered endpoint pair.

## Generated topology contract

The `nodal-materialize-conservative-connectivity` pass is transactional,
deterministic, and idempotent. For every opted-in module it:

1. resolves terminal and node disciplines through Increment 27 declarations and
   aliases and requires continuous potential-plus-flow semantics;
2. unions compatible connection and alias relations;
3. derives stable connection-set symbols from sorted hierarchy/source paths and
   canonical discipline identity, never from traversal counters;
4. emits a spanning set of `nodal.potential_equality` records;
5. emits `nodal.reference_potential` for module/global reference sets;
6. emits one signed `nodal.flow_conservation` zero-sum record per set, using
   branch orientation and the terminal flow-orientation field;
7. retains source relation, endpoint, branch, component-kind, ownership,
   hierarchy, and reference provenance for later residual construction and
   target lowering.

The generated operations are compiler-owned normalized records. Source-authored
copies without compiler provenance are rejected, and generated records
self-check component kind, reference identity, flow provenance, and ownership
against their connection set. Rebuilding them from unchanged source semantics
produces byte-identical MLIR.

## Compatibility and safety rules

- Conservative endpoints require a continuous discipline with distinct
  potential and flow natures.
- Discipline aliases are accepted through canonical Increment 27 compatibility.
- Connections and aliases reject incompatible disciplines.
- A concrete component rejects unowned floating endpoints and connection sets
  without an owned flow term.
- A partial component may retain an intentionally open endpoint, but its flow
  equation remains incomplete and extensible.
- Component hierarchy paths are canonical and unique within one compilation
  unit.
- Global reference sets use the stable identity `global::<discipline>`; module
  references retain the component hierarchy path.
- One connection set cannot mix global and module reference scopes.
- Input/output/inout metadata does not alter KFL signs.
- Existing pre-Increment-28 terminal/node/branch fixtures remain valid unless a
  component explicitly opts into the new contract.

## Explicitly deferred

- public Scala spelling and standard-library electrical component APIs;
- hierarchical instance-port connection expansion and logically flattened
  analog islands;
- general authored equations, residual/DAE construction, equation/unknown
  balance, structural singularity, and solver ordering;
- unit-aware expressions, access-function evaluation, contributions, events,
  analyses, noise, and connect rules;
- Verilog-A/Verilog-AMS declaration and equation emission;
- arrays or buses of conservative terminals and dynamic topology.

Residual DAE construction remains explicitly out of scope. These capabilities
remain owned by later roadmap increments, especially Increments 29-32 and the
continuous-time architecture implementation track. Increment 28 preserves the
required identities and provenance without prematurely defining solver IR.
