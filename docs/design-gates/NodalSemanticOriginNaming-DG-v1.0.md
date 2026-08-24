# Nodal semantic origin and naming design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** public-api
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Implement source-span capture, semantic naming, source binding, sink affinity, and a stable private
origin graph beneath the frozen public API. Populate the already-frozen `DesignReport.sourceMap` without
adding user-visible syntax.

## Required contracts

- Capture repository-relative Scala source spans and nearby declaration bindings transactionally.
- Discover class members only in a lexicographically sorted order; JVM reflection order is not identity.
- Prefer explicit names, Scala declarations, shaped-view derivation, and sink affinity before a
  source-derived digest.
- No traversal-counter-only normal names are permitted.
- Retain expression-level source maps for nodes that may be inlined.
- Record deterministic names for modules, declarations, shaped views, domains, generated clock/reset
  ports, synchronizers, FIFOs, reset controllers, crossings, pipeline/FSM state, anonymous registers,
  and required temporaries.
- Record source spans and origin edges without mutable global state, thread-local state, identity hashes,
  allocation addresses, or backend-specific naming.
- Preserve public API v0.3, the core/library boundary, and the transactional construction lifecycle.

## Deferred work

MLIR lowering, backend materialization decisions, generated infrastructure emission, scheduling,
verification passes, and HDL output remain deferred to later roadmap increments.
