# Nodal digital arithmetic design gate v0.2

Status: Approved for the bounded Foundation 55 implementation below. Implementation
and focused validation remain pending in the increment readiness record.

## Authority and compatibility

The owner's instruction to implement all unchecked Foundation increments from 43
covers the additive arithmetic contract required by
[ADR 0016](../architecture/0016-signed-types-and-staged-loops.md).
This does not claim that the owner separately selected each signature. Preserve
existing operators. Add `/`, `%`, `min` and `max` to same-kind UInt/SInt expression
operators; no Bits arithmetic or implicit mixed signedness is introduced.

## Exact finite contract

- UInt quotient and remainder retain the numerator's exact width.
- SInt quotient has numerator width plus one. This represents the signed minimum
  divided by minus one without implicit wrap or overflow.
- SInt remainder retains numerator width. Division truncates toward zero; a
  nonzero remainder has the numerator's sign and obeys `a = q*b + r`.
- Division/remainder require a divisor proven an exact nonzero integer by the
  existing bounded, independently checked digital value facts. A literal and a
  supported computed constant use the same proof owner. An unproved runtime
  divisor or proven zero rejects explicitly; no runtime zero/default/X behavior
  is invented. Runtime numerators are supported.
- Minimum/maximum accept the same numeric kind and return that kind with exact
  width `max(leftWidth,rightWidth)`. Comparison and selected-value extension
  preserve the mathematical value. No operand reassociation or default width
  substitution is allowed.
- Width addition and constant evaluation retain existing checked static arithmetic
  and finite proof budgets. Failure to establish a bounded constant proof is a
  capability diagnostic, never a guessed value. Large declared widths do not
  authorize proportional allocation during verification.

Static arithmetic/logical shifts remain the supported F55 shift profile. Frozen
shift signatures take host Int amounts; no runtime shift overload is required by
that frozen surface. Runtime shift operands continue to reject. This clarifies
allocation of the earlier pending-policy note rather than removing a required
implemented operation.

## Owners, alternatives and checks

Reuse DigitalCombinationalContract, the existing scalar constant proof owner,
DigitalValueMlir's single schedule and native DigitalValueModel. Native validation
must derive divisor facts from actual SSA producers, not a supplied constant
attribute. Source/bridge/native must agree on kind, width and operation tree.

Rejected alternatives: implicit signed wrap on minimum/minus-one; treating unknown
or zero divisors as zero; simulator-specific unknown-value policy; a second
constant evaluator; default specialization; hidden iterative or multicycle division.
A future general runtime-divisor profile requires its own explicit zero semantics.

Focused checks include dynamic numerator with positive/negative constant divisor,
zero/unproved divisor rejection, all signed sign combinations and minimum/minus-one,
remainder identity, computed-constant and forged-proof cases, differing operand
widths, exact symbolic quotient/min/max widths, wrong kind/result width, and finite
proof/width boundaries. Actual target execution remains with F65/66/67 handoffs;
this contract alone is not an implementation or tool-success claim.
