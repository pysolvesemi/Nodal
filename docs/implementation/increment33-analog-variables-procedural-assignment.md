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

## Final implementation matrix

| Requirement | Authoritative implementation | Acceptance evidence |
|---|---|---|
| First-class variable identity and type | `!nodal.variable<kind, dimension>` and `nodal.analog_variable` | native parse/generic round-trip and invalid type fixtures |
| Explicit reads | `nodal.analog_variable_read` | read-before-initialization native rejection and public bridge serialization |
| Ordered assignment | `nodal.analog_assign` with contiguous `authored_order` | native order rejection plus deterministic Scala snapshot tests |
| Lexical and component ownership | `nodal.analog_procedure` and `nodal.analog_scope` verifier | owner, nested-region, scope, and cross-component tests |
| Type, dimension, guard, and analysis legality | native recursive procedural verifier | dedicated invalid MLIR fixtures and Scala semantic tests |
| Compiler-boundary diagnostics | `NODAL-ANALOG-033-001` through `-019` in the diagnostic inventory | native diagnostics retain semantic path and MLIR location |
| Source-map preservation | declaration/read/assignment `loc(...)` plus root source-map inventory | generic native round-trip and bridge source tests |
| Authoritative serialization | ordinary `ScalaToMlirBridge` emits all procedural operations | deterministic document/hash test; no sidecar required for acceptance |

Increment 33 implementation is merge-ready only when the normal contract, Scala, and native
compiler lanes pass from the same commit. Roadmap/evidence closure remains a separate merge after
the implementation commit is present on `dev`.
