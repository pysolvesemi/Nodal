# NodalDigitalExpressions-DG v0.1

**Status:** Approved
**Scope:** public-api
**API version:** additive to frozen core v0.3
**Decision:** implement exact typed F55 combinational expressions at the existing source/native boundary

## Authority and compatibility

The owner requested: “start implementing all foundation unchecked increment items
in nodal repo from increment 43 and continue until all foundation checklists are
completed. Don't start another track just complete foundation track.” Standing
publication/integration approval applies. This authorizes the required Foundation
implementation; it does not claim a separately chosen signature for every method.

F15 and ADRs 0009/0016/0018 freeze losslessness, source forms, exact operation order,
explicit conversions and semantic widths, but do not publish a complete arithmetic
width table. The rules below make the required implementation precise. Existing
UInt `+`, SInt `+`/`-` and `>>` signatures are preserved. UInt subtraction was not a
frozen source method and is added with a signed result. F54 conversion semantics
remain authoritative. Analog Real/Integer arithmetic remains with its existing
unit/effect owner.

## Scalar contract

For positive owned widths L and R, over every legal parameter setting:

- Same-kind UInt/SInt addition returns that kind with `max(L,R)+1` bits.
- Subtraction returns SInt with `max(L,R)+1` bits, including UInt subtraction.
- Same-kind multiplication returns that kind with `L+R` bits.
- Numeric negation returns SInt with `L+1` bits.
- Mixed UInt/SInt and numeric Bits operations reject without explicit conversion.
- Numeric comparisons return Bool; operands have the same numeric kind. Bits
  equality/inequality requires identical widths.
- Bitwise operations require the same kind and exact width; complement preserves
  the type. No implicit padding policy is inferred from a target language.
- Concatenation returns Bits(L+R), with the left operand in the high bits.
- Extraction uses half-open `[lowerInclusive, upperExclusive)`, returns Bits of
  that extent, and proves nonempty in-range bounds for every legal source width.
- Static nonnegative shift amounts retain actual UInt literal operand identities.
  Left shift grows width by the amount and preserves the original value/pattern;
  right shift retains width. SInt `>>` is arithmetic; UInt/Bits right shift is
  logical. Explicit logical SInt shift preserves the encoded type and zero fills.
  An amount at least the source width yields only the specified fill bits.
- Conditional selection requires Bool and exactly equal branch types. Finite
  digital scalars, enums and storable digital aggregates are supported; Clock/
  Reset and root analog Real/Integer values reject at this digital boundary.
  Aggregate shape/enum identity cannot be erased into a flat guessed carrier.

A static maximum is a real owned integer DAG operation. Checked interval analysis,
instance substitution and structural identity are independently verified; defaults
never specialize overridable widths. The canonical algebra folds `max(W,W)` to W
only when both operand identities are exactly equal; coincident bounds/defaults
do not establish that identity. Ordered nontrivial operation DAGs otherwise remain
distinct. Constant proofs have explicit depth/node/
magnitude bounds, independent of the declared bit-vector width.

## Ownership, effects and implementation boundary

Source capture retains real typed operands. The bridge uses the existing single
SSA dependency schedule, and native DigitalValueModel independently verifies
arity, operand ownership, exact width DAGs, result types and literal index/shift
obligations. Ordinary Bool operations enter this graph only through actual digital
roots; matching an operation name cannot reclassify an analog effect graph.

Division/remainder and numeric minimum/maximum follow the additive
[arithmetic contract](NodalDigitalArithmetic-DG-v0.2.md); their implementation remains
open. Static shifts satisfy the frozen Int-amount surface; runtime amounts remain
explicitly unsupported, rather than an inferred new overload requirement. Collection
recurrence and loop lowering remain open until their contracts and implementation
are added. No target
HDL execution, timing, CDC or state update behavior is claimed here.

## Alternatives and checks

Rejected: parameter-default width inference; over-wide sum substitution for max;
implicit wrap or mixed signedness; generic string expressions; duplicated SSA
registries; manufacturing capability values through selection; changing source
ordering for shorter output. Reuse of the canonical static algebra and digital
DAG owners is smaller and supports later loop, layout and target consumers.

Required focused checks include carry/sign extrema, multiplication and negation
boundaries, exact symbolic max identities and overrides, invalid width/kind/arity,
constant versus forged dynamic shift/extract operands, aggregate selection, mixed
analog/digital ownership, separate-compilation API lookup and native forged input.
Tests and parent completion are recorded in the increment readiness note, not
assumed from this approved design.
