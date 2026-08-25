# Increment 21 — native parse, staged semantic verification, and pass pipeline

## Delivered boundary

Increment 21 turns parsed Nodal MLIR into a gated compiler state. The native
compiler registers one pass for explicit stage execution and one transactional
all-stage pass. Named pipelines make both forms available through `nodalc`.

## Native implementation

`nodal/Transforms/Verification.h` and `lib/Transforms/Verification.cpp` provide:

- the fixed stage enumeration and stable stage names;
- operation- and bridge-inventory-aware whole-design checks;
- module hierarchy resolution and cycle rejection;
- source-origin driver, latch-evidence, and combinational-cycle checks;
- finite-width, symbolic-shape, layout, storage, parameter, generate, and loop
  checks;
- enum/FSM, domain/crossing, Interface/protocol, memory/effect,
  analog/mixed-signal, and target-capability checks;
- `nodal-verify-stage`, `nodal-gate-check`, `nodal-transactional-gate`, and
  `nodal-gate-normalize` registration;
- post-normalization reverification with rollback of acceptance attributes;
- a native verification session that commits textual accepted state only after
  success.

The accepted metadata records pipeline version, effective target, normalized
state, and the complete ordered stage list. It is compiler evidence, not public
source semantics.

## Diagnostics

All whole-design failures use stable `NODAL-VERIFY-*` identifiers. Local
operation/type verifiers from Increment 19 continue to run and remain mandatory.
A pipeline failure produces no accepted marker and cannot replace a prior
accepted session state.

## Tests

The increment includes:

- a valid gate fixture;
- one or more locally valid negative fixtures for every currently observable
  whole-design category;
- a Python runner for named pipelines, explicit textual pass pipelines,
  diagnostics, normalized markers, and deterministic recovery;
- a C++ verification-session test proving last-accepted-state preservation;
- structural and mutation tests for repository, pass-registration, workflow,
  roadmap, and manifest contracts.

## Deferred work

No CIRCT lowering, scheduling, optimization, backend selection, HDL emission,
simulator invocation, formal engine, or analog solver behavior is added here.
Public API v0.3 is unchanged.
