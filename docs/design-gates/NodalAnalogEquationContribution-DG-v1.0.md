# Nodal Analog Equation and Contribution Semantics — v1.0

**Status:** Approved

**Scope:** compiler-ir
**Scope:** public-api

**Increment:** 32

**Compatibility base:** Nodal public API v0.3 and the approved Increment 133 equation/component checkpoint

## Purpose

This gate defines the first implemented source-semantic representation for
continuous analog equations and additive potential/flow contributions. It
preserves the acausal mathematical model without deriving procedural order,
causal assignment, solver callbacks, or backend syntax.

## Binding semantics

An ordinary equation is an unordered simultaneous constraint. Its record owns:

- a stable equation identity;
- the authored left and right expressions;
- matching physical dimension and real-valued operand classification;
- source owner and source span;
- optional dimensionless Boolean guard;
- explicit analysis applicability and continuity class;
- structural residual intent `lhs - rhs == 0`;
- an explicit statement that the equation has not been causally oriented or
  divided.

An initial equation uses the same representation but is marked
initialization-only. Its analysis applicability is exactly `initialization`;
DC, operating-point, transient, AC, or noise applicability is rejected with
`NODAL-ANALOG-133-009`. It is not an ordinary transient equation and is not a
procedural initializer.

A contribution is a distinct additive term targeting one oriented potential or
flow quantity. Contributions:

- have stable identities independent of source traversal order;
- retain target kind, physical dimension, branch identity, and orientation;
- accumulate as a canonical ordered set of terms rather than last-writer-wins
  assignment;
- do not become equations, procedural assignments, or backend statements in
  this increment.

## Region legality

Four source-semantic regions are distinguished:

- ordinary equation;
- initial equation;
- contribution;
- procedural.

Equation construction is legal only in an equation or initial-equation region.
Contribution construction is legal only in a contribution region. Nested or
overlapping regions are rejected. The frozen public `equations`,
`initialEquations`, `contributions`, `equation`, `initialEquation`,
`contribution`, and `<+` paths must invoke the production construction recorder;
a witness-only recorder is not an implementation. Increment 33 owns variables
and ordered procedural assignment; this gate only defines the fail-closed
separation.

## Determinism

Snapshots sort equations by stable equation identity. Contributions are grouped
by the complete oriented target identity—identity, kind, physical dimension,
and orientation—and sorted by contribution identity. Changing declaration or
traversal order therefore cannot change the canonical semantic snapshot.

This ordering is an evidence and serialization convention only. It does not
introduce equation execution order or contribution priority.

## Diagnostics

The stable diagnostic family is `NODAL-ANALOG-032-*` and covers:

- overlapping regions;
- equation or contribution use in an illegal region;
- missing region ownership;
- duplicate equation or contribution identity;
- non-real equation/contribution expressions;
- physical-dimension mismatch;
- non-Boolean or dimensioned guards;
- incomplete owner, analysis, continuity, or source metadata;
- runtime-analysis applicability on an initialization-only equation
  (`NODAL-ANALOG-133-009`);
- empty equation or contribution identities in both Scala and native recorders.

## Required implementation evidence

- The frozen public equation and contribution APIs feed the production Scala
  construction recorder, and Scala and native recorders implement the same
  source-semantic contract.
- Independent executable witnesses prove authored-side retention, initial-only
  classification, no causal orientation/division, additive accumulation,
  source-order independence, and procedural-region rejection.
- Construction snapshots, Scala-to-MLIR source documents, and reproducibility
  snapshots retain a deterministic analog-semantic inventory.
- Machine-readable manifest and repository checker keep the roadmap open until
  exact implementation and post-merge evidence is recorded.
- The permanent workflow is read-only and rejects temporary materializers or
  source-bundle workflows from an accepted tree.

## Accepted alternatives

The Scala construction layer and native compiler may use different internal
containers and APIs when they preserve the same identities, authored sides,
dimensions, metadata, region legality, and deterministic canonical snapshot.
Later compiler stages may derive solver-neutral residuals from the recorded
source semantics while continuing to retain the authored equation evidence.

## Rejected alternatives

The following are rejected:

- inferring causal assignment direction from equation syntax;
- dividing or rearranging an equation merely to make it executable;
- applying source-order priority or last-writer-wins behavior to contributions;
- conflating initial equations, ordinary equations, contributions, or
  procedural assignment;
- treating emitted HDL or a solver callback ABI as the canonical source model;
- accepting anonymous, incomplete, dimensionally invalid, or illegally scoped
  semantic records.

## Compatibility impact

The implementation is additive underneath the frozen public API v0.3 and the
approved continuous-time v0.1 surface. Existing digital behavior is unchanged.
No solver, simulator, topology expansion, target legalization, or backend
execution is enabled by this increment.

## Required tests

The accepted implementation must pass the Scala and native executable
witnesses, repository checker, mutation suite, formatting and static-analysis
checks, contribution policy, Core CI, and every inherited increment workflow on
one exact implementation head. Evidence closure must occur separately and must
leave Increment 33 unchecked until the Increment 32 evidence record is complete.

## Deferred behavior

The following are deliberately not implemented by Increment 32:

- procedural variables and assignment;
- control flow;
- topology expansion and conservative connection equation generation;
- algebraic simplification beyond canonical ordering;
- equation balance, structural singularity, residual/DAE construction, index
  reduction, or state selection;
- solver execution or analysis scheduling;
- equation-to-target legalisation and Verilog-A/Verilog-AMS lowering.

These remain owned by their named roadmap increments.

## Approval evidence

Approved through the project owner's standing increment approval and explicit
instruction to continue the Increment 32 prerequisite before Increment 33.
