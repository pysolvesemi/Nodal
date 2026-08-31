# Increment 33 — Analog variables and procedural assignment

## Status

Implementation checkpoint in progress. The source-semantic Scala and native
recorders, deterministic ordered snapshots, stable diagnostics, and independent
witnesses are present. Public construction-kernel and bridge retention remain
the next checkpoint before implementation acceptance.

## Baseline

The branch starts from `dev` commit
`b1d927772c2a33a535f7d7fbe44a3891900c2fa2`, where Increment 32 is validated,
roadmap revision 1.43 marks it complete, and final post-merge Core CI passed.

## Implemented checkpoint

The Increment 33 runtime models:

- component-local integer, real, and dimensionless Boolean variables;
- optional declaration initializers;
- explicit procedural-region legality;
- nested lexical scopes and scope escape rejection;
- component ownership and cross-owner rejection;
- exact authored assignment order;
- repeated writes retained as separate statements;
- straight-line read-before-write analysis;
- integer-to-real promotion without implicit narrowing;
- physical-dimension compatibility;
- dimensionless Boolean guards;
- canonical analysis applicability;
- stable source and statement identities;
- optional file/line/column provenance;
- deterministic declaration-order and statement-order snapshots.

The Scala and native witnesses exercise matching positive and negative cases.

## Semantic separation

Procedural `:=` is an ordered variable update. It remains distinct from:

- unordered first-class equations (`===`);
- additive potential/flow contributions (`<+`);
- compiler-generated conservative connection equations.

No source assignment is converted into an equation or contribution, and no
sequence of assignments is collapsed to one last-writer-wins record.

## Next implementation checkpoint

The next checkpoint wires this semantic runtime into the existing public
`variable`, `analogProcedure`, and `:=` construction paths. It will retain the
result in `ConstructionSnapshot`, Scala-to-MLIR source documents, and canonical
reproducibility evidence, with production-path tests and mutations proving the
integration.

## Deliberately deferred

Increment 34 owns procedural conditionals, cases, loops, break/continue, and
branch-sensitive definite-assignment analysis. Later increments own topology and
DAE construction, solving, scheduling, target legalization, and Verilog-A or
Verilog-AMS emission.

Increment 33 remains unchecked in the roadmap until the complete implementation
passes its exact-head matrix, is merged, and a separate evidence-closure pull
request records immutable evidence.