# Increment 25 — RC filter end-to-end vertical slice

Increment 25 connects the frozen Scala v0.3 analog syntax to the Increment 24
MLIR and Increment 23 backend transaction for a minimal RC model.

## Pipeline

1. Scala construction captures analog-region membership, operation identity,
   ordered operands, literal units, and contribution target/value identity.
2. `ScalaToMlirBridge` emits typed parameters, terminals, a conservative branch,
   potential/flow access, ordered arithmetic, `ddt`, and a flow contribution.
3. `nodalc` executes `nodal-gate-default` and publishes normalized accepted MLIR.
4. `nodal-translate --nodal-to-verilog-a` renders into a private buffer, verifies
   target structure, reparses the supported subset, and only then publishes the
   exact Verilog-A bytes.

Source-correlation lookup prunes generated build trees before descent and treats
disappearing entries as non-candidates. This keeps parallel forked elaboration
deterministic while retaining source-owner disambiguation.

The Scala and direct-MLIR paths are compared to one exact golden. Failures in
construction, bridge lowering, semantic verification, capability checking,
target verification, or target reparse publish no partial HDL.

The Increment 24 predecessor checker accepts the closed successor only when the
Increment 25 manifest, evidence fields, public API identity, and roadmap
revision agree.

## Accepted evidence

- Pull request: `68`.
- Dedicated Increment 25 workflow: `33068184835`.
- Core CI workflow: `33068184714`.
- Increment 22 compatibility workflow: `33068184871`.
