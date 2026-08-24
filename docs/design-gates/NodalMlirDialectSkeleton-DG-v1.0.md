# Nodal MLIR dialect skeleton design gate v1.0

**Revision:** v1.0
**Status:** Approved
**Scope:** compiler-ir
**Public API:** unchanged at 0.3
**Approved authority:** standing Nodal increment implementation and merge authorization

## Decision

Register the private out-of-tree `nodal` MLIR dialect beneath the frozen
Scala public API. Generate its dialect and operation declarations from
TableGen, make `nodalc` load it alongside the retained CIRCT HW dialect, and
add one verified operation named `nodal.placeholder`.

The placeholder exists only to prove registration, generated declarations,
custom and generic parser/printer round trips, verifier execution, native
linking, and source-tree organization. Its required string `label` is
diagnostic bootstrap metadata and must be non-empty.

## Binding implementation boundary

- The dialect namespace is exactly `nodal`.
- The C++ namespace is exactly `::nodal`.
- TableGen owns dialect and operation declarations and generated
  documentation.
- `nodalc` preserves the Increment 6 crash diagnostic, version output, and
  CIRCT HW dialect registration.
- The permanent Increment 18 workflow is read-only and builds against the
  locked native toolchain.
- Public API v0.3 is unchanged.

## Explicitly deferred

Increment 18 does not define module, port, parameter, type, instance,
hierarchy, clock/reset, domain, state, interface, analog, pipeline, FSM,
scheduling, lowering, optimization, or HDL-emission semantics. Those remain
owned by later roadmap increments, beginning with Increment 19.

## Required evidence

Completion requires the structural checker and its mutation tests, a native
build, custom and generic parser/printer round trips, rejection of an empty
placeholder label, predecessor compatibility, permanent CI, Core CI,
roadmap evidence, a reviewed pull request, and squash merge into `dev`.
