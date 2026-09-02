# Increment 33 — Analog variables and procedural assignment

## Status

Implementation is complete and undergoing exact-head acceptance. Public
construction, ordered source-semantic recording, Scala-to-MLIR serialization,
native IR verification, compiler-boundary diagnostics, and source-map coverage
are present. The roadmap remains open until the implementation is merged and a
separate evidence-closure pull request records immutable validation evidence.

## Baseline

The branch starts from `dev` commit
`b1d927772c2a33a535f7d7fbe44a3891900c2fa2`, where Increment 32 is validated,
roadmap revision 1.43 marks it complete, and final post-merge Core CI passed.

## Implemented checkpoint

The Increment 33 runtime models:

- component-local integer, real, and dimensionless Boolean variables;
- optional declaration initializers;
- explicit procedural-region legality;
- one isolated top-level procedural region per component, with additional regions diagnosed;
- nested lexical scopes and scope escape rejection;
- component ownership and cross-owner rejection;
- exact authored assignment order;
- one combined operation order across declarations and assignments;
- repeated writes retained as separate statements;
- straight-line read-before-write analysis;
- integer-to-real promotion without implicit narrowing;
- physical-dimension compatibility;
- dimensionless Boolean guards;
- canonical analysis applicability;
- stable source and statement identities;
- optional file/line/column provenance;
- deterministic declaration-order, assignment-order, and operation-order snapshots.

The public construction path records these semantics in the compiler-owned
construction snapshot. The ordinary Scala-to-MLIR bridge emits first-class
variable declaration, explicit variable read, lexical scope, and ordered
assignment operations. Scala and native witnesses exercise matching positive
and negative cases.

## Review hardening

Six P1 review findings were corrected before acceptance:

1. Procedural rendering gives authored semantic order precedence over
   source-file and source-line provenance. Helper functions or multiple source
   files therefore cannot reverse authored assignment order.
2. A known dimensionless expression remains dimensionless during assignment
   checking. It cannot inherit the destination variable's physical dimension.
3. Recursive dimension inference preserves an incompatible compound expression
   as `unknown`; it cannot replace that result with the dimension of the first
   variable read. For example, `voltage := voltage + 1.0.real` is rejected with
   `NODAL-ANALOG-033-013`.
4. Variable declarations and assignments carry one monotonically increasing
   `operation_order` in addition to their category-specific counters. This
   preserves declaration/assignment interleaving, including an assignment that
   initializes a value before a later declaration reads it in an initializer,
   even when source coordinates are deliberately inverted.
5. A recorder cannot combine independent top-level procedural regions. A second
   region is rejected with `NODAL-ANALOG-033-020`, preventing definite-assignment
   state and operation counters from leaking across procedures.
6. Boolean-producing expressions retain an explicit `Bool` result descriptor and
   dimensionless result dimension. Comparisons and nested Boolean logic therefore
   assign legally to Boolean variables while still retaining their operand reads.

Dedicated regressions cover bare and compound dimension mismatches, nested
lexical-scope chronology, assignment-before-dependent-initializer chronology,
Boolean comparison result typing, repeated top-level procedural-region rejection,
and native `nodalc` parsing of the resulting first-class procedural MLIR.

## Semantic separation

Procedural `:=` is an ordered variable update. It remains distinct from:

- unordered first-class equations (`===`);
- additive potential/flow contributions (`<+`);
- compiler-generated conservative connection equations.

No source assignment is converted into an equation or contribution, and no
sequence of assignments is collapsed to one last-writer-wins record.

## Deliberately deferred

Increment 34 owns procedural conditionals, cases, loops, break/continue, multiple
independent top-level procedural regions, and branch-sensitive definite-assignment
analysis. Later increments own topology and
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
| Ordered assignment | `nodal.analog_assign` with contiguous `authored_order` | native order rejection, source-provenance inversion regression, and deterministic Scala snapshot tests |
| Cross-category chronology | declaration and assignment records with one contiguous `operation_order` | nested-scope and assignment-before-dependent-initializer regressions with inverted provenance and native parsing |
| Lexical and component ownership | `nodal.analog_procedure` and `nodal.analog_scope` verifier | owner, nested-region, scope, and cross-component tests |
| Type, dimension, guard, and analysis legality | native recursive procedural verifier | dedicated invalid MLIR fixtures, bare and compound dimension regressions, and Scala semantic tests |
| Compiler-boundary diagnostics | `NODAL-ANALOG-033-001` through `-020` in the diagnostic inventory | native diagnostics retain semantic path and MLIR location |
| Source-map preservation | declaration/read/assignment `loc(...)` plus root source-map inventory | generic native round-trip and bridge source tests |
| Authoritative serialization | ordinary `ScalaToMlirBridge` emits all procedural operations | deterministic document/hash test; no sidecar required for acceptance |

Increment 33 implementation is merge-ready only when the normal contract, Scala,
and native compiler lanes and all inherited workflows pass from the same exact
commit. Roadmap/evidence closure remains a separate merge after the implementation
commit is present on `dev`.
