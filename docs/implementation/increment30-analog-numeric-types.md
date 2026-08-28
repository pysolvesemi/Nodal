# Increment 30 — Analog numeric types and expression typing

Increment 30 has started from the fully validated Increment 29 `dev` baseline
`1c1ab49da71f0f52d2af3d93e40a92f4643be776`.

## Approved implementation contract

The design gate introduces a target-neutral quantity type with integer/real
numeric kind and a canonical physical-dimension signature. Boolean remains
`i1`. Legacy analog `f64` is retained as real dimensionless compatibility
during migration.

The approved rules cover:

- deterministic integer-to-real promotion without implicit narrowing;
- physical compatibility for addition, subtraction, comparisons, and
  conditionals;
- canonical exponent algebra for multiplication, division, and derivative
  result dimensions;
- Boolean-only logical operations with no numeric truthiness;
- explicit invalid-operation diagnostics;
- pure constant-folding boundaries that exclude runtime, access, state,
  event, analysis, bridge, contribution, and solver-dependent operations;
- verified erasure of quantity wrappers at the scalar Verilog-A/Verilog-AMS
  backend boundary.

## Implementation sequence

1. Add `!nodal.quantity<kind, dimension>` and canonical dimension helpers.
2. Generalize the existing minimal analog operations while preserving legacy
   `f64` fixtures.
3. Add integer literals, unary negation, comparison, logical, and conditional
   operations.
4. Add one shared native type-inference and compatibility verifier.
5. Add deterministic pure folding and known-zero/non-finite rejection.
6. Type `ddt` structurally while leaving state/context semantics to Increment
   35.
7. Add backend quantity erasure only after semantic verification.
8. Add positive, negative, round-trip, mutation, and inherited regression
   coverage.

The public API remains v0.3 and Increment 30 stays unchecked until native
implementation, dedicated CI, Core CI, review, merge, and evidence closure are
complete.
