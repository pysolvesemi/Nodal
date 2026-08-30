# Increment 32 — First-class analog equations and contributions

## Status

Implemented on the increment branch and awaiting exact-head validation and
accepted-evidence closure.

## Baseline

The implementation starts from `dev` commit
`a38cfed62940c95c38933edd86a064e5725e7d91`, where Increment 133 is validated
and its equation/component checkpoint explicitly unblocks Increment 32.

## Implemented boundary

Increment 32 adds matching Scala and native source-semantic recorders for:

- ordinary unordered equations;
- initialization-only equations;
- additive potential contributions;
- additive flow contributions;
- distinct equation, initial-equation, contribution, and procedural regions;
- stable semantic identities and canonical deterministic snapshots;
- authored expression sides, dimensions, guards, analysis applicability,
  continuity, ownership, source spans, target orientation, and residual intent;
- stable fail-closed diagnostics.

The implementation stores residual intent as `lhs - rhs == 0` while retaining
both authored sides. It never performs causal orientation or division. It
canonicalizes contribution evidence by target and identity, proving that source
order does not create priority or last-writer-wins behavior.

## Verification

The branch contains independent executable Scala and C++ witnesses. They check:

- authored-side retention;
- ordinary versus initialization-only classification;
- absence of causal orientation and unsafe division;
- additive grouping and deterministic source-order permutations;
- duplicate identity rejection;
- illegal procedural-region use;
- stable diagnostic identities.

The repository checker correlates the implementation files, design gate,
manifest, Increment 31 closure, Increment 133 checkpoint, roadmap state,
read-only workflow, compile/run witnesses, and temporary-artifact hygiene.

## Deliberately deferred

Increment 33 owns local analog variables, initialization, lexical scopes,
ordered procedural assignment, read-before-write analysis, and procedural
lowering. Later increments own control flow, differential/state semantics,
topology/DAE construction, solver execution, analysis scheduling, target
legalization, and Verilog-A/Verilog-AMS emission.

The Increment 32 roadmap item remains unchecked until the implementation is
merged, post-merge validation passes, and a separate evidence-closure change
records immutable evidence. Increment 33 remains unchecked throughout this
implementation PR.
