# Increment 29 — Parameters, constants, ranges, and units

Increment 29 adds a compiler-owned parameter model without changing public API
v0.3.

## Implemented

- Explicit real/integer/Boolean parameter kinds and ordinary/structural classes.
- Canonical unit declarations with dimension, positive scale, display symbol,
  and validated native Verilog scale suffix.
- Losslessly spelled unit-aware literals and a typed constant-expression DAG.
- Deterministic folding, parameter-reference cycle rejection, and canonical
  default-value agreement.
- Native range and exclusion constraints applied uniformly to defaults,
  dictionary bindings, and explicit overrides.
- Lossless explicit override records that must agree with canonical instance
  bindings when both forms are present.
- Bounded `static_generate` structural envelopes for topology, component,
  equation, shape, and rank effects.
- Explicit dynamic-value classification that cannot enter constant evaluation.
- Lossless Verilog-A/Verilog-AMS parameter rendering with retained spelling,
  native ranges/exclusions, and deterministic unit comments.

The implementation intentionally limits physical-dimension algebra inside
constant folding. Addition/subtraction require the same dimension, and
multiplication/division allow at most one dimensioned operand. General analog
numeric promotion and composite dimensions remain Increment 30 work.

The model remains fail-closed for unsupported parameter kinds, affine units,
unbounded structural parameters, dynamic structural changes, cyclic defaults,
non-native constraints, and backend operations outside the current analog
vertical slice.
