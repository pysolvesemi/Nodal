# Nodal Analog Numeric and Expression Typing Design Gate v1.0

**Status:** Approved  
**Scope:** compiler IR, native semantic verification, deterministic folding, and verified scalar backend erasure  
**Public API:** unchanged at 0.3  
**Roadmap owner:** Increment 30

## Decision

Increment 30 replaces the minimal analog vertical slice's unqualified `f64`
assumption with a target-neutral scalar quantity model. The compiler owns
numeric kind, physical dimension, promotion, compatibility, and folding before
any Verilog-A/Verilog-AMS spelling is selected.

The initial semantic type is:

```text
!nodal.quantity<integer|real, canonical-dimension>
```

Boolean results remain ordinary `i1`; Boolean values are never quantities and
never acquire numeric truthiness. The dimensionless identity is the canonical
signature `1`. A legacy `f64` analog value remains accepted and is interpreted
as `!nodal.quantity<real, 1>` until all earlier fixtures and bridge producers
have migrated.

A quantity type stores no display unit and no scale. Units continue to be
declared by `nodal.unit`; an authored unit resolves to a canonical dimension
signature and scale before expression typing. This separation prevents target
suffixes, display symbols, or floating-point formatting from defining physical
compatibility.

## Canonical dimension signatures

A dimension signature is a deterministic product of symbolic atoms with signed
integer exponents. The canonical form:

- uses `1` for dimensionless;
- sorts atoms lexically;
- omits exponent `1`;
- removes exponent `0`;
- writes all other exponents explicitly, for example
  `current*voltage^-1`;
- contains no unit scale, display symbol, or target suffix.

Existing atomic dimensions such as `voltage`, `current`, and `resistance`
remain legal atoms. A unit or nature that wants derived-dimensional
equivalence must use the same canonical signature. The compiler never guesses
that independently named atoms are equivalent.

Multiplication adds exponent vectors. Division subtracts them. Equality of
canonical signatures defines physical compatibility for addition,
subtraction, ordering comparison, and conditional arms.

## Numeric promotion

The only implicit numeric promotion is `integer -> real`.

- integer with integer produces integer when the operation is closed over
  integers;
- integer with real, or real with integer, produces real;
- real with real produces real;
- real never narrows to integer implicitly;
- Boolean never promotes to integer or real;
- signless digital bits and finite-width digital integers do not enter analog
  quantity arithmetic without an explicit bridge or conversion.

Promotion changes numeric representation only. It never changes a physical
dimension.

## Expression rules

The existing `nodal.real_literal`, `nodal.parameter_ref`,
`nodal.analog_add`, `nodal.analog_sub`, `nodal.analog_mul`,
`nodal.analog_div`, `nodal.analog_ddt`, `nodal.access`, and
`nodal.contribute` operations retain legacy compatibility and gain
quantity-aware verification.

Increment 30 adds the minimal missing source-semantic operations:

- `nodal.analog_integer_literal`;
- `nodal.analog_neg`;
- `nodal.analog_compare`;
- `nodal.analog_logic`;
- `nodal.analog_select`.

Rules are fixed as follows.

- `add` and `sub` require equal dimensions and return that dimension using the
  promoted numeric kind.
- `mul` combines dimensions by exponent addition and uses the promoted numeric
  kind.
- `div` combines dimensions by exponent subtraction and produces real when an
  exact integer result cannot be proven by the constant folder. A dynamic
  integer division therefore has real result type.
- unary `neg` preserves kind and dimension.
- `ddt` preserves numeric kind only where the operator contract permits it and
  subtracts one `time` exponent. Increment 35 still owns state, context, and
  continuous-time operational semantics.
- ordering comparisons require numeric operands with equal dimensions.
- equality/inequality accept compatible numeric operands or two Boolean
  operands. Every comparison returns `i1`.
- logical `and`, `or`, `xor`, and `not` accept only `i1`.
- `analog_select` requires an `i1` condition. Its arms require equal dimensions
  and a valid numeric promotion, or both arms must be `i1`.
- modulo, bitwise arithmetic on quantities, implicit truth tests, and
  dimension-changing casts are rejected.

Increment 31 resolves potential/flow access results from discipline natures and
uses the typing hooks frozen here. Until then, legacy access values may remain
dimensionless `f64`; a typed access must already carry a verified quantity
result. Contribution-dimension matching is enabled by the same hook and is
completed with access-function resolution.

## Folding boundary

The constant folder may evaluate only a pure, finite expression whose complete
operand graph consists of literals and compile-time parameter constants.

It may fold arithmetic, comparison, logical, negation, and conditional
selection while preserving the canonical result kind and dimension. It must
reject a statically known zero divisor and non-finite real result.

The following are never folded by Increment 30:

- symbolic or runtime parameters;
- `nodal.dynamic_value`;
- conservative access or probe values;
- time, frequency, environment, or analysis queries;
- `ddt`, `idt`, delay, transition, event, noise, and simulator functions;
- contributions, equations, assignments, state, or bridge operations.

A failed fold must not silently convert a dynamic expression into a constant.
A successful fold retains source and expression provenance; it does not erase
the authored operation merely because a backend can print a literal.

## Backend boundary

Verilog-A and Verilog-AMS use scalar real/integer/Boolean spellings rather than
Nodal dimensioned types. A backend may erase `!nodal.quantity` only after the
mandatory quantity verifier has succeeded. Erasure preserves numeric
promotion, parentheses, expression identity, source maps, and diagnostics.
The backend must never use target-language coercion to repair an invalid Nodal
expression.

## Compatibility and deferrals

Increment 24 and 25 `f64` fixtures remain valid. Increment 29 parameter units,
lossless literal spelling, constraints, and structural classification remain
unchanged.

This gate does not freeze public Scala syntax, complex quantities, vectors or
matrices of analog quantities, affine units, access-function public syntax,
first-class equations, procedural analog variables, control flow, state,
continuous-time operator semantics, mathematical-function typing, noise,
analysis projection, or solver lowering. Those remain with their owning
increments.
