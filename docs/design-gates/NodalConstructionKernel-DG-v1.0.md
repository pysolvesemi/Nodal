# NodalConstructionKernel-DG v1.0

**Status:** Approved
**Scope:** public-api
**API version:** 0.3
**Decision:** Activate the private construction kernel beneath frozen public API v0.3
**Approved by:** Repository owner instruction to continue Increment 16 on 2026-08-24
**Public API:** unchanged at 0.3
**Governing gate:** `NodalCoreSemanticsPipelineApi-DG-v0.3.md`

## Decision

Increment 16 may activate private elaboration behavior beneath public API v0.3 only when all of the
following remain true:

1. each elaboration uses an isolated transaction;
2. no public implicit, given, mutable global, or thread-local carries construction state;
3. stable identities derive from hierarchy, explicit names, and deterministic local ordinals rather
   than JVM object identity;
4. state captures a lexical or unambiguous default domain;
5. required child domains are inherited or explicitly bound and required root domains are rejected;
6. `Struct`, `Vec`, and `Mem` retain distinct kind, shape, and storage intent;
7. exported interfaces close only with complete, compatible role access;
8. digital resolved endpoints and conservative topology are registered without prematurely lowering
   or resolving them;
9. construction publishes only after complete validation;
10. no scheduler, MLIR, backend, simulator, synthesis, formal, or timing behavior is claimed.

## Accepted implementation

The accepted implementation uses `java.lang.ScopedValue` only as an immutable session binding. Mutable
state is allocated inside the bound `ConstructionSession`. Temporary identity maps locate live Scala objects during one transaction, but identity values never participate in stable paths, reports, or
serialized artifacts.

`Nodal.emit` remains the public entry point and still emits no HDL files. It now returns deterministic
construction classification and logical Interface ABI evidence. Private inspection is available only to
package tests.

## Exit evidence

- `core/scala/api/src/nodal/ElaborationConstructionKernel.scala`
- `core/scala/testkit/test/src/nodal/ConstructionKernelTests.scala`
- `tests/api/fixtures/increment16/manifest.json`
- `scripts/check_increment16.py`
- `docs/implementation/increment16-construction-kernel.md`

Any public source-surface change, global construction registry, thread-local implementation, or
identity-derived emitted name invalidates this gate.
