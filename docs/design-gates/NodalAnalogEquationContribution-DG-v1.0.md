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
initialization-only. It is not an ordinary transient equation and is not a
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
overlapping regions are rejected. Increment 33 owns variables and ordered procedural assignment; this gate only defines the fail-closed separation.

## Determinism

Snapshots sort equations by stable equation identity. Contributions are grouped
by the complete oriented target identity and sorted by contribution identity.
Changing declaration or traversal order therefore cannot change the canonical
semantic snapshot.

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
- incomplete owner, analysis, continuity, or source metadata.

## Required implementation evidence

- Scala and native recorders implement the same source-semantic contract.
- Independent executable witnesses prove authored-side retention, initial-only
  classification, no causal orientation/division, additive accumulation,
  source-order independence, and procedural-region rejection.
- Machine-readable manifest and repository checker keep the roadmap open until
  exact implementation and post-merge evidence is recorded.
- The permanent workflow is read-only and rejects temporary materializers or
  source-bundle workflows from an accepted tree.

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
