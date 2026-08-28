# Increment 30 — Analog numeric types and expression typing

Increment 30 is implemented on the fully validated Increment 29 baseline while
the public API remains v0.3 and the roadmap item stays unchecked pending CI,
review, merge, and evidence closure.

## Implemented

- Target-neutral `!nodal.quantity<"integer"|"real", "canonical-dimension">`.
- Canonical exponent algebra for multiplication, division, and typed `ddt`.
- Deterministic integer-to-real promotion without Boolean numeric truthiness.
- Quantity-aware literals, parameter references, arithmetic, negation,
  comparisons, Boolean-only logical operations, and conditional selection.
- Shared native inference and verification through
  `nodal-verify-analog-numeric`.
- Pure constant folding through `nodal-fold-analog-constants`, recorded as
  provenance-retaining annotations so authored source operations are not
  erased.
- Static zero-divisor and non-finite-fold diagnostics.
- Signed dimension-exponent overflow is rejected deterministically.
- Conditional folding preserves promoted result kinds, including integer-to-real arms.
- Fixed parameter folding applies the declared unit scale before materializing a
  canonical dimension-only quantity value.
- Fold annotations are recomputed and ignored outside the frozen pure-expression boundary.
- Verified scalar erasure at the Verilog-A/Verilog-AMS backend boundary.
- Positive, negative, folding, legacy-f64, and native-backend fixtures.

Increment 31 retains ownership of final potential/flow access-function
resolution and discipline-nature result dimensions. Increment 32 retains
first-class equations and contribution interaction. Increment 35 retains the
state and operational semantics of `ddt` and `idt`.
