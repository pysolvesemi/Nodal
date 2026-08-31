# Increment 32 — First-class analog equations and contributions

## Status

Implemented on the increment branch and awaiting exact-head validation and
accepted-evidence closure.

## Baseline

The implementation starts from `dev` commit
`a38cfed62940c95c38933edd86a064e5725e7d91`, where Increment 133 is validated
and its equation/component checkpoint explicitly unblocks Increment 32.

## Implemented boundary

Increment 32 wires the frozen public equation/contribution API into the
production construction kernel and adds matching Scala and native
source-semantic recorders for:

- ordinary unordered equations;
- initialization-only equations;
- additive potential contributions;
- additive flow contributions;
- distinct equation, initial-equation, contribution, and procedural regions;
- stable semantic identities and canonical deterministic snapshots;
- authored expression sides, dimensions, guards, analysis applicability,
  continuity, ownership, source spans, target orientation, and residual intent;
- stable fail-closed diagnostics;
- deterministic retention in construction snapshots, Scala-to-MLIR source
  documents, and reproducibility snapshots.

The implementation stores residual intent as `lhs - rhs == 0` while retaining
both authored sides. It never performs causal orientation or division. It
canonicalizes contribution evidence by target and identity, proving that source
order does not create priority or last-writer-wins behavior.

## Verification

The branch contains independent executable Scala and C++ witnesses. They check:

- authored-side retention;
- ordinary versus initialization-only classification and exact
  initialization-analysis applicability;
- absence of causal orientation and unsafe division;
- additive grouping and deterministic source-order permutations;
- duplicate identity rejection;
- illegal procedural-region use;
- stable diagnostic identities;
- real public API integration rather than witness-only recorder use;
- native rejection of empty equation and contribution identities.

The production-path integration suite is kept in the repository-owned
`nodal.internal.testkit` package, so package-visibility policy is exercised by
the same exact-head acceptance matrix as the semantic tests.

The repository checker correlates the implementation files, design gate,
manifest, Increment 31 closure, Increment 133 checkpoint, roadmap state,
read-only workflow, compile/run witnesses, and temporary-artifact hygiene.
The exact implementation head is accepted only after the dedicated workflow,
Core CI, and every inherited pull-request workflow pass on that same commit.
The synchronize event carrying that head must also expose the canonical
`## Design gate` pull-request section required by contribution policy.

Review findings are closed only after production public-API construction,
initialization-only analysis enforcement, and native non-empty identity checks
all pass their direct integration and mutation witnesses on the same exact
implementation head. This owner-authored checkpoint reruns the complete
acceptance matrix after those fixes without changing the semantic boundary.

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
