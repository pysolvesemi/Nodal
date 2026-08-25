# Increment 21 — native semantic verification pipeline

Increment 21 adds the first whole-design native verification and normalization
layer above the Increment 19 dialect and Increment 20 textual bridge.

## Native organization

- `nodal/Transforms/Passes.h` defines the private gate profiles, transactional
  runner, retained accepted-state session, and pass-registration entry point.
- `lib/Transforms/Passes.cpp` implements thirteen read-only semantic stages,
  deterministic normalization, named MLIR pipelines, and clone-before-commit
  transactions.
- `nodalc` registers the passes before entering `MlirOptMain`.
- `nodal-gate-fast`, `nodal-gate-default`, and `nodal-gate-release` are explicit
  command-line pipelines.

Read-only stages request one inventory analysis and mark analyses preserved.
The normalization pass mutates the cloned module and therefore invalidates that
analysis. The nested pass manager enables MLIR verification after every pass.
Only a completely successful clone replaces the original module.

## Validation

The native unit test proves that:

- a valid candidate becomes normalized accepted state;
- a rejected later candidate cannot replace the retained accepted state;
- a failed in-place transaction leaves its input byte-equivalent when printed;
- release and default profiles record distinct accepted profile evidence.

The IR fixtures exercise the full valid semantic inventory and independent
construction, driver, latch, cycle, hierarchy, type, parameter, FSM, domain,
protocol, effect, analog, and capability failures. FileCheck prefixes verify
normalized profile output from each named gate.

Public Scala API v0.3 is unchanged. Cross-layer diagnostic mapping remains
Increment 22, and CIRCT/backend lowering remains deferred.
