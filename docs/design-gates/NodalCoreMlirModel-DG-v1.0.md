# Nodal core MLIR model design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** compiler-ir
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Increment 19 replaces the semantics-free dialect-only boundary with the first
canonical target-neutral hardware model in the private `nodal` MLIR dialect.
The schema owns stable semantic identities and source-correlated structure; it
does not select a backend representation or implement whole-design passes.

## Binding type model

The dialect defines finite-width signless, unsigned, and signed values; ranked
symbolic-capable shaped values; logical Interface, `Valid`, and `Stream` types;
resolved-net and driver identities; conservative terminal and branch types;
semantic enum identities; and clock/reset-domain tokens.

Shape is independent of storage and target layout. Interface identity is
independent of flattened or native target ABI. Resolved digital connectivity is
distinct from conservative analog connectivity.

## Binding operation model

The schema includes modules, ports, symbolic parameters, instances, logical
Interface definitions/roles/members/instances/access and ABI metadata, domains
and requirements/bindings/relationships, constants, shape views/indexing and
flattening, structural generation, bounded hardware iteration, resolved-net
read/driver/drive, conservative terminal/node/branch/access, explicit bridges
and crossings, semantic enum definitions/cases, FSM/state/transition/action and
completion graphs, state ownership, and timing provenance.

## Verification boundary

Increment 19 provides local construction invariants and native parse/print
coverage. Whole-design symbol resolution, driver completeness, hierarchy,
shape/layout/storage, CDC/RDC, protocol, FSM reachability, analog topology,
capability, and transactional pass-pipeline verification remain assigned to
Increment 21.

## Explicitly deferred

- Scala construction-state lowering and process invocation protocol
- Native whole-design semantic pass pipelines
- CIRCT conversion and backend-specific target layouts
- Scheduling, optimization, HDL emission, and external-tool execution
- Continuous-time equation and analysis semantics

Public Scala API v0.3 is unchanged.
